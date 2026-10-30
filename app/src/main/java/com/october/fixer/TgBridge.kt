package com.october.fixer

import android.util.Base64
import android.util.Log
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Telegram's IPs are blocked outright on the user's ISP (TCP never connects, kws*.web.telegram.org
 * included), so Telegram reaches it through a local MTProto proxy (the user adds it once via
 * [link]) and the proxy goes out as MTProto-over-WebSocket through Cloudflare:
 * wss://kws<dc>.<domain>/apiws, where the user's Cloudflare zone proxies kws<dc> to that DC's IP.
 * Same scheme as Flowseal/tg-ws-proxy's CfProxy: behind a proxy the client names the DC (negative for
 * media) in its own header, so nothing is guessed from addresses; the stream is re-encrypted under a
 * fresh relay header and cut into one transport packet per WebSocket frame.
 */
class TgBridge(private val domain: String, private val secret: ByteArray, private val socks: Int = 0) {

    private var server: ServerSocket? = null
    private val pool: ExecutorService = Executors.newCachedThreadPool()
    @Volatile private var closed = false

    /** Client and WebSocket sockets in use: interrupting the pool does not wake a blocked socket read. */
    private val open = ConcurrentHashMap.newKeySet<Closeable>()

    fun start() {
        val s = ServerSocket()
        try {
            s.reuseAddress = true
            s.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 64)
        } catch (e: Exception) {
            try { s.close() } catch (_: Exception) {}
            throw e
        }
        server = s
        Thread({
            while (!closed) {
                val c = try {
                    s.accept()
                } catch (_: Exception) {
                    break
                }
                try {
                    pool.execute { handle(c) }
                } catch (_: RejectedExecutionException) { // closed meanwhile
                    try { c.close() } catch (_: Exception) {}
                    break
                }
            }
        }, "tg-accept").start()
    }

    fun close() {
        closed = true
        try { server?.close() } catch (_: Exception) {}
        pool.shutdownNow()
        for (c in open) try { c.close() } catch (_: Exception) {}
        open.clear()
    }

    private fun handle(c: Socket) {
        var ws: Ws? = null
        var dc = 0
        var up = 0L
        open.add(c)
        try {
            if (closed) return
            c.tcpNoDelay = true
            c.soTimeout = 15000
            val input = c.getInputStream()
            val init = ByteArray(64)
            readFully(input, init)
            c.soTimeout = 0

            // client side: key = sha256(prekey + secret), IV from the header; the reverse for our replies
            val cltDec = ctr(sha256(init.copyOfRange(8, 40), secret), init.copyOfRange(40, 56))
            val rev = init.copyOfRange(8, 56).reversedArray()
            val cltEnc = ctr(sha256(rev.copyOfRange(0, 32), secret), rev.copyOfRange(32, 48))
            val d = cltDec.update(init)
            val tag = d[56].toInt() and 0xFF
            if (tag !in intArrayOf(0xEF, 0xEE, 0xDD) || d[57] != d[56] || d[58] != d[56] || d[59] != d[56]) {
                Log.w(TAG, "tg: bad handshake (wrong secret?)")
                return
            }
            dc = (d[60].toInt() and 0xFF) or (d[61].toInt() shl 8) // signed, negative for media
            val id = Math.abs(dc)
            if (id !in DCS) {
                Log.w(TAG, "tg: dc=$dc not supported")
                return
            }

            // Telegram side: plain obfuscated2 under our own random header carrying the same DC
            val relay = relayInit(d, dc)
            val tgEnc = ctr(relay.copyOfRange(8, 40), relay.copyOfRange(40, 56)).apply { update(relay) }
            val rrev = relay.copyOfRange(8, 56).reversedArray()
            val tgDec = ctr(rrev.copyOfRange(0, 32), rrev.copyOfRange(32, 48))

            // preferred: the Worker at tg.<domain> (worker/cloudflare-worker.js), which reaches the DC over plain
            // TCP; the kws<dc> records' /apiws on the DC IP left large requests unanswered
            var host = "tg.$domain"
            val w = try {
                Ws.connect(host, "/apiws?dst=${DC_IPS.getValue(id)}", socks)
            } catch (e: java.net.UnknownHostException) {
                host = "kws$id.$domain"
                Ws.connect(host, "/apiws")
            }
            ws = w
            open.add(w)
            if (closed) return
            val viaWorker = host.startsWith("tg.")
            Log.d(TAG, "tg: dc=$dc via $host")
            w.send(relay, 0, relay.size)

            val out = c.getOutputStream()
            val down = java.util.concurrent.atomic.AtomicLong()
            pool.execute { // RejectedExecutionException when closed meanwhile: caught below, sockets closed
                try {
                    while (true) {
                        val p = w.read() ?: break
                        val o = cltEnc.update(tgDec.update(p))
                        if (o.size != p.size) Log.w(TAG, "tg: dc=$dc CTR held back ${p.size - o.size}B")
                        out.write(o)
                        down.addAndGet(p.size.toLong())
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "tg: dc=$dc down ${e.javaClass.simpleName}: ${e.message}")
                } finally {
                    Log.d(TAG, "tg: dc=$dc ws ended down=${down.get()} ${w.closeReason}")
                    try { c.close() } catch (_: Exception) {}
                    w.close()
                    open.remove(c); open.remove(w)
                }
            }
            val split = Splitter(tag)
            var sent = 0
            Log.d(TAG, "tg: dc=$dc tag=%02x".format(tag))
            val buf = ByteArray(65536)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                val plain = cltDec.update(buf, 0, n)
                if (viaWorker) { // plain TCP behind the Worker: pass the stream through as read, like tg-ws-proxy
                    val e = tgEnc.update(plain)
                    w.send(e, 0, e.size)
                } else split.feed(plain) { p, off, len ->
                    val qa = split.hasQuickAck(p, off)
                    if (sent++ < 8 || len > 8192 || qa) Log.d(TAG, "tg: dc=$dc -> packet $len${if (qa) " qa" else ""}")
                    val e = tgEnc.update(p, off, len)
                    w.send(e, 0, e.size)
                }
                up += n
            }
            Log.d(TAG, "tg: dc=$dc closed up=$up down=${down.get()}")
        } catch (e: Exception) {
            Log.d(TAG, "tg: dc=$dc up=$up ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try { c.close() } catch (_: Exception) {}
            ws?.close()
            open.remove(c); ws?.let { open.remove(it) }
        }
    }

    /**
     * Cuts the client's plaintext into whole transport packets, one per WebSocket frame: the kws servers
     * read only the first packet of a frame, and Telegram writes msgs_ack and req_DH_params in one go.
     * Packets are re-encrypted by the caller in order, so the Telegram-side stream stays continuous.
     */
    private class Splitter(private val tag: Int) {
        private var dec = ByteArray(65536)
        private var size = 0

        fun feed(d: ByteArray, emit: (ByteArray, Int, Int) -> Unit) {
            val n = d.size
            if (size + n > dec.size) dec = dec.copyOf(maxOf(dec.size * 2, size + n))
            System.arraycopy(d, 0, dec, size, n)
            size += n
            var pos = 0
            while (true) {
                val len = packetLen(pos) ?: break
                if (len <= 0 || len > 16 shl 20) { // not a stream we understand: pass the rest as is
                    emit(dec, pos, size - pos); pos = size; break
                }
                if (size - pos < len) break
                emit(dec, pos, len)
                pos += len
            }
            if (pos > 0) {
                System.arraycopy(dec, pos, dec, 0, size - pos)
                size -= pos
            }
        }

        /** Whether the packet asks the server for a quick ack (top bit of its length). */
        fun hasQuickAck(p: ByteArray, off: Int): Boolean =
            p[if (tag == 0xEF) off else off + 3].toInt() and 0x80 != 0

        /** Whole length (header included) of the packet at [p], or null when its header is incomplete. */
        private fun packetLen(p: Int): Int? {
            fun u(i: Int) = dec[p + i].toInt() and 0xFF
            if (tag == 0xEF) { // abridged: length/4 in one byte, or 0x7f and three more; top bit is quick ack
                if (size - p < 1) return null
                val b = u(0) and 0x7F
                if (b < 0x7F) return 1 + b * 4
                if (size - p < 4) return null
                return 4 + (u(1) or (u(2) shl 8) or (u(3) shl 16)) * 4
            }
            if (size - p < 4) return null // intermediate / padded: 4-byte length, top bit is quick ack
            return 4 + ((u(0) or (u(1) shl 8) or (u(2) shl 16) or (u(3) shl 24)) and 0x7FFFFFFF)
        }
    }

    /** Minimal RFC 6455 client: binary frames only, masked as a client must. */
    private class Ws(private val s: Socket) : Closeable {
        private val input: InputStream = s.inputStream.buffered(32768)
        private val output: OutputStream = s.outputStream
        private val rnd = SecureRandom()
        @Volatile var closeReason = "eof"

        fun send(b: ByteArray, off: Int, len: Int, opcode: Int = 0x2) {
            val ext = if (len < 126) 0 else if (len < 65536) 2 else 8
            val f = ByteArray(2 + ext + 4 + len)
            f[0] = (0x80 or opcode).toByte()
            var i = 2
            when (ext) {
                0 -> f[1] = (0x80 or len).toByte()
                2 -> { f[1] = (0x80 or 126).toByte(); f[2] = (len shr 8).toByte(); f[3] = len.toByte(); i = 4 }
                else -> { f[1] = (0x80 or 127).toByte(); for (k in 0 until 8) f[2 + k] = (len.toLong() shr (56 - 8 * k)).toByte(); i = 10 }
            }
            val mask = ByteArray(4).also { rnd.nextBytes(it) }
            System.arraycopy(mask, 0, f, i, 4)
            i += 4
            for (k in 0 until len) f[i + k] = (b[off + k].toInt() xor mask[k and 3].toInt()).toByte()
            synchronized(output) {
                output.write(f)
                output.flush()
            }
        }

        /** Next data payload, or null when the server closed. Pings are answered here. */
        fun read(): ByteArray? {
            while (true) {
                val b0 = input.read()
                val b1 = input.read()
                if (b0 < 0 || b1 < 0) return null
                var len = (b1 and 0x7F).toLong()
                if (len == 126L) len = ((u8() shl 8) or u8()).toLong()
                else if (len == 127L) { len = 0; repeat(8) { len = (len shl 8) or u8().toLong() } }
                val mask = if (b1 and 0x80 != 0) ByteArray(4).also { readFully(input, it) } else null
                if (len < 0 || len > MAX_FRAME) throw IllegalStateException("frame of $len bytes")
                val p = ByteArray(len.toInt())
                readFully(input, p)
                if (mask != null) for (k in p.indices) p[k] = (p[k].toInt() xor mask[k and 3].toInt()).toByte()
                when (b0 and 0x0F) {
                    0x0, 0x1, 0x2 -> if (p.isNotEmpty()) return p
                    0x8 -> {
                        closeReason = if (p.size >= 2) "close ${((p[0].toInt() and 0xFF) shl 8) or (p[1].toInt() and 0xFF)} ${String(p, 2, p.size - 2)}" else "close"
                        return null
                    }
                    0x9 -> send(p, 0, p.size, 0xA)
                }
            }
        }

        private fun u8(): Int = input.read().also { if (it < 0) throw EOFException() }

        override fun close() {
            try { s.close() } catch (_: Exception) {}
        }

        companion object {
            /** Telegram packets are far smaller; a bigger length is a broken stream, not a reason to allocate. */
            private const val MAX_FRAME = 4L shl 20
            private val WARMUP = "GET /__down?bytes=1 HTTP/1.1\r\nHost: speed.cloudflare.com\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
            private val dns =java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()

            /**
             * IPv4 addresses of [host], via DoH first: the system resolver keeps a negative answer for
             * a kws record the user adds later for up to half an hour, and its v6 answers time out
             * on v4-only Wi-Fi.
             */
            private fun resolve4(host: String): List<InetAddress> {
                val now = System.currentTimeMillis()
                dns[host]?.let { (t, a) -> if (now - t < 300_000) return a }
                val a = try {
                    val c = java.net.URL("https://1.1.1.1/dns-query?name=$host&type=A").openConnection() as java.net.HttpURLConnection
                    c.connectTimeout = 4000
                    c.readTimeout = 4000
                    c.setRequestProperty("accept", "application/dns-json")
                    val body = c.inputStream.bufferedReader().use { it.readText() }
                    Regex("\"type\":1,[^}]*\"data\":\"([0-9.]+)\"").findAll(body).map { InetAddress.getByName(it.groupValues[1]) }.toList()
                } catch (_: Exception) {
                    emptyList()
                }.ifEmpty { InetAddress.getAllByName(host).filterIsInstance<java.net.Inet4Address>() }
                if (a.isEmpty()) throw java.net.UnknownHostException(host)
                dns[host] = now to a
                return a
            }

            /**
             * With [socks] the TCP connection goes through byedpi: plain connections from the phone to
             * Cloudflare froze after ~16-20 KB on the user's ISP, the same stream via byedpi does not.
             */
            /**
             * Plain ws:// on port 80 first, with the Host written as an absolute name ("tg.<domain>."):
             * the ISP's DPI stalls TLS to an x.y.z SNI after ~16 KB and drops plain HTTP whose Host is
             * x.y.z, but its Host match misses the trailing dot, which Cloudflare still routes to the
             * Worker. wss:// on 443 stays as the fallback (as tg-ws-proxy-android, which also runs plain).
             */
            fun connect(host: String, path: String, socks: Int = 0): Ws =
                try {
                    connect(host, path, socks, plain = true)
                } catch (e: Exception) {
                    Log.d(TAG, "tg: ws:// $host failed (${e.javaClass.simpleName}: ${e.message}), trying wss://")
                    connect(host, path, socks, plain = false)
                }

            private fun connect(host: String, path: String, socks: Int, plain: Boolean): Ws {
                val port = if (plain) 80 else 443
                val raw = if (socks > 0) Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socks))) else Socket()
                val s = try {
                    raw.connect(if (socks > 0) InetSocketAddress.createUnresolved(host, port) else InetSocketAddress(resolve4(host).first(), port), 8000)
                    raw.tcpNoDelay = true
                    raw.keepAlive = true // a dead NAT mapping would otherwise leave the read blocked forever
                    if (plain) raw else (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, 443, true) as SSLSocket
                } catch (e: Exception) {
                    try { raw.close() } catch (_: Exception) {}
                    throw e
                }
                try {
                    s.soTimeout = 10000
                    if (s is SSLSocket) {
                        // createSocket() without a host sets no SNI; Cloudflare needs it
                        val p = s.sslParameters
                        p.serverNames = listOf(javax.net.ssl.SNIHostName(host))
                        p.endpointIdentificationAlgorithm = "HTTPS" // createSocket() checks no host name
                        s.sslParameters = p
                        s.startHandshake()
                    }
                    val ws = Ws(s)
                    if (plain) {
                        // the DPI classifies a connection by its first request and lets speed.cloudflare.com
                        // run unthrottled; Cloudflare routes each keep-alive request by its own Host
                        s.outputStream.write(WARMUP)
                        s.outputStream.flush()
                        val head = ws.readHead()
                        if (!head.startsWith("HTTP/1.1 200")) throw IllegalStateException("warm-up: ${head.substringBefore("\r\n")}")
                        val len = Regex("(?im)^content-length:\\s*(\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
                        ws.skip(len)
                    }
                    val key = Base64.encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) }, Base64.NO_WRAP)
                    // the same headers tg-ws-proxy sends: no Origin, no User-Agent
                    val req = "GET $path HTTP/1.1\r\nHost: ${if (plain) "$host." else host}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                        "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Protocol: binary\r\n\r\n"
                    s.outputStream.write(req.toByteArray(Charsets.ISO_8859_1))
                    s.outputStream.flush()
                    val status = ws.readHead().substringBefore("\r\n")
                    if (!status.contains(" 101")) throw IllegalStateException("$host: $status")
                    s.soTimeout = 0
                    return ws
                } catch (e: Exception) {
                    try { s.close() } catch (_: Exception) {}
                    throw e
                }
            }
        }

        /** Reads the HTTP response head, status line and headers. */
        private fun readHead(): String {
            val sb = StringBuilder()
            while (!sb.endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b < 0) throw EOFException("no HTTP response")
                sb.append(b.toChar())
                if (sb.length > 8192) throw IllegalStateException("HTTP head too long")
            }
            return sb.toString()
        }

        private fun skip(n: Int) {
            if (n > 0) readFully(input, ByteArray(n))
        }
    }

    companion object {
        private const val TAG = "Fixer"
        const val PORT = 21441

        /** DCs with a kws<dc> record in the zone. */
        private val DCS = setOf(1, 2, 3, 4, 5, 203)

        /** Where the Worker connects for each DC: tg-ws-proxy's DC_DEFAULT_IPS, media included. */
        private val DC_IPS = mapOf(
            1 to "149.154.175.50", 2 to "149.154.167.51", 3 to "149.154.175.100",
            4 to "149.154.167.91", 5 to "149.154.171.5", 203 to "91.105.192.100",
        )

        /** tg:// link that adds this proxy to Telegram; "dd" asks for padded intermediate, as tg-ws-proxy does. */
        fun link(secret: ByteArray) =
            "tg://proxy?server=127.0.0.1&port=$PORT&secret=dd" + secret.joinToString("") { "%02x".format(it) }

        private fun ctr(key: ByteArray, iv: ByteArray): Cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }

        private fun sha256(a: ByteArray, b: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").run { update(a); update(b); digest() }

        /**
         * A fresh obfuscated2 header for the Telegram side: random, avoiding the starts reserved for
         * other transports, with the client's protocol tag and [dc] written through its own keystream.
         */
        private fun relayInit(clientPlain: ByteArray, dc: Int): ByteArray {
            val rnd = SecureRandom()
            val r = ByteArray(64)
            while (true) {
                rnd.nextBytes(r)
                val start = String(r, 0, 4, Charsets.ISO_8859_1)
                if ((r[0].toInt() and 0xFF) == 0xEF) continue
                if (start in setOf("HEAD", "POST", "GET ", "OPTI", "îîîî", "ÝÝÝÝ", "\u0016\u0003\u0001\u0002")) continue
                if (r[4].toInt() == 0 && r[5].toInt() == 0 && r[6].toInt() == 0 && r[7].toInt() == 0) continue
                break
            }
            val ks = ctr(r.copyOfRange(8, 40), r.copyOfRange(40, 56)).update(ByteArray(64))
            val tail = byteArrayOf(clientPlain[56], clientPlain[57], clientPlain[58], clientPlain[59],
                (dc and 0xFF).toByte(), (dc shr 8 and 0xFF).toByte(), r[62], r[63])
            for (i in 0 until 8) r[56 + i] = (tail[i].toInt() xor ks[56 + i].toInt()).toByte()
            return r
        }

        private fun readFully(input: InputStream, b: ByteArray) {
            var off = 0
            while (off < b.size) {
                val n = input.read(b, off, b.size - off)
                if (n < 0) throw EOFException()
                off += n
            }
        }

        /** Fresh 16-byte proxy secret, hex-encoded for storage. */
        fun newSecret(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

        fun parseSecret(hex: String): ByteArray = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
