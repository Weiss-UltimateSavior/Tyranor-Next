package com.tyranor.runtime.systemnnn;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import java.io.IOException;
import java.util.ArrayList;

import static com.tyranor.runtime.systemnnn.GameRunner.Modal.BACK;
import static com.tyranor.runtime.systemnnn.GameRunner.Modal.CONFIRM;
import static com.tyranor.runtime.systemnnn.GameRunner.Modal.HOVER;
import static com.tyranor.runtime.systemnnn.GameRunner.Modal.NEW;
import static com.tyranor.runtime.systemnnn.GameRunner.Modal.PAGE;
import static com.tyranor.runtime.systemnnn.GameRunner.Modal.PRINT_ACHIEVEMENT;
import static com.tyranor.runtime.systemnnn.GameRunner.Modal.PRINT_RESULT;
import static com.tyranor.runtime.systemnnn.GameRunner.Modal.SELECTED;

/** CCommonPrintAchievement badges and the CCommonPrintResult plate viewer. */
final class GameProgressViewsPanel extends GameUiPanel {
    interface Result {void action(int action,int value);}
    private final boolean achievements;
    private final Result result;
    private final GameUiAssets.Config config;
    private final FrameClock badgeClock=new FrameClock();
    private GameRunner.Modal modal;
    private ArtLayer cards;
    private Description description;
    private GameButton backButton,upButton,downButton,okButton;
    private boolean foreground=true,upReverse,downReverse;
    private int pending=-1,wantedHover,deferred=-1,deferredValue;

    GameProgressViewsPanel(Context context,GameUiAssets assets,GameRunner.Modal modal,
                           int width,int height,Runnable click,Result result){
        super(context,assets,modal.assets.config.value("filenameBG",""),click);
        if(modal.mode!=PRINT_ACHIEVEMENT&&modal.mode!=PRINT_RESULT)
            throw new IllegalArgumentException("Unsupported progress mode "+modal.mode);
        achievements=modal.mode==PRINT_ACHIEVEMENT;
        this.result=result;config=modal.assets.config;
        canvas(width,height);
        opening(config,false);
        uiBackground(config,modal.mode,1);
        update(modal);
    }

    void update(GameRunner.Modal next){
        GameRunner.Modal before=modal;
        if(before!=null&&before.mode!=next.mode)throw new IllegalArgumentException("Progress mode changed");
        boolean rebuild=before==null||before.page!=next.page||before.pages!=next.pages||before.selection!=next.selection;
        modal=next;pending=-1;
        if(rebuild){
            wantedHover=next.group;
            render();
        }else if(description!=null&&before.group!=next.group)description.show(next.text);
        finishPending();
    }

    @Override Bitmap sprite(String name){return asset(assets,modal,name);}

    private static Bitmap asset(GameUiAssets assets,GameRunner.Modal modal,String name){
        if(name==null||name.isEmpty())return null;
        Bitmap image=modal==null||modal.assets==null?null:modal.assets.images.get(GameUiAssets.systemImageKey(name));
        return image!=null?image:assets.image(name);
    }

    private static GameRunner.Modal.Row pendingRow(GameRunner.Modal modal){
        for(GameRunner.Modal.Row row:modal.rows)
            if(row.id==modal.selection&&(row.flags&NEW)!=0)return row;
        return null;
    }

    /** A broken asset never promotes a pending result to acknowledged. */
    static String confirmationFailure(GameUiAssets assets,GameRunner.Modal modal){
        if(modal.mode!=PRINT_RESULT||modal.selection==0)return null;
        if(modal.assets==null)return "Missing result setup";
        GameUiAssets.Config cfg=modal.assets.config;
        GameRunner.Modal.Row row=pendingRow(modal);
        Bitmap dialog=row==null?null:asset(assets,modal,row.asset);
        if(cfg.number("dialogFlag",1)==0||!visiblePixels(dialog,0,0,
            cfg.number("dialogSizeX",460),cfg.number("dialogSizeY",260),1))
            return "Missing result acknowledgement image";
        GameButton.Spec spec=new GameButton.Spec(new ButtonArt.Setup(cfg,"Ok",null,false));
        Bitmap ok=asset(assets,modal,spec.image);
        SpriteAnimation normal=spec.states[0];
        if(!visiblePixels(ok,normal.x,normal.y,normal.w,normal.h,1)||
            (spec.pixelHit&&!visiblePixels(ok,spec.hit.x,spec.hit.y,spec.hit.w,spec.hit.h,1)))
            return "Missing result acknowledgement button";
        return null;
    }

