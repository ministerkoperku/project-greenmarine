package com.koper.greenmarine.client.iris;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.material.FogType;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import java.util.*;

/** Pack/world-owned Iris-compatible frame data. Call capture/sample once at frame preparation,
 * reuse the snapshot for every pass, and reset on teardown. No GPU resources or MarineFrame ABI
 * changes. Missing integrations and per-draw inputs are never replaced by placeholder values.
 * See docs/IRIS-UNIFORMS.md for source semantics and projection/depth integration requirements. */
public final class IrisFrameInputs {
    /** Host projection may use either depth range and either direction. Iris values always use
     * conventional OpenGL clip depth; rawHostProjection remains available for host integration. */
    public record Input(Object worldIdentity, Object packIdentity, long frameStartNanos, boolean paused,
                        int width, int height, double cameraX, double cameraY, double cameraZ,
                        float[] projection, float[] modelView, boolean hostZeroToOne, boolean hostReversedDepth,
                        float sunDegrees, float moonDegrees, float sunPathDegrees, Map<String,Object> hostValues) {
        public Input {
            Objects.requireNonNull(worldIdentity); Objects.requireNonNull(packIdentity);
            if(width<=0||height<=0)throw new IllegalArgumentException("Invalid frame dimensions");
            for(double value:new double[]{cameraX,cameraY,cameraZ,sunDegrees,moonDegrees,sunPathDegrees})
                if(!Double.isFinite(value))throw new IllegalArgumentException("Nonfinite frame geometry");
            projection=matrix(projection);modelView=matrix(modelView);hostValues=copy(hostValues);
        }
        @Override public float[] projection(){return projection.clone();}
        @Override public float[] modelView(){return modelView.clone();}
        @Override public Map<String,Object> hostValues(){return copy(hostValues);}
    }
    public static final class Snapshot {
        private final Map<String,Object> values;
        private final float[] rawProjection;
        private final double expressionDeltaSeconds;
        private Snapshot(Map<String,Object> values,float[] rawProjection,double expressionDeltaSeconds) {
            this.values=copy(values);this.rawProjection=rawProjection.clone();this.expressionDeltaSeconds=expressionDeltaSeconds;
        }
        /** Wall-frame milliseconds, zero while paused. Resume never accumulates paused time.
         * Iris frameTime/frameTimeCounter remain wall-clock values even while paused. */
        public double expressionDeltaSeconds(){return expressionDeltaSeconds;}
        public float[] rawHostProjection(){return rawProjection.clone();}
        public Set<String> availableRoots(){return values.keySet();}
        /** Full unsupported references, including invalid vector/matrix components. */
        public Set<String> unsupported(Set<String> requested) {
            var missing=new TreeSet<String>();
            for(String name:requested)if(!supports(name))missing.add(name);
            return Collections.unmodifiableSet(missing);
        }
        /** Return only the roots required by expressions/layouts; matrices are column-major.
         * Returned arrays are copies, so a consumer cannot mutate this frame's snapshot. */
        public Map<String,Object> resolve(Set<String> requested) {
            var missing=unsupported(requested);
            if(!missing.isEmpty())throw new IllegalArgumentException("Unsupported Iris frame inputs: "+String.join(", ",missing));
            var result=new LinkedHashMap<String,Object>();
            for(String name:new TreeSet<>(requested))result.put(root(name),values.get(root(name)));
            return copy(result);
        }
        /** Exact source keys for PackUniformBlock, retaining Integer components of ivec inputs. */
        public Map<String,Object> resolveValues(Set<String> requested) {
            var missing=unsupported(requested);
            if(!missing.isEmpty())throw new IllegalArgumentException("Unsupported Iris frame inputs: "+String.join(", ",missing));
            var result=new LinkedHashMap<String,Object>();
            for(String reference:new TreeSet<>(requested)) {
                String root=root(reference);Object value=values.get(root);
                if(!reference.equals(root)) {
                    String[] parts=reference.substring(root.length()+1).split("\\.");
                    if(value instanceof int[] vector)value=Integer.valueOf(vector[component(parts[0],vector.length)]);
                    else {var vector=(float[])value;value=Float.valueOf(vector[vector.length==16?component(parts[0],4)*4+component(parts[1],4):component(parts[0],vector.length)]);}
                }
                result.put(reference,value);
            }
            return copy(result);
        }
        private boolean supports(String reference) {
            String root=root(reference);Object value=values.get(root);if(value==null)return false;
            if(root.equals(reference))return true;
            String[] parts=reference.substring(root.length()+1).split("\\.",-1);
            int length=value instanceof float[] f?f.length:value instanceof int[] i?i.length:0;
            if(length==16)return parts.length==2&&component(parts[0],4)>=0&&component(parts[1],4)>=0;
            return parts.length==1&&component(parts[0],length)>=0;
        }
    }

