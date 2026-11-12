package com.tyranor.runtime.systemnnn;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.util.Log;
import java.util.function.BooleanSupplier;

/** Focus requests and callbacks run on the owner. PCM workers only read hasFocus(). */
final class PlayerAudioFocus {
    private final AudioManager manager;
    private final Handler owner;
    private final AudioAttributes attributes;
    private final BooleanSupplier mayRequestFocus;
    private final Runnable focusUnavailable;
    private AudioFocusRequest request;
    private long attempt;
    private boolean unavailableReported;
    private volatile boolean granted;

    PlayerAudioFocus(Context context, Handler owner, AudioAttributes attributes,
                     BooleanSupplier mayRequestFocus, Runnable focusUnavailable) {
        manager = context.getSystemService(AudioManager.class);
        this.owner = owner;
        this.attributes = attributes;
        this.mayRequestFocus = mayRequestFocus;
        this.focusUnavailable = focusUnavailable;
    }

    boolean hasFocus() {
        return granted && mayRequestFocus.getAsBoolean();
    }

    void request() {
        if (request != null || !mayRequestFocus.getAsBoolean()) return;
        if (manager == null) {
            reportUnavailable();
            return;
        }
        int result = AudioManager.AUDIOFOCUS_REQUEST_FAILED;
        long currentAttempt = ++attempt;
        try {
            request = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setAcceptsDelayedFocusGain(true)
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(change -> changed(currentAttempt, change), owner)
                .build();
            result = manager.requestAudioFocus(request);
        } catch (RuntimeException e) {
            Log.w("SystemNNN", "Could not request audio focus", e);
        }
        if (!mayRequestFocus.getAsBoolean()) {
            abandon();
        } else if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            gained();
        } else if (result != AudioManager.AUDIOFOCUS_REQUEST_DELAYED) {
            // Unregister the listener even if the system refused this request.
            abandon();
            reportUnavailable();
        }
    }

    private void changed(long currentAttempt, int change) {
        if (request == null || attempt != currentAttempt) return;
        if (!mayRequestFocus.getAsBoolean()) {
            abandon();
        } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
            gained();
        } else if (change == AudioManager.AUDIOFOCUS_LOSS) {
            abandon();
            reportUnavailable();
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                   change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            granted = false;
        }
    }

    private void gained() {
        granted = true;
        unavailableReported = false;
    }

    private void reportUnavailable() {
        if (unavailableReported || !mayRequestFocus.getAsBoolean()) return;
        unavailableReported = true;
        focusUnavailable.run();
    }

    void abandon() {
        AudioFocusRequest previous = request;
        request = null;
        granted = false;
        if (previous == null) return;
        try {
            manager.abandonAudioFocusRequest(previous);
        } catch (RuntimeException e) {
            Log.w("SystemNNN", "Could not release audio focus", e);
        }
    }
}
