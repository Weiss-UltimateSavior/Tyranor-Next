package com.tyranor.runtime.systemnnn;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.SparseBooleanArray;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import static com.tyranor.runtime.systemnnn.GameRunner.Modal.PRINT_HINT;
import static com.tyranor.runtime.systemnnn.GameRunner.Modal.SELECT_HINT;
import static com.tyranor.runtime.systemnnn.GameRunner.Modal.SELECT_ZUKAN;

/** The engine's hint groups, hint pictures and illustrated reference. */
final class GameReferenceSelectionPanel extends GameUiPanel {
    interface Result {void action(int action,int value);}

    private final int mode;
    private final Result result;
    private final GameUiAssets.Config config;
    private GameRunner.Modal modal;
    private GameButton backButton;
    private boolean actionPending;

    GameReferenceSelectionPanel(Context context,GameUiAssets assets,GameRunner.Modal modal,
                                int width,int height,Runnable click,Result result){
        super(context,assets,modal.assets.config.value("fileNameBG",""),click);
        this.mode=modal.mode;this.config=modal.assets.config;this.result=result;
        if(mode!=SELECT_ZUKAN&&mode!=SELECT_HINT&&mode!=PRINT_HINT)
            throw new IllegalArgumentException("Unsupported reference mode "+mode);
        canvas(width,height);
        opening(config,false);
        printMode=mode;
        if(mode!=SELECT_ZUKAN)uiBackground(config,mode,1);
        update(modal);
    }

    void update(GameRunner.Modal next){
        if(next.mode!=mode)throw new IllegalArgumentException("Reference mode changed");
        modal=next;actionPending=false;backButton=null;
        clearControls();
        setOnClickListener(null);
        if(mode==SELECT_ZUKAN){
            uiBackground(config,mode,modal.selection);
            if(modal.selection!=0){
                setOnClickListener(v->send(GameRunner.Modal.BACK,0));
                return;
            }
            put(new ZukanGrid(),0,0,canvasWidth,canvasHeight);
        }else if(mode==SELECT_HINT)groups();
        else hints();
        addBack();
    }

    @Override Bitmap sprite(String name){
        Bitmap bitmap=modal==null||name==null?null:modal.assets.images.get(GameUiAssets.systemImageKey(name));
        return bitmap==null?super.sprite(name):bitmap;
    }

    private void send(int action,int value){
        if(actionPending)return;
        actionPending=true;
        result.action(action,value);
    }

    @Override void cancelLeave(){
        super.cancelLeave();
        actionPending=false;
    }

    @Override boolean backOverlay(){
        if(!actionPending&&activeFrameReady()&&!openingRunning()){
            if(backButton!=null)backButton.performClick();
            else if(mode==SELECT_ZUKAN&&modal.selection!=0)send(GameRunner.Modal.BACK,0);
        }
        return true;
    }

    private void heading(String title){
        TextView label=text(title,16,Color.WHITE);
        label.setLetterSpacing(1f/16);
        put(label,100,100,16*title.length()+title.length(),16);
    }

    private void groups(){
        heading("ＳＥＬＥＣＴＨＩＮＴ");
        ButtonArt.Setup previous=null;
        int x=0,y=0,rowIndex=0;
        int count=Math.min(256,config.number("groupNumber",1));
        for(int id=1;id<=count;id++){
            String key="group"+id;
            x=config.number(key+"PrintX",previous==null?0:x+previous.width);
            y=config.number(key+"PrintY",previous==null?0:y+previous.height);
            // Locked groups are hidden, but their setup still supplies the next group's defaults.
            previous=new ButtonArt.Setup(config,key,previous,false);
            if(rowIndex==modal.rows.length||modal.rows[rowIndex].id!=id)continue;
            GameRunner.Modal.Row row=modal.rows[rowIndex++];
            if(!row.enabled())continue;
            String label=config.text(key+"Name","提示组 "+id);
            configuredButton(previous,previous.art.image,label,()->send(GameRunner.Modal.CHOOSE,row.id),x,y);
        }
    }