    private static boolean visiblePixels(Bitmap bitmap,int x,int y,int width,int height,int alpha){
        if(bitmap==null||x<0||y<0||x>=bitmap.getWidth()||y>=bitmap.getHeight())return false;
        int w=Math.min(width,bitmap.getWidth()-x),h=Math.min(height,bitmap.getHeight()-y);
        if(w<=0||h<=0)return false;
        int[] row=new int[w];
        for(int i=0;i<h;i++){
            bitmap.getPixels(row,0,w,x,y+i,w,1);
            for(int pixel:row)if((pixel>>>24)>=alpha)return true;
        }
        return false;
    }

    private boolean inputReady(){return foreground&&!controlsBusy()&&activeFrameReady()&&!openingRunning();}
    private void send(int action,int value){
        if(!inputReady())return;
        if(pending==HOVER&&action!=HOVER){deferred=action;deferredValue=value;return;}
        if(pending!=-1)return;
        pending=action;result.action(action,value);
    }

    private void finishPending(){
        if(deferred!=-1){
            int action=deferred;deferred=-1;
            send(action,deferredValue);
        }else flushHover();
    }

    private void flushHover(){
        if(!achievements&&modal.selection==0&&wantedHover!=modal.group)send(HOVER,wantedHover);
    }

    @Override void cancelLeave(){
        if(pending==HOVER){
            pending=-1;wantedHover=modal.group;
            finishPending();
        }else{
            super.cancelLeave();
            pending=deferred=-1;
        }
    }

    @Override void uiActive(boolean active){
        super.uiActive(active);foreground=active;
        badgeClock.setRunning(active&&activeFrameReady());
        if(cards!=null)cards.invalidate();
        if(description!=null)description.refreshClock();
    }

    @Override boolean backOverlay(){
        if(inputReady()){
            if(modal.selection==0&&backButton!=null)backButton.performClick();
            else if(okButton!=null&&config.number("okIsCancelButton",0)!=0)okButton.performClick();
        }
        return true;
    }

