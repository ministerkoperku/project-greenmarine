package com.koper.greenmarine.client.rt;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.lang.reflect.*;
import java.nio.*;
import java.util.*;

/** Optional public-API bridge. No KAvatar/KoperLib symbols enter the base linkage graph. */
final class PlayerReflectionKAvatar {
    record TextureSpan(Identifier texture,int first,int boxes) {}
    record Result(ByteBuffer data,List<TextureSpan> textures,int boxes,String detail) {
        Identifier texture() {return textures.isEmpty()?null:textures.getFirst().texture();}
    }
    record ModelCapture(PlayerReflectionGeometry.Builder geometry,String error) {}
    private record Box(int bone,ModelPart.Cube cube,Matrix4f rotation) {}
    private record Prepared(int bones,List<Box> boxes,String error) {}
    private static final Map<Object,Prepared> PREPARED=new WeakHashMap<>();
    private static final ClassValue<Map<String,Field>> FIELDS=new ClassValue<>() {
        @Override protected Map<String,Field> computeValue(Class<?> type) {
            var fields=new HashMap<String,Field>();for(var field:type.getFields())fields.put(field.getName(),field);return Map.copyOf(fields);
        }
    };
    private static final ClassValue<Map<String,Method>> GETTERS=new ClassValue<>() {
        @Override protected Map<String,Method> computeValue(Class<?> type) {
            var methods=new HashMap<String,Method>();
            for(var method:type.getMethods())if(method.getParameterCount()==0)methods.put(method.getName(),method);
            return Map.copyOf(methods);
        }
    };
    private static final int[][] NORMAL={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
    private static final int[][] U={{0,0,-1},{0,0,1},{1,0,0},{1,0,0},{-1,0,0},{1,0,0}};
    private static final int[][] V={{0,1,0},{0,1,0},{0,0,1},{0,0,-1},{0,1,0},{0,1,0}};
    private static final int[][] CORNERS={{0,0},{1,0},{1,1},{0,1}};
    private PlayerReflectionKAvatar() {}
    private static Object field(Object target,String name) throws ReflectiveOperationException {var field=FIELDS.get(target.getClass()).get(name);if(field==null)throw new NoSuchFieldException(name);return field.get(target);}
    private static Object call(Object target,String name) throws ReflectiveOperationException {var method=GETTERS.get(target.getClass()).get(name);if(method==null)throw new NoSuchMethodException(name);return method.invoke(target);}
    private static String failure(Throwable error) {
        while(error instanceof InvocationTargetException invocation && invocation.getCause()!=null)error=invocation.getCause();
        return error.getClass().getSimpleName()+": "+error.getMessage();
    }
    private static ModelCapture invalid(String error) {return new ModelCapture(new PlayerReflectionGeometry.Builder(),error);}

    /** Atomic part conversion: a malformed or oversized part publishes zero boxes. */
    static ModelCapture captureModel(Object model,float[] matrices,Matrix4fc rawToWorld) {return captureModel(model,matrices,rawToWorld,16);}
    static ModelCapture captureModel(Object model,float[] matrices,Matrix4fc rawToWorld,int capacity) {
        try {
            var prepared=PREPARED.get(model);
            if(prepared==null) {prepared=prepare(model);PREPARED.put(model,prepared);}
            if(prepared.error()!=null)return invalid(prepared.error());
            if(prepared.boxes().size()>capacity)return invalid("KAvatar part has "+prepared.boxes().size()+" visible cubes; limit "+capacity+" (part omitted in full)");
            if(matrices==null || matrices.length!=prepared.bones()*16 || !rawToWorld.isFinite())return invalid("Invalid KAvatar pose matrix count or root");
            for(float value:matrices)if(!Float.isFinite(value))return invalid("Nonfinite KAvatar pose");
            var builder=new PlayerReflectionGeometry.Builder(capacity);
            for(var box:prepared.boxes()) {
                // Builder consumes ModelPart coordinates divided by16; cancel that extra scale.
                var pose=new Matrix4f(rawToWorld).mul(new Matrix4f().set(matrices,box.bone()*16)).mul(box.rotation()).scale(16);
                if(!builder.add(box.cube(),pose))return invalid("Invalid KAvatar cube pose or bounds");
            }
            return new ModelCapture(builder,null);
        } catch(ReflectiveOperationException | RuntimeException | LinkageError unsupported) {
            return invalid("Unsupported KAvatar model: "+failure(unsupported));
        }
    }
    private static Prepared prepare(Object model) throws ReflectiveOperationException {
        var bones=(List<?>)field(model,"bones");
        for(var mesh:(List<?>)field(model,"meshes"))if(((int[])field(mesh,"indices")).length>0)
            return new Prepared(bones.size(),List.of(),"KAvatar triangle meshes are not supported by the 16-box actor ABI");
        int count=0;
        for(var bone:bones)if(renderable(bone))count+=((List<?>)field(bone,"cubes")).size();
        if(count>64)return new Prepared(bones.size(),List.of(),"KAvatar part has "+count+" visible cubes; limit 64 (part omitted in full)");
        int width=(int)field(model,"texWidth"),height=(int)field(model,"texHeight");
        if(width<=0)width=64;if(height<=0)height=64;
        var boxes=new ArrayList<Box>();
        for(int index=0;index<bones.size();index++) {
            var bone=bones.get(index);if(!renderable(bone))continue;
            for(var cube:(List<?>)field(bone,"cubes")) {
                var pivot=(float[])field(cube,"pivot");var q=(float[])field(cube,"rotation");
                var rotation=new Matrix4f().translation(pivot[0],pivot[1],pivot[2])
                    .rotate(new Quaternionf(q[0],q[1],q[2],q[3])).translate(-pivot[0],-pivot[1],-pivot[2]);
                boxes.add(new Box(index,cube(cube,width,height),rotation));
            }
        }
        return new Prepared(bones.size(),List.copyOf(boxes),null);
    }
    private static boolean renderable(Object bone) throws ReflectiveOperationException {
        return (boolean)field(bone,"visible") && !String.valueOf(field(bone,"name")).toLowerCase(Locale.ROOT).contains("hitbox");
    }
    /** KodelModelRender's public model format: px,nx,py,ny,pz,nz; top-down UVs. */
    private static ModelPart.Cube cube(Object source,int texWidth,int texHeight) throws ReflectiveOperationException {
        var origin=((float[])field(source,"origin")).clone();var rawSize=(float[])field(source,"size");var size=rawSize.clone();
        float inflate=(float)field(source,"inflate");var faces=(Object[])field(source,"faces");
        for(int axis=0;axis<3;axis++) {origin[axis]-=inflate;size[axis]+=2*inflate+(rawSize[axis]==0?.16f:0);}
        // Only this new cube is modified; no imported or Minecraft renderer geometry is mutated.
        var out=new ModelPart.Cube(0,0,0,0,0,1,1,1,0,0,0,false,texWidth,texHeight,EnumSet.allOf(net.minecraft.core.Direction.class));
        for(int face=0;face<6;face++) {
            if(faces[face]==null)throw new IllegalArgumentException("KAvatar open cubes need a face mask; unsupported actor ABI");
            Object uv=faces[face];float u=(float)field(uv,"u"),v=(float)field(uv,"v"),uw=(float)field(uv,"uw"),vh=(float)field(uv,"vh");
            int rotation=Math.floorMod((int)field(uv,"rot"),4);boolean mirror=(boolean)field(uv,"mirror");
            float[] base=origin.clone();float uLength=0,vLength=0;
            for(int axis=0;axis<3;axis++) {
                if(NORMAL[face][axis]>0 || U[face][axis]<0 || V[face][axis]<0)base[axis]+=size[axis];
                uLength+=size[axis]*U[face][axis];vLength+=size[axis]*V[face][axis];
            }
            uLength=Math.abs(uLength);vLength=Math.abs(vLength);
            var vertices=new ModelPart.Vertex[4];
            for(int corner=0;corner<4;corner++) {
                float sx=CORNERS[corner][0],sy=CORNERS[corner][1];float[] xyz=new float[3];
                for(int axis=0;axis<3;axis++)xyz[axis]=base[axis]+U[face][axis]*uLength*sx+V[face][axis]*vLength*sy;
                if(face>=2)sx=1-sx;sy=1-sy;
                for(int r=0;r<rotation;r++){float next=sy;sy=1-sx;sx=next;}
                if(mirror)sx=1-sx;
                vertices[corner]=new ModelPart.Vertex(xyz[0],xyz[1],xyz[2],(u+sx*uw)/texWidth,(v+sy*vh)/texHeight);
            }
            out.polygons[face]=new ModelPart.Polygon(vertices,new Vector3f(NORMAL[face][0],NORMAL[face][1],NORMAL[face][2]));
        }
        return out;
    }
    /** Raw model pixels to scene coordinates, matching all three AvatarLayer placement branches. */
    static Matrix4f rawFrame(Matrix4fc root,boolean body,Matrix4f customAnchor,ModelPart vanillaAnchor,Matrix4f transform) {
        var pose=new PoseStack();pose.mulPose(root);
        if(body || customAnchor!=null) {
            pose.translate(0,1.5,0);pose.scale(-1,-1,1);pose.scale(1f/16,1f/16,1f/16);
            if(!body)pose.mulPose(customAnchor);
        } else {
            if(vanillaAnchor!=null)vanillaAnchor.translateAndRotate(pose);
            pose.scale(-1,-1,1);pose.scale(1f/16,1f/16,1f/16);
        }
        return new Matrix4f(pose.last().pose()).mul(transform);
    }
    private static ModelPart limb(PlayerModel model,String name) {
        return switch(name) {
            case "head"->model.head;case "body"->model.body;case "left_arm","leftArm"->model.leftArm;
            case "right_arm","rightArm"->model.rightArm;case "left_leg","leftLeg"->model.leftLeg;
            case "right_leg","rightLeg"->model.rightLeg;default->null;
        };
    }
    private static final class Api {
        final Method avatar,evaluate,seconds,transform;
        final Field serial;
        Api() throws ReflectiveOperationException {
            var loader=PlayerReflectionKAvatar.class.getClassLoader();
            var client=Class.forName("com.koper.kavatar.client.KAvatarClient",false,loader);
            var renderAvatar=Class.forName("com.koper.kavatar.client.render.RenderAvatar",false,loader);
            var frames=Class.forName("com.koper.kavatar.client.render.AvatarFrames",false,loader);
            var pose=Class.forName("com.koper.kavatar.client.render.AvatarPose",false,loader);
            var transformType=Class.forName("com.koper.kavatar.asset.AvatarManifest$Transform",false,loader);
            avatar=client.getMethod("avatar",AvatarRenderState.class);
            evaluate=frames.getMethod("evaluate",renderAvatar,AvatarRenderState.class,PlayerModel.class,double.class,long.class,boolean.class);
            seconds=Class.forName("com.koper.kavatar.client.ClientNetwork",false,loader).getMethod("animationSeconds",float.class);
            transform=pose.getMethod("transform",transformType);serial=frames.getField("serial");
        }
    }
    private static final class OptionalApi {
        static final Api API;
        static final String ERROR;
        static {
            Api api=null;String error=null;
            try {api=new Api();}catch(ClassNotFoundException absent) {
                // A fully absent optional mod is normal; a missing transitive/API class is diagnosed.
                if(!"com.koper.kavatar.client.KAvatarClient".equals(absent.getMessage()))error=failure(absent);
            }catch(ReflectiveOperationException | LinkageError incompatible) {error=failure(incompatible);}
            API=api;ERROR=error;
        }
    }
    private static Result unavailable(String detail) {return new Result(new PlayerReflectionGeometry.Builder().data(),List.of(),0,detail);}

    /** Null means vanilla/no active KAvatar; a non-null empty result is an explicit unsupported custom body. */
    static Result capture(Minecraft client,AvatarRenderState state,PlayerModel body,PoseStack root) {return capture(client,state,body,root,false);}
    static Result capture(Minecraft client,AvatarRenderState state,PlayerModel body,PoseStack root,boolean extended) {
        int capacity=extended?64:16;
        if(OptionalApi.API==null)return OptionalApi.ERROR==null?null:unavailable("KAvatar bridge unavailable: "+OptionalApi.ERROR);
        try {
            var api=OptionalApi.API;Object avatar=api.avatar.invoke(null,state);if(avatar==null)return null;
            double seconds=(double)api.seconds.invoke(null,state.ageInTicks-(float)Math.floor(state.ageInTicks));
            Object frame=api.evaluate.invoke(null,avatar,state,body,seconds,api.serial.getLong(null),false);
            var sampled=(Map<?,?>)call(frame,"parts");Object manifest=field(avatar,"manifest"),bodyDefinition=call(manifest,"body");
            var parts=(List<?>)field(avatar,"parts");boolean replaces=bodyDefinition!=null;
            Identifier playerSkin=state.skin==null?null:state.skin.body().texturePath(),chosen=playerSkin;
            if(replaces)for(Object part:parts) {
                Object source=field(part,"source"),definition=call(source,"definition");
                if(Objects.equals(bodyDefinition,definition))chosen=(boolean)call(definition,"usesPlayerSkin")?playerSkin:(Identifier)field(part,"texture");
            }
            if(chosen==null)return unavailable("KAvatar selected body texture is unavailable");
            var captures=new ArrayList<ModelCapture>();var textures=new ArrayList<TextureSpan>();int count=0,omitted=0;var reasons=new LinkedHashSet<String>();
            if(!replaces) {var base=PlayerReflectionGeometry.capture(body.root(),root);captures.add(new ModelCapture(base,null));count=base.count();textures.add(new TextureSpan(playerSkin,0,count));}
            var anchors=new HashMap<String,Matrix4f>();
            for(Object part:parts) {
                Object source=field(part,"source"),definition=call(source,"definition"),live=field(part,"definition");
                boolean isBody=Objects.equals(bodyDefinition,definition);String anchor=(String)call(live,"anchor");
                Object sampledPart=sampled.get(call(live,"id"));
                if(sampledPart==null) {if(isBody)return unavailable("KAvatar body pose is unavailable");omitted++;reasons.add("missing pose");continue;}
                Object model=call(sampledPart,"model");float[] matrices=(float[])call(sampledPart,"matrices");
                Matrix4f transform=(Matrix4f)api.transform.invoke(null,call(live,"transform"));
                if(isBody) {
                    var bones=(List<?>)field(model,"bones");
                    for(int i=0;i<bones.size();i++) {
                        var bone=bones.get(i);float[] pivot=(float[])field(bone,"pivot");
                        anchors.put((String)field(bone,"name"),new Matrix4f(transform).mul(new Matrix4f().set(matrices,i*16)).translate(pivot[0],pivot[1],pivot[2]));
                    }
                }
                Identifier texture=(boolean)call(definition,"usesPlayerSkin")?playerSkin:(Identifier)field(part,"texture");
                if(texture==null) {if(isBody)return unavailable("KAvatar body texture is unavailable");omitted++;reasons.add("missing texture");continue;}
                if(!extended && !chosen.equals(texture)) {omitted++;reasons.add("different texture; extended actor API required");continue;}
                var raw=rawFrame(root.last().pose(),isBody,anchors.get(anchor),limb(body,anchor),transform);
                var capture=captureModel(model,matrices,raw,capacity);
                if(capture.error()!=null || count+capture.geometry().count()>capacity) {
                    String reason=capture.error()==null?capacity+"-box budget":capture.error();
                    if(isBody)return unavailable("KAvatar body unsupported: "+reason);
                    omitted++;reasons.add(reason);continue;
                }
                textures.add(new TextureSpan(texture,count,capture.geometry().count()));captures.add(capture);count+=capture.geometry().count();
            }
            var buffer=ByteBuffer.allocateDirect(16+capacity*PlayerReflectionGeometry.BOX_BYTES).order(ByteOrder.nativeOrder());int offset=16;
            for(var capture:captures) {
                var data=capture.geometry().data();data.position(16).limit(16+capture.geometry().count()*288);
                buffer.position(offset).put(data);offset+=capture.geometry().count()*288;
            }
            buffer.putFloat(0,count);buffer.clear();
            return new Result(buffer.asReadOnlyBuffer().order(ByteOrder.nativeOrder()),List.copyOf(textures),count,
                "KAvatar: "+count+" posed boxes"+(omitted==0?"":"; "+omitted+" parts omitted ("+String.join(", ",reasons)+")"));
        } catch(ReflectiveOperationException | RuntimeException | LinkageError unsupported) {
            return unavailable("KAvatar capture unavailable: "+failure(unsupported));
        }
    }
}
