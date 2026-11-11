// Centre glow (flash-hot centre). p in normalized units from the centre.
// gel = (rgb tint, strength): tints the glow and adds a slight overall cast (SPEC 7.4); strength 0 = none.
vec3 fxCenterGlow(vec3 c, vec2 p, vec2 glow, vec4 gel) {
    if (glow.x <= 0.0 && gel.a <= 0.0) return c;
    float r = max(glow.y, 1e-3);
    float g = glow.x * exp(-dot(p, p) / (r * r));
    vec3 tint = mix(vec3(1.0), gel.rgb, gel.a);
    c += (1.0 - c) * g * 0.6 * tint;
    return mix(c, c * gel.rgb, 0.18 * gel.a);
}

// Screen blend of the stored first exposure (SPEC 7.4): 1 - (1 - a)(1 - b).
vec3 fxScreen(vec3 a, vec3 b) {
    return 1.0 - (1.0 - a) * (1.0 - b);
}
