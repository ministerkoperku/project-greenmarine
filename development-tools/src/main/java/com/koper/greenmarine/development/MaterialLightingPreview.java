package com.koper.greenmarine.development;

/** Illustrative CPU-lit cube; this deliberately makes no claim about runtime ray tracing. */
final class MaterialLightingPreview {
    static int[] render(int size,MaterialCanvas.Material material) {
        var pixels=new int[size*size];
        for(int y=0;y<size;y++)for(int x=0;x<size;x++){
            double u=(x-size*.5)/(size*.38),v=(y-size*.10)/(size*.38);
            int face=-1;double textureU=0,textureV=0;
            if(Math.abs(u)+Math.abs(2*v-1)<=1){face=0;textureU=(u+2*v)/2;textureV=(2*v-u)/2;}
            else if(u>=-1&&u<0&&v>=1+u*.5&&v<=2+u*.5){face=1;textureU=u+1;textureV=v-1-u*.5;}
            else if(u>=0&&u<=1&&v>=1-u*.5&&v<=2-u*.5){face=2;textureU=u;textureV=v-1+u*.5;}
            int bg=((x/8+y/8)%2==0)?0xff242426:0xff202022;if(face<0){pixels[y*size+x]=bg;continue;}
            double albedo=((int)(textureU*8)+(int)(textureV*8))%2==0?.53:.60;
            double diffuse=face==0?.82:face==1?.35:.56;
            double highlight=Math.exp(-Math.pow((textureU-.65)*3,2)-Math.pow((textureV-.3)*3,2));
            double specular=material.reflection()*(.1+highlight*(1-material.roughness())*.9);
            double value=Math.min(1,albedo*(.18+diffuse)+specular+material.emission()*.8);
            int r=(int)(value*218),g=(int)(value*228),b=(int)(value*245);
            pixels[y*size+x]=0xff000000|r<<16|g<<8|b;
        }
        return pixels;
    }
}
