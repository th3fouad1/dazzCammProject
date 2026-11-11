// Time-driven vintage-video artifacts. All offsets are normalized to the image short side.
vec3 fxVideoArtifacts(
    sampler2D inputTex, vec2 uv, vec3 c, vec2 aspect, float style,
    vec4 a, vec4 b, float scratches, float time, float frameIndex, float seed
) {
    if (style < 0.5) return c;

    // Horizontal chroma bleed/offset: reconstruct luma from the centre and R/B from neighbours.
    if (a.x > 0.0) {
        float dx = (0.0015 + 0.006 * a.x) / aspect.x;
        vec3 left = texture(inputTex, vec2(clamp(uv.x - dx, 0.0, 1.0), uv.y)).rgb;
        vec3 right = texture(inputTex, vec2(clamp(uv.x + dx, 0.0, 1.0), uv.y)).rgb;
        float y = fxLuma(c);
        vec3 shifted = vec3(right.r, y + 0.15 * (c.g - y), left.b);
        c = mix(c, shifted, 0.65 * a.x);
    }

    if (a.y > 0.0) {
        vec2 q = (uv - 0.5) * aspect * 420.0;
        float n = fxGradientNoise(q + vec2(frameIndex * 7.13, seed * 71.0), 0.73);
        c += vec3(n) * a.y * 0.11;
    }

    if (a.w > 0.0) {
        float line = 0.5 + 0.5 * sin(uv.y * 3.14159265 * 1080.0);
        c *= 1.0 - a.w * 0.075 * line;
    }

    if (b.y > 0.0) {
        float f = sin(time * 17.3) * 0.55 + (fxHash(vec2(frameIndex, 4.0), seed) - 0.5) * 0.9;
        c *= 1.0 + clamp(f * b.y, -0.03, 0.03);
    }

    if (b.w > 0.0) {
        float phase = fract(time * 0.035 + seed);
        vec2 p = (uv - vec2(phase < 0.5 ? 0.05 : 0.95, 0.72)) * aspect;
        float leak = exp(-dot(p, p) / 0.055) * (0.35 + 0.65 * sin(time * 1.7) * sin(time * 1.7));
        c += vec3(1.0, 0.32, 0.08) * leak * b.w * 0.32;
    }

    if (scratches > 0.0) {
        float block = floor(frameIndex / 4.0);
        for (int i = 0; i < 3; i++) {
            float x = fxHash(vec2(block, float(i)), seed + 0.37) * aspect.x;
            float on = step(0.72, fxHash(vec2(block, float(i) + 8.0), seed));
            float d = abs((uv.x - 0.5) * aspect.x + 0.5 * aspect.x - x);
            float line = 1.0 - smoothstep(0.00035, 0.0013, d);
            c += vec3(line * on * scratches * 0.42);
        }
    }
    return c;
}
