package com.tyranor.runtime.systemnnn;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Process;
import android.util.Log;
import com.core.engine.R;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

// The game worker joins this consumer before replacing or freeing the native session.
final class DeviceAudio {
    static int rate() {
        int rate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC);
        return rate >= 8000 && rate <= 192000 ? rate : 48000;
    }
    interface ErrorHandler { void failed(DeviceAudio source, String reason); }
    private volatile boolean running = true;
    private volatile boolean focused;
    private boolean focusRequested;
    private final AudioManager manager;
    private final AudioFocusRequest focus;
    private final Thread thread;

    DeviceAudio(Context context, Handler owner, long handle, int rate, ErrorHandler errors) {
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        manager=context.getSystemService(AudioManager.class);
        focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(true).setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(change->{
                focused=change==AudioManager.AUDIOFOCUS_GAIN;
                if(change==AudioManager.AUDIOFOCUS_LOSS)focusRequested=false;
            },owner).build();
        thread = new Thread(() -> {
            AudioTrack track = null;
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
                int minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
                if (minimum <= 0) throw new IllegalStateException(context.getString(R.string.engine_systemnnn_host_pcm_unsupported));
                track = new AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setBufferSizeInBytes(Math.max(minimum, rate * 4 / 12))
                    .setTransferMode(AudioTrack.MODE_STREAM).build();
                final int frames = 512;
                ByteBuffer buffer = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder());
                boolean announced = false;
                boolean playing=false;
                while (running) {
                    if(!focused){
                        if(playing){
                            track.pause();
                            track.flush();
                            playing=false;
                        }
                        Thread.sleep(20);
                        continue;
                    }
                    if(!playing){
                        track.play();
                        playing=true;
                    }
                    buffer.clear();
                    NativeGame.audio(handle, buffer, frames);
                    while (running && focused && buffer.hasRemaining()) {
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
                if (running) errors.failed(this,e.toString());
            } finally {
                if (track != null) {
                    try { track.pause(); track.flush(); } catch (IllegalStateException ignored) {}
                    track.release();
                }
            }
        }, "SystemNNN-Audio");
        thread.start();
        requestFocus();
    }
    void requestFocus(){
        if(!running||focusRequested||manager==null)return;
        try{
            int result=manager.requestAudioFocus(focus);
            focusRequested=result!=AudioManager.AUDIOFOCUS_REQUEST_FAILED;
            focused=result==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }catch(RuntimeException e){Log.w("SystemNNN","Could not request audio focus",e);}
    }
    void close() {
        running = false;
        if(manager!=null)try{manager.abandonAudioFocusRequest(focus);}
        catch(RuntimeException e){Log.w("SystemNNN","Could not release audio focus",e);}
        thread.interrupt();
        boolean interrupted = false;
        while (thread.isAlive()) {
            try { thread.join(); } catch (InterruptedException e) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
