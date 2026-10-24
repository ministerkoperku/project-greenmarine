package com.koper.greenmarine.client;
import com.koper.greenmarine.api.material.MaterialAddons;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class ResourceMaterialsTest {
 @TempDir Path root;
 @AfterEach void clear(){MaterialAddons.reload(List.of());}
 @Test void discoversStandaloneModAddonWithoutFabricResourceApi() throws Exception {
  var dir=root.resolve("assets/robot/greenmarine/materials");Files.createDirectories(dir);
  Files.writeString(dir.resolve("body.json"),"{\"schema\":1,\"id\":\"robot:body\",\"textures\":{\"robot:textures/body.png\":{\"default\":{\"surface\":\"metal\",\"roughness\":0.3,\"reflection\":0.6,\"emission\":0}}}}");
  var sources=ResourceMaterials.discover(List.of(root),id->null);
  assertEquals(Set.of("robot:greenmarine/materials/body.json"),sources.keySet());
  assertTrue(MaterialAddons.reload(List.copyOf(sources.values())).isEmpty());
  assertEquals(2,MaterialAddons.resolve("robot:body","test:pack",1,1).orElseThrow().pixels().get(3));
 }
 @Test void manifestOwnedAddonNeverLeaksThroughAutomaticDiscovery() throws Exception {
  String id="robot:greenmarine/materials/body.json";
  String json="{\"schema\":1,\"id\":\"robot:body\",\"textures\":{\"robot:body\":{\"default\":{\"surface\":\"metal\",\"roughness\":0.3,\"reflection\":0.6,\"emission\":0}}}}";
  var file=root.resolve("assets/robot/greenmarine/materials/body.json");Files.createDirectories(file.getParent());Files.writeString(file,json);
  var pack=com.koper.greenmarine.api.pack.ShaderPack.parse(com.google.gson.JsonParser.parseString("{\"schema\":1,\"id\":\"robot:shader\",\"passes\":[],\"materialAddons\":[\""+id+"\"]}").getAsJsonObject());
  var owned=new MaterialAddons.Source(id,json,key->null,"robot:shader");
  var loaded=new com.koper.greenmarine.api.pack.PackCatalog.LoadedPack(pack,"",Map.of(),Map.of(),List.of(owned));
  var discovered=new LinkedHashMap<>(ResourceMaterials.discover(List.of(root),key->null));
  for(boolean mounted:new boolean[]{false,true}) {
   if(mounted)discovered.put(id,new MaterialAddons.Source(id,json,key->null,null));
   assertTrue(MaterialAddons.reload(ResourceMaterials.sources(discovered,List.of(loaded))).isEmpty());
   assertTrue(MaterialAddons.resolve("robot:body","robot:shader",1,1).isPresent());
   assertTrue(MaterialAddons.resolve("robot:body","other:shader",1,1).isEmpty(),"Owned addon leaked, mounted="+mounted);
  }
 }
}
