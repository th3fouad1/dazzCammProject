# Spec — Vintage Camera App (Android, working label "Dazz Cam")

Status: DRAFT v0.1 — 2026-11-11
Target: Google Play Store, Android 8.0+ (minSdk 26), targetSdk 36.

## 0. Ground rules

- **Working label only.** The display name "Dazz Cam" lives in exactly one place
  (`app/src/main/res/values/strings.xml` → `app_name`). It MUST be replaced before
  the first Play Store upload (trademark of the original app).
- **No "dazz" in permanent identifiers.** `applicationId`, Kotlin namespace, keystore
  alias and repo name use the neutral id `com.vintagelab.camera`. The applicationId
  is permanent after the first Play upload — confirm it with the owner before release.
- **Nothing extracted from the original app.** No decompiling, no LUTs, frames,
  icons, fonts or sounds taken from it. All looks are our own GLSL shaders that
  reproduce the same *categories* of analog look (35mm film, instant, disposable,
  CCD digicam, fisheye...). Generic analog aesthetics are not proprietary.
- **Looks are data.** Every preset (name, parameters, textures) is defined in one
  asset file `app/src/main/assets/presets/presets.json`, so renaming/retuning is a
  config edit, not a code change.

## 1. The hard constraint (read this first)

The product lives or dies on **preview/capture parity at real-time speed**:

```
CameraX Preview ──► SurfaceTexture (GL_TEXTURE_EXTERNAL_OES)
                       │
                       ▼
              FilmShader (GLSL ES 3.0, per-preset uniforms)
                       │
          ┌────────────┴─────────────┐
          ▼                          ▼
   On-screen preview           Capture path:
   (GLSurfaceView, ~30fps,     ImageCapture full-res JPEG/YUV
    preview resolution)        ──► decode to bitmap ──► upload texture
                               ──► SAME FilmShader at full resolution
                               ──► offscreen FBO ──► glReadPixels
                               ──► compose frame/date stamp ──► JPEG
                               ──► MediaStore (Pictures/<AppName>)
```

Rules:
- One shader source, one parameter set, used by both paths. Resolution-dependent
  effects (grain size, blur radius, vignette, light-leak position) are expressed in
  **normalized units** (fraction of short side), never pixels, so the full-res
  result matches the preview.
- Random effects (grain seed, light-leak choice, dust placement) are seeded at the
  shutter press and the seed is passed to the capture render, so what you saw ≈
  what you get.
- Performance budget (Performance Benchmarker validates on a mid-range device):
  - Preview: ≥ 30 fps sustained, shader ≤ 8 ms/frame at 1080p preview.
  - Shutter-to-saved: ≤ 2.5 s for a 12 MP capture.
  - Full-res render that exceeds `GL_MAX_TEXTURE_SIZE` or memory → tiled render
    (fallback: cap at 4096 px long side for v1, documented).

## 2. MVP scope (v1)

