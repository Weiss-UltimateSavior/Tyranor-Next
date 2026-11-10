package com.tyranor.runtime.systemnnn;

import android.content.Context;
import com.core.engine.R;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import java.util.HashMap;
import java.util.Map;

import static com.tyranor.runtime.systemnnn.GameRunner.Modal.BACK;
import static com.tyranor.runtime.systemnnn.GameRunner.Modal.CHOOSE;
import static com.tyranor.runtime.systemnnn.GameRunner.Modal.CONFIRM;
import static com.tyranor.runtime.systemnnn.GameRunner.Modal.HOVER;
import static com.tyranor.runtime.systemnnn.GameRunner.Modal.PAGE;
import static com.tyranor.runtime.systemnnn.GameRunner.Modal.SCROLL;
import static com.tyranor.runtime.systemnnn.GameRunner.Modal.SELECT_CHART;

/** CCommonChartMenu presentation. The native visit owns scroll, hit, branches and route locks. */
final class GameChartPanel extends GameUiPanel {
    interface Result { void action(int action,int value); }

    private static final int NONE=-1,OPEN_OPTIONS=-2;
    private static final int SEEN=0,HIGHLIGHT=1,FORWARD=2,CURRENT=3,CURRENT_HIGHLIGHT=4,DISABLED=5;
    private static final int PAGE_NEW=4,PAGE_DISABLED=3;
    private static final int CAPTURE_NONE=0,CAPTURE_MINI=1,CAPTURE_MAIN=2;

    private final GameUiAssets.Config config;
    private final Result result;
    private final Runnable options;
    private final Map<String,Bitmap> art=new HashMap<>();
    private final Map<Integer,Boolean> visibleNodes=new HashMap<>();
    private final int touchSlop,mainCapture,wheelStep,centerLayer,centerMulti;
    private final boolean reverseCapture,jumpMini,debugHideNodes,scriptBackdrop,scriptTiles;
    private final int[] roadColors=new int[5];
    private final int pulseFrames,pulseScale,hoverScale,revisitScale,hoverAlpha,forwardAlpha,roadFrames;
    private final int miniDotWidth,miniDotHeight,miniDotTime,miniUpper,miniLower,miniWindowTime,miniAlpha1,miniAlpha2;
    private final int descFont,descNext,descColor,descDeltaX,descDeltaY;
    private final int dialogX,dialogY;
    private final SpriteAnimation newMarker;
    private final String newMarkerName;

    private GameRunner.Modal modal;
    private ChartView chart;
    private ChartBackdrop.Frame backdropFrame;
    private ChartComposition composition;
    private Bitmap composited;
    private boolean compositionUnavailable;
    private long visualRevision,compositionEpoch;
    private int tabRevision;
    private MapLayer lower,upper;
    private GameButton menuButton,cancelButton;
    private PageTab[] tabs;
    private int centerSpeed,tabMarkerX,tabMarkerY,descTopX,descTopY,descBottomX,descBottomY;
    private int miniWindowX,miniWindowY,miniWindowWidth;
    private boolean descriptionOnTop,foreground=true,overlayLocked;
    private long clockStart=-1,clockElapsed,warningAt=-1,warningFrames;

    private int inFlight=NONE,flightValue,queuedAction=NONE,queuedValue,desiredScroll=NONE;
    private int desiredHover=NONE,sentHover=NONE;
    private int capture=CAPTURE_NONE,touchId=-1,downY,downScroll,miniAnchor,pointerX,pointerY;
    private float physicalDownX,physicalDownY;
    private boolean tap,dragMoved;

    GameChartPanel(Context context,GameUiAssets assets,GameRunner.Modal modal,int width,int height,
                   Runnable click,Result result,Runnable options){
        super(context,assets,modal.assets.config.value("filenameBG",""),click);
        if(modal.mode!=SELECT_CHART||modal.chart==null)throw new IllegalArgumentException("Missing chart mode");
        this.result=result;this.options=options;
        config=modal.assets.config;
        touchSlop=ViewConfiguration.get(context).getScaledTouchSlop();
        mainCapture=config.number("mainCaptureOk",1);
        reverseCapture=config.number("captureReverse",0)!=0;
        jumpMini=config.number("autoJumpMiniWindow",1)!=0;
        wheelStep=config.number("scrollSpeed",20);
        debugHideNodes=config.number("debugDontPrintButton",0)!=0;
        scriptBackdrop=config.number("backScriptFlag",0)!=0;
        scriptTiles=scriptBackdrop&&config.number("picLoadBackScript",0)!=0;
        centerLayer=clamp(config.number("centerPicLayer",5),0,31);
        centerMulti=clamp(config.number("centerMulti",200),-10000,10000);

        int[][] colors={{128,180,255},{36,99,192},{62,85,101},{191,217,255},{72,148,255}};
        for(int i=0;i<roadColors.length;i++){
            int[] rgb=colors[i];String suffix=Integer.toString(i+1);
            roadColors[i]=rgb("lineColor",suffix,rgb[0],rgb[1],rgb[2]);
        }
        pulseFrames=Math.max(1,config.number("buttonMultiCount",40));
        pulseScale=scale(config.number("buttonMulti",140));
        hoverScale=scale(config.number("selectButtonMulti",140));
        revisitScale=scale(config.number("selectButtonMulti2",120));
        hoverAlpha=alpha(config.number("selectSlotTransPercent",80));
        forwardAlpha=alpha(config.number("selectOkSlotTransPercent",80));
        roadFrames=Math.max(1,config.number("lineColorCount",20));

        miniDotWidth=dimension(config.number("miniMapButtonSizeX",12));
        miniDotHeight=dimension(config.number("miniMapButtonSizeY",3));
        miniDotTime=Math.max(1,config.number("miniButtonTime",10));
        miniUpper=dimension(config.number("miniMapWindowUpSize",15));
        miniLower=dimension(config.number("miniMapWindowDownSize",15));
        miniWindowTime=Math.max(1,config.number("miniWindowTime",30));
        miniAlpha1=alpha(config.number("miniWindowPercent1",30));
        miniAlpha2=alpha(config.number("miniWindowPercent2",90));

        descFont=Math.max(1,dimension(config.number("descFontSize",20)));
        descNext=config.number("descNextY",descFont+8);
        descColor=rgb("descColor","",255,255,255);
        descDeltaX=config.number("descPrintX",32);descDeltaY=config.number("descPrintY",16);
        dialogX=config.number("dialogX",236);dialogY=config.number("dialogY",160);
        newMarkerName=config.value("filenameNewMark","ta_anime");
        newMarker=new SpriteAnimation(0,0,dimension(config.number("newMarkSizeX",16)),
            dimension(config.number("newMarkSizeY",16)),config.number("newMarkType",1),
            config.number("newMarkPattern",1),config.number("newMarkSpeed",1),true);

        canvas(width,height);
        opening(config,false);
        update(modal);
    }

