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
            if (showEditor) {
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
                                ActivityResultContracts.PickVisualMedia.VideoOnly
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
                    onViewAllProjects = { selected = 1 }
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
    onViewAllProjects: () -> Unit
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
            HomeBottomBar(selected = selected, onSelected = onSelected, onNewProject = onNewProject)
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
                        listOf(Color.Transparent, Color.Transparent, Color(0xC9000815))
                    )
                )
        )

        Column(
            Modifier
                .align(alignment)
                .widthIn(max = 300.dp)
                .padding(horizontal = 22.dp),
            horizontalAlignment = if (language == AppLanguage.ARABIC) Alignment.End else Alignment.Start
        ) {
            Text(
                stringResource(R.string.turn_ideas),
                fontSize = 24.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = textAlign
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.professional_tools),
                color = Color.White.copy(alpha = .9f),
                fontSize = 12.sp,
                lineHeight = 17.sp,
                textAlign = textAlign
            )
            Spacer(Modifier.height(15.dp))
            Button(
                onClick = onNewProject,
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7041FF)),
                contentPadding = PaddingValues(horizontal = 17.dp, vertical = 8.dp)
            ) {
                if (language == AppLanguage.ARABIC) {
                    Text(stringResource(R.string.new_project), fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
        Text(title, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(3.dp))
        Text(subtitle, color = Color.White.copy(alpha = .7f), fontSize = 8.sp, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
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
            Text(action, color = Color(0xFF9D70FF), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { onAction?.invoke() })
            Spacer(Modifier.weight(1f))
        }
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
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
                    .width(67.dp)
                    .height(76.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(Color(0xFF0E1725))
                    .padding(vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(icon, null, tint = Color(0xFFD09CFF), modifier = Modifier.size(24.dp))
                Spacer(Modifier.height(5.dp))
                Text(label, fontSize = 9.sp, maxLines = 1)
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
                Text(project.name, color = Color(0xFF172033), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(formatProjectDate(project.dateModifiedSeconds), color = Color(0xFF667085), fontSize = 8.sp, maxLines = 1)
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
            Text(stringResource(R.string.premium_features), fontWeight = FontWeight.Bold, color = Color(0xFFFFD36B), fontSize = 13.sp)
            Text(stringResource(R.string.premium_description), fontSize = 8.sp, color = Color.LightGray, maxLines = 2)
        }
        Button(
            onClick = {},
            shape = RoundedCornerShape(23.dp),
            contentPadding = PaddingValues(horizontal = 11.dp, vertical = 5.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7041FF))
        ) {
            Text(stringResource(R.string.upgrade_now), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun HomeBottomBar(selected: Int, onSelected: (Int) -> Unit, onNewProject: () -> Unit) {
    NavigationBar(containerColor = Color.White, tonalElevation = 2.dp, modifier = Modifier.height(72.dp)) {
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
            selected = false, onClick = onNewProject,
            icon = {
                Box(Modifier.size(50.dp).offset(y = (-8).dp).clip(RoundedCornerShape(50))
                    .background(Brush.linearGradient(listOf(Color(0xFF8A45FF), Color(0xFF216DFF)))),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.Add, null, Modifier.size(30.dp), tint = Color.White) }
            },
            label = { Text(stringResource(R.string.new_project), fontSize = 9.sp) }
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
    onSettingsLiveChange: (EditorSettings) -> Unit, onTrim: () -> Unit, onSplit: () -> Unit,
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
            Triple("soft", Icons.Default.FilterVintage, if(language==AppLanguage.ARABIC)"ناعم" else "Soft")
        )
        "adjust" -> listOf(
            Triple("brightness", Icons.Default.Brightness6, if(language==AppLanguage.ARABIC)"السطوع" else "Brightness"),
            Triple("contrast", Icons.Default.Contrast, if(language==AppLanguage.ARABIC)"التباين" else "Contrast"),
            Triple("saturation", Icons.Default.Colorize, if(language==AppLanguage.ARABIC)"التشبع" else "Saturation"),
            Triple("hue", Icons.Default.Palette, if(language==AppLanguage.ARABIC)"درجة اللون" else "Hue"),
            Triple("temperature", Icons.Default.Thermostat, if(language==AppLanguage.ARABIC)"الحرارة" else "Temperature"),
            Triple("tint", Icons.Default.ColorLens, if(language==AppLanguage.ARABIC)"الصبغة" else "Tint")
        )
        "canvas" -> listOf(
            Triple("16:9", Icons.Default.Screenshot, "16:9"),
            Triple("9:16", Icons.Default.StayCurrentPortrait, "9:16"),
            Triple("1:1", Icons.Default.CropSquare, "1:1"),
            Triple("4:5", Icons.Default.CropPortrait, "4:5"),
            Triple("crop", Icons.Default.Crop, if(language==AppLanguage.ARABIC)"قص حر" else "Crop"),
            Triple("rotate", Icons.AutoMirrored.Filled.RotateRight, if(language==AppLanguage.ARABIC)"تدوير" else "Rotate"),
            Triple("flip", Icons.Default.Flip, if(language==AppLanguage.ARABIC)"قلب" else "Flip")
        )
        "transition" -> listOf(
            Triple("none", Icons.Default.Block, if(language==AppLanguage.ARABIC)"بدون" else "None"),
            Triple("fade", Icons.Default.BlurOn, if(language==AppLanguage.ARABIC)"تلاشي" else "Fade"),
            Triple("slide", Icons.Default.Swipe, if(language==AppLanguage.ARABIC)"انزلاق" else "Slide"),
            Triple("zoom", Icons.Default.ZoomIn, if(language==AppLanguage.ARABIC)"تكبير" else "Zoom"),
            Triple("wipe", Icons.Default.Swipe, if(language==AppLanguage.ARABIC)"مسح" else "Wipe"),
            Triple("flash", Icons.Default.FlashOn, if(language==AppLanguage.ARABIC)"فلاش" else "Flash")
        )
        "subtitles" -> listOf(
            Triple("open", Icons.Default.Subtitles, if(language==AppLanguage.ARABIC)"إدارة الترجمة" else "Manage"),
            Triple("markers", Icons.Default.Bookmark, if(language==AppLanguage.ARABIC)"العلامات" else "Markers")
        )
        "layers" -> listOf(
            Triple("text", Icons.Default.TextFields, if(language==AppLanguage.ARABIC)"النصوص" else "Text"),
            Triple("pip", Icons.Default.PictureInPicture, if(language==AppLanguage.ARABIC)"PIP" else "PIP"),
            Triple("manage", Icons.Default.Layers, if(language==AppLanguage.ARABIC)"إدارة الطبقات" else "Manage layers")
        )
        "videoKeyframes" -> listOf(
            Triple("video", Icons.Default.MovieFilter, if(language==AppLanguage.ARABIC)"حركة الفيديو" else "Video motion"),
            Triple("markers", Icons.Default.Bookmark, if(language==AppLanguage.ARABIC)"العلامات" else "Markers")
        )
        else -> listOf(
            Triple("freeze", Icons.Default.AcUnit, if(language==AppLanguage.ARABIC)"تجميد" else "Freeze"),
            Triple("markers", Icons.Default.Bookmark, if(language==AppLanguage.ARABIC)"علامة" else "Marker"),
            Triple("extract", Icons.Default.AudioFile, if(language==AppLanguage.ARABIC)"استخراج الصوت" else "Extract audio")
        )
    }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
        color = Color(0xFF0C1420), shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(vertical = 7.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(mainTitle, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(if(language==AppLanguage.ARABIC)"لوحة ثابتة أسفل المخطط" else "Inline panel below timeline", color=Color(0xFF8F9CAF), fontSize=8.sp)
            }
            LazyRow(
                Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(featureItems, key={it.first}) { (id, icon, label) ->
                    val selected = when(activeTool) {
                        "adjust" -> adjustFeature == id
                        "effects" -> effectFeature == id
                        "filters" -> settings.filter == id
                        "canvas" -> settings.aspect == id
                        else -> false
                    }
                    FilterChip(
                        selected=selected,
                        onClick={
                            when(activeTool) {
                                "edit" -> when(id) {
                                    "trim" -> onTrim(); "split" -> onSplit(); "duplicate" -> onDuplicate(); "replace" -> onReplace()
                                    "delete" -> onDelete(); "left" -> onMoveLeft(); "right" -> onMoveRight(); "freeze" -> onFreeze()
                                }
                                "audio" -> when(id) {
                                    "volume","fadeIn","fadeOut" -> audioFeature=id
                                    "mute" -> onSettingsLiveChange(settings.copy(muted=!settings.muted))
                                    "keys" -> onAudioKeyframes(); "music" -> onMusicKeyframes()
                                }
                                "text" -> when(id) { "text" -> onTextDialog(); "animation" -> onTextAnimation(); "textLayers" -> onLayersDialog() }
                                "effects" -> effectFeature=id
                                "filters" -> onSettingsLiveChange(settings.copy(filter=id))
                                "adjust" -> adjustFeature=id
                                "canvas" -> when(id) {
                                    "crop" -> onSettingsLiveChange(settings.copy(cropZoom=(settings.cropZoom+0.1f).coerceAtMost(3f)))
                                    "rotate" -> onSettingsLiveChange(settings.copy(rotation=(settings.rotation+90)%360))
                                    "flip" -> onSettingsLiveChange(settings.copy(flipHorizontal=!settings.flipHorizontal))
                                    else -> onSettingsLiveChange(settings.copy(aspect=id))
                                }
                                "transition" -> if(id!="none") onSettingsLiveChange(settings.copy(transition=id))
                                "subtitles" -> when(id) { "open" -> onSubtitles(); "markers" -> onMarkers() }
                                "layers" -> when(id) { "manage","text","pip" -> onLayersDialog() }
                                "videoKeyframes" -> when(id) { "video" -> onVideoKeyframes(); "markers" -> onMarkers() }
                                else -> when(id) { "freeze" -> onFreeze(); "markers" -> onMarkers(); "extract" -> onExtractAudio() }
                            }
                        },
                        leadingIcon={Icon(icon,null,Modifier.size(16.dp))},
                        label={Text(label,fontSize=9.sp,maxLines=1)}
                    )
                }
            }

            if(activeTool=="adjust") {
                val value=when(adjustFeature) {
                    "brightness" -> settings.brightness; "contrast" -> settings.contrast; "saturation" -> settings.saturation
                    "hue" -> settings.hue; "temperature" -> settings.temperature; else -> settings.tint
                }
                val range=when(adjustFeature) {
                    "brightness" -> -1f..1f; "contrast" -> 0f..2f; "saturation" -> 0f..2f; "hue" -> -180f..180f; else -> -100f..100f
                }
                Text(
                    (featureItems.firstOrNull{it.first==adjustFeature}?.third ?: "") + "  " +
                        if(adjustFeature=="contrast"||adjustFeature=="saturation") (value*100).toInt().toString() else value.toInt().toString(),
                    Modifier.padding(horizontal=14.dp), fontSize=10.sp
                )
                Slider(
                    value=value,
                    onValueChange={v -> onSettingsLiveChange(when(adjustFeature) {
                        "brightness" -> settings.copy(brightness=v); "contrast" -> settings.copy(contrast=v); "saturation" -> settings.copy(saturation=v)
                        "hue" -> settings.copy(hue=v); "temperature" -> settings.copy(temperature=v); else -> settings.copy(tint=v)
                    })},
                    valueRange=range, modifier=Modifier.padding(horizontal=12.dp)
                )
            }

            if(activeTool=="audio" && current!=null) {
                val audioClip=current
                Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
                    when(audioFeature) {
                        "volume" -> {
                            Text((audioClip.audioVolume*100).toInt().toString()+"%",fontSize=10.sp,Modifier.width(42.dp))
                            Slider(
                                value=audioClip.audioVolume,
                                onValueChange={v -> onSettingsLiveChange(settings)},
                                valueRange=0f..2f, modifier=Modifier.weight(1f)
                            )
                        }
                        "fadeIn" -> {
                            Text(audioClip.audioFadeIn.toInt().toString()+"s",fontSize=10.sp,Modifier.width(42.dp))
                            Slider(
                                value=audioClip.audioFadeIn,
                                onValueChange={v -> onSettingsLiveChange(settings.copy(fadeIn=v))},
                                valueRange=0f..10f,modifier=Modifier.weight(1f)
                            )
                        }
                        "fadeOut" -> {
                            Text(audioClip.audioFadeOut.toInt().toString()+"s",fontSize=10.sp,Modifier.width(42.dp))
                            Slider(
                                value=audioClip.audioFadeOut,
                                onValueChange={v -> onSettingsLiveChange(settings.copy(fadeOut=v))},
                                valueRange=0f..10f,modifier=Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            if(activeTool=="effects") {
                if(effectFeature=="blur") {
                    Text((if(language==AppLanguage.ARABIC)"ضبابية " else "Blur ") + settings.blurRadius.toInt(),Modifier.padding(horizontal=14.dp),fontSize=10.sp)
                    Slider(value=settings.blurRadius,onValueChange={onSettingsLiveChange(settings.copy(blurRadius=it))},valueRange=0f..20f,modifier=Modifier.padding(horizontal=12.dp))
                } else {
                    Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){
                        Text(if(language==AppLanguage.ARABIC)"البكسلة" else "Mosaic",Modifier.weight(1f),fontSize=10.sp)
                        Switch(checked=settings.mosaicEnabled,onCheckedChange={onSettingsLiveChange(settings.copy(mosaicEnabled=it))})
                    }
                    Text((if(language==AppLanguage.ARABIC)"حجم البكسل " else "Block size ")+(settings.mosaicBlockSize*100).toInt()+"%",Modifier.padding(horizontal=14.dp),fontSize=10.sp)
                    Slider(value=settings.mosaicBlockSize,onValueChange={onSettingsLiveChange(settings.copy(mosaicBlockSize=it))},valueRange=0.02f..0.20f,modifier=Modifier.padding(horizontal=12.dp))
                }
            }
        }
    }
}


@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun EditorScreen(
    projectId: String,
    projectName: String,
    clips: List<Clip>,
    onClipsChanged: (List<Clip>) -> Unit,
    onProjectNameChanged: (String) -> Unit,
    onBack: () -> Unit,
    language: AppLanguage,
    onLanguageSelected: (AppLanguage) -> Unit
) {
    val context = LocalContext.current
    var current by remember { mutableStateOf(clips.firstOrNull()) }
    var settings by remember(projectId) { mutableStateOf(EditorSettingsRepository.load(context, projectId)) }
    var showTrim by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showKeyframes by remember { mutableStateOf(false) }
    var showVideoKeyframes by remember { mutableStateOf(false) }
    var showAudioKeyframes by remember { mutableStateOf(false) }
    var showMusicKeyframes by remember { mutableStateOf(false) }
    var showSubtitles by remember { mutableStateOf(false) }
    var showLayers by remember { mutableStateOf(false) }
    var showMarkers by remember { mutableStateOf(false) }
    var playheadMs by remember { mutableLongStateOf(0L) }
    var previewPlaying by remember { mutableStateOf(false) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var previewToggleToken by remember { mutableIntStateOf(0) }
    var exportSettings by remember { mutableStateOf(ExportSettings()) }
    var status by remember { mutableStateOf("") }
    var lastExportUri by remember { mutableStateOf<Uri?>(null) }
    val exportScope = rememberCoroutineScope()
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        if (uri != null && clips.isNotEmpty()) {
            status = if (language == AppLanguage.ARABIC) "جاري التصدير…" else "Exporting…"
            exportScope.launch {
                val result = ExportEngine(context, context.contentResolver).export(
                    clips = clips, settings = exportSettings, editor = settings, output = uri,
                    onProgress = { progress -> status = progress.message }
                )
                status = if (result.isSuccess) {
                    lastExportUri = uri
                    if (language == AppLanguage.ARABIC) "تم تصدير الفيديو بنجاح" else "Video exported successfully"
                } else {
                    if (language == AppLanguage.ARABIC) "فشل التصدير: ${result.exceptionOrNull()?.message ?: "خطأ"}" else "Export failed: ${result.exceptionOrNull()?.message ?: "Unknown error"}"
                }
            }
        }
    }
    val subtitleImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val imported = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { parseSrt(it.readText()) } ?: emptyList()
                if (imported.isNotEmpty()) { settings = settings.copy(subtitles = imported); EditorSettingsRepository.save(context, projectId, settings) }
                status = if (imported.isNotEmpty()) (if (language == AppLanguage.ARABIC) "تم استيراد ${imported.size} ترجمة" else "Imported ${imported.size} subtitles") else (if (language == AppLanguage.ARABIC) "ملف SRT فارغ أو غير صالح" else "Empty or invalid SRT file")
            }.onFailure { status = if (language == AppLanguage.ARABIC) "تعذر استيراد الترجمة" else "Could not import subtitles" }
        }
    }
    val subtitleExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(subtitlesToSrt(settings.subtitles)) }
                status = if (language == AppLanguage.ARABIC) "تم تصدير ملف الترجمة" else "Subtitle file exported"
            }.onFailure { status = if (language == AppLanguage.ARABIC) "تعذر تصدير الترجمة" else "Could not export subtitles" }
        }
    }
    var tool by remember { mutableStateOf<String?>(null) }
    var activeEditorTool by remember { mutableStateOf<String?>(null) }
    var editingName by remember(projectName) { mutableStateOf(projectName) }
    val undoStack = remember(projectId) { mutableStateListOf<EditorSnapshot>() }
    val redoStack = remember(projectId) { mutableStateListOf<EditorSnapshot>() }

    fun pushUndo() {
        undoStack.add(EditorSnapshot(clips, settings, editingName))
        if (undoStack.size > 50) undoStack.removeAt(0)
        redoStack.clear()
    }

    fun commitClips(next: List<Clip>) {
        if (next == clips) return
        pushUndo()
        onClipsChanged(next)
    }

    fun commitSettings(next: EditorSettings) {
        if (next == settings) return
        pushUndo()
        settings = next
        EditorSettingsRepository.save(context, projectId, next)
        if (clips.isNotEmpty()) ProjectRepository.save(context, projectId, clips, editingName)
    }

    fun updateSettings(next: EditorSettings) = commitSettings(next)

    fun updateSettingsLive(next: EditorSettings) {
        settings = next
        EditorSettingsRepository.save(context, projectId, next)
    }


    fun undo() {
        val snap = undoStack.removeLastOrNull() ?: return
        redoStack.add(EditorSnapshot(clips, settings, editingName))
        onClipsChanged(snap.clips)
        settings = snap.settings
        editingName = snap.projectName
        onProjectNameChanged(snap.projectName)
        EditorSettingsRepository.save(context, projectId, snap.settings)
    }

    fun redo() {
        val snap = redoStack.removeLastOrNull() ?: return
        undoStack.add(EditorSnapshot(clips, settings, editingName))
        onClipsChanged(snap.clips)
        settings = snap.settings
        editingName = snap.projectName
        onProjectNameChanged(snap.projectName)
        EditorSettingsRepository.save(context, projectId, snap.settings)
    }

    LaunchedEffect(clips, editingName, settings) {
        if (clips.isNotEmpty()) {
            kotlinx.coroutines.delay(550)
            ProjectRepository.save(context, projectId, clips, editingName)
            EditorSettingsRepository.save(context, projectId, settings)
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, clips, editingName, settings) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE && clips.isNotEmpty()) {
                ProjectRepository.save(context, projectId, clips, editingName)
                EditorSettingsRepository.save(context, projectId, settings)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        if (uris.isNotEmpty()) {
            val added = uris.mapIndexed { i, uri ->
                persistUriAccess(context, uri)
                Clip(uri, context.getString(R.string.clip_number, clips.size + i + 1))
            }
            commitClips(clips + added)
            current = added.first()
        }
    }
    fun extractAudioFromCurrent() {
        val clip = current
        if (clip == null) {
            status = if (language == AppLanguage.ARABIC) "اختر مقطع فيديو أولاً" else "Select a video clip first"
            return
        }
        exportScope.launch {
            status = if (language == AppLanguage.ARABIC) "جاري استخراج الصوت…" else "Extracting audio…"
            val baseName = clip.name.substringBeforeLast('.').ifBlank { "VideoForge_audio" }
            val result = AudioExtractor.extractToMediaStore(
                resolver = context.contentResolver,
                source = clip.uri,
                displayName = baseName
            ) { progress ->
                val percent = (progress * 100f).toInt().coerceIn(0, 100)
                status = if (language == AppLanguage.ARABIC) "جاري استخراج الصوت… $percent%" else "Extracting audio… $percent%"
            }
            status = if (result.isSuccess) {
                if (language == AppLanguage.ARABIC) "تم استخراج الصوت وحفظه في Music/VideoForge" else "Audio extracted to Music/VideoForge"
            } else {
                val message = result.exceptionOrNull()?.message ?: "Unknown error"
                if (language == AppLanguage.ARABIC) "تعذر استخراج الصوت: " + message else "Audio extraction failed: " + message
            }
        }
    }
    fun createFreezeFrame() {
        val clip = current
        if (clip == null) {
            status = if (language == AppLanguage.ARABIC) "اختر مقطعاً أولاً" else "Select a clip first"
            return
        }
        val offset = timelinePositionOf(clips, clip)
        val local = (playheadMs - offset).coerceIn(0L, clipTimelineDuration(clip))
        val sourceTimeMs = (clip.trimStartMs + local).coerceAtLeast(0L)
        exportScope.launch {
            status = if (language == AppLanguage.ARABIC) "جاري إنشاء الإطار الثابت…" else "Creating freeze frame…"
            val file = runCatching {
                val retriever = MediaMetadataRetriever()
                val bitmap = try {
                    retriever.setDataSource(context, clip.uri)
                    retriever.getFrameAtTime(sourceTimeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
                } finally {
                    retriever.release()
                } ?: error("Unable to capture frame")
                val out = java.io.File(context.cacheDir, "freeze_${System.currentTimeMillis()}.jpg")
                out.outputStream().use { stream ->
                    if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, stream)) error("Unable to save frame")
                }
                bitmap.recycle()
                out
            }.getOrNull()
            if (file == null) {
                status = if (language == AppLanguage.ARABIC) "تعذر إنشاء الإطار الثابت" else "Could not create freeze frame"
                return@launch
            }
            val freeze = Clip(
                uri = Uri.fromFile(file),
                name = clip.name.substringBeforeLast('.').ifBlank { clip.name } + " • Freeze",
                durationMs = 1000L,
                trimStartMs = 0L,
                trimEndMs = 1000L,
                audioMuted = true,
                isFreezeFrame = true,
                freezeDurationMs = 1000L
            )
            val index = clips.indexOf(clip)
            if (index < 0) return@launch
            val start = clip.trimStartMs
            val end = if (clip.trimEndMs == Long.MAX_VALUE) clip.durationMs else clip.trimEndMs
            if (end - start <= 2L || local <= 0L || local >= end - start) {
                val next = clips.toMutableList().also { it.add(index + 1, freeze) }
                commitClips(next)
                current = freeze
                playheadMs = timelinePositionOf(next, freeze)
            } else {
                val cut = (start + local).coerceIn(start + 1L, end - 1L)
                val left = clip.copy(trimEndMs = cut, name = clip.name.substringBeforeLast('.').ifBlank { clip.name } + " • 1")
                val right = clip.copy(trimStartMs = cut, name = clip.name.substringBeforeLast('.').ifBlank { clip.name } + " • 2")
                val next = clips.toMutableList().also {
                    it.removeAt(index)
                    it.add(index, left)
                    it.add(index + 1, freeze)
                    it.add(index + 2, right)
                }
                commitClips(next)
                current = freeze
                playheadMs = timelinePositionOf(next, freeze)
            }
            status = if (language == AppLanguage.ARABIC) "تمت إضافة إطار ثابت لمدة ثانية" else "1-second freeze frame added"
        }
    }

    val replaceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val selected = current
        if (uri != null && selected != null) {
            persistUriAccess(context, uri)
            val duration = mediaDurationMs(context, uri).coerceAtLeast(0L)
            val updated = selected.copy(uri = uri, name = uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { selected.name } ?: selected.name, durationMs = duration, trimStartMs = 0L, trimEndMs = duration)
            commitClips(clips.map { if (it == selected) updated else it })
            current = updated
            playheadMs = timelinePositionOf(clips.map { if (it == selected) updated else it }, updated)
            status = if (language == AppLanguage.ARABIC) "تم استبدال الوسائط" else "Media replaced"
        }
    }

    val overlayImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            persistUriAccess(context, uri)
            val newLayer = PipLayer(uri = uri.toString())
            val nextLayers = settings.pipLayers + newLayer
            commitSettings(settings.copy(pipLayers = nextLayers, overlayImageUri = newLayer.uri,
                overlayImageX = newLayer.x, overlayImageY = newLayer.y, overlayImageScale = newLayer.scale,
                overlayImageRotation = newLayer.rotation, overlayImageAlpha = newLayer.alpha))
            status = if (language == AppLanguage.ARABIC) "تمت إضافة طبقة PIP جديدة (" + nextLayers.size + ")" else "New PIP layer added (" + nextLayers.size + ")"
        }
    }

    val aiCutoutLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            persistUriAccess(context, uri)
            exportScope.launch {
                status = if (language == AppLanguage.ARABIC) "جاري قص العنصر بالذكاء الاصطناعي…" else "AI subject cutout in progress…"
                val cut = aiCutoutImage(context, uri)
                if (cut != null) {
                    val newLayer = PipLayer(uri = cut.toString())
                    val nextLayers = settings.pipLayers + newLayer
                    commitSettings(settings.copy(pipLayers = nextLayers, overlayImageUri = newLayer.uri,
                        overlayImageX = newLayer.x, overlayImageY = newLayer.y, overlayImageScale = newLayer.scale,
                        overlayImageRotation = newLayer.rotation, overlayImageAlpha = newLayer.alpha))
                    status = if (language == AppLanguage.ARABIC) "تم قص العنصر وإضافته كطبقة PIP (" + nextLayers.size + ")" else "Subject cutout added as PIP layer (" + nextLayers.size + ")"
                } else status = if (language == AppLanguage.ARABIC) "تعذر قص الصورة — تحقق من توفر نموذج ML Kit" else "AI cutout failed — check ML Kit model availability"
            }
        }
    }


    Scaffold(
        containerColor = Color(0xFF050912),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("VideoForge", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(if (language == AppLanguage.ARABIC) "محرر احترافي" else "Professional editor", color = Color.Gray, fontSize = 9.sp)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
                actions = {
                    IconButton(onClick = { undo() }, enabled = undoStack.isNotEmpty()) { Icon(Icons.AutoMirrored.Filled.Undo, null) }
                    IconButton(onClick = { redo() }, enabled = redoStack.isNotEmpty()) { Icon(Icons.AutoMirrored.Filled.Redo, null) }
                    IconButton(onClick = { tool = "history" }) { Icon(Icons.Default.History, null) }
                    IconButton(onClick = { tool = "language" }) { Icon(Icons.Default.Language, null) }
                    FilledTonalButton(onClick = { showExport = true }) { Icon(Icons.Default.FileUpload, null, Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.export)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF050912))
            )
        },
        bottomBar = {
            Surface(
                color = Color(0xFF0A0F18),
                tonalElevation = 8.dp,
                shadowElevation = 10.dp
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val dockTools = listOf(
                        Triple("edit", Icons.Default.Edit, if(language==AppLanguage.ARABIC)"تحرير" else "Edit"),
                        Triple("audio", Icons.Default.AudioFile, if(language==AppLanguage.ARABIC)"صوت" else "Audio"),
                        Triple("effects", Icons.Default.AutoAwesome, if(language==AppLanguage.ARABIC)"مؤثرات" else "Effects"),
                        Triple("filters", Icons.Default.FilterVintage, if(language==AppLanguage.ARABIC)"فلتر" else "Filter"),
                        Triple("text", Icons.Default.TextFields, if(language==AppLanguage.ARABIC)"نص" else "Text"),
                        Triple("more", Icons.Default.MoreHoriz, if(language==AppLanguage.ARABIC)"المزيد" else "More")
                    )
                    dockTools.forEach { (id, icon, label) ->
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                                 .clickable { activeEditorTool = id }
                                .padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(icon, null, tint = Color.White, modifier = Modifier.size(23.dp))
                            Text(label, color = Color(0xFFB7C0D0), fontSize = 8.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = editingName,
                    onValueChange = { editingName = it; onProjectNameChanged(it) },
                    label = { Text(if (language == AppLanguage.ARABIC) "اسم المشروع" else "Project name") },
                    singleLine = true, modifier = Modifier.weight(1f), trailingIcon = { Icon(Icons.Default.Edit, null, Modifier.size(17.dp)) }
                )
                Spacer(Modifier.width(7.dp))
                AssistChip(onClick = {}, label = { Text(if (language == AppLanguage.ARABIC) "محفوظ" else "Saved", fontSize = 10.sp) }, leadingIcon = { Icon(Icons.Default.CloudDone, null, Modifier.size(16.dp)) })
            }

            EditorPreview(
                clip = current,
                settings = settings,
                playheadMs = playheadMs,
                clipOffsetMs = current?.let { timelinePositionOf(clips, it) } ?: 0L,
                onSettingsChange = { next ->
                    settings = next
                    EditorSettingsRepository.save(context, projectId, next)
                },
                onPlaybackPosition = { position ->
                    playheadMs = position.coerceIn(0L, timelineTotalDuration(clips))
                    timelineClipAt(clips, playheadMs)?.let { (clipAtPlayhead, _) ->
                        if (clipAtPlayhead != current) current = clipAtPlayhead
                    }
                },
                onPlaybackStateChanged = { previewPlaying = it },
                onPlaybackError = { previewError = it },
                playbackToggleToken = previewToggleToken
            )

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { playheadMs = (playheadMs - 5000L).coerceAtLeast(0L) },
                    enabled = current != null
                ) { Icon(Icons.Default.Replay5, null) }

                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${formatDuration(playheadMs)} / ${formatDuration(timelineTotalDuration(clips))}",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    previewError?.let {
                        Text(
                            if (language == AppLanguage.ARABIC) "تعذر تشغيل المعاينة" else "Preview playback error",
                            color = Color(0xFFFF8A80),
                            fontSize = 9.sp
                        )
                    }
                }

                FilledIconButton(
                    onClick = {
                        previewError = null
                        previewToggleToken += 1
                    },
                    enabled = current != null
                ) {
                    Icon(if (previewPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null)
                }

                IconButton(
                    onClick = { playheadMs = (playheadMs + 5000L).coerceAtMost(timelineTotalDuration(clips)) },
                    enabled = current != null
                ) { Icon(Icons.Default.Forward5, null) }

                IconButton(onClick = { activeEditorTool = "canvas" }, enabled = current != null) {
                    Icon(Icons.Default.CropFree, null)
                }
            }

            // Primary tools are placed after the timeline to match a professional editor workflow.
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (language == AppLanguage.ARABIC) "المخطط الزمني" else "Timeline", fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${clips.size} ${if (language == AppLanguage.ARABIC) "مقطع" else "clips"}", color = Color.Gray, fontSize = 10.sp)
            }
            Timeline(
                clips = clips, current = current, playheadMs = playheadMs,
                videoKeyframes = settings.videoKeyframes,
                textKeyframes = settings.textLayers.flatMap { it.keyframes },
                audioKeyframes = current?.let { selected ->
                    selected.audioKeyframes.map { k ->
                        ClipAudioKeyframe(
                            timeMs = timelinePositionOf(clips, selected) + k.timeMs,
                            volume = k.volume
                        )
                    }.map { AudioKeyframe(it.timeMs, it.volume) }
                } ?: settings.audioKeyframes,
                speedKeyframes = settings.speedKeyframes,
                musicUri = settings.musicUri,
                musicStartMs = settings.musicStartMs,
                musicDurationMs = settings.musicDurationMs,
                musicVolume = settings.musicVolume,
                musicFadeIn = settings.musicFadeIn,
                musicFadeOut = settings.musicFadeOut,
                musicKeyframes = settings.musicKeyframes,
                markers = settings.markers,
                audioBaseVolume = current?.audioVolume ?: settings.volume,
                audioFadeIn = maxOf(settings.fadeIn, current?.audioFadeIn ?: 0f),
                audioFadeOut = maxOf(settings.fadeOut, current?.audioFadeOut ?: 0f),
                onAudioTrackClick = { activeEditorTool = "audio" },
                onSelect = { clip -> current = clip; playheadMs = timelinePositionOf(clips, clip) },
                onPlayheadChange = { position ->
                    playheadMs = position.coerceIn(0L, timelineTotalDuration(clips))
                    timelineClipAt(clips, playheadMs)?.let { (clip, _) -> current = clip }
                },
                onDelete = { clip -> val u = clips.filterNot { it == clip }; commitClips(u); current = u.firstOrNull(); playheadMs = 0L },
                onMoveLeft = { clip -> val i = clips.indexOf(clip); if (i > 0) commitClips(clips.toMutableList().also { it.add(i - 1, it.removeAt(i)) }) },
                onMoveRight = { clip -> val i = clips.indexOf(clip); if (i >= 0 && i < clips.lastIndex) commitClips(clips.toMutableList().also { it.add(i + 1, it.removeAt(i)) }) },
                onTrimEdges = { clip, start, end ->
                    val updated = clip.copy(trimStartMs = start, trimEndMs = end)
                    commitClips(clips.map { if (it == clip) updated else it })
                    current = updated
                    playheadMs = timelinePositionOf(clips, updated).coerceAtMost(timelineTotalDuration(clips))
                },
                onSplit = { clip ->
                    val clipOffset = timelinePositionOf(clips, clip)
                    val localPlayhead = (playheadMs - clipOffset).coerceIn(0L, clipTimelineDuration(clip))
                    val start = clip.trimStartMs
                    val end = if (clip.trimEndMs == Long.MAX_VALUE) clip.durationMs else clip.trimEndMs
                    val cut = (start + localPlayhead).coerceIn(start + 1L, end - 1L)
                    if (end - start > 2L) {
                        val cutLocal = cut - start
                        val leftKeys = clip.audioKeyframes.filter { it.timeMs <= cutLocal }
                        val rightKeys = clip.audioKeyframes.filter { it.timeMs >= cutLocal }.map { it.copy(timeMs = (it.timeMs - cutLocal).coerceAtLeast(0L)) }
                        val left = clip.copy(name = clip.name.substringBeforeLast('.').ifBlank { clip.name } + " • 1", trimStartMs = start, trimEndMs = cut, audioKeyframes = leftKeys)
                        val right = clip.copy(name = clip.name.substringBeforeLast('.').ifBlank { clip.name } + " • 2", trimStartMs = cut, trimEndMs = end, audioKeyframes = rightKeys)
                        val idx = clips.indexOf(clip)
                        commitClips(clips.toMutableList().also { it.removeAt(idx); it.add(idx, left); it.add(idx + 1, right) })
                        current = left; playheadMs = timelinePositionOf(clips, left)
                    }
                },
                onKeyframeSeek = { position ->
                    val safe = position.coerceIn(0L, timelineTotalDuration(clips))
                    playheadMs = safe
                    timelineClipAt(clips, safe)?.let { (clip, _) -> current = clip }
                },
                onVideoKeyframeMove = { oldGlobalMs, newGlobalMs ->
                    val selectedClip = current ?: return@Timeline
                    val offset = timelinePositionOf(clips, selectedClip)
                    val localDuration = clipTimelineDuration(selectedClip)
                    val newLocal = (newGlobalMs - offset).coerceIn(0L, localDuration)
                    val oldLocal = (oldGlobalMs - offset).coerceIn(0L, localDuration)
                    val nearest = settings.videoKeyframes.minByOrNull { kotlin.math.abs(it.timeMs - oldLocal) } ?: return@Timeline
                    val moved = nearest.copy(timeMs = newLocal)
                    val next = settings.videoKeyframes
                        .filterNot { it === nearest || kotlin.math.abs(it.timeMs - nearest.timeMs) <= 1L }
                        .filterNot { kotlin.math.abs(it.timeMs - newLocal) <= 35L } + moved
                    val updated = next.sortedBy { it.timeMs }
                    settings = settings.copy(videoKeyframes = updated)
                    EditorSettingsRepository.save(context, projectId, settings)
                    playheadMs = (offset + newLocal).coerceIn(0L, timelineTotalDuration(clips))
                }
            )

            Text(
                if (language == AppLanguage.ARABIC) "أدوات التحرير" else "Editing tools",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
            )
            LazyRow(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                contentPadding = PaddingValues(bottom = 5.dp)
            ) {
                val tools = listOf(
                    Triple("edit", Icons.Default.Edit, if(language==AppLanguage.ARABIC)"تحرير" else "Edit"),
                    Triple("text", Icons.Default.TextFields, if(language==AppLanguage.ARABIC)"النص" else "Text"),
                    Triple("audio", Icons.Default.MusicNote, if(language==AppLanguage.ARABIC)"الصوت" else "Audio"),
                    Triple("effects", Icons.Default.AutoAwesome, if(language==AppLanguage.ARABIC)"المؤثرات" else "Effects"),
                    Triple("filters", Icons.Default.FilterVintage, if(language==AppLanguage.ARABIC)"الفلاتر" else "Filters"),
                    Triple("adjust", Icons.Default.Tune, if(language==AppLanguage.ARABIC)"الضبط" else "Adjust"),
                    Triple("canvas", Icons.Default.CropFree, if(language==AppLanguage.ARABIC)"المقاس" else "Canvas"),
                    Triple("transition", Icons.Default.SwapHoriz, if(language==AppLanguage.ARABIC)"الانتقال" else "Transition"),
                    Triple("subtitles", Icons.Default.Subtitles, if(language==AppLanguage.ARABIC)"الترجمة" else "Subtitles"),
                    Triple("layers", Icons.Default.Layers, if(language==AppLanguage.ARABIC)"الطبقات" else "Layers"),
                    Triple("videoKeyframes", Icons.Default.Timeline, if(language==AppLanguage.ARABIC)"الحركة" else "Motion"),
                    Triple("more", Icons.Default.MoreHoriz, if(language==AppLanguage.ARABIC)"المزيد" else "More")
                )
                items(tools,key={it.first}){(id,icon,label)->
                    Column(
                        Modifier.width(76.dp).clip(RoundedCornerShape(13.dp))
                            .background(Color(0xFF0D1724))
                            .clickable{tool=id}
                            .padding(vertical=9.dp),
                        horizontalAlignment=Alignment.CenterHorizontally
                    ) {
                        Icon(icon,null,tint=Color(0xFFD09CFF),modifier=Modifier.size(24.dp))
                        Spacer(Modifier.height(4.dp))
                        Text(label,fontSize=9.sp,maxLines=1)
                    }
                }
            }

            EditorFeaturePanel(
                activeTool = activeEditorTool,
                settings = settings, current = current, clips = clips, language = language,
                onSettingsLiveChange = ::updateSettingsLive,
                onTrim = { if (current != null) showTrim = true },
                onSplit = {
                    val clip = current
                    if (clip != null) {
                        val offset = timelinePositionOf(clips, clip)
                        val local = (playheadMs - offset).coerceIn(1L, (clipTimelineDuration(clip)-1L).coerceAtLeast(1L))
                        val start = clip.trimStartMs
                        val end = if (clip.trimEndMs == Long.MAX_VALUE) clip.durationMs else clip.trimEndMs
                        if (end-start > 2L) {
                            val cut=(start+local).coerceIn(start+1L,end-1L)
                            val left=clip.copy(trimEndMs=cut); val right=clip.copy(trimStartMs=cut)
                            val index=clips.indexOf(clip)
                            commitClips(clips.toMutableList().also{it.removeAt(index);it.add(index,left);it.add(index+1,right)})
                            current=left
                        }
                    }
                },
                onDelete = {
                    val clip=current
                    if(clip!=null && clips.size>1){val next=clips.filterNot{it==clip};commitClips(next);current=next.firstOrNull();playheadMs=timelinePositionOf(next,current)}
                },
                onDuplicate = {
                    val clip=current
                    if(clip!=null){val index=clips.indexOf(clip);if(index>=0){val copy=clip.copy(name=clip.name.substringBeforeLast('.').ifBlank{clip.name}+" • copy");commitClips(clips.toMutableList().also{it.add(index+1,copy)});current=copy}}
                },
                onReplace = { replaceLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                onMoveLeft = { val clip=current; if(clip!=null){val i=clips.indexOf(clip);if(i>0)commitClips(clips.toMutableList().also{it.add(i-1,it.removeAt(i))})} },
                onMoveRight = { val clip=current; if(clip!=null){val i=clips.indexOf(clip);if(i in 0 until clips.lastIndex)commitClips(clips.toMutableList().also{it.add(i+1,it.removeAt(i))})} },
                onFreeze = { createFreezeFrame() },
                onExtractAudio = { extractAudioFromCurrent() },
                onAudioKeyframes = { showAudioKeyframes=true },
                onMusicKeyframes = { showMusicKeyframes=true },
                onTextDialog = { tool="text" },
                onTextAnimation = { tool="textAnimation" },
                onSubtitles = { tool="subtitles" },
                onLayersDialog = { showLayers=true },
                onVideoKeyframes = { showVideoKeyframes=true },
                onMarkers = { showMarkers=true }
            )

            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(5.dp)); Text(if (language == AppLanguage.ARABIC) "إضافة وسائط" else "Add media")
                }
                OutlinedButton(onClick = { if (current != null) showTrim = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.ContentCut, null); Spacer(Modifier.width(5.dp)); Text(if (language == AppLanguage.ARABIC) "قص" else "Trim")
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (language == AppLanguage.ARABIC) "أدوات التحرير" else "Editing tools",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = { extractAudioFromCurrent() },
                    enabled = current != null
                ) {
                    Icon(Icons.Default.AudioFile, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(if (language == AppLanguage.ARABIC) "استخراج الصوت" else "Extract audio", fontSize = 10.sp)
                }
                Spacer(Modifier.width(6.dp))
                OutlinedButton(
                    onClick = { createFreezeFrame() },
                    enabled = current != null
                ) {
                    Icon(Icons.Default.PauseCircle, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(if (language == AppLanguage.ARABIC) "إطار ثابت" else "Freeze frame", fontSize = 10.sp)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Tune, null, Modifier.size(18.dp), tint = Color(0xFFD09CFF))
                Spacer(Modifier.width(5.dp))
                Text(if (language == AppLanguage.ARABIC) "الحركة الزمنية" else "Keyframe timeline", fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text(formatTimelineTime(playheadMs), fontSize = 10.sp, color = Color.Gray)
                Spacer(Modifier.width(6.dp))
                OutlinedButton(onClick = { showMarkers = true }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Icon(Icons.Default.Bookmark, null, Modifier.size(16.dp)); Spacer(Modifier.width(3.dp)); Text(if (language == AppLanguage.ARABIC) "علامة ${settings.markers.size}" else "Markers ${settings.markers.size}", fontSize = 9.sp)
                }
                Spacer(Modifier.width(4.dp))
                OutlinedButton(onClick = { updateSettings(settings.copy(markers = (settings.markers + TimelineMarker(timeMs = playheadMs, label = "M${settings.markers.size + 1}" )).sortedBy { it.timeMs })) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Icon(Icons.Default.AddLocationAlt, null, Modifier.size(16.dp)); Spacer(Modifier.width(3.dp)); Text(if (language == AppLanguage.ARABIC) "إضافة علامة" else "Add marker", fontSize = 9.sp)
                }
                Spacer(Modifier.width(4.dp))
                OutlinedButton(onClick = { showLayers = true }, enabled = settings.textLayers.isNotEmpty(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Icon(Icons.Default.Layers, null, Modifier.size(16.dp)); Spacer(Modifier.width(3.dp)); Text(if (language == AppLanguage.ARABIC) "الطبقات" else "Layers", fontSize = 9.sp)
                }
                Spacer(Modifier.width(4.dp))
                OutlinedButton(onClick = { showKeyframes = true }, enabled = settings.textLayers.isNotEmpty(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Icon(Icons.Default.AddCircleOutline, null, Modifier.size(16.dp)); Spacer(Modifier.width(3.dp)); Text(if (language == AppLanguage.ARABIC) "نقطة حركة" else "Keyframe", fontSize = 9.sp)
                }
                Spacer(Modifier.width(4.dp))
                OutlinedButton(onClick = { showVideoKeyframes = true }, enabled = clips.isNotEmpty(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Icon(Icons.Default.MovieFilter, null, Modifier.size(16.dp)); Spacer(Modifier.width(3.dp)); Text(if (language == AppLanguage.ARABIC) "حركة الفيديو" else "Video Motion", fontSize = 9.sp)
                }
                Spacer(Modifier.width(4.dp))
                OutlinedButton(onClick = { showAudioKeyframes = true }, enabled = clips.isNotEmpty(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Icon(Icons.Default.GraphicEq, null, Modifier.size(16.dp)); Spacer(Modifier.width(3.dp)); Text(if (language == AppLanguage.ARABIC) "حركة الصوت" else "Audio Motion", fontSize = 9.sp)
                }
            }
            if (status.isNotBlank()) Text(status, Modifier.padding(horizontal = 14.dp, vertical = 2.dp), color = Color.Gray, fontSize = 10.sp)
            lastExportUri?.let { exportedUri ->
                OutlinedButton(
                    onClick = {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "video/mp4"
                            putExtra(Intent.EXTRA_STREAM, exportedUri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(share, if (language == AppLanguage.ARABIC) "مشاركة الفيديو" else "Share video"))
                    },
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 3.dp)
                ) {
                    Icon(Icons.Default.Share, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(if (language == AppLanguage.ARABIC) "مشاركة آخر فيديو" else "Share last export", fontSize = 10.sp)
                }
            }
        }
    }

    if (showKeyframes) {
        KeyframeDialog(settings, clips, current, playheadMs, language, { updateSettings(it) }, { showKeyframes = false })
    }
    if (showVideoKeyframes) {
        VideoKeyframeDialog(settings, clips, current, playheadMs, language, { updateSettings(it) }, { showVideoKeyframes = false })
    }
    if (showMusicKeyframes) {
        MusicKeyframeDialog(settings, playheadMs, language, { updateSettings(it) }, { showMusicKeyframes = false })
    }
    if (showAudioKeyframes) {
        AudioKeyframeDialog(settings, clips, current, playheadMs, language, { updateSettings(it) }, { updated -> commitClips(clips.map { if (it == current) updated else it }); current = updated }, { showAudioKeyframes = false })
    }
    if (showSubtitles) {
        SubtitleDialog(settings, playheadMs, language, { updateSettings(it) }, { subtitleImportLauncher.launch(arrayOf("text/plain", "application/x-subrip", "application/octet-stream")) }, { subtitleExportLauncher.launch("${editingName.ifBlank { "VideoForge" }}.srt") }, { showSubtitles = false })
    }
    if (showLayers) {
        LayerManagerDialog(settings, language, { updateSettings(it) }, { showLayers = false })
    }
    if (showMarkers) {
        MarkerDialog(settings, playheadMs, language, { updateSettings(it) }, { ms -> playheadMs = ms }, { showMarkers = false })
    }
    if (showTrim && current != null) TrimDialog(clip = current!!, onDismiss = { showTrim = false }, onApply = { start, end ->
        val old = current!!; val updated = old.copy(trimStartMs = start, trimEndMs = end)
        commitClips(clips.map { if (it == old) updated else it }); current = updated; showTrim = false
        status = if (language == AppLanguage.ARABIC) "تم تطبيق القص" else "Trim applied"
    })
    if (showExport) {
        ExportDialog(
            language = language,
            settings = exportSettings,
            onDismiss = { showExport = false },
            onSettings = { exportSettings = it },
            onExport = { selected ->
                exportSettings = selected
                showExport = false
                exportLauncher.launch("${editingName.ifBlank { "VideoForge" }}.mp4")
            }
        )
    }
    tool?.let { active ->
        when (active) {
            "speed" -> SpeedDialog(settings, current, clips, playheadMs, language, { updateSettings(it); tool = null }, { tool = null })
            "audio" -> AudioDialog(settings, current, language, { updateSettings(it) }, { updated -> commitClips(clips.map { if (it == current) updated else it }); current = updated }, { tool = null }, { showMusicKeyframes = true })
            "text" -> TextDialog(settings, language, { updateSettings(it) }, { tool = null })
            "textAnimation" -> TextAnimationDialog(settings, language, { updateSettings(it) }, { tool = null })
            "layers" -> LayerManagerDialog(settings, language, { updateSettings(it) }, { tool = null })
            "subtitles" -> SubtitleDialog(settings, playheadMs, language, { updateSettings(it) }, { subtitleImportLauncher.launch(arrayOf("text/plain", "application/x-subrip", "application/octet-stream")) }, { subtitleExportLauncher.launch("${editingName.ifBlank { "VideoForge" }}.srt") }, { tool = null })
            "effects" -> EffectsDialog(settings, language, { updateSettings(it) }, { tool = null })
            "filters" -> FilterDialog(settings, language, { updateSettings(it) }, { tool = null })
            "adjust" -> AdjustDialog(settings, language, { updateSettings(it) }, { tool = null })
            "canvas" -> CanvasDialog(settings, language, { updateSettings(it) }, { tool = null })
            "transition" -> TransitionDialog(settings, language, { updateSettings(it) }, { tool = null })
            "sticker" -> StickerDialog(settings, language, { updateSettings(it) }, { tool = null })
            "overlay" -> OverlayDialog(settings, language, { updateSettings(it) }, { overlayImageLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, { aiCutoutLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, { tool = null })
            "rotate" -> RotateDialog(settings, language, { updateSettings(it) }, { tool = null })
            "flip" -> FlipDialog(settings, language, { updateSettings(it) }, { tool = null })
            "crop" -> CropDialog(settings, language, { updateSettings(it) }, { tool = null })
            "videoPreset" -> VideoPresetDialog(settings, language, { updateSettings(it) }, { tool = null })
            "settings" -> SettingsSheet(language, onLanguageSelected, { tool = null })
            "language" -> LanguageDialog(language, onLanguageSelected, { tool = null })
            "history" -> HistoryDialog(language, undoStack.size, redoStack.size, { undo(); tool = null }, { redo(); tool = null }, { undoStack.clear(); redoStack.clear() }, { tool = null })
            "edit" -> EditToolsDialog(
                language = language,
                clips = clips,
                current = current,
                onTrim = { if (current != null) { showTrim = true; tool = null } },
                onSplit = {
                    val clip = current
                    if (clip != null) {
                        val offset = timelinePositionOf(clips, clip)
                        val local = (playheadMs - offset).coerceIn(1L, (clipTimelineDuration(clip) - 1L).coerceAtLeast(1L))
                        val start = clip.trimStartMs
                        val end = if (clip.trimEndMs == Long.MAX_VALUE) clip.durationMs else clip.trimEndMs
                        val cut = (start + local).coerceIn(start + 1L, end - 1L)
                        if (end - start > 2L) {
                            val left = clip.copy(trimEndMs = cut, name = clip.name.substringBeforeLast('.').ifBlank { clip.name } + " • 1")
                            val right = clip.copy(trimStartMs = cut, name = clip.name.substringBeforeLast('.').ifBlank { clip.name } + " • 2")
                            val index = clips.indexOf(clip)
                            commitClips(clips.toMutableList().also { it.removeAt(index); it.add(index, left); it.add(index + 1, right) })
                            current = left
                            playheadMs = offset + (cut - start)
                        }
                    }
                    tool = null
                },
                onDelete = {
                    val clip = current
                    if (clip != null && clips.size > 1) {
                        val index = clips.indexOf(clip)
                        val next = clips.toMutableList().also { it.removeAt(index) }
                        commitClips(next)
                        current = next.getOrNull(index.coerceAtMost(next.lastIndex))
                        playheadMs = timelinePositionOf(next, current)
                    }
                    tool = null
                },
                onMoveLeft = {
                    val clip = current
                    if (clip != null) {
                        val index = clips.indexOf(clip)
                        if (index > 0) {
                            val next = clips.toMutableList()
                            next[index] = next[index - 1]
                            next[index - 1] = clip
                            commitClips(next)
                        }
                    }
                    tool = null
                },
                onMoveRight = {
                    val clip = current
                    if (clip != null) {
                        val index = clips.indexOf(clip)
                        if (index >= 0 && index < clips.lastIndex) {
                            val next = clips.toMutableList()
                            next[index] = next[index + 1]
                            next[index + 1] = clip
                            commitClips(next)
                        }
                    }
                    tool = null
                },
                                onReplace = {
                    replaceLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                },
onDuplicate = {
                    val clip = current
                    if (clip != null) {
                        val index = clips.indexOf(clip)
                        if (index >= 0) {
                            val copy = clip.copy(name = clip.name.substringBeforeLast('.').ifBlank { clip.name } + " • copy")
                            commitClips(clips.toMutableList().also { it.add(index + 1, copy) })
                            current = copy
                        }
                    }
                    tool = null
                },
                onDismiss = { tool = null }
            )
        }
    }
}

@Composable
private fun EditorPreview(
    clip: Clip?,
    settings: EditorSettings,
    playheadMs: Long,
    clipOffsetMs: Long = 0L,
    onSettingsChange: (EditorSettings) -> Unit,
    onPlaybackPosition: (Long) -> Unit = {},
    onPlaybackStateChanged: (Boolean) -> Unit = {},
    onPlaybackError: (String) -> Unit = {},
    playbackToggleToken: Int = 0
) {
    val context = LocalContext.current
    val ratio = when (settings.aspect) { "9:16" -> 9f/16f; "1:1" -> 1f; "4:5" -> 4f/5f; "2:3" -> 2f/3f; "3:4" -> 3f/4f; "3:2" -> 3f/2f; "21:9" -> 21f/9f; else -> 16f/9f }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
        val previewWidth = maxWidth
        val calculatedHeight = (previewWidth.value / ratio).coerceAtMost(285f).dp
        Box(Modifier.fillMaxWidth().height(calculatedHeight).clip(RoundedCornerShape(14.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
        if (clip == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.VideoLibrary, null, Modifier.size(54.dp), tint = Color.Gray); Text("Add media", color = Color.Gray, fontSize = 11.sp) }
        } else {
            val player = remember(clip.uri, clip.isFreezeFrame, clip.freezeDurationMs) {
                ExoPlayer.Builder(context).build().apply {
                    val item = if (clip.isFreezeFrame) {
                        MediaItem.Builder().setUri(clip.uri).setImageDurationMs(clip.freezeDurationMs.coerceAtLeast(1L)).build()
                    } else {
                        MediaItem.fromUri(clip.uri)
                    }
                    setMediaItem(item)
                    // Initialize the Media3 effects pipeline before prepare so the preview
                    // decoder can render reliably even when effects are changed later.
                    setVideoEffects(emptyList())
                    prepare()
                    playWhenReady = false
                }
            }
            val musicPlayer = remember(settings.musicUri) {
                ExoPlayer.Builder(context).build().apply {
                    if (settings.musicUri.isNotBlank()) {
                        setMediaItem(MediaItem.fromUri(Uri.parse(settings.musicUri)))
                        repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
                        prepare()
                    }
                    playWhenReady = false
                }
            }
            var lastPlaybackToggleToken by remember(player) { mutableIntStateOf(playbackToggleToken) }

            LaunchedEffect(player, playbackToggleToken) {
                if (playbackToggleToken != lastPlaybackToggleToken) {
                    if (player.isPlaying) player.pause() else player.play()
                    lastPlaybackToggleToken = playbackToggleToken
                }
            }

            DisposableEffect(player) {
                val listener = object : androidx.media3.common.Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        onPlaybackStateChanged(isPlaying)
                    }
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        onPlaybackError(error.message ?: "Playback error")
                        onPlaybackStateChanged(false)
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == androidx.media3.common.Player.STATE_ENDED) onPlaybackStateChanged(false)
                    }
                }
                player.addListener(listener)
                onDispose {
                    player.removeListener(listener)
                    onPlaybackStateChanged(false)
                }
            }

            fun interpolateVolume(keys: List<ClipAudioKeyframe>, timeMs: Long, fallback: Float): Float {
                if (keys.isEmpty()) return fallback
                val sorted = keys.sortedBy { it.timeMs }
                val a = sorted.lastOrNull { it.timeMs <= timeMs } ?: sorted.first()
                val b = sorted.firstOrNull { it.timeMs >= timeMs } ?: sorted.last()
                val span = (b.timeMs - a.timeMs).coerceAtLeast(1L)
                val f = ((timeMs - a.timeMs).toFloat() / span).coerceIn(0f, 1f)
                return (a.volume + (b.volume - a.volume) * f).coerceIn(0f, 2f)
            }
            fun clipPreviewVolume(localMs: Long): Float {
                if (settings.muted || clip.audioMuted) return 0f
                val clipLength = clipTimelineDuration(clip)
                var v = interpolateVolume(clip.audioKeyframes, localMs, clip.audioVolume * settings.volume)
                val fadeIn = maxOf(settings.fadeIn, clip.audioFadeIn).coerceIn(0f, 30f) * 1000f
                val fadeOut = maxOf(settings.fadeOut, clip.audioFadeOut).coerceIn(0f, 30f) * 1000f
                if (fadeIn > 0f) v *= (localMs / fadeIn).coerceIn(0f, 1f)
                if (fadeOut > 0f) v *= ((clipLength - localMs) / fadeOut).coerceIn(0f, 1f)
                return v.coerceIn(0f, 1f)
            }
            fun musicPreviewVolume(globalMs: Long): Float {
                if (settings.musicUri.isBlank()) return 0f
                val local = globalMs - settings.musicStartMs
                if (local < 0L) return 0f
                if (settings.musicDurationMs > 0L && local >= settings.musicDurationMs) return 0f
                val keys = settings.musicKeyframes.sortedBy { it.timeMs }
                var v = if (keys.isEmpty()) settings.musicVolume else {
                    val a = keys.lastOrNull { it.timeMs <= local } ?: keys.first()
                    val b = keys.firstOrNull { it.timeMs >= local } ?: keys.last()
                    val span = (b.timeMs - a.timeMs).coerceAtLeast(1L)
                    val f = ((local - a.timeMs).toFloat() / span).coerceIn(0f, 1f)
                    a.volume + (b.volume - a.volume) * f
                }
                val length = if (settings.musicDurationMs > 0L) settings.musicDurationMs else Long.MAX_VALUE
                if (settings.musicFadeIn > 0f) v *= (local / (settings.musicFadeIn * 1000f)).coerceIn(0f, 1f)
                if (settings.musicFadeOut > 0f && length != Long.MAX_VALUE) v *= ((length - local) / (settings.musicFadeOut * 1000f)).coerceIn(0f, 1f)
                if (settings.musicDucking) {
                    val localClip = (globalMs - clipOffsetMs).coerceAtLeast(0L)
                    val clipLength = clip?.let { clipTimelineDuration(it) } ?: 0L
                    val attackMs = (settings.musicDuckAttack * 1000f).toLong().coerceAtLeast(0L)
                    val releaseMs = (settings.musicDuckRelease * 1000f).toLong().coerceAtLeast(0L)
                    if (clip != null && !settings.muted && !clip.audioMuted && localClip <= clipLength) {
                        val audible = clipPreviewVolume(localClip) > 0.001f
                        if (audible) {
                            val inFactor = if (attackMs > 0L) (localClip.toFloat() / attackMs).coerceIn(0f, 1f) else 1f
                            val outFactor = if (releaseMs > 0L) ((clipLength - localClip).toFloat() / releaseMs).coerceIn(0f, 1f) else 1f
                            val duckProgress = minOf(inFactor, outFactor)
                            val duckGain = 1f + (settings.musicDuckVolume.coerceIn(0f, 1f) - 1f) * duckProgress
                            v *= duckGain
                        }
                    }
                }
                return v.coerceIn(0f, 1f)
            }
            LaunchedEffect(settings.speed, settings.speedKeyframes, settings.volume, settings.muted, clip.audioVolume, clip.audioMuted, clip.audioFadeIn, clip.audioFadeOut, clip.audioKeyframes, settings.fadeIn, settings.fadeOut, settings.musicUri, settings.musicVolume, settings.musicStartMs, settings.musicDurationMs, settings.musicFadeIn, settings.musicFadeOut, settings.musicDucking, settings.musicDuckVolume, settings.musicDuckAttack, settings.musicDuckRelease, settings.musicKeyframes, playheadMs) {
                val local = (playheadMs - clipOffsetMs).coerceAtLeast(0L)
                val speedNow = run {
                    val ks = settings.speedKeyframes.sortedBy { it.timeMs }
                    if (ks.isEmpty()) settings.speed.coerceIn(0.25f, 4f) else {
                        val a = ks.lastOrNull { it.timeMs <= local } ?: ks.first()
                        val b = ks.firstOrNull { it.timeMs >= local } ?: ks.last()
                        val span = (b.timeMs - a.timeMs).coerceAtLeast(1L)
                        val f0 = ((local - a.timeMs).toFloat() / span).coerceIn(0f, 1f)
                        val f = easedProgress(f0, b.easing)
                        (a.speed + (b.speed - a.speed) * f).coerceIn(0.25f, 4f)
                    }
                }
                player.setPlaybackSpeed(speedNow)
                player.volume = clipPreviewVolume(local)
                musicPlayer.volume = musicPreviewVolume(playheadMs)
                if (settings.musicUri.isBlank()) musicPlayer.pause()
                val effects = mutableListOf<Effect>()
                if (settings.brightness != 0f) effects += Brightness(settings.brightness.coerceIn(-1f, 1f))
                if (settings.blurRadius > 0.01f) effects += GaussianBlur(settings.blurRadius.coerceIn(0.1f, 20f))
                if (settings.mosaicEnabled) effects += MosaicEffect(
                    settings.mosaicX.coerceIn(0f, 1f),
                    settings.mosaicY.coerceIn(0f, 1f),
                    settings.mosaicWidth.coerceIn(0.01f, 1f),
                    settings.mosaicHeight.coerceIn(0.01f, 1f),
                    settings.mosaicBlockSize.coerceIn(0.005f, 0.25f)
                )
                if (settings.contrast != 1f) effects += Contrast(((settings.contrast - 1f) * 0.5f).coerceIn(-1f, 1f))
                if (settings.saturation != 1f || settings.hue != 0f || settings.temperature != 0f || settings.tint != 0f) effects += HslAdjustment.Builder().adjustSaturation(((settings.saturation - 1f) * 100f + settings.temperature * 0.10f).coerceIn(-100f, 100f)).adjustHue((settings.hue + settings.temperature * 0.12f + settings.tint * 0.08f).coerceIn(-180f, 180f)).build()
                when (settings.filter) {
                    "mono" -> effects += RgbFilter.createGrayscaleFilter()
                    "invert" -> effects += RgbFilter.createInvertedFilter()
                    "sepia" -> effects += HslAdjustment.Builder().adjustHue(28f).adjustSaturation(-18f).build()
                    "warm" -> effects += HslAdjustment.Builder().adjustHue(18f).adjustSaturation(10f).build()
                    "cool" -> effects += HslAdjustment.Builder().adjustHue(-18f).adjustSaturation(6f).build()
                    "vivid" -> effects += HslAdjustment.Builder().adjustSaturation(28f).build()
                }
                if (settings.rotation % 360 != 0 || kotlin.math.abs(settings.cropZoom - 1f) > 0.001f) effects += ScaleAndRotateTransformation.Builder().setScale(settings.cropZoom.coerceIn(1f, 6f), settings.cropZoom.coerceIn(1f, 6f)).setRotationDegrees(((settings.rotation % 360) + 360) % 360f).build()
                if (settings.flipHorizontal || settings.flipVertical) effects += MatrixTransformation { android.graphics.Matrix().apply { postScale(if (settings.flipHorizontal) -1f else 1f, if (settings.flipVertical) -1f else 1f) } }
                if (kotlin.math.abs(settings.cropX) > 0.001f || kotlin.math.abs(settings.cropY) > 0.001f) effects += MatrixTransformation { android.graphics.Matrix().apply { postTranslate(settings.cropX.coerceIn(-1f, 1f) * 500f, settings.cropY.coerceIn(-1f, 1f) * 500f) } }
                if (settings.videoKeyframes.isNotEmpty()) {
                    val ks = settings.videoKeyframes.sortedBy { it.timeMs }
                    effects += MatrixTransformation { timeUs ->
                        val t = timeUs / 1000L
                        val a = ks.lastOrNull { it.timeMs <= t } ?: ks.first()
                        val b = ks.firstOrNull { it.timeMs >= t } ?: ks.last()
                        val span = (b.timeMs - a.timeMs).coerceAtLeast(1L)
                        val f0 = ((t - a.timeMs).toFloat() / span).coerceIn(0f, 1f)
                        val f = easedProgress(f0, b.easing)
                        val scale = (a.scale + (b.scale - a.scale) * f).coerceIn(0.1f, 6f)
                        val rotation = interpolateAngleDegrees(a.rotation, b.rotation, f)
                        val x = a.x + (b.x - a.x) * f
                        val y = a.y + (b.y - a.y) * f
                        android.graphics.Matrix().apply { postScale(scale, scale); postRotate(rotation); postTranslate(x * 500f, y * 500f) }
                    }
                }
                player.setVideoEffects(effects)
            }
            LaunchedEffect(playheadMs, clip.trimStartMs, clip.trimEndMs, settings.musicUri, settings.musicStartMs, settings.musicDurationMs) {
                val local = (playheadMs - clipOffsetMs).coerceAtLeast(0L)
                val sourcePosition = (clip.trimStartMs + local).coerceIn(clip.trimStartMs, (if (clip.trimEndMs == Long.MAX_VALUE) clip.durationMs else clip.trimEndMs).coerceAtLeast(clip.trimStartMs))
                if (kotlin.math.abs(player.currentPosition - sourcePosition) > 250L) player.seekTo(sourcePosition)
                if (settings.musicUri.isNotBlank()) {
                    val musicLocal = (playheadMs - settings.musicStartMs).coerceAtLeast(0L)
                    val target = if (settings.musicDurationMs > 0L) musicLocal % settings.musicDurationMs else musicLocal
                    if (musicLocal >= 0L && kotlin.math.abs(musicPlayer.currentPosition - target) > 350L) musicPlayer.seekTo(target)
                    musicPlayer.volume = musicPreviewVolume(playheadMs)
                }
            }
            LaunchedEffect(player, musicPlayer, settings.speed, settings.speedKeyframes, settings.musicUri, settings.musicStartMs, settings.musicDurationMs, settings.musicVolume, settings.musicFadeIn, settings.musicFadeOut, settings.musicKeyframes, clip.audioVolume, clip.audioMuted, clip.audioFadeIn, clip.audioFadeOut, clip.audioKeyframes, settings.volume, settings.muted, settings.fadeIn, settings.fadeOut) {
                while (isActive) {
                    val localNow = (player.currentPosition - clip.trimStartMs).coerceAtLeast(0L)
                    val globalNow = (clipOffsetMs + localNow).coerceAtLeast(0L)
                    val ks = settings.speedKeyframes.sortedBy { it.timeMs }
                    val speedNow = if (ks.isEmpty()) settings.speed.coerceIn(0.25f, 4f) else {
                        val a = ks.lastOrNull { it.timeMs <= localNow } ?: ks.first()
                        val b = ks.firstOrNull { it.timeMs >= localNow } ?: ks.last()
                        val span = (b.timeMs - a.timeMs).coerceAtLeast(1L)
                        val f0 = ((localNow - a.timeMs).toFloat() / span).coerceIn(0f, 1f)
                        val f = easedProgress(f0, b.easing)
                        (a.speed + (b.speed - a.speed) * f).coerceIn(0.25f, 4f)
                    }
                    player.setPlaybackSpeed(speedNow)
                    player.volume = clipPreviewVolume(localNow)
                    if (settings.musicUri.isBlank()) {
                        musicPlayer.pause()
                    } else {
                        val musicLocal = globalNow - settings.musicStartMs
                        val active = player.isPlaying && musicLocal >= 0L && (settings.musicDurationMs <= 0L || musicLocal < settings.musicDurationMs)
                        musicPlayer.playWhenReady = active
                        musicPlayer.volume = musicPreviewVolume(globalNow)
                        if (active) {
                            val target = if (settings.musicDurationMs > 0L) musicLocal % settings.musicDurationMs else musicLocal
                            if (kotlin.math.abs(musicPlayer.currentPosition - target) > 500L) musicPlayer.seekTo(target)
                        }
                    }
                    onPlaybackPosition(
                        (clipOffsetMs + localNow).coerceIn(
                            clipOffsetMs,
                            clipOffsetMs + clipTimelineDuration(clip)
                        )
                    )
                    delay(80L)
                }
            }
            DisposableEffect(player, musicPlayer) {
                onDispose {
                    player.pause()
                    musicPlayer.pause()
                    player.release()
                    musicPlayer.release()
                }
            }
            AndroidView(
                factory = { ctx ->
                    val view = android.view.LayoutInflater.from(ctx)
                        .inflate(R.layout.view_editor_player, null, false) as PlayerView
                    view.setEnableComposeSurfaceSyncWorkaround(true)
                    view.player = player
                    view.keepScreenOn = true
                    view
                },
                update = { view -> view.player = player },
                modifier = Modifier.fillMaxSize()
            )
            Box(
                Modifier.fillMaxSize().pointerInput(clip.uri, playheadMs, settings.videoKeyframes, settings.cropZoom, settings.cropX, settings.cropY, settings.rotation) {
                    detectTransformGestures { _, pan, zoom, rotation ->
                        val local = (playheadMs - clipOffsetMs).coerceIn(0L, clipTimelineDuration(clip))
                        if (settings.videoKeyframes.isEmpty()) {
                            // Direct manipulation edits the static reframe until the user creates
                            // motion keyframes. This keeps pinch/drag intuitive for normal crops.
                            val nextZoom = (settings.cropZoom * zoom).coerceIn(1f, 6f)
                            val maxPan = ((nextZoom - 1f) / nextZoom).coerceIn(0f, 1f)
                            onSettingsChange(settings.copy(
                                cropZoom = nextZoom,
                                cropX = (settings.cropX + pan.x / 500f).coerceIn(-maxPan, maxPan),
                                cropY = (settings.cropY + pan.y / 500f).coerceIn(-maxPan, maxPan),
                                rotation = (settings.rotation + rotation).coerceIn(-180f, 180f).toInt()
                            ))
                        } else {
                            val base = settings.videoKeyframes.lastOrNull { it.timeMs <= local }
                            val nextScale = ((base?.scale ?: settings.cropZoom) * zoom).coerceIn(0.5f, 6f)
                            val maxPan = ((nextScale - 1f) / nextScale).coerceIn(0f, 1f)
                            val next = VideoKeyframe(
                                timeMs = local,
                                x = ((base?.x ?: settings.cropX) + pan.x / 500f).coerceIn(-maxPan, maxPan),
                                y = ((base?.y ?: settings.cropY) + pan.y / 500f).coerceIn(-maxPan, maxPan),
                                scale = nextScale,
                                rotation = (base?.rotation ?: settings.rotation.toFloat()) + rotation,
                                easing = base?.easing ?: "easeInOut"
                            )
                            onSettingsChange(settings.copy(videoKeyframes = (settings.videoKeyframes.filterNot { it.timeMs == local } + next).sortedBy { it.timeMs }))
                        }
                    }
                }
            )
            val tint = when (settings.filter) { "warm" -> Color(0x44FF9E5E); "cool" -> Color(0x443A8DFF); "mono" -> Color(0x66333333); "vivid" -> Color(0x2200FFAA); else -> Color.Transparent }
            if (tint.alpha > 0f) Box(Modifier.fillMaxSize().background(tint))
            if (settings.overlayOpacity > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = settings.overlayOpacity)))
            val pipPreviewLayers = settings.pipLayers.ifEmpty {
                if (settings.overlayImageUri.isNotBlank()) listOf(PipLayer(uri=settings.overlayImageUri, x=settings.overlayImageX, y=settings.overlayImageY, scale=settings.overlayImageScale, rotation=settings.overlayImageRotation, alpha=settings.overlayImageAlpha)) else emptyList()
            }
            pipPreviewLayers.filter { it.visible && it.uri.isNotBlank() }.forEach { pip ->
                var bitmap by remember(pip.uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
                LaunchedEffect(pip.uri) {
                    bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { context.contentResolver.openInputStream(Uri.parse(pip.uri))?.use { android.graphics.BitmapFactory.decodeStream(it) } }.getOrNull()
                    }
                }
                if (bitmap != null) Box(
                    Modifier
                        .align(Alignment.Center)
                        .offset(x=(pip.x*120).dp, y=(pip.y*90).dp)
                        .rotate(pip.rotation)
                        .scale(pip.scale)
                        .graphicsLayer { alpha = pip.alpha.coerceIn(0f,1f) }
                        .border(
                            width = if (pip.locked) 1.dp else 0.dp,
                            color = if (pip.locked) Color(0xFF7C4DFF) else Color.Transparent,
                            shape = RoundedCornerShape(6.dp)
                        )
                        .pointerInput(pip.id, pip.locked) {
                            detectTransformGestures { _, pan, zoom, rotation ->
                                if (!pip.locked && settings.pipLayers.isNotEmpty()) {
                                    val next = settings.pipLayers.map { item ->
                                        if (item.id != pip.id) item else item.copy(
                                            x = (item.x + pan.x / 120f).coerceIn(-1.2f, 1.2f),
                                            y = (item.y + pan.y / 90f).coerceIn(-1.2f, 1.2f),
                                            scale = (item.scale * zoom).coerceIn(0.08f, 2.5f),
                                            rotation = item.rotation + rotation
                                        )
                                    }
                                    onSettingsChange(settings.copy(pipLayers = next))
                                }
                            }
                        }
                ) {
                    androidx.compose.foundation.Image(
                        bitmap=bitmap!!.asImageBitmap(),
                        contentDescription=null,
                        contentScale=androidx.compose.ui.layout.ContentScale.Fit,
                        modifier=Modifier.size(150.dp)
                    )
                }
            }
            val previewLayers = settings.textLayers.ifEmpty { if (settings.textVisible && settings.text.isNotBlank()) listOf(TextLayer(text=settings.text, size=settings.textSize, color=settings.textColor, font=settings.textFont)) else emptyList() }
            var selectedLayerId by remember { mutableStateOf(previewLayers.firstOrNull()?.id) }
            LaunchedEffect(previewLayers) {
                if (selectedLayerId == null || previewLayers.none { it.id == selectedLayerId }) {
                    selectedLayerId = previewLayers.firstOrNull()?.id
                }
            }
            previewLayers.filter { it.visible }.forEach { layer ->
                val selected = selectedLayerId == layer.id
                val localTextTime = (playheadMs - clipOffsetMs).coerceAtLeast(0L)
                val textAnimProgress = (localTextTime / 650f).coerceIn(0f,1f)
                val textEase = 1f - (1f-textAnimProgress)*(1f-textAnimProgress)
                val animAlpha = when(layer.animation){"fade"->textEase;"typewriter"->textEase;else->1f}
                val animScale = when(layer.animation){"pop"->0.55f+0.45f*textEase;"zoom"->0.25f+0.75f*textEase;else->1f}
                val animX = if(layer.animation=="slide") -0.35f*(1f-textEase) else 0f
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .offset(x=((layer.x+animX)*120).dp, y=(layer.y*90).dp)
                        .rotate(layer.rotation)
                        .scale(layer.scale*animScale)
                        .pointerInput(layer.id, selected) {
                            detectTransformGestures { _, pan, zoom, rotation ->
                                selectedLayerId = layer.id
                                val next = settings.textLayers.map { item ->
                                    if (item.id != layer.id) item else item.copy(
                                        x = (item.x + pan.x / 120f).coerceIn(-1.2f, 1.2f),
                                        y = (item.y + pan.y / 90f).coerceIn(-1.2f, 1.2f),
                                        scale = (item.scale * zoom).coerceIn(0.15f, 6f),
                                        rotation = item.rotation + rotation
                                    )
                                }
                                if (settings.textLayers.isNotEmpty()) onSettingsChange(settings.copy(textLayers = next))
                            }
                        }
                        .clip(RoundedCornerShape(6.dp))
                        .then(if (layer.backgroundAlpha > 0f) Modifier.background(Color(layer.backgroundColor).copy(alpha=layer.backgroundAlpha.coerceIn(0f,1f)), RoundedCornerShape(6.dp)) else Modifier)
                        .then(if (selected) Modifier.border(1.dp, Color(0xFFB88CFF), RoundedCornerShape(6.dp)) else Modifier)
                        .padding(horizontal=layer.backgroundPadding.dp, vertical=(layer.backgroundPadding * 0.55f).dp),
                    contentAlignment = Alignment.Center
                ) {
                    val displayText = if(layer.animation=="typewriter") layer.text.take((layer.text.length*textAnimProgress).toInt().coerceIn(0,layer.text.length)) else layer.text
                    val commonSize = layer.size.sp
                    val commonWeight = if(layer.bold) FontWeight.Bold else FontWeight.Normal
                    val commonFont = fontFamilyFor(layer.font, layer.bold)
                    if (layer.strokeEnabled && layer.strokeWidth > 0f) {
                        Text(displayText, color=Color(layer.strokeColor).copy(alpha=layer.alpha*animAlpha), fontSize=commonSize, fontWeight=commonWeight, fontFamily=commonFont, textAlign=when(layer.textAlign){"start"->TextAlign.Start;"end"->TextAlign.End;else->TextAlign.Center}, lineHeight=(layer.size*layer.lineHeightMultiplier).sp, letterSpacing=layer.letterSpacing.sp, style=androidx.compose.ui.text.TextStyle(drawStyle=androidx.compose.ui.graphics.drawscope.Stroke(width=layer.strokeWidth)))
                    }
                    Text(displayText, color=Color(layer.color).copy(alpha=layer.alpha*animAlpha), fontSize=commonSize, fontWeight=commonWeight, fontFamily=commonFont, textAlign=TextAlign.Center, style=androidx.compose.ui.text.TextStyle(lineHeight=(layer.size*layer.lineHeightMultiplier).sp, letterSpacing=layer.letterSpacing.sp, textAlign=when(layer.textAlign){"start"->TextAlign.Start;"end"->TextAlign.End;else->TextAlign.Center}, shadow=if(layer.glowEnabled) androidx.compose.ui.graphics.Shadow(Color(layer.glowColor), Offset.Zero, layer.glowRadius) else if(layer.shadowEnabled) androidx.compose.ui.graphics.Shadow(Color(layer.shadowColor), Offset(layer.shadowDx, layer.shadowDy), layer.shadowRadius) else null))
                }
            }
            if (previewLayers.isNotEmpty()) {
                Text(
                    if (LocalLayoutDirection.current == LayoutDirection.Rtl) "اسحب النص • قرص للتكبير/الدوران" else "Drag text • pinch to scale/rotate",
                    color = Color.White.copy(alpha=.65f), fontSize = 8.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom=7.dp)
                )
            }
            if (settings.sticker.isNotBlank()) {
                Text(
                    settings.sticker,
                    fontSize = 38.sp,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(x = (settings.stickerX * 150f).dp, y = (settings.stickerY * 110f).dp)
                        .scale(settings.stickerScale)
                        .rotate(settings.stickerRotation)
                        .pointerInput(settings.sticker, settings.stickerX, settings.stickerY, settings.stickerScale, settings.stickerRotation) {
                            detectTransformGestures { _, pan, zoom, rotation ->
                                onSettingsChange(settings.copy(
                                    stickerX = (settings.stickerX + pan.x / 150f).coerceIn(-1.2f, 1.2f),
                                    stickerY = (settings.stickerY + pan.y / 110f).coerceIn(-1.2f, 1.2f),
                                    stickerScale = (settings.stickerScale * zoom).coerceIn(0.12f, 3f),
                                    stickerRotation = settings.stickerRotation + rotation
                                ))
                            }
                        },
                    color = Color.White.copy(alpha = settings.stickerAlpha.coerceIn(0f, 1f))
                )
            }
        }
    }
    }
}


