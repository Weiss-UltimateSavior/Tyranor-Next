package com.tyranor.runtime.systemnnn;

import android.view.Surface;
import java.nio.ByteBuffer;

final class NativeGame {
    static { System.loadLibrary("systemnnn"); }
    static final int RUNNING=0, DRAW=1, OVERLAP=2, DIALOGUE=3, CHOICE=4, END=5, TITLE=6, MOVIE=7, PLACE=8, MODAL=9;
    static final int META_SIZE=10, CLOCK_DATE=7, CLOCK_MILLISECONDS=8, SCRIPT_CURSOR=9;
    static final int SELECT_PLACE_MODE=22,SELECT_PLACE2_MODE=35;
    static native long open(byte[] path, int sampleRate);
    static native long openWithRuntime(byte[] path,int sampleRate,long contextToken);
    static native long openReplay(byte[] path,int sampleRate,long contextToken,byte[] script,int[] films,int hiddenLayers);
    static native long retainContext(long gameHandle);
    static native long cloneContext(long contextToken);
    static native long forkContext(long contextToken);
    static native void releaseContext(long contextToken);
    static native void close(long handle);
    static native void surface(long handle, Surface target);
    static native void configureTitle(long handle,byte[] script,byte[] background,boolean repeat,int bgm,int plays,int volume,int fade);
    static native void restartTitle(long handle);
    static native int[] titleSprite(long handle,byte[] name);
    static native int[] gallerySprite(long handle,byte[] name);
    static native int[] keyedSprite(long handle,byte[] name);
    static native boolean musicWave(long handle,float[] wave);
    static native byte[] snapshot(long handle);
    static native void restorePcSave(long handle,byte[] save,int[] meta);
    static native byte[] exportPcSave(long handle);
    static native boolean restoreSnapshot(long handle,byte[] data,int wait,int word,int line,byte[][] content,int[] meta,
                                          boolean keepMusicPosition,boolean migrateLegacySystemVariables);
    // meta = wait, word, dialogue sequence, logical width/height, frame time and remaining wait frames.
    static native void tick(long handle, int ms, int x, int y, boolean advance, int choice, boolean replay, int[] meta);
    static native byte[][] content(long handle);
    static native void frameInfo(long handle,int[] meta);
    static native int runtimeMode(long handle);
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
    static native byte[][] chartText(long handle);
    static native void setChartStorage(long handle,byte[] path);
    static native byte[][] statusOverlay(long handle,int type,int mode);
    static native boolean openModal(long handle,int mode,int caller);
    static native int actModal(long handle,int request,int action,int value);
    static native int abortModal(long handle,int request,String reason);
    static native void advanceModal(long handle,int request,int frames);
    static native boolean modalSavable(long handle);
    static native boolean completeModal(long handle,int request,boolean accepted,int elapsedFrames,byte[][] values);
    static native boolean storyExited(long handle);
    // Raw game-encoded speaker and body; content indices remain unchanged for save replay.
    static native byte[][] dialogue(long handle);
    // Each backlog entry contributes speaker, body, four raw voice names and style.
    static native byte[][] backlog(long handle);
    static native long[] backlogJumpIds(long handle);
    static native void captureBacklogJump(long handle,int journalCursor);
    static native int[] backlogJump(long handle,long id);
    static native boolean restoreBacklogJump(long handle,long id,int journalCursor,int[] meta);
    static native void trimReplayedBacklog(long handle,int keep);
    static final int SYSTEM_VARIABLES=1000;
    static final int SCRIPT_PROGRESS=2048;
    static final int OPTIONAL_PROGRESS_WORDS=1024+16384;
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
    static native int[] dialogueProperties(long handle);
    static native void setVolumes(long handle,int music,int sound,int voice,int effectVoice);
    static native void setParameters(long handle,int[] idValuePairs);
    static native int[] parameters(long handle);
    static native int[] displayParameters(long handle);
    static native int[] auxiliaryState(long handle);
    static native boolean legacyVolumeDefaults(long handle);
    // DTO indices 0=theme, 1=window, 2..33=voice-off, 34..35=cursor variants (read-only).
    static native void setAuxiliaryState(long handle,int[] indexValuePairs);
    static native void setTextLength(long handle,int length);
    static native int[] textState(long handle);
    static native int[] settingsTextRates(long handle);
    static native boolean requestAdvance(long handle);
    static native int textCharacters(long handle,int elapsedMs);
    static native boolean autoReady(long handle,int elapsedMs,boolean textComplete);
    static native void playButtonVoice(long handle,int zeroBasedId);
    static native void playSettingsClick(long handle,int se,int voice,int volumeType,boolean ignoreVoiceSwitch,boolean ignoreCharacterMute);
    static native void playConfigFeedback(long handle,int slider,int position,int steps);
    static native void playSystemSe(long handle,int id);
    static native void setSkip(long handle,boolean skip);
    static native boolean skipActive(long handle);
    static native boolean skipEffective(long handle);
    static native int[] readState(long handle);
    static native void restoreReadState(long handle,int[] identifiers);
    static native boolean markRead(long handle,int identifier);
    static native boolean isRead(long handle,int identifier);
    static native long galleryRevision(long handle);
    static native int movieNumber(long handle);
    static native int movieVolume(long handle);
    static native int[] choiceInfo(long handle);
    static native boolean choiceTimedOut(long handle);
    static native byte[][] choiceMessages(long handle);
    static native int[] galleryState(long handle);
    static native void mergeGallery(long handle,int[] keys);
    static native void replaceGallery(long handle,int[] keys);
    static native int musicNumber(long handle,byte[] name);
    static native int galleryVariable(long handle,byte[] name);
    static native int menuVariable(long handle,byte[] name);
    static native void menuMusic(long handle,int number,int plays,int volume,int fade,boolean restart);
    static native void playGalleryMusic(long handle,byte[] name,int plays,int volume);
    static native void stopGalleryMusic(long handle);
    static native void pauseGalleryMusic(long handle,boolean paused);
    static native void galleryMusicVolume(long handle,int volume);
    static native void audio(long handle, ByteBuffer stereo, int frames);
    private NativeGame() {}
}
