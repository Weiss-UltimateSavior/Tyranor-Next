package com.tyranor.runtime.systemnnn;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.Random;

/** State and legal moves for the numbered games registered by CAllMiniGame. */
final class MiniGameBoard {
    static final int PUZZLE=1,CHANGE=2,PAINT=3,CROSS=4,SLIDE=5,MONTE=6,PYRAMID=7,GOLF=8,NIKAKU=9,KUSHI=10;
    static final int WASTE=-2;
    static final int REMOVED_TILE=-3;
    private static final int NONE=-1;
    static final int CARD_TRAVEL_FRAMES=15;
    private static final int CARD_REMOVE_FRAMES=20;
    static final int TURN_FRAMES=10;
    private static final int TILE_ERASE_FRAMES=25;
    static final int CONNECTION_FRAMES=10;
    private static final int HINT_FRAMES=29;
    enum Animation {
        NONE, DEAL, COMPACT, REFILL, REMOVE_CARDS, COLLECT_CARD, TURN_CARD, RECYCLE,
        REVEAL_TILES, CONNECT_TILES, ERASE_TILES, FALL_TILES, SHIFT_TILES, RETURN_TILES
    }

    final int type,columns,rows;
    final int[] cells,dealOrder;
    final ArrayDeque<Integer> stock=new ArrayDeque<>(),waste=new ArrayDeque<>();
    int selected=NONE,held=NONE,hintLeft=6;
    private boolean[] hint;
    private int hintRemaining;
    boolean cleared,gameOver;
    Animation animation=Animation.NONE;
    int frame,frameLimit,firstDealt,turningCard;
    int[] movingTo,erasing,connection,incoming;
    private final int tileWidth,tileHeight;
    private final ArrayDeque<Integer> orphanTiles=new ArrayDeque<>();
    private String sound;
    private int redeals=1;
    private final Random random;

    MiniGameBoard(int type,Random random,int tileWidth,int tileHeight){
        this.type=type;this.random=random;
        this.tileWidth=tileWidth;this.tileHeight=tileHeight;
        columns=type==PUZZLE?3:type==CROSS?8:type==MONTE?6:type==PYRAMID||type==GOLF?7:type>=NIKAKU?18:4;
        rows=type==CROSS||type>=NIKAKU?8:type==PYRAMID?7:type==GOLF?5:type==MONTE?4:columns;
        cells=new int[type==PAINT?0:type==PUZZLE?27:columns*rows];
        dealOrder=makeDealOrder();
        Arrays.fill(cells,NONE);
        switch(type){
        case PUZZLE:
            System.arraycopy(shuffled(18,random),0,cells,9,18);break;
        case CHANGE:
            System.arraycopy(shuffled(16,random),0,cells,0,16);break;
        case CROSS:
            Arrays.fill(cells,0);
            for(int i=0;i<12;i++)reverseCross(random.nextInt(cells.length));
            break;
        case SLIDE:
            for(int i=0;i<cells.length;i++)cells[i]=i;
            int[] edges={1,2,13,14,4,8,7,11};
            for(int i=0;i<12;i++)slide(edges[random.nextInt(edges.length)]);
            break;
        case MONTE:case PYRAMID:case GOLF:
            int[] deck=shuffleBySwaps(type==GOLF?54:52,true);
            int at=deck.length;
            if(type==GOLF)waste.addLast(deck[--at]);
            for(int slot:dealOrder)cells[slot]=deck[--at];
            while(at>0)stock.addLast(deck[--at]);
            begin(Animation.DEAL,dealOrder.length+CARD_TRAVEL_FRAMES);
            break;
        case NIKAKU:case KUSHI:
            System.arraycopy(shuffleBySwaps(cells.length,false),0,cells,0,cells.length);
            if(findPair()==null){
                int neighbor=type==KUSHI?columns-1:1;
                for(int i=1;i<cells.length;i++)if(cells[i]/4==cells[0]/4){
                    int swap=cells[neighbor];cells[neighbor]=cells[i];cells[i]=swap;break;
                }
            }
            begin(Animation.REVEAL_TILES,cells.length+1);
            break;
        default:break;
        }
    }

    private int[] makeDealOrder(){
        if(type<MONTE||type>GOLF)return new int[0];
        int[] order=new int[type==PYRAMID?rows*(rows+1)/2:cells.length];
        int at=0;
        if(type==GOLF){
            for(int x=0;x<columns;x++)for(int y=0;y<rows;y++)order[at++]=y*columns+x;
        }else{
            for(int y=0;y<rows;y++)for(int x=0;x<(type==PYRAMID?y+1:columns);x++)order[at++]=y*columns+x;
        }
        return order;
    }

