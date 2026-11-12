package com.tyranor.runtime.systemnnn;

import com.core.engine.R;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.MotionEvent;
import android.view.View;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;

/** The exit warning shares the system menu's captured scene and background script. */
final class GameSystemMenuPanel extends GameUiPanel {
    private static final int STANDARD_BUTTONS=9,MAX_OPTIONS=256;
    private View cancelWarning;
    private boolean preparingExtension;

    GameSystemMenuPanel(Context context,GameUiAssets assets,String background,Runnable click) {
        super(context,assets,background,click);
    }

    static int optionCount(GameUiAssets.Config config){
        int options=boundedCount(config.number("optionNumber",STANDARD_BUTTONS));
        int extra=config.number("extModeNumber",0)>0?boundedCount(config.number("extButtonNumber",1)):0;
        return Math.min(options,basicCount(config,options)+extra);
    }

    private static int basicCount(GameUiAssets.Config config,int options){
        return boundedCount(config.number("basicButtonNumber",options));
    }

    private static int boundedCount(int count){return Math.max(0,Math.min(MAX_OPTIONS,count));}
    boolean preparingExtension(){return preparingExtension;}
    void preparingExtension(boolean pending){preparingExtension=pending;}
    @Override public boolean dispatchTouchEvent(MotionEvent event){
        return preparingExtension||super.dispatchTouchEvent(event);
    }

    /** CCommonSystemMenu uses CMenuButtonSetup's first layout and dispatches expOptionMode from slot 10. */
    void addExtendedModes(Set<Integer> standardSlots,IntPredicate modeSupported,IntPredicate modeEnabled,IntConsumer open){
        GameUiAssets.Config config=assets.system;
        int limit=optionCount(config);
        if(limit<=STANDARD_BUTTONS)return;
        int basic=basicCount(config,boundedCount(config.number("optionNumber",STANDARD_BUTTONS)));
        int nextX=config.number("menuNextX",0),nextY=config.number("menuNextY",config.number("menu1Button1SizeY",16));
        int x=0,y=0;
        ButtonArt.Setup previous=null;
        for(int slot=1;slot<=limit;slot++){
            String key="menu1Button"+slot;
            x=config.number(key+"PrintX",slot==1?0:x+nextX);
            y=config.number(key+"PrintY",slot==1?0:y+nextY);
            previous=new ButtonArt.Setup(config,key,previous,false);
            if(slot<=STANDARD_BUTTONS||standardSlots.contains(slot)||config.number(key+"ExistFlag",slot<=basic?1:0)==0)continue;
            int mode=config.number("expOptionMode"+slot,0);
            if(mode<=0||mode>=256||!modeSupported.test(mode))continue;
            GameButton.Spec spec=new GameButton.Spec(previous);
            Bitmap image=sprite(spec.image);
            boolean enabled=config.number(key+"EnableFlag",1)!=0&&modeEnabled.test(mode)&&hasHit(image,spec);
            if(!hasFrame(image,spec.states[0])&&(enabled||!hasFrame(image,spec.states[3])))continue;
            String label=config.text(key+"Name",hostText(R.string.engine_systemnnn_ctl_menu_item, slot));
            GameButton button=animatedButton(spec,image,label,()->sound(spec.sound,spec.voice),
                ()->open.accept(mode),x,y,previous.width,previous.height);
            button.setEnabled(enabled);
        }
    }

    private static boolean hasFrame(Bitmap image,SpriteAnimation frame){
        return image!=null&&frame.x>=0&&frame.y>=0&&frame.x<image.getWidth()&&frame.y<image.getHeight();
    }

    private static boolean hasHit(Bitmap image,GameButton.Spec spec){
        if(!hasFrame(image,spec.states[0]))return false;
        int left=Math.max(0,spec.hitX),top=Math.max(0,spec.hitY);
        long right=Math.min(spec.states[0].w,(long)spec.hitX+spec.hit.w);
        long bottom=Math.min(spec.states[0].h,(long)spec.hitY+spec.hit.h);
        if(right<=left||bottom<=top)return false;
        if(!spec.pixelHit)return true;
        if(!hasFrame(image,spec.hit))return false;
        long fromX=spec.hit.x+(long)left-spec.hitX,fromY=spec.hit.y+(long)top-spec.hitY;
        int toX=(int)Math.min(image.getWidth(),spec.hit.x+right-spec.hitX);
        int toY=(int)Math.min(image.getHeight(),spec.hit.y+bottom-spec.hitY);
        if(fromX>=toX||fromY>=toY)return false;
        if(!image.hasAlpha())return true;
        int width=toX-(int)fromX;
        int[] row=new int[width];
        for(int y=(int)fromY;y<toY;y++){
            image.getPixels(row,0,width,(int)fromX,y,width,1);
            for(int pixel:row)if((pixel>>>24)!=0)return true;
        }
        return false;
    }

    void confirmExit(Runnable confirm,Runnable cancel) {
        if(cancelWarning!=null)return;
        GameUiAssets.Config cfg=assets.system;
        boolean printMenu=cfg.number("warningMenuPrintFlag",1)!=0;
        GameUiPanel warning=new GameUiPanel(getContext(),assets,null,click);
        warning.transparentBackground();
        warning.canvas(canvasWidth,canvasHeight);
        warning.sound(this::sound);

        int x=cfg.number("dialogX",331),y=cfg.number("dialogY",277);
        if(printMenu||cfg.number("backScriptFlag",0)==0)
            warning.picture(cfg.value("filenameDialog","ta_dialog_quit"),x,y,
                cfg.number("dialogSizeX",618),cfg.number("dialogSizeY",143));
        View ok=warning.buttonAt(cfg,"dialogOk","dialogOk",hostText(R.string.engine_systemnnn_ctl_menu_confirm_title),confirm,x,y,125,54,192,72);
        cancelWarning=warning.buttonAt(cfg,"dialogCancel","dialogCancel",hostText(R.string.engine_systemnnn_ctl_menu_keep_playing),cancel,x,y,323,54,192,72);
        if(!printMenu){ok.setAlpha(0f);cancelWarning.setAlpha(0f);}

        for(int i=0;i<getChildCount();i++){
            View child=getChildAt(i);
            if(child instanceof GameUiBackground)continue;
            child.setFocusable(false);
            child.setClickable(false);
            child.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            if(!printMenu)child.setVisibility(INVISIBLE);
        }
        addView(warning,new LayoutParams(-1,-1));
        int cue=cfg.number("exitWarningSound",0);
        if(cue>0)sound(cue-1,-1);
    }

    @Override boolean backOverlay() {
        if(cancelWarning==null)return false;
        cancelWarning.performClick();
        return true;
    }
}
