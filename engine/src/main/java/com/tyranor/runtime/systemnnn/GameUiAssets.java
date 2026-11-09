package com.tyranor.runtime.systemnnn;

import android.graphics.Bitmap;
import android.util.Log;
import android.view.PointerIcon;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Original menu configuration and eagerly decoded sprite sheets, loaded on the game worker. */
final class GameUiAssets {
    private static final int MAX_CONFIG_BYTES=1024*1024;
    private static final long MAX_IMAGE_PIXELS=4L*1024*1024, MAX_TOTAL_PIXELS=32L*1024*1024;

    static final class Config {
        private final Map<String,String> values;
        private final GameTextDecoder decoder;
        private Config(Map<String,String> values,GameTextDecoder decoder) { this.values=Collections.unmodifiableMap(values);this.decoder=decoder; }
        String value(String name,String fallback) {
            String result=values.get(key(name));
            return result==null ? fallback : result;
        }
        int number(String name,int fallback) {
            String value=trimAscii(value(name,""));
            int end=0;
            while (end<value.length() && !spacing(value.charAt(end))) ++end;
            try { return Integer.parseInt(value.substring(0,end)); }
            catch (NumberFormatException e) { return fallback; }
        }
        boolean has(String name) { return values.containsKey(key(name)); }
        Map<String,String> fields() { return values; }
        String text(String name,String fallback) {
            String value=value(name,"");
            if(value.isEmpty())return fallback;
            try{return decoder.decodeConfiguration(value.getBytes(StandardCharsets.ISO_8859_1));}
            catch(IOException e){Log.w("SystemNNN","Invalid game configuration text: "+name,e);return fallback;}
        }
        Set<String> imageNames() {
            return imageNames(name->true);
        }
        Set<String> imageNames(java.util.function.Predicate<String> include) {
            Set<String> result=new LinkedHashSet<>();
            for(Map.Entry<String,String> field:values.entrySet())
                if(field.getKey().contains("filename")&&include.test(field.getKey())&&!field.getValue().isEmpty())
                    result.add(systemImageKey(field.getValue()));
            return result;
        }
    }

    static final class ModeAssets {
        final Config config,variant;
        final Map<String,Bitmap> images,opaqueImages;
        ModeAssets(Config config,Map<String,Bitmap> images){
            this(config,null,images);
        }
        ModeAssets(Config config,Config variant,Map<String,Bitmap> images){
            this(config,variant,images,Collections.emptyMap());
        }
        ModeAssets(Config config,Config variant,Map<String,Bitmap> images,Map<String,Bitmap> opaqueImages){
            this.config=config;this.variant=variant;this.images=Collections.unmodifiableMap(images);
            this.opaqueImages=Collections.unmodifiableMap(opaqueImages);
        }
    }

