# Goldfish Review Report: SPEC.md v0.1

**Target:** `D:\Daz camm Projet\docs\SPEC.md` (lines 1-151, DRAFT v0.1, 2026-11-11)
**Mode:** REVIEW
**Date:** 2026-11-11

**Fixed constraints.** These were not reopened:
- Android-only
- Kotlin + CameraX + OpenGL ES
- No assets extracted from any other app
- The display label is temporary

None of the seven reviewer reports argued against these constraints, so no findings had to be discarded. Some findings work within them. For example, S6 decouples the save folder from the temporary label.

**Caveat on technical claims.** No reviewer had web access. Every version-specific claim is marked **(confirm)** and must be checked against current release notes or compatibility tables before anything is pinned. That covers targetSdk 36 behaviour, AGP/Gradle/Kotlin compatibility, Photo Picker backport details, CameraX API availability and Play policy specifics.

**Caveat on numbers.** Every numeric threshold in the proposed text is a reviewer-suggested starting value for the owner to confirm or replace with a measurement. This includes:
- parity tolerances
- memory and stage-time budgets
- JPEG quality
- frame and stamp fractions
- colours
- shader cost estimates

**Severity labels:**
- **Blocking:** a core design decision is missing, wrong or self-contradictory. Implementing as written would build the wrong thing, or make Phase A unverifiable, or make the Phase B parallel split unsafe.
- **Should-fix:** a specification gap an implementer could fill alone but should not, because filling it means silent guesses and later rework. Items tagged **[before Phase A]** must still be settled before Phase A code starts, because the vertical slice and its exit gate depend on them.
- **Nit:** clarity, consistency or structure.

## Reviewer Status

| Reviewer | Status |
| --- | --- |
| `design` | completed |
| `implementation-readiness` | completed |
| `zoom-out` | completed |
| `codebase-details` (technical accuracy only; no code exists yet) | completed |
| `structural` | completed |
| `comprehension` | completed |
| `inventive-catalyst` | completed |

**Totals after deduplication:** 7 blocking, 18 should-fix (4 of them tagged [before Phase A]), 10 nits.

**Overall verdict:** Not ready for implementation.

The architecture direction is sound: one shader source, looks as data, and a vertical slice first. But the spec's "hard constraint" in section 1, preview/capture parity, is designed only at the shader level and has no measurable definition. Several inputs that decide parity are open or contradictory:
- framing
- colour decode
- capture format and resolution
- seed timing
- frames and date stamp
- spatial effects

---

## Blocking

### B1. Parity has no definition, metric or test, and the Phase A exit gate does not check it
* **Reviewer lenses:** comprehension, zoom-out, implementation-readiness, design, inventive-catalyst, structural
* **Affected scope:** lines 24, 43-50 (section 1 rules), 126-128 (Phase A), 148 (Q1)
* **Discrepancy:**
  - "What you saw ≈ what you get" has no tolerance and no way to measure it.
  - It is unclear what parity covers. One reading is "same shader math on the same input". The other is "the saved file looks like the viewfinder", which cannot be fully met: preview and still capture go through different ISP processing, and the flash fires only on capture.
  - Phase A exits on `assembleDebug` green. A slice with broken parity would pass.
  - Q1 (physical device) is listed as an open question. Without a device, no perf, orientation or OES check is possible.
* **Rationale:** The spec says the product "lives or dies" on parity, so parity has to be a pass/fail gate.
* **Proposed spec change:** add `1.1 Parity definition & acceptance`:
  > **Definition.** For the same normalized input, look and seed, the look graph rendered at full resolution and then downscaled (area filter) matches the look graph rendered at preview resolution within these limits:
  > - Grain disabled: mean absolute difference ≤ 3/255 per channel, 99th percentile ≤ 12/255.
  > - Grain enabled: block mean and standard deviation of luminance within ±5% (64×64 blocks).
  > - Vignette, leak and frame positions: no shift greater than 1% of the short side.
  >
  > ISP and flash differences between the preview stream and the ImageCapture output are accepted and outside parity.
  >
  > **Test.** `app/src/androidTest/.../ParityTest.kt` feeds a procedurally generated chart (checker + gradients) as a `sampler2D` into the look graph (see B3) at 1440×1080 and at 4032×3024, for every preset. Separately, a device check confirms that Pass 0's OES conversion of a displayed chart matches its sampler2D conversion within the same tolerance.
  >
  > **Phase A exit (all required):**
  > 1. `./gradlew assembleDebug test lint` passes.
  > 2. The unit tests in S14 pass.
  > 3. ParityTest passes for `classic35`.
  > 4. On a physical device, back and front captures are saved upright, and screenshots of the preview and the saved file are attached.

  Promote Q1 to a prerequisite: "A physical reference device (named model, SoC, API level) is required before Phase A can exit."

### B2. Preview and capture framing are not tied together, and "normalized units" has no reference frame
* **Reviewer lenses:** design, inventive-catalyst, codebase-details, zoom-out, comprehension, implementation-readiness
* **Affected scope:** lines 26-41, 44-47, 73, 100-102
* **Discrepancy:**
  - CameraX `Preview` and `ImageCapture` choose their resolution and crop independently, so the field of view can differ before any shader runs.
  - 1:1 is never a native stream ratio, and 16:9 on a 4:3 sensor is a crop. The spec does not say who crops, or where.
  - "Fraction of short side" could mean the sensor frame, the cropped output or the on-screen view. Vignette and leak centres depend on which one.
* **Rationale:** If framing differs, parity is broken whatever the shaders do.
* **Proposed spec change:** add to the section 1 rules:
  > Preview and ImageCapture use one crop, computed once.
  >
  > **Recommended:** bind both in a single `UseCaseGroup` with a shared `ViewPort` matching the selected ratio. Both paths then use the crop rect that CameraX reports: `SurfaceRequest.TransformationInfo.cropRect` for preview and `ImageProxy.cropRect` for capture. A ratio change rebinds, and a brief preview interruption is accepted.
  >
  > **Alternative (no rebind):** bind both at 4:3 via `ResolutionSelector`. Apply the ratio as a centred crop through `uCropRect`, computed once in `looks/CropMath.kt` and used by both paths.
  >
  > Either way:
  > - 1:1 is a centre crop of 4:3.
  > - Normalized units are always relative to the short side of the **final cropped output**, before any frame margin.
  > - Parity acceptance (B1) includes framing: test-chart corners must align within 1% of the short side.
  >
  > Record the choice in the decisions log.