    private int[] shuffleBySwaps(int size,boolean cards){
        int[] deck=new int[size];
        for(int i=0;i<size;i++)deck[i]=i;
        for(int i=0;i<size;i++){
            int other=cards?(i+random.nextInt(size-1))%size:random.nextInt(size);
            int card=deck[i];deck[i]=deck[other];deck[other]=card;
        }
        return deck;
    }

    private static int[] shuffled(int size,Random random){
        ArrayList<Integer> values=new ArrayList<>(size);
        for(int i=0;i<size;i++)values.add(i);
        Collections.shuffle(values,random);
        int[] result=new int[size];
        for(int i=0;i<size;i++)result[i]=values.get(i);
        return result;
    }

    boolean choose(int index){
        if(hint!=null||animationRunning()||cleared||gameOver)return false;
        if(index==WASTE)return type==PYRAMID&&!waste.isEmpty()&&pair(index);
        if(index<0||index>=cells.length)return false;
        switch(type){
        case PUZZLE:case CHANGE:
            if(cells[index]==NONE&&held==NONE)return false;
            int tile=cells[index];cells[index]=held;held=tile;
            cleared=inOrder(type==PUZZLE?9:cells.length);sound="Click";return true;
        case CROSS:
            reverseCross(index);
            cleared=Arrays.stream(cells).allMatch(value->value==cells[0]);sound="Click";return true;
        case SLIDE:
            if(!slide(index))return false;
            cleared=inOrder(cells.length);sound="Click";return true;
        case GOLF:
            if(!exposed(index)||!golfMatches(cells[index]))return false;
            erasing=new int[]{index};
            begin(Animation.COLLECT_CARD,CARD_REMOVE_FRAMES);
            return true;
        case MONTE:case PYRAMID:case NIKAKU:case KUSHI:
            return exposed(index)&&pair(index);
        default:return false;
        }
    }

    boolean drawStock(){
        if(animationRunning()||cleared||gameOver)return false;
        if(type==MONTE){
            if(canCompact()){
                movingTo=new int[cells.length];
                int target=0;
                for(int i=0;i<cells.length;i++)if(cells[i]>=0)movingTo[i]=target++;
                begin(Animation.COMPACT,CARD_TRAVEL_FRAMES);
            }else if(!refill())return false;
        }else if(type==PYRAMID||type==GOLF){
            if(selected!=NONE)return false;
            if(stock.isEmpty()){
                if(type!=PYRAMID||redeals==0||waste.isEmpty())return false;
                begin(Animation.RECYCLE,waste.size()+TURN_FRAMES);
            }else{
                turningCard=stock.removeFirst();
                begin(Animation.TURN_CARD,TURN_FRAMES);
            }
        }else return false;
        selected=NONE;
        sound="Click";
        return true;
    }

    boolean showHint(){
        if(type<NIKAKU||hintLeft==0||hint!=null||animationRunning()||cleared||gameOver)return false;
        hint=new boolean[cells.length];
        for(int i=0;i<cells.length;i++)if(exposed(i))
            for(int j=i+1;j<cells.length;j++)if(exposed(j)&&matches(i,j)){
                hint[i]=true;
                hint[j]=true;
            }
        hintRemaining=HINT_FRAMES;
        hintLeft--;
        selected=NONE;
        return true;
    }

    boolean hinted(int index){return hint!=null&&hint[index];}
    int hintAlpha(){
        if(hint==null||hintRemaining%5>=3)return 0;
        return (50+hintRemaining*50/HINT_FRAMES)*255/100;
    }
    boolean dismissHint(){
        if(hint==null)return false;
        hint=null;
        hintRemaining=0;
        return true;
    }
    boolean animationRunning(){return animation!=Animation.NONE||hintRemaining>0;}

    String takeSound(){
        String result=sound;
        sound=null;
        return result;
    }

    boolean pressDuringAnimation(){
        switch(animation){
        case NONE:
            return false;
        case FALL_TILES:case SHIFT_TILES:case RETURN_TILES:
            return true;
        case REVEAL_TILES:case CONNECT_TILES:case ERASE_TILES:
            finishAnimation();
            return true;
        default:
            if(frame>0)finishAnimation();
            return true;
        }
    }

