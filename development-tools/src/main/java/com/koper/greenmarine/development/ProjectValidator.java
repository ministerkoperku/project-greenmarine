package com.koper.greenmarine.development;

import com.google.gson.JsonParser;
import com.koper.greenmarine.api.LightRegistry;
import com.koper.greenmarine.api.pack.*;
import com.koper.greenmarine.script.PackScript;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Read-only project preflight. Drafts are frozen before resolving any pack resource. */
final class ProjectValidator {
    record Problem(String path,int line,String message) {}
    record Result(List<Problem> problems,int checkedFiles,int manifests) {
        Result { problems=List.copyOf(problems); }
        boolean valid(){return problems.isEmpty();}
        String summary(){
            String heading=(valid()?"Validation passed":"Validation failed")+": "+checkedFiles+" files, "+manifests+" manifests";
            if(valid())return heading;
            var text=new StringBuilder(heading);
            for(var problem:problems){
                text.append('\n').append(problem.path()).append(':').append(problem.line()).append(": ").append(problem.message());
                if(text.length()>60000){text.setLength(60000);text.append("\nFurther diagnostics omitted");break;}
            }
            return text.toString();
        }
    }
    private static final Pattern SOURCE_LINE=Pattern.compile(":(\\d+):");
    private static final Pattern NAMED_LINE=Pattern.compile("\\bline[ :]+(\\d+)",Pattern.CASE_INSENSITIVE);
    private ProjectValidator(){}

    static Result validate(ShaderProject project,Map<String,String> drafts,Function<String,String> textFallback,
                           Function<String,byte[]> binaryFallback){
        var problems=new ArrayList<Problem>();var files=new TreeSet<String>();int manifests=0;
        String projectPath=project.folder();
        try{
            // Validate every overlay path before using it, including overlays the manifest does not reference.
            Map<String,String> frozen=Map.copyOf(drafts);
            var overlays=new LinkedHashMap<Path,String>();long draftBytes=0;
            for(var entry:frozen.entrySet()){
                if(!entry.getKey().startsWith(projectPath+"/"))throw new IOException("Draft is outside this project: "+entry.getKey());
                Path path=project.workspace().assetPath(entry.getKey());
                int size=entry.getValue().getBytes(StandardCharsets.UTF_8).length;
                if(size>ShaderWorkspace.MAX_SOURCE_BYTES)throw new IOException("Draft exceeds 256 KiB: "+entry.getKey());
                draftBytes+=size;if(draftBytes>32L*1024*1024)throw new IOException("Drafts exceed 32 MiB validation budget");
                overlays.put(path,entry.getValue());
            }
            files.addAll(project.files());files.addAll(frozen.keySet());
            if(files.size()>8192)throw new IOException("Project exceeds 8192 files");
            externalBounds(project,frozen,problems);
            var resolver=new Resources(project,frozen,textFallback,binaryFallback);
            var manifestFiles=files.stream().filter(path->document(project,path,"packs")).toList();
            manifests=manifestFiles.size();
            if(manifests>64)throw new IOException("At most 64 shaderpack manifests are supported");
            if(files.stream().filter(path->document(project,path,"materials")).count()>128)throw new IOException("At most 128 material addons are supported");
            if(manifests==0)problems.add(new Problem(projectPath,1,"Project has no GreenMarine shaderpack manifest"));
            var ids=new HashSet<String>();var stages=new LinkedHashMap<String,ShaderCompiler.Stage>();
            for(String manifest:manifestFiles){
                ShaderPack pack;
                try{
                    pack=ShaderPack.parse(JsonParser.parseString(resolver.file(manifest,65536)).getAsJsonObject());
                    if(!ids.add(pack.id()))problems.add(new Problem(manifest,1,"Duplicate shaderpack: "+pack.id()));
                }catch(IOException|RuntimeException error){add(problems,manifest,error);continue;}
                for(var pass:pack.passes()){
                    if(pass.stage()==PackPass.Stage.COMPUTE)stages.put(PackCatalog.computeResource(pass.fragment()),ShaderCompiler.Stage.COMPUTE);
                    else stages.put(PackCatalog.shaderResource(pass.fragment()),ShaderCompiler.Stage.FRAGMENT);
                    if(pass.fallback()!=null)stages.put(PackCatalog.shaderResource(pass.fallback()),ShaderCompiler.Stage.FRAGMENT);
                }
                for(var program:pack.programs()){
                    stages.put(PackCatalog.shaderResource(program.fragment()),ShaderCompiler.Stage.FRAGMENT);
                    if(program.surface()!=null)stages.put(PackCatalog.shaderResource(program.surface()),ShaderCompiler.Stage.FRAGMENT);
                    if(program.vertex()!=null)stages.put(PackCatalog.shaderResource(program.vertex()).replaceFirst("\\.fsh$",".vsh"),ShaderCompiler.Stage.VERTEX);
                }
                if(pack.script()!=null){
                    String scriptPath=resolver.path(pack.script());
                    try(var script=new PackScript(pack,resolver.require(pack.script(),65536),new LightRegistry())){
                        // Constructor validates bounded initialization; update is intentionally never called.
                    }catch(IOException|RuntimeException error){add(problems,scriptPath,error);}
                }
            }
            for(var entry:stages.entrySet()){
                String id=entry.getKey(),sourcePath=resolver.path(id);
                try{
                    String source=resolver.require(id,ShaderWorkspace.MAX_SOURCE_BYTES);
                    String namespace=id.substring(0,id.indexOf(':'));
                    var result=ShaderCompiler.compile(ShaderCompiler.Language.GLSL,entry.getValue(),source,id,null,include->{
                        try{
                            String qualified=include.indexOf(':')<0?namespace+":"+include:include;
                            ShaderPack.requireId(qualified);int colon=qualified.indexOf(':');
                            return resolver.resource(qualified.substring(0,colon+1)+"shaders/include/"+qualified.substring(colon+1),ShaderWorkspace.MAX_SOURCE_BYTES);
                        }catch(IOException error){throw new UncheckedIOException(error);}
                    });
                    if(!result.success())problems.add(new Problem(sourcePath,line(result.diagnostics()),result.diagnostics()));
                }catch(IOException|RuntimeException error){add(problems,sourcePath,error);}
            }
            // Retain the shared reload validator's material semantics, mask limits and SPIR-V checks.
            var reload=new ReloadValidator(project.workspace().root(),project.workspace().root().resolve(projectPath),
                resolver::mountedText,resolver::mountedBinary,overlays);
            String rejection=reload.validate(files.stream().map(path->project.workspace().root().resolve(path)).toList());
            if(rejection!=null&&!covered(problems,rejection)){
                String path=diagnosticPath(project,files,stages.keySet(),rejection);
                problems.add(new Problem(path,line(rejection),rejection));
            }
        }catch(IOException|RuntimeException error){add(problems,projectPath,error);}
        return new Result(problems,files.size(),manifests);
    }

