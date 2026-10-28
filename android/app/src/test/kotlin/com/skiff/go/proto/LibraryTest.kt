// SPDX-License-Identifier: GPL-3.0-only
package com.skiff.go.proto

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryTest {
    private val engines = listOf("ANALOG", "-", "PHASE", "LOFI", "SAMPLE", "VOICE", "TRIO", "WHEEL", "GRAIN", "PHYS", "DRUM",
        "NOISE", "FM6", "SLICE", "DRUM-X")

    @Test fun keysRepeatAndEngineParams() {
        val keys = Library.paramKeys(listOf("LEVEL", "ATTACK", "RATE", "RATE", null), 4, 6)
        assertEquals(listOf("LEVEL", "ATTACK", "RATE", "RATE#2", "E0", "E1"), keys)
    }

    @Test fun tailMapsTheCommonsAndTheEngine() {
        // a 12-value file (P_E0 4) into 14 values with P_E0 6: ids 0..3 stay, its last 8 land on E0..E7
        val p = (0 until 12).map { it }
        val out = Library.tailParams(p, 14, 6)
        assertEquals(listOf(0, 1, 2, 3, null, null, 4, 5, 6, 7, 8, 9, 10, 11), out)
    }

    @Test fun remapByLabels() {
        val from = listOf("LEVEL", "PAN", "E0")
        val to = listOf("LEVEL", "MUTE", "PAN", "E0")
        assertEquals(listOf(10, null, -5, 3), Library.remapParams(listOf(10, -5, 3), from, to, 3))
    }

    @Test fun legacyLabelsFindTodaysKeys() {
        val keys = Library.paramKeys(listOf("LEVEL", "ATTACK"), 2, 2)
        assertEquals(listOf("LEVEL", "ATTACK"), Library.legacyKeys(listOf("LVL", "ATK"), keys))
        assertEquals(listOf("LEVEL"), Library.legacyKeys(listOf("LEVEL"), keys))
    }

    @Test fun patchFileRoundTrip() {
        val dump = Dump(12, 3, IntArray(6) { it * 10 }, IntArray(2) { 120 + it })
        val json = Library.patchFile("FELUCCA v2.0.1", engines, "E.PIANO", dump)
        assertEquals("felucca-patch", json.getString("format"))
        assertEquals("FM6", json.getString("engineName"))
        val ctx = LibraryContext(listOf("A", "B", "C", "D", "E0", "E1"), engines, "FELUCCA v2.0.1", 4)
        val r = Library.read(JSONObject(json.toString()), ctx)
        assertEquals(0, r.skipped)
        val pt = r.patches.single()
        assertEquals("E.PIANO", pt.name)
        assertEquals(12, pt.engine)
        assertEquals(listOf(0, 10, 20, 30, 40, 50), pt.p)
    }

    @Test fun libraryByLabelsAndDigitalSkipped() {
        val file = JSONObject().put("format", "felucca-library").put("version", 1)
            .put("paramLabels", JSONArray(listOf("LVL", "ATK", "E0")))
            .put("engines", JSONArray(listOf("ANALOG", "DIGITAL")))
            .put("patches", JSONArray()
                .put(JSONObject().put("name", "BASS").put("engine", 0).put("params", JSONArray(listOf(100, JSONObject.NULL, 7))))
                .put(JSONObject().put("name", "OLD FM").put("engine", 1).put("params", JSONArray(listOf(1, 2, 3)))))
        val ctx = LibraryContext(listOf("LEVEL", "ATTACK", "DECAY", "E0"), engines, "FELUCCA v2.0.1", 3)
        val r = Library.read(file, ctx)
        assertEquals(1, r.skipped)                    // DIGITAL: Skiff 2.0 has no such engine ("-")
        val pt = r.patches.single()
        assertEquals("BASS", pt.name)
        assertEquals("ANALOG", pt.engineName)
        assertEquals(listOf(100, null, null, 7), pt.p)
        assertNull(pt.fm6)
    }

    @Test fun libraryFileWritesKeys() {
        val ctx = LibraryContext(listOf("LEVEL", "E0"), engines, "FELUCCA v2.0.1", 1)
        val o = Library.libraryFile("library", listOf(Patch("X", 0, "ANALOG", listOf(1, null))), ctx, "2026-10-27T00:00:00Z")
        assertEquals("felucca-library", o.getString("format"))
        assertEquals(2, o.getInt("pCount"))
        val p = o.getJSONArray("patches").getJSONObject(0)
        assertEquals(true, p.getJSONArray("params").isNull(1))
        assertEquals(1, Library.read(o, ctx).patches.size)
    }
}
