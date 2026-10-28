// SPDX-License-Identifier: GPL-3.0-only
package com.skiff.go.proto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundProtoTest {
    @Test fun wideIdsGoOutAsScope2() {
        assertArrayEquals(intArrayOf(0, 127), Requests.set(0, 127, 0).args.copyOf(2))
        assertArrayEquals(intArrayOf(2, 7), Requests.set(0, 135, 5).args.copyOf(2))     // P_E0 on Skiff 2.0
        assertArrayEquals(intArrayOf(1, 20), Requests.desc(1, 20).args)                 // globals never wide
        val pv = Parse.param(intArrayOf(2, 7) + v14enc(-3))
        assertEquals(ParamValue(0, 135, -3), pv)
    }

    @Test fun trackParamCarriesBit7InTheTrackByte() {
        val r = Requests.trackParam(4, 135, 10)
        assertEquals(4 or 64, r.args[0])
        assertEquals(7, r.args[1])
        assertEquals(ParamValue(4, 135, 10), Parse.trackParam(r.args))
        assertEquals(ParamValue(2, 39, -5), Parse.trackParam(intArrayOf(2, 39) + v14enc(-5)))
    }

    @Test fun descParsesEnumAndWide() {
        val a = intArrayOf(2, 0, Fmt.ENUM) + v14enc(0) + v14enc(2) + v14enc(1) + strEnc("WAVE") + strEnc("") +
            strEnc("SIN") + strEnc("TRI") + strEnc("SAW")
        val d = Parse.desc(a)
        assertEquals(0, d.scope); assertEquals(128, d.id)
        assertEquals(listOf("SIN", "TRI", "SAW"), d.names)
        assertTrue(d.visible)
        assertFalse(d.copy(label = "-").visible)
        assertFalse(d.copy(max = 0, min = 0).visible)
    }

    @Test fun namesWithAndWithoutTitles() {
        val a = intArrayOf(10, 2) + strEnc("STD") + strEnc("808")
        assertEquals(emptyList<String>(), Parse.names(a).titles)
        val b = a + strEnc("KIT") + strEnc("LANES")
        assertEquals(listOf("KIT", "LANES"), Parse.names(b).titles)
        assertEquals(listOf("STD", "808"), Parse.names(b).names)
    }

    @Test fun repliesMatchTheirRequest() {
        val set = Requests.set(0, 33, 10)
        assertTrue(replyMatches(Cmd.SET, set.args, intArrayOf(0, 33) + v14enc(10)))
        assertFalse(replyMatches(Cmd.SET, set.args, intArrayOf(0, 34) + v14enc(10)))   // a late reply to another SET
        val tp = Requests.trackParam(3, 39, 0)
        assertTrue(replyMatches(Cmd.TRACK_PARAM, tp.args, intArrayOf(3, 39) + v14enc(0)))
        assertFalse(replyMatches(Cmd.TRACK_PARAM, tp.args, intArrayOf(2, 39) + v14enc(0)))
        assertTrue(replyMatches(Cmd.WATCH, intArrayOf(3), intArrayOf(1)))              // v3 firmware answers 1
        assertTrue(replyMatches(Cmd.LIST, IntArray(0), intArrayOf(0, 0)))
        assertFalse(replyMatches(Cmd.LIST, intArrayOf(3, 0, 1), intArrayOf(0, 0)))
        assertTrue(replyMatches(Cmd.MENU_SET, intArrayOf(4, 3, 64), intArrayOf(0, 4, 3, 64)))
        assertTrue(replyMatches(Cmd.TRANSPORT, IntArray(0), intArrayOf(0, 1, 0, 0, 0, 0)))
    }

    @Test fun valuesAsTheDeviceShowsThem() {
        fun d(fmt: Int, min: Int = 0, max: Int = 127, names: List<String> = emptyList()) =
            Desc(0, 0, fmt, min, max, 0, "X", "", names)
        assertEquals("100 %", Format.text(d(Fmt.PCT), 127))
        assertEquals("50 %", Format.text(d(Fmt.PCT, max = 100), 50))
        assertEquals("+100 %", Format.text(d(Fmt.BIPCT, -64, 63), 63))
        assertEquals("-100 %", Format.text(d(Fmt.BIPCT, -64, 63), -64))
        assertEquals("1.0 ms", Format.text(d(Fmt.TIME), 0))
        assertEquals("10.00 s", Format.text(d(Fmt.TIME), 127))
        assertEquals("30 Hz", Format.text(d(Fmt.CUTOFF), 0))
        assertEquals("OFF", Format.text(d(Fmt.DB), 0))
        assertEquals("+3 st", Format.text(d(Fmt.SEMI, -12, 12), 3))
        assertEquals("C#", Format.text(d(Fmt.NOTE, 0, 11), 1))
        assertEquals("TRI", Format.text(d(Fmt.ENUM, 0, 2, listOf("SIN", "TRI", "SAW")), 1))
    }

    @Test fun divisionsListLongestFirst() {
        val div = Desc(0, 30, Fmt.ENUM, 0, 9, 2, "DIV", "", listOf("1/4", "1/8", "1/16", "1/32", "8T", "16T", "1/2", "1/1", "2BAR", "4BAR"))
        // 4BAR 2BAR 1/1 1/2 1/4 1/8 8T (0.083) 1/16 16T (0.042) 1/32
        assertEquals(listOf(9, 8, 7, 6, 0, 1, 4, 2, 5, 3), Format.enumShown(div))
        val waves = Desc(0, 10, Fmt.ENUM, 0, 2, 0, "WAVE", "", listOf("SIN", "TRI", "SAW"))
        assertEquals(listOf(0, 1, 2), Format.enumShown(waves))
        assertEquals(1.5 / 8, Format.divLength("1/8D")!!, 1e-9)
    }

    @Test fun retiredValuesAreAliases() {
        val kit = listOf("STD", "66", "10", "77", "80", "10", "66", "55", "77")
        assertEquals(6, Format.aliasOf(kit, 1))      // 66 at 1 plays as 6
        assertEquals(5, Format.aliasOf(kit, 2))
        assertEquals(8, Format.aliasOf(kit, 3))
        assertEquals(4, Format.aliasOf(kit, 4))
        val set = listOf("PIANO", "PIANO", "STRINGS")
        assertEquals(0, Format.aliasOf(set, 1))      // the first is the original when it has the name
    }

    @Test fun enginesInTheDeviceOrder() {
        val names = listOf("ANALOG", "-", "PHASE", "LOFI", "SAMPLE", "VOICE", "TRIO", "WHEEL", "GRAIN", "PHYS", "DRUM", "NOISE",
            "FM6", "SLICE", "DRUM-X")
        assertEquals(listOf(0, 12, 2, 3, 4, 5, 6, 7, 8, 9, 11, 13, 10, 14), Format.engineOrder(names))
    }
}
