package com.tyranor.runtime.systemnnn;

import com.core.engine.R;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;

import static com.tyranor.runtime.systemnnn.GameRunner.Modal.CHOOSE;
import static com.tyranor.runtime.systemnnn.GameRunner.Modal.CONFIRM;
import static com.tyranor.runtime.systemnnn.GameRunner.Modal.HOVER;
import static com.tyranor.runtime.systemnnn.GameRunner.Modal.PAGE;
import static com.tyranor.runtime.systemnnn.GameRunner.Modal.SELECT_HEROIN;
import static com.tyranor.runtime.systemnnn.GameRunner.Modal.SELECT_OBJECT;

/** SELECTHEROIN's portrait ring and SELECTOBJECT's type-owned button groups. */
final class GameAdvancedSelectionPanel extends GameUiPanel {
    interface Result {void action(int action,int value);}
    private static final int NEXT_ALLOWED=1,OK_ALLOWED=2;
    private final boolean heroine;
    private final Result result;
    private final GameUiAssets.Config config;
    private final int turnFrames;
    private final boolean objectHover,previewDisabledObjects;
    private GameRunner.Modal modal;
    private GameButton upButton,downButton,okButton,nextButton,menuButton;
    private Runnable menu,settings,backlog,deferred;
    private ArrayList<ObjectButton> objectButtons;
    private Carousel carousel;
    private boolean foreground=true,overlayLocked;
    private int pending=-1,wantedHover,turnDirection;
    private boolean turnPosted;
    private long turnSince=-1,turnElapsed,objectBegan=-1;

    GameAdvancedSelectionPanel(Context context,GameUiAssets assets,GameRunner.Modal modal,
                                int width,int height,Runnable click,Result result){
        super(context,assets,backgroundName(modal),click);
        if(modal.mode!=SELECT_HEROIN&&modal.mode!=SELECT_OBJECT)
            throw new IllegalArgumentException("Unsupported selection mode "+modal.mode);
        heroine=modal.mode==SELECT_HEROIN;
        config=modal.assets.config;this.result=result;
        turnFrames=Math.max(1,Math.min(1000,config.number("turnFrame",10)));
        objectHover=!heroine&&(config.has("onButtonVarName")||config.number("autoPicLoad",0)!=0);
        previewDisabledObjects=config.number("autoPicCannotOk",0)!=0;
        canvas(width,height);
        opening(config,false);
        uiBackground(config,modal.mode,heroine?1:modal.selection);
        update(modal);
    }

    void optionActions(Runnable menu,Runnable settings,Runnable backlog){
        this.menu=menu;this.settings=heroine?null:settings;
        this.backlog=!heroine&&config.number("backLogOk",0)!=0?backlog:null;
        render();
    }

    void overlayInput(boolean locked){
        overlayLocked=locked;
        if(locked)deferred=null;
    }

    void update(GameRunner.Modal next){
        GameRunner.Modal before=modal;
        if(before!=null&&before.mode!=next.mode)throw new IllegalArgumentException("Selection mode changed");
        modal=next;pending=-1;
        if(before==null||heroine||before.group!=next.group){
            wantedHover=next.selection;
            stopTurn();
            background(backgroundName(next));
            render();
        }else if(before.selection!=next.selection)previewPlace(next.selection);
        finishPending();
    }

    private static String backgroundName(GameRunner.Modal modal){
        String fallback=modal.assets.config.value("filenameBG",modal.mode==SELECT_HEROIN?"bg_selectheroin":"idou_bg");
        return modal.assets.variant==null?fallback:modal.assets.variant.value("filenameBG",fallback);
    }

    @Override Bitmap sprite(String name){return find(assets,modal,name);}

    private static Bitmap find(GameUiAssets assets,GameRunner.Modal modal,String name){
        if(name==null||name.isEmpty())return null;
        String key=GameUiAssets.systemImageKey(name);
        Bitmap bitmap=modal==null||modal.assets==null?null:modal.assets.images.get(key);
        return bitmap==null?assets.image(name):bitmap;
    }

