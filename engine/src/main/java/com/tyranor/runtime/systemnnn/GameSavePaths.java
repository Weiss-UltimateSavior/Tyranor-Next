package com.tyranor.runtime.systemnnn;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Emberfall files live beside the original PC saves under their own names. */
final class GameSavePaths {
    static final String PREFIX="emberfall", READ=".read", GALLERY=".gallery", CHART=".chart", THUMB=".thumb";
    private static final Set<Path> OPEN=ConcurrentHashMap.newKeySet();
    private static final AtomicInteger OWNERS=new AtomicInteger();
    private final File game, directory;

    private GameSavePaths(File game,File directory) {
        this.game=game;
        this.directory=directory;
    }

    static GameSavePaths forGame(File root) throws IOException {
        File game=root.getCanonicalFile();
        File directory=GameResourcePaths.directory(game,"sav");
        if(directory==null)directory=new File(game,"sav");
        if(!game.isDirectory() || Files.isSymbolicLink(directory.toPath()) ||
            directory.exists() && !directory.isDirectory())
            throw new IOException("游戏存档目录无效 · "+directory);
        return new GameSavePaths(game,directory);
    }

    File directory(){return directory;}
    File file(String suffix){return new File(directory,PREFIX+suffix);}
    static boolean hasActiveAccess(){return OWNERS.get()!=0||!OPEN.isEmpty();}

    static Owner acquireOwner(){
        Owner owner=new Owner();
        OWNERS.incrementAndGet();
        return owner;
    }

    static final class Owner implements AutoCloseable {
        private boolean closed;
        @Override public synchronized void close(){
            if(closed)return;
            closed=true;
            OWNERS.decrementAndGet();
        }
    }

    Access open(File legacyFiles) throws IOException {
        Access access=lock();
        try {
            new LegacySaveMigration(directory.toPath()).migrate(game,legacyFiles);
            return access;
        } catch(IOException | RuntimeException e) {
            try{access.close();}catch(IOException closeFailure){e.addSuppressed(closeFailure);}
            throw e;
        }
    }

    Access lock() throws IOException {
        Path dir=directory.toPath();
        Files.createDirectories(dir);
        if(!Files.isDirectory(dir,LinkOption.NOFOLLOW_LINKS))throw new IOException("游戏存档目录无效 · "+directory);
        Path lockFile=file(".lock").toPath();
        // Reserve the path before opening a second channel. Closing it could release this JVM's first lock.
        if(!OPEN.add(lockFile))throw inUse();
        FileChannel channel=null;
        try {
            channel=FileChannel.open(lockFile,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
            FileLock lock;
            try{lock=channel.tryLock();}
            catch(OverlappingFileLockException e){lock=null;}
            if(lock==null)throw inUse();
            return new Access(lockFile,lock);
        } catch(IOException | RuntimeException e) {
            try{if(channel!=null)channel.close();}catch(IOException closeFailure){e.addSuppressed(closeFailure);}
            finally{OPEN.remove(lockFile);}
            throw e;
        }
    }

    private static IOException inUse(){return new SystemNnnHost.SaveBusyException();}

    static final class Access implements Closeable {
        private final Path path;
        private final FileLock lock;
        private boolean closed;
        private Access(Path path,FileLock lock){this.path=path;this.lock=lock;}
        @Override public synchronized void close() throws IOException {
            if(closed)return;
            closed=true;
            try{lock.channel().close();}
            finally{OPEN.remove(path);}
        }
    }
}