    void update(GameRunner.Modal next){
        if(next.mode!=SELECT_CHART||next.chart==null)throw new IllegalArgumentException("Chart update missing view");
        GameRunner.Modal previous=modal;
        int action=inFlight;
        inFlight=NONE;
        if(previous==null||previous.assets!=next.assets)art.clear();
        modal=next;
        chart=next.chart;
        boolean changedPage=previous==null||previous.page!=next.page;
        if(changedPage||previous.assets!=next.assets||previous.chart.scroll!=chart.scroll)visibleNodes.clear();
        boolean warningChanged=previous!=null&&previous.chart.pending!=chart.pending;
        if(warning()&&warningAt<0)warningAt=frames();
        else if(!warning()&&warningAt>=0){
            warningFrames+=frames()-warningAt;
            warningAt=-1;
        }
        if(changedPage||warningChanged){
            resetGesture();
            desiredHover=sentHover=NONE;
            desiredScroll=NONE;
        }
        if(changedPage){
            descriptionOnTop=false;
            centerSpeed=config.number("scrollSpeedX"+(modal.page+1),-1);
            startBackdrop();
        }
        if(action==SCROLL&&chart.pointerY<0)sentHover=NONE;
        if(changedPage||warningChanged||!java.util.Arrays.equals(previous.chart.pageStates,chart.pageStates))renderControls();
        updateDescriptionSide();
        invalidateMap();
        pump();
    }

    private void startBackdrop(){
        if(!scriptBackdrop)return;
        int page=modal.page+1,count=-1;
        if(scriptTiles){
            int common=config.number("backPicNumberCommon",2);
            if(config.has("backPicNumber"+page))count=config.number("backPicNumber"+page,common);
            else if(config.has("backPicSizeY"+page))count=(int)((chart.backExtent+(long)canvasHeight-1)/canvasHeight);
            else count=common;
            count=clamp(count,1,1024);
        }
        chartBackground(config,SELECT_CHART,page,centerLayer,count,frame->{
            if(usesComposer(backdropFrame)!=usesComposer(frame))compositionEpoch++;
            backdropFrame=frame;
            if(frame==null){composition=null;composited=null;}
            invalidateMap();
        });
    }

    private static boolean usesComposer(ChartBackdrop.Frame frame){return frame!=null&&frame.composer!=null;}

    void overlayInput(boolean locked){
        overlayLocked=locked;
        if(locked){
            queuedAction=NONE;
            desiredHover=desiredScroll=NONE;
            resetGesture();
        }else pump();
    }

    @Override void uiActive(boolean active){
        super.uiActive(active);
        if(!active&&clockStart>=0){
            clockElapsed+=SystemClock.uptimeMillis()-clockStart;
            clockStart=-1;
        }
        foreground=active;
        if(active)invalidateMap();
    }

    @Override void cancelLeave(){
        super.cancelLeave();
        if(inFlight==SCROLL)desiredScroll=NONE;
        inFlight=NONE;
        invalidateMap();
        pump();
    }

    @Override boolean backOverlay(){
        if(ready()){
            if(warning()){
                if(cancelButton!=null)cancelButton.performClick();
                else enqueue(CONFIRM,0);
            }else if(external())enqueue(BACK,0);
            else if(options!=null){
                if(menuButton!=null)menuButton.performClick();
                else enqueue(OPEN_OPTIONS,0);
            }
        }
        return true;
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event){
        if(overlayLocked)return true;
        boolean handled=super.dispatchTouchEvent(event);
        if(handled&&usesComposer(backdropFrame))invalidateComposition();
        return handled;
    }

    @Override public boolean dispatchHoverEvent(MotionEvent event){
        if(overlayLocked)return true;
        int action=event.getActionMasked();
        if(ready()&&!warning()){
            if(action==MotionEvent.ACTION_HOVER_EXIT)hover(NONE);
            else if(action==MotionEvent.ACTION_HOVER_ENTER||action==MotionEvent.ACTION_HOVER_MOVE){
                int x=logicalX(event.getX()),y=logicalY(event.getY());
                hover(insideMini(x,y)?NONE:point(x,y));
            }
        }
        boolean handled=super.dispatchHoverEvent(event);
        if(usesComposer(backdropFrame))invalidateComposition();
        return handled;
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent event){
        if(overlayLocked)return true;
        if(event.getActionMasked()==MotionEvent.ACTION_SCROLL&&ready()&&!warning()){
            float wheel=event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if(wheel!=0&&wheelStep!=0){
                hover(NONE);
                scroll((long)wantedScroll()+(wheel>0?-(long)wheelStep:wheelStep));
                return true;
            }
        }
        return super.dispatchGenericMotionEvent(event);
    }

    private boolean ready(){
        return foreground&&!overlayLocked&&!controlsBusy()&&activeFrameReady()&&!openingRunning();
    }

    private boolean warning(){return chart.pending>=0;}
    private boolean external(){return modal.returnMode!=0;}

    private void enqueue(int action,int value){
        if(!ready()||queuedAction!=NONE||(inFlight!=NONE&&inFlight!=HOVER&&inFlight!=SCROLL))return;
        queuedAction=action;queuedValue=value;
        if(action!=CHOOSE){
            desiredHover=NONE;
            if(action!=PAGE)desiredScroll=NONE;
        }
        pump();
    }

    private void hover(int point){
        desiredHover=point;
        pump();
    }

    private void scroll(long value){
        desiredScroll=(int)Math.max(0,Math.min(chart.limit,value));
        pump();
    }

    private int wantedScroll(){
        if(desiredScroll>=0)return desiredScroll;
        return inFlight==SCROLL?flightValue:chart.scroll;
    }

    private void pump(){
        if(!ready()||inFlight!=NONE)return;
        if(queuedAction!=NONE){
            int action=queuedAction,value=queuedValue;
            queuedAction=NONE;
            if(action==OPEN_OPTIONS){if(options!=null)options.run();return;}
            publish(action,value);
            return;
        }
        if(warning())return;
        if(desiredScroll>=0){
            int scroll=desiredScroll;desiredScroll=NONE;
            if(scroll!=chart.scroll){publish(SCROLL,scroll);return;}
        }
        if(desiredHover!=sentHover){
            sentHover=desiredHover;
            publish(HOVER,sentHover);
        }
    }

