// Barrel (fisheye-like) distortion in normalized units; radius 0.5 (short-side edge) maps to itself.
vec2 fxDistort(vec2 p, float barrel) {
    float k = barrel * 1.2;
    if (k <= 0.0) return p;
    float r2 = dot(p, p);
    return p * (1.0 + k * r2) / (1.0 + k * 0.25);
}

// Circle of diameter = short side; 1 px anti-aliased edge.
float fxCircularMask(vec2 p, float enabled, float shortSidePx) {
    if (enabled < 0.5) return 1.0;
    float aa = 1.0 / shortSidePx;
    return 1.0 - smoothstep(0.5 - aa, 0.5 + aa, length(p));
}
