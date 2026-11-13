# Backlog

## B-001 — Photo looks in Video mode (requested 2026-11-11, deferred)
- Show photo presets in Video mode **without frames** (instant / 120 / slide / pano borders disabled in video).
- Animated grain (time-driven) for all of them in video.
- Photo-only modes stay photo-only: double exposure, half-frame, grid.
- Performance gate: each look must hold ≥ 30 fps preview while recording on the reference device (OnePlus 11); enable only the ones that pass. Risky: Pocket (soft focus + bloom + glow), Bubble (fisheye mask).
- Implementation hint: video looks are presets with `kind: "video"`; allow photo presets in the video carousel with a `videoSafe` flag or per-look allow-list.

## B-002 — Trim audio tail when recording stops on background (found 2026-11-11) — FIXED in code 2026-11-13, device check pending
- Pressing HOME while recording saves a valid MP4, but audio runs ~0.7 s past the last video frame (camera stops delivering frames before the stop reaches the recorder).
- Fix: on stop, drop/trim AAC samples with PTS > last written video PTS (or stop AudioRecord at the camera-close event).
- Done: `AudioTailGate` (video/VideoTiming.kt) holds AAC samples ahead of the last written video PTS and drops those past last video PTS + one frame at video EOS. Unit tests in VideoTimingTest. Device check: HOME while recording, ffprobe audio vs video duration within ~35 ms.
