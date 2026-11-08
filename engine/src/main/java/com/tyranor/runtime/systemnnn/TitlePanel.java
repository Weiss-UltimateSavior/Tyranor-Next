package com.tyranor.runtime.systemnnn;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.*;

/** Scale the game's clickable rectangles with the same letterbox as the background. */
final class TitlePanel extends FrameLayout {
    interface Click { void select(TitleMenu.Item item); }
    private final TitleMenu model;
    private final int baseWidth,baseHeight;
    private final GameRunner runner;
    private boolean pending,backgroundStarted;
    private final UiOpening opening;
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
                    if(!pending&&game.commit(()->sound.play(item.spec.sound,item.spec.voice),
                        ()->finishClick(click,item)))pending=true;
                });
                button=game;
            } else {
                Button text=new Button(context); text.setText(item.label); button=text;
                button.setOnClickListener(v->{
                    if(pending||!item.enabled)return;
                    pending=true;
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
        if(item.action!=TitleMenu.Action.START&&item.action!=TitleMenu.Action.CONTINUE)pending=false;
        click.select(item);
    }
    void entryFailed(){pending=false;}
    void previous(Bitmap picture){if(opening.needsPrevious())opening.previous(picture);}
    @Override public boolean dispatchTouchEvent(MotionEvent event){
        if(pending)return true;
        if(opening.touch(event)){invalidate();return true;}
        return super.dispatchTouchEvent(event);
    }
    @Override public void draw(Canvas canvas){
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
        if(!backgroundStarted||opening.running())postInvalidateOnAnimation();
    }
    @Override protected void onDetachedFromWindow(){
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
