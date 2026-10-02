package com.example.scifilauncher

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

/** Shared HUD dressing so every wallpaper reads as hacker/terminal/cybersecurity, not generic
 * sci-fi - corner targeting brackets and a faint scanline overlay, the same visual shorthand
 * this app already uses elsewhere (Security screen, GlitchCoverView). Applied on top of a
 * renderer's own drawing, not a replacement for it. */
fun DrawScope.drawHudCornerBrackets(color: Color, alpha: Float = 0.5f, inset: Float = 24f, armLength: Float = 28f) {
    val w = size.width
    val h = size.height
    val strokeWidth = 2f
    // Top-left
    drawLine(color.copy(alpha = alpha), Offset(inset, inset), Offset(inset + armLength, inset), strokeWidth)
    drawLine(color.copy(alpha = alpha), Offset(inset, inset), Offset(inset, inset + armLength), strokeWidth)
    // Top-right
    drawLine(color.copy(alpha = alpha), Offset(w - inset, inset), Offset(w - inset - armLength, inset), strokeWidth)
    drawLine(color.copy(alpha = alpha), Offset(w - inset, inset), Offset(w - inset, inset + armLength), strokeWidth)
    // Bottom-left
    drawLine(color.copy(alpha = alpha), Offset(inset, h - inset), Offset(inset + armLength, h - inset), strokeWidth)
    drawLine(color.copy(alpha = alpha), Offset(inset, h - inset), Offset(inset, h - inset - armLength), strokeWidth)
    // Bottom-right
    drawLine(color.copy(alpha = alpha), Offset(w - inset, h - inset), Offset(w - inset - armLength, h - inset), strokeWidth)
    drawLine(color.copy(alpha = alpha), Offset(w - inset, h - inset), Offset(w - inset, h - inset - armLength), strokeWidth)
}

fun DrawScope.drawFaintScanlines(color: Color, alpha: Float = 0.05f, spacingPx: Float = 5f) {
    var y = 0f
    while (y < size.height) {
        drawLine(color.copy(alpha = alpha), Offset(0f, y), Offset(size.width, y), 1f)
        y += spacingPx
    }
}
