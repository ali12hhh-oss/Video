package com.videoforge.nativeeditor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal object AiPhotoProcessor {
    fun decode(context: Context, uri: Uri, maxSide: Int = 1600): Bitmap? {
        return runCatching {
            val source = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return null
            val scale = min(1f, maxSide.toFloat() / max(source.width, source.height).toFloat())
            if (scale >= 0.999f) source
            else Bitmap.createScaledBitmap(source, (source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), true)
        }.getOrNull()
    }

    fun apply(source: Bitmap, styleId: String): Bitmap {
        val src = source.copy(Bitmap.Config.ARGB_8888, false)
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val p = IntArray(src.width * src.height)
        src.getPixels(p, 0, src.width, 0, 0, src.width, src.height)
        val w = src.width
        val h = src.height
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            var c = p[i]
            var r = (c shr 16) and 255
            var g = (c shr 8) and 255
            var b = c and 255
            when (styleId) {
                "anime" -> { r = sat(r, 1.18f); g = sat(g, 1.18f); b = sat(b, 1.18f); r = poster(r, 24); g = poster(g, 24); b = poster(b, 24) }
                "cartoon" -> { r = poster(sat(r,1.3f), 32); g = poster(sat(g,1.3f),32); b = poster(sat(b,1.3f),32) }
                "cinematic" -> { r = contrast(r,1.16f); g = contrast(g,1.10f); b = contrast(b,1.08f); r = warm(r,12); b = warm(b,-8) }
                "3d" -> { r = contrast(sat(r,1.12f),1.12f); g = contrast(sat(g,1.08f),1.12f); b = contrast(sat(b,1.08f),1.12f) }
                "oil" -> { val q=18; r=poster(r,q); g=poster(g,q); b=poster(b,q) }
                "manga" -> { val yv=luma(r,g,b); r=yv; g=yv; b=yv; if(yv<135){r=25;g=25;b=25}else{r=245;g=245;b=245} }
                "studio" -> { r=contrast(warm(r,6),1.08f); g=contrast(g,1.06f); b=contrast(cool(b,4),1.05f) }
                "fantasy" -> { r=sat(warm(r,10),1.18f); g=sat(g,1.08f); b=sat(cool(b,16),1.28f) }
                "watercolor" -> { r=poster(sat(r,1.08f),48); g=poster(sat(g,1.08f),48); b=poster(sat(b,1.08f),48) }
                "pencil" -> { val yv=luma(r,g,b); val edge=((abs(r-g)+abs(g-b)+abs(r-b))/3); val v=(255-yv + edge*2).coerceIn(0,255); r=v;g=v;b=v }
                "pixel" -> { r=poster(r,32); g=poster(g,32); b=poster(b,32) }
                "cyberpunk" -> { val yv=luma(r,g,b); r=poster((yv*0.75f+55).toInt(),32); g=poster((yv*0.45f).toInt(),32); b=poster((yv*1.15f+55).toInt().coerceAtMost(255),32) }
                "vintage" -> { val yv=luma(r,g,b); r=(yv*0.88f+42).toInt().coerceIn(0,255); g=(yv*0.76f+30).toInt().coerceIn(0,255); b=(yv*0.58f+18).toInt().coerceIn(0,255) }
                "clay" -> { r=poster(warm(r,14),40); g=poster(warm(g,8),40); b=poster(cool(b,-6),40) }
                "color_manga" -> { r=poster(sat(r,1.28f),36); g=poster(sat(g,1.28f),36); b=poster(sat(b,1.28f),36) }
                "editorial" -> { r=contrast(r,1.2f); g=contrast(g,1.12f); b=contrast(b,1.08f); r=warm(r,7) }
            }
            p[i]=(0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        out.setPixels(p,0,w,0,0,w,h)
        return out
    }

    private fun luma(r:Int,g:Int,b:Int)=((0.299f*r+0.587f*g+0.114f*b).toInt()).coerceIn(0,255)
    private fun sat(v:Int,f:Float)=((v-128)*f+128).toInt().coerceIn(0,255)
    private fun contrast(v:Int,f:Float)=((v-128)*f+128).toInt().coerceIn(0,255)
    private fun warm(v:Int,d:Int)=(v+d).coerceIn(0,255)
    private fun cool(v:Int,d:Int)=(v+d).coerceIn(0,255)
    private fun poster(v:Int,step:Int)=((v/step)*step+step/2).coerceIn(0,255)

    /** Stores a durable private copy for the editor/project instead of relying on cacheDir. */
    fun saveForEditor(context: Context, bitmap: Bitmap, displayName: String): Uri? {
        val safe = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "VideoForge_AI" }
        return runCatching {
            val dir = File(context.filesDir, "ai_photos").apply { mkdirs() }
            val file = File(dir, "$safe.jpg")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            Uri.fromFile(file)
        }.getOrNull()
    }

    fun saveToGallery(context: Context, bitmap: Bitmap, displayName: String): Uri? {
        val name = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "VideoForge_AI" } + ".jpg"
        return runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val values=ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME,name)
                    put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH,Environment.DIRECTORY_PICTURES + "/VideoForge")
                }
                val uri=context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values) ?: return null
                context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG,95,it) } ?: run {
                    context.contentResolver.delete(uri,null,null); return null
                }
                uri
            } else {
                val dir=File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),"VideoForge").apply { mkdirs() }
                val file=File(dir,name)
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,95,it) }
                context.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,Uri.fromFile(file)))
                Uri.fromFile(file)
            }
        }.getOrNull()
    }
}
