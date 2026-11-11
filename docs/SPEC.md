# Spec — Vintage Camera App (Android, working label "Dazz Cam")

| | |
|---|---|
| Status | DRAFT v0.3 — 2026-11-11 |
| Owner | Project owner (the user) — approves looks, names, scope |
| Integrator / reviewer | Claude (main session) |
| Related | `docs/reviews/spec-review-v0.1.md` (Goldfish review), `docs/DESIGN.md` (pending, Visual Storyteller), `third_party/t3mujinpack/NOTICE.md` |
| Changelog | v0.1 initial · v0.2 applies Goldfish review B1–B7, S1–S18, owner decisions D7/D8 · v0.2.1 Phase A implementation notes (§6.4, §7.2, §9, §10) · v0.2.2 Sprint A.1: contract K1–K17 (§6.2), next-shot rule (§5.3), capture memory (§6.5) · v0.3 v1.1: import + editor (§6.7), in-app gallery (§6.8), negatives (§6.9), D19–D24, privacy note (§4) |

> **Read §5 (pipeline & parity) first** — it is the hard constraint.

## 1. Problem statement

Phone photos look clinical. Users want shots that look like 35mm, disposable,
instant or 2000s-digicam photos **while framing**, not as a filter added later.
v1 succeeds when a user can pick a look, see it live at ≥30 fps, press the shutter
and find the same look (same framing, colour, frame and date stamp) in their
phone's gallery within ~2.5 s.

## 2. Fixed constraints

1. Android only; Google Play distribution.
2. Kotlin + CameraX + OpenGL ES 3.0 + Jetpack Compose.
3. Nothing extracted from any other app (no decompiling, no LUTs/frames/icons/fonts/sounds taken from one).
   Colour science comes from MIT-licensed t3mujinpack HaldCLUTs + our own shaders.
4. Display label "Dazz Cam" is temporary: it lives only in `res/values/strings.xml` → `app_name`
   and MUST be replaced before the first Play upload. No "dazz" in `applicationId`
   (`com.vintagelab.camera`, Proposed — D1), namespace, keystore, save folder or repo name.

## 3. Goals, deferred, non-goals

### 3.1 Goals (v1)
1. Live filtered preview; back/front switch; tap-to-focus; pinch zoom; flash off/on/auto (back) and screen flash (front, if supported by pinned CameraX, else hidden).
2. Six looks (see §3.4). Effects: grain, vignette, procedural light leaks (exactly 4 variants), procedural dust, centre glow, soft focus / bloom, fisheye distortion.
3. Frames: none / instant white / film strip. Date stamp (orange 7-segment, procedural).
4. Ratios 4:3, 1:1, 16:9. Self-timer off/3 s/10 s.
5. Save JPEG to MediaStore `Pictures/<SAVE_FOLDER>`; last-shot thumbnail opens it (v1: `ACTION_VIEW`; v1.1: our own viewer, §6.8).
6. In-app "Open-source licenses" screen (t3mujinpack MIT).

### 3.2 Deferred (v1.2+)
Video, accessories (overlay effects imitating physical lens add-ons), more looks, billing.

**Delivered in v1.1 (owner decision D7, review S8/S9 Option B):** photo import and editor (§6.7), in-app gallery (§6.8), re-develop from stored
negatives (§6.9). Out of scope for v1.1: batch import, video import, cropping/rotating in the editor, cloud backup of negatives, editing photos not saved by this app
other than by importing them.

### 3.3 Non-goals
RAW, manual ISO/shutter, accounts/cloud, ads/analytics, user-editable presets,
reproducing any specific third-party app's exact looks or UI.

### 3.4 v1 looks
| id | base film LUT (t3mujinpack) | effect character |
|----|----|----|
| `classic35` | Kodak Gold 200 | slight vignette, fine grain |
| `disposable` | Fuji Superia 400 | heavy grain, strong vignette, centre glow (flash-hot), soft focus |
| `instant` | Fuji Pro 400H | faded, low contrast, default frame `instant_white` |
| `ccd` | Kodak Ultra Max 400 | cool shift, sharpen, bloom, chroma noise, date stamp on by default |
| `ccd2` | Fuji Superia 200 (0.6) | warmer/greener early-2000s digicam: temperature +0.08, tint -0.06, sharpen 0.6, bloom 0.25, chroma noise, low vignette, date stamp on by default |
| `bw400` | Kodak Tri-X 400 | coarse grain, vignette |
| `fisheye` | Kodak ColorPlus 200 | barrel distortion + circular mask |

| `slide` Chrome | Fuji Velvia 50 (0.85) | punchy slide, deep blacks, cool shadows, low grain, default frame `slide_mount` |
| `premium` Pastel | Kodak Portra 400 | clean pastel skin tones, fine grain 0.2, soft vignette |
| `toy120` Toy | Fuji Superia 200 | heavy circular vignette 0.7, soft focus 0.3, leak prob 0.7, saturated, barrel 0.1, frame `film120` |
| `tlr` Twin | Kodak Ektar 100 | rich colour, fine grain, gentle vignette, frame `film120` |
| `square` Square | Fuji Provia 100F | instant-square feel: fade 0.2, warm highlights, frame `instant_square` |
| `fine_mono` Mono 100 | Ilford Delta 100 | smooth fine-grain B&W, high midtone contrast |

Display names are our own string resources (film brand names are trademarks; used only in file names).
Exact numbers live in `presets.json`; Appendix A gives `classic35`. A look is **accepted** when
the owner approves its reference render `docs/looks/<id>.png`; that PNG becomes a golden file.

## 4. Privacy & Play

- No INTERNET permission, no analytics/crash/ads SDKs. Data Safety: no data collected or shared.
- Permissions: CAMERA (runtime) only. **minSdk 29** (owner decision D8) → no storage permission ever. v1.1 import uses the system Photo Picker and the gallery
  lists only rows this install owns, so `READ_MEDIA_IMAGES` / `READ_EXTERNAL_STORAGE` are never requested.
- **Negatives stay on the device** (v1.1, §6.9): the re-develop source and its settings live in app-private `filesDir`, are never uploaded or shared, are removed with
  their photo or when the app is uninstalled (`allowBackup=false`, excluded from data extraction), and can be switched off with "Keep negatives". They hold no GPS:
  the camera path never keeps it, the import path strips it (the working source is re-encoded without metadata; only the copied tag whitelist is stored). Data Safety
  answer stays "no data collected or shared".
- Output EXIF via `androidx.exifinterface`: copy DateTimeOriginal, OffsetTime*, Make, Model,
  ExposureTime, FNumber, ISOSpeedRatings, FocalLength; set Orientation=1 and Software=app_name. Never write GPS (also for imports and re-develops).
- targetSdk 36: `enableEdgeToEdge()`, `WindowInsets.safeDrawing` + cutout padding, back via `BackHandler`,
  layout must survive landscape/multi-window (large-screen orientation locks are ignored).
- Release prerequisites (Mobile Release Engineer, Phase C): privacy policy page, content rating,
  16 KB page alignment check, listing must not name other camera apps.

## 5. Pipeline & parity (the hard constraint)

```
                  ┌─────────────── one crop (ViewPort) ───────────────┐
CameraX Preview ─► SurfaceTexture (OES)            ImageCapture JPEG (in memory, ~12 MP)
                       │                                  │ decode (sRGB) + rotationDegrees
                       ▼                                  ▼
               Pass 0 Normalize (OES)            Pass 0 Normalize (sampler2D)
                       └──────────────┬───────────────────┘
                                      ▼  RGBA8 sRGB working texture, cropped + upright
                         Pass 1 Geometry (fisheye)
                         Pass 2 Pyramid (short side 512/256/128)
                         Pass 3 Combine (3D LUT colour, bloom/soft, sharpen,
                                         glow, leak, vignette, dust, grain)
                         Pass 4 Overlay (frame + date stamp)
                       ┌──────────────┴───────────────────┐
                       ▼                                  ▼
                 GLSurfaceView                  FBO → glReadPixels (Y-flip in Pass 4)
                                                → ARGB bitmap → JPEG q92 + EXIF
                                                → MediaStore (IS_PENDING)
```