### B3. GLSL ES 3.00 external-sampler setup is incomplete, and the two paths do not feed the look shader the same input
* **Reviewer lenses:** codebase-details, design, inventive-catalyst, implementation-readiness
* **Affected scope:** lines 4, 30, 113-114
* **Discrepancy:**
  - **Extension.** With `#version 300 es`, `samplerExternalOES` requires `GL_OES_EGL_image_external_essl3`. The plain `GL_OES_EGL_image_external` is for ESSL 1.00. The essl3 extension is not present on every driver, and the spec has no runtime check or fallback.
  - **`#define` placement.** `#version` must be the first line of the shader, so the variant `#define` has to be inserted after it, not prepended.
  - **Manifest.** GLES 3.0 is not declared.
  - **Different inputs.** In preview, the driver does the YUV→RGB conversion for OES textures, and the matrix and range vary by vendor. Capture samples a decoded sRGB JPEG. The same uniforms can therefore produce different blacks and saturation, which are the traits the looks depend on.
  - **No mipmaps on OES.** OES textures have no mipmaps, so bloom and downsampling cannot read them directly.
* **Rationale:** As written, the preview variant may fail to compile on some devices. And even where it compiles, "one shader" does not mean "one input".
* **Proposed spec change:** replace lines 113-114:
  > Shaders are ESSL 3.00. `ShaderProgram` assembles each source in this order: line 1 `#version 300 es`, then the variant block, then the shared body.
  >
  > **Pass 0 "Normalize"** is the only source-specific pass:
  > - Preview samples `samplerExternalOES` (with `#extension GL_OES_EGL_image_external_essl3 : require`) and applies the SurfaceTexture matrix, crop and rotation.
  > - Capture and import do the same from a `sampler2D`.
  > - Both write an RGBA8 sRGB working texture.
  >
  > All look passes are `sampler2D`-only and identical for every source.
  >
  > If `GL_OES_EGL_image_external_essl3` is missing at startup, preview Pass 0 is compiled as ESSL 1.00 with `GL_OES_EGL_image_external`.
  >
  > The manifest declares `<uses-feature android:glEsVersion="0x00030000" android:required="true"/>`. All EGL contexts are ES 3.0.
  >
  > The working colour space is gamma-encoded sRGB in both paths: no sRGB EGL surfaces or FBO formats, and decode with `inPreferredColorSpace = SRGB`. A per-device "preview tone offset" in Pass 0 is reserved for Phase C calibration and defaults to identity.

  Cost: one extra full-screen preview pass (estimated about 0.5-1 ms, to be measured). In return, the `#define` split shrinks to one small pass, and that pass is the only place a source-specific check is needed.

### B4. Capture format, resolution and the oversize strategy are undecided, and the memory peak is unbounded
* **Reviewer lenses:** codebase-details, design, zoom-out, comprehension, implementation-readiness, inventive-catalyst
* **Affected scope:** lines 35-39 (capture column), 54-55
* **Discrepancy:**
  - **Format.** Line 35 says "JPEG/YUV". YUV is not a normal ImageCapture output: the public formats are JPEG and JPEG_R on API 34+ (confirm).
  - **Delivery.** The spec does not choose between in-memory and file capture. File capture would write an unfiltered original to storage.
  - **Resolution.** Nothing caps it, so CameraX may pick 50 MP on modern sensors (about 200 MB per ARGB buffer). That means the "12 MP" budget is not guaranteed.
  - **Oversize strategy.** Line 54 asks for a tiled render, and line 55 offers "cap at 4096 px" as a fallback. Which one v1 builds is ambiguous. Tiling also breaks bloom, blur, sharpen and fisheye unless tiles overlap and use whole-image coordinates.
  - **Other limits.** `GL_MAX_RENDERBUFFER_SIZE` and `GL_MAX_VIEWPORT_DIMS` matter too. The `glReadPixels` vertical flip is not specified.
  - **Memory.** A naive chain holds about 5 × 48 MB at 12 MP.
* **Rationale:** This is the Phase A capture path. An implementer cannot build it without making all of these choices.
* **Proposed spec change:** replace the capture-column text on lines 35-36 and replace lines 54-55:
  > **Capture:** `ImageCapture` with `OUTPUT_FORMAT_JPEG`, delivered in memory via `takePicture(executor, OnImageCapturedCallback)`.
  > - No unfiltered file is ever written.
  > - Rotation comes from `ImageProxy.imageInfo.rotationDegrees`, not from EXIF parsing.
  > - Close the `ImageProxy` right after decode.
  > - Use `CAPTURE_MODE_MINIMIZE_LATENCY` unless benchmarks justify `MAXIMIZE_QUALITY`.
  >
  > **Resolution:** `ResolutionSelector` targets about 12 MP (`ResolutionStrategy(Size(4000,3000), FALLBACK_RULE_CLOSEST_LOWER)`) together with the selected `AspectRatioStrategy`. The render long side is clamped to `min(GL_MAX_TEXTURE_SIZE, GL_MAX_RENDERBUFFER_SIZE, GL_MAX_VIEWPORT_DIMS, 4096)`, downscaling before upload if needed.
  >
  > **No tiled rendering in v1.** Adding it later requires its own design, with whole-image normalized coordinates (`uTileRect`) and an overlap of at least the largest kernel radius.
  >
  > **Memory plan:**
  > 1. Upload the decoded bitmap, then `recycle()` it immediately.
  > 2. Render into an RGBA8 FBO, with the Y flip done in the final pass.
  > 3. Read back into a reused direct `ByteBuffer`.
  > 4. Write one ARGB_8888 output bitmap.
  >
  > At most 2 full-size buffers are alive at once. Target peak: ≤ 160 MB native.

  Keep the performance budget line (53) for S12. Record the decision in the decisions log (S18).

### B5. The presets.json schema and the shader uniform contract are undefined, and some look traits have no way to be expressed
* **Reviewer lenses:** implementation-readiness, comprehension, structural, design, zoom-out, inventive-catalyst
* **Affected scope:** lines 18-20, 62-72, 108-112
* **Discrepancy:**
  - **Schema.** There are no fields, types, units, ranges, defaults, control-point format, schema version or validation rules. Phase A and Phase B will each invent one.
  - **Uniform types.** Uniforms have no GLSL types. Slash pairs such as `uTemperature/uTint` and `uGrainAmount/uGrainSize` are ambiguous.
  - **Missing uniforms.** There is no crop or short-side uniform, although the normalized-units rule needs one. Some look traits have no uniform at all: soft focus, flash-hot centre, CCD digital noise (as distinct from film grain), mono conversion, circular vignette shape and dust.
  - **Effect order** is undefined. For example, grain applied before the fisheye step gets stretched.
  - **Textures contradict.** Line 18 says presets contain "textures". Lines 70-72 say leaks, dust and frames are procedural. Line 112 has `uLeakTex`. A reader cannot tell what presets.json holds, which matters under the no-extracted-assets rule.
