package com.koper.greenmarine.development;

final class CompletionPopup {
    record Layout(int x,int y,int width,int height,int rows){static final int ROW=16,HELP=24;}
    static Layout layout(int caretX,int caretY,int width,int height,int choices){
        int rows=Math.max(1,Math.min(Math.min(6,choices),Math.max(1,(height-36)/16)));
        int w=Math.min(248,Math.max(40,width-8)),h=rows*16+24;
        int x=Math.clamp(caretX+2,4,Math.max(4,width-w-4)),y=caretY+13;
        if(y+h>height-4)y=caretY-h-3;y=Math.clamp(y,4,Math.max(4,height-h-4));
        return new Layout(x,y,w,h,rows);
    }
}
