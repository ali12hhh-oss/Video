# VideoForge Native

A native Android video editor built with Kotlin, Jetpack Compose and Media3.

The project is intentionally maintained as one continuous project rather than separate releases.

Highlights:
- RTL Arabic and LTR English UI
- Multi-clip editing timeline
- Trim / split / reorder / delete
- Undo / Redo and autosave
- Real Media3 Transformer export
- H.264 / H.265, resolution and quality controls
- Background music and volume mixing
- Filters, color adjustment, rotation and canvas ratios
- Multiple animated text layers
- Keyframes for text position, scale, rotation and opacity
- Bundled free Arabic/Latin fonts
- Stickers and overlays

Open the root folder in Android Studio and let Gradle sync dependencies.

### Continuous professional editing
- Exported clip speed is applied to video and audio at render time (0.25x–4x).
- Media3 dependencies use the stable speed-editing API introduced in Media3 1.9.0.
- The project remains a single continuously developed codebase; there are no phase/version folders.

### Professional motion timeline
The editor now includes persistent video keyframes for scale and rotation. Keyframes are interpolated during real Media3 Transformer export, while text keyframes remain independently editable.

### Export reliability
Before rendering, the exporter validates source media accessibility and encoder capability so unsupported codec requests or missing media fail early with a useful error instead of failing deep into rendering.
