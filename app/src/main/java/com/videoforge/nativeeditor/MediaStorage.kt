package com.videoforge.nativeeditor

import android.content.Context
import android.net.Uri
import java.io.File

/** Owns imported editor media so projects never depend on temporary/content-provider URIs. */
object MediaStorage {
    private const val IMPORT_DIR = "imported_media"

    fun copyToAppStorage(context: Context, uri: Uri, index: Int = 0): Uri? {
        if (uri.scheme.equals("file", ignoreCase = true)) return uri
        return runCatching {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri).orEmpty()
            val extension = when {
                mime.equals("video/mp4", true) -> ".mp4"
                mime.equals("video/quicktime", true) -> ".mov"
                mime.equals("video/webm", true) -> ".webm"
                mime.equals("audio/mpeg", true) -> ".mp3"
                mime.equals("audio/mp4", true) -> ".m4a"
                mime.equals("audio/wav", true) || mime.equals("audio/x-wav", true) -> ".wav"
                mime.equals("audio/ogg", true) -> ".ogg"
                mime.equals("audio/aac", true) -> ".aac"
                mime.equals("image/png", true) -> ".png"
                mime.equals("image/webp", true) -> ".webp"
                mime.equals("image/heic", true) || mime.equals("image/heif", true) -> ".heic"
                mime.startsWith("image/") -> ".jpg"
                mime.startsWith("audio/") -> ".m4a"
                else -> if (VideoForgeMediaUtils.isImageUri(context, uri)) ".jpg" else ".mp4"
            }

            val dir = File(context.filesDir, IMPORT_DIR).apply { mkdirs() }
            val fileName = "media_" + System.currentTimeMillis() + "_" + index + "_" +
                kotlin.math.abs(uri.toString().hashCode()) + extension
            val file = File(dir, fileName)
            resolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output, 1024 * 1024) }
            } ?: return@runCatching null
            Uri.fromFile(file)
        }.getOrNull()
    }
}

object VideoForgeMediaUtils {
    fun isImageUri(context: Context, uri: Uri): Boolean {
        val type = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        if (type != null) return type.startsWith("image/")
        val path = uri.lastPathSegment?.lowercase().orEmpty()
        return path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") ||
            path.endsWith(".webp") || path.endsWith(".heic") || path.endsWith(".heif") || path.endsWith(".gif")
    }
}
