// Film grain: continuous gradient noise evaluated in normalized coordinates (never a per-pixel
// hash), so its scale is a fixed fraction of the short side at any resolution.
vec2 fxGrad(vec2 cell, float salt) {
    float a = fxHash(cell, salt) * 6.2831853;
    return vec2(cos(a), sin(a));
}

float fxGradientNoise(vec2 x, float salt) {
    vec2 i = floor(x);
    vec2 f = fract(x);
    vec2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    float a = dot(fxGrad(i, salt), f);
    float b = dot(fxGrad(i + vec2(1.0, 0.0), salt), f - vec2(1.0, 0.0));
    float c = dot(fxGrad(i + vec2(0.0, 1.0), salt), f - vec2(0.0, 1.0));
    float d = dot(fxGrad(i + vec2(1.0, 1.0), salt), f - vec2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y) * 1.4;
}

vec3 fxGrain(vec3 c, vec2 p, vec3 grain, float time, float seed, float shortSide) {
    if (grain.x <= 0.0) return c;
    // New grain field every 1/24 s in preview; capture reuses the last presented time.
    float frame = floor(time * 24.0);
    vec2 off = vec2(fxHash(vec2(frame, 1.0), seed), fxHash(vec2(frame, 2.0), seed)) * 256.0;
    bool still = shortSide > 1440.0;
    // Preview / video: cells never finer than 4 px of a 1080 short side (no shimmer or aliasing).
    // Full-resolution stills: grain lives on the output pixel grid (~1.8 px cells) plus a coarser
    // octave for clumping, like film grain at print size. It is not a scaled-up copy of the preview
    // field (that became ~11 px blotches at 12 MP); the look matches statistically instead.
    float cell = still ? max(grain.y, 1.8 / shortSide) : max(grain.y, 4.0 / 1080.0);
    vec2 q = p / cell + off;
    float n;
    if (still) {
        n = (fxGradientNoise(q, 0.17) * 0.8 + fxGradientNoise(q * 0.47, 0.29) * 0.35) * (0.873 / sqrt(0.7625));
    } else {
        float w2 = clamp((cell * 1080.0 / 2.03 - 3.5) / 1.5, 0.0, 1.0);
        n = (fxGradientNoise(q, 0.17) * 0.8 + fxGradientNoise(q * 2.03, 0.29) * 0.35 * w2)
            * (0.873 / sqrt(0.64 + 0.1225 * w2 * w2));
    }
    vec3 nc = vec3(fxGradientNoise(q, 0.31), fxGradientNoise(q, 0.43), fxGradientNoise(q, 0.59));
    vec3 nn = mix(vec3(n), nc, grain.z);
    float l = fxLuma(c);
    float w = 0.45 + 0.55 * (1.0 - abs(2.0 * l - 1.0));
    return c + nn * grain.x * (still ? 0.25 : 0.2) * w;
}
