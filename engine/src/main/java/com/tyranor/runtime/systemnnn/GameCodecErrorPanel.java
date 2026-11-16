package com.tyranor.runtime.systemnnn;

import android.content.Context;
import com.core.engine.R;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import java.util.function.IntConsumer;

/** The game's CODECERROR artwork and inherited not/can/exit button group. */
final class GameCodecErrorPanel extends GameUiPanel {
    static final int MODE=32;
    private static final String[] TAGS={"notButton","canButton","exitButton"};
    private final GameUiAssets.ModeAssets data;
    final GameRunner.MovieError error;
    private final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);

    private static final class ButtonPosition {
        final ButtonArt.Setup setup;
        final int x,y;
        ButtonPosition(ButtonArt.Setup setup,int x,int y){this.setup=setup;this.x=x;this.y=y;}
    }

    private static ButtonPosition[] buttonPositions(GameUiAssets.Config cfg){
        ButtonPosition[] positions=new ButtonPosition[TAGS.length];
        ButtonArt.Setup previous=null;
        int x=0,y=0;
        for(int i=0;i<TAGS.length;i++){
            String key=TAGS[i];
            ButtonArt.Setup setup=new ButtonArt.Setup(cfg,key,previous,false);
            x=cfg.number(key+"PrintX",previous==null?0:x+previous.width);
            y=cfg.number(key+"PrintY",previous==null?0:y+previous.height);
            positions[i]=new ButtonPosition(setup,x,y);
            previous=setup;
        }
        return positions;
    }

    static boolean available(GameUiAssets assets,GameUiAssets.ModeAssets data){
        GameUiAssets.Config cfg=data.config;
        if(cfg.number("backScriptFlag",0)==0&&image(assets,data,cfg.value("filenameBG","CodecError"))==null)return false;
        if(cfg.number("dialogFlag",1)!=0&&image(assets,data,cfg.value("filenameDialog","ta_dialog5"))==null)return false;
        ButtonPosition[] positions=buttonPositions(cfg);
        for(int i=0;i<positions.length;i++){
            if(i!=1){
                ButtonPosition position=positions[i];
                ButtonArt.Setup setup=position.setup;
                Bitmap bitmap=image(assets,data,setup.art.image);
                if(!new GameButton.Spec(setup).hasTouchTarget(bitmap,position.x,position.y,
                    assets.sharedConfig.screenWidth,assets.sharedConfig.screenHeight))return false;
            }
        }
        return true;
    }
    private static Bitmap image(GameUiAssets assets,GameUiAssets.ModeAssets data,String name){
        if(name==null)return null;
        Bitmap found=data.images.get(GameUiAssets.systemImageKey(name));
        return found==null?assets.image(name):found;
    }

    GameCodecErrorPanel(Context context,GameUiAssets assets,GameRunner.MovieError error,IntConsumer choose){
        super(context,assets,null,()->{});
        this.error=error;data=error.data;
        canvas(assets.sharedConfig.screenWidth,assets.sharedConfig.screenHeight);
        GameUiAssets.Config cfg=data.config;
        if(cfg.number("dialogFlag",1)!=0){
            String name=cfg.value("filenameDialog","ta_dialog5");
            Bitmap dialog=sprite(name);
            if(dialog!=null){
                int dx=cfg.number("dialogPrintX",-999),dy=cfg.number("dialogPrintY",-999);
                if(dx==-999){dx=(canvasWidth-dialog.getWidth())/2;dy=(canvasHeight-dialog.getHeight())/2;}
                picture(name,dx,dy,dialog.getWidth(),dialog.getHeight());
            }
        }
        String[] labels={context.getString(R.string.engine_systemnnn_movie_skip),context.getString(R.string.engine_systemnnn_movie_available),context.getString(R.string.engine_systemnnn_movie_exit)};
        ButtonPosition[] positions=buttonPositions(cfg);
        for(int i=0;i<positions.length;i++){
            ButtonPosition position=positions[i];
            ButtonArt.Setup setup=position.setup;
            Bitmap image=sprite(setup.art.image);
            GameButton.Spec spec=new GameButton.Spec(setup);
            int action=i;
            boolean enabled=i!=1||error.capability!=0;
            if(image!=null){
                GameButton button=animatedButton(spec,image,labels[i],()->sound(spec.sound,spec.voice),
                    ()->choose.accept(action),position.x,position.y,setup.width,setup.height);
                ButtonArt.Setup feedback=setup;
                button.feedback(event->sound(feedback.sound(event),feedback.voice(event)),setup.enterWait,setup.exitWait);
                button.setEnabled(enabled);button.setClickable(enabled);
            }
        }
        opening(cfg,false);
        uiBackground(cfg,MODE,0);
    }
    @Override Bitmap sprite(String name){
        return data==null?super.sprite(name):image(assets,data,name);
    }
    @Override void drawPanelBackground(Canvas canvas){
        GameUiAssets.Config cfg=data.config;
        if(cfg.number("backScriptFlag",0)!=0)return;
        Bitmap background=sprite(cfg.value("filenameBG","CodecError"));
        int save=canvas.save();
        canvas.scale(getWidth()/(float)canvasWidth,getHeight()/(float)canvasHeight);
        if(background!=null)canvas.drawBitmap(background,0,0,paint);
        canvas.restoreToCount(save);
    }
}
