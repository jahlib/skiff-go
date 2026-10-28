// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Skiff Go contributors. Based on the Skiff firmware (illuminus5) and Felucca (Hügelton Instruments);
// see NOTICE.md.
package com.skiff.go.device

import com.skiff.go.proto.Format
import com.skiff.go.proto.Transport

/**
 * Skiff's protocol has no playhead: TRANSPORT says only that the transport runs. So the position is computed, as the
 * firmware would: from the moment play started, the tempo (G_BPM), the track's DIV (a step's length), LEN and DIR.
 *
 * The start is known to a few ms when the app pressed PLAY, and to the poll's resolution (a few hundred ms) when it
 * was pressed on the FM-1; swing, launches, the song's jumps and a RAND or PING direction are not followed. A tempo
 * change is taken from where the beats had got to. It is a guide for the eye, not a clock.
 */
class Playhead {
    private var running = false
    private var baseMs = 0L
    private var baseBeats = 0.0
    private var bpm = 120.0

    @Synchronized fun reset() { running = false }

    /** The state as a poll or an answer to PLAY / STOP saw it at [nowMs]; [startedHere]: the app's own PLAY. */
    @Synchronized fun onTransport(t: Transport, tempo: Int?, nowMs: Long, startedHere: Boolean = false, pollMs: Long = 250) {
        if (tempo != null && tempo > 0) setBpm(tempo.toDouble(), nowMs)
        val on = t.running
        if (on && !running) {
            // from the top: the start was somewhere in the last poll's gap when the device started it, now when we did
            baseBeats = 0.0
            baseMs = nowMs - if (startedHere || t.pending != 0) 15 else pollMs / 2
        }
        if (t.pending == 3) { baseBeats = 0.0; baseMs = nowMs }     // RESTART: from the top
        running = on
    }

    @Synchronized fun setBpm(tempo: Double, nowMs: Long) {
        if (tempo == bpm) return
        if (running) { baseBeats = beatsAt(nowMs); baseMs = nowMs }
        bpm = tempo
    }

    private fun beatsAt(nowMs: Long) = baseBeats + (nowMs - baseMs) * bpm / 60000.0

    /**
     * The playhead in steps (fractions included) of a track with [len] steps of [div] (the DIV value's name) and [dir]
     * (0 FWD, 1 RVRS, 2 PING); -1 when stopped or not computable (RAND).
     */
    @Synchronized fun position(nowMs: Long, len: Int, divName: String?, dir: Int): Float {
        if (!running || len < 1) return -1f
        val bars = divName?.let { Format.divLength(it) } ?: return -1f
        val steps = beatsAt(nowMs) / (4 * bars)
        if (steps < 0) return -1f
        return when (dir) {
            0 -> (steps % len).toFloat()
            1 -> (len - (steps % len)).toFloat()               // the line runs left through each cell
            2 -> if (len < 2) 0f else {
                val period = 2.0 * (len - 1)
                val m = steps % period
                (if (m < len) m else period - m).toFloat()
            }
            else -> -1f
        }
    }
}
