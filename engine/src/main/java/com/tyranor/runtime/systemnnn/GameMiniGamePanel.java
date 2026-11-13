package com.tyranor.runtime.systemnnn;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import com.core.engine.EngineUiText;
import com.core.engine.R;
import java.util.Random;
import java.util.function.IntConsumer;

final class GameMiniGamePanel extends GameUiPanel {
    private final GameMiniGames game;
    private final IntConsumer playSound;
    private final Runnable clear;
    private final GameButton exit;
    private final Board boardView;
    private boolean hintTouch;

    GameMiniGamePanel(Context context,GameUiAssets assets,GameMiniGames game,int width,int height,
                      IntConsumer playSound,Runnable clear,Runnable back){
        super(context,assets,"",()->{});
        this.game=game;this.playSound=playSound;this.clear=clear;
        canvas(width,height);opening(game.config,false);
        boardView=new Board(context,width,height);
        put(boardView,0,0,width,height);
        int w=DialogueLayout.clamp(game.config.number("exitSizeX",128),1,width);
        int h=DialogueLayout.clamp(game.config.number("exitSizeY",48),1,height);
        int x=game.config.number("exitPrintX"+(game.index+1),game.config.number("exitPrintX",width-w-12));
        int y=game.config.number("exitPrintY"+(game.index+1),game.config.number("exitPrintY",height-h-12));
        GameButton.Spec spec=new GameButton.Spec(game.config,"exit",null,w,h);
        exit=animatedButton(spec,game.exit,EngineUiText.get(context,R.string.engine_systemnnn_host_back),()->playSound.accept(spec.sound),back,x,y,w,h);
        if(!game.instructions.isEmpty())exit.setVisibility(GONE);
    }

