#ifndef GREENMARINE_HISTORY_V1
#define GREENMARINE_HISTORY_V1
layout(std140) uniform MarineHistoryFrame {
    mat4 HistoryProjection;
    mat4 HistoryView;
    vec4 HistoryCameraDelta; // Current camera minus previous camera.
    vec4 HistoryInfo; // Previous color valid, previous normals valid, age, elapsed seconds.
};
uniform sampler2D HistorySampler;
uniform sampler2D HistoryNormalDepth;
// Additional full-resolution rejection inputs for lightingHistory passes.
uniform sampler2D HistoryAlbedoMaterial;
uniform sampler2D HistorySceneDepth;
#endif
