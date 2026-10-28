// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Skiff Go contributors. Based on the Skiff firmware (illuminus5) and Felucca (Hügelton Instruments);
// see NOTICE.md.
package com.skiff.go.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.skiff.go.device.DeviceState
import com.skiff.go.device.Link
import com.skiff.go.proto.Info
import com.skiff.go.proto.Requests
import com.skiff.go.proto.TrackEntry
import kotlinx.coroutines.delay

enum class Tab(val title: String) { SOUND("SOUND"), SEQ("SEQ"), MIXER("MIXER"), DEVICE("DEVICE") }

@Composable
fun App(vm: SkiffViewModel) {
    val state by vm.controller.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.SOUND) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Header(vm, state, tab) { tab = it }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (state.link == Link.READY) when (tab) {
                    Tab.SOUND -> SoundScreen(vm, state)
                    Tab.SEQ -> SequencerScreen(vm, state)
                    Tab.MIXER -> MixerScreen(vm, state)
                    Tab.DEVICE -> DevicePage(vm, state)
                } else ConnectPage(vm, state)
            }
            Notice(vm, state)
        }
    }
}

/** The pinned top row: the name, the pages and the transport once connected, the link's status at the right. */
@Composable
private fun Header(vm: SkiffViewModel, state: DeviceState, tab: Tab, onTab: (Tab) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().background(cs.surface).horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("SKIFF GO", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (state.link == Link.READY) {
            Spacer(Modifier.width(4.dp))
            for (t in Tab.entries) Pad(t.title, t == tab, cs.secondary) { onTab(t) }
            Spacer(Modifier.width(8.dp))
            val tr = state.transport
            val playing = tr?.running == true
            val armed = (tr?.armed ?: 0) != 0
            Pad(if (playing) "STOP" else "PLAY", playing, cs.primary) {
                vm.controller.transport(if (playing) Requests.Op.STOP else Requests.Op.PLAY)
            }
            Pad("RESTART", false, cs.primary) { vm.controller.transport(Requests.Op.RESTART) }
            Pad(if (tr?.countingIn == true) "COUNT" else "REC", armed, cs.error) { vm.controller.transport(Requests.Op.REC) }
        }
        Spacer(Modifier.width(12.dp))
        Text(listOfNotNull(state.deviceName, state.rttMs.takeIf { it >= 0 && state.link == Link.READY }?.let { "$it ms" },
            state.status.takeIf { state.link != Link.READY }).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Pad(label: String, on: Boolean, color: Color, onClick: () -> Unit) {
    Text(label, Modifier.clip(RoundedCornerShape(6.dp)).background(if (on) color else Color.Transparent)
        .border(1.dp, color, RoundedCornerShape(6.dp)).clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 8.dp),
        color = if (on) Color.Black else color, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
}

/** The last action's message along the bottom, gone after a few seconds. */
@Composable
private fun Notice(vm: SkiffViewModel, state: DeviceState) {
    val msg = state.notice ?: return
    LaunchedEffect(msg) { delay(4000); vm.controller.clearNotice() }
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(msg, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        TextButton(onClick = { vm.controller.clearNotice() }) { Text("OK") }
    }
}

@Composable
private fun ConnectPage(vm: SkiffViewModel, state: DeviceState) {
    val devices by vm.controller.devices.collectAsState()
    val cs = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(state.status, style = MaterialTheme.typography.titleMedium,
                color = if (state.link == Link.WRONG_FIRMWARE) cs.error else cs.onBackground)
            state.progress?.let { (done, of) ->
                Text("Reading the device's parameters $done / $of", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                LinearProgressIndicator(progress = { done.toFloat() / of.coerceAtLeast(1) }, Modifier.fillMaxWidth().padding(top = 6.dp))
            }
            state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
            if (!vm.controller.midiAvailable) Text("This phone has no MIDI support.", color = cs.error)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.refreshDevices() }) { Text("Refresh") }
                OutlinedButton(onClick = { vm.connectPreferred() }) { Text("Connect FM-1") }
            }
        }
        items(devices, key = { it.id }) { d ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(cs.surface)
                .clickable(enabled = state.link != Link.CONNECTING && state.link != Link.LOADING) { vm.controller.connect(d.id) }
                .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(d.name, fontWeight = FontWeight.SemiBold)
                    Text(listOf(d.manufacturer, if (d.hasInput && d.hasOutput) "in + out" else "one way only")
                        .filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
                TextButton(onClick = { vm.controller.connect(d.id) }) { Text("Connect") }
            }
        }
        if (devices.isEmpty()) item {
            Text("No MIDI device. Connect the FM-1 by USB (an OTG cable) and tap Refresh.",
                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun DevicePage(vm: SkiffViewModel, state: DeviceState) {
    val info = state.info ?: return
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.tracks?.tracks?.forEachIndexed { i, e ->
                    TrackTile(i, e, info, i == state.selectedTrack, Modifier.weight(1f)) { vm.controller.selectTrack(i) }
                }
            }
        }
        item { InfoCard(info, state) }
        item {
            OutlinedButton(onClick = { vm.controller.disconnect() }) { Text("Disconnect") }
        }
    }
}

