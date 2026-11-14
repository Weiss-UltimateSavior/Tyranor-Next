package com.tyranor.runtime.systemnnn;

import com.core.engine.R;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
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
    private final int bgMode,backColor,printY;
    private final int x,y,step,font,visible,space,gap,voiceCount;
    private final int swipeThreshold;
    private boolean hasCapture;
    private int first;
    private float downY;

    static int bgMode(GameUiAssets.Config cfg){
        if(cfg.number("backScriptFlag",0)!=0)return 3;
        int mode=cfg.number("bgMode",0);
        return mode>=0&&mode<=3?mode:0;
    }

    static int colorForTag(char tag){
        return DialogueMarkup.colorTag(tag);
    }

    GameBacklogPanel(Context context,GameUiAssets assets,DialogueStyle style,int width,int height,Runnable close,Replay replay,LongConsumer jump){
        super(context,assets,bgMode(assets.backlog)==1?assets.backlog.value("backlogBGFileName","bg_backlog"):null,()->{});
        cfg=assets.backlog;this.style=style;this.close=close;this.replay=replay;this.jump=jump;
        bgMode=bgMode(cfg);
        backColor=Color.rgb(limit(cfg.number("backColorR",0),0,255),limit(cfg.number("backColorG",0),0,255),limit(cfg.number("backColorB",0),0,255));
        transparentBackground();
        canvas(width,height);
        font=limit(cfg.number("fontSize",24),8,100);
        step=limit(cfg.number("nextY",font),8,120);
        x=limit(cfg.number("printX",110),0,canvasWidth-1);
        printY=limit(cfg.number("printY",32),-canvasHeight,canvasHeight);
        y=printY+step*3;
        visible=limit(cfg.number("printLines",15),1,64);
        space=limit(cfg.number("space",1),-font/2,font/2);
        gap=limit(assets.printMessage.number("logPrintCR",1),0,8);
        voiceCount=limit(cfg.number("voiceMultiCount",1),1,8);
        swipeThreshold=ViewConfiguration.get(context).getScaledTouchSlop()*2;
        opening(cfg,false);
        if(bgMode==3&&cfg.number("screenToGetPutFlag",0)==0)uiBackground(cfg,7,1);
        render();
    }
    @Override void capturedBackground(Bitmap value,Runnable ready){
        hasCapture=value!=null;
        super.capturedBackground(value,ready);
    }
    @Override void drawPanelBackground(Canvas canvas){
        if(bgMode==0)canvas.drawColor(backColor);
        else if(bgMode==1&&sprite(cfg.value("backlogBGFileName","bg_backlog"))==null)canvas.drawColor(backColor);
        else if(bgMode==2&&!hasCapture)canvas.drawColor(backColor);
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
            DialogueMarkup body=style.markupStyle.backlog(line.body);
            if(body.omittedSymbol)Log.w("SystemNNN","Backlog decorative cat glyph is unavailable; retaining its original text");
            wrap(body,line.color<0?style.bodyColor:0xff000000|line.color,voices,jumpId,measure,available);
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
        clearControls();
        int titleUpper=cfg.number("titlePicUpperFlag",0);
        if(cfg.number("titlePicPrintFlag",0)!=0&&titleUpper==0)drawTitlePicture();
        if(cfg.number("titlePrintFlag",1)!=0){
            String rawTitle=cfg.text("titleMessage","");
            if(!rawTitle.isEmpty()){
                TextView title=styledLabel(rawTitle,Color.WHITE);
                put(title,cfg.number("titlePrintX",canvasWidth/2-font*3),cfg.number("titlePrintY",printY),
                    Math.max(font*10,canvasWidth/2),Math.max(step,font));
            }
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
            if(!row.text.colors.isEmpty()){
                SpannableString colored=new SpannableString(row.text.text);
                for(DialogueMarkup.ColorRun run:row.text.colors)
                    colored.setSpan(new ForegroundColorSpan(run.color),run.start,run.end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                text.setText(colored);
            }
            put(text,x,top-inset,canvasWidth-x,Math.max(step,font)+inset);
            rowActions(row,top);
        }
        if(cfg.number("titlePicPrintFlag",0)!=0&&titleUpper==2)drawTitlePicture();
        if(first>0)arrow(true);
        if(first+visible<rows.size())arrow(false);
        if(cfg.number("titlePicPrintFlag",0)!=0&&titleUpper==1)drawTitlePicture();
        backButton();
    }
    private void drawTitlePicture(){
        String name=cfg.value("titlePicFileName","ta_backlog_title");
        Bitmap pic=sprite(name);
        if(pic!=null)picture(name,cfg.number("titlePicPrintX",0),cfg.number("titlePicPrintY",0),pic.getWidth(),pic.getHeight());
    }
    private TextView styledLabel(String raw,int fallbackColor){
        int color=fallbackColor;
        String body=raw;
        if(body.length()>=2&&body.charAt(0)=='#'){
            int tagged=colorForTag(body.charAt(1));
            if(tagged!=0){color=tagged;body=body.substring(2);}
        }
        DialogueMarkup parsed=style.markup(body);
        TextView view=parsed.annotations.isEmpty()?text(parsed.text,font,color):
            DialogueTextView.panel(getContext(),parsed,style.markupStyle,font,color);
        view.setSingleLine(true);view.setLetterSpacing(space/(float)font);
        return view;
    }
    private void textActionButton(String raw,int fallbackColor,String description,int bx,int by,int w,int h,Runnable cue,Runnable action){
        TextView label=styledLabel(raw,fallbackColor);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        put(label,bx,by,Math.max(font,canvasWidth-bx),Math.max(step,font));
        View hit=new View(getContext()){
            @Override public CharSequence getAccessibilityClassName(){return "android.widget.Button";}
        };
        hit.setContentDescription(description);hit.setSoundEffectsEnabled(false);hit.setFocusable(true);
        hit.setOnClickListener(v->{cue.run();action.run();});
        put(hit,bx,by,w,h);
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
        Runnable action=()->{replay.play(row.voices.get(row.nextVoice));row.nextVoice=(row.nextVoice+1)%row.voices.size();};
        String description=hostText(R.string.engine_systemnnn_ctl_backlog_voice);
        if(cfg.number("voicePicFlag",0)!=0){
            animateIcon(cfg.value("filenameVoice","ta_backlogvoice"),"voicePic",w,h,description,vx,vy,()->{},action);
            return;
        }
        String def=assets.sharedConfig.singleByteText()?"SD":"♪";
        textActionButton(cfg.text("voiceMessage",def),Color.WHITE,description,vx,vy,w,h,()->{},action);
    }
    private void jumpButton(long id,int jx,int jy,int w,int h){
        String description=hostText(R.string.engine_systemnnn_ctl_backlog_jump);
        if(cfg.number("jumpPicFlag",0)!=0){
            animateIcon(cfg.value("filenameJump",""),"jumpPic",w,h,description,jx,jy,()->{},()->jump.accept(id));
            return;
        }
        String def=assets.sharedConfig.singleByteText()?"JP":"⇒";
        textActionButton(cfg.text("jumpMessage",def),Color.WHITE,description,jx,jy,w,h,()->{},()->jump.accept(id));
    }
    private void arrow(boolean up){
        String prefix=up?"upArrow":"downArrow";
        int w=limit(cfg.number("updownArrowSizeX",font),1,512),h=limit(cfg.number("updownArrowSizeY",font),1,512);
        int ax=cfg.number(prefix+"PrintX",(canvasWidth-w)/2);
        int ay=cfg.number(prefix+"PrintY",up?y-step:y+visible*step);
        Runnable cue=()->sound(cfg.number(prefix+"ClickSound",0)-1,-1);
        Runnable action=()->move(up?-1:1);
        String description=hostText(up?R.string.engine_systemnnn_ctl_backlog_earlier:R.string.engine_systemnnn_ctl_backlog_later);
        if(cfg.number("updownArrowPicFlag",0)!=0){
            boolean animated=cfg.number("updownArrowAnimeFlag",0)!=0;
            String name;
            if(animated||cfg.has(prefix+"FileName")){
                name=cfg.value(prefix+"FileName",up?"ta_backlog_up":"ta_backlog_down");
                if(sprite(name)==null&&!cfg.has(prefix+"FileName"))name=up?"ta_up_bt01":"ta_down_bt01";
            }else{
                name=cfg.value("filenameUpdownArrow","ta_backlog_updown");
                if(sprite(name)==null&&!cfg.has("filenameUpdownArrow"))name=up?"ta_up_bt01":"ta_down_bt01";
            }
            animateIcon(name,"updownArrow",w,h,description,ax,ay,cue,action);
            return;
        }
        boolean single=assets.sharedConfig.singleByteText();
        String def=single?(up?"^^":"vv"):(up?"▲":"▼");
        textActionButton(cfg.text(prefix+"Message",def),cfg.has(prefix+"Message")?Color.WHITE:0xff00ffff,
            description,ax,ay,w,h,cue,action);
    }
    private GameButton animateIcon(String name,String prefix,int w,int h,String label,int x,int y,Runnable cue,Runnable action){
        boolean animated=cfg.number(prefix+"AnimeFlag",0)!=0;
        int count=animated?cfg.number(prefix+"AnimePattern",2):1;
        int type=animated?cfg.number(prefix+"AnimeType",5):1;
        int speed=animated?cfg.number(prefix+"AnimeSpeed",10):1;
        SpriteAnimation normal=new SpriteAnimation(0,0,w,h,type,count,speed,false);
        SpriteAnimation focus=animated?new SpriteAnimation(w,0,w,h,type,count,speed,false):normal;
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
