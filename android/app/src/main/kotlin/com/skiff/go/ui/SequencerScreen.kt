// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Skiff Go contributors. Based on the Skiff firmware (illuminus5) and Felucca (Hügelton Instruments);
// see NOTICE.md.
package com.skiff.go.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.skiff.go.device.DeviceState
import com.skiff.go.device.Note
import com.skiff.go.device.Pid
import com.skiff.go.device.Roll
import com.skiff.go.proto.Desc
import com.skiff.go.proto.Format
import com.skiff.go.proto.Step
import com.skiff.go.proto.StepTime

private val NOTE_LENS = listOf(1, 2, 3, 4, 6, 8, 16)
private val COND_NAMES = listOf("--", "FILL", "!FILL", "1:2", "2:2", "1:3", "2:3", "3:3", "1:4", "2:4", "3:4", "4:4")

@Composable
fun SequencerScreen(vm: SkiffViewModel, s: DeviceState) {
    val c = vm.controller
    val info = s.info ?: return
    val color = trackColor(s.selectedTrack)
    DisposableEffect(Unit) { c.sequencerShown(true); onDispose { c.sequencerShown(false) } }
    // (not keyed on the steps being ready: a read that starts blanks them, and that must not start another)
    LaunchedEffect(s.selectedTrack) { c.ensureSteps() }
    LaunchedEffect(s.selectedTrack, s.stepsReady) { if (!s.stepsReady) { kotlinx.coroutines.delay(2000); c.ensureSteps() } }

    var selNote by remember(s.selectedTrack) { mutableStateOf<Note?>(null) }
    var selStep by remember(s.selectedTrack) { mutableIntStateOf(0) }
    var newLen by remember { mutableIntStateOf(1) }
    val length = s.patternLength
    val ready = s.stepsReady
    val notes = remember(s.steps, length, ready) { if (ready) Roll.notes(s.steps, length) else emptyList() }
    val note = selNote?.takeIf { it in notes }

    val frame = remember { mutableStateOf(0L) }
    val running = s.transport?.running == true
    LaunchedEffect(running) { if (running) while (true) androidx.compose.runtime.withFrameNanos { frame.value = it } else frame.value++ }
    val playhead = { c.playhead.position(SystemClock.elapsedRealtime(), length, s.divName, s.direction) }

    Column(Modifier.fillMaxSize()) {
        SeqBar(vm, s, newLen) { newLen = it }
        if (s.stepsRead in 1 until Step.NSTEP && ready) LinearProgressIndicator(progress = { s.stepsRead / 64f }, Modifier.fillMaxWidth())
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when {
                    !ready -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Reading the pattern… ${s.stepsRead}/$length", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LinearProgressIndicator(progress = { s.stepsRead / length.coerceAtLeast(1).toFloat() }, Modifier.padding(top = 8.dp).width(240.dp))
                    }
                    s.drumTrack -> DrumView(s.steps, length, selStep, color, playhead, frame, s.selectedTrack, { selStep = it },
                        { c.editSteps(it) }, Modifier.fillMaxSize())
                    else -> RollView(s.steps, length, s.poly, notes, note, newLen, color, playhead, frame, s.selectedTrack,
                        onSelect = { selNote = it; it?.let { n -> selStep = n.start } },
                        onEdit = { steps, sel -> c.editSteps(steps); selNote = sel; sel?.let { selStep = it.start } },
                        say = { c.say(it) }, modifier = Modifier.fillMaxSize())
                }
            }
            val stepIndex = if (s.drumTrack) selStep else note?.start
            StepSheet(
                step = stepIndex?.let { s.steps.getOrNull(it) }?.takeIf { ready },
                title = when { stepIndex == null -> ""; s.drumTrack -> "STEP ${stepIndex + 1}"
                    else -> "${pitchName(note!!.pitch)} · step ${stepIndex + 1} · ${note.len} long" },
                trigConds = info.trigConds, color = color,
            ) { edit ->
                val i = stepIndex ?: return@StepSheet
                val steps = if (s.drumTrack) s.steps.toMutableList().also { it[i] = edit(it[i]) }
                    else Roll.editStart(s.steps, length, note!!, edit)
                c.editSteps(steps)
            }
        }
    }
}

