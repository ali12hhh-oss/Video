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

## Professional crop presets — 2026-09-18
- Added 2:3, 3:4, 3:2 and ultra-wide 21:9 canvas presets.
- Preview aspect handling supports the new ratios.
- Media3 export presentation now maps the new ratios and keeps the selected export quality as the long-edge bound.
- Existing 16:9, 9:16, 1:1 and 4:5 presets remain supported.

## Audio extraction foundation — 2026-09-18
- Added a non-destructive `AudioExtractor` engine.
- It detects the first audio track and extracts AAC (`audio/mp4a-latm`) without re-encoding into an M4A file under `Music/VideoForge`.
- The original video is never modified.
- The engine reports extraction progress and cleans up partial MediaStore output on failure.
- UI wiring remains part of the next integration pass; final Gradle/Lint verification is still intentionally deferred.
## Audio extraction UI integration — 2026-09-18
- Added a direct **Extract audio / استخراج الصوت** action to the editor.
- The action extracts the selected clip's AAC audio non-destructively through `AudioExtractor` and saves the resulting M4A in `Music/VideoForge`.
- Progress is shown in the editor status area and failures are surfaced without modifying the source video.
- Commit: `38fe3ca409fe6557d41ae819f874dbbca84488ab`.
## Freeze frame — 2026-09-18
- Added non-destructive freeze-frame creation at the current playhead position.
- The editor captures the source frame, inserts it as a timed 1-second image clip, and splits the source clip around the freeze point when needed.
- Freeze-frame metadata is persisted with projects.
- Media3 export now treats freeze frames as timed image inputs and removes their audio.
- Editor preview supports timed image playback for freeze-frame clips.
- Commits: `600fbabca2ebc3e31b84a6d2de94b51a5eb3d8d0`, `3827ab8e41f5d14753805242b38c09015b2742e2`, `23c38e841352d8bede86bf666936ca8a0eba0451`.

## Professional licensed font expansion — 2026-09-18
- Added four additional Arabic + Latin font families from the official Google Fonts repository: Cairo, Tajawal, IBM Plex Sans Arabic, and Readex Pro.
- Bundled font files under `app/src/main/res/font/` and documented their SIL Open Font License 1.1 sources in `FONT_LICENSES.md`.
- Added all four families to the text editor font picker and wired them into Media3 export.
- Variable-font families are also normalized through Typeface weight handling so Bold remains available in preview/export.
- Commits: `bd647acf7b5fb39f96cc852bd6b5bcebd90c6d0c`, `7d6cd7ccffbc58ae4b03f48c47edcb74667c4ebe`, `a3af0f411ea52d7e6ed8acd52e4392f1f29f9abc`, `2feff3c9523c0e4b0182bca3265f50cdbdbac9ba`.


## Expanded Arabic calligraphic and decorative font library — 2026-09-18
- Added eight additional SIL OFL-licensed Arabic/Latin families from the official Google Fonts repository: Aref Ruqaa, El Messiri, Changa, Jomhuria, Lalezar, Katibeh, Lemonada, and Markazi Text.
- Added calligraphic/display choices alongside the existing Noto, Amiri, Cairo, Tajawal, IBM Plex Sans Arabic, and Readex Pro families.
- Bundled the font binaries under `app/src/main/res/font/` and wired every family into the editor preview and export font resolver.
- Variable-weight families use the existing runtime Typeface weight handling; regular-only decorative families can still render synthesized bold when Bold is enabled.

## More Arabic script styles — 2026-09-18
- Added six further OFL-licensed families: Lateef, Harmattan, Mada, Scheherazade New, Reem Kufi, and Rubik.
- The library now includes additional Naskh-style, flowing calligraphic, Kufi, modern sans, and display-oriented options for Arabic/English captions.
- Each new family is bundled locally and connected to both the Compose preview picker and export resolver.
