# Backlog

## B-001 — Photo looks in Video mode (requested 2026-11-11, deferred)
- Show photo presets in Video mode **without frames** (instant / 120 / slide / pano borders disabled in video).
- Animated grain (time-driven) for all of them in video.
- Photo-only modes stay photo-only: double exposure, half-frame, grid.
- Performance gate: each look must hold ≥ 30 fps preview while recording on the reference device (OnePlus 11); enable only the ones that pass. Risky: Pocket (soft focus + bloom + glow), Bubble (fisheye mask).
- Implementation hint: video looks are presets with `kind: "video"`; allow photo presets in the video carousel with a `videoSafe` flag or per-look allow-list.
