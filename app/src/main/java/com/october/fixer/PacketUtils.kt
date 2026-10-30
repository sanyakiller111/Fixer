package com.october.fixer

/** A parsed UDP/53 packet (IPv4 or IPv6). Addresses are raw 4 or 16 bytes. */
class UdpDns(
    val version: Int,
    val src: ByteArray,
    val dst: ByteArray,
    val srcPort: Int,
    val dstPort: Int,
    val payload: ByteArray,
)

/** Tiny LRU cache for DNS answers. Key = query without 2-byte ID. */
class DnsCache(private val maxEntries: Int = 500, private val ttlMs: Long = 60_000) {
    private class Entry(val resp: ByteArray, val at: Long)

    private val map = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > maxEntries
    }

    private fun key(q: ByteArray) = String(q, 2, q.size - 2, Charsets.ISO_8859_1)

    /** Returns cached response with the ID of [query] patched in, or null. */
    @Synchronized
    fun get(query: ByteArray, now: Long = System.currentTimeMillis()): ByteArray? {
        if (query.size < 12) return null
        val k = key(query)
        val e = map[k] ?: return null
        if (now - e.at > ttlMs) { map.remove(k); return null }
        val r = e.resp.copyOf()
        r[0] = query[0]; r[1] = query[1]
        return r
    }

    @Synchronized
    fun put(query: ByteArray, resp: ByteArray, now: Long = System.currentTimeMillis()) {
        if (query.size < 12 || resp.size < 12) return
        map[key(query)] = Entry(resp, now)
    }
}

object PacketUtils {
    private fun u8(b: Byte) = b.toInt() and 0xFF
    private fun u16(p: ByteArray, o: Int) = (u8(p[o]) shl 8) or u8(p[o + 1])
    private fun put16(p: ByteArray, o: Int, v: Int) {
        p[o] = (v shr 8).toByte(); p[o + 1] = v.toByte()
    }

    /** Parses an IP packet; returns non-null only for unfragmented UDP with dst port 53. */
    fun parse(p: ByteArray, len: Int = p.size): UdpDns? = parseUdp(p, len)?.takeIf { it.dstPort == 53 }

    /** Parses any unfragmented UDP packet (IPv4 or IPv6). */
    fun parseUdp(p: ByteArray, len: Int = p.size): UdpDns? {
        if (len < 1) return null
        return when (u8(p[0]) shr 4) {
            4 -> {
                if (len < 28) return null
                val ihl = (u8(p[0]) and 0xF) * 4
                if (ihl < 20 || u8(p[9]) != 17) return null
                if ((u16(p, 6) and 0x3FFF) != 0) return null // fragment
                val end = minOf(u16(p, 2), len)
                udp(p, 4, p.copyOfRange(12, 16), p.copyOfRange(16, 20), ihl, end)
            }
            6 -> {
                if (len < 48 || u8(p[6]) != 17) return null
                val end = minOf(40 + u16(p, 4), len)
                udp(p, 6, p.copyOfRange(8, 24), p.copyOfRange(24, 40), 40, end)
            }
            else -> null
        }
    }

    /** True for a first-fragment UDP packet with destination port 443 (QUIC), IPv4 or IPv6. */
    fun isQuic(p: ByteArray, len: Int = p.size): Boolean {
        if (len < 1) return false
        return when (u8(p[0]) shr 4) {
            4 -> {
                if (len < 28) return false
                val ihl = (u8(p[0]) and 0xF) * 4
                ihl >= 20 && len >= ihl + 8 && u8(p[9]) == 17 && (u16(p, 6) and 0x1FFF) == 0 && u16(p, ihl + 2) == 443
            }
            6 -> len >= 48 && u8(p[6]) == 17 && u16(p, 42) == 443
            else -> false
        }
    }

    private fun udp(p: ByteArray, ver: Int, src: ByteArray, dst: ByteArray, off: Int, end: Int): UdpDns? {
        if (end < off + 8) return null
        val pe = minOf(end, off + u16(p, off + 4))
        if (pe < off + 8) return null
        return UdpDns(ver, src, dst, u16(p, off), u16(p, off + 2), p.copyOfRange(off + 8, pe))
    }

