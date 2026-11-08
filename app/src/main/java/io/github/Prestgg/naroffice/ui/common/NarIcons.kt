// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.Prestgg.naroffice.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Material Design icons (Apache 2.0) that are not part of material-icons-core. */
object NarIcons {
    private fun icon(name: String, d: String, autoMirror: Boolean = false): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f, autoMirror = autoMirror)
            .addPath(PathParser().parsePathString(d).toNodes(), fill = SolidColor(Color.Black))
            .build()

    val Remove by lazy { icon("Remove", "M19,13H5v-2h14v2z") }
    val Undo by lazy { icon("Undo", "M12.5,8c-2.65,0 -5.05,0.99 -6.9,2.6L2,7v9h9l-3.62,-3.62c1.39,-1.16 3.16,-1.88 5.12,-1.88 3.54,0 6.55,2.31 7.6,5.5l2.37,-0.78C21.08,11.03 17.15,8 12.5,8z", true) }
    val Redo by lazy { icon("Redo", "M18.4,10.6C16.55,8.99 14.15,8 11.5,8c-4.65,0 -8.58,3.03 -9.96,7.22L3.9,16c1.05,-3.19 4.05,-5.5 7.6,-5.5 1.95,0 3.73,0.72 5.12,1.88L13,16h9V7l-3.6,3.6z", true) }
    val AlignLeft by lazy { icon("AlignLeft", "M15,15H3v2h12v-2zM15,7H3v2h12V7zM3,13h18v-2H3v2zM3,21h18v-2H3v2zM3,3v2h18V3H3z") }
    val AlignCenter by lazy { icon("AlignCenter", "M7,15v2h10v-2H7zM3,21h18v-2H3v2zM3,13h18v-2H3v2zM7,7v2h10V7H7zM3,3v2h18V3H3z") }
    val AlignRight by lazy { icon("AlignRight", "M3,21h18v-2H3v2zM9,17h12v-2H9v2zM3,13h18v-2H3v2zM9,9h12V7H9v2zM3,3v2h18V3H3z") }
    val AlignJustify by lazy { icon("AlignJustify", "M3,21h18v-2H3v2zM3,17h18v-2H3v2zM3,13h18v-2H3v2zM3,9h18V7H3v2zM3,3v2h18V3H3z") }
    val Bullets by lazy { icon("Bullets", "M4,10.5c-0.83,0 -1.5,0.67 -1.5,1.5s0.67,1.5 1.5,1.5 1.5,-0.67 1.5,-1.5 -0.67,-1.5 -1.5,-1.5zM4,4.5c-0.83,0 -1.5,0.67 -1.5,1.5S3.17,7.5 4,7.5 5.5,6.83 5.5,6 4.83,4.5 4,4.5zM4,16.5c-0.83,0 -1.5,0.68 -1.5,1.5s0.68,1.5 1.5,1.5 1.5,-0.68 1.5,-1.5 -0.67,-1.5 -1.5,-1.5zM7,19h14v-2H7v2zM7,13h14v-2H7v2zM7,5v2h14V5H7z", true) }
    val Numbered by lazy { icon("Numbered", "M2,17h2v0.5H3v1h1v0.5H2v1h3v-4H2v1zM3,8h1V4H2v1h1v3zM2,11h1.8L2,13.1v0.9h3v-1H3.2L5,10.9V10H2v1zM7,5v2h14V5H7zM7,19h14v-2H7v2zM7,13h14v-2H7v2z", true) }
    val IndentMore by lazy { icon("IndentMore", "M3,21h18v-2H3v2zM3,8v8l4,-4 -4,-4zM11,17h10v-2H11v2zM3,3v2h18V3H3zM11,9h10V7H11v2zM11,13h10v-2H11v2z", true) }
    val IndentLess by lazy { icon("IndentLess", "M11,17h10v-2H11v2zM3,12l4,4V8l-4,4zM3,21h18v-2H3v2zM3,3v2h18V3H3zM11,9h10V7H11v2zM11,13h10v-2H11v2z", true) }
    val Image by lazy { icon("Image", "M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2zM8.5,13.5l2.5,3.01L14.5,12l4.5,6H5l3.5,-4.5z") }
    val Table by lazy { icon("Table", "M10,10.02h5V21h-5zM17,21h3c1.1,0 2,-0.9 2,-2v-9h-5v11zM20,3H5c-1.1,0 -2,0.9 -2,2v3h19V5c0,-1.1 -0.9,-2 -2,-2zM3,19c0,1.1 0.9,2 2,2h3V10H3v9z") }
    val Save by lazy { icon("Save", "M17,3H5c-1.11,0 -2,0.9 -2,2v14c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2V7l-4,-4zM12,19c-1.66,0 -3,-1.34 -3,-3s1.34,-3 3,-3 3,1.34 3,3 -1.34,3 -3,3zM15,9H5V5h10v4z") }
    val Highlight by lazy { icon("Highlight", "M6,14l3,3v5h6v-5l3,-3V9H6v5zM11,2h2v3h-2V2zM3.5,5.88l1.41,-1.41 2.12,2.12L5.62,8 3.5,5.88zM16.96,6.59l2.12,-2.12 1.41,1.41L18.38,8l-1.42,-1.41z") }
    val TextFields by lazy { icon("TextFields", "M2.5,4v3h5v12h3V7h5V4H2.5zM21.5,9h-9v3h3v7h3v-7h3V9z") }
    val Copy by lazy { icon("Copy", "M16,1L4,1c-1.1,0 -2,0.9 -2,2v14h2L4,3h12L16,1zM19,5L8,5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h11c1.1,0 2,-0.9 2,-2L21,7c0,-1.1 -0.9,-2 -2,-2zM19,21L8,21L8,7h11v14z") }
    val Paste by lazy { icon("Paste", "M19,2h-4.18C14.4,0.84 13.3,0 12,0c-1.3,0 -2.4,0.84 -2.82,2L5,2c-1.1,0 -2,0.9 -2,2v16c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2L21,4c0,-1.1 -0.9,-2 -2,-2zM12,2c0.55,0 1,0.45 1,1s-0.45,1 -1,1 -1,-0.45 -1,-1 0.45,-1 1,-1zM19,20L5,20L5,4h2v3h10L17,4h2v16z") }
    val Cut by lazy { icon("Cut", "M9.64,7.64c0.23,-0.5 0.36,-1.05 0.36,-1.64 0,-2.21 -1.79,-4 -4,-4S2,3.79 2,6s1.79,4 4,4c0.59,0 1.14,-0.13 1.64,-0.36L10,12l-2.36,2.36C7.14,14.13 6.59,14 6,14c-2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4c0,-0.59 -0.13,-1.14 -0.36,-1.64L12,14l7,7h3v-1L9.64,7.64zM6,8c-1.1,0 -2,-0.89 -2,-2s0.9,-2 2,-2 2,0.89 2,2 -0.9,2 -2,2zM6,20c-1.1,0 -2,-0.89 -2,-2s0.9,-2 2,-2 2,0.89 2,2 -0.9,2 -2,2zM12,12.5c-0.28,0 -0.5,-0.22 -0.5,-0.5s0.22,-0.5 0.5,-0.5 0.5,0.22 0.5,0.5 -0.22,0.5 -0.5,0.5zM19,3l-6,6 2,2 7,-7L22,3z") }
    val Fill by lazy { icon("Fill", "M16.56,8.94L7.62,0 6.21,1.41l2.38,2.38 -5.15,5.15c-0.59,0.59 -0.59,1.54 0,2.12l5.5,5.5c0.29,0.29 0.68,0.44 1.06,0.44s0.77,-0.15 1.06,-0.44l5.5,-5.5c0.59,-0.58 0.59,-1.53 0,-2.12zM5.21,10L10,5.21 14.79,10L5.21,10zM19,11.5s-2,2.17 -2,3.5c0,1.1 0.9,2 2,2s2,-0.9 2,-2c0,-1.33 -2,-3.5 -2,-3.5z") }
    val Function by lazy { icon("Function", "M15.6,5.29c-1.1,-0.1 -2.07,0.71 -2.17,1.82L13.18,10H16v2h-3l-0.44,5.07c-0.2,2.2 -2.14,3.83 -4.34,3.63 -1.31,-0.11 -2.43,-0.85 -3.06,-1.86l1.52,-1.5c0.25,0.89 1.03,1.56 1.99,1.64 1.1,0.1 2.07,-0.71 2.17,-1.82L11,12H8v-2h3.17l0.27,-3.07c0.19,-2.2 2.13,-3.83 4.34,-3.63 1.31,0.11 2.43,0.85 3.06,1.86l-1.52,1.5c-0.25,-0.89 -1.03,-1.56 -1.99,-1.64z") }
    val Slideshow by lazy { icon("Slideshow", "M10,8v8l5,-4 -5,-4zM19,3L5,3c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2L21,5c0,-1.1 -0.9,-2 -2,-2zM19,19L5,19L5,5h14v14z") }
    val Notes by lazy { icon("Notes", "M3,18h12v-2H3v2zM3,6v2h18V6H3zM3,13h18v-2H3v2z") }
    val ViewList by lazy { icon("ViewList", "M3,14h4v-4H3v4zM3,19h4v-4H3v4zM3,9h4V5H3v4zM8,14h13v-4H8v4zM8,19h13v-4H8v4zM8,5v4h13V5H8z") }
    val Duplicate by lazy { icon("Duplicate", "M4,6H2v14c0,1.1 0.9,2 2,2h14v-2H4V6zM20,2H8c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2zM19,11h-4v4h-2v-4H9V9h4V5h2v4h4v2z") }
    val Sort by lazy { icon("Sort", "M3,18h6v-2H3v2zM3,6v2h18V6H3zM3,13h12v-2H3v2z") }
    val Wrap by lazy { icon("Wrap", "M4,19h6v-2H4v2zM20,5H4v2h16V5zM17,11H4v2h13.25c1.1,0 2,0.9 2,2s-0.9,2 -2,2H15v-2l-3,3 3,3v-2h2c2.21,0 4,-1.79 4,-4s-1.79,-4 -4,-4z") }
    val Fullscreen by lazy { icon("Fullscreen", "M7,14L5,14v5h5v-2L7,17v-3zM5,10h2L7,7h3L10,5L5,5v5zM17,17h-3v2h5v-5h-2v3zM14,5v2h3v3h2L19,5h-5z") }
}
