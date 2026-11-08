package com.tyranor.runtime.systemnnn;

import android.graphics.Bitmap;
import android.util.Log;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Title assets are decoded from the selected game. Button source rectangles use the untrimmed canvas. */
final class TitleMenu {
    enum Action {
        START("新游戏"), LOAD("读取存档"), SETTINGS("设置"), EXTRAS("鉴赏"), EXIT("退出"),
        CONTINUE("继续游戏"), WEBSITE("官方网站"), MODE("扩展");
        final String label;
        Action(String label) { this.label=label; }
    }
    static final class Item {
        final Action action;
        final int x,y,width,height,mode;
        final boolean enabled;
        final String label;
        Bitmap image;
        GameButton.Spec spec;
        Item(Action action,int x,int y,int width,int height,boolean enabled) {
            this(action,x,y,width,height,enabled,0,action.label);
        }
        Item(Action action,int x,int y,int width,int height,boolean enabled,int mode,String label){
            this.action=action;this.x=x;this.y=y;this.width=width;this.height=height;this.enabled=enabled;
            this.mode=mode;this.label=label;
        }
    }
    final ArrayList<Item> items=new ArrayList<>();
    final boolean skinned;
    final String website;
    final GameUiAssets.Config config;
    final int frameMs;
    final List<LogoPanel.Slide> logos;

    TitleMenu(File root,long handle,boolean resume,boolean first) throws IOException {
        config=GameUiAssets.readConfig(root,"title");
        frameMs=Math.max(1,GameUiAssets.readConfig(root,"game").number("frameTime",20));
        logos=first?LogoPanel.load(root,handle):Collections.emptyList();
        boolean scripted=number("backScriptFlag",0)!=0;
        String script=scripted ? value("backScriptName","") : "";
        String background=scripted ? "" : GameUiAssets.systemImageKey(value("filenameBG","title_bg"));
        NativeGame.configureTitle(handle,raw(script),raw(background),number("backScriptLoopFlag",0)!=0,
            logos.isEmpty()?number("bgmNumber",-1):-1,number("bgmLoop",0),number("bgmVolume",0),number("bgmFadeInTime",-1));
        website=value("HTMLaddr","");
        boolean extras=GameResourcePaths.file(root,"init","omake.xtx")!=null||GameResourcePaths.file(root,"init2","omake.fxf")!=null;
        if (config.has("menu1Button1FileName") && config.has("menu1Button1PrintX")) {
            Action[] basic={Action.START,Action.LOAD,Action.SETTINGS,Action.EXTRAS,Action.EXIT};
            for (int i=0;i<basic.length;i++) add(handle,"menu1Button"+(i+1),basic[i],
                basic[i]!=Action.EXTRAS || extras);
            int count=DialogueLayout.clamp(number("basicButtonNumber",5),0,64);
            for(MenuModeButton extra:MenuModeButton.read(config,handle,count,5)){
                ButtonArt.Setup setup=extra.setup;
                Item item=new Item(Action.MODE,extra.x,extra.y,setup.width,setup.height,extra.enabled,extra.mode,extra.label);
                item.spec=new GameButton.Spec(setup);
                loadArtwork(handle,item,setup.art.image);
                items.add(item);
            }
            if (number("EnableLastLoadButton",0)!=0) add(handle,"LastLoad",Action.CONTINUE,resume);
            if (number("EnableHTMLButton",0)!=0) add(handle,"HTML",Action.WEBSITE,website.startsWith("https://") || website.startsWith("http://"));
        }
        skinned=!items.isEmpty() && items.get(0).action==Action.START;
        if (!skinned) {
            items.clear();
            for (Action a:new Action[]{Action.START,Action.CONTINUE,Action.LOAD,Action.SETTINGS,Action.EXIT})
                items.add(new Item(a,0,0,0,0,a!=Action.CONTINUE || resume));
            if (extras) items.add(items.size()-1,new Item(Action.EXTRAS,0,0,0,0,true));
        }
    }
    int musicNumber() { return number("bgmNumber",1); }
    boolean musicRestarts() { return number("bgmRestartFlag",1)!=0; }
    void restoreMusic(long handle) {
        NativeGame.menuMusic(handle,musicNumber(),number("bgmLoop",0),number("bgmVolume",0),
            number("bgmFadeInTime",-1),musicRestarts());
    }
    private String value(String k,String fallback) { return config.value(k,fallback); }
    private int number(String k,int fallback) { return config.number(k,fallback); }
    private static byte[] raw(String s) { return s.getBytes(StandardCharsets.ISO_8859_1); }
    private int option(String tag,String name,int fallback) {
        return number(tag+name,number("menu1Button1"+name,fallback));
    }
    private void add(long handle,String tag,Action action,boolean enabled) {
        int x=number(tag+"PrintX",-1),y=number(tag+"PrintY",-1);
        int w=option(tag,"SizeX",216),h=option(tag,"SizeY",52);
        if (x<0 || y<0 || w<=0 || h<=0 || w>2048 || h>2048) return;
        Item item=new Item(action,x,y,w,h,enabled);
        item.spec=new GameButton.Spec(config,tag,"menu1Button1",w,h);
        loadArtwork(handle,item,value(tag+"FileName",""));
        if (item.image==null) Log.w("SystemNNN","Title button uses text: "+tag);
        items.add(item);
    }
    private static void loadArtwork(long handle,Item item,String filename){
        String name=GameUiAssets.systemImageKey(filename);
        if (!name.isEmpty()) {
            int[] pixels=NativeGame.titleSprite(handle,raw(name));
            if (pixels!=null && pixels.length>=2 && (long)pixels[0]*pixels[1]==pixels.length-2L)
                item.image=Bitmap.createBitmap(pixels,2,pixels[0],pixels[0],pixels[1],Bitmap.Config.ARGB_8888);
        }
    }
}
