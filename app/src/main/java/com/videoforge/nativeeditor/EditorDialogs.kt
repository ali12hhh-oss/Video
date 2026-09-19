package com.videoforge.nativeeditor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable fun KeyframeDialog(s:EditorSettings,clips:List<Clip>,clip:Clip?,playheadMs:Long,language:AppLanguage,onChange:(EditorSettings)->Unit,onDismiss:()->Unit){
 var x by remember{mutableFloatStateOf(s.textLayers.firstOrNull()?.x?:0f)}; var y by remember{mutableFloatStateOf(s.textLayers.firstOrNull()?.y?:-.65f)}
 AlertDialog(onDismissRequest=onDismiss,title={Text("Keyframe")},text={Column(Modifier.verticalScroll(rememberScrollState())){Text(formatTimelineTime(playheadMs),fontSize=11.sp);Text("X");Slider(value = x, onValueChange = { value -> x = value }, valueRange = -1f..1f);Text("Y");Slider(value = y, onValueChange = { value -> y = value }, valueRange = -1f..1f)}},confirmButton={TextButton(onClick={val l=s.textLayers.firstOrNull();if(l!=null)onChange(s.copy(textLayers=s.textLayers.map{if(it.id==l.id)it.copy(keyframes=it.keyframes.filterNot{k->k.timeMs==playheadMs}+TextKeyframe(playheadMs,x,y,l.scale,l.rotation,l.alpha))else it}));onDismiss()}){Text("Save")}},dismissButton={TextButton(onClick=onDismiss){Text("Close")}})
}
@Composable fun VideoKeyframeDialog(s:EditorSettings,clips:List<Clip>,clip:Clip?,playheadMs:Long,language:AppLanguage,onChange:(EditorSettings)->Unit,onDismiss:()->Unit){
 AlertDialog(onDismissRequest=onDismiss,title={Text("Video keyframe")},text={Text(formatTimelineTime(playheadMs))},confirmButton={TextButton(onClick={onChange(s.copy(videoKeyframes=s.videoKeyframes.filterNot{it.timeMs==playheadMs}+VideoKeyframe(playheadMs,s.cropX,s.cropY,s.cropZoom,s.rotation.toFloat())));onDismiss()}){Text("Save")}},dismissButton={TextButton(onClick=onDismiss){Text("Close")}})
}
@Composable fun SubtitleDialog(s:EditorSettings,playheadMs:Long,language:AppLanguage,onChange:(EditorSettings)->Unit,onImport:()->Unit,onExport:()->Unit,onDismiss:()->Unit){
 var t by remember{mutableStateOf("")};AlertDialog(onDismissRequest=onDismiss,title={Text("Subtitles")},text={Column{OutlinedTextField(t,{t=it},label={Text("Text")});Row{TextButton(onClick=onImport){Text("Import")};TextButton(onClick=onExport){Text("Export")}}}},confirmButton={TextButton(onClick={onDismiss}){Text("Close")}})
}
@Composable fun LayerManagerDialog(s:EditorSettings,language:AppLanguage,onChange:(EditorSettings)->Unit,onDismiss:()->Unit){AlertDialog(onDismissRequest=onDismiss,title={Text("Layers")},text={Column{if(s.textLayers.isEmpty())Text("No text layers") else s.textLayers.forEach{l->Text(l.name)} }},confirmButton={TextButton(onClick=onDismiss){Text("Close")}})}
@Composable fun MarkerDialog(s:EditorSettings,playheadMs:Long,language:AppLanguage,onChange:(EditorSettings)->Unit,onSeek:(Long)->Unit,onDismiss:()->Unit){AlertDialog(onDismissRequest=onDismiss,title={Text("Markers")},text={Column{ s.markers.forEach{m->TextButton(onClick={onSeek(m.timeMs)}){Text(m.label)}}}},confirmButton={TextButton(onClick={onChange(s.copy(markers=s.markers+TimelineMarker(timeMs=playheadMs)));onDismiss()}){Text("Add")}},dismissButton={TextButton(onClick=onDismiss){Text("Close")}})}
@Composable fun TrimDialog(clip:Clip,onDismiss:()->Unit,onApply:(Long,Long)->Unit){val end=if(clip.trimEndMs==Long.MAX_VALUE)clip.durationMs else clip.trimEndMs;AlertDialog(onDismissRequest=onDismiss,title={Text("Trim")},text={Text(formatTimelineTime(clip.trimStartMs)+" - "+formatTimelineTime(end))},confirmButton={TextButton(onClick={onApply(clip.trimStartMs,end)}){Text("Apply")}},dismissButton={TextButton(onClick=onDismiss){Text("Cancel")}})}
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
        title = { Text(if (language == AppLanguage.ARABIC) "تصدير الفيديو" else "Export video") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    if (language == AppLanguage.ARABIC) "اختر جودة الفيديو" else "Choose video quality",
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                )
                ExportResolution.values().forEach { r ->
                    FilterChip(
                        selected = settings.resolution == r,
                        onClick = { onSettings(settings.copy(resolution = r)) },
                        label = { Text(r.label) }
                    )
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
@Composable fun TextAnimationDialog(s:EditorSettings,language:AppLanguage,onChange:(EditorSettings)->Unit,onDismiss:()->Unit){val v=listOf("none","fade","pop","zoom","slide","typewriter");AlertDialog(onDismissRequest=onDismiss,title={Text("Text animation")},text={Column{v.forEach{x->FilterChip(selected=s.textAnimation==x,onClick={onChange(s.copy(textAnimation=x))},label={Text(x)})}}},confirmButton={TextButton(onClick=onDismiss){Text("Close")}})}
@Composable fun RotateDialog(s:EditorSettings,language:AppLanguage,onChange:(EditorSettings)->Unit,onDismiss:()->Unit){AlertDialog(onDismissRequest=onDismiss,title={Text("Rotate")},text={Row{listOf(0,90,180,270).forEach{v->TextButton(onClick={onChange(s.copy(rotation=v))}){Text(v.toString()+"°")}}}},confirmButton={TextButton(onClick=onDismiss){Text("Close")}})}
@Composable fun FlipDialog(s:EditorSettings,language:AppLanguage,onChange:(EditorSettings)->Unit,onDismiss:()->Unit){AlertDialog(onDismissRequest=onDismiss,title={Text("Flip")},text={Column{Row{Text("Horizontal",Modifier.weight(1f));Switch(s.flipHorizontal,{onChange(s.copy(flipHorizontal=it))})};Row{Text("Vertical",Modifier.weight(1f));Switch(s.flipVertical,{onChange(s.copy(flipVertical=it))})}}},confirmButton={TextButton(onClick=onDismiss){Text("Close")}})}
@Composable fun CropDialog(s:EditorSettings,language:AppLanguage,onChange:(EditorSettings)->Unit,onDismiss:()->Unit){AlertDialog(onDismissRequest=onDismiss,title={Text("Crop")},text={Text("Zoom "+s.cropZoom)},confirmButton={TextButton(onClick=onDismiss){Text("Close")}})}
@Composable fun HistoryDialog(language:AppLanguage,undoCount:Int,redoCount:Int,onUndo:()->Unit,onRedo:()->Unit,onClear:()->Unit,onDismiss:()->Unit){
    val ar = language == AppLanguage.ARABIC
    AlertDialog(
        onDismissRequest=onDismiss,
        title={Text(if(ar) "السجل" else "History")},
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
    AlertDialog(onDismissRequest=onDismiss,title={Text(if(ar) "أدوات المقطع" else "Clip tools")},text={
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
@Composable fun SimpleChoiceDialog(title:String,items:List<String>,selected:Int?,onDismiss:()->Unit,onSelect:(Int)->Unit){AlertDialog(onDismissRequest=onDismiss,title={Text(title)},text={Column(Modifier.verticalScroll(rememberScrollState())){items.forEachIndexed{i,v->FilterChip(selected==i,{onSelect(i)},label={Text(v)})}}},confirmButton={TextButton(onClick=onDismiss){Text("Close")}})}
