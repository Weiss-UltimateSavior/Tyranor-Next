package com.tyranor.runtime.systemnnn;

import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import com.core.engine.EngineUiText;
import com.core.engine.R;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Save and load slot artwork in the game's configured grid. */
final class GameSlotsPanel extends GameUiPanel {
    private final PorterDuffXfermode ADD=new PorterDuffXfermode(PorterDuff.Mode.ADD);
    interface Select { void choose(int slot); }
    static final class CursorAnime {
        final int pattern,type,speed;
        CursorAnime(int pattern,int type,int speed){
            this.pattern=clamp(pattern,0,256);
            this.type=type;
            this.speed=clamp(speed,1,10000);
        }
        static CursorAnime read(GameUiAssets.Config data,boolean saving,boolean exists,boolean clicked){
            String prefix=!saving?"load":exists?"overwrite":"save";
            int pattern0=data.number(prefix+"AnimePattern",0);
            int type0=data.number(prefix+"AnimeType",1);
            int speed0=data.number(prefix+"AnimeSpeed",1);
            if(!clicked)return new CursorAnime(pattern0,type0,speed0);
            return new CursorAnime(data.number(prefix+"AnimePattern2",pattern0),
                data.number(prefix+"AnimeType2",type0),data.number(prefix+"AnimeSpeed2",speed0));
        }
        static String[] cursorCandidates(GameUiAssets.Config data,int selectMode,boolean saving,boolean exists){
            String selectName=data.value("fileNameSelectPic","ta_sl_font");
            String saveCursor=data.value("fileNameSaveCursor",selectName);
            String loadCursor=data.value("fileNameLoadCursor",saveCursor);
            String overwriteCursor=data.value("fileNameOverwriteCursor",loadCursor);
            String primary=selectMode==1?selectName:"";
            String fallback=!saving?loadCursor:saveCursor;
            String overwrite=saving&&exists?overwriteCursor:"";
            return new String[]{primary,overwrite,fallback};
        }
        /** Returns {pic1, pic2, percent, activeFlag} matching CCommonDataFile::GetNowCursorPic and CalcuCursorAnime. */
        int[] frame(long elapsedTicks){
            if(pattern<=1)return new int[]{0,0,100,0};
            long ticks=Math.max(0L,elapsedTicks);
            boolean rev=type==3||type==4||type==7||type==8;
            boolean lim=type==2||type==4||type==6||type==8;
            int dv=rev?Math.max(1,(pattern-1)*2):pattern;
            long cycle=(long)dv*speed;
            long count=lim?Math.min(ticks,cycle):ticks%cycle;
            int pic1=(int)(count/speed),pic2=pic1+1;
            if(lim){
                int limData=rev?dv:dv-1;
                if(pic1>=dv)pic1=limData;
                if(pic2>=dv)pic2=limData;
            }
            pic2%=dv;
            if(rev){
                if(pic1>=pattern)pic1=(pattern-1)*2-pic1;
                if(pic2>=pattern)pic2=(pattern-1)*2-pic2;
            }
            int percent=type>=5?(int)(100L*(speed-(count%speed))/speed):100;
            return new int[]{pic1,pic2,percent,!lim||ticks<cycle?1:0};
        }
        private static int clamp(int v,int lo,int hi){return Math.min(hi,Math.max(lo,v));}
    }
    static final class GameDateLayout {
        static final class Glyph {
            final boolean weekSheet;
            final int cell,x,y,width,height;
            Glyph(boolean weekSheet,int cell,int x,int y,int width,int height){
                this.weekSheet=weekSheet;this.cell=cell;this.x=x;this.y=y;this.width=width;this.height=height;
            }
        }
        static final GameDateLayout DISABLED=new GameDateLayout(
            false,false,false,false,false,true,true,true,0,0,0,0,0,"","",16,16,16,0,0,0,0,0,0,16,16,16,0,0);
        final boolean enabled;
        final boolean yearPrint,monthPrint,dayPrint,weekPrint;
        final boolean yearUpZero,monthUpZero,dayUpZero;
        final int yearSlashFlag,monthSlashFlag,daySlashFlag;
        final int weekYouFlag,weekBiFlag;
        final String timeFont,weekFont;
        final int timeSizeX,timeSizeY,timeNextX;
        final int yearX,yearY,monthX,monthY,dayX,dayY;
        final int weekSizeX,weekSizeY,weekNextX,weekX,weekY;