    static String selectionFailure(GameUiAssets assets,GameRunner.Modal modal){
        if(modal.mode!=SELECT_HEROIN&&modal.mode!=SELECT_OBJECT)return null;
        if(modal.assets==null)return "Missing selection setup";
        if(modal.mode==SELECT_OBJECT){
            if(modal.assets.variant==null)return "Missing object type setup";
            for(ObjectButton item:objectLayout(assets,modal))
                if(item.row.enabled()&&item.usable())return null;
            return "No selectable original object artwork";
        }
        GameUiAssets.Config cfg=modal.assets.config;
        if((modal.group&NEXT_ALLOWED)!=0&&usable(assets,modal,new ButtonArt.Setup(cfg,"Next",null,false),""))return null;
        if((modal.group&OK_ALLOWED)!=0&&usable(assets,modal,new ButtonArt.Setup(cfg,"Ok",null,false),"")){
            GameUiAssets.Config paging=arrowSetup(assets,cfg);
            ButtonArt.Setup up=new ButtonArt.Setup(paging,"Up",null,false),down=new ButtonArt.Setup(paging,"Down",up,false);
            boolean turn=canTurn(cfg,modal.rows.length)&&(usable(assets,modal,up,"ta_upButton")||
                usable(assets,modal,down,"ta_downButton"));
            for(GameRunner.Modal.Row row:modal.rows)
                if(row.enabled()&&(row.id==modal.selection||turn)&&find(assets,modal,row.asset)!=null)return null;
        }
        return "No original heroine confirmation route";
    }

    private static boolean usable(GameUiAssets assets,GameRunner.Modal modal,ButtonArt.Setup art,String fallback){
        GameButton.Spec spec=new GameButton.Spec(art);
        return usable(find(assets,modal,spec.image.isEmpty()?fallback:spec.image),spec);
    }

    private static boolean usable(Bitmap image,GameButton.Spec spec){
        return usable(image,spec,1);
    }

    private static boolean drawable(Bitmap image,SpriteAnimation frame){
        return image!=null&&frame!=null&&frame.x>=0&&frame.y>=0&&frame.x<image.getWidth()&&frame.y<image.getHeight();
    }

    private static boolean usable(Bitmap image,GameButton.Spec spec,int alpha){
        if(!drawable(image,spec.states[0])||spec.hit.w<=0||spec.hit.h<=0)return false;
        if(!spec.pixelHit)return true;
        int x=spec.hit.x,y=spec.hit.y;
        if(x<0||y<0||x>=image.getWidth()||y>=image.getHeight())return false;
        int w=Math.min(spec.hit.w,image.getWidth()-x),h=Math.min(spec.hit.h,image.getHeight()-y);
        int[] pixels=new int[w];
        for(int i=0;i<h;i++){
            image.getPixels(pixels,0,w,x,y+i,w,1);
            for(int pixel:pixels)if((pixel>>>24)>=alpha)return true;
        }
        return false;
    }

    private boolean ready(){return foreground&&!overlayLocked&&turnDirection==0&&!controlsBusy()&&activeFrameReady()&&!openingRunning();}

    private void action(int action,int value){perform(()->publish(action,value));}

    private void perform(Runnable action){
        if(!ready())return;
        if(pending==HOVER){if(deferred==null)deferred=action;}
        else if(pending==-1)action.run();
    }

    private void publish(int action,int value){
        pending=action;
        result.action(action,value);
    }

    private void finishPending(){
        if(deferred!=null){
            Runnable action=deferred;deferred=null;
            perform(action);
        }else flushHover();
    }

    private void flushHover(){
        if(objectHover&&pending==-1&&ready()&&wantedHover!=modal.selection)publish(HOVER,wantedHover);
    }

    @Override void cancelLeave(){
        if(pending==HOVER){
            pending=-1;wantedHover=modal.selection;finishPending();
            return;
        }
        super.cancelLeave();
        pending=-1;deferred=null;
        stopTurn();
        routes(true);
        if(carousel!=null)carousel.invalidate();
    }

