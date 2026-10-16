#ifndef GREENMARINE_ENVIRONMENT_V1
#define GREENMARINE_ENVIRONMENT_V1
layout(std140) uniform MarineEnvironment {
    vec4 Environment; // Camera altitude, rain, accumulated wetness, underwater.
    vec4 Climate; // Temperature, snow, lightning, thunder.
    vec4 SkyColor; // Host sky RGB, surface capture readiness.
    vec4 EnvironmentFogColor;
    vec4 Celestial; // Moon phase, moon angle, day index, sun angle.
    vec4 Moon; // World direction xyz, strength.
    vec4 Dimension; // Overworld, nether, end, sky enabled.
    vec4 EnvironmentTime; // Seconds, day fraction, frame index, paused.
};
#endif
