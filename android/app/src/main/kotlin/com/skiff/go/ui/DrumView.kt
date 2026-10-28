// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Skiff Go contributors. Based on the Skiff firmware (illuminus5) and Felucca (Hügelton Instruments);
// see NOTICE.md.
package com.skiff.go.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import com.skiff.go.device.Roll
import com.skiff.go.proto.Lanes
import com.skiff.go.proto.Step

/**
 * The drum grid: the eight lanes of the kit (a step's hits, and the lanes its GM notes fall on) over the steps. Tap a cell
 * for a hit, hold it for its accent, tap a step's number to pick the step (its sheet). Drag the empty grid to scroll,
 * pinch to zoom along the steps (the pattern's end steps stick to the screen's edges, as in the piano roll).
 */
@Composable
fun DrumView(
    steps: List<Step>, length: Int, selected: Int, color: Color, playhead: () -> Float, frame: State<Long>, trackKey: Int,
    onSelect: (Int) -> Unit, onEdit: (List<Step>) -> Unit, modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    val vp = remember(trackKey) { Viewport() }
    vp.d = density; vp.lw = 56f * density; vp.hdr = 22f * density; vp.cols = length; vp.rows = 8; vp.lockV = true
    val steps0 by rememberUpdatedState(steps)
    val len0 by rememberUpdatedState(length)
    val onEdit0 by rememberUpdatedState(onEdit)
    val onSelect0 by rememberUpdatedState(onSelect)
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE } }

    LaunchedEffect(trackKey, length, vp.size) { if (vp.size.width != 0) vp.fit() }

    val handler = {
        object : GridHandler {
            override fun tap(p: Offset) {
                val step = vp.colAt(p.x); val lane = vp.rowAt(p.y)
                if (p.x < vp.lw || step !in 0 until len0) return
                if (p.y < vp.hdr) { onSelect0(step); return }
                if (lane !in 0..7) return
                val l = steps0.toMutableList(); l[step] = Roll.toggleLane(l[step], lane); onEdit0(l); onSelect0(step)
            }
            override fun longPress(p: Offset): Boolean {
                val step = vp.colAt(p.x); val lane = vp.rowAt(p.y)
                if (p.x >= vp.lw && p.y >= vp.hdr && step in 0 until len0 && lane in 0..7) {
                    val l = steps0.toMutableList(); l[step] = Roll.toggleLaneAccent(l[step], lane); onEdit0(l); onSelect0(step)
                }
                return false
            }
        }
    }

    Canvas(modifier.fillMaxSize().onSizeChanged { vp.size = it; vp.clamp() }.pointerInput(Unit) { gridGestures(vp, handler) }) {
        frame.value
        val n = drawContext.canvas.nativeCanvas
        val lw = vp.lw; val hdr = vp.hdr; val cw = vp.cw; val rh = vp.rh
        val first = (vp.sx / cw).toInt().coerceAtLeast(0)
        val last = ((vp.sx + size.width - lw) / cw).toInt().coerceAtMost(length - 1)
        for (lane in 0..7) {
            val y = hdr + lane * rh
            drawRect(if (lane % 2 == 0) Color(0xFF1B1B1B) else Color(0xFF151515), Offset(lw, y), Size(size.width - lw, rh))
            for (i in first..last) {
                val s = steps.getOrNull(i) ?: continue
                val x = lw + i * cw - vp.sx
                val on = Lanes.lanes(s) and (1 shl lane) != 0
                val acc = Lanes.accents(s) and (1 shl lane) != 0
                if (on) {
                    val inset = (cw * 0.07f).coerceIn(1f, 4f)
                    drawRoundRect(if (acc) color else color.copy(alpha = 0.55f), Offset(x + inset, y + 4f), Size(cw - 2 * inset, rh - 8f), CornerRadius(6f))
                    if (acc) drawRoundRect(Color.White, Offset(x + inset, y + 4f), Size(cw - 2 * inset, rh - 8f), CornerRadius(6f), style = Stroke(2.5f))
                }
            }
        }
        for (i in first..last) {
            val x = lw + i * cw - vp.sx
            drawLine(if (i % 4 == 0) Color(0xFF3E3E3E) else Color(0xFF2C2C2C), Offset(x, hdr), Offset(x, size.height), if (i % 4 == 0) 2f else 1f)
        }
        if (selected in first..last) {
            val x = lw + selected * cw - vp.sx
            drawRect(Color.White.copy(alpha = 0.7f), Offset(x, hdr), Size(cw, 8 * rh), style = Stroke(2.5f))
        }
        val ph = playhead()
        if (ph >= 0f) {
            val x = lw + ph * cw - vp.sx
            if (x >= lw) drawLine(Color.White, Offset(x, hdr), Offset(x, hdr + 8 * rh), 3f)
        }
        drawRect(Color(0xFF0C0C0C), Offset(0f, 0f), Size(lw, size.height))
        paint.textSize = (rh * 0.34f).coerceAtMost(16f * density); paint.color = android.graphics.Color.WHITE
        for (lane in 0..7) n.drawText(Lanes.NAMES[lane], 4f, hdr + lane * rh + rh * 0.58f, paint)
        drawRect(Color(0xFF0C0C0C), Offset(0f, 0f), Size(size.width, hdr))
        paint.textSize = hdr * 0.62f
        val every = if (cw >= 26f * density) 1 else if (cw >= 13f * density) 2 else if (cw >= 7f * density) 4 else 8
        for (i in first..last) {
            if (i % every != 0 && i != selected) continue
            val x = lw + i * cw - vp.sx
            paint.color = if (i == selected) android.graphics.Color.WHITE else if (i % 4 == 0) 0xFFDDDDDD.toInt() else 0xFF8A8A8A.toInt()
            n.drawText("${i + 1}", x + 4f, hdr * 0.78f, paint)
        }
    }
}
