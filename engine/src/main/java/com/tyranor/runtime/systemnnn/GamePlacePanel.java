package com.tyranor.runtime.systemnnn;

import com.core.engine.R;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.function.IntConsumer;

/** CCommonSelectPlace uses the title's configured buttons and a separate background script. */
final class GamePlacePanel extends GameUiPanel {
    private final Runnable menu;
    private final boolean menuAllowed;
    private final PortraitLayer portraits;
    private final ArrayList<PreviewTarget> previewTargets=new ArrayList<>();
    private int preview;

    GamePlacePanel(Context context,GameUiAssets assets,GameRunner.Frame frame,Runnable click,Runnable menu,IntConsumer choose){
        super(context,assets,assets.place(frame.placeMode).value("filenameBG","idou_bg"),click);
        GameUiAssets.Config cfg=assets.place(frame.placeMode);
        this.menu=menu;
        menuAllowed=frame.optionsAllowed&&cfg.number("optionOK",1)!=0;
        canvas(frame.width,frame.height);
        opening(cfg,false);
        uiBackground(cfg,frame.placeMode,0);
        PortraitLayer layer=cfg.number("charaFlag",0)!=0?new PortraitLayer(cfg,frame):null;
        portraits=layer==null||layer.tiles.isEmpty()?null:layer;
        boolean portraitsAbove=cfg.number("charaPrintUpper",1)!=0;
        if(portraits!=null&&!portraitsAbove)put(portraits,0,0,canvasWidth,canvasHeight);
        int w=cfg.number("areaSizeX",160),h=cfg.number("areaSizeY",100);
        for(int i=0;i<frame.places.length;i++){
            if(frame.places[i]==0)continue;
            int place=i+1;
            String key="place"+place;
            String label=cfg.text(key+"Name",cfg.text("placeAppearVarName"+place,hostText(R.string.engine_systemnnn_ctl_place_number, place)));
            GameButton button=(GameButton)button(cfg,key,"place1",label,()->choose.accept(place),0,0,w,h);
            boolean enabled=frame.places[i]==2;
            button.setEnabled(enabled);
            if(!enabled)button.setClickable(false);
            if(enabled||cfg.number("autoPicCannotOk",0)!=0){
                int enter=cfg.number(key+"EnterSound",cfg.number("place1EnterSound",0))-1;
                previewTargets.add(new PreviewTarget(button,place,enter));
            }
        }
        if(portraits!=null&&portraitsAbove)put(portraits,0,0,canvasWidth,canvasHeight);
    }

    static String portraitAsset(GameUiAssets.Config config,int character){
        if(character<=0||config.number("charaFlag",0)==0)return "";
        return config.number("allCharaFlag",0)!=0?config.value("fileNameAllChara","ta_allchara"):
            config.value("fileNameCharaBase","ta_chara")+character;
    }

    @Override void uiActive(boolean active){
        super.uiActive(active);
        if(portraits!=null)portraits.active(active);
    }

