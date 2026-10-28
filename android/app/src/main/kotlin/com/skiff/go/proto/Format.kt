// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Skiff Go contributors. Based on the Skiff firmware (illuminus5) and Felucca (Hügelton Instruments);
// see NOTICE.md.
package com.skiff.go.proto

import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

/** How the editor shows values and lists (editor.html fmtValue, enumShown, aliasOf, engineOrder). */
object Format {
    val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    /** (1.3) ENV SYNC on: ATTACK DECAY RELEASE are note values of the tempo (params.c ESYNC_NAMES, 0..127 split evenly). */
    val ESYNC_NAMES = listOf("0", "1/64T", "1/64", "1/32T", "1/64D", "1/32", "1/16T", "1/32D", "1/16", "1/8T", "1/16D",
        "1/8", "1/4T", "1/8D", "1/4", "1/2T", "1/4D", "1/2", "1/1T", "1/2D", "1/1", "1/1D", "2BAR", "3BAR", "4BAR")

    /** The order the device lists its engines in (engines.c ENGINE_ORDER); the protocol keeps the numbers. */
    val ENGINE_ORDER = listOf("ANALOG", "FM6", "DIGITAL", "PHASE", "LOFI", "SAMPLE", "VOICE", "TRIO", "WHEEL", "GRAIN",
        "PHYS", "NOISE", "SLICE", "DRUM", "DRUM-X")

    private fun f(x: Double, n: Int) = String.format(Locale.US, "%.${n}f", x)
    private fun sign(x: Int) = if (x > 0) "+$x" else "$x"

    /** The value as the device shows it: the text and its unit. */
    fun value(d: Desc, v: Int): Pair<String, String> = when (d.fmt) {
        Fmt.PCT -> (if (d.max == 100) "$v" else "${(v * 100 + 63) / 127}") to "%"
        Fmt.BIPCT -> sign(v * 100 / (if (v > 0) 63 else 64)) to "%"
        Fmt.TIME -> {
            val ms10 = (10 * 10000.0.pow((v and 127) / 127.0)).roundToInt()
            when {
                ms10 < 100 -> f(ms10 / 10.0, 1) to "ms"
                ms10 < 10000 -> "${(ms10 / 10.0).roundToInt()}" to "ms"
                else -> f(ms10 / 10000.0, 2) to "s"
            }
        }
        Fmt.LFOHZ -> { val h = 0.05 * 800.0.pow((v and 127) / 127.0); (if (h < 10) f(h, 2) else f(h, 1)) to "Hz" }
        Fmt.CUTOFF -> { val h = 30 * (16000.0 / 30).pow((v and 127) / 127.0); if (h < 1000) "${h.roundToInt()}" to "Hz" else f(h / 1000, 1) to "kHz" }
        Fmt.DB -> if (v <= 0) "OFF" to "" else f((v - 112) / 2.0, 1) to "dB"
        Fmt.SEMI -> sign(v) to "st"
        Fmt.ENUM -> (d.names.getOrNull(v - d.min) ?: "$v") to d.unit
        Fmt.BPM -> "$v" to "BPM"
        Fmt.NOTE -> NOTE_NAMES[((v % 12) + 12) % 12] to ""
        Fmt.ONOFF -> (if (v != 0) "ON" else "OFF") to ""
        Fmt.STEPS -> "$v" to "STEP"
        Fmt.ST16 -> {
            val t = ((v - 64) * 30 + (if (v < 64) -8 else 8)) / 16
            ((if (t > 0) "+" else "") + f(t / 10.0, 1)) to "st"
        }
        else -> "$v" to d.unit
    }

    fun text(d: Desc, v: Int): String = value(d, v).let { (t, u) -> if (u.isEmpty()) t else "$t $u" }

    /**
     * A retired enum value keeps its number and names the value it now plays (EDITOR_PROTOCOL.md "Retired enum values"):
     * the original is the first value when it has that name, else the last one with it.
     */
    fun aliasOf(names: List<String>, i: Int): Int {
        val n = names.getOrNull(i) ?: return i
        val k = if (names.firstOrNull() == n) 0 else names.lastIndexOf(n)
        return if (k >= 0) k else i
    }

    fun isAlias(d: Desc, v: Int): Boolean = d.fmt == Fmt.ENUM && aliasOf(d.names, v - d.min) != v - d.min

    /** A note division's length in bars (1/4, 8T, 2BAR, 1/8D), null for any other name. */
    fun divLength(n: String): Double? {
        Regex("^(\\d+)/(\\d+)D$").find(n)?.let { return 1.5 * it.groupValues[1].toDouble() / it.groupValues[2].toDouble() }
        Regex("^(\\d+)/(\\d+)$").find(n)?.let { return it.groupValues[1].toDouble() / it.groupValues[2].toDouble() }
        Regex("^(\\d+)(T|BAR)$").find(n)?.let {
            val a = it.groupValues[1].toDouble()
            return if (it.groupValues[2] == "BAR") a else 2.0 / 3 / a
        }
        return null
    }

    /** The values in the order shown: note divisions longest first (as the device's knob steps them, #48), else in order. */
    fun enumShown(d: Desc): List<Int> {
        val vals = (d.min..d.max).toList()
        if (d.fmt != Fmt.ENUM || d.names.isEmpty()) return vals
        val len = d.names.map { divLength(it) }
        if (len.size < vals.size || len.any { it == null }) return vals
        return vals.sortedWith(compareByDescending<Int> { len[it - d.min]!! }.thenBy { it })
    }

    /** Engine numbers in the device's order, the reserved ones ("-") left out. */
    fun engineOrder(names: List<String>): List<Int> {
        fun rank(n: String) = ENGINE_ORDER.indexOf(n).let { if (it < 0) ENGINE_ORDER.size else it }
        return names.indices.filter { names[it] != "-" }.sortedWith(compareBy<Int> { rank(names[it]) }.thenBy { it })
    }
}
