package com.tyranor.runtime.systemnnn;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.LongConsumer;

/** CCommonBackLog's line viewport, navigation and per-line voice replay. */
final class GameBacklogPanel extends GameUiPanel {
    interface Replay {void play(byte[] name);}
    private static final class Row {
        final String text;
        final int color;
        final List<byte[]> voices;
        final long jump;
        int nextVoice;
        Row(String text,int color,List<byte[]> voices,long jump){this.text=text;this.color=color;this.voices=voices;this.jump=jump;}
    }
    private final GameUiAssets.Config cfg;
    private final DialogueStyle style;
    private final Runnable close;
    private final Replay replay;
    private final LongConsumer jump;
    private final ArrayList<Row> rows=new ArrayList<>();
    private final int x,y,step,font,visible,space,gap,voiceCount;
    private final int swipeThreshold;
    private int first;
    private float downY;

    GameBacklogPanel(Context context,GameUiAssets assets,DialogueStyle style,int width,int height,Runnable close,Replay replay,LongConsumer jump){
        super(context,assets,assets.backlog.value("backlogBGFileName","ta_backlogbg"),()->{});
        cfg=assets.backlog;this.style=style;this.close=close;this.replay=replay;this.jump=jump;
        canvas(width,height);
        font=limit(cfg.number("fontSize",24),8,100);
        step=limit(cfg.number("nextY",font),8,120);
        x=limit(cfg.number("printX",110),0,canvasWidth-1);
        y=limit(cfg.number("printY",32),-canvasHeight,canvasHeight)+step*3;
        visible=limit(cfg.number("printLines",15),1,64);
        space=limit(cfg.number("space",1),-font/2,font/2);
        gap=limit(assets.printMessage.number("logPrintCR",1),0,8);
        voiceCount=limit(cfg.number("voiceMultiCount",1),1,8);
        swipeThreshold=ViewConfiguration.get(context).getScaledTouchSlop()*2;
        opening(cfg,false);
        render();
    }
    void entries(List<GameRunner.BacklogLine> entries){
        rows.clear();
        Paint measure=new Paint(Paint.ANTI_ALIAS_FLAG);measure.setTextSize(font);
        int available=Math.max(font,canvasWidth-x-Math.max(font,canvasWidth/10));
        for(GameRunner.BacklogLine line:entries){
            if(line.color<0)for(int i=0;i<gap;i++)rows.add(new Row("",style.bodyColor,Collections.emptyList(),0));
            List<byte[]> voices=line.voices.subList(0,Math.min(voiceCount,line.voices.size()));
            long jumpId=line.jump;
            if(!line.speaker.isEmpty()){
                wrap(line.speaker,style.speakerColor(line.speaker),voices,jumpId,measure,available);
                voices=Collections.emptyList();
                jumpId=0;
            }
            wrap(line.body,line.color<0?style.bodyColor:0xff000000|line.color,voices,jumpId,measure,available);
        }
        first=Math.max(0,rows.size()-visible);
        render();
    }
    private void wrap(String text,int color,List<byte[]> voices,long jumpId,Paint measure,int width){
        boolean firstRow=true;
        for(String paragraph:text.split("\n",-1)){
            int start=0;
            do{
                int count=measure.breakText(paragraph,start,paragraph.length(),true,width,null);
                int end=start+count;
                if(space>0){
                    while(end>start && measure.measureText(paragraph,start,end)+space*(end-start-1)>width)end--;
                }
                if(end<paragraph.length()&&end>start&&Character.isHighSurrogate(paragraph.charAt(end-1)))end--;
                if(end==start&&start<paragraph.length())end+=Character.charCount(paragraph.codePointAt(start));
                rows.add(new Row(paragraph.substring(start,end),color,firstRow?voices:Collections.emptyList(),firstRow?jumpId:0));
                firstRow=false;start=end;
            }while(start<paragraph.length());
        }
    }
    private void render(){
        clearControls();uiBackground(cfg,7,1);
        if(cfg.number("titlePicPrintFlag",0)!=0){
            String name=cfg.value("titlePicFileName","");
            if(sprite(name)!=null)picture(name,cfg.number("titlePicPrintX",0),cfg.number("titlePicPrintY",0),
                sprite(name).getWidth(),sprite(name).getHeight());
        }else if(cfg.number("titlePrintFlag",1)!=0){
            TextView title=text(cfg.text("titleMessage","历史记录"),font,Color.WHITE);
            put(title,cfg.number("titlePrintX",canvasWidth/2-font*3),cfg.number("titlePrintY",0),font*10,step);
        }
        for(int i=0;i<visible&&first+i<rows.size();i++){
            Row row=rows.get(first+i);int top=y+i*step;
            TextView text=text(row.text,font,row.color);
            text.setMaxLines(1);text.setLetterSpacing(space/(float)font);
            put(text,x,top,canvasWidth-x,Math.max(step,font));
            if(row.jump!=0)jumpButton(row.jump,top);
            if(!row.voices.isEmpty()&&cfg.number("voiceFlag",1)!=0)voice(row,top);
        }
        if(first>0)arrow(true);
        if(first+visible<rows.size())arrow(false);
        backButton();
    }
    private void backButton(){
        GameUiAssets.Config back=cfg.has("BackFileName")?cfg:assets.load;
        int w=limit(back.number("BackSizeX",220),1,4096),h=limit(back.number("BackSizeY",54),1,4096);
        GameButton.Spec spec=new GameButton.Spec(back,"Back",null,w,h);
        animatedButton(spec,sprite(spec.image),"返回",()->{},close,
            back.number("BackPrintX",canvasWidth-w-25),back.number("BackPrintY",canvasHeight-h-10),w,h);
    }
    private void voice(Row row,int top){
        int w=limit(cfg.number("voiceSizeX",font),1,512),h=limit(cfg.number("voiceSizeY",font),1,512);
        String image=cfg.number("voicePicFlag",0)==0?"":cfg.value("filenameVoice","ta_backlogvoice");
        int vx=x+cfg.number("voicePrintX",-font),vy=top+cfg.number("voicePrintY",0);
        animateIcon(image,"voicePic",w,h,"重播语音",vx,vy,()->{},()->{
            replay.play(row.voices.get(row.nextVoice));row.nextVoice=(row.nextVoice+1)%row.voices.size();
        });
    }
    private void jumpButton(long id,int top){
        int w=limit(cfg.number("jumpSizeX",font),1,512),h=limit(cfg.number("jumpSizeY",font),1,512);
        int jx=x+cfg.number("jumpPrintX",-font),jy=top+cfg.number("jumpPrintY",0);
        if(cfg.number("jumpPicFlag",0)!=0){
            animateIcon(cfg.value("filenameJump",""),"jumpPic",w,h,"跳转到此处",jx,jy,()->{},()->jump.accept(id));
            return;
        }
        TextView label=text(cfg.text("jumpMessage","⇒"),font,Color.WHITE);
        label.setSingleLine(true);label.setLetterSpacing(space/(float)font);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        put(label,jx,jy,Math.max(font,canvasWidth-jx),Math.max(step,font));
        View hit=new View(getContext()){
            @Override public CharSequence getAccessibilityClassName(){return "android.widget.Button";}
        };
        hit.setContentDescription("跳转到此处");hit.setSoundEffectsEnabled(false);hit.setFocusable(true);
        hit.setOnClickListener(v->jump.accept(id));
        put(hit,jx,jy,w,h);
    }
    private void arrow(boolean up){
        String prefix=up?"upArrow":"downArrow";
        int w=limit(cfg.number("updownArrowSizeX",font),1,512),h=limit(cfg.number("updownArrowSizeY",font),1,512);
        int ax=cfg.number(prefix+"PrintX",(canvasWidth-w)/2);
        int ay=cfg.number(prefix+"PrintY",up?y-step:y+visible*step);
        String name=cfg.number("updownArrowPicFlag",0)!=0?cfg.value(prefix+"FileName",up?"ta_up_bt01":"ta_down_bt01"):"";
        animateIcon(name,"updownArrow",w,h,up?"较早记录":"较新记录",ax,ay,
            ()->sound(cfg.number(prefix+"ClickSound",0)-1,-1),()->move(up?-1:1));
    }
    private GameButton animateIcon(String name,String prefix,int w,int h,String label,int x,int y,Runnable cue,Runnable action){
        int count=cfg.number(prefix+"AnimeFlag",0)!=0?cfg.number(prefix+"AnimePattern",1):1;
        int type=cfg.number(prefix+"AnimeType",1),speed=cfg.number(prefix+"AnimeSpeed",1);
        SpriteAnimation normal=new SpriteAnimation(0,0,w,h,type,count,speed,false);
        SpriteAnimation focus=new SpriteAnimation(w,0,w,h,type,count,speed,false);
        return animatedButton(new GameButton.Spec(name,normal,focus,focus,1),sprite(name),label,cue,action,x,y,w,h);
    }
    private void move(int delta){
        int next=limit(first+delta,0,Math.max(0,rows.size()-visible));
        if(next!=first){first=next;render();}
    }
    @Override public boolean onInterceptTouchEvent(MotionEvent event){
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN)downY=event.getY();
        return event.getActionMasked()==MotionEvent.ACTION_MOVE&&Math.abs(event.getY()-downY)>swipeThreshold;
    }
    @Override public boolean onTouchEvent(MotionEvent event){
        if(event.getActionMasked()==MotionEvent.ACTION_UP){
            float dy=event.getY()-downY;
            if(Math.abs(dy)>swipeThreshold){
                int lines=Math.max(1,Math.round(Math.abs(dy)*canvasHeight/Math.max(1,getHeight())/step));
                move(dy>0?-lines:lines);
            }
        }
        return super.onTouchEvent(event);
    }
    private static int limit(int value,int min,int max){return Math.max(min,Math.min(max,value));}
}