    private void publish(int action,int value){
        inFlight=action;flightValue=value;
        result.action(action,value);
    }

    private int logicalX(float x){return (int)(x*canvasWidth/Math.max(1,getWidth()));}
    private int logicalY(float y){return (int)(y*canvasHeight/Math.max(1,getHeight()));}

    private static int point(int x,int y){return (x<<16)|(y&0xffff);}

    private boolean insideMini(int x,int y){
        return inside(x,y,chart.miniX,chart.miniY,chart.miniWidth,chart.miniHeight);
    }

    private static boolean inside(int x,int y,int left,int top,int width,int height){
        return x>=left&&y>=top&&x-left<width&&y-top<height;
    }

    private void resetGesture(){touchId=-1;capture=CAPTURE_NONE;tap=dragMoved=false;}

    private boolean touchMap(MotionEvent event){
        if(!ready()||warning())return true;
        int kind=event.getActionMasked();
        if(kind==MotionEvent.ACTION_CANCEL){
            resetGesture();hover(NONE);invalidateMap();return true;
        }
        if(kind==MotionEvent.ACTION_DOWN){
            touchId=event.getPointerId(0);
            physicalDownX=event.getX();physicalDownY=event.getY();
            pointerX=logicalX(physicalDownX);pointerY=downY=logicalY(physicalDownY);
            downScroll=wantedScroll();dragMoved=false;
            if(insideMini(pointerX,pointerY)){
                capture=CAPTURE_MINI;tap=false;
                miniAnchor=miniOffset(downScroll);
                int point=pointerY-chart.miniY-miniWindowY,length=miniLength();
                if(jumpMini&&(point<miniAnchor||point>=miniAnchor+length+miniUpper+miniLower)){
                    miniAnchor=clamp(point-(length+miniUpper+miniLower)/2,0,Math.max(0,chart.miniHeight-length));
                    miniScroll(miniAnchor);
                }
                hover(NONE);
            }else{
                boolean graph=inside(pointerX,pointerY,chart.slotX,chart.slotY,chart.slotAreaWidth,chart.slotAreaHeight);
                tap=nodeAt(pointerX,pointerY)!=null;
                capture=!tap&&graph&&mainCapture!=0?CAPTURE_MAIN:CAPTURE_NONE;
                hover(capture==CAPTURE_NONE?point(pointerX,pointerY):NONE);
            }
            invalidateMap();
            return true;
        }
        if(touchId<0)return true;
        int index=event.findPointerIndex(touchId);
        if(index<0)return true;
        pointerX=logicalX(event.getX(index));pointerY=logicalY(event.getY(index));
        if(kind==MotionEvent.ACTION_MOVE){
            dragMoved|=Math.abs(event.getX(index)-physicalDownX)>touchSlop||
                Math.abs(event.getY(index)-physicalDownY)>touchSlop;
            long delta=pointerY-downY;
            if(capture==CAPTURE_MINI)miniScroll((long)miniAnchor+delta);
            else if(capture==CAPTURE_MAIN&&(mainCapture==1||mainCapture==3)){
                if(mainCapture==3)delta=delta*chart.limit/canvasHeight;
                scroll(downScroll+(reverseCapture?delta:-delta));
            }else if(capture==CAPTURE_NONE)hover(point(pointerX,pointerY));
            invalidateMap();
        }else if(kind==MotionEvent.ACTION_UP||
            kind==MotionEvent.ACTION_POINTER_UP&&event.getActionIndex()==index){
            if(tap&&!dragMoved&&nodeAt(pointerX,pointerY)!=null&&desiredScroll==NONE&&inFlight!=SCROLL)
                enqueue(CHOOSE,point(pointerX,pointerY));
            resetGesture();
            invalidateMap();
        }
        return true;
    }

    private ChartView.Node nodeAt(int x,int y){
        if(debugHideNodes)return null;
        for(int i=chart.nodes.length-1;i>=0;i--){
            ChartView.Node node=chart.nodes[i];
            if(node.access==0)continue;
            int cx=node.x+chart.slotX,cy=node.y+chart.slotY-chart.scroll;
            if(inside(x,y,cx-chart.slotWidth/2,cy-chart.slotHeight/2,chart.slotWidth,chart.slotHeight))
                return visibleNodes.computeIfAbsent(node.id,id->nodeVisible(sprite(node.asset),node,chart,
                    canvasWidth,canvasHeight,chart.scroll,chart.scroll))?node:null;
        }
        return null;
    }

    private void miniScroll(long offset){
        if(chart.miniHeight<=0)return;
        offset=Math.max(0,Math.min(chart.miniHeight-miniLength(),offset));
        scroll(offset*chart.extent/chart.miniHeight);
    }

    private int miniLength(){
        return chart.extent<=0?0:clamp((int)((long)chart.miniHeight*canvasHeight/chart.extent),0,chart.miniHeight);
    }

    private int miniOffset(int scroll){
        return chart.extent<=0?0:(int)((long)scroll*chart.miniHeight/chart.extent);
    }

    private static Bitmap find(GameUiAssets assets,GameRunner.Modal modal,String name){
        if(name==null||name.isEmpty()||modal.assets==null)return null;
        Bitmap image=modal.assets.images.get(GameUiAssets.systemImageKey(name));
        return image==null?assets.image(name):image;
    }

    @Override Bitmap sprite(String name){
        if(name==null||name.isEmpty())return null;
        if(!art.containsKey(name))art.put(name,find(assets,modal,name));
        return art.get(name);
    }

    private static boolean nodeVisible(Bitmap image,ChartView.Node node,ChartView chart,int width,int height,
                                       int firstScroll,int lastScroll){
        if(image==null||node.sx<0||node.sy<0||node.sw<=0||node.sh<=0||
            node.sx+(long)node.sw>image.getWidth()||node.sy+(long)node.sh>image.getHeight()||
            chart.slotWidth<=0||chart.slotHeight<=0)return false;
        long left=(long)node.x+chart.slotX-chart.slotWidth/2,top=(long)node.y+chart.slotY-chart.slotHeight/2;
        long l=Math.max(0,-left),t=Math.max(0,firstScroll-top);
        long r=Math.min(chart.slotWidth,width-left),b=Math.min(chart.slotHeight,height-top+lastScroll);
        if(l>=r||t>=b)return false;
        return visiblePixels(image,node.sx+(int)(l*node.sw/chart.slotWidth),node.sy+(int)(t*node.sh/chart.slotHeight),
            node.sx+(int)((r*node.sw+chart.slotWidth-1)/chart.slotWidth),
            node.sy+(int)((b*node.sh+chart.slotHeight-1)/chart.slotHeight),1);
    }