@Composable
private fun TrackTile(i: Int, e: TrackEntry, info: Info, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = trackColor(i)
    val cs = MaterialTheme.colorScheme
    Column(modifier.clip(RoundedCornerShape(8.dp)).background(if (selected) c else cs.surface)
        .border(1.dp, c, RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(10.dp)) {
        val ink = if (selected) Color.Black else cs.onSurface
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("T${i + 1}", color = ink, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (e.armed) Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(cs.error))
            if (e.mute) Text(" M", color = ink, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(4.dp))
        Text(info.engines.getOrNull(e.engine) ?: "ENG ${e.engine}", color = ink, maxLines = 1,
            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
        Text("LVL ${e.level}", color = ink.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun InfoCard(info: Info, state: DeviceState) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(cs.surface).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(info.version, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        val caps = listOfNotNull(
            "TRANSPORT".takeIf { info.skiffCaps and Info.CAP_TRANSPORT != 0 },
            "PROJ NAME".takeIf { info.skiffCaps and Info.CAP_PROJ_NAME != 0 },
            "POOL".takeIf { info.skiffCaps and Info.CAP_POOL != 0 },
            "LIST".takeIf { info.skiffCaps and Info.CAP_LIST != 0 },
            "PERFORM".takeIf { info.skiffCaps and Info.CAP_PERFORM != 0 },
            "SMP MIX".takeIf { info.skiffCaps and Info.CAP_SMP_MIX != 0 },
            "TIES".takeIf { info.hasTies },
            "LIST2".takeIf { info.song2Caps and 1 != 0 },
            "ENTRY SOUNDS".takeIf { info.song2Caps and 2 != 0 },
            "LOCKS".takeIf { info.hasLocks },
        )
        listOf(
            "tracks    ${info.ntrk} (light: ${(0 until info.ntrk).filter { info.lightTracks and (1 shl it) != 0 }.joinToString { "T${it + 1}" }.ifEmpty { "none" }})",
            "params    ${info.pcount} + ${info.gcount} global, engine params from ${info.pe0}",
            "steps     ${info.nstep}, ratchet ×${info.ratchetMax}, ${info.trigConds} trig conditions, motion ${info.motionMax}",
            "engines   ${info.engines.filter { it != "-" }.joinToString(" ")}",
            "menu      ${info.menuCount} settings, DRUM-X lanes ${info.kitLanes}",
            "watch     ${state.watch}",
            "caps      ${caps.joinToString(" ")}",
        ).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
        if (!info.hasTies) Text("This Skiff has no per-note ties: update to Skiff 2.0 or later for the piano roll.",
            style = MaterialTheme.typography.bodySmall, color = cs.error)
    }
}