In:
1. Live filtered camera preview, back/front switch, tap-to-focus, flash (off/on/auto).
2. **6 looks** (our own shaders, categories of the original's popular cameras):
   | id | category | key traits |
   |----|----------|-----------|
   | `classic35` | 35mm consumer film | warm highlights, lifted blacks, medium grain, slight vignette |
   | `disposable` | disposable camera | heavy grain, strong vignette, flash-hot center, green-ish shadows, soft focus |
   | `instant` | instant film | low contrast, cyan shadows/creamy highlights, white instant frame |
   | `ccd` | 2000s CCD digicam | cool cast, sharpen, slight bloom, digital noise, orange date stamp |
   | `bw400` | B&W film | high-contrast mono, coarse grain |
   | `fisheye` | fisheye toy camera | barrel distortion + circular vignette |
3. Effects layer: grain, vignette, light leaks (procedural, ~4 variants), dust
   (procedural), date stamp (orange 7-seg style, our own font rendering or bundled OFL font).
4. Frames: none / instant white / film-strip border (drawn procedurally).
5. Aspect ratios: 4:3, 1:1, 16:9. Self-timer (3s/10s).
6. Import a photo from gallery (Photo Picker) → apply current look → save.
7. Save to MediaStore; in-app mini gallery of our own captures (MediaStore query).

Out of v1 (later): video recording, accessories (prisms, lenses), subscription /
billing, more looks, settings sync, cloud.

## 3. Architecture

Single-module app to start (split later only if build time demands it).

```
com.vintagelab.camera
├── ui/            Jetpack Compose screens: CameraScreen, LookPicker, Gallery, Editor(import)
├── camera/        CameraController (CameraX lifecycle, Preview + ImageCapture use cases)
├── gl/            EglCore, GlPreviewRenderer, OffscreenRenderer, ShaderProgram, textures
├── looks/         Preset model, PresetRepository (parses presets.json), uniforms mapping
├── capture/       CapturePipeline (decode, EXIF rotation, full-res render, compose, encode)
├── storage/       MediaStoreSaver, GalleryRepository
└── App.kt / MainActivity.kt
```

- UI: Jetpack Compose + a `AndroidView(GLSurfaceView)` for the viewfinder.
- State: one `CameraViewModel` (StateFlow) holding selected look, flash, ratio, timer.
- Threading: GL on its own thread (GLSurfaceView render thread for preview; a
  dedicated EGL context on a background HandlerThread for capture/import renders).
  Capture never blocks the preview thread.
- Rotation/mirroring: transform matrix from SurfaceTexture applied in the vertex
  shader for preview; EXIF orientation + front-camera mirror handled explicitly in
  capture path. Unit tests cover the orientation math.
- Permissions: CAMERA only. Saving via MediaStore (no storage permission on API 29+;
  WRITE_EXTERNAL_STORAGE with maxSdkVersion=28 for API 26–28).

## 4. Shader design

One "uber" fragment shader with uniforms, not one shader per look:
`uTone` (curves via 1D LUT texture generated from preset control points),
`uSaturation`, `uTemperature/uTint`, `uShadowTint`, `uHighlightTint`,
`uFade`, `uGrainAmount/uGrainSize`, `uVignette`, `uBloom`, `uSharpen`,
`uDistortion` (fisheye), `uLeakTex/uLeakAmount`, `uSeed`.
Compiled twice: `samplerExternalOES` variant (preview) and `sampler2D` variant
(capture/import) from the same source via a `#define`.

## 5. Build & tooling

- Kotlin 2.x, AGP 8.x, Gradle wrapper 8.14.3 (cached locally), JDK 17.
- Android SDK at `%LOCALAPPDATA%\Android\Sdk` (platforms 34–37, build-tools 36).
- `./gradlew assembleDebug` must pass on every merge; `./gradlew test` for unit tests
  (preset parsing, orientation math, uniform mapping).
- Release signing + Play listing handled later by Mobile Release Engineer.

## 6. Delivery plan

Phase A — **Vertical slice (serial, one implementer):** project skeleton, CameraX
preview → GL → `classic35` shader on screen → capture → same shader full-res →
save to MediaStore. `assembleDebug` green. Reviewed by me + Codex adversarial review.

Phase B — **Parallel modules on top of the slice** (worktrees): remaining 5 looks +
effects; frames/date stamp compositor; UI/UX (look picker, controls, gallery,
import) from Visual Storyteller's design spec; perf pass by Performance Benchmarker.

Phase C — Hardening: device testing, orientation matrix, low-memory, permission
flows, then release prep (rename, icon, signing, listing).

## 7. Roles

- Planner / reviewer / integrator: Claude (main session).
- Plan review: egm Goldfish reviewers via `egm:egm-synthesizer`.
- Architecture check: Software Architect. Perf budget & profiling: Performance Benchmarker.
- UI/UX design spec: Visual Storyteller (only design agent installed).
- Implementation: general-purpose agent(s), one per phase-B module.
- Adversarial review: Codex on every phase deliverable.

## 8. Open questions

- Q1: Physical Android test device available? (Emulator camera cannot validate the GL pipeline.)
- Q2: Final app name + applicationId before first upload.
- Q3: Video in v2 — confirm.