    private Object world,pack;
    private boolean initialized;
    private long lastNanos;
    private int frameCounter;
    private float frameTimeCounter;
    private double shiftX,shiftZ;
    private double[] camera={0,0,0};
    private float[] previousProjection=new Matrix4f().get(new float[16]),previousView=new Matrix4f().get(new float[16]);

    public synchronized void reset() {
        world=pack=null;initialized=false;lastNanos=0;frameCounter=0;frameTimeCounter=0;shiftX=shiftZ=0;
        camera=new double[]{0,0,0};previousProjection=new Matrix4f().get(new float[16]);previousView=previousProjection.clone();
    }

    public synchronized Snapshot sample(Input input) {
        Objects.requireNonNull(input);
        if(world!=input.worldIdentity||pack!=input.packIdentity){reset();world=input.worldIdentity;pack=input.packIdentity;}
        if(initialized&&input.frameStartNanos<lastNanos)throw new IllegalArgumentException("Frame clock moved backwards");
        float frameTime=initialized?((input.frameStartNanos-lastNanos)/1_000_000)/1000f:0;
        float nextTimeCounter=frameTimeCounter+frameTime;if(nextTimeCounter>=3600)nextTimeCounter=0;
        int nextFrameCounter=(frameCounter+1)%720720;
        var values=new LinkedHashMap<String,Object>(input.hostValues);
        // Constants are part of Iris's custom expression environment, not registry raw IDs.
        values.putAll(CONSTANTS);
        values.put("frameCounter",nextFrameCounter);values.put("frameTime",frameTime);values.put("frameTimeCounter",nextTimeCounter);
        values.put("viewWidth",(float)input.width);values.put("viewHeight",(float)input.height);values.put("aspectRatio",(float)input.width/input.height);
        var view=new Matrix4f().set(input.modelView);var projection=new Matrix4f().set(input.projection);
        // Clip range and reversal are independent. MC26.3 Projection passes zFar,zNear to JOML.
        float[] irisProjection=input.projection.clone();
        for(int column=0;column<4;column++) {
            int z=column*4+2,w=column*4+3;
            irisProjection[z]=input.hostZeroToOne
                ?(input.hostReversedDepth?irisProjection[w]-2*irisProjection[z]:2*irisProjection[z]-irisProjection[w])
                :(input.hostReversedDepth?-irisProjection[z]:irisProjection[z]);
        }
        projection.set(irisProjection);
        putMatrix(values,"gbufferModelView",view);putMatrix(values,"gbufferModelViewInverse",new Matrix4f(view).invert());
        putMatrix(values,"gbufferProjection",projection);putMatrix(values,"gbufferProjectionInverse",new Matrix4f(projection).invert());
        values.put("gbufferPreviousModelView",previousView);values.put("gbufferPreviousProjection",previousProjection);
        putMatrix(values,"gbufferPreviousModelViewInverse",new Matrix4f().set(previousView).invert());
        double[] previousCamera=camera.clone();
        double x=input.cameraX+shiftX,z=input.cameraZ+shiftZ;
        double dx=shift(x,previousCamera[0]),dz=shift(z,previousCamera[2]);
        x+=dx;z+=dz;previousCamera[0]+=dx;previousCamera[2]+=dz;
        double[] currentCamera={x,input.cameraY,z};
        values.put("cameraPosition",vector(currentCamera));values.put("previousCameraPosition",vector(previousCamera));
        values.put("eyeAltitude",(float)input.cameraY);
        values.put("cameraPositionInt",new int[]{(int)Math.floor(input.cameraX),(int)Math.floor(input.cameraY),(int)Math.floor(input.cameraZ)});
        values.put("cameraPositionFract",new float[]{fract(input.cameraX),fract(input.cameraY),fract(input.cameraZ)});
        float angle=input.sunDegrees+90;if(angle<0)angle+=360;else if(angle>360)angle-=360;
        values.put("sunAngle",angle/360f);
        values.put("sunPosition",celestial(view,input.sunDegrees,input.sunPathDegrees));
        values.put("moonPosition",celestial(view,input.moonDegrees,input.sunPathDegrees));
        var up=new Matrix4f(view).rotateY((float)Math.toRadians(-90)).transform(new Vector4f(0,100,0,0));
        values.put("upPosition",new float[]{up.x,up.y,up.z});
        var result=new Snapshot(values,input.projection,input.paused?0:frameTime);
        initialized=true;lastNanos=input.frameStartNanos;frameCounter=nextFrameCounter;frameTimeCounter=nextTimeCounter;
        shiftX+=dx;shiftZ+=dz;camera=currentCamera;previousView=input.modelView.clone();previousProjection=irisProjection;
        return result;
    }