### 5.1 Parity definition & acceptance
**Definition.** For the same normalized input, look and seed, the graph rendered at full resolution
then area-downscaled matches the graph rendered at preview resolution:
- grain disabled: mean abs diff ≤ 3/255 per channel, p99 ≤ 12/255;
- grain enabled: 64×64 block luminance mean and std within ±5 %;
- vignette / leak / frame / chart-corner positions: shift ≤ 1 % of short side.

ISP differences between the preview stream and ImageCapture, and flash, are outside parity.

**Test.** `ParityTest` (androidTest) feeds a procedural chart (checker + gradients) as `sampler2D`
through the graph at 1440×1080 and 4032×3024 for every preset. A device check confirms Pass 0 OES
vs sampler2D conversion of the same chart within the same tolerance.

### 5.2 Parity rules
- **Framing.** Preview and ImageCapture are bound in one `UseCaseGroup` with a shared `ViewPort`
  for the selected ratio (D4). Crop rect from `SurfaceRequest.TransformationInfo.cropRect` (preview)
  and `ImageProxy.cropRect` (capture). Ratio change rebinds (brief interruption accepted). 1:1 = centre crop of 4:3.
- **Normalized units** = fraction of the short side of the **final cropped output**, before frame margin.
  Every resolution-dependent quantity (grain scale, radii, leak position, frame, stamp) uses them.
- **Same input.** Pass 0 is the only source-specific pass; it outputs gamma-encoded sRGB in both paths
  (no sRGB EGL surfaces/FBOs; decode with `inPreferredColorSpace = SRGB`). A per-device preview tone
  offset in Pass 0 is reserved for Phase C calibration (identity by default).
- **Seed.** `CameraViewModel` owns a look seed (leak presence/variant/placement, dust layout) shown in
  preview, frozen into the capture job at shutter (after timer), re-rolled after each capture or on "shuffle".
  Preview grain animates via `uGrainTime`; capture uses the frozen seed and the last presented `uGrainTime`.
- **Grain** is continuous noise (gradient/value noise) evaluated in normalized coordinates — never a per-pixel hash.
- **Spatial effects** read the fixed-size pyramid (Pass 2), so their cost and look are resolution-independent.
  If bloom cannot meet parity by end of Phase B, it leaves v1.

### 5.3 Performance budget (starting values; reference device = owner's phone, model recorded in D9)
- Preview: ≥ 30 fps for 95 % of frames over 60 s, re-measured after 10 min; GPU time via
  `EXT_disjoint_timer_query` where available. Render size = min(view, stream), dynamic scale down to 0.6× allowed.
- Shutter-to-saved (tap after timer → `IS_PENDING=0`, median of 10 at ~12 MP) ≤ 2.5 s:
  capture ≤ 600 ms, decode ≤ 300, upload+render ≤ 400, readback ≤ 150, compose ≤ 150, encode+write ≤ 700.
- Next shot: accepted as soon as the previous ImageProxy is closed (bytes copied), while render/save continue.
  At most 1 render job runs and at most 1 decoded job waits; when that queue is full the shutter shows "developing".
  During a capture render preview may drop to ≥ 15 fps.

## 6. Architecture

### 6.1 Modules (single Gradle module `:app`)
```
app/src/main/java/com/vintagelab/camera/
├── MainActivity.kt, App.kt
├── ui/        CameraScreen.kt, LookPicker.kt, Controls.kt, PermissionScreen.kt, LicensesScreen.kt, AppRoot.kt (routes)
│              CameraViewModel.kt, CameraUiState.kt
├── editor/    ImageImporter.kt, ExifOrientation.kt, EditorViewModel.kt, EditorScreen.kt   (v1.1, §6.7)
├── gallery/   GalleryQuery.kt, GalleryRepository.kt, GalleryViewModel.kt, GalleryScreen.kt (v1.1, §6.8)
├── negatives/ NegativeSettings.kt, NegativeStore.kt, NegativeLru.kt                        (v1.1, §6.9)
├── camera/    CameraController.kt (UseCaseGroup, ViewPort, focus/zoom/flash)
├── gl/        EglCore.kt, ShaderProgram.kt, GlPreviewRenderer.kt, OffscreenRenderer.kt,
│              RenderGraph.kt (passes 0–4), Textures.kt, LutLoader.kt
├── looks/     Preset.kt, PresetRepository.kt, UniformMapper.kt, CropMath.kt, LookLutBaker.kt
├── capture/   CapturePipeline.kt, FrameGeometry.kt, DateStamp.kt, ExifWriter.kt
└── storage/   MediaStoreSaver.kt, StorageConfig.kt (SAVE_FOLDER constant)
app/src/main/assets/
├── presets/presets.json
├── luts/<lutId>.png          (33³ LUT as 1089×33 PNG, generated by tools/, see §6.4)
├── shaders/*.glsl            (pass0_oes, pass0_2d, geometry, pyramid, combine, overlay, effects/*.glsl)
└── licenses/t3mujinpack.txt
```

### 6.2 State
```kotlin
data class CameraUiState(
  lookId: String = "classic35", lens = BACK, flash = OFF, ratio = R4_3, timer = OFF,
  frame: FrameId = <preset default>, dateStamp: Boolean = <preset default>, zoom: Float = 0f,
  lookSeed: Long, captureState: Idle | Countdown(n) | Processing | Saved(uri) | Error(msg),
  permission: Granted | Denied | PermanentlyDenied, lastShot: Uri?)
```
Intents: SelectLook, ToggleLens, CycleFlash, SetRatio, SetTimer, CancelTimer, SetFrame,
ToggleDateStamp, Shuffle, Shutter, TapFocus(x,y), Zoom(f).

*Contract additions (Sprint A.1, DESIGN §10.4 K1–K17; code: `ui/CameraUiState.kt`):*
- State: `zoomRatio`, `zoomRange` (K1); `focus: FocusUi?(nx, ny, Focusing|Locked|Failed, seq)` (K2);
  `camera: Opening|Ready|Error(InUse|Disabled|Fatal|GpuUnsupported)` (K3); `captureState.Error(kind: SaveFailed|StorageFull|DevelopFailed, seq)`
  replaces `Error(msg)` (K4); `caps: CameraCaps(hasBack, hasFront, hasFlashUnit, screenFlash)` (K5);
  `looks: List<LookUi(id, displayNameRes, hasRandomFx)>` (K6); `renderMode: Normal|Passthrough` (K7);
  `imagePortrait` from the ViewPort's display rotation (K14); `developing` = shots not yet saved (0..2).
  `Processing` now means "the shutter cannot accept a shot" (§5.3).
- Intents: `PermissionResult(granted, canAskAgain)`, `RefreshPermission(granted)` (K8; refresh only moves to/from Granted);
  `RetryCamera` (K3); `LastShotUnavailable` (K9; cold start seeds `lastShot` with our newest row).
  `TapFocus(x, y)` = normalized [0,1]² in the **image area** (frame margin excluded), upright display orientation (K10).
- Interfaces: `gl/PreviewHost` = `createView(context)` + `events: Flow<FirstFrame|LookApplied|GpuUnsupported|Passthrough>` (K11);
  `CameraController.setScreenFlash(ScreenFlash?)`, front lens uses `FLASH_MODE_SCREEN` when flash ≠ OFF (K12);
  `FrameGeometry.canvasAspect(ratio, frame, imagePortrait)` and `imageRectInCanvas(...)` (K13);
  `MainActivity` forwards camera key (always) and volume keys (not under TalkBack) on key-up (K15);
  `CameraController` follows 180° display flips via `DisplayListener`; under rotation lock capture follows the display (K16).
- K17 (integrator): the capture job uses an immutable `CaptureSettings` snapshot taken at the shutter (after the timer); the seed re-rolls
  at that moment. Lens/Ratio are disabled only while a picture is being taken (rebind would abort it), not while queued jobs render.
