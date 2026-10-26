package com.koper.greenmarine.development;

/** Shared geometry for rendering, input hit testing and resizable editor groups. */
record EditorLayout(int explorer,Rect first,Rect second,boolean down) {
    record Rect(int x,int y,int width,int height) {
        int right(){return x+width;} int bottom(){return y+height;}
        boolean contains(double px,double py){return px>=x&&px<right()&&py>=y&&py<bottom();}
    }
    static EditorLayout calculate(int width,int height,boolean files,int sidebar,boolean split,boolean down,double ratio) {
        int side=files?Math.clamp(sidebar,80,Math.max(80,Math.min(240,width*2/5))):0;
        int x=side==0?0:side+3,w=Math.max(1,width-x),h=Math.max(1,height-62);
        var area=new Rect(x,44,w,h);
        if(!split)return new EditorLayout(side,area,null,down);
        boolean horizontal=down||w<240;
        int extent=(horizontal?h:w)-3,minimum=horizontal?40:60;
        int first=Math.clamp((int)(extent*ratio),minimum,Math.max(minimum,extent-minimum));
        return horizontal?new EditorLayout(side,new Rect(x,44,w,first),new Rect(x,47+first,w,extent-first),true)
            :new EditorLayout(side,new Rect(x,44,first,h),new Rect(x+first+3,44,extent-first,h),false);
    }
}
