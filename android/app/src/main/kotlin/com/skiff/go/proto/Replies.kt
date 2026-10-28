// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Skiff Go contributors. Based on the Skiff firmware (illuminus5) and Felucca (Hügelton Instruments);
// see NOTICE.md.
package com.skiff.go.proto

/**
 * INFO (cmd 1), editor.c ED_INFO: the version, the counts, the engine names, NTRK, CHAIN_ROWS, then tagged blocks
 * (tag, version 1, the tag's bytes). The plain P_COUNT / P_E0 bytes stop at 127; tag 49 has them whole.
 */
data class Info(
    val version: String,
    val nengines: Int,
    val pcount: Int,
    val gcount: Int,
    val nstep: Int,
    val pe0: Int,
    val engines: List<String>,
    val ntrk: Int,
    /** 16 on Felucca and Skiff: the tags follow only then. SLOOP sends its protocol version here instead. */
    val chainRows: Int,
    /** tag -> its bytes, as the firmware sent them. */
    val tags: Map<Int, IntArray>,
) {
    private fun tag(t: Int) = tags[t]
    private fun byte(t: Int, i: Int = 0) = tag(t)?.getOrNull(i) ?: 0

    /** Skiff W3 on: TRANSPORT / PROJ_NAME and the other Skiff commands (tag 57). Felucca and SLOOP don't have it. */
    val isSkiff: Boolean get() = chainRows == 16 && tag(TAG_SKIFF) != null

    val uiCaps get() = byte(TAG_UI)
    val motionMax get() = byte(TAG_MOTION)
    val backupCaps get() = byte(TAG_BACKUP)
    val fm6Factory get() = byte(TAG_FM6)
    val liveSyncCaps get() = byte(TAG_LIVESYNC)
    val menuCount get() = byte(TAG_MENU)
    val ratchetMax get() = byte(TAG_RATCHET).takeIf { it > 0 } ?: 1
    val hasLocks get() = byte(TAG_LOCKS) != 0
    /** Tracks that can't play some engines (bit per track), and those engines (bit per engine). */
    val lightTracks get() = byte(TAG_LIGHT)
    val lightEngines get() = tag(TAG_LIGHT)?.let { it.getOrElse(1) { 0 } or (it.getOrElse(2) { 0 } shl 7) or (it.getOrElse(3) { 0 } shl 14) } ?: 0
    /** A step's FLAM, COND, NUDGE (Skiff W1): the number of trig conditions, 0 without them. */
    val trigConds get() = byte(TAG_STEPX)
    val kitLanes get() = byte(TAG_KIT)
    /** Tag 57: bit 0 TRANSPORT, 1 PROJ_NAME, 2 POOL, 3 LIST, 4 PERFORM, 5 SMP_MIX, 6 a step's per-note ties (2.0). */
    val skiffCaps get() = byte(TAG_SKIFF)
    /** Tag 58 (2.0): bit 0 LIST2 and the new list ops, bit 1 the entries' sounds. */
    val song2Caps get() = byte(TAG_SONG2)
    val hasTies get() = skiffCaps and CAP_TIES != 0

    fun engineAllowed(track: Int, engine: Int): Boolean =
        lightTracks and (1 shl track) == 0 || lightEngines and (1 shl engine) == 0

    companion object {
        const val TAG_UI = 0x55
        const val TAG_MOTION = 0x4D
        const val TAG_BACKUP = 0x42
        const val TAG_FM6 = 0x46
        const val TAG_LIVESYNC = 0x53
        const val TAG_FM6V2 = 0x50
        const val TAG_MENU = 0x4E
        const val TAG_RATCHET = 0x52
        const val TAG_LOCKS = 0x4C
        const val TAG_LIGHT = 0x54
        const val TAG_STEPX = 0x43
        const val TAG_KIT = 0x4B
        const val TAG_SKIFF = 0x57
        const val TAG_WIDE = 0x49
        const val TAG_SONG2 = 0x58

        const val CAP_TRANSPORT = 1
        const val CAP_PROJ_NAME = 2
        const val CAP_POOL = 4
        const val CAP_LIST = 8
        const val CAP_PERFORM = 16
        const val CAP_SMP_MIX = 32
        const val CAP_TIES = 64

        /** The bytes after each known tag's version byte. A tag not listed here ends the parse (its length is unknown). */
        val TAG_LEN = mapOf(
            TAG_UI to 1, TAG_MOTION to 2, TAG_BACKUP to 1, TAG_FM6 to 2, TAG_LIVESYNC to 1, TAG_FM6V2 to 1,
            TAG_MENU to 1, TAG_RATCHET to 1, TAG_LOCKS to 1, TAG_LIGHT to 4, TAG_STEPX to 1, TAG_KIT to 1,
            TAG_SKIFF to 1, TAG_WIDE to 4, TAG_SONG2 to 1,
        )
    }
}