    private void hints(){
        heading("ＰＲＩＮＴＨＩＮＴ");
        int cols=bounded(config.number("picNumX",2),1,64),rows=bounded(config.number("picNumY",1),1,64);
        int originX=config.number("picPrintX",100),originY=config.number("picPrintY",100);
        int stepX=config.number("picNextX",300),stepY=config.number("picNextY",300);
        for(GameRunner.Modal.Row row:modal.rows){
            Bitmap picture=sprite(row.asset);
            if(picture==null)picture=sprite(config.value("nopicFilename",""));
            if(picture==null)continue;
            String key="hint"+modal.group+"_"+row.id;
            int cell=(row.id-1)%(cols*rows);
            int x=config.number(key+"PrintX",originX+stepX*(cell%cols));
            int y=config.number(key+"PrintY",originY+stepY*(cell/cols));
            int w=config.number(key+"SizeX",0),h=config.number(key+"SizeY",0);
            if(w<=0||h<=0){w=picture.getWidth();h=picture.getHeight();}
            hintPicture(picture,x,y,Math.min(w,picture.getWidth()),Math.min(h,picture.getHeight()));
        }
        GameUiAssets.Config buttons=config.number("useCommonUpDownSetup",0)!=0||
            config.number("updownButtonType",0)==1?assets.game:config;
        ButtonArt.Setup up=new ButtonArt.Setup(buttons,"Up",null,false);
        ButtonArt.Setup down=new ButtonArt.Setup(buttons,"Down",up,false);
        int upX=buttons.number("UpPrintX",0),upY=buttons.number("UpPrintY",0);
        if(modal.page>0)
            configuredButton(up,buttons.value("UpFileName","ta_upButton"),"上一页",
                ()->send(GameRunner.Modal.PAGE,modal.page-1),upX,upY);
        if(modal.page+1<modal.pages)
            configuredButton(down,buttons.value("DownFileName","ta_downButton"),"下一页",
                ()->send(GameRunner.Modal.PAGE,modal.page+1),
                buttons.number("DownPrintX",upX+up.width),buttons.number("DownPrintY",upY+up.height));
    }

