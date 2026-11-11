// Release-build fallback when a pass fails to compile: passthrough with Pass 4 semantics
// (identity for passes 1-3, where uImageRect = (0,0,1,1) and uFlipY = 0).
IN vec2 vUv;
uniform sampler2D uInput;
uniform vec4 uImageRect;
uniform float uFlipY;

void main() {
    vec2 cuv = vec2(vUv.x, uFlipY > 0.5 ? vUv.y : 1.0 - vUv.y);
    vec2 iuv = (cuv - uImageRect.xy) / uImageRect.zw;
    vec3 c = vec3(0.0);
    if (iuv.x >= 0.0 && iuv.y >= 0.0 && iuv.x <= 1.0 && iuv.y <= 1.0) {
        c = texture(uInput, vec2(iuv.x, 1.0 - iuv.y)).rgb;
    }
    FRAG_COLOR = vec4(c, 1.0);
}
