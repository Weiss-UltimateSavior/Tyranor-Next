package com.tyranor.runtime.systemnnn;

import com.core.engine.R;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import androidx.appcompat.widget.AppCompatTextView;
import java.io.IOException;

import static com.tyranor.runtime.systemnnn.GameRunner.Modal.BACK;
import static com.tyranor.runtime.systemnnn.GameRunner.Modal.CHOOSE;
import static com.tyranor.runtime.systemnnn.GameRunner.Modal.GROUP;
import static com.tyranor.runtime.systemnnn.GameRunner.Modal.LISTEN_VOICE;
import static com.tyranor.runtime.systemnnn.GameRunner.Modal.LOCKED;
import static com.tyranor.runtime.systemnnn.GameRunner.Modal.NEW;
import static com.tyranor.runtime.systemnnn.GameRunner.Modal.PAGE;
import static com.tyranor.runtime.systemnnn.GameRunner.Modal.PRINT_TERM;

/** Original term and voice lists with their configured radio buttons and common navigation. */
final class GameOptionalCatalogPanel extends GameUiPanel {
    interface Result {void action(int action,int value);}
    // Both PC modes place entries on the same fixed diagonal.
    private static final int LIST_X=100,LIST_Y=200,STEP_X=30,STEP_Y=50,ROW_W=300,ROW_H=40;
    private static final int DETAIL_X=400,DETAIL_Y=200,DETAIL_STEP=30,FONT=16;
    private static final int SELECT_COLOR=0x80dc5ca4,HOVER_COLOR=0x8078c040;

    private final Result result;
    private final boolean terms;
    private final GameUiAssets.Config config;
    private final int swipeSlop;
    private final String masked;
    private GameRunner.Modal modal;
    private GameButton backButton,upButton,downButton;
    private boolean upReverse,downReverse;
    private CatalogRow[] entries;
    private TermDescription description;
    private SpriteAnimation markerAnimation;
    private Bitmap markerImage;
    private long markerStart=-1;
    private boolean actionPending,listGesture,detailGesture,swiping;
    private int firstRow,touchStartRow;
    private float touchStartY,lastTouchY;

    GameOptionalCatalogPanel(Context context,GameUiAssets assets,GameRunner.Modal modal,
                             int width,int height,Runnable click,Result result){
        super(context,assets,modal.assets.config.value("filenameBG",""),click);
        if(modal.mode!=PRINT_TERM&&modal.mode!=LISTEN_VOICE)
            throw new IllegalArgumentException("Unsupported catalog mode "+modal.mode);
        terms=modal.mode==PRINT_TERM;
        config=modal.assets.config;this.result=result;
        String question=assets.game.number("codeByte",2)==1?"???":"？？？";
        masked=terms?question:config.text("unknownVoice",question);
        swipeSlop=ViewConfiguration.get(context).getScaledTouchSlop();
        canvas(width,height);
        opening(config,false);
        uiBackground(config,modal.mode,1);
        update(modal);
    }

    void update(GameRunner.Modal next){
        GameRunner.Modal before=modal;
        if(before!=null&&before.mode!=next.mode)throw new IllegalArgumentException("Catalog mode changed");
        modal=next;actionPending=false;
        boolean changedPage=before==null||before.group!=next.group||before.page!=next.page;
        if(changedPage)firstRow=0;
        int visible=visibleRows();
        boolean rebuild=changedPage||before.pages!=next.pages||entries.length!=visible;
        if(rebuild)render(visible);
        bindRows();
        if(description!=null&&(rebuild||before.selection!=next.selection))
            description.show(next.selection==0?new byte[0][]:next.text);
    }

    @Override void canvas(int width,int height){
        int oldWidth=canvasWidth,oldHeight=canvasHeight;
        super.canvas(width,height);
        if(modal!=null&&(oldWidth!=canvasWidth||oldHeight!=canvasHeight)){
            render(visibleRows());
            bindRows();
            if(description!=null)description.show(modal.selection==0?new byte[0][]:modal.text);
        }
    }

    @Override Bitmap sprite(String name){
        Bitmap image=modal==null||name==null?null:modal.assets.images.get(GameUiAssets.systemImageKey(name));
        return image==null?super.sprite(name):image;
    }

    private boolean canAct(){return !actionPending&&!controlsBusy()&&activeFrameReady()&&!openingRunning();}
    private void send(int action,int value){
        if(!canAct())return;
        actionPending=true;
        result.action(action,value);
    }

    @Override void cancelLeave(){
        super.cancelLeave();
        actionPending=false;
    }

    @Override boolean backOverlay(){
        if(canAct())backButton.performClick();
        return true;
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event){
        return actionPending||super.dispatchTouchEvent(event);
    }

