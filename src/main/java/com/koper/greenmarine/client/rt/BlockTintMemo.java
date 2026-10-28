package com.koper.greenmarine.client.rt;

import java.util.Arrays;
import java.util.function.IntUnaryOperator;

/** A tint source depends on block state, position and tint index, not the quad face. */
final class BlockTintMemo {
    private int[] indices=new int[4],colours=new int[4];
    private int count;
    void reset() {count=0;}
    int colour(int index,IntUnaryOperator lookup) {
        for(int i=0;i<count;i++)if(indices[i]==index)return colours[i];
        int value=lookup.applyAsInt(index);
        if(count==indices.length) {indices=Arrays.copyOf(indices,count*2);colours=Arrays.copyOf(colours,count*2);}
        indices[count]=index;colours[count++]=value;return value;
    }
}