/** TRANSPORT (cmd 76): the reply is sent before the audio takes the request, so read [playing] with [pending]. */
data class Transport(
    val playing: Boolean,
    val countingIn: Boolean,
    /** The request the audio has yet to take: 0 none, 1 start, 2 stop, 3 restart. */
    val pending: Int,
    /** Armed tracks, a bit per track. */
    val armed: Int,
    val selected: Int,
) {
    /** What the transport is about to be: a pending start or restart counts as playing, a pending stop as stopped. */
    val running: Boolean get() = when (pending) { 1, 3 -> true; 2 -> false; else -> playing }
}

/** A parameter's description (DESC). [scope] 0 is a track parameter by its whole id (wide ids folded back), 1 a global. */
data class Desc(
    val scope: Int,
    val id: Int,
    val fmt: Int,
    val min: Int,
    val max: Int,
    val def: Int,
    val label: String,
    val unit: String,
    val names: List<String>,
) {
    /** What the editor shows: a parameter with a range and a name ("-" marks a retired one). */
    val visible: Boolean get() = max > min && label != "-"
}

/** GET / SET replies and the CHANGED push: (scope, id) with wide ids folded back to scope 0. */
data class ParamValue(val scope: Int, val id: Int, val value: Int)

/** DUMP: the selected track's engine, preset, P_COUNT values, then G_COUNT globals. */
data class Dump(val engine: Int, val preset: Int, val p: IntArray, val g: IntArray) {
    override fun equals(other: Any?) = other is Dump && engine == other.engine && preset == other.preset &&
        p.contentEquals(other.p) && g.contentEquals(other.g)
    override fun hashCode() = (31 * engine + preset) * 31 + p.contentHashCode()
}

/** NAMES: an engine's preset names, then the titles of its two EDIT pages (firmware before them: none). */
data class Names(val engine: Int, val names: List<String>, val titles: List<String>)

data class TrackDump(val track: Int, val engine: Int, val preset: Int, val p: IntArray)
data class TrackMix(val track: Int, val level: Int, val mute: Boolean)
data class Preset(val engine: Int, val preset: Int)

data class TrackEntry(val engine: Int, val preset: Int, val level: Int, val mute: Boolean, val armed: Boolean)

/** TRACK (cmd 27): the selected track, NTRK, then per track its engine, preset, level, mute and armed state. */
data class Tracks(val selected: Int, val tracks: List<TrackEntry>)

data class Reload(val engine: Int, val preset: Int, val track: Int)

object Parse {
    fun info(a: IntArray): Info {
        val r = Reader(a)
        val version = r.s()
        val nengines = r.b()
        var pcount = r.b()
        val gcount = r.b()
        val nstep = r.b()
        var pe0 = r.b()
        val engines = List(nengines) { r.s() }
        val ntrk = if (r.remaining) r.b() else 0
        val chainRows = if (r.remaining) r.b() else 0
        val tags = LinkedHashMap<Int, IntArray>()
        if (chainRows == 16) {
            while (r.left >= 2) {
                val tag = r.b()
                r.b()                                    // the tag's version (1)
                val len = Info.TAG_LEN[tag] ?: break
                if (r.left < len) break
                tags[tag] = IntArray(len) { r.b() }
            }
        }
        tags[Info.TAG_WIDE]?.let { w ->
            pcount = w[0] or (w[1] shl 7)
            pe0 = w[2] or (w[3] shl 7)
        }
        return Info(version, nengines, pcount, gcount, nstep, pe0, engines, ntrk, chainRows, tags)
    }

