package com.tyranor.runtime.systemnnn;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import java.io.IOException;

import static com.tyranor.runtime.systemnnn.GameRunner.Modal.BACK;
import static com.tyranor.runtime.systemnnn.GameRunner.Modal.CHOOSE;
import static com.tyranor.runtime.systemnnn.GameRunner.Modal.CONFIRM;
import static com.tyranor.runtime.systemnnn.GameRunner.Modal.GROUP;
import static com.tyranor.runtime.systemnnn.GameRunner.Modal.NEW;
import static com.tyranor.runtime.systemnnn.GameRunner.Modal.PAGE;
import static com.tyranor.runtime.systemnnn.GameRunner.Modal.PRINT_ITEM;
import static com.tyranor.runtime.systemnnn.GameRunner.Modal.PURCHASE_ENABLED;
import static com.tyranor.runtime.systemnnn.GameRunner.Modal.SHOP;
import static com.tyranor.runtime.systemnnn.GameRunner.Modal.SOLD_OUT;

/** CCommonPrintItem and CCommonShop share a grid, but only Shop has a transaction phase. */
final class GameInventoryPanel extends GameUiPanel {
    interface Result {void action(int action,int value);}
    private final boolean shop;
    private final Result result;
    private final GameUiAssets.Config config;
    private final int columns,cellWidth,cellHeight,originX,originY,stepX,stepY;
    private final boolean printNames,lowCursor,wide;
    private GameRunner.Modal modal;
    private Grid grid;
    private GameButton backButton,upButton,downButton,cancelButton;
    private boolean upReverse,downReverse,foreground=true,actionPending,pointerPresent;
    private int hover;
    private float pointerX,pointerY;

    GameInventoryPanel(Context context,GameUiAssets assets,GameRunner.Modal modal,int width,int height,
                       Runnable click,Result result){
        super(context,assets,modal.assets.config.value("filenameBG",""),click);
        if(modal.mode!=PRINT_ITEM&&modal.mode!=SHOP)throw new IllegalArgumentException("Unsupported inventory mode "+modal.mode);
        shop=modal.mode==SHOP;config=modal.assets.config;this.result=result;
        columns=bound(config.number("itemBlockX",4),1,256);
        cellWidth=Math.max(1,config.number("itemSizeX",80));cellHeight=Math.max(1,config.number("itemSizeY",60));
        originX=config.number("itemPrintX",100);originY=config.number("itemPrintY",100);
        stepX=config.number("itemPrintNextX",cellWidth);stepY=config.number("itemPrintNextY",cellHeight);
        printNames=config.number("itemNamePrintFlag",0)!=0;
        lowCursor=config.number("lowCursor",0)!=0;
        wide=assets.game.number("codeByte",2)!=1;
        canvas(width,height);
        opening(config,false);
        uiBackground(config,modal.mode,1);
        update(modal);
    }

    void update(GameRunner.Modal next){
        if(modal!=null&&modal.mode!=next.mode)throw new IllegalArgumentException("Inventory mode changed");
        modal=next;actionPending=false;
        render();
        hover=pointerPresent?hoverAt(pointerX,pointerY):0;
    }

    @Override Bitmap sprite(String name){return find(assets,modal,name);}

    private static Bitmap find(GameUiAssets assets,GameRunner.Modal modal,String name){
        if(name==null||name.isEmpty())return null;
        Bitmap image=modal==null||modal.assets==null?null:modal.assets.images.get(GameUiAssets.systemImageKey(name));
        return image==null?assets.image(name):image;
    }

