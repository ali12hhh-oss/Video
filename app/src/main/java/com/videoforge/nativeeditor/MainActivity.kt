@file:OptIn(
    androidx.media3.common.util.UnstableApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)
@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.videoforge.nativeeditor

import android.net.Uri
import android.os.Bundle
import android.content.Intent
import android.provider.MediaStore
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.res.ResourcesCompat
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Effect
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.GaussianBlur
import androidx.media3.effect.RgbFilter
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.MatrixTransformation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume


private data class RecentProject(
    val id: String,
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val dateModifiedSeconds: Long,
    val clips: List<Clip> = listOf(Clip(uri, name, durationMs))
)

private object RecentProjectsRepository {
    fun load(context: android.content.Context, includeDeviceVideos: Boolean): List<RecentProject> {
        val saved = ProjectRepository.load(context).mapNotNull { project ->
            val primary = project.primaryClip ?: return@mapNotNull null
            RecentProject(
                id = project.id,
                uri = primary.uri,
                name = project.name,
                durationMs = primary.durationMs,
                dateModifiedSeconds = project.updatedAtMs / 1000L,
                clips = project.clips
            )
        }
        if (!includeDeviceVideos) return saved.take(12)

        val resolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_MODIFIED
        )
        val result = saved.toMutableList()
        val existingUris = result.map { it.uri.toString() }.toMutableSet()
        resolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val dateIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            while (cursor.moveToNext() && result.size < 12) {
                val id = cursor.getLong(idIndex)
                val uri = contentUriForVideo(id)
                if (existingUris.add(uri.toString())) {
                    val name = cursor.getString(nameIndex).orEmpty().ifBlank { "Video" }
                    val duration = cursor.getLong(durationIndex)
                    val date = cursor.getLong(dateIndex)
                    result += RecentProject(
                        id = "media:$id",
                        uri = uri,
                        name = name,
                        durationMs = duration,
                        dateModifiedSeconds = date
                    )
                }
            }
        }
        return result.sortedByDescending { it.dateModifiedSeconds }.take(12)
    }

    private fun contentUriForVideo(id: Long): Uri =
        Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString())
}

private fun persistUriAccess(context: android.content.Context, uri: Uri) {
    try {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, flags)
    } catch (_: Exception) {
        // Some picker/providers do not expose persistable permissions; the project still stores the URI.
    }
}

private fun hasVideoPermission(context: android.content.Context): Boolean {
    val permission = if (Build.VERSION.SDK_INT >= 33) {
        android.Manifest.permission.READ_MEDIA_VIDEO
    } else {
        android.Manifest.permission.READ_EXTERNAL_STORAGE
    }
    return androidx.core.content.ContextCompat.checkSelfPermission(
        context, permission
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
}

private fun mediaDurationMs(context: android.content.Context, uri: Uri): Long {
    return runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            retriever.release()
        }
    }.getOrDefault(0L)
}

private fun parseSrt(raw: String): List<Subtitle> {
    val blocks = raw.replace("\r", "").trim().split(Regex("\\n\\s*\\n"))
    return blocks.mapNotNull { block ->
        val lines = block.lines().filter { it.isNotBlank() }
        if (lines.size < 2) return@mapNotNull null
        val timeLine = lines.firstOrNull { it.contains("-->") } ?: return@mapNotNull null
        val parts = timeLine.split("-->").map { it.trim().split(" ").firstOrNull().orEmpty() }
        if (parts.size != 2) return@mapNotNull null
        val start = parseSrtTime(parts[0]) ?: return@mapNotNull null
        val end = parseSrtTime(parts[1]) ?: return@mapNotNull null
        val textIndex = lines.indexOf(timeLine)
        val text = lines.drop(textIndex + 1).joinToString("\n").trim()
        if (text.isBlank() || end <= start) null else Subtitle(text = text, startMs = start, endMs = end)
    }.sortedBy { it.startMs }
}

private fun parseSrtTime(value: String): Long? {
    val m = Regex("(\\d{2}):(\\d{2}):(\\d{2})[,.](\\d{3})").matchEntire(value.trim()) ?: return null
    val (h, min, sec, ms) = m.destructured
    return h.toLong() * 3_600_000L + min.toLong() * 60_000L + sec.toLong() * 1_000L + ms.toLong()
}

private fun srtTime(ms: Long): String {
    val safe = ms.coerceAtLeast(0L)
    val h = safe / 3_600_000L
    val m = (safe % 3_600_000L) / 60_000L
    val s = (safe % 60_000L) / 1_000L
    val milli = safe % 1_000L
    return "%02d:%02d:%02d,%03d".format(h, m, s, milli)
}

