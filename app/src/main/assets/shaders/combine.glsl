// Pass 3 - Combine. Effect functions come from shaders/effects/*.glsl (prepended at load).
// Fixed order (SPEC 7.2): colour (3D LUT), soft focus/bloom/sharpen, centre glow, leak,
// vignette, dust, grain. Double exposure screens the stored first exposure in before the glow.. All positions in normalized units (fraction of the short side).
IN vec2 vUv;
uniform sampler2D uInput;
uniform sampler2D uPyr0;
uniform sampler2D uPyr1;
uniform sampler2D uPyr2;
uniform highp sampler3D uLookLut;
uniform float uLutStrength;
uniform float uShortSide;
uniform vec2 uOutSize;
uniform vec3 uGrain;      // (amount, size, chroma)
uniform float uGrainTime;
uniform vec4 uVignette;   // (amount, radius, softness, shape)
uniform vec2 uCenterGlow; // (amount, radius)
uniform float uSoftFocus;
uniform vec2 uBloom;      // (amount, threshold)
uniform float uSharpen;
uniform vec4 uLeak;       // (enabled, variant, amount, angle)
uniform vec2 uLeakPos;    // image uv, y down
uniform float uDust;
uniform float uSeed;
uniform sampler2D uBlend;  // stored first exposure (bitmap rows: top at t = 0)
uniform float uBlendOn;
uniform vec4 uGel;         // (rgb tint, strength)
uniform float uVideoStyle;
uniform vec4 uVideoFxA;
uniform vec4 uVideoFxB;
uniform float uVideoScratches;
uniform float uTime;
uniform float uFrameIndex;

void main() {
    vec2 img = vec2(vUv.x, 1.0 - vUv.y);
    vec2 aspect = uOutSize / uShortSide;
    vec2 p = (img - 0.5) * aspect;

    vec3 source = texture(uInput, vUv).rgb;
    source = fxVideoArtifacts(uInput, vUv, source, aspect, uVideoStyle, uVideoFxA, uVideoFxB, uVideoScratches, uTime, uFrameIndex, uSeed);
    vec3 c = fxLookColor(uLookLut, source, uLutStrength);
    vec3 b0 = fxLookColor(uLookLut, texture(uPyr0, vUv).rgb, uLutStrength);
    vec3 b1 = fxLookColor(uLookLut, texture(uPyr1, vUv).rgb, uLutStrength);
    vec3 b2 = fxLookColor(uLookLut, texture(uPyr2, vUv).rgb, uLutStrength);
    c = fxSoftBloom(c, b0, b1, b2, uSoftFocus, uBloom, uSharpen);
    if (uBlendOn > 0.5) c = fxScreen(c, texture(uBlend, img).rgb);
    c = fxCenterGlow(c, p, uCenterGlow, uGel);
    c = fxLeak(c, p, aspect, uLeak, uLeakPos);
    c = fxVignette(c, img, p, uVignette);
    c = fxDust(c, p, uDust, uSeed, uShortSide);
    c = fxGrain(c, p, uGrain, uGrainTime, uSeed, uShortSide);
    FRAG_COLOR = vec4(clamp(c, 0.0, 1.0), 1.0);
}