@Composable private fun LanguageDialog(language: AppLanguage, onSelect: (AppLanguage) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (language == AppLanguage.ARABIC) "لغة التطبيق" else "App language") }, text = { Column { TextButton(onClick = { onSelect(AppLanguage.ARABIC); onDismiss() }) { Text("العربية") }; TextButton(onClick = { onSelect(AppLanguage.ENGLISH); onDismiss() }) { Text("English") } } }, confirmButton = { TextButton(onClick = onDismiss) { Text(if (language == AppLanguage.ARABIC) "إغلاق" else "Close") } })
}
@Composable private fun SpeedDialog(
    s: EditorSettings, clip: Clip?, clips: List<Clip>, playheadMs: Long, language: AppLanguage,
    onChange: (EditorSettings) -> Unit, onDismiss: () -> Unit
) {
    var speed by remember(s.speed) { mutableFloatStateOf(s.speed) }
    var easing by remember { mutableStateOf("easeInOut") }
    val offset = clip?.let { timelinePositionOf(clips, it) } ?: 0L
    val local = (playheadMs - offset).coerceAtLeast(0L)
    val duration = clip?.let { clipTimelineDuration(it) } ?: 1L
    val keys = s.speedKeyframes.sortedBy { it.timeMs }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (language == AppLanguage.ARABIC) "السرعة و Speed Ramping" else "Speed & Speed Ramping") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(if (language == AppLanguage.ARABIC) "السرعة الأساسية: %.2fx".format(speed) else "Base speed: %.2fx".format(speed))
            Slider(speed, { speed = it }, valueRange = 0.25f..4f)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
                listOf(.5f, 1f, 1.5f, 2f, 3f).forEach { v -> AssistChip(onClick = { speed = v }, label = { Text("${v}x", fontSize = 10.sp) }) }
            }
            HorizontalDivider()
            Text(if (language == AppLanguage.ARABIC) "منحنى السرعة" else "Speed ramp", fontWeight = FontWeight.Bold)
            Text(if (language == AppLanguage.ARABIC) "تنعيم الانتقال بين النقاط" else "Easing between speed points", color = Color.Gray, fontSize = 11.sp)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf("linear" to if (language == AppLanguage.ARABIC) "خطي" else "Linear", "easeIn" to if (language == AppLanguage.ARABIC) "تسارع" else "Ease In", "easeOut" to if (language == AppLanguage.ARABIC) "تباطؤ" else "Ease Out", "easeInOut" to if (language == AppLanguage.ARABIC) "سينمائي" else "Cinematic", "hold" to if (language == AppLanguage.ARABIC) "ثابت" else "Hold").forEach { (value, label) ->
                    FilterChip(selected = easing == value, onClick = { easing = value }, label = { Text(label, fontSize = 10.sp) })
                }
            }
            Text(if (language == AppLanguage.ARABIC) "إضافة نقطة عند المؤشر الحالي: ${formatDuration(local)}" else "Add a point at the current playhead: ${formatDuration(local)}", color = Color.Gray, fontSize = 11.sp)
            Button(onClick = {
                val next = (s.speedKeyframes.filterNot { it.timeMs == local } + SpeedKeyframe(local, speed, easing)).sortedBy { it.timeMs }
                onChange(s.copy(speed = speed, speedKeyframes = next))
            }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(5.dp)); Text(if (language == AppLanguage.ARABIC) "إضافة نقطة سرعة" else "Add speed point") }
            keys.forEach { k ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(formatDuration(k.timeMs), Modifier.weight(1f), fontSize = 11.sp)
                    Text("%.2fx".format(k.speed), color = Color(0xFFFFB74D), fontWeight = FontWeight.Bold)
                    IconButton(onClick = { onChange(s.copy(speedKeyframes = keys.filterNot { it.timeMs == k.timeMs })) }) { Icon(Icons.Default.DeleteOutline, null) }
                }
            }
            if (keys.isNotEmpty()) TextButton(onClick = { onChange(s.copy(speedKeyframes = emptyList())) }, modifier = Modifier.fillMaxWidth()) { Text(if (language == AppLanguage.ARABIC) "مسح نقاط السرعة" else "Clear speed points") }
            Text(if (language == AppLanguage.ARABIC) "مدة المقطع ${formatDuration(duration)} — الانتقال بين النقاط تدريجي." else "Clip duration ${formatDuration(duration)} — changes interpolate smoothly.", color = Color.Gray, fontSize = 10.sp)
        }
    }, confirmButton = { TextButton(onClick = { onChange(s.copy(speed = speed)); onDismiss() }) { Text(if (language == AppLanguage.ARABIC) "تطبيق" else "Apply") } }, dismissButton = { TextButton(onClick = onDismiss) { Text(if (language == AppLanguage.ARABIC) "إلغاء" else "Cancel") } })
}