/** The pinned top row: the track, undo, the pattern's settings and the length of a new note. */
@Composable
private fun SeqBar(vm: SkiffViewModel, s: DeviceState, newLen: Int, onNewLen: (Int) -> Unit) {
    val c = vm.controller
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().background(cs.surface).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TrackTabs(vm, s)
        Box(Modifier.width(4.dp))
        Pill("UNDO", if (s.canUndo) cs.onSurface else cs.outline) { if (s.canUndo) c.undoSteps() }
        Pill("REDO", if (s.canRedo) cs.onSurface else cs.outline) { if (s.canRedo) c.redoSteps() }
        Box(Modifier.width(4.dp))
        for (id in listOf(Pid.SLEN, Pid.SDIV, Pid.SSWING, Pid.SGATE, Pid.DIR)) s.desc(0, id)?.takeIf { it.visible }?.let { d ->
            ParamPill(vm, s, d)
        }
        if (!s.drumTrack) {
            Box(Modifier.width(4.dp))
            Text("NEW", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            for (n in NOTE_LENS) {
                val on = n == newLen
                Text("$n", Modifier.clip(RoundedCornerShape(6.dp)).background(if (on) cs.primary else Color.Transparent)
                    .border(1.dp, if (on) cs.primary else cs.outline, RoundedCornerShape(6.dp)).clickable { onNewLen(n) }
                    .padding(horizontal = 10.dp, vertical = 8.dp), color = if (on) Color.Black else cs.onSurface,
                    style = MaterialTheme.typography.labelMedium)
            }
            if (!s.poly) Text("MONO", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
    }
}

/** T1..T6 as the Sound page has them. */
@Composable
fun TrackTabs(vm: SkiffViewModel, s: DeviceState) {
    val info = s.info ?: return
    for (i in 0 until info.ntrk) {
        val on = i == s.selectedTrack
        val col = trackColor(i)
        Text("T${i + 1}", Modifier.clip(RoundedCornerShape(6.dp)).background(if (on) col else Color.Transparent)
            .border(1.dp, col, RoundedCornerShape(6.dp)).clickable { if (!on) vm.controller.selectTrack(i) }
            .padding(horizontal = 12.dp, vertical = 8.dp), color = if (on) Color.Black else col, fontWeight = FontWeight.Bold)
    }
}

/** A parameter as a pill: a list for an enum, else its value with - and + around it. */
@Composable
fun ParamPill(vm: SkiffViewModel, s: DeviceState, d: Desc) {
    val cs = MaterialTheme.colorScheme
    val v = s.value(0, d.id) ?: d.def
    var open by remember { mutableStateOf(false) }
    val c = vm.controller
    Row(Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, cs.outline, RoundedCornerShape(6.dp)), verticalAlignment = Alignment.CenterVertically) {
        Text(d.label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        if (d.fmt == com.skiff.go.proto.Fmt.ENUM) Box {
            Text(valueText(s, d, v) + " ▾", Modifier.clickable { open = true }.padding(horizontal = 8.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge, color = cs.primary)
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (x in Format.enumShown(d)) {
                    if (Format.isAlias(d, x) && x != v) continue
                    DropdownMenuItem(text = { Text(Format.value(d, x).first) }, onClick = { open = false; c.setParam(0, d.id, x) })
                }
            }
        } else {
            Text("−", Modifier.clickable { if (v > d.min) c.setParam(0, d.id, v - 1) }.padding(horizontal = 10.dp, vertical = 8.dp), color = cs.primary)
            Text(valueText(s, d, v), style = MaterialTheme.typography.labelLarge, color = cs.onSurface)
            Text("+", Modifier.clickable { if (v < d.max) c.setParam(0, d.id, v + 1) }.padding(horizontal = 10.dp, vertical = 8.dp), color = cs.primary)
        }
    }
}

/** The values of the picked step (what the notes starting on it carry): velocity, accent, slide, chance, ratchet, condition, nudge. */
@Composable
private fun StepSheet(step: Step?, title: String, trigConds: Int, color: Color, onEdit: ((Step) -> Step) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.width(230.dp).fillMaxHeight().background(cs.surface).verticalScroll(rememberScrollState()).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (step == null) {
            Text("Tap a note to edit its step.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            Text("Tap the roll to put a note, drag it to move it, drag its right end to stretch it, hold it to take it out.",
                style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            return@Column
        }
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = color)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Toggle("ACC", step.accent, color) { onEdit { st -> st.copy(flags = st.flags xor 1) } }
            Toggle("SLIDE", step.slide, color) { onEdit { st -> st.copy(flags = st.flags xor 2) } }
            Toggle("REST", step.time == StepTime.REST && step.n > 0, color) {
                onEdit { st -> st.copy(time = if (st.time == StepTime.REST) StepTime.NOTE else StepTime.REST) }
            }
        }
        Labeled("VELOCITY", "${step.velocity}") {
            Slider(step.velocity.toFloat(), { v -> val x = v.toInt().coerceIn(1, 127); if (x != step.velocity) onEdit { st -> st.copy(vel = x) } },
                valueRange = 1f..127f)
        }
        Labeled("CHANCE", "${step.chance} %") {
            Slider(step.chance.toFloat(), { v -> val x = (v / 5).toInt() * 5; if (x != step.chance) onEdit { st -> st.copy(chance = x) } },
                valueRange = 0f..100f, steps = 19)
        }
        Labeled("RATCHET", if (step.flam) "FLAM" else "×${step.ratchet}") {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (r in 1..4) Toggle("×$r", step.ratchet == r && !step.flam, color) { onEdit { st -> st.copy(ratchet = r, flam = false) } }
                Toggle("FLAM", step.flam, color) { onEdit { st -> st.copy(ratchet = 1, flam = true) } }
            }
        }
        if (trigConds > 0) {
            var open by remember { mutableStateOf(false) }
            Labeled("CONDITION", COND_NAMES.getOrElse(step.cond) { "--" }) {
                Box {
                    Text("${COND_NAMES.getOrElse(step.cond) { "--" }} ▾", Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, cs.outline, RoundedCornerShape(6.dp))
                        .clickable { open = true }.padding(horizontal = 12.dp, vertical = 8.dp), color = cs.primary)
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        COND_NAMES.take(trigConds).forEachIndexed { i, n -> DropdownMenuItem(text = { Text(n) }, onClick = { open = false; onEdit { st -> st.copy(cond = i) } }) }
                    }
                }
            }
        }
        Labeled("NUDGE", (if (step.nudge > 0) "+" else "") + "${step.nudge}/64") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("−", Modifier.clickable { onEdit { st -> st.copy(nudge = (st.nudge - 1).coerceAtLeast(-32)) } }.padding(12.dp), color = cs.primary)
                Text("0", Modifier.clickable { onEdit { st -> st.copy(nudge = 0) } }.padding(12.dp), color = cs.onSurfaceVariant)
                Text("+", Modifier.clickable { onEdit { st -> st.copy(nudge = (st.nudge + 1).coerceAtMost(31)) } }.padding(12.dp), color = cs.primary)
            }
        }
    }
}

@Composable
private fun Labeled(label: String, value: String, content: @Composable () -> Unit) {
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
        }
        content()
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, color: Color, onClick: () -> Unit) {
    Text(label, Modifier.clip(RoundedCornerShape(6.dp)).background(if (on) color else Color.Transparent)
        .border(1.dp, if (on) color else MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp)).clickable(onClick = onClick)
        .padding(horizontal = 8.dp, vertical = 7.dp), color = if (on) Color.Black else MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.labelMedium)
}
