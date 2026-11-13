package com.tyranor.runtime.systemnnn;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import com.core.engine.LaunchContract;
import com.core.nativeplugin.NativeRuntimePack;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Host entry point for the built-in Tyranor-Next engine. */
public final class SystemNnnHost {
    public static final boolean DEFAULT_FULLSCREEN = true;
    public static final int DEFAULT_READING_SIZE = 85;
    public static final boolean DEFAULT_EXE_ANALYSIS = true;
    public static final boolean DEFAULT_DEBUG_MODE = false;
    public static final List<Integer> READING_SIZES = Collections.unmodifiableList(Arrays.asList(60, 85, 100, 115));
    static final String EXTRA_FULLSCREEN = "systemnnn.fullscreen";
    static final String EXTRA_READING_SIZE = "systemnnn.reading_size";
    static final String EXTRA_EXE_ANALYSIS = "systemnnn.exe_analysis";
    static final String EXTRA_DEBUG_MODE = "systemnnn.debug_mode";
    public static final String SAVE_MIGRATION_DIRECTORY = GameSavePaths.PREFIX + ".migrate";
    public static final String SAVE_RESTORE_DIRECTORY = "tyn-restore";
    public static final String SAVE_SLOT_PRESENTATION_FILE = GameSavePaths.PREFIX + SaveSlots.SLOT_UI;
    public static final String SAVE_ZIP_STAGING_PREFIX = SystemNnnSaveValidationService.ZIP_PREFIX;
    public static final String SAVE_VALIDATOR_PROCESS = ":systemnnn_validator";
    // Player task writers live in :systemnnn. The launcher opens records only to check liveness.
    private static final Map<File,RandomAccessFile> PLAYER_TASKS = new HashMap<>();
    private static boolean taskRecordsSwept;

    private SystemNnnHost() {}

    /** Checks for a readable spt/sys.spt marker. The player validates the script when opened. */
    public static boolean canOpen(File root) {
        try {
            return hasMarker(canonicalRoot(root));
        } catch (IllegalArgumentException | IOException | SecurityException e) {
            return false;
        }
    }

    /** Queries the engine worker for the cached or explicitly regenerated layout. */
    public static SaveLayoutConfig saveLayoutConfig(Context context,File root,boolean regenerate) throws IOException {
        File game=canonicalRoot(root);
        if(!hasMarker(game))throw new IOException("Cannot read spt/sys.spt in "+game);
        if(!regenerate && GameResourcePaths.file(game,"init2","emberfall-system.cfg")==null &&
           GameResourcePaths.file(game,"init","emberfall-system.cfg")==null)return null;
        if(!NativeRuntimePack.ensure(context,NativeRuntimePack.PACK_SYSTEMNNN))
            throw new IOException("SystemNNN runtime pack unavailable");
        return SystemNnnSaveValidationService.layoutConfig(context,game,regenerate);
    }