        private GameDateLayout(boolean enabled,boolean yearPrint,boolean monthPrint,boolean dayPrint,boolean weekPrint,
                               boolean yearUpZero,boolean monthUpZero,boolean dayUpZero,
                               int yearSlashFlag,int monthSlashFlag,int daySlashFlag,int weekYouFlag,int weekBiFlag,
                               String timeFont,String weekFont,int timeSizeX,int timeSizeY,int timeNextX,
                               int yearX,int yearY,int monthX,int monthY,int dayX,int dayY,
                               int weekSizeX,int weekSizeY,int weekNextX,int weekX,int weekY){
            this.enabled=enabled;
            this.yearPrint=yearPrint;this.monthPrint=monthPrint;this.dayPrint=dayPrint;this.weekPrint=weekPrint;
            this.yearUpZero=yearUpZero;this.monthUpZero=monthUpZero;this.dayUpZero=dayUpZero;
            this.yearSlashFlag=yearSlashFlag;this.monthSlashFlag=monthSlashFlag;this.daySlashFlag=daySlashFlag;
            this.weekYouFlag=weekYouFlag;this.weekBiFlag=weekBiFlag;
            this.timeFont=timeFont;this.weekFont=weekFont;
            this.timeSizeX=timeSizeX;this.timeSizeY=timeSizeY;this.timeNextX=timeNextX;
            this.yearX=yearX;this.yearY=yearY;this.monthX=monthX;this.monthY=monthY;this.dayX=dayX;this.dayY=dayY;
            this.weekSizeX=weekSizeX;this.weekSizeY=weekSizeY;this.weekNextX=weekNextX;this.weekX=weekX;this.weekY=weekY;
        }

        static GameDateLayout read(GameUiAssets.Config data){
            int yearFlag=data.number("gameYearPrintFlag",0);
            int dateFlag=data.number("gameDatePrintFlag",0);
            if(yearFlag==0&&dateFlag==0)return DISABLED;
            int monthFlag=data.number("gameMonthPrintFlag",1);
            int dayFlag=data.number("gameDayPrintFlag",1);
            int weekFlag=data.number("gameWeekPrintFlag",1);
            int slashFlag=data.number("gameDateSlashPrintFlag",1);
            int yearSlash=data.number("gameYearSlashPrintFlag",yearFlag*2*slashFlag);
            int monthSlash=data.number("gameMonthSlashPrintFlag",monthFlag*2*slashFlag);
            int daySlash=data.number("gameDaySlashPrintFlag",0);
            int weekYou=data.number("gameWeekYouPrintFlag",1);
            int weekBi=data.number("gameWeekBiPrintFlag",2);
            boolean hasTime=yearFlag!=0||monthFlag!=0||dayFlag!=0;
            String timeFont=hasTime?data.value("fileNameGameTimeFont","ta_font"):"";
            int picSizeX=16,picSizeY=16,nextX=16;
            if(hasTime){
                picSizeX=clamp(data.number("gameTimeFontSizeX",16),1,512);
                picSizeY=clamp(data.number("gameTimeFontSizeY",16),1,512);
                nextX=clamp(data.number("gameTimeFontNextX",picSizeX),1,512);
            }
            int timeSizeX=picSizeX,timeSizeY=picSizeY,timeNextX=nextX;
            int baseX=clamp(data.number("gameTimePrintX",0),-4096,4096);
            int baseY=clamp(data.number("gameTimePrintY",0),-4096,4096);
            boolean yearUpZero=true;
            int yearX=baseX,yearY=baseY;
            if(yearFlag!=0){
                yearUpZero=data.number("gameYearUpZeroPrintFlag",1)!=0;
                yearX=clamp(data.number("gameYearPrintX",baseX),-4096,4096);
                yearY=clamp(data.number("gameYearPrintY",baseY),-4096,4096);
                baseX=yearX+timeNextX*5;
                baseY=yearY;
            }
            boolean monthUpZero=true;
            int monthX=baseX,monthY=baseY;
            if(monthFlag!=0){
                monthUpZero=data.number("gameMonthUpZeroPrintFlag",1)!=0;
                monthX=clamp(data.number("gameMonthPrintX",baseX),-4096,4096);
                monthY=clamp(data.number("gameMonthPrintY",baseY),-4096,4096);
                baseX=monthX+timeNextX*3;
                baseY=monthY;
            }
            boolean dayUpZero=true;
            int dayX=baseX,dayY=baseY;
            if(dayFlag!=0){
                dayUpZero=data.number("gameDayUpZeroPrintFlag",1)!=0;
                dayX=clamp(data.number("gameDayPrintX",baseX),-4096,4096);
                dayY=clamp(data.number("gameDayPrintY",baseY),-4096,4096);
                baseX=dayX+timeNextX*2+(daySlash!=0?timeNextX:0);
                baseY=dayY;
            }
            String weekFont=weekFlag!=0?data.value("fileNameGameWeekFont","ta_font"):"";
            int weekSizeX=picSizeX,weekSizeY=picSizeY,weekNextX=picSizeX;
            int weekX=baseX,weekY=baseY;
            if(weekFlag!=0){
                weekSizeX=clamp(data.number("gameWeekFontSizeX",picSizeX),1,512);
                weekSizeY=clamp(data.number("gameWeekFontSizeY",picSizeY),1,512);
                weekNextX=clamp(data.number("gameWeekFontNextX",weekSizeX),1,512);
                weekX=clamp(data.number("gameWeekPrintX",baseX),-4096,4096);
                weekY=clamp(data.number("gameWeekPrintY",baseY),-4096,4096);
            }
            return new GameDateLayout(true,yearFlag!=0,monthFlag!=0,dayFlag!=0,weekFlag!=0,
                yearUpZero,monthUpZero,dayUpZero,yearSlash,monthSlash,daySlash,weekYou,weekBi,
                timeFont,weekFont,timeSizeX,timeSizeY,timeNextX,yearX,yearY,monthX,monthY,dayX,dayY,
                weekSizeX,weekSizeY,weekNextX,weekX,weekY);
        }

