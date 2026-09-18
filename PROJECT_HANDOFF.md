# VideoForgeNative — Project Handoff / Continuation Brief

> مسودة انتقال للمحادثة التالية، لضمان استمرار العمل من حالة المستودع الحالية دون فقدان ترتيب الإضافات أو طريقة معالجة أخطاء البناء.

## 1. طبيعة المشروع
VideoForgeNative هو محرر فيديو Android أصلي مبني بـ Kotlin وJetpack Compose وMedia3. المشروع مستمر كمشروع واحد، وليس مجموعة إصدارات أو نسخ منفصلة.

الهدف النهائي: محرر فيديو احترافي جداً بواجهة حديثة وتجربة متماسكة، مع معالجة حقيقية للفيديو والصوت أثناء المعاينة والتصدير، وليس مجرد واجهة شكلية.

- العربية RTL والإنجليزية LTR.
- عدم إنشاء نسخة ثانية من المشروع.
- عدم حذف الميزات الموجودة عند إضافة ميزات جديدة.
- الحفاظ على التوافق مع المشاريع والإعدادات القديمة قدر الإمكان.
- كل ميزة يجب أن ترتبط بالمعاينة والحفظ والتصدير عندما ينطبق ذلك.
- لا تعتبر الميزة مكتملة لمجرد وجود زر لها.

## 2. الخصائص الموجودة
### التحرير الأساسي
- خط زمني متعدد المقاطع.
- Trim / قص، Split / تقسيم، إعادة ترتيب، حذف، استبدال وتكرار.
- Undo / Redo.
- حفظ تلقائي وحفظ إعدادات المشروع.

### التحويل والحركة
- Zoom / Pan / Rotation.
- Flip أفقي وعمودي.
- تحكم بالإيماءات.
- Video motion keyframes.
- Scale / rotation / position keyframes.
- easing: linear وease-in وease-out وease-in-out.
- speed presets وspeed-ramping keyframes.

### الصوت
- موسيقى خلفية وVolume وMute.
- Fade in/out.
- Music ducking مع attack/release.
- Audio keyframes.
- استخراج صوت غير هدّام إلى M4A/AAC.

### النص والترجمة
- طبقات نص متعددة.
- أسماء وظهور/إخفاء وقفل للطبقات.
- إدارة الطبقات وإعادة ترتيبها وتكرارها وحذفها.
- SRT import/export.
- مكتبة خطوط عربية وإنجليزية مرخصة ومحلية.
- letter spacing وline height وalignment وbackground padding وglow.
- Typography presets.

### المؤثرات والصورة
- brightness / contrast / saturation / hue.
- فلاتر متعددة وblur.
- Mosaic / Region Pixelate.
- Sticker editor.
- Image/PIP overlays.

### PIP
- Multi-layer PIP.
- position / scale / rotation / opacity.
- visibility / lock.
- reorder / duplicate / delete / remove all.
- معاينة وتصدير الطبقات.

### المقاسات والتصدير
- 16:9، 9:16، 1:1، 4:5، 2:3، 3:4، 3:2، 21:9.
- Export resolution من 360p حتى 4K حسب الإعدادات.
- FPS وquality.
- H.264 / H.265 عند توفر encoder.
- Media3 Transformer للتصدير الحقيقي.
- preflight validation للمصادر وقدرات الترميز.

### Freeze Frame
- التقاط إطار من الفيديو.
- إدخاله كصورة زمنية.
- تقسيم المقطع عند الحاجة.
- حفظ الحالة.
- معاينة وتصدير timed image بدون صوت.

## 3. الإضافات المنجزة — ترتيبها
1. Multi-layer PIP.
2. Professional aspect/crop presets.
3. Audio extraction.
4. Freeze frame.
5. Expanded licensed Arabic/English fonts.
6. Professional text styling controls.
7. Typography presets.
8. Mosaic / Region Pixelate (preview + export).

هذه القائمة مثبتة أيضاً في BUILD_FIX_QUEUE.md.

## 4. الإضافات والتعديلات المتبقية — بالترتيب
### المرحلة الحالية: Build Fix
يجب الوصول إلى:
- assembleDebug ناجح.
- lint ناجح.
- التعامل مع التحذيرات كأنها أخطاء.
- عدم إعلان النجاح قبل نتيجة CI فعلية.

### بعد تنظيف البناء
1. Direct drag/resize لمنطقة Mosaic، مع حفظ الإعدادات وتطابق preview/export.
2. Remaining effects/transitions.
3. Further timeline/keyframe refinements.
4. Final UI polish.
5. Full build/lint verification.
6. Release/APK validation.
7. AdMob — آخر مرحلة فقط، بعد اكتمال المحرر واختباره نهائياً.

