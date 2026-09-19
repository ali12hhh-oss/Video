package com.videoforge.nativeeditor

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class AiPhotoStyle(val id: String, val titleAr: String, val titleEn: String, val detailAr: String, val detailEn: String)

private val aiPhotoStyles = listOf(
    AiPhotoStyle("anime","أنمي","Anime","تحويل فني بطابع أنمي","Anime-inspired transformation"),
    AiPhotoStyle("cartoon","كرتون","Cartoon","مظهر كرتوني ناعم","Soft cartoon look"),
    AiPhotoStyle("cinematic","سينمائي","Cinematic","إضاءة وألوان سينمائية","Cinematic color and lighting"),
    AiPhotoStyle("3d","شخصية 3D","3D Portrait","مظهر مجسم ثلاثي الأبعاد","Stylized 3D character look"),
    AiPhotoStyle("oil","رسم زيتي","Oil Painting","مظهر لوحة زيتية","Oil-painting treatment"),
    AiPhotoStyle("manga","مانغا","Manga","أسلوب مانغا أبيض وأسود","Manga illustration"),
    AiPhotoStyle("studio","استوديو احترافي","Studio Portrait","إضاءة صورة استوديو","Studio portrait treatment"),
    AiPhotoStyle("fantasy","خيالي","Fantasy","ألوان وأجواء خيالية","Fantasy atmosphere"),
    AiPhotoStyle("watercolor","ألوان مائية","Watercolor","لوحة مائية ناعمة","Soft watercolor look"),
    AiPhotoStyle("pencil","رسم بالقلم","Pencil Sketch","رسم بالقلم الرصاص","Pencil sketch"),
    AiPhotoStyle("pixel","فن البكسل","Pixel Art","أسلوب بكسل ريترو","Retro pixel art"),
    AiPhotoStyle("cyberpunk","سايبربانك","Cyberpunk","نيون وأجواء مستقبلية","Neon futuristic look"),
    AiPhotoStyle("vintage","قديم كلاسيكي","Vintage","ألوان وصورة بطابع قديم","Vintage photo treatment"),
    AiPhotoStyle("clay","طين 3D","3D Clay","مظهر مجسمات الطين","3D clay-figure look"),
    AiPhotoStyle("color_manga","مانغا ملونة","Color Manga","مانغا بألوان زاهية","Vibrant color manga"),
    AiPhotoStyle("editorial","أزياء تحريرية","Editorial Fashion","مظهر تصوير أزياء احترافي","Editorial fashion look")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiStudioScreen(isArabic: Boolean, onBack: () -> Unit, onOpenInEditor: (Uri) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedImage by remember { mutableStateOf<Uri?>(null) }
    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var resultBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var resultUri by remember { mutableStateOf<Uri?>(null) }
    var chosenStyle by remember { mutableStateOf<AiPhotoStyle?>(null) }
    var showOutputChoice by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var comparePosition by remember { mutableFloatStateOf(0.5f) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            selectedImage = uri
            resultBitmap = null
            resultUri = null
            chosenStyle = null
            scope.launch { sourceBitmap = withContext(Dispatchers.IO) { AiPhotoProcessor.decode(context, uri) } }
        }
    }

    fun choosePhoto() = imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    fun applyStyle(style: AiPhotoStyle) {
        val source = sourceBitmap ?: run { choosePhoto(); return }
        chosenStyle = style
        isProcessing = true
        errorText = null
        scope.launch {
            try {
                val processed = withContext(Dispatchers.Default) { AiPhotoProcessor.apply(source, style.id) }
                val file = File(context.cacheDir, "ai_result_${System.currentTimeMillis()}.jpg")
                withContext(Dispatchers.IO) { file.outputStream().use { processed.compress(Bitmap.CompressFormat.JPEG, 95, it) } }
                resultBitmap = processed
                resultUri = Uri.fromFile(file)
                comparePosition = 0.5f
                showOutputChoice = true
            } catch (_: Throwable) {
                errorText = if (isArabic) "تعذر معالجة الصورة." else "Could not process the image."
            } finally { isProcessing = false }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isArabic) "استوديو AI للصور" else "AI Photo Studio", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, if (isArabic) "رجوع" else "Back") } }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (isArabic) "حوّل صورك إلى أنماط فنية ثم احفظ النتيجة أو أرسلها مباشرة إلى محرر الفيديو."
                else "Transform your photo, then save the result or send it directly to the video editor.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Card(Modifier.fillMaxWidth().height(210.dp).clickable(onClick = ::choosePhoto), shape = RoundedCornerShape(20.dp)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (sourceBitmap != null) {
                        Image(sourceBitmap!!.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.AddPhotoAlternate, null, Modifier.size(44.dp))
                            Spacer(Modifier.height(8.dp))
                            Text(if (isArabic) "اختيار صورة من الهاتف" else "Choose a photo")
                        }
                    }
                }
            }
            OutlinedButton(onClick = ::choosePhoto, Modifier.fillMaxWidth()) {
                Icon(Icons.Default.AddPhotoAlternate, null); Spacer(Modifier.width(8.dp))
                Text(if (isArabic) "اختيار صورة أخرى" else "Choose another photo")
            }

            if (resultBitmap != null && sourceBitmap != null) {
                Text(if (isArabic) "المعاينة قبل / بعد" else "Before / After", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    Image(resultBitmap!!.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxWidth(comparePosition).fillMaxHeight()) {
                        Image(sourceBitmap!!.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(2.dp).background(MaterialTheme.colorScheme.primary))
                    }
                }
                Slider(value = comparePosition, onValueChange = { comparePosition = it }, valueRange = 0.05f..0.95f)
            }

            Text(if (isArabic) "الأنماط" else "Styles", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            LazyVerticalGrid(
                columns = GridCells.Fixed(2), modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(aiPhotoStyles) { style ->
                    Card(Modifier.fillMaxWidth().clickable(enabled = !isProcessing) { applyStyle(style) }, shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Box(Modifier.fillMaxWidth().aspectRatio(1.35f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                                if (resultBitmap != null && chosenStyle?.id == style.id) {
                                    Image(resultBitmap!!.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                    Icon(Icons.Default.CheckCircle, null, Modifier.align(Alignment.TopEnd).padding(7.dp))
                                } else Icon(Icons.Default.AutoAwesome, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                            Text(if (isArabic) style.titleAr else style.titleEn, fontWeight = FontWeight.Bold)
                            Text(if (isArabic) style.detailAr else style.detailEn, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                    }
                }
            }
            if (isProcessing) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(if (isArabic) "جارٍ تطبيق النمط..." else "Applying style...")
            }
            errorText?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }

    if (showOutputChoice && resultUri != null && resultBitmap != null) {
        AlertDialog(
            onDismissRequest = { showOutputChoice = false },
            title = { Text(if (isArabic) "ماذا تريد أن تفعل بالصورة؟" else "What would you like to do with the image?") },
            text = { Text(if (isArabic) "تمت معالجة الصورة بنجاح. يمكنك حفظها في الهاتف أو فتحها مباشرة داخل شاشة محرر الفيديو." else "The image is ready. Save it to your phone or open it directly in the video editor.") },
            confirmButton = {
                Button(onClick = {
                    val bitmap = resultBitmap!!
                    scope.launch {
                        val saved = withContext(Dispatchers.IO) { AiPhotoProcessor.saveToGallery(context, bitmap, "VideoForge_AI_${System.currentTimeMillis()}") }
                        showOutputChoice = false
                        if (saved == null) errorText = if (isArabic) "تعذر حفظ الصورة في الهاتف." else "Could not save the image."
                    }
                }) {
                    Icon(Icons.Default.SaveAlt, null); Spacer(Modifier.width(6.dp))
                    Text(if (isArabic) "حفظ في الهاتف" else "Save to phone")
                }
            },
            dismissButton = {
                TextButton(onClick = { showOutputChoice = false; onOpenInEditor(resultUri!!) }) {
                    Icon(Icons.Default.VideoLibrary, null); Spacer(Modifier.width(6.dp))
                    Text(if (isArabic) "فتح في المحرر" else "Open in editor")
                }
            }
        )
    }
}
