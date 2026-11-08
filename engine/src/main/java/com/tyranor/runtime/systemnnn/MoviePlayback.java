package com.tyranor.runtime.systemnnn;

import android.content.Context;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.widget.FrameLayout;
import java.io.File;
import java.nio.charset.StandardCharsets;

/** Plays a local game movie over the canvas. The AudioTrack head is the presentation clock. */
final class MoviePlayback extends FrameLayout implements TextureView.SurfaceTextureListener {
    interface Listener {void finished(String failure);}
    private static final String TAG="SystemNNN";
    private static final int RATE=48000;
    private final TextureView picture;
    private final byte[] filename;
    private final boolean canSkip;
    private final float volume;
    private final Listener listener;
    private volatile boolean stopped;
    private volatile long position=-1,audioEndedAt;
    private volatile String audioFailure;
    private boolean started,finished;
    private int videoWidth,videoHeight;

    MoviePlayback(Context context,File file,int volume,boolean canSkip,Listener listener) {
        super(context);
        this.listener=listener;this.canSkip=canSkip;this.volume=Math.max(0,Math.min(100,volume))/100f;
        filename=file.getPath().getBytes(StandardCharsets.UTF_8);
        setBackgroundColor(Color.BLACK);setSoundEffectsEnabled(false);
        setContentDescription(canSkip?"动画播放 · 轻触返回":"动画播放");
        setOnClickListener(v->skip());
        picture=new TextureView(context);
        picture.setSurfaceTextureListener(this);
        addView(picture,new LayoutParams(-1,-1,Gravity.CENTER));
    }

    void skip(){if(canSkip)finish(null);}
    void interrupted(){finish(null);}
    private void finish(String failure){
        if(finished)return;
        finished=true;stopped=true;listener.finished(failure);
    }
    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture,int width,int height){
        if(started||stopped)return;
        started=true;
        Surface surface=new Surface(texture);
        new Thread(()->decodeVideo(surface),"SystemNNN-MovieVideo").start();
    }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture,int width,int height){}
    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture){}
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture){if(started&&!finished)finish(null);return true;}
    @Override protected void onDetachedFromWindow(){stopped=true;finished=true;super.onDetachedFromWindow();}
    @Override protected void onSizeChanged(int w,int h,int oldW,int oldH){super.onSizeChanged(w,h,oldW,oldH);fit();}
    private void fit(){
        if(videoWidth<=0||videoHeight<=0||getWidth()==0||getHeight()==0)return;
        float scale=Math.min(getWidth()/(float)videoWidth,getHeight()/(float)videoHeight);
        LayoutParams lp=(LayoutParams)picture.getLayoutParams();
        int w=Math.round(videoWidth*scale),h=Math.round(videoHeight*scale);
        if(lp.width!=w||lp.height!=h){lp.width=w;lp.height=h;picture.setLayoutParams(lp);}
    }

    private void decodeVideo(Surface surface){
        long token=0;String error=null;int rendered=0,dropped=0;
        try{
            try{token=open(filename,surface);}finally{surface.release();}
            int[] size=size(token);
            post(()->{videoWidth=size[0];videoHeight=size[1];fit();});
            new Thread(this::decodeAudio,"SystemNNN-MovieAudio").start();
            long lastDraw=0,previousPts=0;
            while(!stopped){
                long lag=position-previousPts;
                // Catch up at a keyframe only when dropping disposable pictures cannot keep pace.
                long pts=next(token,lag>1500000?2:lag>250000?1:0);
                if(pts<0)break;
                previousPts=pts;
                long now=waitForFrame(pts);
                if(stopped||audioFailure!=null)break;
                long wall=SystemClock.uptimeMillis();
                // A slow device must keep presenting even if it cannot catch the audio clock.
                if(rendered!=0&&now-pts>140000&&wall-lastDraw<120){dropped++;continue;}
                if(!present(token))throw new IllegalStateException("动画画面已不可用");
                lastDraw=wall;
                if(rendered++==0)Log.i(TAG,"Movie first frame "+size[0]+"x"+size[1]+" pts="+pts);
                if(rendered==10)Log.i(TAG,"Movie video running frames="+rendered+" dropped="+dropped+" pts="+pts+" clock="+now);
            }
            long timeout=SystemClock.uptimeMillis()+10000;
            while(!stopped&&audioFailure==null&&audioEndedAt==0&&SystemClock.uptimeMillis()<timeout)SystemClock.sleep(10);
            error=audioFailure;
        }catch(RuntimeException e){error=e.getMessage();}
        finally{
            if(token!=0)close(token);
            if(!stopped){
                Log.i(TAG,"Movie video finished frames="+rendered+" dropped="+dropped);
                String failure=error;
                post(()->finish(failure));
            }
        }
    }
    private long waitForFrame(long pts){
        long now=position;
        while(!stopped&&audioFailure==null){
            long end=audioEndedAt;
            now=position;
            if(end!=0)now=Math.max(0,now)+(System.nanoTime()-end)/1000;
            if(now>=0&&now+3000>=pts)break;
            SystemClock.sleep(now<0?5:Math.max(1,Math.min(10,(pts-now)/1000)));
        }
        return now;
    }
    private void decodeAudio(){
        long token=0;AudioTrack output=null;
        try{
            token=open(filename,null);
            if(token==0)return;
            int mask=AudioFormat.CHANNEL_OUT_STEREO,pcm=AudioFormat.ENCODING_PCM_16BIT;
            int buffer=AudioTrack.getMinBufferSize(RATE,mask,pcm);
            if(buffer<=0)throw new IllegalStateException("无法初始化动画音频");
            output=new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE).setChannelMask(mask).setEncoding(pcm).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(buffer,RATE*4/10)).build();
            output.setVolume(volume);
            short[] data=new short[65536];
            long first=next(token,0),written=0;
            if(first<0)return;
            output.play();position=first;
            boolean reported=false;
            do{
                int count=samples(token,data);
                for(int offset=0;!stopped&&offset<count;){
                    int n=output.write(data,offset,count-offset,AudioTrack.WRITE_NON_BLOCKING);
                    if(n<0)throw new IllegalStateException("动画音频输出失败 "+n);
                    if(n==0)SystemClock.sleep(5);
                    offset+=n;written+=n;
                    position=first+Integer.toUnsignedLong(output.getPlaybackHeadPosition())*1000000/RATE;
                    if(n>0&&!reported){reported=true;Log.i(TAG,"Movie PCM accepted 48000 Hz stereo, volume="+volume);}
                }
            }while(!stopped&&next(token,0)>=0);
            long timeout=SystemClock.uptimeMillis()+1000;
            while(!stopped&&SystemClock.uptimeMillis()<timeout){
                long played=Integer.toUnsignedLong(output.getPlaybackHeadPosition());
                position=first+played*1000000/RATE;
                if(played>=written/2)break;
                SystemClock.sleep(5);
            }
        }catch(RuntimeException e){audioFailure=e.getMessage();}
        finally{
            audioEndedAt=System.nanoTime();
            if(output!=null)output.release();
            if(token!=0)close(token);
        }
    }

    private static native long open(byte[] filename,Surface surface);
    private static native int[] size(long token);
    private static native long next(long token,int catchUp);
    private static native boolean present(long token);
    private static native int samples(long token,short[] buffer);
    private static native void close(long token);
}
