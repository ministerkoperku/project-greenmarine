package com.koper.greenmarine.api.material;

import java.nio.*;
import java.util.*;
import java.io.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class MaterialAddonsTest {
    private static String json(String id,String packs,String texture) {
        return "{\"schema\":1,\"id\":\"test:"+id+"\","+packs+"\"textures\":{\"test:textures/robot.png\":"+texture+"}}";
    }
    private static final String METAL="{\"surface\":\"metal\",\"roughness\":0.3,\"reflection\":0.6,\"emission\":0}";
    private static final String SCREEN="{\"surface\":\"coated\",\"roughness\":0.8,\"reflection\":0.05,\"emission\":0.12}";
    private static MaterialAddons.Source source(String id,String json) {return new MaterialAddons.Source(id,json,key->null,null);}
    @AfterEach void clear() {MaterialAddons.reload(List.of());MaterialAddons.setActivePackId("greenmarine:none");}
    @Test void compilesPackedReadOnlyRgbaAndResolvesTextureAliases() {
        MaterialAddons.reload(List.of(source("metal",json("metal","","{\"default\":"+METAL+"}"))));
        var compiled=MaterialAddons.resolve("test:robot","greenmarine:solstice_rt",2,1).orElseThrow();
        assertEquals(2,compiled.width());assertEquals(1,compiled.height());
        var data=compiled.pixels();assertTrue(data.isReadOnly());assertEquals(8,data.remaining());
        assertEquals(77,Byte.toUnsignedInt(data.get(0)));assertEquals(153,Byte.toUnsignedInt(data.get(1)));
        assertEquals(0,data.get(2));assertEquals(2,data.get(3));
        assertThrows(ReadOnlyBufferException.class,()->data.put(0,(byte)0));
        data.position(4);assertEquals(0,compiled.pixels().position());
        assertEquals(MaterialAddons.generation(),compiled.revision());
        assertTrue(MaterialAddons.resolve("other:robot","greenmarine:solstice_rt",2,1).isEmpty());
    }
    @Test void masksPreserveFallbackAndMapOnlyPalettePixels() throws Exception {
        var image=new BufferedImage(2,1,BufferedImage.TYPE_INT_ARGB);image.setRGB(0,0,0xff123456);
        var output=new ByteArrayOutputStream();ImageIO.write(image,"PNG",output);
        String definition=json("mask","","{\"mask\":\"test:greenmarine/masks/screen.png\",\"palette\":{\"123456\":"+SCREEN+"}}");
        MaterialAddons.reload(List.of(new MaterialAddons.Source("mask",definition,k->output.toByteArray(),null)));
        var data=MaterialAddons.resolve("test:textures/robot.png","test:pack",4,1).orElseThrow().pixels();
        assertEquals(204,Byte.toUnsignedInt(data.get(0)));assertEquals(13,Byte.toUnsignedInt(data.get(1)));
        assertEquals(31,Byte.toUnsignedInt(data.get(2)));assertEquals(9,data.get(3));
        assertEquals(9,data.get(7));assertEquals(0,data.get(11));assertEquals(0,data.get(15));
    }
    @Test void specificOverridesGenericAndBrokenAddonIsIsolated() {
        var generic=source("generic",json("generic","","{\"default\":"+METAL+"}"));
        var specific=source("specific",json("specific","\"shaderpacks\":[\"test:pack\"],","{\"default\":"+SCREEN+"}"));
        var errors=MaterialAddons.reload(List.of(specific,source("broken","{bad"),generic));
        assertEquals(1,errors.size());
        assertEquals(9,MaterialAddons.resolve("test:robot","test:pack",1,1).orElseThrow().pixels().get(3));
        assertEquals(2,MaterialAddons.resolve("test:robot","test:other",1,1).orElseThrow().pixels().get(3));
        long before=MaterialAddons.generation();MaterialAddons.setActivePackId("test:pack");
        assertEquals("test:pack",MaterialAddons.activePackId());assertTrue(MaterialAddons.generation()>before);
        MaterialAddons.reload(List.of());assertTrue(MaterialAddons.resolve("test:robot","test:pack",1,1).isEmpty());
    }
    @Test void aggregateMaskLimitStopsDecodingBeforeLaterTextures() throws Exception {
        var image=new BufferedImage(1024,1024,BufferedImage.TYPE_INT_ARGB);
        var encoded=new ByteArrayOutputStream();ImageIO.write(image,"PNG",encoded);
        var textures=new StringJoiner(",");
        for(int i=0;i<18;i++)textures.add("\"test:textures/"+i+".png\":{\"mask\":\"test:mask.png\"}");
        String definition="{\"schema\":1,\"id\":\"test:oversized\",\"textures\":{"+textures+"}}";
        var reads=new java.util.concurrent.atomic.AtomicInteger();
        var errors=MaterialAddons.reload(List.of(new MaterialAddons.Source("oversized",definition,id->{reads.incrementAndGet();return encoded.toByteArray();},null)));
        assertEquals(1,errors.size());assertTrue(errors.getFirst().contains("64 MiB"));
        assertTrue(reads.get()<=17,"stop at first mask exceeding remaining decoded budget");
        assertTrue(MaterialAddons.resolve("test:0","test:pack",1,1).isEmpty());
    }
    @Test void validatesBoundsAndScopesShaderpackOwnedSources() {
        String good=json("owned","","{\"default\":"+METAL+"}");
        MaterialAddons.reload(List.of(new MaterialAddons.Source("owned",good,k->null,"test:owner")));
        assertTrue(MaterialAddons.resolve("test:robot","test:other",1,1).isEmpty());
        assertTrue(MaterialAddons.resolve("test:robot","test:owner",1,1).isPresent());
        assertThrows(IllegalArgumentException.class,()->MaterialAddons.resolve("test:robot","test:owner",0,1));
        assertThrows(IllegalArgumentException.class,()->MaterialAddons.resolve("test:robot","test:owner",4097,4097));
        assertEquals(1,MaterialAddons.reload(List.of(source("bad",good.replace("0.3","1.3")))).size());
    }
}