    /** Real client adapter; invoke on the render thread after extraction and before pack passes.
     * sunPathDegrees must come from the active pack directive, not the vanilla sky path. */
    public Snapshot capture(GameRenderer renderer,Matrix4fc hostProjection,Object packIdentity,long frameStartNanos,float sunPathDegrees) {
        var client=Minecraft.getInstance();var level=client.level;
        if(level==null)throw new IllegalStateException("Iris frame inputs require a loaded world");
        var state=renderer.gameRenderState().levelRenderState;var renderCamera=state.cameraRenderState;
        var mainCamera=renderer.mainCamera();float partial=state.worldPartialTicks;
        var values=new LinkedHashMap<String,Object>();
        long clock=level.getDefaultClockTime();
        values.put("worldTime",worldTime(clock,level.dimensionType().hasFixedTime(),level.dimension()==Level.NETHER||level.dimension()==Level.END));
        values.put("worldDay",(int)(clock/24000L));
        values.put("moonPhase",mainCamera.attributeProbe().getValue(EnvironmentAttributes.MOON_PHASE,partial).index());
        values.put("rainStrength",Math.clamp(level.getRainLevel(partial),0f,1f));
        values.put("thunderStrength",Math.clamp(level.getThunderLevel(partial),0f,1f));
        values.put("near",.05f);values.put("far",client.options.getEffectiveRenderDistance()*16f);
        values.put("screenBrightness",client.options.gamma().get().floatValue());
        values.put("hideGUI",client.gui.hud.isHidden()?1:0);values.put("firstPersonCamera",client.options.getCameraType().isFirstPerson());
        var player=client.player;boolean survival=player!=null&&client.gameMode!=null&&client.gameMode.getPlayerMode().isSurvival();
        values.putAll(playerVitals(survival,player==null?0:player.getHealth(),player==null?1:player.getMaxHealth(),player==null?0:player.getAirSupply(),player==null?1:player.getMaxAirSupply()));
        values.put("is_burning",player!=null&&player.isOnFire());values.put("is_hurt",player!=null&&player.hurtTime>0);
        values.put("is_sneaking",player!=null&&player.isCrouching());values.put("is_sprinting",player!=null&&player.isSprinting());
        values.put("is_invisible",player!=null&&player.isInvisible());values.put("is_on_ground",player!=null&&player.onGround());
        values.put("feetInWater",player!=null&&player.isInShallowWater());values.put("isRiding",player!=null&&player.isPassenger());
        var vehicle=player==null?null:player.getVehicle();values.put("vehicleInWater",vehicle!=null&&vehicle.isInShallowWater());
        FogType fluid=mainCamera.getFluidInCamera();
        values.put("isEyeInWater",fluid==FogType.WATER?1:fluid==FogType.LAVA&&(player==null||!player.isSpectator())?2:fluid==FogType.POWDER_SNOW?3:0);
        var cameraEntity=client.getCameraEntity();
        if(cameraEntity!=null) {
            var feet=cameraEntity.position();var eyeBlock=BlockPos.containing(feet.x,cameraEntity.getEyeY(),feet.z);
            values.put("eyeBrightness",new int[]{level.getBrightness(LightLayer.BLOCK,eyeBlock)*16,level.getBrightness(LightLayer.SKY,eyeBlock)*16});
            var eye=cameraEntity.getEyePosition(partial);values.put("relativeEyePosition",new float[]{(float)(renderCamera.pos.x-eye.x),(float)(renderCamera.pos.y-eye.y),(float)(renderCamera.pos.z-eye.z)});
            if(cameraEntity instanceof LivingEntity living) {var look=living.getViewVector(partial);values.put("playerLookVector",new float[]{(float)look.x,(float)look.y,(float)look.z});}
        }
        if(vehicle!=null) {
            var pos=vehicle.getPosition(partial);var look=vehicle.getForward();
            values.put("relativeVehiclePosition",new float[]{(float)(renderCamera.pos.x-pos.x),(float)(renderCamera.pos.y-pos.y),(float)(renderCamera.pos.z-pos.z)});
            values.put("vehicleLookVector",new float[]{(float)look.x,(float)look.y,(float)look.z});
        } else {values.put("relativeVehiclePosition",new float[3]);values.put("vehicleLookVector",new float[3]);}
        var lightning=new float[4];
        for(var entity:level.entitiesForRendering())if(entity instanceof LightningBolt) {
            var pos=entity.getPosition(partial);lightning=new float[]{(float)(pos.x-renderCamera.pos.x),(float)(pos.y-renderCamera.pos.y),(float)(pos.z-renderCamera.pos.z),1};break;
        }
        values.put("lightningBoltPosition",lightning);
        if(player!=null) {
            var biome=level.getBiome(player.blockPosition());
            values.put("biome",biomeId(biome.unwrapKey().map(key->key.identifier().toString()).orElse("")));
            var tags=new HashSet<String>();for(var entry:BiomeTagBindings.TAGS.entrySet())if(biome.is(entry.getValue()))tags.add(entry.getKey());
            values.put("biome_category",biomeCategory(tags));
            values.put("temperature",biome.value().getBaseTemperature());
            values.put("biome_precipitation",switch(biome.value().getPrecipitationAt(player.blockPosition(),level.getSeaLevel())){case NONE->0;case RAIN->1;case SNOW->2;});
        }
        float sun=mainCamera.attributeProbe().getValue(EnvironmentAttributes.SUN_ANGLE,partial);
        float moon=mainCamera.attributeProbe().getValue(EnvironmentAttributes.MOON_ANGLE,partial);
        var target=renderer.mainRenderTarget();
        return sample(new Input(level,packIdentity,frameStartNanos,client.isPaused(),target.width,target.height,
            renderCamera.pos.x,renderCamera.pos.y,renderCamera.pos.z,hostProjection.get(new float[16]),renderCamera.viewRotationMatrix.get(new float[16]),
            RenderSystem.getDevice().getDeviceInfo().isZZeroToOne(),true,sun,moon,sunPathDegrees,values));
    }