    private int listX(){return Math.min(LIST_X,Math.max(0,canvasWidth-ROW_W));}
    private int listY(){return Math.min(LIST_Y,Math.max(0,canvasHeight-ROW_H));}
    private int visibleRows(){
        int across=1+Math.max(0,canvasWidth-listX()-ROW_W)/STEP_X;
        int down=1+Math.max(0,canvasHeight-listY()-ROW_H)/STEP_Y;
        return Math.min(modal.rows.length,Math.min(across,down));
    }
    private int maxFirstRow(){return modal.rows.length-entries.length;}
    private void bindRows(){
        firstRow=Math.min(firstRow,maxFirstRow());
        for(int i=0;i<entries.length;i++)entries[i].setEntry(modal.rows[firstRow+i]);
    }
    private void scrollRows(int first){
        int row=Math.max(0,Math.min(first,maxFirstRow()));
        if(row==firstRow)return;
        firstRow=row;
        bindRows();
    }

    private void render(int visible){
        clearControls();
        tabs();
        navigation();
        markerImage=null;
        if(terms&&config.number("createNewMark",0)!=0){
            markerImage=sprite(config.value("filenameNewMark","ta_anime"));
            markerAnimation=new SpriteAnimation(0,0,
                dimension(config.number("newMarkSizeX",16)),dimension(config.number("newMarkSizeY",16)),
                config.number("newMarkType",1),config.number("newMarkPattern",1),config.number("newMarkSpeed",1),true);
        }
        entries=new CatalogRow[visible];
        for(int i=0;i<visible;i++){
            int x=listX()+i*STEP_X,y=listY()+i*STEP_Y;
            CatalogRow row=new CatalogRow(Math.max(ROW_W,canvasWidth-x));
            entries[i]=row;
            put(row,x,y,row.logicalWidth,ROW_H);
            if(markerImage!=null){
                row.marker=new NewMarker();
                put(row.marker,x+config.number("newMarkPrintDeltaX",0),y+config.number("newMarkPrintDeltaY",0),
                    markerAnimation.w,markerAnimation.h);
            }
        }
        description=null;
        if(terms&&canvasWidth>DETAIL_X&&canvasHeight>DETAIL_Y){
            description=new TermDescription();
            put(description,DETAIL_X,DETAIL_Y,canvasWidth-DETAIL_X,canvasHeight-DETAIL_Y);
        }
    }

    private void tabs(){
        int count=Math.max(1,Math.min(256,config.number(terms?"termTypeNumber":"voiceCharaNumber",1)));
        if(count<=1)return;
        String prefix=terms?"termType":"voiceChara";
        boolean resetSource=assets.game.number("notSrcCopyFlagEnable",0)!=0;
        ButtonArt.Setup previousOn=null;
        int onX=0,onY=0;
        for(int group=0;group<count;group++){
            String key=prefix+(group+1)+"Button";
            ButtonArt.Setup off=new ButtonArt.Setup(config,key+"1",previousOn,resetSource);
            ButtonArt.Setup on=new ButtonArt.Setup(config,key+"2",off,false);
            int offX=config.number(key+"1PrintX",previousOn==null?0:onX+previousOn.width);
            int offY=config.number(key+"1PrintY",onY);
            onX=config.number(key+"2PrintX",offX);
            onY=config.number(key+"2PrintY",offY);
            previousOn=on;
            int target=group;
            String label=hostText(terms?R.string.engine_systemnnn_ctl_term_group:R.string.engine_systemnnn_ctl_voice_group,group+1);
            if(group==modal.group){
                GameButton selected=new GameButton(getContext(),new GameButton.Spec(on),sprite(on.art.image),label,frameMs);
                selected.setFocusable(false);selected.setSelected(true);
                put(selected,onX,onY,on.width,on.height);
            }else originalButton(off,off.art.image,label,()->send(GROUP,target),offX,offY);
        }
    }

