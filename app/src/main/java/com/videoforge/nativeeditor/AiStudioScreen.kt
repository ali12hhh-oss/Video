package com.videoforge.nativeeditor

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private data class AiPhotoStyle(val titleAr: String, val titleEn: String, val detailAr: String, val detailEn: String)

private val aiPhotoStyles = listOf(
    AiPhotoStyle("أنمي", "Anime", "حوّل الصورة إلى أسلوب أنمي", "Transform a photo into an anime style"),
    AiPhotoStyle("كرتون", "Cartoon", "مظهر كرتوني مرسوم", "Create a cartoon look"),
    AiPhotoStyle("صورة سينمائية", "Cinematic", "ألوان وإضاءة بطابع سينمائي", "Cinematic color and lighting"),
    AiPhotoStyle("صورة ثلاثية الأبعاد", "3D Portrait", "أسلوب شخصية ثلاثية الأبعاد", "A stylized 3D character look"),
    AiPhotoStyle("رسم زيتي", "Oil Painting", "محاكاة الرسم الزيتي", "Oil-painting inspired treatment"),
    AiPhotoStyle("مانغا", "Manga", "أسلوب صفحات المانغا", "Manga-inspired treatment"),
    AiPhotoStyle("صورة احترافية", "Studio Portrait", "مظهر صورة استوديو", "Studio portrait treatment"),
    AiPhotoStyle("خيالي", "Fantasy", "طابع فني خيالي", "Fantasy-inspired treatment")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiStudioScreen(isArabic: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    var selectedImage by remember { mutableStateOf<Uri?>(null) }
    var showNotReady by remember { mutableStateOf(false) }
    var chosenStyle by remember { mutableStateOf<AiPhotoStyle?>(null) }
    val previewBitmap = remember(selectedImage) {
        selectedImage?.let { uri ->
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream -> BitmapFactory.decodeStream(stream) }
            }.getOrNull()
        }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        selectedImage = uri
    }
    val choosePhoto = {
        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isArabic) "استوديو AI للصور" else "AI Photo Studio", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = if (isArabic) "رجوع" else "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                if (isArabic) "اختر صورة ثم استكشف أنماط التحويل." else "Choose a photo, then explore transformation styles.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Card(
                modifier = Modifier.fillMaxWidth().height(190.dp).clickable(onClick = choosePhoto),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (previewBitmap != null) {
                        Image(
                            bitmap = previewBitmap.asImageBitmap(),
                            contentDescription = if (isArabic) "الصورة المختارة" else "Selected photo",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(if (isArabic) "اختيار صورة من الهاتف" else "Choose a photo from your device", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            Button(onClick = choosePhoto, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 12.dp)) {
                Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(if (isArabic) "اختيار صورة" else "Select photo")
            }
            Text(if (isArabic) "تأثيرات الصور" else "Photo styles", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(aiPhotoStyles) { style ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable {
                            chosenStyle = style
                            showNotReady = true
                        },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                Modifier.fillMaxWidth().aspectRatio(1.45f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(if (isArabic) style.titleAr else style.titleEn, fontWeight = FontWeight.Bold)
                            Text(if (isArabic) style.detailAr else style.detailEn, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    if (showNotReady) {
        AlertDialog(
            onDismissRequest = { showNotReady = false },
            title = { Text(if (isArabic) "المعالجة غير مفعّلة بعد" else "Processing not connected yet") },
            text = {
                Text(
                    if (isArabic) "${chosenStyle?.titleAr.orEmpty()} معروض كخيار واجهة أولي. يلزم ربط محرك تحويل صور بالذكاء الاصطناعي قبل تطبيق التأثير أو حفظ نتيجة."
                    else "${chosenStyle?.titleEn.orEmpty()} is currently a UI preview option. An AI image transformation engine must be connected before this effect can be applied or saved."
                )
            },
            confirmButton = { TextButton(onClick = { showNotReady = false }) { Text(if (isArabic) "حسنًا" else "OK") } }
        )
    }
}