    @Override public boolean dispatchHoverEvent(MotionEvent event){
        if(event.getActionMasked()==MotionEvent.ACTION_HOVER_EXIT)hover(0);
        else if(event.getActionMasked()==MotionEvent.ACTION_HOVER_ENTER||
            event.getActionMasked()==MotionEvent.ACTION_HOVER_MOVE)hover(hit(event));
        return super.dispatchHoverEvent(event);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event){
        if(pending!=-1&&pending!=HOVER)return true;
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN||event.getActionMasked()==MotionEvent.ACTION_MOVE)
            hover(hit(event));
        return super.dispatchTouchEvent(event);
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent event){
        if(event.getActionMasked()==MotionEvent.ACTION_SCROLL&&modal.selection==0&&inputReady()){
            float wheel=event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if(wheel!=0){
                if(upButton!=null&&((wheel>0)!=upReverse))return upButton.performClick();
                if(downButton!=null&&((wheel<0)!=downReverse))return downButton.performClick();
            }
        }
        return super.dispatchGenericMotionEvent(event);
    }

    private int hit(MotionEvent event){
        if(achievements||cards==null||modal.selection!=0)return 0;
        float x=event.getX()*canvasWidth/Math.max(1,getWidth());
        float y=event.getY()*canvasHeight/Math.max(1,getHeight());
        for(Part part:cards.parts)if(part.hit(x,y))return part.hitId;
        return 0;
    }

    private void hover(int id){
        if(achievements||modal.selection!=0||!inputReady())return;
        wantedHover=id;
        flushHover();
    }

    private void render(){
        clearControls();description=null;backButton=upButton=downButton=okButton=null;
        cards=new ArtLayer(achievements);
        if(achievements)achievementParts(cards);
        else resultParts(cards);
        put(cards,0,0,canvasWidth,canvasHeight);
        if(!achievements){
            if(modal.selection==0){
                description=new Description();
                description.show(modal.text);
                put(description,0,0,canvasWidth,canvasHeight);
            }
            if(config.number("pagePrintFlag",0)!=0){
                ArtLayer page=new ArtLayer(false);
                pageNumbers(page);
                put(page,0,0,canvasWidth,canvasHeight);
            }
        }
        if(!achievements&&modal.selection!=0)confirmation();
        else navigation();
    }

    private void achievementParts(ArtLayer layer){
        Bitmap numbers=config.number("percentPrintFlag",0)!=0?sprite(config.value("filenameSuuji","")):null;
        int w=size(config.number("suujiFontSizeX",16)),h=size(config.number("suujiFontSizeY",16));
        int step=config.number("suujiNextX",w);
        for(GameRunner.Modal.Row row:modal.rows){
            String key="achievement"+row.id+((row.flags&SELECTED)!=0?"ok":"not");
            int x=config.number(key+"PrintX",0),y=config.number(key+"PrintY",0);
            Bitmap art=sprite(config.value("filename"+key,"ta_anime"));
            int bw=size(config.number(key+"SizeX",16)),bh=size(config.number(key+"SizeY",16));
            if(art!=null)layer.add(art,new SpriteAnimation(0,0,bw,bh,config.number(key+"Type",1),
                config.number(key+"Pattern",1),config.number(key+"Speed",1),true),x,y,0);
            if(numbers!=null){
                int percent;
                try{percent=Integer.parseInt(row.detail);}catch(NumberFormatException invalid){continue;}
                // CommonPrintAchievement adds the configured percent delta twice.
                number(layer,numbers,percent,3,w,h,step,x+2*config.number("percentPrintX",0),
                    y+2*config.number("percentPrintY",0),config.number("suujiUpZeroFlag",0)!=0,
                    config.number("suujiLeftFlag",0)!=0);
            }
        }
    }

    private void resultParts(ArtLayer layer){
        int cols=Math.max(1,Math.min(1024,config.number("resultsNumberX",5)));
        int rows=Math.max(1,Math.min(1024,config.number("resultsNumberY",2)));
        int w=size(config.number("resultsSizeX",150)),h=size(config.number("resultsSizeY",200));
        int x=config.number("resultsPrintX",25),y=config.number("resultsPrintY",70);
        int dx=config.number("resultsNextX",w),dy=config.number("resultsNextY",h);
        for(GameRunner.Modal.Row row:modal.rows){
            if((row.flags&NEW)!=0)continue;
            int slot=(row.id-1)%(cols*rows),cx=slot%cols,cy=slot/cols;
            layer.part(sprite(row.asset),cx*w,cy*h,w,h,x+cx*dx,y+cy*dy,row.enabled()?row.id:0);
        }
        if(modal.selection!=0){
            GameRunner.Modal.Row row=pendingRow(modal);
            if(row!=null)layer.part(sprite(row.asset),0,0,size(config.number("dialogSizeX",460)),
                size(config.number("dialogSizeY",260)),config.number("dialogPrintX",170),config.number("dialogPrintY",130),0);
        }
    }

    private void pageNumbers(ArtLayer layer){
        Bitmap font=sprite(config.value("fileNamePageFont","ta_font_sl3"));
        if(font==null)return;
        int w=size(config.number("pageFontSizeX",21)),h=size(config.number("pageFontSizeY",27));
        int step=config.number("pageFontNextX",w),digits=Math.max(1,Math.min(9,config.number("pageKeta",2)));
        int x=config.number("pagePrintX",0),y=config.number("pagePrintY",0);
        int zeros=config.number("pageUpZeroPrintFlag",0);
        number(layer,font,modal.page+1,digits,w,h,step,x,y,zeros!=0,false);
        if(config.number("pageMaxPrintFlag",0)==0)return;
        number(layer,font,modal.pages,digits,w,h,step,config.number("pageMaxPrintX",x+step*(digits+1)),
            config.number("pageMaxPrintY",y),config.number("maxPageUpZeroPrintFlag",zeros)!=0,
            config.number("pageMaxLeftAlign",0)!=0);
        if(config.number("slashPrintFlag",0)!=0)
            layer.part(font,11*w,0,w,h,config.number("slashPrintX",x+step*digits),config.number("slashPrintY",y),0);
    }

    private static void number(ArtLayer layer,Bitmap font,int value,int digits,int w,int h,int step,int x,int y,
                               boolean zeros,boolean left){
        if(value<0)return;
        String text=Integer.toString(value);
        int first=Math.max(0,text.length()-digits),blank=Math.max(0,digits-text.length());
        if(zeros)for(int i=0;i<blank;i++)layer.part(font,0,0,w,h,x+i*step,y,0);
        int at=zeros||!left?blank:0;
        for(int i=first;i<text.length();i++,at++)
            layer.part(font,(text.charAt(i)-'0')*w,0,w,h,x+at*step,y,0);
    }

    private void navigation(){
        GameUiAssets.Config back=config.number("backButtonType",0)==1||
            config.number("useCommonBackSetup",0)!=0?assets.game:config;
        if(!achievements&&modal.pages>1){
            GameUiAssets.Config pages=config.number("updownButtonType",0)==1||
                config.number("useCommonUpDownSetup",0)!=0?assets.game:config;
            ButtonArt.Setup up=new ButtonArt.Setup(pages,"Up",null,false);
            ButtonArt.Setup down=new ButtonArt.Setup(pages,"Down",up,false);
            upReverse=pages.number("UpUpDownReverse",0)!=0;
            downReverse=pages.number("DownUpDownReverse",0)!=0;
            int x=pages.number("UpPrintX",0),y=pages.number("UpPrintY",0);
            if(modal.page>0)upButton=control(up,"ta_upButton","上一页",()->send(PAGE,modal.page-1),
                config.number("UpPrintX",x),config.number("UpPrintY",y));
            if(modal.page+1<modal.pages)downButton=control(down,"ta_downButton","下一页",()->send(PAGE,modal.page+1),
                config.number("DownPrintX",pages.number("DownPrintX",0)),config.number("DownPrintY",pages.number("DownPrintY",0)));
        }
        int x=back.number("BackPrintX",0),y=back.number("BackPrintY",0);
        if(!achievements){x=config.number("BackPrintX",x);y=config.number("BackPrintY",y);}
        backButton=control(new ButtonArt.Setup(back,"Back",null,false),"ta_backButton","返回",()->send(BACK,0),x,y);
    }

    private void confirmation(){
        if(confirmationFailure(assets,modal)!=null)return;
        int id=modal.selection;
        okButton=control(new ButtonArt.Setup(config,"Ok",null,false),"","确定",()->send(CONFIRM,id),
            config.number("dialogPrintX",170)+config.number("OkPrintX",0),
            config.number("dialogPrintY",130)+config.number("OkPrintY",0));
    }

    private GameButton control(ButtonArt.Setup art,String fallback,String label,Runnable action,int x,int y){
        GameButton.Spec spec=new GameButton.Spec(art);
        Bitmap image=sprite(spec.image.isEmpty()?fallback:spec.image);
        return animatedButton(spec,image,label,()->sound(spec.sound,spec.voice),action,x,y,art.width,art.height);
    }

    private static int size(int value){return Math.max(1,Math.min(4096,value));}

    private static final class Part {
        final Bitmap bitmap;
        final SpriteAnimation frames;
        final RectF box;
        final int hitId;
        Part(Bitmap bitmap,SpriteAnimation frames,int x,int y,int hitId){
            this.bitmap=bitmap;this.frames=frames;this.hitId=hitId;
            box=new RectF(x,y,(long)x+frames.w,(long)y+frames.h);
        }
        boolean hit(float x,float y){
            if(hitId==0||!box.contains(x,y))return false;
            int sx=frames.x+(int)(x-box.left),sy=frames.y+(int)(y-box.top);
            return sx>=0&&sy>=0&&sx<bitmap.getWidth()&&sy<bitmap.getHeight()&&(bitmap.getPixel(sx,sy)>>>24)!=0;
        }
    }

    private final class ArtLayer extends View {
        final ArrayList<Part> parts=new ArrayList<>();
        private final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);
        private final boolean animated;
        ArtLayer(boolean animated){
            super(GameProgressViewsPanel.this.getContext());this.animated=animated;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        void add(Bitmap art,SpriteAnimation frames,int x,int y,int id){parts.add(new Part(art,frames,x,y,id));}
        void part(Bitmap art,int sx,int sy,int w,int h,int x,int y,int id){
            if(art!=null&&sx>=0&&sy>=0&&sx<art.getWidth()&&sy<art.getHeight())
                add(art,new SpriteAnimation(sx,sy,w,h,1,1,1,false),x,y,id);
        }
        @Override protected void onDraw(Canvas canvas){
            boolean running=animated&&foreground&&activeFrameReady();
            if(animated)badgeClock.setRunning(running);
            long frame=animated?badgeClock.frames(frameMs):0;
            int save=canvas.save();
            canvas.scale(getWidth()/(float)canvasWidth,getHeight()/(float)canvasHeight);
            boolean changes=false;
            for(Part part:parts){
                part.frames.draw(canvas,part.bitmap,part.box,paint,frame,255);
                changes|=part.frames.active(frame);
            }
            canvas.restoreToCount(save);
            if(running&&changes)postInvalidateOnAnimation();
        }
    }

    private static final class FrameClock {
        private long since=-1,elapsed;
        void reset(){since=-1;elapsed=0;}
        void setRunning(boolean run){
            if(run==(since>=0))return;
            long now=SystemClock.uptimeMillis();
            if(run)since=now;
            else{elapsed+=now-since;since=-1;}
        }
        long frames(int frameMs){return (elapsed+(since<0?0:SystemClock.uptimeMillis()-since))/frameMs;}
    }

    private final class Description extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final FrameClock clock=new FrameClock();
        private final int type=config.number("descPrintType",1);
        private final int x=config.number("messagePrintX",56),y=config.number("messagePrintY",484);
        private final int font=Math.max(1,Math.min(256,config.number("messageFontSize",22)));
        private final int space=Math.max(-font+1,Math.min(256,config.number("messageSukima",1)));
        private final int nextY=config.number("messageNextY",font+font/2);
        private final int capacity=Math.max(1,Math.min(1024,config.number("messageLength",33)));
        private final int speed=size(config.number("scrollSpeed",3));
        private final int wait=Math.max(0,config.number("scrollStartWait",150));
        private final int start=x-font-space,end=x+(font-space)*(capacity+1);
        private final int color=Color.rgb(component("messageColorR",255),component("messageColorG",247),
            component("messageColorB",153));
        private final int units=assets.game.number("codeByte",2)==1?2:1;
        private final int advance=Math.max(1,font/units+space);
        private String[][] glyphs=new String[0][];
        private int scrollFrames;

        Description(){
            super(GameProgressViewsPanel.this.getContext());
            paint.setTextSize(font);paint.setColor(color);
            if(type==1&&end>start+2*font)paint.setShader(edgeGradient());
        }
        private int component(String key,int value){return Math.max(0,Math.min(255,config.number(key,value)));}
        void refreshClock(){clock.setRunning(foreground&&activeFrameReady());invalidate();}
        void show(byte[][] raw){
            String[] lines=new String[raw.length];
            glyphs=new String[raw.length][];
            boolean invalid=false;
            for(int i=0;i<lines.length;i++){
                try{lines[i]=modal.decoder.decodeConfiguration(raw[i]);}
                catch(IOException e){lines[i]="";invalid=true;}
                glyphs[i]=lines[i].codePoints().mapToObj(code->new String(Character.toChars(code))).toArray(String[]::new);
            }
            if(invalid)Log.w("SystemNNN","Invalid result description text for "+modal.group);
            scrollFrames=raw.length==0?0:Math.max(0,raw[0].length/2-30)*font/speed;
            clock.reset();
            setContentDescription(String.join("\n",lines));
            invalidate();
        }
        private LinearGradient edgeGradient(){
            // CMyFontCache::GradBlt draws eight equal-width alpha bands on each end.
            int[] colors=new int[34];float[] locations=new float[34];
            for(int i=0;i<17;i++){
                int from,to,percent;
                if(i<8){from=font*i/8;to=font*(i+1)/8;percent=(i+1)*100/9;}
                else if(i==8){from=font;to=end-start-font;percent=100;}
                else{
                    int j=i-9;
                    from=end-start-font+font*j/8;to=end-start-font+font*(j+1)/8;percent=(8-j)*100/9;
                }
                colors[2*i]=colors[2*i+1]=(color&0xffffff)|(percent*255/100<<24);
                locations[2*i]=from/(float)(end-start);locations[2*i+1]=to/(float)(end-start);
            }
            return new LinearGradient(start,0,end,0,colors,locations,Shader.TileMode.CLAMP);
        }
        private void line(Canvas canvas,String[] text,int first,int length,float atX,int atY){
            float baseline=atY-paint.ascent();
            for(int i=first;i<text.length&&i-first<length;i++,atX+=advance)
                canvas.drawText(text[i],atX,baseline,paint);
        }
        @Override protected void onDraw(Canvas canvas){
            if(glyphs.length==0)return;
            int save=canvas.save();
            canvas.scale(getWidth()/(float)canvasWidth,getHeight()/(float)canvasHeight);
            if(type==0){
                for(int i=0;i<glyphs.length;i++)line(canvas,glyphs[i],0,glyphs[i].length,x,y+i*nextY);
            }else if(type==1&&end>start){
                boolean running=foreground&&activeFrameReady();
                clock.setRunning(running);
                long frame=clock.frames(frameMs);
                int distance=(int)Math.max(0,Math.min(scrollFrames,frame-wait))*speed;
                int first=distance>2*font?(distance-font)/font:0;
                int offset=distance>2*font?distance%font+font:distance;
                canvas.clipRect(start,0,end,canvasHeight);
                line(canvas,glyphs[0],first*units,capacity*units,x-offset,y);
                if(running&&scrollFrames>0){
                    if(frame<=wait)postInvalidateDelayed((wait+1L-frame)*frameMs);
                    else if(frame<(long)wait+scrollFrames)postInvalidateOnAnimation();
                }
            }
            canvas.restoreToCount(save);
        }
    }
}