    private static boolean document(ShaderProject project,String path,String type){
        String[] parts=path.substring(project.folder().length()+1).split("/");
        return parts.length>=5&&parts[0].equals("assets")&&parts[2].equals("greenmarine")&&parts[3].equals(type)
            &&path.endsWith(".json")&&Arrays.stream(parts).noneMatch(part->part.startsWith("."));
    }
    /** Match ExternalPacks.directory/read: count every entry, but budget only recognized assets. */
    private static void externalBounds(ShaderProject project,Map<String,String> drafts,List<Problem> problems)throws IOException{
        Path pack=project.workspace().assetPath(project.folder()+"/.index").getParent();
        int entries=0;long bytes=0;
        try(var paths=Files.walk(pack)){
            var iterator=paths.iterator();
            while(iterator.hasNext()){
                Path file=iterator.next();
                String relative=project.workspace().root().relativize(file).toString().replace(java.io.File.separatorChar,'/');
                if(++entries>2048){problems.add(new Problem(project.folder(),1,"External pack exceeds 2048 directory entries"));return;}
                if(file.equals(pack))continue;
                project.workspace().assetPath(relative); // Reject linked or unsafe entries, including non-assets.
                if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))continue;
                String name=pack.relativize(file).toString().replace(java.io.File.separatorChar,'/');
                if(!name.startsWith("assets/"))continue;
                int slash=name.indexOf('/',7);if(slash<0)continue;
                try{ShaderPack.requireId(name.substring(7,slash)+":"+name.substring(slash+1));}
                catch(IllegalArgumentException error){add(problems,relative,error);return;}
                int limit=name.endsWith(".spv")?ShaderBinary.MAX_BYTES:262144;
                if(name.contains("/greenmarine/packs/")||name.endsWith(".lua"))limit=65536;
                long size=drafts.containsKey(relative)?drafts.get(relative).getBytes(StandardCharsets.UTF_8).length:Files.size(file);
                if(size>limit){problems.add(new Problem(relative,1,"Resource exceeds "+limit+" bytes: "+name));return;}
                bytes+=size;
                if(bytes>8L*1024*1024){problems.add(new Problem(relative,1,"External pack snapshot exceeds 8 MiB"));return;}
            }
        }
    }
    private static void add(List<Problem> problems,String path,Exception error){
        String message=error.getMessage();if(message==null)message=error.getClass().getSimpleName();
        problems.add(new Problem(path,line(message),message));
    }
    private static int line(String message){
        var matcher=SOURCE_LINE.matcher(message);if(!matcher.find()){matcher=NAMED_LINE.matcher(message);if(!matcher.find())return 1;}
        try{return Math.max(1,Integer.parseInt(matcher.group(1)));}catch(NumberFormatException ignored){return 1;}
    }
    private static boolean covered(List<Problem> problems,String rejection){
        return problems.stream().anyMatch(problem->rejection.contains(problem.message())||problem.message().contains(rejection));
    }
    private static String diagnosticPath(ShaderProject project,Set<String> files,Set<String> stages,String message){
        for(String file:files)if(message.contains(project.workspace().root().resolve(file).toString())||message.contains(file))return file;
        for(String id:stages)if(message.contains(id))return resourcePath(project,id);
        // Resource ids can also identify missing material masks or SPIR-V modules.
        var ids=Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+").matcher(message);
        while(ids.find()){
            String id=ids.group();try{ShaderPack.requireId(id);return resourcePath(project,id);}catch(IllegalArgumentException ignored){}
        }
        return project.folder();
    }
    private static String resourcePath(ShaderProject project,String id){
        int colon=id.indexOf(':');return project.folder()+"/assets/"+id.substring(0,colon)+"/"+id.substring(colon+1);
    }

    private static final class Resources {
        private final ShaderProject project;
        private final Map<String,String> drafts;
        private final Function<String,String> fallback;
        private final Function<String,byte[]> binaryFallback;
        private final Map<String,String> texts=new HashMap<>(),mounted=new HashMap<>();
        private final Map<String,byte[]> binaries=new HashMap<>();
        private long bytes;
        Resources(ShaderProject project,Map<String,String> drafts,Function<String,String> fallback,Function<String,byte[]> binaryFallback){
            this.project=project;this.drafts=drafts;this.fallback=Objects.requireNonNull(fallback);this.binaryFallback=Objects.requireNonNull(binaryFallback);
        }
        String path(String id){ShaderPack.requireId(id);return resourcePath(project,id);}
        private void bound(int size,int limit)throws IOException{
            if(size>limit)throw new IOException("Resource exceeds "+limit+" bytes");
            bytes+=size;if(bytes>32L*1024*1024)throw new IOException("Validation exceeds 32 MiB resource budget");
        }
        String file(String path,int limit)throws IOException{
            String text=texts.get(path);
            if(text==null){text=drafts.containsKey(path)?drafts.get(path):project.workspace().read(path);bound(text.getBytes(StandardCharsets.UTF_8).length,limit);texts.put(path,text);}
            else if(text.getBytes(StandardCharsets.UTF_8).length>limit)throw new IOException("Resource exceeds "+limit+" bytes: "+path);
            return text;
        }
        String resource(String id,int limit)throws IOException{
            String relative=path(id);Path local=project.workspace().assetPath(relative);
            if(drafts.containsKey(relative)||Files.exists(local,LinkOption.NOFOLLOW_LINKS))return file(relative,limit);
            String text=mountedText(id);if(text!=null&&text.getBytes(StandardCharsets.UTF_8).length>limit)throw new IOException("Mounted resource exceeds "+limit+" bytes: "+id);
            return text;
        }
        String require(String id,int limit)throws IOException{
            String text=resource(id,limit);if(text==null)throw new IOException("Missing required resource: "+id);return text;
        }
        String mountedText(String id){
            if(!mounted.containsKey(id)){
                String text=fallback.apply(id);
                if(text!=null)try{bound(text.getBytes(StandardCharsets.UTF_8).length,ShaderWorkspace.MAX_SOURCE_BYTES);}catch(IOException error){throw new UncheckedIOException(error);}
                mounted.put(id,text);
            }
            return mounted.get(id);
        }
        byte[] mountedBinary(String id){
            if(!binaries.containsKey(id)){
                byte[] value=binaryFallback.apply(id);
                if(value!=null)try{bound(value.length,4*1024*1024);value=value.clone();}catch(IOException error){throw new UncheckedIOException(error);}
                binaries.put(id,value);
            }
            byte[] value=binaries.get(id);return value==null?null:value.clone();
        }
    }
}