    /** Iris returns -1 outside survival; health and air are normalized, maximum health is raw. */
    public static Map<String,Object> playerVitals(boolean survival,float health,float maximumHealth,int air,int maximumAir) {
        if(survival&&(!(maximumHealth>0)||maximumAir<=0))throw new IllegalArgumentException("Invalid player vital maximum");
        return Map.of("currentPlayerHealth",survival?health/maximumHealth:-1f,"maxPlayerHealth",survival?maximumHealth:-1f,"currentPlayerAir",survival?(float)air/maximumAir:-1f);
    }
    public static int worldTime(long clock,boolean fixedTime,boolean netherOrEnd){return fixedTime&&!netherOrEnd?0:(int)(clock%24000L);}
    /** Iris BiomeUniforms' unknown map entry is zero; biome constants share this same table. */
    public static int biomeId(String identifier){return Math.max(0,BIOMES.indexOf(identifier));}
    public static int biomeCategory(Set<String> tags) {
        for(var entry:CATEGORY_TAGS.entrySet())if(tags.contains(entry.getKey()))return entry.getValue();
        return 5;
    }

    private static final List<String> BIOMES=List.of(("the_void plains sunflower_plains snowy_plains ice_spikes desert swamp mangrove_swamp forest flower_forest birch_forest dappled_forest dark_forest pale_garden old_growth_birch_forest old_growth_pine_taiga old_growth_spruce_taiga taiga snowy_taiga savanna savanna_plateau windswept_hills windswept_gravelly_hills windswept_forest windswept_savanna jungle sparse_jungle bamboo_jungle badlands eroded_badlands wooded_badlands meadow cherry_grove grove snowy_slopes frozen_peaks jagged_peaks stony_peaks river frozen_river beach snowy_beach stony_shore warm_ocean lukewarm_ocean deep_lukewarm_ocean ocean deep_ocean cold_ocean deep_cold_ocean frozen_ocean deep_frozen_ocean mushroom_fields dripstone_caves lush_caves deep_dark sulfur_caves nether_wastes warped_forest crimson_forest soul_sand_valley basalt_deltas the_end end_highlands end_midlands small_end_islands end_barrens").split(" ")).stream().map(name->"minecraft:"+name).toList();
    private static final Map<String,Integer> CATEGORY_TAGS;
    private static final Map<String,Object> CONSTANTS;
    static {
        var tags=new LinkedHashMap<String,Integer>();
        tags.put("WITHOUT_WANDERING_TRADER_SPAWNS",0);tags.put("HAS_VILLAGE_SNOWY",7);tags.put("IS_HILL",2);tags.put("IS_TAIGA",1);
        tags.put("IS_OCEAN",11);tags.put("IS_JUNGLE",3);tags.put("IS_FOREST",10);tags.put("IS_BADLANDS",4);tags.put("IS_NETHER",16);tags.put("IS_END",8);
        tags.put("IS_BEACH",9);tags.put("HAS_DESERT_PYRAMID",12);tags.put("IS_RIVER",13);tags.put("ALLOWS_SURFACE_SLIME_SPAWNS",14);tags.put("WITHOUT_ZOMBIE_SIEGES",15);tags.put("IS_MOUNTAIN",17);
        CATEGORY_TAGS=Collections.unmodifiableMap(tags);
        var constants=new LinkedHashMap<String,Object>();
        for(int i=0;i<BIOMES.size();i++)constants.put("BIOME_"+BIOMES.get(i).substring(10).toUpperCase(Locale.ROOT),i);
        String[] categories="NONE TAIGA EXTREME_HILLS JUNGLE MESA PLAINS SAVANNA ICY THE_END BEACH FOREST OCEAN DESERT RIVER SWAMP MUSHROOM NETHER MOUNTAIN UNDERGROUND".split(" ");
        for(int i=0;i<categories.length;i++)constants.put("CAT_"+categories[i],i);
        constants.put("PPT_NONE",0);constants.put("PPT_RAIN",1);constants.put("PPT_SNOW",2);CONSTANTS=Collections.unmodifiableMap(constants);
    }
    // Lazy so deterministic CPU tests do not initialize Minecraft registries or client classes.
    private static final class BiomeTagBindings {
        static final Map<String,TagKey<Biome>> TAGS=Map.ofEntries(
            Map.entry("WITHOUT_WANDERING_TRADER_SPAWNS",BiomeTags.WITHOUT_WANDERING_TRADER_SPAWNS),Map.entry("HAS_VILLAGE_SNOWY",BiomeTags.HAS_VILLAGE_SNOWY),
            Map.entry("IS_HILL",BiomeTags.IS_HILL),Map.entry("IS_TAIGA",BiomeTags.IS_TAIGA),Map.entry("IS_OCEAN",BiomeTags.IS_OCEAN),Map.entry("IS_JUNGLE",BiomeTags.IS_JUNGLE),Map.entry("IS_FOREST",BiomeTags.IS_FOREST),
            Map.entry("IS_BADLANDS",BiomeTags.IS_BADLANDS),Map.entry("IS_NETHER",BiomeTags.IS_NETHER),Map.entry("IS_END",BiomeTags.IS_END),Map.entry("IS_BEACH",BiomeTags.IS_BEACH),
            Map.entry("HAS_DESERT_PYRAMID",BiomeTags.HAS_DESERT_PYRAMID),Map.entry("IS_RIVER",BiomeTags.IS_RIVER),Map.entry("ALLOWS_SURFACE_SLIME_SPAWNS",BiomeTags.ALLOWS_SURFACE_SLIME_SPAWNS),
            Map.entry("WITHOUT_ZOMBIE_SIEGES",BiomeTags.WITHOUT_ZOMBIE_SIEGES),Map.entry("IS_MOUNTAIN",BiomeTags.IS_MOUNTAIN));
    }
    private static float[] matrix(float[] values){if(values==null||values.length!=16)throw new IllegalArgumentException("Expected column-major mat4");for(float f:values)if(!Float.isFinite(f))throw new IllegalArgumentException("Nonfinite matrix");return values.clone();}
    private static float[] vector(double[] v){return new float[]{(float)v[0],(float)v[1],(float)v[2]};}
    private static float fract(double value){return(float)(value-Math.floor(value));}
    private static double shift(double value,double previous){return Math.abs(value)>30000||Math.abs(value-previous)>1000?-(value-value%30000):0;}
    private static float[] celestial(Matrix4f view,float degrees,float path){var v=new Matrix4f(view).rotateY((float)Math.toRadians(-90)).rotateZ((float)Math.toRadians(path)).rotateX((float)Math.toRadians(degrees)).transform(new Vector4f(0,100,0,1));return new float[]{v.x,v.y,v.z};}
    private static void putMatrix(Map<String,Object> values,String name,Matrix4f matrix){values.put(name,matrix(matrix.get(new float[16])));}
    private static String root(String name){int dot=name.indexOf('.');return dot<0?name:name.substring(0,dot);}
    private static int component(String part,int length){int index=switch(part){case "x","r"->0;case "y","g"->1;case "z","b"->2;case "w","a"->3;default->{try{yield Integer.parseInt(part);}catch(NumberFormatException ignored){yield -1;}}};return index>=0&&index<length?index:-1;}
    private static Map<String,Object> copy(Map<String,Object> source) {
        var result=new LinkedHashMap<String,Object>();source.forEach((name,value)->{
            Objects.requireNonNull(name);Objects.requireNonNull(value);
            Object copied=value instanceof float[] f?f.clone():value instanceof int[] i?i.clone():value;
            if(!(copied instanceof Float||copied instanceof Integer||copied instanceof Boolean||copied instanceof float[]||copied instanceof int[]))throw new IllegalArgumentException("Invalid Iris input type: "+name);
            result.put(name,copied);
        });return Collections.unmodifiableMap(result);
    }
}
