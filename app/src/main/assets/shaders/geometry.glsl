// Pass 1 - Geometry (barrel distortion + circular mask). fxDistort is in effects/distortion.glsl.
IN vec2 vUv;
uniform sampler2D uInput;
uniform vec2 uDistortion; // (barrel, circularMask)
uniform float uShortSide;
uniform vec2 uOutSize;

void main() {
    vec2 img = vec2(vUv.x, 1.0 - vUv.y);
    vec2 aspect = uOutSize / uShortSide;
    vec2 p = (img - 0.5) * aspect; // normalized units, centre origin
    vec2 q = fxDistort(p, uDistortion.x);
    vec2 s = q / aspect + 0.5;
    vec3 c = texture(uInput, vec2(s.x, 1.0 - s.y)).rgb;
    c *= fxCircularMask(p, uDistortion.y, uShortSide);
    FRAG_COLOR = vec4(c, 1.0);
}