@Composable private fun AudioDialog(
    s: EditorSettings,
    clip: Clip?,
    language: AppLanguage,
    onChange: (EditorSettings) -> Unit,
    onClipChange: (Clip) -> Unit,
    onDismiss: () -> Unit,
    onMusicKeyframes: () -> Unit = {}
) {
    val clipVolume = clip?.audioVolume ?: s.volume
    var volume by remember(s, clip) { mutableFloatStateOf(clipVolume) }
    var muted by remember(s, clip) { mutableStateOf(clip?.audioMuted ?: s.muted) }
    var fadeIn by remember(s, clip) { mutableFloatStateOf(clip?.audioFadeIn ?: s.fadeIn) }
    var fadeOut by remember(s, clip) { mutableFloatStateOf(clip?.audioFadeOut ?: s.fadeOut) }
    var musicVolume by remember(s) { mutableFloatStateOf(s.musicVolume) }
    var musicStart by remember(s) { mutableFloatStateOf(s.musicStartMs / 1000f) }
    var musicDuration by remember(s) { mutableFloatStateOf(s.musicDurationMs / 1000f) }
    var musicFadeIn by remember(s) { mutableFloatStateOf(s.musicFadeIn) }
    var musicFadeOut by remember(s) { mutableFloatStateOf(s.musicFadeOut) }
    var musicDucking by remember(s) { mutableStateOf(s.musicDucking) }
    var musicDuckVolume by remember(s) { mutableFloatStateOf(s.musicDuckVolume) }
    var musicDuckAttack by remember(s) { mutableFloatStateOf(s.musicDuckAttack) }
    var musicDuckRelease by remember(s) { mutableFloatStateOf(s.musicDuckRelease) }
    val context = LocalContext.current
    val musicPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            persistUriAccess(context, uri)
            onChange(s.copy(musicUri = uri.toString()))
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (language == AppLanguage.ARABIC) "الصوت والموسيقى" else "Audio & Music") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    if (clip != null) (if (language == AppLanguage.ARABIC) "صوت المقطع: ${clip.name}" else "Clip audio: ${clip.name}")
                    else (if (language == AppLanguage.ARABIC) "حدد مقطعًا للتحكم بصوته" else "Select a clip to control its audio"),
                    fontWeight = FontWeight.Bold
                )
                if (clip != null) {
                    val waveDuration = (clip.trimEndMs - clip.trimStartMs).coerceAtLeast(1L)
                    Canvas(Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF101323))) {
                        val bars = 72
                        val barWidth = size.width / bars
                        for (i in 0 until bars) {
                            val amp = (0.18 + 0.72 * kotlin.math.abs(kotlin.math.sin(i * 0.63)) * (0.55 + 0.45 * kotlin.math.abs(kotlin.math.sin(i * 0.17 + 1.2)))).toFloat().coerceIn(0.08f, 0.95f)
                            val h = size.height * amp * 0.42f
                            drawRoundRect(Color(0xFF7C5CFF), Offset(i * barWidth + barWidth * 0.2f, size.height / 2f - h), androidx.compose.ui.geometry.Size(barWidth * 0.58f, h * 2f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f))
                        }
                    }
                    Text(if (language == AppLanguage.ARABIC) "موجة صوتية • ${formatTimelineTime(waveDuration)}" else "Waveform • ${formatTimelineTime(waveDuration)}", color = Color.Gray, fontSize = 9.sp)
                }
                Spacer(Modifier.height(7.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (language == AppLanguage.ARABIC) "كتم صوت هذا المقطع" else "Mute this clip", Modifier.weight(1f))
                    Switch(checked = muted, onCheckedChange = { muted = it })
                }
                Text(if (language == AppLanguage.ARABIC) "مستوى المقطع: ${(volume * 100).toInt()}%" else "Clip volume: ${(volume * 100).toInt()}%")
                Slider(value = volume, onValueChange = { volume = it }, valueRange = 0f..2f)
                Text(if (language == AppLanguage.ARABIC) "تلاشي دخول المقطع: ${String.format("%.1f", fadeIn)}s" else "Clip fade in: ${String.format("%.1f", fadeIn)}s", color = Color.Gray, fontSize = 11.sp)
                Slider(value = fadeIn, onValueChange = { fadeIn = it }, valueRange = 0f..10f)
                Text(if (language == AppLanguage.ARABIC) "تلاشي خروج المقطع: ${String.format("%.1f", fadeOut)}s" else "Clip fade out: ${String.format("%.1f", fadeOut)}s", color = Color.Gray, fontSize = 11.sp)
                Slider(value = fadeOut, onValueChange = { fadeOut = it }, valueRange = 0f..10f)
                if (clip != null) {
                    Text(
                        if (language == AppLanguage.ARABIC) "إعدادات سريعة لصوت المقطع" else "Quick clip-audio presets",
                        fontWeight = FontWeight.SemiBold, fontSize = 12.sp
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp), contentPadding = PaddingValues(vertical = 3.dp)) {
                        item {
                            AssistChip(onClick = { volume = 1f; muted = false; fadeIn = 0f; fadeOut = 0f }, label = { Text(if (language == AppLanguage.ARABIC) "صوت طبيعي" else "Natural", fontSize = 9.sp) })
                        }
                        item {
                            AssistChip(onClick = { volume = 0.65f; muted = false; fadeIn = 0.12f; fadeOut = 0.22f }, label = { Text(if (language == AppLanguage.ARABIC) "هادئ" else "Soft", fontSize = 9.sp) })
                        }
                        item {
                            AssistChip(onClick = { volume = 0f; muted = true }, label = { Text(if (language == AppLanguage.ARABIC) "كتم" else "Mute", fontSize = 9.sp) })
                        }
                    }
                    Text(
                        if (language == AppLanguage.ARABIC) "أتمتة مستوى الصوت: ${clip.audioKeyframes.size} نقاط"
                        else "Volume automation: ${clip.audioKeyframes.size} points",
                        color = Color.Gray, fontSize = 10.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        OutlinedButton(onClick = {
                            val d = (clip.trimEndMs - clip.trimStartMs).coerceAtLeast(0L)
                            onClipChange(clip.copy(audioKeyframes = listOf(ClipAudioKeyframe(0L, 0f), ClipAudioKeyframe(d, volume.coerceIn(0f, 2f)))))
                        }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                            Text(if (language == AppLanguage.ARABIC) "Fade دخول" else "Fade in", fontSize = 9.sp)
                        }
                        OutlinedButton(onClick = {
                            val d = (clip.trimEndMs - clip.trimStartMs).coerceAtLeast(0L)
                            onClipChange(clip.copy(audioKeyframes = listOf(ClipAudioKeyframe(0L, volume.coerceIn(0f, 2f)), ClipAudioKeyframe(d, 0f))))
                        }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                            Text(if (language == AppLanguage.ARABIC) "Fade خروج" else "Fade out", fontSize = 9.sp)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = {
                            onClipChange(clip.copy(audioVolume = 1f, audioMuted = false, audioFadeIn = 0f, audioFadeOut = 0f, audioKeyframes = emptyList()))
                        }, contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp)) {
                            Icon(Icons.Default.RestartAlt, null, Modifier.size(15.dp))
                            Spacer(Modifier.width(3.dp))
                            Text(if (language == AppLanguage.ARABIC) "إعادة ضبط صوت المقطع" else "Reset clip audio", fontSize = 9.sp)
                        }
                        OutlinedButton(onClick = {
                            onClipChange(clip.copy(audioKeyframes = emptyList()))
                        }, enabled = clip.audioKeyframes.isNotEmpty(), contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp)) {
                            Icon(Icons.Default.ClearAll, null, Modifier.size(15.dp))
                            Spacer(Modifier.width(3.dp))
                            Text(if (language == AppLanguage.ARABIC) "مسح النقاط" else "Clear points", fontSize = 9.sp)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(if (language == AppLanguage.ARABIC) "موسيقى خلفية" else "Background music", fontWeight = FontWeight.Bold)
                Text(
                    if (s.musicUri.isBlank()) (if (language == AppLanguage.ARABIC) "لم تتم إضافة موسيقى" else "No music selected")
                    else (if (language == AppLanguage.ARABIC) "تم اختيار ملف موسيقى" else "Music selected"),
                    color = Color.Gray, fontSize = 11.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Button(onClick = { musicPicker.launch("audio/*") }) {
                        Icon(Icons.Default.LibraryMusic, null); Spacer(Modifier.width(4.dp)); Text(if (language == AppLanguage.ARABIC) "اختيار" else "Choose")
                    }
                    if (s.musicUri.isNotBlank()) OutlinedButton(onClick = { onChange(s.copy(musicUri = "")) }) { Text(if (language == AppLanguage.ARABIC) "إزالة" else "Remove") }
                }
                Text(if (language == AppLanguage.ARABIC) "إعدادات سريعة للموسيقى" else "Quick music presets", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp), contentPadding = PaddingValues(vertical = 3.dp)) {
                    item { AssistChip(onClick = { musicVolume = 0.35f; musicDucking = true; musicDuckVolume = 0.22f }, label = { Text(if (language == AppLanguage.ARABIC) "خلفية" else "Background", fontSize = 9.sp) }) }
                    item { AssistChip(onClick = { musicVolume = 0.7f; musicDucking = false }, label = { Text(if (language == AppLanguage.ARABIC) "موسيقى واضحة" else "Music focus", fontSize = 9.sp) }) }
                    item { AssistChip(onClick = { musicVolume = 0f }, label = { Text(if (language == AppLanguage.ARABIC) "كتم" else "Mute", fontSize = 9.sp) }) }
                }
                Text(if (language == AppLanguage.ARABIC) "مستوى الموسيقى: ${(musicVolume * 100).toInt()}%" else "Music volume: ${(musicVolume * 100).toInt()}%")
                Slider(value = musicVolume, onValueChange = { musicVolume = it }, valueRange = 0f..1.5f)
                Text(if (language == AppLanguage.ARABIC) "قص مصدر الموسيقى من: ${String.format("%.1f", musicStart)}s" else "Music source start: ${String.format("%.1f", musicStart)}s", color = Color.Gray, fontSize = 11.sp)
                Slider(value = musicStart, onValueChange = { musicStart = it }, valueRange = 0f..300f)
                Text(if (language == AppLanguage.ARABIC) "مدة الموسيقى: ${if (musicDuration <= 0f) "تلقائي" else String.format("%.1f", musicDuration) + "s"}" else "Music duration: ${if (musicDuration <= 0f) "Auto" else String.format("%.1f", musicDuration) + "s"}", color = Color.Gray, fontSize = 11.sp)
                Slider(value = musicDuration, onValueChange = { musicDuration = it }, valueRange = 0f..600f)
                Text(if (language == AppLanguage.ARABIC) "تلاشي دخول الموسيقى: ${String.format("%.1f", musicFadeIn)}s" else "Music fade in: ${String.format("%.1f", musicFadeIn)}s", color = Color.Gray, fontSize = 11.sp)
                Slider(value = musicFadeIn, onValueChange = { musicFadeIn = it }, valueRange = 0f..10f)
                Text(if (language == AppLanguage.ARABIC) "تلاشي خروج الموسيقى: ${String.format("%.1f", musicFadeOut)}s" else "Music fade out: ${String.format("%.1f", musicFadeOut)}s", color = Color.Gray, fontSize = 11.sp)
                Slider(value = musicFadeOut, onValueChange = { musicFadeOut = it }, valueRange = 0f..10f)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (language == AppLanguage.ARABIC) "خفض الموسيقى تلقائيًا أثناء صوت الفيديو" else "Duck music during clip audio", fontWeight = FontWeight.SemiBold)
                        Text(if (language == AppLanguage.ARABIC) "يخفض الموسيقى تلقائيًا عندما يحتوي المقطع على صوت غير مكتوم" else "Automatically lowers music while an audible clip is playing", color = Color.Gray, fontSize = 10.sp)
                    }
                    Switch(checked = musicDucking, onCheckedChange = { musicDucking = it })
                }
                Text(if (language == AppLanguage.ARABIC) "مستوى الموسيقى أثناء الخفض: ${(musicDuckVolume * 100).toInt()}%" else "Music level while ducked: ${(musicDuckVolume * 100).toInt()}%", color = Color.Gray, fontSize = 11.sp)
                Slider(value = musicDuckVolume, onValueChange = { musicDuckVolume = it }, valueRange = 0f..1f, enabled = musicDucking)
                Text(if (language == AppLanguage.ARABIC) "زمن دخول الخفض: ${String.format("%.2f", musicDuckAttack)}s" else "Duck attack: ${String.format("%.2f", musicDuckAttack)}s", color = Color.Gray, fontSize = 11.sp)
                Slider(value = musicDuckAttack, onValueChange = { musicDuckAttack = it }, valueRange = 0f..2f, enabled = musicDucking)
                Text(if (language == AppLanguage.ARABIC) "زمن عودة الموسيقى: ${String.format("%.2f", musicDuckRelease)}s" else "Duck release: ${String.format("%.2f", musicDuckRelease)}s", color = Color.Gray, fontSize = 11.sp)
                Slider(value = musicDuckRelease, onValueChange = { musicDuckRelease = it }, valueRange = 0f..3f, enabled = musicDucking)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = onMusicKeyframes, enabled = s.musicUri.isNotBlank()) {
                        Icon(Icons.Default.Tune, null); Spacer(Modifier.width(4.dp));
                        Text(if (language == AppLanguage.ARABIC) "نقاط مستوى الموسيقى (${s.musicKeyframes.size})" else "Music volume keyframes (${s.musicKeyframes.size})")
                    }
                    OutlinedButton(onClick = {
                        onChange(s.copy(musicUri = "", musicVolume = 0.65f, musicStartMs = 0L, musicDurationMs = 0L, musicFadeIn = 0f, musicFadeOut = 0f, musicDucking = false, musicDuckVolume = 0.28f, musicDuckAttack = 0.12f, musicDuckRelease = 0.22f, musicKeyframes = emptyList()))
                    }, enabled = s.musicUri.isNotBlank() || s.musicKeyframes.isNotEmpty(), contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp)) {
                        Icon(Icons.Default.RestartAlt, null, Modifier.size(15.dp)); Spacer(Modifier.width(3.dp));
                        Text(if (language == AppLanguage.ARABIC) "إعادة ضبط الموسيقى" else "Reset music", fontSize = 9.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (clip != null) {
                    onClipChange(clip.copy(audioVolume = volume, audioMuted = muted, audioFadeIn = fadeIn, audioFadeOut = fadeOut))
                }
                onChange(s.copy(
                    volume = if (clip == null) volume else s.volume,
                    muted = if (clip == null) muted else s.muted,
                    musicVolume = musicVolume,
                    musicStartMs = (musicStart * 1000f).toLong(),
                    musicDurationMs = (musicDuration * 1000f).toLong(),
                    musicFadeIn = musicFadeIn,
                    musicFadeOut = musicFadeOut,
                    musicDucking = musicDucking,
                    musicDuckVolume = musicDuckVolume,
                    musicDuckAttack = musicDuckAttack,
                    musicDuckRelease = musicDuckRelease,
                    fadeIn = if (clip == null) fadeIn else s.fadeIn,
                    fadeOut = if (clip == null) fadeOut else s.fadeOut
                ))
                onDismiss()
            }) { Text(if (language == AppLanguage.ARABIC) "تطبيق" else "Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (language == AppLanguage.ARABIC) "إلغاء" else "Cancel") } }
    )
}