    final Config system,config,save,load,backlog,messageWindow,game,printMessage,dataFile,notice,messageCursor,selection;
    final Config gameMouse,selectPlace,selectPlace2;
    final boolean freeStatus;
    final Bitmap longPrintImage;
    final boolean longPrintImageOffWithWindow;
    final long systemExtensionModes;
    final Map<String,Config> storyButtons;
    final File root;
    RuntimeHandle runtime;
    private final Map<String,Bitmap> images;
    private final int[] textRates;
    private final boolean legacyVolumeDefaults;
    private GameUiAssets(File root,Config system,Config config,Config save,Config load,Config backlog,Config messageWindow,
                         Config game,Config printMessage,Config dataFile,Config notice,Config messageCursor,Config selection,Config gameMouse,Config selectPlace,Config selectPlace2,
                         Map<String,Config> storyButtons,Map<String,Bitmap> images,int[] textRates,boolean freeStatus,boolean legacyVolumeDefaults) throws IOException {
        this.root=root.getCanonicalFile();
        this.system=system; this.config=config; this.save=save; this.load=load; this.backlog=backlog;
        this.messageWindow=messageWindow;
        this.dataFile=dataFile;this.notice=notice;this.messageCursor=messageCursor;this.selection=selection;
        this.game=game;this.printMessage=printMessage;this.storyButtons=Collections.unmodifiableMap(storyButtons);
        this.gameMouse=gameMouse;this.selectPlace=selectPlace;this.selectPlace2=selectPlace2;
        this.images=Collections.unmodifiableMap(images);
        this.longPrintImage=images.get(longPrintImageKey(printMessage));
        this.longPrintImageOffWithWindow=printMessage.number("addLPrintImageOffWithWindow",0)!=0;
        this.textRates=textRates;
        this.freeStatus=freeStatus;
        this.legacyVolumeDefaults=legacyVolumeDefaults;
        long extensions=0;
        for(int item=10;item<=GameSystemMenuPanel.optionCount(system);item++){
            int mode=system.number("expOptionMode"+item,0);
            if(mode>0&&mode<=49)extensions|=1L<<mode;
        }
        systemExtensionModes=extensions;
    }
    OriginalSettingsModel settingsModel(int width,int height){
        return new OriginalSettingsModel(config,game,width,height,legacyVolumeDefaults);
    }
    private static String key(String value) { return value.toLowerCase(Locale.ROOT); }
    private static boolean spacing(char c){return c==' '||c=='\t';}
    private static String trimAscii(String value){
        int first=0,last=value.length();
        while(first<last&&spacing(value.charAt(first)))first++;
        while(last>first&&spacing(value.charAt(last-1)))last--;
        return value.substring(first,last);
    }
    static String imageKey(String value) {
        String name=trimAscii(value);
        StringBuilder result=new StringBuilder(name.length());
        for (int i=0;i<name.length();++i) {
            char c=name.charAt(i);
            // Configuration strings preserve the original CP932 bytes.
            if((c>=0x81&&c<=0x9f||c>=0xe0&&c<=0xfc)&&i+1<name.length()){
                char trail=name.charAt(i+1);
                if(trail>=0x40&&trail<=0xfc&&trail!=0x7f){result.append(c).append(trail);++i;continue;}
            }
            result.append(c=='\\'?'/':c>='A'&&c<='Z'?(char)(c+('a'-'A')):c);
        }
        name=result.toString();
        return name.endsWith(".dwq") ? name.substring(0,name.length()-4) : name;
    }
    static String systemImageKey(String value) {
        String name=imageKey(value);
        return name.isEmpty()||name.indexOf('/')>=0?name:"sys/"+name;
    }
    private static String longPrintImageKey(Config config) {
        if(config.number("LPrintAddImageFlag",0)==0)return "";
        return systemImageKey(config.value("FilenameLPrintImage","lprintimage"));
    }
    /** Does not perform I/O, decoding or JNI calls; safe to query from UI drawing code. */
    Bitmap image(String name) { return name==null ? null : images.get(systemImageKey(name)); }
    Config place(int mode){return mode==NativeGame.SELECT_PLACE2_MODE?selectPlace2:selectPlace;}
    ModeAssets modeAssets(long handle,GameTextDecoder decoder,String name,String... defaults) throws IOException {
        return modeAssets(handle,decoder,name,key->true,Arrays.asList(defaults));
    }
    ModeAssets modeAssets(long handle,GameTextDecoder decoder,String name,java.util.function.Predicate<String> include,
                          Collection<String> additional) throws IOException {
        return modeAssets(handle,decoder,name,include,additional,"");
    }
    ModeAssets modeAssets(long handle,GameTextDecoder decoder,String name,java.util.function.Predicate<String> include,
                          Collection<String> additional,String keyedImage) throws IOException {
        Config config=readConfig(root,name,decoder);
        Set<String> requested=config.imageNames(include);
        for(String image:additional)requested.add(systemImageKey(image));
        return new ModeAssets(config,modeSprites(handle,requested,keyedImage));
    }
    ModeAssets variantModeAssets(long handle,GameTextDecoder decoder,String name,String variantName,Collection<String> rows) throws IOException {
        Config config=readConfig(root,name,decoder),variant=readConfig(root,variantName,decoder);
        Set<String> requested=config.imageNames();
        requested.addAll(variant.imageNames(key->!key.startsWith("object")&&!key.startsWith("add")));
        requested.addAll(rows);
        return new ModeAssets(config,variant,modeSprites(handle,requested));
    }
    ModeAssets chartAssets(long handle,GameTextDecoder decoder,ChartView chart,ModeAssets previous) throws IOException {
        Config config=previous==null?readConfig(root,"selectChart",decoder):previous.config;
        Set<String> opaque=new LinkedHashSet<>();
        for(ChartView.Tile tile:chart.tiles)opaque.add(tile.asset);
        Set<String> requested=new LinkedHashSet<>(chart.images());
        requested.removeAll(opaque);
        requested.addAll(config.imageNames());
        Collections.addAll(requested,"ta_chart_meswin1","ta_chartmap_cur01","ta_chart_comp","ta_chart_font01",
            "ta_chart_minimap01","ta_chart_minimap03","ta_capture","ta_dialog_4","ta_anime");
        return new ModeAssets(config,null,
            modeSprites(handle,requested,chart.center,previous==null?Collections.emptyMap():previous.images,false),
            modeSprites(handle,opaque,"",previous==null?Collections.emptyMap():previous.opaqueImages,true));
    }
    ModeAssets partyDigits(long handle,ModeAssets data,Collection<String> opaque) {
        if(opaque.isEmpty())return data;
        return new ModeAssets(data.config,data.variant,data.images,modeSprites(handle,opaque,"",Collections.emptyMap(),true));
    }
    ModeAssets statusAssets(long handle,GameTextDecoder decoder,boolean party) throws IOException {
        List<String> names=new ArrayList<>(Arrays.asList(game.value("BackFileName","ta_backButton"),
            game.value("UpFileName","ta_upButton"),game.value("DownFileName","ta_downButton"),"ta_suuji"));
        if(!party)return modeAssets(handle,decoder,"printStatus",key->true,names);
        names.add("ta_anime");
        ModeAssets data=variantModeAssets(handle,decoder,"printParty","faceStatusList",names);
        return partyDigits(handle,data,GameStatusPartyPanel.opaqueDigits(data.variant));
    }
    Map<String,Bitmap> modeSprites(long handle,Collection<String> requested){
        return modeSprites(handle,requested,"");
    }
    private Map<String,Bitmap> modeSprites(long handle,Collection<String> requested,String keyedImage){
        return modeSprites(handle,requested,keyedImage,Collections.emptyMap(),false);
    }
    private Map<String,Bitmap> modeSprites(long handle,Collection<String> requested,String keyedImage,
                                         Map<String,Bitmap> cached,boolean opaque){
        Map<String,Bitmap> loaded=new LinkedHashMap<>();
        long remaining=8L*1024*1024;
        String keyed=systemImageKey(keyedImage);
        for(String name:requested){
            String file=systemImageKey(name);
            if(file.isEmpty()||(!opaque&&!file.equals(keyed)&&image(file)!=null)||loaded.containsKey(file))continue;
            Bitmap reused=cached.get(file);
            if(reused!=null){
                long pixels=(long)reused.getWidth()*reused.getHeight();
                if(pixels<=remaining){loaded.put(file,reused);remaining-=pixels;}
                continue;
            }
            byte[] path=file.getBytes(StandardCharsets.ISO_8859_1);
            int[] raw=file.equals(keyed)?NativeGame.keyedSprite(handle,path):NativeGame.gallerySprite(handle,path);
            if(raw==null||raw.length<2)continue;
            int width=raw[0],height=raw[1];
            long pixels=(long)width*height;
            if(width<1||height<1||width>4096||height>4096||pixels>MAX_IMAGE_PIXELS||
                pixels!=raw.length-2L||pixels>remaining)continue;
            if(opaque)for(int i=2;i<raw.length;i++)raw[i]|=0xff000000;
            loaded.put(file,Bitmap.createBitmap(raw,2,width,width,height,Bitmap.Config.ARGB_8888));
            remaining-=pixels;
        }
        return loaded;
    }
    PointerIcon pointer(int variant){
        if(!gameMouse.has("fileNameMouse"))return null;
        int configs=DialogueLayout.clamp(gameMouse.number("mouseNumberConfig",1),1,64);
        int scripts=DialogueLayout.clamp(gameMouse.number("mouseNumberScript",1),1,64);
        int index=DialogueLayout.clamp(variant,0,configs-1)+1;
        String name=gameMouse.value("fileNameMouse","");
        if(configs>1||scripts>1)name=gameMouse.value("fileNameMouse"+(configs>1&&scripts>1?"1_":"")+index,name);
        Bitmap sheet=image(name);
        if(sheet==null)return null;
        int w=DialogueLayout.clamp(gameMouse.number("sizeX",32),1,Math.min(512,sheet.getWidth()));
        int h=DialogueLayout.clamp(gameMouse.number("sizeY",32),1,Math.min(512,sheet.getHeight()));
        return PointerIcon.create(Bitmap.createBitmap(sheet,0,0,w,h),
            DialogueLayout.clamp(gameMouse.number("hotPointX",0),0,w-1),DialogueLayout.clamp(gameMouse.number("hotPointY",0),0,h-1));
    }
    int settingsTextRate(int speed,boolean automatic){
        return textRates[(automatic?5:0)+Math.max(0,Math.min(4,speed))];
    }