    fun transport(a: IntArray): Transport {
        val r = Reader(a)
        r.b()                                            // rc (0)
        return Transport(r.b() != 0, r.b() != 0, r.b(), r.b(), r.b())
    }

    fun tracks(a: IntArray): Tracks {
        val r = Reader(a)
        val sel = r.b()
        val n = r.b()
        return Tracks(sel, List(n) { TrackEntry(r.b(), r.b(), r.v(), r.b() != 0, r.b() != 0) })
    }

    fun reload(a: IntArray): Reload {
        val r = Reader(a)
        val engine = r.b(); val preset = r.b()
        return Reload(engine, preset, if (r.remaining) r.b() else 0)
    }

    fun watch(a: IntArray): Int = Reader(a).b()

    /** GET, SET and the CHANGED push. */
    fun param(a: IntArray): ParamValue {
        val r = Reader(a)
        val (scope, id) = Wide.inn(r.b(), r.b())
        return ParamValue(scope, id, r.v())
    }

    fun desc(a: IntArray): Desc {
        val r = Reader(a)
        val (scope, id) = Wide.inn(r.b(), r.b())
        val fmt = r.b()
        val min = r.v(); val max = r.v(); val def = r.v()
        val label = r.s(); val unit = r.s()
        val names = ArrayList<String>()
        if (fmt == Fmt.ENUM) for (i in 0..(max - min)) { if (i >= 24 || !r.remaining) break; names.add(r.s()) }
        return Desc(scope, id, fmt, min, max, def, label, unit, names)
    }

    fun dump(a: IntArray, info: Info): Dump {
        val r = Reader(a)
        val engine = r.b(); val preset = r.b()
        return Dump(engine, preset, IntArray(info.pcount) { r.v() }, IntArray(info.gcount) { r.v() })
    }

    fun names(a: IntArray): Names {
        val r = Reader(a)
        val engine = r.b()
        val names = List(r.b()) { r.s() }
        val titles = runCatching { listOf(r.s(), r.s()) }.getOrDefault(emptyList())
        return Names(engine, names, titles)
    }

    fun preset(a: IntArray): Preset { val r = Reader(a); return Preset(r.b(), r.b()) }

    /** TRACK_STEP: the track, the index, then the step as STEP_GET has it. */
    fun trackStep(a: IntArray): StepAt {
        val r = Reader(a)
        val track = r.b(); val index = r.b()
        return StepAt(track, index, StepWire.decode(r))
    }

    /** STEP_CHANGED push: the index, then (v3) the selected track. */
    fun stepChanged(a: IntArray): Pair<Int, Int> { val r = Reader(a); return r.b() to (if (r.remaining) r.b() else 0) }

    fun trackDump(a: IntArray, info: Info): TrackDump {
        val r = Reader(a)
        val track = r.b(); val engine = r.b(); val preset = r.b()
        return TrackDump(track, engine, preset, IntArray(info.pcount) { r.v() })
    }

    fun trackMix(a: IntArray): TrackMix { val r = Reader(a); return TrackMix(r.b(), r.v(), r.b() != 0) }

    /** TRACK_PARAM and the TRACK_CHANGED push: bit 6 of the track byte is the id's bit 7 (Skiff 1.3). */
    fun trackParam(a: IntArray): ParamValue {
        val r = Reader(a)
        val t = r.b()
        val id = r.b() or ((t shr 6 and 1) shl 7)
        return ParamValue(t and 63, id, r.v())
    }
}

/**
 * Skiff 1.3's wide ids (EDITOR_PROTOCOL.md "Wide ids"): a track parameter from 128 on goes out as scope 2, id - 128 in
 * GET, SET, DESC and the CHANGED push; inside the app it is scope 0 and its whole id.
 */
object Wide {
    fun out(scope: Int, id: Int): IntArray = if (scope == 0 && id >= 128) intArrayOf(2, id - 128) else intArrayOf(scope, id)
    fun inn(scope: Int, id: Int): Pair<Int, Int> = if (scope == 2) 0 to id + 128 else scope to id
}