* **Rationale:** This is the interface every Phase B parallel worker builds against, and `classic35` in Phase A needs it first.
* **Proposed spec change:**

  Add `4.1 presets.json schema`:
  > Top level: `{ "schemaVersion": 1, "presets": [...] }`.
  >
  > Each field has a type, a range and a neutral default:
  >
  > | Field | Type and range |
  > | --- | --- |
  > | `tone` | per-channel control points: 2-16 points, x strictly increasing, monotone cubic |
  > | `saturation` | [0,2] |
  > | `temperature`, `tint` | [-1,1] |
  > | `shadowTint`, `highlightTint` | {color, amount} |
  > | `fade` | number |
  > | `monochrome` | bool |
  > | `grain` | {amount, size as fraction of short side, chroma} |
  > | `vignette` | {amount, radius, softness, shape} |
  > | `centerGlow` | object |
  > | `blur`, `bloom`, `sharpen`, `distortion` | number or object |
  > | `lightLeak` | {probability, amount, variants: indices 0-3} |
  > | `dust` | number |
  > | `defaultFrame` | enum id |
  > | `dateStamp` | {enabledByDefault, color} |
  >
  > `displayName` is a string-resource key, not a literal.
  >
  > Validation:
  > - Out-of-range values are clamped, with a WARN.
  > - A preset with a duplicate or missing id is skipped, with an ERROR.
  > - Malformed JSON falls back to a built-in identity preset `none`.
  >
  > presets.json contains numbers and enum ids only. Leak and dust textures, if any, are generated procedurally at startup in each GL context. Remove "textures" from line 18.

  Add `4.2 Uniform contract & pass order`:
  > - Exact names and GLSL types, for example `vec4 uCropRect`, `float uShortSide`, `vec3 uGrain`, `vec4 uVignette`, `vec3 uLeak` (replacing `uLeakTex`) and `float uSeed`.
  > - Fixed effect order: geometry/distortion → colour → neighbourhood (blur, bloom, sharpen) → centre glow → leak → vignette → dust → grain → overlay.
  > - `looks/UniformMapper.kt` is a pure-Kotlin `Preset → UniformSet` mapping with unit tests.
  > - Phase A implements **every** uniform, including those `classic35` does not use, so Phase B only edits data and function bodies.

  Add Appendix A: a fully numeric `classic35` entry.

### B6. Frames and the date stamp are composited only on the capture path, and nothing says who controls them
* **Reviewer lenses:** comprehension, zoom-out, inventive-catalyst, implementation-readiness, design
* **Affected scope:** lines 38-39, 66-67, 70-72, 96
* **Discrepancy:**
  - **Capture-only compositing.** The diagram adds "compose frame/date stamp" only after `glReadPixels`. The preview therefore either omits the instant frame and stamp, which breaks parity on the most visible elements, or redraws them with a second implementation that will drift.
  - **Ownership.** `instant` and `ccd` list a frame and a stamp as look traits, but lines 71-72 make them separate user features. Precedence is not stated, and the ViewModel state has no frame or stamp field.
  - **Undefined details:**
    - frame geometry, and whether the canvas grows or the image shrinks
    - stamp content (capture time or EXIF date), format and position
    - the "own rendering or OFL font" choice
* **Rationale:** This decides the compositor architecture and a Phase B module boundary.
* **Proposed spec change:** add `4.3 Frames & date stamp`:
  > **Rendering.** Frames and the date stamp are drawn by the **same GL overlay pass** (the last pass) in both paths. Geometry comes from one Kotlin source (`capture/FrameGeometry.kt`), and the preview shows the framed, stamped result.
  > Acceptable alternative: a Compose overlay in preview that uses the same `FrameGeometry` numbers. Choose one and record it in the decisions log.
  >
  > **Ownership.** Each preset sets `defaultFrame` and `dateStamp.enabledByDefault`. The user can override both per session, via `CameraUiState.frame` and `.dateStamp`.
  >
  > **Geometry**, in fractions of the image short side S (starting values):
  > - `instant_white`: side and top borders 0.06S, bottom border 0.22S; the canvas grows.
  > - `film_strip`: border 0.08S with sprocket holes (specify pitch and size).
  >
  > **Date stamp:**
  > - Text: capture time, or EXIF `DateTimeOriginal` for imports.
  > - Format: `'yy MM dd`.
  > - Position: bottom-right, inset 0.04S, cap height 0.035S.
  > - Colour: from the preset.
  >
  > **Glyphs:** procedural SDF 7-segment shapes in the shader, so no font asset is needed. If an OFL font is chosen instead, name it and ship its `OFL.txt`.

  Remove the "or" on line 71.

### B7. The Phase B parallel modules have no frozen interfaces or file allowlists
* **Reviewer lenses:** implementation-readiness, design, zoom-out
* **Affected scope:** lines 84-93, 126-132, 143
* **Discrepancy:**
  - The Phase B streams all edit the same files in separate worktrees: "remaining 5 looks + effects", "frames/date stamp compositor", "UI/UX" and "perf pass" all touch the uber shader, presets.json, CapturePipeline and the ViewModel. Merge conflicts and guessed APIs are certain.
  - A parallel perf pass would measure code that is still changing.
  - The UI stream depends on a "Visual Storyteller design spec" that has no path and no defined content.
  - Section 3 lists only directories.
* **Rationale:** Parallel worktrees are only safe with frozen contracts and non-overlapping write sets.
* **Proposed spec change:** add `6.1 Module contracts (frozen at end of Phase A)`:
  > **Kotlin interfaces:**
  > - `PresetRepository`
  > - `UniformMapper`
  > - `Compositor`, or the overlay-pass inputs
  > - `CapturePipeline` (`suspend fun process(input, settings): Result<Uri>`)
  > - `GalleryRepository`
  > - `CameraUiState` / `CameraIntent` (S15)
  >
  > **Write allowlists:**
  >
  > | Module | May write |
  > | --- | --- |
  > | B-Looks | presets.json; per-effect GLSL include files (one per effect, concatenated at load); looks tests |
  > | B-Compositor | `capture/Frame*.kt`; the overlay shader chunk |
  > | B-UI | `ui/**`; new string keys only |
  >
  > **Perf pass** moves to Phase B.5, after the merges.
  >
  > **UI entry criterion:** `docs/DESIGN.md` exists and covers the screen list, component inventory, layout per screen, and the permission-denied, empty and error states.
  >
  > **Regression:** a golden image per look (S16).

  Also list the concrete Phase A file tree under `app/src/main/java/com/vintagelab/camera/`.

---

## Should-fix

The four items tagged **[before Phase A]** are listed first.

