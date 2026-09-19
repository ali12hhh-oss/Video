package com.videoforge.nativeeditor

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentationResult
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.tasks.await

/**
 * Real on-device AI image operations used by AI Photo Studio.
 *
 * This engine uses ML Kit's subject-segmentation model supplied through
 * Google Play services. It is not a color filter and does not call a
 * paid cloud generation API.
 */
internal object AiRealEditEngine {

    suspend fun removeBackground(bitmap: Bitmap): Bitmap? {
        val input = InputImage.fromBitmap(bitmap, 0)
        val options = SubjectSegmenterOptions.Builder()
            .enableForegroundBitmap()
            .build()
        val segmenter = SubjectSegmentation.getClient(options)
        return try {
            val result: SubjectSegmentationResult = segmenter.process(input).await()
            result.foregroundBitmap
        } finally {
            segmenter.close()
        }
    }

    fun composeForegroundOverBackground(foreground: Bitmap, background: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(
            background.width,
            background.height,
            Bitmap.Config.ARGB_8888
        )
        val canvas = android.graphics.Canvas(output)
        canvas.drawBitmap(background, 0f, 0f, null)

        val scale = minOf(
            background.width.toFloat() / foreground.width.toFloat(),
            background.height.toFloat() / foreground.height.toFloat()
        )
        val drawWidth = foreground.width * scale
        val drawHeight = foreground.height * scale
        val left = (background.width - drawWidth) / 2f
        val top = (background.height - drawHeight) / 2f
        val destination = android.graphics.RectF(left, top, left + drawWidth, top + drawHeight)
        canvas.drawBitmap(foreground, null, destination, null)
        canvas.setBitmap(null)
        return output
    }

    /**
     * Composites only the selected mask region from a generated image over the
     * original image. This is a local masked-generation foundation: the current
     * MediaPipe backend does not expose true mask-conditioned inpainting.
     */
    fun compositeByMask(original: Bitmap, generated: Bitmap, mask: Bitmap): Bitmap {
        val width = original.width
        val height = original.height
        val output = original.copy(Bitmap.Config.ARGB_8888, true)
        val generatedScaled = Bitmap.createScaledBitmap(generated, width, height, true)
        val maskScaled = Bitmap.createScaledBitmap(mask, width, height, true)
        val canvas = android.graphics.Canvas(output)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val layer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val layerCanvas = android.graphics.Canvas(layer)
        layerCanvas.drawBitmap(generatedScaled, 0f, 0f, null)
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
        layerCanvas.drawBitmap(maskScaled, 0f, 0f, paint)
        paint.xfermode = null
        canvas.drawBitmap(layer, 0f, 0f, null)
        canvas.setBitmap(null)
        layerCanvas.setBitmap(null)
        if (generatedScaled !== generated) generatedScaled.recycle()
        if (maskScaled !== mask) maskScaled.recycle()
        layer.recycle()
        return output
    }

}
