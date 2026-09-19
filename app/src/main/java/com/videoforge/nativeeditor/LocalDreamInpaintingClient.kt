package com.videoforge.nativeeditor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object LocalDreamInpaintingClient {
    private const val DEFAULT_URL = "http://127.0.0.1:8081/generate"
    private const val MAX_SIDE = 512

    suspend fun isAvailable(baseUrl: String = DEFAULT_URL): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL(baseUrl.replace("/generate", "/tokenize"))
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 800
                readTimeout = 1200
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            connection.outputStream.use { it.write(JSONObject().put("prompt", "test").toString().toByteArray()) }
            val code = connection.responseCode
            connection.disconnect()
            code in 200..299
        }.getOrDefault(false)
    }

    suspend fun inpaint(
        source: Bitmap,
        mask: Bitmap,
        prompt: String,
        baseUrl: String = DEFAULT_URL,
        denoiseStrength: Float = 0.78f,
        steps: Int = 20
    ): Result<Bitmap> = withContext(Dispatchers.IO) {
        runCatching {
            val prepared = prepare(source, mask)
            val request = JSONObject()
                .put("prompt", prompt.trim())
                .put("negative_prompt", "blurry, distorted, bad anatomy, malformed, low quality")
                .put("width", prepared.width)
                .put("height", prepared.height)
                .put("steps", steps.coerceIn(8, 30))
                .put("cfg", 7.0)
                .put("denoise_strength", denoiseStrength.coerceIn(0.05f, 1f))
                .put("image", encodePng(prepared.image))
                .put("mask", encodePng(prepared.mask))

            val connection = (URL(baseUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 300_000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "text/event-stream")
            }

            try {
                connection.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
                check(connection.responseCode in 200..299) {
                    "Local AI inpainting server returned HTTP " + connection.responseCode + "."
                }

                var finalImage: Bitmap? = null
                connection.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        if (!line.startsWith("data: ")) return@forEach
                        val payload = line.removePrefix("data: ").trim()
                        if (payload == "[DONE]") return@forEach
                        val event = runCatching { JSONObject(payload) }.getOrNull() ?: return@forEach
                        when (event.optString("type")) {
                            "error" -> error(event.optString("message", "Local AI inpainting failed."))
                            "complete" -> {
                                finalImage = decodeRawRgb(
                                    Base64.decode(event.getString("image"), Base64.DEFAULT),
                                    event.getInt("width"),
                                    event.getInt("height"),
                                    event.optInt("channels", 3)
                                )
                            }
                        }
                    }
                }

                val generated = finalImage ?: error("Local AI did not return an image.")
                restorePreparedResult(generated, prepared, source.width, source.height)
            } finally {
                connection.disconnect()
            }
        }
    }

    private data class Prepared(
        val image: Bitmap,
        val mask: Bitmap,
        val width: Int,
        val height: Int
    )

    private fun prepare(source: Bitmap, mask: Bitmap): Prepared {
        val scale = minOf(MAX_SIDE.toFloat() / source.width, MAX_SIDE.toFloat() / source.height, 1f)
        val w = ((source.width * scale).toInt().coerceAtLeast(8) / 8) * 8
        val h = ((source.height * scale).toInt().coerceAtLeast(8) / 8) * 8
        val image = Bitmap.createScaledBitmap(source, w, h, true)
        val maskScaled = Bitmap.createScaledBitmap(mask, w, h, true)
        val binaryMask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(binaryMask).drawColor(Color.BLACK)
        val pixels = IntArray(w * h)
        maskScaled.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) pixels[i] = if (Color.alpha(pixels[i]) > 24) Color.WHITE else Color.BLACK
        binaryMask.setPixels(pixels, 0, w, 0, 0, w, h)
        if (maskScaled !== mask) maskScaled.recycle()
        return Prepared(image, binaryMask, w, h)
    }

    private fun restorePreparedResult(
        generated: Bitmap,
        prepared: Prepared,
        originalWidth: Int,
        originalHeight: Int
    ): Bitmap {
        val output = Bitmap.createScaledBitmap(generated, originalWidth, originalHeight, true)
        if (generated !== output && !generated.isRecycled) generated.recycle()
        if (!prepared.image.isRecycled) prepared.image.recycle()
        if (!prepared.mask.isRecycled) prepared.mask.recycle()
        return output
    }

    private fun encodePng(bitmap: Bitmap): String {
        val bytes = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)
        return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
    }

    private fun decodeRawRgb(bytes: ByteArray, width: Int, height: Int, channels: Int): Bitmap {
        require(channels >= 3) { "Unsupported Local AI image channel count: " + channels }
        require(bytes.size >= width * height * channels) { "Local AI returned truncated image data." }
        val pixels = IntArray(width * height)
        var sourceIndex = 0
        for (i in pixels.indices) {
            val r = bytes[sourceIndex++].toInt() and 0xff
            val g = bytes[sourceIndex++].toInt() and 0xff
            val b = bytes[sourceIndex++].toInt() and 0xff
            sourceIndex += channels - 3
            pixels[i] = Color.rgb(r, g, b)
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