    @Override void uiActive(boolean active){
        super.uiActive(active);foreground=active;
        if(turnDirection!=0){
            if(!active&&turnSince>=0){turnElapsed+=SystemClock.uptimeMillis()-turnSince;turnSince=-1;}
            else if(active&&turnSince<0)turnSince=SystemClock.uptimeMillis();
            if(carousel!=null)carousel.invalidate();
        }
    }

    @Override boolean backOverlay(){
        if(ready()&&menu!=null){
            if(menuButton!=null)menuButton.performClick();
            else perform(menu);
        }
        return true;
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event){
        if(overlayLocked||turnDirection!=0||(pending!=-1&&pending!=HOVER))return true;
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_MOVE)hoverAt(event);
        return super.dispatchTouchEvent(event);
    }

    @Override public boolean dispatchHoverEvent(MotionEvent event){
        if(overlayLocked)return true;
        if(event.getActionMasked()==MotionEvent.ACTION_HOVER_EXIT){wantedHover=0;flushHover();}
        else hoverAt(event);
        return super.dispatchHoverEvent(event);
    }

    private void hoverAt(MotionEvent event){
        if(!objectHover||!ready())return;
        float x=event.getX()*canvasWidth/Math.max(1,getWidth()),y=event.getY()*canvasHeight/Math.max(1,getHeight());
        wantedHover=0;
        for(ObjectButton item:objectButtons){
            if(item.row.id>0&&(previewDisabledObjects||item.row.enabled())&&item.hit(x-item.x-item.spec.hitX,y-item.y-item.spec.hitY)){
                wantedHover=item.row.id;
                break;
            }
        }
        flushHover();
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent event){
        if(overlayLocked)return true;
        if(event.getActionMasked()==MotionEvent.ACTION_SCROLL&&ready()){
            float wheel=event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if(heroine&&wheel!=0){
                GameUiAssets.Config cfg=arrowSetup(assets,config);
                if(upButton!=null&&((wheel>0)!=(cfg.number("UpUpDownReverse",0)!=0)))return upButton.performClick();
                if(downButton!=null&&((wheel<0)!=(cfg.number("DownUpDownReverse",0)!=0)))return downButton.performClick();
            }else if(!heroine&&wheel>0&&backlog!=null){perform(backlog);return true;}
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override public void draw(Canvas canvas){
        if(!heroine&&objectBegan<0&&activeFrameReady()&&!openingRunning())objectBegan=SystemClock.uptimeMillis();
        super.draw(canvas);
    }

    private void render(){
        clearControls();
        upButton=downButton=okButton=nextButton=menuButton=null;
        if(heroine)renderHeroine();
        else{
            objectButtons=objectLayout(assets,modal);
            for(ObjectButton item:objectButtons)addObject(item);
        }
        menuButton=option("optionButton","systemMenu",hostText(R.string.engine_systemnnn_ctl_menu),menu,1);
        if(!heroine){
            option("quickConfigButton","config",hostText(R.string.engine_systemnnn_ctl_config),settings,0);
            option("quickBacklogButton","backlog",hostText(R.string.engine_systemnnn_ctl_backlog),backlog,0);
        }
    }

    private void renderHeroine(){
        carousel=new Carousel();
        put(carousel,0,0,canvasWidth,canvasHeight);
        String plate=config.value("filenamePlate","");
        Bitmap image=sprite(plate);
        if(image!=null)picture(plate,config.number("plateX",0),config.number("plateY",0),image.getWidth(),image.getHeight());
        if(canTurn(config,modal.rows.length)){
            GameUiAssets.Config cfg=arrowSetup(assets,config);
            ButtonArt.Setup up=new ButtonArt.Setup(cfg,"Up",null,false),down=new ButtonArt.Setup(cfg,"Down",up,false);
            upButton=control(up,"ta_upButton",hostText(R.string.engine_systemnnn_ctl_character_previous),()->beginTurn(1),
                config.number("UpPrintX",cfg.number("UpPrintX",0)),config.number("UpPrintY",cfg.number("UpPrintY",0)));
            downButton=control(down,"ta_downButton",hostText(R.string.engine_systemnnn_ctl_character_next),()->beginTurn(-1),
                config.number("DownPrintX",cfg.number("DownPrintX",0)),config.number("DownPrintY",cfg.number("DownPrintY",0)));
        }
        if((modal.group&OK_ALLOWED)!=0&&modal.page>=0&&modal.page<carousel.images.length&&carousel.images[modal.page]!=null)
            okButton=control(new ButtonArt.Setup(config,"Ok",null,false),"",hostText(R.string.engine_systemnnn_ctl_ok),()->action(CONFIRM,modal.selection),
                config.number("OkPrintX",0),config.number("OkPrintY",0));
        if((modal.group&NEXT_ALLOWED)!=0)
            nextButton=control(new ButtonArt.Setup(config,"Next",null,false),"",hostText(R.string.engine_systemnnn_ctl_next),()->action(CONFIRM,0),
                config.number("NextPrintX",0),config.number("NextPrintY",0));
    }

    private static GameUiAssets.Config arrowSetup(GameUiAssets assets,GameUiAssets.Config cfg){
        return cfg.number("updownButtonType",0)==1||cfg.number("useCommonUpDownSetup",0)!=0?assets.game:cfg;
    }

    private static boolean canTurn(GameUiAssets.Config cfg,int count){
        return count>0&&count>=cfg.number("minTurnNumber",1);
    }

    private GameButton option(String key,String target,String label,Runnable action,int fallbackCreate){
        if(action==null||assets.game.number(key+"CreateFlag",fallbackCreate)==0||
            !heroine&&config.number("disableQuick"+target+"Button",0)!=0)return null;
        GameUiAssets.Config setup=assets.storyButtons.get(key);
        if(setup==null)return null;
        return control(new ButtonArt.Setup(setup,key,null,false),"",label,()->perform(action),
            setup.number(key+"PrintX",0),setup.number(key+"PrintY",0));
    }

    private GameButton control(ButtonArt.Setup art,String fallback,String label,Runnable action,int x,int y){
        GameButton.Spec spec=new GameButton.Spec(art);
        Bitmap image=sprite(spec.image.isEmpty()?fallback:spec.image);
        if(!usable(image,spec))return null;
        return animatedButton(spec,image,label,()->sound(spec.sound,spec.voice),action,x,y,art.width,art.height);
    }

    private void beginTurn(int direction){
        if(!ready()||pending!=-1||!canTurn(config,modal.rows.length))return;
        turnDirection=direction;turnSince=SystemClock.uptimeMillis();turnElapsed=0;turnPosted=false;
        routes(false);
        carousel.invalidate();
    }

    private void finishTurn(){
        if(!isAttachedToWindow()||turnDirection==0||pending!=-1)return;
        if(!foreground){turnPosted=false;return;}
        int next=Math.floorMod(modal.page+turnDirection,modal.rows.length);
        if(next==modal.page){stopTurn();routes(true);carousel.invalidate();}
        else publish(PAGE,next);
    }

    private void stopTurn(){turnDirection=0;turnSince=-1;turnElapsed=0;turnPosted=false;}

    private void routes(boolean visible){
        if(okButton!=null)okButton.setVisibility(visible?VISIBLE:INVISIBLE);
        if(nextButton!=null)nextButton.setVisibility(visible?VISIBLE:INVISIBLE);
    }

    private final class Carousel extends View {
        final Bitmap[] images=new Bitmap[modal.rows.length];
        private final int[] depth=new int[images.length],order=new int[images.length],alpha=new int[images.length];
        private final int[] x=new int[images.length],y=new int[images.length];
        private final int circleX=config.number("circleX",250),circleY=config.number("circleY",120);
        private final int far=config.number("percent1",20),near=config.number("percent2",110),delta=config.number("deltaAngle",15);
        private final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Rect source=new Rect();
        private final RectF target=new RectF();

        Carousel(){
            super(GameAdvancedSelectionPanel.this.getContext());
            for(int i=0;i<images.length;i++)images[i]=sprite(modal.rows[i].asset);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onDraw(Canvas canvas){
            int count=images.length;
            if(count==0)return;
            int turn=turnDirection==0?0:(int)Math.min(turnFrames,
                (turnElapsed+(turnSince<0?0:SystemClock.uptimeMillis()-turnSince))/frameMs);
            int divide=360/count;
            int current=turn>=turnFrames?Math.floorMod(modal.page+turnDirection,count):modal.page;
            int rotation=turn>=turnFrames?0:turnDirection*(turn*divide/turnFrames);
            for(int i=0;i<count;i++){
                int slot=Math.floorMod(current-i,count);
                int angle=(int)Math.floorMod(90L+delta+slot*divide+rotation,360L);
                double radians=angle*(3.14159*2/360.0);
                double sine=Math.sin(radians);
                order[i]=i;depth[i]=(int)(sine*100+.5);
                x[i]=(int)(Math.cos(radians)*circleX+.5);
                y[i]=(int)((1-sine)*circleY/2+.5);
                long opacity=(long)((sine+1)*((long)near-far)/2)+far;
                alpha[i]=(int)Math.max(1,Math.min(100,opacity));
            }
            for(int j=0;j<count-1;j++)for(int i=j+1;i<count;i++)
                if(depth[order[j]]>depth[order[i]]){int temp=order[j];order[j]=order[i];order[i]=temp;}
            int save=canvas.save();
            canvas.scale(getWidth()/(float)canvasWidth,getHeight()/(float)canvasHeight);
            for(int i:order){
                Bitmap art=images[i];
                if(art==null)continue;
                int w=art.getWidth(),h=art.getHeight();
                if(alpha[i]<100){w=Math.min(800,w);h=Math.min(600,h);}
                paint.setAlpha(alpha[i]*255/100);
                source.set(0,0,w,h);target.set(x[i],y[i],(long)x[i]+w,(long)y[i]+h);
                canvas.drawBitmap(art,source,target,paint);
            }
            canvas.restoreToCount(save);
            if(foreground&&turnDirection!=0){
                if(turn<turnFrames)postInvalidateOnAnimation();
                else if(!turnPosted){turnPosted=true;post(GameAdvancedSelectionPanel.this::finishTurn);}
            }
        }
    }

    private static final class ObjectButton {
        final GameRunner.Modal.Row row;
        final GameButton.Spec spec;
        final Bitmap image;
        final int x,y;
        ObjectButton(GameRunner.Modal.Row row,GameButton.Spec spec,Bitmap image,int x,int y){
            this.row=row;this.spec=spec;this.image=image;this.x=x;this.y=y;
        }
        boolean usable(){return GameAdvancedSelectionPanel.usable(image,spec,1);}
        boolean hit(float x,float y){
            if(x<0||y<0||x>=spec.hit.w||y>=spec.hit.h)return false;
            if(!spec.pixelHit)return true;
            int sx=spec.hit.x+(int)x,sy=spec.hit.y+(int)y;
            return sx>=0&&sy>=0&&sx<image.getWidth()&&sy<image.getHeight()&&(image.getPixel(sx,sy)>>>24)!=0;
        }
    }

    private static ArrayList<ObjectButton> objectLayout(GameUiAssets assets,GameRunner.Modal modal){
        ArrayList<ObjectButton> result=new ArrayList<>();
        if(modal.assets.variant==null)return result;
        GameUiAssets.Config cfg=modal.assets.variant;
        int count=Math.max(0,Math.min(1024,cfg.number("objectNumber",0)));
        int adds=Math.max(0,Math.min(1024,cfg.number("addObjectNumber",0)));
        SparseArray<GameRunner.Modal.Row> rows=new SparseArray<>(modal.rows.length);
        for(GameRunner.Modal.Row row:modal.rows)rows.put(row.id,row);
        int[] x=new int[count],y=new int[count];
        ButtonArt.Setup previous=null;
        for(int i=0;i<count;i++){
            String key="object"+(i+1);
            x[i]=cfg.number(key+"PrintX",i==0?0:x[i-1]+previous.width);
            y[i]=cfg.number(key+"PrintY",i==0?0:y[i-1]+previous.height);
            previous=new ButtonArt.Setup(cfg,key,previous,false);
            addObject(result,assets,modal,rows.get(i+1),previous,x[i],y[i]);
        }
        previous=null;
        int dx=modal.assets.config.number("addPrintX",0)+cfg.number("addPrintX",0);
        int dy=modal.assets.config.number("addPrintY",0)+cfg.number("addPrintY",0);
        for(int i=1;i<=adds;i++){
            previous=new ButtonArt.Setup(cfg,"add"+i,previous,false);
            GameRunner.Modal.Row row=rows.get(-i);
            if(row==null)continue;
            int parent;
            try{parent=Integer.parseInt(row.detail)-1;}catch(NumberFormatException invalid){continue;}
            if(parent>=0&&parent<count)addObject(result,assets,modal,row,previous,x[parent]+dx,y[parent]+dy);
        }
        return result;
    }

    private static void addObject(ArrayList<ObjectButton> layout,GameUiAssets assets,GameRunner.Modal modal,
                                  GameRunner.Modal.Row row,ButtonArt.Setup art,int x,int y){
        if(row==null)return;
        GameButton.Spec spec=new GameButton.Spec(art);
        Bitmap bitmap=find(assets,modal,row.asset.isEmpty()?spec.image:row.asset);
        if(drawable(bitmap,spec.states[0])||!row.enabled()&&drawable(bitmap,spec.states[3]))
            layout.add(new ObjectButton(row,spec,bitmap,x,y));
    }

    private void addObject(ObjectButton item){
        GameButton.Spec spec=item.spec;
        String label=item.row.label.isEmpty()?hostText(item.row.id>0?R.string.engine_systemnnn_ctl_object_number:R.string.engine_systemnnn_ctl_attachment_number, Math.abs(item.row.id)):item.row.label;
        GameButton button=animatedButton(spec,item.image,label,()->sound(spec.sound,spec.voice),
            ()->action(CHOOSE,item.row.id),item.x,item.y,spec.states[0].w,spec.states[0].h);
        button.setEnabled(item.row.enabled());
        button.setClickable(false);button.setFocusable(false);
        button.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        if(item.row.enabled()&&item.usable()){
            View hit=new View(getContext()){
                private boolean inMask(MotionEvent event){
                    return item.hit(event.getX()*spec.hit.w/Math.max(1,getWidth()),
                        event.getY()*spec.hit.h/Math.max(1,getHeight()));
                }
                @Override public boolean onTouchEvent(MotionEvent event){
                    if(event.getActionMasked()==MotionEvent.ACTION_DOWN&&(!ready()||!inMask(event)))return false;
                    boolean handled=super.onTouchEvent(event);
                    button.setPressed(isPressed()&&inMask(event));
                    return handled;
                }
                @Override public boolean onHoverEvent(MotionEvent event){
                    button.setHovered(event.getActionMasked()!=MotionEvent.ACTION_HOVER_EXIT&&inMask(event));
                    return super.onHoverEvent(event);
                }
                @Override public CharSequence getAccessibilityClassName(){return "android.widget.Button";}
            };
            hit.setSoundEffectsEnabled(false);hit.setFocusable(true);hit.setContentDescription(label);
            hit.setOnClickListener(v->{
                long wait=(item.row.id>0?2:1)*(long)frameMs;
                if(objectBegan>=0&&SystemClock.uptimeMillis()-objectBegan>=wait)button.performClick();
            });
            put(hit,item.x+spec.hitX,item.y+spec.hitY,Math.min(4096,spec.hit.w),Math.min(4096,spec.hit.h));
        }
    }
}
