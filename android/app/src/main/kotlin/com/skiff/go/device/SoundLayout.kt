// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Skiff Go contributors. Based on the Skiff firmware (illuminus5) and Felucca (Hügelton Instruments);
// see NOTICE.md.
package com.skiff.go.device

import com.skiff.go.proto.Info

/** A page of a group: its title, scope (0 the track's P_*, 1 a global G_*) and the parameter ids it shows. */
data class LayoutPage(val title: String, val scope: Int, val ids: List<Int>)

/**
 * A group of pages as the device and the web editor show them. [engine]: the EDIT group (its title names the engine and
 * its pages take the engine's titles from NAMES). [mod]: the MOD matrix, four rows of SRC, DST, AMT from that id.
 * [where]: "sound", "pattern" (the sequencer's settings) or "global".
 */
data class LayoutGroup(val title: String, val pages: List<LayoutPage> = emptyList(), val engine: Boolean = false,
                       val mod: Int? = null, val where: String = "sound")

/** Parameter ids (firmware core.h), as the web editor keeps them (editor.html, "P_*"). */
object Pid {
    const val LEVEL = 0
    const val SLEN = 29
    const val SDIV = 30
    const val SSWING = 31
    const val SGATE = 32
    const val VOICE = 37
    const val REV = 36
    const val PAN = 39
    const val MUTE = 40
    const val MOD = 49
    val CHORD = listOf(81, 82)
    val LANES = (83..90).toList()
    const val DIR = 91
    val DUCK = listOf(92, 93, 94)
    val DRIVE = listOf(95, 96)
    val ENV2 = listOf(97, 98, 99, 100)
    val LTUNE = (101..108).toList()
    val LDECAY = (109..116).toList()
    val LFO2 = listOf(117, 118, 119)
    const val SPRD = 120
    val INSERT = listOf(121, 122, 123, 124)
    const val IMIX = 125
    const val ESYNC = 126
    val LPAN = (127..134).toList()

    /** The track's own parameters, which no sound load changes (ui.c param_kept; editor.html trackOwn). */
    private val TRACK_OWN = setOf(0, 39, 40) + (17..32) + (45..48)
    fun trackOwn(i: Int, pe0: Int): Boolean = i in TRACK_OWN || (pe0 >= 83 && i in CHORD) || (pe0 >= 92 && i == DIR) ||
        (pe0 >= 95 && i in DUCK)
}

object SoundLayout {
    /** Globals that are actions or placeholders on the device: never shown as controls (editor.html G_SKIP). */
    val G_SKIP = setOf("SLOT", "NAME", "LOAD", "SAVE", "ENG", "SET", "CLRSQ", "INIT", "MIDI", "SYNC", "CPU", "CLK")

    /** The firmwares whose ids [layout] knows (editor.html knownLayout); another one gets every parameter in id order. */
    fun known(info: Info): Boolean = info.gcount == 27 && listOf(143 to 135, 135 to 127, 125 to 117, 103 to 95, 100 to 92,
        99 to 91, 91 to 83, 89 to 81, 69 to 61, 57 to 49).any { (c, e) -> info.pcount == c && info.pe0 == e }