### S4. [before Phase A] The GL lifecycle and the CameraX ↔ SurfaceTexture contract are unspecified, and the EGL context strategy is undecided
* **Reviewer lenses:** design, codebase-details, implementation-readiness, zoom-out
* **Affected scope:** lines 27, 88, 95-99
* **Discrepancy:**
  - **Pause/resume.** GLSurfaceView destroys its EGL context in `onPause` by default, and that takes down the OES texture CameraX is writing into. `AndroidView` does not forward `onPause`/`onResume`.
  - **Surface handling is unspecified:**
    - `setDefaultBufferSize`
    - releasing in the `provideSurface` result callback
    - a new `SurfaceRequest` on every camera or ratio switch
    - `RENDERMODE_WHEN_DIRTY`
    - `updateTexImage` on the GL thread only
  - **Two EGL owners.** `EglCore` sits next to GLSurfaceView's own EGL. Reviewers disagreed on whether the capture context should share with the preview context.
  - **GPU contention.** "Never blocks the preview thread" is true for the CPU only. The GPU is shared, so the preview will drop frames during a 12 MP render.
* **Proposed spec change:** replace lines 97-99:
  > **Preview.** `GlPreviewRenderer` creates the OES texture and the SurfaceTexture on the GL thread, inside `Preview.SurfaceProvider`. It calls `setDefaultBufferSize(request.resolution)` and `provideSurface(surface, glExecutor)`, and releases both only in the result callback.
  > - Render mode is `RENDERMODE_WHEN_DIRTY`, with `requestRender()` called from `onFrameAvailable`.
  > - A `LifecycleEventObserver` in the `AndroidView` factory forwards `onPause`/`onResume`.
  > - On context loss, reset the surface provider so CameraX issues a new `SurfaceRequest`.
  >
  > **Capture.** Capture and import use a **separate, non-shared** ES 3.0 context (pbuffer or surfaceless) on the `capture-gl` HandlerThread. It compiles its own programs and LUTs from the same `PresetRepository` data. This avoids depending on a preview context that can be destroyed at any time. Record it in the decisions log; the alternative is in S10.
  >
  > **GPU contention.** While a capture is rendering, the preview may drop to ≥ 15 fps. Capture passes are separated by `glFlush`.

### S5. [before Phase A] Orientation and mirroring rely on the SurfaceTexture matrix alone, and saved front-camera mirroring is undecided
* **Reviewer lenses:** codebase-details, design, zoom-out, comprehension
* **Affected scope:** lines 100-102
* **Discrepancy:**
  - `getTransformMatrix()` does not reliably contain display rotation and mirroring. CameraX exposes both through `SurfaceRequest.setTransformationInfoListener` (rotationDegrees, cropRect, and mirroring in newer versions (confirm)).
  - The matrix applies to texture coordinates, not vertex positions.
  - The spec does not say whether front photos are saved mirrored.
  - Android 16 at targetSdk 36 ignores orientation locks on large screens (sw ≥ 600dp) (confirm), so the orientation math must handle landscape.
* **Proposed spec change:** replace lines 100-102:
  > **Transforms.** Preview transform = SurfaceTexture matrix (applied to texture coordinates) × a correction from `TransformationInfo` (rotation, crop, mirroring). `TransformationInfo` is the source of truth. Capture rotation comes from `ImageProxy.imageInfo.rotationDegrees`.
  >
  > **Mirroring convention.** The front preview is mirrored. Saved front photos are **not** mirrored by default.
  >
  > **Tests.** `OrientationTest` covers 4 rotations × 2 lens facings × 3 ratios, plus all 8 EXIF orientation values (including the flips) for import.
  >
  > **Layout.** The layout and orientation math handle landscape and multi-window, even when portrait is requested.

### S13. [before Phase A] Toolchain and dependencies are not pinned (version claims unverified)
* **Reviewer lenses:** codebase-details, implementation-readiness, comprehension
* **Affected scope:** lines 118-121
* **Discrepancy:**
  - "Kotlin 2.x, AGP 8.x" is not something you can build against.
  - compileSdk is not stated, although platforms 34-37 are installed.
  - Gradle 8.14.3 limits the project to AGP 8.x (confirm). compileSdk 36 needs a recent AGP 8.x (confirm the minimum). compileSdk 37 likely needs AGP 9 (confirm).
  - Kotlin 2.x needs `org.jetbrains.kotlin.plugin.compose` at the same version as Kotlin.
  - AGP's default build-tools version may not match the installed 36, which breaks offline builds.
  - CameraX, the Compose BOM, serialization and coroutines are unpinned, so implementers will choose versions themselves.
* **Proposed spec change:** replace lines 118-119:
  > All versions are pinned in `gradle/libs.versions.toml` and checked against the official AGP/Gradle/API compatibility table before Phase A:
  > - Gradle 8.14.3
  > - AGP 8.x.y: the highest version that supports Gradle 8.14 and compileSdk 36 (confirm)
  > - Kotlin 2.x.y, with the matching compose compiler plugin
  > - JDK 17 toolchain
  > - compileSdk 36, targetSdk 36, minSdk 26
  > - `buildToolsVersion = "36.0.0"`
  > - Exact versions of camera-core, camera-camera2, camera-lifecycle, the Compose BOM, kotlinx-serialization-json, kotlinx-coroutines, JUnit4 and Robolectric
  >
  > Any other dependency requires integrator approval. Platform 37 is installed but unused until the project moves to AGP 9 / Gradle 9. The SDK path belongs in an uncommitted `local.properties`, not in the spec.

### S14. [before Phase A] There are no named test cases (the B1 exit gate depends on them)
* **Reviewer lenses:** implementation-readiness, comprehension, inventive-catalyst
* **Affected scope:** lines 102, 120-121
* **Discrepancy:** The spec names test areas, but no test files and no test cases.
* **Proposed spec change:** list these tests:
  - `PresetParserTest`: valid file; a missing field takes its default; an out-of-range value is clamped; a duplicate id is skipped; malformed JSON falls back to identity.
  - `ToneLutTest`: identity points give `LUT[i]==i` ±1; the result is monotone.
  - `UniformMapperTest`: golden `classic35` values.
  - `OrientationTest`: as specified in S5.
  - `CropRectTest`: 3 ratios × 4032×3024 and 3024×4032.
  - `ParityTest`: as specified in B1.
  - `SeamTest`: only if tiling is ever added.

  Reference `OrientationTest` from line 102.

### S1. Seed semantics contradict parity: "seeded at the shutter press" means the preview showed a different seed
* **Reviewer lenses:** inventive-catalyst, comprehension, design, implementation-readiness
* **Affected scope:** lines 48-50
* **Discrepancy:**
  - If the seed is drawn at the shutter press, the leak and dust the user framed cannot be the ones that get saved.
  - Preview grain animation is not specified.
