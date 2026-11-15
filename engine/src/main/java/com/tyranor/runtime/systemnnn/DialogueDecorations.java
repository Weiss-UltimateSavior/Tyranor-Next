package com.tyranor.runtime.systemnnn;

import android.graphics.Bitmap;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

/** Original dialogue portrait atlases and CUTIN row geometry for one message. */
final class DialogueDecorations {
    static final DialogueDecorations EMPTY=new DialogueDecorations(0,false,new Portrait[0],new Row[0]);

    static final class Portrait {
        final Bitmap image;
        final SpriteAnimation animation;
        final int x,y,waitMin,waitMax,cycle;

        Portrait(ByteBuffer values,Bitmap image){
            this.image=image;
            x=values.getInt();y=values.getInt();
            int width=values.getInt(),height=values.getInt(),type=values.getInt(),count=values.getInt(),speed=values.getInt();
            animation=new SpriteAnimation(0,0,width,height,type,count,speed,true);
            waitMin=values.getInt();waitMax=Math.max(waitMin,values.getInt());
            cycle=(animation.pingPong?Math.max(1,2*(animation.count-1)):animation.count)*animation.speed;
        }
    }

    static final class Row {
        final Bitmap window;
        final boolean positioned;
        final int x,y,fontDelta,type,windowX,windowY,padX,padY,stepX,stepY,rows,color;

        Row(ByteBuffer values,Bitmap window){
            this.window=window;
            positioned=values.getInt()!=0;x=values.getInt();y=values.getInt();fontDelta=values.getInt();
            type=values.getInt();windowX=values.getInt();windowY=values.getInt();padX=values.getInt();padY=values.getInt();
            stepX=values.getInt();stepY=values.getInt();rows=values.getInt();color=values.getInt();
        }
    }

    final int depth;
    final boolean cutin;
    final Portrait[] portraits;
    final Row[] rows;

    private DialogueDecorations(int depth,boolean cutin,Portrait[] portraits,Row[] rows){
        this.depth=depth;this.cutin=cutin;this.portraits=portraits;this.rows=rows;
    }

    static DialogueDecorations load(byte[][] raw,long handle,GameUiAssets assets) throws IOException {
        if(raw==null)return EMPTY;
        if(raw.length<1||raw[0]==null||raw[0].length<16)throw new IOException("Missing dialogue decoration fields");
        ByteBuffer values=ByteBuffer.wrap(raw[0]).order(ByteOrder.LITTLE_ENDIAN);
        int depth=values.getInt();boolean cutin=values.getInt()!=0;
        int portraits=values.getInt(),rows=values.getInt();
        if(portraits<0||portraits>2||rows<0||rows>64||raw.length!=1+portraits+rows||
            values.remaining()!=4*(9*portraits+13*rows))throw new IOException("Invalid dialogue decoration fields");
        String[] names=new String[portraits+rows];
        for(int i=0;i<names.length;i++)names[i]=new String(raw[i+1],StandardCharsets.ISO_8859_1);
        Map<String,Bitmap> images=assets.modeSprites(handle,Arrays.asList(names));
        Portrait[] faces=new Portrait[portraits];
        Row[] bubbles=new Row[rows];
        for(int i=0;i<portraits;i++)faces[i]=new Portrait(values,image(assets,images,names[i]));
        for(int i=0;i<rows;i++)bubbles[i]=new Row(values,image(assets,images,names[portraits+i]));
        return new DialogueDecorations(depth,cutin,faces,bubbles);
    }

    private static Bitmap image(GameUiAssets assets,Map<String,Bitmap> images,String name){
        String key=GameUiAssets.systemImageKey(name);
        Bitmap image=images.get(key);
        return image!=null?image:assets.image(key);
    }
}
