// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Skiff Go contributors. Based on the Skiff firmware (illuminus5) and Felucca (Hügelton Instruments);
// see NOTICE.md.
package com.skiff.go.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.skiff.go.device.DeviceState
import com.skiff.go.device.Pid

/**
 * The six strips, as the device's MIXER and the Skiff Editor's Tracks tab: LEVEL and MUTE by TRACK_MIX (any track), PAN
 * and the REVERB send of the selected track by SET, of another by TRACK_PARAM (the selection stays).
 */
@Composable
fun MixerScreen(vm: SkiffViewModel, s: DeviceState) {
    val info = s.info ?: return
    val c = vm.controller
    val cs = MaterialTheme.colorScheme
    val panDesc = s.desc(0, Pid.PAN)
    val revDesc = s.desc(0, Pid.REV)
    Row(Modifier.fillMaxSize().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        s.strips.forEachIndexed { i, st ->
            val e = s.tracks?.tracks?.getOrNull(i)
            val col = trackColor(i)
            val selected = i == s.selectedTrack
            Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(cs.surface)
                .border(if (selected) 2.dp else 1.dp, if (selected) col else cs.outline, RoundedCornerShape(8.dp)).padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("T${i + 1}", Modifier.clickable { if (!selected) c.selectTrack(i) }, color = col, fontWeight = FontWeight.Bold)
                val eng = e?.let { info.engines.getOrNull(it.engine) } ?: ""
                val preset = e?.let { s.names.getOrNull(it.engine)?.getOrNull(it.preset) } ?: ""
                Text(eng, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(preset, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                if (panDesc != null && st.pan != null) Box(Modifier.fillMaxWidth()) {
                    ParamControl(panDesc, st.pan, valueText(s, panDesc, st.pan), col) { c.setTrackParam(i, Pid.PAN, it) }
                }
                if (revDesc != null && st.rev != null) Box(Modifier.fillMaxWidth()) {
                    ParamControl(revDesc, st.rev, valueText(s, revDesc, st.rev), col) { c.setTrackParam(i, Pid.REV, it) }
                }
                VerticalSlider(st.level, 0, 127, col, { c.setMix(i, it, st.mute) }, Modifier.weight(1f))
                Text("${st.level}", style = MaterialTheme.typography.labelMedium, color = col)
                Text("MUTE", Modifier.padding(top = 4.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (st.mute) cs.error else Color.Transparent).border(1.dp, cs.error, RoundedCornerShape(6.dp))
                    .clickable { c.setMix(i, st.level, !st.mute) }.padding(horizontal = 10.dp, vertical = 6.dp),
                    color = if (st.mute) Color.Black else cs.error, style = MaterialTheme.typography.labelMedium)
                if (e?.armed == true) Text("REC", color = cs.error, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
