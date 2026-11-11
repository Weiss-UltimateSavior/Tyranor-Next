package com.tyranor.runtime.systemnnn;

import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.FrameLayout;
import android.widget.TextView;
import com.core.engine.EngineUiText;
import com.core.engine.R;
import java.util.*;

/** Transparent CSceneOptionButton layer. No Android toolbar is drawn over original game UI. */
final class GameStoryControls extends FrameLayout {
    interface Listener {void begin(GameStoryControlsModel.Action action);void sound(int se,int voice);void action(GameStoryControlsModel.Action action,boolean target);}
    private final Listener listener;
    private final GameStoryControlsModel model;
    private final List<ArtButton> buttons=new ArrayList<>();
    private int canvasWidth=1280,canvasHeight=720,period=20;
    private boolean typing=true,automatic,skipping;
    private ArtButton clicked;
    private Runnable dispatch;
    private GameChoicePanel choice;
    GameStoryControls(Context context,GameUiAssets assets,Listener listener){
        super(context);this.listener=listener;setSoundEffectsEnabled(false);setClickable(false);
        model=new GameStoryControlsModel(assets.game,assets.printMessage,assets.storyButtons);
        for(GameStoryControlsModel.Button b:model.buttons){
            ArtButton view=new ArtButton(b,assets.image(b.art.image));buttons.add(view);addView(view,new LayoutParams(1,1));
        }
        quickSlot(false);
        if(!model.original){
            String title=EngineUiText.get(context,R.string.engine_systemnnn_ctl_menu);
            TextView menu=new TextView(context);menu.setText(title);menu.setTextColor(Color.WHITE);menu.setGravity(Gravity.CENTER);
            menu.setContentDescription(title);menu.setSoundEffectsEnabled(false);
            menu.setOnClickListener(v->listener.action(GameStoryControlsModel.Action.MENU,false));
            addView(menu,new LayoutParams(140,48,Gravity.BOTTOM|Gravity.RIGHT));
        }
    }
    boolean original(){return model.original;}
    boolean clicking(){return clicked!=null;}
    private String actionLabel(GameStoryControlsModel.Action action){
        int label;
        switch(action){
        case QUICK_SAVE:label=R.string.engine_systemnnn_ctl_quick_save;break;
        case QUICK_LOAD:label=R.string.engine_systemnnn_ctl_quick_load;break;
        case CONFIG:label=R.string.engine_systemnnn_ctl_config;break;
        case BACKLOG:label=R.string.engine_systemnnn_ctl_backlog;break;
        case SAVE:label=R.string.engine_systemnnn_ctl_save;break;
        case LOAD:label=R.string.engine_systemnnn_ctl_load;break;
        case AUTO:label=R.string.engine_systemnnn_ctl_auto;break;
        case SKIP:label=R.string.engine_systemnnn_ctl_skip;break;
        case HIDE:label=R.string.engine_systemnnn_ctl_hide;break;
        default:label=R.string.engine_systemnnn_ctl_menu;break;
        }
        return EngineUiText.get(getContext(),label);
    }
    void quickSlot(boolean available){
        for(ArtButton b:buttons)if(b.spec.action==GameStoryControlsModel.Action.QUICK_LOAD)b.setEnabled(available);
    }
    void cancelClick(){
        if(dispatch!=null)removeCallbacks(dispatch);
        dispatch=null;clicked=null;
        for(ArtButton b:buttons){b.setClickable(!typing);b.invalidate();}
    }
    void canvas(int width,int height){canvasWidth=Math.max(1,width);canvasHeight=Math.max(1,height);requestLayout();}
    void frame(GameRunner.Frame frame,boolean typing,GameChoicePanel choice){
        this.choice=choice;
        boolean choiceButtons=choice!=null&&choice.storyButtonsAllowed();
        this.typing=!choiceButtons&&(typing||frame==null||frame.wait!=NativeGame.DIALOGUE);
        if(frame!=null&&frame.width>0&&frame.height>0&&(canvasWidth!=frame.width||canvasHeight!=frame.height))canvas(frame.width,frame.height);
        if(frame!=null)period=Math.max(1,frame.frameMs);
        boolean visible=frame!=null&&frame.optionsAllowed&&
            (frame.wait==NativeGame.DIALOGUE||
             (frame.wait==NativeGame.DRAW||frame.wait==NativeGame.OVERLAP)&&model.original&&model.drawDuringTransitions);
        setVisibility(visible||choiceButtons?VISIBLE:GONE);
        for(ArtButton button:buttons){
            GameStoryControlsModel.Action action=button.spec.action;
            boolean show=!choiceButtons||action==GameStoryControlsModel.Action.MENU||
                action==GameStoryControlsModel.Action.QUICK_SAVE&&choice.quickSlotAllowed(true)||
                action==GameStoryControlsModel.Action.QUICK_LOAD&&choice.quickSlotAllowed(false);
            button.setVisibility(show?VISIBLE:GONE);
            button.setClickable(show&&!this.typing&&clicked==null);
        }
    }
    void modes(boolean auto,boolean skip){automatic=auto;skipping=skip;invalidate();}
    int contentRight(int x,int y,int bottom,int fallback){
        int right=fallback;
        for(GameStoryControlsModel.Button b:model.buttons)
            if(b.x>x+80&&b.y<bottom&&b.y+b.height>y)right=Math.min(right,b.x-8);
        return right;
    }
    @Override protected void onMeasure(int ws,int hs){
        float sx=MeasureSpec.getSize(ws)/(float)canvasWidth,sy=MeasureSpec.getSize(hs)/(float)canvasHeight;
        for(ArtButton v:buttons){GameStoryControlsModel.Button b=v.spec;LayoutParams p=(LayoutParams)v.getLayoutParams();
            p.leftMargin=Math.round(b.left()*sx);p.topMargin=Math.round(b.top()*sy);
            p.width=Math.max(1,Math.round((b.right()-b.left())*sx));p.height=Math.max(1,Math.round((b.bottom()-b.top())*sy));}
        super.onMeasure(ws,hs);
    }
    private void click(ArtButton b){
        if(typing||clicked!=null||!b.isEnabled()||choice!=null&&choice.confirming())return;
        clicked=b;
        for(ArtButton button:buttons)button.setClickable(false);
        boolean target=b.spec.action==GameStoryControlsModel.Action.AUTO?!automatic:!skipping;
        listener.begin(b.spec.action);listener.sound(b.spec.sound,b.spec.voice);
        b.invalidate();dispatch=()->{cancelClick();listener.action(b.spec.action,target);};
        postDelayed(dispatch,Math.min(10000L,(long)b.spec.clickTime*period));
    }
    @Override protected void onDetachedFromWindow(){cancelClick();super.onDetachedFromWindow();}
    private final class ArtButton extends View {
        final GameStoryControlsModel.Button spec;final Bitmap image;final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        final SpriteAnimation[] sprites;
        final RectF dest=new RectF();
        int state=-1;
        long stateSince;
        ArtButton(GameStoryControlsModel.Button spec,Bitmap image){super(GameStoryControls.this.getContext());this.spec=spec;this.image=image;
            sprites=SpriteAnimation.buttonStates(spec.art,spec.width,spec.height);
            setContentDescription(actionLabel(spec.action));setSoundEffectsEnabled(false);setFocusable(true);setOnClickListener(v->click(this));}
        @Override public CharSequence getAccessibilityClassName(){return "android.widget.Button";}
        @Override public boolean onTouchEvent(MotionEvent e){
            if(typing||clicked!=null)return false;
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN){
                float sx=getWidth()/(float)(spec.right()-spec.left()),sy=getHeight()/(float)(spec.bottom()-spec.top());
                int x=(int)(e.getX()/sx)+spec.left()-spec.x-spec.checkDeltaX;
                int y=(int)(e.getY()/sy)+spec.top()-spec.y-spec.checkDeltaY;
                if(x<0||y<0||x>=spec.checkWidth||y>=spec.checkHeight)return false;
                int px=spec.checkX+x,py=spec.checkY+y;
                if(spec.printMode!=0&&image!=null&&(px<0||py<0||px>=image.getWidth()||py>=image.getHeight()||Color.alpha(image.getPixel(px,py))==0))return false;
            }
            return super.onTouchEvent(e);
        }
        @Override protected void drawableStateChanged(){super.drawableStateChanged();invalidate();}
        @Override protected void onDraw(Canvas canvas){
            int mode=!isEnabled()?3:(isPressed()||clicked==this)?2:isHovered()?1:0;
            long now=SystemClock.uptimeMillis();
            if(mode!=state){state=mode;stateSince=now;}
            long elapsed=(now-stateSince)/period;
            float sx=getWidth()/(float)(spec.right()-spec.left()),sy=getHeight()/(float)(spec.bottom()-spec.top());
            dest.set((spec.x-spec.left())*sx,(spec.y-spec.top())*sy,(spec.x-spec.left()+spec.width)*sx,(spec.y-spec.top()+spec.height)*sy);
            SpriteAnimation sprite=sprites[mode];
            if(sprite.draw(canvas,image,dest,paint,elapsed,255)){
                if(sprite.active(elapsed))postInvalidateOnAnimation();
            }else if(mode==3){
                sprites[0].draw(canvas,image,dest,paint,0,105);
            }
        }
    }
}
