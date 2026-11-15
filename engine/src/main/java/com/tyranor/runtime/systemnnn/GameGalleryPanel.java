package com.tyranor.runtime.systemnnn;

import com.core.engine.R;
import com.core.engine.EngineUiText;

import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.*;
import java.util.BitSet;
import java.util.Locale;
import java.util.function.Consumer;

/** Presents the current original gallery screen in the game's logical coordinates. */
final class GameGalleryPanel extends GameUiPanel {
    private static final PorterDuffXfermode ADD=new PorterDuffXfermode(PorterDuff.Mode.ADD);
    interface Actions {
        void open(GalleryCatalog.Screen screen,int group,int page,int index);
        void mode(int mode);
        void miniGame(int index);
        void cgFrame(GalleryCatalog.Page page,int frame,Consumer<Bitmap> done);
        void back();
        void replay(int group,int index);
        void movie(int index);
        void play(int index);
        void moveSong(int direction);
        void pause();
        void stop();
        void volume(int volume);
    }
    private GalleryCatalog.Page page;
    private final Actions actions;
    private SpectrumView spectrum;
    private CgView cgView;
    private MusicCursor musicCursor;
    private boolean foreground=true;

    GameGalleryPanel(Context context,GameUiAssets assets,GalleryCatalog.Page page,Runnable click,Actions actions,
                     int width,int height) {
        super(context,assets,page.background,click);
        this.actions=actions;
        canvas(width,height);
        update(page);
    }
    void spectrum(int[] bands){if(spectrum!=null&&page.musicState==1)spectrum.update(bands);}
    @Override void uiActive(boolean active){
        super.uiActive(active);
        foreground=active;
        if(cgView!=null)cgView.foregroundChanged();
        if(musicCursor!=null)musicCursor.playing(active&&page.musicState==1);
    }
    GalleryCatalog.Screen screen() { return page.screen; }
    @Override Bitmap sprite(String name) {
        if (name==null) return null;
        Bitmap picture=page.images.get(GameUiAssets.systemImageKey(name));
        return picture!=null?picture:super.sprite(name);
    }
    void update(GalleryCatalog.Page value) {
        if(page==null||page.screen!=value.screen){
            opening(value.config,false);
            musicCursor=null;
        }
        page=value;
        setClipChildren(page.screen!=GalleryCatalog.Screen.CG);
        background(page.background);
        clearControls();
        cgView=null;
        uiBackground(page.config,page.screen.mode,page.page+1);
        setOnClickListener(null);
        switch (page.screen) {
        case OMAKE: menu(); break;
        case CG_GROUP: case SCENE_GROUP: groups(); break;
        case CG: case SCENE: grid(); break;
        case MOVIE: movies(); break;
        case VIEW: viewer(); return;
        case MUSIC: music(); break;
        }
        if (page.screen!=GalleryCatalog.Screen.OMAKE) back(page.config,actions::back);
    }
    private void menu() {
        GameUiAssets.Config cfg=page.config;
        menuButton(1,hostText(R.string.engine_systemnnn_ctl_gallery_cg),page.cgAvailable,() -> actions.open(page.cgGroups?GalleryCatalog.Screen.CG_GROUP:GalleryCatalog.Screen.CG,0,0,-1));
        menuButton(2,hostText(R.string.engine_systemnnn_ctl_gallery_scene),page.sceneAvailable,() -> actions.open(page.sceneGroups?GalleryCatalog.Screen.SCENE_GROUP:GalleryCatalog.Screen.SCENE,0,0,-1));
        menuButton(3,hostText(R.string.engine_systemnnn_ctl_gallery_music),page.musicAvailable,() -> actions.open(GalleryCatalog.Screen.MUSIC,0,0,-1));
        if (cfg.has("menu1Button4FileName")) menuButton(4,hostText(R.string.engine_systemnnn_host_title),true,actions::back);
        else back(cfg,actions::back);
        if (page.movieAvailable && cfg.number("basicButtonNumber",4)>=5 && cfg.number("menu1Button5ExistFlag",1)!=0)
            menuButton(5,hostText(R.string.engine_systemnnn_ctl_gallery_movie),cfg.number("menu1Button5EnableFlag",1)!=0,()->actions.open(GalleryCatalog.Screen.MOVIE,0,0,-1));
        for(MenuModeButton entry:page.modeButtons){
            GameButton.Spec spec=new GameButton.Spec(entry.setup);
            animatedButton(spec,sprite(entry.setup.art.image),entry.label.isEmpty()?MenuModeButton.fallbackLabel(getContext(),entry.mode):entry.label,()->sound(spec.sound,spec.voice),
                ()->actions.mode(entry.mode),entry.x,entry.y,entry.setup.width,entry.setup.height).setEnabled(entry.enabled);
        }
        for(GameMiniGames.Entry entry:page.miniGames){
            if(entry.spec==null){
                View area=new View(getContext());area.setOnClickListener(v->actions.miniGame(entry.index));
                put(area,entry.x,entry.y,entry.w,entry.h);
            }else animatedButton(entry.spec,entry.image,EngineUiText.get(getContext(),R.string.engine_systemnnn_ctl_title_minigame),
                ()->sound(entry.spec.sound,entry.spec.voice),()->actions.miniGame(entry.index),entry.x,entry.y,entry.w,entry.h);
        }
    }
    private void menuButton(int index,String label,boolean enabled,Runnable action) {
        int x=Math.max(0,canvasWidth*(index==4?4:index*2-1)/10);
        button(page.config,"menu1Button"+index,"menu1Button1",label,action,x,
            canvasHeight*(index==4?3:2)/4,216,52).setEnabled(enabled);
    }
    private void groups() {
        GameUiAssets.Config cfg=page.config;
        int w=dimension(cfg,"sizeX",200),h=dimension(cfg,"sizeY",100);
        int cols=Math.max(1,cfg.number("numberX",page.cells.size())),picCols=Math.max(1,cfg.number("picNumberX",cols));
        Bitmap buttons=sprite(cfg.value("filenameButton",""));
        for (GalleryCatalog.Cell cell:page.cells) {
            int i=cell.index;
            int x=cfg.number("baseX",canvasWidth/6)+(i%cols)*cfg.number("baseNextX",w);
            int y=cfg.number("baseY",canvasHeight/3)+(i/cols)*cfg.number("baseNextY",h);
            if (cfg.number("customPrintFlag",0)!=0) {
                x=cfg.number("printX"+(i+1),x); y=cfg.number("printY"+(i+1),y);
            }
            int sx=(i%picCols)*w,sy=(i/picCols)*h;
            Tile button=new Tile(cell,buttons,new Rect(sx,sy,sx+w,sy+h),null,null,100);
            button.setOnClickListener(v -> {
                click.run();
                actions.open(page.screen==GalleryCatalog.Screen.CG_GROUP?GalleryCatalog.Screen.CG:GalleryCatalog.Screen.SCENE,i,0,-1);
            });
            put(button,x,y,w,h);
        }
    }
    private void grid() {
        GameUiAssets.Config cfg=page.config;
        boolean cg=page.screen==GalleryCatalog.Screen.CG;
        int w=dimension(cfg,"sizeX",133),h=dimension(cfg,"sizeY",100);
        int x=cfg.number("printX",85),y=cfg.number("printY",113);
        int dx=cfg.number("nextX",166),dy=cfg.number("nextY",124);
        Bitmap frame=cfg.number("futaPrintFlag",1)!=0?sprite(cfg.value("fileNameFuta","ta_futa1")):null;
        Bitmap cursor=cfg.number("selectPrintMode",1)==2?sprite(cfg.value("fileNameCursor","ta_cg_cur")):null;
        Bitmap animationBadge=cg&&cfg.number("animePrintFlag",0)!=0?sprite(cfg.value("fileNameAnime","ta_futa1")):null;
        int dark=bounded(cfg.number("picMustPrintPercent",0),-100,100);
        Bitmap differenceFont=null;
        if(cg){
            String font=GalleryCatalog.differenceFont(cfg);
            differenceFont=font.isEmpty()?null:sprite(font);
            if(differenceFont==null&&!font.isEmpty())Log.i("SystemNNN","Difference font unavailable, omitting counter: "+font);
            else if(differenceFont!=null&&12L*dimension(cfg,"differenceFontSizeX",21)>differenceFont.getWidth())
                Log.w("SystemNNN","Difference font too narrow for 12 glyphs: "+font);
        }
        for (int i=0;i<page.cells.size();i++) {
            GalleryCatalog.Cell cell=page.cells.get(i);
            Tile tile=new Tile(cell,sprite(cell.picture),null,frame,cursor,dark);
            if(cell.animated)tile.animationBadge=animationBadge;
            tile.differenceFont=differenceFont;
            tile.setOnClickListener(v -> {
                click.run();
                if (cg) actions.open(GalleryCatalog.Screen.VIEW,page.group,0,cell.index);
                else actions.replay(page.group,cell.index);
            });
            tile.setEnabled(cell.enabled);
            put(tile,x+(i%page.columns)*dx,y+(i/page.columns)*dy,w,h);
        }
        if (cfg.number("totalPercentPrintFlag",1)!=0)
            totalPercent(cfg.value("fileNameTotalPercent","ta_font_cg1"));
        pageButtons();
        if (cfg.number("pagePrintFlag",0)!=0) pagePrint("ta_font_sl3");
    }
    private void totalPercent(String filename) {
        GameUiAssets.Config cfg=page.config;
        int w=dimension(cfg,"totalPercentFontSizeX",16),h=dimension(cfg,"totalPercentFontSizeY",22);
        int step=cfg.number("totalPercentFontNextX",w);
        int at=cfg.number("totalPercentPrintX",688),top=cfg.number("totalPercentPrintY",25);
        Bitmap font=sprite(filename);
        if (font==null || h>font.getHeight()) return;
        String digitsText=Integer.toString(Math.max(0,page.percent));
        int numAt=at+Math.max(0,3-digitsText.length())*step;
        for (int i=0;i<digitsText.length();i++)
            putGlyph(font,digitsText.charAt(i)-'0',numAt+i*step,top,w,h);
        putGlyph(font,12,at+3*step,top,w,h);
    }
    private void pagePrint(String defaultFont) {
        GameUiAssets.Config cfg=page.config;
        int keta=bounded(cfg.number("pageKeta",2),1,4);
        int zero=cfg.number("pageUpZeroPrintFlag",0);
        int w=dimension(cfg,"pageFontSizeX",21),h=dimension(cfg,"pageFontSizeY",27);
        int step=dimension(cfg,"pageFontNextX",w);
        int x=cfg.number("pagePrintX",0),y=cfg.number("pagePrintY",0);
        Bitmap font=sprite(cfg.value("fileNamePageFont",defaultFont));
        if (font==null || h>font.getHeight()) return;
        String cur=zero!=0?String.format(Locale.ROOT,"%0"+keta+"d",page.page+1):Integer.toString(page.page+1);
        int curX=x+Math.max(0,keta-cur.length())*step;
        for (int i=0;i<cur.length();i++) putGlyph(font,cur.charAt(i)-'0',curX+i*step,y,w,h);
        if (cfg.number("pageMaxPrintFlag",0)==0) return;
        String max=cfg.number("maxPageUpZeroPrintFlag",zero)!=0
            ?String.format(Locale.ROOT,"%0"+keta+"d",page.pages):Integer.toString(page.pages);
        int maxX=cfg.number("pageMaxPrintX",x+(keta+1)*step);
        if (cfg.number("pageMaxLeftAlign",0)==0) maxX+=Math.max(0,keta-max.length())*step;
        int maxY=cfg.number("pageMaxPrintY",y);
        for (int i=0;i<max.length();i++) putGlyph(font,max.charAt(i)-'0',maxX+i*step,maxY,w,h);
        if (cfg.number("slashPrintFlag",0)!=0)
            putGlyph(font,11,cfg.number("slashPrintX",x+keta*step),cfg.number("slashPrintY",y),w,h);
    }
    private void putGlyph(Bitmap font,int cell,int x,int y,int w,int h) {
        if (font==null || cell<0 || (long)(cell+1)*w>font.getWidth() || h>font.getHeight()) return;
        Rect source=new Rect(cell*w,0,(cell+1)*w,h);
        Tile digit=new Tile(new GalleryCatalog.Cell(cell,"","","",true),font,source,null,null,100);
        digit.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        put(digit,x,y,w,h);
    }
    private void pageButtons() {
        pageArrows(page.config,page.page,page.pages,canvasWidth/2-24,16,canvasHeight-72,
            next->actions.open(page.screen,page.group,next,page.index));
    }
    private void movies(){
        GameUiAssets.Config cfg=page.config;
        int w=dimension(cfg,"sizeX",133),h=dimension(cfg,"sizeY",100);
        int x=cfg.number("printX",85),y=cfg.number("printY",113);
        int dx=cfg.number("nextX",166),dy=cfg.number("nextY",124);
        Bitmap frame=sprite(cfg.value("filenameFuta",""));
        Bitmap cursor=cfg.number("selectPrintMode",1)==2?sprite(cfg.value("fileNameCursor","ta_movie_cur")):null;
        for(int i=0;i<page.cells.size();i++){
            GalleryCatalog.Cell cell=page.cells.get(i);
            Tile tile=new Tile(cell,sprite(cell.picture),null,frame,cursor,100);
            tile.setEnabled(cell.enabled);
            tile.setOnClickListener(v->{
                if(!cell.enabled||!tile.startMovieSelection())return;
                sound(cfg.number("selectSound",1)-1,-1);
            });
            put(tile,x+(i%page.columns)*dx,y+(i/page.columns)*dy,w,h);
        }
        pageButtons();
        if(cfg.number("totalPercentPrintFlag",1)!=0)
            totalPercent(cfg.value("fileNameTotalPercent","ta_font_movie1"));
        if(cfg.number("pagePrintFlag",0)!=0)
            pagePrint("ta_font_sl3");
    }
    private void turn(int direction) {
        actions.open(page.screen,page.group,Math.floorMod(page.page+direction,page.pages),page.index);
    }
    private void viewer() {
        cgView=new CgView(page);
        cgView.setContentDescription("CG · "+(page.page+1)+" / "+page.pages);
        cgView.setSoundEffectsEnabled(false);
        cgView.setOnClickListener(v -> {
            if (page.page+1==page.pages) actions.back();
            else {sound(page.config.number("nextSound",1)-1,-1);turn(1);}
        });
        put(cgView,0,0,canvasWidth,canvasHeight);
    }
    private static final class CgPan {
        final int distance,direction,time,wait;
        CgPan(GalleryCatalog.ScrollAxis axis,GameUiAssets.Config config,String name,int distance){
            this.distance=distance;
            direction=axis.direction>=0?axis.direction:config.number("scrollDirection"+name,0);
            time=axis.time>0?axis.time:Math.max(1,config.number("scrollTime"+name,30));
            wait=axis.wait>=0?axis.wait:Math.max(0,config.number("scrollWait"+name,10));
        }
        boolean moving(long tick){return distance>0&&tick<(long)wait+time;}
        float offset(long tick){
            if(distance<=0)return direction==0?0:distance;
            float progress=Math.min(1,Math.max(0,tick-wait)/(float)time);
            return distance*(direction==0?1-progress:progress);
        }
    }
    private final class CgView extends View {
        private final GalleryCatalog.Page viewPage;
        private final GalleryCatalog.CgImage data;
        private final CgPan panX,panY;
        private Bitmap image,buffered;
        private final RectF destination=new RectF();
        private final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);
        private final int tileWidth,tileHeight,slop;
        private final BitSet missing=new BitSet();
        private int visibleFrame,bufferedFrame=-1,requestFrame=-1;
        private long elapsed,resumedAt=-1,requestSerial;
        private float x,y,downX,downY,originX,originY;
        private boolean manual,moving;
        CgView(GalleryCatalog.Page page){
            super(GameGalleryPanel.this.getContext());
            viewPage=page;data=page.cg;
            image=data.tiles.get(0);visibleFrame=data.firstFrame;
            if(data.animated&&visibleFrame<0)missing.set(data.frameAt(0));
            tileWidth=image.getWidth();tileHeight=image.getHeight();
            int width=0,height=0;
            for(int i=0;i<data.columns;i++)width+=data.tiles.get(i).getWidth();
            for(int i=0;i<data.tiles.size();i+=data.columns)height+=data.tiles.get(i).getHeight();
            panX=new CgPan(data.variant.x,page.config,"X",data.animated?0:width-canvasWidth);
            panY=new CgPan(data.variant.y,page.config,"Y",data.animated?0:height-canvasHeight);
            slop=ViewConfiguration.get(getContext()).getScaledTouchSlop();
        }
        private void stopClock(long now){
            if(resumedAt>=0){elapsed+=now-resumedAt;resumedAt=-1;}
        }
        private void cancelFrameRequest(){
            requestSerial++;
            requestFrame=-1;
        }
        void foregroundChanged(){
            if(!foreground){stopClock(SystemClock.uptimeMillis());cancelFrameRequest();}
            else invalidate();
        }
        @Override protected void onDetachedFromWindow(){
            stopClock(SystemClock.uptimeMillis());cancelFrameRequest();
            super.onDetachedFromWindow();
        }
        private void animate(long tick){
            int wanted=data.frameAt(tick);
            if(wanted!=visibleFrame&&buffered!=null){
                // A slow decode may have missed its deadline. Show the
                // available frame while the worker catches the current phase.
                image=buffered;visibleFrame=bufferedFrame;
                buffered=null;bufferedFrame=-1;
            }
            int next=wanted==visibleFrame||missing.get(wanted)?data.nextFrame(tick):wanted;
            if(next==visibleFrame||next==bufferedFrame||requestFrame>=0||missing.get(next))return;
            requestFrame=next;
            long serial=++requestSerial;
            actions.cgFrame(viewPage,next,result->{
                if(serial!=requestSerial||viewPage!=page||!isAttachedToWindow())return;
                requestFrame=-1;
                if(result==null)missing.set(next);
                else {buffered=result;bufferedFrame=next;}
                invalidate();
            });
        }
        @Override protected void onDraw(Canvas canvas){
            long now=SystemClock.uptimeMillis();
            boolean playing=foreground&&isAttachedToWindow()&&!openingRunning();
            if(!playing)stopClock(now);
            else if(resumedAt<0)resumedAt=now;
            long ms=elapsed+(resumedAt<0?0:now-resumedAt);
            long tick=ms/frameMs;
            int uniqueFrames=Math.min(data.variant.frames,data.bufferMax);
            boolean animate=data.animated&&uniqueFrames-missing.cardinality()>(visibleFrame>=0?1:0);
            if(playing&&animate)animate(tick);
            if(!manual){x=panX.offset(tick);y=panY.offset(tick);}
            float sx=getWidth()/(float)canvasWidth,sy=getHeight()/(float)canvasHeight;
            if(data.animated)drawTile(canvas,image,0,0,sx,sy);
            else for(int i=0;i<data.tiles.size();i++)
                drawTile(canvas,data.tiles.get(i),i%data.columns*tileWidth-x,i/data.columns*tileHeight-y,sx,sy);
            boolean scrolling=!manual&&(panX.moving(tick)||panY.moving(tick));
            if(!playing){
                if(foreground&&isAttachedToWindow()&&(animate||scrolling))postInvalidateDelayed(frameMs);
                return;
            }
            long interval=0;
            if(scrolling)interval=frameMs;
            else if(animate&&requestFrame<0)interval=data.frameHold*(long)frameMs;
            if(interval>0)postInvalidateDelayed(interval-ms%interval);
        }
        private void drawTile(Canvas canvas,Bitmap bitmap,float left,float top,float sx,float sy){
            destination.set(left*sx,top*sy,(left+bitmap.getWidth())*sx,(top+bitmap.getHeight())*sy);
            canvas.drawBitmap(bitmap,null,destination,paint);
        }
        @Override public boolean onTouchEvent(MotionEvent e){
            if(panX.distance<=0&&panY.distance<=0)return super.onTouchEvent(e);
            switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                downX=e.getX();downY=e.getY();originX=x;originY=y;moving=false;return true;
            case MotionEvent.ACTION_MOVE:
                float dx=e.getX()-downX,dy=e.getY()-downY;
                if(moving||Math.abs(dx)>slop||Math.abs(dy)>slop){
                    moving=manual=true;
                    if(panX.distance>0)x=Math.max(0,Math.min(panX.distance,originX-dx*canvasWidth/Math.max(1,getWidth())));
                    if(panY.distance>0)y=Math.max(0,Math.min(panY.distance,originY-dy*canvasHeight/Math.max(1,getHeight())));
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:if(!moving)performClick();return true;
            default:return true;
            }
        }
    }
    private void music() {
        GameUiAssets.Config cfg=page.config;
        int x=cfg.number("cursorPrintX",76),y=cfg.number("cursorPrintY",199);
        int w=dimension(cfg,"cursorSizeX",294),h=dimension(cfg,"cursorSizeY",27);
        int dx=cfg.number("cursorNextX",0),dy=cfg.number("cursorNextY",26);
        int labelX=cfg.number("musicNamePrintX",0),labelY=cfg.number("musicNamePrintY",0);
        int font=dimension(cfg,"messageFontSize",22),align=cfg.number("musicNameCenterFlag",1);
        int maxName=bounded(cfg.number("musicNameMax",12),0,4096);
        boolean singleByte=assets.game.number("codeByte",2)==1;
        boolean cursorOnTop=cfg.number("cursorIsUpper",0)!=0;
        Bitmap cursorArt=sprite(cfg.value("filenameCursor","ta_bgm_selc"));
        final int selected=page.index>=0?page.index:page.cells.isEmpty()?-1:page.cells.get(0).index;
        int enabledColor=Color.rgb(bounded(cfg.number("messageColorR",255),0,255),bounded(cfg.number("messageColorG",255),0,255),
            bounded(cfg.number("messageColorB",255),0,255));
        // CommonListenBGM m_cannotMusicNameColor*: 128 unless cannotColorR/G/B overrides it.
        int cannot=Color.rgb(bounded(cfg.number("cannotColorR",128),0,255),bounded(cfg.number("cannotColorG",128),0,255),
            bounded(cfg.number("cannotColorB",128),0,255));
        MusicCursor cursor=null;
        TextView selectedName=null;
        for (int i=0;i<page.cells.size();i++) {
            GalleryCatalog.Cell cell=page.cells.get(i);
            int atX=x+i*dx,atY=y+i*dy;
            boolean selectedRow=cell.index==selected;
            if(selectedRow&&cursorArt!=null){
                if(musicCursor==null||musicCursor.image!=cursorArt)musicCursor=new MusicCursor(cursorArt,cfg,w,h);
                cursor=musicCursor;
                put(cursor,atX,atY,w,h);
            }
            int textX=atX+labelX+GalleryCatalog.musicNameOffset(align,maxName,font,singleByte,cell.musicNameBytes);
            TextView name=text(cell.label,font,cell.enabled?enabledColor:cannot);
            name.setSingleLine(true);
            name.setGravity(Gravity.TOP|Gravity.LEFT);
            put(name,textX,atY+labelY,Math.max(1,canvasWidth-textX),Math.max(h,font*2));
            if(selectedRow)selectedName=name;

            if(dy>0){
                name.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
                View hit=new View(getContext());
                hit.setContentDescription(cell.label);
                hit.setSoundEffectsEnabled(false);
                hit.setOnClickListener(v -> {click.run(); actions.play(cell.index);});
                hit.setEnabled(cell.enabled);
                put(hit,atX,atY,w,dy);
            }
        }
        // PrintMusicName draws the selected row again after the rest of the list.
        if(cursor!=null&&!cursorOnTop)cursor.bringToFront();
        if(selectedName!=null)selectedName.bringToFront();
        if(cursor!=null&&cursorOnTop)cursor.bringToFront();
        button(cfg,"rew1","rew1",hostText(R.string.engine_systemnnn_ctl_gallery_previous_song),() -> actions.moveSong(-1),359,72,67,38);
        button(cfg,page.musicState==1?"play2":"play1","play1",hostText(R.string.engine_systemnnn_ctl_gallery_play),() -> actions.play(selected),
            cfg.number("play1PrintX",425),cfg.number("play1PrintY",72),82,38).setEnabled(selected>=0);
        button(cfg,"ff1","ff1",hostText(R.string.engine_systemnnn_ctl_gallery_next_song),() -> actions.moveSong(1),503,72,67,38);
        button(cfg,page.musicState==2?"pause2":"pause1","pause1",hostText(R.string.engine_systemnnn_ctl_gallery_pause),actions::pause,
            cfg.number("pause1PrintX",569),cfg.number("pause1PrintY",72),67,38);
        button(cfg,page.musicState==0&&cfg.has("stop2FileName")?"stop2":"stop1","stop1",hostText(R.string.engine_systemnnn_ctl_gallery_stop),actions::stop,
            cfg.number("stop1PrintX",635),cfg.number("stop1PrintY",72),67,38);
        int min=bounded(cfg.number("volumeMin",0),0,100),max=bounded(cfg.number("volumeMax",100),min,100);
        int volumeW=dimension(cfg,"volumeSizeX",205),volumeH=dimension(cfg,"volumeSizeY",20);
        Bitmap volumeArt=sprite(cfg.value("filenameVolume","ta_opt_bar"));
        if(volumeArt==null&&!cfg.has("filenameVolume"))volumeArt=sprite("ta_musicbar01");
        ValueBar volume=new ValueBar(getContext(),volumeArt,hostText(R.string.engine_systemnnn_ctl_gallery_volume),
            min,max,page.musicVolume,volumeW,volumeH,0,()->{},actions::volume);
        put(volume,cfg.number("volumePrintX",715),cfg.number("volumePrintY",87),volumeW,volumeH);
        if(cfg.number("spectrumPrintFlag",0)!=0){
            if(spectrum==null)spectrum=new SpectrumView(cfg);
            if(page.musicState==0)spectrum.update(new int[spectrum.values.length]);
            put(spectrum,cfg.number("spectrumPrintX",0),cfg.number("spectrumPrintY",600)-spectrum.height,
                spectrum.extent,spectrum.height+spectrum.cap);
        }
        pageButtons();
    }
    private final class MusicCursor extends View {
        private final Bitmap image;
        private final SpriteAnimation animation;
        private final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);
        private final RectF dest=new RectF();
        private long elapsed,resumedAt=-1;

        MusicCursor(Bitmap image,GameUiAssets.Config cfg,int w,int h){
            super(GameGalleryPanel.this.getContext());
            this.image=image;
            int frames=cfg.number("cursorAnimeFlag",1)==0?1:
                Math.min(cfg.number("cursorAnimePattern",8),Math.max(1,image.getHeight()/h));
            animation=new SpriteAnimation(0,0,w,h,cfg.number("cursorAnimeType",5),
                frames,cfg.number("cursorAnimeSpeed",1),false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        void playing(boolean active){
            if(active&&!isAttachedToWindow())return;
            if(active==(resumedAt>=0))return;
            long now=SystemClock.uptimeMillis();
            if(active)resumedAt=now;
            else {elapsed+=now-resumedAt;resumedAt=-1;}
            invalidate();
        }
        @Override protected void onAttachedToWindow(){
            super.onAttachedToWindow();
            playing(foreground&&page.musicState==1);
        }
        @Override protected void onDetachedFromWindow(){
            playing(false);
            super.onDetachedFromWindow();
        }
        @Override protected void onDraw(Canvas canvas){
            long frame=(elapsed+(resumedAt<0?0:SystemClock.uptimeMillis()-resumedAt))/frameMs;
            dest.set(0,0,getWidth(),getHeight());
            animation.draw(canvas,image,dest,paint,frame,255);
            if(resumedAt>=0&&animation.active(frame))postInvalidateDelayed(frameMs);
        }
    }
    private final class SpectrumView extends View {
        final int height,extent,cap;
        final int[] values,peaks,wait,speed;
        private final int width,step,hold,gravity,barColor,peakColor,mode;
        private final Paint paint=new Paint();
        private long last=-1;
        SpectrumView(GameUiAssets.Config cfg){
            super(GameGalleryPanel.this.getContext());
            int count=bounded(cfg.number("spectrumPrintNumber",16),1,64);
            values=new int[count];peaks=new int[count];wait=new int[count];speed=new int[count];
            width=dimension(cfg,"spectrumSizeX",4);height=dimension(cfg,"spectrumSizeY",60);
            step=dimension(cfg,"spectrumNextX",width+1);extent=(count-1)*step+width;
            cap=dimension(cfg,"spectrumMaxSizeY",2);mode=cfg.number("spectrumPrintType",2);
            hold=bounded(cfg.number("spectrumMaxWaitTime",15),0,1000);
            gravity=bounded(cfg.number("spectrumMaxGrav",1),0,100);
            barColor=spectrumColor(cfg,"spectrum",128,255,192);
            peakColor=spectrumColor(cfg,"spectrumMax",255,0,0);
            setContentDescription(hostText(R.string.engine_systemnnn_ctl_gallery_spectrum));
        }
        private int spectrumColor(GameUiAssets.Config cfg,String key,int r,int g,int b){
            return Color.argb(bounded(cfg.number(key+"Percent",100),0,100)*255/100,
                bounded(cfg.number(key+"ColorR",r),0,255),bounded(cfg.number(key+"ColorG",g),0,255),bounded(cfg.number(key+"ColorB",b),0,255));
        }
        void update(int[] bands){
            System.arraycopy(bands,0,values,0,Math.min(values.length,bands.length));
            for(int i=0;i<values.length;i++)if(values[i]>peaks[i]){peaks[i]=values[i];wait[i]=hold;speed[i]=0;}
            invalidate();
        }
        @Override protected void onDraw(Canvas c){
            long frame=SystemClock.uptimeMillis()/frameMs;
            int ticks=last<0?0:(int)Math.min(10,Math.max(0,frame-last));last=frame;
            c.save();c.scale(getWidth()/(float)extent,getHeight()/(float)(height+cap));
            boolean falling=false;
            for(int i=0;i<values.length;i++){
                for(int n=0;n<ticks&&peaks[i]>values[i];n++){
                    if(wait[i]>0)wait[i]--;
                    else{peaks[i]=Math.max(values[i],peaks[i]-speed[i]);speed[i]+=gravity;}
                }
                int x=i*step;
                if(mode>=1){paint.setColor(barColor);c.drawRect(x,height-height*values[i]/100f,x+width,height,paint);}
                if(mode>=2&&peaks[i]>0){float y=height-height*peaks[i]/100f;paint.setColor(peakColor);c.drawRect(x,y,x+width,y+cap,paint);}
                falling|=peaks[i]>values[i]&&(wait[i]>0||gravity>0);
            }
            c.restore();if(falling)postInvalidateOnAnimation();
        }
    }
    private static int dimension(GameUiAssets.Config cfg,String key,int fallback) {
        return bounded(cfg.number(key,fallback),1,4096);
    }
    private static int bounded(int value,int min,int max) { return Math.max(min,Math.min(max,value)); }

    private String cellLabel(GalleryCatalog.Cell cell){
        if(!cell.label.isEmpty())return cell.label;
        switch(page.screen){
        case SCENE:return hostText(R.string.engine_systemnnn_ctl_gallery_scene_number,cell.index+1);
        case SCENE_GROUP:return hostText(R.string.engine_systemnnn_ctl_gallery_scene_group,cell.index+1);
        case CG_GROUP:return hostText(R.string.engine_systemnnn_ctl_gallery_cg_group,cell.index+1);
        case MOVIE:return cell.index==0?hostText(R.string.engine_systemnnn_ctl_gallery_opening):
            hostText(R.string.engine_systemnnn_ctl_gallery_movie_number,cell.index);
        default:return "";
        }
    }

    private final class Tile extends View {
        private final GalleryCatalog.Cell cell;
        private final String label;
        private final Bitmap image,frame,cursor;
        private final Rect source,dest=new Rect();
        private final int dark;
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        private final ColorMatrixColorFilter gray;
        private Runnable movieSelection;
        private long movieSelectedAt;
        private Bitmap animationBadge,differenceFont;
        private final Rect glyphSource=new Rect();

        Tile(GalleryCatalog.Cell cell,Bitmap image,Rect source,Bitmap frame,Bitmap cursor,int dark) {
            super(GameGalleryPanel.this.getContext());
            this.cell=cell; this.image=image; this.source=source; this.frame=frame; this.cursor=cursor; this.dark=dark;
            ColorMatrix matrix=new ColorMatrix(); matrix.setSaturation(0); gray=new ColorMatrixColorFilter(matrix);
            label=cellLabel(cell);
            setContentDescription(cell.missingMovie?label+" · "+hostText(R.string.engine_systemnnn_host_movie_missing):
                cell.enabled?label:hostText(R.string.engine_systemnnn_ctl_gallery_locked,label));
            setSoundEffectsEnabled(false);
        }
        boolean startMovieSelection(){
            if(movieSelection!=null)return false;
            movieSelectedAt=android.os.SystemClock.uptimeMillis();
            movieSelection=()->{movieSelection=null;actions.movie(cell.index);invalidate();};
            postDelayed(movieSelection,8L*frameMs);invalidate();return true;
        }
        @Override protected void onDetachedFromWindow(){if(movieSelection!=null)removeCallbacks(movieSelection);movieSelection=null;super.onDetachedFromWindow();}
        @Override protected void drawableStateChanged() { super.drawableStateChanged(); invalidate(); }
        @Override protected void onDraw(Canvas canvas) {
            dest.set(0,0,getWidth(),getHeight());
            boolean movie=page.screen==GalleryCatalog.Screen.MOVIE;
            boolean blockGrid=page.screen==GalleryCatalog.Screen.CG || page.screen==GalleryCatalog.Screen.SCENE || movie;
            if (frame!=null&&!movie) canvas.drawBitmap(frame,null,dest,paint);
            boolean valid=image!=null && (source==null || source.left>=0 && source.top>=0 &&
                source.right<=image.getWidth() && source.bottom<=image.getHeight());
            if (valid && (cell.enabled || movie || dark!=0)) {
                if (blockGrid && source==null) {
                    GameUiAssets.Config cfg=page.config;
                    int bw=dimension(cfg,"sizeX",133),bh=dimension(cfg,"sizeY",100);
                    float sx=getWidth()/(float)bw,sy=getHeight()/(float)bh;
                    int px=cfg.number("picPrintX",0),py=cfg.number("picPrintY",0);
                    int pw=dimension(cfg,"picSizeX",bw),ph=dimension(cfg,"picSizeY",bh);
                    dest.set(Math.round(px*sx),Math.round(py*sy),Math.round((px+pw)*sx),Math.round((py+ph)*sy));
                }
                paint.setColorFilter(!cell.enabled&&!movie&&dark<0?gray:null);
                paint.setAlpha(cell.enabled||movie||dark<0?255:dark*255/100);
                canvas.drawBitmap(image,source,dest,paint);
                paint.setColorFilter(null); paint.setAlpha(255);
                dest.set(0,0,getWidth(),getHeight());
            } else if (movie&&frame!=null) {
                canvas.drawBitmap(frame,null,dest,paint);
            }
            if(animationBadge!=null&&valid&&(cell.enabled||dark!=0)){
                GameUiAssets.Config cfg=page.config;
                float sx=getWidth()/(float)dimension(cfg,"sizeX",133),sy=getHeight()/(float)dimension(cfg,"sizeY",100);
                int x=cfg.number("picPrintX",0),y=cfg.number("picPrintY",0);
                dest.set(Math.round(x*sx),Math.round(y*sy),Math.round((x+animationBadge.getWidth())*sx),
                    Math.round((y+animationBadge.getHeight())*sy));
                canvas.drawBitmap(animationBadge,null,dest,paint);
                dest.set(0,0,getWidth(),getHeight());
            }
            boolean flash=movieSelection!=null&&(android.os.SystemClock.uptimeMillis()-movieSelectedAt)/frameMs%2==0;
            boolean focus=movieSelection==null?(isPressed()||isHovered()):flash;
            if (focus&&blockGrid&&cell.enabled) drawBlockCursor(canvas);
            else if(focus&&!blockGrid&&cursor!=null)canvas.drawBitmap(cursor,null,dest,paint);
            if(movieSelection!=null)postInvalidateOnAnimation();
            if (page.screen==GalleryCatalog.Screen.CG) drawDifference(canvas);
        }
        /** CommonSelectCG::PrintBlock "got/total" counter in the block's logical sizeX/sizeY space. */
        private void drawDifference(Canvas canvas){
            GameUiAssets.Config cfg=page.config;
            int got=cell.unlockedVariants,total=cell.totalVariants;
            if(!GalleryCatalog.differenceVisible(cfg.number("differencePrintFlag",1),cfg.number("differencePrintByGetFlag",0),got,total))
                return;
            int fw=dimension(cfg,"differenceFontSizeX",21),fh=dimension(cfg,"differenceFontSizeY",27);
            int next=bounded(cfg.number("differenceFontNextX",fw),-4096,4096);
            int keta=bounded(cfg.number("differentKeta",2),1,8);
            int px=bounded(cfg.number("differencePrintX",0),-4096,4096),py=bounded(cfg.number("differencePrintY",0),-4096,4096);
            Bitmap font=differenceFont;
            if(font!=null&&12L*fw<=font.getWidth()&&fh<=font.getHeight()){
                float sx=getWidth()/(float)dimension(cfg,"sizeX",133),sy=getHeight()/(float)dimension(cfg,"sizeY",100);
                paint.setAlpha(255);
                for(int[] glyph:GalleryCatalog.differenceGlyphs(got,total,keta,next)){
                    int left=px+glyph[0];
                    glyphSource.set(glyph[1]*fw,0,(glyph[1]+1)*fw,fh);
                    dest.set(Math.round(left*sx),Math.round(py*sy),Math.round((left+fw)*sx),Math.round((py+fh)*sy));
                    canvas.drawBitmap(font,glyphSource,dest,paint);
                }
                dest.set(0,0,getWidth(),getHeight());
            }
        }
        private void drawBlockCursor(Canvas canvas){
            GameUiAssets.Config cfg=page.config;
            int bw=dimension(cfg,"sizeX",133),bh=dimension(cfg,"sizeY",100);
            float sx=getWidth()/(float)bw,sy=getHeight()/(float)bh;
            int px=cfg.number("picPrintX",0),py=cfg.number("picPrintY",0);
            int pw=dimension(cfg,"picSizeX",bw),ph=dimension(cfg,"picSizeY",bh);
            int x=cfg.number("cursorPrintX",px),y=cfg.number("cursorPrintY",py);
            int cw=cfg.has("cursorSizeX")?dimension(cfg,"cursorSizeX",pw):dimension(cfg,"cursprSizeX",pw);
            int ch=cfg.has("cursorSizeY")?dimension(cfg,"cursorSizeY",ph):dimension(cfg,"cursprSizeY",ph);
            dest.set(Math.round(x*sx),Math.round(y*sy),Math.round((x+cw)*sx),Math.round((y+ch)*sy));
            int mode=cfg.number("selectPrintMode",1),percent=bounded(cfg.number("selectPercent",50),0,100);
            if(mode==1){
                paint.setColor(Color.argb(percent*255/100,bounded(cfg.number("cursorColorR",230),0,255),
                    bounded(cfg.number("cursorColorG",0),0,255),bounded(cfg.number("cursorColorB",50),0,255)));
                canvas.drawRect(dest,paint);
            }else if(mode==2&&cursor!=null){
                int type=cfg.number("cursorPrintType",0);
                paint.setAlpha(type==1?percent*255/100:255);
                if(type==2)paint.setXfermode(ADD);
                canvas.drawBitmap(cursor,null,dest,paint);
                paint.setXfermode(null);
            }
            paint.setAlpha(255);
            dest.set(0,0,getWidth(),getHeight());
        }
    }
}
