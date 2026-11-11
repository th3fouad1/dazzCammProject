// Vignette. shape 0 = oval (follows the frame, corners at d = sqrt 2), 1 = circle (short side).
vec3 fxVignette(vec3 c, vec2 img, vec2 p, vec4 v) {
    if (v.x <= 0.0) return c;
    float d = v.w > 0.5 ? length(p) * 2.0 : length((img - 0.5) * 2.0);
    float e0 = v.y * (1.0 - v.z);
    float e1 = max(v.y * (1.0 + 0.5 * v.z), e0 + 1e-3);
    return c * (1.0 - v.x * smoothstep(e0, e1, d));
}
