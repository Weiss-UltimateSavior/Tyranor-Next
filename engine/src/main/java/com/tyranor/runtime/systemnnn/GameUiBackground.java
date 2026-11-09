package com.tyranor.runtime.systemnnn;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import java.util.function.Consumer;

/** A view borrows a decorative VM without stepping or replacing the story session. */
final class GameUiBackground extends TextureView implements TextureView.SurfaceTextureListener {
    private static final int FIRST_FRAME_RETRY_MS=100,MAX_FIRST_FRAME_RETRIES=4;
    private final UiPreviewPool pool;
    private final Handler tasks;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final int frameMs,maxSteps;
    private final ChartBackdrop chart;
    private UiPreviewPool.Lease lease;
    private volatile SurfaceTexture requestedTexture;
    private SurfaceTexture connectedTexture;
    private long deadline;
    private volatile boolean closed,active=true,frameReady;
    private boolean held=true;
    private int firstFrameRetries;
    private boolean chartFramePending;
    private boolean chartCompositionWarning;
    private int mouseX=-1,mouseY=-1;
    private boolean pointerHeld,clicked;

    GameUiBackground(Context context,UiPreviewPool pool,UiPreviewPool.Request request,UiPreviewPool.Warmup warmup,
                     int frameMs,int maxSteps,int[] saved,int savedWidth,int savedHeight){
        this(context,pool,request,warmup,frameMs,maxSteps,saved,savedWidth,savedHeight,null);
    }
    GameUiBackground(Context context,UiPreviewPool pool,UiPreviewPool.Request request,UiPreviewPool.Warmup warmup,
                     int frameMs,int maxSteps,int[] saved,int savedWidth,int savedHeight,ChartBackdrop chart){
        super(context);this.pool=pool;tasks=pool.tasks;this.frameMs=frameMs;this.maxSteps=maxSteps;
        this.chart=chart;
        setOpaque(false);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        if(chart==null)setSurfaceTextureListener(this);else setAlpha(0);
        tasks.post(()->{
            if(closed||pool.isClosed())return;
            try{
                lease=pool.acquire(request,warmup,saved,savedWidth,savedHeight);
                if(lease==null&&!closed&&!pool.isClosed())failed(new IllegalStateException("No available menu preview"));
                if(chart!=null&&lease!=null){publishChart();schedule();}
            }
            catch(RuntimeException|LinkageError e){failed(e);}
        });
    }
    private long handle(){return closed||lease==null?0:lease.handle();}
    boolean splitChart(){return chart!=null;}
    boolean frameReady(){return frameReady||getVisibility()!=VISIBLE;}
    void copyFrame(int width,int height,Consumer<Bitmap> complete){
        if(!frameReady||getVisibility()!=VISIBLE){complete.accept(null);return;}
        if(!tasks.post(()->{
            Bitmap image=null;
            try{
                long handle=handle();
                if(handle!=0){
                    image=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
                    image.setHasAlpha(false);
                    NativeUiPreview.capture(handle,image);
                }
            }catch(RuntimeException|LinkageError e){
                if(image!=null)image.recycle();
                image=null;
                Log.w("SystemNNN","Unable to capture menu script frame",e);
            }
            Bitmap frame=image;
            main.post(()->complete.accept(frame));
        }))complete.accept(null);
    }
    void pointer(int x,int y,boolean down,boolean edge){
        tasks.post(()->{if(handle()!=0&&active&&!held){mouseX=x;mouseY=y;pointerHeld=down;clicked|=edge;}});
    }
    void page(int number){page(number,-1);}
    void page(int number,int chartTiles){tasks.post(()->{
        long handle=handle();
        if(handle!=0)try{
            NativeUiPreview.page(handle,number);
            if(chart!=null){chart.tiles(chartTiles);publishChart();}
        }catch(RuntimeException|LinkageError e){failed(e);}
    });}
    private void publishChart(){
        if(chartFramePending||closed)return;
        ChartBackdrop.Frame frame=chart.render(handle(),ids->
            (base,center,content,output,done)->composeChart(ids,base,center,content,output,done));
        if(frame==null)return;
        chartFramePending=true;
        main.post(()->{
            if(!closed){
                chart.display.accept(frame);
                if(!frameReady){frameReady=true;notifyFrameReady();}
            }
            tasks.post(()->chartFramePending=false);
        });
    }
    private void composeChart(long[] ids,Bitmap base,Bitmap center,Bitmap content,Bitmap output,Consumer<Boolean> done){
        if(!tasks.post(()->{
            boolean drawn=false;
            try{
                long handle=handle();
                if(handle!=0&&chart.matchesTextures(handle,ids))
                    drawn=NativeUiPreview.chartComposite(handle,chart.centerLayer,base,center,content,output);
            }catch(RuntimeException|LinkageError e){
                if(!chartCompositionWarning){chartCompositionWarning=true;Log.w("SystemNNN","Chart screen effect could not use its background",e);}
            }
            boolean ready=drawn;
            main.post(()->done.accept(ready&&!closed));
        }))done.accept(false);
    }
    void active(boolean value){tasks.post(()->{if(active!=value){active=value;schedule();}});}
    void hold(boolean value){tasks.post(()->{if(held!=value){held=value;schedule();}});}
    private void schedule(){
        tasks.removeCallbacks(ticker);
        tasks.removeCallbacks(retryFirstFrame);
        boolean connected=connectedTexture!=null&&connectedTexture==requestedTexture;
        boolean usable=active&&handle()!=0&&(chart!=null||connected);
        if(usable&&!held&&(chart!=null||frameReady)){
            deadline=SystemClock.uptimeMillis();tasks.post(ticker);
        }else{
            clicked=false;pointerHeld=false;
            if(usable&&connected&&!frameReady)tasks.postDelayed(retryFirstFrame,FIRST_FRAME_RETRY_MS);
        }
    }
    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture,int width,int height){
        requestedTexture=texture;frameReady=false;
        Surface surface=new Surface(texture);
        if(!tasks.post(()->{
            if(requestedTexture!=texture||handle()==0){surface.release();return;}
            try{
                lease.surface(surface);connectedTexture=texture;firstFrameRetries=0;schedule();
            }catch(RuntimeException|LinkageError e){failed(e);}
        }))surface.release();
    }
    private final Runnable retryFirstFrame=new Runnable(){public void run(){
        SurfaceTexture texture=connectedTexture;
        if(!active||frameReady||texture==null||texture!=requestedTexture||handle()==0)return;
        if(firstFrameRetries>=MAX_FIRST_FRAME_RETRIES){
            main.post(()->{
                if(closed||!active||frameReady||texture!=requestedTexture)return;
                closed=true;
                tasks.post(()->release(false));
                showFailure(new IllegalStateException("No first frame from menu background surface"));
            });
            return;
        }
        try{
            firstFrameRetries++;
            lease.retrySurface();
            tasks.postDelayed(this,FIRST_FRAME_RETRY_MS);
        }catch(RuntimeException|LinkageError e){
            if(texture==requestedTexture)failed(e);
        }
    }};
    private final Runnable ticker=new Runnable(){public void run(){
        long handle=handle();
        if(handle==0||!active||held||chart==null&&
            (!frameReady||connectedTexture==null||connectedTexture!=requestedTexture))return;
        long now=SystemClock.uptimeMillis(),late=Math.max(0,now-deadline);
        int steps=(int)Math.min(maxSteps,1+late/frameMs);
        // Discard debt beyond the game's catch-up limit. A later pause starts a new clock.
        deadline=now-late%frameMs+frameMs;
        try{
            NativeUiPreview.tick(handle,mouseX,mouseY,clicked,pointerHeld,steps);
            if(chart!=null)publishChart();
            clicked=false;
            tasks.postAtTime(this,deadline);
        }catch(RuntimeException|LinkageError e){failed(e);}
    }};
    private void failed(Throwable e){
        closed=true;release(false);
        main.post(()->showFailure(e));
    }
    private void showFailure(Throwable e){
        Log.w("SystemNNN","Unable to run menu background script",e);
        setVisibility(GONE);
        if(getParent() instanceof GameUiPanel)((GameUiPanel)getParent()).backgroundFailed(this,e);
    }
    private void release(boolean reusable){
        tasks.removeCallbacks(ticker);
        tasks.removeCallbacks(retryFirstFrame);
        if(lease!=null){lease.release(reusable);lease=null;}
        connectedTexture=null;
    }
    void close(){
        if(closed)return;
        closed=true;requestedTexture=null;
        tasks.post(()->release(true));
    }
    @Override protected void onDetachedFromWindow(){close();super.onDetachedFromWindow();}
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture){
        if(requestedTexture==texture){requestedTexture=null;frameReady=false;}
        boolean posted=tasks.post(()->{
            try{
                if(connectedTexture!=texture)return;
                if(lease!=null)lease.surface(null);
                connectedTexture=null;schedule();
            }catch(RuntimeException|LinkageError e){failed(e);}
            finally{texture.release();}
        });
        // The preview pool releases its native windows before quitting this queue.
        return !posted;
    }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s,int w,int h){}
    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture){
        if(closed||requestedTexture!=texture||frameReady)return;
        frameReady=true;
        tasks.post(this::schedule);
        notifyFrameReady();
    }
    private void notifyFrameReady(){if(getParent() instanceof GameUiPanel)((GameUiPanel)getParent()).backgroundReady();}
}
