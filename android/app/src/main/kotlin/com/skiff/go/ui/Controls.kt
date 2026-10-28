// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Skiff Go contributors. Based on the Skiff firmware (illuminus5) and Felucca (Hügelton Instruments);
// see NOTICE.md.
package com.skiff.go.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.skiff.go.proto.Desc
import com.skiff.go.proto.Fmt
import com.skiff.go.proto.Format
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * One parameter as its description says: a switch (ON/OFF), a list (an enum, a note), else a knob. [text] is the value as
 * shown; [optionText] names a list value (a matrix destination by the engine's label).
 */
@Composable
fun ParamControl(desc: Desc, value: Int, text: String, color: Color = MaterialTheme.colorScheme.primary,
                 optionText: (Int) -> String = { Format.value(desc, it).first }, onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(desc.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        when (desc.fmt) {
            Fmt.ONOFF -> Box(Modifier.height(56.dp), contentAlignment = Alignment.Center) {
                Switch(checked = value != 0, onCheckedChange = { onChange(if (it) desc.max else desc.min) })
            }
            Fmt.ENUM, Fmt.NOTE -> ListBox(desc, value, text, color, optionText, onChange)
            else -> Knob(desc, value, color, onChange)
        }
        if (desc.fmt != Fmt.ENUM && desc.fmt != Fmt.NOTE)
            Text(text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A list value: the value as text, tap for the list (in the device's order, retired aliases left out). */
@Composable
private fun ListBox(desc: Desc, value: Int, text: String, color: Color, optionText: (Int) -> String, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.height(56.dp), contentAlignment = Alignment.Center) {
        Text(text, Modifier.border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(6.dp)).clickable { open = true }
            .padding(horizontal = 8.dp, vertical = 10.dp), color = color, style = MaterialTheme.typography.labelMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (v in Format.enumShown(desc)) {
                if (Format.isAlias(desc, v) && v != value) continue
                DropdownMenuItem(text = { Text(optionText(v), color = if (v == value) color else Color.Unspecified) },
                    onClick = { open = false; onChange(v) })
            }
        }
    }
}

/** A knob: a vertical drag turns it (a finger moved sideways turns it finely), a double tap sets the default. */
@Composable
fun Knob(desc: Desc, value: Int, color: Color, onChange: (Int) -> Unit, size: Dp = 56.dp) {
    val density = LocalDensity.current
    val latest by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    var dragged by remember(desc.scope, desc.id) { mutableFloatStateOf(value.toFloat()) }
    val track = MaterialTheme.colorScheme.outline
    val face = MaterialTheme.colorScheme.surfaceVariant
    val span = (desc.max - desc.min).coerceAtLeast(1)
    val bipolar = desc.min < 0 && desc.max > 0
    val frac = ((value - desc.min).toFloat() / span).coerceIn(0f, 1f)
    val zero = if (bipolar) (-desc.min).toFloat() / span else 0f
    Canvas(Modifier.size(size).semantics {
        contentDescription = desc.label
        progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), desc.min.toFloat()..desc.max.toFloat())
        setProgress { v -> change(v.roundToInt().coerceIn(desc.min, desc.max)); true }
    }.pointerInput(desc.scope, desc.id, desc.min, desc.max) {
        var startX = 0f
        val fine = with(density) { 32.dp.toPx() }
        val travel = with(density) { 200.dp.toPx() }
        detectDragGestures(
            onDragStart = { startX = it.x; dragged = latest.toFloat() },
            onDrag = { c, delta ->
                val precision = if (abs(c.position.x - startX) > fine) 0.15f else 1f
                dragged = (dragged - delta.y / travel * span * precision).coerceIn(desc.min.toFloat(), desc.max.toFloat())
                val next = dragged.roundToInt()
                if (next != latest) change(next)
                c.consume()
            },
        )
    }.pointerInput(desc.scope, desc.id, desc.def) {
        detectTapGestures(onDoubleTap = { change(desc.def) })
    }) {
        val r = this.size.minDimension * 0.4f
        val o = Offset(this.size.width / 2f, this.size.height / 2f)
        val tl = Offset(o.x - r, o.y - r)
        val dia = Size(r * 2, r * 2)
        val w = 5.dp.toPx()
        drawCircle(face, radius = r * 0.8f, center = o)
        drawArc(track, 150f, 240f, false, tl, dia, style = Stroke(w, cap = StrokeCap.Round))
        val a0 = 150f + 240f * minOf(zero, frac)
        drawArc(color, a0, 240f * abs(frac - zero), false, tl, dia, style = Stroke(w, cap = StrokeCap.Round))
        val ang = (150f + 240f * frac) * PI / 180.0
        val dir = Offset(cos(ang).toFloat(), sin(ang).toFloat())
        drawLine(color, o + dir * (r * 0.35f), o + dir * (r * 0.72f), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
    }
}

/** A vertical fader: the whole length is touch-sensitive, a touch jumps to its height and a drag follows it. */
@Composable
fun VerticalSlider(value: Int, min: Int, max: Int, color: Color, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val latest by rememberUpdatedState(onChange)
    val span = (max - min).coerceAtLeast(1)
    val track = MaterialTheme.colorScheme.outline
    val frac = ((value - min).toFloat() / span).coerceIn(0f, 1f)
    Canvas(modifier.fillMaxWidth().pointerInput(min, max) {
        val pad = with(density) { 14.dp.toPx() }
        fun at(y: Float): Int = (min + (1f - ((y - pad) / (size.height - 2 * pad).coerceAtLeast(1f)).coerceIn(0f, 1f)) * span).roundToInt()
        awaitEachGesture {
            val down = awaitFirstDown()
            latest(at(down.position.y))
            down.consume()
            drag(down.id) { c -> latest(at(c.position.y)); c.consume() }
        }
    }) {
        val pad = 14.dp.toPx()
        val cx = size.width / 2f
        val h = (size.height - 2 * pad).coerceAtLeast(1f)
        val w = 10.dp.toPx()
        drawRoundRect(track.copy(alpha = 0.5f), Offset(cx - w / 2, pad), Size(w, h), CornerRadius(w / 2))
        val ty = pad + h * (1f - frac)
        drawRoundRect(color, Offset(cx - w / 2, ty), Size(w, pad + h - ty), CornerRadius(w / 2))
        val tw = 40.dp.toPx(); val th = 22.dp.toPx()
        drawRoundRect(color, Offset(cx - tw / 2, ty - th / 2), Size(tw, th), CornerRadius(6.dp.toPx()))
        drawLine(Color.Black, Offset(cx - tw / 4, ty), Offset(cx + tw / 4, ty), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
    }
}