@Composable private fun MusicKeyframeDialog(
    s: EditorSettings,
    playheadMs: Long,
    language: AppLanguage,
    onChange: (EditorSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val start = s.musicStartMs
    val local = (playheadMs - start).coerceAtLeast(0L)
    val keys = s.musicKeyframes
    var volume by remember(local, keys) { mutableFloatStateOf(keys.lastOrNull { it.timeMs <= local }?.volume ?: s.musicVolume) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (language == AppLanguage.ARABIC) "أتمتة موسيقى الخلفية" else "Background music automation") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(if (language == AppLanguage.ARABIC) "النقطة: ${formatTimelineTime(local)}" else "Point: ${formatTimelineTime(local)}", color = Color.Gray, fontSize = 11.sp)
            Text(if (language == AppLanguage.ARABIC) "المستوى: ${(volume * 100).toInt()}%" else "Volume: ${(volume * 100).toInt()}%")
            Slider(value = volume, onValueChange = { volume = it }, valueRange = 0f..1.5f)
            keys.sortedBy { it.timeMs }.forEach { k ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(formatTimelineTime(k.timeMs), Modifier.weight(1f), fontSize = 10.sp)
                    Text("${(k.volume * 100).toInt()}%", fontSize = 10.sp, color = Color.Gray)
                    IconButton(onClick = { onChange(s.copy(musicKeyframes = keys.filterNot { it.timeMs == k.timeMs })); }) { Icon(Icons.Default.Delete, null) }
                }
            }
        }
    }, confirmButton = {
        TextButton(onClick = {
            val next = (keys.filterNot { it.timeMs == local } + MusicKeyframe(local, volume)).sortedBy { it.timeMs }
            onChange(s.copy(musicKeyframes = next)); onDismiss()
        }) { Text(if (language == AppLanguage.ARABIC) "حفظ النقطة" else "Save point") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text(if (language == AppLanguage.ARABIC) "إغلاق" else "Close") } })
}

