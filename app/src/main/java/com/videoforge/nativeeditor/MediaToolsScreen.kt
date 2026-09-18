package com.videoforge.nativeeditor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** On-device subject cutout utility. The caller controls navigation into and out of this page. */
@Composable
internal fun AiCutoutToolScreen(
    arabic: Boolean,
    onBack: () -> Unit,
    onUseResult: (Uri) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var resultBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var resultUri by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                message = ""
                sourceBitmap = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                }
                resultBitmap = null
                resultUri = null
                if (sourceBitmap == null) message = if (arabic) "تعذر فتح الصورة" else "Could not open image"
                busy = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
            Text(if (arabic) "إزالة الخلفية بالذكاء الاصطناعي" else "AI Background Cutout", fontSize = 19.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            val shown = resultBitmap ?: sourceBitmap
            if (shown != null) {
                Image(shown.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Image, null, modifier = Modifier.size(54.dp))
                    Text(if (arabic) "اختر صورة للبدء" else "Choose an image to begin")
                }
            }
            if (busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp))
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy
        ) { Icon(Icons.Default.Image, null); Spacer(Modifier.size(8.dp)); Text(if (arabic) "اختيار صورة" else "Choose image") }
        Button(
            onClick = {
                val input = sourceBitmap ?: return@Button
                scope.launch {
                    busy = true
                    message = ""
                    try {
                        val cutout = withContext(Dispatchers.IO) { segmentSubject(context, input) }
                        if (cutout != null) {
                            resultBitmap = cutout
                            resultUri = withContext(Dispatchers.IO) { writePngToCache(context, cutout) }
                        } else message = if (arabic) "لم يتم العثور على عنصر مناسب" else "No subject could be detected"
                    } catch (_: Exception) {
                        message = if (arabic) "تعذرت المعالجة؛ جرّب صورة أخرى" else "Processing failed; try another image"
                    } finally { busy = false }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = sourceBitmap != null && !busy
        ) { Icon(Icons.Default.Science, null); Spacer(Modifier.size(8.dp)); Text(if (arabic) "إزالة الخلفية" else "Remove background") }
        Button(
            onClick = { resultUri?.let(onUseResult) },
            modifier = Modifier.fillMaxWidth(),
            enabled = resultUri != null && !busy
        ) { Icon(Icons.Default.Save, null); Spacer(Modifier.size(8.dp)); Text(if (arabic) "استخدام النتيجة" else "Use result") }
    }
}

private suspend fun segmentSubject(context: Context, bitmap: Bitmap): Bitmap? {
    val client = SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundBitmap().build())
    return try {
        client.process(InputImage.fromBitmap(bitmap, 0)).await().foregroundBitmap
    } finally {
        client.close()
    }
}

private fun writePngToCache(context: Context, bitmap: Bitmap): Uri {
    val file = File(context.cacheDir, "cutout_${System.currentTimeMillis()}.png")
    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return Uri.fromFile(file)
}
