// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Skiff Go contributors. Based on the Skiff firmware (illuminus5) and Felucca (Hügelton Instruments);
// see NOTICE.md.
package com.skiff.go.proto

import org.json.JSONArray
import org.json.JSONObject

/** A sound as the web editor's files keep it: an engine and its P_COUNT values (null: the file has none for that id). */
data class Patch(
    val name: String,
    val engine: Int,
    val engineName: String,
    val p: List<Int?>,
    val tags: List<String> = emptyList(),
    /** An FM6 sound's own 128-byte patch. */
    val fm6: IntArray? = null,
)

/** What a library file is read against: the device's parameter keys, engines, firmware and P_E0. */
data class LibraryContext(val keys: List<String>?, val engines: List<String>, val firmware: String, val pe0: Int?)

data class LibraryRead(val patches: List<Patch>, val skipped: Int)

/**
 * The web editor's sound files (editor.html "librarian"), so a sound moves between Skiff Go and the Skiff Editor:
 * - "felucca-patch": "Save to file", the whole current sound (engine, preset, p, g and the steps);
 * - "felucca-library": a library, patches whose values are keyed by the parameters' labels (paramLabels), so a file
 *   from another firmware finds each value by name; one without labels maps by count (the last 8 are P_E0..P_E7).
 */
object Library {
    const val FORMAT = "felucca-library"
    const val PATCH = "felucca-patch"
    const val FM6_PACKED = 128

    /** A key per parameter id from the DESC labels: the label, "#n" on its n-th repeat; the engine's are E0..E7. */
    fun paramKeys(labels: List<String?>, pe0: Int, pcount: Int): List<String> {
        val seen = HashMap<String, Int>()
        return (0 until pcount).map { i ->
            if (i >= pe0) "E${i - pe0}" else {
                val l = labels.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: "P$i"
                val n = (seen[l] ?: 0) + 1
                seen[l] = n
                if (n > 1) "$l#$n" else l
            }
        }
    }

    /** The track parameters' labels before Skiff U4 (LVL ATK .., by id): a file keyed by them still finds its values. */
    private val LEGACY_TP = listOf("LVL", "ATK", "DEC", "SUS", "REL", "FLT", "PIT", "SHP", "FX", "RATE", "WAVE", "PHS", "FADE",
        "PIT", "FLT", "SHP", "AMP", "MODE", "RATE", "OCT", "GATE", "SWG", "PROB", "HOLD", "ORD", "ROOT", "SCL", "QNT", "TRN",
        "LEN", "DIV", "SWG", "GATE", "DST", "CHO", "DLY", "REV", "VCE", "GLD", "PAN", "MUTE", "GLMOD", "PRIO", "ALLOC",
        "DTUNE", "SLCR", "PAT", "RATE", "DEPTH", "SRC1", "DST1", "AMT1", "SRC2", "DST2", "AMT2", "SRC3", "DST3", "AMT3",
        "SRC4", "DST4", "AMT4", "ATK", "DEC", "SUS", "REL", "LVL", "ATK", "DEC", "SUS", "REL", "LVL", "ATK", "DEC", "SUS",
        "REL", "LVL", "ATK", "DEC", "SUS", "REL", "LVL", "CHRD", "VOIC", "KICK", "SNARE", "CLAP", "HATCL", "HATOP", "TOM",
        "RIM", "BELL", "DIR", "BY", "DEPTH", "REL")

    fun legacyKeys(fileKeys: List<String>?, keys: List<String>?): List<String>? {
        if (fileKeys == null || keys == null || "LVL" !in fileKeys || "LEVEL" in fileKeys) return fileKeys
        val old = paramKeys(LEGACY_TP, LEGACY_TP.size, LEGACY_TP.size)
        val to = old.withIndex().associate { (i, k) -> k to keys.getOrNull(i) }
        return fileKeys.map { k -> to[k] ?: k }
    }

    /** Values without keys from another firmware: ids below its P_E0 kept their places, its last 8 are E0..E7. */
    fun tailParams(p: List<Int?>, n: Int, pe0: Int): List<Int?> {
        val out = MutableList<Int?>(n) { null }
        val common = maxOf(0, p.size - 8)
        for (i in 0 until minOf(common, pe0)) if (i < n) out[i] = p[i]
        for (k in 0 until 8) if (common + k < p.size && pe0 + k < n) out[pe0 + k] = p[common + k]
        return out
    }

    /** Values by [fromKeys] -> values by [toKeys] (null where the source has no such key). */
    fun remapParams(p: List<Int?>, fromKeys: List<String>?, toKeys: List<String>?, pe0: Int?): List<Int?> {
        if (fromKeys == null && toKeys != null && pe0 != null && pe0 > 0 && p.size != toKeys.size) return tailParams(p, toKeys.size, pe0)
        if (fromKeys == null || toKeys == null || fromKeys == toKeys) return p
        val at = fromKeys.withIndex().associate { (i, k) -> k to i }
        return toKeys.map { k -> at[k]?.let { p.getOrNull(it) } }
    }

    private fun ints(a: JSONArray?): List<Int?> = if (a == null) emptyList() else List(a.length()) { i ->
        if (a.isNull(i)) null else a.optDouble(i).takeIf { it.isFinite() }?.let { Math.round(it).toInt() }
    }

