package com.videoforge.nativeeditor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

@Composable
fun Timeline(
    clips: List<Clip>, current: Clip?, playheadMs: Long,
    videoKeyframes: List<VideoKeyframe>, textKeyframes: List<TextKeyframe>, audioKeyframes: List<AudioKeyframe>, speedKeyframes: List<SpeedKeyframe>,
    musicUri: String, musicStartMs: Long, musicDurationMs: Long, musicVolume: Float, musicFadeIn: Float, musicFadeOut: Float,
    musicKeyframes: List<MusicKeyframe>, markers: List<TimelineMarker>, audioBaseVolume: Float, audioFadeIn: Float, audioFadeOut: Float,
    onAudioTrackClick: () -> Unit, onSelect: (Clip) -> Unit, onPlayheadChange: (Long) -> Unit,
    onDelete: (Clip) -> Unit, onMoveLeft: (Clip) -> Unit, onMoveRight: (Clip) -> Unit,
    onTrimEdges: (Clip, Long, Long) -> Unit, onSplit: (Clip) -> Unit, onKeyframeSeek: (Long) -> Unit,
    onVideoKeyframeMove: (Long, Long) -> Unit, onAddMedia: () -> Unit
 ) {
    val total = clips.sumOf { timelineClipDurationForUi(it) }.coerceAtLeast(1L)
    val safePlayhead = playheadMs.coerceIn(0L, total)
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(formatTimelineTime(safePlayhead), fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text(formatTimelineTime(total), color = Color.Gray, fontSize = 11.sp)
        }
        Slider(value = safePlayhead.toFloat(), onValueChange = { onPlayheadChange(it.toLong()) }, valueRange = 0f..max(1L, total).toFloat())
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            clips.forEach { clip ->
                val selected = clip == current
                val duration = timelineClipDurationForUi(clip)
                Column(Modifier.width(150.dp).clip(RoundedCornerShape(8.dp)).background(if (selected) Color(0xFF263B5A) else Color(0xFF151B26)).padding(7.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(clip.name, maxLines = 1, fontSize = 10.sp)
                    Text(formatTimelineTime(duration), color = Color.Gray, fontSize = 9.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        AssistChip(onClick = { onSelect(clip) }, label = { Text("Select", fontSize = 8.sp) })
                        AssistChip(onClick = { onSplit(clip) }, label = { Text("Split", fontSize = 8.sp) })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        AssistChip(onClick = { onMoveLeft(clip) }, label = { Text("←", fontSize = 9.sp) })
                        AssistChip(onClick = { onMoveRight(clip) }, label = { Text("→", fontSize = 9.sp) })
                        AssistChip(onClick = { onDelete(clip) }, label = { Text("×", fontSize = 9.sp) })
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            FilledTonalButton(onClick = onAddMedia, modifier = Modifier.height(36.dp)) {
                Text("+ Add media", fontSize = 11.sp)
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth()) {
            Text("V ${videoKeyframes.size}  T ${textKeyframes.size}  A ${audioKeyframes.size}", fontSize = 9.sp, color = Color.Gray, modifier = Modifier.weight(1f))
            Text("S ${speedKeyframes.size}  M ${musicKeyframes.size} • ${markers.size}", fontSize = 9.sp, color = Color.Gray)
        }
        if (musicUri.isNotBlank()) {
            Button(onClick = onAudioTrackClick, modifier = Modifier.fillMaxWidth().height(34.dp)) {
                Text("Music • ${(musicVolume * 100).toInt()}%", fontSize = 9.sp)
            }
        } else if (clips.isNotEmpty()) {
            AssistChip(onClick = onAudioTrackClick, label = { Text("Audio • ${(audioBaseVolume * 100).toInt()}%", fontSize = 9.sp) })
        }
        if (videoKeyframes.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                videoKeyframes.sortedBy { it.timeMs }.forEach { key ->
                    AssistChip(onClick = { onKeyframeSeek(key.timeMs) }, label = { Text(formatTimelineTime(key.timeMs), fontSize = 8.sp) })
                }
            }
        }
        current?.let { clip ->
            val start = clip.trimStartMs
            val end = if (clip.trimEndMs == Long.MAX_VALUE) clip.durationMs else clip.trimEndMs
            if (end > start) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    AssistChip(onClick = { onTrimEdges(clip, (start + 100L).coerceAtMost(end - 1L), end) }, label = { Text("Trim start +", fontSize = 8.sp) })
                    AssistChip(onClick = { onTrimEdges(clip, start, (end - 100L).coerceAtLeast(start + 1L)) }, label = { Text("Trim end -", fontSize = 8.sp) })
                }
            }
        }
    }
}

private fun timelineClipDurationForUi(clip: Clip): Long {
    val end = if (clip.trimEndMs == Long.MAX_VALUE) clip.durationMs else clip.trimEndMs
    return (end - clip.trimStartMs).coerceAtLeast(0L)
}