private fun subtitlesToSrt(subtitles: List<Subtitle>): String = buildString {
    subtitles.sortedBy { it.startMs }.forEachIndexed { index, subtitle ->
        append(index + 1).append('\n')
        append(srtTime(subtitle.startMs)).append(" --> ").append(srtTime(subtitle.endMs.coerceAtLeast(subtitle.startMs + 1L))).append('\n')
        append(subtitle.text.trim()).append("\n\n")
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}

private fun formatProjectDate(seconds: Long): String {
    if (seconds <= 0L) return ""
    val date = java.util.Date(seconds * 1000L)
    return java.text.SimpleDateFormat("yyyy/MM/dd  •  HH:mm", java.util.Locale.getDefault()).format(date)
}

private fun loadVideoThumbnail(context: android.content.Context, uri: Uri): android.graphics.Bitmap? {
    return try {
        if (Build.VERSION.SDK_INT >= 29) {
            context.contentResolver.loadThumbnail(uri, Size(480, 270), null)
        } else {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val bitmap = retriever.getFrameAtTime(0L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            retriever.release()
            bitmap
        }
    } catch (_: Exception) {
        null
    }
}

private suspend fun aiCutoutImage(context: android.content.Context, uri: Uri): Uri? = suspendCancellableCoroutine { cont ->
    try {
        val bitmap = context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) }
        if (bitmap == null) { cont.resume(null); return@suspendCancellableCoroutine }
        val options = SubjectSegmenterOptions.Builder().enableForegroundBitmap().build()
        val segmenter = SubjectSegmentation.getClient(options)
        segmenter.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                val fg = result.foregroundBitmap
                val out = if (fg != null) {
                    val file = java.io.File(context.filesDir, "ai_cutout_${System.currentTimeMillis()}.png")
                    file.outputStream().use { fg.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    Uri.fromFile(file)
                } else null
                segmenter.close()
                if (cont.isActive) cont.resume(out)
            }
            .addOnFailureListener { e -> segmenter.close(); if (cont.isActive) cont.resume(null) }
    } catch (_: Throwable) { if (cont.isActive) cont.resume(null) }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LanguageManager.setLanguage(this, LanguageManager.getLanguage(this))
        WatermarkRewardManager.initialize(this)
        setContent { VideoForgeApp() }
    }
}

@Composable
private fun VideoForgeApp() {
    val context = LocalContext.current
    var language by remember { mutableStateOf(LanguageManager.getLanguage(context)) }
    var showEditor by remember { mutableStateOf(false) }
    var clips by remember { mutableStateOf(listOf<Clip>()) }
    var projectId by remember { mutableStateOf(ProjectRepository.newId()) }
    var projectName by remember { mutableStateOf(context.getString(R.string.new_project)) }
    var selected by remember { mutableIntStateOf(0) }
    var showTemplates by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showAiStudio by remember { mutableStateOf(false) }
    var showBrandSplash by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        delay(1100L)
        showBrandSplash = false
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(20)
    ) { uris ->
        if (uris.isNotEmpty()) {
            projectId = ProjectRepository.newId()
            clips = uris.mapIndexed { i, uri ->
                persistUriAccess(context, uri)
                Clip(uri, context.getString(R.string.clip_number, i + 1))
            }
            projectName = clips.firstOrNull()?.name ?: context.getString(R.string.new_project)
            ProjectRepository.save(context, projectId, clips, projectName)
            showEditor = true
        }
    }

    val direction = if (language == AppLanguage.ARABIC) LayoutDirection.Rtl else LayoutDirection.Ltr

    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                background = Color(0xFF05070C),
                surface = Color(0xFF10131B),
                surfaceVariant = Color(0xFF171B25),
                primary = Color(0xFF7C4DFF),
                secondary = Color(0xFF00D9C6)
            )
        ) {
            if (showBrandSplash) {
                BrandSplashScreen()
            } else if (showEditor) {
                EditorScreen(
                    projectId = projectId,
                    projectName = projectName,
                    clips = clips,
                    onClipsChanged = { updated ->
                        clips = updated
                        ProjectRepository.save(context, projectId, updated, projectName)
                    },
                    onProjectNameChanged = { name ->
                        projectName = name
                        if (clips.isNotEmpty()) ProjectRepository.save(context, projectId, clips, name)
                    },
                    onBack = { showEditor = false },
                    language = language,
                    onLanguageSelected = {
                        language = it
                        LanguageManager.setLanguage(context, it)
                    }
                )
            } else if (showAiStudio) {
                AiStudioScreen(
                    isArabic = language == AppLanguage.ARABIC,
                    onBack = { showAiStudio = false },
                    onOpenInEditor = { uri ->
                        projectId = ProjectRepository.newId()
                        projectName = if (language == AppLanguage.ARABIC) "صورة AI" else "AI Photo"
                        clips = listOf(Clip(uri, projectName))
                        ProjectRepository.save(context, projectId, clips, projectName)
                        showAiStudio = false
                        showEditor = true
                    }
                )
            } else {
                if (selected == 1) {
                    ProjectsScreen(
                        language = language,
                        onBackHome = { selected = 0 },
                        onOpenProject = { project ->
                            projectId = project.id
                            projectName = project.name
                            clips = project.clips
                            selected = 0
                            showEditor = true
                        },
                        onNewProject = { projectId = ProjectRepository.newId(); projectName = context.getString(R.string.new_project); clips = emptyList(); selected = 0; showEditor = true }
                    )
                } else {
                    HomeScreen(
                    language = language,
                    onLanguageSelected = {
                        language = it
                        LanguageManager.setLanguage(context, it)
                    },
                    onOpenSettings = { showSettings = true },
                    selected = selected,
                    onSelected = { selected = it },
                    onNewProject = { projectId = ProjectRepository.newId(); projectName = context.getString(R.string.new_project); clips = emptyList(); showEditor = true },
                    onImport = {
                        picker.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageAndVideo
                            )
                        )
                    },
                    onOpenTemplates = { showTemplates = true },
                    onOpenProject = { project ->
                        projectId = project.id
                        projectName = project.name
                        clips = project.clips
                        selected = 0
                        showEditor = true
                    },
                    onViewAllProjects = { selected = 1 },
                    onOpenAiStudio = { showAiStudio = true }
                    )
                }
            }
        }
    }

    if (showSettings) {
        SettingsSheet(
            language = language,
            onLanguageSelected = {
                language = it
                LanguageManager.setLanguage(context, it)
            },
            onDismiss = { showSettings = false }
        )
    }

    if (showTemplates) {
        TemplatesSheet(onDismiss = { showTemplates = false }, onUseTemplate = {
            showTemplates = false
            showEditor = true
        })
    }
}

