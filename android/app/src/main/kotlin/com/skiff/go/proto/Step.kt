// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Skiff Go contributors. Based on the Skiff firmware (illuminus5) and Felucca (Hügelton Instruments);
// see NOTICE.md.
package com.skiff.go.proto

/**
 * A sequencer step as TRACK_STEP carries it (editor.c ed_step_reply / ed_step_put, 17 bytes after the index):
 * n, 4 notes, time, flags (1 accent, 2 slide), vel, the hits (7 bits each and the top bits), chance, ratchet, FLAM,
 * COND, NUDGE + 32 and, with INFO 57 01 bit 6, the tie bits.
 *
 * [ties]: bit k says note k goes on from the step before (a note held over the step line); only on a step without
 * [hit]s, and only for the notes in use. [hit] / [acc]: the drum lanes that strike on the step and their accents.
 */
data class Step(
    val n: Int = 0,
    val notes: List<Int> = List(4) { 0 },
    val time: Int = StepTime.REST,
    val flags: Int = 0,
    val vel: Int = 0,
    val hit: Int = 0,
    val acc: Int = 0,
    val chance: Int = 100,
    val ratchet: Int = 1,
    val flam: Boolean = false,
    val cond: Int = 0,
    val nudge: Int = 0,
    val ties: Int = 0,
) {
    val accent: Boolean get() = flags and 1 != 0
    val slide: Boolean get() = flags and 2 != 0
    /** The velocity that plays: 0 means 96 (seq.c). */
    val velocity: Int get() = if (vel == 0) 96 else vel
    /** The tie bits that count (core.h step_ties). */
    val tieMask: Int get() = if (hit != 0) 0 else ties and ((1 shl n) - 1)
    fun tied(k: Int): Boolean = tieMask shr k and 1 != 0
    /** Every note on the step goes on: it plays as a TIE does. */
    val isTie: Boolean get() = time == StepTime.TIE || (time == StepTime.NOTE && n > 0 && tieMask == (1 shl n) - 1)
    /** A note or a drum hit starts here (ui.c step_starts). */
    val starts: Boolean get() = time != StepTime.TIE && (hit != 0 || (n > 0 && tieMask != (1 shl n) - 1))
    val empty: Boolean get() = n == 0 && hit == 0 && time != StepTime.TIE

    fun note(k: Int): Int = notes.getOrElse(k) { 0 }
    fun withNotes(list: List<Int>, count: Int) = copy(notes = List(4) { list.getOrElse(it) { 0 } }, n = count)

    companion object {
        const val NSTEP = 64
        /** core.h step_clear: nothing on it, a REST. */
        val EMPTY = Step()
    }
}

/** A step answer: its track and index with it. */
data class StepAt(val track: Int, val index: Int, val step: Step)

object StepWire {
    /** The 17 bytes of a step as TRACK_STEP / STEP_SET write it. */
    fun encode(s: Step): IntArray {
        val ties = if (s.time == StepTime.TIE) 0 else s.tieMask
        return intArrayOf(
            s.n.coerceIn(0, 4), *IntArray(4) { s.note(it) and 0x7F }, s.time.coerceIn(0, 2), s.flags and 3, s.vel and 0x7F,
            s.hit and 0x7F, s.acc and s.hit and 0x7F, (s.hit shr 7 and 1) or ((s.acc and s.hit) shr 7 and 1 shl 1),
            s.chance.coerceIn(0, 100), s.ratchet.coerceIn(1, 4), if (s.flam && s.ratchet == 1) 1 else 0,
            s.cond.coerceIn(0, 11), (s.nudge.coerceIn(-32, 31) + 32), ties and 15,
        )
    }

    /** The step bytes of a reply from the index on (STEP_GET: after the index; TRACK_STEP: after track and index). */
    fun decode(r: Reader): Step {
        val n = r.b()
        val notes = List(4) { r.b() }
        val time = r.b(); val flags = r.b(); val vel = r.b()
        var hit = 0; var acc = 0
        if (r.left >= 3) { val h = r.b(); val c = r.b(); val x = r.b(); hit = h or ((x and 1) shl 7); acc = (c or ((x and 2) shl 6)) and hit }
        val chance = if (r.remaining) r.b() else 100
        val ratchet = if (r.remaining) r.b() else 1
        val flam = if (r.remaining) r.b() else 0
        val cond = if (r.remaining) r.b() else 0
        val nudge = (if (r.remaining) r.b() else 32) - 32
        val ties = if (r.remaining) r.b() else 0
        require(chance <= 100 && ratchet in 1..4 && flam <= 1 && ties <= 15) { "Invalid step" }
        return Step(n.coerceAtMost(4), notes, time.coerceAtMost(2), flags, vel, hit, acc, chance, ratchet, flam != 0, cond, nudge, ties)
    }
}

/** The drum lanes of a step (editor.c / eng_drum.c DRUM_LANE_NOTE) and the lane a GM note strikes. */
object Lanes {
    val NAMES = listOf("KICK", "SNARE", "CLAP", "HATCL", "HATOP", "TOM", "RIM", "BELL")
    val NOTE = listOf(36, 38, 39, 42, 46, 45, 37, 56)
    private const val LANE_OF = "00612153535455757773777675555555773366336665577"
    fun of(note: Int): Int = LANE_OF[(if (note in 35..81) note else 36 + ((note + 120 - 36) % 12)) - 35] - '0'

    /** Lanes a step strikes: its hits and the lanes its notes fall on (a NOTE step only). */
    fun lanes(s: Step): Int =
        if (s.time != StepTime.NOTE) 0 else (0 until s.n).fold(s.hit) { m, k -> m or (1 shl of(s.note(k))) }
    fun accents(s: Step): Int = if (s.accent) lanes(s) else s.acc and lanes(s)
}
