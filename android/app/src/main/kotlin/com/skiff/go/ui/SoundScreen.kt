// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Skiff Go contributors. Based on the Skiff firmware (illuminus5) and Felucca (Hügelton Instruments);
// see NOTICE.md.
package com.skiff.go.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.skiff.go.device.DeviceState
import com.skiff.go.device.LayoutGroup
import com.skiff.go.device.Pid
import com.skiff.go.device.SoundLayout
import com.skiff.go.proto.Desc
import com.skiff.go.proto.Format
import com.skiff.go.proto.Patch

/** A matrix destination E1..E8 by the engine's label of that parameter, as the device shows it (editor.html modDst). */
fun modDst(s: DeviceState, d: Desc, txt: String): String {
    val info = s.info ?: return txt
    if (!Regex("^DST\\d$").matches(d.label)) return txt
    val n = Regex("^E([1-8])$").find(txt)?.groupValues?.get(1)?.toInt() ?: return txt
    return s.pdesc.getOrNull(info.pe0 + n - 1)?.takeIf { it.visible }?.label ?: txt
}

/** The value as the device shows it, ENV SYNC's note values included. */
fun valueText(s: DeviceState, d: Desc, v: Int): String {
    val info = s.info
    if (d.scope == 0 && d.id in setOf(1, 2, 4) && info != null && info.pe0 >= 127 && (s.dump?.p?.getOrNull(Pid.ESYNC) ?: 0) != 0)
        return Format.ESYNC_NAMES[((v and 127) * Format.ESYNC_NAMES.size) shr 7]
    val (t, u) = Format.value(d, v)
    val txt = modDst(s, d, t)
    return if (u.isEmpty()) txt else "$txt $u"
}

@Composable
fun SoundScreen(vm: SkiffViewModel, s: DeviceState) {
    val info = s.info ?: return
    val c = vm.controller
    val ctx = LocalContext.current
    var picked by remember { mutableStateOf<List<Patch>?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        val text = c.patchJson() ?: return@rememberLauncherForActivityResult
        if (uri != null) runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }
            .onSuccess { vm.say("Saved") }.onFailure { vm.say("Not saved: ${it.message}") }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: error("empty")
            c.readFile(text)
        }.onSuccess { r ->
            when {
                r.patches.isEmpty() -> vm.say("No sound this firmware plays in the file (${r.skipped} skipped)")
                r.patches.size == 1 -> c.applyPatch(r.patches[0])
                else -> picked = r.patches
            }
        }.onFailure { vm.say("Can't read the file: ${it.message}") }
    }

    Column(Modifier.fillMaxSize()) {
        SoundBar(vm, s, onSave = {
            val name = (s.presetName ?: s.engineName).lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            save.launch("skiff-${s.engineName.lowercase()}-$name.json")
        }, onOpen = { open.launch(arrayOf("application/json", "text/plain", "*/*")) })
        val groups = if (SoundLayout.known(info)) SoundLayout.layout(info.pe0, s.engineName) else SoundLayout.fallback(info)
        LazyVerticalStaggeredGrid(StaggeredGridCells.Adaptive(330.dp), Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalItemSpacing = 8.dp) {
            items(groups, key = { it.title }) { g -> GroupCard(vm, s, g) }
        }
    }
    picked?.let { list ->
        AlertDialog(onDismissRequest = { picked = null }, title = { Text("Load a sound onto T${s.selectedTrack + 1}") },
            text = {
                LazyColumn {
                    items(list) { pt ->
                        Text("${pt.name}  ·  ${pt.engineName}", Modifier.fillMaxWidth().clickable { picked = null; c.applyPatch(pt) }
                            .padding(vertical = 10.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picked = null }) { Text("Cancel") } })
    }
}

/** The Sound page's top row: the track, the engine, the preset, INIT, and the sound files. */
@Composable
private fun SoundBar(vm: SkiffViewModel, s: DeviceState, onSave: () -> Unit, onOpen: () -> Unit) {
    val info = s.info ?: return
    val c = vm.controller
    val cs = MaterialTheme.colorScheme
    val sel = s.selectedTrack
    val tc = trackColor(sel)
    Row(Modifier.fillMaxWidth().background(cs.surface).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TrackTabs(vm, s)
        Box(Modifier.width(6.dp))
        val engines = Format.engineOrder(info.engines)
        Menu(s.engineName.ifEmpty { "ENGINE" }, tc, engines.map { e ->
            Triple(info.engines[e], info.engineAllowed(sel, e)) { c.choosePreset(e, 0) }
        })
        val dump = s.dump
        val names = dump?.let { s.names.getOrNull(it.engine) } ?: emptyList()
        Menu(s.presetName ?: "PRESET", tc, names.indices.filter { Format.aliasOf(names, it) == it }.map { p ->
            Triple(names[p], true) { c.choosePreset(dump!!.engine, p) }
        })
        Pill("INIT", cs.onSurfaceVariant) { c.initSound() }
        Pill("SAVE FILE", cs.onSurfaceVariant, onSave)
        Pill("OPEN FILE", cs.onSurfaceVariant, onOpen)
    }
}

@Composable
fun Pill(label: String, color: Color, onClick: () -> Unit) {
    Text(label, Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
        .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp), color = color,
        style = MaterialTheme.typography.labelMedium, maxLines = 1)
}