@Composable
private fun BrandSplashScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF02040A), Color(0xFF070B18), Color(0xFF120B2A))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0x332C6BFF), Color.Transparent),
                        radius = 720f
                    )
                )
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(R.drawable.videoforge_logo),
                contentDescription = "VideoForge",
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                modifier = Modifier
                    .width(260.dp)
                    .height(230.dp)
            )
            Spacer(Modifier.height(24.dp))
            Box(
                modifier = Modifier
                    .width(210.dp)
                    .height(5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0x332D8CFF))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.72f)
                        .clip(RoundedCornerShape(50))
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFF00D9FF), Color(0xFF6D4CFF), Color(0xFFB52CFF))
                            )
                        )
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Create Amazing Videos",
                color = Color.White.copy(alpha = 0.78f),
                fontSize = 12.sp,
                letterSpacing = 1.2.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun HomeScreen(
    language: AppLanguage,
    onLanguageSelected: (AppLanguage) -> Unit,
    onOpenSettings: () -> Unit,
    selected: Int,
    onSelected: (Int) -> Unit,
    onNewProject: () -> Unit,
    onImport: () -> Unit,
    onOpenTemplates: () -> Unit,
    onOpenProject: (RecentProject) -> Unit,
    onViewAllProjects: () -> Unit,
    onOpenAiStudio: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var recentProjects by remember { mutableStateOf(emptyList<RecentProject>()) }
    var hasPermission by remember { mutableStateOf(true) }

    fun reloadRecentProjects() {
        recentProjects = ProjectRepository.load(context).mapNotNull { project ->
            val primary = project.primaryClip ?: return@mapNotNull null
            RecentProject(project.id, primary.uri, project.name, primary.durationMs, project.updatedAtMs / 1000L, project.clips)
        }.sortedByDescending { it.dateModifiedSeconds }.take(12)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reloadRecentProjects()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        reloadRecentProjects()
    }

    val scroll = rememberScrollState()

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF6D3DFF), onPrimary = Color.White,
            secondary = Color(0xFF008F82),
            background = Color(0xFFF5F7FB), surface = Color.White,
            surfaceVariant = Color(0xFFE9EDF5),
            onBackground = Color(0xFF172033), onSurface = Color(0xFF172033),
            onSurfaceVariant = Color(0xFF4E5A6D)
        )
    ) {
    Scaffold(
        containerColor = Color(0xFFF5F7FB),
        topBar = { HomeTopBar(language, onLanguageSelected, onOpenSettings) },
        bottomBar = {
            HomeBottomBar(selected = selected, onSelected = onSelected, onImport = onImport)
        }
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(scroll)
                .padding(bottom = 8.dp)
        ) {
            Spacer(Modifier.height(6.dp))
            HeroCard(language = language, onNewProject = onNewProject)
            Spacer(Modifier.height(16.dp))

            ActionCards(onImport = onImport, onTemplates = onOpenTemplates, onCapture = {
                val intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE)
                try { context.startActivity(intent) } catch (_: Exception) { }
            })
            Spacer(Modifier.height(12.dp))
            AiPhotoStudioEntry(language = language, onClick = onOpenAiStudio)
            Spacer(Modifier.height(16.dp))

            SectionHeader(stringResource(R.string.drafts), stringResource(R.string.view_all), onViewAllProjects)
            Spacer(Modifier.height(8.dp))
            RecentProjects(
                projects = recentProjects,
                hasPermission = true,
                onRequestPermission = { },
                onOpenProject = onOpenProject
            )
            Spacer(Modifier.height(16.dp))

            PremiumBanner()
            Spacer(Modifier.height(10.dp))
        }
    }
    }
}