    static String inventoryFailure(GameUiAssets assets,GameRunner.Modal modal){
        if(modal.mode!=PRINT_ITEM&&modal.mode!=SHOP)return null;
        if(modal.assets==null)return "Missing inventory setup";
        GameUiAssets.Config cfg=modal.assets.config;
        if(UiPreviewPool.Request.read(cfg,modal.mode,1)!=null||find(assets,modal,cfg.value("filenameBG",""))!=null)return null;
        boolean names=cfg.number("itemNamePrintFlag",0)!=0;
        for(GameRunner.Modal.Row row:modal.rows)
            if(find(assets,modal,row.asset)!=null||names&&!row.label.isEmpty())return null;
        GameUiAssets.Config back=backSetup(assets,cfg);
        if(find(assets,modal,back.value("BackFileName","ta_backButton"))!=null)return null;
        if(modal.mode==SHOP&&find(assets,modal,cfg.value("filenameGamePoint",""))!=null)return null;
        if(modal.selection!=0&&(modal.text.length>0||find(assets,modal,plateName(cfg,modal.mode==SHOP))!=null))return null;
        return "No displayable original inventory resources";
    }

    @Override void uiActive(boolean active){super.uiActive(active);foreground=active;}

    private boolean ready(){return foreground&&!actionPending&&!controlsBusy()&&activeFrameReady()&&!openingRunning();}

    private void send(int action,int value){
        if(!ready())return;
        actionPending=true;result.action(action,value);
    }

    @Override void cancelLeave(){super.cancelLeave();actionPending=false;}

    @Override boolean backOverlay(){
        if(ready()){
            GameButton button=shop&&modal.selection!=0?cancelButton:backButton;
            if(button!=null)button.performClick();else send(BACK,0);
        }
        return true;
    }