    /** editor.html LAYOUT(pe0) for the engine named [eng]. */
    fun layout(pe0: Int, eng: String): List<LayoutGroup> {
        val kit = eng == "DRUM" || eng == "DRUM-X"
        val g = ArrayList<LayoutGroup>()
        g += LayoutGroup("ENV", buildList {
            add(LayoutPage("ENV", 0, listOf(1, 2, 3, 4)))
            add(LayoutPage("ENV DEST", 0, if (pe0 >= 127) listOf(5, 6, 7, Pid.ESYNC) else listOf(5, 6, 7)))
            if (pe0 >= 117) add(LayoutPage("ENV 2", 0, Pid.ENV2))
        })
        g += LayoutGroup("LFO", buildList {
            add(LayoutPage("LFO", 0, listOf(9, 10, 11, 12)))
            if (pe0 >= 127) add(LayoutPage("LFO 2", 0, Pid.LFO2))
            add(LayoutPage("LFO DEST", 0, listOf(13, 14, 15, 16)))
        })
        if (pe0 >= 61) g += LayoutGroup("MOD", mod = Pid.MOD)
        g += LayoutGroup("EDIT", engine = true, pages = buildList {
            add(LayoutPage("EDIT 1", 0, (pe0..pe0 + 3).toList()))
            add(LayoutPage("EDIT 2", 0, (pe0 + 4..pe0 + 7).toList()))
            if (pe0 >= 91 && kit) { add(LayoutPage("LANES", 0, Pid.LANES.take(4))); add(LayoutPage("LANES 2", 0, Pid.LANES.drop(4))) }
            if (pe0 >= 135 && kit) { add(LayoutPage("PAN", 0, Pid.LPAN.take(4))); add(LayoutPage("PAN 2", 0, Pid.LPAN.drop(4))) }
            if (pe0 >= 117 && eng == "DRUM") {
                add(LayoutPage("TUNE", 0, Pid.LTUNE.take(4))); add(LayoutPage("TUNE 2", 0, Pid.LTUNE.drop(4)))
                add(LayoutPage("DECAY", 0, Pid.LDECAY.take(4))); add(LayoutPage("DECAY 2", 0, Pid.LDECAY.drop(4)))
            }
        })
        if (pe0 >= 81 && eng == "DIGITAL") g += LayoutGroup("OP ENV", buildList {
            for (k in 0..3) add(LayoutPage("OP${k + 1}", 0, listOf(61 + 5 * k, 62 + 5 * k, 63 + 5 * k, 64 + 5 * k)))
            add(LayoutPage("OP LVL", 0, listOf(65, 70, 75, 80)))
        })
        g += LayoutGroup("VOICE", buildList {
            add(LayoutPage("LEVEL", 0, listOf(0)))
            add(LayoutPage("VOICE", 0, listOf(37, 38, 41, 42)))
            add(LayoutPage("VOICE 2", 0, listOf(43, 44, 39, 40)))
            if (pe0 >= 127) add(LayoutPage("SPREAD", 0, listOf(Pid.SPRD)))
        })
        g += LayoutGroup("FX", buildList {
            add(LayoutPage("FX", 0, listOf(33, 34, 35, 36)))
            if (pe0 >= 117) add(LayoutPage("DRIVE", 0, listOf(33) + Pid.DRIVE))
            if (pe0 >= 127) { add(LayoutPage("INSERT", 0, Pid.INSERT)); add(LayoutPage("INSERT 2", 0, listOf(Pid.IMIX))) }
            add(LayoutPage("SLICER", 0, listOf(45, 46, 47, 48)))
            if (pe0 >= 95) add(LayoutPage("DUCK", 0, Pid.DUCK))
            add(LayoutPage("DLY", 1, listOf(4, 5, 6, 7)))
            add(LayoutPage("REVERB", 1, listOf(24, 8, 9)))
            add(LayoutPage("CHORUS", 1, if (pe0 >= 117) listOf(10, 11, 25, 26) else listOf(10, 11)))
        })
        g += LayoutGroup("SCALE", buildList {
            add(LayoutPage("SCALE", 0, listOf(25, 26, 27, 28)))
            if (pe0 >= 83) add(LayoutPage("CHORD", 0, Pid.CHORD))
        })
        g += LayoutGroup("ARP", listOf(LayoutPage("ARP", 0, listOf(17, 18, 19, 20)), LayoutPage("ARP 2", 0, listOf(21, 22, 23, 24))))
        g += LayoutGroup("SEQ PATTERN", buildList {
            add(LayoutPage("PATTERN", 0, listOf(29, 30, 31, 32)))
            if (pe0 >= 92) add(LayoutPage("PLAY", 0, listOf(Pid.DIR)))
        }, where = "pattern")
        g += LayoutGroup("GLOBAL", listOf(LayoutPage("GLOBAL", 1, listOf(0, 1, 2, 3)), LayoutPage("SYSTEM", 1, listOf(14))),
            where = "global")
        return g
    }

    /** For a firmware [known] does not cover: every track parameter, then the engine's, then the globals, in id order. */
    fun fallback(info: Info): List<LayoutGroup> = listOf(
        LayoutGroup("INSTRUMENT", listOf(LayoutPage("INSTRUMENT", 0, (0 until info.pe0).toList()))),
        LayoutGroup("EDIT", listOf(LayoutPage("EDIT", 0, (info.pe0 until info.pcount).toList())), engine = true),
        LayoutGroup("GLOBAL", listOf(LayoutPage("GLOBAL", 1, (0 until info.gcount).toList())), where = "global"),
    )
}
