package com.tyranor.runtime.systemnnn;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import com.core.engine.LaunchContract;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/** Host entry point for the built-in Tyranor-Next engine. */
public final class SystemNnnHost {
    public static final String SAVE_MIGRATION_DIRECTORY = GameSavePaths.PREFIX + ".migrate";
    public static final String SAVE_RESTORE_DIRECTORY = "tyn-restore";
    public static final String SAVE_SLOT_PRESENTATION_FILE = GameSavePaths.PREFIX + SaveSlots.SLOT_UI;

    private SystemNnnHost() {}

    /** Checks for a readable spt/sys.spt marker. The player validates the script when opened. */
    public static boolean canOpen(File root) {
        try {
            return hasMarker(canonicalRoot(root));
        } catch (IllegalArgumentException | IOException | SecurityException e) {
            return false;
        }
    }

    /** Builds an explicit in-app intent using an absolute filesystem path. */
    public static Intent createLaunchIntent(Context context, File root, String title) throws IOException {
        File game = canonicalRoot(root);
        if (!hasMarker(game)) {
            throw new IOException("Cannot read spt/sys.spt in " + game);
        }
        return new Intent(context, PlayerActivity.class)
            .setAction(LaunchContract.RUNTIME_MODULE_ACTION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            .putExtra(LaunchContract.RUNTIME_MODULE_FOLDER_PATH, game.getPath())
            .putExtra(LaunchContract.RUNTIME_MODULE_GAME_TITLE, title)
            .putExtra(LaunchContract.RUNTIME_MODULE_PROTOCOL_VERSION, LaunchContract.RUNTIME_MODULE_VERSION);
    }

    /** Returns the shared sav directory without creating it. */
    public static File saveDirectory(Context context, File root) throws IOException {
        return GameSavePaths.forGame(canonicalRoot(root)).directory();
    }

    public static Closeable lockSaveDirectory(File root) throws IOException {
        return GameSavePaths.forGame(canonicalRoot(root)).lock();
    }

    public static boolean hasActiveSaveAccess() {
        return GameSavePaths.hasActiveAccess();
    }

    /** The live Activity keeps this lock until it is destroyed, including during save migration. */
    static Closeable registerPlayerTask(Activity activity,File game,String sourceUri) throws IOException {
        File record=playerTaskFile(activity,activity.getTaskId());
        File directory=record.getParentFile();
        if(!directory.isDirectory()&&!directory.mkdirs()&&!directory.isDirectory())
            throw new IOException("Cannot create the SystemNNN task directory");
        RandomAccessFile owner=new RandomAccessFile(record,"rw");
        try{
            if(owner.getChannel().tryLock()==null)throw new IOException("SystemNNN task already registered");
            owner.setLength(0);
            owner.writeUTF(sourceUri==null?"":sourceUri);
            owner.writeUTF(game.getCanonicalPath());
            return owner;
        }catch(IOException|RuntimeException e){
            try{owner.close();}catch(IOException close){e.addSuppressed(close);}
            throw e;
        }
    }

    /** The launcher also verifies that the matching AppTask still displays PlayerActivity. */
    public static boolean isLivePlayerTask(Context context,int taskId,String sourceUri,File gameRoot) {
        if(taskId<0)return false;
        try(RandomAccessFile record=new RandomAccessFile(playerTaskFile(context,taskId),"r")){
            FileLock unlocked=record.getChannel().tryLock(0,Long.MAX_VALUE,true);
            if(unlocked!=null){
                unlocked.release();
                return false;
            }
            String registeredUri=record.readUTF();
            String registeredRoot=record.readUTF();
            return !registeredUri.isEmpty()&&registeredUri.equals(sourceUri)||
                gameRoot!=null&&registeredRoot.equals(gameRoot.getCanonicalPath());
        }catch(IOException|OverlappingFileLockException|SecurityException e){return false;}
    }

    private static File playerTaskFile(Context context,int taskId){
        return new File(new File(context.getFilesDir(),"engine_sessions"),"systemnnn-"+taskId+".task");
    }

    public static boolean isManagedSaveFile(Path relative) {
        if (relative.isAbsolute() || relative.getFileName() == null) return false;
        String name = relative.getFileName().toString();
        if (relative.getNameCount() == 1 && name.endsWith(".bak")) {
            name = name.substring(0, name.length() - 4);
            if (name.equals(SAVE_SLOT_PRESENTATION_FILE)) return false;
            relative = relative.resolveSibling(name);
        }
        return LegacySaveMigration.ownedFile(relative);
    }

    public static boolean isManagedSaveDirectory(Path relative) {
        return !relative.isAbsolute() && LegacySaveMigration.chartDirectory(relative);
    }

    /** Checks the staged snapshot without promoting any legacy backup files. */
    public static boolean canReplaceSaveSnapshot(Map<String,File> incoming,Set<String> existing) throws IOException {
        boolean hasStory=false;
        GameSave.ImportValidation validation=new GameSave.ImportValidation();
        for(Map.Entry<String,File> entry:incoming.entrySet()){
            String name=entry.getKey();
            Integer slot=storySaveSlot(name);
            // AtomicFile reads the legacy backup first when both files exist.
            if(slot==null||incoming.containsKey(name+".bak"))continue;
            File file=entry.getValue();
            try(InputStream in=Files.newInputStream(file.toPath(),LinkOption.NOFOLLOW_LINKS)){
                if(GameSave.readForImport(in,file.lastModified(),slot,validation).metadata.slot!=slot)return false;
            }
            hasStory=true;
        }
        String progress=GameSavePaths.PREFIX+GameSavePaths.READ;
        File primary=incoming.get(progress),backup=incoming.get(progress+".bak");
        boolean hasProgress=primary!=null||backup!=null;
        if(hasProgress&&!GameProgress.hasReadableBase(primary,backup))return false;
        if(hasStory)return true;
        String chart=GameSavePaths.PREFIX+GameSavePaths.CHART+"/";
        return hasProgress&&existing.stream().noneMatch(name->storySaveSlot(name)!=null||name.startsWith(chart));
    }

    /** Caller holds the game's shared save lock. Retired backups stay outside ZIP imports. */
    public static int clearRetiredProgressBackups(File directory) throws IOException {
        return GameProgress.clearRetiredBackups(directory);
    }

    private static Integer storySaveSlot(String name){
        String prefix=GameSavePaths.PREFIX;
        return name.startsWith(prefix)?SaveSlots.committedSlot(name.substring(prefix.length())):null;
    }

    public static final class SaveBusyException extends IOException {
        SaveBusyException() {
            super("这款游戏的存档正在使用，请退出另一个播放器后重试");
        }
    }

    private static File canonicalRoot(File root) throws IOException {
        if (root == null || !root.isAbsolute()) {
            throw new IllegalArgumentException("SystemNNN requires an absolute filesystem path");
        }
        return root.getCanonicalFile();
    }

    private static boolean hasMarker(File game) {
        if(SystemNnnGameImport.resourcesEvicted(game))return false;
        File marker = GameResourcePaths.file(game, "spt", "sys.spt");
        return marker != null && marker.canRead();
    }
}