    static String chartFailure(GameUiAssets assets,GameRunner.Modal modal,int width,int height){
        if(modal.mode!=SELECT_CHART)return null;
        if(modal.assets==null||modal.chart==null)return "Missing original chart setup";
        ChartView chart=modal.chart;
        if(chart.pending>=0&&!confirmationVisible(assets,modal,width,height))
            return "Missing visible original chart confirmation";
        boolean actionable=false;
        for(ChartView.Node node:chart.nodes){
            if(node.access==0)continue;
            actionable=true;
            if(nodeVisible(find(assets,modal,node.asset),node,chart,width,height,0,chart.limit))return null;
        }
        if(!actionable)return null;
        PageTab[] tabs=pageTabs(assets.game,modal.assets.config,modal);
        for(int page=0;page<tabs.length;page++){
            PageTab tab=tabs[page];
            if(tab==null||!tab.enabled||page==modal.page)continue;
            GameButton.Spec spec=new GameButton.Spec(tab.setup);
            if(validHit(find(assets,modal,spec.image),spec,tab.x,tab.y,width,height))return null;
        }
        return "No displayable original chart destination";
    }

    private static int clamp(int n,int low,int high){return Math.max(low,Math.min(high,n));}
    private static int dimension(int n){return clamp(n,0,4096);}
    private static int alpha(int n){return clamp(n,0,100)*255/100;}
    private static int scale(int n){return clamp(n,1,1000);}
    private int rgb(String name,String suffix,int r,int g,int b){
        return Color.rgb(clamp(config.number(name+"R"+suffix,r),0,255),
            clamp(config.number(name+"G"+suffix,g),0,255),clamp(config.number(name+"B"+suffix,b),0,255));
    }

    private static PageTab[] pageTabs(GameUiAssets.Config game,GameUiAssets.Config config,GameRunner.Modal modal){
        PageTab[] tabs=new PageTab[modal.chart.pageStates.length];
        boolean resetSource=game.number("notSrcCopyFlagEnable",0)!=0;
        ButtonArt.Setup previous=null;
        int previousX=0,previousY=0;
        for(int page=0;page<tabs.length;page++){
            String key="grow"+(page+1)+"Button";
            ButtonArt.Setup first=new ButtonArt.Setup(config,key+"1",previous,resetSource);
            ButtonArt.Setup second=new ButtonArt.Setup(config,key+"2",first,false);
            int x=config.number(key+"1PrintX",previousX),y=config.number(key+"1PrintY",previousY);
            int selectedX=config.number(key+"2PrintX",x),selectedY=config.number(key+"2PrintY",y);
            previous=second;
            previousX=selectedX+second.width;
            previousY=selectedY;
            if(modal.chart.pageStates[page]==0)continue;
            boolean selected=page==modal.page;
            boolean enabled=(modal.chart.pageStates[page]&~PAGE_NEW)!=PAGE_DISABLED;
            tabs[page]=new PageTab(selected?second:first,selected?selectedX:x,selected?selectedY:y,enabled);
        }
        return tabs;
    }

    private void renderControls(){
        tabRevision++;
        clearControls();
        lower=new MapLayer(getContext(),false);
        put(lower,0,0,canvasWidth,canvasHeight);
        tabs=pageTabs(assets.game,config,modal);
        for(int page=0;page<tabs.length;page++){
            PageTab tab=tabs[page];
            if(tab==null)continue;
            int target=page;
            GameButton button=control(tab.setup,hostText(R.string.engine_systemnnn_ctl_chart_chapter,page+1),
                ()->enqueue(PAGE,target),tab.x,tab.y,tab.enabled);
            tab.button=button;
            if(button!=null)button.prepare(this::invalidateComposition);
            if(button!=null&&(page==modal.page||warning())){
                button.setClickable(false);
                button.setFocusable(false);
            }
        }

        int currentPage=modal.selection<0?-1:modal.selection/Math.max(1,config.number("chartBlockSize",1000));
        String key="grow"+(currentPage+1)+"Button";
        tabMarkerX=config.number(key+"1PrintX",0);
        tabMarkerY=config.number(key+"1PrintY",0);
        if(currentPage==modal.page){
            tabMarkerX=config.number(key+"2PrintX",tabMarkerX);
            tabMarkerY=config.number(key+"2PrintY",tabMarkerY);
        }

        Bitmap window=sprite(config.value("filenameMiniMapWindow","ta_chart_minimap03"));
        int width=window==null?0:window.getWidth();
        int firstMiniWidth=config.number("miniMapSizeX1",config.number("miniMapSizeX",120));
        miniWindowX=config.number("miniMapWindowPrintX",(firstMiniWidth-width)/2);
        miniWindowY=config.number("miniMapWindowPrintY",-miniUpper);
        miniWindowWidth=dimension(config.number("miniMapWindowSizeX",width));

        Bitmap desc=sprite(config.value("filenameDescWindow","ta_chart_meswin1"));
        int descWidth=desc==null?0:desc.getWidth(),descHeight=desc==null?0:desc.getHeight();
        descTopX=config.number("upperDescWindowPrintX",(canvasWidth-descWidth)/2);
        descBottomX=config.number("lowerDescWindowPrintX",(canvasWidth-descWidth)/2);
        int topY=(canvasHeight/2-descHeight)/2;
        descTopY=config.number("upperDescWindowPrintY",topY);
        descBottomY=config.number("lowerDescWindowPrintY",canvasHeight-topY-descHeight);

        upper=new MapLayer(getContext(),true);
        put(upper,0,0,canvasWidth,canvasHeight);
        menuButton=cancelButton=null;
        if(warning()){
            Bitmap dialog=sprite(config.value("filenameDialog","ta_dialog_4"));
            if(dialog!=null)picture(config.value("filenameDialog","ta_dialog_4"),dialogX,dialogY,dialog.getWidth(),dialog.getHeight());
            ButtonArt.Setup yes=new ButtonArt.Setup(config,"dialogOk",null,false);
            ButtonArt.Setup no=new ButtonArt.Setup(config,"dialogCancel",yes,false);
            int x=config.number("dialogOkPrintX",0),y=config.number("dialogOkPrintY",0);
            if(confirmationVisible(assets,modal,canvasWidth,canvasHeight))
                control(yes,hostText(R.string.engine_systemnnn_ctl_chart_confirm),()->enqueue(CONFIRM,1),dialogX+x,dialogY+y,true);
            cancelButton=control(no,hostText(R.string.engine_systemnnn_ctl_chart_cancel),()->enqueue(CONFIRM,0),
                dialogX+config.number("dialogCancelPrintX",x+yes.width),dialogY+config.number("dialogCancelPrintY",y),true);
        }
        GameUiAssets.Config menu=assets.storyButtons.get("optionButton");
        if(options!=null&&menu!=null&&assets.game.number("optionButtonCreateFlag",1)!=0&&
            config.number("disableQuickSystemMenuButton",0)==0){
            ButtonArt.Setup art=new ButtonArt.Setup(menu,"optionButton",null,false);
            menuButton=control(art,hostText(R.string.engine_systemnnn_ctl_menu),()->enqueue(OPEN_OPTIONS,0),menu.number("optionButtonPrintX",0),
                menu.number("optionButtonPrintY",0),true);
            if(menuButton!=null&&warning()){
                menuButton.setClickable(false);
                menuButton.setFocusable(false);
            }
        }
    }

