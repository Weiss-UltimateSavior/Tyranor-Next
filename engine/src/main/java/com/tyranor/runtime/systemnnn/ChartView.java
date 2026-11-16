package com.tyranor.runtime.systemnnn;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;

/** Geometry and raw asset names resolved by the suspended native chart session. */
final class ChartView {
    private static final int HEADER=26, NODE_WORDS=11, ROAD_WORDS=5;

    static final class Node {
        final int id,x,y,style,access,sx,sy,sw,sh,routes,seen;
        final String asset;
        Node(int[] geometry,int at,String asset){
            id=geometry[at++];x=geometry[at++];y=geometry[at++];style=geometry[at++];access=geometry[at++];
            sx=geometry[at++];sy=geometry[at++];sw=geometry[at++];sh=geometry[at++];
            routes=geometry[at++];seen=geometry[at];this.asset=asset;
        }
    }
    static final class Road {
        final int x1,y1,x2,y2,style;
        Road(int[] geometry,int at){
            x1=geometry[at++];y1=geometry[at++];x2=geometry[at++];y2=geometry[at++];style=geometry[at];
        }
    }
    static final class Tile {
        final int y;
        final String asset;
        Tile(int y,String asset){this.y=y;this.asset=asset;}
    }

    final int scroll,minScroll,limit,extent,backExtent,centerY,slotX,slotY,slotAreaWidth,slotAreaHeight,slotWidth,slotHeight;
    final int miniX,miniY,miniWidth,miniHeight,miniBaseX,miniBaseY,pointerY,pending,pagePercent,totalPercent;
    final Integer currentPage;
    final int[] pageStates;
    final String center,minimap,unseen,current,complete;
    final Tile[] tiles;
    final Node[] nodes;
    final Road[] roads;
    final String[] description;

    ChartView(int[] geometry,byte[][] text,Function<byte[],String> decode){
        if(geometry==null||geometry.length<HEADER||text==null)throw new IllegalArgumentException("Missing chart geometry");
        int pageCount=geometry[20],tileCount=geometry[21],nodeCount=geometry[22],roadCount=geometry[23],descCount=geometry[24];
        long words=HEADER+(long)pageCount+tileCount+(long)NODE_WORDS*nodeCount+(long)ROAD_WORDS*roadCount;
        if(pageCount<1||pageCount>32||tileCount<0||nodeCount<0||roadCount<0||descCount<0||
            (words!=geometry.length&&words+2!=geometry.length)||
            5L+tileCount+nodeCount+descCount!=text.length)throw new IllegalArgumentException("Invalid chart geometry");
        scroll=geometry[0];limit=geometry[1];extent=geometry[2];centerY=geometry[3];backExtent=geometry[25];
        minScroll=words==geometry.length?0:geometry[(int)words];
        currentPage=words==geometry.length?null:geometry[(int)words+1];
        if(minScroll>limit)throw new IllegalArgumentException("Invalid chart scroll range");
        if(currentPage!=null&&(currentPage< -1||currentPage>=pageCount))
            throw new IllegalArgumentException("Invalid current chart page");
        slotX=geometry[4];slotY=geometry[5];slotAreaWidth=geometry[6];slotAreaHeight=geometry[7];
        slotWidth=geometry[8];slotHeight=geometry[9];
        miniX=geometry[10];miniY=geometry[11];miniWidth=geometry[12];miniHeight=geometry[13];
        miniBaseX=geometry[14];miniBaseY=geometry[15];
        pointerY=geometry[16];pending=geometry[17];pagePercent=geometry[18];totalPercent=geometry[19];
        center=asset(text[0]);minimap=asset(text[1]);unseen=asset(text[2]);current=asset(text[3]);complete=asset(text[4]);
        int at=HEADER,raw=5;
        pageStates=new int[pageCount];System.arraycopy(geometry,at,pageStates,0,pageCount);at+=pageCount;
        tiles=new Tile[tileCount];for(int i=0;i<tileCount;i++)tiles[i]=new Tile(geometry[at++],asset(text[raw++]));
        nodes=new Node[nodeCount];
        for(int i=0;i<nodeCount;i++){nodes[i]=new Node(geometry,at,asset(text[raw++]));at+=NODE_WORDS;}
        roads=new Road[roadCount];
        for(int i=0;i<roadCount;i++){roads[i]=new Road(geometry,at);at+=ROAD_WORDS;}
        description=new String[descCount];for(int i=0;i<descCount;i++)description[i]=decode.apply(text[raw++]);
    }
    int clampScroll(long value){return (int)Math.max(minScroll,Math.min(limit,value));}
    long scrollRange(){return (long)limit-minScroll;}
    int miniLength(int viewport){
        long total=minScroll==0?extent:scrollRange()+viewport;
        return total<=0?0:(int)Math.max(0,Math.min(miniHeight,(long)miniHeight*viewport/total));
    }
    int miniOffset(int value,int viewport){
        if(minScroll==0)return extent<=0?0:(int)((long)value*miniHeight/extent);
        long range=scrollRange();
        return range<=0?0:(int)(((long)clampScroll(value)-minScroll)*Math.max(0,miniHeight-miniLength(viewport))/range);
    }
    int scrollFromMini(long offset,int viewport){
        if(miniHeight<=0)return scroll;
        int travel=Math.max(0,miniHeight-miniLength(viewport));
        offset=Math.max(0,Math.min(travel,offset));
        return minScroll==0?clampScroll(offset*extent/miniHeight):
            travel==0?minScroll:clampScroll(minScroll+offset*scrollRange()/travel);
    }
    Set<String> images(){
        Set<String> images=new LinkedHashSet<>();
        for(String image:new String[]{center,minimap,unseen,current,complete})if(!image.isEmpty())images.add(image);
        for(Tile tile:tiles)if(!tile.asset.isEmpty())images.add(tile.asset);
        for(Node node:nodes)if(!node.asset.isEmpty())images.add(node.asset);
        return images;
    }
    private static String asset(byte[] raw){return GameUiAssets.systemImageKey(new String(raw,StandardCharsets.ISO_8859_1));}
}