* **Proposed spec change:** replace lines 48-50:
  > **Look seed.** `CameraViewModel` owns a look seed that controls leak presence, variant and placement, and dust layout. The preview displays it. At the shutter press (or when the timer expires), it is frozen into the capture job. It is re-rolled after each capture, or when the user taps "shuffle".
  >
  > **Grain time.** Preview grain animates through a separate `uGrainTime`. Capture uses the frozen seed, with `uGrainTime` fixed at its value for the last presented frame.
  >
  > **Import.** A new look seed is created when the Editor opens and is reused for the save.

  Visual Storyteller to confirm that a leak staying static between shots is acceptable.

### S2. Spatial effects (bloom, soft focus, sharpen, grain) don't fit a single-pass uber shader with normalized units
* **Reviewer lenses:** design, inventive-catalyst, zoom-out, codebase-details, implementation-readiness
* **Affected scope:** lines 44-47, 65-67, 108-112
* **Discrepancy:**
  - **Blur cost.** A normalized blur radius covers about 3× more pixels at 12 MP than at 1080p. With fixed taps the blur is undersampled. With scaled taps the cost grows quadratically.
  - **Bloom** needs a downsample chain, which a single pass cannot provide.
  - **Sharpen.** A fixed 3×3 sharpen kernel depends on resolution.
  - **Grain.** A per-pixel hash grain changes frequency with resolution.
* **Proposed spec change:** replace "One uber fragment shader..." (line 108) with a fixed render graph:
  > **Passes:**
  > - Pass 0 Normalize
  > - Pass 1 Geometry
  > - Pass 2 Downsample pyramid at fixed short sides (512/256/128), identical in both paths
  > - Pass 3 Combine: colour; bloom and soft focus from the pyramid; unsharp mask against a pyramid level at a normalized radius; vignette; leak; grain
  > - Pass 4 Overlay
  >
  > **Grain** is continuous noise evaluated at normalized coordinates. It is either value or gradient noise, or a tileable, mipmapped grain texture generated procedurally at first launch. It is never a per-pixel hash.

  If bloom cannot meet parity (B1) by the end of Phase B, move bloom out of v1.

### S3. GLES has no 1D textures; consider a baked 3D LUT
* **Reviewer lenses:** codebase-details, inventive-catalyst
* **Affected scope:** line 109
* **Discrepancy:**
  - OpenGL ES 3.0 has no `sampler1D` or `GL_TEXTURE_1D`.
  - Separately, running every colour operation as per-pixel arithmetic costs preview time, and the two shader variants may round differently.
* **Proposed spec change:** line 109 becomes:
  > "`uToneLut`: a 256×1 `GL_TEXTURE_2D` (RGBA8, LINEAR, CLAMP_TO_EDGE) built from control points by monotone cubic interpolation and sampled at `vec2(v, 0.5)`."

  Optionally add: "All spatially-invariant colour operations are baked on the GPU into a 33³ `sampler3D` LUT when the preset changes, so both paths sample identical colour data."

### S6. The MediaStore save path is correct only on API 29+, and the save folder depends on the temporary label
* **Reviewer lenses:** codebase-details, implementation-readiness, comprehension, design, structural
* **Affected scope:** lines 8-10, 40, 75, 103-104
* **Discrepancy:**
  - **API 26-28.** `RELATIVE_PATH` and `IS_PENDING` exist only on API 29+, so API 26-28 needs a separate write path. There, `WRITE_EXTERNAL_STORAGE` is a runtime permission that needs a flow for refusal.
  - **Half-written files on 29+.** Without `IS_PENDING`, other apps can see half-written files.
  - **Permissions wording.** "CAMERA only" on line 103 contradicts line 104.
  - **Folder name.** `Pictures/<AppName>` ties the folder to the temporary label, which conflicts with lines 8-10. After the rename, new photos would go to a different folder and the gallery would miss older captures.
  - **Unspecified details.** JPEG quality and the filename pattern are not given.
* **Proposed spec change:** replace lines 40 and 103-104:
  > **Permissions.** CAMERA (runtime) on all API levels. `WRITE_EXTERNAL_STORAGE` with `maxSdkVersion="28"`, requested at the first save on API 26-28; if refused, show an error with a link to Settings. No storage permission on 29+. Photo Picker needs no permission.
  >
  > **Folder.** Save to `Pictures/<SAVE_FOLDER>`. `SAVE_FOLDER` is a constant in `storage/StorageConfig.kt`, not derived from `app_name`, and is fixed before release (Q2).
  >
  > **API 29+.** Insert with `RELATIVE_PATH` and `IS_PENDING=1`, write the file, then set `IS_PENDING=0`. On startup, delete our own rows that are still pending.
  >
  > **API 26-28.** Write to `getExternalStoragePublicDirectory(DIRECTORY_PICTURES)/<SAVE_FOLDER>`, then insert with `DATA` or scan the file.
  >
  > **File format.** JPEG quality 92 (starting value). Filename pattern `IMG_yyyyMMdd_HHmmss_SSS.jpg`.

  Alternatively, raise minSdk to 29 (owner decision, S9).

### S7. Policies for EXIF, privacy, Play Data Safety and targetSdk 36 behaviour are missing
* **Reviewer lenses:** zoom-out, design, codebase-details, structural
* **Affected scope:** lines 4, 39-40, 74, 122
* **Discrepancy:**
  - **EXIF.** Decoding and re-encoding drops all EXIF, so galleries sort by file time. Orientation must be reset to 1, or images rotate twice. There is no GPS policy, and imports may carry location.
  - **Play declarations.** Data Safety, the privacy policy and the content rating all depend on decisions the spec doesn't make: INTERNET permission, and any analytics, crash or ads SDKs. CAMERA is a sensitive permission.
  - **targetSdk 36** brings enforced edge-to-edge, predictive back and large-screen orientation changes, and Play requires 16 KB page-size alignment for native libraries (all confirm).
* **Proposed spec change:** add `2.1 Privacy & Play`:
  > **Network and SDKs.** v1 declares no INTERNET permission and ships no analytics, crash-reporting or ads SDKs. Data Safety: no data collected or shared.
  >
  > **Output EXIF.** Via `androidx.exifinterface`, copy `DateTimeOriginal`, `OffsetTime*`, `Make`, `Model`, `ExposureTime`, `FNumber`, `ISOSpeedRatings` and `FocalLength`. Set `Orientation=1` and `Software=<app_name>`. Never write GPS, and strip it from imports.
  >
  > **Release prerequisites** (Mobile Release Engineer): a static privacy policy page and the content-rating questionnaire. The listing must not name other camera apps.
  >
  > **targetSdk 36:**
  > - Call `enableEdgeToEdge()`.
  > - Use `WindowInsets.safeDrawing` and cutout padding.
  > - Handle back only through `OnBackPressedDispatcher` / `BackHandler`.
  > - Verify 16 KB alignment of the release APK with the pinned CameraX version.
  > - Re-confirm targetSdk against the current Play requirement at release.

