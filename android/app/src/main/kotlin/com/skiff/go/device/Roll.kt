// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Skiff Go contributors. Based on the Skiff firmware (illuminus5) and Felucca (Hügelton Instruments);
// see NOTICE.md.
package com.skiff.go.device

import com.skiff.go.proto.Lanes
import com.skiff.go.proto.Step
import com.skiff.go.proto.StepTime

/** A note of the piano roll: the step it starts on, how many steps it sounds, its pitch. */
data class Note(val start: Int, val len: Int, val pitch: Int) {
    val end: Int get() = start + len
}

/**
 * Skiff 2.0's note model (design/TIER_B.md "Per-note ties"; ui.c ed_unfold / ed_del / ed_add, ui_input.c ed_open /
 * ed_lift / ed_place / ed_close): a step holds the notes that sound on it, each tied (bit k of the ties) when it goes on
 * from the step before; a TIE step holds every note sounding. Edits work on the unfolded steps (every TIE as the notes it
 * holds, all tied), then fold back: a step where every note sounding goes on is a TIE again, a step that did not change
 * keeps its bytes. Everything here takes and returns whole steps; a null result is "no room" (STEP n TAKEN).
 */
object Roll {
    /** The notes of step [s] a next step can tie: a NOTE's, a RATCH or FLAM step's own never tie out (ui.c ed_cont). */
    fun cont(s: Step): Int = when {
        s.time == StepTime.REST || s.ratchet > 1 || s.flam -> s.tieMask
        s.time == StepTime.NOTE -> (1 shl s.n) - 1
        else -> 0
    }

    /** Stored step [o] unfolded, [p] the one before unfolded (null: none): a TIE as the notes [p] can tie, all tied. */
    fun unfold(o: Step, p: Step?): Step {
        if (o.time != StepTime.TIE || p == null) return o
        val m = cont(p)
        val ns = (0 until p.n).filter { m shr it and 1 != 0 }.map { p.note(it) }
        return o.copy(time = StepTime.NOTE, n = ns.size, notes = List(4) { ns.getOrElse(it) { 0 } }, hit = 0, acc = 0,
            ties = (1 shl ns.size) - 1)
    }

    fun unfoldAll(steps: List<Step>, len: Int): MutableList<Step> {
        val w = ArrayList<Step>(len)
        for (i in 0 until len) w.add(unfold(steps[i], if (i > 0) w[i - 1] else null))
        return w
    }

    private fun noteAt(s: Step, pitch: Int): Int = (0 until s.n).firstOrNull { s.note(it) == pitch } ?: -1

    /** The last step the note of [pitch] starting at [i] sounds on. */
    private fun endOf(w: List<Step>, len: Int, i: Int, pitch: Int): Int {
        var e = i
        while (e + 1 < len) {
            val k = noteAt(w[e + 1], pitch)
            if (k < 0 || !w[e + 1].tied(k)) break
            e++
        }
        return e
    }

    /** The notes of a pattern: one per start, its steps counted through the steps that tie it on (inside [len]). */
    fun notes(steps: List<Step>, len: Int): List<Note> {
        if (steps.size < len) return emptyList()
        val w = unfoldAll(steps, len)
        val out = ArrayList<Note>()
        for (i in 0 until len) {
            val s = w[i]
            if (s.time != StepTime.NOTE) continue
            for (k in 0 until s.n) if (!s.tied(k)) out += Note(i, endOf(w, len, i, s.note(k)) - i + 1, s.note(k))
        }
        return out
    }

    private fun cleared(s: Step) = Step()

    /** ui.c ed_del: entry [k] out (the notes above down a place, their ties along). */
    private fun del(s: Step, k: Int): Step {
        val start = !s.tied(k)
        val ns = s.notes.toMutableList()
        for (x in k until s.n - 1) ns[x] = ns[x + 1]
        val n = s.n - 1
        val lo = (1 shl k) - 1
        val ties = if (s.hit == 0) (s.ties and lo) or ((s.ties shr 1) and lo.inv()) else s.ties
        var r = s.copy(notes = ns, n = n, ties = ties)
        if (n == 0 && s.hit == 0) return cleared(r)
        if (start && s.hit == 0 && r.tieMask == (1 shl n) - 1)       // only notes going on are left: none of its own values
            r = r.copy(flags = 0, vel = 0, chance = 100, ratchet = 1, flam = false, cond = 0, nudge = 0)
        return r
    }