    /** Builds an IP+UDP reply to [req] carrying [dns], with addresses/ports swapped. */
    fun buildReply(req: UdpDns, dns: ByteArray): ByteArray {
        val v4 = req.version == 4
        val hl = if (v4) 20 else 40
        val ulen = 8 + dns.size
        val out = ByteArray(hl + ulen)
        if (v4) {
            out[0] = 0x45
            put16(out, 2, out.size)
            put16(out, 6, 0x4000)
            out[8] = 64; out[9] = 17
            req.dst.copyInto(out, 12); req.src.copyInto(out, 16)
            put16(out, 10, checksum(out, 0, 20))
        } else {
            out[0] = 0x60
            put16(out, 4, ulen)
            out[6] = 17; out[7] = 64
            req.dst.copyInto(out, 8); req.src.copyInto(out, 24)
        }
        put16(out, hl, req.dstPort)
        put16(out, hl + 2, req.srcPort)
        put16(out, hl + 4, ulen)
        dns.copyInto(out, hl + 8)
        // UDP checksum over pseudo-header + UDP segment
        val addrOff = if (v4) 12 else 8
        val addrLen = if (v4) 8 else 32
        var sum = sum(out, addrOff, addrLen, 0) + 17 + ulen
        sum = sum(out, hl, ulen, sum)
        var c = fold(sum)
        if (c == 0) c = 0xFFFF
        put16(out, hl + 6, c)
        return out
    }

    /** One's-complement checksum; returns 0 when run over a header that already has a valid checksum. */
    fun checksum(d: ByteArray, off: Int, len: Int): Int = fold(sum(d, off, len, 0))

    private fun sum(d: ByteArray, off: Int, len: Int, init: Long): Long {
        var s = init
        var i = off
        val end = off + len
        while (i + 1 < end) { s += u16(d, i); i += 2 }
        if (i < end) s += u8(d[i]) shl 8
        return s
    }

    private fun fold(sum: Long): Int {
        var s = sum
        while (s shr 16 != 0L) s = (s and 0xFFFF) + (s shr 16)
        return (s.toInt().inv()) and 0xFFFF
    }

    /**
     * Adds an EDNS Client Subnet option with prefix 0 (RFC 7871 "don't use my location"), so geo-DNS
     * answers as for an unknown client instead of the resolver's region. Returns [q] unchanged if
     * the additional section is anything other than nothing or a single trailing OPT record.
     */
    fun withEcsZero(q: ByteArray): ByteArray {
        if (q.size < 12 || u16(q, 4) != 1 || u16(q, 6) != 0 || u16(q, 8) != 0) return q
        var i = 12
        while (i < q.size && q[i].toInt() != 0) {
            if (u8(q[i]) and 0xC0 != 0) return q
            i += u8(q[i]) + 1
        }
        val qend = i + 5
        if (qend > q.size) return q
        val ecs = byteArrayOf(0, 8, 0, 4, 0, 1, 0, 0) // code 8, len 4, family IPv4, src/scope prefix 0
        val ar = u16(q, 10)
        if (ar == 0 && q.size == qend) {
            val opt = byteArrayOf(0, 0, 41, 0x10, 0, 0, 0, 0, 0, 0, ecs.size.toByte())
            val r = q + opt + ecs
            put16(r, 10, 1)
            return r
        }
        // Single OPT record at the end: root name, type 41, class, ttl, rdlen, rdata
        if (ar != 1 || q.size < qend + 11 || q[qend].toInt() != 0 || u16(q, qend + 1) != 41) return q
        val rdlen = u16(q, qend + 9)
        if (qend + 11 + rdlen != q.size) return q
        var o = qend + 11
        while (o + 4 <= q.size) {
            if (u16(q, o) == 8) return q // already has ECS
            o += 4 + u16(q, o + 2)
        }
        val r = q + ecs
        put16(r, qend + 9, rdlen + ecs.size)
        return r
    }

    /** Turns a DNS query into a SERVFAIL response (QR=1, RCODE=2), keeping only the question section. */
    fun servfail(q: ByteArray): ByteArray {
        if (q.size < 12) return q.copyOf()
        var i = 12
        while (i < q.size && q[i].toInt() != 0) {
            if (u8(q[i]) and 0xC0 != 0) { i++; break } // compression pointer (unexpected in question)
            i += u8(q[i]) + 1
        }
        val qend = i + 5 // zero label + QTYPE + QCLASS
        val valid = qend <= q.size && u16(q, 4) == 1
        val r = q.copyOf(if (valid) qend else 12)
        r[2] = ((u8(q[2]) and 0x01) or 0x80).toByte() // QR=1, keep RD, opcode 0
        r[3] = 0x82.toByte()                           // RA=1, RCODE=2
        if (!valid) put16(r, 4, 0)
        put16(r, 6, 0); put16(r, 8, 0); put16(r, 10, 0)
        return r
    }
}
