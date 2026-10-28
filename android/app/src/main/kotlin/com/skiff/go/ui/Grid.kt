// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Skiff Go contributors. Based on the Skiff firmware (illuminus5) and Felucca (Hügelton Instruments);
// see NOTICE.md.
package com.skiff.go.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Scroll and zoom of a grid (the piano roll, the drum grid). Content starts at ([lw], [hdr]) inside the canvas: a label
 * column on the left, the step numbers on top. Pinch zooms along the axis the fingers moved more (locked until they lift),
 * around the point between them; the pattern's end steps stick to the edges of the screen: it can't be zoomed out past
 * "all [cols] steps across", and near that it snaps to it exactly; scrolled to either end, its first or last step is flush
 * with the screen's edge.
 */
class Viewport {
    var sx by mutableFloatStateOf(0f)
    var sy by mutableFloatStateOf(0f)
    var cw by mutableFloatStateOf(40f)
    var rh by mutableFloatStateOf(22f)
    var size by mutableStateOf(IntSize.Zero)
    var d = 1f
    var lw = 0f
    var hdr = 0f
    var cols = 1
    var rows = 1
    /** The drum grid: the lanes always fill the height (no vertical scroll or zoom). */
    var lockV = false

    val w get() = size.width.toFloat()
    val h get() = size.height.toFloat()
    val fitCw get() = if (w > lw) (w - lw) / cols else 14f * d
    /** The biggest a step gets: 120 dp, or the fit when a short pattern on a wide screen needs more to fill it. */
    private val maxCw get() = max(120f * d, fitCw)

    fun clamp() {
        if (lockV) rh = max(1f, h - hdr) / rows
        else rh = rh.coerceIn(max(3f * d, (h - hdr) / rows), 44f * d)
        cw = cw.coerceIn(fitCw, maxCw)
        sx = sx.coerceIn(0f, max(0f, cols * cw - (w - lw)))
        sy = sy.coerceIn(0f, max(0f, rows * rh - (h - hdr)))
    }

    /** All the steps across the screen, the first at its left edge and the last at its right. */
    fun fit() { cw = fitCw; sx = 0f; clamp() }

    fun scrollBy(dx: Float, dy: Float) { sx += dx; sy += dy; clamp() }

    /** Zooms by [zx] and [zy] around [c] (canvas coordinates), the content point under it staying where it is. */
    fun zoom(zx: Float, zy: Float, c: Offset) {
        val ox = (sx + c.x - lw) / cw
        val oy = (sy + c.y - hdr) / rh
        cw = (cw * zx).coerceIn(fitCw, maxCw)
        if (!lockV) rh = (rh * zy).coerceIn(max(3f * d, (h - hdr) / rows), 44f * d)
        sx = ox * cw - (c.x - lw)
        sy = oy * rh - (c.y - hdr)
        if (abs(cw - fitCw) * cols < 24f * d) { cw = fitCw; sx = 0f }          // the magnet: the whole pattern fits
        clamp()
    }

    /** Scrolls a little when a finger dragging a note is near an edge of the grid. */
    fun nudge(p: Offset) {
        val edge = 36f * d
        val step = 10f * d
        if (p.x > w - edge) scrollBy(step, 0f) else if (p.x < lw + edge) scrollBy(-step, 0f)
        if (!lockV) { if (p.y > h - edge) scrollBy(0f, step) else if (p.y < hdr + edge) scrollBy(0f, -step) }
    }

    fun colAt(x: Float) = floor((x - lw + sx) / cw).toInt()
    fun rowAt(y: Float) = floor((y - hdr + sy) / rh).toInt()
}

/** What a grid does with one finger; a new one is made for each gesture. */
interface GridHandler {
    fun tap(p: Offset) {}
    /** The finger moved off where it went down: true when it takes hold of something (a note) rather than scrolling. */
    fun beginMove(p: Offset): Boolean = false
    /** Held without moving; true when the finger then drags what it holds. */
    fun longPress(p: Offset): Boolean = false
    fun drag(p: Offset) {}
    fun release(p: Offset) {}
    fun cancel() {}
}

/**
 * One finger: tap, hold, drag what it took hold of, or scroll. Two fingers: pinch zoom (and scroll with the point between
 * them), which cancels what one had started.
 */
suspend fun PointerInputScope.gridGestures(vp: Viewport, handler: () -> GridHandler) {
    val slop = viewConfiguration.touchSlop
    val longMs = viewConfiguration.longPressTimeoutMillis
    awaitEachGesture {
        val h = handler()
        val down = awaitFirstDown(requireUnconsumed = false)
        var claimed = false
        var longDone = false
        var moved = false
        var multi = false
        var zoomAxis = 0                       // 0 undecided, 1 horizontal, 2 vertical; locked until every finger lifts
        var spanX0 = -1f
        var spanY0 = -1f
        var last = down.position
        val t0 = System.currentTimeMillis()
        while (true) {
            val event = if (!longDone && !moved && !multi) {
                val left = (longMs - (System.currentTimeMillis() - t0)).coerceAtLeast(1L)
                withTimeoutOrNull(left) { awaitPointerEvent() }
            } else awaitPointerEvent()
            if (event == null) {
                longDone = true
                claimed = h.longPress(down.position)
                continue
            }
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size >= 2) {
                if (claimed) { h.cancel(); claimed = false }
                multi = true
                val a = pressed[0]; val b = pressed[1]
                val spanX = abs(a.position.x - b.position.x)
                val spanY = abs(a.position.y - b.position.y)
                if (spanX0 < 0f) { spanX0 = spanX; spanY0 = spanY }
                val zx = run {
                    val old = abs(a.previousPosition.x - b.previousPosition.x)
                    if (old > 8f) (spanX / old).coerceIn(0.5f, 2f) else 1f
                }
                val zy = run {
                    val old = abs(a.previousPosition.y - b.previousPosition.y)
                    if (old > 8f) (spanY / old).coerceIn(0.5f, 2f) else 1f
                }
                if (zoomAxis == 0) {
                    val dx = abs(spanX - spanX0)
                    val dy = abs(spanY - spanY0)
                    if (max(dx, dy) > 16f * vp.d) zoomAxis = if (dx > dy) 1 else 2
                }
                val centre = event.calculateCentroid(useCurrent = true)
                vp.zoom(if (zoomAxis == 2) 1f else zx, if (zoomAxis == 1) 1f else 1f + (zy - 1f) * 0.45f, centre)
                event.changes.forEach { it.consume() }
            } else if (pressed.size == 1) {
                val c = pressed[0]
                if (multi) c.consume()
                else if (claimed) { h.drag(c.position); vp.nudge(c.position); c.consume() }
                else if (longDone) c.consume()                                     // held and let be: no scroll after it
                else if (!moved && (c.position - down.position).getDistance() > slop) {
                    moved = true
                    if (h.beginMove(down.position)) { claimed = true; h.drag(c.position); vp.nudge(c.position) }
                    else vp.scrollBy(down.position.x - c.position.x, down.position.y - c.position.y)
                    c.consume()
                } else if (moved) {
                    vp.scrollBy(last.x - c.position.x, last.y - c.position.y)
                    c.consume()
                }
                last = c.position
            } else {
                val p = event.changes.first().position
                if (claimed) h.release(p)
                else if (!moved && !multi && !longDone) h.tap(down.position)
                event.changes.forEach { it.consume() }
                break
            }
        }
    }
}
