package com.october.fixer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.graphics.drawable.Icon
import android.os.ParcelFileDescriptor
import android.service.quicksettings.TileService
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructTimeval
import android.util.Log
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class FixVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var tunOut: FileOutputStream? = null
    private var reader: Thread? = null
    private var sender: Thread? = null
    private var pool: ExecutorService? = null
    private var byedpi: Process? = null
    private var sockTun: FileDescriptor? = null
    private var sockHev: ParcelFileDescriptor? = null
    private var hevStarted = false
    private var relay: UdpRelay? = null
    private var tgDomain: String? = null // null: Telegram toggle off or no domain set
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var preferred = 0 // index of the upstream that answered last
    private val cache = DnsCache()
    private val worker = Executors.newSingleThreadExecutor() // start/stop take ~1 s and would freeze the UI

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // stopSelf(startId): a start that came while this stop was queued keeps the service alive
            worker.execute { stopVpn(startId = startId) }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RESTART) { // settings changed: same service, fresh byedpi/tun
            if (running) worker.execute {
                stopVpn(final = false)
                startVpn()
            }
            return START_STICKY
        }
        if (!running) {
            lastError = null
            // must happen promptly on the main thread; the slow part (byedpi, hev, sleeps) runs on the worker
            try {
                startForegroundNotification()
            } catch (e: Exception) {
                // without startForeground the system kills the service in a few seconds anyway
                lastError = "Не удалось показать уведомление: ${e.message}"
                broadcastState()
                stopSelf(startId)
                return START_NOT_STICKY
            }
            worker.execute {
                if (!running) {
                    // again: a stop queued before this start has removed the notification meanwhile
                    try { startForegroundNotification() } catch (_: Exception) {}
                    total = 0; viaDoh = 0; viaUdp = 0; failed = 0; viaTunnel = 0; viaRelay = 0
                    startVpn()
                }
            }
        }
        return START_STICKY
    }

    @Synchronized
    private fun startVpn() {
        try {
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            // Telegram is not routed through the tunnel: it talks to this local proxy, which goes out directly
            tgDomain = if (prefs.getBoolean(KEY_TG, false)) tgDomain(this) else null
            tgError = null
            tgDomain?.let { d ->
                // a side feature: if its port is taken, Roblox and YouTube still get the VPN
                try {
                    tg = TgBridge(d, TgBridge.parseSecret(tgSecret(this))).also { it.start() }
                } catch (e: Exception) {
                    Log.w(TAG, "tg bridge failed", e)
                    tgError = if (e is java.net.BindException) "порт ${TgBridge.PORT} занят" else e.javaClass.simpleName
                }
            }
            startByedpi()
            val fd = establish()
                ?: throw IllegalStateException(lastError ?: "VPN не создан (нет разрешения или включён другой VPN)")
            tun = fd
            val out = FileOutputStream(fd.fileDescriptor)
            tunOut = out
            relay = UdpRelay({ protect(it) }, { p ->
                try {
                    synchronized(out) { out.write(p) }
                } catch (_: Exception) {
                }
            }, ::onRobloxServer)
            startTunnel()
            pool = Executors.newFixedThreadPool(8)
            if (byedpi?.let { isAlive(it) } != true) throw IllegalStateException("byedpi завершился при старте")
            watchNetwork()
            running = true
            broadcastState()
            reader = Thread({ readLoop(fd) }, "fix-reader").also { it.start() }
            sender = Thread({ tunnelToTun() }, "fix-tunnel-rx").also { it.start() }
        } catch (e: Throwable) {
            // our own IllegalStateExceptions carry a message for the user
            lastError = if (e is IllegalStateException && e.message != null) e.message else "${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "start failed", e)
            stopVpn()
        }
    }

    private fun establish(): ParcelFileDescriptor? {
        val b = Builder()
            .setSession("Fixer")
            .addAddress("10.111.0.1", 32)
            .addDnsServer("10.111.0.2")
            .addRoute("10.111.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .setMtu(MTU)
            .setBlocking(true)
        // IPv4 only: the phone hops between v4-only Wi-Fi and v6 LTE (v4 via clat), and v6 through
        // byedpi fails with "Network is unreachable" whenever the current network lacks it
        var allowed = 0
        for (pkg in APPS) {
            try {
                packageManager.getPackageInfo(pkg, 0)
                b.addAllowedApplication(pkg)
                allowed++
            } catch (_: PackageManager.NameNotFoundException) {
            }
        }
        // with no allowed app the VPN would take the whole phone, Fixer's own DoH and byedpi sockets included
        if (allowed == 0) throw IllegalStateException("Не найдено ни YouTube, ни Roblox, ни браузера — нечего направлять через VPN")
        return try {
            b.establish()
        } catch (e: Exception) {
            lastError = "VPN: ${e.javaClass.simpleName}: ${e.message}"
            null
        }
    }

    private fun startByedpi() {
        val exe = File(applicationInfo.nativeLibraryDir, "libbyedpi.so")
        if (!exe.exists()) throw IllegalStateException("libbyedpi.so не найден (ABI?)")
        // first group: Roblox hosts pass through untouched (desync made its CDN stall)
        val skip = File(filesDir, "nodesync.txt").apply { writeText(NO_DESYNC_HOSTS.joinToString("\n")) }
        val p = ProcessBuilder(
            listOf(exe.path, "--ip", "127.0.0.1", "--port", SOCKS_PORT.toString()) +
                listOf("-H", skip.path, "-An") + DPI_ARGS
        )
            .redirectErrorStream(true)
            .start()
        byedpi = p
        Thread({
            try {
                p.inputStream.bufferedReader().forEachLine { Log.d(TAG, "byedpi: $it") }
            } catch (_: Exception) {
            }
            onByedpiExit(p)
        }, "byedpi-log").start()
        Thread.sleep(300)
        if (!isAlive(p)) throw IllegalStateException("byedpi не запустился")
    }

    @Synchronized
    private fun onByedpiExit(p: Process) {
        if (running && byedpi === p) {
            lastError = "byedpi завершился"
            stopVpn()
        }
    }

    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            private var current: Network? = null
            override fun onAvailable(network: Network) {
                if (current != null && current != network) {
                    Log.d(TAG, "network changed: dropping UDP flows")
                    relay?.reset()
                }
                current = network
            }
        }
        cm.registerDefaultNetworkCallback(cb) // our uid is outside the VPN, so this is the real network
        netCallback = cb
    }

    private fun isAlive(p: Process): Boolean = try {
        p.exitValue(); false
    } catch (_: IllegalThreadStateException) {
        true
    }

    private fun startTunnel() {
        val a = FileDescriptor()
        val b = FileDescriptor()
        Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_DGRAM, 0, a, b)
        for (f in listOf(a, b)) {
            Os.setsockoptInt(f, OsConstants.SOL_SOCKET, OsConstants.SO_SNDBUF, SOCK_BUF)
            Os.setsockoptInt(f, OsConstants.SOL_SOCKET, OsConstants.SO_RCVBUF, SOCK_BUF)
        }
        // so blocked reads/writes of our threads notice a stop
        // short send timeout: the same reader thread also serves DNS and UDP, a stalled hev must not block them
        Os.setsockoptTimeval(a, OsConstants.SOL_SOCKET, OsConstants.SO_SNDTIMEO, StructTimeval.fromMillis(20))
        Os.setsockoptTimeval(a, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO, StructTimeval.fromMillis(1000))
        sockTun = a
        sockHev = ParcelFileDescriptor.dup(b)
        Os.close(b)
        val cfg = File(filesDir, "hev.yml")
        cfg.writeText(
            "tunnel:\n  mtu: $MTU\n" +
                "socks5:\n  port: $SOCKS_PORT\n  address: 127.0.0.1\n  udp: 'udp'\n" +
                "misc:\n  log-level: warn\n  log-file: ${File(filesDir, "hev.log").path}\n"
        )
        if (!TProxy.TProxyStartService(cfg.path, sockHev!!.fd)) throw IllegalStateException("hev не запустился")
        hevStarted = true
        Thread.sleep(200)
        if (!TProxy.TProxyIsRunning()) throw IllegalStateException("hev завершился при старте")
    }

    private fun readLoop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = tunOut ?: return
        val sock = sockTun ?: return
        val buf = ByteArray(32767)
        try {
            while (!Thread.currentThread().isInterrupted) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                val udp = PacketUtils.parseUdp(buf, n)
                val req = udp?.takeIf { it.dstPort == 53 }
                if (req != null) {
                    pool?.execute {
                        val reply = PacketUtils.buildReply(req, resolve(req.payload))
                        try {
                            synchronized(output) { output.write(reply) }
                        } catch (_: Exception) {
                        }
                    }
                    continue
                }
                if (PacketUtils.isQuic(buf, n)) continue // drop: apps fall back to TCP
                if (udp != null) { // game traffic etc.: direct, byedpi's UDP relay broke Roblox joins (error 279)
                    relay?.send(udp)
                    viaRelay++
                    continue
                }
                try {
                    Os.write(sock, buf, 0, n)
                    viaTunnel++
                } catch (_: ErrnoException) {
                    // queue full: drop like a congested link
                }
            }
        } catch (_: Exception) {
            // tun closed or pool shut down
        }
    }

    private fun tunnelToTun() {
        val output = tunOut ?: return
        val sock = sockTun ?: return
        val buf = ByteArray(32767)
        while (!Thread.currentThread().isInterrupted) {
            val n = try {
                Os.read(sock, buf, 0, buf.size)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EAGAIN) continue else break
            }
            if (n <= 0) continue
            try {
                synchronized(output) { output.write(buf, 0, n) }
            } catch (_: Exception) {
            }
        }
    }

    private fun resolve(query: ByteArray): ByteArray {
        if (query.size < 12) return PacketUtils.servfail(query)
        total++
        cache.get(query)?.let { return it }
        val start = preferred
        for (k in UPSTREAMS.indices) {
            val i = (start + k) % UPSTREAMS.size
            val u = UPSTREAMS[i]
            val r = doh(u, query)?.takeIf { usable(query, it) } ?: continue
            preferred = i
            cache.put(query, r)
            viaDoh++
            logAnswer(u, query, r)
            return r
        }
        for (h in UDP_SERVERS) {
            val r = udp(h, query)?.takeIf { usable(query, it) } ?: continue
            cache.put(query, r)
            viaUdp++
            logAnswer(h, query, r)
            return r
        }
        failed++
        Log.w(TAG, "FAIL ${qname(query)}")
        return PacketUtils.servfail(query)
    }

    /** Same ID, not truncated, NOERROR/NXDOMAIN; anything else (SERVFAIL, REFUSED, TC) tries the next server. */
    private fun usable(q: ByteArray, r: ByteArray): Boolean {
        if (r[0] != q[0] || r[1] != q[1] || (r[2].toInt() and 0x02) != 0) return false
        val rcode = r[3].toInt() and 0xF
        return rcode == 0 || rcode == 3
    }

    private fun logAnswer(via: String, q: ByteArray, r: ByteArray) {
        val qtype = if (q.size > 12) {
            var i = 12
            while (i < q.size && q[i].toInt() != 0) i += (q[i].toInt() and 0xFF) + 1
            if (i + 2 < q.size) ((q[i + 1].toInt() and 0xFF) shl 8) or (q[i + 2].toInt() and 0xFF) else -1
        } else -1
        Log.d(TAG, "${qname(q)} t=$qtype via=$via rcode=${r[3].toInt() and 0xF} an=${((r[6].toInt() and 0xFF) shl 8) or (r[7].toInt() and 0xFF)} len=${r.size}")
    }

    private fun qname(q: ByteArray): String {
        val sb = StringBuilder()
        var i = 12
        while (i < q.size && q[i].toInt() != 0) {
            val n = q[i].toInt() and 0xFF
            if (n and 0xC0 != 0 || i + 1 + n > q.size) break
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(q, i + 1, n, Charsets.ISO_8859_1))
            i += n + 1
        }
        return sb.toString()
    }

    private fun udp(host: String, query: ByteArray): ByteArray? {
        var s: DatagramSocket? = null
        return try {
            s = DatagramSocket()
            protect(s)
            s.soTimeout = 2000
            val addr = InetAddress.getByName(host)
            s.send(DatagramPacket(query, query.size, addr, 53))
            val buf = ByteArray(4096)
            val p = DatagramPacket(buf, buf.size)
            s.receive(p)
            if (p.length >= 12) buf.copyOf(p.length) else null
        } catch (_: Exception) {
            null
        } finally {
            s?.close()
        }
    }

    private fun doh(url: String, query: ByteArray): ByteArray? {
        var c: HttpURLConnection? = null
        return try {
            c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 2000
                readTimeout = 2500
                doOutput = true
                setRequestProperty("Content-Type", "application/dns-message")
                setRequestProperty("Accept", "application/dns-message")
            }
            c.outputStream.use { it.write(PacketUtils.withEcsZero(query)) }
            if (c.responseCode != 200) {
                c.disconnect()
                return null
            }
            // read fully and close the stream (no disconnect) so the TLS connection is reused
            val body = c.inputStream.use { it.readBytes() }
            if (body.size >= 12) body else null
        } catch (_: Exception) {
            c?.disconnect()
            null
        }
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        val b = if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
            )
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, FixVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        b.setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_shades)
            .setContentIntent(open)
            .setOngoing(true)
        if (Build.VERSION.SDK_INT >= 23) {
            b.addAction(
                Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_shades), getString(R.string.notif_stop), stop).build()
            )
        } else {
            @Suppress("DEPRECATION")
            b.addAction(R.drawable.ic_shades, getString(R.string.notif_stop), stop)
        }
        val n = b.build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    /** Roblox joined a game server: during an MM2 pick, say where it is and offer the next one if it is far. */
    private fun onRobloxServer(ip: ByteArray, port: Int, rttMs: Int) {
        Log.d(TAG, "roblox server ${InetAddress.getByAddress(ip).hostAddress}:$port rtt=${rttMs}ms")
        if (!Mm2.armed() || !Mm2.newJoin(ip)) return
        Thread({ notifyServer(ip, rttMs) }, "mm2-notify").start()
    }

    private fun notifyServer(ip: ByteArray, rttMs: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        val where = Mm2.city(ip) ?: "Сервер"
        val good = rttMs <= Mm2.GOOD_MS
        Mm2.last = "$where · сеть $rttMs мс"
        val next = try {
            Mm2.next()
        } catch (_: Exception) {
            null
        }
        val b = if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_MM2, "Ближайший сервер MM2", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null)
                    enableVibration(false)
                }
            )
            Notification.Builder(this, CHANNEL_MM2)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        b.setContentTitle("MM2: $where · сеть $rttMs мс")
            .setContentText(if (good) "Хороший сервер, можно играть" else "Далеко. Нажми «Другой сервер»")
            .setSmallIcon(R.drawable.ic_shades)
            .setAutoCancel(true)
        if (Build.VERSION.SDK_INT >= 26 && good) b.setTimeoutAfter(10_000)
        if (next != null) {
            val join = PendingIntent.getActivity(
                this, 2,
                Intent(Intent.ACTION_VIEW, Mm2.joinUri(next.id)).setPackage(ROBLOX).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            if (Build.VERSION.SDK_INT >= 23) {
                b.addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_shades), "Другой сервер", join).build())
            } else {
                @Suppress("DEPRECATION")
                b.addAction(R.drawable.ic_shades, "Другой сервер", join)
            }
        }
        try {
            nm.notify(NOTIF_MM2, b.build())
        } catch (_: SecurityException) { // notifications denied
        }
    }

    @Synchronized
    private fun stopVpn(final: Boolean = true, startId: Int? = null) {
        running = false
        netCallback?.let {
            try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        netCallback = null
        reader?.interrupt(); reader = null
        sender?.interrupt()
        pool?.shutdownNow(); pool = null
        try { tun?.close() } catch (_: Exception) {}
        tun = null; tunOut = null
        relay?.close(); relay = null
        tg?.close(); tg = null
        if (hevStarted) {
            try { TProxy.TProxyStopService() } catch (_: Throwable) {}
            hevStarted = false
        }
        byedpi?.let { p ->
            byedpi = null
            p.destroy()
            var i = 0
            while (isAlive(p) && i++ < 10) Thread.sleep(50)
            if (isAlive(p) && Build.VERSION.SDK_INT >= 26) p.destroyForcibly()
        }
        sender?.join(500); sender = null
        try { sockHev?.close() } catch (_: Exception) {}
        sockHev = null
        sockTun?.let { try { Os.close(it) } catch (_: Exception) {} }
        sockTun = null
        if (!final) return
        broadcastState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (startId != null) stopSelf(startId) else stopSelf()
    }

    private fun broadcastState() {
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName))
        try {
            TileService.requestListeningState(this, ComponentName(this, FixTileService::class.java))
        } catch (_: Exception) {
        }
    }

    override fun onRevoke() {
        worker.execute { stopVpn() }
    }

    override fun onDestroy() {
        if (running) stopVpn()
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.october.fixer.STOP"
        const val ACTION_STATE = "com.october.fixer.STATE"
        const val ACTION_RESTART = "com.october.fixer.RESTART"
        const val PREFS = "fixer"
        const val KEY_TG = "tg"
        const val KEY_TG_DOMAIN = "tg_domain"
        // the author's Cloudflare zone with kws1..kws5/kws203 records; a user's own domain overrides it
        const val DEFAULT_TG_DOMAIN = "teneighty.bet"

        fun tgDomain(c: android.content.Context): String =
            c.getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_TG_DOMAIN, null)?.takeIf { it.isNotEmpty() }
                ?: DEFAULT_TG_DOMAIN

        /** The local proxy's secret, made once and kept: Telegram stores it with the proxy entry. */
        fun tgSecret(c: android.content.Context): String {
            val prefs = c.getSharedPreferences(PREFS, MODE_PRIVATE)
            prefs.getString(KEY_TG_SECRET, null)?.let { return it }
            return TgBridge.newSecret().also { prefs.edit().putString(KEY_TG_SECRET, it).apply() }
        }
        private const val KEY_TG_SECRET = "tg_secret"
        private const val CHANNEL = "fixer"
        private const val TAG = "Fixer"
        private const val NOTIF_ID = 1
        private const val NOTIF_MM2 = 2
        private const val CHANNEL_MM2 = "mm2"
        const val ROBLOX = "com.roblox.client"
        private const val MTU = 1500
        private const val SOCK_BUF = 1 shl 20
        private const val SOCKS_PORT = 21080
        // picked by testing on the user's ISP (Wi-Fi "Bureya_5G"); the simpler disorder/tlsrec presets time out.
        // "-b+500" is --buf-size 500, not an offset; tested on the phone: same speed, and -b 16384 failed some requests
        private val DPI_ARGS = "-s1 -q1 -Y -Ar -s5 -o1+s -At -f-1 -r1+s -As -s1 -o1+s -s-1 -An -b+500".split(" ")
        private val NO_DESYNC_HOSTS = listOf("roblox.com", "rbxcdn.com", "rbx.com", "rbxinfra.com", "roblox.cn")
        private val APPS = listOf(
            "com.google.android.youtube", "com.roblox.client", "com.android.chrome", "com.mi.globalbrowser",
            "com.yandex.browser", "org.mozilla.firefox", "com.opera.browser", "com.sec.android.app.sbrowser",
            "app.revanced.android.youtube",
        )
        private val UPSTREAMS = listOf(
            // Google honours ECS 0/0; Cloudflare ignores ECS and answers for its own (Russian) PoP
            "https://8.8.8.8/dns-query",
            "https://8.8.4.4/dns-query",
            "https://1.1.1.1/dns-query",
            "https://1.0.0.1/dns-query",
        )
        private val UDP_SERVERS = listOf("9.9.9.9", "149.112.112.112", "8.8.8.8")

        @Volatile
        var running = false

        @Volatile
        var lastError: String? = null

        /** Why the Telegram proxy did not start with the VPN, or null. */
        @Volatile
        var tgError: String? = null

        /** The running Telegram proxy, closed with the VPN. */
        @Volatile
        var tg: TgBridge? = null
            private set

        @Volatile
        var total = 0

        @Volatile
        var viaDoh = 0

        @Volatile
        var viaUdp = 0

        @Volatile
        var failed = 0

        @Volatile
        var viaTunnel = 0

        @Volatile
        var viaRelay = 0
    }
}
