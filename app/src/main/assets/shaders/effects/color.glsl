// Colour: baked 33^3 look LUT (film LUT + parametric ops), sampled at texel centres.
vec3 fxLookColor(highp sampler3D lut, vec3 c, float strength) {
    vec3 coord = clamp(c, 0.0, 1.0) * (32.0 / 33.0) + 0.5 / 33.0;
    vec3 l = texture(lut, coord).rgb;
    return mix(c, l, strength);
}