    /** ui.c ed_add: pitch [p] in, tied (it goes on) or not; null: no room (4 notes, p there already, a tie onto hits, a TIE). */
    private fun add(s: Step, p: Int, tie: Boolean): Step? {
        if (s.time == StepTime.TIE || s.n >= 4 || noteAt(s, p) >= 0 || (tie && s.hit != 0)) return null
        var r = s
        if (s.n == 0 && s.hit == 0) r = r.copy(time = StepTime.NOTE)
        val ties = if (s.hit == 0) (s.ties and (1 shl s.n).inv()) or (if (tie) 1 shl s.n else 0) else s.ties
        val ns = s.notes.toMutableList(); ns[s.n] = p
        return r.copy(notes = ns, n = s.n + 1, ties = ties)
    }

    /** The values a step carries for the notes starting on it (velocity, accent, slide, RATCH, FLAM, CHANCE, COND, NUDGE). */
    private fun withValues(d: Step, v: Step) =
        d.copy(flags = v.flags, vel = v.vel, chance = v.chance, ratchet = v.ratchet, flam = v.flam, cond = v.cond, nudge = v.nudge)

    private fun startsAny(s: Step): Boolean = s.hit != 0 || (0 until s.n).any { !s.tied(it) }

    /** Folds the edited unfolded copy [w] back over the stored [orig] steps (ui_input.c ed_close). */
    fun fold(orig: List<Step>, w: List<Step>, len: Int): List<Step> {
        val out = ArrayList(orig)
        var po: Step? = null
        var pu: Step? = null
        for (i in 0 until len) {
            val cur = w[i]
            val o = unfold(orig[i], if (i > 0) po else null)
            var held = i > 0 && cur.hit == 0 && cur.time == StepTime.NOTE
            val m = if (held) cont(pu!!) else 0
            var k = 0
            while (held && k < cur.n) {
                val at = noteAt(pu!!, cur.note(k))
                held = cur.tied(k) && at >= 0 && (m shr at and 1) != 0
                k++
            }
            held = held && Integer.bitCount(m) == cur.n
            val same = cur == o
            out[i] = when {
                same && orig[i].time == StepTime.TIE && held -> orig[i]
                held && cur.n > 0 -> cur.copy(time = StepTime.TIE, n = 0, notes = List(4) { 0 }, ties = 0)
                same && orig[i].time != StepTime.TIE -> orig[i]
                else -> cur
            }
            po = o
            pu = cur
        }
        return out
    }

    // ---------------------------------------------------------------- edits ---

    /** Whether any other note (not [self]) sounds on steps [start, start + len) in a single-voice track. */
    private fun clash(ns: List<Note>, self: Note?, start: Int, len: Int) =
        ns.any { it != self && it.start < start + len && it.end > start }

    /** Lifts [note] out of the unfolded copy [w]; returns its start step's values. */
    private fun lift(w: MutableList<Step>, len: Int, note: Note): Step {
        val vals = w[note.start]
        val e = endOf(w, len, note.start, note.pitch)
        for (j in note.start..e) {
            val k = noteAt(w[j], note.pitch)
            if (k >= 0) w[j] = del(w[j], k)
        }
        return vals
    }

    /** Puts a note of [pitch] at [start], [n] steps, into [w]; false when a step has no room. */
    private fun place(w: MutableList<Step>, len: Int, start: Int, pitch: Int, n: Int): Boolean {
        for (j in 0 until n) {
            if (start + j >= len) return false
            w[start + j] = add(w[start + j], pitch, j != 0) ?: return false
        }
        return true
    }

