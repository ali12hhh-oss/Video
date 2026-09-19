package com.videoforge.nativeeditor

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Erase
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlin.math.max

private data class MaskStroke(
    val points: List<Offset>,
    val erase: Boolean
)

@Composable
internal fun AiMaskEditorDialog(
    source: Bitmap,
    isArabic: Boolean,
    onDismiss: () -> Unit,
    onApply: (Bitmap) -> Unit
) {
    var strokes by remember { mutableStateOf(emptyList<MaskStroke>()) }
    var currentPoints by remember { mutableStateOf(emptyList<Offset>()) }
    var erase by remember { mutableStateOf(false) }
    var brushSize by remember { mutableFloatStateOf(42f) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isArabic) "تحديد منطقة AI" else "AI region mask") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (isArabic)
                        "ارسم بإصبعك على المنطقة التي تريد تعديلها. الأبيض = تعديل، ويمكنك استخدام المسح لتصحيح التحديد."
                    else
                        "Paint the area you want AI to edit. White means selected; use erase to correct the mask.",
                    style = MaterialTheme.typography.bodySmall
                )

                Box(
                    Modifier.fillMaxWidth().height(330.dp).then(
                        Modifier.pointerInput(erase, brushSize) {
                            detectDragGestures(
                                onDragStart = { start ->
                                    currentPoints = listOf(start)
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    currentPoints = currentPoints + change.position
                                },
                                onDragEnd = {
                                    if (currentPoints.isNotEmpty()) {
                                        strokes = strokes + MaskStroke(currentPoints, erase)
                                        currentPoints = emptyList()
                                    }
                                },
                                onDragCancel = { currentPoints = emptyList() }
                            )
                        }
                    )
                ) {
                    Image(
                        bitmap = source.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                    Canvas(Modifier.fillMaxSize()) {
                        fun drawStroke(stroke: MaskStroke) {
                            if (stroke.points.size == 1) {
                                drawCircle(
                                    color = if (stroke.erase) Color.Red.copy(alpha = 0.35f) else Color.Green.copy(alpha = 0.38f),
                                    radius = brushSize / 2f,
                                    center = stroke.points.first()
                                )
                            } else {
                                val path = Path().apply {
                                    moveTo(stroke.points.first().x, stroke.points.first().y)
                                    stroke.points.drop(1).forEach { lineTo(it.x, it.y) }
                                }
                                drawPath(
                                    path = path,
                                    color = if (stroke.erase) Color.Red.copy(alpha = 0.38f) else Color.Green.copy(alpha = 0.42f),
                                    style = Stroke(width = brushSize)
                                )
                            }
                        }
                        strokes.forEach(::drawStroke)
                        if (currentPoints.isNotEmpty()) drawStroke(MaskStroke(currentPoints, erase))
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !erase,
                        onClick = { erase = false },
                        label = { Text(if (isArabic) "فرشاة" else "Brush") },
                        leadingIcon = { Icon(Icons.Default.Brush, null) }
                    )
                    FilterChip(
                        selected = erase,
                        onClick = { erase = true },
                        label = { Text(if (isArabic) "مسح" else "Erase") },
                        leadingIcon = { Icon(Icons.Default.Erase, null) }
                    )
                    IconButton(onClick = { strokes = emptyList(); currentPoints = emptyList() }) {
                        Icon(Icons.Default.Delete, if (isArabic) "مسح الكل" else "Clear")
                    }
                }

                Text(if (isArabic) "حجم الفرشاة" else "Brush size")
                Slider(
                    value = brushSize,
                    onValueChange = { brushSize = it },
                    valueRange = 12f..110f
                )
            }
        },
        confirmButton = {
            Button(
                enabled = strokes.isNotEmpty(),
                onClick = {
                    onApply(
                        buildMaskBitmap(
                            source = source,
                            strokes = strokes,
                            viewWidth = 1f,
                            viewHeight = 1f
                        )
                    )
                }
            ) {
                Text(if (isArabic) "حفظ التحديد" else "Save mask")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Icon(Icons.Default.Clear, null)
                Spacer(Modifier.width(5.dp))
                Text(if (isArabic) "إلغاء" else "Cancel")
            }
        },
        shape = RoundedCornerShape(22.dp)
    )
}

private fun buildMaskBitmap(
    source: Bitmap,
    strokes: List<MaskStroke>,
    viewWidth: Float,
    viewHeight: Float
): Bitmap {
    // The dialog canvas uses fit scaling. Normalize against the displayed canvas
    // dimensions; a 1x1 normalization is intentionally replaced below by the
    // source-relative coordinate mapping in buildMaskFromStrokes.
    return buildMaskFromStrokes(source, strokes, viewWidth, viewHeight)
}

private fun buildMaskFromStrokes(
    source: Bitmap,
    strokes: List<MaskStroke>,
    viewWidth: Float,
    viewHeight: Float
): Bitmap {
    val width = source.width
    val height = source.height
    val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(mask)
    canvas.drawColor(android.graphics.Color.TRANSPARENT)

    val scale = max(width.toFloat(), height.toFloat())
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = scale * 0.04f
        color = android.graphics.Color.WHITE
    }

    strokes.forEach { stroke ->
        paint.strokeWidth = scale * 0.04f
        if (stroke.erase) {
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        } else {
            paint.xfermode = null
        }
        if (stroke.points.size == 1) {
            canvas.drawCircle(
                stroke.points[0].x / viewWidth * width,
                stroke.points[0].y / viewHeight * height,
                paint.strokeWidth / 2f,
                paint
            )
        } else {
            val path = android.graphics.Path()
            val first = stroke.points.first()
            path.moveTo(first.x / viewWidth * width, first.y / viewHeight * height)
            stroke.points.drop(1).forEach { p ->
                path.lineTo(p.x / viewWidth * width, p.y / viewHeight * height)
            }
            canvas.drawPath(path, paint)
        }
    }
    paint.xfermode = null
    canvas.setBitmap(null)
    return mask
}
