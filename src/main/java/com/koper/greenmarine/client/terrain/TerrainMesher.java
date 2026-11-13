package com.koper.greenmarine.client.terrain;

import com.koper.greenmarine.api.render.TerrainCapture;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.chunk.*;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.*;
import net.minecraft.world.level.block.state.BlockState;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Worker-thread capture. All emitted coordinates and UVs follow the host's exact order. */
public final class TerrainMesher {
    private static final ThreadLocal<Build> BUILD=new ThreadLocal<>();
    private static final AtomicLong CAPTURED=new AtomicLong(),REJECTED=new AtomicLong();
    private static final class Build {
        final TerrainCapture.Configuration config;final SectionPos section;
        final IdentityHashMap<Object,ChunkSectionLayer> builders=new IdentityHashMap<>();
        final EnumMap<ChunkSectionLayer,ByteArrayOutputStream> layers=new EnumMap<>(ChunkSectionLayer.class);
        final float[][] fluid=new float[4][5];final int[] ao={255,255,255,255};
        BlockPos block;int id,emission,tint=-1,fluidCount;boolean liquid;
        Build(TerrainCapture.Configuration config,SectionPos section){this.config=config;this.section=section;}
    }
    private TerrainMesher() {}
    public static void invalidate(){TerrainSidecars.invalidate();}
    public static long capturedSections(){return CAPTURED.get();}public static long rejectedSections(){return REJECTED.get();}
    public static void begin(SectionPos section){var c=TerrainCapture.configuration();if(c.enabled())BUILD.set(new Build(c,section));else BUILD.remove();}
    public static void discard(){BUILD.remove();}
    public static void builder(Object builder,ChunkSectionLayer layer){var b=BUILD.get();if(b!=null)b.builders.put(builder,layer);}
    public static void block(BlockPos pos,BlockState state,boolean fluid){var b=BUILD.get();if(b==null)return;b.block=pos.immutable();b.id=b.config.mapping().blockId(state);b.emission=state.getLightEmission();b.liquid=fluid;b.fluidCount=0;b.tint=-1;Arrays.fill(b.ao,255);}
    public static void ambient(QuadInstance quad){var b=BUILD.get();if(b!=null)for(int i=0;i<4;i++)b.ao[i]=quad.getColor(i)&255;}
    public static void flat(){var b=BUILD.get();if(b!=null)Arrays.fill(b.ao,255);}
    public static void whiteTint(){var b=BUILD.get();if(b!=null)b.tint=-1;}
    public static void tint(int tint){var b=BUILD.get();if(b!=null)b.tint=tint;}
    public static void quad(Object builder,float x,float y,float z,BakedQuad quad){
        var b=BUILD.get();if(b==null||b.block==null)return;
        var v=new float[4][5];for(int i=0;i<4;i++){var p=quad.position(i);v[i][0]=p.x()+x;v[i][1]=p.y()+y;v[i][2]=p.z()+z;v[i][3]=UVPair.unpackU(quad.packedUV(i));v[i][4]=UVPair.unpackV(quad.packedUV(i));}
        append(b,builder,v,false);
    }
    public static void fluidVertex(Object builder,float x,float y,float z,float u,float v){
        var b=BUILD.get();if(b==null||!b.liquid||b.block==null)return;
        float[] p=b.fluid[b.fluidCount++];p[0]=x;p[1]=y;p[2]=z;p[3]=u;p[4]=v;
        if(b.fluidCount==4){append(b,builder,b.fluid,true);b.fluidCount=0;}
    }
    private static void append(Build b,Object builder,float[][] vertices,boolean fluid){
        var layer=b.builders.get(builder);if(layer==null)return;
        int[] colors=new int[4];for(int i=0;i<4;i++)colors[i]=(b.tint&0xffffff)|(fluid?255:b.ao[i])<<24;
        var data=TerrainVertex.encodeQuad(vertices,b.block.getX()&15,b.block.getY()&15,b.block.getZ()&15,b.id,fluid?1:-1,colors,b.emission);
        b.layers.computeIfAbsent(layer,k->new ByteArrayOutputStream()).writeBytes(data.array());
    }
    public static void finish(SectionCompiler.Results results){
        var b=BUILD.get();BUILD.remove();if(b==null)return;
        if(b.config.generation()!=TerrainCapture.configuration().generation()){REJECTED.incrementAndGet();return;}
        var layers=new EnumMap<ChunkSectionLayer,TerrainSidecarData.Layer>(ChunkSectionLayer.class);
        for(var e:results.renderedLayers.entrySet()) {
            var records=b.layers.get(e.getKey());int vertices=e.getValue().drawState().vertexCount();
            if(records==null||records.size()!=vertices*TerrainVertex.BYTES){REJECTED.incrementAndGet();return;}
            byte[] host=new byte[0];if(Boolean.getBoolean("greenmarine.terrain.diagnostics")){var source=e.getValue().vertexBuffer().duplicate();host=new byte[source.remaining()];source.get(host);}
            layers.put(e.getKey(),new TerrainSidecarData.Layer(records.toByteArray(),host));
        }
        ((TerrainAttachment)(Object)results).greenmarine$terrainData(new TerrainSidecarData(b.config.generation(),b.section.x(),b.section.y(),b.section.z(),layers));CAPTURED.incrementAndGet();
    }
}
