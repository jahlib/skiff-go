// SPDX-License-Identifier: GPL-3.0-only
package com.skiff.go.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportTest {
    private fun vp(cols: Int = 16, w: Int = 1000, h: Int = 400) = Viewport().apply {
        d = 1f; lw = 44f; hdr = 20f; this.cols = cols; rows = 109; size = IntSize(w, h); fit()
    }

    @Test fun fitsTheWholePatternAcrossTheScreen() {
        val v = vp()
        assertEquals((1000f - 44f) / 16, v.cw, 0.001f)
        assertEquals(0f, v.sx, 0f)
        assertEquals(v.cols * v.cw, v.w - v.lw, 0.01f)             // the first step at the left edge, the last at the right
    }

    @Test fun cannotBeZoomedOutPastTheFit() {
        val v = vp()
        v.zoom(0.5f, 1f, Offset(500f, 200f))
        assertEquals(v.fitCw, v.cw, 0.001f)
        assertEquals(0f, v.sx, 0f)
    }

    @Test fun zoomsInAroundTheFingersAndScrollsToBothEnds() {
        val v = vp()
        val centre = Offset(500f, 200f)
        val before = (v.sx + centre.x - v.lw) / v.cw                  // the step under the fingers
        v.zoom(2f, 1f, centre)
        assertEquals(v.fitCw * 2, v.cw, 0.01f)
        assertEquals(before, (v.sx + centre.x - v.lw) / v.cw, 0.01f)  // it stays under them
        v.scrollBy(10_000f, 0f)
        assertEquals(v.cols * v.cw, v.sx + (v.w - v.lw), 0.01f)       // scrolled to the end: the last step flush right
        v.scrollBy(-10_000f, 0f)
        assertEquals(0f, v.sx, 0f)                                    // and to the start: the first flush left
    }

    @Test fun snapsToTheFitWhenCloseToIt() {
        val v = vp()
        v.zoom(1.02f, 1f, Offset(500f, 200f))                         // a hair in: 16 steps x 1 dp is inside the magnet
        assertEquals(v.fitCw, v.cw, 0.001f)
        v.zoom(1.5f, 1f, Offset(500f, 200f))
        assertTrue(v.cw > v.fitCw * 1.4f)                             // a real zoom is not caught
    }

    @Test fun zoomIsCappedAndTheDrumGridKeepsItsLanesInView() {
        val v = vp()
        repeat(20) { v.zoom(2f, 1f, Offset(300f, 100f)) }
        assertEquals(120f, v.cw, 0.001f)
        val g = vp().apply { rows = 8; lockV = true; fit() }
        g.zoom(1f, 2f, Offset(300f, 100f))
        assertEquals((g.h - g.hdr) / 8, g.rh, 0.001f)                 // the lanes always fill the height
        assertEquals(0f, g.sy, 0f)
    }

    @Test fun aShortPatternOnAWideScreenStillFits() {
        val v = vp(cols = 4, w = 1400)
        assertEquals((1400f - 44f) / 4, v.fitCw, 0.01f)
        assertEquals(v.fitCw, v.cw, 0.01f)                            // it fills the screen, the first step left, the last right
        v.zoom(0.5f, 1f, Offset(300f, 100f))
        assertEquals(v.fitCw, v.cw, 0.01f)
    }
}
