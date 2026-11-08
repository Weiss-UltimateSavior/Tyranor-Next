package com.tyranor.runtime.systemnnn;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** The selected game's message assets and decoded speaker palette; loaded on the game worker. */
final class DialogueStyle {
    final Bitmap[] windows,nameWindows;
    final DialogueLayout.Counts counts;
    final DialogueLayout.Text[] layouts,longLayouts;
    private final DialogueLayout.Name[] nameLayouts;
    int windowX,windowY,windowWidth,windowHeight,nameX,nameY,nameWidth,nameHeight;
    final boolean original,nameWindowEnabled;
    final int fontWeight,bodyColor,defaultNameColor,nameShadeColor;
    private final boolean printName,autoNameWindow,nameTableEnabled;
    final int readBodyColor,readNameColor;
    private final Map<String,Integer> names;
    private final Map<String,String> windowConfig;
    private int canvasWidth=1280,canvasHeight=720;

    private DialogueStyle(Bitmap[] windows,Bitmap[] nameWindows,Map<String,String> windowConfig,Map<String,String> config,
                          Map<String,String> game,Map<String,Integer> names) {
        this.windows=windows;this.nameWindows=nameWindows;this.windowConfig=windowConfig;this.names=names;
        original=!config.isEmpty()||!windowConfig.isEmpty();
        counts=DialogueLayout.counts(windowConfig);layouts=DialogueLayout.layouts(config);
        longLayouts=DialogueLayout.longLayouts(config,layouts);
        nameWindowEnabled=DialogueLayout.number(windowConfig,"nameWindowFlag",0)!=0;
        printName=DialogueLayout.number(config,"nameFlag",1)!=0;
        autoNameWindow=DialogueLayout.number(config,"autoNameWindowFlag",0)!=0;
        nameTableEnabled=DialogueLayout.usesNameTable(config);
        nameLayouts=DialogueLayout.nameLayouts(config,layouts,DialogueLayout.number(game,"codeByte",2)==1);
        int weight=DialogueLayout.number(game,"fontWeight",700);
        fontWeight=weight>=0&&weight<=1000?weight:700;
        boolean custom=DialogueLayout.number(config,"colorCustomFlag",0)!=0;
        bodyColor=color(config,"color",Color.WHITE);
        defaultNameColor=custom?color(config,"nameColor",Color.WHITE):Color.WHITE;
        nameShadeColor=custom?color(config,"nameShadeColor",Color.BLACK):Color.BLACK;
        readBodyColor=color(config,"readMessageColor",bodyColor);readNameColor=color(config,"readNameColor",defaultNameColor);
        canvas(canvasWidth,canvasHeight);
    }
    void canvas(int width,int height){
        canvasWidth=Math.max(1,width);canvasHeight=Math.max(1,height);
        windowX=number(windowConfig,"printX",0,0,canvasWidth);windowY=number(windowConfig,"printY",300,0,canvasHeight);
        windowWidth=number(windowConfig,"sizeX",640,1,canvasWidth);windowHeight=number(windowConfig,"sizeY",180,1,canvasHeight);
        nameX=number(windowConfig,"namePrintX",0,0,canvasWidth);nameY=number(windowConfig,"namePrintY",200,0,canvasHeight);
        nameWidth=number(windowConfig,"nameSizeX",640,1,canvasWidth);nameHeight=number(windowConfig,"nameSizeY",100,1,canvasHeight);
    }
    int bodyX(DialogueLayout.Text text){return DialogueLayout.clamp(text.x,0,canvasWidth-1);}
    int bodyY(DialogueLayout.Text text){return DialogueLayout.clamp(text.y,0,canvasHeight-1);}
    int bodyWidth(DialogueLayout.Text text){return Math.max(1,canvasWidth-bodyX(text)-Math.min(80,canvasWidth/4));}
    int bodyHeight(DialogueLayout.Text text){return Math.max(1,canvasHeight-bodyY(text)-Math.min(10,canvasHeight/4));}
    static DialogueStyle fallback() { return new DialogueStyle(new Bitmap[1],new Bitmap[1],new HashMap<>(),new HashMap<>(),new HashMap<>(),new HashMap<>()); }
    static DialogueStyle load(File root,GameTextDecoder codec,GameUiAssets assets) {
        Map<String,String> config=assets.printMessage.fields(),windows=assets.messageWindow.fields();Map<String,Integer> palette=new HashMap<>();
        try {
            boolean useNameTable=DialogueLayout.usesNameTable(config);
            File sourceNames=useNameTable?GameResourcePaths.file(root,"spt","namecol.xtx"):null;
            boolean encrypted=useNameTable&&sourceNames==null;
            if(encrypted)sourceNames=GameResourcePaths.file(root,"spt","namecol.fxf");
            if (sourceNames!=null) {
                byte[] raw=GameTextDecoder.readBounded(sourceNames,1024*1024);
                if(encrypted)for(int i=0;i<raw.length;i++)raw[i]^=(byte)255;
                String text=codec.decode(raw);
                for (String row:text.split("[\\r\\n]+")) {
                    String[] fields=row.trim().split("\\s+");
                    if (fields.length<4 || fields[0].startsWith("@") || fields[0].startsWith("//")) continue;
                    try {
                        int at=fields.length-3;
                        StringBuilder name=new StringBuilder(fields[0]);
                        for (int i=1;i<at;i++) name.append(' ').append(fields[i]);
                        int color=Color.rgb(Integer.parseInt(fields[at])&255,Integer.parseInt(fields[at+1])&255,Integer.parseInt(fields[at+2])&255);
                        if (!palette.containsKey(name.toString())) palette.put(name.toString(),color);
                    } catch (NumberFormatException ignored) { /* Ignore an incomplete palette row. */ }
                }
            }
        } catch (IOException e) { Log.w("SystemNNN","Message style uses default text colors",e); }
        DialogueLayout.Counts counts=DialogueLayout.counts(windows);
        String[] windowNames=DialogueLayout.names(windows,"filenameWindow","ta_meswin",counts);
        String[] nameNames=DialogueLayout.names(windows,"filenameNameWindow","ta_namewin",counts);
        Bitmap[] images=new Bitmap[counts.size()],nameImages=new Bitmap[counts.size()];
        boolean nameEnabled=DialogueLayout.number(windows,"nameWindowFlag",0)!=0;
        for(int i=0;i<images.length;i++){
            images[i]=assets.image(windowNames[i]);
            if(nameEnabled)nameImages[i]=assets.image(nameNames[i]);
        }
        return new DialogueStyle(images,nameImages,windows,config,assets.game.fields(),palette);
    }
    DialogueLayout.Name nameLayout(int font){return nameLayouts[DialogueLayout.clamp(font,0,nameLayouts.length-1)];}
    DialogueLayout.Message split(String text,boolean print){return DialogueLayout.split(text,print,printName,autoNameWindow,names.keySet());}
    int speakerColor(String name) { return nameTableEnabled?names.getOrDefault(name,Color.WHITE):defaultNameColor; }
    private static int number(Map<String,String> values,String key,int fallback,int min,int max) {
        return DialogueLayout.clamp(DialogueLayout.number(values,key,fallback),min,max);
    }
    private static int color(Map<String,String> config,String prefix,int fallback) {
        return Color.rgb(number(config,prefix+"R",Color.red(fallback),0,255),
            number(config,prefix+"G",Color.green(fallback),0,255),number(config,prefix+"B",Color.blue(fallback),0,255));
    }
}
