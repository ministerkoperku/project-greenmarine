package com.koper.greenmarine.development.iris;

import com.koper.greenmarine.development.IrisImporter;
import java.nio.file.*;
import java.util.*;

/** Unrelated original Iris inputs, regenerated through the normal public converter entrypoint. */
public final class AutomaticTerrainFixture {
    public static void main(String[] args) throws Exception {
        Path source=Path.of(args[0]),destination=Path.of(args[1]);
        String stages=args.length>2?args[2]:"none";
        Files.createDirectories(source.resolve("shaders"));
        Files.writeString(source.resolve("shaders/block.properties"),"block.37=stone\nblock.73=oak_leaves\nblock.111=water\n");
        int variant=0;
        for(String name:List.of("gbuffers_terrain_solid","gbuffers_terrain_cutout","gbuffers_water")) {
            variant++;
            Files.writeString(source.resolve("shaders/"+name+".vsh"),"""
                #version 120
                attribute vec4 mc_Entity;varying float material;varying vec2 uv;
                void main(){gl_Position=ftransform();material=mc_Entity.x;uv=(gl_TextureMatrix[0]*gl_MultiTexCoord0).xy;}
                """);
            Files.writeString(source.resolve("shaders/"+name+".fsh"),"""
                #version 120
                /* RENDERTARGETS: 0,1 */
                /*
                const int colortex0Format = RGBA32F;
                const int colortex1Format = RGBA32F;
                */
                const vec4 colortex0ClearColor=vec4(0.);
                uniform sampler2D gtexture;uniform sampler2D lightmap;
                varying float material;varying vec2 uv;
                void main(){
                    vec4 texel=texture2D(gtexture,uv);
                    vec4 light=texture2D(lightmap,vec2(.5));
                    float alpha=ALPHA;
                    gl_FragData[0]=vec4(material/255.,VARIANT,texel.b*.01+light.b*.01,alpha);
                    gl_FragData[1]=vec4(texel.rgb,light.b);
                }
                """.replace("VARIANT",Float.toString(variant*.25f)).replace("ALPHA",variant==2?"(mod(floor(gl_FragCoord.x),8.)<4. ? .05 : 1.)":"1."));
        }
        if(stages.equals("depth"))addDepth(source);
        else if(stages.equals("sampled-depth"))addSampledDepth(source);
        else if(!stages.equals("none"))addStages(source,stages);
        Files.writeString(source.resolve("shaders/final.vsh"),"#version 120\nvarying vec2 uv;void main(){gl_Position=ftransform();uv=gl_MultiTexCoord0.xy;}");
        Files.writeString(source.resolve("shaders/final.fsh"),"#version 120\nuniform sampler2D colortex0;varying vec2 uv;void main(){gl_FragColor=texture2D(colortex0,uv);}");
        var report=IrisImporter.convert(source,destination);
        if(!report.runnable())throw new IllegalStateException(String.join("\n",report.diagnostics()));
        System.out.println("Converted original terrain fixture: "+destination);
    }
    private static void addSampledDepth(Path source) throws Exception {
        Files.writeString(source.resolve("shaders/shaders.properties"),"image.sampledDepthProof=none RGBA RGBA32F FLOAT false true 1.0 1.0\n");
        Files.writeString(source.resolve("shaders/composite4.csh"),"""
            #version 450
            layout(local_size_x=8,local_size_y=8,local_size_z=1) in;
            layout(rgba32f) uniform writeonly image2D sampledDepthProof;
            uniform sampler2D depthtex0,depthtex1,depthtex2;
            void main(){ivec2 p=ivec2(gl_GlobalInvocationID.xy);if(any(greaterThanEqual(p,imageSize(sampledDepthProof))))return;
                imageStore(sampledDepthProof,p,vec4(texelFetch(depthtex0,p,0).r,texelFetch(depthtex1,p,0).r,texelFetch(depthtex2,p,0).r,1.));}
            """);
        for(String name:List.of("gbuffers_terrain_solid","gbuffers_terrain_cutout","gbuffers_water")) {
            Path fragment=source.resolve("shaders/"+name+".fsh");
            boolean water=name.equals("gbuffers_water");
            String text=Files.readString(fragment).replace("#version 120","#version 450")
                .replace("RENDERTARGETS: 0,1","RENDERTARGETS: 0,1,"+(water?"3":"2"))
                .replace("const int colortex1Format = RGBA32F;","const int colortex1Format = RGBA32F;\nconst int colortex2Format = RGBA32F;\nconst int colortex3Format = RGBA32F;")
                .replace("void main()",(water?"uniform sampler2D depthtex0,depthtex1,depthtex2;\n":"")+"void main()")
                .replace("gl_FragData[1]=vec4(texel.rgb,light.b);","gl_FragData[1]=vec4(texel.rgb,light.b);\n"+(water
                    ?"gl_FragData[2]=vec4(texelFetch(depthtex0,ivec2(gl_FragCoord.xy),0).r,texelFetch(depthtex1,ivec2(gl_FragCoord.xy),0).r,texelFetch(depthtex2,ivec2(gl_FragCoord.xy),0).r,1.);"
                    :"gl_FragData[2]=vec4(gl_FragCoord.z,0.,0.,1.);"));
            Files.writeString(fragment,text);
        }
        for(String pass:List.of("prepare","deferred","composite4")) {
            int target=switch(pass){case "prepare"->5;case "deferred"->6;default->4;};
            Files.writeString(source.resolve("shaders/"+pass+".vsh"),"#version 450\nvoid main(){gl_Position=ftransform();}");
            Files.writeString(source.resolve("shaders/"+pass+".fsh"),"""
                #version 450
                /* RENDERTARGETS: SLOT */
                /*
                const int colortexSLOTFormat = RGBA32F;
                */
                uniform sampler2D depthtex0,depthtex1,depthtex2;
                void main(){ivec2 p=ivec2(gl_FragCoord.xy);vec2 uv=gl_FragCoord.xy/vec2(textureSize(depthtex0,0));
                    gl_FragData[0]=vec4(texelFetch(depthtex0,p,0).r,textureLod(depthtex1,uv,0.).r,texture(depthtex2,uv).r,1.);}
                """.replace("SLOT",Integer.toString(target)));
        }
    }
    private static void addDepth(Path source) throws Exception {
        Files.writeString(source.resolve("shaders/composite4.vsh"),"#version 450\nvoid main(){gl_Position=ftransform();gl_Position.z=-.5*gl_Position.w;}");
        Files.writeString(source.resolve("shaders/composite4.fsh"),"""
            #version 450
            /* RENDERTARGETS: 3 */
            /*
            const int colortex3Format = RGBA32F;
            */
            const vec4 colortex3ClearColor=vec4(0.);
            void main(){gl_FragDepth=gl_FragCoord.z+.125;gl_FragData[0]=vec4(gl_FragCoord[2],.25,gl_FragDepth,1.);}
            """);
        for(String name:List.of("gbuffers_terrain_solid","gbuffers_terrain_cutout","gbuffers_water")) {
            Path vertex=source.resolve("shaders/"+name+".vsh"),fragment=source.resolve("shaders/"+name+".fsh");
            Files.writeString(vertex,Files.readString(vertex).replace("#version 120","#version 450")
                .replace("void main()","noperspective varying float expectedDepth;\nvoid main()")
                .replace("material=mc_Entity.x;","expectedDepth=.5*(gl_Position.z/gl_Position.w+1.);material=mc_Entity.x;"));
            String text=Files.readString(fragment).replace("#version 120","#version 450")
                .replace("#version 450","#version 450\nlayout(depth_greater) out float gl_FragDepth;")
                .replace("RENDERTARGETS: 0,1","RENDERTARGETS: 0,1,2")
                .replace("const int colortex1Format = RGBA32F;","const int colortex1Format = RGBA32F;\nconst int colortex2Format = RGBA32F;")
                .replace("void main()","noperspective varying float expectedDepth;\nvoid writeDepth(out float d){d=gl_FragCoord[2]+.00003;}\nvoid main()")
                .replace("gl_FragData[1]=vec4(texel.rgb,light.b);","""
                    gl_FragData[1]=vec4(texel.rgb,light.b);
                    writeDepth(gl_FragDepth);
                    gl_FragData[2]=vec4(gl_FragCoord.z,expectedDepth,gl_FragDepth,1.);
                    if(mod(floor(gl_FragCoord.x),2.)<1.)return;
                    gl_FragDepth+=.00002;gl_FragData[2].z=gl_FragDepth;
                    """);
            Files.writeString(fragment,text);
        }
    }
    private static void addStages(Path source,String mode) throws Exception {
        boolean tess=mode.startsWith("tessellation");
        if(!tess && !mode.equals("geometry"))throw new IllegalArgumentException("Unknown stage fixture: "+mode);
        Files.writeString(source.resolve("shaders/shaders.properties"),"uniform.float.stageGain=0.125\n");
        String fields="{float material;vec2 uv;float stageProof;}";
        for(String name:List.of("gbuffers_terrain_solid","gbuffers_terrain_cutout","gbuffers_water")) {
            Files.writeString(source.resolve("shaders/"+name+".vsh"),"""
                #version 400
                in vec4 mc_Entity;out VertexData FIELDS v;
                void main(){gl_Position=ftransform();v.material=mc_Entity.x;
                    v.uv=(gl_TextureMatrix[0]*gl_MultiTexCoord0).xy;v.stageProof=0.;}
                """.replace("FIELDS",fields));
            if(tess) {
                Files.writeString(source.resolve("shaders/"+name+".tcs"),"""
                    #version 400
                    layout(vertices=CONTROL_VERTICES) out;uniform float stageGain;
                    in VertexData FIELDS v[];out ControlData FIELDS c[];
                    void main(){c[gl_InvocationID].material=v[gl_InvocationID%3].material;c[gl_InvocationID].uv=v[gl_InvocationID%3].uv;
                        c[gl_InvocationID].stageProof=v[gl_InvocationID%3].stageProof+stageGain;
                        gl_out[gl_InvocationID].gl_Position=gl_in[gl_InvocationID%3].gl_Position;
                        if(gl_InvocationID==0){gl_TessLevelOuter[0]=1.;gl_TessLevelOuter[1]=1.;gl_TessLevelOuter[2]=1.;gl_TessLevelInner[0]=1.;}}
                    """.replace("FIELDS",fields).replace("CONTROL_VERTICES",mode.equals("tessellation4")?"4":"3"));
                Files.writeString(source.resolve("shaders/"+name+".tes"),"""
                    #version 400
                    layout(triangles,equal_spacing,ccw) in;uniform float stageGain;
                    in ControlData FIELDS c[];out EvalData FIELDS e;
                    void main(){e.material=c[0].material;e.uv=c[0].uv*gl_TessCoord.x+c[1].uv*gl_TessCoord.y+c[2].uv*gl_TessCoord.z;
                        e.stageProof=c[CONTROL_PROOF_INDEX].stageProof+2.*stageGain;
                        gl_Position=gl_in[0].gl_Position*gl_TessCoord.x+gl_in[1].gl_Position*gl_TessCoord.y+gl_in[2].gl_Position*gl_TessCoord.z;}
                    """.replace("FIELDS",fields).replace("CONTROL_PROOF_INDEX",mode.equals("tessellation4")?"3":"0"));
            }
            Files.writeString(source.resolve("shaders/"+name+".gsh"),"""
                #version 400
                layout(triangles) in;layout(triangle_strip,max_vertices=3) out;uniform float stageGain;
                in INPUT FIELDS v[];out GeometryData FIELDS g;
                void main(){for(int i=0;i<3;i++){g.material=v[i].material;g.uv=v[i].uv;g.stageProof=v[i].stageProof+4.*stageGain;
                    gl_Position=gl_in[i].gl_Position;EmitVertex();}EndPrimitive();}
                """.replace("FIELDS",fields).replace("INPUT",tess?"EvalData":"VertexData"));
            Path fragment=source.resolve("shaders/"+name+".fsh");
            String text=Files.readString(fragment).replace("#version 120","#version 400")
                .replace("const int colortex1Format = RGBA32F;","const int colortex1Format = RGBA32F;\nconst int colortex2Format = RGBA16F;")
                .replace("RENDERTARGETS: 0,1","RENDERTARGETS: 0,1,2")
                .replace("varying float material;varying vec2 uv;","in GeometryData "+fields+" g;")
                .replace("texture2D(gtexture,uv)","texture2D(gtexture,g.uv)")
                .replace("vec4(material/255.","vec4(g.material/255.")
                .replace("gl_FragData[1]=vec4(texel.rgb,light.b);","gl_FragData[1]=vec4(texel.rgb,light.b);gl_FragData[2]=vec4(g.stageProof,0.,0.,1.);");
            Files.writeString(fragment,text);
        }
    }
}
