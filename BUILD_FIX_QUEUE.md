# VideoForgeNative — Build Fix Queue

This file preserves the feature order while the project is in build-fix mode.

## Features already implemented and preserved
1. Multi-layer PIP
2. Professional aspect/crop presets
3. Audio extraction
4. Freeze frame
5. Expanded licensed Arabic/English fonts
6. Professional text styling controls
7. Typography presets
8. Mosaic / Region Pixelate (preview + export)

## Deferred until build/lint is clean
- Direct drag/resize manipulation of the Mosaic region.
- Remaining planned effects/transitions.
- Further timeline/keyframe refinements.
- Final UI polish.
- Full build/lint verification and fixes.
- Release/APK validation.
- AdMob only after the editor is fully completed and tested.

## Current build-fix findings
- CI workflow exists, but no workflow run/status is currently reported for the latest commit.
- The project should be compiled locally/through CI before claiming build success.
- Known earlier source inspection indicated missing dialog declarations/references such as HistoryDialog, EditToolsDialog, TrimDialog, CropDialog and LayerManagerDialog; these must be verified and fixed during this phase.

Do not treat this queue as a replacement for the roadmap; it is a temporary checkpoint so feature order is not lost.
