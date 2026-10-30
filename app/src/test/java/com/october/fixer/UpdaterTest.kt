package com.october.fixer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdaterTest {

    private val sha = "a".repeat(64)

    @Test
    fun parsesRelease() {
        val r = Updater.parse("""{"versionCode":4,"versionName":"1.3","apk":"Fixer-1.3.apk","sha256":"${sha.uppercase()}","notes":"x"}""")!!
        assertEquals(4, r.code)
        assertEquals("1.3", r.name)
        assertEquals("Fixer-1.3.apk", r.apk)
        assertEquals(sha, r.sha256)
    }

    @Test
    fun rejectsReleaseWithoutCodeOrHash() {
        assertNull(Updater.parse("""{"versionName":"1.3","sha256":"$sha"}"""))
        assertNull(Updater.parse("""{"versionCode":4,"sha256":"abc"}"""))
        assertNull(Updater.parse("""{"versionCode":4}"""))
    }
}