    private void navigation(){
        GameUiAssets.Config back=config.number("backButtonType",0)==1||
            config.number("useCommonBackSetup",0)!=0?assets.game:config;
        GameUiAssets.Config pages=config.number("updownButtonType",0)==1||
            config.number("useCommonUpDownSetup",0)!=0?assets.game:config;
        ButtonArt.Setup backArt=new ButtonArt.Setup(back,"Back",null,false);
        ButtonArt.Setup up=new ButtonArt.Setup(pages,"Up",null,false);
        ButtonArt.Setup down=new ButtonArt.Setup(pages,"Down",up,false);
        int upX=pages.number("UpPrintX",0),upY=pages.number("UpPrintY",0);
        upReverse=pages.number("UpUpDownReverse",0)!=0;
        downReverse=pages.number("DownUpDownReverse",0)!=0;
        String upImage=up.art.image.isEmpty()?"ta_upButton":up.art.image;
        String downImage=down.art.image.isEmpty()?"ta_downButton":down.art.image;
        upButton=modal.page>0?originalButton(up,upImage,hostText(R.string.engine_systemnnn_ctl_page_previous),
            ()->send(PAGE,modal.page-1),upX,upY):null;
        downButton=modal.page+1<modal.pages?originalButton(down,downImage,hostText(R.string.engine_systemnnn_ctl_page_next),
            ()->send(PAGE,modal.page+1),pages.number("DownPrintX",upX+up.width),
            pages.number("DownPrintY",upY+up.height)):null;
        backButton=originalButton(backArt,back.value("BackFileName","ta_backButton"),hostText(R.string.engine_systemnnn_host_back),
            ()->send(BACK,0),back.number("BackPrintX",0),back.number("BackPrintY",0));
    }

    private GameButton originalButton(ButtonArt.Setup art,String image,String label,Runnable action,int x,int y){
        GameButton.Spec spec=new GameButton.Spec(art);
        return animatedButton(spec,sprite(image),label,()->sound(spec.sound,spec.voice),action,x,y,art.width,art.height);
    }

    private static int dimension(int size){return Math.max(1,Math.min(4096,size));}

    private boolean detailAt(MotionEvent event){
        return description!=null&&description.scrollable()&&event.getX()>=description.getLeft()&&
            event.getY()>=description.getTop()&&event.getX()<description.getRight()&&event.getY()<description.getBottom();
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent event){
        if(event.getActionMasked()==MotionEvent.ACTION_SCROLL&&canAct()){
            float wheel=event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if(wheel!=0){
                if(detailAt(event)){description.move(-wheel*DETAIL_STEP);return true;}
                if(maxFirstRow()>0){
                    int steps=Math.max(1,Math.round(Math.abs(wheel)));
                    scrollRows(firstRow+(wheel>0?-steps:steps));return true;
                }
                if(upButton!=null&&((wheel>0)!=upReverse))return upButton.performClick();
                if(downButton!=null&&((wheel<0)!=downReverse))return downButton.performClick();
            }
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event){
        switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                lastTouchY=touchStartY=event.getY();swiping=false;
                touchStartRow=firstRow;
                detailGesture=detailAt(event);
                float x=event.getX()*canvasWidth/Math.max(1,getWidth());
                float y=event.getY()*canvasHeight/Math.max(1,getHeight())-listY();
                int row=(int)(y/STEP_Y);
                listGesture=y>=0&&row<entries.length&&y-row*STEP_Y<ROW_H&&
                    x>=listX()+row*STEP_X&&x<listX()+row*STEP_X+ROW_W;
                break;
            case MotionEvent.ACTION_MOVE:
                if(startSwipe(event))return true;
                break;
            default:break;
        }
        return super.onInterceptTouchEvent(event);
    }

    private boolean startSwipe(MotionEvent event){
        if(!(listGesture||detailGesture)||!canAct()||Math.abs(event.getY()-touchStartY)<=swipeSlop)return false;
        swiping=true;
        return true;
    }

    private void dragRows(MotionEvent event){
        float distance=touchStartY-event.getY();
        if(Math.abs(distance)<=swipeSlop){scrollRows(touchStartRow);return;}
        int steps=Math.max(1,Math.round(Math.abs(distance)*canvasHeight/Math.max(1,getHeight())/STEP_Y));
        scrollRows(touchStartRow+(distance>0?steps:-steps));
    }

    @Override public boolean onTouchEvent(MotionEvent event){
        if(event.getActionMasked()==MotionEvent.ACTION_MOVE&&(swiping||startSwipe(event))){
            if(detailGesture){
                description.move((lastTouchY-event.getY())*canvasHeight/Math.max(1,getHeight()));
                lastTouchY=event.getY();
            }else if(maxFirstRow()>0)dragRows(event);
            return true;
        }
        if(swiping&&event.getActionMasked()==MotionEvent.ACTION_UP){
            swiping=false;
            if(!detailGesture&&canAct()){
                if(maxFirstRow()>0)dragRows(event);
                else{
                    GameButton page=event.getY()<touchStartY?downButton:upButton;
                    if(page!=null)page.performClick();
                }
            }
            return true;
        }
        if(event.getActionMasked()==MotionEvent.ACTION_CANCEL){listGesture=detailGesture=swiping=false;}
        return super.onTouchEvent(event);
    }

