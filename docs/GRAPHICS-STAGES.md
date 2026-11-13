# Geometry and tessellation programs

`ShaderProgram.withStages(Map<GraphicsStage, ShaderStageSource>, inputPatchVertices)`
adds geometry, tessellation control and tessellation evaluation stages to a
Renderpearl program. Its pipeline still declares the host vertex and fragment
shaders. Endpoint entries in the stage map can replace those sources too.

```java
var program = new ShaderProgram(pipeline, sources, includes, Map.of())
    .withStages(Map.of(
        GraphicsStage.TESS_CONTROL, new ShaderStageSource(controlGlsl),
        GraphicsStage.TESS_EVALUATION, new ShaderStageSource(evaluationGlsl),
        GraphicsStage.GEOMETRY, new ShaderStageSource(geometryGlsl)), 3);
var compiled = session.compile(program);
```

Every `ShaderStageSource` requires an editable GLSL fallback and optionally
accepts a `StageBinary` of the same stage. The existing pack `ShaderBinary`
remains fragment-only. A tessellation declaration requires both stages and an
explicit input patch size from 1 through 32; that size is independent of the
control shader's output vertex count. Programs without tessellation pass zero.

Declare resources used only by intermediate stages in the pipeline's normal
`BindGroupLayout` too. Host binding discovery includes them, assigns dense
bindings across the entire chain and rewrites the actual intermediate SPIR-V.
Their descriptor visibility includes the stages that use them. GLSL includes
come from the program's captured include map; pipeline defines apply to the
intermediate GLSL compilation as well.

This execution path requires Minecraft's Vulkan backend and the corresponding
features enabled on its actual device. Geometry and tessellation features are
requested independently and are optional for ordinary programs. Compilation
checks the actual compiled stage interfaces, including interface block members,
matrices, arrays, patch data and the arrays implied by intermediate stages.
Physical-device limits and supported SPIR-V capabilities are checked before
creating a pipeline. Unsupported requirements fail explicitly.

Drawing continues through Minecraft's device, Renderpearl pipelines and command
encoders. GreenMarine owns its intermediate shader modules and releases them
after pipeline creation; the host retains its normal deferred pipeline lifetime.
Both host pipeline variants receive the full stage chain.

The standalone GPU probe has passed geometry and a three-point triangular,
fractional-odd CCW tessellation plus geometry chain, including intermediate-only
uniforms and depth/no-depth readback on the RX 9070 XT Vulkan host. This is not
full converted Eclipse world/shadow acceptance. OpenGL execution of intermediate
stages is unsupported; Android runtime acceptance has not been tested.

Schema 1 [world programs](WORLD-PROGRAMS.md) can declare the same intermediate
stages by resource identifier. Automatic Iris terrain conversion emits the
original adapted stages and uses three input vertices for the host's triangular
index stream. Other input primitives remain explicit conversion requirements.
Separate actual-world Vulkan probes have passed geometry and tessellation plus
geometry across solid, cutout and water draws, including intermediate-only
uniforms. Full Eclipse resource, shadow and entity execution remains separate.
