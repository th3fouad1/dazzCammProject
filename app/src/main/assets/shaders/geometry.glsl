// Pass 1 - Geometry (barrel distortion + circular mask). fxDistort is in effects/distortion.glsl.
IN vec2 vUv;
uniform sampler2D uInput;
uniform vec2 uDistortion; // (barrel, circularMask)
uniform float uShortSide;
uniform vec2 uOutSize;
uniform vec2 uPrism; // (mode 0 off / 1 split / 2 tri / 3 halo, amount)
uniform float uVideoStyle;
uniform vec4 uVideoFxA; // bleed, noise, tracking, scanlines
uniform vec4 uVideoFxB; // gate weave, flicker, rounded border, light leaks
uniform float uTime;
uniform float uFrameIndex;

// Image uv (y down) -> Pass 0 texel, clamped to the image.
vec3 tapImg(vec2 s) {
    return texture(uInput, vec2(clamp(s.x, 0.0, 1.0), 1.0 - clamp(s.y, 0.0, 1.0))).rgb;
}

// Prism lens (SPEC 7.4). s = image uv (y down); aspect = outSize / shortSide. Offsets are in short-side units,
// so the effect has the same shape at every render size.
vec3 fxPrism(vec3 base, vec2 s, vec2 aspect, float mode, float amount) {
    if (mode < 0.5) return base;
    vec3 o = base;
    if (mode < 1.5) {
        // Split: a mirrored copy of the right-hand side about an off-centre axis, soft-blended in.
        float axis = 0.60;
        float m = smoothstep(axis - 0.10, axis + 0.10, s.x);
        o = mix(base, tapImg(vec2(2.0 * axis - s.x, s.y)), m * 0.6);
    } else if (mode < 2.5) {
        // Tri: three radial copies (up, lower left, lower right), each weighted 1/3.
        float R = 0.05;
        vec2 o0 = R * vec2(0.0, -1.0) / aspect;
        vec2 o1 = R * vec2(-0.8660254, 0.5) / aspect;
        vec2 o2 = R * vec2(0.8660254, 0.5) / aspect;
        o = (tapImg(s + o0) + tapImg(s + o1) + tapImg(s + o2)) * (1.0 / 3.0);
    } else {
        // Halo: radial (zoom) blur confined to a ring around the centre.
        vec2 p = (s - 0.5) * aspect;
        float r = length(p);
        float ring = smoothstep(0.18, 0.36, r) * (1.0 - smoothstep(0.52, 0.80, r));
        vec3 acc = vec3(0.0);
        for (int i = 0; i < 9; i++) {
            float k = 1.0 - 0.12 * float(i) / 8.0;
            acc += tapImg(0.5 + (s - 0.5) * k);
        }
        o = mix(base, acc * (1.0 / 9.0) * (1.0 + 0.08 * ring), ring * 0.85);
    }
    return mix(base, o, clamp(amount, 0.0, 1.0));
}

void main() {
    vec2 img = vec2(vUv.x, 1.0 - vUv.y);
    vec2 aspect = uOutSize / uShortSide;
    vec2 p = (img - 0.5) * aspect; // normalized units, centre origin
    vec2 q = fxDistort(p, uDistortion.x);
    vec2 s = q / aspect + 0.5;
    if (uVideoStyle > 0.5) {
        // Gate weave is sub-pixel-scale at 1080 short-side; VHS tracking drifts in soft horizontal bands.
        vec2 weave = vec2(sin(uFrameIndex * 1.73), cos(uFrameIndex * 1.11)) * (0.0013 * uVideoFxB.x) / aspect;
        float band = sin(s.y * 43.0 + uTime * 2.4) * sin(s.y * 7.0 - uTime * 0.7);
        s += weave;
        s.x += band * 0.006 * uVideoFxA.z / aspect.x;
    }
    vec3 c = texture(uInput, vec2(s.x, 1.0 - s.y)).rgb;
    c = fxPrism(c, s, aspect, uPrism.x, uPrism.y);
    c *= fxCircularMask(p, uDistortion.y, uShortSide);
    FRAG_COLOR = vec4(c, 1.0);
}
