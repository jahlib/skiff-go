// SPDX-License-Identifier: GPL-3.0-only
package com.skiff.go.proto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    private val engines = listOf("ANALOG", "-", "PHASE", "LOFI", "SAMPLE", "VOICE", "TRIO", "WHEEL", "GRAIN", "PHYS",
        "DRUM", "NOISE", "FM6", "SLICE", "DRUM-X")

    /** INFO as Skiff 2.0.1's editor.c ED_INFO writes it (P_COUNT 143, P_E0 135, six tracks, T5 and T6 light). */
    private fun skiffInfo(withTies: Boolean = true): IntArray {
        val a = ArrayList<Int>()
        a += strEnc("FELUCCA v2.0.1").toList()
        a += listOf(15, 127, 27, 64, 127)
        engines.forEach { a += strEnc(it).toList() }
        a += listOf(6, 16)
        a += listOf(0x55, 1, 25)
        a += listOf(0x4D, 1, 64, 1, 0x42, 1, 3, 0x46, 1, 8, 0, 0x53, 1, 3, 0x50, 1, 3, 0x4E, 1, 16, 0x52, 1, 4, 0x4C, 1, 1)
        val em = (1 shl 8) or (1 shl 9)                  // GRAIN and PHYS
        a += listOf(0x54, 1, 0x30, em and 127, (em shr 7) and 127, (em shr 14) and 127)
        a += listOf(0x43, 1, 12, 0x4B, 1, 8, 0x57, 1, if (withTies) 127 else 63, 0x49, 1, 143 and 127, 143 shr 7, 135 and 127, 135 shr 7,
            0x58, 1, 3)
        return a.toIntArray()
    }

    @Test fun skiffInfoParses() {
        val i = Parse.info(skiffInfo())
        assertTrue(i.isSkiff)
        assertEquals("FELUCCA v2.0.1", i.version)
        assertEquals(143, i.pcount)
        assertEquals(135, i.pe0)
        assertEquals(27, i.gcount)
        assertEquals(6, i.ntrk)
        assertEquals(15, i.engines.size)
        assertEquals("DRUM-X", i.engines[14])
        assertEquals(64, i.motionMax)
        assertEquals(16, i.menuCount)
        assertEquals(4, i.ratchetMax)
        assertTrue(i.hasLocks)
        assertEquals(12, i.trigConds)
        assertEquals(8, i.kitLanes)
        assertTrue(i.hasTies)
        assertEquals(3, i.song2Caps)
        assertEquals(0x30, i.lightTracks)
        assertFalse(i.engineAllowed(4, 9))               // T5: no PHYS
        assertFalse(i.engineAllowed(5, 8))               // T6: no GRAIN
        assertTrue(i.engineAllowed(0, 9))                // T1 plays PHYS
        assertTrue(i.engineAllowed(4, 12))               // T5 plays FM6
    }

    @Test fun skiffWithoutTiesIsStillSkiff() {
        val i = Parse.info(skiffInfo(withTies = false))
        assertTrue(i.isSkiff)
        assertFalse(i.hasTies)
    }

    /** SLOOP ends INFO with its protocol version after NTRK, no tags: never taken for Skiff. */
    @Test fun sloopIsRejected() {
        val a = ArrayList<Int>()
        a += strEnc("FELUCCA SLOOP 2.8").toList()
        a += listOf(13, 71, 50, 64, 63)
        repeat(13) { a += strEnc("E$it").toList() }
        a += listOf(4, 12)
        val i = Parse.info(a.toIntArray())
        assertFalse(i.isSkiff)
        assertEquals(12, i.chainRows)
        assertEquals(71, i.pcount)
    }

    /** Felucca has the tags but not Skiff's 57. */
    @Test fun feluccaIsRejected() {
        val a = ArrayList<Int>()
        a += strEnc("FELUCCA v1.1.5").toList()
        a += listOf(14, 99, 27, 64, 91)
        repeat(14) { a += strEnc("E$it").toList() }
        a += listOf(4, 16, 0x55, 1, 9, 0x4D, 1, 64, 1, 0x42, 1, 3)
        val i = Parse.info(a.toIntArray())
        assertFalse(i.isSkiff)
        assertEquals(3, i.backupCaps)
    }

    /** An unknown tag ends the parse without failing (its length is unknown); what came before stays. */
    @Test fun unknownTagStopsParse() {
        val a = skiffInfo().toMutableList()
        val at = a.indices.first { a[it] == 0x43 && a.getOrNull(it + 1) == 1 && a.getOrNull(it + 2) == 12 }
        a.addAll(at, listOf(0x7A, 1, 9, 9, 9))
        val i = Parse.info(a.toIntArray())
        assertEquals(4, i.ratchetMax)
        assertEquals(0, i.trigConds)
        assertFalse(i.isSkiff)
    }

    @Test fun transportParses() {
        val t = Parse.transport(intArrayOf(0, 0, 0, 1, 0b100, 2))
        assertFalse(t.playing)
        assertTrue(t.running)                            // a start pending
        assertEquals(4, t.armed)
        assertEquals(2, t.selected)
        assertFalse(Parse.transport(intArrayOf(0, 1, 0, 2, 0, 0)).running)
    }

    @Test fun tracksParse() {
        val a = intArrayOf(1, 2) + intArrayOf(10, 0) + v14enc(100) + intArrayOf(0, 1) + intArrayOf(12, 3) + v14enc(127) + intArrayOf(1, 0)
        val t = Parse.tracks(a)
        assertEquals(1, t.selected)
        assertEquals(2, t.tracks.size)
        assertEquals(100, t.tracks[0].level)
        assertTrue(t.tracks[0].armed)
        assertTrue(t.tracks[1].mute)
        assertEquals(12, t.tracks[1].engine)
    }

    @Test fun frameRoundTrip() {
        val f = frame(Cmd.TRANSPORT, intArrayOf(Requests.Op.PLAY))
        assertArrayEquals(byteArrayOf(0xF0.toByte(), 0x7D, 0x46, 0x4C, 76, 1, 0xF7.toByte()), f)
        val back = unframe(f)!!
        assertEquals(Cmd.TRANSPORT, back.cmd)
        assertArrayEquals(intArrayOf(1), back.args)
        assertEquals(-8192, v14dec(0, 0))
        assertEquals(127, v14enc(127).let { v14dec(it[0], it[1]) })
    }
}