    /**
     * A note in: [len] steps of [pitch] from [start]. On a single voice (MONO, LEGATO, UNISON: the new note takes over) a
     * note before is cut at the new start and one it covers is taken out; a note that starts under it and goes on beyond it
     * stops the edit. Null: no room.
     */
    fun addNote(steps: List<Step>, length: Int, note: Note, poly: Boolean): List<Step>? {
        if (note.start !in 0 until length || note.end > length || note.len < 1 || note.pitch !in 1..127) return null
        var cur = steps
        if (!poly) {
            for (o in notes(cur, length)) {
                if (!(o.start < note.end && o.end > note.start)) continue
                cur = when {
                    o.start < note.start -> resizeNote(cur, length, o, note.start - o.start, poly = true) ?: return null
                    o.end <= note.end -> removeNote(cur, length, o)
                    else -> return null
                }
            }
        }
        val w = unfoldAll(cur, length)
        if (!place(w, length, note.start, note.pitch, note.len)) return null
        return fold(cur, w, length)
    }

    fun removeNote(steps: List<Step>, length: Int, note: Note): List<Step> {
        val w = unfoldAll(steps, length)
        lift(w, length, note)
        return fold(steps, w, length)
    }

    /** [note] moved to [start] and [pitch], its start step's values with it when the new step has none of its own. */
    fun moveNote(steps: List<Step>, length: Int, note: Note, start: Int, pitch: Int, poly: Boolean): List<Step>? {
        if (start !in 0 until length || start + note.len > length || pitch !in 1..127) return null
        if (start == note.start && pitch == note.pitch) return steps
        if (!poly && clash(notes(steps, length), note, start, note.len)) return null
        val w = unfoldAll(steps, length)
        val vals = lift(w, length, note)
        val own = startsAny(w[start])
        if (!place(w, length, start, pitch, note.len)) return null
        if (!own) w[start] = withValues(w[start], vals)
        return fold(steps, w, length)
    }

    /** [note] stretched or cut to [newLen] steps (a note can go on through steps where others start). */
    fun resizeNote(steps: List<Step>, length: Int, note: Note, newLen: Int, poly: Boolean): List<Step>? {
        if (newLen < 1 || note.start + newLen > length) return null
        if (newLen == note.len) return steps
        if (!poly && clash(notes(steps, length), note, note.start, newLen)) return null
        val w = unfoldAll(steps, length)
        val vals = lift(w, length, note)
        val own = startsAny(w[note.start])
        if (!place(w, length, note.start, note.pitch, newLen)) return null
        if (!own) w[note.start] = withValues(w[note.start], vals)
        return fold(steps, w, length)
    }

    /** The values of the step the notes of [note] start on (velocity, accent, ..) set through [edit]. */
    fun editStart(steps: List<Step>, length: Int, note: Note, edit: (Step) -> Step): List<Step> {
        val w = unfoldAll(steps, length)
        w[note.start] = edit(w[note.start])
        return fold(steps, w, length)
    }

    // ---------------------------------------------------------------- the drum grid ---

    /** Lane [lane] of step [s] on or off: its hit, and the notes on that lane (they become the lane's hit when on). */
    fun toggleLane(s: Step, lane: Int): Step {
        val bit = 1 shl lane
        var r = s
        if (Lanes.lanes(s) and bit != 0) {
            r = r.copy(hit = r.hit and bit.inv(), acc = r.acc and bit.inv())
            for (k in (0 until r.n).reversed()) if (Lanes.of(r.note(k)) == lane) r = del(r, k)
            if (r.accent && Lanes.lanes(r) == 0) r = r.copy(flags = r.flags and 1.inv())
        } else r = r.copy(hit = r.hit or bit)
        if (r.n == 0 && r.hit == 0) return cleared(r)
        return if (r.time == StepTime.NOTE) r else r.copy(time = StepTime.NOTE)
    }

    /** The accent of lane [lane] on step [s] toggled (a lane that does not strike does nothing). */
    fun toggleLaneAccent(s: Step, lane: Int): Step {
        val bit = 1 shl lane
        if (Lanes.lanes(s) and bit == 0) return s
        val on = Lanes.accents(s) and bit != 0
        // the step's own accent flag accents every lane: turn it into the per-lane accents first
        val base = if (s.accent) s.copy(flags = s.flags and 1.inv(), acc = Lanes.lanes(s)) else s
        return base.copy(acc = if (on) base.acc and bit.inv() else (base.acc or bit) and Lanes.lanes(base))
    }
}
