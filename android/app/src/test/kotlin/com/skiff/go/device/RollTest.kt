// SPDX-License-Identifier: GPL-3.0-only
package com.skiff.go.device

import com.skiff.go.proto.Lanes
import com.skiff.go.proto.Reader
import com.skiff.go.proto.Step
import com.skiff.go.proto.StepTime
import com.skiff.go.proto.StepWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RollTest {
    private val len = 16
    private fun empty() = List(Step.NSTEP) { Step() }
    private fun List<Step>.roll(l: Int = len) = Roll.notes(this, l).sortedWith(compareBy({ it.start }, { it.pitch }))

    @Test fun aLongNoteIsANoteAndTies() {
        val s = Roll.addNote(empty(), len, Note(2, 3, 60), poly = true)!!
        assertEquals(StepTime.NOTE, s[2].time); assertEquals(1, s[2].n); assertEquals(60, s[2].note(0)); assertFalse(s[2].tied(0))
        assertEquals(StepTime.TIE, s[3].time); assertEquals(StepTime.TIE, s[4].time)
        assertEquals(StepTime.REST, s[5].time)
        assertEquals(listOf(Note(2, 3, 60)), s.roll())
    }

    @Test fun overlappingNotesUsePerNoteTies() {
        var s = Roll.addNote(empty(), len, Note(0, 3, 60), true)!!
        s = Roll.addNote(s, len, Note(1, 1, 64), true)!!
        // step 1: 60 goes on (tied), 64 starts; step 2: only 60 goes on, but 64 sounded before: a NOTE with a tie, not a TIE
        assertEquals(2, s[1].n); assertTrue(s[1].tied(0)); assertFalse(s[1].tied(1)); assertEquals(64, s[1].note(1))
        assertEquals(StepTime.NOTE, s[2].time); assertEquals(1, s[2].n); assertTrue(s[2].tied(0)); assertEquals(60, s[2].note(0))
        assertEquals(listOf(Note(0, 3, 60), Note(1, 1, 64)), s.roll())
        // taking the second note out gives the original bytes back: TIE steps again
        val back = Roll.removeNote(s, len, Note(1, 1, 64))
        assertEquals(Roll.addNote(empty(), len, Note(0, 3, 60), true), back)
    }

    @Test fun aChordsNotesEndApart() {
        var s = Roll.addNote(empty(), len, Note(0, 4, 60), true)!!
        s = Roll.addNote(s, len, Note(0, 2, 64), true)!!
        assertEquals(listOf(Note(0, 4, 60), Note(0, 2, 64)), s.roll())
        assertEquals(2, s[0].n)
        assertEquals(StepTime.TIE, s[1].time)                  // both go on: a TIE
        assertEquals(StepTime.NOTE, s[2].time)                 // only the long one goes on: a NOTE with its tie bit
        assertEquals(1, s[2].n); assertTrue(s[2].tied(0)); assertEquals(60, s[2].note(0))
        assertEquals(StepTime.TIE, s[3].time)                  // one note left going on: a TIE again
    }

    @Test fun anUntouchedTieKeepsItsBytes() {
        var s = Roll.addNote(empty(), len, Note(0, 4, 60), true)!!
        val odd = s[2].copy(notes = listOf(99, 98, 0, 0))       // a TIE whose stale note bytes differ
        s = s.toMutableList().also { it[2] = odd }
        val t = Roll.addNote(s, len, Note(8, 1, 70), true)!!   // an edit elsewhere
        assertEquals(odd, t[2])
    }

    @Test fun movingAndResizing() {
        val s = Roll.addNote(empty(), len, Note(2, 2, 60), true)!!
        val v = Roll.editStart(s, len, Note(2, 2, 60)) { it.copy(vel = 110, flags = 1, chance = 50) }
        val moved = Roll.moveNote(v, len, Note(2, 2, 60), 6, 67, true)!!
        assertEquals(listOf(Note(6, 2, 67)), moved.roll())
        assertEquals(110, moved[6].vel); assertTrue(moved[6].accent); assertEquals(50, moved[6].chance)   // its values go along
        assertTrue(moved[2].empty)
        val longer = Roll.resizeNote(moved, len, Note(6, 2, 67), 5, true)!!
        assertEquals(listOf(Note(6, 5, 67)), longer.roll())
        val shorter = Roll.resizeNote(longer, len, Note(6, 5, 67), 1, true)!!
        assertEquals(listOf(Note(6, 1, 67)), shorter.roll())
        assertEquals(StepTime.REST, shorter[7].time)
        assertNull(Roll.resizeNote(shorter, len, Note(6, 1, 67), 20, true))      // past LEN
        assertNull(Roll.moveNote(shorter, len, Note(6, 1, 67), 16, 67, true))
    }

    @Test fun fourNotesAtMostAndNoPitchTwice() {
        var s = empty()
        for (p in listOf(60, 62, 64, 65)) s = Roll.addNote(s, len, Note(0, 1, p), true)!!
        assertEquals(4, s[0].n)
        assertNull(Roll.addNote(s, len, Note(0, 1, 67), true))                    // a fifth: no room
        assertNull(Roll.addNote(s, len, Note(0, 2, 60), true))                    // 60 is there already
        val t = Roll.addNote(empty(), len, Note(0, 4, 60), true)!!
        assertNull(Roll.addNote(t, len, Note(2, 1, 60), true))                    // a pitch can't overlap itself
    }

    @Test fun aSingleVoiceTakesOver() {
        val s = Roll.addNote(empty(), len, Note(0, 4, 60), true)!!
        val t = Roll.addNote(s, len, Note(2, 1, 64), poly = false)!!
        assertEquals(listOf(Note(0, 2, 60), Note(2, 1, 64)), t.roll())          // the first is cut where the new one starts
        val u = Roll.addNote(t, len, Note(0, 3, 67), poly = false)!!
        assertEquals(listOf(Note(0, 3, 67)), u.roll())                           // covered notes are taken out
        val other = Roll.addNote(u, len, Note(3, 2, 72), poly = false)!!
        assertNull(Roll.moveNote(other, len, Note(3, 2, 72), 2, 72, false))       // a move onto another note: refused
    }

    @Test fun ratchetAndFlamStepsNeverTieOut() {
        var s = Roll.addNote(empty(), len, Note(0, 2, 60), true)!!
        s = s.toMutableList().also { it[0] = it[0].copy(ratchet = 3) }
        // step 0 ratchets: its note does not go on into step 1, which is then not a held TIE
        val w = Roll.unfoldAll(s, len)
        assertEquals(0, w[1].n)
    }

    @Test fun drumGrid() {
        var s = Step()
        s = Roll.toggleLane(s, 0)
        assertEquals(1, s.hit); assertEquals(StepTime.NOTE, s.time)
        s = Roll.toggleLane(s, 3)
        assertEquals(0b1001, s.hit)
        s = Roll.toggleLaneAccent(s, 3)
        assertEquals(0b1000, Lanes.accents(s))
        s = Roll.toggleLaneAccent(s, 3)
        assertEquals(0, Lanes.accents(s))
        s = Roll.toggleLane(Roll.toggleLane(s, 0), 3)
        assertTrue(s.empty); assertEquals(StepTime.REST, s.time)
        // a GM note on a lane is that lane's hit: 38 strikes the SNARE, taking it off removes the note
        val withNote = Step(n = 1, notes = listOf(38, 0, 0, 0), time = StepTime.NOTE)
        assertEquals(0b10, Lanes.lanes(withNote))
        assertTrue(Roll.toggleLane(withNote, 1).empty)
    }

    @Test fun stepWireRoundTrip() {
        val s = Step(n = 2, notes = listOf(60, 64, 0, 0), time = StepTime.NOTE, flags = 3, vel = 101, hit = 0x81, acc = 0x80,
            chance = 75, ratchet = 1, flam = true, cond = 5, nudge = -7, ties = 0)
        val a = StepWire.encode(s)
        assertEquals(17, a.size)
        assertEquals(listOf(2, 60, 64, 0, 0, 0, 3, 101, 0x01, 0x00, 0b11, 75, 1, 1, 5, 25, 0), a.toList())
        assertEquals(s, StepWire.decode(Reader(a)))
        // a tied note of a step without hits carries its tie bit; with hits the bits are ignored
        val t = Step(n = 2, notes = listOf(60, 64, 0, 0), time = StepTime.NOTE, ties = 0b01)
        assertEquals(1, StepWire.encode(t).last())
        assertEquals(0, StepWire.encode(t.copy(hit = 1)).last())
        assertEquals(0, StepWire.encode(t.copy(time = StepTime.TIE)).last())
        assertNotNull(StepWire.decode(Reader(a.copyOf(8))))      // an older device: the short step
    }
}