    void advanceAnimation(){
        if(hintRemaining>0){hintRemaining--;return;}
        if(animationRunning()&&++frame>=frameLimit)finishAnimation();
    }

    private void begin(Animation next,int frames){
        animation=next;
        frame=0;
        frameLimit=frames;
    }

    private boolean refill(){
        if(stock.isEmpty())return false;
        int slot=0;
        while(slot<cells.length&&cells[slot]>=0)slot++;
        firstDealt=slot;
        while(slot<cells.length&&!stock.isEmpty())cells[slot++]=stock.removeFirst();
        if(firstDealt==slot)return false;
        begin(Animation.REFILL,slot-firstDealt+CARD_TRAVEL_FRAMES);
        return true;
    }

    private void finishAnimation(){
        Animation finished=animation;
        animation=Animation.NONE;
        switch(finished){
        case COMPACT:
            commitMoves();
            if(refill())frame=1;
            else checkEnd();
            break;
        case REMOVE_CARDS:
            for(int index:erasing)remove(index);
            checkEnd();
            break;
        case COLLECT_CARD:
            waste.clear();
            waste.addLast(cells[erasing[0]]);
            remove(erasing[0]);
            checkEnd();
            break;
        case TURN_CARD:
            if(type==GOLF)waste.clear();
            waste.addLast(turningCard);
            checkEnd();
            break;
        case RECYCLE:
            stock.addAll(waste);waste.clear();redeals--;
            break;
        case REFILL:
            checkEnd();
            break;
        case CONNECT_TILES:
            boolean flowers=cells[erasing[0]]/4>=34;
            for(int index:erasing)cells[index]=REMOVED_TILE;
            if(flowers&&!empty())begin(Animation.ERASE_TILES,TILE_ERASE_FRAMES);
            else checkEnd();
            break;
        case ERASE_TILES:
            if(type==KUSHI)eraseSkewer();
            if(empty())checkEnd();
            else startFall();
            break;
        case FALL_TILES:
            commitMoves();
            if(type==KUSHI&&hasBottomGap())startShift();
            else if(type!=KUSHI||!startReturn())checkEnd();
            break;
        case SHIFT_TILES:
            commitMoves();
            if(!startReturn())checkEnd();
            break;
        case RETURN_TILES:
            for(int col=0;col<columns;col++)if(incoming[col]>=0){
                int row=emptyTopRows(col)-1;
                cells[row*columns+col]=incoming[col];
            }
            if(!startReturn())checkEnd();
            break;
        default:break;
        }
    }

    int dealProgress(int ordinal){
        int delay=animation==Animation.REFILL?ordinal-firstDealt:ordinal;
        return Math.min(CARD_TRAVEL_FRAMES,frame-delay);
    }

    boolean marked(int index){
        if(erasing!=null)for(int slot:erasing)if(slot==index)return true;
        return false;
    }

    int wasteCard(boolean beneath){
        if(!beneath)return waste.isEmpty()?NONE:waste.peekLast();
        Iterator<Integer> cards=waste.descendingIterator();
        if(cards.hasNext())cards.next();
        return cards.hasNext()?cards.next():NONE;
    }

    private boolean inOrder(int count){
        for(int i=0;i<count;i++)if(cells[i]!=i)return false;
        return true;
    }
    private void reverseCross(int index){
        cells[index]^=1;
        int x=index%columns,y=index/columns;
        if(x>0)cells[index-1]^=1;
        if(x+1<columns)cells[index+1]^=1;
        if(y>0)cells[index-columns]^=1;
        if(y+1<rows)cells[index+columns]^=1;
    }
    private boolean slide(int index){
        int x=index%4,y=index/4;
        int delta;
        if(x==1||x==2){
            if(y!=0&&y!=3)return false;
            delta=y==0?4:-4;
        }else{
            if(y==0||y==3)return false;
            delta=x==0?1:-1;
        }
        int last=cells[index+3*delta];
        for(int i=3;i>0;i--)cells[index+i*delta]=cells[index+(i-1)*delta];
        cells[index]=last;
        return true;
    }

