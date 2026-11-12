package com.tyranor.runtime.systemnnn;

import android.content.Context;
import com.core.engine.EngineUiText;
import com.core.engine.R;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.widget.FrameLayout;
import java.io.File;
import java.nio.charset.StandardCharsets;

/** Plays a local game movie over the canvas. Both decoders survive a temporary loss of the view. */
final class MoviePlayback extends FrameLayout implements TextureView.SurfaceTextureListener {
    enum Outcome { COMPLETED, SKIPPED, FAILED }
    interface Listener { void finished(MoviePlayback source, Outcome outcome, String failure); }

    private static final String TAG = "SystemNNN";
    private static final int RATE = 48000;
    private final Handler owner = new Handler(Looper.getMainLooper());
    private final TextureView picture;
    private final byte[] filename;
    private final boolean canSkip;
    private final float volume;
    private final Listener listener;
    private final AudioAttributes audioAttributes = new AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build();
    private final PlayerAudioFocus audioFocus;

    // The gate guards playback state, never decoder calls or AudioTrack operations.
    private final Object gate = new Object();
    private final Clock clock = new Clock();
    private SurfaceTexture requestedTexture, boundTexture;
    private boolean paused, decoderFinished, audioReady, audioEnded;
    private String audioFailure;
    private volatile boolean stopped;
    // The main thread owns view layout and terminal callbacks.
    private boolean started;
    private Outcome pendingOutcome;
    private String pendingFailure;
    private int videoWidth, videoHeight;

    MoviePlayback(Context context, File file, int volume, boolean canSkip, Listener listener) {
        super(context);
        this.listener = listener;
        this.canSkip = canSkip;
        this.volume = Math.max(0, Math.min(100, volume)) / 100f;
        filename = file.getPath().getBytes(StandardCharsets.UTF_8);
        audioFocus = new PlayerAudioFocus(context, owner, audioAttributes, this::mayRequestFocus,
            () -> Log.i(TAG, "Movie playing muted while audio focus is unavailable"));
        setBackgroundColor(Color.BLACK);
        setSoundEffectsEnabled(false);
        setContentDescription(EngineUiText.get(context, canSkip ? R.string.engine_systemnnn_ctl_movie_skippable :
            R.string.engine_systemnnn_ctl_movie_playing));
        setOnClickListener(v -> skip());
        picture = new TextureView(context);
        picture.setSurfaceTextureListener(this);
        addView(picture, new LayoutParams(-1, -1, Gravity.CENTER));
    }

    void skip() {
        if (canSkip) finish(Outcome.SKIPPED, null);
    }

    void pause() {
        synchronized (gate) {
            paused = true;
            updatePlaybackLocked();
        }
        audioFocus.abandon();
    }

    void resume() {
        synchronized (gate) {
            paused = false;
            updatePlaybackLocked();
        }
        retryFocus();
        deliverOutcome();
    }

    void retryFocus() {
        if (mayRequestFocus()) audioFocus.request();
    }

    void dispose() {
        synchronized (gate) {
            if (stopped) return;
            stopped = true;
            requestedTexture = null;
            updatePlaybackLocked();
        }
        pendingOutcome = null;
        pendingFailure = null;
        audioFocus.abandon();
    }

    private boolean mayRequestFocus() {
        synchronized (gate) { return volume > 0 && clock.running && audioReady && !decoderFinished; }
    }

    private void updatePlaybackLocked() {
        clock.setRunning(!stopped && !paused && requestedTexture != null, System.nanoTime());
        gate.notifyAll();
    }

    private void finish(Outcome outcome, String failure) {
        if (stopped) return;
        dispose();
        listener.finished(this, outcome, failure);
    }