        boolean hasTime(){return yearPrint||monthPrint||dayPrint;}

        boolean outside(int cardWidth,int cardHeight){
            if(!enabled)return false;
            if(yearPrint&&boxOutside(yearX,yearY,(yearSlashFlag!=0?4:3)*timeNextX+timeSizeX,timeSizeY,cardWidth,cardHeight))
                return true;
            if(monthPrint&&boxOutside(monthX,monthY,(monthSlashFlag!=0?2:1)*timeNextX+timeSizeX,timeSizeY,cardWidth,cardHeight))
                return true;
            if(dayPrint&&boxOutside(dayX,dayY,(daySlashFlag!=0?2:1)*timeNextX+timeSizeX,timeSizeY,cardWidth,cardHeight))
                return true;
            int weekCells=weekBiFlag!=0?2:weekYouFlag!=0?1:0;
            return weekPrint&&boxOutside(weekX,weekY,weekCells*weekNextX+weekSizeX,weekSizeY,cardWidth,cardHeight);
        }

        private static boolean boxOutside(int x,int y,int w,int h,int cardWidth,int cardHeight){
            return x<0||y<0||x+w>cardWidth||y+h>cardHeight;
        }

        List<Glyph> glyphs(PcStorySave.GameDate gd){
            if(!enabled||gd==null)return java.util.Collections.emptyList();
            List<Glyph> out=new ArrayList<>(12);
            if(yearPrint){
                appendSuuji(out,false,yearX,yearY,timeSizeX,timeSizeY,timeNextX,4,yearUpZero,gd.year);
                if(yearSlashFlag!=0&&yearSlashFlag-1+10>=0)
                    out.add(new Glyph(false,yearSlashFlag-1+10,yearX+timeNextX*4,yearY,timeSizeX,timeSizeY));
            }
            if(monthPrint){
                appendSuuji(out,false,monthX,monthY,timeSizeX,timeSizeY,timeNextX,2,monthUpZero,gd.month);
                if(monthSlashFlag!=0&&monthSlashFlag-1+10>=0)
                    out.add(new Glyph(false,monthSlashFlag-1+10,monthX+timeNextX*2,monthY,timeSizeX,timeSizeY));
            }
            if(dayPrint){
                appendSuuji(out,false,dayX,dayY,timeSizeX,timeSizeY,timeNextX,2,dayUpZero,gd.day);
                if(daySlashFlag!=0&&daySlashFlag-1+10>=0)
                    out.add(new Glyph(false,daySlashFlag-1+10,dayX+timeNextX*2,dayY,timeSizeX,timeSizeY));
            }
            if(weekPrint){
                appendSuuji(out,true,weekX,weekY,weekSizeX,weekSizeY,weekNextX,1,false,gd.week);
                if(weekYouFlag!=0&&weekYouFlag-1+10>=0)
                    out.add(new Glyph(true,weekYouFlag-1+10,weekX+weekNextX,weekY,weekSizeX,weekSizeY));
                if(weekBiFlag!=0&&weekBiFlag-1+10>=0)
                    out.add(new Glyph(true,weekBiFlag-1+10,weekX+weekNextX*2,weekY,weekSizeX,weekSizeY));
            }
            return out;
        }