@Composable
private fun AiPhotoStudioEntry(language: AppLanguage, onClick: () -> Unit) {
    val arabic = language == AppLanguage.ARABIC
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF171B2A))
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(54.dp).clip(RoundedCornerShape(16.dp)).background(
                    Brush.linearGradient(listOf(Color(0xFFFF4F9A), Color(0xFF7048FF)))
                ), contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(if (arabic) "استوديو AI للصور" else "AI Photo Studio", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Spacer(Modifier.height(3.dp))
                Text(
                    if (arabic) "حوّل صورك إلى أنماط فنية واحفظها أو افتحها في المحرر"
                    else "Transform photos, save them, or open them in the video editor",
                    color = Color.White.copy(alpha = 0.72f), fontSize = 12.sp
                )
            }
            Text(if (arabic) "فتح" else "Open", color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ProjectsScreen(
    language: AppLanguage,
    onBackHome: () -> Unit,
    onOpenProject: (RecentProject) -> Unit,
    onNewProject: () -> Unit
) {
    val context = LocalContext.current
    var projects by remember { mutableStateOf(emptyList<RecentProject>()) }
    var query by rememberSaveable { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<RecentProject?>(null) }
    var deleteTarget by remember { mutableStateOf<RecentProject?>(null) }
    var duplicateTarget by remember { mutableStateOf<RecentProject?>(null) }

    fun reload() {
        val saved = ProjectRepository.load(context)
        projects = saved.mapNotNull { p ->
            p.primaryClip?.let { clip ->
                RecentProject(p.id, clip.uri, p.name, clip.durationMs, p.updatedAtMs / 1000L, p.clips)
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) reload() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { reload() }

    val filtered = projects.filter { it.name.contains(query.trim(), ignoreCase = true) }

    Scaffold(
        containerColor = Color(0xFF020812),
        topBar = {
            Surface(color = Color(0xFF020812)) {
                Row(
                    Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBackHome) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
                    Text(stringResource(R.string.projects), Modifier.weight(1f), fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    FilledIconButton(
                        onClick = onNewProject,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF7041FF))
                    ) { Icon(Icons.Default.Add, null) }
                }
            }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 14.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                placeholder = { Text(stringResource(R.string.search_projects)) },
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF0C1521),
                    unfocusedContainerColor = Color(0xFF0C1521),
                    focusedBorderColor = Color(0xFF7041FF),
                    unfocusedBorderColor = Color(0xFF202A39)
                )
            )
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(R.string.project_count, filtered.size),
                color = Color.Gray, fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 2.dp)
            )
            Spacer(Modifier.height(8.dp))

            if (filtered.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.VideoLibrary, null, tint = Color(0xFF8B5CFF), modifier = Modifier.size(58.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(if (query.isBlank()) R.string.no_saved_projects else R.string.no_matching_projects), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = onNewProject) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.new_project)) }
                }
            } else {
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 20.dp)
                ) {
                    items(filtered, key = { it.id }) { project ->
                        ProjectListCard(
                            project = project,
                            onOpen = { onOpenProject(project) },
                            onRename = { renameTarget = project },
                            onDelete = { deleteTarget = project },
                            onDuplicate = { duplicateTarget = project }
                        )
                    }
                }
            }
        }
    }

    renameTarget?.let { project ->
        var name by remember(project.id) { mutableStateOf(project.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.rename_project)) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text(stringResource(R.string.project_name)) }) },
            confirmButton = {
                TextButton(onClick = {
                    ProjectRepository.rename(context, project.id, name)
                    renameTarget = null
                    reload()
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    duplicateTarget?.let { project ->
        AlertDialog(
            onDismissRequest = { duplicateTarget = null },
            title = { Text(if (language == AppLanguage.ARABIC) "تكرار المشروع" else "Duplicate project") },
            text = { Text(if (language == AppLanguage.ARABIC) "سيتم إنشاء نسخة مستقلة من المشروع مع جميع المقاطع." else "A separate copy will be created with all clips.") },
            confirmButton = {
                TextButton(onClick = {
                    val newId = ProjectRepository.duplicate(context, project.id)
                    if (newId != null) {
                        EditorSettingsRepository.save(context, newId, EditorSettingsRepository.load(context, project.id))
                    }
                    duplicateTarget = null
                    reload()
                }) { Text(if (language == AppLanguage.ARABIC) "تكرار" else "Duplicate") }
            },
            dismissButton = { TextButton(onClick = { duplicateTarget = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    deleteTarget?.let { project ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.delete_project)) },
            text = { Text(stringResource(R.string.delete_project_confirm, project.name)) },
            confirmButton = {
                TextButton(onClick = {
                    ProjectRepository.delete(context, project.id)
                    deleteTarget = null
                    reload()
                }) { Text(stringResource(R.string.delete), color = Color(0xFFFF6B6B)) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

@Composable
private fun ProjectListCard(
    project: RecentProject,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit
) {
    val context = LocalContext.current
    var thumbnail by remember(project.uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(project.uri) {
        thumbnail = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { loadVideoThumbnail(context, project.uri) }
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF0C1521)).clickable(onClick = onOpen).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(132.dp).height(78.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xFF111D2B))) {
            if (thumbnail != null) {
                androidx.compose.foundation.Image(thumbnail!!.asImageBitmap(), null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Default.PlayCircle, null, tint = Color(0xFF7C4DFF), modifier = Modifier.align(Alignment.Center).size(34.dp))
            }
            Text(formatDuration(project.durationMs), Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xDD000000)).padding(horizontal = 4.dp, vertical = 2.dp), fontSize = 8.sp)
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(project.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.clip_count, project.clips.size), color = Color.Gray, fontSize = 10.sp)
            Text(formatProjectDate(project.dateModifiedSeconds), color = Color.Gray, fontSize = 9.sp)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, null, tint = Color.LightGray) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.open_project)) }, leadingIcon = { Icon(Icons.Default.PlayArrow, null) }, onClick = { menu = false; onOpen() })
                DropdownMenuItem(text = { Text(stringResource(R.string.rename_project)) }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text(if (LocalLayoutDirection.current == LayoutDirection.Rtl) "تكرار المشروع" else "Duplicate project") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) }, onClick = { menu = false; onDuplicate() })
                DropdownMenuItem(text = { Text(stringResource(R.string.delete_project)) }, leadingIcon = { Icon(Icons.Default.DeleteOutline, null) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun HomeTopBar(
    language: AppLanguage,
    onLanguageSelected: (AppLanguage) -> Unit,
    onOpenSettings: () -> Unit
) {
    var languageMenu by remember { mutableStateOf(false) }
    Surface(color = Color.White, shadowElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onOpenSettings) {
                Icon(
                    Icons.Default.Menu,
                    contentDescription = if (language == AppLanguage.ARABIC) "الإعدادات" else "Settings",
                    tint = Color(0xFF172033)
                )
            }
            androidx.compose.foundation.Image(
                painter = painterResource(R.drawable.videoforge_logo),
                contentDescription = stringResource(R.string.app_name),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                modifier = Modifier.weight(1f).height(48.dp)
            )
            Box {
                OutlinedButton(
                    onClick = { languageMenu = true },
                    shape = RoundedCornerShape(22.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF6D3DFF)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color(0xFFF5F1FF),
                        contentColor = Color(0xFF4B278F)
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.Language, null, Modifier.size(18.dp), tint = Color(0xFF6D3DFF))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        if (language == AppLanguage.ARABIC) "العربية" else "English",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                DropdownMenu(
                    expanded = languageMenu,
                    onDismissRequest = { languageMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.arabic)) },
                        onClick = {
                            languageMenu = false
                            onLanguageSelected(AppLanguage.ARABIC)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.english)) },
                        onClick = {
                            languageMenu = false
                            onLanguageSelected(AppLanguage.ENGLISH)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroCard(language: AppLanguage, onNewProject: () -> Unit) {
    val alignment = if (language == AppLanguage.ARABIC) Alignment.CenterEnd else Alignment.CenterStart
    val textAlign = if (language == AppLanguage.ARABIC) androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Start

    Box(
        Modifier
            .fillMaxWidth()
            .height(246.dp)
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(19.dp))
    ) {
        androidx.compose.foundation.Image(
            painter = painterResource(R.drawable.hero_cinematic),
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        if (language == AppLanguage.ARABIC) {
                            listOf(Color(0xB8000715), Color(0x4A08142A), Color(0xA6000612))
                        } else {
                            listOf(Color(0xA6000612), Color(0x4A08142A), Color(0xB8000715))
                        }
                    )
                )
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0x18000815), Color(0xD9000815))
                    )
                )
        )

        Column(
            Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 340.dp)
                .padding(horizontal = 22.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                stringResource(R.string.turn_ideas),
                fontSize = 27.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(7.dp))
            Text(
                stringResource(R.string.professional_tools),
                color = Color.White.copy(alpha = .9f),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onNewProject,
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7041FF)),
                contentPadding = PaddingValues(horizontal = 17.dp, vertical = 8.dp)
            ) {
                if (language == AppLanguage.ARABIC) {
                    Text(stringResource(R.string.new_project), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(2.dp))
                    Icon(Icons.Default.ChevronLeft, null, Modifier.size(18.dp))
                } else {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.new_project), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(Modifier.width(2.dp))
                    Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun ActionCards(onImport: () -> Unit, onTemplates: () -> Unit, onCapture: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        HomeActionCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Default.Collections,
            title = stringResource(R.string.import_media),
            subtitle = stringResource(R.string.videos_photos_audio),
            accent = Color(0xFF713BFF),
            onClick = onImport
        )
        HomeActionCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Default.AutoAwesomeMotion,
            title = stringResource(R.string.ready_templates),
            subtitle = stringResource(R.string.start_quickly),
            accent = Color(0xFF00C5B6),
            onClick = onTemplates
        )
        HomeActionCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Default.Videocam,
            title = stringResource(R.string.capture_video),
            subtitle = stringResource(R.string.from_camera),
            accent = Color(0xFF1674F5),
            onClick = onCapture
        )
    }
}

