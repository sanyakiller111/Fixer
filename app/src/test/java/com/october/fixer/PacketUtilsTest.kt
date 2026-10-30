package com.october.fixer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketUtilsTest {

    private val dnsQuery: ByteArray = byteArrayOf(
        0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0,
        7, 101, 120, 97, 109, 112, 108, 101, // "example"
        3, 99, 111, 109, 0,                  // "com"
        0, 1, 0, 1,
    )

    private fun port(p: ByteArray, o: Int) = ((p[o].toInt() and 0xFF) shl 8) or (p[o + 1].toInt() and 0xFF)

    private fun buildV4Query(): ByteArray {
        val ulen = 8 + dnsQuery.size
        val p = ByteArray(20 + ulen)
        p[0] = 0x45
        p[2] = (p.size shr 8).toByte(); p[3] = p.size.toByte()
        p[8] = 64; p[9] = 17
        byteArrayOf(10, 111, 0, 1).copyInto(p, 12) // src
        byteArrayOf(10, 111, 0, 2).copyInto(p, 16) // dst
        val c = PacketUtils.checksum(p, 0, 20)
        p[10] = (c shr 8).toByte(); p[11] = c.toByte()
        p[20] = 0xC3.toByte(); p[21] = 0x50 // src port 50000
        p[22] = 0; p[23] = 53               // dst port 53
        p[24] = (ulen shr 8).toByte(); p[25] = ulen.toByte()
        dnsQuery.copyInto(p, 28)
        return p
    }

    @Test
    fun ipv4ParseAndReply() {
        val pkt = buildV4Query()
        val req = PacketUtils.parse(pkt, pkt.size)
        assertNotNull(req)
        req!!
        assertEquals(4, req.version)
        assertEquals(50000, req.srcPort)
        assertEquals(53, req.dstPort)
        assertArrayEquals(dnsQuery, req.payload)

        val answer = dnsQuery.copyOf().also { it[2] = 0x81.toByte(); it[3] = 0x80.toByte() }
        val reply = PacketUtils.buildReply(req, answer)

        assertArrayEquals(byteArrayOf(10, 111, 0, 2), reply.copyOfRange(12, 16)) // new src = old dst
        assertArrayEquals(byteArrayOf(10, 111, 0, 1), reply.copyOfRange(16, 20)) // new dst = old src
        assertEquals(53, port(reply, 20))
        assertEquals(50000, port(reply, 22))
        assertEquals(20 + 8 + answer.size, reply.size)
        assertEquals(0, PacketUtils.checksum(reply, 0, 20)) // valid IPv4 header checksum
        assertArrayEquals(answer, reply.copyOfRange(28, reply.size))
        assertNull(PacketUtils.parse(reply, reply.size)) // reply is not destined to port 53
        assertEquals(50000, PacketUtils.parseUdp(reply, reply.size)!!.dstPort) // but is still UDP
    }

    @Test
    fun servfailSetsFlags() {
        val r = PacketUtils.servfail(dnsQuery)
        assertEquals(0x12, r[0].toInt())
        assertEquals(0x34, r[1].toInt())
        assertTrue((r[2].toInt() and 0x80) != 0)
        assertEquals(2, r[3].toInt() and 0x0F)
        assertEquals(dnsQuery.size, r.size)
    }

    @Test
    fun cacheRewritesId() {
        val cache = DnsCache()
        val resp = dnsQuery.copyOf().also { it[2] = 0x81.toByte() }
        cache.put(dnsQuery, resp)
        val q2 = dnsQuery.copyOf().also { it[0] = 0x77; it[1] = 0x66 }
        val got = cache.get(q2)!!
        assertEquals(0x77, got[0].toInt())
        assertEquals(0x66, got[1].toInt())
    }

    @Test
    fun ecsZeroAddedToPlainAndOptQueries() {
        // header (1 question) + "a.b" A IN
        val plain = byteArrayOf(0, 1, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 1, 'a'.code.toByte(), 1, 'b'.code.toByte(), 0, 0, 1, 0, 1)
        val withOpt = PacketUtils.withEcsZero(plain)
        assertEquals(1, port(withOpt, 10))
        assertEquals(plain.size + 11 + 8, withOpt.size)
        assertEquals(41, port(withOpt, plain.size + 1))
        assertEquals(8, port(withOpt, plain.size + 11))

        // already has an empty OPT: option is appended and rdlen grows
        val again = withOpt.copyOf(plain.size + 11)
        again[again.size - 1] = 0; again[10] = 0; again[11] = 1
        val r = PacketUtils.withEcsZero(again)
        assertEquals(8, port(r, plain.size + 9))
        assertArrayEquals(withOpt, r)
        // idempotent
        assertArrayEquals(r, PacketUtils.withEcsZero(r))
    }

    private fun udpPacket(v6: Boolean, proto: Int, dport: Int, frag: Int = 0): ByteArray {
        val hl = if (v6) 40 else 20
        val p = ByteArray(hl + 8 + 20)
        if (v6) {
            p[0] = 0x60; p[4] = 0; p[5] = 28; p[6] = proto.toByte(); p[7] = 64
        } else {
            p[0] = 0x45; p[2] = 0; p[3] = p.size.toByte(); p[6] = (frag shr 8).toByte(); p[7] = frag.toByte()
            p[8] = 64; p[9] = proto.toByte()
        }
        p[hl] = 0xC3.toByte(); p[hl + 1] = 0x50
        p[hl + 2] = (dport shr 8).toByte(); p[hl + 3] = dport.toByte()
        return p
    }

    @Test
    fun quicDetection() {
        assertTrue(PacketUtils.isQuic(udpPacket(false, 17, 443)))
        assertTrue(PacketUtils.isQuic(udpPacket(true, 17, 443)))
        assertFalse(PacketUtils.isQuic(udpPacket(false, 17, 53)))
        assertFalse(PacketUtils.isQuic(udpPacket(true, 17, 3478)))
        assertFalse(PacketUtils.isQuic(udpPacket(false, 6, 443))) // TCP/443 is not QUIC
        assertFalse(PacketUtils.isQuic(udpPacket(true, 6, 443)))
        assertFalse(PacketUtils.isQuic(udpPacket(false, 17, 443, frag = 0x00B9))) // non-first fragment
        assertFalse(PacketUtils.isQuic(ByteArray(0)))
        assertFalse(PacketUtils.isQuic(udpPacket(false, 17, 443), 20)) // truncated
        assertFalse(PacketUtils.isQuic(udpPacket(true, 17, 443), 44))
        val p = udpPacket(false, 17, 443)
        assertFalse(PacketUtils.isQuic(p, 0))
        p[0] = 0x25
        assertFalse(PacketUtils.isQuic(p)) // ihl 20 but version 2
    }
}
