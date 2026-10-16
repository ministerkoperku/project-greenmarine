package com.koper.greenmarine.api.pack;

import org.junit.jupiter.api.Test;
import java.nio.*;
import static org.junit.jupiter.api.Assertions.*;

class ShaderBinaryTest {
    private byte[] fixture() throws Exception {
        try(var input=getClass().getResourceAsStream("/assets/greenmarine/greenmarine/spirv/pack_grade.spv")) {
            assertNotNull(input); return input.readAllBytes();
        }
    }
    @Test void acceptsValidatedFragmentModuleAndOwnsItsBytes() throws Exception {
        byte[] source=fixture(); var binary=new ShaderBinary(source); source[0]=0;
        assertEquals(0x07230203,ByteBuffer.wrap(binary.bytes()).order(ByteOrder.LITTLE_ENDIAN).getInt());
        byte[] exposed=binary.bytes(); exposed[0]=0; assertNotEquals(0,binary.bytes()[0]);
    }
    @Test void rejectsMalformedHeadersTruncatedInstructionsAndOtherShaderStages() throws Exception {
        byte[] source=fixture();
        for(int length:new int[]{0,4,19,21,source.length-1}) {
            assertThrows(IllegalArgumentException.class,()->new ShaderBinary(java.util.Arrays.copyOf(source,length)));
        }
        byte[] bad=source.clone(); ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(0,42);
        assertThrows(IllegalArgumentException.class,()->new ShaderBinary(bad));
        byte[] stage=source.clone(); var words=ByteBuffer.wrap(stage).order(ByteOrder.LITTLE_ENDIAN);
        for(int offset=20;offset<stage.length;) {
            int instruction=words.getInt(offset),count=instruction>>>16;
            if((instruction&0xFFFF)==15) { words.putInt(offset+4,0); break; }
            offset+=count*4;
        }
        assertThrows(IllegalArgumentException.class,()->new ShaderBinary(stage));
    }
}