@Composable
private fun HomeActionCard(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    onClick: () -> Unit
) {
    Column(
        modifier
            .height(144.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(
                    listOf(accent.copy(alpha = .45f), Color(0xFF101827))
                )
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = .11f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, Modifier.size(27.dp), tint = Color.White)
        }
        Spacer(Modifier.height(8.dp))
        Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp, lineHeight = 16.sp, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(3.dp))
        Text(subtitle, color = Color.White.copy(alpha = .9f), fontSize = 10.sp, lineHeight = 13.sp, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun SectionHeader(title: String, action: String?, onAction: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (action != null) {
            Text(action, color = Color(0xFF9D70FF), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { onAction?.invoke() })
            Spacer(Modifier.weight(1f))
        }
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun QuickTools() {
    // Keep each icon and label explicitly paired so RTL/LTR layout never swaps labels.
    data class QuickTool(val icon: ImageVector, val label: String)
    val tools = listOf(
        QuickTool(Icons.Default.ContentCut, stringResource(R.string.trim_clip)),
        QuickTool(Icons.Default.Speed, stringResource(R.string.speed)),
        QuickTool(Icons.Default.TextFields, stringResource(R.string.text)),
        QuickTool(Icons.Default.MusicNote, stringResource(R.string.music)),
        QuickTool(Icons.Default.AutoAwesome, stringResource(R.string.effects)),
        QuickTool(Icons.Default.Tune, stringResource(R.string.adjust))
    )
    LazyRow(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        items(tools) { tool ->
            val icon = tool.icon
            val label = tool.label
            Column(
                Modifier
                    .width(74.dp)
                    .height(82.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(Color(0xFF0E1725))
                    .padding(vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(icon, null, tint = Color(0xFFD09CFF), modifier = Modifier.size(26.dp))
                Spacer(Modifier.height(6.dp))
                Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

@Composable
private fun RecentProjects(
    projects: List<RecentProject>,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onOpenProject: (RecentProject) -> Unit
) {
    if (projects.isEmpty()) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            color = Color.White, shape = RoundedCornerShape(16.dp), shadowElevation = 1.dp
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFFF0EBFF)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.VideoLibrary, null, tint = Color(0xFF6D3DFF), modifier = Modifier.size(27.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.no_saved_projects), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(Modifier.height(3.dp))
                    Text(stringResource(R.string.create_first_project), color = Color(0xFF667085), fontSize = 10.sp)
                }
                Icon(Icons.Default.ChevronRight, null, tint = Color(0xFF667085))
            }
        }
        return
    }
    LazyRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(projects.take(6), key = { it.id }) { project ->
            val context = LocalContext.current
            var thumbnail by remember(project.uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
            LaunchedEffect(project.uri) {
                thumbnail = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { loadVideoThumbnail(context, project.uri) }
            }
            Column(
                Modifier.width(166.dp).clip(RoundedCornerShape(16.dp)).background(Color.White)
                    .clickable { onOpenProject(project) }.padding(8.dp)
            ) {
                Box(Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xFFE9EDF5))) {
                    if (thumbnail != null) {
                        androidx.compose.foundation.Image(
                            bitmap = thumbnail!!.asImageBitmap(), contentDescription = project.name,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(Icons.Default.PlayCircle, null, tint = Color(0xFF6D3DFF), modifier = Modifier.align(Alignment.Center).size(34.dp))
                    }
                    if (project.durationMs > 0L) {
                        Text(
                            formatDuration(project.durationMs),
                            Modifier.align(Alignment.BottomEnd).padding(5.dp).clip(RoundedCornerShape(5.dp))
                                .background(Color(0xDD172033)).padding(horizontal = 5.dp, vertical = 2.dp),
                            fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = Color.White
                        )
                    }
                }
                Spacer(Modifier.height(7.dp))
                Text(project.name, color = Color(0xFF101828), fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(formatProjectDate(project.dateModifiedSeconds), color = Color(0xFF475467), fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun PremiumBanner() {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(Color(0xFF24175F), Color(0xFF4220A3), Color(0xFF17296D))
                )
            )
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF2F326A)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.WorkspacePremium, null, Modifier.size(25.dp), tint = Color(0xFFFFC857))
        }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.premium_features), fontWeight = FontWeight.Bold, color = Color(0xFFFFD36B), fontSize = 15.sp)
            Text(stringResource(R.string.premium_description), fontSize = 10.sp, lineHeight = 14.sp, color = Color.White.copy(alpha = 0.88f), maxLines = 2)
        }
        Button(
            onClick = {},
            shape = RoundedCornerShape(23.dp),
            contentPadding = PaddingValues(horizontal = 11.dp, vertical = 5.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7041FF))
        ) {
            Text(stringResource(R.string.upgrade_now), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun HomeBottomBar(selected: Int, onSelected: (Int) -> Unit, onImport: () -> Unit) {
    NavigationBar(containerColor = Color.White, tonalElevation = 2.dp, modifier = Modifier.height(78.dp)) {
        NavigationBarItem(
            selected = selected == 0, onClick = { onSelected(0) },
            icon = { Icon(Icons.Default.Home, null) },
            label = { Text(stringResource(R.string.home), fontSize = 10.sp) }
        )
        NavigationBarItem(
            selected = selected == 1, onClick = { onSelected(1) },
            icon = { Icon(Icons.Default.Folder, null) },
            label = { Text(stringResource(R.string.drafts), fontSize = 10.sp) }
        )
        NavigationBarItem(
            selected = false, onClick = onImport,
            icon = {
                Box(Modifier.size(56.dp).offset(y = (-2).dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(Brush.linearGradient(listOf(Color(0xFF8A45FF), Color(0xFF216DFF)))),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.Add, null, Modifier.size(30.dp), tint = Color.White) }
            },
            label = { Text(stringResource(R.string.new_project), fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    language: AppLanguage,
    onLanguageSelected: (AppLanguage) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color.White
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp)
        ) {
            Text(
                stringResource(R.string.settings),
                color = Color(0xFF172033),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (language == AppLanguage.ARABIC) "إعدادات VideoForge والمحرر" else "VideoForge and editor settings",
                color = Color(0xFF667085),
                fontSize = 11.sp
            )

            Spacer(Modifier.height(18.dp))
            Text(
                if (language == AppLanguage.ARABIC) "عام" else "General",
                color = Color(0xFF6D3DFF),
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(7.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFFF5F7FB),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Language, null, tint = Color(0xFF6D3DFF))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.language), color = Color(0xFF172033), fontWeight = FontWeight.SemiBold)
                        Text(
                            if (language == AppLanguage.ARABIC) "العربية" else "English",
                            color = Color(0xFF667085),
                            fontSize = 10.sp
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = language == AppLanguage.ARABIC,
                            onClick = { onLanguageSelected(AppLanguage.ARABIC) },
                            label = { Text(stringResource(R.string.arabic), fontSize = 10.sp) }
                        )
                        FilterChip(
                            selected = language == AppLanguage.ENGLISH,
                            onClick = { onLanguageSelected(AppLanguage.ENGLISH) },
                            label = { Text(stringResource(R.string.english), fontSize = 10.sp) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                if (language == AppLanguage.ARABIC) "المحرر" else "Editor",
                color = Color(0xFF6D3DFF),
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(7.dp))
            SettingsInfoRow(
                icon = Icons.Default.VideoSettings,
                title = if (language == AppLanguage.ARABIC) "إعدادات التحرير" else "Editing settings",
                description = if (language == AppLanguage.ARABIC) "خيارات مرتبطة بسلوك محرر الفيديو وأدواته" else "Options related to the video editor and its tools"
            )
            SettingsInfoRow(
                icon = Icons.Default.Tune,
                title = if (language == AppLanguage.ARABIC) "إعدادات المعالجة" else "Processing settings",
                description = if (language == AppLanguage.ARABIC) "إعدادات الصورة والصوت والمؤثرات المستخدمة أثناء التحرير" else "Video, audio and effects processing options"
            )

            Spacer(Modifier.height(16.dp))
            Text(
                if (language == AppLanguage.ARABIC) "التصدير" else "Export",
                color = Color(0xFF6D3DFF),
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(7.dp))
            SettingsInfoRow(
                icon = Icons.Default.VideoFile,
                title = if (language == AppLanguage.ARABIC) "إعدادات التصدير" else "Export settings",
                description = if (language == AppLanguage.ARABIC) "الدقة والترميز ومعدل الإطارات وجودة الفيديو" else "Resolution, codec, frame rate and video quality"
            )

            Spacer(Modifier.height(16.dp))
            Text(
                if (language == AppLanguage.ARABIC) "التطبيق" else "Application",
                color = Color(0xFF6D3DFF),
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(7.dp))
            SettingsInfoRow(
                icon = Icons.Default.Storage,
                title = if (language == AppLanguage.ARABIC) "المشاريع والمسودات" else "Projects & drafts",
                description = if (language == AppLanguage.ARABIC) "إدارة المشاريع المحفوظة وملفات العمل" else "Manage saved projects and working files"
            )
            SettingsInfoRow(
                icon = Icons.Default.Info,
                title = if (language == AppLanguage.ARABIC) "حول VideoForge" else "About VideoForge",
                description = if (language == AppLanguage.ARABIC) "معلومات التطبيق وإصداره" else "Application information and version"
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SettingsInfoRow(
    icon: ImageVector,
    title: String,
    description: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = 7.dp),
        color = Color(0xFFF5F7FB),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            Modifier.padding(13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xFFEDE8FF)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = Color(0xFF6D3DFF))
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color(0xFF172033), fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                Spacer(Modifier.height(2.dp))
                Text(description, color = Color(0xFF667085), fontSize = 9.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TemplatesSheet(onDismiss: () -> Unit, onUseTemplate: () -> Unit) {
    val templates = listOf(
        Triple(Icons.Default.MovieFilter, stringResource(R.string.template_cinematic), stringResource(R.string.template_cinematic_desc)),
        Triple(Icons.Default.Favorite, stringResource(R.string.template_social), stringResource(R.string.template_social_desc)),
        Triple(Icons.Default.Bolt, stringResource(R.string.template_fast), stringResource(R.string.template_fast_desc))
    )
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF0A111D)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.ready_templates), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            templates.forEach { (icon, title, desc) ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF111B2A)).clickable { onUseTemplate() }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF28185C)), contentAlignment = Alignment.Center) {
                        Icon(icon, null, tint = Color(0xFFC9A7FF))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, fontWeight = FontWeight.Bold)
                        Text(desc, color = Color.Gray, fontSize = 11.sp)
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = Color.LightGray)
                }
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

