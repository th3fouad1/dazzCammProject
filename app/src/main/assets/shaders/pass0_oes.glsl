// Pass 0 - Normalize (camera OES texture). Compiled as ESSL 3.00, or ESSL 1.00 as a fallback.
// Output: upright, cropped, gamma-encoded sRGB working image. vUv is GL-oriented (y up);
// image uv is y-down. uSrcMatrix maps image uv to buffer uv (y down), see SourceTransform.
IN vec2 vUv;
uniform SAMPLER_OES uInput;
uniform mat3 uSrcMatrix;
uniform mat4 uStMatrix;
uniform vec4 uCropRect; // buffer uv (x, y, w, h)

void main() {
    vec2 img = vec2(vUv.x, 1.0 - vUv.y);
    vec2 b = (uSrcMatrix * vec3(img, 1.0)).xy;
    b = clamp(b, uCropRect.xy, uCropRect.xy + uCropRect.zw);
    // SurfaceTexture coordinates are GL-oriented: t = 0 at the bottom of the buffer.
    vec2 t = (uStMatrix * vec4(b.x, 1.0 - b.y, 0.0, 1.0)).xy;
    // Reserved: per-device preview tone offset (Phase C calibration), identity for now.
    FRAG_COLOR = vec4(TEX_OES(uInput, t).rgb, 1.0);
}
