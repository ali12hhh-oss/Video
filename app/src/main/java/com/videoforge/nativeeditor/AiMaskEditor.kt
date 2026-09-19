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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

private data class MaskStroke(
    val points: List<Offset>,
    val erase: Boolean,
    val size: Float
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
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

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
                    Modifier.fillMaxWidth().height(330.dp).onSizeChanged { canvasSize = it }.then(
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
                                        strokes = strokes + MaskStroke(currentPoints, erase, brushSize)
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
                                    radius = stroke.size / 2f,
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
                                    style = Stroke(width = stroke.size)
                                )
                            }
                        }
                        strokes.forEach(::drawStroke)
                        if (currentPoints.isNotEmpty()) drawStroke(MaskStroke(currentPoints, erase, brushSize))
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
                        leadingIcon = { Icon(Icons.Default.Brush, null) }
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
                            viewWidth = canvasSize.width.toFloat().coerceAtLeast(1f),
                            viewHeight = canvasSize.height.toFloat().coerceAtLeast(1f)
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

    // ContentScale.Fit letterboxes the source image. Map finger coordinates
    // back through that exact displayed image rectangle so the mask aligns
    // with the real pixels rather than the dialog bounds.
    val fitScale = minOf(viewWidth / width.toFloat(), viewHeight / height.toFloat())
    val displayedWidth = width * fitScale
    val displayedHeight = height * fitScale
    val left = (viewWidth - displayedWidth) / 2f
    val top = (viewHeight - displayedHeight) / 2f

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = android.graphics.Color.WHITE
    }

    fun mapX(x: Float) = ((x - left) / fitScale).coerceIn(0f, width.toFloat())
    fun mapY(y: Float) = ((y - top) / fitScale).coerceIn(0f, height.toFloat())

    strokes.forEach { stroke ->
        paint.strokeWidth = stroke.size / fitScale
        paint.xfermode = if (stroke.erase) {
            PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        } else {
            null
        }

        if (stroke.points.size == 1) {
            val p = stroke.points.first()
            if (p.x in left..(left + displayedWidth) && p.y in top..(top + displayedHeight)) {
                canvas.drawCircle(
                    mapX(p.x),
                    mapY(p.y),
                    paint.strokeWidth / 2f,
                    paint
                )
            }
        } else {
            val path = android.graphics.Path()
            val first = stroke.points.first()
            path.moveTo(mapX(first.x), mapY(first.y))
            stroke.points.drop(1).forEach { p ->
                path.lineTo(mapX(p.x), mapY(p.y))
            }
            canvas.drawPath(path, paint)
        }
    }

    paint.xfermode = null
    canvas.setBitmap(null)
    return mask
}
}
