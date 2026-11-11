# Spec — Vintage Camera App (Android, working label "Dazz Cam")

| | |
|---|---|
| Status | DRAFT v0.2 — 2026-11-11 |
| Owner | Project owner (the user) — approves looks, names, scope |
| Integrator / reviewer | Claude (main session) |
| Related | `docs/reviews/spec-review-v0.1.md` (Goldfish review), `docs/DESIGN.md` (pending, Visual Storyteller), `third_party/t3mujinpack/NOTICE.md` |
| Changelog | v0.1 initial · v0.2 applies Goldfish review B1–B7, S1–S18, owner decisions D7/D8 · v0.2.1 Phase A implementation notes (§6.4, §7.2, §9, §10) |

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
5. Save JPEG to MediaStore `Pictures/<SAVE_FOLDER>`; last-shot thumbnail opens it via `ACTION_VIEW`.
6. In-app "Open-source licenses" screen (t3mujinpack MIT).

### 3.2 Deferred (v1.1+)
Photo import + editor, in-app gallery (owner decision D7), video, accessories
(overlay effects imitating physical lens add-ons), more looks, billing.

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
| `bw400` | Kodak Tri-X 400 | coarse grain, vignette |
| `fisheye` | Kodak ColorPlus 200 | barrel distortion + circular mask |

Display names are our own string resources (film brand names are trademarks; used only in file names).
Exact numbers live in `presets.json`; Appendix A gives `classic35`. A look is **accepted** when
the owner approves its reference render `docs/looks/<id>.png`; that PNG becomes a golden file.

## 4. Privacy & Play

- No INTERNET permission, no analytics/crash/ads SDKs. Data Safety: no data collected or shared.
- Permissions: CAMERA (runtime) only. **minSdk 29** (owner decision D8) → no storage permission ever.
- Output EXIF via `androidx.exifinterface`: copy DateTimeOriginal, OffsetTime*, Make, Model,
  ExposureTime, FNumber, ISOSpeedRatings, FocalLength; set Orientation=1 and Software=app_name. Never write GPS.
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
- Shutter-to-next-shot ≤ 0.5 s. During a capture render preview may drop to ≥ 15 fps.

## 6. Architecture

### 6.1 Modules (single Gradle module `:app`)
```
app/src/main/java/com/vintagelab/camera/
├── MainActivity.kt, App.kt
├── ui/        CameraScreen.kt, LookPicker.kt, Controls.kt, PermissionScreen.kt, LicensesScreen.kt
│              CameraViewModel.kt, CameraUiState.kt
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
ToggleDateStamp, Shuffle, Shutter, TapFocus(x,y), Zoom(f). Persist lookId, ratio, flash, timer,
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

### 6.6 Storage (API 29+ only)
Insert with `RELATIVE_PATH = Pictures/<SAVE_FOLDER>` and `IS_PENDING=1`, write, set `IS_PENDING=0`;
on startup delete our own still-pending rows; on failure delete the pending row.
Filename `IMG_yyyyMMdd_HHmmss_SSS.jpg`. `SAVE_FOLDER` is a constant in `StorageConfig.kt` (fixed before release, D1).

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
| `defaultFrame` | `"none"\|"instant_white"\|"film_strip"` | none |
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
- Date stamp: capture time, format `'yy MM dd`, bottom-right of the image area, inset 0.04 S, cap height 0.035 S,
  colour from preset, slight glow. Glyphs: procedural SDF 7-segment in the shader (no font asset).

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
| D9 | reference device = owner's phone (model TBD at connection) | Proposed | 2026-11-11 |
| D10 | colour = t3mujinpack HaldCLUT (MIT) baked with parametric ops | Accepted | 2026-11-11 |

## 12. Risks
| ID | risk | mitigation | phase |
|---|---|---|---|
| R1 | OES essl3 extension missing on some GPU | ESSL 1.00 Pass 0 fallback | A |
| R2 | preview vs capture ISP colour differ visibly | Phase C tone offset calibration | C |
| R3 | 12 MP render too slow/hot on mid device | MINIMIZE_LATENCY, cap 4096, profile | B.5 |
| R4 | bloom breaks parity | drop bloom from v1 | B |
| R5 | trademark (app label, film names) | rename before release; own display names | C |

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