لا يتم تغيير هذا الترتيب إلا بقرار صريح.

## 5. طريقة التعامل مع أخطاء البناء
المبدأ المعتمد: الإصلاح على دفعات صغيرة، وليس إصلاح كل الأخطاء دفعة واحدة.

1. افتح آخر GitHub Actions run يرسله المستخدم.
2. اقرأ log لذلك الـjob تحديداً.
3. استخرج مجموعة صغيرة من الأخطاء المترابطة.
4. أصلح هذه المجموعة فقط.
5. اعمل commit واضحاً.
6. انتظر CI جديداً.
7. اقرأ الأخطاء الجديدة فقط.
8. انتقل للمجموعة التالية.
9. لا تضف تعديلات غير لازمة إذا اختفى الخطأ بسبب إصلاح آخر.
10. التحذيرات تعامل كأنها أخطاء.
11. لا تقل إن البناء ناجح إلا بعد نجاح assembleDebug وlint فعلياً.

### أمثلة على إصلاحات سابقة
- توحيد Java/Kotlin على JDK 17.
- معالجة مشكلة libandroidx.graphics.path.so أثناء packaging.
- استرجاع tail ناقص من MainActivity.kt كان يسبب أخطاء Compose/Slider متسلسلة.
- تعديل MosaicEffect ليتوافق مع Media3 GlProgram.
- جعل استدعاءات Slider صريحة.
- إصلاح newline حرفي خاطئ في EditorSettings.kt.
- ظهرت لاحقاً مجموعات أخطاء في ExportEngine.kt وMainActivity.kt، ويجب التعامل معها تدريجياً لا دفعة واحدة.

## 6. CI والأعمال الأخيرة
Workflow يستخدم JDK 17 وGradle 8.9، مع assembleDebug وlint ورفع APK debug عند النجاح. تم تحديث actions إلى checkout@v6 وsetup-java@v5 وsetup-gradle@v6.

Runs الحديثة المرتبطة بمرحلة الإصلاح:
- 35299024307 / Job 105457775764.
- 35300690281 / Job 105462483279.
- 35300954694 / Job 105463282257.
- 35301162601 / Job 105463887854.
- 35301413551 / Job 105464650111.
- 35301747682 / Job 105465634660.

عند بدء المحادثة الجديدة يجب فحص أحدث run فعلياً، لأن runs اللاحقة قد تكون أزالت أخطاء موجودة في runs السابقة.

## 7. أخطاء ظهرت سابقاً
ظهرت في ExportEngine.kt مراجع غير محلولة مثل OverlaySettings وcreateSepiaFilter وProgressHolder وclipDurationMs ومشكلة onError.

وظهرت في MainActivity.kt مشاكل مثل detectTransformGestures وdetectTapGestures وdetectDragGestures وsuspendCancellableCoroutine وupdateSettings ومراجع keyframes/dialogs وformatTimelineTime، إضافة إلى syntax error قرب السطر 2321.

لا تُصلح هذه القائمة عمياناً. افحص أحدث CI log أولاً، لأن بعضها قد يكون cascading أو تم إصلاحه في commits لاحقة.

## 8. الوثائق المرجعية
- README.md: تعريف المشروع والخصائص العامة.
- PROJECT_STATUS.md: سجل التقدم.
- BUILD_FIX_QUEUE.md: ترتيب الميزات المؤجلة ومرحلة build-fix.
- FONT_LICENSES.md: مصادر وترخيص الخطوط.
- PROJECT_HANDOFF.md: هذه المسودة الانتقالية.

الوثائق لا تغني عن اختبار البناء الفعلي.

## 9. قواعد العمل للمحادثة التالية
- المطلوب تنفيذ فعلي داخل GitHub، وليس خطة نظرية فقط.
- عند إصلاح البناء: أصلح عدداً محدداً من الأخطاء المترابطة في كل دفعة.
- بعد كل دفعة افحص run جديداً.
- لا تخلط إصلاحات البناء مع إضافة ميزة جديدة.
- لا تغير ترتيب roadmap.
- لا تنشئ مشروعاً أو نسخة منفصلة.
- لا تضف AdMob قبل اكتمال المحرر.
- لا تعتبر زر الواجهة دليلاً على اكتمال الوظيفة.
- الهدف النهائي: تطبيق احترافي جداً، متماسك، مستقر، وقابل للتصدير الحقيقي.

## 10. نقطة الاستئناف
Build-fix mode → أحدث GitHub Actions run → مجموعة صغيرة من الأخطاء المترابطة → إصلاح → commit → CI جديد → تكرار.

بعد نجاح البناء والـlint بالكامل:
Mosaic direct drag/resize → remaining effects/transitions → timeline/keyframe refinements → final UI polish → full verification → APK/release validation → AdMob.
