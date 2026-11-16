package com.tyranor.runtime.systemnnn;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.Surface;
import com.core.nativeplugin.NativeRuntimePack;
import java.io.IOException;
import java.nio.ByteBuffer;

final class NativeGame {
    private static boolean loaded;

    static synchronized void load(Context context) throws IOException {
        if (loaded) return;
        String path = NativeRuntimePack.libraryPath(context, NativeRuntimePack.PACK_SYSTEMNNN, "libsystemnnn.so");
        if (path == null) throw new IOException("SystemNNN runtime pack unavailable");
        System.load(path);
        loaded = true;
    }
    static final int RUNNING=0, DRAW=1, OVERLAP=2, DIALOGUE=3, CHOICE=4, END=5, TITLE=6, MOVIE=7, PLACE=8, MODAL=9, NATIVE_PANEL=10;
    static final int META_SIZE=18, CLOCK_DATE=7, CLOCK_MILLISECONDS=8, SCRIPT_CURSOR=9, DRAW_MESSAGE=10,
        TITLE_BACKGROUND_ENDED=11, PACED_MS=12, FILM_SKIP_ENABLED=13, SCRIPT_MOUSE=14, TICK_EXECUTED=15,
        NATIVE_PANEL_MODE=16, NATIVE_PANEL_SAVABLE=17;
    static final int SELECT_PLACE_MODE=22,SELECT_PLACE2_MODE=35;
    static native long openWithOptions(byte[] path,int sampleRate,long contextToken,boolean analyzeExecutable,boolean debugMode);
    static native long openReplay(byte[] path,int sampleRate,long contextToken,byte[] script,int[] films,int hiddenLayers);
    static native long retainContext(long gameHandle);
    static native long cloneContext(long contextToken);
    static native long forkContext(long contextToken);
    static native void releaseContext(long contextToken);
    static native void close(long handle);
    static native long createSurface(Surface target);
    static native void releaseSurface(long token);
    static native void deactivateSurface(long token);
    static native void bindSurface(long handle,long token);
    static native void presentSurface(long handle);
    static native long presentStoryFrame(long handle);
    static native boolean configureMenuBackground(long handle,int mode,byte[] script,byte[] background,boolean repeat);
    // A zero result signals script end. A positive result is the current engine frame period.
    static native int advanceOpening(long handle,int elapsedMs);
    static native void resetFramePacing(long handle);
    static native void restartTitle(long handle);
    static native int[] titleSprite(long handle,byte[] name);
    static native int[] gallerySprite(long handle,byte[] name);
    static native int[] keyedSprite(long handle,byte[] name);
    static native boolean musicWave(long handle,float[] wave);
    static native byte[] snapshot(long handle);
    static native void restorePcSave(long handle,byte[] save,int[] meta,long previous);
    static native int[] currentPcSystemSaveFormat(long gameHandle);
    static native Object[] saveLayoutConfig(byte[] gameDirectory,boolean regenerate);
    static native byte[] exportPcSave(long handle,int[] pixels,int width,int height);
    static native Object[] inspectPcSave(byte[] save,int pictureBudget);
    static native int[] pcSaveDateVariables(byte[] gameDirectory);
    static native int[] inspectPcSaveGameDate(byte[] save,int yearVar,int dateVar);
    static native int computeGameWeekday(int year,int month,int day);
    static native int[] sharedConfigSnapshot(long handle);
    static native boolean inspectSaveSnapshot(ByteBuffer data,int length,int wait,int word,int line,byte[][] content);
    static native boolean restoreSnapshot(long handle,byte[] data,int wait,int word,int line,byte[][] content,int[] meta,
                                          boolean keepMusicPosition,boolean migrateLegacySystemVariables,boolean restartMusic);
    // meta = wait, word, dialogue sequence, logical width/height, frame time and remaining wait frames.
    // A live tick uses ms for real-time waits and reports the animation budget in PACED_MS.
    static native void tick(long handle, int ms, int x, int y, boolean advance, int choice, boolean replay, int[] meta);
    static native boolean tickStory(long handle, int ms, int x, int y, boolean advance, int choice, int[] meta, GameRunner host);
    static native byte[][] content(long handle);
    static native void frameInfo(long handle,int[] meta);
    static native int runtimeMode(long handle);
    static native void nativePanelInput(long handle,int x,int y,boolean click,boolean cancel);
    static native boolean nativePanelMenu(long handle,int mode);
    static native int takeNativePanelMenu(long handle);
    static native boolean resumeNativePanel(long handle);
    // Int fields, raw script name, raw BGM name, and pending script DEBUG messages.
    static native Object[] debugSnapshot(long handle);
    static native long systemMenuModes(long handle,long requested);
    // Modal identity, native mode, return mode (0 resumes the script), then command parameters.
    static native int[] modalInfo(long handle);
    static native int[] modalLimits(long handle);
    static native byte[][] modalText(long handle);
    // Page, page count, group, selection and balance followed by row ID and flags pairs.
    static native int[] modalView(long handle);
    // Each row contributes a raw label, asset name and detail string.
    static native byte[][] modalRows(long handle);
    static native int[] chartGeometry(long handle);
    static native boolean legacyChart(long handle);
    static native boolean legacyChartDirectory(byte[] root);
    static native boolean validateChartFile(byte[] root,byte[] relativePath,byte[] data);
    static native byte[][] chartText(long handle);
    static native void setChartStorage(long handle,byte[] path);
    static native byte[][] statusOverlay(long handle,int type,int mode);
    static native byte[][][] statusOverlays(long handle,int mode);
    static native boolean openModal(long handle,int mode,int caller);
    static native int actModal(long handle,int request,int action,int value);
    static native int abortModal(long handle,int request,String reason);
    static native void advanceModal(long handle,int request,int frames);
    static native boolean modalSavable(long handle);
    static native boolean completeModal(long handle,int request,boolean accepted,int elapsedFrames,byte[][] values);
    static native boolean storyExited(long handle);
    // Raw game-encoded speaker and body; content indices remain unchanged for save replay.
    static native byte[][] dialogue(long handle);
    static native byte[][] dialogueVisuals(long handle);
    // Each backlog entry contributes speaker, body, four raw voice names and style.
    static native byte[][] backlog(long handle);
    static native long[] backlogJumpIds(long handle);
    static native void captureBacklogJump(long handle,int journalCursor);
    static native int[] backlogJump(long handle,long id);
    static native boolean restoreBacklogJump(long handle,long id,int journalCursor,int[] meta);
    static native void trimReplayedBacklog(long handle,int keep);
    static final int SYSTEM_VARIABLES=1000;
    static final int SCRIPT_PROGRESS=2048;
    static final int LEGACY_OPTIONAL_PROGRESS_WORDS=1024+16384;
    static final int OPTIONAL_PROGRESS_WORDS=LEGACY_OPTIONAL_PROGRESS_WORDS+8;
    static native int[] systemVariables(long handle);
    static native void seedLegacyReplayVariables(long handle);
    static native void setSystemVariables(long handle,int[] values);
    static native int scriptRandomState(long handle);
    static native void setScriptRandomState(long handle,int state);
    static native int[] scriptProgress(long handle);
    static native void setScriptProgress(long handle,int[] values);
    // System surname, given name and 100 replacement slots, all in game encoding.
    static native byte[][] runtimeText(long handle);
    static native boolean restoreRuntimeText(long handle,byte[][] fields);
    static native int[] optionalProgress(long handle);
    static native void restoreOptionalProgress(long handle,int[] words);
    static native boolean voiceRoleHeard(long handle,int character);
    static native byte[] chartProgress(long handle);
    static native void restoreChartProgress(long handle,byte[] flags);
    static native int configMask(long handle);
    static native long takeScriptUiEvents(long handle);
    static native int[] placeStates(long handle);
    static native int placeMode(long handle);
    static native int[] placeCharacters(long handle);
    static native void suspendScriptAudio(long handle);
    static native void restartLoopScriptAudio(long handle);
    static native void replayBacklogVoice(long handle,byte[] name);
    static native void stopScriptAudio(long handle);
    static native int[] saveThumbnail(long handle,int width,int height);
    static native int[] saveSceneThumbnail(long handle,int width,int height);
    static native int saveSlotArt(long handle);
    static native long messagePlaneToken(long handle);
    static native void pushMessagePlane(long token,Bitmap bitmap,int[] key,boolean showBackdrop);
    static native void pushStoryPlanes(long token,Bitmap bitmap,Bitmap floating,int[] key,boolean showBackdrop);
    static native void releaseMessagePlane(long token);
    static native int[] expectedPlaneKey(long handle);
    static native int[] presentedPlaneKey(long handle);
    static native int[] dialogueProperties(long handle);
    static native void setVolumes(long handle,int music,int sound,int voice,int effectVoice);
    static native void setParameters(long handle,int[] idValuePairs);
    static native int[] parameters(long handle);
    static native int[] displayParameters(long handle);
    static native int[] auxiliaryState(long handle);
    static native void setPcDisplayOptions(long handle,boolean noSkipFrame,int mouseCursor);
    static native boolean legacyVolumeDefaults(long handle);
    static native boolean usesXAudio2(long handle);
    // DTO indices 0=theme, 1=window, 2..33=voice-off, 34..35=cursor variants (read-only).
    static native void setAuxiliaryState(long handle,int[] indexValuePairs);
    static native byte[] appendPrefix(long handle);
    static native void setTextLength(long handle,int length,int visible);
    static native int[] textState(long handle);
    static native int[] settingsTextRates(long handle);
    static native boolean requestAdvance(long handle);
    static native boolean requestFilmSkip(long handle);
    static native boolean completeMiniGame(long handle,int number);
    static native int textCharacters(long handle,int elapsedMs);
    static native boolean autoReady(long handle,int elapsedMs,boolean textComplete);
    static native void playButtonVoice(long handle,int zeroBasedId);
    static native void playSettingsClick(long handle,int se,int voice,int volumeType,boolean ignoreVoiceSwitch,boolean ignoreCharacterMute);
    static native void playConfigFeedback(long handle,int slider,int position,int steps);
    static native void playSystemSe(long handle,int id);
    static native void setSkip(long handle,boolean skip);
    static native void setCtrlSkip(long handle,boolean enabled);
    static native boolean skipActive(long handle);
    static native int storyModes(long handle);
    static native boolean skipEffective(long handle);
    static native int[] readState(long handle);
    static native void restoreReadState(long handle,int[] identifiers);
    static native boolean markRead(long handle,int identifier);
    static native boolean isRead(long handle,int identifier);
    static native long galleryRevision(long handle);
    static native int movieNumber(long handle);
    static native int movieVolume(long handle);
    static native float movieGain(long handle);
    static native int[] choiceInfo(long handle);
    static native boolean choiceTimedOut(long handle);
    static native byte[][] choiceMessages(long handle);
    static native int[] galleryState(long handle);
    static native void mergeGallery(long handle,int[] keys);
    static native void replaceGallery(long handle,int[] keys);
    static native int musicNumber(long handle,byte[] name);
    static native int galleryVariable(long handle,byte[] name);
    static native int menuVariable(long handle,byte[] name);
    static native int openingVariable(long handle,byte[] name);
    // Presets a named variable of a new story before its first tick. False when the game has no such name.
    static native boolean setStoryVariable(long handle,byte[] name,int value);
    static native void menuMusic(long handle,int number,int plays,int volume,int fade,boolean restart);
    static native void inheritMenuMusic(long handle,long previous);
    static native void playGalleryMusic(long handle,byte[] name,int plays,int volume);
    static native void stopGalleryMusic(long handle);
    static native void pauseGalleryMusic(long handle,boolean paused);
    static native void galleryMusicVolume(long handle,int volume);
    static native void audio(long handle, ByteBuffer stereo, int frames);
    private NativeGame() {}
}
