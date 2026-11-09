package com.tyranor.runtime.systemnnn;

import android.graphics.Bitmap;
import android.util.Log;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Title assets are decoded from the selected game. Button source rectangles use the untrimmed canvas. */
final class TitleMenu {
    enum Action {
        START("新游戏"), LOAD("读取存档"), SETTINGS("设置"), EXTRAS("鉴赏"), EXIT("退出"),
        CONTINUE("继续游戏"), LAST_LOAD("读取上次存档"), WEBSITE("官方网站"), MODE("扩展");
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
    final int frameMs,autoRestartTime;
    final boolean restartOnBackgroundEnd;
    TitleMenu(File root,long handle,boolean resume,boolean lastLoad) throws IOException {
        config=GameUiAssets.readConfig(root,"title");
        frameMs=Math.max(1,GameUiAssets.readConfig(root,"game").number("frameTime",20));
        boolean scripted=number("backScriptFlag",0)!=0;
        autoRestartTime=number("autoRestartTime",0);
        restartOnBackgroundEnd=scripted&&number("scriptEndIsModeEnd",0)!=0;
        String script=scripted ? value("backScriptName","") : "";
        String background=scripted ? "" : GameUiAssets.systemImageKey(value("filenameBG","title_bg"));
        NativeGame.configureMenuBackground(handle,4,raw(script),raw(background),number("backScriptLoopFlag",0)!=0);
        try{restoreMusic(handle);}
        catch(RuntimeException e){Log.w("SystemNNN","Title music unavailable",e);}
        website=value("HTMLaddr","");
        boolean extras=GameResourcePaths.file(root,"init","omake.xtx")!=null||GameResourcePaths.file(root,"init2","omake.fxf")!=null;
        Action[] basic={Action.START,Action.LOAD,Action.SETTINGS,Action.EXTRAS,Action.EXIT};
        int count=DialogueLayout.clamp(number("basicButtonNumber",basic.length),0,64);
        List<MenuModeButton> buttons=MenuModeButton.readTitle(config,handle,count,basic.length);
        skinned=config.has("menu1Button1FileName")||config.has("menu1Button1PrintX")||
            config.has("menu1Button1PrintY")||buttons.stream().anyMatch(button->!button.setup.art.image.isEmpty());
        Map<String,Bitmap> artwork=new HashMap<>();
        for(MenuModeButton button:buttons){
            Action action=button.index<basic.length?basic[button.index]:Action.MODE;
            if(!skinned&&(action==Action.MODE||action==Action.EXTRAS&&!extras))continue;
            ButtonArt.Setup setup=button.setup;
            boolean enabled=button.enabled&&(action!=Action.EXTRAS||extras);
            Item item=new Item(action,button.x,button.y,setup.width,setup.height,enabled,
                button.mode,action==Action.MODE?button.label:action.label);
            if(skinned){
                item.spec=new GameButton.Spec(setup);
                loadArtwork(handle,item,artwork);
            }
            items.add(item);
        }
        if(skinned){
            if (number("EnableLastLoadButton",0)>0) add(handle,"LastLoad",Action.LAST_LOAD,lastLoad,artwork);
            if (number("EnableHTMLButton",0)!=0) add(handle,"HTML",Action.WEBSITE,
                website.startsWith("https://")||website.startsWith("http://"),artwork);
        }else{
            int continueIndex=!items.isEmpty()&&items.get(0).action==Action.START?1:0;
            items.add(continueIndex,new Item(Action.CONTINUE,0,0,0,0,resume));
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
    private void add(long handle,String tag,Action action,boolean enabled,Map<String,Bitmap> artwork) {
        int x=number(tag+"PrintX",-1),y=number(tag+"PrintY",-1);
        int w=option(tag,"SizeX",216),h=option(tag,"SizeY",52);
        if (x<0 || y<0 || w<=0 || h<=0 || w>2048 || h>2048) return;
        Item item=new Item(action,x,y,w,h,enabled);
        item.spec=new GameButton.Spec(config,tag,null,w,h);
        loadArtwork(handle,item,artwork);
        if (item.image==null) Log.w("SystemNNN","Title button uses text: "+tag);
        items.add(item);
    }
    private static void loadArtwork(long handle,Item item,Map<String,Bitmap> artwork){
        String name=GameUiAssets.systemImageKey(item.spec.image);
        if(name.isEmpty())return;
        if(!artwork.containsKey(name)){
            Bitmap image=null;
            int[] pixels=NativeGame.titleSprite(handle,raw(name));
            if (pixels!=null && pixels.length>=2 && (long)pixels[0]*pixels[1]==pixels.length-2L)
                image=Bitmap.createBitmap(pixels,2,pixels[0],pixels[0],pixels[1],Bitmap.Config.ARGB_8888);
            artwork.put(name,image);
        }
        item.image=artwork.get(name);
    }
}