    /** Builds an explicit in-app intent using an absolute filesystem path. */
    public static Intent createLaunchIntent(Context context, File root, String title, String sourceUri,
                                            boolean fullscreen, int readingSize, boolean exeAnalysis, boolean debugMode) throws IOException {
        File game = canonicalRoot(root);
        if (!hasMarker(game)) {
            throw new IOException("Cannot read spt/sys.spt in " + game);
        }
        return new Intent(context, PlayerActivity.class)
            .setAction(LaunchContract.RUNTIME_MODULE_ACTION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            .putExtra(LaunchContract.RUNTIME_MODULE_FOLDER_PATH, game.getPath())
            .putExtra(LaunchContract.ROOT_URI, sourceUri)
            .putExtra(LaunchContract.RUNTIME_MODULE_GAME_TITLE, title)
            .putExtra(LaunchContract.RUNTIME_MODULE_PROTOCOL_VERSION, LaunchContract.RUNTIME_MODULE_VERSION)
            .putExtra(EXTRA_FULLSCREEN, fullscreen)
            .putExtra(EXTRA_READING_SIZE, normalizeReadingSize(readingSize))
            .putExtra(EXTRA_EXE_ANALYSIS, exeAnalysis)
            .putExtra(EXTRA_DEBUG_MODE, debugMode);
    }

    public static int legacyReadingSize(Context context) {
        Object value = PlayerSettings.preferences(context).getAll().get("androidReadingSizePercent");
        return value instanceof Integer ? normalizeReadingSize((Integer) value) : DEFAULT_READING_SIZE;
    }

    static int normalizeReadingSize(int value) {
        return READING_SIZES.contains(value) ? value : DEFAULT_READING_SIZE;
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

    /** The player keeps this record through the final save flush. */
    static synchronized Closeable registerPlayerTask(Activity activity,File game,String sourceUri) throws IOException {
        File record=playerTaskFile(activity,activity.getTaskId());
        File directory=record.getParentFile();
        if(!directory.isDirectory()&&!directory.mkdirs()&&!directory.isDirectory())
            throw new IOException("Cannot create the SystemNNN task directory");
        if(PLAYER_TASKS.containsKey(record))throw new IOException("SystemNNN task already registered");
        if(!taskRecordsSwept){
            File[] stale=directory.listFiles((parent,name)->name.matches("systemnnn-[0-9]+\\.task"));
            if(stale!=null){
                taskRecordsSwept=true;
                for(File file:stale)if(!PLAYER_TASKS.containsKey(file)&&!file.delete())taskRecordsSwept=false;
            }
        }
        RandomAccessFile owner=new RandomAccessFile(record,"rw");
        try{
            if(owner.getChannel().tryLock()==null)throw new IOException("SystemNNN task already registered");
            owner.setLength(0);
            owner.writeUTF(sourceUri==null?"":sourceUri);
            owner.writeUTF(game.getCanonicalPath());
            PLAYER_TASKS.put(record,owner);
            return ()->releasePlayerTask(record,owner);
        }catch(IOException|RuntimeException e){
            try{owner.close();}catch(IOException close){e.addSuppressed(close);}
            throw e;
        }
    }

    private static synchronized void releasePlayerTask(File record,RandomAccessFile owner) throws IOException {
        if(!PLAYER_TASKS.remove(record,owner))return;
        try{owner.close();}
        finally{Files.deleteIfExists(record.toPath());}
    }

    /** The launcher also verifies that the matching AppTask belongs to PlayerActivity. */
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
        if (relative.getNameCount() == 1 && (isPcStorySave(name)||isPcGlobalSave(name))) return true;
        if (relative.getNameCount() == 1 && name.endsWith(".bak")) {
            name = name.substring(0, name.length() - 4);
            if (name.equals(SAVE_SLOT_PRESENTATION_FILE)) return false;
            relative = relative.resolveSibling(name);
        }
        return LegacySaveMigration.ownedFile(relative);
    }

    public static boolean isPcStorySave(String name) {
        return PcStorySave.isSlotFile(name);
    }

    public static boolean isPcGlobalSave(String name) {
        return PcSystemSave.isSystemFile(name)||PcSystemSave.isResultFile(name);
    }

    /** Retired slots can still be put back by an interrupted older transaction. */
    public static boolean isRetiredRollbackFile(Path relative) {
        if(relative.isAbsolute()||relative.getNameCount()!=1)return false;
        String name=relative.getFileName().toString();
        if(name.endsWith(".bak"))relative=relative.resolveSibling(name.substring(0,name.length()-4));
        return LegacySaveMigration.retiredSlot(relative);
    }

    public static boolean isManagedSaveDirectory(Path relative) {
        return !relative.isAbsolute() && LegacySaveMigration.chartDirectory(relative);
    }

    /** Checks the staged snapshot without promoting its backup files. */
    public static boolean validateStagedSaveSnapshot(Context context,File stage,Set<String> existing,Runnable checkCancelled)
        throws IOException {
        return SystemNnnSaveValidationService.validate(context,stage,requiresStorySnapshot(existing),checkCancelled);
    }

    static boolean canReplaceSaveSnapshot(Map<String,File> incoming,boolean requiresStory) throws IOException {
        return canReplaceSaveSnapshot(incoming,requiresStory,PcStorySave::valid);
    }

    interface SlotValidator {
        boolean valid(InputStream input) throws IOException;
    }

    static boolean canReplaceSaveSnapshot(Map<String,File> incoming,boolean requiresStory,SlotValidator validator)
        throws IOException {
        boolean hasStory=false,hasPcProgress=false;
        for(Map.Entry<String,File> entry:incoming.entrySet()){
            String name=entry.getKey();
            if(isPcGlobalSave(name)){
                if(!PcSystemSave.valid(entry.getValue()))return false;
                hasPcProgress|=PcSystemSave.isSystemFile(name);
                continue;
            }
            // AtomicFile opens the backup first when both files exist.
            if(!isPcStorySave(name)||incoming.containsKey(name+".bak"))continue;
            File file=entry.getValue();
            try(InputStream in=Files.newInputStream(file.toPath(),LinkOption.NOFOLLOW_LINKS)){
                if(!validator.valid(in))return false;
            }
            hasStory=true;
        }
        String progress=GameSavePaths.PREFIX+GameSavePaths.READ;
        File primary=incoming.get(progress),backup=incoming.get(progress+".bak");
        boolean hasProgress=primary!=null||backup!=null;
        if(hasProgress&&!GameProgress.hasReadableBase(primary,backup))return false;
        if(hasStory)return true;
        return (hasProgress||hasPcProgress)&&!requiresStory;
    }

    static boolean requiresStorySnapshot(Set<String> existing){
        String chart=GameSavePaths.PREFIX+GameSavePaths.CHART+"/";
        return existing.stream().anyMatch(name->isPcStorySave(name)||name.startsWith(chart));
    }

    /** Caller holds the game's shared save lock. Retired backups stay outside ZIP imports. */
    public static int clearRetiredProgressBackups(File directory) throws IOException {
        return GameProgress.clearRetiredBackups(directory);
    }

    public static final class SaveBusyException extends IOException {
        SaveBusyException() {
            super("SAVE_DIR_IN_USE");
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
