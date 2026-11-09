package com.tyranor.runtime.systemnnn;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.*;
import java.util.function.Consumer;

/** Scale the game's clickable rectangles with the same letterbox as the background. */
final class TitlePanel extends FrameLayout {
    interface Click { void select(TitleMenu.Item item); }
    private final TitleMenu model;
    private final int baseWidth,baseHeight;
    private final GameRunner runner;
    private boolean pending,touching,backgroundStarted,shown;
    private final UiOpening opening;
    private boolean restarting;
    private UiOpening restartExit;
    private Runnable finishRestart;
    private Bitmap initial;

    TitlePanel(Context context,String name,TitleMenu title,int width,int height,GameRunner runner,Bitmap initial,
               Click click,Click prepare,GameUiPanel.Sound sound) {
        super(context); model=title; baseWidth=width; baseHeight=height;
        this.runner=runner;this.initial=initial;
        opening=new UiOpening(title.config,title.frameMs,false);
        setWillNotDraw(false);
        setClickable(true);
        LinearLayout fallback=null;
        if (!title.skinned || title.items.isEmpty()) {
            fallback=new LinearLayout(context); fallback.setOrientation(LinearLayout.VERTICAL);
            fallback.setGravity(Gravity.CENTER); fallback.setBackgroundColor(0xcb151923);
            TextView label=new TextView(context); label.setText(name); label.setGravity(Gravity.CENTER);
            label.setTextSize(22); label.setTextColor(Color.WHITE); fallback.addView(label);
            addView(fallback,new LayoutParams(-1,-1));
        }
        for (TitleMenu.Item item:title.items) {
            View button;
            if (item.spec!=null && fallback==null) {
                GameButton game=new GameButton(context,item.spec,item.image,item.label,title.frameMs);
                game.prepare(()->prepare.select(item));
                game.setOnClickListener(v->{
                    if(!pending&&!restarting&&game.commit(()->sound.play(item.spec.sound,item.spec.voice),
                        ()->finishClick(click,item)))pending(true);
                });
                button=game;
            } else {
                Button text=new Button(context); text.setText(item.label); button=text;
                button.setOnClickListener(v->{
                    if(pending||restarting||!item.enabled)return;
                    pending(true);
                    finishClick(click,item);
                });
            }
            button.setContentDescription(item.label); button.setEnabled(item.enabled);
            button.setSoundEffectsEnabled(false);
            if (fallback!=null) fallback.addView(button,new LinearLayout.LayoutParams(-2,-2));
            else {button.setTag(item); addView(button,new LayoutParams(1,1,Gravity.TOP|Gravity.LEFT));}
        }
    }
    private void finishClick(Click click,TitleMenu.Item item){
        // Story entry completes when the first story frame replaces the title.
        click.select(item);
        if(item.action!=TitleMenu.Action.START&&item.action!=TitleMenu.Action.CONTINUE&&
            item.action!=TitleMenu.Action.LAST_LOAD)pending(false);
    }
    private void pending(boolean busy){
        pending=busy;
        runner.titleInteraction(model,pending||touching);
    }
    void entryFailed(){pending(false);}
    boolean leaveForRestart(GameRunner.Capture background,Consumer<Bitmap> done){
        if(pending||touching||restarting)return false;
        restarting=true;
        Bitmap picture=null;
        if(background!=null){
            try{
                picture=background.bitmap();
                Bitmap composed=UiOpening.snapshot(this,picture,c->super.draw(c));
                if(composed!=picture)picture.recycle();
                picture=composed;
            }catch(RuntimeException|OutOfMemoryError e){
                android.util.Log.w("SystemNNN","Unable to compose outgoing title",e);
            }
        }
        if(picture==null){done.accept(null);return true;}
        restartExit=UiOpening.exit(model.config,model.frameMs);
        restartExit.previous(picture);
        if(restartExit.hasFade()){
            Bitmap outgoing=model.config.number("fillExitScreenFlag",1)!=0?
                UiOpening.solid(restartExit.color()):picture;
            finishRestart=()->done.accept(outgoing);
        }else done.accept(picture);
        invalidate();
        return true;
    }
    boolean skipRestartExit(){
        if(finishRestart==null)return false;
        restartExit.skip();
        invalidate();
        return true;
    }
    void previous(Bitmap picture){if(opening.needsPrevious())opening.previous(picture);}
    @Override public boolean dispatchTouchEvent(MotionEvent event){
        int action=event.getActionMasked();
        if(restarting){
            if(action==MotionEvent.ACTION_DOWN)skipRestartExit();
            return true;
        }
        if(action==MotionEvent.ACTION_DOWN){
            touching=true;
            runner.titleInteraction(model,true);
        }
        try{
            if(pending)return true;
            if(opening.touch(event)){invalidate();return true;}
            return super.dispatchTouchEvent(event);
        }finally{
            if(touching&&(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)){
                touching=false;
                runner.titleInteraction(model,pending);
            }
        }
    }
    @Override public void draw(Canvas canvas){
        if(restartExit!=null){
            if(restartExit.hasFade()){
                restartExit.begin();
                if(restartExit.overlay(canvas,this,baseWidth,baseHeight))postInvalidateOnAnimation();
                else if(finishRestart!=null){
                    post(finishRestart);
                    finishRestart=null;
                }
            }else restartExit.drawPrevious(canvas,this);
            return;
        }
        if(opening.needsTarget()){
            opening.target(opening.delaysControls()||initial==null?initial:
                UiOpening.snapshot(this,initial,c->super.draw(c)));
            initial=null;
        }
        opening.begin();
        if(!opening.fading()&&!backgroundStarted){
            backgroundStarted=true;runner.startTitleBackground();
        }
        if(!opening.drawTarget(canvas,this))super.draw(canvas);
        opening.overlay(canvas,this,baseWidth,baseHeight);
        if(!shown){shown=true;runner.titleShown();}
        if(!backgroundStarted||opening.running())postInvalidateOnAnimation();
    }
    @Override protected void onDetachedFromWindow(){
        if(pending||touching){touching=false;pending(false);}
        if(restartExit!=null)restartExit.close();
        finishRestart=null;
        opening.close();
        super.onDetachedFromWindow();
    }
    @Override protected boolean drawChild(Canvas canvas,View child,long drawingTime){
        int save=canvas.save();
        boolean drawn=opening.reveal(canvas,child,indexOfChild(child),getChildCount(),
            getWidth()/(float)baseWidth,getHeight()/(float)baseHeight)&&super.drawChild(canvas,child,drawingTime);
        canvas.restoreToCount(save);return drawn;
    }
    @Override protected void onMeasure(int wSpec,int hSpec) {
        if (model.skinned && !model.items.isEmpty()) {
            float sx=(float)MeasureSpec.getSize(wSpec)/baseWidth,sy=(float)MeasureSpec.getSize(hSpec)/baseHeight;
            for (int i=0;i<getChildCount();i++) {
                View view=getChildAt(i); TitleMenu.Item it=(TitleMenu.Item)view.getTag();
                LayoutParams p=(LayoutParams)view.getLayoutParams();
                p.width=Math.max(1,Math.round(it.width*sx)); p.height=Math.max(1,Math.round(it.height*sy));
                p.leftMargin=Math.round(it.x*sx); p.topMargin=Math.round(it.y*sy);
            }
        }
        super.onMeasure(wSpec,hSpec);
    }
}
