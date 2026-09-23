package com.videoforge.nativeeditor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

/**
 * A compact color-wheel control modeled after the three-way (Shadows / Midtones / Highlights)
 * wheels in professional NLEs. Reports a normalized (x, y) offset in -1f..1f as the user drags
 * the indicator dot; the caller decides what real color parameter that offset drives — see
 * EditorFeaturePanel's "adjust" panel, which maps the three wheels onto the existing
 * tint/brightness, hue/saturation, and temperature/contrast controls (this app does not have a
 * separate lift/gamma/gain render pipeline, so the wheels are a genuine interactive front end
 * over the color adjustments that already exist rather than a new color-science stage).
 */
@Composable
fun ColorWheel(
    label: String,
    offsetX: Float,
    offsetY: Float,
    onDrag: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            offsetX = offsetX,
            offsetY = offsetY,
            onDrag = onDrag
        )
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color(0xFFB7C0D0), fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Box(offsetX: Float, offsetY: Float, onDrag: (Float, Float) -> Unit) {
    androidx.compose.foundation.layout.Box(
        Modifier
            .size(72.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    val nx = (offsetX + dragAmount.x / (size.width / 2f)).coerceIn(-1f, 1f)
                    val ny = (offsetY + dragAmount.y / (size.height / 2f)).coerceIn(-1f, 1f)
                    onDrag(nx, ny)
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = min(this.size.width, this.size.height) / 2f
            drawCircle(
                brush = Brush.sweepGradient(
                    listOf(
                        Color(0xFFFF3B30), Color(0xFFFFCC00), Color(0xFF34C759),
                        Color(0xFF32ADE6), Color(0xFF5856D6), Color(0xFFFF2D55), Color(0xFFFF3B30)
                    )
                ),
                radius = radius,
                center = center
            )
            drawCircle(
                color = Color.Black.copy(alpha = 0.32f),
                radius = radius * 0.34f,
                center = center
            )
            drawCircle(
                color = Color.Black.copy(alpha = 0.4f),
                radius = radius,
                center = center,
                style = Stroke(width = 1.5f)
            )
            val dot = Offset(
                center.x + offsetX * radius * 0.86f,
                center.y + offsetY * radius * 0.86f
            )
            drawCircle(color = Color.White, radius = 6f, center = dot)
            drawCircle(color = Color.Black.copy(alpha = 0.55f), radius = 6f, center = dot, style = Stroke(width = 1.5f))
        }
    }
}

@Composable
fun ColorWheelsRow(
    shadowsX: Float, shadowsY: Float, onShadows: (Float, Float) -> Unit,
    midtonesX: Float, midtonesY: Float, onMidtones: (Float, Float) -> Unit,
    highlightsX: Float, highlightsY: Float, onHighlights: (Float, Float) -> Unit,
    labelShadows: String, labelMidtones: String, labelHighlights: String
) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxSize(),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly
    ) {
        ColorWheel(labelShadows, shadowsX, shadowsY, onShadows)
        ColorWheel(labelMidtones, midtonesX, midtonesY, onMidtones)
        ColorWheel(labelHighlights, highlightsX, highlightsY, onHighlights)
    }
}
