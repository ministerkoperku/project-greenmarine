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
 * never close its skin. Armor, capes, held items and arbitrary entities are absent.
 */
public final class PlayerReflection {
    public static final int BYTES=PlayerReflectionGeometry.BYTES;
    public static final int MAX_BOXES=PlayerReflectionGeometry.MAX_BOXES;
    public static final int EXTENDED_BYTES=PlayerReflectionGeometry.EXTENDED_BYTES;
    public static final int EXTENDED_MAX_BOXES=PlayerReflectionGeometry.EXTENDED_MAX_BOXES;
    private static final ActorAtlas ATLAS=new ActorAtlas();
    public record Snapshot(ByteBuffer data,GpuTextureView skin,int boxes) {}
    private static volatile String detail="Local player reflection has not been captured";
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
    private static Snapshot empty(String reason,boolean extended) {detail=reason;return new Snapshot(sized(new PlayerReflectionGeometry.Builder().data(),extended),null,0);}
    /** Legacy 16-box payloads are zero-padded for the extended ABI; never truncated. */
    static ByteBuffer sized(ByteBuffer data,boolean extended) {
        int bytes=extended?EXTENDED_BYTES:BYTES;
        if(data.remaining()==bytes)return data;
        if(data.remaining()>bytes)throw new IllegalArgumentException("Actor payload exceeds the selected ABI");
        var out=ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());out.put(data.duplicate()).clear();
        return out.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
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
            return new Snapshot(sized(geometry.data(),extendedActors),skin,geometry.count());
        } catch(RuntimeException | LinkageError unavailable) {
            // Optional host/model/skin availability must never reuse a stale player's body.
            return empty("Local player capture unavailable: "+unavailable.getClass().getSimpleName()+": "+unavailable.getMessage(),extendedActors);
        }
    }
}
