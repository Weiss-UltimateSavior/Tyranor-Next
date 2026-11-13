package com.tyranor.runtime.systemnnn;

import android.graphics.Bitmap;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The registration and controls in minigame.xtx / minigame.fxf. */
final class GameMiniGames {
    static final class Entry {
        final int index,type,x,y,w,h;
        final GameButton.Spec spec;
        final Bitmap image;
        Entry(int index,int type,int x,int y,int w,int h,GameButton.Spec spec,Bitmap image){
            this.index=index;this.type=type;this.x=x;this.y=y;this.w=w;this.h=h;this.spec=spec;this.image=image;
        }
    }
    final GameUiAssets.Config config;
    final int index,type;
    final Bitmap first,second,trump,mahjong,exit,congratulations;
    final List<Bitmap> instructions=new ArrayList<>();

    GameMiniGames(GameUiAssets.Config config,int index,long handle,List<Bitmap> backgrounds) throws IOException {
        this.config=config;this.index=index;
        type=config.number("miniGame"+(index+1),0);
        if(index<0||index>=config.number("addMiniGame",0)||type<1||type>10)throw new IOException("Invalid mini-game configuration");
        Bitmap fallback=picture(handle,config.value("noCGFileName","nnnlogo"),true);
        first=backgrounds.isEmpty()?fallback:backgrounds.get(0);
        second=backgrounds.size()>1?backgrounds.get(1):null;
        trump=type>=MiniGameBoard.MONTE&&type<=MiniGameBoard.GOLF?
            picture(handle,config.value("trumpPicFileName",""),false):null;
        mahjong=type>=MiniGameBoard.NIKAKU?picture(handle,config.value("mahjongPicFileName",""),false):null;
        exit=picture(handle,config.value("exitFileName",""),false);
        congratulations=picture(handle,config.value("congraFileName","ta_congra"),false);
        int count=DialogueLayout.clamp(config.number("miniGameDescNumber"+(index+1),0),0,16);
        for(int page=1;page<=count;page++){
            Bitmap image=picture(handle,config.value("miniGameDescFileName"+(index+1)+"_"+page,"bg_desc"),true);
            if(image!=null)instructions.add(image);
        }
    }
    static List<Entry> entries(File root,GameUiAssets.Config parent,long handle,String mode) throws IOException {
        if(parent.number("miniGameFlag",0)==0)return Collections.emptyList();
        return entries(GameUiAssets.readConfig(root,"minigame"),handle,mode);
    }
    static List<Entry> entries(GameUiAssets.Config config,long handle,String mode) {
        int count=DialogueLayout.clamp(config.number("addMiniGame",0),0,64);
        int checks=DialogueLayout.clamp(config.number("miniGameCheckNumber",count),0,count);
        List<Entry> entries=new ArrayList<>();
        boolean buttons=config.number("areaButtonMode",0)!=0;
        ButtonArt.Setup previous=null;
        String caller="omake";
        int areaX=0,areaY=0,areaW=16,areaH=16,buttonX=0,buttonY=0;
        for(int i=1;i<=checks;i++){
            String tag="miniGameButton"+i;
            previous=new ButtonArt.Setup(config,tag,previous,false);
            caller=config.value("miniGameCheckMode"+i,caller);
            areaX=config.number("miniGameCheckX"+i,areaX);areaY=config.number("miniGameCheckY"+i,areaY);
            areaW=DialogueLayout.clamp(config.number("miniGameCheckSizeX"+i,areaW),1,4096);
            areaH=DialogueLayout.clamp(config.number("miniGameCheckSizeY"+i,areaH),1,4096);
            buttonX=config.number(tag+"PrintX",buttonX);buttonY=config.number(tag+"PrintY",buttonY);
            int x=buttons?buttonX:areaX,y=buttons?buttonY:areaY;
            areaX+=areaW;buttonX+=previous.width;
            int type=config.number("miniGame"+i,0);
            String number=mode.equals("title")?"4":"15";
            if(type<1||type>10||!caller.equalsIgnoreCase(mode)&&!caller.equals(number))continue;
            String variable=config.value("checkVarName"+i,"");
            if(!variable.isEmpty()){
                byte[] key=raw(variable);
                if(NativeGame.menuVariable(handle,key)==0&&NativeGame.openingVariable(handle,key)!=-1)continue;
            }
            GameButton.Spec spec=buttons?new GameButton.Spec(previous):null;
            Bitmap art=buttons?picture(handle,GameUiAssets.systemImageKey(previous.art.image),false):null;
            entries.add(new Entry(i-1,type,x,y,buttons?previous.width:areaW,buttons?previous.height:areaH,spec,art));
        }
        return entries;
    }
    int layout(String field,int fallback){return config.number("miniGame"+(index+1)+field,fallback);}
    int sound(String name){return config.number("miniGame"+name+"Sound",0)-1;}

    static Bitmap picture(long handle,String name,boolean large){
        if(name.isEmpty())return null;
        int[] data=large?NativeGame.gallerySprite(handle,raw(name)):NativeGame.titleSprite(handle,raw(name));
        if(data==null||data.length<2||data[0]<=0||data[1]<=0||(long)data[0]*data[1]!=data.length-2L)return null;
        return Bitmap.createBitmap(data,2,data[0],data[0],data[1],Bitmap.Config.ARGB_8888);
    }
    private static byte[] raw(String value){return value.getBytes(StandardCharsets.ISO_8859_1);}
}
