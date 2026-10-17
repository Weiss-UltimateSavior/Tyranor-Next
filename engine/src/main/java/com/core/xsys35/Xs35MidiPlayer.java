package com.core.xsys35;

import android.media.MediaPlayer;
import android.util.Log;

import java.io.IOException;

/**
 * xsystem35 的 MIDI 播放器（移植上游 {@code GameActivity.MidiPlayer}）。
 *
 * <p>引擎（{@code src/midi.android.c}）把 MIDI 数据写成应用私有目录的 {@code tmp.mid} 后经
 * {@code midiStart} 反射回调交给宿主播放；本类用单实例 {@link MediaPlayer} 串行化
 * STOPPED / PLAYING / PAUSED / RELEASED 四态。方法由 SDL 原生线程与 UI 线程（生命周期）
 * 共同调用，故全部 {@code synchronized}。
 */
final class Xs35MidiPlayer {

    private static final String TAG = "Xs35MidiPlayer";

    private enum State {
        STOPPED,
        PLAYING,
        PAUSED,
        RELEASED,
    }

    private final MediaPlayer player = new MediaPlayer();
    private State state = State.STOPPED;

    synchronized void start(String path, boolean loop) {
        if (state == State.RELEASED) {
            return;
        }
        state = State.STOPPED;
        try {
            player.reset();
            player.setDataSource(path);
            player.setLooping(loop);
            player.prepare();
            player.start();
            state = State.PLAYING;
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            Log.e(TAG, "Cannot play midi: " + path, e);
            resetQuietly();
        }
    }

    synchronized void stop() {
        if (state != State.PLAYING && state != State.PAUSED) {
            return;
        }
        try {
            player.stop();
        } catch (IllegalStateException e) {
            Log.w(TAG, "midi stop failed", e);
        }
        state = State.STOPPED;
    }

    synchronized int currentPosition() {
        if (state != State.PLAYING && state != State.PAUSED) {
            return 0;
        }
        try {
            return player.getCurrentPosition();
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    synchronized void onActivityStop() {
        if (state != State.PLAYING) {
            return;
        }
        try {
            if (player.isPlaying()) {
                player.pause();
                state = State.PAUSED;
            }
        } catch (IllegalStateException e) {
            Log.w(TAG, "midi pause failed", e);
        }
    }

    synchronized void onActivityResume() {
        if (state != State.PAUSED) {
            return;
        }
        try {
            player.start();
            state = State.PLAYING;
        } catch (IllegalStateException e) {
            Log.w(TAG, "midi resume failed", e);
        }
    }

    synchronized void release() {
        if (state == State.RELEASED) {
            return;
        }
        state = State.RELEASED;
        try {
            player.release();
        } catch (RuntimeException e) {
            Log.w(TAG, "midi release failed", e);
        }
    }

    private void resetQuietly() {
        try {
            player.reset();
        } catch (IllegalStateException e) {
            Log.w(TAG, "midi reset failed", e);
        }
        state = State.STOPPED;
    }
}