    boolean exposed(int index){
        if(cells[index]<0)return false;
        int next=index+columns;
        if(type==PYRAMID)return next>=cells.length||index%columns<columns-1&&cells[next]==NONE&&cells[next+1]==NONE;
        return type!=GOLF||next>=cells.length||cells[next]==NONE;
    }
    private boolean golfMatches(int value){
        int current=waste.peekLast();
        int difference=(value%13-current%13+13)%13;
        return value>=52||current>=52||difference==1||difference==12;
    }
    private boolean pair(int index){
        int value=valueAt(index);
        if(type==PYRAMID&&value%13==12&&selected==NONE){
            erasing=new int[]{index};
            begin(Animation.REMOVE_CARDS,CARD_REMOVE_FRAMES);
            sound="Good";
            return true;
        }
        if(selected==index){selected=NONE;if(type<NIKAKU)sound="Click";return true;}
        if(selected==NONE){selected=index;sound="Click";return true;}
        if(!matches(selected,index)){
            if(type<NIKAKU)sound="Bad";
            return false;
        }
        if(type==KUSHI){
            int delta=Integer.signum(index%columns-selected%columns)+columns*Integer.signum(index/columns-selected/columns);
            erasing=new int[(index-selected)/delta+1];
            for(int i=0;i<erasing.length;i++)erasing[i]=selected+i*delta;
            begin(Animation.ERASE_TILES,TILE_ERASE_FRAMES);
        }else if(type==NIKAKU){
            erasing=new int[]{selected,index};
            connection=connectionPath(selected,index);
            begin(Animation.CONNECT_TILES,(connection.length/2-1)*(CONNECTION_FRAMES+1));
        }else{
            erasing=new int[]{selected,index};
            begin(Animation.REMOVE_CARDS,CARD_REMOVE_FRAMES);
        }
        selected=NONE;sound="Good";return true;
    }
    private void remove(int index){
        if(index==WASTE)waste.removeLast();
        else cells[index]=NONE;
    }
    private int valueAt(int index){return index==WASTE?waste.peekLast():cells[index];}
    private boolean matches(int first,int second){
        int a=valueAt(first),b=valueAt(second);
        if(type==PYRAMID)return a%13+b%13==11;
        int dx=Math.abs(first%columns-second%columns),dy=Math.abs(first/columns-second/columns);
        if(type==MONTE)return a%13==b%13&&dx<=1&&dy<=1;
        if(a/4!=b/4)return false;
        if(type==NIKAKU)return connectionPath(first,second)!=null;
        if(dx>0&&dy>0)return dx==1&&dy==1;
        int delta=dx==0?columns:1;
        for(int i=Math.min(first,second)+delta;i<Math.max(first,second);i+=delta)
            if(cells[i]==NONE)return false;
        return true;
    }
    private int[] connectionPath(int a,int b){
        int x=a%columns,y=a/columns,u=b%columns,v=b/columns;
        if((x==u||y==v)&&line(x,y,u,v,a,b))return new int[]{x,y,u,v};
        if(x!=u&&y!=v){
            if(line(x,y,x,v,a,b)&&line(x,v,u,v,a,b))return new int[]{x,y,x,v,u,v};
            if(line(x,y,u,y,a,b)&&line(u,y,u,v,a,b))return new int[]{x,y,u,y,u,v};
        }
        for(int column=-1;column<=columns;column++){
            if(column!=x&&column!=u&&line(x,y,column,y,a,b)&&line(column,y,column,v,a,b)&&line(column,v,u,v,a,b))
                return new int[]{x,y,column,y,column,v,u,v};
        }
        for(int row=-1;row<=rows;row++){
            if(row!=y&&row!=v&&line(x,y,x,row,a,b)&&line(x,row,u,row,a,b)&&line(u,row,u,v,a,b))
                return new int[]{x,y,x,row,u,row,u,v};
        }
        return null;
    }
    private boolean line(int x,int y,int u,int v,int a,int b){
        int dx=Integer.compare(u,x),dy=Integer.compare(v,y);
        for(;;x+=dx,y+=dy){
            if(x>=0&&x<columns&&y>=0&&y<rows){
                int index=y*columns+x;
                if(index!=a&&index!=b&&cells[index]>=0)return false;
            }
            if(x==u&&y==v)return true;
        }
    }
    private void startFall(){
        movingTo=new int[cells.length];
        int maxDistance=0;
        for(int x=0;x<columns;x++){
            int target=rows-1;
            for(int y=rows-1;y>=0;y--){
                int index=y*columns+x;
                if(cells[index]<0)continue;
                movingTo[index]=target*columns+x;
                maxDistance=Math.max(maxDistance,(target-y)*tileHeight);
                target--;
            }
        }
        begin(Animation.FALL_TILES,acceleratedFrames(maxDistance));
    }