    private GameButton control(ButtonArt.Setup setup,String label,Runnable action,int x,int y,boolean enabled){
        GameButton.Spec spec=new GameButton.Spec(setup);
        Bitmap image=sprite(spec.image);
        if(!canDraw(image,spec.states[enabled?0:3])&&!canDraw(image,spec.states[0]))return null;
        GameButton button=animatedButton(spec,image,label,()->sound(spec.sound,spec.voice),action,x,y,setup.width,setup.height);
        button.setEnabled(enabled);
        return button;
    }

    private static boolean canDraw(Bitmap image,SpriteAnimation frame){
        return image!=null&&frame.x>=0&&frame.y>=0&&frame.x<image.getWidth()&&frame.y<image.getHeight();
    }

    private static boolean confirmationVisible(GameUiAssets assets,GameRunner.Modal modal,int width,int height){
        GameUiAssets.Config config=modal.assets.config;
        int dialogX=config.number("dialogX",236),dialogY=config.number("dialogY",160);
        Bitmap plate=find(assets,modal,config.value("filenameDialog","ta_dialog_4"));
        if(plate==null||!partVisible(plate,0,0,plate.getWidth(),plate.getHeight(),dialogX,dialogY,width,height))return false;
        GameButton.Spec spec=new GameButton.Spec(new ButtonArt.Setup(config,"dialogOk",null,false));
        return validHit(find(assets,modal,spec.image),spec,(long)dialogX+config.number("dialogOkPrintX",0),
            (long)dialogY+config.number("dialogOkPrintY",0),width,height);
    }

    private static boolean validHit(Bitmap image,GameButton.Spec spec,long x,long y,int width,int height){
        SpriteAnimation normal=spec.states[0];
        if(!partVisible(image,normal.x,normal.y,normal.w,normal.h,x,y,width,height))return false;
        long l=Math.max(Math.max(0,-x),spec.hitX),t=Math.max(Math.max(0,-y),spec.hitY);
        long r=Math.min(Math.min(normal.w,width-x),(long)spec.hitX+spec.hit.w);
        long b=Math.min(Math.min(normal.h,height-y),(long)spec.hitY+spec.hit.h);
        if(l>=r||t>=b)return false;
        if(!spec.pixelHit)return true;
        if(!canDraw(image,spec.hit))return false;
        return visiblePixels(image,spec.hit.x+l-spec.hitX,spec.hit.y+t-spec.hitY,
            spec.hit.x+r-spec.hitX,spec.hit.y+b-spec.hitY,1);
    }

    private static boolean partVisible(Bitmap image,int sx,int sy,int w,int h,long x,long y,int width,int height){
        if(image==null||sx<0||sy<0||w<=0||h<=0)return false;
        return visiblePixels(image,sx+Math.max(0,-x),sy+Math.max(0,-y),sx+Math.min(w,width-x),
            sy+Math.min(h,height-y),1);
    }

    private static boolean visiblePixels(Bitmap image,long left,long top,long right,long bottom,int alpha){
        if(left<0||top<0)return false;
        right=Math.min(right,image.getWidth());bottom=Math.min(bottom,image.getHeight());
        if(left>=right||top>=bottom)return false;
        if(!image.hasAlpha())return true;
        int w=(int)(right-left);
        int[] pixels=new int[w];
        for(int y=(int)top;y<bottom;y++){
            image.getPixels(pixels,0,w,(int)left,y,w,1);
            for(int pixel:pixels)if((pixel>>>24)>=alpha)return true;
        }
        return false;
    }

    private void updateDescriptionSide(){
        if(chart.pointerY<0)return;
        if(!descriptionOnTop&&chart.pointerY>=descBottomY-64)descriptionOnTop=true;
        else if(descriptionOnTop){
            Bitmap art=sprite(config.value("filenameDescWindow","ta_chart_meswin1"));
            int height=art==null?0:art.getHeight();
            if(chart.pointerY<descTopY+height+64)descriptionOnTop=false;
        }
    }

    private void invalidateMap(){
        invalidateComposition();
        if(lower!=null)lower.invalidate();
        if(upper!=null)upper.invalidate();
    }

    private void invalidateComposition(){
        visualRevision++;
        invalidate();
    }

    @Override void drawPanelBackground(Canvas canvas){
        composited=null;
        if(lower==null)return;
        long frame=frames();
        boolean useExact=usesComposer(backdropFrame)&&!openingRunning();
        boolean advance=foreground&&activeFrameReady()&&!overlayLocked&&isAttachedToWindow();
        if(useExact&&advance&&!compositionUnavailable){
            try{
                if(composition==null)composition=new ChartComposition(backdropFrame);
                composition.request(backdropFrame,frame);
            }catch(OutOfMemoryError error){
                compositionUnavailable=true;
                composition=null;
                Log.w("SystemNNN","Unable to allocate chart effect buffers",error);
            }
        }
        if(useExact&&composition!=null)composited=composition.current();
        int saved=canvas.save();
        canvas.scale(getWidth()/(float)canvasWidth,getHeight()/(float)canvasHeight);
        boolean animated;
        if(composited!=null){
            lower.plane(canvas,composited);
            animated=composition.animated&&!composition.pending&&!composition.failed;
        }else animated=lower.background(canvas,frame);
        canvas.restoreToCount(saved);
        if(animated&&advance)postInvalidateOnAnimation();
    }

    @Override protected boolean drawChild(Canvas canvas,View child,long drawingTime){
        if(composited!=null){
            if(child==lower||child==upper)return false;
            for(PageTab tab:tabs)if(tab!=null&&tab.button==child)return false;
        }
        return super.drawChild(canvas,child,drawingTime);
    }

