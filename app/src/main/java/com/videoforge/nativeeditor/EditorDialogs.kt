
package com.videoforge.nativeeditor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Alignment
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
private fun ReferenceDialogTitle(text: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Box(
                Modifier.size(width = 4.dp, height = 25.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFF9A63FF), Color(0xFF2D8CFF))))
            )
            Spacer(Modifier.width(9.dp))
            Text(
                text,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1
            )
        }
        Box(
            Modifier.fillMaxWidth().height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color(0xFF6B3CFF), Color(0xFF2585FF), Color.Transparent)
                    )
                )
        )
    }
}

@Composable
fun KeyframeDialog(
    s: EditorSettings,
    clips: List<Clip>,
    clip: Clip?,
    playheadMs: Long,
    language: AppLanguage,
    onChange: (EditorSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val ar = language == AppLanguage.ARABIC
    var selectedId by remember { mutableStateOf(s.textLayers.firstOrNull()?.id) }
    val selected = s.textLayers.firstOrNull { it.id == selectedId } ?: s.textLayers.firstOrNull()
    var x by remember(selected?.id, playheadMs) { mutableFloatStateOf(selected?.x ?: 0f) }
    var y by remember(selected?.id, playheadMs) { mutableFloatStateOf(selected?.y ?: -.65f) }
    var easing by remember(selected?.id, playheadMs) { mutableStateOf("easeInOut") }
    LaunchedEffect(selected?.id, playheadMs) {
        val k = selected?.keyframes?.firstOrNull { it.timeMs == playheadMs }
        if (k != null) {
            x = k.x
            y = k.y
            easing = k.easing
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { ReferenceDialogTitle(if (ar) "إطار حركة النص" else "Text keyframe") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(formatTimelineTime(playheadMs), fontSize = 11.sp)
                if (s.textLayers.isEmpty()) {
                    Text(if (ar) "أضف طبقة نص أولاً." else "Add a text layer first.")
                } else {
                    Text(if (ar) "الطبقة" else "Layer", fontSize = 11.sp)
                    s.textLayers.forEach { layer ->
                        FilterChip(
                            selected = (selected?.id == layer.id),
                            onClick = { selectedId = layer.id },
                            label = { Text(layer.name.ifBlank { layer.text.ifBlank { "Text" } }) }
                        )
                    }
                    Text(if (ar) "الموضع الأفقي" else "Horizontal position", fontSize = 11.sp)
                    Slider(value = x, onValueChange = { x = it }, valueRange = -1f..1f)
                    Text(if (ar) "الموضع العمودي" else "Vertical position", fontSize = 11.sp)
                    Slider(value = y, onValueChange = { y = it }, valueRange = -1f..1f)
                    Text(if (ar) "التسارع" else "Easing", fontSize = 11.sp)
                    listOf("linear", "easeIn", "easeOut", "easeInOut", "hold").forEach { mode ->
                        FilterChip(
                            selected = easing == mode,
                            onClick = { easing = mode },
                            label = { Text(mode) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected != null,
                onClick = {
                    val layer = selected ?: return@TextButton
                    onChange(
                        s.copy(
                            textLayers = s.textLayers.map {
                                if (it.id == layer.id) {
                                    it.copy(
                                        keyframes = it.keyframes.filterNot { k -> k.timeMs == playheadMs } +
                                            TextKeyframe(playheadMs, x, y, layer.scale, layer.rotation, layer.alpha, easing)
                                    )
                                } else it
                            }
                        )
                    )
                    onDismiss()
                }
            ) { Text(if (ar) "حفظ" else "Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (ar) "إغلاق" else "Close") }
        }
    )
}
@Composable
fun VideoKeyframeDialog(
    s: EditorSettings,
    clips: List<Clip>,
    clip: Clip?,
    playheadMs: Long,
    language: AppLanguage,
    onChange: (EditorSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val ar = language == AppLanguage.ARABIC
    val existing = s.videoKeyframes.firstOrNull { it.timeMs == playheadMs }
    var x by remember(playheadMs, existing) { mutableFloatStateOf(existing?.x ?: s.cropX) }
    var y by remember(playheadMs, existing) { mutableFloatStateOf(existing?.y ?: s.cropY) }
    var scale by remember(playheadMs, existing) { mutableFloatStateOf(existing?.scale ?: s.cropZoom) }
    var rotation by remember(playheadMs, existing) { mutableFloatStateOf(existing?.rotation ?: s.rotation.toFloat()) }
    var easing by remember(playheadMs, existing) { mutableStateOf(existing?.easing ?: "easeInOut") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { ReferenceDialogTitle(if (ar) "إطار حركة الفيديو" else "Video keyframe") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(formatTimelineTime(playheadMs), fontSize = 11.sp)
                Text(if (ar) "الموضع الأفقي" else "Horizontal position", fontSize = 11.sp)
                Slider(value = x, onValueChange = { x = it }, valueRange = -1f..1f)
                Text(if (ar) "الموضع العمودي" else "Vertical position", fontSize = 11.sp)
                Slider(value = y, onValueChange = { y = it }, valueRange = -1f..1f)
                Text(if (ar) "التكبير: %.2fx".format(scale) else "Scale: %.2fx".format(scale), fontSize = 11.sp)
                Slider(value = scale, onValueChange = { scale = it }, valueRange = 0.1f..6f)
                Text(if (ar) "الدوران: %.1f°".format(rotation) else "Rotation: %.1f°".format(rotation), fontSize = 11.sp)
                Slider(value = rotation, onValueChange = { rotation = it }, valueRange = -360f..360f)
                Text(if (ar) "التسارع" else "Easing", fontSize = 11.sp)
                listOf("linear", "easeIn", "easeOut", "easeInOut", "hold").forEach { mode ->
                    FilterChip(
                        selected = easing == mode,
                        onClick = { easing = mode },
                        label = { Text(mode) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val next = (s.videoKeyframes.filterNot { it.timeMs == playheadMs } +
                    VideoKeyframe(playheadMs, x, y, scale, rotation, easing)).sortedBy { it.timeMs }
                onChange(s.copy(videoKeyframes = next))
                onDismiss()
            }) { Text(if (ar) "حفظ" else "Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (ar) "إغلاق" else "Close") }
        }
    )
}
@Composable fun SubtitleDialog(s:EditorSettings,playheadMs:Long,language:AppLanguage,onChange:(EditorSettings)->Unit,onImport:()->Unit,onExport:()->Unit,onDismiss:()->Unit){
 val ar=language==AppLanguage.ARABIC
 val existing=remember(playheadMs,s.subtitles){s.subtitles.firstOrNull{playheadMs>=it.startMs&&playheadMs<it.endMs}}
 var t by remember(existing?.id){mutableStateOf(existing?.text?:"")}
 var durationSec by remember(existing?.id){mutableFloatStateOf(((existing?.endMs?.minus(existing.startMs)?:2000L).coerceAtLeast(1000L)/1000f).coerceIn(1f,30f))}
 AlertDialog(onDismissRequest=onDismiss,title={ReferenceDialogTitle(if(ar)"الترجمة" else "Subtitles")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
  Text(formatTimelineTime(playheadMs),fontSize=11.sp)
  OutlinedTextField(value=t,onValueChange={t=it},label={Text(if(ar)"النص" else "Text")},modifier=Modifier.fillMaxWidth(),minLines=2)
  Text(if(ar)"مدة الترجمة: "+"%.1f".format(durationSec)+" ث" else "Subtitle duration: "+"%.1f".format(durationSec)+" s",fontSize=11.sp); Slider(value=durationSec,onValueChange={durationSec=it},valueRange=1f..30f)
  Row{TextButton(onClick=onImport){Text(if(ar)"استيراد" else "Import")};TextButton(onClick=onExport){Text(if(ar)"تصدير" else "Export")}}
 }},confirmButton={TextButton(onClick={val start=playheadMs.coerceAtLeast(0L);val end=start+(durationSec*1000L).toLong().coerceAtLeast(1000L);val updated=if(t.isBlank())s.subtitles.filterNot{existing!=null&&it.id==existing.id}else if(existing!=null)s.subtitles.map{if(it.id==existing.id)it.copy(text=t,startMs=start,endMs=end)else it}else s.subtitles+(Subtitle(text=t,startMs=start,endMs=end));onChange(s.copy(subtitles=updated.sortedBy{it.startMs}));onDismiss()}){Text(if(ar)"حفظ" else "Save")}},dismissButton={TextButton(onClick=onDismiss){Text(if(ar)"إغلاق" else "Close")}})}
@Composable
fun LayerManagerDialog(
    s: EditorSettings,
    language: AppLanguage,
    onChange: (EditorSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val ar = language == AppLanguage.ARABIC
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { ReferenceDialogTitle(if (ar) "الطبقات" else "Layers") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (s.textLayers.isEmpty()) {
                    Text(if (ar) "لا توجد طبقات نصية" else "No text layers")
                } else {
                    s.textLayers.forEachIndexed { index, layer ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            Text(
                                layer.name.ifBlank { layer.text.ifBlank { "Text" } },
                                Modifier.weight(1f),
                                maxLines = 1
                            )
                            IconButton(
                                enabled = index > 0,
                                onClick = {
                                    val layers = s.textLayers.toMutableList()
                                    layers[index] = layers[index - 1]
                                    layers[index - 1] = layer
                                    onChange(s.copy(textLayers = layers))
                                }
                            ) { Text("↑") }
                            IconButton(
                                enabled = index < s.textLayers.lastIndex,
                                onClick = {
                                    val layers = s.textLayers.toMutableList()
                                    layers[index] = layers[index + 1]
                                    layers[index + 1] = layer
                                    onChange(s.copy(textLayers = layers))
                                }
                            ) { Text("↓") }
                            Switch(
                                checked = layer.visible,
                                onCheckedChange = { visible ->
                                    onChange(s.copy(textLayers = s.textLayers.map {
                                        if (it.id == layer.id) it.copy(visible = visible) else it
                                    }))
                                }
                            )
                            IconButton(
                                onClick = {
                                    onChange(s.copy(textLayers = s.textLayers.map {
                                        if (it.id == layer.id) it.copy(locked = !it.locked) else it
                                    }))
                                }
                            ) { Text(if (layer.locked) "🔒" else "🔓") }
                            IconButton(
                                onClick = {
                                    onChange(s.copy(textLayers = s.textLayers.filterNot { it.id == layer.id }))
                                }
                            ) { Text("×") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(if (ar) "إغلاق" else "Close")
            }
        }
    )
}
@Composable fun MarkerDialog(s:EditorSettings,playheadMs:Long,language:AppLanguage,onChange:(EditorSettings)->Unit,onSeek:(Long)->Unit,onDismiss:()->Unit){
 val ar=language==AppLanguage.ARABIC
 var newLabel by remember(playheadMs){mutableStateOf(if(ar)"علامة جديدة" else "New marker")}
 AlertDialog(onDismissRequest=onDismiss,title={ReferenceDialogTitle(if(ar)"علامات الخط الزمني" else "Timeline markers")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(7.dp)){
  Text(if(ar)"الموضع الحالي: "+formatTimelineTime(playheadMs) else "Current position: "+formatTimelineTime(playheadMs),fontSize=11.sp)
  OutlinedTextField(value=newLabel,onValueChange={newLabel=it},singleLine=true,label={Text(if(ar)"اسم العلامة" else "Marker label")},modifier=Modifier.fillMaxWidth())
  if(s.markers.isEmpty()) Text(if(ar)"لا توجد علامات بعد." else "No markers yet.",fontSize=11.sp)
  s.markers.sortedBy{it.timeMs}.forEach{m->Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){TextButton(onClick={onSeek(m.timeMs)},modifier=Modifier.weight(1f)){Text(formatTimelineTime(m.timeMs)+"  •  "+m.label,maxLines=1)};IconButton(onClick={onChange(s.copy(markers=s.markers.filterNot{it.id==m.id}.sortedBy{it.timeMs}))}){Text("×")}}}
 }},confirmButton={TextButton(onClick={val label=newLabel.trim().ifBlank{if(ar)"علامة" else "Marker"};val same=s.markers.firstOrNull{it.timeMs==playheadMs};val next=if(same!=null)s.markers.map{if(it.id==same.id)it.copy(label=label)else it}else s.markers+TimelineMarker(timeMs=playheadMs,label=label);onChange(s.copy(markers=next.sortedBy{it.timeMs}));onDismiss()}){Text(if(ar)"إضافة / تحديث" else "Add / update")}},dismissButton={TextButton(onClick=onDismiss){Text(if(ar)"إغلاق" else "Close")}})}
@Composable fun TrimDialog(clip:Clip,language:AppLanguage,onDismiss:()->Unit,onApply:(Long,Long)->Unit){val ar=language==AppLanguage.ARABIC;val originalEnd=if(clip.trimEndMs==Long.MAX_VALUE)clip.durationMs else clip.trimEndMs;var start by remember{mutableFloatStateOf(clip.trimStartMs.toFloat())};var end by remember{mutableFloatStateOf(originalEnd.toFloat())};val gap=100L;val duration=clip.durationMs.coerceAtLeast(gap);AlertDialog(onDismissRequest=onDismiss,title={ReferenceDialogTitle(if(ar)"قص" else "Trim")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)){Text(formatTimelineTime(start.toLong())+" — "+formatTimelineTime(end.toLong()),fontSize=12.sp);Text(if(ar)"البداية" else "Start");Slider(value=start,onValueChange={start=it.coerceIn(0f,(end-gap).coerceAtLeast(0f))},valueRange=0f..duration.toFloat());Text(if(ar)"النهاية" else "End");Slider(value=end,onValueChange={end=it.coerceIn((start+gap).coerceAtMost(duration.toFloat()),duration.toFloat())},valueRange=0f..duration.toFloat())}},confirmButton={TextButton(onClick={onApply(start.toLong(),end.toLong())}){Text(if(ar)"تطبيق" else "Apply")}},dismissButton={TextButton(onClick=onDismiss){Text(if(ar)"إلغاء" else "Cancel")}})}
@Composable
fun ExportDialog(
    language: AppLanguage,
    settings: ExportSettings,
    watermarkRemoved: Boolean,
    onWatchAdToRemoveWatermark: () -> Unit,
    onDismiss: () -> Unit,
    onSettings: (ExportSettings) -> Unit,
    onExport: (ExportSettings) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { ReferenceDialogTitle(if (language == AppLanguage.ARABIC) "تصدير الفيديو" else "Export video") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFF0C1728),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF24476F))
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(32.dp).clip(RoundedCornerShape(9.dp))
                                    .background(Brush.linearGradient(listOf(Color(0xFF6B3CFF), Color(0xFF2585FF)))),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("HD", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(if (language == AppLanguage.ARABIC) "جودة التصدير" else "Export quality", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                                Text(if (language == AppLanguage.ARABIC) "اختر الدقة المناسبة للمشروع" else "Choose the resolution for this project", color = Color(0xFF8396B1), fontSize = 9.sp)
                            }
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp), contentPadding = PaddingValues(end = 2.dp)) {
                            items(ExportResolution.values().toList()) { r ->
                                FilterChip(
                                    selected = settings.resolution == r,
                                    onClick = { onSettings(settings.copy(resolution = r)) },
                                    label = { Text(r.label, fontSize = 10.sp) }
                                )
                            }
                        }
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            if (language == AppLanguage.ARABIC) "النسخة المجانية" else "Free version",
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                        Text(
                            if (watermarkRemoved) {
                                if (language == AppLanguage.ARABIC) "تمت إزالة العلامة المائية لهذا التصدير." else "Watermark removed for this export."
                            } else {
                                if (language == AppLanguage.ARABIC) "ستظهر علامة VideoForge صغيرة أعلى يمين الفيديو." else "A small VideoForge watermark will appear at the top-right of the video."
                            },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (!watermarkRemoved) {
                            Button(
                                onClick = onWatchAdToRemoveWatermark,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (language == AppLanguage.ARABIC) "مشاهدة إعلان لإزالة العلامة" else "Watch an ad to remove watermark")
                            }
                        } else {
                            Text(
                                if (language == AppLanguage.ARABIC) "يمكنك الآن التصدير بدون العلامة المائية." else "You can now export without the watermark.",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onExport(settings) }) {
                Text(if (language == AppLanguage.ARABIC) "تصدير" else "Export")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (language == AppLanguage.ARABIC) "إلغاء" else "Cancel")
            }
        }
    )
}
@Composable fun TextAnimationDialog(s:EditorSettings,language:AppLanguage,onChange:(EditorSettings)->Unit,onDismiss:()->Unit){val ar=language==AppLanguage.ARABIC;val v=listOf("none" to ("بدون" to "None"),"fade" to ("ظهور" to "Fade"),"pop" to ("انبثاق" to "Pop"),"zoom" to ("تكبير" to "Zoom"),"slide" to ("انزلاق" to "Slide"),"typewriter" to ("كتابة" to "Typewriter"));AlertDialog(onDismissRequest=onDismiss,title={ReferenceDialogTitle(if(ar)"حركة النص" else "Text animation")},text={Column{v.forEach{(key,labels)->FilterChip(selected=s.textAnimation==key,onClick={onChange(s.copy(textAnimation=key))},label={Text(if(ar)labels.first else labels.second)})}}},confirmButton={TextButton(onClick=onDismiss){Text(if(ar)"إغلاق" else "Close")}})}
@Composable fun RotateDialog(s:EditorSettings,language:AppLanguage,onChange:(EditorSettings)->Unit,onDismiss:()->Unit){val ar=language==AppLanguage.ARABIC;AlertDialog(onDismissRequest=onDismiss,title={ReferenceDialogTitle(if(ar)"تدوير" else "Rotate")},text={Row{listOf(0,90,180,270).forEach{v->TextButton(onClick={onChange(s.copy(rotation=v))}){Text(v.toString()+"°")}}}},confirmButton={TextButton(onClick=onDismiss){Text(if(ar)"إغلاق" else "Close")}})}
@Composable fun FlipDialog(s:EditorSettings,language:AppLanguage,onChange:(EditorSettings)->Unit,onDismiss:()->Unit){val ar=language==AppLanguage.ARABIC;AlertDialog(onDismissRequest=onDismiss,title={ReferenceDialogTitle(if(ar)"قلب" else "Flip")},text={Column{Row{Text(if(ar)"أفقي" else "Horizontal",Modifier.weight(1f));Switch(s.flipHorizontal,{onChange(s.copy(flipHorizontal=it))})};Row{Text(if(ar)"عمودي" else "Vertical",Modifier.weight(1f));Switch(s.flipVertical,{onChange(s.copy(flipVertical=it))})}}},confirmButton={TextButton(onClick=onDismiss){Text(if(ar)"إغلاق" else "Close")}})}
@Composable fun CropDialog(s:EditorSettings,language:AppLanguage,onChange:(EditorSettings)->Unit,onDismiss:()->Unit){val ar=language==AppLanguage.ARABIC;AlertDialog(onDismissRequest=onDismiss,title={ReferenceDialogTitle(if(ar)"قص وإطار" else "Crop")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)){Text(if(ar)"التكبير: %.2fx".format(s.cropZoom) else "Zoom: %.2fx".format(s.cropZoom),fontSize=12.sp);Slider(value=s.cropZoom,onValueChange={onChange(s.copy(cropZoom=it))},valueRange=1f..4f);Text(if(ar)"الموضع الأفقي" else "Horizontal position",fontSize=11.sp);Slider(value=s.cropX,onValueChange={onChange(s.copy(cropX=it))},valueRange=-1f..1f);Text(if(ar)"الموضع العمودي" else "Vertical position",fontSize=11.sp);Slider(value=s.cropY,onValueChange={onChange(s.copy(cropY=it))},valueRange=-1f..1f)}},confirmButton={TextButton(onClick=onDismiss){Text(if(ar)"إغلاق" else "Close")}})}
@Composable fun HistoryDialog(language:AppLanguage,undoCount:Int,redoCount:Int,onUndo:()->Unit,onRedo:()->Unit,onClear:()->Unit,onDismiss:()->Unit){
    val ar = language == AppLanguage.ARABIC
    AlertDialog(
        onDismissRequest=onDismiss,
        title={ReferenceDialogTitle(if(ar) "السجل" else "History")},
        text={
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text(if(ar) "تراجع: $undoCount • إعادة: $redoCount" else "Undo: $undoCount • Redo: $redoCount")
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    OutlinedButton(onClick=onUndo, enabled=undoCount>0){Text(if(ar) "تراجع" else "Undo")}
                    OutlinedButton(onClick=onRedo, enabled=redoCount>0){Text(if(ar) "إعادة" else "Redo")}
                }
                TextButton(onClick=onClear, enabled=undoCount>0 || redoCount>0){
                    Text(if(ar) "مسح السجل" else "Clear history")
                }
            }
        },
        confirmButton={TextButton(onClick=onDismiss){Text(if(ar) "إغلاق" else "Close")}}
    )
}
@Composable fun EditToolsDialog(language:AppLanguage,clips:List<Clip>,current:Clip?,onTrim:()->Unit,onSplit:()->Unit,onDelete:()->Unit,onMoveLeft:()->Unit,onMoveRight:()->Unit,onReplace:()->Unit,onDuplicate:()->Unit,onDismiss:()->Unit){
    val ar=language==AppLanguage.ARABIC
    AlertDialog(onDismissRequest=onDismiss,title={ReferenceDialogTitle(if(ar) "أدوات المقطع" else "Clip tools")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)){
            Button(onClick=onTrim,enabled=current!=null,modifier=Modifier.fillMaxWidth()){Text(if(ar) "قص" else "Trim")}
            Button(onClick=onSplit,enabled=current!=null,modifier=Modifier.fillMaxWidth()){Text(if(ar) "تقسيم" else "Split")}
            Button(onClick=onDuplicate,enabled=current!=null,modifier=Modifier.fillMaxWidth()){Text(if(ar) "تكرار المقطع" else "Duplicate")}
            Button(onClick=onReplace,enabled=current!=null,modifier=Modifier.fillMaxWidth()){Text(if(ar) "استبدال الوسائط" else "Replace media")}
            OutlinedButton(onClick=onMoveLeft,enabled=current!=null,modifier=Modifier.fillMaxWidth()){Text(if(ar) "تحريك لليسار" else "Move left")}
            OutlinedButton(onClick=onMoveRight,enabled=current!=null,modifier=Modifier.fillMaxWidth()){Text(if(ar) "تحريك لليمين" else "Move right")}
            Button(onClick=onDelete,enabled=current!=null && clips.size>1,modifier=Modifier.fillMaxWidth()){Text(if(ar) "حذف المقطع" else "Delete clip")}
        }
    },confirmButton={TextButton(onClick=onDismiss){Text(if(ar) "إغلاق" else "Close")}})
}
@Composable fun SimpleChoiceDialog(title:String,items:List<String>,selected:Int?,language:AppLanguage,onDismiss:()->Unit,onSelect:(Int)->Unit){AlertDialog(onDismissRequest=onDismiss,title={ReferenceDialogTitle(title)},text={Column(Modifier.verticalScroll(rememberScrollState())){items.forEachIndexed{i,v->FilterChip(selected==i,{onSelect(i)},label={Text(v)})}}},confirmButton={TextButton(onClick=onDismiss){Text(if(language==AppLanguage.ARABIC)"إغلاق" else "Close")}})}