@Composable private fun AudioKeyframeDialog(
    s: EditorSettings,
    clips: List<Clip>,
    clip: Clip?,
    playheadMs: Long,
    language: AppLanguage,
    onChange: (EditorSettings) -> Unit,
    onClipChange: (Clip) -> Unit,
    onDismiss: () -> Unit
) {
    val offset = timelinePositionOf(clips, clip)
    val local = (playheadMs - offset).coerceAtLeast(0L)
    val keyframes = clip?.audioKeyframes ?: s.audioKeyframes.map { ClipAudioKeyframe(it.timeMs, it.volume) }
    var volume by remember(clip, local, keyframes) { mutableFloatStateOf(keyframes.lastOrNull { it.timeMs <= local }?.volume ?: (clip?.audioVolume ?: s.volume)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (language == AppLanguage.ARABIC) "أتمتة مستوى الصوت" else "Volume automation") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(if (language == AppLanguage.ARABIC) "النقطة الحالية: ${formatTimelineTime(local)}" else "Current point: ${formatTimelineTime(local)}", color = Color.Gray, fontSize = 11.sp)
                Text(if (language == AppLanguage.ARABIC) "المستوى: ${(volume * 100).toInt()}%" else "Volume: ${(volume * 100).toInt()}%")
                Slider(value = volume, onValueChange = { volume = it }, valueRange = 0f..2f)
                if (keyframes.isEmpty()) {
                    Text(if (language == AppLanguage.ARABIC) "لا توجد نقاط أتمتة لهذا المقطع." else "No automation points for this clip.", color = Color.Gray, fontSize = 11.sp)
                }
                keyframes.sortedBy { it.timeMs }.forEach { k ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(formatTimelineTime(k.timeMs), Modifier.weight(1f), fontSize = 10.sp)
                        Text("${(k.volume * 100).toInt()}%", fontSize = 10.sp, color = Color.Gray)
                        IconButton(onClick = {
                            if (clip != null) onClipChange(clip.copy(audioKeyframes = clip.audioKeyframes.filterNot { it.timeMs == k.timeMs }))
                            else onChange(s.copy(audioKeyframes = s.audioKeyframes.filterNot { it.timeMs == k.timeMs }))
                        }) { Icon(Icons.Default.Delete, null) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (clip != null) {
                    val next = (clip.audioKeyframes.filterNot { it.timeMs == local } + ClipAudioKeyframe(local, volume)).sortedBy { it.timeMs }
                    onClipChange(clip.copy(audioKeyframes = next))
                } else {
                    val k = AudioKeyframe(local, volume)
                    onChange(s.copy(audioKeyframes = (s.audioKeyframes.filterNot { it.timeMs == local } + k).sortedBy { it.timeMs }))
                }
                onDismiss()
            }) { Text(if (language == AppLanguage.ARABIC) "حفظ النقطة" else "Save point") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (language == AppLanguage.ARABIC) "إغلاق" else "Close") } }
    )
}

