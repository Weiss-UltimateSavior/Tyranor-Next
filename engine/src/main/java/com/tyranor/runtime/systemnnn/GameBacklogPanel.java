package com.tyranor.runtime.systemnnn;

import com.core.engine.R;

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
        final DialogueMarkup text;
        final int color;
        final List<byte[]> voices;
        final long jump;
        int nextVoice;
        Row(DialogueMarkup text,int color,List<byte[]> voices,long jump){this.text=text;this.color=color;this.voices=voices;this.jump=jump;}
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
            if(line.color<0)for(int i=0;i<gap;i++)rows.add(new Row(DialogueMarkup.EMPTY,style.bodyColor,Collections.emptyList(),0));
            List<byte[]> voices=line.voices.subList(0,Math.min(voiceCount,line.voices.size()));
            long jumpId=line.jump;
            if(!line.speaker.isEmpty()){
                wrap(style.markup(line.speaker),style.speakerColor(line.speaker),voices,jumpId,measure,available);
                voices=Collections.emptyList();
                jumpId=0;
            }
            wrap(style.markup(line.body),line.color<0?style.bodyColor:0xff000000|line.color,voices,jumpId,measure,available);
        }
        first=Math.max(0,rows.size()-visible);
        render();
    }
    private void wrap(DialogueMarkup text,int color,List<byte[]> voices,long jumpId,Paint measure,int width){
        boolean firstRow=true;
        int paragraphOffset=0;
        for(String paragraph:text.text.split("\n",-1)){
            int start=0;
            do{
                int count=measure.breakText(paragraph,start,paragraph.length(),true,width,null);
                int end=start+count;
                if(space>0){
                    while(end>start && measure.measureText(paragraph,start,end)+space*(end-start-1)>width)end--;
                }
                if(end<paragraph.length()&&end>start&&Character.isHighSurrogate(paragraph.charAt(end-1)))end--;
                if(end==start&&start<paragraph.length())end+=Character.charCount(paragraph.codePointAt(start));
                rows.add(new Row(text.slice(paragraphOffset+start,paragraphOffset+end),color,
                    firstRow?voices:Collections.emptyList(),firstRow?jumpId:0));
                firstRow=false;start=end;
            }while(start<paragraph.length());
            paragraphOffset+=paragraph.length()+1;
        }
    }
    private void render(){
        clearControls();uiBackground(cfg,7,1);
        if(cfg.number("titlePicPrintFlag",0)!=0){
            String name=cfg.value("titlePicFileName","");
            if(sprite(name)!=null)picture(name,cfg.number("titlePicPrintX",0),cfg.number("titlePicPrintY",0),
                sprite(name).getWidth(),sprite(name).getHeight());
        }else if(cfg.number("titlePrintFlag",1)!=0){
            TextView title=text(cfg.text("titleMessage",hostText(R.string.engine_systemnnn_ctl_backlog_title)),font,Color.WHITE);
            put(title,cfg.number("titlePrintX",canvasWidth/2-font*3),cfg.number("titlePrintY",0),font*10,step);
        }
        for(int i=0;i<visible&&first+i<rows.size();i++){
            Row row=rows.get(first+i);int top=y+i*step;
            TextView text;
            int inset=0;
            if(row.text.annotations.isEmpty())text=text(row.text.text,font,row.color);
            else{
                DialogueTextView annotated=DialogueTextView.panel(getContext(),row.text,style.markupStyle,font,row.color);
                inset=annotated.markupTopInset(font);text=annotated;
            }
            text.setMaxLines(1);text.setLetterSpacing(space/(float)font);
            put(text,x,top-inset,canvasWidth-x,Math.max(step,font)+inset);
            rowActions(row,top);
        }
        if(first>0)arrow(true);
        if(first+visible<rows.size())arrow(false);
        backButton();
    }
    private void backButton(){
        GameUiAssets.Config back=cfg.has("BackFileName")?cfg:assets.load;
        int w=limit(back.number("BackSizeX",220),1,4096),h=limit(back.number("BackSizeY",54),1,4096);
        GameButton.Spec spec=new GameButton.Spec(back,"Back",null,w,h);
        animatedButton(spec,sprite(spec.image),hostText(R.string.engine_systemnnn_host_back),()->{},close,
            back.number("BackPrintX",canvasWidth-w-25),back.number("BackPrintY",canvasHeight-h-10),w,h);
    }
    private void rowActions(Row row,int top){
        boolean replayVoice=!row.voices.isEmpty()&&cfg.number("voiceFlag",1)!=0;
        if(!replayVoice&&row.jump==0)return;
        int voiceWidth=limit(cfg.number("voiceSizeX",font),1,512),voiceHeight=limit(cfg.number("voiceSizeY",font),1,512);
        int jumpWidth=limit(cfg.number("jumpSizeX",font),1,512),jumpHeight=limit(cfg.number("jumpSizeY",font),1,512);
        boolean voicePosition=cfg.has("voicePrintX"),jumpPosition=cfg.has("jumpPrintX");
        int vx=x+cfg.number("voicePrintX",-font),vy=top+cfg.number("voicePrintY",0);
        int jx=x+cfg.number("jumpPrintX",-font),jy=top+cfg.number("jumpPrintY",0);
        if(!voicePosition)vx=limit(vx,0,Math.max(0,canvasWidth-voiceWidth));
        if(!jumpPosition)jx=limit(jx,0,Math.max(0,canvasWidth-jumpWidth));
        if(replayVoice&&row.jump!=0&&vx<jx+jumpWidth&&jx<vx+voiceWidth&&vy<jy+jumpHeight&&jy<vy+voiceHeight){
            if(!jumpPosition)jx=beside(vx,voiceWidth,jumpWidth);
            else if(!voicePosition)vx=beside(jx,jumpWidth,voiceWidth);
        }
        if(row.jump!=0)jumpButton(row.jump,jx,jy,jumpWidth,jumpHeight);
        if(replayVoice)voice(row,vx,vy,voiceWidth,voiceHeight);
    }
    private int beside(int x,int width,int otherWidth){
        return x>=otherWidth?x-otherWidth:limit(x+width,0,Math.max(0,canvasWidth-otherWidth));
    }
    private void voice(Row row,int vx,int vy,int w,int h){
        String image=cfg.number("voicePicFlag",0)==0?"":cfg.value("filenameVoice","ta_backlogvoice");
        animateIcon(image,"voicePic",w,h,hostText(R.string.engine_systemnnn_ctl_backlog_voice),vx,vy,()->{},()->{
            replay.play(row.voices.get(row.nextVoice));row.nextVoice=(row.nextVoice+1)%row.voices.size();
        });
    }
    private void jumpButton(long id,int jx,int jy,int w,int h){
        if(cfg.number("jumpPicFlag",0)!=0){
            animateIcon(cfg.value("filenameJump",""),"jumpPic",w,h,hostText(R.string.engine_systemnnn_ctl_backlog_jump),jx,jy,()->{},()->jump.accept(id));
            return;
        }
        TextView label=text(cfg.text("jumpMessage","⇒"),font,Color.WHITE);
        label.setSingleLine(true);label.setLetterSpacing(space/(float)font);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        put(label,jx,jy,Math.max(font,canvasWidth-jx),Math.max(step,font));
        View hit=new View(getContext()){
            @Override public CharSequence getAccessibilityClassName(){return "android.widget.Button";}
        };
        hit.setContentDescription(hostText(R.string.engine_systemnnn_ctl_backlog_jump));hit.setSoundEffectsEnabled(false);hit.setFocusable(true);
        hit.setOnClickListener(v->jump.accept(id));
        put(hit,jx,jy,w,h);
    }
    private void arrow(boolean up){
        String prefix=up?"upArrow":"downArrow";
        int w=limit(cfg.number("updownArrowSizeX",font),1,512),h=limit(cfg.number("updownArrowSizeY",font),1,512);
        int ax=cfg.number(prefix+"PrintX",(canvasWidth-w)/2);
        int ay=cfg.number(prefix+"PrintY",up?y-step:y+visible*step);
        String name=cfg.number("updownArrowPicFlag",0)!=0?cfg.value(prefix+"FileName",up?"ta_up_bt01":"ta_down_bt01"):"";
        animateIcon(name,"updownArrow",w,h,up?hostText(R.string.engine_systemnnn_ctl_backlog_earlier):hostText(R.string.engine_systemnnn_ctl_backlog_later),ax,ay,
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