/** A button that opens a list: (name, enabled, action) each. */
@Composable
private fun Menu(label: String, color: Color, items: List<Triple<String, Boolean, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Box {
        Text("$label ▾", Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, color, RoundedCornerShape(6.dp))
            .clickable { open = true }.padding(horizontal = 10.dp, vertical = 8.dp), color = color,
            style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { (name, enabled, act) ->
                DropdownMenuItem(text = { Text(if (enabled) name else "$name (T1–T4 only)") }, enabled = enabled,
                    onClick = { open = false; act() })
            }
        }
    }
}

/** A group of pages as a card: the pages' knobs four to a row; the MOD matrix a row per slot. */
@Composable
private fun GroupCard(vm: SkiffViewModel, s: DeviceState, g: LayoutGroup) {
    val info = s.info ?: return
    val cs = MaterialTheme.colorScheme
    val color = trackColor(s.selectedTrack)
    fun visibleDesc(scope: Int, id: Int): Desc? =
        s.desc(scope, id)?.takeIf { it.visible && !(scope == 1 && it.label in SoundLayout.G_SKIP) }
    @Composable
    fun Control(scope: Int, id: Int, mod: Modifier) {
        val d = visibleDesc(scope, id) ?: return
        val v = s.value(scope, id) ?: d.def
        Box(mod) {
            ParamControl(d, v, valueText(s, d, v), color, optionText = { modDst(s, d, Format.value(d, it).first) }) {
                vm.controller.setParam(scope, id, it)
            }
        }
    }
    val pages = g.pages.mapIndexedNotNull { i, p ->
        val ids = p.ids.filter { visibleDesc(p.scope, it) != null }
        if (ids.isEmpty()) null else Triple(if (g.engine) s.titles.getOrNull(s.dump?.engine ?: -1)?.getOrNull(i)?.takeIf { it.isNotBlank() } ?: p.title else p.title, p.scope, ids)
    }
    if (pages.isEmpty() && g.mod == null) return
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(cs.surface).padding(8.dp)) {
        Text(if (g.engine) "EDIT ${s.engineName}" else g.title, style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
        if (g.mod != null) {
            for (k in 0 until 4) {
                val base = g.mod + 3 * k
                if ((0..2).any { visibleDesc(0, base + it) == null }) continue
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${k + 1}", Modifier.width(20.dp), color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    for (j in 0..2) Control(0, base + j, Modifier.weight(1f))
                }
            }
        }
        pages.forEach { (title, scope, ids) ->
            if (pages.size > 1) Text(title, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp))
            ids.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { id -> Control(scope, id, Modifier.weight(1f)) }
                    repeat(4 - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
    }
}
