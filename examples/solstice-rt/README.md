# Solstice RT resource pack

Editable manifest for the built-in Solstice RT pack. The Gradle packaging task
adds the independently authored shader resources and shared binding includes
from `src/main/resources`; this folder alone is the declaration source.

Build the archive with `./gradlew solsticeShaderpack`, then enable it as a
Minecraft resource pack and select **Solstice RT** in GreenMarine's F6 screen.
GreenMarine 0.8 or newer with its Solstice surface/environment/history API and named scene-pass inputs
are required. A full-resolution HDR scene pass supplies linear color to bloom and
composition; Low uses that same cached scene without bloom. Without the
archive, the same pack is available from the built-in resource catalog.

See [Solstice RT documentation](../../docs/SOLSTICE-RT.md) for controls,
hardware fallback, binding contracts and limitations. Eclipse code/assets are
not distributed. Vulkan/OpenGL runtime acceptance is distinct from offline
shader compilation; no Iris/OptiFine compatibility is implied.