    // CCommonSelectPlace::GetOnPlace checks the configured picture masks in place order.
    private void previewAt(MotionEvent event){
        for(PreviewTarget target:previewTargets){
            GameButton button=target.button;
            if(button.pictureHit(event.getX()-button.getLeft(),event.getY()-button.getTop())){
                preview(target.place,target.cue);
                return;
            }
        }
        preview(0,-1);
    }
    @Override void panelPointer(MotionEvent event){
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN)previewAt(event);
        else if(action==MotionEvent.ACTION_CANCEL)preview(0,-1);
    }
    @Override public boolean dispatchHoverEvent(MotionEvent event){
        if(event.getActionMasked()==MotionEvent.ACTION_HOVER_EXIT)preview(0,-1);
        else if(!openingRunning())previewAt(event);
        return super.dispatchHoverEvent(event)||!previewTargets.isEmpty();
    }
    private static final class PreviewTarget {
        final GameButton button;
        final int place,cue;
        PreviewTarget(GameButton button,int place,int cue){
            this.button=button;this.place=place;this.cue=cue;
        }
    }

    private void preview(int place,int cue){
        if(preview==place)return;
        preview=place;previewPlace(place);
        if(cue>=0)sound(cue,-1);
    }
    @Override boolean backOverlay(){if(menuAllowed)menu.run();return true;}

    private static final class Portrait {
        final Bitmap image;
        final SpriteAnimation animation;
        final RectF destination;
        Portrait(Bitmap image,SpriteAnimation animation,RectF destination){
            this.image=image;this.animation=animation;this.destination=destination;
        }
    }

    private final class PortraitLayer extends View {
        final ArrayList<Portrait> tiles=new ArrayList<>();
        private final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);
        private final int firstActiveFrame;
        private boolean foreground=true;
        private long activeSince=-1,elapsed,frame=1;

        PortraitLayer(GameUiAssets.Config cfg,GameRunner.Frame state){
            super(GamePlacePanel.this.getContext());
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            boolean shared=cfg.number("allCharaFlag",0)!=0,custom=cfg.number("charaCustomFlag",0)!=0;
            int width=Math.max(1,Math.min(4096,cfg.number("charaSizeX",32)));
            int height=Math.max(1,Math.min(4096,cfg.number("charaSizeY",32)));
            int offsetX=cfg.number("charaX",0),offsetY=cfg.number("charaY",0);
            int type=cfg.number("charaAnimeType",1),pattern=cfg.number("charaAnimePattern",1);
            int speed=cfg.number("charaAnimeSpeed",1);
            // PrintAllChara advances before drawing, including the entry fade's target frame.
            firstActiveFrame=cfg.number("enterFadeInType",0)!=0&&cfg.number("enterFadeInTime",10)>0?2:1;
            int count=Math.min(state.places.length,Math.min(state.placeCharacters.length,state.placePortraits.length));
            for(int i=0;i<count;i++){
                int character=state.placeCharacters[i];
                Bitmap image=state.placePortraits[i];
                if(state.places[i]!=2||character<=0||image==null)continue;
                int place=i+1;
                long x=(long)cfg.number("areaX"+place,0)+(custom?cfg.number("charaX"+place,offsetX):offsetX);
                long y=(long)cfg.number("areaY"+place,0)+(custom?cfg.number("charaY"+place,offsetY):offsetY);
                if(x>=canvasWidth||x+width<=0||y>=canvasHeight||y+height<=0)continue;
                long sourceX=shared?(long)(character-1)*width:0;
                if(sourceX>=image.getWidth())continue;
                // Each character owns a single column of the shared sheet, or a single row
                // of its own sheet. Never wrap to another character when art is incomplete.
                int available=shared?image.getHeight()/height:image.getWidth()/width;
                SpriteAnimation animation=new SpriteAnimation((int)sourceX,0,width,height,type,
                    Math.max(1,Math.min(pattern,available)),speed,!shared);
                tiles.add(new Portrait(image,animation,new RectF(x,y,x+width,y+height)));
            }
        }

        void active(boolean active){
            if(foreground==active)return;
            foreground=active;
            if(!active&&activeSince>=0){
                elapsed+=SystemClock.uptimeMillis()-activeSince;
                activeSince=-1;
            }
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas){
            boolean advancing=foreground&&activeFrameReady();
            if(advancing){
                long now=SystemClock.uptimeMillis();
                if(activeSince<0)activeSince=now;
                frame=firstActiveFrame+(elapsed+now-activeSince)/frameMs;
            }
            int state=canvas.save();
            canvas.scale(getWidth()/(float)canvasWidth,getHeight()/(float)canvasHeight);
            boolean animate=false;
            for(Portrait tile:tiles){
                tile.animation.draw(canvas,tile.image,tile.destination,paint,frame,255);
                animate|=tile.animation.active(frame);
            }
            canvas.restoreToCount(state);
            if(advancing&&animate)postInvalidateOnAnimation();
        }
    }
}
