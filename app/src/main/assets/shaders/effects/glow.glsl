// Centre glow (flash-hot centre). p in normalized units from the centre.
vec3 fxCenterGlow(vec3 c, vec2 p, vec2 glow) {
    if (glow.x <= 0.0) return c;
    float r = max(glow.y, 1e-3);
    float g = glow.x * exp(-dot(p, p) / (r * r));
    return c + (1.0 - c) * g * 0.6;
}
