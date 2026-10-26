package com.koper.greenmarine.development;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.zip.*;

/** Whole-project archives are staged before replacing a previous export. */
final class ShaderPackExporter {
    record Exported(Path file,int files,boolean includesDraft) {}
    private static final long MAX_BYTES=256L*1024*1024;
    static Exported exportZip(Path workspace,String source,String draft,Path destination)throws IOException {
        return exportProject(workspace,source,draft==null?Map.of():Map.of(source,draft),destination);
    }
    static Exported exportProject(Path workspace,String source,Map<String,String> drafts,Path destination)throws IOException {
        return exportProject(workspace,source,drafts,Map.of(),destination);
    }
    static Exported exportProject(Path workspace,String source,Map<String,String> drafts,Map<String,byte[]> images,Path destination)throws IOException {
        workspace=workspace.toAbsolutePath().normalize();destination=destination.toAbsolutePath().normalize();
        if(source==null||source.contains("\\")||source.contains(":")||source.startsWith("/"))throw new IOException("Invalid project source");
        String[] parts=source.split("/",-1);
        if(parts.length<2||Arrays.stream(parts).anyMatch(s->s.isBlank()||s.equals(".")||s.equals("..")))throw new IOException("Invalid project source");
        Path project=workspace.resolve(parts[0]);
        for(Path path=project;path!=null;path=path.getParent())if(Files.isSymbolicLink(path))throw new IOException("Linked project paths are unsupported");
        if(!Files.isDirectory(project,LinkOption.NOFOLLOW_LINKS))throw new IOException("Project directory does not exist");
        Files.createDirectories(destination.getParent());
        destination=destination.getParent().toRealPath().resolve(destination.getFileName());
        if(destination.startsWith(project.toRealPath()))throw new IOException("Choose an export destination outside the project");
        if(Files.isSymbolicLink(destination)||Files.exists(destination)&&!Files.isRegularFile(destination))throw new IOException("Destination must be a regular ZIP file");
        var files=new ArrayList<Path>();long[] total={0};
        Files.walkFileTree(project,EnumSet.noneOf(FileVisitOption.class),64,new SimpleFileVisitor<>(){
            @Override public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes attributes)throws IOException {
                if(Files.isSymbolicLink(dir))throw new IOException("Linked project directory: "+dir);return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attributes)throws IOException {
                if(!attributes.isRegularFile()||attributes.isSymbolicLink())throw new IOException("Project contains a linked or non-regular asset: "+file);
                total[0]+=attributes.size();if(total[0]>MAX_BYTES||files.size()>=8192)throw new IOException("Pack exceeds 256 MiB or 8192 files");files.add(file);return FileVisitResult.CONTINUE;
            }
        });
        var candidates=new HashMap<Path,byte[]>();
        for(var entry:drafts.entrySet()){
            Path file=new ShaderWorkspace(workspace).assetPath(entry.getKey());
            if(!file.startsWith(project)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("Draft is not a regular file in this project");
            byte[] bytes=entry.getValue().getBytes(StandardCharsets.UTF_8);
            if(bytes.length>ShaderWorkspace.MAX_SOURCE_BYTES)throw new IOException("Draft exceeds source size limit");candidates.put(file,bytes);
        }
        for(var entry:images.entrySet()) {
            Path file=new ShaderWorkspace(workspace).assetPath(entry.getKey());
            if(!file.startsWith(project)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||!entry.getKey().toLowerCase(Locale.ROOT).endsWith(".png"))throw new IOException("Image draft is outside the project");
            MaterialCanvas.previewPng(entry.getValue());candidates.put(file,entry.getValue());
        }
        Path temporary=Files.createTempFile(destination.getParent(),".greenmarine-pack-",".zip");
        try {
            long written=0;
            try(var zip=new ZipOutputStream(Files.newOutputStream(temporary))) {
                files.sort(Comparator.naturalOrder());
                for(Path file:files) {
                    if(Files.isSymbolicLink(file))throw new IOException("Project asset changed to a symbolic link");
                    String relative=project.relativize(file).toString().replace('\\','/');
                    if(relative.split("/").length>64)throw new IOException("Project is too deeply nested");
                    zip.putNextEntry(new ZipEntry(relative));
                    if(candidates.containsKey(file)){byte[] candidate=candidates.get(file);zip.write(candidate);written+=candidate.length;}
                    else try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) {
                        byte[] buffer=new byte[16384];int read;
                        while((read=input.read(buffer))!=-1){written+=read;if(written>MAX_BYTES)throw new IOException("Pack changed beyond 256 MiB during export");zip.write(buffer,0,read);}
                    }
                    if(written>MAX_BYTES)throw new IOException("Pack exceeds export size limit");zip.closeEntry();
                }
            }
            try{Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException e){Files.move(temporary,destination,StandardCopyOption.REPLACE_EXISTING);}
            return new Exported(destination,files.size(),!candidates.isEmpty());
        }finally{Files.deleteIfExists(temporary);}
    }
}