### S8. The in-app gallery empties after a reinstall, and the API 26-28 read path is not stated
* **Reviewer lenses:** design, codebase-details, zoom-out, comprehension
* **Affected scope:** lines 75, 91
* **Discrepancy:**
  - On 29+, the app sees only the MediaStore rows it owns, and loses ownership after a reinstall or data clear (confirm).
  - `READ_MEDIA_IMAGES` is restricted by Play's photo/video permission policy.
  - On 26-28, reading needs READ, which the WRITE grant covers as part of the same group.
  - The spec doesn't say whether imports count as "our captures", or which actions the gallery offers. Delete on 30+ needs `createDeleteRequest`.
* **Proposed spec change:**
  > The gallery shows rows under `Pictures/<SAVE_FOLDER>` owned by this installation, saved imports included, sorted by `DATE_TAKEN DESC`. Never request `READ_MEDIA_IMAGES`. After a reinstall, show an empty state with "Open in Photos". Actions: view (pager), share, and delete (via `createDeleteRequest` on 30+).

  Or defer the gallery (S9).

### S9. Scope: v1 contains a second product (import, Editor, gallery), and minSdk 26 forces a legacy storage branch
* **Reviewer lenses:** zoom-out (with supporting points from design, codebase-details, comprehension, structural)
* **Affected scope:** lines 4, 26-41 (the diagram has no import branch), 57-78, 86, 91, 103-104
* **Discrepancy:**
  - **Import adds a lot of work:**
    - arbitrary input sizes and formats (HEIC, 50-200 MP)
    - EXIF flips
    - the Photo Picker backport on older devices (confirm the manifest `ModuleDependencies` entry)
    - a second parity pair (Editor preview vs saved output)
    - its own seed and date semantics
    - the Editor screen
  - **The section 1 diagram omits the import path entirely.**
  - **The gallery** adds the problems in S8.
  - **Neither is the core value**, which is shooting through a look.
  - **minSdk 26** exists only to support a second save path and permission flow.
* **Proposed spec change** (owner decision; record it in the decisions log):
  > **Option A (recommended).** v1 drops Photo Picker import and the in-app gallery. After capture, a last-shot thumbnail opens the saved image via `ACTION_VIEW`. Import and gallery move to v1.1. Consider also deferring the film-strip frame.
  >
  > **Option B.** Keep import and specify it fully:
  > - Add a third branch to the section 1 diagram: `Photo Picker URI → decode + orientation → Pass 0 (sampler2D) → look graph → overlay → JPEG → MediaStore`.
  > - Pick with `PickVisualMedia(ImageOnly)`, plus the backport manifest entry.
  > - Decode with `ImageDecoder` on 28+. On 26-27, use `BitmapFactory` with `inSampleSize` and `ExifInterface` (all 8 orientations).
  > - Cap input to the B4 limit.
  > - The Editor preview renders the same graph from a downsampled copy, and parity is checked as in B1.
  > - The original file is never modified.
  >
  > **Separately:** consider minSdk 29 to remove the API 26-28 storage branch, after checking device share.

### S10. There is no "Alternatives considered" section, which fails the feature-spec standard
* **Reviewer lenses:** structural, design (codebase-details raised the CameraEffect point)
* **Affected scope:** whole document (lines 82, 95, 108)
* **Discrepancy:** Every major decision is a single path with no rationale:
  - uber shader vs one shader per look
  - GLSurfaceView vs SurfaceView or TextureView driven by our own EGL
  - JPEG→Bitmap vs YUV or HardwareBuffer
  - 1D tone LUT vs baked 3D LUT
  - tiled render vs a resolution cap
  - single module vs multiple modules
  - Most importantly: CameraX's own `CameraEffect` / `SurfaceProcessor` with `PREVIEW | IMAGE_CAPTURE` targets. This is the API CameraX provides for running one GL effect on both paths. Confirm its capture-resolution and quality limits.
* **Proposed spec change:** add `Alternatives considered` as a table (Decision | Chosen | Rejected | Concrete trade-off), one row per item above. Add a time-boxed **Phase A spike** of at most 1 day:
  > "Prototype `CameraEffect`/`SurfaceProcessor`. Adopt it only if its IMAGE_CAPTURE output meets the B4 resolution and quality bar and allows seed control. Otherwise, record why it was rejected."

### S11. There is no section on risks and failure handling
* **Reviewer lenses:** structural, design, inventive-catalyst, implementation-readiness
* **Affected scope:** whole document (lines 97-99, 134-135)
* **Discrepancy:** None of these situations has defined behaviour, so each implementer will invent their own handling:
  - repeated shutter presses during processing (several 48 MB jobs at once)
  - the app going to the background, or the process dying, mid-capture
  - out-of-memory errors
  - a shader failing to compile or link on some GPUs
  - EGL context loss
  - the camera being in use by another app or disconnected (`CameraState` errors)
  - the permission being denied, or permanently denied
  - a full disk
  - an import that can't be decoded
* **Proposed spec change:** add a `Risks & mitigations` table (ID | risk | likelihood/impact | mitigation | phase) and a `3.2 Failure handling` table. At minimum:
  > - **Concurrent captures:** at most 1 in flight. While it processes, the shutter shows a "developing…" state, which fits the film theme. Optionally, persist `{source JPEG, CaptureParams, schemaVersion}` to `cacheDir` before rendering, and resume orphaned jobs at the next start.
  > - **Processing scope:** run capture work in an Application-scoped coroutine, not `viewModelScope`.
  > - **Out of memory:** retry once with the long side at 2048 px, then show an error.
  > - **Shader compile failure:** log `GL_RENDERER` and the info log. Crash in debug builds; fall back to a passthrough shader in release.
  > - **Camera error:** show a non-crashing error state and retry on resume.
  > - **Permission permanently denied:** show a rationale screen with a Settings link.
  > - **Save failure:** delete the pending row so no partial file is left.

### S12. The performance budget can't be measured as written
* **Reviewer lenses:** comprehension, design, implementation-readiness, inventive-catalyst, codebase-details
* **Affected scope:** lines 35, 51-53
* **Discrepancy:**
  - "Mid-range device" and "sustained" are undefined.
  - Line 35 says "~30fps" and line 52 says "≥ 30 fps".
  - "1080p" doesn't fit the 4:3 or 1:1 ratios.
  - Shutter-to-saved has no start or end event, and the spec doesn't say whether the self-timer counts.
  - The 2.5 s target has no breakdown by stage.
  - Thermal throttling is ignored.
  - Preview render size (stream or view) is unstated.