private data class FontOption(val key: String, val ar: String, val en: String, val regular: Int, val bold: Int)

private fun fontOptions(): List<FontOption> = listOf(
    FontOption("noto_sans_arabic", "Noto Sans Arabic", "Noto Sans Arabic", R.font.noto_sans_arabic, R.font.noto_sans_arabic_bold),
    FontOption("noto_kufi_arabic", "Noto Kufi Arabic", "Noto Kufi Arabic", R.font.noto_kufi_arabic, R.font.noto_kufi_arabic_bold),
    FontOption("noto_naskh_arabic", "Noto Naskh Arabic", "Noto Naskh Arabic", R.font.noto_naskh_arabic, R.font.noto_naskh_arabic_bold),
    FontOption("amiri", "أميري", "Amiri", R.font.amiri, R.font.amiri_bold),
    FontOption("cairo", "القاهرة", "Cairo", R.font.cairo, R.font.cairo),
    FontOption("tajawal", "تجوال", "Tajawal", R.font.tajawal, R.font.tajawal_bold),
    FontOption("ibm_plex_sans_arabic", "IBM Plex Sans Arabic", "IBM Plex Sans Arabic", R.font.ibm_plex_sans_arabic, R.font.ibm_plex_sans_arabic_bold),
    FontOption("readex_pro", "ريديكس برو", "Readex Pro", R.font.readex_pro, R.font.readex_pro_bold),
    FontOption("aref_ruqaa", "رقعة عارف", "Aref Ruqaa", R.font.aref_ruqaa, R.font.aref_ruqaa_bold),
    FontOption("el_messiri", "المسيري", "El Messiri", R.font.el_messiri, R.font.el_messiri),
    FontOption("changa", "تشانغا", "Changa", R.font.changa, R.font.changa),
    FontOption("jomhuria", "جمهورية", "Jomhuria", R.font.jomhuria, R.font.jomhuria),
    FontOption("lalezar", "لاله‌زار", "Lalezar", R.font.lalezar, R.font.lalezar),
    FontOption("katibeh", "كاتبه", "Katibeh", R.font.katibeh, R.font.katibeh),
    FontOption("lemonada", "ليمونادة", "Lemonada", R.font.lemonada, R.font.lemonada),
    FontOption("markazi_text", "مرْكزي", "Markazi Text", R.font.markazi_text, R.font.markazi_text),
    FontOption("lateef", "لطيف", "Lateef", R.font.lateef, R.font.lateef_bold),
    FontOption("harmattan", "هرماتان", "Harmattan", R.font.harmattan, R.font.harmattan_bold),
    FontOption("mada", "مدى", "Mada", R.font.mada, R.font.mada),
    FontOption("scheherazade_new", "شهرزاد الجديدة", "Scheherazade New", R.font.scheherazade_new, R.font.scheherazade_new_bold),
    FontOption("reem_kufi", "ريم كوفي", "Reem Kufi", R.font.reem_kufi, R.font.reem_kufi),
    FontOption("rubik", "روبيك", "Rubik", R.font.rubik, R.font.rubik),
    FontOption("lato", "Lato", "Lato", R.font.lato, R.font.lato_bold),
    FontOption("inter", "Inter", "Inter", R.font.inter, R.font.inter_bold),
    FontOption("cabin", "Cabin", "Cabin", R.font.cabin, R.font.cabin_bold),
    FontOption("comic_neue", "كوميك", "Comic Neue", R.font.comic_neue, R.font.comic_neue_bold),
    FontOption("dejavu_sans", "DejaVu Sans", "DejaVu Sans", R.font.dejavu_sans, R.font.dejavu_sans_bold)
)

private fun fontFamilyFor(key: String, bold: Boolean = false): FontFamily {
    val f = fontOptions().firstOrNull { it.key == key } ?: fontOptions().first()
    return FontFamily(if (bold) Font(f.bold) else Font(f.regular))
}

