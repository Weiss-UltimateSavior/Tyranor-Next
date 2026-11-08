package com.tyranor.runtime.systemnnn;

import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.view.View;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** The game's initial logo sequence, timed in engine frames. */
final class LogoPanel extends View {
    static final class Slide {
        final Bitmap image;
        final int in,hold,out,inColor,outColor;
        final boolean skip;
        Slide(Bitmap image,GameUiAssets.Config cfg,int index){
            this.image=image;
            boolean times=cfg.number("useCustomTimeFlag",0)!=0,colors=cfg.number("useCustomColorFlag",0)!=0;
            in=frames(cfg,"FadeInTime",index,20,times);
            hold=frames(cfg,"KeepTime",index,20,times);
            out=frames(cfg,"FadeOutTime",index,10,times);
            inColor=color(cfg,"FadeInColor",index,colors);outColor=color(cfg,"FadeOutColor",index,colors);
            skip=cfg.number("useCustomSkipFlag",0)==0||cfg.number("skipOK"+index,1)!=0;
        }
    }
    static List<Slide> load(File root,long handle) throws IOException {
        GameUiAssets.Config cfg=GameUiAssets.readConfig(root,"logo");
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
        setContentDescription("启动 Logo");setSoundEffectsEnabled(false);setOnClickListener(v->skip());
    }
    void skip(){if(!finished&&slides.get(index).skip)next();}
    private void next(){
        removeCallbacks(refresh);
        if(++index==slides.size()){finished=true;post(done);}
        else{since=-1;invalidate();}
    }
    @Override protected void onDraw(Canvas c){
        if(finished)return;
        long now=SystemClock.uptimeMillis();if(since<0)since=now;
        Slide slide=slides.get(index);
        long elapsed=now-since,in=(long)slide.in*frameMs,hold=(long)slide.hold*frameMs,out=(long)slide.out*frameMs;
        if(elapsed>=in+hold+out){next();return;}
        c.drawColor(elapsed<in?slide.inColor:slide.outColor);
        float ratio=elapsed<in?elapsed/(float)in:elapsed<in+hold?1:(in+hold+out-elapsed)/(float)out;
        paint.setAlpha(Math.round(ratio*255));dest.set(0,0,getWidth(),getHeight());
        c.drawBitmap(slide.image,null,dest,paint);
        if(elapsed<in||elapsed>=in+hold)postInvalidateOnAnimation();
        else{removeCallbacks(refresh);postDelayed(refresh,in+hold-elapsed);}
    }
    @Override protected void onDetachedFromWindow(){removeCallbacks(refresh);removeCallbacks(done);super.onDetachedFromWindow();}
}
