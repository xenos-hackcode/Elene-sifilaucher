package com.example.scifilauncher

import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min

private enum class DragHandle { NONE, MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }
private const val MIN_REGION_PX = 120f
private const val HANDLE_GRAB_RADIUS_PX = 56f

/** A screenshot-crop-style selector: drag the corners to resize, drag inside to move. Compose
 * pointer coordinates here are raw window pixels, the same space ScreenRecordService measures
 * via WindowManager.getRealMetrics() for the actual capture - since this activity is already
 * edge-to-edge, they line up directly with no extra conversion needed. */
@Composable
fun CropSelectorOverlay(
    themeColor: Color,
    backgroundBitmap: android.graphics.Bitmap?,
    onConfirm: (Rect) -> Unit,
    onCancel: () -> Unit
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var rect by remember(containerSize) {
        mutableStateOf(
            if (containerSize == IntSize.Zero) {
                androidx.compose.ui.geometry.Rect.Zero
            } else {
                androidx.compose.ui.geometry.Rect(
                    containerSize.width * 0.15f, containerSize.height * 0.25f,
                    containerSize.width * 0.85f, containerSize.height * 0.65f
                )
            }
        )
    }
    var activeDrag by remember { mutableStateOf(DragHandle.NONE) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { containerSize = it }
    ) {
        // A real snapshot of the screen so you're aligning the region against actual content -
        // without this the whole selector is just a blank void with no reference point.
        if (backgroundBitmap != null) {
            Image(
                bitmap = backgroundBitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize()
            )
        }
        if (containerSize != IntSize.Zero) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(containerSize) {
                        detectDragGestures(
                            onDragStart = { pos ->
                                activeDrag = when {
                                    (pos - rect.topLeft).getDistance() < HANDLE_GRAB_RADIUS_PX -> DragHandle.TOP_LEFT
                                    (pos - Offset(rect.right, rect.top)).getDistance() < HANDLE_GRAB_RADIUS_PX -> DragHandle.TOP_RIGHT
                                    (pos - Offset(rect.left, rect.bottom)).getDistance() < HANDLE_GRAB_RADIUS_PX -> DragHandle.BOTTOM_LEFT
                                    (pos - Offset(rect.right, rect.bottom)).getDistance() < HANDLE_GRAB_RADIUS_PX -> DragHandle.BOTTOM_RIGHT
                                    rect.contains(pos) -> DragHandle.MOVE
                                    else -> DragHandle.NONE
                                }
                            },
                            onDragEnd = { activeDrag = DragHandle.NONE },
                            onDragCancel = { activeDrag = DragHandle.NONE }
                        ) { change, dragAmount ->
                            change.consume()
                            val w = containerSize.width.toFloat()
                            val h = containerSize.height.toFloat()
                            rect = when (activeDrag) {
                                DragHandle.MOVE -> {
                                    val nx = (rect.left + dragAmount.x).coerceIn(0f, w - rect.width)
                                    val ny = (rect.top + dragAmount.y).coerceIn(0f, h - rect.height)
                                    androidx.compose.ui.geometry.Rect(nx, ny, nx + rect.width, ny + rect.height)
                                }
                                DragHandle.TOP_LEFT -> androidx.compose.ui.geometry.Rect(
                                    min((rect.left + dragAmount.x).coerceIn(0f, w), rect.right - MIN_REGION_PX),
                                    min((rect.top + dragAmount.y).coerceIn(0f, h), rect.bottom - MIN_REGION_PX),
                                    rect.right, rect.bottom
                                )
                                DragHandle.TOP_RIGHT -> androidx.compose.ui.geometry.Rect(
                                    rect.left,
                                    min((rect.top + dragAmount.y).coerceIn(0f, h), rect.bottom - MIN_REGION_PX),
                                    max((rect.right + dragAmount.x).coerceIn(0f, w), rect.left + MIN_REGION_PX),
                                    rect.bottom
                                )
                                DragHandle.BOTTOM_LEFT -> androidx.compose.ui.geometry.Rect(
                                    min((rect.left + dragAmount.x).coerceIn(0f, w), rect.right - MIN_REGION_PX),
                                    rect.top,
                                    rect.right,
                                    max((rect.bottom + dragAmount.y).coerceIn(0f, h), rect.top + MIN_REGION_PX)
                                )
                                DragHandle.BOTTOM_RIGHT -> androidx.compose.ui.geometry.Rect(
                                    rect.left, rect.top,
                                    max((rect.right + dragAmount.x).coerceIn(0f, w), rect.left + MIN_REGION_PX),
                                    max((rect.bottom + dragAmount.y).coerceIn(0f, h), rect.top + MIN_REGION_PX)
                                )
                                DragHandle.NONE -> rect
                            }
                        }
                    }
            ) {
                val scrim = Color.Black.copy(alpha = 0.65f)
                drawRect(scrim, topLeft = Offset(0f, 0f), size = Size(size.width, rect.top))
                drawRect(scrim, topLeft = Offset(0f, rect.bottom), size = Size(size.width, size.height - rect.bottom))
                drawRect(scrim, topLeft = Offset(0f, rect.top), size = Size(rect.left, rect.height))
                drawRect(scrim, topLeft = Offset(rect.right, rect.top), size = Size(size.width - rect.right, rect.height))
                drawRect(themeColor, topLeft = rect.topLeft, size = rect.size, style = Stroke(width = 4f))
                listOf(
                    rect.topLeft, Offset(rect.right, rect.top),
                    Offset(rect.left, rect.bottom), Offset(rect.right, rect.bottom)
                ).forEach { corner -> drawCircle(themeColor, radius = 14f, center = corner) }
            }
        }

        Text(
            text = "Drag the corners to resize, drag inside to move",
            color = Color.White,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp)
        )

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "CANCEL",
                color = Color.Gray,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .clickable { onCancel() }
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            )
            Text(
                text = "USE THIS AREA",
                color = Color.Black,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(themeColor)
                    .clickable {
                        onConfirm(Rect(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt()))
                    }
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            )
        }
    }
}
