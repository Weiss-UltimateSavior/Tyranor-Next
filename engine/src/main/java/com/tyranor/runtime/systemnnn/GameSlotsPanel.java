package com.tyranor.runtime.systemnnn;

import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import com.core.engine.EngineUiText;
import com.core.engine.R;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;

/** Save and load slot artwork in the game's configured grid. */
final class GameSlotsPanel extends GameUiPanel {
    interface Select { void choose(int slot); }
    private final boolean saving;
    private final Select select;
    private final Runnable close,transferPc;
    private final GameUiAssets.Config config,data;
    private final SaveSlots.Layout layout;
    private final Map<Integer,SaveSlots.Entry> entries=new HashMap<>();
    private final int cardWidth,cardHeight;
    private final Bitmap artworkAtlas,lastSaveAtlas;
    private final int artworkColumns,artworkMinimum;
    private final Rect artworkDestination;
    private final SpriteAnimation lastSaveAnimation;
    private final RectF lastSaveDestination;
    private long lastSaveSince=SystemClock.uptimeMillis();
    private int page,backgroundPage=-1;
    private GameUiPanel confirmation;
    private SlotCard loadCard;
    private Runnable pendingLoad;
    private long loadClickAt;
    private boolean refreshEntries;
    GameSlotsPanel(Context c,GameUiAssets assets,boolean saving,SaveSlots.Entry[] slots,Integer lastSelected,Runnable click,Runnable close,Select select,Runnable transferPc,
                   UiPreviewPool.Warmup warmup){
        super(c,assets,(saving?assets.save:assets.load).value("filenameBG",saving?"ta_savebg01":"ta_loadbg01"),click);
        this.saving=saving;this.select=select;this.close=close;this.transferPc=transferPc;
        config=saving?assets.save:assets.load;data=assets.dataFile;
        layout=new SaveSlots.Layout(config,assets.game,data,saving);
        page=layout.openingPage(lastSelected);
        cardWidth=limit(data.number("areaSizeX",132),1,4096);cardHeight=limit(data.number("areaSizeY",100),1,4096);
        artworkAtlas=data.number("addPicPrintFlag",0)!=0?sprite(data.value("fileNameAddPic","ta_sl_font")):null;
        artworkColumns=data.number("addPicMultiFlag",0)!=0?Math.max(1,data.number("addPicMultiNumberX",1)):0;
        int specialLimit=data.number("specialNumberPrintFlag",0)!=0?data.number("specialNumberPrintLimit",-1):-1;
        artworkMinimum=data.number("addPicWithSpecialFlag",0)!=0?specialLimit:Integer.MIN_VALUE;
        int artX=limit(data.number("addPicPrintX",0),-4096,4096),artY=limit(data.number("addPicPrintY",0),-4096,4096);
        artworkDestination=new Rect(artX,artY,artX+limit(data.number("addPicSizeX",16),1,4096),
            artY+limit(data.number("addPicSizeY",16),1,4096));
        lastSaveAtlas=config.number("lastSelectFlag",0)!=0?sprite(data.value("filenameLastSave","")):null;
        int markX=limit(data.number("lastSavePrintX",0),-4096,4096),markY=limit(data.number("lastSavePrintY",0),-4096,4096);
        int markWidth=limit(data.number("lastSaveSizeX",16),1,4096),markHeight=limit(data.number("lastSaveSizeY",16),1,4096);
        lastSaveDestination=new RectF(markX,markY,markX+markWidth,markY+markHeight);
        lastSaveAnimation=new SpriteAnimation(0,0,markWidth,markHeight,data.number("lastSaveType",1),
            data.number("lastSavePattern",1),data.number("lastSaveSpeed",1),true);
        // Original decorations may extend past the slot base, e.g. lastSavePrintY=-6.
        if(artworkAtlas!=null||lastSaveAtlas!=null)setClipChildren(false);
        opening(config,false);
        entries(slots,warmup);
    }
    static String pcTransferLabel(Context context,boolean saving){
        return EngineUiText.get(context,saving?R.string.engine_systemnnn_ctl_pc_export:R.string.engine_systemnnn_ctl_pc_import);
    }
    String pcTransferLabel(){return pcTransferLabel(getContext(),saving);}
    private String uiText(int resource,Object... args){
        return EngineUiText.localizedContext(getContext()).getString(resource,args);
    }
    void performPcTransfer(){transferPc.run();}
    @Override boolean controlsBusy(){return super.controlsBusy()||confirmation!=null||loadCard!=null;}
    void entries(SaveSlots.Entry[] value){entries(value,null);}
    private void entries(SaveSlots.Entry[] value,UiPreviewPool.Warmup warmup){
        entries.clear();
        for(SaveSlots.Entry entry:value)entries.put(entry.slot,entry);
        if(confirmation==null&&loadCard==null)render(warmup);else refreshEntries=true;
    }
    boolean hasOtherSaves(){
        if(saving)return false;
        for(SaveSlots.Entry entry:entries.values())
            if(entry.exists&&!entry.corrupt&&!layout.shows(entry.slot))return true;
        return false;
    }
    List<SaveSlots.Entry> otherSaves(){
        List<SaveSlots.Entry> result=new ArrayList<>();
        if(!saving)for(SaveSlots.Entry entry:entries.values())
            if(entry.exists&&!entry.corrupt&&!layout.shows(entry.slot))result.add(entry);
        result.sort((a,b)->Integer.compare(a.slot,b.slot));
        return result;
    }
    void loadOther(int slot){
        SaveSlots.Entry entry=entries.get(slot);
        if(!saving&&entry!=null&&entry.exists&&!entry.corrupt&&!layout.shows(slot))select.choose(slot);
    }
    private void render(){render(null);}
    private void render(UiPreviewPool.Warmup warmup){
        clearControls();
        if(backgroundPage!=page){uiBackground(config,saving?6:5,page+1,warmup);backgroundPage=page;}
        int x=config.number("printX",85),y=config.number("printY",113);
        int dx=config.number("nextX",166),dy=config.number("nextY",124);
        boolean allowQuick=config.number(saving?"quickSaveSelectOk":"quickLoadSelectOk",0)!=0;
        for(int i=0;i<layout.perPage;i++){
            int index=page*layout.perPage+i;
            SaveSlots.Entry e=entries.get(layout.slotAt(index));
            if(e==null)continue;
            SlotCard card=new SlotCard(e,index+1);
            card.setEnabled((e.slot!=SaveSlots.QUICK||allowQuick)&&(saving||e.exists&&!e.corrupt));
            card.setOnClickListener(v->{
                if(!saving)beginLoad(card,e.slot);
                else if(e.exists&&config.number("warningPrintFlag",1)!=0){
                    sound(config.number("warningSound",0)-1,-1);confirm(e);
                }else select.choose(e.slot);
            });
            put(card,x+(i%layout.columns)*dx,y+(i/layout.columns)*dy,cardWidth,cardHeight);
        }
        if(layout.pageMode==1)pageArrows(config,page,layout.pages,616,16,646,this::showPage);
        else if(layout.pageMode==2)pageTabs();
        if(config.number("pagePrintFlag",0)!=0)pageNumber(layout.pages);
        View back=back(config,close);
        back.setContentDescription(uiText(R.string.engine_systemnnn_ctl_back_transfer,pcTransferLabel()));
        back.setOnLongClickListener(v->{performPcTransfer();return true;});
    }
    private void showPage(int next){page=next;lastSaveSince=SystemClock.uptimeMillis();render();}
    private void pageTabs(){
        ButtonArt.Setup previous=null;
        int nextX=0,nextY=0;
        boolean resetSource=assets.game.number("notSrcCopyFlagEnable",0)!=0;
        for(int i=0;i<layout.pages;i++){
            String key="tab"+(i+1)+"Button";
            ButtonArt.Setup off=new ButtonArt.Setup(config,key+"1",previous,resetSource);
            ButtonArt.Setup on=new ButtonArt.Setup(config,key+"2",off,false);
            int x=config.number(key+"1PrintX",nextX),y=config.number(key+"1PrintY",nextY);
            int onX=config.number(key+"2PrintX",x),onY=config.number(key+"2PrintY",y);
            previous=on;nextX=onX+on.width;nextY=onY;
            boolean selected=i==page;
            ButtonArt.Setup setup=selected?on:off;
            GameButton.Spec spec=new GameButton.Spec(setup);
            int target=i;
            GameButton tab=animatedButton(spec,sprite(spec.image),Integer.toString(i+1),
                ()->sound(spec.sound,spec.voice),()->showPage(target),selected?onX:x,selected?onY:y,setup.width,setup.height);
            tab.setContentDescription(uiText(R.string.engine_systemnnn_ctl_page_count,i+1,layout.pages));
            tab.setSelected(selected);
            if(selected){tab.setOnClickListener(null);tab.setClickable(false);}
        }
    }
    private void pageNumber(int pages){
        int places=limit(config.number("pageKeta",2),1,4);
        int zero=config.number("pageUpZeroPrintFlag",0);
        int w=limit(config.number("pageFontSizeX",21),1,512),h=limit(config.number("pageFontSizeY",27),1,512);
        int advance=limit(config.number("pageFontNextX",w),1,512);
        int x=config.number("pagePrintX",0),y=config.number("pagePrintY",0);
        Bitmap font=sprite(config.value("fileNamePageFont","ta_font_sl3"));
        String number=zero!=0?digits(page+1,places):Integer.toString(page+1);
        View label=pageGlyphs(font,number,w,h,advance,x+Math.max(0,places-number.length())*advance,y);
        label.setContentDescription(uiText(R.string.engine_systemnnn_ctl_page_count,page+1,pages));
        if(config.number("pageMaxPrintFlag",0)==0)return;
        String maximum=config.number("maxPageUpZeroPrintFlag",zero)!=0?digits(pages,places):Integer.toString(pages);
        int maxX=config.number("pageMaxPrintX",x+(places+1)*advance);
        if(config.number("pageMaxLeftAlign",0)==0)maxX+=Math.max(0,places-maximum.length())*advance;
        pageGlyphs(font,maximum,w,h,advance,maxX,config.number("pageMaxPrintY",y));
        if(config.number("slashPrintFlag",0)!=0)
            pageGlyphs(font,"/",w,h,advance,config.number("slashPrintX",x+places*advance),config.number("slashPrintY",y));
    }
    private View pageGlyphs(Bitmap font,String number,int w,int h,int advance,int x,int y){
        int width=w+(number.length()-1)*advance;
        View label;
        if(font!=null&&font.getWidth()>=w*(number.equals("/")?12:10)&&font.getHeight()>=h){
            label=new View(getContext()){
                private final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);
                @Override protected void onDraw(Canvas canvas){
                    canvas.save();canvas.scale(getWidth()/(float)width,getHeight()/(float)h);
                    glyphs(canvas,paint,font,number,0,0,w,h,advance);canvas.restore();
                }
            };
        }else label=text(number,Math.min(w,h),saving?0xffff6262:0xff6ad6ff);
        put(label,x,y,width,h);
        return label;
    }
    private void confirm(SaveSlots.Entry e){
        GameUiAssets.Config cfg=assets.save;
        GameUiPanel dialog=new GameUiPanel(getContext(),assets,null,click);confirmation=dialog;
        dialog.canvas(canvasWidth,canvasHeight);
        int x=cfg.number("dialogX",331),y=cfg.number("dialogY",277);
        dialog.picture(cfg.value("filenameDialog","ta_dialog_save"),x,y,cfg.number("dialogSizeX",618),cfg.number("dialogSizeY",143));
        dialog.sound(this::sound);
        dialog.buttonAt(cfg,"dialogOk","dialogOk",uiText(R.string.engine_systemnnn_ctl_overwrite),()->{dismissConfirmation();select.choose(e.slot);},x,y,90,60,192,72);
        dialog.buttonAt(cfg,"dialogCancel","dialogCancel",uiText(R.string.engine_systemnnn_ctl_cancel_overwrite),this::dismissConfirmation,x,y,323,60,192,72);
        addView(dialog,new LayoutParams(-1,-1));
    }
    private void dismissConfirmation(){
        removeView(confirmation);confirmation=null;
        if(refreshEntries){refreshEntries=false;render();}
    }
    private void beginLoad(SlotCard card,int slot){
        if(loadCard!=null)return;
        loadCard=card;loadClickAt=SystemClock.uptimeMillis();
        sound(config.number("loadSound",0)-1,-1);
        card.invalidate();
        pendingLoad=()->{pendingLoad=null;select.choose(slot);};
        postDelayed(pendingLoad,limit(config.number("clickTime",8),0,1000)*(long)frameMs);
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event){
        return pendingLoad!=null||super.dispatchTouchEvent(event);
    }
    @Override boolean backOverlay(){
        if(loadCard!=null)return true;
        if(confirmation==null)return false;
        click.run();dismissConfirmation();return true;
    }
    @Override void cancelLeave(){
        if(loadCard!=null){loadCard.invalidate();loadCard=null;}
        super.cancelLeave();
        if(refreshEntries){refreshEntries=false;render();}
    }
    @Override protected void onDetachedFromWindow(){
        if(pendingLoad!=null){removeCallbacks(pendingLoad);pendingLoad=null;}
        super.onDetachedFromWindow();
    }
    private static int limit(int v,int lo,int hi){return Math.min(hi,Math.max(lo,v));}
    private static String digits(int value,int digits){return String.format(Locale.ROOT,"%0"+digits+"d",value);}
    private Rect artworkSource(Integer number){
        if(artworkAtlas==null||number==null||number<artworkMinimum)return null;
        int width=artworkDestination.width(),height=artworkDestination.height();
        if(artworkColumns>0&&number<0)return null;
        long x=artworkColumns==0?0:(long)(number%artworkColumns)*width;
        long y=artworkColumns==0?0:(long)(number/artworkColumns)*height;
        if(x+width>artworkAtlas.getWidth()||y+height>artworkAtlas.getHeight())return null;
        return new Rect((int)x,(int)y,(int)x+width,(int)y+height);
    }
    private static boolean glyphs(Canvas c,Paint paint,Bitmap font,String value,int x,int y,int w,int h,int advance){
        if(font==null||w<=0||h<=0||font.getHeight()<h||font.getWidth()<w*10)return false;
        for(int i=0;i<value.length();i++){
            char ch=value.charAt(i);
            int glyph=ch==':'?10:ch=='/'?11:ch-'0';
            if(glyph>=0&&(glyph+1)*w<=font.getWidth()){
                int offset=glyph*w;
                c.drawBitmap(font,new Rect(offset,0,offset+w,h),new Rect(x,y,x+w,y+h),paint);
            }
            x+=advance;
        }
        return true;
    }
    private final class SlotCard extends View {
        private final SaveSlots.Entry e;
        private final Bitmap preview,base;
        private final Rect artworkTile;
        private final int serial;
        private final String time;
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        SlotCard(SaveSlots.Entry e,int serial){
            super(GameSlotsPanel.this.getContext());this.e=e;this.serial=serial;setSoundEffectsEnabled(false);
            time=e.exists?new SimpleDateFormat("yyyy/MM/dd HH:mm",Locale.ROOT).format(new Date(e.savedAt)):"";
            String kind=saving?"Save":"Load";
            String image=e.slot==SaveSlots.QUICK?data.value("filenameQuick"+kind+"Base","ta_quk_slot01"):
                data.value("filename"+kind+"Base","");
            base=data.number("basePrintFlag",1)!=0?sprite(image):null;
            preview=e.picture;
            artworkTile=artworkSource(e.artwork);
            setContentDescription(e.label(getContext())+" · "+(e.corrupt?uiText(R.string.engine_systemnnn_ctl_slot_unreadable):
                !e.exists?uiText(R.string.engine_systemnnn_ctl_slot_empty):time+" · "+
                uiText(R.string.engine_systemnnn_ctl_position,e.script,e.line)+" · "+e.speaker+" · "+e.displaySummary(getContext())));
        }
        @Override public CharSequence getAccessibilityClassName(){return "android.widget.Button";}
        @Override protected void drawableStateChanged(){super.drawableStateChanged();invalidate();}
        private int line(Canvas c,String s,int x,int y,int size,int color){
            p.setColor(color);p.setTextSize(size);
            int n=p.breakText(s,true,Math.max(1,cardWidth-x-6),null);
            if(n<s.length()&&n>0&&Character.isHighSurrogate(s.charAt(n-1)))n--;
            c.drawText(s,0,n,x,y-p.ascent(),p);return n;
        }
        private void picture(Canvas c,Bitmap bitmap,int x,int y,int w,int h){
            if(bitmap==null)return;
            p.setAlpha(255);c.drawBitmap(bitmap,null,new Rect(x,y,x+w,y+h),p);
        }
        private void timestamp(Canvas c){
            if(data.number("saveTimePrintFlag",1)==0)return;
            int x=data.number("saveTimePrintX",245),y=data.number("saveTimePrintY",11);
            int w=limit(data.number("saveTimeFontSizeX",20),1,512),h=limit(data.number("saveTimeFontSizeY",30),1,512);
            p.setAlpha(255);
            if(!glyphs(c,p,sprite(data.value("fileNameSaveTimeFont","")),time,x,y,w,h,limit(data.number("saveTimeFontNextX",w),1,512)))
                line(c,time,x,y,16,Color.WHITE);
        }
        private boolean serialNumber(Canvas c,boolean mustFit){
            if(data.number("serialPrintFlag",1)==0)return false;
            int x=data.number("serialPrintX",cardWidth-24),y=data.number("serialPrintY",5);
            int w=limit(data.number("serialFontSizeX",14),1,512),h=limit(data.number("serialFontSizeY",20),1,512);
            int advance=limit(data.number("serialFontNextX",w),1,512);
            String number=digits(serial,limit(data.number("serialNumberKeta",2),1,4));
            int width=w+(number.length()-1)*advance;
            if(mustFit&&(x<0||y<0||x>cardWidth-width||y>cardHeight-h))return false;
            p.setAlpha(255);
            return glyphs(c,p,sprite(data.value("fileNameSerialNumberFont","")),number,x,y,w,h,advance)||
                line(c,number,x,y,14,Color.WHITE)>0;
        }
        private void thumbnail(Canvas c){
            int px=data.number("picPrintX",6),py=data.number("picPrintY",8);
            int pw=limit(data.number("picSizeX",182),1,512),ph=limit(data.number("picSizeY",102),1,512);
            picture(c,preview,px,py,pw,ph);
        }
        private void contents(Canvas c){
            timestamp(c);
            if(e.slot!=SaveSlots.AUTO)serialNumber(c,false);
            int x=data.number("textPrintX",210),y=data.number("textPrintY",87),size=limit(data.number("textFontSize",16),8,40);
            int positionY=data.number("addPicPrintY",46);
            if(artworkTile==null){
                String position=uiText(R.string.engine_systemnnn_ctl_position,e.script,e.line);
                if(e.slot==SaveSlots.AUTO)position=e.label(getContext())+" · "+position;
                line(c,position,data.number("addPicPrintX",x),positionY,14,0xffb7cddb);
            }
            if(data.number("textPrintFlag",1)!=0){
                int color=Color.rgb(data.number("textColorR",128)&255,data.number("textColorG",224)&255,data.number("textColorB",224)&255);
                String body=e.displaySummary(getContext());
                String summary=e.speaker.isEmpty()?body:e.speaker+"　"+body;
                if(e.slot==SaveSlots.QUICK&&base!=null){
                    // The quick-slot plate already has a label in the comment box.
                    int small=Math.min(size,13),top=y-small-10;
                    if(top>=positionY+16)line(c,summary,x,top,small,color);
                    return;
                }
                int n=line(c,summary,x,y,size,color);
                if(n<summary.length()&&y+size*2<=cardHeight)line(c,summary.substring(n),x,y+size,size,color);
            }
        }
        @Override protected void onDraw(Canvas canvas){
            int save=canvas.save();
            canvas.scale(getWidth()/(float)cardWidth,getHeight()/(float)cardHeight);
            int clip=canvas.save();
            canvas.clipRect(0,0,cardWidth,cardHeight);
            if(base!=null)picture(canvas,base,0,0,base.getWidth(),base.getHeight());
            if(e.slot==SaveSlots.AUTO&&!e.exists||e.slot==SaveSlots.QUICK&&base==null)
                line(canvas,e.label(getContext()),17,38,20,saving?0xffffb7aa:0xffa4e9ff);
            if(saving&&base==null&&e.slot>=0&&!e.exists&&!e.corrupt&&!serialNumber(canvas,true)){
                int x=Math.min(17,cardWidth/4),y=Math.min(38,cardHeight/4);
                if(line(canvas,e.label(getContext()),x,y,Math.min(20,cardHeight-y),0xffffb7aa)==0){
                    p.setColor(0x99ffb7aa);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1);
                    canvas.drawRect(0,0,cardWidth,cardHeight,p);p.setStyle(Paint.Style.FILL);
                }
            }
            if(e.corrupt)line(canvas,uiText(R.string.engine_systemnnn_ctl_slot_unreadable),data.number("textPrintX",210),data.number("textPrintY",87),16,0xff91a9ba);
            else if(e.exists){
                thumbnail(canvas);
                if(artworkTile!=null){
                    canvas.restoreToCount(clip);
                    p.setAlpha(255);canvas.drawBitmap(artworkAtlas,artworkTile,artworkDestination,p);
                    clip=canvas.save();
                    canvas.clipRect(0,0,cardWidth,cardHeight);
                }
                contents(canvas);
            }
            int selectMode=data.number("selectPrintMode",1);
            boolean selected=loadCard==this;
            if(selected&&pendingLoad!=null)postInvalidateOnAnimation();
            if(isPressed()||selected&&(selectMode==2||((SystemClock.uptimeMillis()-loadClickAt)/frameMs&1)==1)){
                Bitmap cursor=sprite(data.value(saving?"filenameSaveCursor":"filenameLoadCursor",""));
                int x=data.number("selectPrintX",0),y=data.number("selectPrintY",0);
                int w=limit(data.number("selectSizeX",cardWidth),1,4096),h=limit(data.number("selectSizeY",cardHeight),1,4096);
                if(selectMode==0){
                    int alpha=255*limit(data.number("selectColorPercent",50),0,100)/100;
                    p.setColor(Color.argb(alpha,data.number("selectColorR",255)&255,data.number("selectColorG",255)&255,data.number("selectColorB",255)&255));
                    canvas.drawRect(x,y,x+w,y+h,p);
                }else if(cursor!=null&&w<=cursor.getWidth()&&h<=cursor.getHeight()){
                    int srcX=selected&&selectMode==2&&cursor.getWidth()>=2*w?w:0;
                    p.setAlpha(255);canvas.drawBitmap(cursor,new Rect(srcX,0,srcX+w,h),new Rect(x,y,x+w,y+h),p);
                }else{
                    p.setColor(0x99e69419);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(3);
                    canvas.drawRect(2,2,cardWidth-2,cardHeight-2,p);p.setStyle(Paint.Style.FILL);
                }
            }
            canvas.restoreToCount(clip);
            if(e.lastSaved&&lastSaveAtlas!=null){
                long frame=(SystemClock.uptimeMillis()-lastSaveSince)/frameMs;
                if(lastSaveAnimation.draw(canvas,lastSaveAtlas,lastSaveDestination,p,frame,255)&&lastSaveAnimation.active(frame))
                    postInvalidateOnAnimation();
            }
            canvas.restoreToCount(save);
        }
    }
}