    @Override public void draw(Canvas canvas){
        super.draw(canvas);
        boardView.scheduleAnimation();
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event){
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){
            int buttons=event.getButtonState();
            if((buttons&MotionEvent.BUTTON_TERTIARY)!=0)hintTouch=boardView.hintButton(true);
            else if((buttons&MotionEvent.BUTTON_SECONDARY)!=0)hintTouch=boardView.hintButton(false);
            else hintTouch=boardView.dismissHint();
        }
        if(hintTouch){
            if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)hintTouch=false;
            return true;
        }
        return super.dispatchTouchEvent(event);
    }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent event){
        if(event.getActionMasked()==MotionEvent.ACTION_BUTTON_PRESS){
            int button=event.getActionButton();
            if((button==MotionEvent.BUTTON_SECONDARY||button==MotionEvent.BUTTON_TERTIARY)&&
                (hintTouch||boardView.hintButton(button==MotionEvent.BUTTON_TERTIARY)))return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    private final class Board extends View {
        private static final int BRUSH_SIZE=64;
        private static final int TILE_BACK=144,TILE_HINT=145;
        final int width,height;
        final Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint bitmapPaint=new Paint(Paint.FILTER_BITMAP_FLAG);
        final Rect source=new Rect();
        final RectF bounds,stockArea=new RectF(),wasteArea=new RectF();
        int hintX,hintY;
        final RectF movingPiece=new RectF();
        final RectF[] cells;
        final Random random=new Random();
        final int cardW,cardH;
        final MiniGameBoard board;
        final Runnable animationTick=this::advanceAnimation;
        long nextAnimationFrame;
        boolean animationTouch,outcomeReported;
        int page;
        Bitmap painted,paintFront,paintBack;
        int[] brushMask,brushSource,brushTarget;
        float lastX,lastY;
        int from=-1;
        int congraX,congraY,congraSpeedX=4,congraSpeedY;

        Board(Context context,int width,int height){
            super(context);setClickable(true);this.width=width;this.height=height;
            bounds=new RectF(0,0,width,height);
            boolean tiles=game.type>=MiniGameBoard.NIKAKU;
            String prefix=tiles?"mahjong":"trump";
            cardW=DialogueLayout.clamp(game.config.number(prefix+"PicSizeX",tiles?16:96),1,512);
            cardH=DialogueLayout.clamp(game.config.number(prefix+"PicSizeY",tiles?16:144),1,512);
            board=new MiniGameBoard(game.type,random,cardW,cardH);
            cells=new RectF[board.cells.length];
            layoutBoard();
        }

        private void layoutBoard(){
            int type=game.type,columns=board.columns,rows=board.rows;
            if(type==MiniGameBoard.PUZZLE){
                float w=width/8f,h=height/8f;
                for(int i=0;i<9;i++)cells[i]=rect((i%3*2+1.5f)*w,(i/3*2+1.5f)*h,w,h);
                for(int i=0;i<12;i++)cells[9+i]=rect((i%6+1)*w,(i<6?.25f:6.75f)*h,w,h);
                for(int i=0;i<6;i++)cells[21+i]=rect((i<3?.25f:6.75f)*w,(i%3*2+1.5f)*h,w,h);
                return;
            }
            if(type<=MiniGameBoard.SLIDE){
                for(int i=0;i<cells.length;i++)cells[i]=rect(i%columns*width/(float)columns,
                    i/columns*height/(float)rows,width/(float)columns,height/(float)rows);
                return;
            }
            if(type==MiniGameBoard.PYRAMID){
                int gap=DialogueLayout.clamp((width-cardW*7)/7,0,cardW*3/4);
                int dx=game.layout("nextX",cardW-(cardW-gap)/2),dy=game.layout("nextY",(height-16-cardH)/6);
                int x=game.layout("startX",width/2-cardW/2-6*dx),y=game.layout("startY",8);
                stockArea.set(rect(x,y,cardW,cardH));wasteArea.set(rect(x+2*dx,y,cardW,cardH));
                for(int row=0;row<rows;row++)for(int col=0;col<=row;col++)
                    cells[row*columns+col]=rect(x+dx*(2*col+6-row),y+row*dy,cardW,cardH);
                return;
            }
            int dx=cardW,dy=cardH,x=0,y=0;
            if(type==MiniGameBoard.MONTE){
                dx=game.layout("nextX",100);dy=game.layout("nextY",150);
                x=game.layout("startX",4);y=game.layout("startY",2);
                stockArea.set(rect(x+7*dx,y,cardW,cardH));
            }else if(type==MiniGameBoard.GOLF){
                int gap=(width-cardW*7)/7;
                dx=cardW+gap;dy=(height-cardH-16)/6;
                x=gap/2;y=8;
                stockArea.set(rect(x+dx*4,y+dy*4+cardH+8,cardW,cardH));
                wasteArea.set(rect(x+dx*3,y+dy*4+cardH+8,cardW,cardH));
            }else{
                x=game.layout("startX",0)+cardW;
                y=game.layout("startY",0)+cardH;
                hintX=cardW;hintY=height-cardH-16;
            }
            for(int i=0;i<cells.length;i++)cells[i]=rect(x+i%columns*dx,y+i/columns*dy,cardW,cardH);
        }

        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);
            int saved=canvas.save();
            canvas.scale(getWidth()/(float)width,getHeight()/(float)height);
            canvas.drawColor(0xff004080);
            if(page<game.instructions.size()){
                canvas.drawBitmap(game.instructions.get(page),null,bounds,bitmapPaint);
            }else{
                Bitmap background=game.type==MiniGameBoard.PAINT?painted:game.first;
                if(background==null&&game.type==MiniGameBoard.PAINT)background=game.second;
                if(background!=null)canvas.drawBitmap(background,null,bounds,bitmapPaint);
                if(game.type<=MiniGameBoard.SLIDE)drawPuzzle(canvas);
                else if(!board.cleared){
                    if(game.type<MiniGameBoard.NIKAKU)drawCards(canvas);
                    else drawTiles(canvas);
                }
                if(board.cleared&&game.congratulations!=null)drawCongratulations(canvas);
            }
            canvas.restoreToCount(saved);
        }

        private void drawPuzzle(Canvas canvas){
            int type=game.type;
            for(int i=0;i<cells.length;i++){
                int value=board.cells[i];
                if(type==MiniGameBoard.CROSS){
                    Bitmap tile=value==0?game.first:game.second;
                    if(tile!=null)imagePart(canvas,tile,cells[i],cells[i]);
                    else if(value!=0)shade(canvas,cells[i],0xa0145280);
                }else{
                    if(value<0)shade(canvas,cells[i],0xb0000000);
                    else puzzleTile(canvas,value,cells[i]);
                }
                if(!board.cleared)outline(canvas,cells[i],0x657b94a6);
            }
            if(board.held>=0){
                float w=width/(type==MiniGameBoard.PUZZLE?8f:4f),h=height/(type==MiniGameBoard.PUZZLE?8f:4f);
                puzzleTile(canvas,board.held,rect(lastX-w/2,lastY-h/2,w,h));
            }
        }
        private void puzzleTile(Canvas canvas,int value,RectF dest){
            Bitmap picture=game.first;
            RectF part;
            if(game.type==MiniGameBoard.PUZZLE){
                int index=value%9;
                if(value>=9)picture=game.second;
                float w=width/8f,h=height/8f;
                part=rect((index%3*2+1.5f)*w,(index/3*2+1.5f)*h,w,h);
            }else part=cells[value];
            if(picture!=null)imagePart(canvas,picture,part,dest);
            else {
                shade(canvas,dest,0xff123a55);
                drawLabel(canvas,Integer.toString(value+1),dest,Math.min(dest.width(),dest.height())/3f);
            }
        }
        private void imagePart(Canvas canvas,Bitmap image,RectF part,RectF dest){
            source.set(Math.round(part.left*image.getWidth()/width),Math.round(part.top*image.getHeight()/height),
                Math.round(part.right*image.getWidth()/width),Math.round(part.bottom*image.getHeight()/height));
            canvas.drawBitmap(image,source,dest,bitmapPaint);
        }
        private void drawCards(Canvas canvas){
            MiniGameBoard.Animation phase=board.animation;
            boolean recycle=phase==MiniGameBoard.Animation.RECYCLE;
            boolean erase=phase==MiniGameBoard.Animation.REMOVE_CARDS;
            boolean collect=phase==MiniGameBoard.Animation.COLLECT_CARD;
            piece(canvas,recycle||!board.stock.isEmpty()?54:55,stockArea);
            if(!wasteArea.isEmpty()&&!collect){
                boolean eraseWaste=erase&&board.marked(MiniGameBoard.WASTE);
                int previous=recycle?-1:board.wasteCard(eraseWaste);
                piece(canvas,previous<0?55:previous,wasteArea);
                if(eraseWaste)piece(canvas,board.wasteCard(false),wasteArea,cardFade());
                else if(board.selected==MiniGameBoard.WASTE)shade(canvas,wasteArea,0x680080ff);
            }
            if(phase==MiniGameBoard.Animation.TURN_CARD){
                int count=board.frame,max=board.frameLimit;
                movePiece(canvas,board.turningCard,stockArea,wasteArea,count,max,30+70*count/max);
            }else if(recycle){
                int ordinal=0,max=MiniGameBoard.TURN_FRAMES;
                for(int value:board.waste){
                    int count=board.frame+ordinal++-max;
                    if(count>=0&&count<=max)movePiece(canvas,value,wasteArea,stockArea,count,max,30+70*count/max);
                }
            }
            for(int ordinal=0;ordinal<board.dealOrder.length;ordinal++){
                int index=board.dealOrder[ordinal],value=board.cells[index];
                if(value<0||collect&&board.marked(index))continue;
                RectF location=cells[index];
                if(phase==MiniGameBoard.Animation.DEAL||phase==MiniGameBoard.Animation.REFILL&&ordinal>=board.firstDealt){
                    int count=board.dealProgress(ordinal);
                    if(count<0&&game.type!=MiniGameBoard.MONTE)continue;
                    movePiece(canvas,value,stockArea,location,Math.max(0,count),MiniGameBoard.CARD_TRAVEL_FRAMES,100);
                }else if(phase==MiniGameBoard.Animation.COMPACT){
                    movePiece(canvas,value,location,cells[board.movingTo[index]],board.frame,board.frameLimit,100);
                }else{
                    piece(canvas,value,location,erase&&board.marked(index)?cardFade():100);
                    if(board.selected==index)shade(canvas,location,0x680080ff);
                }
            }
            if(collect){
                int index=board.erasing[0],count=board.frame,max=board.frameLimit;
                int opacity=Math.min(99,50+50*(max-count)/max);
                movePiece(canvas,board.cells[index],cells[index],wasteArea,count,max,opacity);
            }
        }

        private int cardFade(){
            return Math.max(1,Math.min(99,100*(board.frameLimit-board.frame)/board.frameLimit));
        }

        private void movePiece(Canvas canvas,int value,RectF from,RectF to,int frame,int duration,int opacity){
            movingPiece.set(from);
            movingPiece.offset((int)((to.left-from.left)*frame/duration),(int)((to.top-from.top)*frame/duration));
            piece(canvas,value,movingPiece,opacity);
        }

        private void drawTiles(Canvas canvas){
            MiniGameBoard.Animation phase=board.animation;
            if(!board.gameOver){
                for(int i=0;i<board.hintLeft;i++)
                    piece(canvas,TILE_HINT,rect(hintX+i*cardW,hintY,cardW,cardH));
            }
            boolean falling=phase==MiniGameBoard.Animation.FALL_TILES;
            boolean shifting=phase==MiniGameBoard.Animation.SHIFT_TILES;
            boolean erasing=phase==MiniGameBoard.Animation.ERASE_TILES;
            int hintAlpha=board.hintAlpha();
            for(int i=0;i<cells.length;i++){
                int value=board.cells[i];
                boolean removed=value==MiniGameBoard.REMOVED_TILE;
                if(value<0&&(!removed||falling))continue;
                int opacity=board.gameOver?40:100;
                RectF location=cells[i];
                if(removed||(phase==MiniGameBoard.Animation.REVEAL_TILES&&value>=board.frame))value=TILE_BACK;
                if(erasing&&(removed||game.type==MiniGameBoard.KUSHI&&board.marked(i)))
                    opacity=100*(board.frameLimit-board.frame)/board.frameLimit;
                if(falling||shifting){
                    int offset=board.tileOffset(i);
                    movingPiece.set(location);
                    movingPiece.offset(shifting?-offset:0,falling?offset:0);
                    location=movingPiece;
                }
                piece(canvas,value,location,opacity);
                if(board.selected==i)shade(canvas,location,0x680080ff);
                if(hintAlpha!=0&&board.hinted(i))shade(canvas,location,(hintAlpha<<24)|0xff4080);
                if(phase==MiniGameBoard.Animation.CONNECT_TILES&&board.marked(i))shade(canvas,location,0x8040ffc0);
            }
            if(phase==MiniGameBoard.Animation.RETURN_TILES){
                for(int col=0;col<board.columns;col++)if(board.incoming[col]>=0){
                    movingPiece.set(cells[col]);
                    movingPiece.offset(0,board.returnOffset(col)-cardH);
                    piece(canvas,board.incoming[col],movingPiece);
                }
            }else if(phase==MiniGameBoard.Animation.CONNECT_TILES){
                drawConnection(canvas);
            }
        }

        private void drawConnection(Canvas canvas){
            int[] path=board.connection;
            int ticks=MiniGameBoard.CONNECTION_FRAMES;
            int active=board.frame/(ticks+1);
            int originX=(int)cells[0].left+cardW/2,originY=(int)cells[0].top+cardH/2;
            ink.setColor(0xff40ff80);
            for(int leg=0;leg<=active;leg++){
                int at=leg*2;
                int count=leg<active?ticks:board.frame%(ticks+1);
                int x=originX+path[at]*cardW,y=originY+path[at+1]*cardH;
                int endX=x+(path[at+2]-path[at])*cardW*count/ticks;
                int endY=y+(path[at+3]-path[at+1])*cardH*count/ticks;
                canvas.drawRect(Math.min(x,endX)-4,Math.min(y,endY)-4,Math.max(x,endX)+5,Math.max(y,endY)+5,ink);
            }
        }

        private void piece(Canvas canvas,int value,RectF dest){
            piece(canvas,value,dest,100);
        }
        private void piece(Canvas canvas,int value,RectF dest,int opacity){
            boolean tiles=game.type>=MiniGameBoard.NIKAKU;
            Bitmap image=tiles?game.mahjong:game.trump;
            int col,row;
            if(tiles&&(value==TILE_BACK||value==TILE_HINT)){
                col=value==TILE_BACK?0:8;row=6;
            }else if(tiles){
                int group=value/4;
                if(group<27){
                    col=group%9;row=group/9;
                    if(value%4==3&&(col==2||col==4)){col=row*2+(col==4?1:0);row=5;}
                }else if(group<34){col=group-27;row=3;}
                else {col=value-136;row=4;}
            }else{
                col=value%13;row=value/13;
                if(image!=null&&image.getWidth()<cardW*13&&!(row<4&&col<10)){
                    if(row<3){col=row*3+col-10;row=4;}
                    else {col=(value-49)%10;row=(value-49)/10+5;}
                }
            }
            source.set(col*cardW,row*cardH,(col+1)*cardW,(row+1)*cardH);
            if(image!=null&&source.right<=image.getWidth()&&source.bottom<=image.getHeight()){
                bitmapPaint.setAlpha(opacity*255/100);
                canvas.drawBitmap(image,source,dest,bitmapPaint);
                bitmapPaint.setAlpha(255);
                return;
            }
            int layer=opacity<100?canvas.saveLayerAlpha(dest,opacity*255/100):0;
            shade(canvas,dest,0xfff4f1e9);outline(canvas,dest,0xff39414d);
            String label=tiles?(value==TILE_BACK?"▧":value==TILE_HINT?"?":Integer.toString(value/4+1)):value>=54?"◆":value>=52?"★":
                new String[]{"A","2","3","4","5","6","7","8","9","10","J","Q","K"}[value%13]+"♠♥♣♦".charAt(value/13);
            drawLabel(canvas,label,dest,cardW*.30f);
            if(layer!=0)canvas.restoreToCount(layer);
        }
        private void drawLabel(Canvas canvas,String label,RectF area,float size){
            ink.setColor(0xffffffff);
            if(label.length()>1&&label.length()<=4)ink.setColor(0xff17222c);
            ink.setTextSize(size);ink.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(label,area.centerX(),area.centerY()-(ink.ascent()+ink.descent())/2,ink);
        }
        private void shade(Canvas canvas,RectF area,int color){ink.setColor(color);canvas.drawRect(area,ink);}
        private void outline(Canvas canvas,RectF area,int color){
            ink.setStyle(Paint.Style.STROKE);ink.setStrokeWidth(1);shade(canvas,area,color);ink.setStyle(Paint.Style.FILL);
        }

        @Override public boolean onTouchEvent(MotionEvent event){
            float x=event.getX()*width/Math.max(1,getWidth()),y=event.getY()*height/Math.max(1,getHeight());
            switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                animationTouch=page==game.instructions.size()&&board.pressDuringAnimation();
                if(animationTouch){
                    presentBoard();
                    return true;
                }
                lastX=x;lastY=y;from=at(x,y);
                if(game.type==MiniGameBoard.PAINT&&page==game.instructions.size())stroke(event,x,y);
                return true;
            case MotionEvent.ACTION_MOVE:
                if(animationTouch)return true;
                if(game.type==MiniGameBoard.PAINT&&page==game.instructions.size()&&
                    ((int)x!=(int)lastX||(int)y!=(int)lastY))stroke(event,x,y);
                lastX=x;lastY=y;
                if(board.held>=0)invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                if(animationTouch){animationTouch=false;return true;}
                lastX=x;lastY=y;
                if(page<game.instructions.size()){
                    page++;if(page==game.instructions.size())exit.setVisibility(VISIBLE);
                }else if(stockArea.contains(x,y))board.drawStock();
                else if(wasteArea.contains(x,y))board.choose(MiniGameBoard.WASTE);
                else if(game.type>=MiniGameBoard.NIKAKU&&x>=hintX&&x<hintX+cardW*board.hintLeft&&
                    y>=hintY&&y<hintY+cardW)board.showHint();
                else {
                    int target=at(x,y);
                    if(game.type==MiniGameBoard.PUZZLE&&from>=0&&target>=0&&from!=target&&board.held<0)board.choose(from);
                    board.choose(target);
                }
                performClick();presentBoard();return true;
            case MotionEvent.ACTION_CANCEL:from=-1;animationTouch=false;return true;
            default:return true;
            }
        }
        boolean dismissHint(){
            if(!board.dismissHint())return false;
            presentBoard();
            return true;
        }
        boolean hintButton(boolean middle){
            if(game.type<MiniGameBoard.NIKAKU||page!=game.instructions.size()||openingRunning()||controlsBusy())
                return false;
            if(dismissHint())return true;
            if(!middle||!board.showHint())return false;
            presentBoard();
            return true;
        }
        @Override public boolean performClick(){super.performClick();return true;}
        private int at(float x,float y){
            for(int i=cells.length-1;i>=0;i--)
                if(cells[i]!=null&&cells[i].contains(x,y)&&
                    (game.type<=MiniGameBoard.SLIDE||board.exposed(i)))return i;
            return -1;
        }
        private void presentBoard(){
            String sound=board.takeSound();
            if(!outcomeReported&&(board.cleared||board.gameOver)){
                outcomeReported=true;
                if(board.cleared){
                    congraX=width/2;congraY=height/2;
                    playSound.accept(game.sound("Clear"));clear.run();
                }else playSound.accept(game.sound("Gameover"));
            }else if(sound!=null){
                playSound.accept(game.sound(sound));
            }
            invalidate();
            if(board.animationRunning())scheduleAnimation();
            else stopAnimationClock();
        }

        private boolean canAnimate(){
            return board.animationRunning()&&page==game.instructions.size()&&isAttachedToWindow()&&isShown()&&
                getWindowVisibility()==VISIBLE&&activeFrameReady()&&!openingRunning()&&!controlsBusy();
        }

        void scheduleAnimation(){
            if(nextAnimationFrame==0&&canAnimate()){
                nextAnimationFrame=SystemClock.uptimeMillis()+frameMs;
                postDelayed(animationTick,frameMs);
            }
        }

        private void advanceAnimation(){
            if(!canAnimate()){nextAnimationFrame=0;return;}
            long now=SystemClock.uptimeMillis();
            while(nextAnimationFrame<=now&&board.animationRunning()){
                board.advanceAnimation();
                nextAnimationFrame+=frameMs;
            }
            if(board.animationRunning())postDelayed(animationTick,Math.max(1,nextAnimationFrame-now));
            else nextAnimationFrame=0;
            presentBoard();
        }

        private void stopAnimationClock(){
            removeCallbacks(animationTick);
            nextAnimationFrame=0;
        }

        @Override protected void onDetachedFromWindow(){
            stopAnimationClock();
            super.onDetachedFromWindow();
        }

        @Override protected void onWindowVisibilityChanged(int visibility){
            super.onWindowVisibilityChanged(visibility);
            if(visibility!=VISIBLE)stopAnimationClock();
            else scheduleAnimation();
        }
        private void drawCongratulations(Canvas canvas){
            int w=game.congratulations.getWidth(),h=game.congratulations.getHeight();
            int maxX=Math.max(0,width-w),maxY=Math.max(0,height-h);
            congraX+=congraSpeedX;
            if(congraX<0){congraX=0;congraSpeedX=random.nextInt(10)+3;}
            if(congraX>maxX){congraX=maxX;congraSpeedX=-random.nextInt(10)-3;}
            congraSpeedY+=2;congraY+=congraSpeedY;
            if(congraY<0){congraY=0;congraSpeedY=1;}
            if(congraY>maxY){
                congraY=maxY;
                int speed=congraSpeedY*(70+random.nextInt(30))/100;
                congraSpeedY=speed<10?-20-random.nextInt(50):-speed;
            }
            canvas.drawBitmap(game.congratulations,null,rect(congraX,congraY,w,h),bitmapPaint);
            postInvalidateDelayed(20);
        }
        private void stroke(MotionEvent event,float x,float y){
            if(game.first==null)return;
            if(painted==null){
                painted=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
                Canvas initial=new Canvas(painted);
                initial.drawColor(0xff004080);
                paintFront=Bitmap.createScaledBitmap(game.first,width,height,true);
                if(game.second!=null){
                    paintBack=Bitmap.createScaledBitmap(game.second,width,height,true);
                    initial.drawBitmap(paintBack,null,bounds,bitmapPaint);
                }
                brushMask=new int[BRUSH_SIZE*BRUSH_SIZE];
                brushSource=new int[brushMask.length];brushTarget=new int[brushMask.length];
                for(int row=0;row<BRUSH_SIZE;row++)for(int col=0;col<BRUSH_SIZE;col++){
                    int dx=col-BRUSH_SIZE/2,dy=row-BRUSH_SIZE/2;
                    int distance=(int)Math.sqrt((dx*dx+dy*dy)*1.5);
                    if(distance<48)brushMask[row*BRUSH_SIZE+col]=50-distance+random.nextInt(5);
                }
            }
            int brushX=(int)x-BRUSH_SIZE/2,brushY=(int)y-BRUSH_SIZE/2;
            int left=Math.max(0,brushX),top=Math.max(0,brushY);
            int w=Math.min(width,brushX+BRUSH_SIZE)-left,h=Math.min(height,brushY+BRUSH_SIZE)-top;
            if(w<=0||h<=0)return;
            Bitmap picture=(event.getButtonState()&MotionEvent.BUTTON_SECONDARY)!=0?paintBack:paintFront;
            if(picture==null)java.util.Arrays.fill(brushSource,0,w*h,0xff004080);
            else picture.getPixels(brushSource,0,w,left,top,w,h);
            painted.getPixels(brushTarget,0,w,left,top,w,h);
            for(int row=0;row<h;row++)for(int col=0;col<w;col++){
                int weight=brushMask[(top-brushY+row)*BRUSH_SIZE+left-brushX+col];
                if(weight==0)continue;
                int i=row*w+col,front=brushSource[i],back=brushTarget[i];
                int red=(((front>>>16)&255)*weight+((back>>>16)&255)*(256-weight))>>>8;
                int green=(((front>>>8)&255)*weight+((back>>>8)&255)*(256-weight))>>>8;
                int blue=((front&255)*weight+(back&255)*(256-weight))>>>8;
                brushTarget[i]=0xff000000|(red<<16)|(green<<8)|blue;
            }
            painted.setPixels(brushTarget,0,w,left,top,w,h);
            invalidate();
        }
    }
    private static RectF rect(float x,float y,float w,float h){return new RectF(x,y,x+w,y+h);}
}
