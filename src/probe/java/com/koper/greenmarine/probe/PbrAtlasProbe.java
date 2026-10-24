package com.koper.greenmarine.probe;

import com.koper.greenmarine.client.world.PbrAtlas;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.*;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Production stitching and GLSL decoding, including map removal and restoration on resource reload. */
final class PbrAtlasProbe {
    private static int phase;
    private static PbrAtlas atlas;
    private static CompletableFuture<Void> reload;
    private static final Map<Path,byte[]> saved=new LinkedHashMap<>();
    static void run(Minecraft client,Path output) {
        if(phase==3)return;
        try {
            if(atlas==null) {
                Files.writeString(output.resolve("pbr-result.json"),"{\"status\":\"running\"}\n");
                Files.deleteIfExists(output.resolve("pbr-failed.txt"));
                // Exercise the actual world-program owner and its resource-reload reset hooks.
                var field=com.koper.greenmarine.client.world.WorldPrograms.class.getDeclaredField("PBR");
                field.setAccessible(true);atlas=(PbrAtlas)field.get(null);
            }
            if(reload!=null) {if(!reload.isDone())return;reload.join();reload=null;}
            sample(client,output,phase!=1);
            var run=net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir();
            if(phase==0) {
                if(!Files.isRegularFile(run.resolve(".greenmarine-pbr-probe")))throw new IllegalStateException("Prepare an isolated fixture with tools/prepare-pbr-probe.py");
                for(String name:List.of("stone_s.png","stone_n.png")) {
                    var path=run.resolve("resourcepacks/pbr-fixture/assets/minecraft/textures/block/"+name);
                    saved.put(path,Files.readAllBytes(path));Files.delete(path);
                }
            } else if(phase==1) restore();
            phase++;
            if(phase<3) {reload=client.reloadResourcePacks();return;}
            Files.writeString(output.resolve("pbr-result.json"),"{\"status\":\"passed\",\"stages\":[\"mapped\",\"removed\",\"restored\"]}\n");
            client.stop();
        } catch(Exception e) {
            phase=3;com.koper.greenmarine.GreenMarine.LOGGER.error("LabPBR probe failed",e);
            try{restore();Files.writeString(output.resolve("pbr-result.json"),"{\"status\":\"failed\"}\n");Files.writeString(output.resolve("pbr-failed.txt"),e.toString());}catch(Exception restoreError){e.addSuppressed(restoreError);}
            client.stop();
        }
    }
    private static void restore() throws java.io.IOException {for(var entry:saved.entrySet())Files.write(entry.getKey(),entry.getValue());}
    private static void sample(Minecraft client,Path output,boolean mapped) throws Exception {
        {
            var device=RenderSystem.getDevice();var encoder=device.createCommandEncoder();
            var specular=atlas.specular(encoder);var normal=atlas.normal(encoder);var presence=atlas.presence(encoder);
            var blocks=client.getAtlasManager().getAtlasOrThrow(net.minecraft.data.AtlasIds.BLOCKS);
            var sprite=blocks.getSprite(Identifier.parse("minecraft:block/stone"));
            var dirt=blocks.getSprite(Identifier.parse("minecraft:block/dirt"));
            boolean addonFixture=Files.isRegularFile(net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve(".greenmarine-material-addon-probe"));
            float u=(sprite.getU0()+sprite.getU1())*.5f,v=(sprite.getV0()+sprite.getV1())*.5f;
            float halfU=.5f/blocks.getTexture().getWidth(0),halfV=.5f/blocks.getTexture().getHeight(0);
            var pipeline=RenderPipeline.builder().withLocation(Identifier.parse("greenmarine:probe_pbr"))
                .withVertexShader(Identifier.parse("greenmarine:probe_pbr")).withFragmentShader(Identifier.parse("greenmarine:probe_pbr"))
                .withBindGroupLayout(BindGroupLayout.builder().withUniform("MarinePbrSpecular",UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("MarinePbrNormal",UniformType.COMBINED_IMAGE_SAMPLER).withUniform("MarinePbrPresence",UniformType.COMBINED_IMAGE_SAMPLER).build())
                .withColorTargetState(new ColorTargetState(Optional.empty(),GpuFormat.RGBA8_UNORM,ColorTargetState.WRITE_ALL))
                .withDepthStencilState(Optional.empty()).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).build();
            String body="void main(){vec2 uv=vec2("+u+","+v+");int x=int(gl_FragCoord.x);"
                +"if(x==5)uv=vec2("+(sprite.getU0()+halfU)+","+(sprite.getV0()+halfV)+");"
                +"if(x==6)uv=vec2("+(sprite.getU1()-halfU)+","+(sprite.getV1()-halfV)+");"
                +"if(x==7)uv=vec2("+((dirt.getU0()+dirt.getU1())*.5f)+","+((dirt.getV0()+dirt.getV1())*.5f)+");"
                +"MarinePbr p=marinePbrMaterial(uv);color=vec4(p.present?1:0,p.roughness,p.metal?1:0,1);"
                +"if(x==1)color=texture(MarinePbrSpecular,uv);if(x==2)color=vec4(vec2(textureSize(MarinePbrSpecular,0))/2048,0,1);"
                +"if(x==3){vec4 n=marinePbrNormal(uv);color=vec4(n.xy*.5+.5,n.w,1);}if(x==4)color=vec4(float(marinePbrMaps(uv)&3)/3,0,0,1);if(x==8)color=marineInferredMaterial(uv,vec4(.96,0,0,1),vec3(.5))*vec4(1,1,1,1.0/9.0);"
                +"if(x>=9){if(x>=10)uv=vec2("+(sprite.getU0()+halfU)+","+(sprite.getV0()+halfV)+");"
                +"color=x==11?marineInferredMaterial(uv,vec4(.96,0,0,1),vec3(.5))*vec4(1,1,1,1.0/9.0):marineAddonProperties(uv)*vec4(1,1,1,1.0/255.0);}}";
            try(var sources=new Sources(client,body);var compiled=device.compilePipeline(pipeline,sources,Runnable::run).join().finishCompile();
                var texture=device.createTexture("LabPBR probe",GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_COPY_SRC,GpuFormat.RGBA8_UNORM,12,1,1,1);
                var view=device.createTextureView(texture);
                var readback=device.createBuffer(()->"LabPBR probe readback",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,48)) {
                if(compiled==null)throw new IllegalStateException("LabPBR fixture shader did not compile");
                try(var pass=encoder.createRenderPass(()->"LabPBR probe",view,Optional.empty())) {
                    pass.setPipeline(compiled);var sampler=RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
                    pass.setUniform("MarinePbrSpecular",specular,sampler);pass.setUniform("MarinePbrNormal",normal,sampler);pass.setUniform("MarinePbrPresence",presence,sampler);pass.draw(3,1,0,0);
                }
                encoder.copyTextureToBuffer(texture,readback,0,()->{},0);
                try(var fence=encoder.createFence()) {encoder.submit();if(!fence.awaitCompletion(10_000_000_000L))throw new IllegalStateException("Readback timeout");}
                try(var pixels=readback.map(true,false)) {
                    var data=pixels.data();int[] values=new int[48];for(int i=0;i<48;i++)values[i]=Byte.toUnsignedInt(data.get(i));
                    String result="maps="+atlas.specularMaps()+"/"+atlas.normalMaps()+", size="+specular.getWidth(0)+"x"+specular.getHeight(0)+", pixels="+Arrays.toString(values);
                    Files.writeString(output.resolve("pbr-"+phase+".txt"),result);com.koper.greenmarine.GreenMarine.LOGGER.info("LabPBR probe phase {}: {}",phase,result);
                    int present=mapped?255:0;
                    if(values[0]!=present || values[2]!=present || values[20]!=present || values[24]!=present || values[28]!=0
                        || values[16]!=present || values[14]!=255 || Math.abs(values[1]-(mapped?16:255))>1
                        || atlas.specularMaps()!=(mapped?1:0) || atlas.normalMaps()!=(mapped?1:0)
                        || specular.getWidth(0)<=1 || Math.abs(values[32]-(mapped || addonFixture?245:232))>1 || values[35]!=28)throw new IllegalStateException("LabPBR atlas mismatch: "+result);
                    if(addonFixture) {
                        if(values[36]!=(mapped?0:204) || values[37]!=(mapped?0:13) || values[38]!=(mapped?0:31)
                            || values[39]!=(mapped?0:9) || values[43]!=0 || Math.abs(values[44]-(mapped?245:232))>1)
                            throw new IllegalStateException("Addon precedence/mask-hole mismatch: "+result);
                    }
                }
            }
        }
    }
    private static final class Sources implements ShaderSource {
        private final Minecraft client;private final String body;
        private final List<CachedIncludeSource> includes=new ArrayList<>();
        Sources(Minecraft client,String body) {this.client=client;this.body=body;}
        public String getShader(Identifier id,ShaderType type) {
            return type==ShaderType.VERTEX?"#version 450\nvoid main(){vec2 uv=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);gl_Position=vec4(uv*2-1,0,1);}":
                "#version 450\n#include <greenmarine:labpbr.glsl>\nlayout(location=0) out vec4 color;"+body;
        }
        public CachedIncludeSource getInclude(Identifier id) {
            try(var in=client.getResourceManager().getResourceOrThrow(id.withPath("shaders/include/"+id.getPath())).open()) {
                var source=CachedIncludeSource.create(id,new String(in.readAllBytes(),StandardCharsets.UTF_8));includes.add(source);return source;
            }catch(Exception e){throw new IllegalStateException(e);}
        }
        public void close() {includes.forEach(CachedIncludeSource::close);}
    }
}
