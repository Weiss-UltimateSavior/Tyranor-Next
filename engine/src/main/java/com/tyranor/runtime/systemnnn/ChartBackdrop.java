package com.tyranor.runtime.systemnnn;

import android.graphics.Bitmap;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.Function;

/** Pictures from one leased chart preview, drawn on either side of the chart content. */
final class ChartBackdrop {
    interface Composer {
        void compose(Bitmap base,Bitmap center,Bitmap content,Bitmap output,Consumer<Boolean> done);
    }
    static final class Frame {
        final Bitmap beforeCenter,afterCenter,foreground,center;
        final Bitmap[] tiles;
        final Composer composer;
        Frame(Bitmap[] groups,int mask,Bitmap center,Bitmap[] tiles,Composer composer){
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
    private Bitmap[] tiles=new Bitmap[0];
    private Bitmap center;
    private long[] textureIds=new long[0];
    private long revision;
    private int next,scriptTiles;
    private boolean rendered;

    ChartBackdrop(int width,int height,int centerLayer,int scriptTiles,Consumer<Frame> display){
        this.width=width;this.height=height;this.centerLayer=centerLayer;this.scriptTiles=scriptTiles;this.display=display;
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
        Bitmap[] updated=new Bitmap[Math.max(0,ids.length-1)];
        for(int i=0;i<updated.length;i++){
            if(ids[i]==0)continue;
            updated[i]=i<tiles.length&&i<textureIds.length-1&&textureIds[i]==ids[i]?
                tiles[i]:texture(handle,i,false);
        }
        long oldCenter=textureIds.length==0?0:textureIds[textureIds.length-1];
        long newCenter=ids.length==0?0:ids[ids.length-1];
        if(oldCenter!=newCenter)center=newCenter==0?null:texture(handle,centerLayer,true);
        textureIds=ids;tiles=updated;
    }
    private static Bitmap texture(long handle,int slot,boolean transparent){
        int[] raw=NativeUiPreview.chartTexture(handle,slot,transparent);
        if(raw==null||raw.length<2)return null;
        int w=raw[0],h=raw[1];
        if(w<=0||h<=0||(long)w*h!=raw.length-2L)return null;
        return Bitmap.createBitmap(raw,2,w,w,h,Bitmap.Config.ARGB_8888);
    }
}
