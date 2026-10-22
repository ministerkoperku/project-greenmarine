#ifndef GREENMARINE_EXTERNAL_SURFACE
#define GREENMARINE_EXTERNAL_SURFACE
// Reference shading for renderers that draw their own pixels and want GreenMarine
// surface RT on them (ExternalRenderers.surfaceOutputs()). Bind the four views
// yourself; this file only holds the arithmetic Solstice terrain uses.
//
// screenUv  = gl_FragCoord.xy / framebuffer size (same for every image)
// lighting  = texture(lightingView, screenUv), linear filter
// reflection= texture(reflectionView, screenUv), linear filter
// captured  = texture(normalDepthView, screenUv), nearest filter

// The RT texel belongs to this fragment only if the material capture saw the same
// surface: matching normal and (reversed-Z) depth.
bool greenmarineExternalSameSurface(vec4 captured, vec3 worldNormal, float fragDepth) {
    return dot(captured.xyz, worldNormal) > .95
        && abs(captured.w - fragDepth) < max(.00001, fragDepth * .01);
}

// albedo: linear base colour. reflectWeight: your Fresnel/roughness weight in [0,1]
// (0 for ordinary matte blocks). Returns linear radiance before tonemapping.
vec3 greenmarineExternalShade(vec3 albedo, vec4 lighting, vec4 reflection, float reflectWeight) {
    vec3 lit = albedo * max(lighting.rgb, vec3(0));
    return mix(lit, max(reflection.rgb, vec3(0)), clamp(reflection.a * reflectWeight, 0.0, 1.0));
}

// Schlick weight matching Solstice's dielectric classes (F0 0.04).
float greenmarineExternalFresnel(vec3 worldNormal, vec3 toEye, float roughness) {
    float f = .04 + .96 * pow(1.0 - max(dot(worldNormal, toEye), 0.0), 5.0);
    return f * (1.0 - roughness);
}
#endif
