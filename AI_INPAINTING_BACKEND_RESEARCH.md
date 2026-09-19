# AI Studio — Mask-conditioned generation research

**Date:** 2026-09-19  
**Status:** Research/architecture decision; no inpainting implementation is claimed by this document.

## Current implementation

`LocalAiImageGenerator` runs MediaPipe Image Generator with Stable Diffusion 1.5 and its Canny EDGE conditioning plugin. It accepts a prompt, source image, conditioning type, iteration count, and seed. `AiMaskEditor` lets the user paint/erase a selection, and `AiRealEditEngine.compositeByMask` composites pixels from the generated full-frame result through that mask.

This current sequence is a masked composite, **not mask-conditioned inpainting**: the generator is not given the painted mask as an inpainting constraint. The output inside the selection may therefore be inconsistent with the surrounding image.

## Verified API capability boundary

Google's MediaPipe Image Generator documentation describes text-to-image generation, optional condition images, and the EDGE, FACE, and DEPTH plugin conditions. The documented plugin types do not include an inpainting mask input. Therefore the current MediaPipe backend cannot be represented as a true inpainting engine without changing the inference backend or adding a separately verified mask-aware model/API.

References:
- https://developers.google.com/edge/mediapipe/solutions/vision/image_generator
- https://developers.google.com/edge/mediapipe/solutions/vision/image_generator/android

## Required design for real inpainting

A backend must explicitly accept, at minimum:

1. The original image.
2. A mask aligned to the original image, with a documented polarity (selected pixels to regenerate vs preserve).
3. A text prompt, and optionally a negative prompt and generation strength.
4. A result image whose regenerated region is guided by surrounding context.

The app must retain the original image and mask separately, validate dimensions/polarity, and composite or accept the returned image according to the backend contract. Feathering and mask expansion must be deliberate options rather than accidental coordinate side-effects.

## Candidate directions

- **On-device mask-aware diffusion:** investigate an Android-compatible inference stack and a model specifically supporting inpainting. Before integration, verify Android ABI/device requirements, model format, memory footprint, commercial/use license, and whether inference can run without network access. Do not assume the existing MediaPipe model can be reused as an inpainting model.
- **Optional remote inpainting provider:** technically possible only with explicit provider selection, credential handling, network disclosure, pricing/quotas, privacy consent, and a secure backend design. Do not embed secret API keys in the APK or silently send user photos to a service. This does not satisfy an offline/free promise by itself.

## Acceptance criteria before calling it inpainting

- The model/backend consumes the actual user mask, not just a Canny edge map.
- Unselected pixels remain protected according to the documented backend behavior.
- Selected content is generated in context with adjacent pixels.
- Test cases cover a small object, a large region, boundary strokes, erasing, empty masks, transparent images, portrait/landscape images, and memory/error handling.
- UI, project status, and release notes describe backend, offline/network behavior, model size, and licensing accurately.
- CI build/lint succeeds and runtime testing is performed on supported Android devices.

## Next implementation step

Keep the current mask drawing tool available, but avoid presenting its current generation action as completed inpainting. Implement the inpainting backend behind a small interface only after a candidate is verified against the criteria above; then route remove-object, replace-object, Generative Fill, and Expand through capabilities the selected backend actually supports.