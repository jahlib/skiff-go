// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Skiff Go contributors. Based on the Skiff firmware (illuminus5) and Felucca (Hügelton Instruments);
// see NOTICE.md.
package com.skiff.go.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.skiff.go.R

// JetBrains Mono (variable weight), bundled under the SIL OFL 1.1 — see fonts/OFL.txt.
val SkiffFontFamily = FontFamily(Font(R.font.jetbrains_mono, FontWeight.Normal))

private val base = Typography()

private val SkiffTypography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = SkiffFontFamily),
    displayMedium = base.displayMedium.copy(fontFamily = SkiffFontFamily),
    displaySmall = base.displaySmall.copy(fontFamily = SkiffFontFamily),
    headlineLarge = base.headlineLarge.copy(fontFamily = SkiffFontFamily),
    headlineMedium = base.headlineMedium.copy(fontFamily = SkiffFontFamily),
    headlineSmall = base.headlineSmall.copy(fontFamily = SkiffFontFamily),
    titleLarge = base.titleLarge.copy(fontFamily = SkiffFontFamily),
    titleMedium = base.titleMedium.copy(fontFamily = SkiffFontFamily),
    titleSmall = base.titleSmall.copy(fontFamily = SkiffFontFamily),
    bodyLarge = base.bodyLarge.copy(fontFamily = SkiffFontFamily),
    bodyMedium = base.bodyMedium.copy(fontFamily = SkiffFontFamily),
    bodySmall = base.bodySmall.copy(fontFamily = SkiffFontFamily),
    labelLarge = base.labelLarge.copy(fontFamily = SkiffFontFamily),
    labelMedium = base.labelMedium.copy(fontFamily = SkiffFontFamily),
    labelSmall = base.labelSmall.copy(fontFamily = SkiffFontFamily),
)

/** The six tracks' colours of Skiff's THEME SKIFF (tools/gen_ui_palettes.py TRACKS): vermilion teal amber violet pink lime. */
val TrackColors = listOf(
    Color(0xFFFF5A36), Color(0xFF22C3B4), Color(0xFFFFBF00),
    Color(0xFF9580FF), Color(0xFFFF6FAE), Color(0xFFA3E635),
)

fun trackColor(track: Int): Color = TrackColors[track.mod(TrackColors.size)]

/** THEME SKIFF's BLOCKS look: neutral tiles on near black, white values. */
private val Dark = darkColorScheme(
    primary = Color(0xFF22C3B4),
    onPrimary = Color.Black,
    secondary = Color(0xFF9580FF),
    tertiary = Color(0xFFFF5A36),
    error = Color(0xFFFF5A36),
    background = Color(0xFF0C0C0C),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF141414),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF1E1E1E),
    onSurfaceVariant = Color(0xFFB4B4B4),
    outline = Color(0xFF2C2C2C),
)

@Composable
fun SkiffTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Dark, typography = SkiffTypography, content = content)
}
