package com.october.fixer

import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Picks Murder Mystery 2 servers and judges the one Roblox joined.
 *
 * A server's address is only revealed by gamejoin with the account cookie, which Fixer never touches.
 * So the picker works the other way round: join a candidate by its jobId, let the VPN see which
 * game server Roblox connects to and how fast it answers, and offer the next candidate if it is far.
 */
object Mm2 {
    const val PLACE_ID = 142823291L

    /**
     * Network round trip at or under this counts as a good server. Measured on the phone: Frankfurt
     * 84-86 ms by Fixer, 102 ms by Roblox's NetworkPing (it adds frame time); US servers are far beyond.
     */
    const val GOOD_MS = 120

    class Server(val id: String, val playing: Int, val maxPlayers: Int, val ping: Int?)

    private var cached: List<Server> = emptyList()
    private var cachedAt = 0L
    private val tried = HashSet<String>()

    /** Rapid joins make Roblox ask for a captcha, so the button waits this long between picks. */
    const val COOLDOWN_MS = 20_000L

    @Volatile var lastPick = 0L

    /** Set by the "best server" button; the VPN only reports joins while a pick is in progress. */
    @Volatile var armedUntil = 0L

    fun armed() = System.currentTimeMillis() < armedUntil

    fun arm() {
        armedUntil = System.currentTimeMillis() + ARMED_MS
    }

    /** "Франкфурт · 48 мс" for the main screen, or null before the first join. */
    @Volatile var last: String? = null

    private var lastIp: String? = null
    private var lastAt = 0L

    /** Roblox may open a few flows to one server; only the first in a while counts as a join. */
    @Synchronized
    fun newJoin(ip: ByteArray, now: Long = System.currentTimeMillis()): Boolean {
        val key = ip.joinToString(".") { (it.toInt() and 0xFF).toString() }
        if (key == lastIp && now - lastAt < SAME_JOIN_MS) return false
        lastIp = key
        lastAt = now
        return true
    }

    /**
     * The next server to try, or null if Roblox gave none. Blocking: call off the main thread.
     * The fetch runs outside the lock: [newJoin] shares it and is called from the UDP relay's reader.
     */
    fun next(): Server? {
        val stale = synchronized(this) { System.currentTimeMillis() - cachedAt > CACHE_MS || cached.none { it.id !in tried } }
        val fresh = if (stale) fetch() else null
        synchronized(this) {
            if (fresh != null) {
                cached = fresh
                cachedAt = System.currentTimeMillis()
                if (cached.all { it.id in tried }) tried.clear()
            }
            return pick(cached, tried)?.also { tried.add(it.id) }
        }
    }

    /**
     * Servers with a free slot and some players. `ping` is the players' own average, not ours, so it
     * only breaks ties: prefer the lower half, then pick at random so retries don't land on one server.
     */
    fun pick(list: List<Server>, skip: Set<String>, random: java.util.Random = java.util.Random()): Server? {
        val open = list.filter { it.id !in skip && it.playing in MIN_PLAYERS until it.maxPlayers }
        if (open.isEmpty()) return null
        val sorted = open.sortedBy { it.ping ?: Int.MAX_VALUE }
        val top = sorted.take(maxOf(1, (sorted.size + 1) / 2))
        return top[random.nextInt(top.size)]
    }

    private fun fetch(): List<Server> {
        val c = URL("$SERVERS_URL?limit=100&excludeFullGames=true").openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty("Accept", "application/json")
            if (c.responseCode != 200) return emptyList()
            parse(c.inputStream.bufferedReader().readText())
        } catch (_: Exception) {
            emptyList()
        } finally {
            c.disconnect()
        }
    }

    fun parse(json: String): List<Server> {
        val data = JSONObject(json).optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val o = data.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Server(id, o.optInt("playing"), o.optInt("maxPlayers"), if (o.isNull("ping")) null else o.optInt("ping"))
        }
    }

    fun joinUri(jobId: String): Uri = Uri.parse("roblox://experiences/start?placeId=$PLACE_ID&gameInstanceId=$jobId")

    fun webJoinUri(jobId: String): Uri =
        Uri.parse("https://www.roblox.com/games/start?placeId=$PLACE_ID&gameInstanceId=$jobId")

    /** Roblox game servers (AS22697) live in 128.116.0.0/17. */
    fun isRobloxServer(ip: ByteArray) =
        ip.size == 4 && ip[0] == 128.toByte() && ip[1] == 116.toByte() && (ip[2].toInt() and 0x80) == 0

    /** City of a Roblox /24, only where ipinfo.io and community lists agree; geo-IP for Roblox is often wrong. */
    fun city(ip: ByteArray): String? = if (isRobloxServer(ip)) CITIES[ip[2].toInt() and 0xFF] else null

    private val CITIES: Map<Int, String> = buildMap {
        for (n in listOf(5, 44)) put(n, "Франкфурт")
        put(21, "Амстердам")
        put(13, "Париж")
        put(33, "Лондон")
        for (n in listOf(11, 53, 56, 74, 80, 87, 102)) put(n, "Эшберн, США")
        put(32, "Нью-Йорк")
        put(48, "Чикаго")
        for (n in listOf(45, 127)) put(n, "Майами")
        put(95, "Даллас")
        for (n in listOf(1, 63)) put(n, "Лос-Анджелес")
        put(115, "Сиэтл")
        for (n in listOf(50, 54, 97)) put(n, "Сингапур")
        for (n in listOf(55, 120)) put(n, "Токио")
        put(51, "Сидней")
    }

    private const val SERVERS_URL = "https://games.roblox.com/v1/games/$PLACE_ID/servers/Public"
    private const val CACHE_MS = 60_000L
    private const val ARMED_MS = 30 * 60_000L
    private const val SAME_JOIN_MS = 60_000L
    private const val MIN_PLAYERS = 3
}
