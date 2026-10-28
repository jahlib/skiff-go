// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Skiff Go contributors. Based on the Skiff firmware (illuminus5) and Felucca (Hügelton Instruments);
// see NOTICE.md.
package com.skiff.go.proto

/** A request: its command and argument bytes. */
class Req(val cmd: Int, val args: IntArray) {
    override fun equals(other: Any?) = other is Req && cmd == other.cmd && args.contentEquals(other.args)
    override fun hashCode() = 31 * cmd + args.contentHashCode()
}

object Requests {
    fun info() = Req(Cmd.INFO, IntArray(0))
    fun ping() = Req(Cmd.PING, IntArray(0))
    /** 3: the pushes plus TRACK_CHANGED for the tracks that are not selected (v4). */
    fun watch(on: Int) = Req(Cmd.WATCH, intArrayOf(if (on == 3) 3 else if (on != 0) 1 else 0))
    fun track(select: Int? = null) = Req(Cmd.TRACK, if (select == null) IntArray(0) else intArrayOf(select and 0x7F))
    /** No op: the state (the editor's keep-alive); 1 PLAY, 2 STOP, 3 RESTART, 4 REC (arm / disarm, as the button). */
    fun transport(op: Int = 0) = Req(Cmd.TRANSPORT, if (op == 0) IntArray(0) else intArrayOf(op))

    fun get(scope: Int, id: Int) = Req(Cmd.GET, Wide.out(scope, id))
    fun set(scope: Int, id: Int, v: Int) = Req(Cmd.SET, Wide.out(scope, id) + v14enc(v))
    fun desc(scope: Int, id: Int) = Req(Cmd.DESC, Wide.out(scope, id))
    fun dump() = Req(Cmd.DUMP, IntArray(0))
    fun names(engine: Int) = Req(Cmd.NAMES, intArrayOf(engine and 0x7F))
    /** Loads the preset's sound onto the selected track (its steps and own parameters stay). */
    fun preset(engine: Int, preset: Int) = Req(Cmd.PRESET, intArrayOf(engine and 0x7F, preset and 0x7F))

    fun trackDump(track: Int) = Req(Cmd.TRACK_DUMP, intArrayOf(track and 0x7F))
    fun trackStepGet(track: Int, index: Int) = Req(Cmd.TRACK_STEP, intArrayOf(track and 0x7F, index and 0x7F))
    /** Writes a step of any track (its 17 bytes) and answers with it as the device keeps it. */
    fun trackStepSet(track: Int, index: Int, s: Step) =
        Req(Cmd.TRACK_STEP, intArrayOf(track and 0x7F, index and 0x7F) + StepWire.encode(s))

    /** Get (level null), or set the track's P_LEVEL and P_MUTE. */
    fun trackMix(track: Int, level: Int? = null, mute: Boolean = false) =
        Req(Cmd.TRACK_MIX, if (level == null) intArrayOf(track and 0x7F) else intArrayOf(track and 0x7F) + v14enc(level) + intArrayOf(if (mute) 1 else 0))
    /** Get (v null) or set parameter [id] of any track; the selection stays. Bit 6 of the track byte: the id's bit 7. */
    fun trackParam(track: Int, id: Int, v: Int? = null): Req {
        val head = intArrayOf((track and 63) or ((id shr 7 and 1) shl 6), id and 0x7F)
        return Req(Cmd.TRACK_PARAM, if (v == null) head else head + v14enc(v))
    }

    /** An FM6 patch: target 0 a track's own (index = the track), 2 a factory patch (GET only), 3 a user preset's. */
    fun fm6Put(target: Int, index: Int, packed: IntArray) =
        Req(Cmd.FM6_PUT, intArrayOf(target and 0x7F, index and 0x7F) + packed.map { it and 0x7F })

    object Op {
        const val PLAY = 1
        const val STOP = 2
        const val RESTART = 3
        const val REC = 4
    }
}

/**
 * Whether reply [a] answers request (cmd, [args]): the same cmd, and the bytes that name what was asked for (editor.html
 * replyMatches). A late reply to a request that timed out then never completes the next one.
 */
fun replyMatches(cmd: Int, args: IntArray, a: IntArray): Boolean {
    fun at(x: IntArray, i: Int) = x.getOrElse(i) { -1 }
    return when (cmd) {
        Cmd.MOTION, Cmd.MENU_DESC, Cmd.KIT_GET, Cmd.KIT_PUT, Cmd.STEP_GET, Cmd.STEP_SET, Cmd.NAMES, Cmd.SMP_BEGIN,
        Cmd.SMP_END, Cmd.SMP_ERASE, Cmd.SMP_MIX, Cmd.SONG, Cmd.FM6_ERASE, Cmd.UP_LIST, Cmd.UP_GET, Cmd.UP_PUT,
        Cmd.UP_STORE, Cmd.UP_LOAD, Cmd.UP_ERASE, Cmd.TRACK_MIX, Cmd.TRACK_DUMP -> at(a, 0) == at(args, 0)
        Cmd.MENU_SET -> at(a, 1) == at(args, 0)
        Cmd.PROJ_NAME, Cmd.POOL, Cmd.LIST, Cmd.LIST2, Cmd.PERFORM -> at(a, 0) == (args.getOrNull(0) ?: 0)
        Cmd.BACKUP_GET -> at(a, 0) == at(args, 0) && (0 until 5).all { at(a, 2 + it) == at(args, 1 + it) }
        Cmd.BACKUP_PUT -> at(a, 0) == at(args, 0) && at(a, 1) == at(args, 1)
        Cmd.UI_SET -> at(a, 1) == at(args, 0) && at(a, 2) == at(args, 1)
        Cmd.FAV_GET, Cmd.FAV_SET -> at(a, 0) !in setOf(0, 3, 4) ||
            (at(a, 1) == at(args, 0) && at(a, 2) == at(args, 1) && at(a, 3) == at(args, 2) && at(a, 4) == at(args, 3))
        Cmd.GET, Cmd.SET, Cmd.DESC, Cmd.FM6_GET, Cmd.FM6_PUT, Cmd.PROJECT, Cmd.TRACK_STEP, Cmd.TRACK_PARAM ->
            at(a, 0) == at(args, 0) && at(a, 1) == at(args, 1)
        Cmd.SMP_WRITE -> (0 until 4).all { at(a, it) == at(args, it) }
        Cmd.WATCH -> (at(a, 0) and 1) == (at(args, 0) and 1)
        else -> true
    }
}
