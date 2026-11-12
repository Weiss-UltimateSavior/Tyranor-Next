package com.tyranor.runtime.systemnnn;

import android.graphics.*;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import java.util.function.Consumer;

/** Configured frame clocks for CCommonGeneral and the menu button reveal. */
final class UiOpening {
    private final int period,fadeFrames,fadeLast,fadeType,fadeColor,wait,reveal,revealType,deltaX,deltaY;
    private final boolean stagger,skip,exiting;
    private static final Paint SNAPSHOT_PAINT=new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect bounds=new Rect();
    private long start=-1;
    private boolean revealSkipped,consumeGesture,targetReady;
    private Bitmap before,after;
    private Bitmap wipeImage;
    private Paint maskPaint;
    private long wipeHandle;
    private int wipeFrame=-1;
    private boolean wipeFailed;

    UiOpening(GameUiAssets.Config cfg,int period,boolean system){
        this(cfg,period,system,false,false);
    }
    static UiOpening exit(GameUiAssets.Config cfg,int period){
        return new UiOpening(cfg,period,false,true,false);
    }
    static UiOpening jump(GameUiAssets.Config cfg,int period){
        return new UiOpening(cfg,period,false,true,true);
    }
    private UiOpening(GameUiAssets.Config cfg,int period,boolean system,boolean exiting,boolean jumping){
        this.period=Math.max(1,period);
        this.exiting=exiting;
        String prefix=exiting?"exitFadeOut":"enterFadeIn";
        int jumpType=jumping?cfg.number("jumpFadeType",-1):-1;
        fadeType=jumping?Math.max(1,jumpType):cfg.number(prefix+"Type",0);
        boolean supported=supportedFade(fadeType,exiting);
        fadeFrames=supported&&(!jumping||jumpType>=0)?frames(cfg.number(jumping?"jumpFadeFrame":prefix+"Time",jumping?0:10)):0;
        fadeLast=Math.max(1,fadeFrames-(exiting&&!(jumping&&jumpType==0)?1:0));
        String colorPrefix=jumping?"jumpExitScreen":prefix+"Color";
        int defaultColor=jumping?128:0;
        fadeColor=Color.rgb(channel(cfg.number(colorPrefix+"R",defaultColor)),channel(cfg.number(colorPrefix+"G",defaultColor)),
            channel(cfg.number(colorPrefix+"B",defaultColor)));
        skip=!jumping&&cfg.number(prefix+"SkipOkFlag",1)!=0;
        int menuType=system?cfg.number("MenuPrintType",1):cfg.number("menuStartEffectType",0);
        wait=exiting||system?0:frames(cfg.number("menuStartWaitTime",0));
        reveal=exiting||system&&menuType==0?0:frames(cfg.number(system?"MenuPrintTime":"menuStartEffectTime",0));
        revealType=menuType;stagger=system;
        deltaX=cfg.number("menuStartDeltaX",0);deltaY=cfg.number("menuStartDeltaY",0);
    }
    private static boolean supportedFade(int type,boolean exiting){return type>=1&&type<=96||!exiting&&type==-1;}
    static boolean hasEnterFade(GameUiAssets.Config config){
        return supportedFade(config.number("enterFadeInType",0),false)&&config.number("enterFadeInTime",10)>0;
    }
    private static int frames(int n){return Math.max(0,Math.min(1000,n));}
    private static int channel(int n){return Math.max(0,Math.min(255,n));}
    void begin(){if(start<0)start=SystemClock.uptimeMillis();}
    private long frame(){return start<0?0:(SystemClock.uptimeMillis()-start)/period;}
    private long menuFrame(){return Math.max(0,frame()-fadeFrames);}
    boolean running(){return fading()||!revealSkipped&&menuFrame()<wait+reveal;}
    boolean fading(){return frame()<fadeFrames;}
    boolean hasFade(){return fadeFrames>0;}
    boolean needsWipeTarget(){return hasFade()&&fadeType>1;}
    boolean delaysControls(){return wait>0||reveal>0;}
    int color(){return fadeColor;}
    boolean needsPrevious(){return fadeFrames>0&&fadeType!=-1;}
    void previous(Bitmap bitmap){before=bitmap;}
    void drawPrevious(Canvas canvas,View view){
        if(before!=null)drawFrame(canvas,view,before,255);
    }
    void target(Bitmap bitmap){after=bitmap;targetReady=true;}
    boolean needsTarget(){return !exiting&&hasFade()&&!targetReady;}
    boolean drawTarget(Canvas canvas,View view){
        if(exiting||!fading()||after==null)return false;
        drawFrame(canvas,view,after,255);
        return true;
    }
    boolean skip(){
        if(!running())return false;
        if(fading()){
            if(skip&&start>=0)start=SystemClock.uptimeMillis()-fadeFrames*(long)period;
        }else revealSkipped=true;
        return true;
    }
    boolean touch(MotionEvent event){
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN)consumeGesture=skip();
        boolean consume=consumeGesture;
        if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)consumeGesture=false;
        return consume;
    }
    boolean reveal(Canvas c,View child,int index,int count,float sx,float sy){
        if(!delaysControls()||revealSkipped)return true;
        long frames=menuFrame();
        if(fading()||frames<wait)return false;
        float progress=reveal==0?1:Math.min(1,(frames-wait)/(float)reveal);
        if(progress>=1)return true;
        if(progress<=0)return false;
        float x=child.getLeft(),y=child.getTop(),w=child.getWidth(),h=child.getHeight();
        int kind=revealType/2;
        if(stagger||kind>=6&&kind<=11){
            float delay=1f/(Math.max(1,count)*2+1);
            float local=Math.max(0,Math.min(1,(progress-index*delay)/(1-count*delay)));
            if(local<=0)return false;
            switch(stagger?revealType:kind-5){
            case 1:c.clipRect(x,y,x+w,y+h*local);c.translate(0,-h*(1-local));break;
            case 2:c.clipRect(x,y+h*(1-local),x+w,y+h);c.translate(0,h*(1-local));break;
            case 3:c.clipRect(x,y,x+w*local,y+h);c.translate(-w*(1-local),0);break;
            case 4:c.clipRect(x+w*(1-local),y,x+w,y+h);c.translate(w*(1-local),0);break;
            case 5:c.clipRect(x,y+h*(1-local)/2,x+w,y+h*(1+local)/2);break;
            case 6:c.clipRect(x+w*(1-local)/2,y,x+w*(1+local)/2,y+h);break;
            default:break;
            }
        }else{
            if(kind>=3&&kind<=5)c.scale(kind==4?1:progress,kind==5?1:progress,x+w/2,y+h/2);
            else if(kind<=2){
                double angle=kind==0?0:(index/(double)Math.max(1,count)+(kind==2?progress:0))*Math.PI*2;
                float tx=deltaX*sx*(1-progress),ty=deltaY*sy*(1-progress);
                c.translate((float)(tx*Math.cos(angle)-ty*Math.sin(angle)),(float)(tx*Math.sin(angle)+ty*Math.cos(angle)));
            }
            if((revealType&1)!=0){
                c.saveLayerAlpha(null,Math.round(progress*255));
            }
        }
        return true;
    }
    boolean overlay(Canvas c,View view,int logicalWidth,int logicalHeight){
        long at=frame();
        if(at>=fadeFrames){
            close();
            if(exiting)c.drawColor(fadeColor);
            return false;
        }
        int tick=(int)Math.min(at,fadeLast);
        if(fadeType>1&&drawWipe(c,view,logicalWidth,logicalHeight,tick))return true;
        int alpha=255-tick*255/fadeLast;
        if(fadeType==-1){
            paint.setColor(fadeColor);paint.setAlpha(alpha);
            c.drawRect(0,0,view.getWidth(),view.getHeight(),paint);
        }else if(before!=null){
            if(exiting)c.drawColor(fadeColor);
            drawFrame(c,view,before,alpha);
        }
        return true;
    }
    private boolean drawWipe(Canvas c,View view,int width,int height,int tick){
        if(wipeFailed||before==null||(!exiting&&after==null))return false;
        if(tick==0){drawFrame(c,view,before,255);return true;}
        // These families only select/mix the same source pixel. Keep their geometry
        // at the game's logical size, but never downsample the captured UI art.
        boolean masked=fadeType>=89&&fadeType<=96;
        try{
            if(wipeHandle==0){
                double scale=Math.min(1,Math.min(4096.0/Math.max(width,height),Math.sqrt((2*1024*1024.0)/width/height)));
                int w=Math.max(1,(int)(width*scale)),h=Math.max(1,(int)(height*scale));
                wipeImage=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
                wipeImage.setHasAlpha(false);
                if(masked){
                    Bitmap white=solid(Color.WHITE),black=null;
                    try{
                        black=solid(Color.BLACK);
                        wipeHandle=NativeUiPreview.createWipe(white,black,fadeColor,w,h);
                    }finally{
                        // createWipe copies these private 1px inputs synchronously.
                        white.recycle();if(black!=null)black.recycle();
                    }
                    maskPaint=new Paint(Paint.FILTER_BITMAP_FLAG);
                    maskPaint.setColorFilter(new ColorMatrixColorFilter(new float[]{
                        0,0,0,0,0, 0,0,0,0,0, 0,0,0,0,0, 1,0,0,0,0}));
                    maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
                }else wipeHandle=NativeUiPreview.createWipe(before,after,fadeColor,w,h);
            }
            if(wipeFrame!=tick){
                NativeUiPreview.drawWipe(wipeHandle,wipeImage,fadeType-1,tick,fadeLast);
                wipeFrame=tick;
            }
        }catch(RuntimeException|LinkageError error){
            Log.w("SystemNNN","Panel transition "+fadeType+" uses crossfade",error);
            wipeFailed=true;closeWipe();return false;
        }
        if(masked){
            if(exiting)c.drawColor(fadeColor);else drawFrame(c,view,after,255);
            int saved=c.saveLayer(0,0,view.getWidth(),view.getHeight(),null);
            try{
                drawFrame(c,view,before,255);
                c.drawBitmap(wipeImage,null,bounds,maskPaint);
            }finally{c.restoreToCount(saved);}
        }else drawFrame(c,view,wipeImage,255);
        return true;
    }
    private void drawFrame(Canvas canvas,View view,Bitmap image,int alpha){
        bounds.set(0,0,view.getWidth(),view.getHeight());paint.setAlpha(alpha);
        canvas.drawBitmap(image,null,bounds,paint);
    }
    private void closeWipe(){
        long handle=wipeHandle;wipeHandle=0;
        if(handle!=0)NativeUiPreview.closeWipe(handle);
        wipeImage=null;maskPaint=null;wipeFrame=-1;
    }
    void close(){
        before=null;after=null;closeWipe();
    }
    static float snapshotScale(int width,int height){
        if(width<=0||height<=0)return 1;
        double scale=Math.min(1,Math.min(4096.0/Math.max(width,height),Math.sqrt((4*1024*1024.0)/width/height)));
        // Floor first so callers rounding the dimensions stay inside both limits.
        int w=Math.max(1,(int)(width*scale)),h=Math.max(1,(int)(height*scale));
        return (float)Math.min(w/(double)width,h/(double)height);
    }
    static Bitmap snapshot(View view,Bitmap base,Consumer<Canvas> draw){
        int w=view.getWidth(),h=view.getHeight();
        if(w<=0||h<=0)return base;
        float scale=snapshotScale(w,h);
        Bitmap image=Bitmap.createBitmap(Math.max(1,Math.round(w*scale)),Math.max(1,Math.round(h*scale)),Bitmap.Config.ARGB_8888);
        Canvas c=new Canvas(image);c.scale(scale,scale);c.drawColor(Color.BLACK);
        if(base!=null)c.drawBitmap(base,null,new Rect(0,0,w,h),SNAPSHOT_PAINT);
        draw.accept(c);
        return image;
    }
    static void drawOnto(View view,Bitmap frame,Consumer<Canvas> draw){
        Canvas canvas=new Canvas(frame);
        canvas.scale(frame.getWidth()/(float)view.getWidth(),frame.getHeight()/(float)view.getHeight());
        draw.accept(canvas);
    }
    static Bitmap solid(int color){return Bitmap.createBitmap(new int[]{color},1,1,Bitmap.Config.ARGB_8888);}
}
