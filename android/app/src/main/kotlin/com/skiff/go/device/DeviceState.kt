// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Skiff Go contributors. Based on the Skiff firmware (illuminus5) and Felucca (Hügelton Instruments);
// see NOTICE.md.
package com.skiff.go.device

import com.skiff.go.proto.Desc
import com.skiff.go.proto.Dump
import com.skiff.go.proto.Info
import com.skiff.go.proto.Step
import com.skiff.go.proto.Tracks
import com.skiff.go.proto.Transport

enum class Link { DISCONNECTED, CONNECTING, LOADING, READY, WRONG_FIRMWARE }

/** A mixer strip: level and mute come with TRACK, pan and reverb send from TRACK_DUMP / pushes (null: not read). */
data class Strip(val level: Int, val mute: Boolean, val pan: Int?, val rev: Int?)

data class DeviceState(
    val link: Link = Link.DISCONNECTED,
    val status: String = "Not connected",
    val deviceName: String? = null,
    val info: Info? = null,
    val tracks: Tracks? = null,
    val transport: Transport? = null,
    /** WATCH's reply: 0 off, 1 the pushes, 3 also TRACK_CHANGED. */
    val watch: Int = 0,
    /** The last keep-alive's round trip (ms), -1 unknown. */
    val rttMs: Int = -1,
    val error: String? = null,
    /** Reading the descriptions at connect: done, of. */
    val progress: Pair<Int, Int>? = null,
    val pdesc: List<Desc?> = emptyList(),
    val gdesc: List<Desc?> = emptyList(),
    /** Per engine: its preset names, and the titles of its two EDIT pages. */
    val names: List<List<String>> = emptyList(),
    val titles: List<List<String>> = emptyList(),
    /** The selected track's sound (engine, preset, parameters) and the globals. */
    val dump: Dump? = null,
    val strips: List<Strip> = emptyList(),
    /** A short message about the last action (a load, a file, a refusal). */
    val notice: String? = null,
    /** The selected track's 64 steps ([stepsTrack] says whose; empty while they are read), and how many are read. */
    val steps: List<Step> = emptyList(),
    val stepsTrack: Int = -1,
    val stepsRead: Int = 0,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
) {
    val selectedTrack: Int get() = tracks?.selected ?: transport?.selected ?: 0
    val engineName: String get() = dump?.let { info?.engines?.getOrNull(it.engine) } ?: ""
    val presetName: String? get() = dump?.let { names.getOrNull(it.engine)?.getOrNull(it.preset) }

    /** The pattern's settings (core.h): LEN, DIV, direction; the voice mode (POLY lets notes overlap). */
    val patternLength: Int get() = (value(0, Pid.SLEN) ?: 16).coerceIn(1, Step.NSTEP)
    val divName: String? get() = desc(0, Pid.SDIV)?.let { d -> value(0, Pid.SDIV)?.let { d.names.getOrNull(it - d.min) } }
    val direction: Int get() = value(0, Pid.DIR) ?: 0
    val tempo: Int? get() = value(1, 0)?.takeIf { gdesc.getOrNull(0)?.label == "BPM" }
    val poly: Boolean get() = (value(0, Pid.VOICE) ?: 0) == 0
    val drumTrack: Boolean get() = engineName == "DRUM" || engineName == "DRUM-X"
    val stepsReady: Boolean get() = stepsTrack == selectedTrack && steps.size == Step.NSTEP

    fun desc(scope: Int, id: Int): Desc? = (if (scope == 0) pdesc else gdesc).getOrNull(id)
    fun value(scope: Int, id: Int): Int? = dump?.let { (if (scope == 0) it.p else it.g).getOrNull(id) }
}
