# VideoForgeNative — Current Project Status

## Current focus: video editing
This project remains a single continuous project (not split into versions).

### Video editing implemented / continued
- Native Android Kotlin + Jetpack Compose editor.
- Multi-clip timeline with global playhead and draggable playhead.
- Trim handles with minimum-duration protection.
- Video transform preview: zoom, pan, rotation and gesture control.
- Horizontal/vertical flip controls with persistent project settings.
- Reframe/aspect controls: 16:9, 9:16, 1:1 and 4:5; preview and fixed-resolution export use the selected aspect ratio.
- Video motion keyframes for X/Y/scale/rotation.
- Motion keyframe easing: linear, ease-in, ease-out and ease-in-out.
- Speed presets plus speed-ramping keyframes with easing; export now applies the ramp through Media3 SpeedProvider instead of exporting only the base speed.
- Color controls: brightness, contrast, saturation and hue.
- Filter presets: warm, cool, grayscale, sepia, inverted and vivid.
- Export resolution, FPS, quality and H.264/H.265 controls.
- Media3 Transformer export pipeline for trims, transforms, color effects, overlays and speed changes.
- Sticker editor: selectable sticker presets with position, scale, rotation and opacity controls; the same settings are persisted and applied to preview/export.
- Video settings persist per project, including motion/speed keyframes, easing and flips.

### Important verification note
A local Android build has not been run in this environment because the project does not include a Gradle wrapper and a `gradle` executable is unavailable here. The source was inspected and updated statically; device/build verification still needs to be performed in Android Studio or GitHub Actions.

## Next editing areas
After the video pass, continue with the remaining editor areas such as advanced audio/music UX, text/subtitles polish, transitions, export UX and final polish.

### Reliability / export safety pass
- Export now performs preflight validation for clip duration settings and source URI readability before starting Media3 Transformer.
- Export rejects HEVC requests when no HEVC encoder is available and rejects H.264 requests when no AVC encoder is available.
- Background music source is validated before export.
- Content URIs selected through the media/music pickers request persistent read access where supported, reducing broken-project references after restart.


## Latest completed editor work
- Added persistent text-layer name, visibility, and lock state.
- Added a layer manager with reorder, hide/show, lock/unlock, rename, duplicate, add, and delete actions.
- Hidden text layers are excluded from preview and export; locked layers reject direct gesture edits.
- Added SRT subtitle import and export with timed parsing and bilingual controls.
- Fixed legacy text export fallback so `plus()` is no longer used without assigning its result.
- Added static structural checks after these changes. Android Gradle build/Lint still requires an Android SDK/Gradle environment and remains intentionally deferred to the final GitHub build stage.


## Audio enhancement pass — 2026-09-17
- Added configurable music-ducking attack/release times.
- Persisted duck attack/release settings with backward-compatible defaults.
- Preview applies the same ducking ramp as export.
- Export music automation now generates attack/release transition points and supports overlapping audible clips.
- Static Kotlin delimiter scan passed.
- Gradle/Lint runtime build remains intentionally deferred to final GitHub stage.


## Multi-layer PIP pass — 2026-09-18
- Replaced the single-image PIP export path with a persistent multi-layer PIP model while keeping backward compatibility with existing projects.
- Multiple image/PIP layers can now be added from the existing image picker; each layer stores its own position, scale, rotation, opacity and visibility.
- PIP layers are persisted with the project and restored after reopening.
- All visible PIP layers are included in Media3 Transformer export, instead of only one image overlay.
- PIP layers are also rendered in the editor preview.


## Multi-layer PIP controls — 2026-09-18
- Added an in-editor PIP layer manager for the multi-layer model.
- Layers can be selected, reordered up/down, duplicated, hidden/shown, edited, or deleted.
- Selected-layer controls now expose position, scale, rotation, and opacity.
- Added remove-all-PIP action while preserving the existing legacy single-overlay fields for backward compatibility.
- PIP ordering remains consistent with the persisted layer list used by preview/export.
