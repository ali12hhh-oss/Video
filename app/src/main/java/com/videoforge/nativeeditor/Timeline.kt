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
    musicTimelineStartMs: Long = 0L,
    musicTrackIndex: Int = 0,
    audioTracks: List<AudioTrack> = emptyList(),
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
    onAddAudio: () -> Unit = {},
    onMoveAudioTrack: (AudioTrack, Long, Int) -> Unit = { _, _, _ -> },
    onTrimAudioTrack: (AudioTrack, Long, Long) -> Unit = { _, _, _ -> },
    onMoveMusicTrack: (Long, Int) -> Unit = { _, _ -> },
    onAddText: () -> Unit = {},
    onMoveClip: (Clip, Long, Int) -> Unit = { _, _, _ -> },
    onTextTrim: (TextLayer, Long, Long) -> Unit = { _, _, _ -> },
    textLayers: List<TextLayer> = emptyList(),
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
    val legacySequential = clips.isNotEmpty() && clips.all { it.timelineStartMs == 0L && it.trackIndex == 0 }
    fun clipStartMs(clip: Clip): Long {
        if (!legacySequential) return clip.timelineStartMs.coerceAtLeast(0L)
        var position = 0L
        for (item in clips) {
            if (item == clip) return position
            position += timelineClipDurationForUi(item)
        }
        return 0L
    }
    val visualTracks = clips.groupBy { if (legacySequential) 0 else it.trackIndex.coerceAtLeast(0) }.toSortedMap()
    fun trackTint(index: Int): Color = when (index % 4) {
        0 -> Color(0xFF55A8FF)
        1 -> Color(0xFF9B6BFF)
        2 -> Color(0xFF33D6B2)
        else -> Color(0xFFFF9B5C)
    }

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
            val addRail = 40.dp
            val contentWidthPx = widthPx - with(density) { addRail.toPx() }
            val playheadX = with(density) { (safePlayhead.toFloat() / total.toFloat() * contentWidthPx).toDp() }

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
                    // Fine reference-style ruler.
                    Row(
                        Modifier.fillMaxWidth().height(24.dp).padding(start = 8.dp, end = 48.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
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

                    // Four clean NLE lanes: video, images/PIP, text, audio.
                    Column(
                        Modifier.fillMaxWidth().padding(top = 25.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        @Composable
                        fun TrackLane(trackIndex: Int, laneClips: List<Clip>, height: androidx.compose.ui.unit.Dp = laneHeight) {
                            val tint = trackTint(trackIndex)
                            Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(7.dp)).background(Color(0xFF0A1728))) {
                                Row(Modifier.align(Alignment.CenterStart).padding(start=6.dp), verticalAlignment=Alignment.CenterVertically) {
                                    Icon(Icons.Default.Videocam, contentDescription=null, tint=tint, modifier=Modifier.size(15.dp))
                                    Text(if(trackIndex==0) "V1" else "V${trackIndex+1}", color=Color(0xFF9EB0CA), fontSize=7.sp, fontWeight=FontWeight.Bold, modifier=Modifier.padding(start=3.dp))
                                }
                                laneClips.sortedBy { clipStartMs(it) }.forEach { clip ->
                                    val start = clipStartMs(clip)
                                    val duration = timelineClipDurationForUi(clip).coerceAtLeast(MIN_CLIP_DURATION_MS)
                                    val leftFraction=(start.toFloat()/total.toFloat()).coerceIn(0f,1f)
                                    val widthFraction=(duration.toFloat()/total.toFloat()).coerceIn(0.008f,1f)
                                    val selected=clip==current
                                    val mediaIsImage=imageUri(clip.uri)||clip.isFreezeFrame
                                    val blockTint=if(mediaIsImage) Color(0xFF33D6B2) else tint
                                    Box(
                                        Modifier.fillMaxHeight().fillMaxWidth(widthFraction)
                                            .offset(x=with(density){(leftFraction*contentWidthPx).toDp()})
                                            .padding(vertical=3.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .border(if(selected)2.dp else 1.dp,if(selected)Color.White else blockTint.copy(alpha=.42f),RoundedCornerShape(6.dp))
                                            .background(Color(0xFF14243A))
                                            .clickable{onSelect(clip);onPlayheadChange(start)}
                                            .pointerInput(clip,total,trackIndex){
                                                var movedMs=0L
                                                var movedTrack=0
                                                detectDragGestures(onDragStart={movedMs=0L;movedTrack=0;onSelect(clip);onPlayheadChange(start)}){change,dragAmount->
                                                    change.consume()
                                                    movedMs += (dragAmount.x/contentWidthPx*total.toFloat()).toLong()
                                                    movedTrack += (dragAmount.y/with(density){(laneHeight+5.dp).toPx()}).toInt()
                                                    onMoveClip(clip,(start+movedMs).coerceAtLeast(0L),(trackIndex+movedTrack).coerceAtLeast(0))
                                                }
                                            }
                                    ){
                                        ClipFilmstrip(clip.uri,Modifier.fillMaxSize())
                                        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha=.08f),Color.Black.copy(alpha=.30f)))))
                                        Box(Modifier.fillMaxSize().background(blockTint.copy(alpha=if(selected).18f else .04f)))
                                        if(selected){
                                            Text(if(mediaIsImage)"صورة" else "فيديو",color=Color.White.copy(alpha=.9f),fontSize=7.sp,fontWeight=FontWeight.Bold,modifier=Modifier.align(Alignment.Center).background(Color.Black.copy(alpha=.35f),RoundedCornerShape(4.dp)).padding(horizontal=4.dp,vertical=2.dp))
                                            TrimHandle(Alignment.CenterStart){deltaMs->
                                                val oldStart=clip.trimStartMs
                                                val oldEnd=if(clip.trimEndMs==Long.MAX_VALUE)clip.durationMs else clip.trimEndMs
                                                onTrimEdges(clip,(oldStart+deltaMs).coerceIn(0L,oldEnd-MIN_CLIP_DURATION_MS),oldEnd)
                                            }
                                            TrimHandle(Alignment.CenterEnd){deltaMs->
                                                val oldStart=clip.trimStartMs
                                                val oldEnd=if(clip.trimEndMs==Long.MAX_VALUE)clip.durationMs else clip.trimEndMs
                                                onTrimEdges(clip,oldStart,(oldEnd+deltaMs).coerceIn(oldStart+MIN_CLIP_DURATION_MS,clip.durationMs))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        visualTracks.forEach { (trackIndex,laneClips) ->
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                                Box(Modifier.weight(1f)){TrackLane(trackIndex,laneClips)}
                                IconButton(onClick=onAddMedia,modifier=Modifier.size(36.dp)){Icon(Icons.Default.Add,contentDescription="Add media",tint=Color.White)}
                            }
                        }

                        // Text lane: real selectable text segments rather than detached chips.
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.weight(1f).height(42.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(Color(0xFF111126))
                        ) {
                            Icon(
                                Icons.Default.TextFields,
                                contentDescription = "Text",
                                tint = Color(0xFFC69BFF),
                                modifier = Modifier.align(Alignment.CenterStart).padding(start = 6.dp).size(15.dp)
                            )
                            textLayers.forEachIndexed { i, layer ->
                                val left = (layer.startMs.coerceAtLeast(0L).toFloat() / total.toFloat()).coerceIn(0f, 1f)
                                val width = ((layer.endMs - layer.startMs).coerceAtLeast(300L).toFloat() / total.toFloat()).coerceIn(0.02f, 1f)
                                Box(
                                    Modifier.fillMaxHeight().fillMaxWidth(width)
                                        .offset(x = with(density) { (left * contentWidthPx).toDp() })
                                        .padding(vertical = 4.dp, horizontal = 2.dp)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(TextLayerColors[i % TextLayerColors.size])
                                        .clickable { onKeyframeSeek(safePlayhead) },
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Text(
                                        "T  ${layer.name.ifBlank { layer.text }}",
                                        color = Color.White,
                                        fontSize = 8.sp,
                                        maxLines = 1,
                                        modifier = Modifier.padding(horizontal = 8.dp)
                                    )
                                    TrimHandle(Alignment.CenterStart) { deltaMs ->
                                        val nextStart = (layer.startMs + deltaMs).coerceIn(0L, layer.endMs - 300L)
                                        onTextTrim(layer, nextStart, layer.endMs)
                                    }
                                    TrimHandle(Alignment.CenterEnd) { deltaMs ->
                                        val nextEnd = (layer.endMs + deltaMs).coerceIn(layer.startMs + 300L, total)
                                        onTextTrim(layer, layer.startMs, nextEnd)
                                    }
                                }
                            }
                        }
                        }

                        // Audio lanes are real timeline tracks. They stay hidden until audio exists.
                        if (musicUri.isNotBlank()) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
                                Box(
                                    Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(7.dp))
                                        .background(Color(0xFF101C2C)).clickable { onAudioTrackClick() }
                                ) {
                                    Icon(Icons.Default.MusicNote, contentDescription="Audio", tint=Color(0xFF39BFFF), modifier=Modifier.align(Alignment.CenterStart).padding(start=6.dp).size(16.dp))
                                    val start = musicTimelineStartMs.coerceAtLeast(0L)
                                    val duration = musicDurationMs.coerceAtLeast(1L)
                                    val widthFraction=(duration.toFloat()/total.toFloat()).coerceIn(0.01f,1f)
                                    Box(
                                        Modifier.fillMaxHeight().fillMaxWidth(widthFraction)
                                            .offset(x=with(density){(start.toFloat()/total.toFloat()*contentWidthPx).toDp()})
                                            .padding(vertical=4.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF087CC1))
                                            .pointerInput(total,start,duration,musicTrackIndex){
                                                detectDragGestures(onDragStart={onPlayheadChange(start)}){change,drag->
                                                    change.consume()
                                                    val delta=(drag.x/contentWidthPx*total.toFloat()).toLong()
                                                    val dTrack=(drag.y/with(density){(laneHeight+5.dp).toPx()}).toInt()
                                                    onMoveMusicTrack((start+delta).coerceAtLeast(0L),(musicTrackIndex+dTrack).coerceAtLeast(0))
                                                }
                                            }
                                    ) {
                                        Canvas(Modifier.fillMaxSize().padding(horizontal=8.dp,vertical=5.dp)){
                                            val bars=54; val barWidth=size.width/bars
                                            for(i in 0 until bars){val phase=(musicUri.hashCode()*.0001f+i*.73f);val h=(.18f+.72f*((kotlin.math.sin(phase*1.9f)+1f)/2f)).coerceIn(.08f,.95f);drawRoundRect(Color(0xFF64D9FF),Offset(i*barWidth+barWidth*.18f,(size.height*(1f-h))/2f),androidx.compose.ui.geometry.Size(barWidth*.56f,size.height*h),cornerRadius=androidx.compose.ui.geometry.CornerRadius(2f,2f))}
                                        }
                                        val handle=7.dp
                                        Box(Modifier.fillMaxHeight().width(handle).background(Color.White).align(Alignment.CenterStart).pointerInput(total,start,duration){detectDragGestures{change,drag->change.consume();val delta=(drag.x/contentWidthPx*total).toLong();val sourceEnd=if(musicSourceDurationMs>0L)musicSourceDurationMs else Long.MAX_VALUE;val nextStart=musicStartMs.coerceIn(0L,(sourceEnd-300L).coerceAtLeast(0L));val maxDuration=if(sourceEnd==Long.MAX_VALUE)total else(sourceEnd-nextStart).coerceAtLeast(300L);onMusicTrim(nextStart,(duration-delta).coerceIn(300L,maxDuration))}})
                                        Box(Modifier.fillMaxHeight().width(handle).background(Color.White).align(Alignment.CenterEnd).pointerInput(total,start,duration){detectDragGestures{change,drag->change.consume();val nextEnd=(duration+(drag.x/contentWidthPx*total).toLong()).coerceIn(300L,total-start);onMusicTrim(musicStartMs,nextEnd)}} 
                                    }
                                }
                            }
                        }
                        audioTracks.sortedWith(compareBy<AudioTrack>{it.trackIndex}.thenBy{it.timelineStartMs}).forEach { track ->
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                Box(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(7.dp)).background(Color(0xFF14142A))) {
                                    Icon(Icons.Default.MusicNote,contentDescription="Audio track",tint=Color(0xFFB38CFF),modifier=Modifier.align(Alignment.CenterStart).padding(start=6.dp).size(16.dp))
                                    val start=track.timelineStartMs.coerceAtLeast(0L)
                                    val width=(track.durationMs.coerceAtLeast(300L).toFloat()/total.toFloat()).coerceIn(.01f,1f)
                                    Box(Modifier.fillMaxHeight().fillMaxWidth(width).offset(x=with(density){(start.toFloat()/total.toFloat()*contentWidthPx).toDp()}).padding(vertical=4.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF6740B8))
                                        .clickable{onPlayheadChange(start)}
                                        .pointerInput(track,total){
                                            var movedMs=0L
                                            var movedTrack=0
                                            detectDragGestures(onDragStart={movedMs=0L;movedTrack=0;onPlayheadChange(start)}){change,drag->
                                                change.consume()
                                                movedMs += (drag.x/contentWidthPx*total.toFloat()).toLong()
                                                movedTrack += (drag.y/with(density){(laneHeight+5.dp).toPx()}).toInt()
                                                onMoveAudioTrack(track,(start+movedMs).coerceAtLeast(0L),(track.trackIndex+movedTrack).coerceAtLeast(0))
                                            }
                                        }){
                                        Canvas(Modifier.fillMaxSize().padding(horizontal=8.dp,vertical=5.dp)){val bars=42;val bw=size.width/bars;for(i in 0 until bars){val h=(.2f+.7f*((kotlin.math.sin((track.id.hashCode()*.0002f+i*.91f))+1f)/2f));drawRoundRect(Color(0xFFD3B8FF),Offset(i*bw+bw*.2f,(size.height*(1f-h))/2f),androidx.compose.ui.geometry.Size(bw*.55f,size.height*h),cornerRadius=androidx.compose.ui.geometry.CornerRadius(2f,2f))}}
                                        if (track.durationMs >= 300L) {
                                            TrimHandle(Alignment.CenterStart) { delta ->
                                                val oldStart=track.sourceStartMs
                                                val oldDuration=track.durationMs
                                                val maxStart=(track.sourceDurationMs-oldDuration).coerceAtLeast(0L)
                                                val nextStart=(oldStart+delta).coerceIn(0L,maxStart)
                                                onTrimAudioTrack(track,nextStart,oldDuration-(nextStart-oldStart))
                                            }
                                            TrimHandle(Alignment.CenterEnd) { delta ->
                                                val maxDuration=if(track.sourceDurationMs>0L) (track.sourceDurationMs-track.sourceStartMs).coerceAtLeast(300L) else Long.MAX_VALUE
                                                onTrimAudioTrack(track,track.sourceStartMs,(track.durationMs+delta).coerceIn(300L,maxDuration))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (musicUri.isNotBlank() || audioTracks.isNotEmpty()) {
                            Row(Modifier.fillMaxWidth().padding(top=1.dp),horizontalArrangement=Arrangement.End){
                                IconButton(onClick=onAddAudio,modifier=Modifier.size(34.dp)){Icon(Icons.Default.Add,contentDescription="Add audio",tint=Color.White)}
                            }
                        } else {
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                                Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(7.dp)).background(Color(0xFF101C2C)).clickable{onAudioTrackClick()}){
                                    Icon(Icons.Default.MusicNote,null,tint=Color(0xFF39BFFF),modifier=Modifier.align(Alignment.CenterStart).padding(start=6.dp).size(16.dp))
                                    Text("إضافة صوت",color=Color(0xFF7C8DA6),fontSize=9.sp,modifier=Modifier.align(Alignment.Center))
                                }
                                IconButton(onClick=onAddAudio,modifier=Modifier.size(36.dp)){Icon(Icons.Default.Add,contentDescription="Add audio",tint=Color.White)}
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

