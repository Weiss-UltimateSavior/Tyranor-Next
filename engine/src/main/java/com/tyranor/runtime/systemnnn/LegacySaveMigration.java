package com.tyranor.runtime.systemnnn;

import static com.tyranor.runtime.systemnnn.GameSavePaths.*;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Imports complete files while the shared save directory is locked. */
final class LegacySaveMigration {
    private static final String CHART_DIR=PREFIX+CHART;
    private final Path saves, stage, ready;
    private final Map<Path,Path> copies=new LinkedHashMap<>();
    private boolean foundLegacy;

    LegacySaveMigration(Path saves) {
        this.saves=saves;
        stage=saves.resolve(SystemNnnHost.SAVE_MIGRATION_DIRECTORY);
        ready=stage.resolve("ready");
    }

    void migrate(File game,File legacyFiles) throws IOException {
        try {
            exists(saves.resolve(CHART_DIR),true);
            finishStage();
            if(legacyFiles==null)throw new IOException("无法访问旧版应用存档目录");
            Path files=legacyFiles.toPath();
            String key=UUID.nameUUIDFromBytes(game.getPath().getBytes(StandardCharsets.UTF_8)).toString();
            Path migrated=files.resolve(PREFIX+".migrated-"+key);
            if(exists(migrated,false))return;
            plan(files,key);
            Path host=files.resolve("systemnnn");
            if(exists(host,true))plan(host.resolve(key),key);
            if(!copies.isEmpty()){
                Files.createDirectory(stage);
                for(Map.Entry<Path,Path> copy:copies.entrySet()){
                    Path target=stage.resolve(copy.getKey()), source=copy.getValue();
                    Files.createDirectories(target.getParent());
                    Files.copy(source,target);
                    Files.setLastModifiedTime(target,Files.getLastModifiedTime(source));
                    try(FileChannel channel=FileChannel.open(target,StandardOpenOption.WRITE)){channel.force(true);}
                }
                // A ready stage can be completed by either host even if the original process exits.
                Files.createFile(ready);
                finishStage();
            }
            if(foundLegacy)Files.createFile(migrated);
        } catch(IOException | UncheckedIOException | SecurityException e) {
            throw new IOException("无法迁移游戏存档 · "+e.getMessage(),e);
        }
    }

    private void plan(Path files,String key) throws IOException {
        if(!exists(files,true))return;
        try(DirectoryStream<Path> candidates=Files.newDirectoryStream(files,key+"*")){
            for(Path file:candidates){
                String suffix=file.getFileName().toString().substring(key.length());
                Integer slot=SaveSlots.committedSlot(suffix);
                if(slot!=null)planFile(files,key,SaveSlots.suffix(slot),THUMB);
            }
        }
        planFile(files,key,SaveSlots.LAST_SELECTION,null);
        planFile(files,key,READ,".ext");
        planFile(files,key,GALLERY,null);
        planChart(files.resolve(key+CHART));
    }

    private void planFile(Path files,String key,String suffix,String companion) throws IOException {
        Path source=committed(files.resolve(key+suffix));
        if(source==null)return;
        foundLegacy=true;
        Path name=saves.getFileSystem().getPath(PREFIX+suffix);
        if(copies.containsKey(name) || committed(saves.resolve(name))!=null)return;
        if(companion!=null){
            Path sideName=saves.getFileSystem().getPath(PREFIX+suffix+companion);
            Path existing=committed(saves.resolve(sideName));
            if(suffix.equals(READ) && existing!=null)
                throw new IOException("目标已有全局进度扩展，但缺少对应的基础文件 · "+existing);
            Path side=committed(files.resolve(key+suffix+companion));
            if(existing==null && side!=null)copies.put(sideName,side);
        }
        copies.put(name,source);
    }

