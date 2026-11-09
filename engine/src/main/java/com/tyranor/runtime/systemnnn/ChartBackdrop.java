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
        final int height;
        final Bitmap image;
        Tile(int height,Bitmap image){this.height=height;this.image=image;}
    }
    static final class Frame {
        final Bitmap beforeCenter,afterCenter,foreground,center;
        final Tile[] tiles;
        final Composer composer;
        Frame(Bitmap[] groups,int mask,Bitmap center,Tile[] tiles,Composer composer){
            beforeCenter=(mask&1)==0?null:groups[0];
            afterCenter=(mask&2)==0?null:groups[1];
            foreground=(mask&4)==0?null:groups[2];
            this.center=center;this.tiles=tiles;this.composer=composer;
        }
    }
    final Consumer<Frame> display;
    final int centerLayer;
    private final int width,height;
    private final Bitmap[][] frames=new Bitmap[3][3];
    private Tile[] tiles=new Tile[0];
    private Bitmap center;
    private long[] textureIds=new long[0];
    private long revision;
    private int next,scriptTiles;
    private boolean rendered;

    ChartBackdrop(int width,int height,int centerLayer,int scriptTiles,Consumer<Frame> display){
        this.width=width;this.height=height;this.centerLayer=centerLayer;this.scriptTiles=scriptTiles;this.display=display;
    }
    static boolean supportsCanvas(int width,int height){
        return width>0&&height>0&&(long)width*height<=MAX_CANVAS_PIXELS&&UiOpening.snapshotScale(width,height)==1f;
    }
    void tiles(int count){scriptTiles=count;}
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
        return new Frame(group,mask,center,tiles,exact?composite.apply(ids):null);
    }
    boolean matchesTextures(long handle,long[] ids){
        return ids.length==0||Arrays.equals(ids,NativeUiPreview.chartTextureIds(handle,ids.length-1,centerLayer));
    }
    private void updateTextures(long handle,long[] ids){
        Tile[] updated=new Tile[Math.max(0,ids.length-1)];
        long oldCenter=textureIds.length==0?0:textureIds[textureIds.length-1];
        long newCenter=ids.length==0?0:ids[ids.length-1];
        if(oldCenter!=newCenter){
            Tile texture=newCenter==0?null:texture(handle,centerLayer,true,MAX_TEXTURE_PIXELS);
            center=texture==null?null:texture.image;
        }
        int remaining=MAX_TEXTURE_PIXELS-pixels(center);
        for(int i=0;i<updated.length;i++){
            if(ids[i]==0)continue;
            Tile previous=i<tiles.length&&textureIds[i]==ids[i]?tiles[i]:null;
            if(previous!=null&&previous.image!=null)
                updated[i]=pixels(previous.image)<=remaining?previous:new Tile(previous.height,null);
            else updated[i]=texture(handle,i,false,remaining);
            if(updated[i]!=null)remaining-=pixels(updated[i].image);
        }
        textureIds=ids;tiles=updated;
    }
    private static int pixels(Bitmap image){
        return image==null?0:image.getWidth()*image.getHeight();
    }
    private static Tile texture(long handle,int slot,boolean transparent,int budget){
        int[] raw=NativeUiPreview.chartTextureWithinBudget(handle,slot,transparent,budget);
        if(raw==null||raw.length<2)return null;
        int w=raw[0],h=raw[1];
        if(w<=0||h<=0)return null;
        long area=(long)w*h;
        Bitmap image=area<=budget&&area==raw.length-2L?Bitmap.createBitmap(raw,2,w,w,h,Bitmap.Config.ARGB_8888):null;
        return new Tile(h,image);
    }
}
