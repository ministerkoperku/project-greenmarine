package com.koper.greenmarine.client.world;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Executes the production bridge against a lazy-render-definition DH fixture. */
class DistantBridgeInitializationTest {
    @TempDir static Path classes;
    private static final String API="com.seibel.distanthorizons.api.";
    private static final String UTIL="com.seibel.distanthorizons.core.util.RenderUtil";
    private static final String OWNER="com.koper.greenmarine.client.world.DistantTerrain";

    @BeforeAll static void compileFixture() throws Exception {
        var sources=new LinkedHashMap<String,String>();
        sources.put(API+"DhApi","public class DhApi {public static class Delayed {public static Object definition; public static boolean initialized; public static Object renderProxy=new Proxy(); public static Object configs=new Config();} public static class Proxy implements "+API+"interfaces.render.IDhApiRenderProxy {public Object getDhDepthTextureBlazeWrapper(){return new "+API+"objects.DhApiResult();} public Object getDepthDirection(){return \"REVERSE\";} public Object getDepthRange(){return \"ZERO_TO_ONE\";}}}");
        sources.put(API+"objects.DhApiResult","public class DhApiResult {public Object payload;}");
        sources.put(UTIL,"public class RenderUtil {public static final Object RENDER_DEF="+API+"DhApi.Delayed.definition; static {"+API+"DhApi.Delayed.initialized=true;} public static float getNearClipPlaneInBlocks(){return 1;} public static float getHeightBasedNearClipOverrideBlockDistance(){return 1;} public static float getFarClipPlaneDistanceInBlocks(){return 100;}}");
        sources.put(API+"interfaces.render.IDhApiRenderProxy","public interface IDhApiRenderProxy {Object getDhDepthTextureBlazeWrapper();Object getDepthDirection();Object getDepthRange();}");
        sources.put(API+"interfaces.config.IDhApiConfig","public interface IDhApiConfig {Object graphics();}");
        sources.put(API+"interfaces.config.client.IDhApiGraphicsConfig","public interface IDhApiGraphicsConfig {Object renderingEnabled();Object fog();Object lodShading();}");
        sources.put(API+"interfaces.config.client.IDhApiFogConfig","public interface IDhApiFogConfig {Object enableDhFog();}");
        sources.put(API+"interfaces.config.IDhApiConfigValue","public interface IDhApiConfigValue {Object getValue();void setValue(Object v);void clearValue();}");
        sources.put(API+"enums.config.EDhApiLodShading","public enum EDhApiLodShading {DISABLED}");
        sources.put(API+"Config","public class Config implements "+API+"interfaces.config.IDhApiConfig,"+API+"interfaces.config.client.IDhApiGraphicsConfig,"+API+"interfaces.config.client.IDhApiFogConfig,"+API+"interfaces.config.IDhApiConfigValue {public Object graphics(){return this;} public Object renderingEnabled(){return this;} public Object fog(){return this;} public Object lodShading(){return this;} public Object enableDhFog(){return this;} public Object getValue(){return Boolean.TRUE;} public void setValue(Object v){} public void clearValue(){}}");
        var args=new ArrayList<String>(List.of("-d",classes.toString()));
        for(var entry:sources.entrySet()) {
            int dot=entry.getKey().lastIndexOf('.');var path=classes.resolve(entry.getKey().replace('.','/')+".java");
            Files.createDirectories(path.getParent());Files.writeString(path,"package "+entry.getKey().substring(0,dot)+";\n"+entry.getValue());args.add(path.toString());
        }
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new)));
    }
    private static URLClassLoader loader() throws Exception {
        return new URLClassLoader(new URL[]{classes.toUri().toURL()},DistantBridgeInitializationTest.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name,boolean resolve) throws ClassNotFoundException {
                if(!name.startsWith(OWNER))return super.loadClass(name,resolve);
                var loaded=findLoadedClass(name);
                if(loaded==null)try(var in=getParent().getResourceAsStream(name.replace('.','/')+".class")) {
                    if(in==null)throw new ClassNotFoundException(name);var data=in.readAllBytes();loaded=defineClass(name,data,0,data.length);
                } catch(java.io.IOException error) {throw new ClassNotFoundException(name,error);}
                if(resolve)resolveClass(loaded);return loaded;
            }
        };
    }
    private static Object bridge(ClassLoader loader) throws Exception {
        var type=Class.forName(OWNER+"$Bridge",true,loader);var ctor=type.getDeclaredConstructor();ctor.setAccessible(true);return ctor.newInstance();
    }
    @Test void metadataDiscoveryDoesNotFreezeAnUnboundRenderDefinition() throws Exception {
        try(var loader=loader()) {
            var delayed=Class.forName(API+"DhApi$Delayed",true,loader);bridge(loader);
            assertFalse(delayed.getField("initialized").getBoolean(null),"metadata discovery must not initialize RenderUtil before DH registers its renderer");
            var definition=new Object();delayed.getField("definition").set(null,definition);
            var util=Class.forName(UTIL,false,loader);util.getMethod("getNearClipPlaneInBlocks").invoke(null);
            assertSame(definition,util.getField("RENDER_DEF").get(null));
        }
    }
    @Test void absentDepthDoesNotInvokeClipUtilities() throws Exception {
        try(var loader=loader()) {
            var delayed=Class.forName(API+"DhApi$Delayed",true,loader);var bridge=bridge(loader);
            var input=bridge.getClass().getDeclaredMethod("input",int.class,int.class);input.setAccessible(true);input.invoke(bridge,1200,675);
            assertFalse(delayed.getField("initialized").getBoolean(null),"a missing DH depth texture is a not-ready frame, not permission to initialize clip utilities");
        }
    }
}