* **Proposed spec change:** replace lines 51-53 (starting values):
  > **Reference device:** <owner names the model and SoC>.
  >
  > **Preview:**
  > - ≥ 30 fps for 95% of frames over 60 s.
  > - Re-measured after 10 min of continuous preview.
  > - GPU time measured with `EXT_disjoint_timer_query` where available.
  > - Renders at min(view size, stream size), and may scale dynamically down to 0.6× to hold 30 fps. Parity is resolution-independent, so this is allowed.
  >
  > **Shutter-to-saved:** from the shutter tap (after any timer) to `IS_PENDING=0`, median of 10 captures at ~12 MP. Stage budget:
  >
  > | Stage | Budget |
  > | --- | --- |
  > | Capture | ≤ 600 ms |
  > | Decode | ≤ 300 ms |
  > | Upload + render | ≤ 400 ms |
  > | Readback | ≤ 150 ms |
  > | Compose | ≤ 150 ms |
  > | Encode + write | ≤ 700 ms |
  >
  > **Shutter-to-next-shot:** ≤ 0.5 s.

### S15. CameraViewModel state and intents are undefined
* **Reviewer lenses:** implementation-readiness, comprehension
* **Affected scope:** line 96
* **Discrepancy:** The spec gives only "selected look, flash, ratio, timer", with no types, defaults or persistence. It also leaves out lens facing, frame, date stamp, capture state and permission state. B-UI depends on all of these.
* **Proposed spec change:**
  > `data class CameraUiState(lookId="classic35", lens=BACK, flash=OFF, ratio=R4_3, timer=OFF, frame=NONE, dateStamp=false, captureState: Idle|Countdown(n)|Processing|Saved(uri)|Error(msg), permission)`
  >
  > Persist `lookId`, `ratio`, `flash`, `timer`, `frame` and `dateStamp` via DataStore Preferences.
  >
  > Intents: `SelectLook`, `ToggleLens`, `CycleFlash`, `SetRatio`, `SetTimer`, `CancelTimer`, `SetFrame`, `ToggleDateStamp`, `Shutter`, `TapFocus(x,y)`.

### S16. Look traits are adjectives, and nothing says when a look is accepted
* **Reviewer lenses:** implementation-readiness, comprehension
* **Affected scope:** lines 62-69
* **Discrepancy:** Descriptions like "green-ish shadows", "slight bloom" and "medium grain" force the implementer to make aesthetic decisions. There is also no definition of when a look is done.
* **Proposed spec change:**
  > Each look ships with numeric presets.json values (Appendix A, supplied by the owner or Visual Storyteller) and a reference render of the parity chart at `docs/looks/<id>.png`. A look is accepted when the owner approves that render. The PNG then becomes a golden file for regression testing.

  If implementers are expected to tune the looks themselves, state that and define an approval loop.

### S17. MVP feature behaviours are underspecified
* **Reviewer lenses:** implementation-readiness, codebase-details, comprehension, zoom-out
* **Affected scope:** lines 60, 70, 73
* **Discrepancy:**
  - **Front flash.** Front cameras usually have no flash. CameraX offers `FLASH_MODE_SCREEN` in newer versions (confirm).
  - **Tap-to-focus.** Without `PreviewView` there is no built-in metering point factory, so taps must be mapped through the inverse preview transform.
  - **Timer.** Cancelling it and the countdown UI are not defined.
  - **Leaks.** "~4" light-leak variants is not an exact number.
  - **Pinch zoom** is not mentioned.
* **Proposed spec change:** add a `2.2 Behaviour table` (feature | trigger | UI state | result | cancel/error). At minimum:
  > - **Front flash:** screen flash if the pinned CameraX version supports it, otherwise hidden.
  > - **Tap-to-focus:** map the tap through the inverse preview transform, then `SurfaceOrientedMeteringPointFactory(bufferW, bufferH)`, then `FocusMeteringAction`. Auto-cancel after 3 s; show a ring indicator.
  > - **Timer:** tapping the shutter again cancels it. The countdown is shown as large numerals.
  > - **Light leaks:** exactly 4 procedural variants, each defined by formula.
  > - **Pinch zoom:** explicitly in or out of v1.

  Manifest: `android.hardware.camera.any` required; `camera.flash` and `camera.autofocus` set to `required="false"`.

### S18. Missing problem statement, goals/non-goals, decisions log and Phase C exit criteria, plus dangling references
* **Reviewer lenses:** structural, comprehension, implementation-readiness
* **Affected scope:** lines 1-5, 13, 57-78, 122, 128, 132, 134-135, 139-150
* **Discrepancy:**
  - There is no problem statement.
  - "Out of v1 (later)" mixes deferred features with permanent non-goals. Q3 (line 150) asks to confirm video for v2, although video is already listed as "later".
  - Some items are stated as decided but are still open: the applicationId (line 13 vs Q2), the font (line 71) and tiling (lines 54-55).
  - Phase C has no exit criteria, and its "orientation matrix" is undefined.
  - People and documents are not identified:
    - "Reviewed by me" (line 128) and "the owner" (line 13) are not named.
    - The Mobile Release Engineer (line 122) is missing from section 7.
    - The design spec (line 132) has no path.
  - The fixed constraints are never stated in the spec.
* **Proposed spec change:**
  - Add a `Problem statement` (who the user is, what they need, what v1 success looks like).
  - Split section 2 into `Goals (v1)`, `Deferred` and `Non-goals`. Non-goals: RAW; manual ISO and shutter; accounts and cloud; ads and analytics; user-editable presets; reproducing any specific third-party app's exact looks.
  - Add a `Fixed constraints` section listing the four constraints at the top of this report.
  - Add a `Decisions log` (ID | decision | Proposed/Accepted | date | rationale), seeded with:
    - applicationId (Proposed, gated by Q2)
    - pass graph
    - no tiling in v1
    - crop mechanism (B2)
    - capture context strategy (S4)
    - frame/stamp rendering approach (B6)
    - S9 scope option
    - glyph approach
  - Phase C exit: the orientation matrix is {back, front} × {0, 90, 180, 270} × each ratio, saved correctly. Capture survives `onTrimMemory(RUNNING_LOW)` on the reference device. Permission flows are tested on API 28 and API 29+.
  - Line 128: "Reviewed by the integrator (section 7) + Codex adversarial review."
  - Line 132: "...from `docs/DESIGN.md` (Visual Storyteller; Phase B UI entry criterion)."
  - Add the Mobile Release Engineer to section 7, and define "owner".

---

## Nits

### N1. "Look", "preset" and "category" are used interchangeably, and there is no glossary
* **Lenses:** structural, comprehension
* **Scope:** lines 16, 18, 61, 89, 109
* **Change:** add a `Glossary`:
  - **Look:** the user-facing option, 1:1 with a preset.
  - **Preset:** the data record that defines a look.
  - **Category:** a descriptive genre only.
  - Also define OES, FBO, LUT, OFL, ISP, vertical slice, worktree, and the agent roles.

  Use "look" in UI text and "preset" for data.