    @Override protected void onDetachedFromWindow(){
        composition=null;
        composited=null;
        backdropFrame=null;
        super.onDetachedFromWindow();
    }

    private boolean drawGrow(Canvas canvas){
        boolean animated=false;
        for(PageTab tab:tabs){
            GameButton button=tab==null?null:tab.button;
            if(button==null||button.getWidth()<=0||button.getHeight()<=0)continue;
            int saved=canvas.save();
            canvas.translate(tab.x,tab.y);
            canvas.scale(tab.setup.width/(float)button.getWidth(),tab.setup.height/(float)button.getHeight());
            button.draw(canvas);
            canvas.restoreToCount(saved);
            int state=!button.isEnabled()?3:controlsBusy()&&!warning()?2:button.isPressed()||button.isHovered()?1:0;
            animated|=button.spec.states[state].count>1;
        }
        return animated;
    }

    /** One job owns the input pixels until the preview worker returns. */
    private final class ChartComposition {
        private final int width,height;
        private final Bitmap base,center,content;
        private final Canvas baseCanvas,centerCanvas,contentCanvas;
        private final Bitmap[] outputs=new Bitmap[2];
        private int front=-1,frontPage,frontScroll,frontTabs;
        private long frontEpoch=-1,lastRevision=-1,lastFrame=-1;
        private boolean pending,animated,failed;

        ChartComposition(ChartBackdrop.Frame backdrop){
            width=backdrop.rasterWidth;height=backdrop.rasterHeight;
            base=bitmap();center=bitmap();content=bitmap();
            baseCanvas=logicalCanvas(base);centerCanvas=logicalCanvas(center);contentCanvas=logicalCanvas(content);
        }
        private Bitmap bitmap(){return Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);}
        private Canvas logicalCanvas(Bitmap image){
            Canvas canvas=new Canvas(image);
            canvas.scale(width/(float)canvasWidth,height/(float)canvasHeight);
            return canvas;
        }

        Bitmap current(){
            return front>=0&&frontEpoch==compositionEpoch&&frontPage==modal.page&&frontScroll==chart.scroll&&
                frontTabs==tabRevision?outputs[front]:null;
        }