    private void hintPicture(Bitmap picture,int x,int y,int width,int height){
        Rect source=new Rect(0,0,width,height);
        View view=new View(getContext()){
            private final Rect target=new Rect();
            private final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);
            @Override protected void onDraw(Canvas canvas){
                target.set(0,0,getWidth(),getHeight());
                canvas.drawBitmap(picture,source,target,paint);
            }
        };
        put(view,x,y,width,height);
    }

    private void addBack(){
        GameUiAssets.Config controls=config.number("useCommonBackSetup",0)!=0||
            config.number("backButtonType",0)==1?assets.game:config;
        ButtonArt.Setup setup=new ButtonArt.Setup(controls,"Back",null,false);
        int x=controls.number("BackPrintX",0),y=controls.number("BackPrintY",0);
        if(mode==SELECT_ZUKAN){x=config.number("BackPrintX",x);y=config.number("BackPrintY",y);}
        backButton=configuredButton(setup,controls.value("BackFileName","ta_backButton"),"返回",
            ()->send(GameRunner.Modal.BACK,0),x,y);
    }

    private GameButton configuredButton(ButtonArt.Setup setup,String image,String label,Runnable action,int x,int y){
        GameButton.Spec spec=new GameButton.Spec(setup);
        return animatedButton(spec,sprite(image),label,()->sound(spec.sound,spec.voice),action,x,y,setup.width,setup.height);
    }

    private static int bounded(int value,int min,int max){return Math.max(min,Math.min(max,value));}

    private final class ZukanGrid extends View {
        private final int cellWidth=bounded(config.number("zukanSizeX",16),1,4096);
        private final int cellHeight=bounded(config.number("zukanSizeY",16),1,4096);
        private final int columns=bounded(config.number("zukanNumberX",5),1,4096);
        private final int rows=bounded(config.number("zukanNumberY",4),1,4096);
        private final int originX=config.number("zukanPrintX",0),originY=config.number("zukanPrintY",0);
        private final int stepX=bounded(config.number("zukanNextX",cellWidth),1,4096);
        private final int stepY=bounded(config.number("zukanNextY",cellHeight),1,4096);
        private final Bitmap seat=sprite(config.value("filenameSeat",""));
        private final Bitmap cursor=sprite(config.value("filenameZukanCursor","ta_anime"));
        private final SpriteAnimation cursorAnimation=new SpriteAnimation(0,0,
            bounded(config.number("zukanCursorSizeX",16),1,4096),bounded(config.number("zukanCursorSizeY",16),1,4096),
            config.number("zukanCursorType",1),config.number("zukanCursorPattern",1),config.number("zukanCursorSpeed",1),true);
        private final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Rect source=new Rect(),dest=new Rect();
        private final RectF cursorDest=new RectF();
        private final SparseBooleanArray selectable=new SparseBooleanArray();
        private final GameRunner.Modal.Row[] cells=modal.rows;
        private int highlighted,pressed;
        private long highlightSince;

        ZukanGrid(){
            super(GameReferenceSelectionPanel.this.getContext());
            int tiles=seat==null?0:(seat.getWidth()/cellWidth)*(seat.getHeight()/cellHeight);
            for(GameRunner.Modal.Row row:cells)if(row.enabled()&&row.id<=tiles)selectable.append(row.id,true);
            setSoundEffectsEnabled(false);
        }

        private int tileX(int id){return originX+(id-1)%columns*stepX;}
        private int tileY(int id){return originY+(id-1)/columns*stepY;}
        private int hit(MotionEvent event){
            int x=(int)(event.getX()*canvasWidth/Math.max(1,getWidth()))-originX;
            int y=(int)(event.getY()*canvasHeight/Math.max(1,getHeight()))-originY;
            if(x<0||y<0||x%stepX>=cellWidth||y%stepY>=cellHeight)return 0;
            int column=x/stepX,line=y/stepY;
            if(column>=columns||line>=rows)return 0;
            int id=1+line*columns+column;
            return selectable.get(id)?id:0;
        }

        private void highlight(int id){
            if(highlighted==id)return;
            if(highlighted==0)highlightSince=SystemClock.uptimeMillis();
            highlighted=id;
            invalidate();
        }

        @Override public boolean onHoverEvent(MotionEvent event){
            int id=event.getActionMasked()==MotionEvent.ACTION_HOVER_EXIT?0:hit(event);
            highlight(id);
            return id!=0||super.onHoverEvent(event);
        }

        @Override public boolean onTouchEvent(MotionEvent event){
            int id=hit(event);
            switch(event.getActionMasked()){
                case MotionEvent.ACTION_DOWN:
                    if(id==0||actionPending||controlsBusy())return false;
                    pressed=id;highlight(id);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    highlight(id);
                    return true;
                case MotionEvent.ACTION_UP:
                    if(pressed!=0&&id==pressed&&!controlsBusy())performClick();
                    pressed=0;highlight(0);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    pressed=0;highlight(0);
                    return true;
                default:return pressed!=0;
            }
        }

        @Override public boolean performClick(){
            super.performClick();
            if(pressed==0)return false;
            send(GameRunner.Modal.CHOOSE,pressed);
            return true;
        }

        @Override protected void onDraw(Canvas canvas){
            int save=canvas.save();
            canvas.scale(getWidth()/(float)canvasWidth,getHeight()/(float)canvasHeight);
            if(seat!=null)drawSeat(canvas);
            if(cursor!=null&&highlighted!=0){
                int x=tileX(highlighted),y=tileY(highlighted);
                cursorDest.set(x,y,x+cursorAnimation.w,y+cursorAnimation.h);
                long frame=(SystemClock.uptimeMillis()-highlightSince)/frameMs;
                cursorAnimation.draw(canvas,cursor,cursorDest,paint,frame,255);
                if(cursorAnimation.active(frame))postInvalidateOnAnimation();
            }
            canvas.restoreToCount(save);
        }

        private void drawSeat(Canvas canvas){
            int atlasColumns=seat.getWidth()/cellWidth;
            if(atlasColumns==0)return;
            for(GameRunner.Modal.Row row:cells){
                int x=(row.id-1)%atlasColumns*cellWidth,y=(row.id-1)/atlasColumns*cellHeight;
                if(y+cellHeight>seat.getHeight())continue;
                source.set(x,y,x+cellWidth,y+cellHeight);
                int left=tileX(row.id),top=tileY(row.id);
                dest.set(left,top,left+cellWidth,top+cellHeight);
                canvas.drawBitmap(seat,source,dest,paint);
            }
        }
    }
}
