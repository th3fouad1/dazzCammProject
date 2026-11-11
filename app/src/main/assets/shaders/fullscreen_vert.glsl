// Full-screen quad. ATTR / VARY_OUT come from the variant block (ESSL 3.00 or 1.00).
ATTR vec2 aPosition;
VARY_OUT vec2 vUv;

void main() {
    vUv = aPosition * 0.5 + 0.5;
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
