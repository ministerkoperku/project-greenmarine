package com.koper.greenmarine.development;

/** Area coverage keeps single-texel masks visible even below one screen pixel. */
final class MaterialPreview {
    static int[] render(MaterialCanvas canvas,int size,int vx,int vy,int vw,int vh,int[] albedo,int aw,int ah,boolean maskOnly) {
        int[] result=new int[size*size];
        for(int y=0;y<size;y++)for(int x=0;x<size;x++) {
            int cx=vx+x*vw/size,cy=vy+y*vh/size;
            int background=((cx/4+cy/4)%2==0)?0xff243144:0xff304158;
            if(!maskOnly&&albedo!=null)background=0xff000000|albedo[(cy*ah/canvas.height())*aw+cx*aw/canvas.width()]&0xffffff;
            int mask=0;
            int right=Math.min(canvas.width(),vx+Math.max(x*vw/size+1,(x+1)*vw/size));
            int bottom=Math.min(canvas.height(),vy+Math.max(y*vh/size+1,(y+1)*vh/size));
            for(int sy=cy;sy<bottom;sy++)for(int sx=cx;sx<right;sx++)if(canvas.pixel(sx,sy)!=0)mask=canvas.pixel(sx,sy);
            result[y*size+x]=mask==0?background:maskOnly?mask:blend(background,mask);
        }
        return result;
    }
    private static int blend(int a,int b){return 0xff000000|((((a>>16&255)+(b>>16&255))/2)<<16)|((((a>>8&255)+(b>>8&255))/2)<<8)|((a&255)+(b&255))/2;}
}
