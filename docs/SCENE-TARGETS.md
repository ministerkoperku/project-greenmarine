# Named scene targets and multiple fragment outputs

Schema 1 packs can declare owned 2D colour targets and map a scene shader's output
locations to them. Existing manifests and Java constructors retain their single
output behaviour. Named targets complement storage images; their names cannot
alias a storage image, buffer, or pass.

```json
{
  "schema": 1,
  "id": "demo:accumulation",
  "targets": {
    "Radiance": {
      "format": "rgba16f", "scale": 1,
      "clear": [0, 0, 0, 0], "clearEachFrame": false
    },
    "Material": {
      "format": "r32ui", "scale": 1,
      "clear": [4294967295, 0, 0, 0]
    }
  },
  "passes": [
    {
      "name": "accumulate", "fragment": "demo:core/accumulate",
      "inputs": {"PreviousRadiance": "Radiance", "PreviousMaterial": "Material"},
      "outputs": {"0": "Radiance", "1": "Material"}
    },
    {
      "name": "present", "fragment": "demo:core/present",
      "inputs": {"FinalRadiance": "Radiance", "FinalMaterial": "Material"}
    }
  ]
}
```

The accumulation fragment declares `layout(location=0) out vec4 radiance` and
`layout(location=1) out uint material`. It samples `PreviousRadiance` through a
`sampler2D` and `PreviousMaterial` through a `usampler2D`. A presentation fragment
converts the result to a normal floating-point colour output. An integer target
cannot be implicitly presented by the built-in floating-point copy shader.

## Formats and dimensions

Target formats share the storage-image format vocabulary: `rgba8`, `rgba16f`,
`rgba32f`, `r16f`, `rg16f`, `r32f`, `rg32f`, `r32ui`, `rg32ui`, `rgba32ui`, and
`r11g11b10f`. The host device must support each requested attachment format.
Allocation failure is explicit; named HDR targets never fall back to an 8-bit
format. Unsigned formats use typed integer clears and nearest sampling on Vulkan.
Floating-point targets use Renderpearl and also have a portable OpenGL path;
unsigned target initialization currently requires Vulkan.

Use either `"size":[width,height]` or `"scale":factor`. Fixed dimensions must be
positive and no greater than 16384; scale must be positive, finite, and at most
4. Relative dimensions round upward and have the same 16384 limit. The actual
host texture limit also applies. All outputs of one pass must resolve to the
same width and height; formats may differ. A storage volume or line image cannot
be attached as a named colour target.

Output locations must be consecutive from zero, with a different target at each
location. The host's attachment count applies. Target dimensions determine the
pass viewport and `Frame.xy`; omit legacy pass `scale`, `format`, and `history`
when using named outputs. Existing single-output history passes remain available.

## Initialization, feedback and lifetime

`clear` has four numeric components and defaults to `[0,0,0,0]`. Unsigned clears
accept exact integers from 0 through 4294967295 and preserve their bits. A
converter should emit the source pack's explicit clear values rather than infer
one universal value for Iris targets.

Named targets are initialized before their first read, even when their first
writer is excluded by quality or capability selection. An input referencing a
target reads its latest completed pass value; a self input reads the value before
that pass. Each write uses a separate owned texture. Previous contents are copied
with identical format and dimensions before rendering, preserving discarded and
unwritten pixels, then all outputs are published together after the draw.

Values persist across frames. `clearEachFrame:true` resets the target at frame
start. Pack changes, resource reloads, world changes, viewport resizing and changes
to the required target set reinitialize the owned pairs. A parameter-only edit
retains the current targets through the existing renderer lifecycle.

Inputs naming an earlier scene **pass** still refer to that exact pass's first
output. These retained results remain stable if subsequent passes overwrite the
same named target. Inputs naming targets instead observe their latest value.

Packs can declare up to 256 raster passes, 256 compute passes, 64 named colour
targets, and 32 named inputs per pass. Only targets used by selected passes are
allocated. Each allocated target owns two textures; retained pass results can
require additional snapshots. Feedback copies consume bandwidth, so those costs
must be measured separately from shader and CPU submission work.

Named targets do not change the 288-byte MarineFrame, parameter order, lightmap
bindings, shader fallback rules, or native ABI. Their existence does not certify
that an imported shaderpack's world stages and visual effects are equivalent.