        private static void appendSuuji(List<Glyph> out,boolean weekSheet,int printX,int printY,
                                        int sizeX,int sizeY,int nextX,int keta,boolean upZero,int dat){
            if(dat<0)return;
            int putX=printX+(keta-1)*nextX;
            for(int i=0;i<keta;i++){
                int d=dat%10;
                if(d!=0||i==0||dat>0||upZero)
                    out.add(new Glyph(weekSheet,d,putX,printY,sizeX,sizeY));
                putX-=nextX;
                dat/=10;
                if(dat==0&&!upZero)return;
            }
        }

        private static int clamp(int v,int lo,int hi){return Math.min(hi,Math.max(lo,v));}
    }
    private final boolean saving;
    private final Select select;
    private final Runnable close;
    private final GameUiAssets.Config config,data;
    private final SaveSlots.Layout layout;
    private final GameDateLayout gameDateLayout;
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
    private SlotCard selectedCard;
    private Runnable pendingSelect;
    private long clickAt;
    private boolean refreshEntries;
    GameSlotsPanel(Context c,GameUiAssets assets,boolean saving,SaveSlots.Entry[] slots,Integer lastSelected,Runnable click,Runnable close,Select select,
                   UiPreviewPool.Warmup warmup){
        super(c,assets,(saving?assets.save:assets.load).value("filenameBG",saving?"ta_savebg01":"ta_loadbg01"),click);
        this.saving=saving;this.select=select;this.close=close;
        config=saving?assets.save:assets.load;data=assets.dataFile;
        layout=new SaveSlots.Layout(config,assets.game,data,saving);
        gameDateLayout=GameDateLayout.read(data);
        page=layout.openingPcPage(lastSelected);
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
        int picX=data.number("picPrintX",0),picY=data.number("picPrintY",0);
        int picW=limit(data.number("picSizeX",cardWidth),1,4096),picH=limit(data.number("picSizeY",cardHeight),1,4096);
        int selectX=limit(data.number("selectPrintX",picX),-4096,4096),selectY=limit(data.number("selectPrintY",picY),-4096,4096);
        int selectW=limit(data.number("selectSizeX",picW),1,4096),selectH=limit(data.number("selectSizeY",picH),1,4096);
        boolean cursorOutside=selectX<0||selectY<0||selectX+selectW>cardWidth||selectY+selectH>cardHeight;
        boolean dateOutside=gameDateLayout.outside(cardWidth,cardHeight);
        // Original decorations may extend past the slot base, e.g. lastSavePrintY=-6 or selectPrintX=-1.
        if(artworkAtlas!=null||lastSaveAtlas!=null||cursorOutside||dateOutside)setClipChildren(false);
        opening(config,false);
        entries(slots,warmup);
    }
    static String baseImageName(GameUiAssets.Config data,boolean saving,boolean quick){
        if(data.number("basePrintFlag",0)==0)return "";
        if(data.number("baseChangeLoadSave",1)==0){
            String loadBase=data.value("filenameLoadBase","");
            return quick&&data.has("filenameQuickLoadBase")?data.value("filenameQuickLoadBase",""):loadBase;
        }
        String normal=data.value(saving?"filenameSaveBase":"filenameLoadBase","");
        String quickKey=saving?"filenameQuickSaveBase":"filenameQuickLoadBase";
        return quick&&data.has(quickKey)?data.value(quickKey,""):normal;
    }
    private String uiText(int resource,Object... args){
        return EngineUiText.localizedContext(getContext()).getString(resource,args);
    }
    @Override boolean controlsBusy(){return super.controlsBusy()||confirmation!=null||selectedCard!=null;}
    void entries(SaveSlots.Entry[] value){entries(value,null);}
    private void entries(SaveSlots.Entry[] value,UiPreviewPool.Warmup warmup){
        entries.clear();
        for(SaveSlots.Entry entry:value)entries.put(entry.slot,entry);
        if(confirmation==null&&selectedCard==null){lastSaveSince=SystemClock.uptimeMillis();render(warmup);}else refreshEntries=true;
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
            SaveSlots.Entry e=entries.get(index);
            if(e==null)continue;
            boolean quick=index==layout.quickPosition;
            SlotCard card=new SlotCard(e,index+1,quick);
            card.setEnabled((!quick||allowQuick)&&(saving||e.exists&&!e.corrupt));
            card.setOnClickListener(v->{
                if(!saving)beginSelect(card,e.slot);
                else if(e.exists&&config.number("warningPrintFlag",1)!=0){
                    sound(config.number("warningSound",0)-1,-1);confirm(card,e);
                }else beginSelect(card,e.slot);
            });
            put(card,x+(i%layout.columns)*dx,y+(i/layout.columns)*dy,cardWidth,cardHeight);
        }
        if(layout.pageMode==1)pageArrows(config,page,layout.pages,616,16,646,this::showPage);
        else if(layout.pageMode==2)pageTabs();
        if(config.number("pagePrintFlag",0)!=0)pageNumber(layout.pages);
        back(config,close);
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
        if(label!=null)label.setContentDescription(uiText(R.string.engine_systemnnn_ctl_page_count,page+1,pages));
        if(config.number("pageMaxPrintFlag",0)==0)return;
        String maximum=config.number("maxPageUpZeroPrintFlag",zero)!=0?digits(pages,places):Integer.toString(pages);
        int maxX=config.number("pageMaxPrintX",x+(places+1)*advance);
        if(config.number("pageMaxLeftAlign",0)==0)maxX+=Math.max(0,places-maximum.length())*advance;
        pageGlyphs(font,maximum,w,h,advance,maxX,config.number("pageMaxPrintY",y));
        if(config.number("slashPrintFlag",0)!=0)
            pageGlyphs(font,"/",w,h,advance,config.number("slashPrintX",x+places*advance),config.number("slashPrintY",y));
    }
    private View pageGlyphs(Bitmap font,String number,int w,int h,int advance,int x,int y){
        if(font==null||font.getWidth()<w*(number.equals("/")?12:10)||font.getHeight()<h)return null;
        int width=w+(number.length()-1)*advance;
        View label=new View(getContext()){
            private final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);
            @Override protected void onDraw(Canvas canvas){
                canvas.save();canvas.scale(getWidth()/(float)width,getHeight()/(float)h);
                glyphs(canvas,paint,font,number,0,0,w,h,advance);canvas.restore();
            }
        };
        put(label,x,y,width,h);
        return label;
    }
    private void confirm(SlotCard card,SaveSlots.Entry e){
        GameUiAssets.Config cfg=assets.save;
        GameUiPanel dialog=new GameUiPanel(getContext(),assets,null,click);confirmation=dialog;
        dialog.canvas(canvasWidth,canvasHeight);
        int x=cfg.number("dialogX",331),y=cfg.number("dialogY",277);
        dialog.picture(cfg.value("filenameDialog","ta_dialog_save"),x,y,cfg.number("dialogSizeX",618),cfg.number("dialogSizeY",143));
        dialog.sound(this::sound);
        dialog.buttonAt(cfg,"dialogOk","dialogOk",uiText(R.string.engine_systemnnn_ctl_overwrite),()->{removeView(confirmation);confirmation=null;beginSelect(card,e.slot);},x,y,90,60,192,72);
        dialog.buttonAt(cfg,"dialogCancel","dialogCancel",uiText(R.string.engine_systemnnn_ctl_cancel_overwrite),this::dismissConfirmation,x,y,323,60,192,72);
        addView(dialog,new LayoutParams(-1,-1));
    }
    private void dismissConfirmation(){
        removeView(confirmation);confirmation=null;
        if(refreshEntries&&selectedCard==null){refreshEntries=false;lastSaveSince=SystemClock.uptimeMillis();render();}
    }
    private void beginSelect(SlotCard card,int slot){
        if(selectedCard!=null)return;
        selectedCard=card;clickAt=SystemClock.uptimeMillis();
        if(!saving)sound(config.number("loadSound",0)-1,-1);
        card.invalidate();
        pendingSelect=()->{
            pendingSelect=null;
            if(saving){
                SlotCard done=selectedCard;selectedCard=null;
                if(done!=null)done.invalidate();
                if(refreshEntries){refreshEntries=false;lastSaveSince=SystemClock.uptimeMillis();render();}
            }
            select.choose(slot);
        };
        postDelayed(pendingSelect,limit(config.number("clickTime",8),0,1000)*(long)frameMs);
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event){
        return pendingSelect!=null||super.dispatchTouchEvent(event);
    }
    @Override boolean backOverlay(){
        if(selectedCard!=null)return true;
        if(confirmation==null)return false;
        click.run();dismissConfirmation();return true;
    }
    @Override void cancelLeave(){
        if(selectedCard!=null){selectedCard.invalidate();selectedCard=null;}
        super.cancelLeave();
        if(refreshEntries){refreshEntries=false;lastSaveSince=SystemClock.uptimeMillis();render();}
    }
    @Override protected void onDetachedFromWindow(){
        if(pendingSelect!=null){removeCallbacks(pendingSelect);pendingSelect=null;}
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
        private final boolean quick;
        private final String time,summary,label;
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        private final Rect cursorSrc=new Rect(),cursorDest=new Rect();
        private final RectF cursorDestF=new RectF();
        private int cursorState;
        private long cursorSince;
        SlotCard(SaveSlots.Entry e,int serial,boolean quick){
            super(GameSlotsPanel.this.getContext());this.e=e;this.serial=serial;setSoundEffectsEnabled(false);
            time=e.exists?new SimpleDateFormat("yyyy/MM/dd HH:mm",Locale.ROOT).format(new Date(e.savedAt)):"";
            this.quick=quick;
            label=quick?SaveSlots.labelFor(getContext(),SaveSlots.QUICK):e.label(getContext());
            String image=baseImageName(data,saving,quick);
            base=image.isEmpty()?null:sprite(image);
            preview=e.picture;
            artworkTile=artworkSource(e.artwork);
            summary=e.displaySummary(getContext());
            setContentDescription(label+" · "+(e.corrupt?uiText(R.string.engine_systemnnn_ctl_slot_unreadable):
                !e.exists?uiText(R.string.engine_systemnnn_ctl_slot_empty):time+(summary.isEmpty()?"":" · "+summary)));
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
            int x=data.number("saveTimePrintX",0),y=data.number("saveTimePrintY",0);
            int w=limit(data.number("saveTimeFontSizeX",10),1,512),h=limit(data.number("saveTimeFontSizeY",10),1,512);
            p.setAlpha(255);
            glyphs(c,p,sprite(data.value("fileNameSaveTimeFont","ta_sl_font")),time,x,y,w,h,limit(data.number("saveTimeFontNextX",w),1,512));
        }
        private void serialNumber(Canvas c){
            if(data.number("serialPrintFlag",0)==0)return;
            int x=data.number("serialPrintX",0),y=data.number("serialPrintY",0);
            int w=limit(data.number("serialFontSizeX",12),1,512),h=limit(data.number("serialFontSizeY",12),1,512);
            int advance=limit(data.number("serialFontNextX",w),1,512);
            int keta=limit(data.number("serialNumberKeta",2),1,4);
            boolean upZero=data.number("logPrintFlag",0)!=0&&data.number("logSerialUpZeroPrintFlag",0)!=0;
            String number=upZero?digits(serial,keta):Integer.toString(serial);
            int drawX=upZero?x:x+Math.max(0,keta-number.length())*advance;
            p.setAlpha(255);
            glyphs(c,p,sprite(data.value("fileNameSerialNumberFont","ta_sl_font")),number,drawX,y,w,h,advance);
        }
        private void thumbnail(Canvas c){
            int px=data.number("picPrintX",0),py=data.number("picPrintY",0);
            int pw=limit(data.number("picSizeX",cardWidth),1,4096),ph=limit(data.number("picSizeY",cardHeight),1,4096);
            picture(c,preview,px,py,pw,ph);
        }
        private void corruptError(Canvas c){
            int ex=data.number("errorPrintX",0),ey=data.number("errorPrintY",0);
            int ox=data.number("oldPrintX",ex),oy=data.number("oldPrintY",ey);
            int bx=data.number("badPrintX",ox),by=data.number("badPrintY",oy);
            Bitmap err=sprite(data.value("fileNameError",""));
            if(err!=null){picture(c,err,ex,ey,err.getWidth(),err.getHeight());return;}
            Bitmap bad=sprite(data.value("fileNameBad",""));
            if(bad!=null)picture(c,bad,bx,by,bad.getWidth(),bad.getHeight());
        }
        private void drawGameDate(Canvas c,PcStorySave.GameDate gd){
            Bitmap timePic=gameDateLayout.hasTime()&&!gameDateLayout.timeFont.isEmpty()?sprite(gameDateLayout.timeFont):null;
            Bitmap weekPic=gameDateLayout.weekPrint&&!gameDateLayout.weekFont.isEmpty()?sprite(gameDateLayout.weekFont):null;
            if(timePic==null&&weekPic==null)return;
            p.setAlpha(255);
            for(GameDateLayout.Glyph g:gameDateLayout.glyphs(gd)){
                Bitmap sheet=g.weekSheet?weekPic:timePic;
                if(sheet!=null&&g.cell>=0&&(long)(g.cell+1)*g.width<=sheet.getWidth()&&g.height<=sheet.getHeight()){
                    int srcX=g.cell*g.width;
                    c.drawBitmap(sheet,new Rect(srcX,0,srcX+g.width,g.height),
                        new Rect(g.x,g.y,g.x+g.width,g.y+g.height),p);
                }
            }
        }
        private int contents(Canvas c,int clip){
            timestamp(c);
            serialNumber(c);
            if(gameDateLayout.enabled&&e.gameDate!=null){
                c.restoreToCount(clip);
                drawGameDate(c,e.gameDate);
                clip=c.save();
                c.clipRect(0,0,cardWidth,cardHeight);
            }
            if(data.number("textPrintFlag",0)!=0&&!summary.isEmpty()){
                int x=data.number("textPrintX",0),y=data.number("textPrintY",0),size=limit(data.number("textFontSize",12),8,64);
                int nextY=data.number("textNextY",30),lines=limit(data.number("textPrintNumber",1),1,4);
                int color=Color.rgb(limit(data.number("textColorR",255),0,255),
                    limit(data.number("textColorG",255),0,255),limit(data.number("textColorB",255),0,255));
                String rem=summary;
                for(int i=0;i<lines&&!rem.isEmpty();i++){
                    int atY=y+i*nextY;
                    if(atY+size>cardHeight&&i>0)break;
                    int n=line(c,rem,x,atY,size,color);
                    if(n<=0)break;
                    rem=rem.substring(n);
                }
            }
            return clip;
        }
        @Override protected void onDraw(Canvas canvas){
            int save=canvas.save();
            canvas.scale(getWidth()/(float)cardWidth,getHeight()/(float)cardHeight);
            int clip=canvas.save();
            canvas.clipRect(0,0,cardWidth,cardHeight);
            if(base!=null)picture(canvas,base,0,0,base.getWidth(),base.getHeight());
            if(e.corrupt)corruptError(canvas);
            else if(e.exists){
                thumbnail(canvas);
                if(artworkTile!=null){
                    canvas.restoreToCount(clip);
                    p.setAlpha(255);canvas.drawBitmap(artworkAtlas,artworkTile,artworkDestination,p);
                    clip=canvas.save();
                    canvas.clipRect(0,0,cardWidth,cardHeight);
                }
                clip=contents(canvas,clip);
            }
            canvas.restoreToCount(clip);
            long now=SystemClock.uptimeMillis();
            drawCursor(canvas,now);
            if(e.lastSaved&&lastSaveAtlas!=null){
                long frame=(now-lastSaveSince)/frameMs;
                if(lastSaveAnimation.draw(canvas,lastSaveAtlas,lastSaveDestination,p,frame,255)&&lastSaveAnimation.active(frame))
                    postInvalidateOnAnimation();
            }
            canvas.restoreToCount(save);
        }
        private Bitmap resolveCursor(int selectMode){
            for(String candidate:CursorAnime.cursorCandidates(data,selectMode,saving,e.exists)){
                if(!candidate.isEmpty()){
                    Bitmap pic=sprite(candidate);
                    if(pic!=null)return pic;
                }
            }
            return null;
        }
        private void drawCursor(Canvas canvas,long now){
            int selectMode=data.number("selectPrintMode",0);
            boolean selected=selectedCard==this;
            boolean focused=isEnabled()&&(selected||isPressed()||isHovered());
            int state=!focused?0:selected?2:1;
            if(state!=cursorState){cursorState=state;cursorSince=now;}
            if(selected&&pendingSelect!=null&&selectMode!=2)postInvalidateOnAnimation();
            if(state==0||(state==2&&selectMode!=2&&((now-clickAt)/frameMs&1)==0))return;
            int picX=data.number("picPrintX",0),picY=data.number("picPrintY",0);
            int picW=limit(data.number("picSizeX",cardWidth),1,4096),picH=limit(data.number("picSizeY",cardHeight),1,4096);
            int x=limit(data.number("selectPrintX",picX),-4096,4096),y=limit(data.number("selectPrintY",picY),-4096,4096);
            int w=limit(data.number("selectSizeX",picW),1,4096),h=limit(data.number("selectSizeY",picH),1,4096);
            if(selectMode==0){
                int alpha=255*limit(data.number("selectPercent",data.number("selectColorPercent",50)),0,100)/100;
                p.setColor(Color.argb(alpha,limit(data.number("selectColorR",230),0,255),
                    limit(data.number("selectColorG",0),0,255),limit(data.number("selectColorB",255),0,255)));
                canvas.drawRect(x,y,x+w,y+h,p);
                return;
            }
            Bitmap cursor=resolveCursor(selectMode);
            if(cursor==null||w>cursor.getWidth()||h>cursor.getHeight())return;
            int srcX=selected&&selectMode==2&&cursor.getWidth()>=2L*w?w:0;
            CursorAnime anime=selectMode==2?CursorAnime.read(data,saving,e.exists,selected):null;
            cursorDest.set(x,y,x+w,y+h);
            if(anime!=null&&anime.pattern>1&&cursor.getHeight()>=(long)anime.pattern*h){
                long ticks=Math.max(0L,(selected?now-clickAt:now-cursorSince)/frameMs);
                int[] f=anime.frame(ticks);
                if(f[2]>=100||f[0]==f[1]){
                    cursorSrc.set(srcX,f[0]*h,srcX+w,(f[0]+1)*h);
                    p.setAlpha(255);canvas.drawBitmap(cursor,cursorSrc,cursorDest,p);
                }else if(f[2]<=0){
                    cursorSrc.set(srcX,f[1]*h,srcX+w,(f[1]+1)*h);
                    p.setAlpha(255);canvas.drawBitmap(cursor,cursorSrc,cursorDest,p);
                }else{
                    int alpha1=Math.round(255f*f[2]/100f);
                    cursorDestF.set(x,y,x+w,y+h);
                    int layer=canvas.saveLayer(cursorDestF,null);
                    cursorSrc.set(srcX,f[0]*h,srcX+w,(f[0]+1)*h);
                    p.setAlpha(alpha1);canvas.drawBitmap(cursor,cursorSrc,cursorDest,p);
                    cursorSrc.set(srcX,f[1]*h,srcX+w,(f[1]+1)*h);
                    p.setAlpha(255-alpha1);p.setXfermode(ADD);canvas.drawBitmap(cursor,cursorSrc,cursorDest,p);
                    p.setXfermode(null);p.setAlpha(255);
                    canvas.restoreToCount(layer);
                }
                if(f[3]!=0&&(!selected||pendingSelect!=null))postInvalidateOnAnimation();
            }else{
                cursorSrc.set(srcX,0,srcX+w,h);
                p.setAlpha(255);canvas.drawBitmap(cursor,cursorSrc,cursorDest,p);
            }
        }
    }
}
