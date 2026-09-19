package com.videoforge.nativeeditor

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapExtractor
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.vision.imagegenerator.ImageGenerator
import com.google.mediapipe.tasks.vision.imagegenerator.ImageGenerator.ConditionOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Real on-device generative image engine.
 *
 * No paid inference API is used. The foundation weights are downloaded once and
 * inference is performed locally by MediaPipe. The model is intentionally kept
 * outside the APK because the converted Stable Diffusion package is too large
 * for practical APK bundling.
 *
 * The current first backend uses Stable Diffusion 1.5 + MediaPipe's Canny
 * conditioning path. This is genuine diffusion generation conditioned by the
 * selected photo; it is not a color filter.
 */
internal object LocalAiImageGenerator {
    private const val MODEL_URL =
        "https://huggingface.co/On-device/stable-diffusion-v1-5-mediapipe/resolve/main/sd15.zip?download=true"
    private const val MODEL_ZIP = "sd15.zip"
    private const val MODEL_DIR_NAME = "mediapipe_sd15"
    private const val EDGE_PLUGIN_URL =
        "https://storage.googleapis.com/mediapipe-models/image_generator/plugin_models/float32/latest/canny_edge_plugin.tflite"
    private const val EDGE_PLUGIN_NAME = "canny_edge_plugin.tflite"

    private fun root(context: Context) = File(context.filesDir, MODEL_DIR_NAME)
    private fun zipFile(context: Context) = File(context.filesDir, MODEL_ZIP)
    private fun pluginFile(context: Context) = File(context.filesDir, EDGE_PLUGIN_NAME)

    fun isReady(context: Context): Boolean =
        findModelRoot(root(context)) != null && pluginFile(context).isFile

    fun modelRoot(context: Context): File? = findModelRoot(root(context))

    suspend fun ensureModels(
        context: Context,
        onProgress: (Int) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val modelRoot = root(context)
            if (findModelRoot(modelRoot) == null) {
                downloadFile(MODEL_URL, zipFile(context), onProgress)
                unzipSafely(zipFile(context), modelRoot)
                zipFile(context).delete()
            }
            if (!pluginFile(context).isFile) {
                downloadFile(EDGE_PLUGIN_URL, pluginFile(context)) { }
            }
            check(findModelRoot(modelRoot) != null) { "The downloaded AI model is incomplete." }
            check(pluginFile(context).isFile) { "The AI conditioning plugin is missing." }
        }
    }

    suspend fun generate(
        context: Context,
        source: Bitmap,
        prompt: String,
        iterations: Int = 12,
        seed: Int = (System.nanoTime() and Int.MAX_VALUE.toLong()).toInt()
    ): Result<Bitmap> = withContext(Dispatchers.Default) {
        runCatching {
            check(isReady(context)) { "AI model is not installed yet." }
            val modelPath = findModelRoot(root(context))!!.absolutePath
            val edge = pluginFile(context).absolutePath

            val edgeOptions = ConditionOptions.EdgeConditionOptions.builder()
                .setPluginModelBaseOptions(
                    com.google.mediapipe.tasks.core.BaseOptions.builder()
                        .setModelAssetPath(edge)
                        .build()
                )
                .setThreshold1(100f)
                .setThreshold2(200f)
                .setApertureSize(3)
                .setL2Gradient(false)
                .build()

            val conditions = ConditionOptions.builder()
                .setEdgeConditionOptions(edgeOptions)
                .build()

            val options = ImageGenerator.ImageGeneratorOptions.builder()
                .setImageGeneratorModelDirectory(modelPath)
                .build()

            val generator = ImageGenerator.createFromOptions(context, options, conditions)
            try {
                val input = BitmapImageBuilder(source).build()
                val result = generator.generate(
                    prompt.trim(),
                    input,
                    ConditionOptions.ConditionType.EDGE,
                    iterations.coerceIn(4, 20),
                    seed
                )
                BitmapExtractor.extract(result.generatedImage())
            } finally {
                generator.close()
            }
        }
    }

    private fun downloadFile(
        url: String,
        destination: File,
        onProgress: (Int) -> Unit = {}
    ) {
        destination.parentFile?.mkdirs()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 120_000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }
        try {
            connection.connect()
            check(connection.responseCode in 200..299) {
                "Model download failed: HTTP ${connection.responseCode}"
            }
            val total = connection.contentLengthLong
            var downloaded = 0L
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        if (total > 0) {
                            onProgress(((downloaded * 100L) / total).toInt().coerceIn(0, 100))
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun unzipSafely(zip: File, destination: File) {
        destination.mkdirs()
        val canonicalDestination = destination.canonicalFile
        ZipInputStream(BufferedInputStream(zip.inputStream())).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val target = File(destination, entry.name).canonicalFile
                check(target.path == canonicalDestination.path || target.path.startsWith(canonicalDestination.path + File.separator)) {
                    "Unsafe model archive entry."
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { output -> input.copyTo(output, 1024 * 1024) }
                }
            }
        }
    }

    private fun findModelRoot(base: File): File? {
        if (!base.isDirectory) return null
        val directMarkers = listOf(
            "alphas_cumprod.bin",
            "cond_stage_model",
            "diffusion_model",
            "manifest.json"
        )
        if (directMarkers.any { File(base, it).exists() }) return base
        base.listFiles()?.firstNotNullOfOrNull { child ->
            if (child.isDirectory) findModelRoot(child) else null
        }?.let { return it }
        return null
    }
}