- U3: `configChanges` also includes `density|uiMode`. U7: `MediaActionSound.SHUTTER_CLICK` plays on every capture. Persist lookId, ratio, flash, timer,
frame, dateStamp with DataStore Preferences. Selecting a look resets frame/dateStamp to the preset defaults.

### 6.3 GL lifecycle & threading
- **Preview.** `GlPreviewRenderer` creates OES texture + SurfaceTexture on the GL thread inside
  `Preview.SurfaceProvider`; `setDefaultBufferSize(request.resolution)`; `provideSurface(surface, glExecutor)`;
  release only in the result callback. `RENDERMODE_WHEN_DIRTY`, `requestRender()` from `onFrameAvailable`;
  `updateTexImage` only on the GL thread. A `LifecycleEventObserver` in the `AndroidView` factory forwards
  onPause/onResume; on context loss, reset the surface provider so CameraX issues a new `SurfaceRequest`.
- **Capture.** Separate, non-shared ES 3.0 context (pbuffer) on a `capture-gl` HandlerThread; compiles its
  own programs/LUTs from the same `PresetRepository` (D5). Passes separated by `glFlush`.
- **Shaders.** ESSL 3.00. `ShaderProgram` assembles: line 1 `#version 300 es`, then variant block, then body.
  Pass 0 OES uses `#extension GL_OES_EGL_image_external_essl3 : require`; if absent at runtime, Pass 0 is
  compiled as ESSL 1.00 with `GL_OES_EGL_image_external`. Manifest: `<uses-feature android:glEsVersion="0x00030000" android:required="true"/>`.
- **Transforms.** Preview = SurfaceTexture matrix (on texture coords) × correction from
  `TransformationInfo` (rotation, crop, mirroring) — TransformationInfo is source of truth.
  Capture rotation from `ImageProxy.imageInfo.rotationDegrees`. Front preview mirrored; saved front photos **not** mirrored.
- **Capture work** runs in an Application-scoped coroutine; at most 1 job in flight (shutter shows "developing…").

### 6.4 Colour: 3D LUT
- `tools/haldclut_to_lut33` (Kotlin/JVM or Python script, committed) resamples each level-12 HaldCLUT
  (1728², 144³) to a 33³ cube stored as a 1089×33 RGB PNG in `assets/luts/`.
- On preset change, `LookLutBaker` (GPU, both contexts) bakes **film LUT ∘ parametric colour ops**
  (exposure, tone curve, saturation, temperature/tint, shadow/highlight tint, fade, mono) into one
  33³ `sampler3D` (RGBA8, LINEAR, CLAMP_TO_EDGE). Combine pass samples it once → identical colour in both paths.
- *Phase A notes.* (1) The converter is `app/src/test/java/com/vintagelab/camera/tools/HaldClutConverter.kt`
  (pure JVM with a minimal PNG codec, because the Android unit-test classpath has no `javax.imageio`). Regenerate with
  `./gradlew :app:testDebugUnitTest -PgenerateLuts --tests '*GenerateLuts*'`. Image layout lives in one place,
  `looks/LutLayout.kt`: entry (r,g,b) → x = r + 33·g, y = b. (2) `LookLutBaker` bakes on the **CPU** (pure Kotlin,
  ~36k entries) rather than the GPU, so both contexts upload byte-identical cubes and the bake is unit-testable.
  Bake order: exposure (linear light) → film LUT mixed by `lutStrength` → temperature/tint → tone r/g/b then all →
  saturation → shadow/highlight tint → fade → mono.

### 6.5 Capture
- `ImageCapture` `OUTPUT_FORMAT_JPEG`, `takePicture(executor, OnImageCapturedCallback)` — no unfiltered file
  is ever written. `CAPTURE_MODE_MINIMIZE_LATENCY` unless benchmarks justify otherwise. Close `ImageProxy` right after decode.
- `ResolutionSelector`: `ResolutionStrategy(Size(4000,3000), FALLBACK_RULE_CLOSEST_LOWER)` + aspect strategy.
  Render long side clamped to `min(GL_MAX_TEXTURE_SIZE, GL_MAX_RENDERBUFFER_SIZE, GL_MAX_VIEWPORT_DIMS, 4096)`.
  **No tiling in v1** (D3).
- Memory: upload then `recycle()` the decoded bitmap; RGBA8 FBO; reused direct ByteBuffer readback;
  one ARGB_8888 output bitmap. ≤ 2 full-size buffers alive; target peak ≤ 160 MB.
- *Sprint A.1:* the readback buffer is allocated per shot and dropped before compression; JPEG bytes stream straight into the
  pending MediaStore descriptor (no byte-array copy). The render cap is `min(GL limits, capForMemory(memoryClass))`
  (half the heap class for the two 8 B/px buffers, clamped 2048–4096). The whole render/compress/save attempt is retried once
  at 2048 on OOM. `update(IS_PENDING=0)` must return 1 row, otherwise the row is deleted and the save fails.

### 6.6 Storage (API 29+ only)
Insert with `RELATIVE_PATH = Pictures/<SAVE_FOLDER>` and `IS_PENDING=1`, write, set `IS_PENDING=0`;
on startup delete our own still-pending rows; on failure delete the pending row.
Filename `IMG_yyyyMMdd_HHmmss_SSS.jpg`. `SAVE_FOLDER` is a constant in `StorageConfig.kt` (fixed before release, D1).

### 6.7 Import and Editor (v1.1)
Original UI; nothing imitates another app. The Editor is the camera's render graph fed by a stored photo instead of CameraX.

**Entry.** ⋯ menu › "Import photo" (camera) or the Gallery top bar. `ActivityResultContracts.PickVisualMedia(ImageOnly)` is registered in `AppRoot`
(it must outlive the menu). The system Photo Picker needs **no storage permission**; on API 29-32 the manifest carries the Play-services backport entry
(`com.google.android.gms.metadata.ModuleDependencies`, `photopicker_activity:0:required`), without it the contract falls back to `ACTION_OPEN_DOCUMENT`.

**Decode** (`editor/ImageImporter`). `ImageDecoder` (software allocator, sRGB target, so JPEG, HEIC, PNG and WebP work). `ImageDecoder` applies the
EXIF orientation itself (all eight values); `editor/ExifOrientation` (pure, "mirror then rotate clockwise", unit tested for the 8 cases) is used by the
`BitmapFactory` fallback when `ImageDecoder` fails. The long side is capped at the **render cap** of `CapturePipeline.renderCap()` (same memory-budgeted
`min(GL limits, capForMemory)` as a capture; sub-sampled first, then scaled). The result is re-encoded as JPEG q95 **without any metadata**, so the working
source and everything derived from it carry no GPS. Only a whitelist (`ExifWriter.COPIED_TAGS`: date, make, model, exposure, aperture, ISO, focal length)
is kept as a map for the output EXIF. The original is only ever read.

**Preview** (same graph, same context). The editor holds the q95 source bytes and a downsampled copy (about 720-1440 px long side, power-of-two
sub-sampling). Every parameter change renders that copy through `CapturePipeline.renderEditPreview`, that is `OffscreenRenderer.render` (the capture-gl
context, Passes 0-4) with `recycleInput=false`, and shows the resulting bitmap in Compose. Renders are conflated (`collectLatest`: the newest parameters win)
and the LUT bake runs on the GL thread, never on the main thread. The baked-LUT cache in `LutLoader` is bounded (40 entries) because sliders create many
distinct adjusted looks.

