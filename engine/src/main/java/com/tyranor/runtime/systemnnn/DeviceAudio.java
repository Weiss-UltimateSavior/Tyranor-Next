package com.tyranor.runtime.systemnnn;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Process;
import android.util.Log;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

// The game worker joins this consumer before replacing or freeing the native session.
final class DeviceAudio {
    static int rate() {
        int rate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC);
        return rate >= 8000 && rate <= 192000 ? rate : 48000;
    }
    interface ErrorHandler { void failed(String reason); }
    private volatile boolean running = true;
    private final Thread thread;

    DeviceAudio(long handle, int rate, ErrorHandler errors) {
        thread = new Thread(() -> {
            AudioTrack track = null;
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
                int minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
                if (minimum <= 0) throw new IllegalStateException("设备不支持双声道 PCM");
                track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setBufferSizeInBytes(Math.max(minimum, rate * 4 / 12))
                    .setTransferMode(AudioTrack.MODE_STREAM).build();
                final int frames = 512;
                ByteBuffer buffer = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder());
                boolean announced = false;
                track.play();
                while (running) {
                    buffer.clear();
                    NativeGame.audio(handle, buffer, frames);
                    while (running && buffer.hasRemaining()) {
                        int start = buffer.position();
                        int count = track.write(buffer, buffer.remaining(), AudioTrack.WRITE_NON_BLOCKING);
                        if (count < 0) throw new IllegalStateException("AudioTrack " + count);
                        if (count == 0) { Thread.sleep(3); continue; }
                        if (!announced) for (int i=start; i+1<start+count; i+=2) {
                            if (buffer.getShort(i) != 0) {
                                Log.i("SystemNNN", "AudioTrack accepted nonzero stereo PCM at " + rate + " Hz, session " + track.getAudioSessionId());
                                announced = true; break;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                if (running) errors.failed(e.toString());
            } finally {
                if (track != null) {
                    try { track.pause(); track.flush(); } catch (IllegalStateException ignored) {}
                    track.release();
                }
            }
        }, "SystemNNN-Audio");
        thread.start();
    }
    void close() {
        running = false;
        boolean interrupted = false;
        while (thread.isAlive()) {
            try { thread.join(); } catch (InterruptedException e) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
