package com.koper.greenmarine.client.rt;

import com.koper.greenmarine.mixin.PlayerReflectionRendererAccessor;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.player.PlayerModelType;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Render-thread snapshots of the local body, including first-person and optional KAvatar views.
 * Skin views may be borrowed or owned atlas views: retain the Snapshot until its GPU submission completes;
 * never close its skin. Extended snapshots add the nearest living entities' animated models after the
 * player; armor, capes, held items and non-cube models are absent.
 */
public final class PlayerReflection {
    public static final int BYTES=PlayerReflectionGeometry.BYTES;
    public static final int MAX_BOXES=PlayerReflectionGeometry.MAX_BOXES;
    public static final int EXTENDED_BYTES=PlayerReflectionGeometry.EXTENDED_BYTES;
    public static final int EXTENDED_MAX_BOXES=PlayerReflectionGeometry.EXTENDED_MAX_BOXES;
    private static final ActorAtlas ATLAS=new ActorAtlas();
    public record Snapshot(ByteBuffer data,GpuTextureView skin,int boxes) {}
    private static volatile String detail="Local player reflection has not been captured";
    private static final int ENTITY_LIMIT=8;
    private static final double ENTITY_RADIUS=32;
    private static volatile java.util.Set<Integer> traced=java.util.Set.of();
    /** Entities whose sun shadow the native actors cast in the last snapshot. */
    public static boolean traced(int entityId) {return traced.contains(entityId);}
    public static String detail() {return detail;}
    private PlayerReflection() {}
    /** Render thread only, after RayWorld has drained submissions retaining the owned atlas. */
    public static void close() {ATLAS.close();}
    private static final class Models {
        static final PlayerModel WIDE=create(false),SLIM=create(true);
        private static PlayerModel create(boolean slim) {
            return new PlayerModel(LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE,slim),64,64).bakeRoot(),slim);
        }
    }
    private static Snapshot empty(String reason,boolean extended) {detail=reason;traced=java.util.Set.of();return new Snapshot(sized(new PlayerReflectionGeometry.Builder().data(),extended),null,0);}
    /** Legacy 16-box payloads are zero-padded for the extended ABI; never truncated. */
    static ByteBuffer sized(ByteBuffer data,boolean extended) {
        int bytes=extended?EXTENDED_BYTES:BYTES;
        if(data.remaining()==bytes)return data;
        if(data.remaining()>bytes)throw new IllegalArgumentException("Actor payload exceeds the selected ABI");
        var out=ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());out.put(data.duplicate()).clear();
        return out.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
    }

    /** A borrowed texture the actor atlas can sample (it never copies or closes it). */
    static boolean usableTexture(Minecraft client,net.minecraft.resources.Identifier id) {
        var texture=client.getTextureManager().getTexture(id);var view=texture==null?null:texture.getTextureView();
        return view!=null && !view.isClosed() && !view.texture().isClosed() && view.texture().getFormat()==com.mojang.renderpearl.api.GpuFormat.RGBA8_UNORM
            && (view.texture().usage()&com.mojang.renderpearl.api.textures.GpuTexture.USAGE_TEXTURE_BINDING)!=0 && view.texture().getDepthOrLayers()==1;
    }
    /** Appends the nearest visible living entities that fit the extended box budget whole. */
    private static PlayerReflectionKAvatar.Result withEntities(Minecraft client,PlayerReflectionKAvatar.Result actor,int originX,int originY,int originZ) {
        var builder=new PlayerReflectionGeometry.Builder(actor.data(),actor.boxes(),EXTENDED_MAX_BOXES);
        var spans=new java.util.ArrayList<>(actor.textures());var ids=new java.util.HashSet<Integer>();ids.add(client.player.getId());
        var camera=client.gameRenderer.mainCamera().position();
        var nearby=new java.util.ArrayList<net.minecraft.world.entity.LivingEntity>();
        for(var entity:client.level.entitiesForRendering())
            if(entity instanceof net.minecraft.world.entity.LivingEntity living && entity!=client.player && !entity.isRemoved()
                && !entity.isInvisible() && entity.position().distanceToSqr(camera)<ENTITY_RADIUS*ENTITY_RADIUS)nearby.add(living);
        nearby.sort(java.util.Comparator.comparingDouble(entity->entity.position().distanceToSqr(camera)));
        var dispatcher=client.getEntityRenderDispatcher();int added=0;
        for(var entity:nearby) {
            if(added==ENTITY_LIMIT || builder.remaining()==0)break;
            try {
                float partial=client.getDeltaTracker().getGameTimeDeltaPartialTick(!client.level.tickRateManager().isEntityFrozen(entity));
                if(!(dispatcher.extractEntity(entity,partial) instanceof net.minecraft.client.renderer.entity.state.LivingEntityRenderState state)
                    || state.isInvisible || !(state.scale>0) || !Float.isFinite(state.scale))continue;
                var renderer=dispatcher.getRenderer(state);
                if(!(renderer instanceof net.minecraft.client.renderer.entity.LivingEntityRenderer<?,?,?> living)
                    || !(renderer instanceof PlayerReflectionRendererAccessor transforms))continue;
                @SuppressWarnings({"unchecked","rawtypes"})
                var textureId=((net.minecraft.client.renderer.entity.LivingEntityRenderer)living).getTextureLocation(state);
                if(textureId==null || !usableTexture(client,textureId))continue;
                var offset=renderer.getRenderOffset(state);
                var pose=PlayerReflectionPose.root(state,originX,originY,originZ,offset,
                    p->transforms.greenmarine$reflectionRotations(state,p,state.bodyRot,state.scale),p->transforms.greenmarine$reflectionScale(state,p));
                @SuppressWarnings({"unchecked","rawtypes"})
                net.minecraft.client.model.EntityModel model=(net.minecraft.client.model.EntityModel)living.getModel();
                model.setupAnim(state);
                int first=builder.count();
                if(!PlayerReflectionGeometry.appendWhole(builder,model.root(),pose))continue;
                spans.add(new PlayerReflectionKAvatar.TextureSpan(textureId,first,builder.count()-first));ids.add(entity.getId());added++;
                ArmorReflection.append(client,builder,spans,renderer,state,pose);
            } catch(RuntimeException|LinkageError unsupported) {
                // One unusual renderer must not cost the player or other entities their reflection.
            }
        }
        traced=java.util.Set.copyOf(ids);
        if(added==0)return actor;
        return new PlayerReflectionKAvatar.Result(builder.data(),java.util.List.copyOf(spans),builder.count(),
            actor.detail()+"; "+added+" nearby entities ("+(builder.count()-actor.boxes())+" boxes)");
    }
    /** Always returns fresh initialized data; unavailable/invisible/stale input never reuses an older actor. */
    public static Snapshot snapshot(Minecraft client,int originX,int originY,int originZ) {return snapshot(client,originX,originY,originZ,false);}
    /** Extended mode includes up to64 complete custom boxes and combines all supported part textures. */
    public static Snapshot snapshot(Minecraft client,int originX,int originY,int originZ,boolean extendedActors) {
        if(client==null || client.level==null || client.player==null || client.player.isRemoved()) return empty("Local player/world unavailable",extendedActors);
        if(client.player.isSpectator() || client.player.isInvisible()) return empty("Local player is hidden or a spectator",extendedActors);
        try {
            // Match LevelExtractor.extractVisibleEntities, including frozen entities.
            float partial=client.getDeltaTracker().getGameTimeDeltaPartialTick(!client.level.tickRateManager().isEntityFrozen(client.player));
            if(!Float.isFinite(partial)) return empty("Invalid player animation time",extendedActors);
            var dispatcher=client.getEntityRenderDispatcher();
            if(!(dispatcher.extractEntity(client.player,partial) instanceof AvatarRenderState state)
                || state.id!=client.player.getId() || state.isSpectator || state.isInvisible || state.skin==null
                || !Double.isFinite(state.x) || !Double.isFinite(state.y) || !Double.isFinite(state.z)
                || !(state.scale>0) || !Float.isFinite(state.scale)) return empty("Unsupported, hidden or stale local avatar state",extendedActors);
            var renderer=dispatcher.getRenderer(state);
            if(!(renderer instanceof PlayerReflectionRendererAccessor transforms)) return empty("Player renderer transform accessor unavailable",extendedActors);
            var offset=renderer.getRenderOffset(state);
            if(offset==null || !Double.isFinite(offset.x()) || !Double.isFinite(offset.y()) || !Double.isFinite(offset.z())) return empty("Invalid player render offset",extendedActors);
            var pose=PlayerReflectionPose.root(state,originX,originY,originZ,offset,
                p->transforms.greenmarine$reflectionRotations(state,p,state.bodyRot,state.scale),
                p->transforms.greenmarine$reflectionScale(state,p));
            var model=state.skin.model()==PlayerModelType.SLIM?Models.SLIM:Models.WIDE;
            model.resetPose();model.setupAnim(state);
            var custom=PlayerReflectionKAvatar.capture(client,state,model,pose,extendedActors);
            if(custom!=null) {
                if(custom.boxes()==0 || custom.texture()==null)return empty(custom.detail(),extendedActors);
                if(extendedActors)custom=withEntities(client,custom,originX,originY,originZ);else traced=java.util.Set.of(client.player.getId());
                var atlas=ATLAS.prepare(client,custom);
                detail=custom.detail()+(custom.textures().stream().map(PlayerReflectionKAvatar.TextureSpan::texture).distinct().count()>1?"; combined texture atlas":"");
                return new Snapshot(sized(atlas.data(),extendedActors),atlas.skin(),custom.boxes());
            }
            var texture=client.getTextureManager().getTexture(state.skin.body().texturePath());
            var skin=texture==null?null:texture.getTextureView();
            if(skin==null || skin.isClosed() || skin.texture().isClosed()) return empty("Player skin texture is not ready",extendedActors);
            var geometry=PlayerReflectionGeometry.capture(model.root(),pose);
            if(geometry.count()==0) return empty("No valid visible player cubes",extendedActors);
            detail="Local player: "+geometry.count()+" animated skin boxes";
            if(extendedActors) {
                var dressed=new PlayerReflectionGeometry.Builder(geometry.data(),geometry.count(),EXTENDED_MAX_BOXES);
                var spans=new java.util.ArrayList<PlayerReflectionKAvatar.TextureSpan>();
                spans.add(new PlayerReflectionKAvatar.TextureSpan(state.skin.body().texturePath(),0,geometry.count()));
                int armor=ArmorReflection.append(client,dressed,spans,renderer,state,pose);
                if(armor>0)detail+=", "+armor+" armor pieces";
                var body=new PlayerReflectionKAvatar.Result(dressed.data(),java.util.List.copyOf(spans),dressed.count(),detail);
                var all=withEntities(client,body,originX,originY,originZ);
                if(all.boxes()>geometry.count()) {
                    var atlas=ATLAS.prepare(client,all);detail=all.detail();
                    return new Snapshot(sized(atlas.data(),true),atlas.skin(),all.boxes());
                }
            }
            traced=java.util.Set.of(client.player.getId());
            return new Snapshot(sized(geometry.data(),extendedActors),skin,geometry.count());
        } catch(RuntimeException | LinkageError unavailable) {
            // Optional host/model/skin availability must never reuse a stale player's body.
            return empty("Local player capture unavailable: "+unavailable.getClass().getSimpleName()+": "+unavailable.getMessage(),extendedActors);
        }
    }
}