    private fun strs(a: JSONArray?): List<String> = if (a == null) emptyList() else List(a.length()) { a.optString(it) }

    private fun fm6Of(a: JSONArray?): IntArray? =
        if (a != null && a.length() == FM6_PACKED) IntArray(FM6_PACKED) { a.optInt(it) and 127 } else null

    /** The current sound as the web editor's "Save to file" writes it (without the steps: the app reads them later). */
    fun patchFile(firmware: String, engines: List<String>, presetName: String?, dump: Dump): JSONObject = JSONObject()
        .put("format", PATCH).put("version", 1).put("firmware", firmware)
        .put("engineName", engines.getOrElse(dump.engine) { "" })
        .put("presetName", presetName ?: JSONObject.NULL)
        .put("engine", dump.engine).put("preset", dump.preset)
        .put("p", JSONArray(dump.p.toList())).put("g", JSONArray(dump.g.toList()))

    /** A library file of [patches], keyed by the device's parameter labels. */
    fun libraryFile(kind: String, patches: List<Patch>, ctx: LibraryContext, exported: String): JSONObject = JSONObject()
        .put("format", FORMAT).put("version", 1).put("kind", kind).put("firmware", ctx.firmware)
        .put("pCount", ctx.keys?.size ?: 0).put("pE0", ctx.pe0 ?: JSONObject.NULL)
        .put("paramLabels", JSONArray(ctx.keys ?: emptyList<String>())).put("engines", JSONArray(ctx.engines))
        .put("exported", exported)
        .put("patches", JSONArray(patches.map { pt ->
            JSONObject().put("name", pt.name).put("engine", pt.engine)
                .put("engineName", pt.engineName.ifEmpty { ctx.engines.getOrElse(pt.engine) { "" } })
                .put("params", JSONArray(pt.p.map { it ?: JSONObject.NULL })).put("pattern", JSONObject.NULL)
                .put("tags", JSONArray(pt.tags)).put("created", exported).put("modified", exported)
                .apply { pt.fm6?.let { put("fm6", JSONArray(it.toList())) } }
        }))

    /**
     * Reads a "felucca-patch" or "felucca-library" file against the device. A patch whose engine the device does not
     * have (a DIGITAL sound: Skiff 2.0 retired it, the Skiff Editor's converter takes those) is skipped. Throws on a file
     * that is neither.
     */
    fun read(obj: JSONObject, ctx: LibraryContext): LibraryRead {
        fun engIndex(name: String?, idx: Int, fileEngines: List<String>): Int {
            val nm = name?.takeIf { it.isNotEmpty() } ?: fileEngines.getOrNull(idx)
            if (ctx.engines.isNotEmpty()) {
                if (nm != null) return ctx.engines.indexOf(nm).takeIf { nm != "-" } ?: -1
                return if (idx < ctx.engines.size) idx else -1
            }
            return idx
        }
        when (obj.optString("format")) {
            PATCH -> {
                val p = ints(obj.optJSONArray("p") ?: throw IllegalArgumentException("felucca-patch without p"))
                if (!obj.has("engine")) throw IllegalArgumentException("felucca-patch without engine")
                val name = obj.optString("engineName")
                val e = engIndex(name, obj.optInt("engine"), emptyList())
                if (e < 0) return LibraryRead(emptyList(), 1)
                val keys = ctx.keys
                val vals = if (keys != null && ctx.pe0 != null && p.size != keys.size) tailParams(p, keys.size, ctx.pe0)
                    else p.take(keys?.size ?: p.size)
                val title = obj.optString("presetName").takeIf { it.isNotEmpty() && it != "null" } ?: name.ifEmpty { "PATCH" }
                return LibraryRead(listOf(Patch(title, e, ctx.engines.getOrElse(e) { name }, vals, listOf("file"))), 0)
            }
            FORMAT -> {
                if (obj.optInt("version") < 1) throw IllegalArgumentException("version")
                val list = obj.optJSONArray("patches") ?: throw IllegalArgumentException("no patches")
                val fileEngines = strs(obj.optJSONArray("engines"))
                val labels = strs(obj.optJSONArray("paramLabels")).takeIf { it.isNotEmpty() }
                val fileKeys = legacyKeys(labels, ctx.keys)
                val out = ArrayList<Patch>()
                var skipped = 0
                for (i in 0 until list.length()) {
                    val x = list.optJSONObject(i)
                    val params = x?.optJSONArray("params")
                    if (x == null || params == null) { skipped++; continue }
                    val e = engIndex(x.optString("engineName"), x.optInt("engine"), fileEngines)
                    if (e < 0) { skipped++; continue }
                    val p = remapParams(ints(params), fileKeys, ctx.keys, ctx.pe0)
                    out += Patch(x.optString("name", "PATCH").take(32).ifEmpty { "PATCH" }, e,
                        ctx.engines.getOrElse(e) { x.optString("engineName") }, p,
                        strs(x.optJSONArray("tags")).map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
                        fm6Of(x.optJSONArray("fm6")))
                }
                return LibraryRead(out, skipped)
            }
            else -> throw IllegalArgumentException("not a Skiff Editor sound file")
        }
    }
}
