package com.tyranor.runtime.systemnnn;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;
import java.util.function.IntConsumer;

/** CNotice artwork, positions and timing in game coordinates. */
final class GameNotice extends View {
    private final GameUiAssets assets;
    private final IntConsumer sound;
    private final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect src=new Rect();
    private final RectF dst=new RectF();
    private final int[] x=new int[3],y=new int[3],alpha=new int[3],time=new int[3],cue=new int[3];
    private int canvasWidth=1280,canvasHeight=720,number,frameMs,previousPhase;
    private long started;
    private Bitmap image;

    GameNotice(Context context,GameUiAssets assets,IntConsumer sound){
        super(context);this.assets=assets;this.sound=sound;
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);setVisibility(GONE);
    }
    void canvas(int width,int height){canvasWidth=width;canvasHeight=height;invalidate();}
    void clear(){image=null;number=0;setVisibility(GONE);}
    static String filename(GameUiAssets.Config cfg,int number){
        int bx=limit(cfg.number("blockX",1),1,64),by=limit(cfg.number("blockY",1),1,64);
        int sheet=(number-1)/(bx*by);
        String name=cfg.value("filenameNoticeTag","ta_notice");
        return sheet>0?name+(sheet+1):name;
    }
    void show(int notice,int frameMs){
        GameUiAssets.Config cfg=assets.notice;
        if(notice<=0||notice>Math.min(64,cfg.number("noticeNumber",1)))return;
        if(image!=null&&number==notice&&cfg.number("sameDontSetFlag",1)!=0)return;
        Bitmap bitmap=assets.image(filename(cfg,notice));
        if(bitmap==null)return;
        int w=limit(cfg.number("sizeX",160),1,4096),h=limit(cfg.number("sizeY",80),1,4096);
        int bx=limit(cfg.number("blockX",1),1,64),by=limit(cfg.number("blockY",1),1,64);
        int sx=(notice-1)%bx*w,sy=((notice-1)/bx)%by*h;
        if(sx+w>bitmap.getWidth()||sy+h>bitmap.getHeight())return;
        String[] phases={"start","wait","end"};
        for(int i=1;i<=notice;i++){
            for(int p=0;p<phases.length;p++){
                String key=phases[p];int fallback=Math.max(0,p-1);
                x[p]=cfg.number(key+"X"+i,i==1?(p==0?0:x[fallback]):x[p]);
                y[p]=cfg.number(key+"Y"+i,i==1?(p==0?0:y[fallback]):y[p]);
                alpha[p]=limit(cfg.number(key+"Percent"+i,i==1?(p==0?100:alpha[fallback]):alpha[p]),0,100);
                time[p]=limit(cfg.number(key+"Time"+i,i==1?(p==0?30:time[fallback]):time[p]),0,3600);
                cue[p]=cfg.number(key+"Sound"+i,i==1?0:cue[p]);
            }
        }
        src.set(sx,sy,sx+w,sy+h);image=bitmap;number=notice;this.frameMs=limit(frameMs,1,1000);
        started=SystemClock.uptimeMillis();previousPhase=0;setVisibility(VISIBLE);invalidate();
        if(cue[0]>0)sound.accept(cue[0]-1);
    }
    @Override protected void onDraw(Canvas canvas){
        if(image==null)return;
        float elapsed=(SystemClock.uptimeMillis()-started)/(float)frameMs;
        int phase=0;
        while(phase<3&&elapsed>=time[phase])elapsed-=time[phase++];
        if(phase==3){clear();return;}
        if(phase!=previousPhase){
            previousPhase=phase;
            if(cue[phase]>0)sound.accept(cue[phase]-1);
        }
        int from=phase==0?0:1,to=phase==2?2:1;
        float t=elapsed/time[phase];
        float px=x[from]+((float)x[to]-x[from])*t,py=y[from]+((float)y[to]-y[from])*t;
        float sx=getWidth()/(float)canvasWidth,sy=getHeight()/(float)canvasHeight;
        dst.set(px*sx,py*sy,(px+src.width())*sx,(py+src.height())*sy);
        paint.setAlpha(Math.round((alpha[from]+(alpha[to]-alpha[from])*t)*2.55f));
        canvas.drawBitmap(image,src,dst,paint);postInvalidateOnAnimation();
    }
    private static int limit(int value,int min,int max){return Math.max(min,Math.min(max,value));}
}
