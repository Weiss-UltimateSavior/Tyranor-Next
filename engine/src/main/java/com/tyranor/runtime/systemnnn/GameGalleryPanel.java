package com.tyranor.runtime.systemnnn;

import com.core.engine.R;

import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
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
    }
    GalleryCatalog.Screen screen() { return page.screen; }
    @Override Bitmap sprite(String name) {
        if (name==null) return null;
        Bitmap picture=page.images.get(GameUiAssets.systemImageKey(name));
        return picture!=null?picture:super.sprite(name);
    }
    void update(GalleryCatalog.Page value) {
        if(page==null||page.screen!=value.screen)opening(value.config,false);
        page=value;
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
        int w=dimension(cfg,"sizeX",cg?156:326),h=dimension(cfg,"sizeY",cg?88:40);
        int x=cfg.number("printX",138),y=cfg.number("printY",102);
        int dx=cfg.number("nextX",w),dy=cfg.number("nextY",h);
        Bitmap frame=cfg.number("futaPrintFlag",1)!=0?sprite(cfg.value("filenameFuta","")):null;
        Bitmap cursor=sprite(cfg.value("filenameCursor",""));
        Bitmap animationBadge=cg&&cfg.number("animePrintFlag",0)!=0?sprite(cfg.value("fileNameAnime","ta_futa1")):null;
        int dark=bounded(cfg.number("picMustPrintPercent",0),0,100);
        for (int i=0;i<page.cells.size();i++) {
            GalleryCatalog.Cell cell=page.cells.get(i);
            Tile tile=new Tile(cell,sprite(cell.picture),null,frame,cursor,dark);
            if(cell.animated)tile.animationBadge=animationBadge;
            tile.setOnClickListener(v -> {
                click.run();
                if (cg) actions.open(GalleryCatalog.Screen.VIEW,page.group,0,cell.index);
                else actions.replay(page.group,cell.index);
            });
            tile.setEnabled(cell.enabled);
            put(tile,x+(i%page.columns)*dx,y+(i/page.columns)*dy,w,h);
        }
        if (cfg.number("totalPercentPrintFlag",0)!=0)
            digits(page.percent+"%",cfg.value("filenameTotalPercent",""),"totalPercent",206,659,24,30,20);
        pageButtons();
        if (cfg.number("pagePrintFlag",0)!=0) {
            int count=bounded(cfg.number("pageKeta",2),1,4);
            digits(String.format(Locale.ROOT,"%0"+count+"d",page.page+1),cfg.value("fileNamePageFont",""),"page",1204,340,32,54,26);
        }
    }
    private void digits(String value,String filename,String tag,int x,int y,int width,int height,int spacing) {
        GameUiAssets.Config cfg=page.config;
        int w=dimension(cfg,tag+"FontSizeX",width),h=dimension(cfg,tag+"FontSizeY",height);
        int step=cfg.number(tag+"FontNextX",spacing);
        int at=cfg.number(tag+"PrintX",x),top=cfg.number(tag+"PrintY",y);
        Bitmap font=sprite(filename);
        for (int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            int n=c=='%'?12:c-'0';
            Rect source=new Rect(n*w,0,(n+1)*w,h);
            if (font!=null && source.right<=font.getWidth() && h<=font.getHeight()) {
                Tile digit=new Tile(new GalleryCatalog.Cell(i,"","","",true),font,source,null,null,100);
                digit.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
                put(digit,at+i*step,top,w,h);
            } else put(text(String.valueOf(c),h,Color.WHITE),at+i*step,top,w,h);
        }
    }
    private void pageButtons() {
        pageArrows(page.config,page.page,page.pages,canvasWidth/2-24,16,canvasHeight-72,
            next->actions.open(page.screen,page.group,next,page.index));
    }
    private void movies(){
        GameUiAssets.Config cfg=page.config;
        int w=dimension(cfg,"sizeX",320),h=dimension(cfg,"sizeY",180);
        int x=cfg.number("printX",(canvasWidth-w)/2),y=cfg.number("printY",(canvasHeight-h)/2);
        int dx=cfg.number("nextX",w),dy=cfg.number("nextY",h);
        Bitmap frame=sprite(cfg.value("filenameFuta","")),cursor=sprite(cfg.value("fileNameCursor",""));
        for(int i=0;i<page.cells.size();i++){
            GalleryCatalog.Cell cell=page.cells.get(i);
            Tile tile=new Tile(cell,sprite(cell.picture),null,frame,cursor,100);
            tile.setEnabled(cell.enabled);
            tile.setOnClickListener(v->{
                if(!cell.enabled||!tile.startMovieSelection())return;
                sound(cfg.number("selectSound",0)-1,-1);
            });
            put(tile,x+(i%page.columns)*dx,y+(i/page.columns)*dy,w,h);
        }
        pageButtons();
        if(cfg.number("totalPercentPrintFlag",0)!=0)
            digits(page.percent+"%",cfg.value("filenameTotalPercent",""),"totalPercent",195,659,24,30,20);
        if(cfg.number("pagePrintFlag",0)!=0)
            digits(String.format(Locale.ROOT,"%02d",page.page+1),cfg.value("fileNamePageFont",""),"page",110,635,36,38,22);
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
        int x=cfg.number("cursorPrintX",440),y=cfg.number("cursorPrintY",206);
        int w=dimension(cfg,"cursorSizeX",480),h=dimension(cfg,"cursorSizeY",24);
        int dx=cfg.number("cursorNextX",0),dy=cfg.number("cursorNextY",h);
        int labelX=cfg.number("musicNamePrintX",30),labelY=cfg.number("musicNamePrintY",0);
        int first=-1;
        for (int i=0;i<page.cells.size();i++) {
            GalleryCatalog.Cell cell=page.cells.get(i);
            if (cell.enabled && first==-1) first=cell.index;
            int atX=x+i*dx,atY=y+i*dy;
            if (cell.index==page.index) picture(cfg.value("filenameCursor",""),atX,atY,w,h);
            TextView name=text(cell.label,dimension(cfg,"messageFontSize",16),cell.enabled?Color.WHITE:0xff8f9eaa);
            name.setSingleLine(true);
            name.setGravity(cfg.number("musicNameCenterFlag",0)!=0?Gravity.CENTER:Gravity.CENTER_VERTICAL);
            name.setSoundEffectsEnabled(false);
            name.setOnClickListener(v -> {click.run(); actions.play(cell.index);});
            name.setEnabled(cell.enabled);
            put(name,atX+labelX,atY+labelY,Math.max(1,w-labelX),h);
        }
        final int selected=page.index>=0?page.index:first;
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
        ValueBar volume=new ValueBar(getContext(),sprite(cfg.value("filenameVolume","ta_musicbar01")),hostText(R.string.engine_systemnnn_ctl_gallery_volume),
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
        private Bitmap animationBadge;

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
            if (frame!=null&&!movie) canvas.drawBitmap(frame,null,dest,paint);
            boolean valid=image!=null && (source==null || source.left>=0 && source.top>=0 &&
                source.right<=image.getWidth() && source.bottom<=image.getHeight());
            if (valid && (cell.enabled || dark>0)) {
                paint.setColorFilter(cell.enabled||movie?null:gray); paint.setAlpha(cell.enabled||movie?255:dark*255/100);
                canvas.drawBitmap(image,source,dest,paint);
                paint.setColorFilter(null); paint.setAlpha(255);
            } else if (movie&&frame!=null) {
                canvas.drawBitmap(frame,null,dest,paint);
            } else if (frame==null) {
                paint.setColor(0xbf0c2339); canvas.drawRect(dest,paint);
                paint.setColor(cell.enabled?Color.WHITE:0xff8f9eaa); paint.setTextAlign(Paint.Align.CENTER);
                paint.setTextSize(Math.max(12,Math.min(22,getHeight()/2f)));
                canvas.drawText(label,getWidth()/2f,getHeight()*.65f,paint); paint.setAlpha(255);
            }
            if(animationBadge!=null&&valid&&(cell.enabled||dark>0)){
                GameUiAssets.Config cfg=page.config;
                float sx=getWidth()/(float)dimension(cfg,"sizeX",156),sy=getHeight()/(float)dimension(cfg,"sizeY",88);
                int x=cfg.number("picPrintX",0),y=cfg.number("picPrintY",0);
                dest.set(Math.round(x*sx),Math.round(y*sy),Math.round((x+animationBadge.getWidth())*sx),
                    Math.round((y+animationBadge.getHeight())*sy));
                canvas.drawBitmap(animationBadge,null,dest,paint);
                dest.set(0,0,getWidth(),getHeight());
            }
            boolean flash=movieSelection!=null&&(android.os.SystemClock.uptimeMillis()-movieSelectedAt)/frameMs%2==0;
            boolean focus=movieSelection==null?(isPressed()||isHovered()):flash;
            if (focus&&movie&&cell.enabled) drawMovieCursor(canvas);
            else if(focus&&!movie&&cursor!=null)canvas.drawBitmap(cursor,null,dest,paint);
            if(movieSelection!=null)postInvalidateOnAnimation();
            if (cell.enabled && !cell.detail.isEmpty() && page.config.number("differencePrintFlag",0)!=0) {
                paint.setColor(Color.WHITE); paint.setTextAlign(Paint.Align.LEFT); paint.setTextSize(Math.max(12,getHeight()/5f));
                canvas.drawText(cell.detail,getWidth()*.35f,getHeight()*.88f,paint);
            }
        }
        private void drawMovieCursor(Canvas canvas){
            GameUiAssets.Config cfg=page.config;
            int w=dimension(cfg,"sizeX",320),h=dimension(cfg,"sizeY",180);
            float sx=getWidth()/(float)w,sy=getHeight()/(float)h;
            int x=cfg.number("cursorPrintX",0),y=cfg.number("cursorPrintY",0);
            int cw=dimension(cfg,"cursorSizeX",w),ch=dimension(cfg,"cursorSizeY",h);
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
        }
    }
}
