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
}
