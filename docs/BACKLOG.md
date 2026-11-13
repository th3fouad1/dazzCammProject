# Backlog

## B-001 — Photo looks in Video mode (requested 2026-11-11, deferred)
- Show photo presets in Video mode **without frames** (instant / 120 / slide / pano borders disabled in video).
- Animated grain (time-driven) for all of them in video.
- Photo-only modes stay photo-only: double exposure, half-frame, grid.
- Performance gate: each look must hold ≥ 30 fps preview while recording on the reference device (OnePlus 11); enable only the ones that pass. Risky: Pocket (soft focus + bloom + glow), Bubble (fisheye mask).
- Implementation hint: video looks are presets with `kind: "video"`; allow photo presets in the video carousel with a `videoSafe` flag or per-look allow-list.

## B-002 — Trim audio tail when recording stops on background (found 2026-11-11) — FIXED 2026-11-13, verified on device (SPEC Appendix F)
- Pressing HOME while recording saves a valid MP4, but audio runs ~0.7 s past the last video frame (camera stops delivering frames before the stop reaches the recorder).
- Fix: on stop, drop/trim AAC samples with PTS > last written video PTS (or stop AudioRecord at the camera-close event).
- Done: `AudioTailGate` (video/VideoTiming.kt) holds AAC samples ahead of the last written video PTS and drops those past last video PTS + one frame at video EOS. Unit tests in VideoTimingTest. Device check: HOME while recording, ffprobe audio vs video duration within ~35 ms.

## B-003 — Own HDR and Night modes (owner request 2026-11-13) — DONE v1 2026-11-13 (SPEC 7.7); open: handheld rotation alignment
- OEM HDR/Night is not exposed on the OnePlus 11 (SPEC Appendix G), so build an in-app multi-frame merge that outputs a replacement `CaptureInput` (render graph and `process(input, settings)` unchanged).
- Night first: N same-exposure frames (sequential `takePicture`, measure interval), low-res global alignment, per-pixel rejection against the reference, GPU accumulation one frame at a time (check half-float render targets). HDR second: underexposed burst merge + shadow lift, reusing align/merge.
- Verify bracketing really changes exposure before merge code. Excluded with flash, grid, double exposure, half-frame, video. UI shows "hold still" with the measured time.
- Acceptance on device: flat-patch noise below a single frame, no ghosting on moving TV picture. Exposure control stays internal (manual ISO/shutter remains a non-goal, SPEC 3.3).