    static Config readConfig(File root,String name) throws IOException {
        return readConfig(root,name,GameTextDecoder.load(root));
    }
    static Config readConfig(File root,String name,GameTextDecoder decoder) throws IOException {
        if (!name.matches("[A-Za-z0-9_]+")) throw new IOException("Invalid menu configuration name");
        File file=GameResourcePaths.file(root,"init2",name+".fxf");
        boolean encrypted=file!=null;
        if (!encrypted) file=GameResourcePaths.file(root,"init",name+".xtx");
        Map<String,String> values=new HashMap<>();
        if (file==null) return new Config(values,decoder);
        if (file.length()>MAX_CONFIG_BYTES) throw new IOException("Menu configuration too large: "+name);
        ByteArrayOutputStream content=new ByteArrayOutputStream();
        try (InputStream input=new FileInputStream(file)) {
            byte[] block=new byte[4096];
            for (int count;(count=input.read(block))!=-1;) {
                if (content.size()>MAX_CONFIG_BYTES-count) throw new IOException("Menu configuration too large: "+name);
                content.write(block,0,count);
            }
        }
        byte[] raw=content.toByteArray();
        if (encrypted) for (int i=0;i<raw.length;++i) raw[i]^=(byte)0xff;
        try (BufferedReader reader=new BufferedReader(new StringReader(new String(raw,StandardCharsets.ISO_8859_1)))) {
            for (String line;(line=reader.readLine())!=null;) {
                // readLine removes CR/LF only. Other low bytes can be the
                // second byte of a legal sjis_ext tunnel pair, including at EOF.
                line=trimAscii(line);
                int split=0;
                while(split<line.length()&&!spacing(line.charAt(split)))split++;
                if(split==line.length())continue;
                String field=line.substring(0,split);
                while(split<line.length()&&spacing(line.charAt(split)))split++;
                if(field.matches("[A-Za-z0-9_]+"))values.putIfAbsent(key(field),line.substring(split));
            }
        }
        return new Config(values,decoder);
    }