        void request(ChartBackdrop.Frame backdrop,long frame){
            if(pending||lastRevision==visualRevision&&(failed||!animated||lastFrame==frame))return;
            int page=modal.page,scroll=chart.scroll,tabs=tabRevision,write=front==0?1:0;
            long epoch=compositionEpoch;
            if(outputs[write]==null){
                outputs[write]=bitmap();
                outputs[write].setHasAlpha(false);
            }
            base.eraseColor(Color.BLACK);
            center.eraseColor(Color.TRANSPARENT);
            content.eraseColor(Color.TRANSPARENT);
            lower.tiles(baseCanvas,backdrop);
            animated=lower.center(centerCanvas,backdrop,frame);
            animated|=lower.roads(contentCanvas,graphFrames(frame))&&!warning();
            animated|=drawGrow(contentCanvas);
            animated|=upper.content(contentCanvas,frame);
            lastRevision=visualRevision;lastFrame=frame;
            pending=true;failed=false;
            backdrop.composer.compose(base,center,content,outputs[write],success->{
                if(composition!=this)return;
                pending=false;
                if(epoch==compositionEpoch&&page==modal.page&&scroll==chart.scroll&&tabs==tabRevision){
                    if(success){
                        front=write;frontPage=page;frontScroll=scroll;frontTabs=tabs;frontEpoch=epoch;
                    }else{
                        frontEpoch=-1;
                        failed=true;
                    }
                }
                invalidate();
            });
        }
    }

    private long frames(){
        if(foreground&&activeFrameReady()&&clockStart<0)clockStart=SystemClock.uptimeMillis();
        return (clockElapsed+(clockStart<0?0:SystemClock.uptimeMillis()-clockStart))/frameMs;
    }

    private long graphFrames(long frame){return (warningAt<0?frame:warningAt)-warningFrames;}

    private static int triangle(long frame,int length,int first,int last){
        int half=Math.max(1,length/2),t=(int)(frame%length);
        t=Math.min(t,length-t);
        return first+(last-first)*t/half;
    }

    private static int blendColor(int c1,int c2,int percent){
        return Color.rgb(Color.red(c1)+(Color.red(c2)-Color.red(c1))*percent/100,
            Color.green(c1)+(Color.green(c2)-Color.green(c1))*percent/100,
            Color.blue(c1)+(Color.blue(c2)-Color.blue(c1))*percent/100);
    }

    private static final class PageTab {
        final ButtonArt.Setup setup;
        final int x,y;
        final boolean enabled;
        GameButton button;
        PageTab(ButtonArt.Setup setup,int x,int y,boolean enabled){
            this.setup=setup;this.x=x;this.y=y;this.enabled=enabled;
        }
    }

    private final class MapLayer extends View {
        private final boolean top;
        private final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Rect source=new Rect();
        private final RectF destination=new RectF();
        private final ColorMatrixColorFilter grey;
        private final Matrix patternOrigin=new Matrix();
        private Bitmap centerBitmap;
        private BitmapShader centerPattern;
        MapLayer(Context context,boolean top){
            super(context);this.top=top;
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            ColorMatrix matrix=new ColorMatrix();matrix.setSaturation(0);
            grey=new ColorMatrixColorFilter(matrix);
        }

        @Override public boolean onTouchEvent(MotionEvent event){return !top&&touchMap(event);}

        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            long frame=frames();
            int restore=c.save();
            c.scale(getWidth()/(float)canvasWidth,getHeight()/(float)canvasHeight);
            boolean animated;
            if(!top){
                animated=roads(c,graphFrames(frame))&&!warning();
            }else{
                animated=content(c,frame);
                if(backdropFrame!=null)plane(c,backdropFrame.foreground);
            }
            c.restoreToCount(restore);
            if(animated&&foreground&&activeFrameReady()&&!overlayLocked)postInvalidateOnAnimation();
        }

        private boolean background(Canvas c,long frame){
            ChartBackdrop.Frame backdrop=backdropFrame;
            tiles(c,backdrop);
            if(backdrop!=null)plane(c,backdrop.beforeCenter);
            boolean animated=center(c,backdrop,frame);
            if(backdrop!=null)plane(c,backdrop.afterCenter);
            return animated;
        }

        private long scrollDistance(){
            return Math.max(0,chart.backExtent-(long)canvasHeight)*chart.scroll;
        }

        private void tiles(Canvas c,ChartBackdrop.Frame backdrop){
            if(scriptTiles&&backdrop!=null){
                int y=chart.limit<=0?0:-(int)(scrollDistance()/chart.limit);
                for(ChartBackdrop.Tile tile:backdrop.tiles){
                    if(tile==null)continue;
                    blit(c,tile.image,0,0,tile.image.getWidth(),tile.image.getHeight(),0,y,tile.width,tile.height,255);
                    y+=tile.height;
                }
            }else for(ChartView.Tile tile:chart.tiles){
                Bitmap opaque=modal.assets.opaqueImages.get(GameUiAssets.systemImageKey(tile.asset));
                image(c,opaque,0,tile.y);
            }
        }

        private boolean center(Canvas c,ChartBackdrop.Frame backdrop,long frame){
            long dy=chart.limit<=0?0:-(scrollDistance()*centerMulti)/(100L*chart.limit);
            if(scriptTiles&&backdrop!=null){
                ChartBackdrop.Tile tile=backdrop.center;
                if(tile==null)return false;
                repeatCenter(c,tile.image,tile.width,tile.height,frame,dy);
            }else{
                Bitmap center=sprite(chart.center);
                if(center==null)return false;
                repeatCenter(c,center,center.getWidth(),center.getHeight(),frame,dy);
            }
            return centerSpeed!=0;
        }

        private boolean content(Canvas c,long frame){
            boolean animated=pageMarks(c,frame);
            animated|=minimap(c,frame);
            if(!debugHideNodes)animated|=nodes(c,graphFrames(frame))&&!warning();
            percentages(c);
            description(c);
            if(capture==CAPTURE_MAIN){
                Bitmap drag=sprite(config.value("filenameCapturePic","ta_capture"));
                image(c,drag,pointerX+config.number("capturePrintX",0),pointerY+config.number("capturePrintY",0));
            }
            return animated;
        }

        private void repeatCenter(Canvas c,Bitmap center,int w,int h,long frame,long dy){
            if(centerBitmap!=center){
                centerBitmap=center;
                centerPattern=new BitmapShader(center,Shader.TileMode.REPEAT,Shader.TileMode.REPEAT);
            }
            int x=(int)Math.floorMod(-frame*centerSpeed,(long)canvasWidth);
            int y=dy<0?(int)(dy%h):(int)Math.min(dy,canvasHeight+(long)h)-h;
            patternOrigin.setScale(w/(float)center.getWidth(),h/(float)center.getHeight());
            patternOrigin.postTranslate(-(x%w),y);
            centerPattern.setLocalMatrix(patternOrigin);
            paint.setShader(centerPattern);
            paint.setAlpha(255);
            if(w>=h&&y<canvasHeight)c.drawRect(0,Math.max(0,y),canvasWidth,canvasHeight,paint);
            else for(int putY=y;putY<canvasHeight;putY+=h)
                c.drawRect(0,putY,canvasWidth,putY+w,paint);
            paint.setShader(null);
        }

        private boolean roads(Canvas c,long frame){
            int shift=chart.slotY-chart.scroll;
            int hot=blendColor(roadColors[3],roadColors[4],triangle(frame,roadFrames,0,100));
            boolean animated=false;
            paint.setStrokeWidth(1);
            for(ChartView.Road edge:chart.roads){
                int x1=edge.x1+chart.slotX,y1=edge.y1+shift,x2=edge.x2+chart.slotX,y2=edge.y2+shift;
                if(edge.style<0||edge.style>=roadColors.length)continue;
                paint.setColor(edge.style==4?hot:roadColors[edge.style]);
                c.drawLine(x1,y1,x2,y2,paint);
                c.drawLine(x1+1,y1,x2+1,y2,paint);
                c.drawLine(x1,y1+1,x2,y2+1,paint);
                if(edge.style==0||edge.style==4){
                    c.drawLine(x1-1,y1,x2-1,y2,paint);
                    c.drawLine(x1,y1-1,x2,y2-1,paint);
                }
                animated|=edge.style==4;
            }
            return animated;
        }

        private boolean pageMarks(Canvas c,long frame){
            boolean drawn=false;
            if(config.number("newMarkPrintFlag",0)!=0){
                Bitmap mark=sprite(newMarkerName);
                if(mark!=null&&newMarker.w>0&&newMarker.h>0){
                    int dx=config.number("newMarkPrintX",0),dy=config.number("newMarkPrintY",0);
                    for(int i=0;i<tabs.length;i++){
                        if(tabs[i]==null||(chart.pageStates[i]&PAGE_NEW)==0)continue;
                        int x=tabs[i].x+dx,y=tabs[i].y+dy;
                        destination.set(x,y,x+newMarker.w,y+newMarker.h);
                        drawn|=newMarker.draw(c,mark,destination,paint,frame,255);
                    }
                }
            }
            if(modal.selection>=0)
                image(c,sprite(config.value("filenameNowChart","ta_chartmap_cur01")),tabMarkerX,tabMarkerY);
            return drawn&&newMarker.active(frame);
        }

        private boolean minimap(Canvas c,long frame){
            image(c,sprite(chart.minimap),chart.miniBaseX,chart.miniBaseY);
            Bitmap dots=sprite(config.value("filenameMiniMapButton","ta_chart_minimap01"));
            boolean animated=false;
            if(dots!=null&&chart.extent>0&&chart.slotAreaWidth>0&&miniDotWidth>0&&miniDotHeight>0){
                boolean blink=((frame+1)/miniDotTime&1)!=0;
                for(ChartView.Node node:chart.nodes){
                    int state=0;
                    if(complete(node))state=1;
                    if(node.id==modal.selection)state=2;
                    if(node.access==1){
                        animated=true;
                        if(!blink)continue;
                        state=3;
                    }
                    int x=chart.miniX+(int)((long)node.x*chart.miniWidth/chart.slotAreaWidth)-miniDotWidth/2;
                    int y=chart.miniY+(int)((long)node.y*chart.miniHeight/chart.extent)-miniDotHeight/2;
                    blit(c,dots,state*miniDotWidth,0,miniDotWidth,miniDotHeight,x,y,miniDotWidth,miniDotHeight,255);
                }
            }
            Bitmap window=sprite(config.value("filenameMiniMapWindow","ta_chart_minimap03"));
            if(window==null||miniWindowWidth<=0)return animated;
            int center=window.getHeight()-miniUpper-miniLower,length=miniLength();
            if(center<0||length<=0)return animated;
            int x=chart.miniX+miniWindowX,y=chart.miniY+miniWindowY+miniOffset(chart.scroll);
            int opacity=triangle(frame+1,miniWindowTime,miniAlpha1,miniAlpha2);
            blit(c,window,0,0,miniWindowWidth,miniUpper,x,y-miniUpper,miniWindowWidth,miniUpper,opacity);
            blit(c,window,0,miniUpper,miniWindowWidth,center,x,y,miniWindowWidth,length,opacity);
            blit(c,window,0,miniUpper+center,miniWindowWidth,miniLower,x,y+length,miniWindowWidth,miniLower,opacity);
            return animated||miniAlpha1!=miniAlpha2;
        }

        private boolean nodes(Canvas c,long frame){
            int shift=chart.slotY-chart.scroll;
            int pulse=triangle(frame,pulseFrames,100,pulseScale);
            Bitmap unseen=sprite(chart.unseen),current=sprite(chart.current),complete=sprite(chart.complete);
            int w=chart.slotWidth,h=chart.slotHeight;
            int markW=complete==null?0:dimension(config.number("compSizeX",complete.getWidth()));
            int markH=complete==null?0:dimension(config.number("compSizeY",complete.getHeight()));
            int markX=config.number("compPrintX",60),markY=config.number("compPrintY",5);
            int markDX=config.number("compNextX",markW),markDY=config.number("compNextY",0);
            int markAlpha=alpha(config.number("compPercent",20));
            boolean animated=false;
            for(ChartView.Node node:chart.nodes){
                int cx=node.x+chart.slotX,cy=node.y+shift;
                if(cx< -w||cx>=canvasWidth||cy< -2*h||cy>=canvasHeight+h)continue;
                int size=100,opacity=255,markScale=100;
                switch(node.style){
                    case HIGHLIGHT:size=node.access==1?hoverScale:revisitScale;opacity=hoverAlpha;markScale=pulseScale;break;
                    case FORWARD:size=markScale=pulse;opacity=forwardAlpha;animated=true;break;
                    case CURRENT_HIGHLIGHT:size=revisitScale;markScale=pulseScale;break;
                    default:break;
                }
                int bw=w*size/100,bh=h*size/100,x=cx-bw/2,y=cy-bh/2;
                if(node.style<SEEN)blit(c,unseen,0,0,w,h,x,y,w,h,255);
                else{
                    Bitmap image=sprite(node.asset);
                    if(node.style==DISABLED)paint.setColorFilter(grey);
                    blit(c,image,node.sx,node.sy,node.sw,node.sh,x,y,bw,bh,opacity);
                    paint.setColorFilter(null);
                    if(node.style==CURRENT)image(c,current,x,y);
                    if(node.style==CURRENT_HIGHLIGHT)blit(c,current,0,0,w,h,x,y,bw,bh,255);
                }
                int routes=clamp(node.routes,1,8),seenRoutes=node.seen&((1<<routes)-1);
                if(complete==null||seenRoutes==0)continue;
                for(int route=0;route<routes;route++){
                    boolean seen=(seenRoutes&(1<<route))!=0;
                    int px=x+markX+route*markDX*markScale/100,py=y+markY+route*markDY;
                    blit(c,complete,0,0,markW,markH,px,py,markW,markH,seen?255:markAlpha);
                }
            }
            return animated;
        }

        private boolean complete(ChartView.Node node){
            int mask=(1<<clamp(node.routes,1,8))-1;
            return (node.seen&mask)==mask;
        }

        private void percentages(Canvas c){
            image(c,sprite(config.value("filenameTasseido","ta_chart_comp")),config.number("tasseidoBaseX",0),config.number("tasseidoBaseY",0));
            Bitmap digits=sprite(config.value("filenameChartFont","ta_chart_font01"));
            if(digits==null)return;
            int w=dimension(config.number("chartFontSizeX",11)),h=dimension(config.number("chartFontSizeY",18));
            int next=config.number("chartFontNextX",w);
            number(c,digits,w,h,next,config.number("totalTasseidoX",80),config.number("totalTasseidoY",20),chart.totalPercent);
            number(c,digits,w,h,next,config.number("tasseidoX",80),config.number("tasseidoY",34),chart.pagePercent);
        }

        private void number(Canvas c,Bitmap digits,int w,int h,int next,int x,int y,int value){
            if(value<0)return;
            boolean pad=config.number("chartFontUpZeroFlag",0)!=0;
            boolean left=config.number("chartFontLeftFlag",0)!=0;
            if(left){
                boolean seen=false;
                for(int div=100;div>0;div/=10){
                    int digit=value/div;
                    if(digit!=0||pad||seen||div==1){
                        blit(c,digits,digit*w,0,w,h,x,y,w,h,255);
                        seen=true;x+=next;
                    }
                    value%=div;
                }
            }else{
                x+=2*next;
                for(int i=0;i<3;i++){
                    blit(c,digits,(value%10)*w,0,w,h,x,y,w,h,255);
                    value/=10;
                    if(value==0&&!pad)break;
                    x-=next;
                }
            }
        }

        private void description(Canvas c){
            if(chart.description.length==0)return;
            int x=descriptionOnTop?descTopX:descBottomX,y=descriptionOnTop?descTopY:descBottomY;
            image(c,sprite(config.value("filenameDescWindow","ta_chart_meswin1")),x,y);
            paint.setAntiAlias(true);
            paint.setColor(descColor);
            paint.setTextSize(descFont);
            x+=descDeltaX;
            float baseline=y+descDeltaY-paint.ascent();
            for(String text:chart.description){
                if(text!=null&&!text.isEmpty())c.drawText(text,x,baseline,paint);
                baseline+=descNext;
            }
            paint.setAntiAlias(false);
        }

        private void image(Canvas c,Bitmap image,int x,int y){
            if(image!=null)blit(c,image,0,0,image.getWidth(),image.getHeight(),x,y,image.getWidth(),image.getHeight(),255);
        }

        private void plane(Canvas c,Bitmap image){
            if(image!=null)blit(c,image,0,0,image.getWidth(),image.getHeight(),0,0,canvasWidth,canvasHeight,255);
        }

        private void blit(Canvas c,Bitmap image,int sx,int sy,int w,int h,int x,int y,int dw,int dh,int alpha){
            if(image==null||w<=0||h<=0||dw<=0||dh<=0||sx<0||sy<0||alpha==0)return;
            int endX=Math.min(image.getWidth(),sx+w),endY=Math.min(image.getHeight(),sy+h);
            if(endX<=sx||endY<=sy)return;
            source.set(sx,sy,endX,endY);
            destination.set(x,y,x+(endX-sx)*((float)dw/w),y+(endY-sy)*((float)dh/h));
            paint.setAlpha(alpha);
            c.drawBitmap(image,source,destination,paint);
            paint.setAlpha(255);
        }
    }
}
