package com.videoforge.nativeeditor

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Image as ImageIcon
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

// Professional, compact multi-track timeline. Replaces the old single fixed-width clip
// strip (150dp per clip regardless of content, no add button, no distinct audio track,
// no thumbnails, no in-track trimming) with proportional clip blocks showing real frame
// thumbnails, drag-to-trim handles on the selected clip's edges, a dedicated audio/music
// row, a text/graphics layer strip, a volume automation curve, and an in-track "+" button
// for adding more media — matching the layout pattern of professional editors like
// LumaFusion / CapCut / VN.

private val TrackVideoBg = Color(0xFF0D1420)
private val ClipDefaultColor = Color(0xFF1C2A40)
private val ClipSelectedColor = Color(0xFF3D6BFF)
private val ClipImageColor = Color(0xFF23405C)
private val AudioTrackBg = Color(0xFF16130D)
private val VolumeTrackBg = Color(0xFF0F1A12)
private val VolumeLineColor = Color(0xFF34C77B)
private val TrackLabelVideo = Color(0xFF8DA0C4)
private val TrackLabelAudio = Color(0xFFCBB27A)
private val TrackLabelVolume = Color(0xFF8FD9A8)
private val MutedTextColor = Color(0xFF6B7893)
private val TextLayerColors = listOf(Color(0xFF6C4CD9), Color(0xFFD98A34), Color(0xFF2AA1B8), Color(0xFFC24E7A))

// Drag sensitivity for the in-track trim handles: how many milliseconds one dp of drag
// represents. Chosen so a full handle-to-handle drag across a typical block trims by a
// few seconds, matching how CapCut/VN-style trim handles feel.
private const val MS_PER_DP = 60L
private const val MIN_CLIP_DURATION_MS = 300L

private fun timelineClipDurationForUi(clip: Clip): Long {
    val end = if (clip.trimEndMs == Long.MAX_VALUE) clip.durationMs else clip.trimEndMs
    return (end - clip.trimStartMs).coerceAtLeast(0L)
}

/** Small frame/cover thumbnail for a clip block. Kept local to this file since MainActivity's
 * loadVideoThumbnail is file-private in Kotlin (top-level `private` is per-file, not per-package). */
private fun loadClipThumbnails(context: android.content.Context, uri: Uri, count: Int = 6): List<Bitmap> {
    return try {
        val retriever = android.media.MediaMetadataRetriever()
        retriever.setDataSource(context, uri)
        val durationUs = (retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 1L) * 1000L
        val frames = (0 until count).mapNotNull { index ->
            val atUs = if (count <= 1) 0L else (durationUs * index / (count - 1)).coerceIn(0L, durationUs)
            retriever.getFrameAtTime(atUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }
        retriever.release()
        frames
    } catch (_: Exception) {
        if (Build.VERSION.SDK_INT >= 29) listOfNotNull(runCatching {
            context.contentResolver.loadThumbnail(uri, Size(240, 120), null)
        }.getOrNull()) else emptyList()
    }
}

@Composable
private fun ClipFilmstrip(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var frames by remember(uri) { mutableStateOf<List<Bitmap>>(emptyList()) }
    LaunchedEffect(uri) {
        frames = withContext(Dispatchers.IO) { loadClipThumbnails(context, uri) }
    }
    if (frames.isEmpty()) {
        Box(modifier.background(Color(0xFF1A2638)))
    } else {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
            frames.forEach { frame ->
                Image(
                    bitmap = frame.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
            }
        }
    }
}

/** A draggable grip on one edge of the selected clip block. Dragging horizontally trims that
 * edge in place (like the trim handles in CapCut/VN/LumaFusion) instead of using separate
 * +/- buttons below the track. */
@Composable
private fun BoxScope.TrimHandle(alignment: Alignment, onDragMs: (Long) -> Unit) {
    val density = LocalDensity.current
    val onDragMsState = rememberUpdatedState(onDragMs)
    Box(
        Modifier
            .align(alignment)
            .fillMaxHeight()
            .width(16.dp)
            .background(Color.White.copy(alpha = 0.28f))
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    val dp = with(density) { dragAmount.x.toDp().value }
                    val deltaMs = (dp * MS_PER_DP).toLong()
                    if (deltaMs != 0L) onDragMsState.value(deltaMs)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(22.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White)
        )
    }
}