    private static void request(Set<String> names,Config config,String... fields) {
        for (String field:fields) {
            String name=config.value(field,"");
            if (!name.isEmpty()) names.add(systemImageKey(name));
        }
    }
    private static void common(Set<String> names,Config config) {
        request(names,config,"BackFileName","UpFileName","DownFileName","fileNamePageFont",
            "filenameDialog","fileNameDialog2","dialogOkFileName","dialogCancelFileName",
            "dialogOk2FileName","dialogCancel2FileName");
    }

    static GameUiAssets load(File root,long handle) throws IOException {
        Config system=readConfig(root,"systemmenu"),config=readConfig(root,"config");
        Config save=readConfig(root,"save"),load=readConfig(root,"load"),backlog=readConfig(root,"backlog");
        Config messageWindow=readConfig(root,"messageWindow");
        Config game=readConfig(root,"game"),printMessage=readConfig(root,"printmessage");
        Config messageCursor=readConfig(root,"messageCursor"),selection=readConfig(root,"selectmessage");
        Config gameMouse=readConfig(root,"gameMouse");
        Config selectPlace=readConfig(root,"selectPlace");
        Config selectPlace2=readConfig(root,"selectPlace2");
        Map<String,Config> storyButtons=new LinkedHashMap<>();
        for(String name:GameStoryControlsModel.CONFIG_NAMES)storyButtons.put(name,readConfig(root,name));
        DialogueLayout.Counts windowCounts=DialogueLayout.counts(messageWindow.fields());
        Set<String> messageImages=new LinkedHashSet<>();
        for(String name:DialogueLayout.names(messageWindow.fields(),"filenameWindow","ta_meswin",windowCounts))
            messageImages.add(systemImageKey(name));
        if(messageWindow.number("nameWindowFlag",0)!=0)
            for(String name:DialogueLayout.names(messageWindow.fields(),"filenameNameWindow","ta_namewin",windowCounts))
                messageImages.add(systemImageKey(name));
        String longPrintImage=longPrintImageKey(printMessage);
        if(!longPrintImage.isEmpty())messageImages.add(longPrintImage);
        Set<String> names=new LinkedHashSet<>(messageImages);
        for(String name:new String[]{
            "ta_menu_bg01","ta_cnf_bg01","ta_cnf_bg02","ta_cnf_bg03",
            "ta_savebg01","ta_save_slot01","ta_loadbg01","ta_load_slot01","ta_backlogbg",
            "ta_back01","ta_up_bt01","ta_down_bt01","ta_bar01","ta_bar03",
            "ta_dialog_save","ta_dialog_quit","ta_dialog_bt01","ta_dialog_bt02"})names.add(systemImageKey(name));
        names.addAll(config.imageNames());
        names.addAll(messageCursor.imageNames());
        names.addAll(gameMouse.imageNames());
        if(game.number("selectPlaceIsDefault",0)!=0)names.addAll(selectPlace.imageNames());
        if(game.number("selectPlace2IsDefault",0)!=0&&game.number("selectPlace2ClassDisable",0)==0)names.addAll(selectPlace2.imageNames());
        names.addAll(selection.imageNames());
        for(Config button:storyButtons.values())names.addAll(button.imageNames());
        for (int i=1;i<=Math.max(9,GameSystemMenuPanel.optionCount(system));++i)
            request(names,system,"Menu1Button"+i+"FileName");
        request(names,system,"backScreenFileName","filenameMenuBase");
        common(names,system); common(names,config); common(names,save); common(names,load);
        names.addAll(save.imageNames(field->field.matches("tab[0-9]+button[12]filename")));
        names.addAll(load.imageNames(field->field.matches("tab[0-9]+button[12]filename")));
        for (int i=1;i<=2;++i)
            request(names,config,"tab"+i+"Button1FileName","tab"+i+"Button2FileName");
        for (String kind:new String[]{"music","sound","voice","soundvoice","total"})
            request(names,config,kind+"Button1FileName",kind+"Button2FileName",kind+"BarFileName");
        for (String kind:new String[]{"skip1","skip2","auto1","auto2"})
            request(names,config,kind+"Button1FileName",kind+"Button2FileName");
        request(names,config,"messageSpeedBarFileName","autoSpeedBarFileName");
        request(names,backlog,"backlogBGFileName","titlePicFileName","upArrowFileName","downArrowFileName","filenameVoice","filenameJump");
        Config data=readConfig(root,"dataFile"),notice=readConfig(root,"notice");
        request(names,save,"filenameBG");request(names,load,"filenameBG");
        request(names,data,"filenameSaveCursor","filenameLoadCursor","fileNameSaveTimeFont",
            "fileNameSerialNumberFont","filenameSaveBase","filenameLoadBase","filenameQuickSaveBase","filenameQuickLoadBase","filenameLastSave",
            "fileNameError","fileNameBad","fileNameOld");
        if(data.number("addPicPrintFlag",0)!=0)names.add(systemImageKey(data.value("fileNameAddPic","ta_sl_font")));
        if(game.number("noticeFlag",0)!=0){
            for(int number:new int[]{game.number("loadNoticeNumber",1),game.number("saveNoticeNumber",2)})
                if(number>0&&number<=Math.min(64,notice.number("noticeNumber",1)))names.add(systemImageKey(GameNotice.filename(notice,number)));
        }

        Map<String,Bitmap> images=new LinkedHashMap<>();
        long total=0;
        StringBuilder inventory=new StringBuilder();
        for (String name:names) {
            int[] raw;
            try{raw=NativeGame.titleSprite(handle,name.getBytes(StandardCharsets.ISO_8859_1));}
            catch(RuntimeException e){Log.w("SystemNNN","UI sprite unavailable: "+name,e);continue;}
            catch(OutOfMemoryError e){Log.w("SystemNNN","UI bitmap memory exhausted; remaining sprites omitted");break;}
            if (raw==null || raw.length<2) {
                if(messageImages.contains(name))Log.w("SystemNNN","Message asset unavailable: "+name);
                continue;
            }
            int w=raw[0],h=raw[1];
            long pixels=(long)w*h;
            if (w<=0 || h<=0 || w>4096 || h>4096 || pixels>MAX_IMAGE_PIXELS ||
                pixels!=raw.length-2L || pixels>MAX_TOTAL_PIXELS-total) {
                Log.w("SystemNNN","UI sprite exceeds bitmap bounds: "+name);
                continue;
            }
            try {
                Bitmap bitmap=Bitmap.createBitmap(raw,2,w,w,h,Bitmap.Config.ARGB_8888);
                images.put(name,bitmap); total+=pixels;
                inventory.append(name).append('=').append(w).append('x').append(h).append(' ');
            } catch (IllegalArgumentException e) {
                Log.w("SystemNNN","Invalid UI sprite: "+name,e);
            } catch (OutOfMemoryError e) {
                Log.w("SystemNNN","UI bitmap memory exhausted; remaining sprites omitted");
                break;
            }
        }
        Log.i("SystemNNN","UI sprites loaded "+images.size()+", pixels="+total+": "+inventory);
        boolean freeStatus=false;
        for(String name:new String[]{"printStatus","printParty"}){
            try{freeStatus|=readConfig(root,name).number("freePrintFlag",0)!=0;}
            catch(IOException e){Log.w("SystemNNN","Optional status configuration unavailable: "+name,e);}
        }
        return new GameUiAssets(root,system,config,save,load,backlog,messageWindow,game,printMessage,data,notice,messageCursor,selection,gameMouse,selectPlace,selectPlace2,
            storyButtons,images,NativeGame.settingsTextRates(handle),freeStatus,NativeGame.legacyVolumeDefaults(handle));
    }
}