### N2. Section order mixes the project plan with the feature definition
* **Lenses:** structural, zoom-out
* **Scope:** whole document
* **Change:** reorder the sections:
  1. Header
  2. Problem
  3. Goals / Non-goals
  4. Constraints
  5. Requirements & acceptance
  6. Architecture: pipeline & parity (current section 1), modules and threading, shader
  7. Alternatives
  8. Risks
  9. Decisions
  10. Delivery
  11. Open questions
  12. Glossary
  13. Appendix: Build & tooling, Roles

  Keep a "read this first" pointer to the parity section.

### N3. Header metadata is incomplete
* **Lenses:** structural
* **Scope:** lines 1-4
* **Change:** add Owner, Reviewers, Related docs (`docs/DESIGN.md`, pending) and a Changelog line.

### N4. Pseudo-headings, and a table nested in a list
* **Lenses:** structural
* **Scope:** lines 43, 59, 62-69
* **Change:** "Rules:" → `### Parity rules`; "In:" → `### Goals (v1)`. Move the looks table into its own `### v1 looks` subsection.

### N5. "Renaming is a config edit" is inaccurate once display names are string resources
* **Lenses:** implementation-readiness, zoom-out
* **Scope:** lines 18-20
* **Change:** "Renaming a look = editing its string resource; retuning = editing presets.json."

  Optional simplification from zoom-out: a Kotlin table in `Presets.kt` instead of JSON. That gives compile-time checking and needs no parser. Record the choice in the decisions log.

### N6. Q1's description of the emulator is inaccurate
* **Lenses:** codebase-details
* **Scope:** line 148
* **Change:** "The emulator runs GLES 3 and external textures, but cannot validate device camera transforms, vendor driver behaviour or the performance budget."

### N7. Transform wording is imprecise
* **Lenses:** codebase-details
* **Scope:** line 100
* **Change:** "applied in the vertex shader" → "multiplied with the texture coordinates in the vertex shader".

### N8. Build commands are not Windows-aware
* **Lenses:** codebase-details, comprehension
* **Scope:** lines 119-120, 130
* **Change:**
  - "On Windows, use `.\gradlew.bat`."
  - "`sdk.dir` goes in an uncommitted `local.properties`."
  - "The repo is a git repo with a `main` branch. Phase B worktrees merge into `main`."

### N9. "Accessories (prisms, lenses)" is undefined
* **Lenses:** comprehension
* **Scope:** line 77
* **Change:** "Accessories = optional overlay effects that imitate physical lens add-ons (out of v1)."

### N10. Bundled OFL font licensing, if that route is chosen
* **Lenses:** implementation-readiness
* **Scope:** line 71
* **Change:** if a font is bundled, name it (for example DSEG7 Classic, OFL 1.1, after verifying its licence) and ship `assets/licenses/OFL.txt` with an in-app licences screen. This does not apply if the procedural SDF glyphs in B6 are adopted.

---

## Severity re-ratings (compiler overrides of reviewer severity)

| Item | Reviewer rating | Final | Reason |
| --- | --- | --- | --- |
| S10 Alternatives considered | Blocker (structural, design) | Should-fix | Fails the feature-spec standard, but writing the section does not change what Phase A builds. The CameraEffect spike is the one part with impact, and it is time-boxed inside Phase A. |
| S11 Risks & failure handling | Blocker (structural, design) | Should-fix | The mitigations matter for Phase B/C. The Phase A slice can run with crash-on-error behaviour. |
| S1 Seed semantics | Blocker (comprehension) | Should-fix | Has a clear fix and only touches leak and dust, which are not part of the Phase A `classic35` slice. B1's grain-disabled metric is unaffected. |
| S3 No 1D textures in GLES | Blocker (codebase-details) | Should-fix | Factually correct, but the fix is one sentence (256×1 2D texture) and any implementer would hit it immediately. |
| S6 MediaStore / save folder | Blocker (codebase-details) | Should-fix | Phase A can save on API 29+ only. The 26-28 branch and the folder constant can be settled before Phase C. |
| S13 / S14 Toolchain and tests | Blocker (implementation-readiness) | Should-fix [before Phase A] | Mechanical gaps rather than design decisions, but the B1 exit gate depends on them, so they must be settled before Phase A code starts. |
| S4 / S5 GL lifecycle and orientation | Major (all lenses) | Should-fix [before Phase A] | The vertical slice needs them. Tagged so they are not deferred. |

## Reviewer disagreements and resolutions

| Topic | Positions | Resolution in this report |
| --- | --- | --- |
| Capture EGL context | Shared with preview (design, zoom-out) vs independent (implementation-readiness, codebase-details) | Independent, because GLSurfaceView's context can be destroyed at any time. Shared stays an alternative (S10). Record in the decisions log. |
| Aspect-ratio crop mechanism | Shared `ViewPort`/`UseCaseGroup` with rebind (design, codebase-details, zoom-out, inventive-catalyst) vs fixed 4:3 bind + shader `uCropRect`, no rebind (implementation-readiness) | Both are offered in B2, with ViewPort recommended. Owner picks; record in the decisions log. |
| Frame/stamp in preview | GL overlay pass in both paths (zoom-out, inventive-catalyst) vs Compose overlay with shared geometry (implementation-readiness, codebase-details) | GL pass preferred. A Compose overlay using shared `FrameGeometry` is acceptable (B6). |
| Oversize render | Cap, no tiling (most) vs always-tiled (inventive-catalyst) | Cap for v1. Tiling needs its own design later (B4). |
| Import and gallery in v1 | Defer (zoom-out) vs specify fully (others) | Owner decision, Option A recommended (S9). |
| presets.json vs Kotlin table | JSON (spec) vs `Presets.kt` (zoom-out) | Keep JSON unless the owner prefers the simpler option (N5). |

## Suggested revision order for v0.2

The first four steps resolve everything that must be settled before Phase A starts.

1. **Parity foundation:** B1, B2, B3 (parity definition, shared framing, Pass 0 normalization, the ESSL3 fix).
2. **Capture path and GL lifecycle:** B4, S4, S5.
3. **Data and shader contract:** B5, S3, plus Appendix A with a fully numeric `classic35`.
4. **Pinned toolchain and named tests:** S13, S14. Phase A can start after this step.
5. **Phase B readiness:** B6, B7, S1, S2, S12, S15, S16.
6. **Storage, privacy and Play:** S6, S7, S8, S9 (scope decision), S10, S11, S17, S18.
7. **Structure and clarity:** the nits.
