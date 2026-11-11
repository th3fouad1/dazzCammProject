// Shared helpers: integer lattice hashes (grain itself is continuous noise, see grain.glsl).
uint fxHashU(uint x) {
    x ^= x >> 16u;
    x *= 0x7feb352du;
    x ^= x >> 15u;
    x *= 0x846ca68bu;
    x ^= x >> 16u;
    return x;
}

float fxHash(vec2 cell, float salt) {
    ivec2 i = ivec2(cell) + ivec2(32768);
    uint h = fxHashU(uint(i.x) ^ fxHashU(uint(i.y) ^ fxHashU(uint(int(salt * 65536.0)))));
    return float(h >> 8u) / 16777216.0;
}

float fxLuma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}
