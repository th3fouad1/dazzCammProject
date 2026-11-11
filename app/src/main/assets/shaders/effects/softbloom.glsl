// Soft focus / bloom / sharpen from the fixed-size pyramid (short side 512/256/128):
// cost and look are resolution-independent. b0..b2 are already LUT-coloured.
vec3 fxSoftBloom(vec3 c, vec3 b0, vec3 b1, vec3 b2, float soft, vec2 bloom, float sharpen) {
    if (sharpen > 0.0) c += (c - b0) * sharpen * 1.5;
    if (soft > 0.0) {
        vec3 s = (b0 + b1) * 0.5;
        vec3 screen = 1.0 - (1.0 - c) * (1.0 - s);
        c = mix(c, mix(s, screen, 0.5), soft * 0.6);
    }
    if (bloom.x > 0.0) {
        vec3 h = (b1 + b2) * 0.5;
        vec3 bright = max(h - bloom.y, 0.0) / max(1.0 - bloom.y, 1e-3);
        c += bright * bloom.x;
    }
    return c;
}
