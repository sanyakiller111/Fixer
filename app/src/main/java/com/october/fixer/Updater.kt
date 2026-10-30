package com.october.fixer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import org.json.JSONObject
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest

/**
 * Updates outside a store: version.json and the APK sit next to the Telegram Worker as static files
 * (worker/publish-update.ps1). Android still asks the user to confirm the install, and accepts it only
 * when the APK is signed with the same key, so a swapped file cannot replace Fixer.
 */
object Updater {
    private const val HOST = "tg.teneighty.bet"
    private const val TAG = "Fixer"

    class Release(val code: Int, val name: String, val apk: String, val sha256: String, val notes: String)

    fun parse(json: String): Release? {
        val o = JSONObject(json)
        val code = o.optInt("versionCode", 0).takeIf { it > 0 } ?: return null
        val sha = o.optString("sha256").lowercase().takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: return null
        return Release(code, o.optString("versionName", "?"), o.optString("apk", "Fixer.apk"), sha, o.optString("notes"))
    }

    /** A newer release than [current], or null. Blocking: call off the main thread. */
    fun check(current: Int): Release? = try {
        val r = parse(String(get("version.json", 64 shl 10), Charsets.UTF_8))
        Log.i(TAG, "update check: have $current, server ${r?.code}")
        r?.takeIf { it.code > current }
    } catch (e: Exception) {
        Log.d(TAG, "update check: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    /** Downloads and checks the APK; [progress] gets 0..100. Blocking. */
    fun download(c: Context, r: Release, progress: (Int) -> Unit): File {
        val body = get(r.apk.substringAfterLast('/'), 64 shl 20, progress)
        val sha = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
        if (sha != r.sha256) throw IllegalStateException("файл повреждён при загрузке")
        val f = File(c.cacheDir, "update.apk")
        f.writeBytes(body)
        return f
    }

    /** Hands the APK to the system installer; it shows its own "Update?" sheet via [InstallResult]. */
    fun install(c: Context, apk: File) {
        val pi = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(c.packageName)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            apk.inputStream().use { input ->
                s.openWrite("fixer.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    s.fsync(out)
                }
            }
            // mutable: the installer fills in the status extras
            val done = PendingIntent.getBroadcast(
                c, 3, Intent(c, InstallResult::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            s.commit(done.intentSender)
        }
    }

    /**
     * The same trick as TgBridge's ws://: on the user's ISP TLS to Cloudflare and plain HTTP to
     * "tg.<domain>" both stall after ~16-30 KB, while a connection whose first request goes to
     * speed.cloudflare.com runs free and Cloudflare routes the next request by its own Host, written with
     * a trailing dot the DPI does not match. Checked on the phone: the whole APK, VPN off.
     * Plain HTTP is fine here: the hash and Android's signature check guard the file.
     */
    private fun get(name: String, limit: Int, progress: ((Int) -> Unit)? = null): ByteArray {
        val ip = InetAddress.getAllByName(HOST).firstOrNull { it is Inet4Address } ?: throw java.net.UnknownHostException(HOST)
        Socket().use { s ->
            s.connect(InetSocketAddress(ip, 80), 8000)
            s.soTimeout = 15000
            val input = s.getInputStream().buffered(32768)
            val out = s.getOutputStream()
            out.write("GET /__down?bytes=1 HTTP/1.1\r\nHost: speed.cloudflare.com\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
            val warm = readHead(input)
            if (status(warm) != 200) throw IllegalStateException("разогрев: ${warm.substringBefore("\r\n")}")
            readBody(input, length(warm) ?: 0L, Long.MAX_VALUE, null)
            out.write("GET /update/$name HTTP/1.1\r\nHost: $HOST.\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
            val head = readHead(input)
            val code = status(head)
            if (code != 200) throw IllegalStateException("сервер ответил $code")
            val total = length(head)
            if (total != null && total > limit) throw IllegalStateException("файл слишком большой")
            return readBody(input, total, limit.toLong(), progress)
        }
    }

    /** [total] bytes, or up to EOF when the length is unknown. */
    private fun readBody(input: InputStream, total: Long?, limit: Long, progress: ((Int) -> Unit)?): ByteArray {
        val out = java.io.ByteArrayOutputStream(if (total != null && total in 1..limit) total.toInt() else 8192)
        val buf = ByteArray(32768)
        while (total == null || out.size() < total) {
            val want = if (total == null) buf.size else minOf(buf.size.toLong(), total - out.size()).toInt()
            val n = input.read(buf, 0, want)
            if (n < 0) {
                if (total == null) break else throw EOFException("оборвалось на ${out.size()} из $total байт")
            }
            out.write(buf, 0, n)
            if (out.size() > limit) throw IllegalStateException("файл слишком большой")
            if (progress != null && total != null && total > 0) progress((out.size() * 100L / total).toInt())
        }
        return out.toByteArray()
    }

    private fun readHead(input: InputStream): String {
        val sb = StringBuilder()
        while (!sb.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) throw EOFException("нет ответа сервера")
            sb.append(b.toChar())
            if (sb.length > 16384) throw IllegalStateException("слишком длинный ответ")
        }
        return sb.toString()
    }

    private fun status(head: String): Int = head.substringBefore("\r\n").split(' ').getOrNull(1)?.toIntOrNull() ?: 0

    private fun length(head: String): Long? =
        Regex("(?im)^content-length:\\s*(\\d+)").find(head)?.groupValues?.get(1)?.toLong()

    /** The installer's answer: its confirmation sheet first, then success or an error. */
    class InstallResult : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (val status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                    c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                PackageInstaller.STATUS_SUCCESS -> Unit // the app is restarted by the update
                else -> {
                    val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    Log.w(TAG, "update install: status=$status $msg")
                    if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                        android.widget.Toast.makeText(c, "Обновление не установилось", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}
