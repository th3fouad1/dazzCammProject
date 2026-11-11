# Design Spec: Vintage Camera App (Android, Compose)

| | |
|---|---|
| Status | DRAFT v0.2, 2026-11-11 |
| Authors | v0.1 Visual Storyteller · v0.2 UX Architect (lead) + UI Designer (visual system) |
| Implements | `docs/SPEC.md` v0.2: §3 goals, §4 edge-to-edge, §6.2 state/intents, §7.3 frames/stamp, §8 behaviours |
| Consumer | Phase B-UI (`ui/**` + new string keys) |
| Name rule | The UI never shows or depends on the working label. The app name appears only through `@string/app_name` (the permission screen title doesn't use the name). |

Conventions:
- All sizes are in dp unless marked sp.
- **Deck** = the area that holds the shutter. **Lamp** = the 6 dp status dot used across the UI. **Chrome** = every control surface that isn't the viewfinder.
- **[K#]** marks a dependency on a contract item that is requested but not yet in SPEC §6.2 (see §10.4). Most have a B-UI fallback. K8, K10 and K16 are **required** (no fallback).
- **[needs integrator OK]** marks anything that touches files outside `ui/**`.

---

## Revision notes (UX Architect / UI Designer)

v0.1 had a strong mood, tokens, copy and swatch art. v0.2 keeps all of that. It changes how the screen behaves on real windows, how the UI talks to the ViewModel, and the details that would not work as written in Compose.

| # | Change | Why |
|---|---|---|
| 1 | **Overlay mode removed.** The viewfinder is always contained and never sits under chrome (§4.1). Tokens `scrimTop` and `scrimBottom` are deleted. | In 16:9 portrait, overlay mode hid about 150 dp of the frame (including the date stamp at bottom-right) under a 72 % scrim. The product promise is "what you frame is what you save" (SPEC §1). |
| 2 | **Layout modes Regular / Compact / Mini**, chosen once per window from raw dp (§4.2). **Window shape** places the chrome. **Image orientation** (display rotation) sets the canvas aspect. The example table was recomputed with a script. | v0.1 treated these as one orientation. A phone in split-screen (about 412 × 390 dp, display still portrait) was classed as landscape and left a 132 dp-wide viewfinder. Two of the v0.1 example values were also wrong. |
| 3 | **Control priority tiers and reach zones** (§3.1). Hardware shutter keys added (volume / camera key). One-handed zoom by dragging the pill, with slider semantics. | Pinch was the only way to zoom, which needs two hands. The top bar is out of thumb reach, so only set-and-forget controls live there. |
| 4 | **Look switching rebuilt around one source of truth** (`lookId`, §6.4). A tap selects. A user drag that settles selects. Programmatic scrolls and TalkBack scrolls never select. Swipes follow the carousel axis. Neither the swipe nor the carousel wraps. The dip on look change is removed. | v0.1 had three problems. TalkBack focus scrolling changed the look. A viewfinder swipe scrolled the carousel, which then selected again on settle. The swipe wrapped but the carousel didn't. The dip also made fast browsing feel slow. |
| 5 | **Processing lockout narrowed** (§6.1). Only Lens and Ratio are disabled while developing, because they rebind the camera. Look, Frame, Date and Flash stay live, **provided the capture job uses a settings snapshot (K17a)**. | SPEC freezes the seed at the shutter, and `CapturePipeline.process(input, settings)` suggests a snapshot, but that isn't stated. If K17a is not confirmed, Look, Frame and Date also lock during Processing. |
| 6 | **Permission flow made explicit** (§3.3). On first run the system dialog opens immediately. The rationale screen appears only after a denial. "Permanently denied" is detected without persisting anything. | The v0.1 rationale screen added a tap before the system dialog and couldn't tell "never asked" from "permanently denied". |
| 7 | **Back, windowing and orientation** (§3.4, §3.5): predictive back, `configChanges` (approved), 180° rotation, rotation lock, multi-window camera loss, foldables without new dependencies. | These are needed for targetSdk 36, and v0.1 had gaps here. |
| 8 | **Contrast audited by script.** Ratios were corrected, for example onAmber on amber is 8.0:1 (v0.1 said 9.4:1). Viewfinder pills now use a 70 % black fill. A halo was added to the focus ring and countdown. | At 40 % black, `ink` text over a white scene was 2.3:1, which fails WCAG AA. |
| 9 | **Compose feasibility fixes.** GLSurfaceView can't be clipped or faded, so corners are square and transitions are overlay veils. `Screen` is an enum (saveable). Fixed-size text uses `dp.toSp()`. Announcements come from a live-region node, not `announceForAccessibility` (being deprecated). Haptics use `HapticFeedbackType`. `collectionInfo` uses the list size. The 9 sp timer badge is replaced by a value label. | v0.1 relied on APIs or behaviours that Compose or the platform don't provide. |
| 10 | **Contract gaps listed** (§10.4, K1–K17). Examples: zoom ratio and range, focus result, camera error kind, typed capture errors, capabilities, permission/retry intents, ScreenFlash hand-off, tap coordinate convention. | The design needed signals that `CameraUiState` doesn't carry, and that contract freezes after Phase A. |
| 11 | **Integrator decisions applied.** Look names are approved as temporary working names. IBM Plex Mono is bundled (the "until approved" fallback is removed). `configChanges` is approved. `FrameGeometry.canvasAspect(...)` is contract, so the UI-side computation is removed. Token files are renamed `Color.kt`, `Type.kt`, `Shape.kt`, `Motion.kt`. §8 row "Pastel border" is renamed to Instant. | Decided by the integrator. |
| 12 | **New §10 "Compose implementation map"**: composable tree, the state each composable reads and the intents it sends, and the `ui/theme/` plan. | This was missing from the B-UI entry criterion. |
| — | **Kept on purpose: forced dark theme, no light/system toggle.** This departs from the UX Architect's usual default. | A viewfinder must be judged against a dark, neutral surround, and a light chrome would change how the image's colour is perceived. The Licenses screen stays dark for consistency. |

---

## 1. Principles and mood

**Mood: "darkroom bench".** A matte graphite instrument lit by a single warm **amber lamp** (ready or selected), **safelight red** while a photo develops, and **cream paper** for prints. The UI looks like a well-made tool, not a copy of any specific camera body. There is no leatherette, no chrome bezels and no fake screws.

**The session as a story.** Each step has its own visual beat:
1. **Load**: the screen is dark and an aperture opens (permission granted, first frame).
2. **Frame**: pick a look and see it live.
3. **Shoot**: the viewfinder blinks and a haptic click fires.
4. **Develop**: the shutter ring shows a safelight sweep.
5. **Print**: the thumbnail drops in like a fresh print.

| # | Principle | What it means in practice |
|---|---|---|
| P1 | **The picture is the hero, and it is honest** | The viewfinder is the largest canvas that fits **without** any chrome over it. It shows exactly the saved canvas, including the frame border and the date stamp. Overlays stay at the viewfinder edges and never cover its centre third, except the countdown and the focus ring. |
| P2 | **One lamp, one meaning** | Amber always means "active/selected/ready". Red always means "busy/error". Colour is never the only signal: every state also has a glyph or label change, plus a TalkBack `stateDescription`. |
| P3 | **Mechanical honesty** | Motion is short and snaps into place, like detents and blinks. Each physical-feeling action has exactly one haptic. Nothing bounces for decoration. |
| P4 | **Own-made, asset-free** | Icons are code-drawn `ImageVector`s, swatches are procedural and glyphs are 7-segment. No bitmaps or icon packs. The only bundled font is IBM Plex Mono (OFL 1.1). |
| P5 | **One thumb runs the loop** | The shooting loop (look, shutter, lens, zoom, last shot) sits in the thumb zone. Set-and-forget settings may sit in the stretch zone (§3.1). Every target is ≥ 48 dp. |

---

## 2. Design tokens

### 2.1 Colour (single dark theme, forced: ignore the system light mode)
Kotlin: `ui/theme/Color.kt` (`object VlColors`). Ratios were computed with the WCAG 2.1 formula. "deck" = `#171614`, "raised" = `#22201D`.

| token | hex | use | vs deck | vs raised |
|---|---|---|---|---|
| `bench` | `#0E0D0C` | window background, letterbox/pillarbox, permission and licenses background, transition veil | — | — |
| `deck` | `#171614` | control bar, deck and rail surfaces | — | — |
| `raised` | `#22201D` | lens button, cards, snackbar, error panel, bottom sheet | — | — |
| `pressed` | `#2E2B27` | pressed fill for every button | — | — |
| `hairline` | `#3A3631` | 1 dp dividers, unselected card border, lamp-off outline (decorative) | 1.5:1 | 1.4:1 |
| `ink` | `#EDE7DC` | primary text, glyphs, shutter ring | 14.7:1 | 13.2:1 |
| `inkMuted` | `#A89F92` | secondary text, inactive glyph | 6.9:1 | 6.2:1 |
| `inkDisabled` | `#5E5850` | disabled glyphs (WCAG-exempt) | 2.6:1 | 2.3:1 |
| `amber` | `#FF8A1F` | lamp on, selected border/name, focus ring, final countdown digits, primary button. Same value as the date-stamp default. | 7.7:1 | 6.9:1 |
| `amberPressed` | `#E07614` | primary button pressed | — | — |
| `onAmber` | `#1A0E02` | text on an amber button | 8.0:1 on amber, 6.1:1 on amberPressed | |
| `safelight` | `#C2361F` | "developing" sweep and glow on the shutter ring (graphic only, never text) | 3.3:1 | 3.0:1 |
| `signal` | `#E5484D` | error glyph, error lamp (graphic only; error **text** is always `ink`) | 4.6:1 | 4.2:1 |
| `onSignal` | `#1A0506` | M3 onError | 5.0:1 on signal | |
| `paper` | `#F4F1EA` | thumbnail print border, Instant swatch border. Same value as the `instant_white` frame. | — | — |
| `shutterCore` | `#D9D2C5` | shutter inner disc idle | — | — |
| `shutterCorePressed` | `#B8B0A2` | shutter inner disc pressed | — | — |
| `flashWhite` | `#FFF8EE` | front screen-flash overlay (warm white) | — | — |
| `vfScrim` | `#B3000000` (70 % black) | fill behind **text** on the viewfinder: zoom pill, look toast | ink on it over a pure-white scene: **6.9:1** | |
| `vfScrimIcon` | `#99000000` (60 % black) | fill behind **icon-only** viewfinder buttons (shuffle) | ink over white scene: 4.7:1 (graphic ≥ 3:1) | |
| `halo` | `#80000000` | 1 dp outer stroke on the focus ring and a 3 dp stroke behind countdown numerals, so amber/ink stay visible on bright scenes (amber on white alone is 2.4:1) | — | |

**Material 3 mapping** (`darkColorScheme`, tonal elevation 0 everywhere). Only the M3 components we use read these: Snackbar, DropdownMenu, TopAppBar, Button, TextButton, ModalBottomSheet.

| M3 role | token | | M3 role | token |
|---|---|---|---|---|
| primary | amber | | surface | deck |
| onPrimary | onAmber | | onSurface | ink |
| secondary | inkMuted | | surfaceVariant, surfaceContainerLowest…Highest | raised |
| background | bench | | onSurfaceVariant | inkMuted |
| onBackground | ink | | outline / outlineVariant | hairline |
| error | signal | | inverseSurface (snackbar) | raised |
| onError | onSignal | | inverseOnSurface | ink |
| scrim | `#99000000` | | surfaceTint | `Color.Transparent` (no tonal tint) |

System bars:
- Call `enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))` in `MainActivity` (SPEC §4).
- The status bar stays visible, with light icons.
- Bars are not hidden in v1, so no immersive-mode handling is needed.

### 2.2 Typography
| family | source | license | used for |
|---|---|---|---|
| **IBM Plex Mono** Medium (500) + SemiBold (600) | bundled `res/font/plex_mono_medium.ttf`, `res/font/plex_mono_semibold.ttf` (**approved**) | SIL Open Font License 1.1. Ship `assets/licenses/ibm_plex_mono_ofl.txt` and list it on the Licenses screen. | control labels, values, look names, numerals |
| **Roboto** (system default) | `FontFamily.Default` | Apache 2.0, system-provided | body copy, titles, buttons, snackbar |

There are no downloadable fonts, because the app has no INTERNET permission and must not depend on GMS.

| style token | family / weight | size | line | tracking | case | notes |
|---|---|---|---|---|---|---|
| `labelEngraved` | Mono 500 | 11 sp | 14 sp | +0.12 em | UPPER | look names, lamp captions, "Developing…" |
| `labelValue` | Mono 600 | 13 sp, capped (see below) | 16 sp | +0.04 em | as-is | ratio "4:3", timer "3s"/"10s" |
| `zoom` | Mono 600 | 12 sp, capped | 16 sp | 0 | as-is | "1.0×", `fontFeatureSettings = "tnum"` |
| `lookToast` | Mono 600 | 14 sp | 18 sp | +0.16 em | UPPER | look-name toast on the viewfinder |
| `countdown` | Mono 600 | fixed 160 dp: `with(LocalDensity.current) { 160.dp.toSp() }` | 1.0 | −0.02 em | — | `tnum`; decorative, announced separately |
| `title` → M3 `titleLarge` | Roboto 500 | 22 sp | 28 sp | 0 | sentence | permission and licenses titles |
| `body` → M3 `bodyLarge` | Roboto 400 | 15 sp | 22 sp | 0 | sentence | permission body, error panel, snackbar |
| `button` → M3 `labelLarge` | Roboto 500 | 15 sp | 20 sp | +0.02 em | sentence | M3 buttons |
| `licenseText` | Mono 500 | 12 sp | 18 sp | 0 | as-is | full licence text, inside `SelectionContainer` |

Font scaling:
- All text scales with the user's font size.
- Text inside fixed 48 dp controls (`labelValue`, `zoom`) is capped at 1.3× with `(13f * min(fontScale, 1.3f)).dp.toSp()`. Use `dp.toSp()`, not division by `fontScale`, so Android 14+ non-linear font scaling is respected.
- Meaning is always duplicated in `contentDescription` / `stateDescription`.
- Look names use one line and ellipsize.
- Text components on the camera screen use `TextDirection.Content` (see the RTL rule in §4.1).

### 2.3 Spacing, radii, elevation, z-order
| spacing token | xs | s | m | l | xl | xxl |
|---|---|---|---|---|---|---|
| dp | 4 | 8 | 12 | 16 | 24 | 32 |

| radius token | dp | used on |
|---|---|---|
| `rViewfinder` | **0** | the viewfinder. GLSurfaceView is a separate surface, so Compose `clip()` and `alpha` don't apply to it. Square corners avoid corner masks. |
| `rChip` | 4 | control-button pressed plate, snackbar, dropdown menu (M3 `extraSmall`) |
| `rSwatch` | 6 | swatch inside a card |
| `rCard` | 8 | look card, thumbnail, error panel, license card (M3 `small` / `medium`) |
| `rSheet` | 16 | bottom-sheet top corners (M3 `large`) |
| `rFull` | 50 % | shutter, lens button, lamp, zoom pill, focus ring, look-toast pill |

**Elevation: flat.** All surfaces use tonal elevation 0 and no shadows. Separation comes from colour steps (`bench` < `deck` < `raised`). Snackbar, DropdownMenu and ModalBottomSheet keep their M3 default shadows.

**Z-order**, bottom to top. Composables are drawn above the GLSurfaceView's hole. Never call `setZOrderOnTop` or `setZOrderMediaOverlay(true)`.
1. `bench`
2. viewfinder (`AndroidView` hosting the `GLSurfaceView`)
3. viewfinder input and semantics node (transparent)
4. viewfinder overlays: focus ring, shuffle, zoom pill, look toast
5. countdown
6. transition veil (dip and blink), camera status panel
7. chrome (control bar, carousel, deck)
8. snackbar
9. screen-flash overlay (full window, above everything)

### 2.4 Motion
Kotlin: `ui/theme/Motion.kt`.

| token | duration | easing | use |
|---|---|---|---|
| `instant` | 70 ms | `LinearEasing` | shutter press-in, button pressed plate |
| `quick` | 120 ms | `Standard` | lamp on/off, icon cross-fade, toast in, veil in |
| `standard` | 220 ms | `Standard` | focus ring settle, countdown enter, icon rotate, veil out |
| `emphasized` | 360 ms | `EmphasizedDecel` | thumbnail print-drop, aperture open, lens flip glyph |
| `developLoop` | 1400 ms | `LinearEasing`, infinite | safelight sweep |
| `detent` | spring | `spring(dampingRatio = 0.75f, stiffness = 800f)` | carousel centring, zoom-pill expansion |

Easings: `Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)`, `EmphasizedDecel = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)`, `Accel = CubicBezierEasing(0.3f, 0f, 1f, 1f)`.

**Reduced motion:**
- Reduced motion is on when `Settings.Global.ANIMATOR_DURATION_SCALE == 0f`.
- Read it on every `ON_RESUME` into `LocalReducedMotion`.
- Every animation then becomes either an alpha cross-fade of ≤ 120 ms or an instant change.
- The blink becomes a 120 ms 30 % dim.
- The develop sweep becomes a static safelight ring.

### 2.5 Haptics
Use `LocalHapticFeedback.current.performHapticFeedback(type)`, which respects the system setting. Never use `Vibrator`. Compose maps each `HapticFeedbackType` to platform constants with API fallbacks.

At the start of Phase B, verify that the pinned BOM (2025.09.01) exposes each type below. If one is missing, call `LocalView.current.performHapticFeedback(...)` with the constant in the last column.

| event | `HapticFeedbackType` | view fallback (API 29) |
|---|---|---|
| shutter finger-down (touch or hardware key) | `VirtualKey` | `VIRTUAL_KEY` |
| capture fires (after any timer) | `Confirm` | `LONG_PRESS` |
| look changes (tap, swipe or settle) | `SegmentTick` | `CLOCK_TICK` |
| toggle on / off (date stamp; cycle buttons when they leave or return to "off") | `ToggleOn` / `ToggleOff` | `CLOCK_TICK` |
| countdown each second | `SegmentTick` | `CLOCK_TICK` |
| zoom crosses a 1×, 2× or 5× detent | `SegmentFrequentTick` | `CLOCK_TICK` |
| focus locked | `ContextClick` | `CONTEXT_CLICK` |
| error snackbar or error panel shown | `Reject` | `LONG_PRESS` |
| shutter tapped while developing; swipe past the first or last look | none | — |

---

## 3. Information architecture and flows

### 3.1 Control priority and reach zones
Portrait, one hand, phone ~6.1–6.7″. The **thumb zone** is the bottom ~45 % of the safe area. The **stretch zone** is the top ~25 %. Landscape: the end-side rail is the thumb zone (right hand on the right edge in either landscape rotation, because the layout never mirrors; see §4.1).

| tier | rule | controls | where |
|---|---|---|---|
| **T1: the shooting loop** | one tap, always visible, thumb zone | Shutter, Look (carousel), Lens, Last shot | Deck + carousel |
| **T1 gestures** | no visible chrome needed | tap to focus, pinch or pill drag to zoom, swipe to change look, volume/camera key = shutter | viewfinder, hardware |
| **T2: set-and-forget** | one tap, always visible; persisted in DataStore (SPEC §6.2), so they're rarely touched mid-session | Flash, Timer, Ratio, Frame, Date stamp | control bar (stretch zone). In Mini mode they move into the Controls sheet. |
| **T3: contextual** | shown only when it applies | Shuffle (only when the look has a leak or dust), Cancel timer (the shutter itself) | viewfinder bottom-start, shutter |
| **T4: rare** | behind ⋯ | Open-source licenses, Import photo, Gallery, Keep negatives (v1.1, section 12) | ⋯ menu |

Rule for future controls: anything used on most shots must go in T1. Adding a T2 control needs a slot in both the control bar **and** the Controls sheet (§4.6).

### 3.2 Screens and navigation
There is no navigation library, because adding a dependency needs approval.
- Routes are `enum class Screen { Camera, Licenses, Gallery, Editor }` (v1.1, section 12), held in `rememberSaveable` (enums are saveable as-is).
- The permission and error states are **states of the Camera route**, not separate destinations.

```
App start ─► Camera route
              ├─ permission ≠ Granted ──► PermissionGate (§3.3)
              └─ permission = Granted ──► CameraScreen
                                           ├─ camera status ≠ Ready ─► CameraStatusPanel in the viewfinder slot
                                           └─ ⋯ menu ─► Licenses
PermissionGate footer link ─► Licenses
Licenses ─ back (system predictive back or top-bar arrow) ─► Camera
```

| screen / state | file | entry | exit |
|---|---|---|---|
| CameraScreen | `ui/CameraScreen.kt` | permission Granted | ⋯ › Licenses / Import / Gallery; thumbnail › our Viewer (v1.1; v1 used external `ACTION_VIEW`) |
| PermissionGate | `ui/PermissionScreen.kt` | permission Denied or PermanentlyDenied | see §3.3 |
| CameraStatusPanel (Opening / Error) | `ui/CameraScreen.kt` | camera status ≠ Ready **[K3]** | automatic, or "Try again" |
| Licenses | `ui/LicensesScreen.kt` | ⋯ menu, or the PermissionGate footer | back |
| Gallery (grid + Viewer) | `gallery/GalleryScreen.kt` | ⋯ › Gallery; thumbnail; after an editor save | back; Re-develop › Editor |
| Editor | `editor/EditorScreen.kt` | ⋯ › Import photo (Picker); Viewer › Re-develop | back; Save › Gallery (see section 12) |

**Leaving the Camera route unbinds the camera.** On Licenses, `CameraScreen` leaves composition, the `GLSurfaceView` is disposed and `CameraController` unbinds. The privacy indicator turns off. On return the camera rebinds behind the opening veil (§6.7).

### 3.3 Permission flow
SPEC has `permission: Granted | Denied | PermanentlyDenied`. The UI resolves it like this, and nothing is persisted:

1. **Cold start, not granted.** Render PermissionGate with the rationale copy, and launch the system request once from a `LaunchedEffect`. Guard it with a `rememberSaveable` flag `autoRequested`. It survives rotation and process death on purpose: after a process restart the user has already seen the dialog, and the rationale button is one tap away.
2. **Result granted.** Send `PermissionResult(granted = true)` **[K8]**. The aperture opens and the screen cross-fades to the camera (§6.7).
3. **Result denied, and `shouldShowRequestPermissionRationale == true`** → `Denied`. Keep the rationale layout with the "Allow camera" button, which relaunches the request. Never auto-request again in this process.
4. **Result denied, and rationale `false`** → `PermanentlyDenied`. This covers "Don't ask again" and the Android 11+ second denial. If the dialog was suppressed, the system returns denied instantly, so a cold start lands here with no flash of the rationale. Show the settings layout. "Open settings" fires `ACTION_APPLICATION_DETAILS_SETTINGS` with `package:` URI.
5. **On every `ON_RESUME`**, re-check `checkSelfPermission` and send `RefreshPermission(granted)` **[K8]**. This covers a grant in Settings, and one-time ("Only this time") grants that expire in the background.
   - `RefreshPermission` only moves the state **to or from Granted**.
   - It never changes Denied ↔ PermanentlyDenied. Only a launcher result (`PermissionResult`) does that, because `shouldShowRequestPermissionRationale` is unreliable outside a result (it is false both before the first ask and after "Don't ask again").
   - The resume that follows the dialog closing is therefore harmless.

The launcher is `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission())` inside `PermissionScreen.kt`. It needs only activity-compose, which is already pinned.

### 3.4 Back behaviour (predictive back, targetSdk 36)
- **Camera root**: there is **no enabled back callback**, so the system plays the predictive back-to-home animation.
  - Exception: while `captureState is Countdown`, a `BackHandler(enabled = true)` sends `CancelTimer`. A back gesture during a self-timer means "stop".
- **Licenses**: use `PredictiveBackHandler`. While the gesture progresses, scale the screen to 1 → 0.9 and offset it 0 → 24 dp toward the swipe edge. On commit, set `screen = Camera`. On cancel, spring back.
- **DropdownMenu and ModalBottomSheet** consume back themselves and must not add a handler.

### 3.5 Window, orientation, foldables, multi-window
- **`configChanges`**: `MainActivity` declares `android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden"` (**approved** list). Rotation, fold/unfold and multi-window resizes then recompose without tearing down GL or the camera. The layout reads only `BoxWithConstraints` sizes, never `Configuration.orientation`.
  - **Proposed, not approved (U3):** also add `density|uiMode`. Some foldables change density on fold, and `uiMode` changes (dark mode, car/desk dock) don't affect us, since the theme is forced dark. Without them those events recreate the Activity, which is safe but costs a camera/GL restart.
- **Two orientations, kept separate:**
  - **Window shape** = `safe.width > safe.height`. It decides where the chrome goes (§4.2).
  - **Image orientation** = whether the upright camera image is taller than wide. It comes from the **display rotation**, not from the window: `imagePortrait = (rotation ∈ {0°, 180°}) == naturalPortrait`, where `naturalPortrait = display.mode.physicalWidth < physicalHeight`. Prefer the `imagePortrait` field **[K14]** so the UI and the ViewPort share one source; the formula is the fallback.
  - The two differ in split-screen (a landscape-shaped window on a portrait display) and on some foldables.
- **180° flips** (landscape ↔ reverse landscape) don't trigger a configuration change. `CameraController` must listen through `DisplayManager.DisplayListener` and update `targetRotation` **[K16]**.
- **Rotation lock on**: the UI stays portrait, and in v1 capture follows the display, so the photo matches the preview exactly (a sideways hold saves a portrait canvas). Following the device orientation instead would save upright landscape photos, but the frame border and date stamp would then sit on a different edge than previewed. That is an open question **[K16]**. Counter-rotating the glyphs is v1.1.
- **Multi-window and multi-resume**: when another resumed app takes the camera, CameraX reports `ERROR_CAMERA_IN_USE` / `ERROR_MAX_CAMERAS_IN_USE`. The UI shows the "in use" panel without a full-screen takeover, disables the shutter, and recovers on its own when the camera status returns to Ready.
- **Foldables**: there is no posture-specific layout in v1. Tabletop and book layouts need `androidx.window`, a new dependency, and are deferred to v1.1. An unfolded inner screen is just a large window (§4.3 example 673 × 841). A hinge that crosses the viewfinder is accepted in v1.
- **Picture-in-picture**: not supported (default). Freeform windows use the same mode rules.
- **Keep screen on**: while CameraScreen is composed, `DisposableEffect { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }`.

---

## 4. Camera screen layout

### 4.1 Layout algorithm
1. **Safe area.** The outer `Box` fills the window with `bench`, edge to edge. The inner layout uses `Modifier.windowInsetsPadding(WindowInsets.safeDrawing)`, which includes the cutout and bars, then `BoxWithConstraints`. `safe` = its `maxWidth × maxHeight`.
2. **No RTL mirroring of the camera layout.** Wrap `CameraLayout` in `CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr)`. Text still renders RTL through `TextDirection.Content`. Camera controls are spatial, like media transport controls, and right-hand reach doesn't depend on the locale.
3. **Canvas aspect** = `FrameGeometry.canvasAspect(ratio, frame, imagePortrait)` (approved SPEC contract). It returns width / height of the **output canvas including the frame margin**, so the viewfinder shows the paper border and film strip exactly as saved (SPEC §7.3). The UI doesn't compute it itself.
4. **Mode** (Regular / Compact / Mini, §4.2) is chosen **once per window size** using a fixed reference aspect (4:3, no frame). Changing the ratio or frame then never makes the chrome jump.
5. **Slot** = `safe` minus the chrome of the chosen mode.
6. **Contain, always.** The viewfinder is the largest rect of the canvas aspect that fits in the slot, centred in it. Leftover space is `bench`. Chrome never covers the viewfinder.
7. **Size changes use a veil, not animation.** On a ratio, frame or lens change, the transition veil covers the viewfinder (`bench`, alpha → 1, `quick`). Apply the new size and rebind, then lift the veil on the first new frame (`standard`) **[K11]**. The fallback is to lift it 350 ms after the size applies, and to wait at most 800 ms for a rebind. Never animate GLSurfaceView bounds.

### 4.2 Layout modes
The chrome sizes below are for the axis the chrome occupies.

| mode | window portrait (h ≥ w) | window landscape (w > h) |
|---|---|---|
| **Regular** | control bar 56 (top) + carousel 96 + deck 120 (bottom) = 272 | start rail 64 + carousel 96 + deck 120 (end) = 280 |
| **Compact** | control bar 56 + carousel 64 (cards 56 × 56, swatch only) + deck 104 (shutter 72) = 224 | start rail 56 + carousel 64 + deck 104 = 224 |
| **Mini** | one bottom bar 88: look chip, shutter 64, lens, ⋯ | one end rail 88, same items |

**Selection rule.** For the window's shape, take the first of Regular → Compact → Mini in which the reference 4:3 viewfinder area is ≥ 40 % of `safe.width × safe.height`. Mini always qualifies. Implement it as a pure function `chooseMode(safeW, safeH, imagePortrait): LayoutMode` in `ui/CameraLayout.kt` and unit test it with the table below.

Additional rules:
- **Wide portrait** (`safe.width ≥ 600`, for example a tablet or unfolded inner screen): the chrome keeps phone sizes. The content of the control bar, carousel and deck is centred with a max width of 560. The viewfinder may be wider.
- **Landscape, `safe.height < 360` (Regular or Compact)**: the start rail keeps Flash, Timer, Ratio and ⋯. Frame and Date stamp move into the ⋯ menu as checkable items, so 4 × 48 dp fits.

### 4.3 Example results (computed; dp, safe area)
These are informative. `chooseMode` and `canvasAspect` are authoritative. Columns give the viewfinder size.

| window | mode | 4:3 | 1:1 | 16:9 | 4:3 + instant | 4:3 + strip |
|---|---|---|---|---|---|---|
| 412 × 859 phone portrait | Regular | 412 × 549 | 412 × 412 | 330 × 587 | 408 × 587 | 393 × 587 |
| 360 × 728 small phone | Regular | 342 × 456 | 360 × 360 | 256 × 456 | 317 × 456 | 305 × 456 |
| 360 × 560 short window | Compact | 252 × 336 | 336 × 336 | 189 × 336 | 233 × 336 | 225 × 336 |
| 859 × 412 phone landscape | Regular | 549 × 412 | 412 × 412 | 579 × 326 | 468 × 412 | 474 × 412 |
| 412 × 390 split-screen (display portrait, `imagePortrait = true`) | Mini | 292 × 390 | 324 × 324 | 219 × 390 | 271 × 390 | 261 × 390 |
| 673 × 841 unfolded inner screen | Regular | 427 × 569 | 569 × 569 | 320 × 569 | 395 × 569 | 381 × 569 |
| 1232 × 800 tablet landscape | Regular | 952 × 714 | 800 × 800 | 952 × 536 | 908 × 800 | 920 × 800 |
| 480 × 360 freeform | Mini | 392 × 294 | 360 × 360 | 392 × 220 | 392 × 345 | 392 × 341 |

Assumptions used for the frame columns (FrameGeometry, SPEC §7.3):
- `instant_white` adds 0.12 S to the width and 0.28 S to the height.
- `film_strip` adds 0.16 S to the height in output orientation.

### 4.4 Portrait wireframe (Regular)
```
┌──────────────── window (bench, edge-to-edge) ────────────────┐
│░░░░░░░░░░░░░░ status bar / cutout (safeDrawing.top) ░░░░░░░░░│
├──────────────────────────────────────────────────────────────┤ ┐
│ [⚡]  [3s]  [4:3]  [▭]  [8.]                           [⋮]    │ │ ControlBar 56 (stretch zone, T2)
│  •     •           •    •    ← lamps (6dp) under toggles     │ │ 48dp buttons, 4dp gaps, 8dp side pad
├──────────────────────────────────────────────────────────────┤ ┘
│ ┌──────────────────────────────────────────────────────────┐ │ ┐
│ │ (look toast pill, top-centre)                            │ │ │
│ │                    VIEWFINDER                            │ │ │ Slot (flex): viewfinder contained,
│ │          (canvas aspect incl. frame margin)   ┌───┐      │ │ │ letterbox = bench, never under chrome
│ │                                               │ ◯ │ focus│ │ │
│ │                                               └───┘ 72   │ │ │
│ │ [⚄]                      ( 1.0× )            '26 11 11   │ │ │ shuffle bottom-start, zoom bottom-centre;
│ └──────────────────────────────────────────────────────────┘ │ │ the date stamp area (bottom-end) stays clear
├──────────────────────────────────────────────────────────────┤ ┘
│  ┌────┐ ┌────┐ ┏━━━━┓ ┌────┐ ┌────┐ ┌────┐                    │ ┐ LookCarousel 96 (thumb zone, T1)
│  │swch│ │swch│ ┃swch┃ │swch│ │swch│ │swch│  ← LazyRow, snap   │ │ card 72×88, gap 8
│  │NAME│ │NAME│ ┃NAME┃ │NAME│ │NAME│ │NAME│    selected centre │ │
│  └────┘ └────┘ ┗━━━━┛ └────┘ └────┘ └────┘                    │ ┘
│     ┌────┐              ╭──────╮              ╭────╮         │ ┐ Deck 120 (thumb zone, T1)
│     │thumb│             │  ◯   │              │ ⟲  │         │ │ thumb 56 | shutter 80 | lens 56
│     └────┘              ╰──────╯              ╰────╯         │ │ each centred in a third
│                       DEVELOPING…                            │ ┘ (label only while Processing > 600 ms)
│░░░░░░░░░░░░░░ navigation bar (safeDrawing.bottom) ░░░░░░░░░░░│
└──────────────────────────────────────────────────────────────┘
Snackbar: 8 above the carousel top edge, horizontal margin 16, max width 560.
```

### 4.5 Landscape wireframe (Regular; never mirrored; see §4.1-2)
```
┌─────────────────────────────────── window (bench) ──────────────────────────────────┐
│░cutout░│┌──────┐┌───────────────────────────────────────────┐┌───────┐┌──────────┐ │
│░(safe ░││ [⚡]• ││                                           ││ ┌───┐ ││  ╭────╮  │ │
│░ start)││ [3s]•││                                           ││ │sw │ ││  │ ⟲  │  │ │ lens 56
│░       ││ [4:3]││              VIEWFINDER                   ││ └───┘ ││  ╰────╯  │ │
│░       ││ [▭]• ││                                           ││ ┏━━━┓ ││ ╭──────╮ │ │
│░       ││ [8.]•││                                           ││ ┃sw ┃ ││ │  ◯   │ │ │ shutter 80
│░       ││      ││                                           ││ ┗━━━┛ ││ ╰──────╯ │ │
│░       ││      ││ [⚄]               ( 1.0× )                ││ ┌───┐ ││  ┌────┐  │ │
│░       ││ [⋮]  ││                                           ││ │sw │ ││  │thmb│  │ │ thumb 56
│░       │└──────┘└───────────────────────────────────────────┘└───────┘└──────────┘ │
└──────────────────────────────────────────────────────────────────────────────────────┘
 Start rail 64 (T2)           Slot (flex)                        Carousel  Deck 120 (T1)
 buttons 48, SpaceEvenly,                                        96 wide   column, SpaceEvenly
 ⋮ pinned to the bottom                                          LazyColumn
```
- In landscape the carousel is vertical: the same `LookPicker(axis)` composable, with cards 88 wide × 72 tall and the name to the right of a 48 × 48 swatch.
- The snackbar sits bottom-centre of the slot, max width 480.

### 4.6 Mini wireframe (split-screen, freeform, very short windows)
```
 portrait-shaped window                        landscape-shaped window (e.g. 412 × 390)
┌──────────────────────────────┐              ┌───────────────────────────────┬────────┐
│                              │              │                               │ [●sw]  │ look chip 48
│         VIEWFINDER           │              │          VIEWFINDER           │  ╭──╮  │
│ [⚄]       (1.0×)             │              │ [⚄]        (1.0×)             │  │◯ │  │ shutter 64
├──────────────────────────────┤              │                               │  ╰──╯  │
│ [●sw]    ╭──╮    [⟲]   [⋮•]  │ bar 88       │                               │  [⟲]   │ lens 48
│          ╰──╯                │              │                               │  [⋮•]  │ ⋯ 48 (lamp if flash/timer ≠ off)
└──────────────────────────────┘              └───────────────────────────────┴────────┘
```
- **Look chip**: a 32 dp swatch of the current look inside a 48 dp target. Tapping it opens the **Controls sheet** scrolled to the looks.
- **⋯** opens the same sheet. Its lamp is on when flash ≠ OFF or timer ≠ OFF, because those settings affect the next shot.
- **Controls sheet** (`ModalBottomSheet`, `raised`, `rSheet`):
  - a horizontal `LookPicker` (Regular cards)
  - a row of the five T2 toggles (same components)
  - a row with the last-shot thumbnail (56) and a "Open-source licenses" `TextButton`
- The sheet stays open while toggling. A selected look closes it after 300 ms, so the user sees the change.

### 4.7 Element positions (portrait Regular, reference 412 × 859 safe)
| element | size (dp) | position |
|---|---|---|
| ControlBar | full width × 56 | top of `safe`; buttons 48 × 48 in a start-aligned group with 4 gaps and 8 padding; ⋯ end-aligned |
| Viewfinder | per §4.1 | centred in the slot; square corners |
| Shuffle | 48 touch / 40 visual circle (`vfScrimIcon` fill, ink glyph 22) | viewfinder bottom-start, inset 12 |
| Zoom pill | 32 visual high, min 56 wide, `vfScrim`, `rFull`; **48 touch** via `Modifier.minimumInteractiveComponentSize()` | viewfinder bottom-centre, inset 12; expands to 200 × 40 while zooming |
| Look toast | pill `vfScrim`, `rFull`, padding 12 × 6 | viewfinder top-centre, inset 16 |
| Focus ring | 72 diameter | centred on the tap point, clamped 36 inside the **image area** (not the frame margin) |
| Countdown | 160 numeral | centre of the image area |
| CameraStatusPanel | the viewfinder rect (or a 3:4 rect in the slot before the first bind), `raised`, `rCard`, padding 24 | over the viewfinder |
| LookCarousel | full width × 96 | directly above the deck; `contentPadding` horizontal = `(width − 72) / 2` so the selected card can centre |
| Deck | full width × 120 | bottom of `safe` |
| Thumbnail | 56 × 56 | deck start third, centred |
| Shutter | 80 × 80 | deck centre |
| Developing label | text | 4 below the shutter, inside the deck |
| Lens switch | 56 visual / 56 touch | deck end third, centred. If there is no second camera **[K5]**, it is hidden and its slot is kept. |
| Snackbar | max width 560, margin 16 | 8 above the carousel |

Viewfinder overlays (shuffle, zoom, toast) stay **outside the central third** and clear of the date stamp's bottom-end corner. The focus ring and countdown are the only exceptions.

---

## 5. Component inventory

Icons are custom `ImageVector`s in `ui/icons/VlIcons.kt`: 24 dp grid, 1.75 dp stroke, round caps and joins, `ink` tint, and no fills except where noted. They are all original geometric drawings, described here so that any developer draws the same thing.

| icon | drawing |
|---|---|
| `FlashOff` | zig-zag bolt (3 segments) with a diagonal slash from top-left to bottom-right |
| `FlashOn` | the same bolt, filled |
| `FlashAuto` | outline bolt plus a small "A" drawn as 2 strokes at the bottom-right (8 dp) |
| `ScreenFlash` | rounded rect (10 × 16, r 2) with 3 short rays from its top edge |
| `Timer` | circle r 8, a tick at 12 o'clock outside it, and a hand from the centre to 2 o'clock (shown only when the timer is off) |
| `FrameNone` | dashed rect 16 × 16 (dash 2, gap 2) |
| `FrameInstant` | rect 16 × 18, with a filled band in the bottom 5 dp |
| `FrameStrip` | rect 18 × 14, with 3 dots (r 1) above and 3 below |
| `DateStamp` | one 7-segment "8" glyph (filled segments, 1.5 dp gaps), 10 × 16 |
| `Shuffle` | a die face (rounded square 16, r 3) rotated 12°, with 3 diagonal pips |
| `LensSwitch` | two opposing 160° arcs around a centre dot, with an arrowhead on each |
| `More` | 3 vertical dots, r 1.5 |
| `Back` | left chevron plus a shaft (Licenses screen only; mirrors in RTL there) |
| `Aperture` | 6 blades (permission illustration, 96 dp), animated |

### 5.1 Components
Every interactive component is ≥ 48 × 48 dp touch, either through its own size or `minimumInteractiveComponentSize()`.

| component | sizes | states | TalkBack | semantics |
|---|---|---|---|---|
| **ControlButton** (generic base, `ui/Controls.kt`) | touch 48 × 48; glyph 24 **or** `labelValue` text; lamp 6 at y +19 from centre | default: ink · pressed: `pressed` plate `rChip` 40 × 40, 70 ms · disabled: inkDisabled, no lamp · on: amber lamp filled · off: lamp is a 1 dp `hairline` outline | per instance | `Role.Button` (cycle) or `Role.Switch` (binary), plus `stateDescription` |
| **Flash** (cycle) | base | **Back lens**: OFF › AUTO › ON › OFF; the glyph changes; the lamp is on when ≠ OFF. **Front lens**, with screen flash supported **[K5]**: OFF › ON › OFF, where ON is shown with the `ScreenFlash` glyph and AUTO is shown as ON. **Hidden, slot kept**: on the front lens without screen-flash support, or the back lens without a flash unit. No new enum: the camera layer maps ON/AUTO on the front lens to `FLASH_MODE_SCREEN`. **`CycleFlash` on the front lens skips AUTO** (OFF ↔ ON), so every tap visibly changes something. A stored AUTO is shown as ON there and is kept when switching back. | `cd_flash` "Flash" | `state_flash_off` / `_auto` / `_on` / `_screen` |
| **Timer** (cycle) | base; shows the `Timer` glyph when off, and the `labelValue` text "3s" / "10s" when on (replaces the v0.1 9 sp badge) | OFF › 3 › 10 › OFF; lamp on when ≠ OFF · disabled during Countdown | `cd_timer` "Self-timer" | `state_timer_off` / `_3` / `_10` |
| **Ratio** (cycle) | base; `labelValue` text | 4:3 › 1:1 › 16:9 › 4:3; no lamp (always set) · disabled during Countdown and Processing | `cd_ratio` "Aspect ratio" | `state_ratio_4_3` etc. ("4 by 3") |
| **Frame** (cycle) | base | none › instant › strip › none; the glyph changes; lamp on when ≠ none · always enabled | `cd_frame` "Frame" | `state_frame_none` / `_instant` / `_strip` |
| **Date stamp** (switch) | base | on: amber-tinted 7-segment glyph + lamp · off: ink glyph, lamp outline · always enabled | `cd_date_stamp` "Date stamp" | `Role.Switch`, `toggleableState` |
| **More** | base | opens a `DropdownMenu` (raised, `rChip`) with "Open-source licenses". In landscape with height < 360 it also holds Frame and Date stamp as checkable items. | `cd_more` "More options" | `Role.Button` |
| **Shuffle** | 48 touch, 40 visual | default · pressed: fill `#CC000000` · **shown only** when the current look `hasRandomFx` **[K6]** (fallback: always shown) | `cd_shuffle` "Shuffle light leak and dust" | `Role.Button`; announces `a11y_shuffled` |
| **Shutter** | 80 touch (72 in Compact, 64 in Mini); outer ring 76 Ø, stroke 3 `ink`; gap 4; inner disc 62 Ø `shutterCore`; scale the parts proportionally | idle · pressed: the disc scales to 0.87 (`shutterCorePressed`, 70 ms) · countdown: the disc becomes a 24 × 24 `ink` rounded square (r 4) = "cancel" · processing: disc `pressed`, ring `hairline` plus a 90° `safelight` arc sweeping (`developLoop`) and an 8 dp outer `safelight` glow at 35 % · unavailable (camera not Ready): ring `inkDisabled`, disc `raised` | idle: `cd_shutter` "Take photo"; countdown: `cd_shutter_cancel` "Cancel timer"; processing: `cd_shutter_busy` "Developing photo" | `Role.Button`; processing and unavailable set `disabled()` |
| **Lens switch** | 56 Ø `raised` circle, glyph 24 | default · pressed `pressed` · switching: the glyph rotates 180° (`emphasized`) · disabled during Countdown and Processing | `cd_lens_switch` "Switch camera"; state `state_lens_back` / `state_lens_front` | `Role.Button` |
| **Last-shot thumbnail** | 56 × 56, `rCard`, 2 dp `paper` border, image `ContentScale.Crop` | empty (lastShot null): 1.5 dp dashed `hairline` border, `FrameInstant` glyph inkDisabled 20, disabled · developing: `raised` fill with a `safelight` pulse (alpha 0.06 ↔ 0.14, 1400 ms) · has photo: a 112 px thumbnail from `ContentResolver.loadThumbnail(uri, Size(112, 112), null)` on `Dispatchers.IO` · pressed: scale 0.94 | empty: `cd_thumb_empty` "No photos yet"; has photo: `cd_thumb` "Open last photo" | `Role.Button` |
| **Look card** | §5.2 | default · pressed (scale 0.96) · selected · always enabled | name + `cd_look_suffix` → "Golden look" | `Modifier.selectable(selected, role = Role.RadioButton)`. The list uses `selectableGroup()` and `collectionInfo(rowCount, columnCount)` from `looks.size` (1 × n in portrait, n × 1 in landscape). Each item has `collectionItemInfo`. |
| **LookPicker** | 96 high (portrait) / 96 wide (landscape); Compact 64 | `LazyRow` / `LazyColumn` with `rememberSnapFlingBehavior(lazyListState, SnapPosition.Center)`. Selection rules in §6.4. | — | — |
| **Look chip** (Mini) | 48 touch, 32 swatch, lamp | opens the Controls sheet | `cd_look_chip` "Look: %1$s. Show looks and controls" | `Role.Button` |
| **Zoom pill** | 32 high, min 56 wide, 48 touch. While active: 200 × 40 with a tick bar (ticks every 0.5×, major ticks at 1×/2×/5× within range) **[K1]** | idle: "1.0×", fades to alpha 0.6 after 1.5 s · active (pinch or drag): alpha 1, expanded (`detent` spring) · **tap**: toggles 1× ↔ 2× (2× only if max ≥ 2) · **horizontal drag on the pill**: slider, 200 dp = full linear range (one-handed zoom) | `cd_zoom` "Zoom %1$s times" | `progressBarRangeInfo(zoomRatio, min..max)` + `setProgress` action, so TalkBack users can adjust with swipe up/down or volume keys. The fallback without **[K1]** shows the linear value as a percentage, with no detents. |
| **Focus ring** | 72 Ø, stroke 1.5 amber plus a 1 dp `halo` outside it, 4 inner ticks 6 long at 0/90/180/270° | §6.5 | not focusable; "Focused" is announced | — |
| **Look toast** | `lookToast` text on a `vfScrim` pill | visible 900 ms | — | the announcement comes from the Announcer (§5.3), not from the toast |
| **Countdown numeral** | 160; `ink`, or `amber` when ≤ 3; drawn twice (a 3 dp `halo` stroke behind the fill) | §6.3 | — | Announcer |
| **CameraStatusPanel** | §4.7 | **Opening**: shown only after the camera has stayed Opening for 1 s; small `Aperture` glyph 40 `inkMuted` with `status_opening` · **Error**: glyph 40 `signal`, `title` 18 sp, `body`, and "Try again" as an amber primary button (except Disabled) | the title is `heading()` | `liveRegion = Polite` on the title |
| **Snackbar** | M3 Snackbar, `raised`, `rChip`, `body` 14 sp, optional amber `TextButton` action | short (4 s); the error variant has a 6 dp `signal` lamp at the start | announced by M3 | — |
| **Primary button** | M3 `Button`, height 48, min width 160, `rFull`, amber / onAmber | pressed `amberPressed` · disabled `raised` / inkDisabled | label | — |
| **Text button** | M3 `TextButton`, height 48, ink | pressed `pressed` | label | — |

### 5.2 Look card anatomy
```
 ┌──── 72 ────┐
 │ ┌──64──┐   │  swatch 64×64, rSwatch, inset 4
 │ │      │   │
 │ └──────┘   │  4 gap
 │ GOLDEN  •  │  name labelEngraved, 1 line; the lamp (selected only) sits after the name
 └────────────┘  88 high; border 1dp hairline, or 2dp amber when selected; bg raised; rCard
```
- Selected: 2 dp amber border, name in amber, lamp on.
- Unselected: name in `inkMuted`.
- Compact: a 56 × 56 swatch only. The name is hidden visually and kept in semantics. When selected, the border is 2 dp amber.

### 5.3 Accessibility (TalkBack)
**Viewfinder node.** GLSurfaceView has no Compose semantics. A transparent `Box` the size of the viewfinder (`ViewfinderInput`) carries the gestures and:
- `contentDescription` = "Viewfinder, {look} look"
- `onClick(label = a11y_action_focus_center)` → `TapFocus(0.5, 0.5)`
- these `customActions`:
  - `a11y_action_zoom_in` / `a11y_action_zoom_out`: ±0.1 linear
  - `a11y_action_zoom_reset`: back to 1×
  - `a11y_action_next_look` / `a11y_action_prev_look`
  - `a11y_action_shuffle`, when shuffle is shown

**Announcer.** One state-driven node in `CameraScreen`: a **1 × 1 dp** transparent `Box` (a 0-size node may be skipped by accessibility services) with `semantics { liveRegion = LiveRegionMode.Polite; contentDescription = message }`. **Verify on the reference device with TalkBack in Phase B.** If announcements are dropped, put `liveRegion` on the visible elements instead: the look toast, the countdown numeral and the developing label. Its message changes for:
- look selected (`a11y_look_selected`)
- timer started, plus ticks 10, 5, 3, 2, 1 (10 s timer) or 3, 2, 1 (3 s timer)
- timer cancelled
- photo saved
- focused
- shuffled

Don't use `View.announceForAccessibility`. Android is deprecating it in favour of live regions and pane titles, so check its status in the API 36 reference when implementing. Repeated identical messages are made distinct with a zero-width suffix toggle.

**TalkBack and the carousel.** When touch exploration is on (`AccessibilityManager.isTouchExplorationEnabled`, observed through an `AccessibilityStateChangeListener`), **scroll-settle never selects**. Only activating a card (double-tap) selects it. Viewfinder swipes don't apply under TalkBack, so users have the custom actions instead.

**Traversal.** Each chrome group is `semantics { isTraversalGroup = true }`, and groups are ordered with `traversalIndex`:
1. ControlBar
2. Viewfinder
3. Shuffle
4. Zoom
5. LookPicker
6. Thumbnail
7. Shutter
8. Lens

The order is the same in landscape and Mini. In Mini, the look chip takes LookPicker's place and ⋯ comes after Lens.

**Screen titles.** Set `paneTitle` on PermissionGate, Licenses and the Controls sheet.

**Hardware keys** (§6.8) make the shutter reachable without finding it on screen.

### 5.4 Gestures on the viewfinder
All gestures are in one `pointerInput` on `ViewfinderInput`. Taps inside the frame margin (outside the image area) are ignored.

| gesture | action |
|---|---|
| single tap | `TapFocus(x, y)`. The coordinates are **normalized [0, 1]² in the image area** (canvas minus frame margin, via `FrameGeometry.imageRectInCanvas`) in upright display orientation **[K10, K13]**. The ViewModel maps them through the inverse preview transform (SPEC §8). |
| pinch (2 pointers) | `Zoom(f)`: linear zoom = start + (scale − 1) × 0.5, clamped to [0, 1]. Pinch cancels any swipe in progress. |
| swipe along the carousel axis (horizontal in portrait or Mini, vertical in landscape Regular/Compact) | One pointer. Triggers when travel ≥ 64 dp **and** velocity ≥ 600 dp/s, or when travel ≥ 120 dp at any speed. Moving toward start/top = next look, toward end/bottom = previous. **No wrap**: at either end, nothing changes and the toast re-shows the current name. **Ignored when the touch starts inside `WindowInsets.systemGestures`**, which belong to system back and home. Never use `systemGestureExclusion` on the viewfinder. |
| double tap | none (avoids a delay on single tap) |
| long press | none in v1 |

---

## 6. Interaction and motion

### 6.1 Shutter press (timer OFF) and what stays enabled
| t (ms) | event |
|---|---|
| 0 | finger down: the disc scales to 0.87 (`instant`), `VirtualKey` haptic |
| up | `Shutter` intent; the disc springs back (`standard`) |
| capture fires | **blink** on the transition veil (viewfinder only): `#000000` alpha 0→1 in 40 ms, hold 40 ms, 1→0 in 160 ms (`Accel`/`Standard`); `Confirm` haptic. There is no blink when screen flash is active (§6.6). |
| captureState → Processing | the shutter enters **developing** (safelight sweep); the thumbnail starts the developing pulse |
| → Saved(uri) | **print drop**: the thumbnail's new image starts at scale 1.15, alpha 0 and settles to 1, 1 (`emphasized`). The shutter returns to idle (`quick`). No snackbar on success; the Announcer says `a11y_photo_saved`. For enablement, the UI treats Saved exactly like Idle. |
| → Error(kind) | the shutter returns to idle; an error snackbar (§7.1) is shown with a `Reject` haptic, once per error instance **[K4]** |

**Enablement matrix** (✓ = enabled):

| control | Idle / Saved | Countdown | Processing | camera not Ready |
|---|---|---|---|---|
| Look, Frame, Date, Flash, Shuffle | ✓ | ✓ | ✓ if the job owns a settings snapshot (K17a); otherwise Look, Frame and Date are — | ✓ (persisted for later) |
| Timer | ✓ | — | ✓ | ✓ |
| Ratio, Lens | ✓ | — (a rebind would race the timer) | — (a rebind would abort the capture in flight) | — |
| Shutter | ✓ | ✓ (= cancel) | — (silently ignored, SPEC §8) | — |
| Thumbnail | ✓ if lastShot | ✓ | ✓ (opens the previous photo) | ✓ |

**Shutter sound.** v1 has no shutter sound of its own. When `MediaActionSound.mustPlayShutterSound()` (API 33+) returns true, the app plays the platform `MediaActionSound.SHUTTER_CLICK`, which is a system sound and not an asset. Below API 33 the app has no reliable regional signal and stays silent. The owner decides whether to make the click always-on (U7).

### 6.2 "Developing…" state
- The shutter looks as described in §5.1. The 90° arc rotates clockwise with a period of 1400 ms.
- The label `shutter_developing` ("Developing…", `labelEngraved`, `inkMuted`) fades in 4 dp below the shutter **only if Processing lasts > 600 ms**. This avoids flicker on fast phones.
- A tap on the shutter during Processing shows the label at once and does nothing else.
- The preview keeps running. Nothing blocks the full screen.

### 6.3 Timer countdown
- **Countdown(n)**: the numeral sits at the centre of the image area, in `ink` for n > 3 and `amber` for n ≤ 3, with a `halo` stroke behind it.
- **Each tick**: the new numeral enters from scale 1.4 → 1.0 and alpha 0 → 1 (`standard`). The previous numeral fades out in 180 ms. `SegmentTick` haptic.
- **Cancel**: the shutter shows the cancel square. Tapping it, pressing a hardware shutter key or using system back sends `CancelTimer`. The numeral fades out in 120 ms and the Announcer says `a11y_timer_cancelled`.
- **Announcer**: says `a11y_timer_started` at the start, then 10, 5, 3, 2, 1 (10 s timer) or 3, 2, 1 (3 s timer), not every second.
- **Reduced motion**: numerals swap with no scale.

### 6.4 Look switch (speed first)
**Single source of truth.** `state.lookId` is the source of truth, and the carousel only **reflects** it.
- When `lookId` changes from any source, the carousel calls `animateScrollToItem` to centre that card.
- Programmatic scrolls never select.

**Three ways to select:**
1. **Tap a card** → `SelectLook(id)` immediately.
2. **Drag/fling the carousel, then let it settle** → when the scroll stops, `SelectLook(centred)`. This only happens when the scroll began with a user `DragInteraction` (observed on `lazyListState.interactionSource`), touch exploration is off, and 150 ms pass after the settle. Passing over cards mid-fling does nothing.
3. **Swipe the viewfinder** (§5.4) → `SelectLook(next/prev)` immediately. The carousel follows through the programmatic scroll and doesn't re-select.

**On every change of `lookId`:**
- `SegmentTick` haptic.
- Frame and date reset to the preset defaults (SPEC §6.2), and their lamps animate (`quick`), so the reset is visible.
- The look toast fades in over 120 ms, holds 900 ms and fades out over 220 ms. A new change restarts it.
- The Announcer says `a11y_look_selected`.
- Shuffle visibility updates.

**No dip on look change.** The new LUT appears on the next rendered frame. A 33³ bake is a few ms on the GPU (SPEC §6.4), and a veil on every switch made fast browsing feel slow. If the colour "pop" proves visible on the reference device, Phase B.5 may add an 80 ms 20 % veil keyed on the renderer's `lookApplied` event **[K11]**.

### 6.5 Focus ring
1. On tap, the ring appears at the tap point at scale 1.3 and alpha 0, then settles to scale 1.0 and alpha 1 (`standard`). The ticks point inward.
2. When the focus result is success **[K2]**, the ticks grow from 6 to 9 dp (`quick`), the `ContextClick` haptic fires and the Announcer says "Focused". The ring holds 600 ms, then fades out over 300 ms.
3. On failure, the ring turns `inkMuted` and fades out over 300 ms with no haptic.
4. The ring always dismisses at 3 s (matching the auto-cancel in SPEC §8). Without **[K2]**, it settles and fades after 1 s, with no success tick and no haptic.
5. A new tap replaces the ring immediately.

### 6.6 Front screen flash
Front lens with flash on: the UI implements CameraX `ImageCapture.ScreenFlash` (CameraX 1.4+, pinned 1.5.3) in `ui/ScreenFlashOverlay.kt`. The instance is handed to `CameraController` **[K12]**.
- `apply(expirationTimeMillis, listener)`: show a full-window `flashWhite` overlay above everything (alpha 0 → 1 in 150 ms). Set `window.attributes.screenBrightness = 1f`. Call `listener.onCompleted()` when the fade ends, and always before the expiration time.
- `clear()`: fade the overlay out in 200 ms and restore the brightness to `BRIGHTNESS_OVERRIDE_NONE`.
- There is no black blink.
- If the device reports no screen-flash support **[K5]**, the flash button is hidden on the front lens (SPEC §3.1).

### 6.7 Other transitions
| trigger | motion |
|---|---|
| Camera opening (first bind, return from Licenses, resume) | the veil is at `bench` 100 % and lifts on the first frame (`standard`) **[K11]**; fallback: 350 ms after camera status becomes Ready. The status panel appears only if Opening lasts > 1 s. |
| Lens switch | the glyph rotates 180° (`emphasized`); veil as in §4.1-7 |
| Ratio / frame change | veil and resize as in §4.1-7 (a frame change resizes the canvas without a rebind) |
| Mode change (window resize, fold) | no veil; the layout snaps, and the GL surface resizes on the next frame |
| Cycle buttons | the old and new glyph or label cross-fade (`quick`); lamp alpha `quick` |
| Shuffle | the glyph rotates +180° (`standard`); `SegmentTick` haptic; no viewfinder transition (the new leak just appears) |
| Permission granted | the aperture blades open (`emphasized`), then a cross-fade to CameraScreen in 220 ms |
| Errors | the snackbar slides up 16 dp and fades in (`standard`) |

### 6.8 Hardware keys **[K15, needs integrator OK: `MainActivity`]**
`MainActivity.onKeyDown` forwards keys to the ViewModel while the Camera route is shown with permission Granted:
- `KEYCODE_CAMERA` always acts like the shutter.
- `KEYCODE_VOLUME_UP` and `KEYCODE_VOLUME_DOWN` act like the shutter **only while touch exploration is off**. Under TalkBack they keep their system role, so they can still adjust the focused zoom slider and the speech volume.
- `Shutter` fires on key-up, and `repeatCount > 0` is ignored.
- During Countdown, the same keys send `CancelTimer`.
- Otherwise the keys keep their system behaviour (volume) on Licenses, PermissionGate and when the camera is not Ready.
- A key-down shows the shutter's pressed state, so the user sees what happened.

### 6.10 Prism lens, multi-lens grid, pano look (Sprint B4)
- **More menu order**: `Double exposure`, `Half-frame`, `Multi-lens grid` (each `: On/Off`), `Prism lens: Off|Split|Tri|Halo` (each press cycles and keeps the menu open), `Flash gel: <name>`, `Open-source licenses`. The More lamp also lights while the prism is on. In the Mini controls sheet the same items are stacked text buttons.
- **Grid chip**: `Grid n/4` (same chip as 6.9, no cancel button: the burst cannot be cancelled once started). The shutter shows the normal "Developing…" busy state during the burst.
- **Locks**: grid skips ratio-forcing frames like half-frame; ratio is locked during the burst only.
- **Pano look**: swatch is the horizon card with dark top/bottom strips and sprocket holes. The viewfinder letterboxes the 2.7:1 image in portrait (frame button reads `Panorama strip`).
- **Layout**: the camera layout stays forced LTR (a camera UI is not mirrored, see 4.5); text uses start/end arrangement.

### 6.9 Capture modes (Sprint B3)
Original controls; reach the modes from **More options** (the "⋯" menu in the top bar, or the controls sheet in Mini layout). No new top-bar button.
- **More menu items** (in this order): `Double exposure: On/Off`, `Half-frame: On/Off`, `Flash gel: <name>` (each press cycles none › warm › red › blue › green and keeps the menu open), then `Open-source licenses`. Double exposure and Half-frame are mutually exclusive; choosing one disarms the other and discards any held first shot. The More lamp is lit while a mode or a gel is active.
- **Progress chip** (top-start corner of the viewfinder slot, `raised` at 88 %, `labelValue`): `Double exposure 0/2` or `Half-frame 0/2` once armed; `… 1/2` after shot 1 is held, with a 44 dp `×` button (`Discard first shot`). Polite live region. The chip is the only new chrome.
- **Ghost** (double exposure, shot 1 held): shot 1 shows at 50 % over the live preview (GL, Pass 4). Half-frame shows no ghost.
- **Shutter** during shot-1 development shows the normal "Developing…" busy state; shot 2 is accepted only after shot 1 is stored. The thumbnail only changes after shot 2 saves.
- **Locks**: while Half-frame is armed the ratio button is disabled (4:3 forced) and ratio-forcing frames are skipped; while a double-exposure first shot is held, ratio and frame are disabled.
- **Flash gel dot**: an 8 dp dot in the gel colour at the top-end of the flash icon when the flash is on (back: On/Auto; front: screen flash) and a gel is selected. State text reads `On, Red gel`. The front screen-flash overlay is tinted 70 % toward the gel colour.
- **Drop notice**: when a held first shot is lost (app backgrounded, memory trim, out of memory): snackbar and TalkBack announcement `First shot discarded.`; the chip returns to `0/2`.
- Haptics and motion: no new haptics; the existing `Confirm` on save applies to shot 2 only.

---

## 7. Copy and string keys

### 7.1 Permission, status and error copy
| state | title | body | primary | secondary |
|---|---|---|---|---|
| Rationale (Denied) | **Let the light in** | Camera access is needed to frame and take photos. Photos stay on your phone. | Allow camera | Open-source licenses (TextButton, footer) |
| Permanently denied | **Camera access is off** | Turn on Camera for this app in Settings to start shooting. | Open settings | Open-source licenses |
| Opening (> 1 s) | — | Opening camera… | — | — |
| Camera in use (IN_USE, MAX_CAMERAS_IN_USE) | **Camera in use** | Another app is using the camera. It reconnects when it's free. | Try again | — |
| Camera disabled by policy (`ERROR_CAMERA_DISABLED`) | **Camera disabled** | Your device policy blocks the camera. | — | — |
| Camera fatal / other bind failure / recoverable error lasting > 3 s | **Camera stopped** | Something went wrong. Try again. | Try again | — |
| Thumbnail empty | — (cd only) | No photos yet | — | — |

**Permission screen layout.** The content is a vertical stack centred in `safe`:
- the Aperture illustration (96, `inkMuted` blades on a `raised` disc of 120)
- 24 gap, title
- 8 gap, body (max width 320, centred)
- 32 gap, primary button
- the secondary button pinned to the bottom with a 16 inset

The illustration is decorative (`contentDescription = null`). In landscape with `safe.height < 480`, the illustration is hidden and the stack scrolls (`verticalScroll`).

**Snackbars** (short, no title). The source **[K4]** is the `CaptureErrorKind`, a UI-local event, or render status **[K7]**.

| event | message | action |
|---|---|---|
| `SaveFailed` | Couldn't save photo. | — |
| `StorageFull` | Storage full. Photo not saved. | — |
| `DevelopFailed` (including after the OOM retry) | Couldn't develop photo. Try again. | — |
| UI: no viewer app (`ActivityNotFoundException`) | No app can open photos. | — |
| UI: the last photo was deleted externally (the open or thumbnail load fails) | Photo no longer available. | — (also sends `LastShotUnavailable` **[K9]**) |
| render passthrough (release shader failure; once per process) | Looks unavailable on this device. | — |

### 7.2 Licenses screen
- M3 `TopAppBar` (`deck`) with the Back glyph and the title "Open-source licenses". The content uses `WindowInsets.safeDrawing` padding.
- A `LazyColumn` of cards (`raised`, `rCard`, 16 padding, 8 gap). Each card shows the name (`title` 16 sp), the licence name (`inkMuted`) and an expand chevron. Expanded, it shows the full text from `assets/licenses/*.txt` in `licenseText`, inside a `SelectionContainer`.
- Entries:
  - t3mujinpack, MIT
  - IBM Plex Mono, SIL OFL 1.1
  - AndroidX / Jetpack libraries, Apache 2.0 (one combined entry with a short notice)
- Footer: `licenses_version` "Version %1$s".

### 7.3 String keys (all new; English; `res/values/strings.xml`)
Look display names are **approved as temporary working names**. They are string-only, have no trademark references and can be renamed without code changes.

| key | English |
|---|---|
| `look_classic35` | Golden |
| `look_disposable` | Pocket |
| `look_instant` | Instant |
| `look_ccd` | Digi |
| `look_bw400` | Mono |
| `look_fisheye` | Bubble |
| `cd_look_suffix` | %1$s look |
| `cd_viewfinder` | Viewfinder, %1$s look |
| `cd_look_chip` | Look: %1$s. Show looks and controls |
| `cd_controls_sheet` | Camera controls |
| `cd_flash` | Flash |
| `state_flash_off` | Off |
| `state_flash_auto` | Auto |
| `state_flash_on` | On |
| `state_flash_screen` | Screen flash |
| `cd_timer` | Self-timer |
| `state_timer_off` | Off |
| `state_timer_3` | 3 seconds |
| `state_timer_10` | 10 seconds |
| `label_timer_3` | 3s |
| `label_timer_10` | 10s |
| `cd_ratio` | Aspect ratio |
| `state_ratio_4_3` | 4 by 3 |
| `state_ratio_1_1` | Square |
| `state_ratio_16_9` | 16 by 9 |
| `label_ratio_4_3` | 4:3 |
| `label_ratio_1_1` | 1:1 |
| `label_ratio_16_9` | 16:9 |
| `cd_frame` | Frame |
| `state_frame_none` | None |
| `state_frame_instant` | Instant border |
| `state_frame_strip` | Film strip |
| `cd_date_stamp` | Date stamp |
| `cd_more` | More options |
| `menu_licenses` | Open-source licenses |
| `cd_shuffle` | Shuffle light leak and dust |
| `a11y_shuffled` | Shuffled |
| `cd_shutter` | Take photo |
| `cd_shutter_cancel` | Cancel timer |
| `cd_shutter_busy` | Developing photo |
| `shutter_developing` | Developing… |
| `cd_lens_switch` | Switch camera |
| `state_lens_back` | Back camera |
| `state_lens_front` | Front camera |
| `cd_thumb` | Open last photo |
| `cd_thumb_empty` | No photos yet |
| `cd_zoom` | Zoom %1$s times |
| `label_zoom` | %1$s× |
| `a11y_action_focus_center` | Focus center |
| `a11y_action_zoom_in` | Zoom in |
| `a11y_action_zoom_out` | Zoom out |
| `a11y_action_zoom_reset` | Reset zoom |
| `a11y_action_next_look` | Next look |
| `a11y_action_prev_look` | Previous look |
| `a11y_action_shuffle` | Shuffle light leak and dust |
| `a11y_look_selected` | %1$s look |
| `a11y_photo_saved` | Photo saved |
| `a11y_timer_started` | Timer, %1$d seconds |
| `a11y_timer_cancelled` | Timer cancelled |
| `a11y_focus_locked` | Focused |
| `status_opening` | Opening camera… |
| `perm_title` | Let the light in |
| `perm_body` | Camera access is needed to frame and take photos. Photos stay on your phone. |
| `perm_allow` | Allow camera |
| `perm_denied_title` | Camera access is off |
| `perm_denied_body` | Turn on Camera for this app in Settings to start shooting. |
| `perm_open_settings` | Open settings |
| `err_camera_in_use_title` | Camera in use |
| `err_camera_in_use_body` | Another app is using the camera. It reconnects when it\'s free. |
| `err_camera_disabled_title` | Camera disabled |
| `err_camera_disabled_body` | Your device policy blocks the camera. |
| `err_camera_generic_title` | Camera stopped |
| `err_camera_generic_body` | Something went wrong. Try again. |
| `action_retry` | Try again |
| `err_save_failed` | Couldn\'t save photo. |
| `err_storage_full` | Storage full. Photo not saved. |
| `err_develop_failed` | Couldn\'t develop photo. Try again. |
| `err_no_viewer` | No app can open photos. |
| `err_photo_missing` | Photo no longer available. |
| `err_looks_unavailable` | Looks unavailable on this device. |
| `licenses_title` | Open-source licenses |
| `licenses_version` | Version %1$s |
| `cd_back` | Back |
| `cd_expand` | Show license text |
| `cd_collapse` | Hide license text |


**Sprint B3 strings (capture modes)**

| key | English |
|---|---|
| `menu_double_exposure` | Double exposure |
| `menu_half_frame` | Half-frame |
| `menu_toggle_state` | %1$s: %2$s |
| `menu_flash_gel` | Flash gel: %1$s |
| `toggle_on` / `toggle_off` | On / Off (renamed from `state_on`/`state_off`, which collide with androidx names and showed the system French "Désactivé") |
| `state_flash_with_gel` | %1$s, %2$s gel |
| `gel_none` / `gel_warm` / `gel_red` / `gel_blue` / `gel_green` | No gel / Warm / Red / Blue / Green |
| `multi_chip_double` | Double exposure %1$d/2 |
| `multi_chip_half` | Half-frame %1$d/2 |
| `cd_multi_cancel` | Discard first shot |
| `a11y_double_on` | Double exposure on. Take the first shot. |
| `a11y_half_on` | Half-frame on. Take the first shot. |
| `a11y_first_kept` | First shot kept. Take the second. |
| `msg_first_dropped` | First shot discarded. |

**Sprint B4 strings**

All user-visible text lives in `res/values/strings.xml` with a complete `res/values-fr/strings.xml` (checked by `StringResourcesTest`; look names are identical in both). Languages: English and French only, following the system language; `res/xml/locales_config.xml` lists `en`, `fr` (per-app language in Android settings, Android 13+); no in-app picker.

| key | English |
|---|---|
| `look_pano` | Wide 35 |
| `state_frame_pano` | Panorama strip |
| `menu_grid` | Multi-lens grid |
| `menu_prism` | Prism lens: %1$s |
| `prism_off` / `prism_split` / `prism_tri` / `prism_halo` | Off / Split / Tri / Halo |
| `multi_chip_grid` | Grid %1$d/4 |
| `a11y_grid_on` | Grid on. One press takes four photos. |
| `glyph_close` | × (not translatable) |

---

## 8. Look swatch art direction (no external assets)

**Decision:** swatches are procedural Compose drawings of one shared abstract **"horizon card"** scene. Each look paints that scene with its own colours and effect cues.
- They are rendered once per density into an `ImageBitmap` (64 dp, or 56 / 48 / 32 dp for Compact, landscape and the look chip) using `CanvasDrawScope`. They are cached in a `remember` keyed by look id and size.
- File: `ui/LookSwatches.kt`.
- **Rejected:** sampling the live preview. It would need 6 extra GL renders per frame, which breaks the SPEC §5.3 budget, and dark or busy scenes make the swatches unreadable and unstable.
- **Optional v1.x upgrade [needs integrator OK: a thumbnail API on `RenderGraph`]:** render the same horizon card as a 128 px bitmap through the real graph, so the swatch is exact.

**Horizon card geometry** (unit square, origin top-left):
- sky: a vertical gradient from y = 0 to 0.62
- ground: a band from 0.62 to 1
- sun: a disc at (0.68, 0.34), r 0.13
- one foreground shape: a rounded pill at (0.18–0.40, 0.70–0.84), r 0.07 (reads as "a subject")

**Shared effect primitives:**
- **Vignette:** `Brush.radialGradient(transparent → #000000 @ amount)`, centre (0.5, 0.5), radius 0.75.
- **Grain:** one 64 × 64 px noise `ImageBitmap` generated at startup with `Random(seed = 0x5EED)`. Each pixel is grey 128 ± 64. It is drawn with `ImageShader(TileMode.Repeated)` and `BlendMode.Overlay` at the look's alpha. For "coarse" grain, scale the shader by 2×.
- **Glow:** `radialGradient(color @ a → transparent)`.
- **Fade:** draw a `#FFFFFF` rect at 6–14 % with `BlendMode.Screen`.
- **Mono:** apply `ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })` to the scene layer before effects.

| look | sky top → bottom | sun | ground | subject | effects |
|---|---|---|---|---|---|
| `classic35` Golden | `#F6C88E` → `#E8895A` | `#FFF1D0` | `#5B4A2E` | `#3A2C1C` | vignette 0.25, grain 0.10 fine |
| `disposable` Pocket | `#2A2B33` → `#3B3A40` (night) | none | `#1C1B1E` | `#F2D3B3` with a hot centre glow `#FFF4E0` @ 0.85, r 0.35 at (0.3, 0.75) | vignette 0.55, grain 0.22 coarse, glow (flash-hot) |
| `instant` Instant | `#E9D8E4` → `#F4E6CF` | `#FFFDF5` | `#B9C3A9` | `#8E8A9A` | fade 14 %, grain 0.06. The scene is inset into a `paper` `#F4F1EA` border (sides/top 6 %, bottom 22 %, matching SPEC §7.3) |
| `ccd` Digi | `#A3C8EA` → `#E2EEF7` (cool) | `#FFFFFF` + bloom glow `#FFFFFF` @ 0.6, r 0.3 | `#4E6D73` | `#2D3E44` | a tiny 7-segment stamp: 3 amber `#FF8A1F` bars (each 0.06 × 0.012) at the bottom-right, inset 0.06; grain 0.05 with chroma (tint the noise blue at 30 %) |
| `bw400` Mono | `#DADADA` → `#7C7C7C` | `#FFFFFF` | `#2B2B2B` | `#111111` | mono filter, vignette 0.35, grain 0.20 coarse |
| `fisheye` Bubble | `#F2B47C` → `#7FB4C9` | `#FFF6E2` | `#3C4A3A`, horizon drawn as an **arc** (quadratic, control y 0.50) | none | circular mask r 0.48 on a `bench` `#0E0D0C` field, plus a 1 dp `#FFFFFF` rim highlight @ 20 % |
| `slide` Chrome | `#2F6FD0` -> `#8FC4F0` | `#FFF3C0` | `#1F5A2A` | `#B5231B` | vignette 0.15, grain 0.05, inside a `slide_mount` card (`#EDE9DF`, border 0.12, window radius 0.03) |
| `premium` Pastel | `#F3DCCB` -> `#F8EBDD` | `#FFFAF0` | `#BFB59E` | `#D9A58B` | fade 5 %, vignette 0.10, grain 0.08 fine |
| `toy120` Toy | `#E8A04A` -> `#4FA3B8` | `#FFE9A8` | `#2E5A3A` | `#D2432F` | vignette 0.7 (circle-ish), centre glow 0.35, grain 0.14, `film120` black border 0.05 with four grey ticks |
| `tlr` Twin | `#2B7AC0` -> `#A9D6EE` | `#FFF4D8` | `#3B6B2E` | `#C4381F` | vignette 0.2, grain 0.08, `film120` border with ticks |
| `square` Square | `#D9B98C` -> `#EBD3B0` | `#FFF0C8` | `#8A8F70` | `#5C6A74` | fade 20 %, grain 0.06, inset in a `paper` border (sides/top 7 %, bottom 24 %, matching `instant_square`) |
| `fine_mono` Mono 100 | `#E6E6E6` -> `#8A8A8A` | `#FFFFFF` | `#1F1F1F` | `#0A0A0A` | mono filter, vignette 0.15, grain 0.06 fine (higher midtone contrast than `bw400`) |

Rules:
- Swatches never show leaks or dust, so they stay stable.
- Each swatch must stay distinguishable in greyscale. Golden, Instant and Digi have distinct luminance profiles, and Mono is the only fully grey one.
- Because the swatches carry most of the look identity, the selected state also uses the border, the amber name and the lamp, never colour alone (P2).
- A new look must get its own row in this table.

---

## 9. App icon direction (adaptive icon, vector only)

All concepts:
- 108 dp canvas, key art inside the 66 dp safe circle
- `<monochrome>` layer for themed icons (API 33+)
- foreground and background as `VectorDrawable`
- no text, no brand or film-maker references, no camera-body silhouette

| # | concept | description | monochrome layer |
|---|---|---|---|
| 1 | **Lamp in the dark** (recommended) | `bench` background. One large ring lens (outer 52 dp, stroke 6, `ink`) with a 20 dp amber `#FF8A1F` disc offset to the upper right inside it, like a lit indicator or a low sun seen through glass. A thin safelight-red crescent (`#C2361F`) on the lower-left inner edge suggests a light leak. Matches the UI's single-lamp story. | ring + disc as one shape |
| 2 | **Leak band** | `paper` `#F4F1EA` background. A soft diagonal band from top-left to bottom-right fades amber → safelight red → transparent and crosses a solid `#171614` circle (40 dp). It reads as "light leak on a print". | circle with a diagonal cut-out band |
| 3 | **Sprocket print** | `deck` background. A rounded rectangle (48 × 40, r 6) filled with an amber-to-cream vertical gradient. Above and below it are 3 small rounded sprocket holes in `ink`, and a tiny 7-segment "8" in amber at the bottom-right. Evokes film plus a date stamp without any specific brand. | rect + holes |

Deliverable for Phase C (Mobile Release Engineer): the chosen concept as `ic_launcher_foreground.xml`, `ic_launcher_background.xml` and `ic_launcher_monochrome.xml`, plus a 512 px Play icon exported from the same vectors.

---

## 10. Compose implementation map

### 10.1 Principles for the B-UI implementer
- **State hoisting.** `CameraScreen(state: CameraUiState, onIntent: (CameraIntent) -> Unit, previewHost: PreviewHost, onOpenLicenses: () -> Unit)` is stateless, and every child receives only the fields it reads. UI-local state lives in `remember` inside the owning composable: toast visibility, veil alpha, the focus-ring animation, the sheet's open state, `autoRequested`. That state is never sent to the ViewModel.
- **ViewModel access.** `MainActivity` obtains `CameraViewModel` with `by viewModels()` (activity-ktx, which comes with activity-compose) and passes `vm.uiState` and `vm::onIntent` down. Using `viewModel()` would need `lifecycle-viewmodel-compose`, a new dependency.
  - Collect with `collectAsStateWithLifecycle()` from `lifecycle-runtime-compose`. It arrives transitively through activity-compose 1.11, but declare it explicitly in the version catalog **[needs integrator OK]**. The fallback is `collectAsState()`.
- **Recomposition.** Zoom changes at the pointer rate. Pass `zoomRatio` as a lambda (`zoomRatio: () -> Float`) to `ZoomPill` only, so a pinch doesn't recompose the deck.
  - Strong skipping (Kotlin 2.2 default) covers the rest.
  - Mark the state classes `@Immutable`. Lists in state are read-only and replaced, never mutated.
- **Intent type.** `sealed interface CameraIntent` with exactly the SPEC §6.2 intents plus the requested ones in §10.4.

### 10.2 Composable tree (CameraScreen)
```
MainActivity                                   (outside ui/: enableEdgeToEdge, viewModels(), onKeyDown → K15)
└─ VlTheme                                     ui/theme/Theme.kt
   └─ AppRoot(state, onIntent, previewHost)    ui/AppRoot.kt — rememberSaveable Screen enum
      ├─ Screen.Camera
      │   ├─ PermissionGate                    ui/PermissionScreen.kt  (permission ≠ Granted)
      │   └─ CameraScreen                      ui/CameraScreen.kt
      │       └─ Box(fillMaxSize, bench)
      │           ├─ CameraLayout(mode)        ui/CameraLayout.kt — safeDrawing padding, BoxWithConstraints, LTR, chooseMode()
      │           │   ├─ ControlBar(axis)      ui/Controls.kt — absent in Mini
      │           │   │   ├─ FlashButton
      │           │   │   ├─ TimerButton
      │           │   │   ├─ RatioButton
      │           │   │   ├─ FrameButton
      │           │   │   ├─ DateStampButton
      │           │   │   └─ MoreMenu (DropdownMenu)
      │           │   ├─ ViewfinderSlot        ui/Viewfinder.kt — computes the contain rect from canvasAspect
      │           │   │   ├─ Viewfinder        AndroidView(factory = previewHost::createView) (GLSurfaceView from gl/, K11)
      │           │   │   ├─ ViewfinderInput   gestures + semantics node (§5.3, §5.4)
      │           │   │   ├─ FocusRing
      │           │   │   ├─ ShuffleButton
      │           │   │   ├─ ZoomPill
      │           │   │   ├─ LookToast
      │           │   │   ├─ CountdownNumeral
      │           │   │   ├─ TransitionVeil    dip + blink
      │           │   │   └─ CameraStatusPanel Opening / Error
      │           │   ├─ LookPicker(axis, compact)   ui/LookPicker.kt — absent in Mini
      │           │   │   └─ LookCard × n      uses LookSwatches.kt
      │           │   └─ Deck(axis) | MiniBar(axis)
      │           │       ├─ LastShotThumbnail            (Mini: in ControlsSheet)
      │           │       ├─ ShutterButton + DevelopingLabel
      │           │       ├─ LensSwitchButton
      │           │       └─ (Mini) LookChip, MoreButton → ControlsSheet (ModalBottomSheet)
      │           ├─ SnackbarHost
      │           ├─ Announcer                 0-size live-region node
      │           └─ ScreenFlashOverlay        ui/ScreenFlashOverlay.kt — implements ImageCapture.ScreenFlash (K12)
      └─ Screen.Licenses
          └─ LicensesScreen(onBack)            ui/LicensesScreen.kt — PredictiveBackHandler
```

### 10.3 What each composable reads and sends
"SPEC" means the field or intent is in SPEC §6.2. **K#** means it is requested in §10.4 (use the fallback until it lands).

| composable | reads (`CameraUiState`) | sends (intents) | UI-local |
|---|---|---|---|
| AppRoot | `permission` (SPEC) | — | `screen` (rememberSaveable enum) |
| PermissionGate | `permission` (SPEC) | `PermissionResult(granted, canAskAgain)`, `RefreshPermission(granted)` (K8) | launcher, `autoRequested` |
| CameraLayout | `ratio`, `frame` (SPEC); `imagePortrait` (K14) | — | `mode`, contain rect |
| FlashButton | `flash`, `lens` (SPEC); `caps.hasFlashUnit`, `caps.screenFlash` (K5) | `CycleFlash` (SPEC) | — |
| TimerButton | `timer`, `captureState` (SPEC) | `SetTimer(next)` (SPEC) | — |
| RatioButton | `ratio`, `captureState` (SPEC); `camera` (K3) | `SetRatio(next)` (SPEC) | — |
| FrameButton | `frame` (SPEC) | `SetFrame(next)` (SPEC) | — |
| DateStampButton | `dateStamp` (SPEC) | `ToggleDateStamp` (SPEC) | — |
| MoreMenu | — | — (calls `onOpenLicenses`) | `expanded` |
| Viewfinder | — (the renderer reads the look/seed from the ViewModel through `gl/`) | — | `previewHost` events (K11) |
| ViewfinderInput | `lookId`, `zoom` (SPEC); `looks` (K6) | `TapFocus(nx, ny)` (SPEC, convention K10), `Zoom(f)`, `SelectLook(id)` (SPEC) | gesture tracking |
| FocusRing | `focus` (K2) | — | last tap point, animation |
| ShuffleButton | `lookId`, `captureState` (SPEC); `looks[].hasRandomFx` (K6) | `Shuffle` (SPEC) | — |
| ZoomPill | `zoom` (SPEC); `zoomRatio`, `zoomRange` (K1) | `Zoom(f)` (SPEC) | expanded, idle fade |
| LookToast | `lookId` (SPEC), `looks` (K6) | — | visible timer |
| CountdownNumeral | `captureState.Countdown(n)` (SPEC) | — | — |
| TransitionVeil | `lens`, `ratio`, `frame`, `captureState` (SPEC); `camera` (K3) | — | veil alpha; first-frame events (K11) |
| CameraStatusPanel | `camera` (K3) | `RetryCamera` (K3) | Opening > 1 s timer |
| LookPicker / LookCard | `lookId` (SPEC), `looks` (K6) | `SelectLook(id)` (SPEC) | `LazyListState`, drag tracking, touch-exploration flag |
| LastShotThumbnail | `lastShot`, `captureState` (SPEC) | `LastShotUnavailable` (K9) | decoded bitmap; launches `ACTION_VIEW` |
| ShutterButton | `captureState` (SPEC); `camera` (K3) | `Shutter`, `CancelTimer` (SPEC) | pressed state |
| DevelopingLabel | `captureState` (SPEC) | — | > 600 ms timer |
| LensSwitchButton | `lens`, `captureState` (SPEC); `caps.hasFront/hasBack` (K5) | `ToggleLens` (SPEC) | rotation animation |
| LookChip, ControlsSheet | everything the bar, picker and thumbnail read | the same intents | sheet state |
| SnackbarHost | `captureState.Error(kind, seq)` (K4); `renderMode` (K7) | — | `SnackbarHostState`; shows each `seq` once |
| Announcer | `lookId`, `lookSeed` (shuffled), `timer`, `captureState` (SPEC); `looks` (K6); `focus` (K2) | — | message |
| ScreenFlashOverlay | `lens`, `flash` (SPEC) | — (callbacks from CameraX via K12) | overlay alpha, window brightness |
| LicensesScreen | — | — (calls `onBack`) | expanded cards |

### 10.4 Requested contract additions (for the integrator, before the Phase A freeze)
All are **additive with defaults**, so Phase A code compiles unchanged. B-UI ships a fallback where the last column gives one. K8, K10 and K16 are **required**.

| ID | request | why | B-UI fallback |
|---|---|---|---|
| K1 | `zoomRatio: Float = 1f`, `zoomRange: ClosedFloatingPointRange<Float> = 1f..1f` (from CameraX `ZoomState`) | the "1.0×" label, detents, 1× ↔ 2× tap and slider semantics. The linear value doesn't map to 1× when the minimum ratio is < 1 (logical multi-cameras). The UI sends ratio targets as linear `Zoom(f)` with CameraX's mapping (field of view is linear in 1/ratio): `f = (1/r − 1/min) / (1/max − 1/min)`, inverse `r = 1 / (1/min + f · (1/max − 1/min))`. Put this in a pure `ZoomMath.kt` with unit tests. Verify it at Phase B start against `ZoomState.linearZoom` on the device. | pill shows linear %; tap resets to linear 0; no detents |
| K2 | `focus: FocusUi? = null` = (nx, ny, phase Focusing / Locked / Failed, seq) | ring success/failure, haptic, "Focused" announcement | ring settles and fades after 1 s |
| K3 | `camera: CameraStatus = Opening`, with `Opening \| Ready \| Error(kind: InUse \| Disabled \| Fatal)`; intent `RetryCamera` | status panel copy, shutter availability, auto-recover after multi-window loss | treat as Ready; no panel |
| K4 | `CaptureState.Error(kind: CaptureErrorKind, seq: Long)` with `SaveFailed \| StorageFull \| DevelopFailed`, replacing `Error(msg: String)` | localized snackbar keys; show once across recompositions | map any `Error` to `err_develop_failed` |
| K5 | `caps: CameraCaps` = hasBack, hasFront, hasFlashUnit (current lens), screenFlash | hide Flash and Lens correctly | show both; flash shows `FlashOff` only on the front lens |
| K6 | `looks: List<LookUi>` = id, `displayNameRes`, `hasRandomFx` (leak probability > 0 or dust > 0), in presets.json order | carousel content, names, Shuffle visibility, `collectionInfo` | UI reads `PresetRepository` directly **[needs integrator OK]** |
| K7 | `renderMode: Normal \| Passthrough` | "Looks unavailable" snackbar | none |
| K8 | intents `PermissionResult(granted: Boolean, canAskAgain: Boolean)` and `RefreshPermission(granted: Boolean)` | §3.3; the ViewModel can't see the launcher result otherwise | **required** |
| K9 | intent `LastShotUnavailable`; on cold start the ViewModel seeds `lastShot` with the newest own row in `Pictures/<SAVE_FOLDER>` (our own rows need no permission on API 29+) | the thumbnail is otherwise empty after every launch | thumbnail empty after launch |
| K10 | `TapFocus(x, y)` convention: normalized [0, 1]² in the **image area**, upright display orientation | frame margins are visible in the viewfinder; resolution-independent | **required** (convention only) |
| K11 | `PreviewHost` from `gl/` = `fun createView(context): GLSurfaceView` (called in the `AndroidView` factory, per SPEC §6.3; never a held View, because the view is disposed on leaving the Camera route) + `events: Flow<PreviewEvent>` (`FirstFrame`, `LookApplied`) | lift veils on real frames instead of timers | fixed timeouts (§4.1-7, §6.7); the factory still calls the Phase A renderer setup |
| K12 | `CameraController.setScreenFlash(ImageCapture.ScreenFlash)`, plus `FLASH_MODE_SCREEN` on the front lens when flash ≠ OFF | the UI owns the window overlay and brightness | hide flash on the front lens |
| K13 | `FrameGeometry.imageRectInCanvas(ratio, frame, imagePortrait): RectF` (normalized), next to the approved `canvasAspect(ratio, frame, imagePortrait): Float` | tap mapping, focus-ring clamping, countdown centring | treat the whole canvas as the image |
| K14 | `imagePortrait: Boolean`, from the same display rotation used for the ViewPort | the UI and the camera crop must agree in split-screen | formula in §3.5 |
| K15 | `MainActivity.onKeyDown` / `onKeyUp` forwarding of volume and camera keys (§6.8) | one-handed and accessible shutter | none |
| K16 | `CameraController`: `DisplayListener` updates `targetRotation` on 180° flips (**required**). **Open question:** under rotation lock, should `ImageCapture.targetRotation` follow `OrientationEventListener`? | the 180° case is needed for upright saves. The rotation-lock case conflicts with parity: the preview canvas stays portrait while the saved canvas would be landscape, so the instant border and date stamp would land on a different edge than previewed (SPEC §5). | 180°: **required**. Rotation lock: v1 default = follow the display (WYSIWYG); integrator decides. |
| K17 | Confirm that (a) `CapturePipeline.process` uses a settings snapshot, so a look change during Processing can't touch the job, and (b) Processing spans the whole job. Note that SPEC §5.3 "shutter-to-next-shot ≤ 0.5 s" conflicts with §6.3 "1 job in flight", because a 2.5 s develop locks the shutter. | §6.1 enablement matrix | the matrix assumes (a) and (b) |

### 10.5 `ui/theme/` token files
| file | contents |
|---|---|
| `Color.kt` | `object VlColors { val Bench = Color(0xFF0E0D0C); val Deck…; val VfScrim = Color(0xB3000000); … }` (every §2.1 token, nothing else). `internal val VlColorScheme = darkColorScheme(primary = VlColors.Amber, …)` per the §2.1 mapping, with `surfaceTint = Color.Transparent`. |
| `Type.kt` | `val PlexMono = FontFamily(Font(R.font.plex_mono_medium, FontWeight.Medium), Font(R.font.plex_mono_semibold, FontWeight.SemiBold))`. `val VlTypography = Typography(titleLarge = …, bodyLarge = …, labelLarge = …)` for the M3 roles. `object VlType { val labelEngraved; val lookToast; val licenseText }`, plus `@Composable fun VlType.labelValue()`, `zoom()` and `countdown()` that apply the §2.2 caps and fixed sizes through `LocalDensity` and `dp.toSp()`. |
| `Shape.kt` | `object VlRadius { Viewfinder = 0.dp; Chip = 4.dp; Swatch = 6.dp; Card = 8.dp; Sheet = 16.dp }`. `val VlShapes = Shapes(extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(8.dp), large = RoundedCornerShape(16.dp))`. |
| `Motion.kt` | `object VlMotion { Instant = 70; Quick = 120; Standard = 220; Emphasized = 360; DevelopLoop = 1400 }`; easings `Standard`, `EmphasizedDecel`, `Accel`; helpers `fun <T> quick(): TweenSpec<T>` etc.; `val Detent = spring<Float>(0.75f, 800f)`. `val LocalReducedMotion = staticCompositionLocalOf { false }`, and `@Composable fun rememberReducedMotion()` that re-reads `ANIMATOR_DURATION_SCALE` on `ON_RESUME`. Every helper returns `snap()` or a ≤ 120 ms fade when reduced motion is on. |
| `Dimens.kt` (added) | the spacing scale (§2.3); component sizes (§4.7, §5.1); `data class ChromeMetrics(bar, carousel, deck, shutter, cardW, cardH)` with `Regular`, `Compact` and `Mini` instances used by `chooseMode()`. |
| `Theme.kt` (added) | `@Composable fun VlTheme(content)` = `CompositionLocalProvider(LocalReducedMotion provides rememberReducedMotion()) { MaterialTheme(VlColorScheme, VlTypography, VlShapes, content) }`. It is always dark and never reads `isSystemInDarkTheme()`. |

Tests in B-UI (JVM, no device):
- `ChooseModeTest` covers the §4.3 table.
- `FlashCycleTest` covers the back and front sequences in §5.1.
- `EnablementTest` covers the §6.1 matrix.
- `SwipeDirectionTest` covers the axis, no-wrap and system-gesture start rules.

---

## 11. Open items for the owner / integrator
| ID | item | status |
|---|---|---|
| U1 | Look display names | **Closed**: approved as temporary (Golden, Pocket, Instant, Digi, Mono, Bubble) |
| U2 | Bundle IBM Plex Mono | **Closed**: approved |
| U3 | `configChanges` on `MainActivity` | **Closed** for the approved list. **Open**: proposed addition of `density\|uiMode` (§3.5). |
| U4 | `FrameGeometry.canvasAspect(...)` in the contract | **Closed**: approved. `imageRectInCanvas` is requested as K13. |
| U5 | Optional RenderGraph thumbnail API for exact swatches (§8) | open, v1.x |
| U6 | Pick an app icon concept (§9) | open, Phase C |
| U7 | Shutter sound: region-forced only (default), or always on | open, owner |
| U8 | Contract requests K1–K17 (§10.4), especially K3, K4, K8 and K17, before the Phase A freeze | open, integrator |
| U9 | Declare `lifecycle-runtime-compose` explicitly (already transitive) | open, integrator |

## 12. v1.1 screens: Import editor, Gallery, Viewer (original design)

Same tokens as the camera (section 2): Bench background, Deck bars, Raised cards, amber for the one primary action, Plex Mono labels.
No navigation library: `enum class Screen { Camera, Licenses, Gallery, Editor }` (section 3.2). The Viewer is a state of the Gallery route.

### 12.1 Routes
```
Camera ⋯ menu ─► Import photo ─► system Photo Picker ─► Editor ─ Save ─► Gallery (viewer on the new photo)
Camera ⋯ menu ─► Gallery ─► grid ─► Viewer ─► Share | Re-develop ─► Editor ─ Save ─► Gallery (viewer on the new photo) | Delete
Camera last-shot thumbnail ─► Gallery, viewer opened on that photo
Gallery top bar "Import photo" ─► Picker ─► Editor (back returns to Gallery)
Back: Viewer ─► grid ─► Camera; Editor ─► where it came from (disabled while saving)
```
Entering Gallery or Editor leaves the Camera route, so the camera unbinds (as for Licenses). Returning to the camera refreshes the last-shot thumbnail.

### 12.2 Editor (import and re-develop)
```
┌──────────────────────────────┐
│ ←  Develop / Re-develop  Save│  top bar (Deck); Save = amber text, disabled until Ready/while saving
├──────────────────────────────┤
│                              │
│        live preview          │  Fit, same render graph as the camera; spinner + label while Loading / Saving
│                              │
├──────────────────────────────┤
│  [frame] [stamp] [prism] [⚄] │  4 x 48 dp ControlButton (frame cycle, date stamp, prism cycle, shuffle)
│  Exposure     ───●───  +0.0 EV│  -1..+1 EV, step 0.05
│  Temperature  ───●───  +0     │  -0.5..+0.5 (shown -50..+50), step 0.05
│  [ Reset adjustments ]        │  only when a slider is not at 0
│  look carousel (LookPicker)   │  96 dp, same component as the camera
└──────────────────────────────┘
```
Landscape: preview left, a 340 dp scrollable control column right. States: Loading (spinner "Opening photo..."), Ready, Saving (spinner "Developing...",
all controls disabled, back disabled), Failed (message + Back: open failed / negative gone), transient snackbar for preview or save errors.
Accessibility: sliders carry label + value as state description; preview has the content description "Preview, <look> look"; every button has a content description.

### 12.3 Gallery grid
Top bar: back, "Gallery", text action "Import photo" (amber). Body: `LazyVerticalGrid`, adaptive 110 dp cells, 2 dp gaps, square cropped thumbnails on Raised.
States: Loading (spinner), Empty, Grid. **Empty** (also right after a reinstall): title "No photos here yet", body explaining that photos from before a reinstall stay in
the Photos app, and the text button **Open in Photos**; if no gallery app exists a toast says so.

### 12.4 Viewer
Full-screen pager on Bench, swipe between photos, top scrim row with back arrow and "n of m", bottom scrim row with three text actions:
**Share** (ink), **Re-develop** (amber, shown only when a negative exists), **Delete** (Signal red). Delete asks the system on Android 11+ (the system dialog is the confirmation);
a snackbar confirms "Photo deleted" or reports a failure. After the last photo is deleted the grid shows the empty state.

### 12.5 Camera changes
The thumbnail opens the Viewer. The ⋯ menu (and the Mini controls sheet) gains: Import photo, Gallery, "Keep negatives: On/Off" (default On), above Open-source licenses.

### 12.6 String keys (v1.1; EN in `values/strings.xml`, FR in `values-fr/strings.xml`; `StringResourcesTest` enforces completeness)
| key | EN | FR |
|---|---|---|
| `menu_import` | Import photo | Importer une photo |
| `menu_gallery` | Gallery | Galerie |
| `menu_keep_negatives` | Keep negatives | Conserver les négatifs |
| `editor_title_import` | Develop | Développer |
| `editor_title_redevelop` | Re-develop | Redévelopper |
| `editor_save` | Save | Enregistrer |
| `editor_saving` | Developing… | Développement… |
| `editor_loading` | Opening photo… | Ouverture de la photo… |
| `editor_exposure` | Exposure | Exposition |
| `editor_temperature` | Temperature | Température |
| `editor_reset` | Reset adjustments | Réinitialiser les réglages |
| `cd_editor_preview` | Preview, %1$s look | Aperçu, rendu %1$s |
| `cd_prism` | Prism lens | Objectif prisme |
| `err_import_failed` | Couldn't open that photo. | Impossible d'ouvrir cette photo. |
| `err_negative_missing` | The negative of this photo is no longer available. | Le négatif de cette photo n'est plus disponible. |
| `err_delete_failed` | Couldn't delete the photo. | Impossible de supprimer la photo. |
| `err_no_gallery_app` | No app can show your photos. | Aucune application ne peut afficher vos photos. |
| `msg_photo_deleted` | Photo deleted | Photo supprimée |
| `gallery_title` | Gallery | Galerie |
| `gallery_empty_title` | No photos here yet | Pas encore de photos ici |
| `gallery_empty_body` | Photos you take or develop in this app appear here. Photos from before a reinstall stay in your Photos app. | Les photos prises ou développées dans cette application apparaissent ici. Celles d'avant une réinstallation restent dans votre application Photos. |
| `gallery_open_photos` | Open in Photos | Ouvrir dans Photos |
| `cd_photo_n` | Photo %1$d | Photo %1$d |
| `viewer_position` | %1$d of %2$d | %1$d sur %2$d |
| `viewer_share` | Share | Partager |
| `viewer_delete` | Delete | Supprimer |
| `viewer_redevelop` | Re-develop | Redévelopper |
