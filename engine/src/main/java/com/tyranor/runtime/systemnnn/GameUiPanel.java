package com.tyranor.runtime.systemnnn;

import com.core.engine.R;

import android.content.Context;
import android.graphics.*;
import android.util.TypedValue;
import android.view.*;
import android.widget.*;
import com.core.engine.EngineUiText;
import java.util.ArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Game-owned UI art in the same logical coordinate space as the scene. */
class GameUiPanel extends FrameLayout {
    protected final GameUiAssets assets;
    protected final Runnable click;
    private final Context hostContext;
    protected int canvasWidth=1280,canvasHeight=720;
    private String backgroundName;
    private boolean transparentBackground,scriptedBackground;
    interface Sound {void play(int se,int voice);}
    private Sound sound;
    private GameUiBackground backdrop;
    private UiPreviewPool.Request backdropRequest;
    private GameButton pendingButton;
    final int frameMs;
    private UiOpening opening;
    private boolean copyingTarget,backdropHeld,leaving;
    private Runnable finishExit,capturedBackdropReady;
    private Consumer<Throwable> backdropFailure;
    private Bitmap capturedBackground;
    private View independentStatus;
    int printMode=1;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final ArrayList<Placement> positions=new ArrayList<>();
    private static final class Placement {
        final View view;final int x,y,w,h;final float font;
        Placement(View v,int x,int y,int w,int h){view=v;this.x=x;this.y=y;this.w=w;this.h=h;font=v instanceof TextView?((TextView)v).getTextSize():0;}
    }
    GameUiPanel(Context context,GameUiAssets assets,String background,Runnable click) {
        super(context);this.assets=assets;this.backgroundName=background;this.click=click;
        hostContext=EngineUiText.localizedContext(context);
        frameMs=Math.max(1,assets.game.number("frameTime",20));
        setWillNotDraw(false);setClickable(true);setSoundEffectsEnabled(false);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    final String hostText(int resource,Object... args){
        return args.length==0?hostContext.getString(resource):hostContext.getString(resource,args);
    }
    void background(String name){backgroundName=name;invalidate();}
    void transparentBackground(){transparentBackground=true;invalidate();}
    void statusForCapture(View layer){independentStatus=layer;}
    void capturedBackground(Bitmap value,Runnable ready){capturedBackground=value;capturedBackdropReady=ready;invalidate();}
    boolean awaitingCapturedBackdrop(){return capturedBackground!=null&&backdrop!=null&&!backdrop.frameReady();}
    void opening(GameUiAssets.Config cfg,boolean system){
        if(opening!=null)opening.close();
        opening=new UiOpening(cfg,frameMs,system);
        scriptedBackground=cfg.number("backScriptFlag",0)!=0;
        holdBackground(true);
    }
    boolean wantsPrevious(){return opening!=null&&opening.needsPrevious();}
    boolean openingRunning(){return opening!=null&&opening.running();}
    boolean activeFrameReady(){return !waitingForBackground()&&(opening==null||!opening.fading());}
    boolean controlsBusy(){return leaving||pendingButton!=null;}
    void previous(Bitmap picture){if(opening!=null)opening.previous(picture);}
    void capture(Consumer<Bitmap> done){
        capture(false,done);
    }
    private void capture(boolean backgroundOnly,Consumer<Bitmap> done){
        if(getWidth()<=0||getHeight()<=0){done.accept(null);return;}
        GameUiBackground background=backdrop;
        Consumer<Bitmap> compose=frame->{
            if(frame!=null){
                if(getWidth()>0&&getHeight()>0){
                    Bitmap source=frame;
                    frame=UiOpening.snapshot(this,source,canvas->{
                        if(!backgroundOnly)drawCapturedControls(canvas,background);
                    });
                    // copyFrame supplies a fresh, privately owned background bitmap.
                    if(frame!=source)source.recycle();
                }
                done.accept(frame);
            }else done.accept(UiOpening.snapshot(this,null,canvas->{
                drawBackground(canvas);
                if(!backgroundOnly)drawCapturedControls(canvas,background);
            }));
        };
        if(background==null||background.splitChart())compose.accept(null);
        else{
            // Native capture retains its 2 Mi-pixel limit; scale UI art only once,
            // with Canvas filtering, after reading the logical script background.
            double scale=Math.min(1,Math.min(4096.0/Math.max(canvasWidth,canvasHeight),
                Math.sqrt((2*1024*1024.0)/canvasWidth/canvasHeight)));
            background.copyFrame(Math.max(1,(int)(canvasWidth*scale)),Math.max(1,(int)(canvasHeight*scale)),compose);
        }
    }
    private void drawCapturedControls(Canvas canvas,GameUiBackground background){
        for(int i=0;i<getChildCount();i++){
            View child=getChildAt(i);
            if(child!=background&&child.getVisibility()==VISIBLE)drawChild(canvas,child,getDrawingTime());
        }
        if(independentStatus!=null&&independentStatus.getVisibility()==VISIBLE)independentStatus.draw(canvas);
    }
    void leave(GameUiAssets.Config config,boolean forceCapture,Consumer<Bitmap> done){
        leave(config,forceCapture,(picture,filled)->done.accept(picture));
    }
    void leave(GameUiAssets.Config config,boolean forceCapture,BiConsumer<Bitmap,Boolean> done){
        UiOpening exit=UiOpening.exit(config,frameMs);
        leave(exit,exit.hasFade()&&config.number("fillExitScreenFlag",1)!=0,
            forceCapture||config.number("createExitScreenFlag",1)!=0,done);
    }
    void leaveForBacklogJump(GameUiAssets.Config config,Consumer<Bitmap> done){
        leave(UiOpening.jump(config,frameMs),true,false,(picture,filled)->done.accept(picture));
    }
    private void leave(UiOpening exit,boolean fill,boolean captureScreen,BiConsumer<Bitmap,Boolean> done){
        if(leaving)return;
        leaving=true;
        holdBackground(true);
        if(!exit.hasFade()&&!captureScreen){
            done.accept(fill?UiOpening.solid(exit.color()):null,fill);return;
        }
        capture(false,picture->{
            if(!isAttachedToWindow())return;
            Bitmap exitScreen=fill?UiOpening.solid(exit.color()):picture;
            Runnable complete=()->done.accept(exitScreen,fill);
            if(!exit.hasFade()||picture==null){complete.run();return;}
            exit.previous(picture);
            if(opening!=null)opening.close();
            opening=exit;
            finishExit=complete;
            invalidate();
        });
    }
    void cancelLeave(){
        if(opening!=null)opening.close();
        leaving=false;finishExit=null;opening=null;
        holdBackground(false);invalidate();
    }
    boolean skipTransition(){
        if(opening!=null&&opening.skip()){invalidate();return true;}
        return leaving;
    }
    Bitmap sprite(String name){return assets.image(name);}
    void canvas(int width,int height){
        int w=Math.max(1,width),h=Math.max(1,height);
        if(w==canvasWidth&&h==canvasHeight)return;
        canvasWidth=w;canvasHeight=h;requestLayout();
    }
    void sound(Sound value){sound=value;}
    void sound(int se,int voice){if(sound!=null)sound.play(se,voice);else click.run();}
    void uiActive(boolean active){if(backdrop!=null)backdrop.active(active);}
    void discardBackground(){if(backdrop!=null)backdrop.close();}
    void uiBackground(GameUiAssets.Config cfg,int mode,int page){
        uiBackground(cfg,mode,page,null,0,0);
    }
    void uiBackground(GameUiAssets.Config cfg,int mode,int page,int[] saved,int width,int height){
        uiBackground(cfg,mode,page,null,saved,width,height);
    }
    void uiBackground(GameUiAssets.Config cfg,int mode,int page,UiPreviewPool.Warmup warmup){
        uiBackground(cfg,mode,page,warmup,null,0,0);
    }
    void chartBackground(GameUiAssets.Config cfg,int mode,int page,int centerLayer,int scriptTiles,Consumer<ChartBackdrop.Frame> display){
        printMode=mode;
        boolean supported=ChartBackdrop.supportsCanvas(canvasWidth,canvasHeight);
        UiPreviewPool.Request request=UiPreviewPool.Request.read(cfg,mode,page);
        if(supported&&backdrop!=null&&backdrop.splitChart()&&request!=null&&request.sameScript(backdropRequest)){
            backdrop.page(page,scriptTiles);return;
        }
        if(backdrop!=null){removeView(backdrop);backdrop.close();backdrop=null;backdropRequest=null;}
        if(!supported){
            scriptedBackground=false;
            display.accept(null);
            return;
        }
        UiPreviewPool pool=request==null||assets.runtime==null?null:assets.runtime.previews();
        if(pool==null)return;
        backdropRequest=request;backdropHeld=true;
        ChartBackdrop chart=new ChartBackdrop(canvasWidth,canvasHeight,centerLayer,scriptTiles,display);
        backdrop=new GameUiBackground(getContext(),pool,request,null,frameMs,UiPreviewPool.stepLimit(assets.game),null,0,0,chart);
        addView(backdrop,0,new LayoutParams(-1,-1));
    }
    private void uiBackground(GameUiAssets.Config cfg,int mode,int page,UiPreviewPool.Warmup warmup,int[] saved,int width,int height){
        printMode=mode;
        scriptedBackground=cfg.number("backScriptFlag",0)!=0;
        UiPreviewPool.Request request=UiPreviewPool.Request.read(cfg,mode,page);
        if(saved==null&&backdrop!=null&&request!=null&&request.sameScript(backdropRequest)){backdrop.page(page);return;}
        if(backdrop!=null){removeView(backdrop);backdrop.close();backdrop=null;backdropRequest=null;}
        UiPreviewPool pool=request==null||assets.runtime==null?null:assets.runtime.previews();
        if(pool==null)return;
        backdropRequest=request;
        backdrop=new GameUiBackground(getContext(),pool,request,warmup,frameMs,UiPreviewPool.stepLimit(assets.game),saved,width,height);
        backdropHeld=true;
        addView(backdrop,0,new LayoutParams(-1,-1));
    }
    void previewPlace(int place){if(backdrop!=null)backdrop.page(place);}
    private void holdBackground(boolean hold){
        if(backdrop!=null&&backdropHeld!=hold){backdropHeld=hold;backdrop.hold(hold);}
    }
    void clearControls(){
        for(int i=getChildCount()-1;i>=0;i--)if(getChildAt(i)!=backdrop)removeViewAt(i);
        positions.clear();pendingButton=null;
    }
    /** A modal that consumes Back handles its own button feedback. */
    boolean backOverlay(){return false;}
    boolean isAdjusting(){return false;}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        drawBackground(canvas);
    }
    private void drawBackground(Canvas canvas){
        Bitmap image=capturedBackground!=null?capturedBackground:scriptedBackground?null:sprite(backgroundName);
        if(image!=null)canvas.drawBitmap(image,null,new Rect(0,0,getWidth(),getHeight()),paint);
        else if(!transparentBackground)canvas.drawColor(scriptedBackground?Color.BLACK:backgroundName==null?0xc4000611:0xff000813);
        drawPanelBackground(canvas);
    }
    void drawPanelBackground(Canvas canvas){}
    @Override public boolean dispatchTouchEvent(MotionEvent event){
        if(waitingForBackground())return true;
        if(opening!=null&&opening.touch(event)){invalidate();return true;}
        if(leaving)return true;
        if(backdrop!=null){
            int action=event.getActionMasked();
            boolean down=action==MotionEvent.ACTION_DOWN;
            boolean held=down||action==MotionEvent.ACTION_MOVE;
            backdrop.pointer((int)(event.getX()*canvasWidth/Math.max(1,getWidth())),
                (int)(event.getY()*canvasHeight/Math.max(1,getHeight())),held,down);
        }
        return super.dispatchTouchEvent(event);
    }
    private boolean waitingForBackground(){
        return opening!=null&&opening.needsTarget()||backdrop!=null&&!backdrop.frameReady();
    }
    @Override protected void onLayout(boolean changed,int left,int top,int right,int bottom){
        super.onLayout(changed,left,top,right,bottom);
        backgroundReady();
    }
    void backgroundReady(){
        if(capturedBackdropReady!=null&&!awaitingCapturedBackdrop()){
            Runnable ready=capturedBackdropReady;capturedBackdropReady=null;ready.run();
        }
        if(opening!=null&&opening.needsTarget()&&!copyingTarget&&getWidth()>0&&getHeight()>0&&
            (backdrop==null||backdrop.frameReady())){
            UiOpening entering=opening;
            if(entering.delaysControls()&&!entering.needsWipeTarget()){
                entering.target(null);
                invalidate();
                return;
            }
            copyingTarget=true;
            capture(entering.delaysControls(),picture->{
                copyingTarget=false;
                if(opening==entering)entering.target(picture);
                invalidate();
            });
        }
        invalidate();
    }
    void onBackdropFailure(Consumer<Throwable> listener){backdropFailure=listener;}
    void backgroundFailed(GameUiBackground source,Throwable failure){
        if(source!=backdrop)return;
        backdrop=null;backdropRequest=null;scriptedBackground=false;
        removeView(source);
        if(backdropFailure!=null)backdropFailure.accept(failure);
        backgroundReady();
    }
    @Override public void draw(Canvas canvas){
        if(awaitingCapturedBackdrop()){
            // Latch the script's first buffer while leaving the outgoing scene visible.
            // The filtered screenshot belongs to the script's composition.
            super.drawChild(canvas,backdrop,getDrawingTime());
            if(awaitingCapturedBackdrop())return;
        }
        if(waitingForBackground()){
            super.draw(canvas);
            if(opening!=null)opening.drawPrevious(canvas,this);
            return;
        }
        if(opening==null){if(!leaving)holdBackground(false);super.draw(canvas);return;}
        opening.begin();
        boolean fading=opening.fading();
        if(!leaving&&!fading)holdBackground(false);
        if(!opening.drawTarget(canvas,this))super.draw(canvas);
        opening.overlay(canvas,this,canvasWidth,canvasHeight);
        if(fading||opening.running())postInvalidateOnAnimation();
        else if(finishExit!=null){post(finishExit);finishExit=null;}
    }
    @Override protected void onDetachedFromWindow(){
        capturedBackdropReady=null;
        if(opening!=null)opening.close();
        super.onDetachedFromWindow();
    }
    @Override protected boolean drawChild(Canvas canvas,View child,long drawingTime){
        if(opening==null||child==backdrop)return super.drawChild(canvas,child,drawingTime);
        int offset=backdrop==null?0:1,save=canvas.save();
        boolean drawn=opening.reveal(canvas,child,indexOfChild(child)-offset,getChildCount()-offset,
            getWidth()/(float)canvasWidth,getHeight()/(float)canvasHeight)&&super.drawChild(canvas,child,drawingTime);
        canvas.restoreToCount(save);return drawn;
    }
    void put(View view,int x,int y,int width,int height){
        positions.add(new Placement(view,x,y,width,height));
        addView(view,new FrameLayout.LayoutParams(1,1,Gravity.TOP|Gravity.LEFT));
    }
    @Override public void removeAllViews(){super.removeAllViews();positions.clear();}
    @Override protected void onMeasure(int wSpec,int hSpec){
        float sx=MeasureSpec.getSize(wSpec)/(float)canvasWidth,sy=MeasureSpec.getSize(hSpec)/(float)canvasHeight;
        for(Placement p:positions){
            FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)p.view.getLayoutParams();
            lp.width=Math.max(1,Math.round(p.w*sx));lp.height=Math.max(1,Math.round(p.h*sy));
            lp.leftMargin=Math.round(p.x*sx);lp.topMargin=Math.round(p.y*sy);
            if(p.font>0)((TextView)p.view).setTextSize(TypedValue.COMPLEX_UNIT_PX,p.font*Math.min(sx,sy));
        }
        super.onMeasure(wSpec,hSpec);
    }
    TextView text(String label,int fontPx,int color){
        TextView v=new TextView(getContext());v.setText(label);v.setTextColor(color);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX,fontPx);v.setGravity(Gravity.CENTER_VERTICAL);
        v.setIncludeFontPadding(false);return v;
    }
    void picture(String name,int x,int y,int w,int h){
        ImageView view=new ImageView(getContext());view.setImageBitmap(sprite(name));view.setScaleType(ImageView.ScaleType.FIT_XY);put(view,x,y,w,h);
    }
    GameButton button(GameUiAssets.Config cfg,String tag,String fallbackTag,String label,Runnable action,int dx,int dy,int dw,int dh){
        return buttonAt(cfg,tag,fallbackTag,label,action,0,0,dx,dy,dw,dh);
    }
    void pageArrows(GameUiAssets.Config cfg,int page,int pageCount,int x,int upY,int downY,IntConsumer selectPage){
        if(pageCount<=1)return;
        if(page>0)button(cfg,"Up","Up","↑",()->selectPage.accept(page-1),x,upY,48,48)
            .setContentDescription(hostText(R.string.engine_systemnnn_ctl_page_previous));
        if(page<pageCount-1)button(cfg,"Down","Down","↓",()->selectPage.accept(page+1),x,downY,48,48)
            .setContentDescription(hostText(R.string.engine_systemnnn_ctl_page_next));
    }
    GameButton buttonAt(GameUiAssets.Config cfg,String tag,String fallbackTag,String label,Runnable action,int baseX,int baseY,int dx,int dy,int dw,int dh){
        int w=cfg.number(tag+"SizeX",cfg.number(fallbackTag+"SizeX",dw));
        int h=cfg.number(tag+"SizeY",cfg.number(fallbackTag+"SizeY",dh));
        w=Math.max(1,Math.min(4096,w));h=Math.max(1,Math.min(4096,h));
        int x=baseX+cfg.number(tag+"PrintX",dx),y=baseY+cfg.number(tag+"PrintY",dy);
        GameButton.Spec spec=new GameButton.Spec(cfg,tag,fallbackTag,w,h);
        return animatedButton(spec,sprite(spec.image),label,()->sound(spec.sound,spec.voice),action,x,y,w,h);
    }
    GameButton animatedButton(GameButton.Spec spec,Bitmap image,String label,Runnable cue,Runnable action,int x,int y,int w,int h){
        GameButton b=new GameButton(getContext(),spec,image,label,frameMs);
        b.setOnClickListener(v->{
            if(pendingButton!=null||leaving)return;
            if(b.commit(cue,()->{pendingButton=null;action.run();}))pendingButton=b;
        });
        put(b,x,y,w,h);
        return b;
    }
    void chip(String label,boolean selected,Runnable action,int x,int y,int w,int h){
        TextView b=text(label,24,selected?0xffffca50:0xffa4e7ff);b.setGravity(Gravity.CENTER);
        b.setContentDescription(label);b.setSelected(selected);b.setSoundEffectsEnabled(false);
        b.setBackground(new android.graphics.drawable.Drawable(){
            final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
            public void draw(Canvas c){
                Rect bounds=getBounds();Bitmap template=assets.image("ta_cnf_bg01");
                if(template!=null&&template.getWidth()>=541&&template.getHeight()>=438)
                    c.drawBitmap(template,new Rect(303,392,541,438),bounds,p);
                else {p.setColor(0xff07253b);c.drawRect(bounds,p);}
                p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(selected?3:1);p.setColor(selected?0xffffb735:0xff12658a);
                c.drawRect(bounds.left+2,bounds.top+2,bounds.right-2,bounds.bottom-2,p);p.setStyle(Paint.Style.FILL);
            }
            public void setAlpha(int a){}public void setColorFilter(ColorFilter f){}public int getOpacity(){return PixelFormat.TRANSLUCENT;}
        });
        b.setOnClickListener(v->{click.run();action.run();});put(b,x,y,w,h);
    }
    View back(GameUiAssets.Config cfg,Runnable close){
        return button(cfg,"Back","Back",hostText(R.string.engine_systemnnn_host_back),close,1035,655,220,54);
    }
}
