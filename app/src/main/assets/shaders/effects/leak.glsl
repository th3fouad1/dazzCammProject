// Procedural light leak, exactly 4 variants (0..3). Origin uLeakPos on an image edge (uv, y down).
vec3 fxLeakTint(float v) {
    if (v < 0.5) return vec3(1.0, 0.36, 0.10);   // 0: red-orange
    if (v < 1.5) return vec3(1.0, 0.68, 0.22);   // 1: amber
    if (v < 2.5) return vec3(0.95, 0.22, 0.38);  // 2: magenta-red
    return vec3(1.0, 0.86, 0.45);                // 3: pale gold
}

vec3 fxLeak(vec3 c, vec2 p, vec2 aspect, vec4 leak, vec2 pos) {
    if (leak.x < 0.5) return c;
    vec2 o = (pos - 0.5) * aspect;
    vec2 d = p - o;
    float ca = cos(leak.w);
    float sa = sin(leak.w);
    vec2 q = vec2(ca * d.x + sa * d.y, -sa * d.x + ca * d.y);
    float v = leak.y;
    float m;
    if (v < 0.5) {
        vec2 e = q * vec2(1.0, 2.2);
        m = exp(-dot(e, e) / 0.16);                                          // soft blob
    } else if (v < 1.5) {
        m = exp(-dot(q, q) / 0.30) * (0.6 + 0.4 * exp(-dot(q, q) / 0.04));   // wide wash + core
    } else if (v < 2.5) {
        m = exp(-(q.x * q.x) / 0.012) * exp(-(q.y * q.y) / 0.6);             // streak
    } else {
        vec2 q2 = q - vec2(0.18, 0.12);
        m = max(exp(-dot(q, q) / 0.08), 0.7 * exp(-dot(q2, q2) / 0.05));    // double blob
    }
    vec3 l = clamp(fxLeakTint(v) * m * leak.z, 0.0, 1.0);
    return 1.0 - (1.0 - c) * (1.0 - l);
}
