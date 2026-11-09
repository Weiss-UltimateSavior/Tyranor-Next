package com.tyranor.runtime.systemnnn;

import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;

/** Original selection window, hit rectangles and animated focus cursor. */
final class GameChoicePanel extends GameUiPanel {
    interface Select {void choose(int oneBased);}
    private final GameUiAssets.Config cfg;
    private final DialogueMarkup.Style markup;
    private final GameRunner.Frame frame;
    private final Select select;
    private final java.util.function.BooleanSupplier pressed;
    private final ArrayList<ChoiceRow> rows=new ArrayList<>();
    private final ArrayList<android.widget.TextView> messages=new ArrayList<>();
    private final int cursorW,cursorH,dx,dy,font,visible,appearType;
    private final Bitmap cursor;
    private final SpriteAnimation animation;
    private final boolean special,center;
    private final long appearAt,readyAt;
    private int page,focused=-1;
    private boolean changeColor;
    private Runnable confirmation;
    private ChoiceTimer timer;
    private boolean drawingOptions;
    private int remaining;

    GameChoicePanel(Context context,GameUiAssets assets,GameRunner.Frame frame,boolean changeColor,
                    java.util.function.BooleanSupplier pressed,Select select){
        super(context,assets,null,()->{});
        this.frame=frame;this.select=select;this.pressed=pressed;this.changeColor=changeColor;cfg=assets.selection;
        markup=new DialogueMarkup.Style(assets.game.fields());
        remaining=frame.choiceInfo[4];
        setWillNotDraw(true);setClickable(false);canvas(frame.width,frame.height);
        special=frame.choiceInfo[2]>0&&frame.choices.length<=cfg.number("useSpecialNumber",0);
        cursorW=bound(cfg.number(special?"specialCursorSizeX":"cursorSizeX",469),1,4096);
        cursorH=bound(cfg.number(special?"specialCursorSizeY":"cursorSizeY",27),1,4096);
        dx=cfg.number(special?"specialCursorDeltaX":"cursorDeltaX",-8);
        dy=cfg.number(special?"specialCursorDeltaY":"cursorDeltaY",-2);
        font=bound(cfg.number(special?"specialFontSize":"fontSize",24),8,128);
        center=cfg.number(special?"centeringFlag":"newCenteringFlag",0)!=0;
        boolean scroll=!special&&cfg.number("useScrollFlag",0)!=0;
        visible=scroll?Math.max(1,cfg.number("messagePrintGyo",4)-frame.choiceMessages.length):frame.choices.length;
        String art=cfg.value("cursorFileName","ta_select_cur01");
        cursor=sprite(special?cfg.value("filenameSpecialCursor",art):art);
        int patterns=cfg.number(special?"specialAnimePatternNumber":"animePatternNumber",1);
        int period=cfg.number(special?"specialAnimeFrame":"animeFrame",1);
        boolean blend=cfg.number(special?"specialAnimePrintType":"animePrintType",0)!=0;
        boolean reverse=cfg.number(special?"specialAnimeReverse":"animeReverse",0)!=0;
        animation=new SpriteAnimation(0,0,cursorW,cursorH,(blend?5:1)+(reverse?2:0),patterns,period,false);
        appearType=cfg.number("firstAppearType",0);
        appearAt=SystemClock.uptimeMillis();
        readyAt=appearAt+(appearType==0?0:bound(cfg.number("firstAppearTime",10),0,1000))*(long)frame.frameMs;
        render();
    }
    void changeColor(boolean value){
        if(value==changeColor)return;
        changeColor=value;
        for(int i=0;i<messages.size();i++)messages.get(i).setTextColor(textColor(i));
        for(ChoiceRow row:rows)row.invalidate();
    }
    void remaining(int value){remaining=value;if(timer!=null)timer.invalidate();}
    boolean isTimer(View view){return timer!=null&&view==timer;}
    void drawOptions(Canvas canvas){
        if(getVisibility()!=VISIBLE)return;
        drawingOptions=true;
        try{draw(canvas);}finally{drawingOptions=false;}
    }
    void drawTimer(Canvas canvas){
        if(getVisibility()==VISIBLE&&timer!=null&&timer.getVisibility()==VISIBLE)timer.draw(canvas);
    }
    @Override protected boolean drawChild(Canvas canvas,View child,long drawingTime){
        return (!drawingOptions||child!=timer)&&super.drawChild(canvas,child,drawingTime);
    }
    boolean confirming(){return confirmation!=null;}
    private boolean timeLimitActionAllowed(){return frame.choiceInfo[0]==0||cfg.number("timeLimitOptionOk",0)!=0;}
    boolean menuAllowed(){
        return timeLimitActionAllowed()&&cfg.number("optionOK",0)!=0&&cfg.number("disableQuickSystemMenuButton",0)==0;
    }
    boolean backlogAllowed(){
        return timeLimitActionAllowed()&&(cfg.number("optionOK",0)!=0||cfg.number("backlogOK",1)!=0);
    }
    boolean storyButtonsAllowed(){return frame.optionsAllowed&&menuAllowed();}
    boolean quickSlotAllowed(boolean saving){return cfg.number(saving?"disableQuickSaveButton":"disableQuickLoadButton",0)==0;}
    private int color(String prefix,int r,int g,int b){
        return Color.rgb(bound(cfg.number(prefix+"R",r),0,255),bound(cfg.number(prefix+"G",g),0,255),bound(cfg.number(prefix+"B",b),0,255));
    }
    private int textColor(int index){
        boolean visited=changeColor&&index<32&&(frame.choiceInfo[3]&(1<<index))!=0;
        return visited?color("selectedColor",255,0,0):color("messageColor",255,255,255);
    }
    private void render(){
        clearControls();rows.clear();messages.clear();
        setClipChildren(true);
        if(!special){
            String tag=frame.choiceInfo[0]>0?"timeLimitWindow":"window";
            if(cfg.number(tag+"PrintFlag",0)!=0){
                int x=cfg.number(tag+"PrintX",0),y=cfg.number(tag+"PrintY",0);
                picture(cfg.value(tag+"FileName","ta_select_win01"),x,y,
                    bound(cfg.number(tag+"SizeX",canvasWidth),1,4096),bound(cfg.number(tag+"SizeY",220),1,4096));
            }
        }
        int count=Math.max(1,Math.min(8,frame.choices.length));
        int x=cfg.number("messagePrintX",66),y=cfg.number("newMessagePrintY"+count,cfg.number("messagePrintY",383));
        int horizontal=cfg.number("messageNextX",0);
        int step=cfg.number("newMessageNextY"+count,cfg.number("messageNextY",30));
        for(int i=0;i<frame.choiceMessages.length;i++){
            DialogueMarkup caption=markup.parse(frame.choiceMessages[i]);
            int tone=textColor(i),inset=0;
            android.widget.TextView message;
            if(caption.annotations.isEmpty())message=text(caption.text,font,tone);
            else{
                DialogueTextView annotated=DialogueTextView.panel(getContext(),caption,markup,font,tone);
                annotated.markupTextColor(true);
                inset=annotated.markupTopInset(font);
                message=annotated;
            }
            int mx=x+i*horizontal;
            messages.add(message);
            put(message,mx,y+i*step-inset,Math.max(1,canvasWidth-mx),Math.max(font+4,step)+inset);
        }
        for(int i=0;i<visible&&page+i<frame.choices.length;i++){
            int rowIndex=i+frame.choiceMessages.length;
            int mx=x+rowIndex*horizontal,my=y+rowIndex*step;
            if(special){
                int wx=cfg.number("specialWindow"+frame.choices.length+"printX"+(i+1),0);
                int wy=cfg.number("specialWindow"+frame.choices.length+"printY"+(i+1),my);
                picture(cfg.value("filenameSpecialWindow",""),wx,wy,cfg.number("specialWindowSizeX",cursorW),cfg.number("specialWindowSizeY",cursorH));
                mx=wx+cfg.number("specialMessagePrintX",0);my=wy+cfg.number("specialMessagePrintY",0);
            }
            ChoiceRow row=new ChoiceRow(page+i);rows.add(row);put(row,mx+dx,my+dy,cursorW,cursorH);
        }
        int last=Math.max(0,frame.choices.length-visible);
        if(last>0){
            button(cfg,"Up","Up","上一项",()->move(-1,last),canvasWidth/2-24,y-66,48,48).setEnabled(page>0);
            button(cfg,"Down","Down","下一项",()->move(1,last),canvasWidth/2-24,y+(visible+frame.choiceMessages.length)*step+4,48,48).setEnabled(page<last);
        }
        if(frame.choiceInfo[0]>0&&(cfg.number("timerPrintType",1)>0||cfg.number("timerTimePrintFlag",0)>=2)){
            timer=new ChoiceTimer();
            put(timer,0,0,canvasWidth,canvasHeight);
        }
    }
    private void move(int direction,int last){if(!confirming()){page=bound(page+direction,0,last);highlight(-1);render();}}
    private void highlight(int index){if(!confirming()){focused=index;for(ChoiceRow row:rows)row.invalidate();}}
    private void choose(int index){
        if(confirming()||SystemClock.uptimeMillis()<readyAt||!pressed.getAsBoolean())return;
        highlight(index);sound(cfg.number("selectSound",0)-1,-1);
        confirmation=()->{confirmation=null;select.choose(index+1);};
        postDelayed(confirmation,bound(cfg.number("clickingTime",5),0,1000)*(long)frame.frameMs);
    }
    private final class ChoiceTimer extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        private final Bitmap bar=sprite(cfg.value("fileNameTimer",""));
        private final Bitmap digits=sprite(cfg.value("fileNameTimerTime",""));
        private final Bitmap fractional=sprite(cfg.value("fileNameTimerTime2",cfg.value("fileNameTimerTime","")));
        private final Rect src=new Rect(),dst=new Rect();
        ChoiceTimer(){super(GameChoicePanel.this.getContext());setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onDraw(Canvas canvas){
            canvas.save();canvas.scale(getWidth()/(float)canvasWidth,getHeight()/(float)canvasHeight);
            int type=cfg.number("timerPrintType",1),x=cfg.number("timerPrintX",0),y=cfg.number("timerPrintY",0);
            int w=bound(cfg.number("timerPicSizeX",bar==null?100:bar.getWidth()),1,4096);
            int h=bound(cfg.number("timerPicSizeY",bar==null?32:bar.getHeight()),1,4096);
            int units=remaining/100,total=Math.max(1,frame.choiceInfo[0]/100);
            if(type==1||type==2){
                int sx=0,sy=0,dir=cfg.number("timerBarHoukou",1);
                int visibleW=dir==1||dir==3?w*units/total:w;
                int visibleH=dir==0||dir==2?h*units/total:h;
                if(dir==0)sy=h-visibleH;
                if(dir==3)sx=w-visibleW;
                if(type==1){
                    int r=mix(cfg.number("timerColorR1",0),cfg.number("timerColorR2",255),total-units,total);
                    int g=mix(cfg.number("timerColorG1",255),cfg.number("timerColorG2",0),total-units,total);
                    int b=mix(cfg.number("timerColorB1",0),cfg.number("timerColorB2",0),total-units,total);
                    paint.setColor(Color.rgb(r,g,b));canvas.drawRect(x+sx,y+sy,x+sx+visibleW,y+sy+visibleH,paint);
                }else draw(canvas,bar,sx,sy,visibleW,visibleH,x+sx,y+sy,255);
            }else if((type==3||type==4)&&bar!=null){
                int columns=bar.getWidth()/w,rows=bar.getHeight()/h;
                if(columns>0&&rows>0){
                    int count=bound(cfg.number("timerPicNumber",1),1,columns*rows);
                    int step=Math.max(1,total/Math.max(1,count-1));
                    int frame=type==3?(int)((long)count*units/total):units/step;
                    frame=Math.min(count-1,frame);
                    draw(canvas,bar,(frame%columns)*w,(frame/columns)*h,w,h,x,y,255);
                    if(type==4&&frame+1<count){
                        int next=frame+1,alpha=(int)(255L*(units%step)/step);
                        draw(canvas,bar,(next%columns)*w,(next/columns)*h,w,h,x,y,alpha);
                    }
                }
            }
            int format=cfg.number("timerTimePrintFlag",0);
            if(format>=2){
                int px=cfg.number("timerTimePrintX",0),py=cfg.number("timerTimePrintY",0);
                int dw=bound(cfg.number("timerTimeFontSizeX",16),1,512),dh=bound(cfg.number("timerTimeFontSizeY",16),1,512);
                int count=bound(cfg.number("timerTimeKeta",3),1,9);
                number(canvas,digits,remaining/1000,"",count,dw,dh,px,py);
                if(format>=3){
                    int fraction=remaining%1000/(format==4?10:format>=5?100:1);
                    number(canvas,fractional,fraction,"2",bound(cfg.number("timerTimeKeta2",format-2),1,9),
                        bound(cfg.number("timerTimeFontSizeX2",dw),1,512),bound(cfg.number("timerTimeFontSizeY2",dh),1,512),
                        cfg.number("timerTimePrintX2",px+count*dw),cfg.number("timerTimePrintY2",py));
                }
            }
            canvas.restore();
        }
        private int mix(int a,int b,int at,int total){return bound(a,0,255)+(bound(b,0,255)-bound(a,0,255))*at/total;}
        private void number(Canvas c,Bitmap image,int number,String suffix,int count,int w,int h,int x,int y){
            String text=Integer.toString(number);
            int start=Math.max(0,text.length()-count),blank=Math.max(0,count-text.length());
            int advance=cfg.number("timerTimeFontNextX"+suffix,w);
            if(cfg.number("timerTimeUpzeroFlag"+suffix,0)!=0)
                for(int i=0;i<blank;i++)draw(c,image,0,0,w,h,x+i*advance,y,255);
            else if(cfg.number("timerTimeLeftFlag"+suffix,0)!=0)blank=0;
            for(int i=start;i<text.length();i++)
                draw(c,image,(text.charAt(i)-'0')*w,0,w,h,x+(blank+i-start)*advance,y,255);
        }
        private void draw(Canvas canvas,Bitmap image,int sx,int sy,int w,int h,int x,int y,int alpha){
            if(image==null||w<=0||h<=0||sx+w>image.getWidth()||sy+h>image.getHeight()||alpha<=0)return;
            src.set(sx,sy,sx+w,sy+h);dst.set(x,y,x+w,y+h);
            paint.setAlpha(alpha);canvas.drawBitmap(image,src,dst,paint);
        }
    }
    @Override protected void onDetachedFromWindow(){if(confirmation!=null)removeCallbacks(confirmation);confirmation=null;super.onDetachedFromWindow();}
    private final class ChoiceRow extends View {
        final int index;
        final String label;
        final DialogueTextView annotated;
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        final RectF dest=new RectF();
        ChoiceRow(int index){
            super(GameChoicePanel.this.getContext());this.index=index;
            DialogueMarkup content=markup.parse(frame.choices[index]);
            label=content.text;
            if(content.annotations.isEmpty())annotated=null;
            else{
                annotated=DialogueTextView.panel(getContext(),content,markup,font,Color.WHITE);
                annotated.setGravity(android.view.Gravity.TOP|android.view.Gravity.LEFT);
                annotated.setSingleLine(true);annotated.markupTextColor(true);
                int height=font*2+annotated.markupTopInset(font);
                annotated.measure(MeasureSpec.makeMeasureSpec(cursorW,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(height,MeasureSpec.EXACTLY));
                annotated.layout(0,0,cursorW,height);
                // Ruby may sit above the row without enlarging its touch target.
                GameChoicePanel.this.setClipChildren(false);
            }
            setContentDescription(label);setFocusable(true);setSoundEffectsEnabled(false);
            setOnClickListener(v->choose(index));
        }
        @Override public CharSequence getAccessibilityClassName(){return "android.widget.Button";}
        @Override protected void onFocusChanged(boolean gain,int direction,Rect previous){super.onFocusChanged(gain,direction,previous);if(gain)highlight(index);}
        @Override public boolean onHoverEvent(MotionEvent e){if(e.getActionMasked()==MotionEvent.ACTION_HOVER_ENTER)highlight(index);return super.onHoverEvent(e);}
        @Override public boolean onTouchEvent(MotionEvent e){
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN)highlight(index);
            return super.onTouchEvent(e);
        }
        @Override protected void onDraw(Canvas canvas){
            canvas.save();canvas.scale(getWidth()/(float)cursorW,getHeight()/(float)cursorH);
            long now=SystemClock.uptimeMillis();
            if(focused==index&&now>=readyAt){
                long elapsed=(now-readyAt)/Math.max(1,frame.frameMs);
                dest.set(0,0,cursorW,cursorH);
                if(!animation.draw(canvas,cursor,dest,paint,elapsed,255)){
                    paint.setColor(0x88498bcc);canvas.drawRect(dest,paint);
                }
                if(animation.active(elapsed))postInvalidateOnAnimation();
            }
            int tone=textColor(index);
            paint.setColor(tone);
            paint.setTextSize(font);
            float textX=-dx;
            if(center)textX+=(cfg.number(special?"centerAreaSizeX":"newCenteringSizeX",cursorW)-paint.measureText(label))/2;
            int rubyTop=annotated==null?0:annotated.getPaddingTop();
            canvas.clipRect(0,annotated==null?0:Math.min(0,-dy-rubyTop),cursorW,cursorH);
            if(now<readyAt){
                float shown=font*Math.max(0,now-appearAt)/(float)(readyAt-appearAt);
                float top=appearType==1?(font-shown)/2:appearType==3?font-shown:0;
                canvas.clipRect(0,top-dy-rubyTop,cursorW,top+shown-dy);
                postInvalidateOnAnimation();
            }
            if(annotated==null)canvas.drawText(label,textX,-dy-paint.ascent(),paint);
            else{
                annotated.setTextColor(tone);
                canvas.translate(textX,-dy-rubyTop);
                annotated.draw(canvas);
            }
            canvas.restore();
        }
    }
    private static int bound(int n,int min,int max){return Math.max(min,Math.min(n,max));}
}
