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
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class AiPhotoStyle(val id: String, val titleAr: String, val titleEn: String, val detailAr: String, val detailEn: String)

private object AiStylePrompts {
    fun prompt(styleId: String, isArabic: Boolean): String {
        val base = when (styleId) {
            "anime" -> "anime character illustration, expressive anime eyes, clean line art, detailed cel shading, stylized anime clothing"
            "cartoon" -> "high quality 3D cartoon character, polished animated-film look, expressive face, clean shapes, stylized clothing"
            "cinematic" -> "cinematic film still, dramatic professional lighting, realistic skin and materials, rich depth, cinematic color grading"
            "3d" -> "high-end 3D character render, detailed realistic materials, studio-quality lighting, polished 3D clothing and hair"
            "oil" -> "traditional oil painting, visible painterly brushwork, rich layered pigments, museum-quality portrait composition"
            "manga" -> "black and white manga illustration, precise ink linework, screentone shading, expressive manga composition"
            "studio" -> "professional studio portrait photography, softbox lighting, realistic skin texture, premium portrait retouching"
            "fantasy" -> "epic fantasy character portrait, magical atmosphere, detailed fantasy costume, cinematic lighting, rich environment"
            "watercolor" -> "delicate watercolor painting, translucent washes, natural paper texture, elegant hand-painted details"
            "pencil" -> "detailed graphite pencil portrait, realistic pencil strokes, fine cross-hatching, monochrome paper drawing"
            "pixel" -> "high quality pixel art, crisp pixel clusters, limited retro palette, detailed game character sprite aesthetic"
            "cyberpunk" -> "cyberpunk portrait, neon city lighting, futuristic fashion, glowing accents, detailed sci-fi atmosphere"
            "vintage" -> "classic vintage portrait photography, authentic film grain, period color palette, timeless wardrobe styling"
            "clay" -> "stylized 3D clay figure, handcrafted clay materials, soft studio lighting, miniature diorama aesthetic"
            "color_manga" -> "high quality full-color manga illustration, clean ink lines, vibrant cel shading, detailed character design"
            "editorial" -> "luxury editorial fashion photograph, premium magazine photography, sophisticated lighting, high-fashion wardrobe styling"
            else -> "high quality artistic portrait transformation"
        }

        val preservation = "Preserve the subject's identity, facial structure, pose, body proportions, camera composition and important scene elements. Transform the visual style coherently."
        val wardrobe = when (styleId) {
            "anime", "cartoon", "3d", "fantasy", "cyberpunk", "clay", "editorial" ->
                "Adapt the clothing and hair styling to match the chosen visual world while keeping the person recognizable."
            else -> "Keep the clothing recognizable while harmonizing its materials, colors and rendering with the chosen style."
        }
        return "$base. $wardrobe $preservation. High detail, coherent anatomy, clean hands, consistent lighting."
    }
}

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
    var aiPrompt by remember { mutableStateOf("") }
    var backgroundPrompt by remember { mutableStateOf("") }
    var aiModelReady by remember { mutableStateOf(LocalAiImageGenerator.isReady(context)) }
    var aiDownloadProgress by remember { mutableIntStateOf(0) }
    var aiStatus by remember { mutableStateOf<String?>(null) }
    var showMaskEditor by remember { mutableStateOf(false) }
    var maskBitmap by remember { mutableStateOf<Bitmap?>(null) }

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

    fun removeBackgroundWithAi() {
        val source = sourceBitmap ?: run { choosePhoto(); return }
        chosenStyle = null
        errorText = null
        aiStatus = null
        isProcessing = true
        scope.launch {
            try {
                val generated = withContext(Dispatchers.Default) {
                    AiRealEditEngine.removeBackground(source)
                }
                if (generated != null) {
                    resultBitmap = generated
                    resultUri = withContext(Dispatchers.IO) {
                        AiPhotoProcessor.saveForEditor(
                            context, generated, "VideoForge_AI_BackgroundRemoved_${System.currentTimeMillis()}"
                        )
                    }
                    comparePosition = 0.5f
                    showOutputChoice = true
                } else {
                    errorText = if (isArabic) "لم يتمكن محرك AI من فصل العنصر الأمامي." else "AI could not separate the foreground subject."
                }
            } catch (t: Throwable) {
                errorText = t.localizedMessage
                    ?: if (isArabic) "تعذر إزالة الخلفية بالذكاء الاصطناعي." else "AI background removal failed."
            } finally {
                isProcessing = false
            }
        }
    }

    fun replaceBackgroundWithAi() {
        val source = sourceBitmap ?: run { choosePhoto(); return }
        if (!LocalAiImageGenerator.isReady(context)) {
            aiStatus = if (isArabic)
                "جهّز محرك AI أولًا من قسم تعديل AI ثم جرّب استبدال الخلفية."
            else
                "Prepare the AI engine first from the AI editing section, then try background replacement."
            return
        }
        val prompt = backgroundPrompt.ifBlank {
            if (isArabic)
                "خلفية سينمائية احترافية فقط، بدون أشخاص أو أجسام إضافية، إضاءة طبيعية متوافقة مع العنصر الأمامي"
            else
                "professional cinematic background only, no people or extra subjects, natural lighting matching the foreground subject"
        }
        chosenStyle = null
        errorText = null
        aiStatus = null
        isProcessing = true
        scope.launch {
            try {
                val foreground = withContext(Dispatchers.Default) {
                    AiRealEditEngine.removeBackground(source)
                }
                if (foreground == null) {
                    errorText = if (isArabic) "تعذر فصل العنصر الأمامي." else "Could not separate the foreground subject."
                    return@launch
                }
                val generatedBackground = LocalAiImageGenerator.generate(
                    context = context,
                    source = source,
                    prompt = prompt,
                    iterations = 16
                ).getOrThrow()
                val composed = withContext(Dispatchers.Default) {
                    AiRealEditEngine.composeForegroundOverBackground(foreground, generatedBackground)
                }
                resultBitmap = composed
                resultUri = withContext(Dispatchers.IO) {
                    AiPhotoProcessor.saveForEditor(
                        context, composed, "VideoForge_AI_Background_${System.currentTimeMillis()}"
                    )
                }
                comparePosition = 0.5f
                showOutputChoice = true
            } catch (t: Throwable) {
                errorText = t.localizedMessage
                    ?: if (isArabic) "تعذر استبدال الخلفية بالذكاء الاصطناعي." else "AI background replacement failed."
            } finally {
                isProcessing = false
            }
        }
    }

    fun applyMaskedAiEdit() {
        val source = sourceBitmap ?: return
        val mask = maskBitmap ?: return
        val prompt = aiPrompt.ifBlank {
            if (isArabic) "عدّل المنطقة المحددة فقط بشكل واقعي ومتناسق مع الصورة" else "Edit only the selected region realistically and consistently with the photo"
        }
        chosenStyle = null
        errorText = null
        isProcessing = true
        scope.launch {
            try {
                check(LocalDreamInpaintingClient.isAvailable()) {
                    if (isArabic) "محرك Inpainting المحلي غير متصل. افتح Local Dream وحمّل نموذجًا قبل التنفيذ."
                    else "Local inpainting engine is not available. Open Local Dream and load a model first."
                }
                val composed = LocalDreamInpaintingClient.inpaint(
                    source = source,
                    mask = mask,
                    prompt = prompt,
                    denoiseStrength = 0.78f,
                    steps = 20
                ).getOrThrow()
                resultBitmap = composed
                resultUri = withContext(Dispatchers.IO) {
                    AiPhotoProcessor.saveForEditor(
                        context, composed, "VideoForge_AI_Masked_${System.currentTimeMillis()}"
                    )
                }
                comparePosition = 0.5f
                showOutputChoice = true
            } catch (t: Throwable) {
                errorText = t.localizedMessage
                    ?: if (isArabic) "تعذر تنفيذ تعديل المنطقة المحددة." else "Masked AI edit failed."
            } finally {
                isProcessing = false
            }
        }
    }

    fun applyStyle(style: AiPhotoStyle) {
        val source = sourceBitmap ?: run { choosePhoto(); return }
        chosenStyle = style
        errorText = null
        aiStatus = null

        if (!LocalAiImageGenerator.isReady(context)) {
            aiStatus = if (isArabic)
                "هذا النمط يستخدم توليد AI حقيقيًا. جهّز محرك AI أولًا من قسم تعديل AI."
            else
                "This style uses real generative AI. Prepare the AI engine first from the AI editing section."
            return
        }

        isProcessing = true
        scope.launch {
            try {
                val prompt = AiStylePrompts.prompt(style.id, isArabic)
                val result = LocalAiImageGenerator.generate(
                    context = context,
                    source = source,
                    prompt = prompt,
                    iterations = 16
                )
                result.fold(
                    onSuccess = { generated ->
                        resultBitmap = generated
                        resultUri = withContext(Dispatchers.IO) {
                            AiPhotoProcessor.saveForEditor(
                            context, generated, "VideoForge_AI_Style_${System.currentTimeMillis()}"
                        )
                        }
                        comparePosition = 0.5f
                        showOutputChoice = true
                    },
                    onFailure = {
                        errorText = it.localizedMessage
                            ?: if (isArabic) "تعذر تشغيل نمط AI على هذا الجهاز."
                            else "The AI style could not run on this device."
                    }
                )
            } catch (t: Throwable) {
                errorText = t.localizedMessage
                    ?: if (isArabic) "تعذر معالجة الصورة." else "Could not process the image."
            } finally {
                isProcessing = false
            }
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

            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (isArabic) "أدوات AI السريعة" else "Quick AI tools",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (isArabic) "فصل الشخص/العنصر الأمامي يتم محليًا بواسطة نموذج ML Kit، بدون API مدفوع."
                        else "Foreground separation runs locally with the ML Kit AI model, without a paid API.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        enabled = sourceBitmap != null && !isProcessing,
                        onClick = ::removeBackgroundWithAi,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.LayersClear, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (isArabic) "إزالة الخلفية بالـAI" else "Remove background with AI")
                    }
                    OutlinedTextField(
                        value = backgroundPrompt,
                        onValueChange = { backgroundPrompt = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 3,
                        placeholder = {
                            Text(
                                if (isArabic) "مثال: شاطئ استوائي عند الغروب"
                                else "Example: tropical beach at sunset"
                            )
                        },
                        label = { Text(if (isArabic) "وصف الخلفية الجديدة" else "New background prompt") }
                    )
                    Button(
                        enabled = sourceBitmap != null && !isProcessing,
                        onClick = ::replaceBackgroundWithAi,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.AutoAwesome, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (isArabic) "استبدال الخلفية بالـAI" else "Replace background with AI")
                    }

                    OutlinedButton(
                        enabled = sourceBitmap != null && !isProcessing,
                        onClick = { showMaskEditor = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.LayersClear, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (isArabic) "تحديد منطقة للتعديل بالفرشاة" else "Paint a region for AI editing")
                    }
                    if (maskBitmap != null) {
                        Button(
                            enabled = !isProcessing,
                            onClick = ::applyMaskedAiEdit,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.AutoAwesome, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (isArabic) "تطبيق AI على المنطقة المحددة" else "Apply AI to selected region")
                        }
                    }
                }
            }

            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (isArabic) "تعديل AI حقيقي على الجهاز" else "Real on-device AI editing",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (isArabic)
                            "اكتب ما تريد تغييره. يستخدم التطبيق نموذج توليد صور محليًا فعليًا، ويمكن أيضًا اختيار أحد الأنماط الـ16 أدناه. لا يعتمد الاستدلال على API مدفوع."
                        else
                            "Describe the change. The app uses a real local image-generation model, and the 16 styles below use the same generative backend with the selected photo as visual guidance.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = aiPrompt,
                        onValueChange = { aiPrompt = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                        placeholder = {
                            Text(if (isArabic) "مثال: حوّل المشهد إلى غروب سينمائي مع سماء برتقالية" else "Example: Turn the scene into a cinematic sunset with an orange sky")
                        },
                        label = { Text(if (isArabic) "وصف التعديل" else "Edit prompt") }
                    )
                    if (!aiModelReady) {
                        Text(
                            if (isArabic) "محرك AI يحتاج تنزيل نموذج محلي كبير (~1.9 GB) مرة واحدة. بعد ذلك يعمل الاستدلال على الجهاز."
                            else "The AI engine needs a large local model download (~1.9 GB) once. Inference then runs on-device.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Button(
                            enabled = !isProcessing,
                            onClick = {
                                isProcessing = true
                                aiStatus = null
                                scope.launch {
                                    val result = LocalAiImageGenerator.ensureModels(context) { aiDownloadProgress = it }
                                    aiModelReady = result.isSuccess && LocalAiImageGenerator.isReady(context)
                                    isProcessing = false
                                    aiStatus = result.exceptionOrNull()?.localizedMessage
                                        ?: if (isArabic) "تم تجهيز محرك AI." else "AI engine is ready."
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (isArabic) "تنزيل وتجهيز محرك AI المجاني"
                                else "Download and prepare free AI engine"
                            )
                        }
                        if (isProcessing && aiDownloadProgress > 0) {
                            LinearProgressIndicator(
                                progress = { aiDownloadProgress / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text("$aiDownloadProgress%")
                        }
                    } else {
                        Button(
                            enabled = sourceBitmap != null && aiPrompt.isNotBlank() && !isProcessing,
                            onClick = {
                                val source = sourceBitmap ?: return@Button
                                isProcessing = true
                                aiStatus = null
                                scope.launch {
                                    val result = LocalAiImageGenerator.generate(
                                        context = context,
                                        source = source,
                                        prompt = aiPrompt,
                                        iterations = 12
                                    )
                                    result.fold(
                                        onSuccess = { generated ->
                                            resultBitmap = generated
                                            resultUri = withContext(Dispatchers.IO) {
                                                AiPhotoProcessor.saveForEditor(
                                                context, generated, "VideoForge_AI_Generated_${System.currentTimeMillis()}"
                                            )
                                            }
                                            comparePosition = 0.5f
                                            showOutputChoice = true
                                        },
                                        onFailure = {
                                            aiStatus = it.localizedMessage
                                                ?: if (isArabic) "تعذر تشغيل نموذج AI على هذا الجهاز." else "The on-device AI model could not run on this device."
                                        }
                                    )
                                    isProcessing = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.AutoAwesome, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (isArabic) "تطبيق تعديل AI فعلي" else "Apply real AI edit")
                        }
                    }
                    aiStatus?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                    if (maskBitmap != null) {
                        OutlinedButton(
                            enabled = !isProcessing,
                            onClick = {
                                val intent = context.packageManager.getLaunchIntentForPackage("io.github.xororz.localdream")
                                if (intent != null) {
                                    context.startActivity(intent)
                                    aiStatus = if (isArabic)
                                        "تم فتح محرك AI. اختر نموذجًا محليًا ثم ارجع إلى VideoForgeNative لتطبيق التعديل."
                                    else
                                        "AI engine opened. Select a local model, then return to VideoForgeNative to apply the edit."
                                } else {
                                    aiStatus = if (isArabic)
                                        "محرك Inpainting المحلي غير مثبت على الجهاز."
                                    else
                                        "The local inpainting engine is not installed on this device."
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (isArabic) "فتح محرك Inpainting المحلي" else "Open local Inpainting engine")
                        }
                    }
                }
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

    if (showMaskEditor && sourceBitmap != null) {
        AiMaskEditorDialog(
            source = sourceBitmap!!,
            isArabic = isArabic,
            onDismiss = { showMaskEditor = false },
            onApply = { mask ->
                maskBitmap = mask
                showMaskEditor = false
                aiStatus = if (isArabic)
                    "تم حفظ التحديد. اكتب وصف التعديل ثم طبّقه على المنطقة المحددة."
                else
                    "Mask saved. Describe the change, then apply it to the selected region."
            }
        )
    }

    if (showOutputChoice && resultUri != null && resultBitmap != null) {
        AlertDialog(
            onDismissRequest = { showOutputChoice = false },
            title = { Text(if (isArabic) "ماذا تريد أن تفعل بالصورة؟" else "What would you like to do with the image?") },
            text = { Text(if (isArabic) "تمت معالجة الصورة بنجاح. يمكنك حفظها في الهاتف أو فتحها مباشرة داخل شاشة محرر الفيديو." else "The image is ready. Save it to your phone or open it directly in the video editor.") },
            confirmButton = {
                Button(onClick = {
                    val bitmap = resultBitmap!!
                    val activity = context as? android.app.Activity
                    showOutputChoice = false

                    fun saveImageToPhone() {
                        scope.launch {
                            val saved = withContext(Dispatchers.IO) {
                                AiPhotoProcessor.saveToGallery(
                                    context,
                                    bitmap,
                                    "VideoForge_AI_${System.currentTimeMillis()}"
                                )
                            }
                            if (saved == null) {
                                errorText = if (isArabic) "تعذر حفظ الصورة في الهاتف." else "Could not save the image."
                            } else {
                                aiStatus = if (isArabic) "تم حفظ الصورة في مجلد VideoForge داخل الصور." else "Image saved to the VideoForge folder in Pictures."
                            }
                        }
                    }

                    if (activity != null) {
                        InterstitialAdManager.showBeforeAction(
                            activity = activity,
                            onFinished = ::saveImageToPhone
                        )
                    } else {
                        saveImageToPhone()
                    }
                }) {
                    Icon(Icons.Default.SaveAlt, null); Spacer(Modifier.width(6.dp))
                    Text(if (isArabic) "حفظ في الهاتف" else "Save to phone")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    val bitmap = resultBitmap!!
                    scope.launch {
                        val editorUri = withContext(Dispatchers.IO) {
                            AiPhotoProcessor.saveForEditor(context, bitmap, "VideoForge_AI_${System.currentTimeMillis()}")
                        }
                        if (editorUri != null) {
                            showOutputChoice = false
                            onOpenInEditor(editorUri)
                        } else {
                            errorText = if (isArabic) "تعذر تجهيز الصورة للمحرر." else "Could not prepare the image for the editor."
                        }
                    }
                }) {
                    Icon(Icons.Default.VideoLibrary, null); Spacer(Modifier.width(6.dp))
                    Text(if (isArabic) "فتح في المحرر" else "Open in editor")
                }
            }
        )
    }
}