    private final class CatalogRow extends AppCompatTextView {
        final int logicalWidth;
        private final Paint fill=new Paint();
        GameRunner.Modal.Row entry;
        NewMarker marker;
        boolean locked,hover;
        CatalogRow(int width){
            super(GameOptionalCatalogPanel.this.getContext());
            setEmojiCompatEnabled(false);
            logicalWidth=width;
            setSingleLine(true);setEllipsize(null);
            setGravity(Gravity.LEFT|Gravity.TOP);setIncludeFontPadding(false);
            setTextSize(TypedValue.COMPLEX_UNIT_PX,FONT);setTextColor(Color.WHITE);setLetterSpacing(1f/FONT);
            setSoundEffectsEnabled(false);
            setOnClickListener(v->{if(entry!=null&&entry.enabled())send(CHOOSE,entry.id);});
        }
        void setEntry(GameRunner.Modal.Row row){
            entry=row;locked=(row.flags&LOCKED)!=0;
            setText(locked?masked:row.label);
            setEnabled(row.enabled());setClickable(row.enabled());setFocusable(row.enabled());
            setSelected(terms&&row.id==modal.selection);
            if(marker!=null)marker.setVisibility(!locked&&(row.flags&NEW)!=0?VISIBLE:INVISIBLE);
            invalidate();
        }
        private boolean withinHit(float x){return x>=0&&x*logicalWidth<ROW_W*(float)getWidth();}
        @Override public boolean onTouchEvent(MotionEvent event){
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN&&(!withinHit(event.getX())||!canAct()))return false;
            return super.onTouchEvent(event);
        }
        @Override public boolean onHoverEvent(MotionEvent event){
            hover=event.getActionMasked()!=MotionEvent.ACTION_HOVER_EXIT&&withinHit(event.getX());
            invalidate();
            return super.onHoverEvent(event);
        }
        @Override protected void drawableStateChanged(){super.drawableStateChanged();invalidate();}
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);
            if(locked)return;
            float right=getWidth()*ROW_W/(float)logicalWidth;
            if(isSelected()){fill.setColor(SELECT_COLOR);canvas.drawRect(0,0,right,getHeight(),fill);}
            if(hover||isPressed()){fill.setColor(HOVER_COLOR);canvas.drawRect(0,0,right,getHeight(),fill);}
        }
    }

    private final class NewMarker extends View {
        private final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);
        private final RectF bounds=new RectF();
        NewMarker(){
            super(GameOptionalCatalogPanel.this.getContext());
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        @Override protected void onDraw(Canvas canvas){
            long now=SystemClock.uptimeMillis();
            if(markerStart<0&&activeFrameReady())markerStart=now;
            long frame=markerStart<0?0:(now-markerStart)/frameMs;
            bounds.set(0,0,getWidth(),getHeight());
            markerAnimation.draw(canvas,markerImage,bounds,paint,frame,255);
            if(markerAnimation.active(frame))postInvalidateOnAnimation();
        }
    }

    private final class TermDescription extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private String[] lines=new String[0];
        private float scroll;
        TermDescription(){
            super(GameOptionalCatalogPanel.this.getContext());
            paint.setColor(Color.WHITE);paint.setTextSize(FONT);
        }
        void show(byte[][] text){
            lines=new String[text.length];
            boolean malformed=false;
            for(int i=0;i<lines.length;i++){
                try{lines[i]=modal.decoder.decodeConfiguration(text[i]);}
                catch(IOException invalid){malformed=true;lines[i]="";}
            }
            if(malformed)Log.w("SystemNNN","Invalid text in term description "+modal.selection);
            setVisibility(lines.length==0?INVISIBLE:VISIBLE);
            setContentDescription(String.join("\n",lines));
            scroll=0;
            invalidate();
        }
        private int height(){return canvasHeight-DETAIL_Y;}
        private int maximum(){
            return lines.length==0?0:Math.max(0,(lines.length-1)*DETAIL_STEP+FONT-height());
        }
        boolean scrollable(){return maximum()>0;}
        private void move(float delta){
            scroll=Math.max(0,Math.min(maximum(),scroll+delta));
            invalidate();
        }
        @Override protected void onDraw(Canvas canvas){
            int state=canvas.save();
            canvas.scale(getWidth()/(float)(canvasWidth-DETAIL_X),getHeight()/(float)height());
            int first=(int)(scroll/DETAIL_STEP);
            for(int i=first;i<lines.length;i++){
                float y=i*DETAIL_STEP-scroll;
                if(y>=height())break;
                canvas.drawText(lines[i],0,y-paint.ascent(),paint);
            }
            canvas.restoreToCount(state);
        }
    }
}
