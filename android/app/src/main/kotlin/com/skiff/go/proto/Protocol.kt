// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Skiff Go contributors. Based on the Skiff firmware (illuminus5) and Felucca (Hügelton Instruments);
// see NOTICE.md.
package com.skiff.go.proto

/**
 * The Felucca editor protocol as Skiff speaks it (SysEx over USB-MIDI): firmware/src/editor.c and
 * web/EDITOR_PROTOCOL.md of https://github.com/illuminus5/skiff.
 *
 * A request is F0 7D 46 4C <cmd> <args...> F7. Every arg byte is 7-bit (0..127). Values are
 * 14-bit, LSB first, biased by 8192 (range -8192..8191). Strings are ASCII ended by a 0 byte.
 * Every valid request gets exactly one reply with the same cmd; the device holds one incoming
 * frame at a time, so requests go out one by one.
 */
object Proto {
    val HDR = intArrayOf(0x7D, 0x46, 0x4C) // non-commercial SysEx id + "FL"
    const val SOX = 0xF0
    const val EOX = 0xF7
}

/**
 * Skiff's command numbers (editor.c). 1..32 are Felucca's and match SLOOP; from 33 on they differ from
 * SLOOP's and some numbers mean other things there, so a Skiff editor must never talk to SLOOP (see [Info.isSkiff]).
 */
object Cmd {
    const val INFO = 1
    const val GET = 2
    const val SET = 3
    const val DUMP = 4
    const val DESC = 5
    const val STEP_GET = 6
    const val STEP_SET = 7
    const val PRESET = 8
    const val PROJECT = 9
    const val NAMES = 10
    const val SMP_BEGIN = 11
    const val SMP_WRITE = 12
    const val SMP_END = 13
    const val SMP_ERASE = 14
    const val SMP_INFO = 15
    const val UP_LIST = 16
    const val UP_GET = 17
    const val UP_PUT = 18
    const val UP_STORE = 19
    const val UP_LOAD = 20
    const val UP_ERASE = 21
    const val WATCH = 22
    const val CHANGED = 23        // push
    const val RELOAD = 24         // push
    const val PING = 25
    const val STEP_CHANGED = 26   // push
    const val TRACK = 27
    const val TRACK_MIX = 28
    const val TRACK_DUMP = 29
    const val TRACK_STEP = 30
    const val TRACK_PARAM = 31
    const val TRACK_CHANGED = 32  // push
    const val SONG = 33           // Felucca's project-slot song; always empty on Skiff
    const val UI_STATE = 34
    const val UI_SET = 35
    const val UI_PALETTES = 36
    const val FAV_GET = 37
    const val FAV_SET = 38
    const val COLORS = 39
    const val MOTION = 64         // automation events and parameter locks
    const val BACKUP_LIST = 65
    const val BACKUP_GET = 66
    const val BACKUP_PUT = 67
    const val FM6_GET = 68
    const val FM6_PUT = 69
    const val FM6_LIST = 70
    const val FM6_ERASE = 71
    const val MENU_DESC = 72
    const val MENU_SET = 73
    const val KIT_GET = 74        // the DRUM-X kit
    const val KIT_PUT = 75
    const val TRANSPORT = 76
    const val PROJ_NAME = 77
    const val POOL = 78
    const val LIST = 79
    const val PERFORM = 80
    const val SMP_MIX = 81
    const val LIST2 = 82

    /** Frames the device sends on its own while WATCH is on; never replies. */
    val PUSH = setOf(CHANGED, RELOAD, STEP_CHANGED, TRACK_CHANGED)
}

/** Parameter display formats (firmware core.h). */
object Fmt {
    const val INT = 0
    const val PCT = 1
    const val BIPCT = 2
    const val TIME = 3
    const val LFOHZ = 4
    const val CUTOFF = 5
    const val DB = 6
    const val SEMI = 7
    const val ENUM = 8
    const val BPM = 9
    const val NOTE = 10
    const val ONOFF = 11
    const val OCT = 12
    const val STEPS = 13
    const val ST16 = 14
}

/** Step note timing. */
object StepTime {
    const val NOTE = 0
    const val TIE = 1
    const val REST = 2
}

/** A decoded editor frame: command + its 7-bit argument bytes. */
data class Frame(val cmd: Int, val args: IntArray) {
    override fun equals(other: Any?): Boolean =
        other is Frame && cmd == other.cmd && args.contentEquals(other.args)
    override fun hashCode(): Int = 31 * cmd + args.contentHashCode()
}

/** 14-bit value, LSB first, biased by 8192. */
fun v14enc(v: Int): IntArray {
    val u = v.coerceIn(-8192, 8191) + 8192
    return intArrayOf(u and 0x7F, (u shr 7) and 0x7F)
}

fun v14dec(lo: Int, hi: Int): Int = (lo or (hi shl 7)) - 8192

/** ASCII bytes ended by a 0. */
fun strEnc(s: String): IntArray {
    val out = ArrayList<Int>(s.length + 1)
    for (c in s) out.add(c.code and 0x7F)
    out.add(0)
    return out.toIntArray()
}

/** Builds F0 7D 46 4C <cmd> <args...> F7. Throws on a non-7-bit arg. */
fun frame(cmd: Int, args: IntArray = IntArray(0)): ByteArray {
    val out = ByteArray(args.size + 6)
    out[0] = Proto.SOX.toByte()
    out[1] = Proto.HDR[0].toByte()
    out[2] = Proto.HDR[1].toByte()
    out[3] = Proto.HDR[2].toByte()
    out[4] = (cmd and 0x7F).toByte()
    for (i in args.indices) {
        val b = args[i]
        require(b in 0..0x7F) { "bad data byte $b" }
        out[5 + i] = b.toByte()
    }
    out[out.size - 1] = Proto.EOX.toByte()
    return out
}

/** Parses a raw MIDI message into a Frame, or null when it is not an editor frame. */
fun unframe(d: ByteArray): Frame? {
    if (d.size < 6) return null
    if ((d[0].toInt() and 0xFF) != Proto.SOX) return null
    if ((d[d.size - 1].toInt() and 0xFF) != Proto.EOX) return null
    if ((d[1].toInt() and 0xFF) != Proto.HDR[0] ||
        (d[2].toInt() and 0xFF) != Proto.HDR[1] ||
        (d[3].toInt() and 0xFF) != Proto.HDR[2]
    ) return null
    val cmd = d[4].toInt() and 0xFF
    val args = IntArray(d.size - 6)
    for (i in args.indices) args[i] = d[5 + i].toInt() and 0xFF
    return Frame(cmd, args)
}

/** Sequential reader over an argument array: bytes, 14-bit values and 0-ended strings. */
class Reader(private val a: IntArray) {
    var i = 0
        private set

    val remaining: Boolean get() = i < a.size
    val left: Int get() = a.size - i

    fun b(): Int {
        if (i >= a.size) throw IllegalStateException("short reply")
        return a[i++]
    }

    fun v(): Int {
        val lo = b()
        return v14dec(lo, b())
    }

    fun s(): String {
        val sb = StringBuilder()
        while (true) {
            val c = b()
            if (c == 0) return sb.toString()
            sb.append(c.toChar())
        }
    }
}
