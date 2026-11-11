// Pass 0 - Normalize (decoded capture bitmap as sampler2D; bitmap row 0 is at t = 0).
IN vec2 vUv;
uniform sampler2D uInput;
uniform mat3 uSrcMatrix;
uniform vec4 uCropRect; // buffer uv (x, y, w, h)

void main() {
    vec2 img = vec2(vUv.x, 1.0 - vUv.y);
    vec2 b = (uSrcMatrix * vec3(img, 1.0)).xy;
    b = clamp(b, uCropRect.xy, uCropRect.xy + uCropRect.zw);
    FRAG_COLOR = vec4(TEX2D(uInput, b).rgb, 1.0);
}
