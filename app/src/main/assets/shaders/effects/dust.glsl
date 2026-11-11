// Procedural dust specks on a jittered grid in normalized units (cell 0.06 S), seeded per shot.
vec3 fxDust(vec3 c, vec2 p, float dust, float seed, float shortSidePx) {
    if (dust <= 0.0) return c;
    float cellSize = 0.06;
    vec2 g = p / cellSize;
    vec2 cell = floor(g);
    vec2 f = fract(g);
    float aa = 1.0 / (shortSidePx * cellSize);
    float dark = 0.0;
    float light = 0.0;
    for (int j = -1; j <= 1; j++) {
        for (int i = -1; i <= 1; i++) {
            vec2 o = vec2(float(i), float(j));
            vec2 cc = cell + o;
            if (fxHash(cc, seed + 0.11) >= dust * 0.18) continue;
            vec2 ctr = o + vec2(fxHash(cc, seed + 0.23), fxHash(cc, seed + 0.37));
            float r = mix(0.012, 0.05, fxHash(cc, seed + 0.41));
            float m = 1.0 - smoothstep(r - aa, r + aa, length(f - ctr));
            if (fxHash(cc, seed + 0.53) > 0.7) {
                light = max(light, m);
            } else {
                dark = max(dark, m);
            }
        }
    }
    c = mix(c, c * 0.2, dark * 0.85);
    c = mix(c, vec3(0.95), light * 0.7);
    return c;
}
