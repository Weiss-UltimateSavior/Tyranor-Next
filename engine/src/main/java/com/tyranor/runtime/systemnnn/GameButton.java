package com.tyranor.runtime.systemnnn;

import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import static com.tyranor.runtime.systemnnn.ButtonArt.number;

/** CCommonButton states in the original atlas, with the original click wait. */
final class GameButton extends View {
    static final class Spec {
        final SpriteAnimation[] states;
        final String image;
        final int clickTime,sound,voice;
        final int hitX,hitY;
        final SpriteAnimation hit;
        final boolean pixelHit;
        Spec(GameUiAssets.Config cfg,String key,String fallback,int w,int h){
            ButtonArt art=new ButtonArt(cfg,key,fallback);
            image=art.image;states=SpriteAnimation.buttonStates(art,w,h);
            hitX=number(cfg,key,fallback,"CheckDeltaX",0);hitY=number(cfg,key,fallback,"CheckDeltaY",0);
            pixelHit=number(cfg,key,fallback,"PrintMode",2)!=0;
            hit=new SpriteAnimation(number(cfg,key,fallback,"CheckPicX",0),number(cfg,key,fallback,"CheckPicY",0),
                number(cfg,key,fallback,"CheckSizeX",w),number(cfg,key,fallback,"CheckSizeY",h),1,1,1,false);
            clickTime=Math.max(0,Math.min(1000,number(cfg,key,fallback,"ClickTime",1)));
            sound=number(cfg,key,fallback,"ClickSound",0)-1;voice=number(cfg,key,fallback,"ClickVoice",0)-1;
        }
        Spec(ButtonArt.Setup setup){
            image=setup.art.image;states=SpriteAnimation.buttonStates(setup.art,setup.width,setup.height);
            hitX=setup.hitX;hitY=setup.hitY;pixelHit=setup.pixelHit;
            hit=new SpriteAnimation(setup.hitPicX,setup.hitPicY,setup.hitWidth,setup.hitHeight,1,1,1,false);
            clickTime=setup.clickTime;sound=setup.sound;voice=setup.voice;
        }
        Spec(String image,SpriteAnimation normal,SpriteAnimation hover,SpriteAnimation pressed,int frames){
            this.image=image;states=new SpriteAnimation[]{normal,hover,pressed,null};
            hit=normal;hitX=hitY=0;pixelHit=true;clickTime=frames;sound=voice=-1;
        }
    }
    final Spec spec;
    private final Bitmap image;
    private final int period;
    private final String label;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final RectF dest=new RectF();
    private int state=-1;
    private long stateSince;
    private Runnable pending;
    private Runnable prepare;

    GameButton(Context context,Spec spec,Bitmap image,String label,int frameMs){
        super(context);this.spec=spec;this.image=image;this.label=label;period=Math.max(1,frameMs);
        setContentDescription(label);setSoundEffectsEnabled(false);setFocusable(true);
    }
    void prepare(Runnable action){prepare=action;}
    boolean commit(Runnable cue,Runnable action){
        if(pending!=null||!isEnabled())return false;
        pending=()->{pending=null;invalidate();action.run();};
        cue.run();invalidate();postDelayed(pending,spec.clickTime*(long)period);
        if(prepare!=null)prepare.run();
        return true;
    }
    @Override protected void onDetachedFromWindow(){if(pending!=null)removeCallbacks(pending);pending=null;super.onDetachedFromWindow();}
    @Override public CharSequence getAccessibilityClassName(){return "android.widget.Button";}
    @Override protected void drawableStateChanged(){super.drawableStateChanged();invalidate();}
    boolean pictureHit(float localX,float localY){
        float x=localX*spec.states[0].w/Math.max(1,getWidth())-spec.hitX;
        float y=localY*spec.states[0].h/Math.max(1,getHeight())-spec.hitY;
        return x>=0&&y>=0&&x<spec.hit.w&&y<spec.hit.h&&
            (!spec.pixelHit||spec.hit.hit(image,x/spec.hit.w,y/spec.hit.h));
    }
    @Override public boolean onTouchEvent(MotionEvent event){
        if(pending!=null)return true;
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN&&!pictureHit(event.getX(),event.getY()))return false;
        return super.onTouchEvent(event);
    }
    @Override protected void onDraw(Canvas c){
        int mode=!isEnabled()?3:pending!=null?2:isPressed()||isHovered()?1:0;
        long now=SystemClock.uptimeMillis();
        if(mode!=state){state=mode;stateSince=now;}
        long elapsed=(now-stateSince)/period;
        SpriteAnimation animation=spec.states[mode];
        dest.set(0,0,getWidth(),getHeight());
        boolean drawn=animation!=null&&animation.draw(c,image,dest,paint,elapsed,255);
        if(!drawn)drawn=spec.states[0].draw(c,image,dest,paint,0,isEnabled()?255:100);
        if(!drawn){
            paint.setColor(mode==2?0xff9a5613:0xff07253b);c.drawRect(dest,paint);
            paint.setColor(isEnabled()?0xffffbc42:0xff8d9da8);paint.setTextSize(getHeight()*.4f);paint.setTextAlign(Paint.Align.CENTER);
            c.drawText(label,getWidth()/2f,getHeight()*.64f,paint);
        }
        if(animation!=null&&animation.active(elapsed))postInvalidateOnAnimation();
    }
}
