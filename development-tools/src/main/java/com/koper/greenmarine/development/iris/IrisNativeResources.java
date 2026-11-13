package com.koper.greenmarine.development.iris;

import com.google.gson.*;
import com.koper.greenmarine.development.IrisProjectExporter;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Pure resource lowering for the standalone converter; no Minecraft or native renderer classes.
 * A complete result means that the resource semantics have a native representation, not that a
 * program compiles, its host inputs exist, or the converted pack has passed rendering acceptance.
 * Unsupported declarations remain explicit requirements and always make complete() false.
 *
 * <p>World targets/resources use the native manifest JSON schema directly. Image bindings retain
 * the separate storage-image and sampler names; buffer bindings use original Iris binding numbers,
 * not the translated Vulkan descriptor numbers. Texture bindings retain paths/descriptors for a
 * future upload implementation and are never substituted with blank writable images. Passes are
 * ordered computes-before-graphics within each numerically sorted source program. Final passes
 * have no named outputs because they present to the scene. Geometry passes have no nativePhase;
 * callers must retain Minecraft's dispatch ordering. Flips/mipmaps are source intent, not an
 * assertion that the runtime executes those operations. Every diagnostic is a resource blocker.
 */
public final class IrisNativeResources {
    public record Diagnostic(String code,String world,String path,String message) {}
    public record ImageBinding(String image,String sampler,int dimensions,boolean clearEachFrame) {}
    public record BufferBinding(String group,String block,int binding,String resource) {}
    public record TextureBinding(String key,String scope,String sampler,String path,List<String> descriptor) {
        public TextureBinding {descriptor=List.copyOf(descriptor);}
    }
    /** nativePhase is null for host geometry programs, whose ordering is owned by Minecraft. */
    public record Pass(String program,String source,String kind,String nativePhase,Map<Integer,String> outputs,
                       Map<String,Boolean> flips,Set<String> mipmaps,String groups) {
        public Pass {outputs=immutable(outputs);flips=immutable(flips);mipmaps=Collections.unmodifiableSet(new LinkedHashSet<>(mipmaps));}
    }
    public record World(String folder,JsonObject targets,JsonObject resources,List<ImageBinding> imageBindings,
                        List<BufferBinding> bufferBindings,List<TextureBinding> textureBindings,List<Pass> passes,List<Diagnostic> diagnostics) {
        public World {targets=targets.deepCopy();resources=resources.deepCopy();imageBindings=List.copyOf(imageBindings);bufferBindings=List.copyOf(bufferBindings);textureBindings=List.copyOf(textureBindings);passes=List.copyOf(passes);diagnostics=List.copyOf(diagnostics);}
        @Override public JsonObject targets(){return targets.deepCopy();}
        @Override public JsonObject resources(){return resources.deepCopy();}
        public boolean complete(){return diagnostics.isEmpty();}
    }
    public record Result(List<World> worlds,List<Diagnostic> diagnostics) {
        public Result {worlds=List.copyOf(worlds);diagnostics=List.copyOf(diagnostics);}
        public boolean complete(){return diagnostics.isEmpty()&&worlds.stream().allMatch(World::complete);}
    }
    private static final Set<String> FORMATS=Set.of("RGBA8","RGBA16F","RGBA32F","R32F","RG32F","R16F","RG16F","R32UI","RG32UI","RGBA32UI","R11G11B10F");
    private static final Set<String> UINT=Set.of("R32UI","RG32UI","RGBA32UI");
    private static final Map<String,String> ALIASES=Map.of("gcolor","colortex0","gdepth","colortex1","gnormal","colortex2","composite","colortex3","gaux1","colortex4","gaux2","colortex5","gaux3","colortex6","gaux4","colortex7");
    private static final Pattern DIRECTIVE=Pattern.compile("(colortex[0-9]+|gcolor|gdepth|gnormal|composite|gaux[1-4])(Format|Clear|ClearColor)");
    private static final Pattern REFERENCE=Pattern.compile("\\b(colortex[0-9]+|colorimg[0-9]+|gcolor|gdepth|gnormal|gaux[1-4])\\b");
    private static final Pattern MIPMAP=Pattern.compile("(colortex[0-9]+|gcolor|gdepth|gnormal|composite|gaux[1-4])MipmapEnabled");
    private static final Pattern DECIMAL=Pattern.compile("([+-]?)\\s*((?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?)[fFlLuU]*");
    private IrisNativeResources() {}
    public static Result compile(IrisPipelinePlan plan){return compile(plan,List.of());}
    public static Result compile(IrisPipelinePlan plan,List<IrisProjectExporter.Group> groups) {
        Objects.requireNonNull(plan);Objects.requireNonNull(groups);
        var worlds=new ArrayList<World>();var diagnostics=new ArrayList<Diagnostic>();
        for(var world:plan.worlds()) {
            var lowered=world(plan,world,groups);worlds.add(lowered);diagnostics.addAll(lowered.diagnostics());
        }
        return new Result(worlds,diagnostics);
    }
    private static World world(IrisPipelinePlan plan,IrisPipelinePlan.World world,List<IrisProjectExporter.Group> groups) {
        var d=new ArrayList<Diagnostic>();var targets=new JsonObject();var resources=new JsonObject();
        var images=new JsonObject();var buffers=new JsonObject();resources.add("images",images);resources.add("buffers",buffers);
        var imageBindings=new ArrayList<ImageBinding>();var bufferBindings=new ArrayList<BufferBinding>();
        var textureBindings=new ArrayList<TextureBinding>();var passes=new ArrayList<Pass>();
        var used=new TreeSet<String>(Comparator.comparingInt(IrisNativeResources::targetIndex));
        var constants=new LinkedHashMap<String,IrisPipelinePlan.Constant>();var origins=new HashMap<String,String>();
        boolean legacyDepth=false;
        for(var program:world.programs())if(program.enabled()&&!program.optionalIntegration()) {
            var stages=new ArrayList<IrisPipelinePlan.Stage>(program.stages().values());stages.addAll(program.computes());
            for(var stage:stages) {
                if(!stage.preprocessed()){issue(d,"STAGE_NOT_PREPROCESSED",world.folder(),stage.path(),"Resource directives could not be selected by preprocessing");continue;}
                if(Pattern.compile("\\buniform\\s+\\w+\\s+gdepth\\b").matcher(stage.preprocessedText()).find())legacyDepth=true;
                var ref=REFERENCE.matcher(stage.preprocessedText());while(ref.find())addTarget(used,canonical(ref.group(1)),d,world.folder(),stage.path());
                for(var entry:stage.constants().entrySet()) {
                    var directive=DIRECTIVE.matcher(entry.getKey());if(!directive.matches())continue;
                    String name=canonical(directive.group(1))+directive.group(2);addTarget(used,canonical(directive.group(1)),d,world.folder(),stage.path());
                    var old=constants.putIfAbsent(name,entry.getValue());origins.putIfAbsent(name,stage.path());
                    if(old!=null&&!normalized(old.expression()).equals(normalized(entry.getValue().expression())))issue(d,"CONFLICTING_TARGET_DIRECTIVE",world.folder(),stage.path(),name+" conflicts with "+origins.get(name));
                }
            }
            for(var compute:program.computes())passes.add(pass(plan,world,program,compute,true,used,d));
            var fragment=program.stages().get(IrisStage.FRAGMENT);
            if(fragment!=null)passes.add(pass(plan,world,program,fragment,false,used,d));
        }
        for(var property:plan.renderSettings().entrySet())if(property.getKey().startsWith("size.buffer.")) {
            String name=canonical(property.getKey().substring(12));
            if(name.matches("colortex[0-9]+"))addTarget(used,name,d,world.folder(),property.getKey());
        }
        for(String name:used)try {var target=target(plan,name,constants,legacyDepth,d,world.folder());if(target!=null)targets.add(name,target);}
        catch(IllegalArgumentException invalid){issue(d,"INVALID_TARGET",world.folder(),name,invalid.getMessage());}
        for(var pass:passes) {
            String extent=null;
            for(String name:pass.outputs().values())if(targets.has(name)) {
                var target=targets.getAsJsonObject(name);String shape=target.has("size")?"size:"+target.get("size"):"scale:"+target.get("scale").getAsDouble();
                if(extent!=null&&!extent.equals(shape))issue(d,"TARGET_DIMENSION_MISMATCH",world.folder(),pass.source(),"All MRT attachments of a pass must have identical extents");
                extent=shape;
                if(pass.kind().equals("WORLD")&&(!target.has("scale")||target.get("scale").getAsDouble()!=1))issue(d,"WORLD_TARGET_DIMENSIONS",world.folder(),pass.source(),name+" must match the full-resolution borrowed host depth attachment");
            }
        }
        for(var image:plan.images()) {
            imageBindings.add(new ImageBinding(image.name(),image.sampler().equals("none")?null:image.sampler(),image.dimensions().size(),image.clear()));
            try {
                bindingName(image.name());String format=format(image.internalFormat());
                var declaration=new JsonObject();declaration.addProperty("format",format);
                if(image.relative()) {
                    if(image.dimensions().size()!=2||!image.dimensions().get(0).equals(image.dimensions().get(1)))throw new IllegalArgumentException("Native relative images require equal x/y scale and two dimensions");
                    double scale=image.dimensions().getFirst();if(!Set.of(1d,.75,.5,.25).contains(scale))throw new IllegalArgumentException("Native relative image scale must be 1/0.75/0.5/0.25");
                    declaration.addProperty("scale",scale);
                }else {
                    var size=new JsonArray();int axis=0;
                    for(double extent:image.dimensions()) {int maximum=axis++==2?2048:8192;if(extent<1||extent>maximum||extent!=Math.rint(extent))throw new IllegalArgumentException("Image extent exceeds native bounds");size.add((int)extent);}
                    declaration.add("size",size);
                }
                declaration.addProperty("clearEachFrame",image.clear());
                images.add(image.name(),declaration);
            }catch(IllegalArgumentException invalid){issue(d,invalid.getMessage().startsWith("Unsupported format")?"UNSUPPORTED_FORMAT":"INVALID_IMAGE",world.folder(),"image."+image.name(),invalid.getMessage());}
        }
        lowerBuffers(plan,world,groups,buffers,bufferBindings,d);
        for(var texture:plan.textures()) {
            textureBindings.add(new TextureBinding(texture.key(),texture.scope(),texture.sampler(),texture.path(),texture.extraTokens()));
            issue(d,"CUSTOM_TEXTURE_UPLOAD",world.folder(),texture.key(),"Native pack resources have no file/raw custom texture upload declaration: "+texture.path());
        }
        for(var diagnostic:plan.diagnostics())if(Set.of("INVALID_RESOURCE","INVALID_PROPERTY","STAGE_PREPROCESS_FAILED","MULTIPLE_TARGET_DIRECTIVES").contains(diagnostic.code())) {
            if(diagnostic.path().equals("shaders.properties")||diagnostic.path().startsWith(world.folder().isEmpty()?"":world.folder()+"/"))
                issue(d,diagnostic.code(),world.folder(),diagnostic.path(),diagnostic.message());
        }
        if(targets.size()>64||images.size()>16||buffers.size()>16)issue(d,"NATIVE_RESOURCE_BUDGET",world.folder(),"shaders.properties","Native manifest limits: 64 targets, 16 storage images and 16 storage buffers");
        for(String name:images.keySet())if(targets.has(name)||buffers.has(name))issue(d,"RESOURCE_NAME_COLLISION",world.folder(),name,"Target/image/buffer names must be unique");
        for(String name:buffers.keySet())if(targets.has(name))issue(d,"RESOURCE_NAME_COLLISION",world.folder(),name,"Target/image/buffer names must be unique");
        return new World(world.folder(),targets,resources,imageBindings,bufferBindings,textureBindings,passes,d);
    }
    private static JsonObject target(IrisPipelinePlan plan,String name,Map<String,IrisPipelinePlan.Constant> constants,boolean legacyDepth,List<Diagnostic> d,String world) {
        String format;
        String declared=expression(constants,name+"Format","RGBA");
        try {format=format(legacyDepth&&name.equals("colortex1")&&declared.strip().equals("RGBA")?"RGBA32F":declared);}
        catch(IllegalArgumentException invalid){issue(d,"UNSUPPORTED_FORMAT",world,name,invalid.getMessage());return null;}
        boolean clear=bool(expression(constants,name+"Clear","true"));
        String color=expression(constants,name+"ClearColor",null);
        // fogColor is frame-dependent; inventing a fixed black clear changes sky/transparency.
        if(clear&&color==null&&name.equals("colortex0")) {issue(d,"DYNAMIC_TARGET_CLEAR",world,name,"Default colortex0 clear needs frame fogColor with alpha 1; native targets currently accept only literal clear values");return null;}
        List<Double> values=color==null?(name.equals("colortex1")?List.of(1d,1d,1d,1d):List.of(0d,0d,0d,0d)):vector(color,4);
        var json=new JsonObject();json.addProperty("format",format);json.addProperty("clearEachFrame",clear);var components=new JsonArray();
        for(double value:values) {
            if(!Double.isFinite(value)||Math.abs(value)>Float.MAX_VALUE)throw new IllegalArgumentException("Clear components must fit float32");
            components.add(UINT.contains(format)?(double)Integer.toUnsignedLong(Float.floatToRawIntBits((float)value)):value);
        }
        json.add("clear",components);String size=plan.renderSettings().get("size.buffer."+name);
        if(size==null)for(var alias:ALIASES.entrySet())if(alias.getValue().equals(name))size=plan.renderSettings().get("size.buffer."+alias.getKey());
        if(size==null){json.addProperty("scale",1);return json;}
        String[] extents=size.strip().split("\\s+");if(extents.length!=2)throw new IllegalArgumentException("size.buffer requires two dimensions");
        if(extents[0].matches("[0-9]+")&&extents[1].matches("[0-9]+")) {
            int width=Integer.parseInt(extents[0]),height=Integer.parseInt(extents[1]);if(width<1||height<1||width>16384||height>16384)throw new IllegalArgumentException("Target dimensions must be in 1..16384");
            var array=new JsonArray();array.add(width);array.add(height);json.add("size",array);
        }else {
            double x=number(extents[0]),y=number(extents[1]);if(x!=y||x<=0||x>4)throw new IllegalArgumentException("Native target scales require equal x/y in (0,4]");json.addProperty("scale",x);
        }
        return json;
    }
    private static Pass pass(IrisPipelinePlan plan,IrisPipelinePlan.World world,IrisPipelinePlan.Program program,IrisPipelinePlan.Stage stage,boolean compute,Set<String> used,List<Diagnostic> d) {
        var outputs=new LinkedHashMap<Integer,String>();var flips=new LinkedHashMap<String,Boolean>();var mipmaps=new LinkedHashSet<String>();
        String kind=compute?"COMPUTE":program.phase()==IrisPipelinePlan.Phase.GBUFFERS?"WORLD":program.phase()==IrisPipelinePlan.Phase.SHADOW?"SHADOW":"SCENE";
        boolean geometry=kind.equals("WORLD")||kind.equals("SHADOW");
        String phase=geometry?null:switch(program.phase()) {case SETUP,BEGIN,SHADOWCOMP,PREPARE,DEFERRED,COMPOSITE,FINAL -> program.phase().name();default -> null;};
        if(phase==null&&!geometry)issue(d,"UNSUPPORTED_PASS_PHASE",world.folder(),stage.path(),"No native phase for "+program.phase());
        if(!compute) {
            String local="scale."+(world.folder().isEmpty()?"":world.folder()+"/")+program.name();
            String viewport=plan.renderSettings().getOrDefault(local,plan.renderSettings().get("scale."+program.name()));
            if(viewport!=null)try {
                String[] parts=viewport.strip().split("\\s+");boolean identity=(parts.length==1||parts.length==3)&&number(parts[0])==1;
                if(parts.length==3)identity&=number(parts[1])==0&&number(parts[2])==0;
                if(!identity)issue(d,"PASS_VIEWPORT",world.folder(),local,"Source viewport transform "+viewport+" needs an explicit native viewport adapter; it does not change target allocation size");
            }catch(IllegalArgumentException invalid){issue(d,"PASS_VIEWPORT",world.folder(),local,invalid.getMessage());}
        }
        if(!compute&&program.phase()!=IrisPipelinePlan.Phase.FINAL) {
            List<Integer> indices=stage.targets().isEmpty()?List.of(0,1,2,3,4,5,6,7):stage.targets();var duplicates=new HashSet<Integer>();
            Set<Integer> declared=fragmentOutputs(program,stage,d,world.folder());
            if(!stage.targets().isEmpty()&&(declared.size()!=indices.size()||declared.stream().anyMatch(i->i<0||i>=indices.size())))
                issue(d,"FRAGMENT_OUTPUT_MISMATCH",world.folder(),stage.path(),"Explicit render-target count/locations differ from the fragment output interface "+declared);
            for(int location:declared)if(location<0||location>=indices.size())issue(d,"FRAGMENT_OUTPUT_MISMATCH",world.folder(),stage.path(),"Fragment location "+location+" has no Iris target mapping");
            for(int location=0;location<indices.size();location++) {
                if(!declared.contains(location))continue;
                int index=indices.get(location);String target="colortex"+index;
                if(kind.equals("SHADOW")){issue(d,"SHADOW_RENDER_TARGETS",world.folder(),stage.path(),"Shadow color attachments require the shadow render-target path");break;}
                addTarget(used,target,d,world.folder(),stage.path());outputs.put(location,target);
                if(!duplicates.add(index))issue(d,"DUPLICATE_TARGET_OUTPUT",world.folder(),stage.path(),"Multiple fragment locations map to "+target);
                if(!geometry) {
                    String local="flip."+(world.folder().isEmpty()?"":world.folder()+"/")+program.name()+"."+target;
                    String localPrefix="flip."+(world.folder().isEmpty()?"":world.folder()+"/")+program.name()+".";
                    String genericPrefix="flip."+program.name()+".";
                    try {boolean flip=bool(targetSetting(plan.renderSettings(),localPrefix,target,targetSetting(plan.renderSettings(),genericPrefix,target,"true")));flips.put(target,flip);
                        if(!flip)issue(d,"SUPPRESSED_TARGET_FLIP",world.folder(),local,"Native named-target writes publish their new version; Iris requested suppressing that flip");
                    }catch(IllegalArgumentException invalid){issue(d,"INVALID_FLIP",world.folder(),local,invalid.getMessage());}
                }
            }
            for(int location=0;location<outputs.size();location++)if(!outputs.containsKey(location))issue(d,"SPARSE_FRAGMENT_OUTPUTS",world.folder(),stage.path(),"Native named outputs require consecutive locations starting at zero");
        }
        for(var entry:stage.constants().entrySet()) {
            var m=MIPMAP.matcher(entry.getKey());if(!m.matches())continue;
            try {if(bool(entry.getValue().expression())) {String target=canonical(m.group(1));mipmaps.add(target);addTarget(used,target,d,world.folder(),stage.path());issue(d,"TARGET_MIPMAPS",world.folder(),stage.path(),target+" requires mipmap generation before this program");}}
            catch(IllegalArgumentException invalid){issue(d,"INVALID_MIPMAP",world.folder(),stage.path(),invalid.getMessage());}
        }
        String groups=null;
        if(compute) {
            var count=stage.constants().get("workGroups");
            try {
                if(count!=null) {var v=vector(count.expression(),3);var tokens=new ArrayList<String>();for(double x:v){if(x<1||x>65535||x!=Math.rint(x))throw new IllegalArgumentException("Dispatch group counts must be integers in 1..65535");tokens.add(Integer.toString((int)x));}groups=String.join(",",tokens);}
                else if(stage.constants().containsKey("workGroupsRender")) {
                    var factors=vector(stage.constants().get("workGroupsRender").expression(),2);
                    if(factors.equals(List.of(1d,1d)))groups="screen";
                    else issue(d,"RELATIVE_COMPUTE_DISPATCH",world.folder(),stage.path(),"Nonidentity workGroupsRender factors need native dispatch scaling: "+factors);
                }else groups="screen";
            }catch(IllegalArgumentException invalid){issue(d,"INVALID_COMPUTE_DISPATCH",world.folder(),stage.path(),invalid.getMessage());}
        }
        return new Pass(program.name(),stage.path(),kind,phase,outputs,flips,mipmaps,groups);
    }
    /** Use the same source linker as export so modern output locations match the actual program ABI. */
    private static Set<Integer> fragmentOutputs(IrisPipelinePlan.Program program,IrisPipelinePlan.Stage fragment,List<Diagnostic> d,String world) {
        String text;
        try {
            var sources=new EnumMap<IrisStage,String>(IrisStage.class);
            program.stages().forEach((kind,stage)->sources.put(kind,stage.preprocessedText()));
            // An inventory can contain a fragment without a vertex stage. Its legacy outputs are
            // still explicit after translation, but complete program compilation remains separate.
            text=sources.containsKey(IrisStage.VERTEX)?IrisProgramLinker.link(sources).stages().get(IrisStage.FRAGMENT).text():IrisTranslator.translate(fragment.preprocessedText(),IrisStage.FRAGMENT).text();
        }catch(RuntimeException invalid){issue(d,"OUTPUT_INTERFACE_UNRESOLVED",world,fragment.path(),invalid.getMessage());return Set.of();}
        var result=new TreeSet<Integer>();
        var declarations=Pattern.compile("layout\\s*\\(([^)]*)\\)\\s*(?:(?:flat|smooth|noperspective|centroid|sample|invariant|precise)\\s+)*out\\s+(?:(?:highp|mediump|lowp)\\s+)?(\\w+)\\s+\\w+\\s*(?:\\[\\s*(\\d+)\\s*])?\\s*;").matcher(text);
        while(declarations.find()) {
            var at=Pattern.compile("\\blocation\\s*=\\s*(\\d+)\\b").matcher(declarations.group(1));if(!at.find())continue;
            int location=Integer.parseInt(at.group(1)),count=declarations.group(3)==null?1:Integer.parseInt(declarations.group(3));
            if(declarations.group(2).matches("mat[2-4](x[2-4])?"))count=Math.multiplyExact(count,declarations.group(2).charAt(3)-'0');
            if(location>31||count>32||location+count>32){issue(d,"FRAGMENT_OUTPUT_LIMIT",world,fragment.path(),"Fragment output interface exceeds 32 locations");continue;}
            for(int i=0;i<count;i++)result.add(location+i);
        }
        if(result.isEmpty())issue(d,"OUTPUT_INTERFACE_UNRESOLVED",world,fragment.path(),"No supported location-decorated fragment output declarations");
        return result;
    }
    private static void lowerBuffers(IrisPipelinePlan plan,IrisPipelinePlan.World world,List<IrisProjectExporter.Group> groups,JsonObject buffers,List<BufferBinding> bindings,List<Diagnostic> d) {
        var names=new TreeMap<Integer,Set<String>>();var selected=groups.stream().filter(g->g.world().equals(world.folder())&&!g.optionalIntegration()&&g.linked()).toList();
        var sourceBindings=new HashMap<String,Integer>();
        for(var group:selected)for(var block:group.blocks())if(block.storage().equals("buffer")) {
            int source=sourceBinding(world,group,block);sourceBindings.put(group.id()+"/"+block.name(),source);
            if(source<0)issue(d,"BUFFER_SOURCE_BINDING_UNRESOLVED",world.folder(),group.id(),"Cannot establish original Iris binding for block "+block.name()+" from its linked descriptor "+block.binding());
            else names.computeIfAbsent(source,k->new TreeSet<>()).add(block.name());
        }
        var declaredBindings=new HashSet<Integer>();for(var buffer:plan.buffers())declaredBindings.add(buffer.binding());
        for(var group:selected)for(var block:group.blocks())if(block.storage().equals("buffer")) {
            int source=sourceBindings.get(group.id()+"/"+block.name());if(source>=0&&!declaredBindings.contains(source))
                issue(d,"BUFFER_DECLARATION_MISSING",world.folder(),group.id(),"Block "+block.name()+" requires undeclared bufferObject."+source);
        }
        var identities=new HashMap<String,Integer>();
        long total=0;
        for(var buffer:plan.buffers()) {
            String path="bufferObject."+buffer.binding();
            if(buffer.relative()){issue(d,"RELATIVE_BUFFER_SIZE",world.folder(),path,"Window-relative SSBO allocation cannot use a fixed native byte count");continue;}
            if(buffer.byteSize()<4||buffer.byteSize()>(64L<<20)||buffer.byteSize()%4!=0){issue(d,"BUFFER_SIZE",world.folder(),path,"Native buffer size must be a multiple of four in 4..64 MiB");continue;}
            Set<String> owners=names.getOrDefault(buffer.binding(),Set.of());
            if(owners.isEmpty()){issue(d,"BUFFER_BLOCK_UNRESOLVED",world.folder(),path,"No linked buffer block at source binding "+buffer.binding());continue;}
            if(owners.size()!=1){issue(d,"BUFFER_BINDING_ALIASES",world.folder(),path,"Several block names share Iris storage identity "+owners+"; native names need an alias binding mechanism");continue;}
            String name=owners.iterator().next();
            try {bindingName(name);}catch(IllegalArgumentException invalid){issue(d,"INVALID_BUFFER_NAME",world.folder(),path,invalid.getMessage());continue;}
            Integer previous=identities.putIfAbsent(name,buffer.binding());
            if(previous!=null&&previous!=buffer.binding()){issue(d,"BUFFER_IDENTITY_COLLISION",world.folder(),path,"Same block name has independent source buffer bindings "+previous+" and "+buffer.binding());continue;}
            var declaration=new JsonObject();declaration.addProperty("size",buffer.byteSize());buffers.add(name,declaration);total+=buffer.byteSize();
            for(var group:selected)for(var block:group.blocks())if(block.storage().equals("buffer")&&sourceBindings.get(group.id()+"/"+block.name())==buffer.binding())bindings.add(new BufferBinding(group.id(),block.name(),buffer.binding(),name));
        }
        if(total>(128L<<20))issue(d,"BUFFER_BUDGET",world.folder(),"shaders.properties","Native buffers exceed the 128 MiB total allocation budget");
    }
    private static int sourceBinding(IrisPipelinePlan.World world,IrisProjectExporter.Group group,IrisProgramLinker.ResourceBlock block) {
        var bindings=new HashSet<Integer>();
        for(var program:world.programs())if(program.name().equals(group.program())) {
            var stages=new ArrayList<IrisPipelinePlan.Stage>(program.stages().values());stages.addAll(program.computes());
            for(var stage:stages) {
                if(!group.stages().isEmpty()&&group.stages().stream().noneMatch(s->s.source().equals(stage.path())))continue;
                String source=stage.preprocessedText().replaceAll("/\\*[\\s\\S]*?\\*/|(?m)//[^\\r\\n]*","");
                var declarations=Pattern.compile("\\blayout\\s*\\(([^)]*)\\)\\s*(?:(?:readonly|writeonly|coherent|volatile|restrict)\\s+)*buffer\\s+"+Pattern.quote(block.name())+"\\s*\\{").matcher(source);
                while(declarations.find()){var at=Pattern.compile("\\bbinding\\s*=\\s*(\\d+)\\b").matcher(declarations.group(1));if(at.find())bindings.add(Integer.parseInt(at.group(1)));}
            }
        }
        if(bindings.size()==1)return bindings.iterator().next();
        // Explicit low bindings in externally supplied pure metadata are already source bindings.
        // Auto-allocated/high Vulkan descriptors cannot be inverted without source evidence.
        return bindings.isEmpty()&&block.binding()<IrisTranslator.SSBO_BINDING_BASE?block.binding():-1;
    }
    private static void addTarget(Set<String> targets,String name,List<Diagnostic> d,String world,String path) {
        int index=targetIndex(name);if(index<0||index>31){issue(d,"TARGET_INDEX",world,path,"Iris color target index must be in 0..31: "+name);return;}targets.add(name);
    }
    private static int targetIndex(String name){try{return Integer.parseInt(name.substring(8));}catch(RuntimeException invalid){return -1;}}
    private static String canonical(String name){if(name.startsWith("colorimg"))return "colortex"+name.substring(8);return ALIASES.getOrDefault(name,name);}
    private static String expression(Map<String,IrisPipelinePlan.Constant> values,String key,String fallback){var c=values.get(key);return c==null?fallback:c.expression();}
    private static String format(String source){String format=source.strip().toUpperCase(Locale.ROOT);if(format.equals("RGBA"))format="RGBA8";if(format.equals("R11F_G11F_B10F"))format="R11G11B10F";if(!FORMATS.contains(format))throw new IllegalArgumentException("Unsupported format "+source+"; no exact native storage/attachment format");return format;}
    private static boolean bool(String text){if(text.strip().equals("true"))return true;if(text.strip().equals("false"))return false;throw new IllegalArgumentException("Expected literal boolean: "+text);}
    private static double number(String text){var m=DECIMAL.matcher(text.strip());if(!m.matches())throw new IllegalArgumentException("Expected numeric literal: "+text);double value=Double.parseDouble(m.group(1)+m.group(2));if(!Double.isFinite(value))throw new IllegalArgumentException("Numeric literal is not finite");return value;}
    private static List<Double> vector(String text,int count){var m=Pattern.compile("[iu]?vec"+count+"\\s*\\(([^()]*)\\)").matcher(text.strip());if(!m.matches())throw new IllegalArgumentException("Expected literal vec"+count+" constructor: "+text);String[] tokens=m.group(1).split(",");if(tokens.length!=1&&tokens.length!=count)throw new IllegalArgumentException("Vector constructor has wrong component count");var values=new ArrayList<Double>();for(int i=0;i<count;i++)values.add(number(tokens[tokens.length==1?0:i]));return values;}
    private static String normalized(String expression){return expression.replaceAll("\\s+","");}
    private static String targetSetting(Map<String,String> settings,String prefix,String target,String fallback) {
        if(settings.containsKey(prefix+target))return settings.get(prefix+target);
        for(var alias:ALIASES.entrySet())if(alias.getValue().equals(target)&&settings.containsKey(prefix+alias.getKey()))return settings.get(prefix+alias.getKey());
        return fallback;
    }
    private static void bindingName(String name){if(!name.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")||name.startsWith("gl_")||name.contains("__")||Set.of("MarineFrame","ColorSampler","DepthSampler","LightmapSampler","SceneSampler","VoxelFrame","VoxelMaterials").contains(name))throw new IllegalArgumentException("Invalid native resource binding name: "+name);}
    private static void issue(List<Diagnostic> d,String code,String world,String path,String message){var next=new Diagnostic(code,world,path,message);if(!d.contains(next))d.add(next);}
    private static <K,V> Map<K,V> immutable(Map<K,V> map){return Collections.unmodifiableMap(new LinkedHashMap<>(map));}
}