    private void planChart(Path old) throws IOException {
        if(!exists(old,true))return;
        try(DirectoryStream<Path> pages=Files.newDirectoryStream(old)){
            for(Path page:pages){
                if(!chartPage(page) || !exists(page,true))continue;
                Path targetPage=saves.resolve(CHART_DIR).resolve(page.getFileName());
                exists(targetPage,true);
                try(DirectoryStream<Path> nodes=Files.newDirectoryStream(page)){
                    for(Path node:nodes){
                        if(!chartNode(node) || !exists(node,false))continue;
                        foundLegacy=true;
                        Path target=targetPage.resolve(node.getFileName());
                        if(!exists(target,false))copies.putIfAbsent(saves.relativize(target),node);
                    }
                }
            }
        }
    }

    private void finishStage() throws IOException {
        if(!exists(stage,true))return;
        boolean complete=exists(ready,false);
        List<Path> pending;
        try(Stream<Path> stream=Files.walk(stage)){
            // Companions sort before their base in reverse order. Directories follow their contents.
            pending=stream.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
        }
        for(Path path:pending){
            if(path.equals(stage)||path.equals(ready))continue;
            Path relative=stage.relativize(path);
            boolean directory=chartDirectory(relative);
            if(!(directory||ownedFile(relative)) || !exists(path,directory))
                throw new IOException("无法识别迁移暂存项 · "+path);
        }
        for(Path path:pending){
            if(path.equals(stage)||path.equals(ready))continue;
            Path relative=stage.relativize(path);
            if(!complete || chartDirectory(relative))Files.delete(path);
            else publish(path,relative);
        }
        Files.deleteIfExists(ready);
        Files.delete(stage);
    }

    private void publish(Path source,Path relative) throws IOException {
        Path target=saves.resolve(relative);
        if(relative.getNameCount()>1){
            exists(saves.resolve(CHART_DIR),true);
            exists(target.getParent(),true);
            Files.createDirectories(target.getParent());
        }
        String name=target.getFileName().toString();
        Path base=null;
        if(name.endsWith(THUMB))base=target.resolveSibling(name.substring(0,name.length()-THUMB.length()));
        else if(name.equals(PREFIX+READ+".ext"))base=saves.resolve(PREFIX+READ);
        if(committed(target)!=null || base!=null && committed(base)!=null)Files.delete(source);
        else Files.move(source,target);
    }

    static boolean ownedFile(Path relative) {
        if(relative.getNameCount()==3)
            return relative.getName(0).toString().equals(CHART_DIR) &&
                chartPage(relative.getName(1)) && chartNode(relative.getName(2));
        if(relative.getNameCount()!=1)return false;
        String name=relative.toString();
        if(name.equals(PREFIX+READ) || name.equals(PREFIX+READ+".ext") || name.equals(PREFIX+GALLERY) ||
            name.equals(PREFIX+SaveSlots.LAST_SELECTION) || name.equals(PREFIX+SaveSlots.SLOT_UI))return true;
        if(!name.startsWith(PREFIX))return false;
        String suffix=name.substring(PREFIX.length());
        if(suffix.endsWith(THUMB))suffix=suffix.substring(0,suffix.length()-THUMB.length());
        return SaveSlots.slotForSuffix(suffix)!=null;
    }

    static boolean chartDirectory(Path relative) {
        return relative.getName(0).toString().equals(CHART_DIR) &&
            (relative.getNameCount()==1 || relative.getNameCount()==2 && chartPage(relative.getName(1)));
    }
    private static boolean chartPage(Path path){return path.getFileName().toString().matches("chart[1-9][0-9]*");}
    private static boolean chartNode(Path path){return path.getFileName().toString().matches("slot[0-9]+\\.dat");}

    private static Path committed(Path path) throws IOException {
        Path backup=path.resolveSibling(path.getFileName()+".bak");
        if(exists(backup,false))return backup;
        return exists(path,false)?path:null;
    }
    private static boolean exists(Path path,boolean directory) throws IOException {
        BasicFileAttributes attributes;
        try{attributes=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}
        catch(NoSuchFileException e){return false;}
        if(!(directory?attributes.isDirectory():attributes.isRegularFile()))
            throw new IOException("文件类型不符 · "+path);
        return true;
    }
}