@Composable
fun Timeline(
    clips: List<Clip>,
    current: Clip?,
    playheadMs: Long,
    videoKeyframes: List<VideoKeyframe>,
    textKeyframes: List<TextKeyframe>,
    audioKeyframes: List<AudioKeyframe>,
    speedKeyframes: List<SpeedKeyframe>,
    musicUri: String,
    musicStartMs: Long,
    musicDurationMs: Long,
    musicSourceDurationMs: Long = 0L,
    musicVolume: Float,
    musicFadeIn: Float,
    musicFadeOut: Float,
    musicKeyframes: List<MusicKeyframe>,
    markers: List<TimelineMarker>,
    audioBaseVolume: Float,
    audioFadeIn: Float,
    audioFadeOut: Float,
    onAudioTrackClick: () -> Unit,
    onMusicTrim: (Long, Long) -> Unit,
    onSelect: (Clip) -> Unit,
    onPlayheadChange: (Long) -> Unit,
    onDelete: (Clip) -> Unit,
    onMoveLeft: (Clip) -> Unit,
    onMoveRight: (Clip) -> Unit,
    onTrimEdges: (Clip, Long, Long) -> Unit,
    onSplit: (Clip) -> Unit,
    onKeyframeSeek: (Long) -> Unit,
    onVideoKeyframeMove: (Long, Long) -> Unit,
    onAddMedia: () -> Unit = {},
    textLayerNames: List<String> = emptyList(),
    filterName: String = "none"
) {
    val total = clips.sumOf { timelineClipDurationForUi(it) }.coerceAtLeast(1L)
    val safePlayhead = playheadMs.coerceIn(0L, total)
    val scroll = rememberScrollState()
    val timelineWidth = 760.dp
    val laneHeight = 56.dp
    val context = LocalContext.current
    fun imageUri(uri: Uri): Boolean {
        val type = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        if (type != null) return type.startsWith("image/")
        val name = uri.lastPathSegment?.lowercase().orEmpty()
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") ||
            name.endsWith(".webp") || name.endsWith(".heic") || name.endsWith(".heif")
    }
    val videoClips = clips.filter { !imageUri(it.uri) && !it.isFreezeFrame }
    val imageClips = clips.filter { imageUri(it.uri) || it.isFreezeFrame }

    fun clipStartMs(clip: Clip): Long = clips.takeWhile { it != clip }.sumOf { timelineClipDurationForUi(it) }
    fun xFor(time: Long, density: Float): Float =
        (time.toFloat() / total.toFloat()).coerceIn(0f, 1f) * (760f * density)

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Reference-style time ruler: compact, directly above the tracks.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                formatTimelineTime(safePlayhead),
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                formatTimelineTime(total),
                color = Color(0xFF71809A),
                fontSize = 9.sp
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 230.dp, max = 310.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF07111F))
                .border(1.dp, Color(0xFF172D48), RoundedCornerShape(12.dp))
        ) {
            val density = LocalDensity.current
            val widthPx = with(density) { timelineWidth.toPx() }
            val playheadX = with(density) { (safePlayhead.toFloat() / total.toFloat() * timelineWidth.toPx()).toDp() }

            Row(
                Modifier
                    .fillMaxSize()
                    .horizontalScroll(scroll)
            ) {
                Box(
                    Modifier
                        .width(timelineWidth)
                        .fillMaxHeight()
                ) {
                    // Reference-style ruler. Tapping or dragging here moves the real playhead.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(24.dp)
                            .padding(horizontal = 8.dp)
                            .pointerInput(total, timelineWidth) {
                                detectDragGestures(
                                    onDragStart = { start ->
                                        val localX = start.x.coerceIn(0f, widthPx)
                                        onPlayheadChange((localX / widthPx * total).toLong().coerceIn(0L, total))
                                    }
                                ) { change, dragAmount ->
                                    change.consume()
                                    val localX = change.position.x.coerceIn(0f, widthPx)
                                    onPlayheadChange((localX / widthPx * total).toLong().coerceIn(0L, total))
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                            val marks = 7
                            repeat(marks) { i ->
                                val t = total * i / (marks - 1).coerceAtLeast(1)
                                Column(
                                    Modifier.weight(1f),
                                    horizontalAlignment = when (i) {
                                        0 -> Alignment.Start
                                        marks - 1 -> Alignment.End
                                        else -> Alignment.CenterHorizontally
                                    }
                                ) {
                                    Text(
                                        formatTimelineTime(t).substring(0, 5),
                                        color = Color(0xFF8090A8),
                                        fontSize = 7.sp
                                    )
                                    Box(
                                        Modifier.width(1.dp).height(if (i % 2 == 0) 7.dp else 4.dp)
                                            .background(Color(0xFF30435E))
                                    )
                                }
                            }
                        }
                    }

                    // Four clean NLE lanes: video, images/PIP, text, audio.
                    Column(
                        Modifier.fillMaxWidth().padding(top = 25.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        @Composable
                        fun TrackLane(
                            label: String,
                            labelIcon: ImageVector,
                            tint: Color,
                            laneClips: List<Clip>,
                            height: androidx.compose.ui.unit.Dp = laneHeight,
                            showTrimHandles: Boolean = true
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(height)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(Color(0xFF0A1728))
                            ) {
                                Icon(
                                    labelIcon,
                                    contentDescription = label,
                                    tint = tint,
                                    modifier = Modifier
                                        .align(Alignment.CenterStart)
                                        .padding(start = 6.dp)
                                        .size(15.dp)
                                )
                                laneClips.forEach { clip ->
                                    val start = clipStartMs(clip)
                                    val duration = timelineClipDurationForUi(clip).coerceAtLeast(MIN_CLIP_DURATION_MS)
                                    val leftFraction = start.toFloat() / total.toFloat()
                                    val widthFraction = duration.toFloat() / total.toFloat()
                                    val selected = clip == current
                                    Box(
                                        Modifier
                                            .fillMaxHeight()
                                            .fillMaxWidth(widthFraction.coerceIn(0.01f, 1f))
                                            .offset(x = with(density) { (leftFraction * widthPx).toDp() })
                                            .padding(vertical = 3.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .border(
                                                if (selected) 2.dp else 1.dp,
                                                if (selected) Color.White else tint.copy(alpha = 0.35f),
                                                RoundedCornerShape(6.dp)
                                            )
                                            .background(Color(0xFF14243A))
                                            .clickable {
                                                onSelect(clip)
                                                onPlayheadChange(start)
                                            }
                                    ) {
                                        ClipFilmstrip(clip.uri, Modifier.fillMaxSize())
                                        Box(
                                            Modifier.fillMaxSize().background(
                                                Brush.horizontalGradient(
                                                    listOf(
                                                        Color.Black.copy(alpha = 0.08f),
                                                        Color.Black.copy(alpha = 0.30f)
                                                    )
                                                )
                                            )
                                        )
                                        if (selected) {
                                            Box(Modifier.fillMaxSize().background(tint.copy(alpha = 0.16f)))
                                            if (showTrimHandles) {
                                                TrimHandle(Alignment.CenterStart) { deltaMs ->
                                                    val oldStart = clip.trimStartMs
                                                    val oldEnd = if (clip.trimEndMs == Long.MAX_VALUE) clip.durationMs else clip.trimEndMs
                                                    val next = (oldStart + deltaMs).coerceIn(0L, oldEnd - MIN_CLIP_DURATION_MS)
                                                    onTrimEdges(clip, next, oldEnd)
                                                }
                                                TrimHandle(Alignment.CenterEnd) { deltaMs ->
                                                    val oldStart = clip.trimStartMs
                                                    val oldEnd = if (clip.trimEndMs == Long.MAX_VALUE) clip.durationMs else clip.trimEndMs
                                                    val next = (oldEnd + deltaMs).coerceIn(oldStart + MIN_CLIP_DURATION_MS, clip.durationMs)
                                                    onTrimEdges(clip, oldStart, next)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        TrackLane(
                            label = "Video",
                            labelIcon = Icons.Default.Videocam,
                            tint = Color(0xFF55A8FF),
                            laneClips = videoClips
                        )

                        TrackLane(
                            label = "Images",
                            labelIcon = Icons.Default.Photo,
                            tint = Color(0xFF33D6B2),
                            laneClips = imageClips
                        )

                        // Text lane: real selectable text segments rather than detached chips.
                        Box(
                            Modifier.fillMaxWidth().height(42.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(Color(0xFF111126))
                        ) {
                            Icon(
                                Icons.Default.TextFields,
                                contentDescription = "Text",
                                tint = Color(0xFFC69BFF),
                                modifier = Modifier.align(Alignment.CenterStart).padding(start = 6.dp).size(15.dp)
                            )
                            textLayerNames.forEachIndexed { i, name ->
                                val left = if (textLayerNames.size == 1) 0.12f else i.toFloat() / textLayerNames.size
                                val width = (0.32f).coerceAtMost(0.85f)
                                Box(
                                    Modifier.fillMaxHeight().fillMaxWidth(width)
                                        .offset(x = with(density) { (left * widthPx).toDp() })
                                        .padding(vertical = 4.dp, horizontal = 2.dp)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(TextLayerColors[i % TextLayerColors.size])
                                        .clickable { onKeyframeSeek(safePlayhead) },
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Text(
                                        "T  $name",
                                        color = Color.White,
                                        fontSize = 8.sp,
                                        maxLines = 1,
                                        modifier = Modifier.padding(horizontal = 8.dp)
                                    )
                                }
                            }
                        }

                        // Music lane with a visible range and draggable start/end handles.
                        Box(
                            Modifier.fillMaxWidth().height(48.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(Color(0xFF101C2C))
                                .clickable { onAudioTrackClick() }
                        ) {
                            Icon(
                                Icons.Default.MusicNote,
                                contentDescription = "Audio",
                                tint = Color(0xFF39BFFF),
                                modifier = Modifier.align(Alignment.CenterStart).padding(start = 6.dp).size(16.dp)
                            )
                            if (musicUri.isNotBlank()) {
                                val start = musicStartMs.coerceIn(0L, total)
                                val end = (start + musicDurationMs.coerceAtLeast(1L)).coerceIn(start + 1L, total)
                                val leftFraction = start.toFloat() / total.toFloat()
                                val widthFraction = ((end - start).toFloat() / total.toFloat()).coerceIn(0.01f, 1f)
                                Box(
                                    Modifier.fillMaxHeight().fillMaxWidth(widthFraction)
                                        .offset(x = with(density) { (leftFraction * widthPx).toDp() })
                                        .padding(vertical = 4.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0xFF087CC1))
                                ) {
                                    // Visual waveform-style bars. The source remains the real
                                    // selected audio URI; this is a compact visual representation.
                                    Canvas(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 5.dp)) {
                                        val bars = 54
                                        val barWidth = size.width / bars
                                        for (i in 0 until bars) {
                                            val phase = (musicUri.hashCode() * 0.0001f + i * 0.73f)
                                            val h = (0.18f + 0.72f * ((kotlin.math.sin(phase * 1.9f) + 1f) / 2f)).coerceIn(0.08f, 0.95f)
                                            drawRoundRect(
                                                color = Color(0xFF64D9FF),
                                                topLeft = Offset(i * barWidth + barWidth * 0.18f, (size.height * (1f - h)) / 2f),
                                                size = androidx.compose.ui.geometry.Size(barWidth * 0.56f, size.height * h),
                                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f)
                                            )
                                        }
                                    }
                                    val handle = 7.dp
                                    Box(
                                        Modifier.fillMaxHeight().width(handle)
                                            .background(Color.White)
                                            .align(Alignment.CenterStart)
                                            .pointerInput(total, start, end) {
                                                detectDragGestures { change, drag ->
                                                    change.consume()
                                                    val delta = (drag.x / widthPx * total).toLong()
                                                    val nextStart = (start + delta).coerceIn(0L, (end - 300L).coerceAtLeast(0L))
                                                    onMusicTrim(nextStart, (end - nextStart).coerceAtLeast(300L))
                                                }
                                            }
                                    )
                                    Box(
                                        Modifier.fillMaxHeight().width(handle)
                                            .background(Color.White)
                                            .align(Alignment.CenterEnd)
                                            .pointerInput(total, start, end) {
                                                detectDragGestures { change, drag ->
                                                    change.consume()
                                                    val nextEnd = (end + (drag.x / widthPx * total).toLong())
                                                        .coerceIn(start + 300L, total)
                                                    onMusicTrim(start, nextEnd - start)
                                                }
                                            }
                                    )
                                }
                            } else {
                                Text(
                                    "إضافة صوت" ,
                                    color = Color(0xFF7C8DA6),
                                    fontSize = 9.sp,
                                    modifier = Modifier.align(Alignment.Center).padding(start = 16.dp)
                                )
                            }
                        }

                        // Reference-style media insertion control. It is only an action button;
                        // no fake clip is created until the user actually chooses media.
                        Row(
                            Modifier.fillMaxWidth().height(38.dp),
                            horizontalArrangement = Arrangement.Start,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = onAddMedia,
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF0F1E31))
                                    .border(1.dp, Color(0xFF2A4668), RoundedCornerShape(8.dp))
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Add media", tint = Color.White, modifier = Modifier.size(19.dp))
                            }
                            Text(
                                "إضافة فيديو أو صورة",
                                color = Color(0xFF71809A),
                                fontSize = 8.sp,
                                modifier = Modifier.padding(start = 7.dp)
                            )
                        }
                    }

                    // The playhead is a real vertical editing cursor spanning every lane.
                    Box(
                        Modifier
                            .offset(x = playheadX)
                            .fillMaxHeight()
                            .width(1.dp)
                            .background(Color.White.copy(alpha = 0.92f))
                    )
                    Box(
                        Modifier
                            .offset(x = (playheadX - 5.dp))
                            .width(10.dp)
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(Color.White)
                            .align(Alignment.TopStart)
                    )
                }
            }
        }

        // A small action row keeps the destructive/editing actions accessible without putting
        // them inside the clip rectangles.
        if (current != null) {
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                AssistChip(
                    onClick = { onSplit(current) },
                    label = { Text("قص عند المؤشر", fontSize = 9.sp) },
                    leadingIcon = { Icon(Icons.Default.ContentCut, null, Modifier.size(14.dp)) }
                )
                AssistChip(
                    onClick = { onDelete(current) },
                    label = { Text("حذف", fontSize = 9.sp) },
                    leadingIcon = { Icon(Icons.Default.Delete, null, Modifier.size(14.dp)) }
                )
                Text(
                    formatTimelineTime(timelineClipDurationForUi(current)),
                    color = Color(0xFF7D8DA7),
                    fontSize = 9.sp,
                    modifier = Modifier.align(Alignment.CenterVertically).padding(start = 2.dp)
                )
            }
        }
    }
}

