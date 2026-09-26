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
    musicVolume: Float,
    musicFadeIn: Float,
    musicFadeOut: Float,
    musicKeyframes: List<MusicKeyframe>,
    markers: List<TimelineMarker>,
    audioBaseVolume: Float,
    audioFadeIn: Float,
    audioFadeOut: Float,
    onAudioTrackClick: () -> Unit,
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

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Scrub bar with current / total time readout.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(formatTimelineTime(safePlayhead), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.weight(1f))
            Text(formatTimelineTime(total), color = Color.Gray, fontSize = 11.sp)
        }
        Slider(
            value = safePlayhead.toFloat(),
            onValueChange = { onPlayheadChange(it.toLong()) },
            valueRange = 0f..max(1L, total).toFloat(),
            modifier = Modifier.fillMaxWidth().height(20.dp)
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("0:00", color = MutedTextColor, fontSize = 7.sp)
            Text(formatTimelineTime(total / 4), color = MutedTextColor, fontSize = 7.sp)
            Text(formatTimelineTime(total / 2), color = MutedTextColor, fontSize = 7.sp)
            Text(formatTimelineTime((total * 3) / 4), color = MutedTextColor, fontSize = 7.sp)
            Text(formatTimelineTime(total), color = MutedTextColor, fontSize = 7.sp)
        }

        // Thin strip above the video track showing where text/graphic layers sit, like the
        // colored title bars in professional NLEs. Layers currently span the whole project
        // (no per-layer time range in the data model yet), so each gets an equal-width chip.
        if (textLayerNames.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                textLayerNames.forEachIndexed { i, name ->
                    Box(
                        Modifier
                            .height(20.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(TextLayerColors[i % TextLayerColors.size])
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(name, color = Color.White, fontSize = 8.sp, maxLines = 1)
                    }
                }
            }
        }

        // Video / image track — compact proportional clip blocks with real thumbnails. The
        // selected clip grows drag handles on both edges so you can trim it directly in place
        // by pulling the edges in or out, instead of separate trim buttons.
        Text("Video", color = TrackLabelVideo, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 2.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(TrackVideoBg)
                .horizontalScroll(rememberScrollState())
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            clips.forEach { clip ->
                val selected = clip == current
                val durationSec = (timelineClipDurationForUi(clip) / 1000f).coerceAtLeast(0.3f)
                val blockWidth = (54f + durationSec * 14f).coerceIn(64f, 220f).dp
                val clipStart = clip.trimStartMs
                val clipEnd = if (clip.trimEndMs == Long.MAX_VALUE) clip.durationMs else clip.trimEndMs
                Box(
                    Modifier
                        .width(blockWidth)
                        .height(56.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (clip.isFreezeFrame) ClipImageColor else ClipDefaultColor)
                        .then(if (selected) Modifier.border(1.5.dp, Color.White, RoundedCornerShape(9.dp)) else Modifier)
                        .clickable { onSelect(clip) }
                ) {
                    // Real frame/photo thumbnail behind the label, like a professional NLE.
                    ClipFilmstrip(clip.uri, Modifier.fillMaxSize())
                    // Scrim so the label stays readable over any thumbnail content.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                    )
                    if (selected) {
                        Box(Modifier.fillMaxSize().background(ClipSelectedColor.copy(alpha = 0.22f)))
                    }
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(
                                horizontal = if (selected) 18.dp else 7.dp,
                                vertical = 5.dp
                            ),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (clip.isFreezeFrame) Icons.Default.ImageIcon else Icons.Default.Videocam,
                                null, tint = Color.White, modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(clip.name, maxLines = 1, fontSize = 9.sp, color = Color.White, modifier = Modifier.weight(1f))
                        }
                        Text(formatTimelineTime(timelineClipDurationForUi(clip)), color = Color(0xFFE4E9F2), fontSize = 8.sp)
                        if (selected) {
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                IconButton(onClick = { onSplit(clip) }, modifier = Modifier.size(18.dp)) {
                                    Icon(Icons.Default.ContentCut, null, tint = Color.White, modifier = Modifier.size(11.dp))
                                }
                                IconButton(onClick = { onMoveLeft(clip) }, modifier = Modifier.size(18.dp)) {
                                    Text("‹", color = Color.White, fontSize = 12.sp)
                                }
                                IconButton(onClick = { onMoveRight(clip) }, modifier = Modifier.size(18.dp)) {
                                    Text("›", color = Color.White, fontSize = 12.sp)
                                }
                                IconButton(onClick = { onDelete(clip) }, modifier = Modifier.size(18.dp)) {
                                    Icon(Icons.Default.Close, null, tint = Color(0xFFFF8A80), modifier = Modifier.size(11.dp))
                                }
                            }
                        }
                    }
                    if (selected) {
                        TrimHandle(Alignment.CenterStart) { deltaMs ->
                            val newStart = (clipStart + deltaMs).coerceIn(0L, clipEnd - MIN_CLIP_DURATION_MS)
                            onTrimEdges(clip, newStart, clipEnd)
                        }
                        TrimHandle(Alignment.CenterEnd) { deltaMs ->
                            val newEnd = (clipEnd + deltaMs).coerceIn(clipStart + MIN_CLIP_DURATION_MS, clip.durationMs)
                            onTrimEdges(clip, clipStart, newEnd)
                        }
                    }
                }
            }
            // Add media (+) — always at the end of the video track.
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .border(1.dp, ClipSelectedColor, RoundedCornerShape(9.dp))
                    .clickable { onAddMedia() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Add, null, tint = ClipSelectedColor, modifier = Modifier.size(22.dp))
            }
        }

        // Text / graphics lane — directly under the video filmstrip.
        if (textLayerNames.isNotEmpty()) {
            Text("Text", color = Color(0xFFB58CFF), fontSize = 9.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 2.dp, top = 2.dp))
            Row(
                Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF151225)).horizontalScroll(rememberScrollState())
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                textLayerNames.forEachIndexed { i, name ->
                    Box(
                        Modifier.width(120.dp).fillMaxHeight().clip(RoundedCornerShape(6.dp))
                            .background(TextLayerColors[i % TextLayerColors.size]),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text("T  $name", color = Color.White, fontSize = 8.sp, maxLines = 1, modifier = Modifier.padding(horizontal = 8.dp))
                    }
                }
            }
        }

        // Effects lane reflects the actual filter state.
        Text("Effects", color = Color(0xFFFFB56B), fontSize = 9.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 2.dp, top = 2.dp))
        Row(
            Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF19140E)).padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.fillMaxWidth().height(20.dp).clip(RoundedCornerShape(5.dp))
                    .background(if (filterName == "none") Color(0xFF29241D) else Color(0xFF9A5A22)),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    if (filterName == "none") "No filter" else "Filter • $filterName",
                    color = Color.White, fontSize = 8.sp, maxLines = 1,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
        }

        // Audio / music track — visually distinct from the video track.
        Text("Audio", color = TrackLabelAudio, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 2.dp, top = 2.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(AudioTrackBg)
                .clickable { onAudioTrackClick() }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.MusicNote, null, tint = TrackLabelAudio, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(7.dp))
            if (musicUri.isNotBlank()) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Music", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text("${formatTimelineTime(musicStartMs)} → ${formatTimelineTime(musicStartMs + musicDurationMs)}", color = TrackLabelAudio, fontSize = 8.sp)
                    }
                    Box(Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFF241D10))) {
                        val activeEnd = (musicStartMs + musicDurationMs).coerceAtMost(total)
                        val activeStart = musicStartMs.coerceIn(0L, total)
                        val left = (activeStart.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                        val width = ((activeEnd - activeStart).coerceAtLeast(1L).toFloat() / total.toFloat()).coerceIn(0.01f, 1f)
                        Row(Modifier.fillMaxSize()) {
                            Spacer(Modifier.fillMaxHeight().weight(left.coerceAtLeast(0.001f)))
                            Box(Modifier.fillMaxHeight().weight(width.coerceAtLeast(0.001f)).clip(RoundedCornerShape(5.dp)).background(Color(0xFF8E6A2F)))
                            Spacer(Modifier.fillMaxHeight().weight((1f - left - width).coerceAtLeast(0.001f)))
                        }
                    }
                }
                Text("${(musicVolume * 100).toInt()}%", color = Color(0xFFCBB27A), fontSize = 8.sp, modifier = Modifier.padding(start = 6.dp))
            } else if (clips.isNotEmpty()) {
                Text("Clip audio • ${(audioBaseVolume * 100).toInt()}%", color = Color(0xFFB7C0D0), fontSize = 10.sp, modifier = Modifier.weight(1f))
            } else {
                Text("No audio yet", color = MutedTextColor, fontSize = 10.sp, modifier = Modifier.weight(1f))
            }
        }

        // Volume automation track — draws the combined audio-keyframe curve across the whole
        // timeline as a filled line chart, like the green "Volume" row in professional NLEs.
        Text("Volume", color = TrackLabelVolume, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 2.dp, top = 2.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(34.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(VolumeTrackBg)
        ) {
            val w = size.width
            val h = size.height
            val points = (audioKeyframes.map { it.timeMs to it.volume } +
                listOf(0L to (audioKeyframes.minByOrNull { it.timeMs }?.volume ?: audioBaseVolume)) +
                listOf(total to (audioKeyframes.maxByOrNull { it.timeMs }?.volume ?: audioBaseVolume)))
                .distinctBy { it.first }
                .sortedBy { it.first }
            if (points.size >= 2) {
                val line = Path()
                points.forEachIndexed { i, (t, v) ->
                    val x = (t.toFloat() / total.toFloat()).coerceIn(0f, 1f) * w
                    val y = h - (v.coerceIn(0f, 2f) / 2f) * h
                    if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
                }
                val fill = Path().apply {
                    addPath(line)
                    lineTo(w, h)
                    lineTo(0f, h)
                    close()
                }
                drawPath(fill, color = VolumeLineColor.copy(alpha = 0.22f))
                drawPath(line, color = VolumeLineColor, style = Stroke(width = 2.5f))
            }
        }

        // Motion keyframe markers for the selected clip's video track (only shown when present).
        if (videoKeyframes.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                videoKeyframes.sortedBy { it.timeMs }.forEach { key ->
                    AssistChip(onClick = { onKeyframeSeek(key.timeMs) }, label = { Text(formatTimelineTime(key.timeMs), fontSize = 8.sp) })
                }
            }
        }

        // Compact stats row — keeps every keyframe/marker param meaningfully surfaced.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("V${videoKeyframes.size} · T${textKeyframes.size} · A${audioKeyframes.size}", fontSize = 8.sp, color = MutedTextColor)
            Text("S${speedKeyframes.size} · M${musicKeyframes.size} · ${markers.size}⚑", fontSize = 8.sp, color = MutedTextColor)
        }
    }
}