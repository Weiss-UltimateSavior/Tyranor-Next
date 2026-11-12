package com.tyranor.runtime.systemnnn;

import android.graphics.Bitmap;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.Function;

/** Pictures from one leased chart preview, drawn on either side of the chart content. */
final class ChartBackdrop {
    // Nine split layers may coexist with the five buffers used for exact composition.
    private static final long MAX_CANVAS_PIXELS=64L*1024*1024/(14*Integer.BYTES);
    private static final int MAX_TEXTURE_PIXELS=8*1024*1024;
    interface Composer {
        void compose(Bitmap base,Bitmap center,Bitmap content,Bitmap output,Consumer<Boolean> done);
    }
    static final class Tile {
        final int width,height;
        final Bitmap image;
        private final int budget;
        Tile(int width,int height,Bitmap image,int budget){
            this.width=width;this.height=height;this.image=image;this.budget=budget;
        }
    }
    static final class Frame {
        final Bitmap beforeCenter,afterCenter,foreground;
        final int rasterWidth,rasterHeight;
        final Tile center;
        final Tile[] tiles;
        final Composer composer;
        Frame(Bitmap[] groups,int mask,int width,int height,Tile center,Tile[] tiles,Composer composer){
            beforeCenter=(mask&1)==0?null:groups[0];
            afterCenter=(mask&2)==0?null:groups[1];
            foreground=(mask&4)==0?null:groups[2];
            rasterWidth=width;rasterHeight=height;
            this.center=center;this.tiles=tiles;this.composer=composer;
        }
    }
    final Consumer<Frame> display;
    final int centerLayer;
    private final int width,height;
    private final Bitmap[][] frames=new Bitmap[3][3];
    private Tile[] tiles=new Tile[0];
    private Tile center;
    private long[] textureIds=new long[0];
    private long revision;
    private int next,scriptTiles;
    private boolean rendered;

    ChartBackdrop(int width,int height,int centerLayer,int scriptTiles,Consumer<Frame> display){
        double scale=Math.min(UiOpening.snapshotScale(width,height),Math.sqrt(MAX_CANVAS_PIXELS/(double)width/height));
        this.width=Math.max(1,(int)(width*scale));
        this.height=Math.max(1,(int)(height*scale));
        this.centerLayer=centerLayer;this.display=display;
        tiles(scriptTiles);
    }
    void tiles(int count){scriptTiles=Math.min(count,4);}
    Frame render(long handle,Function<long[],Composer> composite){
        long current=NativeUiPreview.chartLayerRevision(handle,centerLayer);
        long[] ids=scriptTiles<0?new long[0]:NativeUiPreview.chartTextureIds(handle,scriptTiles,centerLayer);
        boolean texturesChanged=!Arrays.equals(textureIds,ids);
        if(rendered&&!texturesChanged&&revision==current)return null;
        if(texturesChanged)updateTextures(handle,ids);
        boolean exact=NativeUiPreview.chartNeedsComposite(handle,centerLayer);
        int mask=exact?0:NativeUiPreview.chartLayerMask(handle,centerLayer);
        Bitmap[] group=frames[next];
        next=(next+1)%frames.length;
        for(int i=0;i<group.length;i++){
            if((mask&(1<<i))!=0&&group[i]==null)group[i]=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        }
        if(mask!=0)mask=NativeUiPreview.chartLayers(handle,centerLayer,group[0],group[1],group[2]);
        revision=current;rendered=true;
        return new Frame(group,mask,width,height,center,tiles,exact?composite.apply(ids):null);
    }
    void discard(){
        for(Bitmap[] group:frames)Arrays.fill(group,null);
        tiles=null;
        center=null;
    }
    boolean matchesTextures(long handle,long[] ids){
        return ids.length==0||Arrays.equals(ids,NativeUiPreview.chartTextureIds(handle,ids.length-1,centerLayer));
    }
    private void updateTextures(long handle,long[] ids){
        int count=Math.max(0,ids.length-1);
        int[] requested=new int[ids.length];
        int total=0,present=0;
        for(int i=0;i<ids.length;i++){
            if(ids[i]==0)continue;
            boolean isCenter=i==count;
            int[] size=NativeUiPreview.chartTextureWithinBudget(handle,isCenter?centerLayer:i,isCenter,0);
            if(size==null||size.length!=4)continue;
            requested[i]=size[2]*size[3];
            total+=requested[i];
            present++;
        }
        Tile[] updated=new Tile[count];
        Tile newCenter=null;
        for(int i=0;i<ids.length;i++){
            if(requested[i]==0)continue;
            // Give every texture room for pixels, and share the remainder by area.
            int budget=total<=MAX_TEXTURE_PIXELS?requested[i]:
                1+(int)((long)(requested[i]-1)*(MAX_TEXTURE_PIXELS-present)/(total-present));
            boolean isCenter=i==count;
            int previousIndex=isCenter?textureIds.length-1:i;
            Tile previous=isCenter?center:(i<tiles.length?tiles[i]:null);
            Tile image=previous!=null&&previous.budget==budget&&textureIds[previousIndex]==ids[i]?previous:
                texture(handle,isCenter?centerLayer:i,isCenter,budget);
            if(isCenter)newCenter=image;
            else updated[i]=image;
        }
        textureIds=ids;tiles=updated;center=newCenter;
    }
    private static Tile texture(long handle,int slot,boolean transparent,int budget){
        int[] raw=NativeUiPreview.chartTextureWithinBudget(handle,slot,transparent,budget);
        if(raw==null||raw.length<4)return null;
        int width=raw[0],height=raw[1],w=raw[2],h=raw[3];
        if(w<=0||h<=0||w>width||h>height)return null;
        long area=(long)w*h;
        if(area>budget||area!=raw.length-4L)return null;
        Bitmap image=Bitmap.createBitmap(raw,4,w,w,h,Bitmap.Config.ARGB_8888);
        return new Tile(width,height,image,budget);
    }
}
