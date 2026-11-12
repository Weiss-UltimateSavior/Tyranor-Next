package com.tyranor.runtime.systemnnn;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;

/** Frame ordering and interpolation shared by the original button and cursor atlases. */
final class SpriteAnimation {
    private static final PorterDuffXfermode ADD=new PorterDuffXfermode(PorterDuff.Mode.ADD);
    final int x,y,w,h,count,speed;
    final boolean horizontal,pingPong,limited,blend;
    private final Rect first=new Rect(),second=new Rect();
    private final RectF clippedDest=new RectF();
    SpriteAnimation(int x,int y,int w,int h,int type,int count,int speed,boolean horizontal){
        this.x=x;this.y=y;this.w=w;this.h=h;this.horizontal=horizontal;
        this.count=Math.max(1,Math.min(256,count));this.speed=Math.max(1,Math.min(1000,speed));
        pingPong=type==3||type==4||type==7||type==8;
        limited=type==2||type==4||type==6||type==8;blend=type>=5;
    }
    static SpriteAnimation[] buttonStates(ButtonArt art,int width,int height){
        SpriteAnimation[] result=new SpriteAnimation[art.states.length];
        for(int i=0;i<result.length;i++){
            ButtonArt.State state=art.states[i];
            result[i]=new SpriteAnimation(state.x,state.y,width,height,state.type,state.frames,state.speed,art.horizontal);
        }
        return result;
    }
    private int cycle(){return pingPong?Math.max(1,2*(count-1)):count;}
    boolean active(long frame){return count>1&&(!limited||frame<(long)cycle()*speed);}
    private int pattern(int n){return count==1?0:pingPong&&n>=count?2*(count-1)-n:n;}
    private boolean rect(Bitmap image,int index,Rect out){
        if(image==null||x<0||y<0||w<=0||h<=0||x>=image.getWidth()||y>=image.getHeight())return false;
        int left=x,top=y;
        if(horizontal){
            int firstRow=Math.max(1,(image.getWidth()-x)/w);
            if(index<firstRow)left+=index*w;
            else{int columns=Math.max(1,image.getWidth()/w),extra=index-firstRow;
                top+=h*(1+extra/columns);left=w*(extra%columns);}
        }else{
            int firstColumn=Math.max(1,(image.getHeight()-y)/h);
            if(index<firstColumn)top+=index*h;
            else{int rows=Math.max(1,image.getHeight()/h),extra=index-firstColumn;
                left+=w*(1+extra/rows);top=h*(extra%rows);}
        }
        if(left<0||top<0||left>=image.getWidth()||top>=image.getHeight())return false;
        out.set(left,top,Math.min(left+w,image.getWidth()),Math.min(top+h,image.getHeight()));return true;
    }
    boolean hit(Bitmap image,float rx,float ry){
        if(!rect(image,0,first))return true;
        int px=first.left+Math.max(0,Math.min(w-1,(int)(rx*w)));
        int py=first.top+Math.max(0,Math.min(h-1,(int)(ry*h)));
        // Original DWQ hit masks treat even alpha 1 as an active pixel.
        return first.contains(px,py)&&(image.getPixel(px,py)>>>24)!=0;
    }
    private void blit(Canvas c,Bitmap image,Rect source,RectF dest,Paint paint){
        // CPicture clips to the available pixels without changing the button's scale.
        clippedDest.set(dest.left,dest.top,dest.left+dest.width()*source.width()/w,dest.top+dest.height()*source.height()/h);
        c.drawBitmap(image,source,clippedDest,paint);
    }
    boolean draw(Canvas c,Bitmap image,RectF dest,Paint paint,long elapsed,int alpha){
        int cycle=cycle();
        long time=limited?Math.min(elapsed,(long)cycle*speed):elapsed%(cycle*(long)speed);
        int last=pingPong?cycle:cycle-1;
        int n=(int)(time/speed),a=pattern(Math.min(last,n)),b=pattern(limited?Math.min(last,n+1):(n+1)%cycle);
        if(!rect(image,a,first)&&!rect(image,0,first))return false;
        int mix=blend?(int)(alpha*(time%speed)/speed):0;
        if(mix==0||a==b||!rect(image,b,second)){
            paint.setAlpha(alpha);blit(c,image,first,dest,paint);
        }else{
            int layer=c.saveLayer(dest,null);
            paint.setAlpha(alpha-mix);blit(c,image,first,dest,paint);
            paint.setAlpha(mix);paint.setXfermode(ADD);blit(c,image,second,dest,paint);paint.setXfermode(null);
            c.restoreToCount(layer);
        }
        paint.setAlpha(255);return true;
    }
}