    private boolean hasBottomGap(){
        int bottom=cells.length-columns;
        for(int i=bottom;i+1<cells.length;i++)if(cells[i]<0&&cells[i+1]>=0)return true;
        return false;
    }

    private void startShift(){
        movingTo=new int[cells.length];
        int maxDistance=0;
        for(int y=0;y<rows;y++){
            int target=0;
            for(int x=0;x<columns;x++){
                int index=y*columns+x;
                if(cells[index]<0)continue;
                movingTo[index]=y*columns+target;
                maxDistance=Math.max(maxDistance,(x-target)*tileWidth);
                target++;
            }
        }
        begin(Animation.SHIFT_TILES,acceleratedFrames(maxDistance));
    }

    private void commitMoves(){
        int[] result=new int[cells.length];
        Arrays.fill(result,NONE);
        for(int i=0;i<cells.length;i++)if(cells[i]>=0)result[movingTo[i]]=cells[i];
        System.arraycopy(result,0,cells,0,cells.length);
    }

    private void eraseSkewer(){
        int[] removed=new int[erasing.length-1];
        for(int i=0;i<erasing.length;i++){
            int index=erasing[i];
            if(i<removed.length)removed[i]=cells[index];
            remove(index);
        }
        int[] remaining=new int[36];
        for(int value:cells)if(value>=0)remaining[value/4]++;
        for(int group=0;group<remaining.length;group++){
            if(remaining[group]!=1)continue;
            for(int value:removed)if(value/4==group){orphanTiles.addLast(value);break;}
        }
    }

    private boolean startReturn(){
        if(orphanTiles.isEmpty())return false;
        incoming=new int[columns];
        Arrays.fill(incoming,NONE);
        int maxDistance=0;
        while(!orphanTiles.isEmpty()){
            int start=random.nextInt(columns),chosen=-1;
            for(int n=0;n<columns;n++){
                int col=(start+n)%columns;
                if(cells[col]==NONE&&incoming[col]==NONE){chosen=col;break;}
            }
            if(chosen<0)break;
            incoming[chosen]=orphanTiles.removeFirst();
            maxDistance=Math.max(maxDistance,emptyTopRows(chosen)*tileHeight);
        }
        if(maxDistance==0){orphanTiles.clear();return false;}
        begin(Animation.RETURN_TILES,acceleratedFrames(maxDistance));
        return true;
    }

    private int emptyTopRows(int col){
        int row=0;
        while(row<rows&&cells[row*columns+col]==NONE)row++;
        return row;
    }

    int tileOffset(int index){
        int target=movingTo[index];
        int distance=animation==Animation.SHIFT_TILES
            ?(index%columns-target%columns)*tileWidth:(target/columns-index/columns)*tileHeight;
        return Math.min(distance,acceleratedDistance(frame));
    }

    int returnOffset(int column){
        return Math.min(emptyTopRows(column)*tileHeight,acceleratedDistance(frame));
    }

    private static int acceleratedDistance(int frames){return frames*(frames+1)/2;}

    private static int acceleratedFrames(int distance){
        int frames=1;
        while(acceleratedDistance(frames)<distance)frames++;
        return frames;
    }
    private int[] findPair(){
        for(int i=0;i<cells.length;i++)if(exposed(i))
            for(int j=i+1;j<cells.length;j++)if(exposed(j)&&matches(i,j))return new int[]{i,j};
        return null;
    }
    private boolean empty(){return Arrays.stream(cells).noneMatch(value->value>=0);}

    private void checkEnd(){
        cleared=empty()&&(type!=MONTE||stock.isEmpty());
        if(cleared)return;
        if(type==GOLF){
            if(!stock.isEmpty())return;
            for(int i=0;i<cells.length;i++)if(exposed(i)&&golfMatches(cells[i]))return;
        }else{
            if(type==MONTE&&(canCompact()||cells[cells.length-1]==NONE&&!stock.isEmpty()))return;
            if(type==PYRAMID){
                if(!stock.isEmpty()||redeals>0&&!waste.isEmpty())return;
                if(!waste.isEmpty()&&waste.peekLast()%13==12)return;
                for(int i=0;i<cells.length;i++)if(exposed(i)&&
                    (cells[i]%13==12||!waste.isEmpty()&&matches(i,WASTE)))return;
            }
            if(findPair()!=null)return;
        }
        gameOver=true;
    }
    private boolean canCompact(){
        boolean gap=false;
        for(int value:cells){
            if(value==NONE)gap=true;
            else if(gap)return true;
        }
        return false;
    }
}