**Controls.** Look carousel (the camera's `LookPicker`), frame cycle (frames that force a ratio are skipped for half-frame/grid sources), date stamp, prism,
shuffle (new seed), **exposure** slider +-1 EV and **temperature** slider +-0.5, both snapped to 0.05 and applied as `EditAdjust.apply(preset, ev, temp)`:
`preset.copy(exposure += ev, temperature += t)` through the normal 33^3 bake, so preview and save are identical by construction. A new look seed is
rolled when an import opens. Selecting a look resets frame and stamp to its defaults, as in the camera. The date stamp uses EXIF `DateTimeOriginal` of the
import if present, otherwise now.

**Save.** `CapturePipeline.processEdit(EditRequest)`: source decoded afresh per attempt (the renderer consumes it; the OOM retry at 2048 needs a smaller
one), rendered at the cap through `OffscreenRenderer`, JPEG q92 + EXIF (`ExifWriter.plan`: original date kept, Orientation=1, Software, no GPS) into a NEW
MediaStore row. The file name and `DATE_TAKEN` use the save time so the photo sorts first; stamp and EXIF keep the original date. The develop runs in
`appScope`, so leaving the screen does not lose it. On success the Gallery opens on the new photo. Back is disabled while saving.

**States.** Idle (no session, e.g. after process death: back to Camera) / Loading / Ready / Saving / Failed (open failed or negative missing: message and back).
Preview and save errors show a snackbar and keep the editor open.

### 6.8 In-app gallery (v1.1)
- **Query** (`gallery/GalleryQuery`, pure and unit tested): rows of `Pictures/<SAVE_FOLDER>/` that **this install owns** (`OWNER_PACKAGE_NAME`), finished
  (`IS_PENDING=0`), `DATE_TAKEN DESC, _ID DESC`, pages of 60. API 29 puts `LIMIT n OFFSET m` in the sort string; API 30+ rejects that and uses the query
  `Bundle` (`QUERY_ARG_LIMIT/OFFSET`). **`READ_MEDIA_IMAGES` is never requested**; the manifest still declares CAMERA only.
- **Grid**: adaptive cells, thumbnails from `ContentResolver.loadThumbnail` (256 px) with a 96-entry memory cache; next page when the last visible cell is within 12 of the end.
- **Viewer**: full-screen `HorizontalPager`, decoded at screen scale (long side at most 2048, never full resolution). Actions: **Share**
  (`ACTION_SEND`, `image/jpeg`, content URI + `ClipData`, `FLAG_GRANT_READ_URI_PERMISSION`, chooser), **Re-develop** (only when a negative exists), **Delete**.
- **Delete**: API 30+ `MediaStore.createDeleteRequest` and `StartIntentSenderForResult`; `RESULT_OK` already means the row is gone. API 29: `ContentResolver.delete`,
  on `RecoverableSecurityException` the user-action intent sender, then a retry. After a delete the negative of that photo is removed and the camera's last-shot thumbnail is refreshed.
- **Entry**: ⋯ menu › Gallery, and the **last-shot thumbnail**, which now opens our viewer on that photo (no `ACTION_VIEW`).
- **Empty state / after reinstall**: a reinstalled app no longer owns its old rows (S8), so the list is empty. The empty state explains it and offers
  "Open in Photos" (`Intent.makeMainSelectorActivity(ACTION_MAIN, CATEGORY_APP_GALLERY)`, falling back to `ACTION_VIEW` on `image/*`).

### 6.9 Negatives and re-develop (v1.1)
Every saved photo also keeps its *negative* in app-private storage `filesDir/negatives/<mediaId>/` (`mediaId` = MediaStore `_ID`):
- `source.jpg`: the decoded **upright** input as JPEG q95, long side at most 3072 (`NEGATIVE_LONG_SIDE`). Camera shot: the ViewPort crop rotated upright, **before** any
  forced-ratio frame crop, so the frame can change later. Import / edit: the editor source as it is (copied, not re-encoded, when re-developing).
  Half-frame and grid: the **composed** diptych / 2x2 bitmap (it is the graph's input). **Double exposure** has no neutral composite (the blend happens after
  the colour pass), so it keeps shot 2 as `source.jpg` plus the developed first exposure as `blend.jpg` (q92) and re-develops with the same blend.
- `settings.json` (`negatives/NegativeSettings`, kotlinx.serialization, `schemaVersion` = 1, unknown keys ignored, newer schema = not loadable): `lookId`, `seed`,
  `grainTime`, `frame`, `dateStamp`, `stampTime`, `prism`, `gel` (effective gel after the flash-fired rule), `exposureEv`, `temperature`, `captureMode`
  (`normal|double|half|grid|import`), source size, the copied EXIF tags, `hasBlend`, `createdAtMs`. It stores the look *id* plus the editor deltas, not a preset.
  It is written last (temp file + rename) and is the commit marker.
- **Written once, after a successful save**, in the develop queue (never per OOM attempt) and never failing the save; `Keep negatives` off skips it.
- **Cap and LRU** (`negatives/NegativeLru`, pure): keep at most 200 entries and 1.5 GB; the least recently used (`settings.json` mtime, refreshed when a negative is opened) go first.
  At startup negatives whose photo no longer exists are pruned (only when the id query succeeded and returned photos), then the cap applies.
- **Delete**: deleting a photo in the gallery deletes its negative. A photo deleted elsewhere is cleaned at the next start.
- **Re-develop**: viewer action opens the Editor on the negative with its saved settings (title "Re-develop"); the user may change look, frame, stamp, prism,
  exposure and temperature and saves a NEW photo (new MediaStore row, new negative). The old photo and negative are untouched. The gel cannot be edited; it
  stays as shot.
- **Setting**: "Keep negatives" (⋯ menu, DataStore `keepNegatives`, default on).

## 7. Shader contract

### 7.1 presets.json (schemaVersion 1)
`{ "schemaVersion": 1, "presets": [ {...} ] }`. Every field optional with a neutral default:

| field | type / range | default |
|---|---|---|
| `id` | string, unique, required | — |
| `displayNameRes` | string-resource key | id |
| `lut` | asset id in `luts/` or null | null (identity) |
| `lutStrength` | [0,1] | 1 |
| `exposure` | [-2,2] EV | 0 |
| `tone` | `{r,g,b,all}` each 2–16 `[x,y]` points in [0,1], x strictly increasing, monotone cubic | identity |
| `saturation` | [0,2] | 1 |
| `temperature`, `tint` | [-1,1] | 0 |
| `shadowTint`, `highlightTint` | `{color:"#RRGGBB", amount:[0,1]}` | amount 0 |
| `fade` | [0,1] (lifts blacks) | 0 |
| `monochrome` | bool | false |
| `grain` | `{amount:[0,1], size:[0.0005,0.01] (×short side), chroma:[0,1]}` | amount 0 |
| `vignette` | `{amount:[0,1], radius:[0,1.5], softness:[0,1], shape:"oval"\|"circle"}` | amount 0 |
| `centerGlow` | `{amount:[0,1], radius:[0,1]}` | amount 0 |
| `softFocus` | [0,1] | 0 |
| `bloom` | `{amount:[0,1], threshold:[0,1]}` | amount 0 |
| `sharpen` | [0,1] | 0 |
| `distortion` | `{barrel:[0,1], circularMask:bool}` | 0 / false |
| `lightLeak` | `{probability:[0,1], amount:[0,1], variants:[0..3]}` | probability 0 |
| `dust` | [0,1] | 0 |
| `defaultFrame` | `"none"\|"instant_white"\|"film_strip"\|"instant_square"\|"instant_mini"\|"film120"\|"slide_mount"\|"pano_sprocket"` | none |
| `dateStamp` | `{enabledByDefault:bool, color:"#RRGGBB"}` | false, #FF8A1F |

Validation: out-of-range → clamp + WARN; duplicate/missing id → skip + ERROR; malformed JSON → built-in
identity preset `none`. presets.json holds numbers and ids only; leak/dust are procedural per GL context.

### 7.2 Uniforms & pass order
`looks/UniformMapper.kt` is a pure-Kotlin `Preset → UniformSet` mapping (unit tested). Names/types:
`vec4 uCropRect; float uShortSide; vec2 uOutSize; sampler3D uLookLut; float uLutStrength;`
`vec3 uGrain (amount,size,chroma); float uGrainTime; vec4 uVignette (amount,radius,softness,shape);`
`vec2 uCenterGlow; float uSoftFocus; vec2 uBloom; float uSharpen; vec2 uDistortion;`
`vec4 uLeak (enabled,variant,amount,angle); vec2 uLeakPos; float uDust; float uSeed;`
`int uFrame; vec4 uStampColor; float uStampOn; vec4 uStampDigits[2]` (8 glyph codes).
*Phase A notes.* `uLutStrength` is the mix of the whole baked look against the input and is 1 for every
preset (the preset's `lutStrength` mixes only the film LUT, inside the bake). Extra uniforms beyond this list:
Pass 0 `mat3 uSrcMatrix` (upright image uv → buffer uv, from `looks/CropMath.kt` `SourceTransform`) and
`mat4 uStMatrix` (OES only); Pass 2 `vec2 uFootprint`; Pass 4 `vec2 uCanvasSize`, `vec4 uImageRect`,
`float uFlipY`, `vec3 uFrameColor`, `vec4 uSprocket`, `vec2 uStampGeom` (values from `FrameGeometry`). `uStampDigits`
slot codes: 0–9 digits, 10 blank, 11 apostrophe; slots = [', y, y, M, M, d, d, blank].
Fixed order: geometry/distortion → colour (3D LUT) → soft focus/bloom/sharpen (pyramid) → centre glow →
leak → vignette → dust → grain → overlay (frame, stamp). Phase A implements **every** uniform so Phase B
only edits data and effect function bodies (`shaders/effects/<effect>.glsl`, concatenated at load).

### 7.3 Frames & date stamp
Drawn in Pass 4 in both paths (D6), geometry from `capture/FrameGeometry.kt` (S = short side of the cropped image):
- `instant_white`: sides/top 0.06 S, bottom 0.22 S, canvas grows; colour #F4F1EA.
- `film_strip`: 0.08 S black border top/bottom with sprocket holes (pitch 0.06 S, 0.03×0.04 S rounded rects).
- `instant_square`: image forced 1:1; sides/top 0.07 S, bottom 0.24 S; colour #F4F1EA.
- `instant_mini`: image forced to 0.76 (w/h) in portrait (1/0.76 in landscape); sides/top 0.06 S, bottom 0.18 S; #F4F1EA.
- `film120`: image forced 1:1; 0.05 S near-black border; light-grey frame-number tick marks (0.010 x 0.016 S, pitch 0.12 S,
  every 4th taller) centred in the bottom border; procedural rectangles, no font.
- `slide_mount`: 0.12 S cream (#EDE9DF) mount on all sides, window corners rounded r = 0.03 S (image pixels outside the
  rounded rect become mount colour), faint inner drop shadow (offset 0.006/0.010 S, blur 0.012 S, 30 %) cast onto the image.
- `pano_sprocket` (Sprint B4): image forced to **2.7:1 landscape** (also in a portrait UI: the crop is 2.7:1 wide and the viewfinder
  letterboxes it). The canvas is the image itself (no margin); the film edge is drawn **over** the top and bottom edge of the image:
  a near-black rebate strip 0.09 S high (96 % opaque) holding rounded-rect sprocket holes (0.035 x 0.05 S, radius 0.008 S, pitch 0.075 S,
  row centred in the image width, centre 0.045 S from the edge) that show warm light (#DBCDAD), plus a soft warm light bleed around each
  hole (falloff 0.012 S, confined to the strip). S = image short side. Geometry in `FrameGeometry` (`PANO_*`, `panoHoles`), shader in Pass 4 (`panoOverlay`).
  The look `pano` ("Wide 35": LUT `kodak_ektar_100`, vignette 0.25, grain 0.3) has it as default frame.
- Forced ratios (square, mini, film120, pano): `FrameGeometry.constrainCrop` -> `CropMath.constrainToAspect` shrinks the centred
  source crop to the forced upright aspect. It is applied to the CameraX crop rect in the preview renderer and to the
  scaled crop in `CapturePipeline`, so both paths crop identically; `canvasAspect` ignores the ratio button for these frames.
- Frame codes (`uFrame`): 0 none, 1 instant_white, 2 film_strip, 3 instant_square, 4 instant_mini, 5 film120, 6 slide_mount, 7 pano_sprocket.
- Date stamp: capture time, format `'yy MM dd`, bottom-right of the image area, inset 0.04 S, cap height 0.035 S,
  colour from preset, slight glow. Glyphs: procedural SDF 7-segment in the shader (no font asset).

### 7.4 Capture modes (Sprint B3)
Three user-armed capture behaviours, all on the existing render graph; none adds a pass.

**State.** `CameraUiState.multiMode: None|Double|Half` and `multiExposure: Off|Waiting|HasFirst`
(`capture/CaptureModes.kt`, pure reducers `MultiShotRules`). Arming sets `Waiting`; a stored first shot sets `HasFirst`;
a saved second shot returns to `Waiting` (the mode stays armed). Intents: `ToggleDoubleExposure`, `ToggleHalfFrame`,
`CancelFirstExposure` (discard shot 1, stay armed), `CycleFlashGel`. While armed at most **one** shot is in flight
(`maxOutstanding`=1) so shot 2 can never race the storing of shot 1; the single in-flight render rule (§6.5 / K17) is unchanged.
The shot kind (`Normal|First|Second`) is fixed at the shutter, after the timer, into the K17a snapshot.

**First exposure store.** `CapturePipeline` keeps at most one first exposure: a single ARGB bitmap, long side
<= `STORE_LONG_SIDE` (3072) and <= the memory-budgeted cap, so a third buffer stays affordable. It is dropped (`clearFirst`)
on cancel, mode change, `MainActivity.onStop`, `onTrimMemory >= UI_HIDDEN`, and after an OOM that survives the 2048 retry
while developing shot 2. Dropping while HasFirst returns to Waiting and shows "First shot discarded." (`multiDropSeq`).
A first exposure that finishes developing after the app went to the background is dropped immediately (`bgEpoch`).

**Double exposure.** Shot 1 is rendered with the current look through Passes 0-3 *without* per-image effects (grain, dust,
leak, vignette, glow, gel, frame, stamp; `UniformMapper.firstExposure`), unframed, and read back (not saved).
Shot 2 uploads it as `uBlend` (texture unit 5) and Pass 3 screen-blends it right after colour/bloom and before centre glow:
`c = 1-(1-a)(1-b)` (`fxScreen`). Glow, leak, vignette, dust, grain, frame and stamp therefore run **once** on the combined
image. Ratio and frame are locked between the shots (same image-area aspect). Preview: a <= 512 px copy is the preview
renderer's `ghost`, sampled in Pass 4 at 50 % inside the image rect (`uGhost`, unit 5, one extra texture fetch, mirrored for
the front camera); capture contexts never set it. Shot 2 is the only EXIF source (capture time, lens data) of the saved file.

**Half-frame.** Each half is an upright crop of aspect 0.72 taken with `CropMath.constrainToAspect`
(from a 4:3 capture; ratio is forced to 4:3 and locked while armed). Shot 1 is stored as the upright half bitmap.
`Diptych.layout(cap)`: halves `halfW = 0.72 H` side by side, gap `0.015 H` (H = half height = canvas short side), dark base
#101010, canvas `(1.455 H) x H`, long side <= cap. The composite is drawn on the CPU (`Canvas`) and then goes through the
normal graph with an identity transform, so look, grain, frame and stamp apply once over the whole diptych. Frames that
force a ratio (square, mini, roll film) are skipped while armed (`CameraRules.nextFrame`) because the diptych has its own
shape. No ghost is shown; the chip reads "Half-frame 1/2".
Note: the diptych is landscape (about 1.455:1), as a pair of portrait frames on half-frame film.

**Coloured flash.** `FlashGel` none/warm/red/blue/green is a persisted user setting (DataStore key `flashGel`), not part of
presets.json. At capture it applies only if a flash really fired: EXIF Flash bit 0 of the camera JPEG, or the front screen
flash on (`FlashGel.effective`). Then `uGel` = (rgb, 1): the centre glow is tinted by the gel colour (and forced to at least
amount 0.35 / radius 0.55 if the look has none) and the whole frame gets a cast `mix(c, c*gel, 0.18)` (`fxCenterGlow`).
The front screen-flash overlay is tinted with the gel. The preview is unchanged except for a coloured dot on the flash icon.
In a pair, the gel of shot 2 applies to the combined image; shot 1's flash is not tinted.

**Multi-lens grid (Sprint B4).** `MultiMode.Grid4`, armed from the More menu. One shutter press runs a **burst of 4** pictures,
`MultiShotRules.GRID_INTERVAL_MS` = 150 ms apart; each is accepted as soon as the previous `ImageProxy` is closed (`takePicture`
returns the in-memory JPEG after the close), so the existing capture-queue rules apply and a slow develop only stretches the spacing.
Phases: `Waiting` (armed) -> `Burst` (`gridShots` 0..4) -> `Waiting`. Like `SecondInFlight`, **no cancel and no mode toggle once the
burst started** (`canChangeMode`); the ratio is locked during the burst; any failure aborts the whole grid (one error, `GridAborted` for
late cells). Backgrounding aborts it. Indicator chip `Grid n/4` (n = pictures taken).
Cells: each picture is cropped to the upright camera crop (`constrainToAspect` to the first cell's aspect) and scaled into one small
bitmap (canvas long side <= `STORE_LONG_SIDE` 3072 and <= the memory cap, so 4 cells ~ 28 MB). The last picture composes the 2x2
grid on the CPU (`GridComposite.layout`: cells row-major, gap = 0.01 S, S = canvas short side, dark base #101010) and sends it through
the normal graph with an identity transform: look, prism, grain, frame and date stamp run **once** over the composite; EXIF comes from the last picture.
Frames that force a ratio (square, mini, roll, pano) are skipped while armed (as in half-frame).

**Prism lens (Sprint B4).** `PrismMode` Off/Split/Tri/Halo, a persisted user setting (DataStore key `prism`, not part of a look),
cycled from the More menu. Uniform `vec2 uPrism` = (mode code, amount 1), Pass 1 only (`fxPrism` in `geometry.glsl`, after the barrel
distortion and before the circular mask). All offsets are normalized (image uv or short-side units divided by the aspect), so preview
and capture match; it is off in `ParityTest`. Split: the right-hand side is mirrored about x = 0.60 (soft blend over 0.10 each way, 60 %);
Tri: three copies displaced 0.05 S at 90, 210, 330 degrees, weight 1/3 each; Halo: 9-tap radial zoom blur (to 0.88x) confined to a ring
0.18-0.36 S (in) and 0.52-0.80 S (out) around the centre, 85 %. While half-frame or grid is armed the prism is disabled (preview and capture; `MultiShotRules.effectivePrism`): applied over the composite it would only affect its right side, unlike the per-frame preview.

### 7.5 Decisions (B3, B4)
See D11-D14 and D15-D18 in section 11.

## 8. Behaviour & failure handling

| feature / event | behaviour |
|---|---|
| Tap-to-focus | map tap through inverse preview transform → `SurfaceOrientedMeteringPointFactory(bufW,bufH)` → `FocusMeteringAction`, auto-cancel 3 s, ring indicator |
| Pinch zoom | `CameraControl.setLinearZoom` |
| Timer | large-numeral countdown; shutter tap cancels |
| Shutter while processing | ignored; shutter shows "developing…" |
| Permission denied / permanently denied | rationale screen / Settings link |
| Camera error (`CameraState`) | non-crashing error state, retry on resume |
| Shader compile failure | log `GL_RENDERER` + info log; crash in debug, passthrough in release |
| OOM during capture | retry once at long side 2048, then error |
| Save failure / disk full | delete pending row, error toast |

Manifest: `android.hardware.camera.any` required; `camera.flash`, `camera.autofocus` `required="false"`.

## 9. Build & tooling (pinned)
`gradle/libs.versions.toml`, all versions exact; adding a dependency needs integrator approval:
- Gradle wrapper 8.14.3, AGP 8.13.1, Kotlin 2.2.20 + `org.jetbrains.kotlin.plugin.compose` 2.2.20,
  `kotlin.plugin.serialization` 2.2.20, JDK 17 toolchain.
- compileSdk 36, targetSdk 36, minSdk 29, buildToolsVersion 36.0.0.
- CameraX 1.5.3 (core, camera2, lifecycle), Compose BOM 2025.09.01, activity-compose 1.11.0,
  kotlinx-serialization-json 1.8.1, DataStore Preferences 1.1.7, exifinterface (latest 1.4.x stable),
  coroutines (BOM-compatible), JUnit 4.13.2, Robolectric (latest stable 4.x).
  (AGP/Kotlin/BOM versions are present in the local Gradle cache; others resolved online once.)
- Windows: `.\gradlew.bat`. `sdk.dir` in uncommitted `local.properties`. Git branch `main`.
- *Phase A resolution.* Every pinned version above resolved and compiled unchanged. Resolved for the unpinned
  entries: exifinterface 1.4.1, coroutines 1.10.2. Additions, pending integrator approval: androidx core-ktx 1.17.0,
  lifecycle-runtime-compose / lifecycle-viewmodel-compose 2.9.4, and for androidTest only androidx.test.ext:junit 1.3.0
  and androidx.test:runner 1.7.0. Robolectric is **not** used, because every §9.1 unit test is plain JVM.
  `app/lint.xml` ignores only version-upgrade checks (GradleDependency, NewerVersionAvailable,
  AndroidGradlePluginVersion, OldTargetApi), since this section pins those versions.
- *Sprint A.1 approvals:* core-ktx 1.17.0, lifecycle-runtime-compose / lifecycle-viewmodel-compose 2.9.4, androidx.test ext-junit 1.3.0 /
  runner 1.7.0. IBM Plex Mono Medium/SemiBold (OFL 1.1, from github.com/IBM/plex) bundled in `res/font/`, license in
  `assets/licenses/ibm_plex_mono_ofl.txt`.

### 9.1 Tests
- `PresetParserTest`: valid file; missing field → default; out-of-range → clamp; duplicate id skipped; malformed → identity.
- `ToneCurveTest`: identity points → identity ±1/255; monotone output.
- `UniformMapperTest`: golden `classic35` values.
- `CropRectTest`: 3 ratios × 4032×3024 and 3024×4032.
- `OrientationTest`: 4 rotations × 2 lens facings × 3 ratios.
- `HaldClutConverterTest`: identity HaldCLUT → identity 33³ ±1/255.
- `ParityTest` (androidTest): §5.1.

## 10. Alternatives considered
| decision | chosen | rejected | trade-off |
|---|---|---|---|
| One GL effect on both paths | own pipeline | CameraX `CameraEffect`/`SurfaceProcessor` | built-in parity, but capture resolution/quality and seed control unclear → spike **deferred to Phase B** (see note below); adopt only if it meets §6.5 |
| Shader structure | fixed pass graph + data | one shader per look | fewer programs, one contract; slightly harder to read |
| Colour | baked 33³ LUT | per-pixel maths | identical colour both paths, cheap; bake on preset change |
| Viewfinder | GLSurfaceView | TextureView/SurfaceView + own EGL | less code; must forward lifecycle |
| Capture input | JPEG → Bitmap | YUV / HardwareBuffer | simple, standard; slower decode |
| Oversize | cap 4096 | tiling | no seams; lower max resolution |
| Crop | shared ViewPort | fixed 4:3 + shader crop | CameraX guarantees same crop; rebind on ratio change |
| Capture context | separate | shared with preview | survives preview context loss; compiles twice |
| Presets | JSON | Kotlin table | owner can retune without code; needs parser + tests |

*CameraEffect spike: deferred to Phase B (Phase A ran out of time).* Phase A built the planned own pipeline. The spike
must answer three questions before it can be adopted: (1) does the `IMAGE_CAPTURE` target give full-resolution
output at q92; (2) can the frozen look seed and `uGrainTime` reach the processor per capture; (3) can
`OffscreenRenderer` and `RenderGraph` be reused as the processor body unchanged? If any answer is no, the own
pipeline stays.

## 11. Decisions log
| ID | decision | status | date |
|---|---|---|---|
| D1 | applicationId `com.vintagelab.camera`, SAVE_FOLDER, final app name | Proposed (confirm before first upload) | 2026-11-11 |
| D2 | pass graph §5 / §7.2 | Accepted | 2026-11-11 |
| D3 | no tiling in v1, cap 4096 | Accepted | 2026-11-11 |
| D4 | shared ViewPort crop | Accepted | 2026-11-11 |
| D5 | separate capture EGL context | Accepted | 2026-11-11 |
| D6 | frame/stamp in GL Pass 4, SDF glyphs | Accepted | 2026-11-11 |
| D7 | import + gallery deferred to v1.1 | Accepted (owner) | 2026-11-11 |
| D8 | minSdk 29 | Accepted (owner) | 2026-11-11 |
| D9 | reference device = OnePlus 11 (CPH2449), Snapdragon 8 Gen 2 (SM8550), Adreno 740, Android 15 / API 35 | Accepted | 2026-11-11 |
| D10 | colour = t3mujinpack HaldCLUT (MIT) baked with parametric ops | Accepted | 2026-11-11 |
| D11 | multi-shot first exposure kept as one CPU bitmap (<= 3072 long side), not a GL texture: survives context loss, droppable on OOM/background, reusable for the ghost | Accepted | 2026-11-11 |
| D12 | double exposure = screen blend in Pass 3 before glow/grain; first shot rendered without per-image effects so they run once | Accepted | 2026-11-11 |
| D13 | half-frame = CPU diptych (0.72 halves, 0.015 S gap) fed through the normal graph; ratio forced 4:3 and ratio-forcing frames skipped while armed | Accepted | 2026-11-11 |
| D14 | flash gel is a DataStore user setting, applied only if a flash fired (EXIF bit or screen flash); no presets.json change | Accepted | 2026-11-11 |
| D15 | pano_sprocket draws the film edge over the image (canvas = image), forced 2.7:1; look `pano` "Wide 35" | Accepted | 2026-11-11 |
| D16 | prism lens = Pass 1 geometry effect in normalized units, user setting (not a look field), off in parity tests | Accepted | 2026-11-11 |
| D17 | multi-lens grid = burst of 4 (150 ms), CPU 2x2 composite (gap 0.01 S) through the normal graph; no cancel once started | Accepted | 2026-11-11 |
| D18 | languages: English (default) + French only, always the system language, no in-app picker; `localeConfig` lists en, fr; app-owned string names must not collide with library names (`state_on`/`state_off` renamed `toggle_on`/`toggle_off`, which had picked up the system "Désactivé") | Accepted (owner) | 2026-11-11 |
| D19 | import = system Photo Picker (`PickVisualMedia(ImageOnly)`), `ImageDecoder` (applies EXIF orientation; `ExifOrientation` mapping only for the BitmapFactory fallback), capped at the capture render cap, working source re-encoded JPEG q95 without metadata | Accepted | 2026-11-11 |
| D20 | editor preview = the capture-gl context and the same Passes 0-4 on a downsampled copy, shown as a bitmap in Compose (no third GL surface); sliders are `EditAdjust` deltas on the preset through the normal LUT bake; `LutLoader` baked cache bounded to 40 | Accepted | 2026-11-11 |
| D21 | gallery = own rows only (`OWNER_PACKAGE_NAME` + `Pictures/<SAVE_FOLDER>/` + `IS_PENDING=0`), `DATE_TAKEN DESC, _ID DESC`, 60 per page (sort-string LIMIT on API 29, Bundle on 30+); no `READ_MEDIA_IMAGES`; reinstall = empty + "Open in Photos" (S8 Option B) | Accepted | 2026-11-11 |
| D22 | delete = `createDeleteRequest` on API 30+, `delete` + `RecoverableSecurityException` flow on API 29; the negative goes with the photo | Accepted | 2026-11-11 |
| D23 | negatives in `filesDir/negatives/<mediaId>/` (source.jpg q95 <= 3072 px upright pre-frame-crop, settings.json schemaVersion 1, optional blend.jpg); newest 200 / 1.5 GB, LRU by last open; written once after a successful save and never failing it; "Keep negatives" default on. Composite modes store the composed bitmap; double exposure stores shot 2 + the developed first exposure | Accepted | 2026-11-11 |
| D24 | re-develop and import saves create a NEW photo whose file name and `DATE_TAKEN` are the save time (so it sorts first); the stamp and EXIF DateTimeOriginal keep the original date | Accepted | 2026-11-11 |

## 12. Risks
| ID | risk | mitigation | phase |
|---|---|---|---|
| R1 | OES essl3 extension missing on some GPU | ESSL 1.00 Pass 0 fallback | A |
| R2 | preview vs capture ISP colour differ visibly | Phase C tone offset calibration | C |
| R3 | 12 MP render too slow/hot on mid device | MINIMIZE_LATENCY, cap 4096, profile | B.5 |
| R4 | bloom breaks parity | drop bloom from v1 | B |
| R5 | trademark (app label, film names) | rename before release; own display names | C |
| R6 | negatives grow storage / unexpected for users | cap 200 / 1.5 GB, LRU, "Keep negatives" off switch, removed with the photo | v1.1 |
| R7 | `ImageDecoder` orientation behaviour differs per codec/OEM | `ExifOrientation` fallback path; on-device check of rotated JPEG/HEIC before release | v1.1 |

## 13. Delivery plan

**Phase A — vertical slice (serial, one implementer).** Toolchain + skeleton; CameraX preview → Pass 0 →
full render graph with every uniform → `classic35` on screen; capture → same graph full-res → JPEG + EXIF →
MediaStore; HaldCLUT converter + 3D LUT; CameraEffect spike (≤1 day, result recorded in §10).
**Exit (all required):** `.\gradlew.bat assembleDebug test lint` green; §9.1 unit tests green;
ParityTest green for `classic35`; on the owner's phone, back and front captures saved upright with preview and
saved-file screenshots attached. Then module contracts below are **frozen**. Review: integrator + Codex adversarial.

**Phase B — parallel modules (worktrees, merge into `main`):**
| module | may write |
|---|---|
| B-Looks | `presets.json`, `shaders/effects/*.glsl`, `assets/luts/*`, `looks/` tests, `docs/looks/*.png` |
| B-Overlay | `capture/FrameGeometry.kt`, `capture/DateStamp.kt`, `shaders/overlay.glsl` |
| B-UI | `ui/**`, new string keys only — entry criterion: `docs/DESIGN.md` (Visual Storyteller) covering screens, components, layouts, permission/empty/error states |
Frozen interfaces: `PresetRepository`, `UniformMapper`, `RenderGraph` pass inputs, `CapturePipeline.process(input, settings): Result<Uri>`, `CameraUiState`/intents.

**Phase B.5 — perf pass** (Performance Benchmarker) after merges, against §5.3.

**Phase C — hardening & release.** Exit: orientation matrix {back,front}×{0,90,180,270}×ratios saved correctly;
capture survives `onTrimMemory(RUNNING_LOW)`; permission flows tested; rename (D1), icon, signing,
Play listing (Mobile Release Engineer).

## 14. Roles
Integrator/reviewer: Claude. Plan review: egm Goldfish. Architecture: Software Architect.
Perf: Performance Benchmarker. UI/UX: Visual Storyteller. Implementation: general-purpose agents.
Adversarial review: Codex. Release: Mobile Release Engineer. Owner: the user.

## 15. Open questions
- Q1: owner's phone model/SoC/API level (D9) — recorded when connected.
- Q2: final app name, applicationId, SAVE_FOLDER (D1) — before first upload.

## 16. Glossary
**Look** = user-facing option (1:1 with a preset). **Preset** = its data record. **OES** = external
texture fed by the camera. **FBO** = offscreen framebuffer. **LUT** = colour lookup table. **HaldCLUT** = LUT stored as an image.
**ISP** = phone's image signal processor. **Vertical slice** = thin end-to-end working path.

## Appendix A — `classic35` (starting values, owner tunes)
```json
{ "id": "classic35", "displayNameRes": "look_classic35", "lut": "kodak_gold_200", "lutStrength": 0.9,
  "exposure": 0.05, "tone": { "all": [[0,0.04],[0.25,0.24],[0.75,0.78],[1,0.97]] },
  "saturation": 1.05, "temperature": 0.08, "tint": 0.0,
  "shadowTint": {"color":"#1E4A46","amount":0.08}, "highlightTint": {"color":"#FFD9A0","amount":0.10},
  "fade": 0.04, "monochrome": false,
  "grain": {"amount":0.25,"size":0.0012,"chroma":0.15},
  "vignette": {"amount":0.25,"radius":0.9,"softness":0.6,"shape":"oval"},
  "centerGlow": {"amount":0,"radius":0.5}, "softFocus": 0, "bloom": {"amount":0,"threshold":0.8},
  "sharpen": 0, "distortion": {"barrel":0,"circularMask":false},
  "lightLeak": {"probability":0.3,"amount":0.35,"variants":[0,1,2,3]}, "dust": 0.1,
  "defaultFrame": "none", "dateStamp": {"enabledByDefault": false, "color": "#FF8A1F"} }
```

## Appendix B — First device run (2026-11-11, OnePlus 11, API 35)
- Install + launch OK; permission flow OK; back preview upright; front preview mirrored.
- Capture OK: back 2996×3994 (render cap), front 1726×2300; saved upright, not mirrored, EXIF Orientation=1, Software set, date stamp rendered.
- ParityTest: `grainOff_meanAndP99` PASS (all presets). `grainOn_blockStatsWithin5Percent` FAIL — classic35 139/352 blocks outside ±5 %.
  Likely cause: grain frequency near/above the preview Nyquist, so the area-downscaled full-res grain loses variance. Phase B task: band-limit grain (octave clamp by `uShortSide`), and measure std after a matched low-pass in the test.
- Observation to verify in Phase B: front-camera Mono capture looks brighter/less vignetted than its preview (ISP difference vs vignette parity).

## Appendix C — Look tuning pass 1 (2026-11-11, OnePlus 11, indoor evening light)
- Method: same scene captured with all 6 looks via adb, mean RGB / warmth (R−B) measured, 3 iterations.
- Golden: warmth R−B 6 → 25 (temperature 0.45, amber highlight tint 0.3, grain 0.4, vignette 0.35). Accepted.
- Pocket: exposure +0.3, temp 0.32, sat 1.3, glow/soft focus reduced. Still dark indoors without flash (strong vignette) — re-check in daylight and with flash.
- Instant / Digi / Mono / Bubble: minor tweaks; Digi stays cool-cyan (LUT-dominated), Mono more contrast, Bubble more saturated.
- Bug found: swiping on the viewfinder changes look only once per session; carousel taps work. → Phase B fix. **Fixed in 1a58677** (stale pointerInput state).
- Note: `temperature` has a weak effect relative to the film LUT; consider widening its range in the baker.


## Appendix D — Performance pass (B.5) (2026-11-11, OnePlus 11 / SD 8 Gen 2, API 35, debug build)
- Instrumentation (debug only, `BuildConfig.DEBUG`, tag `VLPerf`, `perf/PerfLog.kt`): preview line every 60 frames
  (CPU `onDrawFrame` p50/p95, GPU-sync time p50/p95, frame interval p50/p95/max, fps, frames >50 ms) and one `SHOT` line per
  capture (capture→ImageProxy, decode, upload+render, readback, compress+write, total from `takePicture` call). Read with `adb logcat -s VLPerf`.
- **GPU time is a proxy, not `EXT_disjoint_timer_query`:** the Android SDK has no Java binding for that extension (needs JNI/NDK, not
  in this project). Every 4th frame ends with `glFinish` and reports begin→GPU-idle (CPU submit + GPU execution); it is an upper bound
  of true GPU time. Debug builds also `glFinish` before capture readback so upload+render and readback are attributed correctly.
- Method: 60 s per look at the camera's 30 fps stream (4:3, 1080×2412 screen, indoor), `logcat` streamed to file (the ring buffer
  drops lines otherwise); 10 shutter taps 5 s apart per look; "fps" = 1000 / median frame interval.

| Metric | Budget | Measured | Pass |
|---|---|---|---|
| Preview Pocket (disposable: soft focus+bloom+glow), 60 s | ≥ 30 fps, 95 % of frames | 31.3 fps, 0 of ~1800 frames >50 ms, interval p95 ≈ 41 ms (camera cadence jitter), max 46.9 ms; CPU p95 ≤ 14.9 ms, GPU-sync p50 12.6 / p95 ≤ 15 ms | pass |
| Preview Digi (ccd: bloom+sharpen) | ≥ 30 fps | 31.3 fps, 0 frames >50 ms, max 48.2 ms; CPU p95 ≤ 12.4, GPU-sync p50 10.2 / p95 ≤ 13.0 ms | pass |
| Preview Bubble (fisheye) | ≥ 30 fps | 31.4 fps, 0 frames >50 ms, max 48.4 ms; CPU p95 ≤ 14.6, GPU-sync p50 12.4 / p95 ≤ 15.7 ms | pass |
| Preview Pocket re-measured after ~14 min of camera use | ≥ 30 fps | 31.3 fps, 0 frames >50 ms, GPU-sync p50 12.9 / p95 ≤ 15.1 ms | pass |
| Preview during capture render (Pocket, 10 shots) | ≥ 15 fps | worst frame 241.7 ms in the window containing a shot (one stall); 21 of ~3300 frames >50 ms, in 10 of 56 windows (shot windows plus one at look switch) | pass |
| Shutter-to-saved median of 10, Golden (classic35) | ≤ 2.5 s | 1.14 s (max 1.27 s) | pass |
| Shutter-to-saved median of 10, Pocket (disposable) | ≤ 2.5 s | 1.11 s (max 1.24 s) | pass |
| Stage: capture→ImageProxy (Golden / Pocket) | ≤ 600 ms | 625 / 605 ms | marginal fail (+4 % / +1 %) |
| Stage: decode | ≤ 300 ms | 98 / 98 ms | pass |
| Stage: upload+render | ≤ 400 ms | 92 / 97 ms | pass |
| Stage: readback | ≤ 150 ms | 72 / 77 ms | pass |
| Stage: compose | ≤ 150 ms | n/a (normal shots have no CPU compose; composite modes not measured) | n/a |
| Stage: compress+write | ≤ 700 ms | 255 / 229 ms | pass |
| Thermal after ~5 min and ~14 min of camera use | note | `ThermalService` status 2 (moderate) at ~11 min and ~15 min (not sampled earlier); battery 37.7 °C, skin 27.9–42.7 °C, GPU zones 45–54 °C; no fps loss between the first and the 14-min Pocket run | note |

- Changes made: instrumentation only. No render-graph, shader or capture-path change, so parity is untouched.
- Open: the capture stage is camera/ISP time (CameraX `CAPTURE_MODE_MINIMIZE_LATENCY` already set; the pipeline also runs an AE-precapture task) and
  is outside the app's control; the total has 1.3 s of headroom. Decide whether to relax that sub-budget to 650 ms or test `ImageCapture` with
  AE precapture skipped. True `EXT_disjoint_timer_query` timing would need a small JNI shim.
- Swipe re-verified 2026-11-11 after B.5: works (Golden→Pocket→Instant→Digi). The B.5 report was a false alarm: its UI probe searched English labels while the phone runs in French.

## Appendix E — v1.1 device verification (2026-11-11, OnePlus 11)
| check | result |
|---|---|
| Share from viewer | PASS — system chooser opens with the image preview (URI grant OK) |
| Import JPEG 4000×3000 stored + EXIF Orientation=6 | PASS — editor and saved file upright (3000×4000, Orientation=1) |
| Import HEIC 5000×7000 | PASS — decoded, capped to 2925×4096, upright |
| Delete from viewer (API 35 `createDeleteRequest`) | PASS — system dialog, file removed, negative removed, count 58→57 |
| Negatives prune after deleting photos outside the app | PASS — 7 → 4 negatives on next launch |
| Viewer paging stress (25 fast swipes) | PASS — no crash, PSS stable |
Risk R7 (ImageDecoder orientation / HEIC) closed.

