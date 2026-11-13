package com.koper.greenmarine.client;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ComputeReflectionTest {
    @Test void unsignedDeviceLimitsDoNotRejectValidDispatches() {
        var limits = new ComputeBackend.Limits(1024, new int[]{-1, 65535, 65535}, new int[]{1024, 1024, 1024});
        assertEquals(Integer.MAX_VALUE, limits.maxGroupCount()[0]);
        assertEquals(65535, limits.maxGroupCount()[2]);
    }
    @Test void reflectsBindingsByNameWithDistinctAutomaticSlots() {
        var source = """
            #version 450
            layout(local_size_x = 8, local_size_y = 4) in;
            layout(std140) uniform MarineFrame { vec4 Frame; };
            layout(std430) buffer SunData { vec4 sun; } sunData;
            layout(rgba16f) uniform image2D Sky;
            uniform sampler2D SceneSampler;
            layout(binding = 7) uniform sampler2D Pinned;
            void main() {
                ivec2 p = ivec2(gl_GlobalInvocationID.xy);
                vec4 c = texture(SceneSampler, vec2(p) / Frame.xy) + texture(Pinned, vec2(0.5));
                imageStore(Sky, p, c * sunData.sun);
            }
            """;
        var r = ComputeBackend.reflect(ComputeBackend.compile("test:sky", source));
        var byName = new HashMap<String, ComputeBackend.Slot>();
        r.slots().forEach(s -> byName.put(s.name(), s));
        assertEquals(Set.of("MarineFrame", "SunData", "Sky", "SceneSampler", "Pinned"), byName.keySet());
        assertEquals(ComputeBackend.Kind.UNIFORM_BUFFER, byName.get("MarineFrame").kind());
        assertEquals(ComputeBackend.Kind.STORAGE_BUFFER, byName.get("SunData").kind());
        assertEquals(ComputeBackend.Kind.STORAGE_IMAGE, byName.get("Sky").kind());
        assertEquals(ComputeBackend.Kind.SAMPLED_TEXTURE, byName.get("SceneSampler").kind());
        assertEquals(7, byName.get("Pinned").binding());
        assertEquals(5, r.slots().stream().map(ComputeBackend.Slot::binding).distinct().count());
        assertArrayEquals(new int[]{8, 4, 1}, new int[]{r.localX(), r.localY(), r.localZ()});
    }

    @Test void rejectsUnsupportedResourcesAndBadSource() {
        assertThrows(IllegalArgumentException.class, () -> ComputeBackend.compile("test:bad", "#version 450\nvoid main() { nope(); }"));
        var array = "#version 450\nlayout(local_size_x=1) in;\nlayout(rgba8) uniform image2DArray V;\nvoid main(){ imageStore(V, ivec3(0), vec4(1)); }";
        assertThrows(IllegalArgumentException.class, () -> ComputeBackend.reflect(ComputeBackend.compile("test:array", array)));
        var push = "#version 450\nlayout(local_size_x=1) in;\nlayout(push_constant) uniform P { float x; } p;\nlayout(std430) buffer B { float y; };\nvoid main(){ y = p.x; }";
        assertThrows(IllegalArgumentException.class, () -> ComputeBackend.reflect(ComputeBackend.compile("test:push", push)));
    }

    @Test void reflectsVolumeStorageAndSamplingForLpv() {
        var source = """
            #version 450
            layout(local_size_x=4, local_size_y=4, local_size_z=4) in;
            layout(rgba16f) uniform image3D NextLpv;
            uniform sampler3D PreviousLpv;
            void main() {
                ivec3 p = ivec3(gl_GlobalInvocationID);
                imageStore(NextLpv, p, texelFetch(PreviousLpv, p, 0));
            }
            """;
        var reflection = ComputeBackend.reflect(ComputeBackend.compile("test:lpv", source));
        assertEquals(2, reflection.slots().size());
        assertEquals(Set.of("NextLpv", "PreviousLpv"), reflection.slots().stream().map(ComputeBackend.Slot::name).collect(java.util.stream.Collectors.toSet()));
        assertEquals(4, reflection.localZ());
    }

    @Test void reflectsOneDimensionalIntegerBlockData() {
        var reflection = ComputeBackend.reflect(ComputeBackend.compile("test:block_data", """
            #version 450
            layout(local_size_x=8) in;
            layout(rg32ui) uniform uimage1D BlockData;
            uniform usampler1D Previous;
            void main(){int p=int(gl_GlobalInvocationID.x);imageStore(BlockData,p,texelFetch(Previous,p,0));}
            """));
        assertEquals(2, reflection.slots().size());
        assertTrue(reflection.slots().stream().allMatch(s -> s.dimensions() == 1));
    }

    @Test void expandsIncludesWithCycleProtection() {
        var includes = Map.of("demo:a.glsl", "#include <demo:b.glsl>\nfloat a;", "demo:b.glsl", "float b;", "demo:loop.glsl", "#include <demo:loop.glsl>");
        assertEquals("float b;\n\nfloat a;\n\nvoid main(){}", ComputeBackend.expand("#include <demo:a.glsl>\nvoid main(){}", includes::get));
        assertThrows(IllegalArgumentException.class, () -> ComputeBackend.expand("#include <demo:loop.glsl>", includes::get));
        assertThrows(IllegalArgumentException.class, () -> ComputeBackend.expand("#include <demo:missing.glsl>", includes::get));
    }
}
