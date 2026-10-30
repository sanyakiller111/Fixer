package com.october.fixer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class Mm2Test {

    private fun ip(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun parsesServerList() {
        val json = """{"previousPageCursor":null,"nextPageCursor":"x","data":[
            {"id":"a","maxPlayers":12,"playing":11,"playerTokens":[],"players":[],"fps":59.9,"ping":71},
            {"id":"b","maxPlayers":12,"playing":4,"fps":60},
            {"maxPlayers":12,"playing":4}
        ]}"""
        val list = Mm2.parse(json)
        assertEquals(listOf("a", "b"), list.map { it.id })
        assertEquals(71, list[0].ping)
        assertNull(list[1].ping)
    }

    @Test
    fun nullPingIsUnknownNotZero() {
        val list = Mm2.parse("""{"data":[{"id":"a","maxPlayers":12,"playing":6,"ping":null}]}""")
        assertNull(list[0].ping)
    }

    @Test
    fun nullOrMissingDataIsEmpty() {
        assertTrue(Mm2.parse("""{"data":null}""").isEmpty())
        assertTrue(Mm2.parse("""{"errors":[{"code":0}]}""").isEmpty())
    }

    @Test
    fun pickSkipsFullEmptyAndTried() {
        val list = listOf(
            Mm2.Server("full", 12, 12, 40),
            Mm2.Server("empty", 1, 12, 40),
            Mm2.Server("tried", 6, 12, 40),
            Mm2.Server("ok", 6, 12, 300),
        )
        repeat(20) { assertEquals("ok", Mm2.pick(list, setOf("tried"), Random(it.toLong()))?.id) }
        assertNull(Mm2.pick(list, setOf("tried", "ok")))
    }

    @Test
    fun pickPrefersLowerHalfByPing() {
        val list = (1..10).map { Mm2.Server("s$it", 6, 12, it * 50) }
        val seen = (0 until 200).mapNotNull { Mm2.pick(list, emptySet(), Random(it.toLong()))?.id }.toSet()
        assertEquals(setOf("s1", "s2", "s3", "s4", "s5"), seen)
    }

    @Test
    fun robloxRangeAndCities() {
        assertTrue(Mm2.isRobloxServer(ip(128, 116, 5, 20)))
        assertTrue(Mm2.isRobloxServer(ip(128, 116, 127, 1)))
        assertFalse(Mm2.isRobloxServer(ip(128, 116, 128, 1)))
        assertFalse(Mm2.isRobloxServer(ip(8, 8, 8, 8)))
        assertEquals("Франкфурт", Mm2.city(ip(128, 116, 44, 9)))
        assertEquals("Амстердам", Mm2.city(ip(128, 116, 21, 9)))
        assertNull(Mm2.city(ip(128, 116, 3, 9))) // sources disagree: no label
    }

    @Test
    fun sameServerCountsOnceAMinute() {
        val a = ip(128, 116, 5, 1)
        assertTrue(Mm2.newJoin(a, 1_000_000))
        assertFalse(Mm2.newJoin(a, 1_030_000))
        assertTrue(Mm2.newJoin(ip(128, 116, 21, 1), 1_031_000))
        assertTrue(Mm2.newJoin(a, 1_032_000))
    }

    @Test
    fun rttIsFirstReplyAfterLastSend() {
        val p = RttProbe()
        assertNull(p.received(5)) // a reply before we sent anything is not timed
        val ms = 1_000_000L
        // the handshake seen on the phone: two packets 8 ms apart, the reply 84 ms after the second
        p.sent(0)
        p.sent(8 * ms)
        assertEquals(84, p.received(92 * ms))
        p.sent(93 * ms)
        assertNull(p.received(93 * ms + 100_000)) // the busy stream afterwards is not timed
    }
}
