package com.tyranor.runtime.systemnnn;

import android.content.Context;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.view.accessibility.*;
import java.util.function.IntConsumer;

/** Touch and accessibility input over the game's original slider atlas. */
final class ValueBar extends View {
    final Bitmap image;final String label;final int min,max,logicalWidth,logicalHeight,padding;final IntConsumer change;final Runnable click;
    final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);int value;boolean tracking;
    private IntConsumer commit;
    ValueBar(Context context,Bitmap image,String label,int min,int max,int value,int w,int h,int padding,Runnable click,IntConsumer change){super(context);this.image=image;this.padding=padding;this.click=click;this.label=label;this.min=min;this.max=Math.max(min,max);this.value=Math.max(min,Math.min(value,this.max));logicalWidth=w;logicalHeight=h;this.change=change;
        setFocusable(true);setClickable(true);setSoundEffectsEnabled(false);describe();}
    void describe(){setContentDescription(label+" "+value+" / "+max);}
    void update(int next){next=Math.max(min,Math.min(next,max));if(next!=value){value=next;describe();invalidate();change.accept(value);sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED);}}
    void touch(float x){update(min+Math.round(Math.max(0,Math.min(1,x/Math.max(1,getWidth())))*(max-min)));}
    void onCommit(IntConsumer action){commit=action;}
    private void clickCue(){if(click!=null)click.run();}
    private void committed(){if(commit!=null)commit.accept(value);}
    @Override protected void onDraw(Canvas c){
        float sy=getHeight()/(float)(logicalHeight+padding),top=padding*.5f*sy,bottom=top+logicalHeight*sy;
        float fraction=max==min?1:(value-min)/(float)(max-min);int extent=Math.round(logicalWidth*fraction);p.setAlpha(isEnabled()?255:110);
        if(image!=null&&extent>0){
            int width=Math.min(extent,image.getWidth()),height=Math.min(logicalHeight,image.getHeight());
            c.drawBitmap(image,new Rect(0,0,width,height),new RectF(0,top,getWidth()*width/(float)logicalWidth,top+height*sy),p);
        }
        else if(image==null){p.setColor(0xff365776);c.drawRect(0,top,getWidth(),bottom,p);p.setColor(0xff83d8ff);c.drawRect(0,top,getWidth()*fraction,bottom,p);}
    }
    @Override public boolean onTouchEvent(MotionEvent e){if(!isEnabled())return false;
        switch(e.getActionMasked()){
        case MotionEvent.ACTION_DOWN:tracking=true;setPressed(true);requestFocus();if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);clickCue();touch(e.getX());return true;
        case MotionEvent.ACTION_MOVE:touch(e.getX());return true;
        case MotionEvent.ACTION_UP:touch(e.getX());performClick();tracking=false;setPressed(false);if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);committed();return true;
        case MotionEvent.ACTION_CANCEL:tracking=false;setPressed(false);if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);return true;default:return true;}
    }
    @Override public boolean performClick(){if(!tracking){clickCue();committed();}super.performClick();return true;}
    @Override public boolean onKeyDown(int code,KeyEvent e){if(code==KeyEvent.KEYCODE_DPAD_LEFT||code==KeyEvent.KEYCODE_DPAD_RIGHT){clickCue();update(value+(code==KeyEvent.KEYCODE_DPAD_RIGHT?1:-1));committed();return true;}return super.onKeyDown(code,e);}
    @Override public CharSequence getAccessibilityClassName(){return "android.widget.SeekBar";}
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setClassName(getAccessibilityClassName());info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,min,max,value));
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);}
    @Override public boolean performAccessibilityAction(int action,Bundle args){if(!isEnabled())return false;
        if(action==AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()&&args!=null&&args.containsKey(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)){update(Math.round(args.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)));committed();return true;}
        if(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD||action==AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD){update(value+(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD?1:-1));committed();return true;}return super.performAccessibilityAction(action,args);}
}