    private void deliverOutcome() {
        if (pendingOutcome == null) return;
        synchronized (gate) { if (!clock.running) return; }
        finish(pendingOutcome, pendingFailure);
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        synchronized (gate) {
            if (stopped) return;
            requestedTexture = texture;
            updatePlaybackLocked();
        }
        if (!started) {
            started = true;
            new Thread(this::decodeVideo, "SystemNNN-MovieVideo").start();
        }
        retryFocus();
        deliverOutcome();
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {}
    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) {}

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        boolean stillPlaying;
        synchronized (gate) {
            if (requestedTexture == texture) requestedTexture = null;
            updatePlaybackLocked();
            stillPlaying = clock.running;
        }
        if (!stillPlaying) audioFocus.abandon();
        // Disconnect the old consumer after the view detaches, including when the
        // decoder is blocked trying to acquire an output buffer from that consumer.
        owner.post(texture::release);
        return false;
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        fit();
    }

    private void fit() {
        if (videoWidth <= 0 || videoHeight <= 0 || getWidth() == 0 || getHeight() == 0) return;
        float scale = Math.min(getWidth() / (float) videoWidth, getHeight() / (float) videoHeight);
        LayoutParams lp = (LayoutParams) picture.getLayoutParams();
        int w = Math.round(videoWidth * scale), h = Math.round(videoHeight * scale);
        if (lp.width != w || lp.height != h) {
            lp.width = w;
            lp.height = h;
            picture.setLayoutParams(lp);
        }
    }

    private boolean awaitPlayback() throws InterruptedException {
        synchronized (gate) {
            while (!clock.running && !stopped && !decoderFinished) gate.wait();
            return !stopped && !decoderFinished;
        }
    }

    // The video worker owns the decoder and its ANativeWindow across every rebind.
    private void bindSurface(long token) {
        SurfaceTexture previous;
        synchronized (gate) {
            previous = boundTexture;
            if (previous == requestedTexture) return;
        }
        if (previous != null) {
            setSurface(token, null);
            clearBinding();
        }

        SurfaceTexture target;
        synchronized (gate) {
            if (!clock.running || audioFailure != null) return;
            target = requestedTexture;
            boundTexture = target;
        }
        Surface surface = null;
        try {
            surface = new Surface(target);
            if (setSurface(token, surface)) return;
            if (currentSurface()) throw new IllegalStateException("Movie surface unavailable");
        } catch (RuntimeException e) {
            if (currentSurface()) throw e;
        } finally {
            if (surface != null) surface.release();
        }
        clearBinding();
    }

    private void clearBinding() {
        synchronized (gate) { boundTexture = null; }
    }

    private boolean currentSurface() {
        synchronized (gate) {
            return clock.running && boundTexture != null && boundTexture == requestedTexture;
        }
    }

    private boolean awaitFrame(long token, long pts) throws InterruptedException {
        for (;;) {
            bindSurface(token);
            synchronized (gate) {
                if (stopped || audioFailure != null) return false;
                if (boundTexture != requestedTexture && (boundTexture != null || clock.running)) continue;
                if (clock.running && boundTexture != null) {
                    long now = clock.position(System.nanoTime());
                    if (pts < 0 || now >= 0 && now + 3000 >= pts) return true;
                    gate.wait(now < 0 ? 5 : Math.max(1, Math.min(10, (pts - now) / 1000)));
                } else gate.wait();
            }
        }
    }

    private long position() {
        synchronized (gate) { return clock.position(System.nanoTime()); }
    }

    private long activeMicros() {
        synchronized (gate) { return clock.activeMicros(System.nanoTime()); }
    }

    private void decodeVideo() {
        long token = 0;
        String error = null;
        int rendered = 0, dropped = 0;
        try {
            if (!awaitPlayback()) return;
            token = openVideo(filename);
            if (token == 0) throw new IllegalStateException("Movie video track unavailable");
            int[] size = size(token);
            owner.post(() -> {
                if (stopped) return;
                videoWidth = size[0];
                videoHeight = size[1];
                fit();
            });
            new Thread(this::decodeAudio, "SystemNNN-MovieAudio").start();

            long lastDraw = 0, previousPts = 0;
            while (awaitFrame(token, -1)) {
                long lag = position() - previousPts;
                // Drop disposable pictures before seeking to the next keyframe on a slow device.
                long pts = next(token, lag > 1500000 ? 2 : lag > 250000 ? 1 : 0);
                if (pts < 0) {
                    awaitAudioEnd(token);
                    break;
                }
                previousPts = pts;
                while (awaitFrame(token, pts)) {
                    long now = position(), wall = SystemClock.uptimeMillis();
                    if (rendered != 0 && now - pts > 140000 && wall - lastDraw < 120) {
                        dropped++;
                        break;
                    }
                    if (!currentSurface()) continue;
                    boolean drawn = present(token);
                    if (!currentSurface()) continue; // Retry this decoded frame after the new target is ready.
                    if (!drawn) throw new IllegalStateException("Movie frame could not be presented");
                    lastDraw = wall;
                    if (rendered++ == 0) Log.i(TAG, "Movie first frame " + size[0] + "x" + size[1] + " pts=" + pts);
                    if (rendered == 10) Log.i(TAG, "Movie video running frames=" + rendered + " dropped=" + dropped + " pts=" + pts + " clock=" + now);
                    break;
                }
            }
            synchronized (gate) { error = audioFailure; }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = reason(e);
        } catch (RuntimeException | LinkageError e) {
            error = reason(e);
        } finally {
            if (token != 0) close(token);
            clearBinding();
            synchronized (gate) {
                decoderFinished = true;
                gate.notifyAll();
            }
            if (!stopped) {
                Log.i(TAG, "Movie video finished frames=" + rendered + " dropped=" + dropped);
                String failure = error;
                owner.post(() -> {
                    if (stopped) return;
                    audioFocus.abandon();
                    pendingOutcome = failure == null ? Outcome.COMPLETED : Outcome.FAILED;
                    pendingFailure = failure;
                    deliverOutcome();
                });
            }
        }
    }

    private void awaitAudioEnd(long token) throws InterruptedException {
        long lastProgress = Long.MIN_VALUE, lastAdvance = activeMicros();
        while (awaitFrame(token, -1)) {
            synchronized (gate) {
                if (audioEnded) return;
                long now = System.nanoTime();
                long active = clock.activeMicros(now), progress = clock.position(now);
                if (progress > lastProgress) {
                    lastProgress = progress;
                    lastAdvance = active;
                } else if (active - lastAdvance >= 10_000_000) {
                    throw new IllegalStateException("Movie audio did not advance");
                }
                gate.wait(10);
            }
        }
    }

    private void decodeAudio() {
        long token = 0;
        AudioTrack track = null;
        String error = null;
        try {
            if (!awaitPlayback()) return;
            token = open(filename, null);
            if (token == 0) return;
            long first = next(token, 0);
            if (first < 0) return;
            int mask = AudioFormat.CHANNEL_OUT_STEREO, pcm = AudioFormat.ENCODING_PCM_16BIT;
            int buffer = AudioTrack.getMinBufferSize(RATE, mask, pcm);
            if (buffer <= 0) throw new IllegalStateException("Movie audio format unavailable");
            track = new AudioTrack.Builder().setAudioAttributes(audioAttributes)
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE).setChannelMask(mask).setEncoding(pcm).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(buffer, RATE * 4 / 10)).build();
            setGain(track, 0f);
            AudioOutput output = new AudioOutput(track, first);
            synchronized (gate) {
                audioReady = true;
                clock.prepareAudio(first, System.nanoTime());
                gate.notifyAll();
            }
            owner.post(this::retryFocus);
            short[] data = new short[65536];
            boolean reported = false;
            do {
                int count = samples(token, data);
                for (int offset = 0; offset < count;) {
                    if (!output.ready()) return;
                    int n = track.write(data, offset, count - offset, AudioTrack.WRITE_NON_BLOCKING);
                    if (n < 0) throw new IllegalStateException("Movie audio output failed " + n);
                    offset += n;
                    output.written += n;
                    output.publishPosition();
                    if (n == 0) {
                        if (output.stalled()) {
                            Log.w(TAG, "Movie audio clock stalled, continuing without audio");
                            return;
                        }
                        SystemClock.sleep(5);
                    } else if (!reported) {
                        reported = true;
                        Log.i(TAG, "Movie PCM accepted 48000 Hz stereo, volume=" + volume);
                    }
                }
                if (!output.ready()) return;
            } while (next(token, 0) >= 0);

            while (output.ready() && output.publishPosition() < output.written / 2) {
                if (output.stalled()) {
                    Log.w(TAG, "Movie audio tail stalled");
                    break;
                }
                SystemClock.sleep(5);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = reason(e);
        } catch (RuntimeException | LinkageError e) {
            error = reason(e);
        } finally {
            if (track != null) try {
                track.release();
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not release movie audio output", e);
            }
            if (token != 0) close(token);
            endAudio(error);
        }
    }

    private void endAudio(String error) {
        synchronized (gate) {
            audioReady = false;
            audioEnded = true;
            if (!stopped && !decoderFinished) audioFailure = error;
            clock.continueSilently(System.nanoTime());
            gate.notifyAll();
        }
        owner.post(() -> { if (!mayRequestFocus()) audioFocus.abandon(); });
    }

    private static String reason(Throwable error) {
        return error.getMessage() == null ? error.toString() : error.getMessage();
    }

    private static void setGain(AudioTrack track, float gain) {
        int result = track.setVolume(gain);
        if (result != AudioTrack.SUCCESS) throw new IllegalStateException("Movie audio gain failed " + result);
    }

    private final class AudioOutput {
        private final AudioTrack track;
        private final long first;
        private boolean playing;
        private float gain;
        private long written, lastHead, lastAdvance = activeMicros();

        AudioOutput(AudioTrack track, long first) {
            this.track = track;
            this.first = first;
        }

        boolean ready() throws InterruptedException {
            for (;;) {
                boolean run;
                synchronized (gate) {
                    if (stopped || decoderFinished) return false;
                    run = clock.running;
                    if (run && !playing) {
                        long remaining = first - clock.position(System.nanoTime());
                        if (remaining > 0) {
                            gate.wait(Math.max(1, Math.min(10, remaining / 1000)));
                            continue;
                        }
                    }
                }
                if (run) {
                    // A muted track still drives the A/V clock during focus denial or loss.
                    float nextGain = audioFocus.hasFocus() ? volume : 0f;
                    if (gain != nextGain) {
                        setGain(track, nextGain);
                        gain = nextGain;
                    }
                    if (!playing) {
                        track.play();
                        playing = true;
                        lastAdvance = activeMicros();
                        publishPosition();
                    }
                    return true;
                }
                if (playing) {
                    track.pause();
                    playing = false;
                    publishPosition();
                }
                if (!awaitPlayback()) return false;
            }
        }

        long publishPosition() {
            long head = Integer.toUnsignedLong(track.getPlaybackHeadPosition());
            if (head > lastHead) {
                lastHead = head;
                lastAdvance = activeMicros();
            }
            synchronized (gate) {
                clock.audioPosition(first + head * 1000000 / RATE);
                gate.notifyAll();
            }
            return head;
        }

        boolean stalled() {
            return lastHead < written / 2 && activeMicros() - lastAdvance >= 2_000_000;
        }
    }

    static final class Clock {
        private boolean running;
        private long since, elapsed, position = -1, silentAt = -1;
        private long audioPrerollAt = -1, firstAudioPts;

        void setRunning(boolean value, long now) {
            if (running == value) return;
            if (running) elapsed += now - since;
            since = now;
            running = value;
        }

        long activeMicros(long now) {
            return (elapsed + (running ? now - since : 0)) / 1000;
        }

        void prepareAudio(long firstPts, long now) {
            firstAudioPts = firstPts;
            audioPrerollAt = activeMicros(now);
        }

        void audioPosition(long pts) {
            position = Math.max(position, pts);
        }

        void continueSilently(long now) {
            position = Math.max(0, position(now));
            silentAt = activeMicros(now);
        }

        long position(long now) {
            if (silentAt >= 0) return position + activeMicros(now) - silentAt;
            if (position >= 0 || audioPrerollAt < 0) return position;
            return Math.min(firstAudioPts, activeMicros(now) - audioPrerollAt);
        }
    }

    private static native long open(byte[] filename, Surface surface);
    private static native long openVideo(byte[] filename);
    private static native boolean setSurface(long token, Surface surface);
    private static native int[] size(long token);
    private static native long next(long token, int catchUp);
    private static native boolean present(long token);
    private static native int samples(long token, short[] buffer);
    private static native void close(long token);
}
