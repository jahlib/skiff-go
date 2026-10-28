// SPDX-License-Identifier: GPL-3.0-only
package com.skiff.go.device

import com.skiff.go.proto.Transport
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayheadTest {
    private fun t(playing: Boolean, pending: Int = 0) = Transport(playing, false, pending, 0, 0)

    @Test fun stoppedHasNoPosition() {
        val p = Playhead()
        assertEquals(-1f, p.position(0, 16, "1/16", 0), 0f)
    }

    @Test fun forwardFromAnAppPlay() {
        val p = Playhead()
        p.onTransport(t(true), 120, 10_000, startedHere = true)
        // 120 BPM: a beat is 500 ms, a 1/16 step 125 ms; the start is taken 15 ms before the answer
        assertEquals(0.12f, p.position(10_000, 16, "1/16", 0), 0.01f)
        assertEquals(4.12f, p.position(10_500, 16, "1/16", 0), 0.01f)
        assertEquals(0.12f, p.position(10_000 + 16 * 125, 16, "1/16", 0), 0.01f)       // it wraps at LEN
        assertEquals(2.12f + 0f, p.position(10_000 + 250, 16, "1/16", 0), 0.01f)
    }

    @Test fun reverseAndPingPong() {
        val p = Playhead()
        p.onTransport(t(true), 120, 0, startedHere = true)
        p.onTransport(t(true), 120, 1)                       // (a later poll: no restart)
        assertEquals(16f - 0.1f, p.position(0, 16, "1/16", 1), 0.2f)
        // PING at LEN 4 goes 0 1 2 3 2 1 0 ...
        assertEquals(3.0f, p.position(3 * 125 - 15L, 4, "1/16", 2), 0.01f)
        assertEquals(2.0f, p.position(4 * 125 - 15L, 4, "1/16", 2), 0.01f)
        assertEquals(-1f, p.position(100, 16, "1/16", 3), 0f)                      // RAND: not computable
    }

    @Test fun aTempoChangeKeepsTheBeatsSoFar() {
        val p = Playhead()
        p.onTransport(t(true), 120, 0, startedHere = true)       // start at -15 ms
        p.setBpm(60.0, 985)                                    // 1000 ms in: two beats so far
        assertEquals(8f, p.position(985, 16, "1/16", 0), 0.01f)
        assertEquals(12f, p.position(1985, 16, "1/16", 0), 0.01f)   // now a beat takes a second: 4 steps
    }

    @Test fun triplesAndBars() {
        val p = Playhead()
        p.onTransport(t(true), 120, 0, startedHere = true)
        assertEquals(3f, p.position(500 - 15L, 16, "8T", 0), 0.01f)             // an eighth triplet is a third of a beat
        assertEquals(0.5f, p.position(2000 - 15L, 16, "2BAR", 0), 0.01f)      // 2 BAR: a step is 8 beats = 4 s
    }

    @Test fun restartGoesBackToTheTop() {
        val p = Playhead()
        p.onTransport(t(true), 120, 0, startedHere = true)
        p.onTransport(t(true, pending = 3), 120, 2000)
        assertEquals(0f, p.position(2000, 16, "1/16", 0), 0.01f)
    }
}
