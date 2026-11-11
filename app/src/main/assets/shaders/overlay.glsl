// Pass 4 - Overlay: frame + date stamp (SPEC 7.3), drawn on the canvas in both paths.
// uFlipY = 1 for the capture FBO so glReadPixels returns rows top-first (Y-flip in Pass 4).
IN vec2 vUv;
uniform sampler2D uInput;
uniform float uShortSide;   // S, px (image area)
uniform vec2 uCanvasSize;   // px
uniform vec4 uImageRect;    // canvas uv, y down
uniform float uFlipY;
uniform int uFrame;         // 0 none, 1 instant_white, 2 film_strip
uniform vec3 uFrameColor;   // instant paper colour
uniform vec4 uSprocket;     // (pitch, w, h, radius) in S
uniform vec2 uStampGeom;    // (inset, capHeight) in S
uniform vec4 uStampColor;
uniform float uStampOn;
uniform vec4 uStampDigits[2]; // 8 glyph codes: 0-9 digits, 10 blank, 11 apostrophe

const int SEG[10] = int[10](63, 6, 91, 79, 102, 109, 125, 7, 127, 111);

float sdBox(vec2 p, vec2 c, vec2 h) {
    vec2 d = abs(p - c) - h;
    return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0);
}

int glyphCode(int i) {
    vec4 v = i < 4 ? uStampDigits[0] : uStampDigits[1];
    int k = i < 4 ? i : i - 4;
    float g = k == 0 ? v.x : (k == 1 ? v.y : (k == 2 ? v.z : v.w));
    return int(floor(g + 0.5));
}

// Signed distance (in H units) to a 7-segment glyph; q in glyph cell (x right, y up), cell w x 1.
float sdGlyph(vec2 q, int code, float w) {
    float t = 0.13;
    q.x -= q.y * 0.12; // slight italic
    if (code == 11) return sdBox(q, vec2(0.12, 0.82), vec2(0.05, 0.14)) - 0.02;
    if (code < 0 || code > 9) return 1e3;
    int m = SEG[code];
    float d = 1e3;
    float hx = w * 0.5 - t * 0.6;
    float hy = 0.25 - t * 0.6;
    if ((m & 1) != 0) d = min(d, sdBox(q, vec2(w * 0.5, 1.0 - t * 0.5), vec2(hx, t * 0.5)));
    if ((m & 2) != 0) d = min(d, sdBox(q, vec2(w - t * 0.5, 0.75), vec2(t * 0.5, hy)));
    if ((m & 4) != 0) d = min(d, sdBox(q, vec2(w - t * 0.5, 0.25), vec2(t * 0.5, hy)));
    if ((m & 8) != 0) d = min(d, sdBox(q, vec2(w * 0.5, t * 0.5), vec2(hx, t * 0.5)));
    if ((m & 16) != 0) d = min(d, sdBox(q, vec2(t * 0.5, 0.25), vec2(t * 0.5, hy)));
    if ((m & 32) != 0) d = min(d, sdBox(q, vec2(t * 0.5, 0.75), vec2(t * 0.5, hy)));
    if ((m & 64) != 0) d = min(d, sdBox(q, vec2(w * 0.5, 0.5), vec2(hx, t * 0.5)));
    return d - 0.015;
}

vec3 drawStamp(vec3 c, vec2 ip, vec2 rs) {
    float S = uShortSide;
    float H = uStampGeom.y * S;
    float W = 0.55;
    // Slot x offsets in H units for: apostrophe, yy, MM, dd (group gap 0.6 H, glyph gap 0.25 H).
    float total = 5.70;
    vec2 origin = vec2(rs.x - uStampGeom.x * S - total * H, rs.y - uStampGeom.x * S); // baseline-left, px
    vec2 q = vec2(ip.x - origin.x, origin.y - ip.y) / H; // H units, y up
    if (q.x < -0.6 || q.x > total + 0.6 || q.y < -0.6 || q.y > 1.6) return c;
    float d = 1e3;
    d = min(d, sdGlyph(q, glyphCode(0), 0.3));
    d = min(d, sdGlyph(q - vec2(0.45, 0.0), glyphCode(1), W));
    d = min(d, sdGlyph(q - vec2(1.25, 0.0), glyphCode(2), W));
    d = min(d, sdGlyph(q - vec2(2.40, 0.0), glyphCode(3), W));
    d = min(d, sdGlyph(q - vec2(3.20, 0.0), glyphCode(4), W));
    d = min(d, sdGlyph(q - vec2(4.35, 0.0), glyphCode(5), W));
    d = min(d, sdGlyph(q - vec2(5.15, 0.0), glyphCode(6), W));
    float dpx = d * H;
    float core = 1.0 - smoothstep(-0.75, 0.75, dpx);
    float glow = exp(-max(dpx, 0.0) / (0.25 * H)) * 0.35;
    vec3 col = uStampColor.rgb;
    c = mix(c, col, core * uStampColor.a);
    c += col * glow * (1.0 - core) * 0.6;
    return clamp(c, 0.0, 1.0);
}

vec3 frameColor(vec2 px, vec2 ip, vec2 rs) {
    if (uFrame == 1) return uFrameColor;
    if (uFrame == 2) {
        float S = uShortSide;
        float band = uImageRect.y * uCanvasSize.y;
        float cy = ip.y < 0.0 ? -band * 0.5 : rs.y + band * 0.5;
        float pitch = uSprocket.x * S;
        vec2 q = vec2(mod(px.x, pitch) - pitch * 0.5, ip.y - cy);
        vec2 h = vec2(uSprocket.y, uSprocket.z) * 0.5 * S;
        float r = uSprocket.w * S;
        float d = length(max(abs(q) - h + r, 0.0)) - r;
        float hole = 1.0 - smoothstep(-0.75, 0.75, d);
        return mix(vec3(0.02), vec3(0.80, 0.76, 0.68), hole);
    }
    return vec3(0.0);
}

void main() {
    vec2 cuv = vec2(vUv.x, uFlipY > 0.5 ? vUv.y : 1.0 - vUv.y); // canvas uv, y down
    vec2 px = cuv * uCanvasSize;
    vec2 r0 = uImageRect.xy * uCanvasSize;
    vec2 rs = uImageRect.zw * uCanvasSize;
    vec2 ip = px - r0;
    vec3 c;
    if (ip.x >= 0.0 && ip.y >= 0.0 && ip.x < rs.x && ip.y < rs.y) {
        vec2 iuv = ip / rs;
        c = texture(uInput, vec2(iuv.x, 1.0 - iuv.y)).rgb;
    } else {
        c = frameColor(px, ip, rs);
    }
    if (uStampOn > 0.5) c = drawStamp(c, ip, rs);
    FRAG_COLOR = vec4(c, 1.0);
}
