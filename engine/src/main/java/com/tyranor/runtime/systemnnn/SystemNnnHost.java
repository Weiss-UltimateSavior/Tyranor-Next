package com.tyranor.runtime.systemnnn;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;

/** Host entry point for the built-in Tyranor-Next engine. */
public final class SystemNnnHost {
    private static final String ACTION_LAUNCH = "com.tyranor.runtime.action.LAUNCH";
    private static final String EXTRA = "com.tyranor.runtime.extra.";
    public static final String SAVE_MIGRATION_DIRECTORY = GameSavePaths.PREFIX + ".migrate";
    public static final String SAVE_RESTORE_DIRECTORY = "tyn-restore";

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
            .setAction(ACTION_LAUNCH)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            .putExtra(EXTRA + "FOLDER_PATH", game.getPath())
            .putExtra(EXTRA + "GAME_TITLE", title)
            .putExtra(EXTRA + "PROTOCOL_VERSION", 1);
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
            relative = relative.resolveSibling(name.substring(0, name.length() - 4));
        }
        return LegacySaveMigration.ownedFile(relative);
    }

    public static boolean isManagedSaveDirectory(Path relative) {
        return !relative.isAbsolute() && LegacySaveMigration.chartDirectory(relative);
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
        File marker = GameResourcePaths.file(game, "spt", "sys.spt");
        return marker != null && marker.canRead();
    }
}