@Composable private fun TextDialog(s: EditorSettings, language: AppLanguage, onChange: (EditorSettings) -> Unit, onDismiss: () -> Unit) {
    val presets = if (language == AppLanguage.ARABIC)
        listOf("عنوان الفيديو", "رحلتي الجديدة", "لحظة لا تُنسى", "صباح الخير", "مساء الخير", "استكشف العالم", "ذكريات جميلة", "أجمل اللحظات", "تابعني للمزيد", "اشترك الآن", "شكراً للمشاهدة", "النهاية")
    else listOf("VIDEO TITLE", "MY NEW JOURNEY", "A MOMENT TO REMEMBER", "GOOD MORNING", "GOOD EVENING", "EXPLORE THE WORLD", "BEAUTIFUL MEMORIES", "BEST MOMENTS", "FOLLOW FOR MORE", "SUBSCRIBE NOW", "THANKS FOR WATCHING", "THE END")
    var layers by remember(s.textLayers, s.text) { mutableStateOf(s.textLayers.ifEmpty { if (s.text.isNotBlank()) listOf(TextLayer(text=s.text, size=s.textSize, color=s.textColor, font=s.textFont)) else listOf(TextLayer(text="")) }) }
    var selected by remember { mutableIntStateOf(0) }
    var bold by remember { mutableStateOf(layers.firstOrNull()?.bold ?: true) }
    val layer = layers.getOrNull(selected) ?: TextLayer()
    fun edit(next: TextLayer) { layers = layers.mapIndexed { i, old -> if (i == selected) next else old } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (language == AppLanguage.ARABIC) "النصوص والخطوط" else "Text & Fonts") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                layers.forEachIndexed { i, l ->
                    FilterChip(selected=i==selected, onClick={selected=i; bold=layers[i].bold}, label={Text("${i+1}")})
                }
                AssistChip(onClick={ layers = layers + TextLayer(y = -0.55f + layers.size.coerceAtMost(4)*0.25f); selected = layers.lastIndex }, label={Text(if(language==AppLanguage.ARABIC) "+ نص" else "+ Text")}, leadingIcon={Icon(Icons.Default.Add,null)})
            }
            Spacer(Modifier.height(7.dp))
            OutlinedTextField(layer.text, { edit(layer.copy(text=it)) }, label={Text(if(language==AppLanguage.ARABIC) "النص" else "Text")}, modifier=Modifier.fillMaxWidth(), minLines=2)
            Text(if(language==AppLanguage.ARABIC) "قوالب نص جاهزة" else "Ready text templates", fontWeight=FontWeight.Bold, modifier=Modifier.padding(top=8.dp))
            LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp), contentPadding=PaddingValues(vertical=4.dp)) { items(presets) { p -> AssistChip(onClick={edit(layer.copy(text=p))}, label={Text(p, maxLines=1)}) } }
            Text(if(language==AppLanguage.ARABIC) "الخط" else "Font", fontWeight=FontWeight.Bold, modifier=Modifier.padding(top=7.dp))
            LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp), contentPadding=PaddingValues(vertical=4.dp)) {
                items(fontOptions()) { f -> FilterChip(selected=layer.font==f.key, onClick={edit(layer.copy(font=f.key))}, label={Text(if(language==AppLanguage.ARABIC) f.ar else f.en, fontFamily=fontFamilyFor(f.key), maxLines=1)}) }
            }
            Text(if(language==AppLanguage.ARABIC) "الحجم ${layer.size.toInt()}" else "Size ${layer.size.toInt()}"); Slider(layer.size, {edit(layer.copy(size=it))}, valueRange = 16f..96f)
            Row(verticalAlignment=Alignment.CenterVertically) { Text(if(language==AppLanguage.ARABIC) "عريض" else "Bold", Modifier.weight(1f)); Switch(checked=bold, onCheckedChange={bold=it; edit(layer.copy(bold=it))}) }
            Text(if(language==AppLanguage.ARABIC) "أنماط احترافية" else "Professional styles", fontWeight=FontWeight.Bold)
            LazyRow(horizontalArrangement=Arrangement.spacedBy(5.dp), contentPadding=PaddingValues(vertical=3.dp)) {
                val textPresets = listOf(
                    Triple("cinematic", "سينمائي", "Cinematic"),
                    Triple("headline", "عنوان قوي", "Bold Headline"),
                    Triple("minimal", "بسيط", "Minimal"),
                    Triple("neon", "نيون", "Neon"),
                    Triple("subtitle", "ترجمة", "Subtitle")
                )
                items(textPresets) { (id, ar, en) ->
                    AssistChip(onClick = {
                        val preset = when (id) {
                            "cinematic" -> layer.copy(size = 48f, bold = true, alpha = 1f, backgroundAlpha = 0.35f, backgroundPadding = 12f, shadowEnabled = true, shadowRadius = 8f, shadowDx = 2f, shadowDy = 3f, strokeEnabled = true, strokeWidth = 2f, strokeColor = 0xFF000000, glowEnabled = false, letterSpacing = 0.5f, lineHeightMultiplier = 1f, textAlign = "center")
                            "headline" -> layer.copy(size = 56f, bold = true, alpha = 1f, backgroundAlpha = 0f, backgroundPadding = 6f, shadowEnabled = true, shadowRadius = 5f, shadowDx = 2f, shadowDy = 2f, strokeEnabled = true, strokeWidth = 1.5f, glowEnabled = false, letterSpacing = 0f, textAlign = "center")
                            "minimal" -> layer.copy(size = 34f, bold = false, alpha = 0.96f, backgroundAlpha = 0f, backgroundPadding = 2f, shadowEnabled = false, strokeEnabled = false, glowEnabled = false, letterSpacing = 0.2f, lineHeightMultiplier = 1.1f, textAlign = "center")
                            "neon" -> layer.copy(size = 46f, bold = true, alpha = 1f, backgroundAlpha = 0f, backgroundPadding = 8f, shadowEnabled = false, strokeEnabled = false, glowEnabled = true, glowColor = 0xFFFFFFFF, glowRadius = 18f, letterSpacing = 1f, textAlign = "center")
                            else -> layer.copy(size = 30f, bold = true, alpha = 1f, backgroundAlpha = 0.6f, backgroundPadding = 10f, shadowEnabled = true, shadowRadius = 4f, shadowDx = 1f, shadowDy = 2f, strokeEnabled = false, glowEnabled = false, letterSpacing = 0f, lineHeightMultiplier = 1.05f, textAlign = "center")
                        }
                        bold = preset.bold
                        edit(preset)
                    }, label = { Text(if(language == AppLanguage.ARABIC) ar else en, fontSize = 9.sp) })
                }
            }
            Text(if(language==AppLanguage.ARABIC) "لون النص" else "Text color", fontWeight=FontWeight.Bold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf(Color.White to "White", Color(0xFFFFD54F) to "Gold", Color(0xFF80D8FF) to "Cyan", Color(0xFFFF80AB) to "Pink", Color(0xFFB39DDB) to "Purple").forEach { (c,label) ->
                    FilterChip(selected=layer.color == c.toArgb().toLong(), onClick={edit(layer.copy(color=c.toArgb().toLong()))}, label={Text(if(language==AppLanguage.ARABIC) when(label){"White"->"أبيض";"Gold"->"ذهبي";"Cyan"->"سماوي";"Pink"->"وردي";else->"بنفسجي"} else label, fontSize=9.sp)})
                }
            }
            Text(if(language==AppLanguage.ARABIC) "شفافية ${(layer.alpha*100).toInt()}%" else "Opacity ${(layer.alpha*100).toInt()}%"); Slider(layer.alpha, {edit(layer.copy(alpha=it))}, valueRange = 0.1f..1f)
            Text(if(language==AppLanguage.ARABIC) "تباعد الحروف" else "Letter spacing"); Slider(layer.letterSpacing, {edit(layer.copy(letterSpacing=it))}, valueRange = -2f..8f)
            Text(if(language==AppLanguage.ARABIC) "ارتفاع الأسطر" else "Line height"); Slider(layer.lineHeightMultiplier, {edit(layer.copy(lineHeightMultiplier=it))}, valueRange = 0.8f..2f)
            Text(if(language==AppLanguage.ARABIC) "محاذاة النص" else "Text alignment", fontWeight=FontWeight.Bold)
            Row(horizontalArrangement=Arrangement.spacedBy(5.dp)) { listOf("start" to "يمين","center" to "وسط","end" to "يسار").forEach { (v,l) -> FilterChip(selected=layer.textAlign==v,onClick={edit(layer.copy(textAlign=v))},label={Text(if(language==AppLanguage.ARABIC) l else v)}) } }
            Text(if(language==AppLanguage.ARABIC) "حركة النص" else "Text animation", fontWeight=FontWeight.Bold)
            val anims=listOf("none" to if(language==AppLanguage.ARABIC) "بدون" else "None", "fade" to if(language==AppLanguage.ARABIC) "ظهور" else "Fade", "pop" to if(language==AppLanguage.ARABIC) "انبثاق" else "Pop", "slide" to if(language==AppLanguage.ARABIC) "انزلاق" else "Slide", "zoom" to if(language==AppLanguage.ARABIC) "تكبير" else "Zoom", "typewriter" to if(language==AppLanguage.ARABIC) "كتابة" else "Typewriter")
            LazyRow(horizontalArrangement=Arrangement.spacedBy(5.dp),contentPadding=PaddingValues(vertical=3.dp)){items(anims){(v,l)->FilterChip(selected=layer.animation==v,onClick={edit(layer.copy(animation=v))},label={Text(l,fontSize=9.sp)})}}
            Text(if(language==AppLanguage.ARABIC) "خلفية النص ${(layer.backgroundAlpha*100).toInt()}%" else "Text background ${(layer.backgroundAlpha*100).toInt()}%")
            Slider(layer.backgroundAlpha, {edit(layer.copy(backgroundAlpha=it))}, valueRange = 0f..0.9f)
            Text(if(language==AppLanguage.ARABIC) "حشوة الخلفية" else "Background padding"); Slider(layer.backgroundPadding, {edit(layer.copy(backgroundPadding=it))}, valueRange = 0f..30f)
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text(if(language==AppLanguage.ARABIC) "ظل النص" else "Text shadow", Modifier.weight(1f))
                Switch(checked=layer.shadowEnabled, onCheckedChange={edit(layer.copy(shadowEnabled=it))})
            }
            if (layer.shadowEnabled) {
                Text(if(language==AppLanguage.ARABIC) "قوة الظل ${layer.shadowRadius.toInt()}" else "Shadow strength ${layer.shadowRadius.toInt()}")
                Slider(layer.shadowRadius, {edit(layer.copy(shadowRadius=it))}, valueRange = 0f..20f)
                Text(if(language==AppLanguage.ARABIC) "إزاحة أفقية ${layer.shadowDx.toInt()}" else "Shadow X ${layer.shadowDx.toInt()}")
                Slider(layer.shadowDx, {edit(layer.copy(shadowDx=it))}, valueRange = -15f..15f)
                Text(if(language==AppLanguage.ARABIC) "إزاحة رأسية ${layer.shadowDy.toInt()}" else "Shadow Y ${layer.shadowDy.toInt()}")
                Slider(layer.shadowDy, {edit(layer.copy(shadowDy=it))}, valueRange = -15f..15f)
            }
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text(if(language==AppLanguage.ARABIC) "حدود النص" else "Text outline", Modifier.weight(1f))
                Switch(checked=layer.strokeEnabled, onCheckedChange={edit(layer.copy(strokeEnabled=it))})
            }
            Row(verticalAlignment=Alignment.CenterVertically) { Text(if(language==AppLanguage.ARABIC) "توهج Glow" else "Glow", Modifier.weight(1f)); Switch(checked=layer.glowEnabled,onCheckedChange={edit(layer.copy(glowEnabled=it))}) }
            if (layer.glowEnabled) { Text(if(language==AppLanguage.ARABIC) "قوة التوهج" else "Glow radius"); Slider(layer.glowRadius, {edit(layer.copy(glowRadius=it))}, valueRange = 1f..30f) }
            if (layer.strokeEnabled) {
                Text(if(language==AppLanguage.ARABIC) "سماكة الحدود ${"%.1f".format(layer.strokeWidth)}" else "Outline width ${"%.1f".format(layer.strokeWidth)}")
                Slider(layer.strokeWidth, {edit(layer.copy(strokeWidth=it))}, valueRange = 0.5f..12f)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                    listOf(Color.Black to "Black", Color.White to "White", Color(0xFFFFD54F) to "Gold", Color(0xFF5E35B1) to "Purple").forEach { (c,l) ->
                        FilterChip(selected=layer.strokeColor==c.toArgb().toLong(), onClick={edit(layer.copy(strokeColor=c.toArgb().toLong()))}, label={Text(if(language==AppLanguage.ARABIC) when(l){"Black"->"أسود";"White"->"أبيض";"Gold"->"ذهبي";else->"بنفسجي"} else l,fontSize=9.sp)})
                    }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(5.dp)){listOf(Color.Transparent to "None",Color.Black to "Black",Color.White to "White",Color(0xFF5E35B1) to "Purple").forEach{(c,l)->FilterChip(selected=layer.backgroundColor==c.toArgb().toLong(),onClick={edit(layer.copy(backgroundColor=c.toArgb().toLong()))},label={Text(if(language==AppLanguage.ARABIC) when(l){"None"->"بدون";"Black"->"أسود";"White"->"أبيض";else->"بنفسجي"}else l,fontSize=9.sp)})}}
            Text(if(language==AppLanguage.ARABIC) "الموضع الأفقي" else "Horizontal position"); Slider(layer.x, {edit(layer.copy(x=it))}, valueRange = -1f..1f)
            Text(if(language==AppLanguage.ARABIC) "الموضع الرأسي" else "Vertical position"); Slider(layer.y, {edit(layer.copy(y=it))}, valueRange = -1f..1f)
            Text(if(language==AppLanguage.ARABIC) "التكبير ${"%.2f".format(layer.scale)}x" else "Scale ${"%.2f".format(layer.scale)}x"); Slider(layer.scale, {edit(layer.copy(scale=it))}, valueRange = 0.25f..2.5f)
            Text(if(language==AppLanguage.ARABIC) "الدوران ${layer.rotation.toInt()}°" else "Rotation ${layer.rotation.toInt()}°"); Slider(layer.rotation, {edit(layer.copy(rotation=it))}, valueRange = -180f..180f)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = {
                    val copy = layer.copy(id = System.nanoTime().toString(), y = (layer.y + 0.12f).coerceIn(-1f, 1f))
                    layers = layers + copy
                    selected = layers.lastIndex
                    bold = copy.bold
                }, modifier = Modifier.weight(1f), enabled = layer.text.isNotBlank()) {
                    Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                    Text(if(language==AppLanguage.ARABIC) "تكرار" else "Duplicate")
                }
                if (layers.size > 1) OutlinedButton(onClick={layers=layers.filterIndexed{i,_->i!=selected};selected=(selected-1).coerceAtLeast(0)}) {
                    Icon(Icons.Default.Delete,null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(if(language==AppLanguage.ARABIC) "حذف" else "Delete")
                }
            }
        }
    }, confirmButton={TextButton(onClick={
        val clean=layers.filter{it.text.isNotBlank()}; val first=clean.firstOrNull();
        onChange(s.copy(text=first?.text ?: "", textSize=first?.size ?: s.textSize, textColor=first?.color ?: s.textColor, textFont=first?.font ?: s.textFont, textVisible=clean.isNotEmpty(), textLayers=clean)); onDismiss()
    }){Text(if(language==AppLanguage.ARABIC) "تطبيق" else "Apply")}}, dismissButton={TextButton(onClick=onDismiss){Text(if(language==AppLanguage.ARABIC) "إلغاء" else "Cancel")}})
}

@Composable
private fun VideoPresetDialog(s: EditorSettings, language: AppLanguage, onChange: (EditorSettings) -> Unit, onDismiss: () -> Unit) {
    data class Preset(val id: String, val ar: String, val en: String, val apply: (EditorSettings) -> EditorSettings)
    val presets = listOf(
        Preset("reset", "إعادة ضبط الفيديو بالكامل", "Reset all video settings") { it.copy(
            speed = 1f, speedKeyframes = emptyList(),
            filter = "none", brightness = 0f, contrast = 1f, saturation = 1f, hue = 0f, temperature = 0f, tint = 0f,
            aspect = "16:9", cropZoom = 1f, cropX = 0f, cropY = 0f, rotation = 0,
            flipHorizontal = false, flipVertical = false, overlayOpacity = 0f, sticker = "", stickerX = 0.78f, stickerY = 0.72f, stickerScale = 0.35f, stickerRotation = 0f, stickerAlpha = 1f,
            transition = "none", transitionDuration = 0.4f, motionIntensity = 1f,
            videoKeyframes = emptyList()
        ) },
        Preset("landscape", "سينمائي 16:9", "Cinematic 16:9") { it.copy(aspect = "16:9", cropZoom = 1f, cropX = 0f, cropY = 0f, rotation = 0, flipHorizontal = false, flipVertical = false) },
        Preset("portrait", "عمودي 9:16", "Portrait 9:16") { it.copy(aspect = "9:16", cropZoom = maxOf(it.cropZoom, 1.15f), cropX = 0f, cropY = 0f, rotation = 0) },
        Preset("square", "مربع 1:1", "Square 1:1") { it.copy(aspect = "1:1", cropZoom = maxOf(it.cropZoom, 1.08f), cropX = 0f, cropY = 0f, rotation = 0) },
        Preset("social", "اجتماعي 4:5", "Social 4:5") { it.copy(aspect = "4:5", cropZoom = maxOf(it.cropZoom, 1.12f), cropX = 0f, cropY = 0f, rotation = 0) },
        Preset("portrait2x3", "صورة 2:3", "Photo 2:3") { it.copy(aspect = "2:3", cropZoom = maxOf(it.cropZoom, 1.08f), cropX = 0f, cropY = 0f, rotation = 0) },
        Preset("portrait3x4", "عمودي 3:4", "Portrait 3:4") { it.copy(aspect = "3:4", cropZoom = maxOf(it.cropZoom, 1.08f), cropX = 0f, cropY = 0f, rotation = 0) },
        Preset("landscape3x2", "صورة 3:2", "Photo 3:2") { it.copy(aspect = "3:2", cropZoom = maxOf(it.cropZoom, 1.08f), cropX = 0f, cropY = 0f, rotation = 0) },
        Preset("cinema21x9", "سينمائي عريض 21:9", "Ultra-wide 21:9") { it.copy(aspect = "21:9", cropZoom = maxOf(it.cropZoom, 1.02f), cropX = 0f, cropY = 0f, rotation = 0) }
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (language == AppLanguage.ARABIC) "إعدادات الفيديو السريعة" else "Video Presets") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(if (language == AppLanguage.ARABIC) "طبّق إعداداً جاهزاً للإطار بسرعة، ثم عدّل التفاصيل من الاقتصاص والمقاس." else "Apply a ready-made frame setup, then fine-tune it with Crop and Canvas.", color = Color.Gray, fontSize = 11.sp)
                Text(if (language == AppLanguage.ARABIC) "يمكنك أيضًا لمس/سحب الكادر في المعاينة: التكبير والسحب والدوران تُطبّق مباشرة، أو تنشئ نقطة حركة عند وجود Keyframes." else "You can also manipulate the preview directly: pinch, drag and rotate edit the static frame, or the current motion keyframe when keyframes exist.", color = Color.Gray, fontSize = 10.sp)
                presets.forEach { preset ->
                    OutlinedButton(onClick = { onChange(preset.apply(s)); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                        Icon(if (preset.id == "reset") Icons.Default.RestartAlt else Icons.Default.Tune, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(7.dp))
                        Text(if (language == AppLanguage.ARABIC) preset.ar else preset.en)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(if (language == AppLanguage.ARABIC) "إغلاق" else "Close") } }
    )
}

@Composable
private fun FilterDialog(s: EditorSettings, language: AppLanguage, onChange: (EditorSettings)->Unit, onDismiss:()->Unit) {
    val vals=listOf("none","warm","cool","mono","sepia","invert","vivid","dream","noir","faded","tealOrange","vintage","sunset","ice","dramatic","soft")
    val labels=if(language==AppLanguage.ARABIC) listOf("بدون","سينمائي دافئ","سينمائي بارد","أبيض وأسود","سيبيا","معكوس","حيوي","حالم","نوير","باهت","Teal & Orange","فنتج","غروب","جليدي","درامي","ناعم") else listOf("None","Warm Cinema","Cool Cinema","Grayscale","Sepia","Inverted","Vivid","Dream","Noir","Faded","Teal & Orange","Vintage","Sunset","Ice","Dramatic","Soft")
    AlertDialog(onDismissRequest=onDismiss,title={Text(if(language==AppLanguage.ARABIC)"الفلاتر" else "Filters")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(7.dp)){
            Text(if(language==AppLanguage.ARABIC)"اختر فلترًا جاهزًا" else "Choose a ready filter",fontWeight=FontWeight.Bold)
            labels.forEachIndexed{i,label->FilterChip(selected=s.filter==vals[i],onClick={onChange(s.copy(filter=vals[i]));onDismiss()},label={Text(label)},modifier=Modifier.fillMaxWidth())}
            OutlinedButton(onClick={onDismiss},modifier=Modifier.fillMaxWidth()){Text(if(language==AppLanguage.ARABIC)"إغلاق" else "Close")}
        }
    },confirmButton={})
}
@Composable
private fun EffectsDialog(s: EditorSettings, language: AppLanguage, onChange:(EditorSettings)->Unit,onDismiss:()->Unit){
    var blur by remember(s.blurRadius){mutableFloatStateOf(s.blurRadius)}
    var mosaicEnabled by remember(s.mosaicEnabled){mutableStateOf(s.mosaicEnabled)}
    var blockSize by remember(s.mosaicBlockSize){mutableFloatStateOf(s.mosaicBlockSize)}
    var mx by remember(s.mosaicX){mutableFloatStateOf(s.mosaicX)}; var my by remember(s.mosaicY){mutableFloatStateOf(s.mosaicY)}
    var mw by remember(s.mosaicWidth){mutableFloatStateOf(s.mosaicWidth)}; var mh by remember(s.mosaicHeight){mutableFloatStateOf(s.mosaicHeight)}
    AlertDialog(onDismissRequest=onDismiss,title={Text(if(language==AppLanguage.ARABIC)"المؤثرات" else "Effects")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(7.dp)){
            Text(if(language==AppLanguage.ARABIC)"المؤثرات والمعالجة" else "Effects & processing",fontWeight=FontWeight.Bold)
            Text(if(language==AppLanguage.ARABIC)"ضبابية ${blur.toInt()}" else "Blur ${blur.toInt()}"); Slider(value=blur,onValueChange={blur=it},valueRange=0f..20f)
            Button(onClick={onChange(s.copy(blurRadius=blur));onDismiss()},modifier=Modifier.fillMaxWidth()){Text(if(language==AppLanguage.ARABIC)"تطبيق الضبابية" else "Apply blur")}
            HorizontalDivider()
            Text(if(language==AppLanguage.ARABIC)"بكسلة منطقة (Mosaic)" else "Region Mosaic",fontWeight=FontWeight.Bold)
            Row(verticalAlignment=Alignment.CenterVertically){Text(if(language==AppLanguage.ARABIC)"تفعيل البكسلة" else "Enable mosaic",Modifier.weight(1f));Switch(checked=mosaicEnabled,onCheckedChange={mosaicEnabled=it})}
            Text("X ${(mx*100).toInt()}%");Slider(value=mx,onValueChange={mx=it},valueRange=0f..0.9f)
            Text("Y ${(my*100).toInt()}%");Slider(value=my,onValueChange={my=it},valueRange=0f..0.9f)
            Text(if(language==AppLanguage.ARABIC)"العرض ${(mw*100).toInt()}%" else "Width ${(mw*100).toInt()}%");Slider(value=mw,onValueChange={mw=it},valueRange=0.05f..1f)
            Text(if(language==AppLanguage.ARABIC)"الارتفاع ${(mh*100).toInt()}%" else "Height ${(mh*100).toInt()}%");Slider(value=mh,onValueChange={mh=it},valueRange=0.05f..1f)
            Text(if(language==AppLanguage.ARABIC)"حجم البكسل ${(blockSize*100).toInt()}%" else "Block size ${(blockSize*100).toInt()}%");Slider(value=blockSize,onValueChange={blockSize=it},valueRange=0.02f..0.20f)
            Button(onClick={onChange(s.copy(blurRadius=blur,mosaicEnabled=mosaicEnabled,mosaicBlockSize=blockSize,mosaicX=mx,mosaicY=my,mosaicWidth=mw,mosaicHeight=mh));onDismiss()},modifier=Modifier.fillMaxWidth()){Text(if(language==AppLanguage.ARABIC)"تطبيق المؤثرات" else "Apply effects")}
            OutlinedButton(onClick={onDismiss},modifier=Modifier.fillMaxWidth()){Text(if(language==AppLanguage.ARABIC)"إغلاق" else "Close")}
        }
    },confirmButton={})
}
@Composable private fun AdjustDialog(s: EditorSettings, language: AppLanguage, onChange:(EditorSettings)->Unit,onDismiss:()->Unit){
    var b by remember{mutableFloatStateOf(s.brightness)};var c by remember{mutableFloatStateOf(s.contrast)};var sat by remember{mutableFloatStateOf(s.saturation)};var hue by remember{mutableFloatStateOf(s.hue)};var temp by remember{mutableFloatStateOf(s.temperature)};var tintValue by remember{mutableFloatStateOf(s.tint)}
    AlertDialog(onDismissRequest=onDismiss,title={Text(if(language==AppLanguage.ARABIC)"ضبط متقدم" else "Advanced Adjust")},text={Column{Text(if(language==AppLanguage.ARABIC)"السطوع ${(b*100).toInt()}" else "Brightness ${(b*100).toInt()}");Slider(value = b, onValueChange = { v -> b = v }, valueRange = -1f..1f);Text(if(language==AppLanguage.ARABIC)"التباين ${(c*100).toInt()}%" else "Contrast ${(c*100).toInt()}%");Slider(value = c, onValueChange = { v -> c = v }, valueRange = 0f..2f);Text(if(language==AppLanguage.ARABIC)"التشبع ${(sat*100).toInt()}%" else "Saturation ${(sat*100).toInt()}%");Slider(value = sat, onValueChange = { v -> sat = v }, valueRange = 0f..2f);Text(if(language==AppLanguage.ARABIC)"درجة اللون ${hue.toInt()}°" else "Hue ${hue.toInt()}°");Slider(value = hue, onValueChange = { v -> hue = v }, valueRange = -180f..180f);Text(if(language==AppLanguage.ARABIC)"حرارة اللون ${temp.toInt()}" else "Temperature ${temp.toInt()}");Slider(value = temp, onValueChange = { v -> temp = v }, valueRange = -100f..100f);Text(if(language==AppLanguage.ARABIC)"الصبغة ${tintValue.toInt()}" else "Tint ${tintValue.toInt()}");Slider(value = tintValue, onValueChange = { v -> tintValue = v }, valueRange = -100f..100f)}},confirmButton={TextButton(onClick={onChange(s.copy(brightness=b,contrast=c,saturation=sat,hue=hue,temperature=temp,tint=tintValue));onDismiss()}){Text(if(language==AppLanguage.ARABIC)"تطبيق" else "Apply")}},dismissButton={TextButton(onClick=onDismiss){Text(if(language==AppLanguage.ARABIC)"إلغاء" else "Cancel")}})
}

