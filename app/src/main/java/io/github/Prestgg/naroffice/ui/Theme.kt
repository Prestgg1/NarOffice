// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.Prestgg.naroffice.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val NarRed = Color(0xFFB5202E)
val WordBlue = Color(0xFF2B5797)
val SheetGreen = Color(0xFF1E7145)
val SlideOrange = Color(0xFFC43E1C)

private val Light = lightColorScheme(
    primary = NarRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD8),
    onPrimaryContainer = Color(0xFF410006),
    secondary = Color(0xFF3E7D4A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC0EFC5),
    onSecondaryContainer = Color(0xFF002109),
    background = Color(0xFFFFFBF8),
    onBackground = Color(0xFF221A1A),
    surface = Color(0xFFFFFBF8),
    onSurface = Color(0xFF221A1A),
    surfaceVariant = Color(0xFFF5DDDB),
    onSurfaceVariant = Color(0xFF534342),
    surfaceContainer = Color(0xFFF8EDEC),
    surfaceContainerHigh = Color(0xFFF2E6E5),
    surfaceContainerLow = Color(0xFFFCF3F2),
    outline = Color(0xFF857372),
    outlineVariant = Color(0xFFD8C2C0),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFFB3B0),
    onPrimary = Color(0xFF68000F),
    primaryContainer = Color(0xFF92001C),
    onPrimaryContainer = Color(0xFFFFDAD8),
    secondary = Color(0xFFA5D3AA),
    onSecondary = Color(0xFF0B3818),
    secondaryContainer = Color(0xFF25502E),
    onSecondaryContainer = Color(0xFFC0EFC5),
    background = Color(0xFF1A1112),
    onBackground = Color(0xFFF0DEDD),
    surface = Color(0xFF1A1112),
    onSurface = Color(0xFFF0DEDD),
    surfaceVariant = Color(0xFF534342),
    onSurfaceVariant = Color(0xFFD8C2C0),
    surfaceContainer = Color(0xFF271D1E),
    surfaceContainerHigh = Color(0xFF322828),
    surfaceContainerLow = Color(0xFF22191A),
    outline = Color(0xFFA08C8B),
    outlineVariant = Color(0xFF534342),
)

@Composable
fun NarTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