private data class EditorSnapshot(
    val clips: List<Clip>,
    val settings: EditorSettings,
    val projectName: String
)

private fun clipTimelineDuration(clip: Clip): Long {
    val end = if (clip.trimEndMs == Long.MAX_VALUE) clip.durationMs else clip.trimEndMs
    return (end - clip.trimStartMs).coerceAtLeast(1L)
}

private fun timelineTotalDuration(clips: List<Clip>): Long =
    clips.sumOf(::clipTimelineDuration).coerceAtLeast(1L)

private fun timelinePositionOf(clips: List<Clip>, target: Clip?): Long {
    if (target == null) return 0L
    var position = 0L
    for (clip in clips) {
        if (clip == target) return position
        position += clipTimelineDuration(clip)
    }
    return 0L
}

private fun timelineClipAt(clips: List<Clip>, positionMs: Long): Pair<Clip, Long>? {
    var offset = 0L
    val p = positionMs.coerceAtLeast(0L)
    for (clip in clips) {
        val d = clipTimelineDuration(clip)
        if (p <= offset + d || clip == clips.last()) {
            return clip to (p - offset).coerceIn(0L, d)
        }
        offset += d
    }
    return null
}


@Composable
private fun EditorFeaturePanel(
    activeTool: String?, settings: EditorSettings, current: Clip?, clips: List<Clip>, language: AppLanguage,
    onSettingsLiveChange: (EditorSettings) -> Unit, onCurrentClipChange: (Clip) -> Unit, onTrim: () -> Unit, onSplit: () -> Unit,
    onDelete: () -> Unit, onDuplicate: () -> Unit, onReplace: () -> Unit,
    onMoveLeft: () -> Unit, onMoveRight: () -> Unit, onFreeze: () -> Unit,
    onExtractAudio: () -> Unit, onAudioKeyframes: () -> Unit, onMusicKeyframes: () -> Unit,
    onTextDialog: () -> Unit, onTextAnimation: () -> Unit, onSubtitles: () -> Unit,
    onLayersDialog: () -> Unit, onVideoKeyframes: () -> Unit, onMarkers: () -> Unit
) {
    if (activeTool == null) return
    var adjustFeature by remember(activeTool) { mutableStateOf("brightness") }
    var effectFeature by remember(activeTool) { mutableStateOf("blur") }
    var audioFeature by remember(activeTool) { mutableStateOf("volume") }

    val mainTitle = when (activeTool) {
        "edit" -> if (language == AppLanguage.ARABIC) "تحرير المقطع" else "Edit clip"
        "audio" -> if (language == AppLanguage.ARABIC) "الصوت" else "Audio"
        "text" -> if (language == AppLanguage.ARABIC) "النص" else "Text"
        "effects" -> if (language == AppLanguage.ARABIC) "المؤثرات" else "Effects"
        "filters" -> if (language == AppLanguage.ARABIC) "الفلاتر" else "Filters"
        "adjust" -> if (language == AppLanguage.ARABIC) "تعديل الصورة" else "Adjust"
        "canvas" -> if (language == AppLanguage.ARABIC) "المقاس والقص" else "Canvas & Crop"
        "transition" -> if (language == AppLanguage.ARABIC) "الانتقالات" else "Transitions"
        "subtitles" -> if (language == AppLanguage.ARABIC) "الترجمة" else "Subtitles"
        "layers" -> if (language == AppLanguage.ARABIC) "الطبقات" else "Layers"
        "videoKeyframes" -> if (language == AppLanguage.ARABIC) "الحركة" else "Motion"
        else -> if (language == AppLanguage.ARABIC) "المزيد" else "More"
    }

    val featureItems = when (activeTool) {
        "edit" -> listOf(
            Triple("trim", Icons.Default.ContentCut, if(language==AppLanguage.ARABIC)"قص" else "Trim"),
            Triple("split", Icons.Default.CallSplit, if(language==AppLanguage.ARABIC)"تقسيم" else "Split"),
            Triple("duplicate", Icons.Default.ContentCopy, if(language==AppLanguage.ARABIC)"تكرار" else "Duplicate"),
            Triple("replace", Icons.Default.SwapHoriz, if(language==AppLanguage.ARABIC)"استبدال" else "Replace"),
            Triple("delete", Icons.Default.Delete, if(language==AppLanguage.ARABIC)"حذف" else "Delete"),
            Triple("left", Icons.Default.KeyboardArrowLeft, if(language==AppLanguage.ARABIC)"يسار" else "Left"),
            Triple("right", Icons.Default.KeyboardArrowRight, if(language==AppLanguage.ARABIC)"يمين" else "Right"),
            Triple("freeze", Icons.Default.AcUnit, if(language==AppLanguage.ARABIC)"تجميد" else "Freeze")
        )
        "audio" -> listOf(
            Triple("volume", Icons.Default.VolumeUp, if(language==AppLanguage.ARABIC)"مستوى الصوت" else "Volume"),
            Triple("mute", Icons.Default.VolumeOff, if(language==AppLanguage.ARABIC)"كتم" else "Mute"),
            Triple("fadeIn", Icons.Default.TrendingUp, if(language==AppLanguage.ARABIC)"تلاشي دخول" else "Fade in"),
            Triple("fadeOut", Icons.Default.TrendingDown, if(language==AppLanguage.ARABIC)"تلاشي خروج" else "Fade out"),
            Triple("keys", Icons.Default.Timeline, if(language==AppLanguage.ARABIC)"مفاتيح الصوت" else "Keyframes"),
            Triple("music", Icons.Default.MusicNote, if(language==AppLanguage.ARABIC)"الموسيقى" else "Music")
        )
        "text" -> listOf(
            Triple("text", Icons.Default.TextFields, if(language==AppLanguage.ARABIC)"إضافة/تعديل النص" else "Text"),
            Triple("animation", Icons.Default.Animation, if(language==AppLanguage.ARABIC)"الحركة" else "Animation"),
            Triple("textLayers", Icons.Default.Layers, if(language==AppLanguage.ARABIC)"طبقات النص" else "Text layers")
        )
        "effects" -> listOf(
            Triple("blur", Icons.Default.BlurOn, if(language==AppLanguage.ARABIC)"ضبابية" else "Blur"),
            Triple("mosaic", Icons.Default.GridOn, if(language==AppLanguage.ARABIC)"بكسلة" else "Mosaic")
        )
        "filters" -> listOf(
            Triple("none", Icons.Default.FilterNone, if(language==AppLanguage.ARABIC)"بدون" else "None"),
            Triple("warm", Icons.Default.WbSunny, if(language==AppLanguage.ARABIC)"دافئ" else "Warm"),
            Triple("cool", Icons.Default.AcUnit, if(language==AppLanguage.ARABIC)"بارد" else "Cool"),
            Triple("mono", Icons.Default.Contrast, if(language==AppLanguage.ARABIC)"أبيض وأسود" else "Mono"),
            Triple("vintage", Icons.Default.PhotoFilter, if(language==AppLanguage.ARABIC)"فنتج" else "Vintage"),
            Triple("dramatic", Icons.Default.TheaterComedy, if(language==AppLanguage.ARABIC)"درامي" else "Dramatic"),