@Composable private fun CanvasDialog(s:EditorSettings,language:AppLanguage,onChange:(EditorSettings)->Unit,onDismiss:()->Unit){
    val vals=listOf("16:9","9:16","1:1","4:5","2:3","3:4","3:2","21:9");
    val labels=if(language==AppLanguage.ARABIC) listOf("أفقي 16:9","عمودي 9:16","مربع 1:1","عمودي 4:5","صورة 2:3","عمودي 3:4","صورة 3:2","سينمائي عريض 21:9") else listOf("Landscape 16:9","Portrait 9:16","Square 1:1","Portrait 4:5","Photo 2:3","Portrait 3:4","Photo 3:2","Ultra-wide 21:9")
    SimpleChoiceDialog(if(language==AppLanguage.ARABIC)"مقاس الفيديو"else"Canvas / Aspect ratio",labels,null,onDismiss){onChange(s.copy(aspect=vals[it]));onDismiss()}
}

@Composable private fun TransitionDialog(s:EditorSettings,language:AppLanguage,onChange:(EditorSettings)->Unit,onDismiss:()->Unit){
    val vals=listOf("none","fade","slide","zoom","wipe","blur","flash","spin","push","pull","glitch","crossZoom","rotateZoom","lightLeak","filmBurn","radial","shutter","cube","elastic","digital","prism","swing","bounce");
    val labels=if(language==AppLanguage.ARABIC) listOf("بدون","تلاشي","انزلاق","تكبير سينمائي","مسح","ضباب","فلاش","دوران","دفع","سحب","تشويش","زوم متقاطع","دوران + زوم","تسريب ضوء","احتراق فيلم","دائري","مصراع","مكعب","مرن","رقمي","منشور","تأرجح","ارتداد") else listOf("None","Fade","Slide","Cinematic Zoom","Wipe","Blur","Flash","Spin","Push","Pull","Glitch","Cross Zoom","Rotate Zoom","Light Leak","Film Burn","Radial","Shutter","Cube","Elastic","Digital","Prism","Swing","Bounce")
    var selected=remember{mutableStateOf(s.transition)}
    var duration by remember{mutableFloatStateOf(s.transitionDuration)}
    var intensity by remember{mutableFloatStateOf(s.motionIntensity)}
    AlertDialog(onDismissRequest=onDismiss,title={Text(if(language==AppLanguage.ARABIC)"انتقالات وتأثيرات الحركة"else"Transitions & Motion")},text={
        Column(Modifier.verticalScroll(rememberScrollState())){
            labels.forEachIndexed{i,label->FilterChip(selected=selected.value==vals[i],onClick={selected.value=vals[i]},label={Text(label)},modifier=Modifier.fillMaxWidth().padding(vertical=2.dp))}
            Spacer(Modifier.height(8.dp))
            Text(if(language==AppLanguage.ARABIC)"مدة الحركة ${(duration*1000).toInt()} مللي ثانية"else"Motion duration ${(duration*1000).toInt()} ms")
            Slider(value = duration, onValueChange = { v -> duration = v }, valueRange = 0.2f..1.2f)
            Text(if(language==AppLanguage.ARABIC)"شدة الحركة ${(intensity*100).toInt()}%"else"Motion intensity ${(intensity*100).toInt()}%")
            Slider(value = intensity, onValueChange = { v -> intensity = v }, valueRange = 0.35f..1.8f)
            Text(if(language==AppLanguage.ARABIC)"إعدادات سريعة" else "Quick presets", fontWeight=FontWeight.SemiBold, fontSize=12.sp)
            LazyRow(horizontalArrangement=Arrangement.spacedBy(5.dp)){
                item{AssistChip(onClick={selected.value="fade";duration=0.45f;intensity=0.85f},label={Text(if(language==AppLanguage.ARABIC)"سينمائي" else "Cinematic",fontSize=9.sp)})}
                item{AssistChip(onClick={selected.value="zoom";duration=0.65f;intensity=1.05f},label={Text(if(language==AppLanguage.ARABIC)"زوم" else "Zoom",fontSize=9.sp)})}
                item{AssistChip(onClick={selected.value="slide";duration=0.4f;intensity=0.9f},label={Text(if(language==AppLanguage.ARABIC)"سريع" else "Fast",fontSize=9.sp)})}
                item{AssistChip(onClick={selected.value="none";duration=0.4f;intensity=1f},label={Text(if(language==AppLanguage.ARABIC)"بدون" else "None",fontSize=9.sp)})}
            }
        }
    },confirmButton={TextButton(onClick={onChange(s.copy(transition=selected.value,transitionDuration=duration,motionIntensity=intensity));onDismiss()}){Text(if(language==AppLanguage.ARABIC)"تطبيق"else"Apply")}},dismissButton={TextButton(onClick=onDismiss){Text(if(language==AppLanguage.ARABIC)"إلغاء"else"Cancel")}})
}

@Composable private fun StickerDialog(s:EditorSettings,language:AppLanguage,onChange:(EditorSettings)->Unit,onDismiss:()->Unit){
    val vals=listOf("🔥","❤️","✨","⭐","😂","🎉","😎","📍","🎬","🎵","💥","⚡","🌟","🏆","🚀","❤️‍🔥","☀️","🌙","✓","NEW")
    var sticker by remember(s.sticker){mutableStateOf(s.sticker)}
    var x by remember(s.stickerX){mutableFloatStateOf(s.stickerX)}
    var y by remember(s.stickerY){mutableFloatStateOf(s.stickerY)}
    var scale by remember(s.stickerScale){mutableFloatStateOf(s.stickerScale)}
    var rotation by remember(s.stickerRotation){mutableFloatStateOf(s.stickerRotation)}
    var alpha by remember(s.stickerAlpha){mutableFloatStateOf(s.stickerAlpha)}
    AlertDialog(onDismissRequest=onDismiss,title={Text(if(language==AppLanguage.ARABIC)"الملصقات"else"Stickers")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)){
            LazyRow(horizontalArrangement=Arrangement.spacedBy(5.dp),contentPadding=PaddingValues(vertical=4.dp)){items(vals){v->FilterChip(selected=sticker==v,onClick={sticker=v},label={Text(v,fontSize=20.sp)})}}
            Text(if(language==AppLanguage.ARABIC)"الموضع الأفقي ${(x*100).toInt()}%"else"Horizontal ${(x*100).toInt()}%")
            Slider(value = x, onValueChange = { v -> x = v }, valueRange = -1f..1f)
            Text(if(language==AppLanguage.ARABIC)"الموضع العمودي ${(y*100).toInt()}%"else"Vertical ${(y*100).toInt()}%")
            Slider(value = y, onValueChange = { v -> y = v }, valueRange = -1f..1f)
            Text(if(language==AppLanguage.ARABIC)"الحجم ${"%.2f".format(scale)}×"else"Scale ${"%.2f".format(scale)}×")
            Slider(value = scale, onValueChange = { v -> scale = v }, valueRange = 0.15f..1.2f)
            Text(if(language==AppLanguage.ARABIC)"الدوران ${rotation.toInt()}°"else"Rotation ${rotation.toInt()}°")
            Slider(value = rotation, onValueChange = { v -> rotation = v }, valueRange = -180f..180f)
            Text(if(language==AppLanguage.ARABIC)"الشفافية ${(alpha*100).toInt()}%"else"Opacity ${(alpha*100).toInt()}%")
            Slider(value = alpha, onValueChange = { v -> alpha = v }, valueRange = 0.1f..1f)
            OutlinedButton(onClick={sticker=""},modifier=Modifier.fillMaxWidth()){Text(if(language==AppLanguage.ARABIC)"إزالة الملصق"else"Remove sticker")}
        }
    },confirmButton={TextButton(onClick={onChange(s.copy(sticker=sticker,stickerX=x,stickerY=y,stickerScale=scale,stickerRotation=rotation,stickerAlpha=alpha));onDismiss()}){Text(if(language==AppLanguage.ARABIC)"تطبيق"else"Apply")}},dismissButton={TextButton(onClick=onDismiss){Text(if(language==AppLanguage.ARABIC)"إلغاء"else"Cancel")}})
}

@Composable
private fun OverlayDialog(
    s: EditorSettings, language: AppLanguage, onChange: (EditorSettings) -> Unit,
    onPickImage: () -> Unit, onAiCutout: () -> Unit, onDismiss: () -> Unit
) {
    val fallback = if (s.overlayImageUri.isNotBlank()) listOf(PipLayer(uri=s.overlayImageUri, x=s.overlayImageX, y=s.overlayImageY, scale=s.overlayImageScale, rotation=s.overlayImageRotation, alpha=s.overlayImageAlpha)) else emptyList()
    var layers by remember(s.pipLayers, s.overlayImageUri) { mutableStateOf(if (s.pipLayers.isNotEmpty()) s.pipLayers else fallback) }
    var selected by remember(layers) { mutableIntStateOf(0.coerceAtMost((layers.size - 1).coerceAtLeast(0))) }
    val selectedLayer = layers.getOrNull(selected)
    fun editSelected(transform: (PipLayer) -> PipLayer) {
        val current = layers.getOrNull(selected) ?: return
        layers = layers.mapIndexed { i, item -> if (i == selected) transform(current) else item }
    }
    fun commit(next: List<PipLayer> = layers, opacity: Float = s.overlayOpacity) {
        val first = next.firstOrNull()
        onChange(s.copy(pipLayers=next, overlayOpacity=opacity, overlayImageUri=first?.uri.orEmpty(), overlayImageX=first?.x ?: 0.72f, overlayImageY=first?.y ?: -0.72f, overlayImageScale=first?.scale ?: 0.32f, overlayImageRotation=first?.rotation ?: 0f, overlayImageAlpha=first?.alpha ?: 1f))
    }
    AlertDialog(
        onDismissRequest=onDismiss,
        title={ Text(if(language==AppLanguage.ARABIC) "طبقات الصورة / PIP" else "Image / PIP Layers") },
        text={ Column(Modifier.verticalScroll(rememberScrollState()).fillMaxWidth(), verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                Button(onClick=onPickImage, modifier=Modifier.weight(1f)) { Icon(Icons.Default.AddPhotoAlternate,null); Spacer(Modifier.width(4.dp)); Text(if(language==AppLanguage.ARABIC) "إضافة PIP" else "Add PIP") }
                OutlinedButton(onClick=onAiCutout, modifier=Modifier.weight(1f)) { Icon(Icons.Default.AutoAwesome,null); Spacer(Modifier.width(4.dp)); Text(if(language==AppLanguage.ARABIC) "قص AI" else "AI Cutout") }
            }
            if (layers.isEmpty()) {
                Text(if(language==AppLanguage.ARABIC) "لا توجد طبقات. أضف صورة أو نتيجة قص AI." else "No PIP layers. Add an image or an AI cutout.", color=Color.Gray, fontSize=11.sp)
            } else {
                Text(if(language==AppLanguage.ARABIC) "ترتيب الطبقات — الأعلى يظهر فوق ما تحته" else "Layer order — higher rows render above lower rows", fontWeight=FontWeight.SemiBold, fontSize=12.sp)
                layers.forEachIndexed { index, layer ->
                    val active=index==selected
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if(active) Color(0xFF25203A) else Color(0xFF151922)).clickable{selected=index}.padding(6.dp), verticalAlignment=Alignment.CenterVertically) {
                        Text((index+1).toString(), color=if(active) Color(0xFFB88CFF) else Color.Gray, fontWeight=FontWeight.Bold, modifier=Modifier.width(22.dp))
                        Text("PIP " + (index+1), Modifier.weight(1f), maxLines=1, fontSize=11.sp)
                        IconButton(onClick={ if(index>0){ val n=layers.toMutableList(); val t=n[index-1]; n[index-1]=n[index]; n[index]=t; layers=n; selected=index-1 } }, enabled=index>0, modifier=Modifier.size(30.dp)){ Icon(Icons.Default.KeyboardArrowUp,null,Modifier.size(18.dp)) }
                        IconButton(onClick={ if(index<layers.lastIndex){ val n=layers.toMutableList(); val t=n[index+1]; n[index+1]=n[index]; n[index]=t; layers=n; selected=index+1 } }, enabled=index<layers.lastIndex, modifier=Modifier.size(30.dp)){ Icon(Icons.Default.KeyboardArrowDown,null,Modifier.size(18.dp)) }
                        IconButton(onClick={ layers=layers.filterIndexed{i,_->i!=index}; selected=(selected.coerceAtMost(layers.lastIndex)).coerceAtLeast(0) }, modifier=Modifier.size(30.dp)){ Icon(Icons.Default.DeleteOutline,null,Modifier.size(18.dp)) }
                    }
                }
                selectedLayer?.let { layer ->
                    HorizontalDivider()
                    Text(if(language==AppLanguage.ARABIC) "الطبقة المحددة" else "Selected layer", fontWeight=FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                        FilterChip(selected=layer.visible, onClick={editSelected{it.copy(visible=!it.visible)}}, label={Text(if(language==AppLanguage.ARABIC) if(layer.visible) "مرئية" else "مخفية" else if(layer.visible) "Visible" else "Hidden")}, leadingIcon={Icon(if(layer.visible) Icons.Default.Visibility else Icons.Default.VisibilityOff,null,Modifier.size(16.dp))})
                        AssistChip(onClick={ val copy=layer.copy(id=System.nanoTime().toString(), x=(layer.x+0.08f).coerceIn(-1f,1f), y=(layer.y+0.08f).coerceIn(-1f,1f)); layers=layers+copy; selected=layers.lastIndex }, label={Text(if(language==AppLanguage.ARABIC) "تكرار" else "Duplicate")}, leadingIcon={Icon(Icons.Default.ContentCopy,null,Modifier.size(16.dp))})
                    }
                    Text(if(language==AppLanguage.ARABIC) "الموضع الأفقي " + (layer.x*100).toInt() + "%" else "Horizontal " + (layer.x*100).toInt() + "%")
                    Slider(value = layer.x, onValueChange = { v -> editSelected { it.copy(x = v) } }, valueRange = -1f..1f)
                    Text(if(language==AppLanguage.ARABIC) "الموضع العمودي " + (layer.y*100).toInt() + "%" else "Vertical " + (layer.y*100).toInt() + "%")
                    Slider(value = layer.y, onValueChange = { v -> editSelected { it.copy(y = v) } }, valueRange = -1f..1f)
                    Text(if(language==AppLanguage.ARABIC) "الحجم " + (layer.scale*100).toInt() + "%" else "Scale " + (layer.scale*100).toInt() + "%")
                    Slider(value = layer.scale, onValueChange = { v -> editSelected { it.copy(scale = v) } }, valueRange = 0.08f..2f)
                    Text(if(language==AppLanguage.ARABIC) "الدوران " + layer.rotation.toInt() + "°" else "Rotation " + layer.rotation.toInt() + "°")
                    Slider(value = layer.rotation, onValueChange = { v -> editSelected { it.copy(rotation = v) } }, valueRange = -180f..180f)
                    Text(if(language==AppLanguage.ARABIC) "الشفافية " + (layer.alpha*100).toInt() + "%" else "Opacity " + (layer.alpha*100).toInt() + "%")
                    Slider(value = layer.alpha, onValueChange = { v -> editSelected { it.copy(alpha = v) } }, valueRange = 0.05f..1f)
                }
            }
            Text(if(language==AppLanguage.ARABIC) "طبقة لونية " + (s.overlayOpacity*100).toInt() + "%" else "Color overlay " + (s.overlayOpacity*100).toInt() + "%", fontSize=11.sp)
            Slider(value = s.overlayOpacity, onValueChange = { v -> commit(opacity = v) }, valueRange = 0f..0.75f)
            if(layers.isNotEmpty()) OutlinedButton(onClick={layers=emptyList();selected=0}, modifier=Modifier.fillMaxWidth()){ Icon(Icons.Default.DeleteSweep,null); Spacer(Modifier.width(5.dp)); Text(if(language==AppLanguage.ARABIC) "إزالة جميع طبقات PIP" else "Remove all PIP layers") }
        } },
        confirmButton={TextButton(onClick={commit();onDismiss()}){Text(if(language==AppLanguage.ARABIC) "تطبيق" else "Apply")}},
        dismissButton={TextButton(onClick=onDismiss){Text(if(language==AppLanguage.ARABIC) "إلغاء" else "Cancel")}}
    )
}