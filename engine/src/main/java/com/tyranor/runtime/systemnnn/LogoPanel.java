package com.tyranor.runtime.systemnnn;

import com.core.engine.R;

import android.content.Context;
import com.core.engine.EngineUiText;
import android.graphics.*;
import android.os.SystemClock;
import android.view.View;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** The game's initial logo sequence, timed in engine frames. */
final class LogoPanel extends View {
    static final class Slide {
        final Bitmap image;
        final int in,hold,out,inColor,outColor;
        final boolean skip,captureExit;
        Slide(Bitmap image,GameUiAssets.Config cfg,int index){
            this.image=image;
            boolean times=cfg.number("useCustomTimeFlag",0)!=0,colors=cfg.number("useCustomColorFlag",0)!=0;
            in=frames(cfg,"FadeInTime",index,20,times);
            hold=frames(cfg,"KeepTime",index,20,times);
            out=frames(cfg,"FadeOutTime",index,10,times);
            inColor=color(cfg,"FadeInColor",index,colors);outColor=color(cfg,"FadeOutColor",index,colors);
            skip=cfg.number("useCustomSkipFlag",0)==0||cfg.number("skipOK"+index,1)!=0;
            captureExit=cfg.number("createExitScreenFlag",1)!=0||cfg.number("exitFadeOutType",0)!=0;
        }
    }
    static List<Slide> load(GameUiAssets.Config cfg,long handle) {
        List<Slide> slides=new ArrayList<>();
        long total=0;
        for(int i=1;i<=Math.min(8,cfg.number("logoNumber",1));i++){
            String name=GameUiAssets.systemImageKey(cfg.value("filename"+i,""));
            if(name.isEmpty())continue;
            int[] pixels=NativeGame.titleSprite(handle,name.getBytes(StandardCharsets.ISO_8859_1));
            if(pixels==null||pixels.length<2||(long)pixels[0]*pixels[1]!=pixels.length-2L||total+pixels.length>4000000)continue;
            total+=pixels.length;
            slides.add(new Slide(Bitmap.createBitmap(pixels,2,pixels[0],pixels[0],pixels[1],Bitmap.Config.ARGB_8888),cfg,i));
        }
        return slides;
    }
    private static int frames(GameUiAssets.Config cfg,String key,int index,int fallback,boolean custom){
        int n=cfg.number("default"+key,fallback);
        if(custom)n=cfg.number(key+index,n);
        return Math.max(0,Math.min(10000,n));
    }
    private static int channel(GameUiAssets.Config cfg,String key,int index,boolean custom){
        int value=cfg.number("default"+key,255);
        return Math.max(0,Math.min(255,custom?cfg.number(key+index,value):value));
    }
    private static int color(GameUiAssets.Config cfg,String key,int index,boolean custom){
        return Color.rgb(channel(cfg,key+"R",index,custom),channel(cfg,key+"G",index,custom),channel(cfg,key+"B",index,custom));
    }
    private final List<Slide> slides;
    private final Runnable done;
    private final int frameMs;
    private final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect dest=new Rect();
    private final Runnable refresh=this::invalidate;
    private int index;
    private long since=-1;
    private boolean finished;
    LogoPanel(Context context,List<Slide> slides,int frameMs,Runnable done){
        super(context);this.slides=slides;this.frameMs=frameMs;this.done=done;
        setContentDescription(EngineUiText.get(context,R.string.engine_systemnnn_ctl_startup_logo));setSoundEffectsEnabled(false);setOnClickListener(v->skip());
    }
    void skip(){if(!finished&&slides.get(index).skip)next();}
    Bitmap exitScreen(){
        Slide slide=slides.get(index);
        if(!slide.captureExit)return null;
        return slide.out==0?slide.image:UiOpening.solid(slide.outColor);
    }
    private void next(){
        removeCallbacks(refresh);
        if(index+1==slides.size()){finished=true;post(done);}
        else{index++;since=-1;invalidate();}
    }
    @Override protected void onDraw(Canvas c){
        long now=SystemClock.uptimeMillis();
        while(!finished){
            Slide slide=slides.get(index);
            if(since<0)since=now;
            long elapsed=now-since,in=(long)slide.in*frameMs,hold=(long)slide.hold*frameMs,out=(long)slide.out*frameMs;
            if(elapsed>=in+hold+out){next();continue;}
            c.drawColor(elapsed<in?slide.inColor:slide.outColor);
            float ratio=elapsed<in?elapsed/(float)in:elapsed<in+hold?1:(in+hold+out-elapsed)/(float)out;
            drawSlide(c,slide,Math.round(ratio*255));
            if(elapsed<in||elapsed>=in+hold)postInvalidateOnAnimation();
            else{removeCallbacks(refresh);postDelayed(refresh,in+hold-elapsed);}
            return;
        }
        Slide slide=slides.get(index);
        c.drawColor(slide.outColor);
        if(slide.out==0)drawSlide(c,slide,255);
    }
    private void drawSlide(Canvas c,Slide slide,int alpha){
        paint.setAlpha(alpha);dest.set(0,0,getWidth(),getHeight());
        c.drawBitmap(slide.image,null,dest,paint);
    }
    @Override protected void onDetachedFromWindow(){removeCallbacks(refresh);removeCallbacks(done);super.onDetachedFromWindow();}
}
