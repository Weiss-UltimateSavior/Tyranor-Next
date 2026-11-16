package com.tyranor.runtime.systemnnn;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import java.util.Arrays;

/** Frame ordering and interpolation shared by the original button and cursor atlases. */
final class SpriteAnimation {
    private static final PorterDuffXfermode ADD=new PorterDuffXfermode(PorterDuff.Mode.ADD);
    final int x,y,w,h,count,speed;
    final boolean horizontal,pingPong,limited,blend;
    private final Rect first=new Rect(),second=new Rect();
    private final RectF clippedDest=new RectF();
    private Bitmap cachedImage;
    private int cachedGeneration;
    private Bitmap[] frames;
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
            for(int j=0;j<i;j++){
                ButtonArt.State earlier=art.states[j];
                if(state.x==earlier.x&&state.y==earlier.y&&state.type==earlier.type&&
                        state.frames==earlier.frames&&state.speed==earlier.speed){
                    result[i]=result[j];
                    break;
                }
            }
            if(result[i]==null)result[i]=new SpriteAnimation(state.x,state.y,width,height,state.type,state.frames,state.speed,art.horizontal);
        }
        return result;
    }
    private int cycle(){return pingPong?Math.max(1,2*(count-1)):count;}
    boolean active(long frame){return count>1&&(!limited||frame<(long)cycle()*speed);}
    private int pattern(int n){return count==1?0:pingPong&&n>=count?2*(count-1)-n:n;}
    private boolean rect(Bitmap image,int index,Rect out){
        if(image==null||index<0||x<0||y<0||w<=0||h<=0||x>=image.getWidth()||y>=image.getHeight())return false;
        long left=x,top=y;
        if(horizontal){
            int firstRow=Math.max(1,(image.getWidth()-x)/w);
            if(index<firstRow)left+=index*w;
            else{int columns=Math.max(1,image.getWidth()/w),extra=index-firstRow;
                top+=(long)h*(1+extra/columns);left=(long)w*(extra%columns);}
        }else{
            int firstColumn=Math.max(1,(image.getHeight()-y)/h);
            if(index<firstColumn)top+=index*h;
            else{int rows=Math.max(1,image.getHeight()/h),extra=index-firstColumn;
                left+=(long)w*(1+extra/rows);top=(long)h*(extra%rows);}
        }
        if(left>=image.getWidth()||top>=image.getHeight())return false;
        out.set((int)left,(int)top,(int)Math.min(left+w,image.getWidth()),(int)Math.min(top+h,image.getHeight()));return true;
    }
    boolean hit(Bitmap image,float rx,float ry){
        if(!rect(image,0,first))return false;
        int px=first.left+Math.max(0,Math.min(w-1,(int)(rx*w)));
        int py=first.top+Math.max(0,Math.min(h-1,(int)(ry*h)));
        // Original DWQ hit masks treat even alpha 1 as an active pixel.
        return first.contains(px,py)&&(image.getPixel(px,py)>>>24)!=0;
    }
    boolean hasHitPixels(Bitmap image,int left,int top,int right,int bottom){
        if(!rect(image,0,first))return false;
        int x=(int)Math.min(first.right,first.left+(long)left);
        int y=(int)Math.min(first.bottom,first.top+(long)top);
        int width=(int)Math.min(first.right,first.left+(long)right)-x;
        int endY=(int)Math.min(first.bottom,first.top+(long)bottom);
        if(width<=0||y>=endY)return false;
        if(!image.hasAlpha())return true;
        int[] row=new int[width];
        for(;y<endY;y++){
            image.getPixels(row,0,width,x,y,width,1);
            for(int pixel:row)if((pixel>>>24)!=0)return true;
        }
        return false;
    }
    private void blit(Canvas c,Bitmap image,int index,Rect source,RectF dest,Paint paint){
        Bitmap frame=image;
        // Filtering a source rectangle can read neighboring atlas pixels.
        if(source.width()!=image.getWidth()||source.height()!=image.getHeight()){
            if(frames==null)frames=new Bitmap[count];
            frame=frames[index];
            if(frame==null){
                frame=Bitmap.createBitmap(image,source.left,source.top,source.width(),source.height());
                frames[index]=frame;
            }
        }
        // CPicture clips to the available pixels without changing the button's scale.
        clippedDest.set(dest.left,dest.top,dest.left+dest.width()*source.width()/w,dest.top+dest.height()*source.height()/h);
        c.drawBitmap(frame,null,clippedDest,paint);
    }
    boolean draw(Canvas c,Bitmap image,RectF dest,Paint paint,long elapsed,int alpha){
        int generation=image==null?0:image.getGenerationId();
        if(image!=cachedImage||generation!=cachedGeneration){
            cachedImage=image;
            cachedGeneration=generation;
            if(frames!=null)Arrays.fill(frames,null);
        }
        int cycle=cycle();
        long time=limited?Math.min(elapsed,(long)cycle*speed):elapsed%(cycle*(long)speed);
        int last=pingPong?cycle:cycle-1;
        int n=(int)(time/speed),a=pattern(Math.min(last,n)),b=pattern(limited?Math.min(last,n+1):(n+1)%cycle);
        int firstIndex=a;
        if(!rect(image,firstIndex,first)){
            firstIndex=0;
            if(!rect(image,firstIndex,first))return false;
        }
        int mix=blend?(int)(alpha*(time%speed)/speed):0;
        if(mix==0||a==b||!rect(image,b,second)){
            paint.setAlpha(alpha);blit(c,image,firstIndex,first,dest,paint);
        }else{
            int layer=c.saveLayer(dest,null);
            paint.setAlpha(alpha-mix);blit(c,image,firstIndex,first,dest,paint);
            paint.setAlpha(mix);paint.setXfermode(ADD);blit(c,image,b,second,dest,paint);paint.setXfermode(null);
            c.restoreToCount(layer);
        }
        paint.setAlpha(255);return true;
    }
}
