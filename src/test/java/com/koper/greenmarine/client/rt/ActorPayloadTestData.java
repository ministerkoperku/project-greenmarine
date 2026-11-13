package com.koper.greenmarine.client.rt;

import java.nio.ByteBuffer;
import org.joml.Matrix4f;
import org.joml.Vector4f;

final class ActorPayloadTestData {
    static Matrix4f matrix(ByteBuffer data,int offset) {
        var values=new float[16];
        for(int i=0;i<16;i++)values[i]=data.getFloat(offset+i*4);
        return new Matrix4f().set(values);
    }
    static Vector4f vector(ByteBuffer data,int offset) {
        return new Vector4f(data.getFloat(offset),data.getFloat(offset+4),data.getFloat(offset+8),data.getFloat(offset+12));
    }
}
