// Pass 2 - Pyramid level (box-filtered downsample). uFootprint = one target texel in source uv.
IN vec2 vUv;
uniform sampler2D uInput;
uniform vec2 uFootprint;

void main() {
    vec3 s = vec3(0.0);
    for (int j = 0; j < 4; j++) {
        for (int i = 0; i < 4; i++) {
            vec2 o = (vec2(float(i), float(j)) + 0.5) / 4.0 - 0.5;
            s += texture(uInput, vUv + o * uFootprint).rgb;
        }
    }
    FRAG_COLOR = vec4(s / 16.0, 1.0);
}
