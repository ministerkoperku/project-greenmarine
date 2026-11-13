package com.koper.greenmarine.compat.integration;

import com.koper.greenmarine.GreenMarine;
import com.koper.greenmarine.api.render.BlockRayModels;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import java.lang.reflect.*;
import java.util.*;

/** Optional adapter; base entrypoints and APIs never link Mechanics Dream or Fabric API classes. */
public final class MechanicsMicroGeometry implements ClientModInitializer {
    private static final ClassValue<Map<String,Method>> GETTERS=new ClassValue<>() {
        @Override protected Map<String,Method> computeValue(Class<?> type) {
            var getters=new HashMap<String,Method>();
            for(var method:type.getMethods())if(method.getParameterCount()==0)getters.put(method.getName(),method);
            return Map.copyOf(getters);
        }
    };
    @Override public void onInitializeClient() {
        if(!FabricLoader.getInstance().isModLoaded("mechanics_dream"))return;
        try {
            var loader=getClass().getClassLoader();
            var block=Class.forName("com.koper.mechanics_dream.MicroGridBlock",false,loader);
            var data=Class.forName("com.koper.mechanics_dream.MicroGridData",false,loader);
            var get=data.getMethod("get",BlockGetter.class,BlockPos.class);
            var local=data.getMethod("fromLocalData",CompoundTag.class,HolderLookup.Provider.class);
            BlockRayModels.register("greenmarine:mechanics_microgrids",state->block.isInstance(state.getBlock()),context->{
                try {
                    Object grid=context.localData()==null?get.invoke(null,context.level(),context.position())
                        :local.invoke(null,context.localData(),context.level().registryAccess());
                    // The raster model explicitly omits nested microgrid materials.
                    return decode(grid).stream().filter(part->!block.isInstance(part.material().getBlock())).toList();
                }catch(ReflectiveOperationException error){throw new IllegalStateException("Cannot read Mechanics Dream micro geometry",error);}
            });
            GreenMarine.LOGGER.info("Mechanics Dream microgrid ray geometry adapter initialized");
        }catch(ReflectiveOperationException|LinkageError incompatible) {
            GreenMarine.LOGGER.warn("Mechanics Dream microgrid geometry adapter unavailable",incompatible);
        }
    }
    private static Object value(Object target,String name) throws ReflectiveOperationException {
        var method=GETTERS.get(target.getClass()).get(name);
        if(method==null)throw new NoSuchMethodException(name);
        return method.invoke(target);
    }
    private record Piece(int x,int y,int z,int size,BlockState material) {
        boolean contains(int x,int y,int z) {return x>=this.x&&x<this.x+size&&y>=this.y&&y<this.y+size&&z>=this.z&&z<this.z+size;}
    }
    static List<BlockRayModels.Part> decode(Object grid) throws ReflectiveOperationException {
        if(grid==null)return List.of();
        var pieces=new ArrayList<Piece>();
        for(Object piece:(List<?>)value(grid,"pieces")) {
            var decoded=new Piece((int)value(piece,"x"),(int)value(piece,"y"),(int)value(piece,"z"),(int)value(piece,"size"),(BlockState)value(piece,"material"));
            if(decoded.size()<1 || decoded.size()>4 || decoded.x()<0 || decoded.y()<0 || decoded.z()<0
                || decoded.x()+decoded.size()>4 || decoded.y()+decoded.size()>4 || decoded.z()+decoded.size()>4 || decoded.material()==null)
                throw new IllegalArgumentException("Invalid Mechanics Dream micro piece");
            pieces.add(decoded);
        }
        var result=new ArrayList<BlockRayModels.Part>();
        for(var piece:pieces) {
            int mask=0;
            for(var direction:Direction.values()) {
                boolean visible=false;
                for(int y=piece.y();y<piece.y()+piece.size()&&!visible;y++)for(int z=piece.z();z<piece.z()+piece.size()&&!visible;z++)
                    for(int x=piece.x();x<piece.x()+piece.size();x++) {
                        boolean face=switch(direction) {case WEST->x==piece.x();case EAST->x==piece.x()+piece.size()-1;
                            case DOWN->y==piece.y();case UP->y==piece.y()+piece.size()-1;case NORTH->z==piece.z();case SOUTH->z==piece.z()+piece.size()-1;};
                        if(!face)continue;
                        int nx=x+direction.getStepX(),ny=y+direction.getStepY(),nz=z+direction.getStepZ();boolean occupied=false;
                        for(var other:pieces)if(other.contains(nx,ny,nz)){occupied=true;break;}
                        if(!occupied){visible=true;break;}
                    }
                if(visible)mask|=1<<direction.ordinal();
            }
            result.add(new BlockRayModels.Part(piece.material(),piece.x()/4f,piece.y()/4f,piece.z()/4f,piece.size()/4f,mask));
        }
        return List.copyOf(result);
    }
}
