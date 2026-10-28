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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import com.skiff.go.device.Note
import com.skiff.go.device.Roll
import com.skiff.go.proto.Format
import com.skiff.go.proto.Step
import kotlin.math.min
import kotlin.math.roundToInt

private const val TOP_PITCH = 108      // C8: the roll shows C-1 (0) .. C8, a little more than a synth's keyboard
private val BLACK = booleanArrayOf(false, true, false, true, false, false, true, false, true, false, true, false)

fun pitchName(p: Int) = Format.NOTE_NAMES[((p % 12) + 12) % 12] + (p / 12 - 1)

/** A move or resize in progress: the note as it would be. */
private data class Drag(val from: Note, val to: Note)

/**
 * The piano roll: a bar for each note (a note that goes on is one bar through its steps, overlapping notes on their own
 * rows). One finger: tap to put a note of the chosen length, tap a note to pick it, drag it to move it, drag its right end
 * to stretch it, hold it to take it out, drag the empty roll to scroll. Two fingers: pinch to zoom (along the axis the
 * fingers move more), the pattern's end steps sticking to the screen's edges ([Viewport]). The edits are computed by
 * [Roll] (the firmware's own tie model) and handed to [onEdit] whole.
 */
@Composable
fun RollView(
    steps: List<Step>, length: Int, poly: Boolean, notes: List<Note>, selected: Note?, newLen: Int, color: Color,
    playhead: () -> Float, frame: State<Long>, trackKey: Int, onSelect: (Note?) -> Unit, onEdit: (List<Step>, Note?) -> Unit,
    say: (String) -> Unit, modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    val vp = remember(trackKey) { Viewport() }
    vp.d = density; vp.lw = 44f * density; vp.hdr = 20f * density; vp.cols = length; vp.rows = TOP_PITCH + 1
    var drag by remember { mutableStateOf<Drag?>(null) }
    val steps0 by rememberUpdatedState(steps)
    val notes0 by rememberUpdatedState(notes)
    val len0 by rememberUpdatedState(length)
    val poly0 by rememberUpdatedState(poly)
    val newLen0 by rememberUpdatedState(newLen)
    val onEdit0 by rememberUpdatedState(onEdit)
    val onSelect0 by rememberUpdatedState(onSelect)
    val say0 by rememberUpdatedState(say)

    // the whole pattern across the screen, the notes in the middle: when the track, its length or the view's size changes
    LaunchedEffect(trackKey, length, vp.size) {
        if (vp.size.width == 0) return@LaunchedEffect
        vp.fit()
        val mid = if (notes0.isEmpty()) 60 else notes0.map { it.pitch }.average().roundToInt()
        vp.sy = (TOP_PITCH - mid) * vp.rh - (vp.h - vp.hdr) / 2
        vp.clamp()
    }

    fun pitchAt(y: Float) = TOP_PITCH - vp.rowAt(y)
    fun noteAt(p: Offset): Note? {
        if (p.x < vp.lw || p.y < vp.hdr) return null
        val st = vp.colAt(p.x); val pi = pitchAt(p.y)
        return notes0.firstOrNull { it.pitch == pi && st >= it.start && st < it.end }
    }

    val handler = {
        object : GridHandler {
            var target: Note? = null
            var resize = false
            var start = Offset.Zero
            override fun tap(p: Offset) {
                if (p.x < vp.lw || p.y < vp.hdr) return
                val t = noteAt(p)
                if (t != null) { onSelect0(t); return }
                val st = vp.colAt(p.x); val pi = pitchAt(p.y)
                if (st !in 0 until len0 || pi !in 1..127) return
                val n = Note(st, min(newLen0, len0 - st), pi)
                val r = Roll.addNote(steps0, len0, n, poly0)
                if (r == null) say0("Step ${st + 1} is taken") else onEdit0(r, n)
            }
            override fun beginMove(p: Offset): Boolean {
                val t = noteAt(p) ?: return false
                target = t; start = p
                val edge = min(22f * vp.d, vp.cw * t.len * 0.5f)
                resize = p.x - vp.lw + vp.sx > t.end * vp.cw - edge
                drag = Drag(t, t)
                onSelect0(t)
                return true
            }
            override fun longPress(p: Offset): Boolean {
                noteAt(p)?.let { onEdit0(Roll.removeNote(steps0, len0, it), null) }
                return false
            }
            override fun drag(p: Offset) {
                val t = target ?: return
                val ds = ((p.x - start.x) / vp.cw).roundToInt()
                val dp = -((p.y - start.y) / vp.rh).roundToInt()
                drag = Drag(t, if (resize) t.copy(len = (t.len + ds).coerceIn(1, len0 - t.start))
                    else t.copy(start = (t.start + ds).coerceIn(0, len0 - t.len), pitch = (t.pitch + dp).coerceIn(1, 127)))
            }
            override fun release(p: Offset) {
                drag(p)
                val d = drag
                drag = null
                if (d == null || d.to == d.from) return
                val r = if (resize) Roll.resizeNote(steps0, len0, d.from, d.to.len, poly0)
                    else Roll.moveNote(steps0, len0, d.from, d.to.start, d.to.pitch, poly0)
                if (r == null) say0("There is no room there") else onEdit0(r, d.to)
            }
            override fun cancel() { drag = null }
        }
    }

    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE } }
    val dim = Color(0xFF1B1B1B); val dark = Color(0xFF121212); val line = Color(0xFF2C2C2C); val beat = Color(0xFF3E3E3E)

    Canvas(modifier.fillMaxSize().onSizeChanged { vp.size = it; vp.clamp() }.pointerInput(Unit) { gridGestures(vp, handler) }) {
        frame.value
        val n = drawContext.canvas.nativeCanvas
        val lw = vp.lw; val hdr = vp.hdr; val cw = vp.cw; val rh = vp.rh
        val right = size.width
        fun rowTop(pitch: Int) = hdr + (TOP_PITCH - pitch) * rh - vp.sy
        val pitchFrom = (TOP_PITCH - ((size.height + vp.sy - hdr) / rh).toInt() - 1).coerceAtLeast(0)
        val pitchTo = (TOP_PITCH - ((vp.sy) / rh).toInt() + 1).coerceAtMost(TOP_PITCH)
        for (p in pitchFrom..pitchTo) {
            val y = rowTop(p)
            if (y + rh < hdr || y > size.height) continue
            drawRect(if (BLACK[p % 12]) dark else dim, Offset(lw, y), Size(right - lw, rh))
            drawLine(if (p % 12 == 0) beat else line, Offset(lw, y + rh), Offset(right, y + rh), 1f)
        }
        val first = (vp.sx / cw).toInt().coerceAtLeast(0)
        val last = ((vp.sx + right - lw) / cw).toInt().coerceAtMost(length - 1)
        for (i in first..last) {
            val x = lw + i * cw - vp.sx
            drawLine(if (i % 4 == 0) beat else line, Offset(x, hdr), Offset(x, size.height), if (i % 4 == 0) 2f else 1f)
        }
        val d = drag
        for (nt in notes) {
            val shown = if (d != null && nt == d.from) d.to else nt
            val x = lw + shown.start * cw - vp.sx
            val y = rowTop(shown.pitch)
            if (x + shown.len * cw < lw || x > right || y + rh < hdr || y > size.height) continue
            val st = steps.getOrNull(nt.start)
            val vel = (st?.velocity ?: 96) / 127f
            val inset = if (rh > 10f * density) 2f else 0.5f
            val body = Size((shown.len * cw - 3f).coerceAtLeast(2f), (rh - 2 * inset).coerceAtLeast(2f))
            drawRoundRect(color.copy(alpha = 0.45f + 0.55f * vel), Offset(x + 1.5f, y + inset), body, CornerRadius(5f))
            if (st?.accent == true) drawRoundRect(Color.White.copy(alpha = 0.9f), Offset(x + 1.5f, y + inset), Size(5f, body.height), CornerRadius(2f))
            if (nt == selected || (d != null && nt == d.from))
                drawRoundRect(Color.White, Offset(x + 1.5f, y + inset), body, CornerRadius(5f), style = Stroke(2.5f))
            if (rh >= 14f * density && shown.len * cw >= 30f * density) {
                paint.color = android.graphics.Color.BLACK; paint.textSize = rh * 0.5f
                n.drawText(pitchName(shown.pitch), x + 9f, y + rh * 0.7f, paint)
            }
        }
        val ph = playhead()
        if (ph >= 0f) {
            val x = lw + ph * cw - vp.sx
            if (x in lw..right) drawLine(Color.White, Offset(x, hdr), Offset(x, size.height), 3f)
        }
        // the keys and the step numbers over everything
        drawRect(Color(0xFF0C0C0C), Offset(0f, 0f), Size(lw, size.height))
        for (p in pitchFrom..pitchTo) {
            val y = rowTop(p)
            if (y + rh < hdr || y > size.height) continue
            drawRect(if (BLACK[p % 12]) Color(0xFF222222) else Color(0xFFE6E6E6), Offset(0f, y + 0.5f), Size(lw - 2f, (rh - 1f).coerceAtLeast(1f)))
            if ((p % 12 == 0 || p == 60) && rh >= 9f * density) {
                paint.color = android.graphics.Color.BLACK; paint.textSize = (rh * 0.55f).coerceAtMost(13f * density)
                n.drawText(pitchName(p), 4f, y + rh * 0.72f, paint)
            }
        }
        drawRect(Color(0xFF0C0C0C), Offset(0f, 0f), Size(right, hdr))
        paint.textSize = hdr * 0.62f
        val every = if (cw >= 26f * density) 1 else if (cw >= 13f * density) 2 else if (cw >= 7f * density) 4 else 8
        for (i in first..last) {
            if (i % every != 0) continue
            val x = lw + i * cw - vp.sx
            paint.color = if (i % 4 == 0) android.graphics.Color.WHITE else 0xFF8A8A8A.toInt()
            n.drawText("${i + 1}", x + 4f, hdr * 0.78f, paint)
        }
    }
}