    @Override public boolean dispatchHoverEvent(MotionEvent event){
        if(event.getActionMasked()==MotionEvent.ACTION_HOVER_EXIT){pointerPresent=false;setHover(0);}
        else if(event.getActionMasked()==MotionEvent.ACTION_HOVER_ENTER||
            event.getActionMasked()==MotionEvent.ACTION_HOVER_MOVE)point(event);
        return super.dispatchHoverEvent(event);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event){
        if(actionPending)return true;
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN||event.getActionMasked()==MotionEvent.ACTION_MOVE)point(event);
        if(event.getActionMasked()==MotionEvent.ACTION_CANCEL){pointerPresent=false;setHover(0);}
        return super.dispatchTouchEvent(event);
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent event){
        if(event.getActionMasked()==MotionEvent.ACTION_SCROLL&&ready()){
            float wheel=event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if(wheel!=0){
                if(upButton!=null&&((wheel>0)!=upReverse))return upButton.performClick();
                if(downButton!=null&&((wheel<0)!=downReverse))return downButton.performClick();
            }
        }
        return super.dispatchGenericMotionEvent(event);
    }

    private void point(MotionEvent event){
        if(!ready())return;
        pointerPresent=true;
        pointerX=event.getX()*canvasWidth/Math.max(1,getWidth());
        pointerY=event.getY()*canvasHeight/Math.max(1,getHeight());
        setHover(hoverAt(pointerX,pointerY));
    }

    private int hoverAt(float x,float y){
        Cell cell=grid.hit(x,y);
        return cell!=null&&cell.row.enabled()?cell.row.id:0;
    }

    private void setHover(int id){
        if(hover==id)return;
        hover=id;
        if(grid!=null)grid.invalidate();
    }

    private void render(){
        clearControls();
        backButton=upButton=downButton=cancelButton=null;
        tabs();
        if(!shop||modal.selection==0)navigation();
        grid=new Grid();
        put(grid,0,0,canvasWidth,canvasHeight);
        if(shop){
            if(grid.selected!=null)purchaseControls(grid.selected);
            DigitAtlas numbers=new DigitAtlas("gamePoint");
            if(numbers.bitmap!=null){
                int points=modal.balance,x=config.number("gamePointPrintX",400),y=config.number("gamePointPrintY",550);
                View balance=new View(getContext()){
                    final Painter painter=new Painter();
                    @Override protected void onDraw(Canvas canvas){
                        int save=canvas.save();
                        canvas.scale(getWidth()/(float)canvasWidth,getHeight()/(float)canvasHeight);
                        numbers.draw(canvas,painter,points,x,y);
                        canvas.restoreToCount(save);
                    }
                };
                put(balance,0,0,canvasWidth,canvasHeight);
            }
        }
    }

    private void tabs(){
        int count=bound(config.number("itemTypeNumber",1),1,256);
        if(count==1)return;
        String prefix=shop?"shopitemType":"haveitemType";
        boolean resetSource=assets.game.number("notSrcCopyFlagEnable",0)!=0;
        ButtonArt.Setup previousOn=null;
        int onX=0,onY=0;
        for(int group=0;group<count;group++){
            String key=prefix+(group+1)+"Button";
            ButtonArt.Setup off=new ButtonArt.Setup(config,key+"1",previousOn,resetSource);
            ButtonArt.Setup on=new ButtonArt.Setup(config,key+"2",off,false);
            int offX=config.number(key+"1PrintX",previousOn==null?0:onX+previousOn.width);
            int offY=config.number(key+"1PrintY",onY);
            onX=config.number(key+"2PrintX",offX);onY=config.number(key+"2PrintY",offY);
            previousOn=on;
            String label="物品分类 "+(group+1);
            if(group==modal.group){
                GameButton.Spec spec=new GameButton.Spec(on);
                Bitmap image=sprite(spec.image);
                if(!drawable(image,spec.states[0]))continue;
                GameButton selected=new GameButton(getContext(),spec,image,label,frameMs);
                selected.setSelected(true);selected.setFocusable(false);
                put(selected,onX,onY,on.width,on.height);
            }else{
                int target=group;
                control(off,"",label,()->send(GROUP,target),offX,offY,true);
            }
        }
    }

    private static GameUiAssets.Config backSetup(GameUiAssets assets,GameUiAssets.Config cfg){
        return cfg.number("backButtonType",0)==1||cfg.number("useCommonBackSetup",0)!=0?assets.game:cfg;
    }

    private void navigation(){
        GameUiAssets.Config back=backSetup(assets,config);
        GameUiAssets.Config pages=config.number("updownButtonType",0)==1||
            config.number("useCommonUpDownSetup",0)!=0?assets.game:config;
        ButtonArt.Setup up=new ButtonArt.Setup(pages,"Up",null,false),down=new ButtonArt.Setup(pages,"Down",up,false);
        int x=pages.number("UpPrintX",0),y=pages.number("UpPrintY",0);
        upReverse=pages.number("UpUpDownReverse",0)!=0;
        downReverse=pages.number("DownUpDownReverse",0)!=0;
        if(modal.page>0)upButton=control(up,"ta_upButton","上一页",()->send(PAGE,modal.page-1),x,y,true);
        if(modal.page+1<modal.pages)
            downButton=control(down,"ta_downButton","下一页",()->send(PAGE,modal.page+1),
                pages.number("DownPrintX",x+up.width),pages.number("DownPrintY",y+up.height),true);
        backButton=control(new ButtonArt.Setup(back,"Back",null,false),"ta_backButton","返回",()->send(BACK,0),
            back.number("BackPrintX",0),back.number("BackPrintY",0),true);
    }

    private void purchaseControls(Cell cell){
        int id=cell.row.id;
        ButtonArt.Setup ok=new ButtonArt.Setup(config,"Ok",null,false);
        GameButton.Spec spec=new GameButton.Spec(ok);
        Bitmap image=sprite(spec.image);
        boolean allowed=cell.price!=null&&(cell.row.flags&PURCHASE_ENABLED)!=0&&purchaseHit(image,spec);
        String label=cell.price==null?"购买":"购买 "+cell.row.label+"，价格 "+cell.price;
        control(ok,"",label,()->{
            if(modal.selection==id&&allowed)send(CONFIRM,id);
        },config.number("OkPrintX",0),config.number("OkPrintY",0),allowed);
        cancelButton=control(new ButtonArt.Setup(config,"Cancel",null,false),"","取消",()->send(BACK,0),
            config.number("CancelPrintX",0),config.number("CancelPrintY",0),true);
    }

    private static boolean drawable(Bitmap image,SpriteAnimation frame){
        return image!=null&&frame!=null&&frame.x>=0&&frame.y>=0&&frame.x<image.getWidth()&&frame.y<image.getHeight();
    }

    private static boolean anyVisible(Bitmap image,SpriteAnimation area,int alpha){
        if(!drawable(image,area))return false;
        int w=Math.min(area.w,image.getWidth()-area.x),h=Math.min(area.h,image.getHeight()-area.y);
        if(w<=0||h<=0)return false;
        int[] pixels=new int[w];
        for(int y=0;y<h;y++){
            image.getPixels(pixels,0,w,area.x,area.y+y,w,1);
            for(int pixel:pixels)if((pixel>>>24)>=alpha)return true;
        }
        return false;
    }

    private static boolean purchaseHit(Bitmap image,GameButton.Spec spec){
        return anyVisible(image,spec.states[0],1)&&(!spec.pixelHit||anyVisible(image,spec.hit,1))&&spec.hit.w>0&&spec.hit.h>0;
    }

    private GameButton control(ButtonArt.Setup art,String fallback,String label,Runnable action,int x,int y,boolean enabled){
        GameButton.Spec spec=new GameButton.Spec(art);
        Bitmap image=sprite(spec.image.isEmpty()?fallback:spec.image);
        if(!drawable(image,spec.states[0])&&(enabled||!drawable(image,spec.states[3])))return null;
        GameButton button=animatedButton(spec,image,label,()->sound(spec.sound,spec.voice),action,x,y,art.width,art.height);
        button.setEnabled(enabled);
        return button;
    }

    private static int bound(int value,int lo,int hi){return Math.max(lo,Math.min(hi,value));}
    private static String plateName(GameUiAssets.Config cfg,boolean shop){
        return cfg.value("filenamePlate",shop?"ta_shopitem_plate":"ta_haveitem_plate");
    }
    private int color(String tag,int alpha){
        return Color.argb(alpha,bound(config.number(tag+"R",255),0,255),bound(config.number(tag+"G",255),0,255),
            bound(config.number(tag+"B",255),0,255));
    }

    private final class Cell {
        final GameRunner.Modal.Row row;
        final Bitmap image;
        final int x,y;
        final Integer price;
        Cell(GameRunner.Modal.Row row,int index){
            this.row=row;image=sprite(row.asset);
            x=originX+index%columns*stepX;y=originY+index/columns*stepY;
            price=shop?price(row.detail):null;
        }
        boolean hit(float x,float y){return x>=this.x&&x<(long)this.x+cellWidth&&y>=this.y&&y<(long)this.y+cellHeight;}
        private Integer price(String raw){
            try{return Integer.valueOf(raw);}catch(NumberFormatException invalid){return null;}
        }
    }

    private final class Grid extends View {
        final Cell[] cells=new Cell[modal.rows.length];
        final Cell selected;
        private final boolean useCursor=config.number("useCursorPic",0)!=0;
        private final Bitmap cursor=useCursor?sprite(config.value("filenameCursor",shop?"ta_shopitem_cursor":"ta_haveitem_cursor")):null;
        private final int cursorColor=color("cursorColor",bound(config.number("cursorColorA",50),0,100)*255/100);
        private final Bitmap plate=sprite(plateName(config,shop));
        private final Bitmap newMark=shop&&config.number("newMarkPrintFlag",0)!=0?sprite(config.value("filenameNewMark","")):null;
        private final Bitmap soldOut=shop&&config.number("soldOutPrintFlag",0)!=0?sprite(config.value("filenameSoldOut","")):null;
        private final DigitAtlas price=new DigitAtlas("pricePoint");
        private final int nameFont=bound(config.number("itemNameFontSize",24),1,256);
        private final int nameColor=color("itemNameFontColor",255);
        private final int nameX=config.number("itemNamePrintX",0),nameY=config.number("itemNamePrintY",0);
        private final int priceX=config.number("pricePointPrintX",50),priceY=config.number("pricePointPrintY",0);
        private final int newX=config.number("newMarkPrintX",0),newY=config.number("newMarkPrintY",0);
        private final int soldX=config.number("soldOutPrintX",0),soldY=config.number("soldOutPrintY",0);
        private final int plateX=config.number("platePrintX",100),plateY=config.number("platePrintY",150);
        private final boolean plateItem=config.number("plateItemPrintFlag",1)!=0;
        private final int plateItemX=plateX+config.number("plateItemPrintX",50),plateItemY=plateY+config.number("plateItemPrintY",50);
        private final int descX=plateX+config.number("descPrintX",300),descY=plateY+config.number("descPrintY",30);
        private final int descStepX=config.number("descPrintNextX",0),descStepY=config.number("descPrintNextY",30);
        private final int descFont=bound(config.number("descFontSize",24),1,256);
        private final int descColor=color("descColor",255);
        private final String[] description;
        private final Painter painter=new Painter();
        private int pressed;
        Grid(){
            super(GameInventoryPanel.this.getContext());
            Cell detail=null;
            for(int i=0;i<cells.length;i++){
                Cell cell=cells[i]=new Cell(modal.rows[i],i);
                if(cell.row.id==modal.selection)detail=cell;
            }
            selected=detail;
            description=detail==null?new String[0]:description();
            setSoundEffectsEnabled(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        private String[] description(){
            String[] lines=new String[modal.text.length];
            boolean invalid=false;
            for(int i=0;i<lines.length;i++){
                try{lines[i]=modal.decoder.decodeConfiguration(modal.text[i]);}
                catch(IOException e){lines[i]="";invalid=true;}
            }
            if(invalid)Log.w("SystemNNN","Invalid inventory description for "+modal.selection);
            return lines;
        }
        Cell hit(float x,float y){
            for(Cell cell:cells)if(cell.hit(x,y))return cell;
            return null;
        }
        @Override public boolean onTouchEvent(MotionEvent event){
            Cell cell=hit(event.getX()*canvasWidth/Math.max(1,getWidth()),event.getY()*canvasHeight/Math.max(1,getHeight()));
            switch(event.getActionMasked()){
                case MotionEvent.ACTION_DOWN:
                    if(!ready()||selected!=null||cell==null||!cell.row.enabled())return false;
                    pressed=cell.row.id;
                    return true;
                case MotionEvent.ACTION_MOVE:return pressed!=0;
                case MotionEvent.ACTION_UP:
                    if(pressed!=0&&cell!=null&&cell.row.id==pressed)performClick();
                    pressed=0;
                    return true;
                case MotionEvent.ACTION_CANCEL:pressed=0;return true;
                default:return pressed!=0;
            }
        }
        @Override public boolean performClick(){
            super.performClick();
            if(pressed==0)return false;
            send(CHOOSE,pressed);
            return true;
        }
        @Override protected void onDraw(Canvas canvas){
            int save=canvas.save();
            canvas.scale(getWidth()/(float)canvasWidth,getHeight()/(float)canvasHeight);
            Cell pointed=selected;
            if(pointed==null&&hover!=0)for(Cell cell:cells)if(cell.row.id==hover){pointed=cell;break;}
            if(lowCursor)cursor(canvas,pointed);
            for(Cell cell:cells)drawCell(canvas,cell);
            if(!lowCursor)cursor(canvas,pointed);
            if(selected!=null)detail(canvas);
            canvas.restoreToCount(save);
        }
        private void cursor(Canvas canvas,Cell cell){
            if(cell==null)return;
            if(useCursor)painter.image(canvas,cursor,cell.x,cell.y);
            else {
                painter.paint.setColor(cursorColor);
                canvas.drawRect(cell.x,cell.y,(long)cell.x+cellWidth,(long)cell.y+cellHeight,painter.paint);
            }
        }
        private void drawCell(Canvas canvas,Cell cell){
            painter.image(canvas,cell.image,cell.x,cell.y);
            if(!printNames)return;
            painter.text(canvas,cell.row.label,cell.x+nameX,cell.y+nameY,nameFont,nameColor);
            if(!shop)return;
            if(cell.price!=null)price.draw(canvas,painter,cell.price,cell.x+priceX,cell.y+priceY);
            if((cell.row.flags&NEW)!=0)painter.image(canvas,newMark,cell.x+newX,cell.y+newY);
            if((cell.row.flags&SOLD_OUT)!=0)painter.image(canvas,soldOut,cell.x+soldX,cell.y+soldY);
        }
        private void detail(Canvas canvas){
            painter.image(canvas,plate,plateX,plateY);
            if(plateItem)painter.image(canvas,selected.image,plateItemX,plateItemY);
            for(int i=0;i<description.length;i++)
                painter.text(canvas,description[i],descX+i*descStepX,descY+i*descStepY,descFont,descColor);
        }
    }

    private final class Painter {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        final Rect source=new Rect();
        final RectF target=new RectF();
        void image(Canvas canvas,Bitmap image,int x,int y){
            if(image!=null)part(canvas,image,0,0,image.getWidth(),image.getHeight(),x,y);
        }
        void part(Canvas canvas,Bitmap image,int sx,int sy,int width,int height,int x,int y){
            int w=Math.min(width,image.getWidth()-sx),h=Math.min(height,image.getHeight()-sy);
            if(w<=0||h<=0||x>=canvasWidth||y>=canvasHeight||(long)x+w<=0||(long)y+h<=0)return;
            source.set(sx,sy,sx+w,sy+h);
            target.set(x,y,(long)x+w,(long)y+h);
            paint.setAlpha(255);
            canvas.drawBitmap(image,source,target,paint);
        }
        void text(Canvas canvas,String text,int x,int y,int font,int color){
            if(text.isEmpty()||x>=canvasWidth||y>=canvasHeight||(long)y+font*2<=0)return;
            paint.setTextSize(font);paint.setColor(color);
            float baseline=y-paint.ascent();
            int advance=(wide?font:font/2)+1;
            for(int i=0;i<text.length()&&x<canvasWidth;){
                int end=i+Character.charCount(text.codePointAt(i));
                if((long)x+advance>0)canvas.drawText(text,i,end,x,baseline,paint);
                x+=advance;i=end;
            }
        }
    }

    private final class DigitAtlas {
        final Bitmap bitmap;
        final int width,height,digits,step,leadingDivisor;
        final boolean leadingZero,left;
        DigitAtlas(String tag){
            bitmap=sprite(config.value("filename"+tag,""));
            width=bound(config.number(tag+"FontSizeX",16),1,4096);
            height=bound(config.number(tag+"FontSizeY",16),1,4096);
            digits=bound(config.number(tag+"Keta",2),1,9);
            step=config.number(tag+"NextX",width);
            int divisor=1;
            for(int i=1;i<digits;i++)divisor*=10;
            leadingDivisor=divisor;
            leadingZero=config.number(tag+"UpZeroFlag",0)!=0;
            left=config.number(tag+"LeftFlag",0)!=0;
        }
        void draw(Canvas canvas,Painter painter,int value,int x,int y){
            // CSuuji's display range is unsigned even though SHOP accepts signed prices.
            if(bitmap==null||value<0)return;
            if(left){
                boolean started=leadingZero;
                int divisor=leadingDivisor;
                for(int i=0;i<digits;i++){
                    int digit=value/divisor;
                    value%=divisor;divisor/=10;
                    if(digit!=0||started||i+1==digits){
                        put(canvas,painter,digit,x,y);
                        x+=step;started=true;
                    }
                }
            }else{
                x+=(digits-1)*step;
                for(int i=0;i<digits;i++){
                    put(canvas,painter,value%10,x,y);
                    value/=10;x-=step;
                    if(value==0&&!leadingZero)break;
                }
            }
        }
        private void put(Canvas canvas,Painter painter,int digit,int x,int y){
            long source=(long)digit*width;
            if(source<bitmap.getWidth())painter.part(canvas,bitmap,(int)source,0,width,height,x,y);
        }
    }
}
