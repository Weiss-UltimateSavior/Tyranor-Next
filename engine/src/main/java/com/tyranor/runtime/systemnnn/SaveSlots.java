package com.tyranor.runtime.systemnnn;

import android.graphics.Bitmap;
import android.content.Context;
import com.core.engine.EngineUiText;
import com.core.engine.R;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

final class SaveSlots {
    static final int AUTO=-1, QUICK=-2, MAX_MANUAL_SLOTS=4096;
    static final String LAST_SELECTION=".last-slot";
    static final String SLOT_UI=".slot-ui";
    private static final int DEFAULT_SLOT_COUNT=20;
    private static final int LAST_MAGIC=0x4c535432; // LST2, physical PC slot numbers
    private static final int SLOT_UI_MAGIC=0x534c5532; // SLU2
    private static final int MAX_CACHED_PICTURE_PIXELS=6*1024*1024;
    private final GameSavePaths paths;
    private PcStorySave pc;
    private GameTextDecoder decoder;
    private PcStorySave.GameDateVars gameDateVars=PcStorySave.GameDateVars.NONE;
    private int quickNumber=-1;
    private volatile Map<Integer,Entry> cached=Collections.emptyMap();
    private volatile int manualCount=DEFAULT_SLOT_COUNT;
    private volatile int loadCount;
    private volatile Integer lastSelected;
    private volatile SlotUi slotUi=SlotUi.EMPTY;

    private static final class Artwork {
        final long savedAt;
        final int number;
        Artwork(long savedAt,int number){this.savedAt=savedAt;this.number=number;}
    }
    private static final class SlotUi {
        static final SlotUi EMPTY=new SlotUi(AUTO,0,Collections.emptyMap());
        final int lastSaved;
        final long lastSavedAt;
        final Map<Integer,Artwork> artwork;
        SlotUi(int lastSaved,long lastSavedAt,Map<Integer,Artwork> artwork){
            this.lastSaved=lastSaved;this.lastSavedAt=lastSavedAt;this.artwork=artwork;
        }
    }

    static final class Layout {
        final int columns,rows,pages,pageMode,perPage,quickPosition;
        Layout(GameUiAssets.Config config,GameUiAssets.Config game,GameUiAssets.Config data,boolean saving){
            this(config.number("blockNumberX",4),config.number("blockNumberY",3),config.number("page",10),
                config.number("pageMode",-1),game.number(saving?"quickSaveEnable":"quickLoadEnable",
                    game.number("quickSaveEnable",0))!=0,data.number("quickSlotNumber",1));
        }
        Layout(int columns,int rows,int pages,int pageMode,boolean quick,int quickNumber){
            this.columns=Math.max(1,Math.min(16,columns));this.rows=Math.max(1,Math.min(16,rows));
            perPage=this.columns*this.rows;
            // The PC reserves atlas entries 0–6 before the 200-entry tab pool.
            int pageLimit=pageMode==2?Math.min(193,MAX_MANUAL_SLOTS/perPage):MAX_MANUAL_SLOTS/perPage;
            this.pages=Math.max(1,Math.min(pageLimit,pages));
            this.pageMode=pageMode<0?(this.pages>1?1:0):Math.min(2,pageMode);
            int count=perPage*this.pages;
            quickPosition=quick&&quickNumber>0&&quickNumber<=count?quickNumber-1:-1;
        }
        int openingPcPage(Integer slot){
            return slot==null||pageMode==0?0:Math.max(0,Math.min(pages-1,slot/perPage));
        }
    }
    static final class Entry {
        final int slot;
        final boolean exists,corrupt;
        final long savedAt;
        final String summary;
        final Bitmap picture;
        final Integer artwork;
        final boolean lastSaved;
        final PcStorySave.GameDate gameDate;
        Entry(int slot,boolean exists,boolean corrupt,long savedAt,String summary,Bitmap picture) {
            this(slot,exists,corrupt,savedAt,summary,picture,null,false,null);
        }
        private Entry(int slot,boolean exists,boolean corrupt,long savedAt,String summary,
                      Bitmap picture,Integer artwork,boolean lastSaved,PcStorySave.GameDate gameDate) {
            this.slot=slot; this.exists=exists; this.corrupt=corrupt; this.savedAt=savedAt;
            this.summary=summary;
            this.picture=picture;
            this.artwork=artwork;this.lastSaved=lastSaved;
            this.gameDate=gameDate;
        }
        private Entry withoutPicture(){
            return new Entry(slot,exists,corrupt,savedAt,summary,null,artwork,lastSaved,gameDate);
        }
        private Entry withUi(SlotUi state){
            if(!exists||corrupt)return this;
            Artwork art=state.artwork.get(slot);
            Integer number=artwork!=null?artwork:art!=null&&art.savedAt==savedAt?art.number:null;
            boolean marked=slot!=AUTO&&state.lastSaved==slot&&state.lastSavedAt==savedAt;
            if(number==null&&!marked)return this;
            return new Entry(slot,exists,corrupt,savedAt,summary,picture,number,marked,gameDate);
        }
        String label(Context context) { return labelFor(context,slot); }
        String displaySummary(Context context) {
            if(corrupt)return EngineUiText.get(context,R.string.engine_systemnnn_ctl_slot_unreadable);
            return summary;
        }
    }
    SaveSlots(GameSavePaths paths) {
        this.paths=paths;
    }
    int quickNumber(){return quickNumber;}
    PcStorySave.GameDateVars gameDateVars(){return gameDateVars;}
    File pcFile(int slot){
        if(slot<0||slot>=MAX_MANUAL_SLOTS)throw new IllegalArgumentException("Invalid PC save slot");
        return pc.file(slot);
    }
    static String suffix(int slot) {
        if (!valid(slot)) throw new IllegalArgumentException("无效的存档编号");
        if (slot==AUTO) return ".resume";
        if (slot==QUICK) return ".quick.sav";
        if (slot==0) return ".sav";
        return ".slot"+(slot<10?"0":"")+slot+".sav";
    }
    void preload(File root,GameTextDecoder decoder) throws IOException {
        preload(root,decoder,null);
    }
    void preload(File root,GameTextDecoder decoder,GameUiAssets.SharedConfig shared) throws IOException {
        this.decoder=decoder;
        GameUiAssets.Config game=GameUiAssets.readConfig(root,"game",decoder);
        GameUiAssets.Config data=GameUiAssets.readConfig(root,"dataFile",decoder);
        Layout save=new Layout(GameUiAssets.readConfig(root,"save",decoder),game,data,true);
        Layout load=new Layout(GameUiAssets.readConfig(root,"load",decoder),game,data,false);
        boolean fromNative=shared!=null&&shared.fromNative;
        pc=new PcStorySave(paths.directory(),fromNative?shared.trial():game.number("taikenflag",0)!=0);
        boolean showGameDate=data.number("gameYearPrintFlag",0)!=0||data.number("gameDatePrintFlag",0)!=0;
        gameDateVars=!showGameDate?PcStorySave.GameDateVars.NONE:
            (fromNative?shared.gameDateVars():PcStorySave.GameDateVars.load(root,game));
        int quick=data.number("quickSlotNumber",1);
        quickNumber=quick>0&&quick<=MAX_MANUAL_SLOTS?quick-1:-1;
        loadCount=load.pages*load.perPage;
        preload(Math.max(save.pages*save.perPage,load.pages*load.perPage));
    }
    private void preload(int manualCount) throws IOException {
        Set<Integer> found=new TreeSet<>();
        try(DirectoryStream<Path> files=Files.newDirectoryStream(paths.directory().toPath())){
            for(Path file:files){
                int number=pc.number(file.getFileName().toString());
                if(number>=0)found.add(number);
            }
        }
        Map<Integer,Entry> entries=new TreeMap<>();
        int remainingPixels=MAX_CACHED_PICTURE_PIXELS;
        for(int slot:found){
            Entry entry=readEntry(slot,remainingPixels);
            if(entry.exists)entries.put(slot,entry);
            remainingPixels-=(int)picturePixels(entry.picture);
        }
        cached=entries;
        this.manualCount=manualCount;
        lastSelected=readLastSelection();
        slotUi=readSlotUi();
    }
    Entry[] entries(){
        Map<Integer,Entry> snapshot=cached;
        SlotUi decoration=slotUi;
        List<Entry> entries=new ArrayList<>(manualCount);
        for(int slot=0;slot<manualCount;slot++)entries.add(entry(snapshot,slot).withUi(decoration));
        for(Entry saved:snapshot.values())if(saved.slot>=manualCount)entries.add(saved.withUi(decoration));
        return entries.toArray(new Entry[0]);
    }
    Entry entry(int slot){return entry(cached,slot).withUi(slotUi);}
    private static Entry entry(Map<Integer,Entry> entries,int slot){
        Entry saved=entries.get(slot);
        return saved==null?empty(slot):saved;
    }
    void savedPc(int slot,PcStorySave.Preview preview){cache(pcEntry(slot,preview));}
    private void cache(Entry saved){
        Map<Integer,Entry> next=new TreeMap<>(cached);
        next.remove(saved.slot);
        long available=MAX_CACHED_PICTURE_PIXELS;
        for(Entry entry:next.values())available-=picturePixels(entry.picture);
        long pixels=picturePixels(saved.picture);
        if(pixels>available && pixels<=MAX_CACHED_PICTURE_PIXELS){
            List<Entry> oldPictures=new ArrayList<>();
            for(Entry entry:next.values())if(entry.picture!=null)oldPictures.add(entry);
            oldPictures.sort(Comparator.comparingLong(entry->entry.savedAt));
            for(Entry old:oldPictures){
                next.put(old.slot,old.withoutPicture());
                available+=picturePixels(old.picture);
                if(available>=pixels)break;
            }
        }
        next.put(saved.slot,pixels<=available?saved:saved.withoutPicture());
        cached=next;
    }
    private static long picturePixels(Bitmap picture){
        return picture==null?0:(long)picture.getWidth()*picture.getHeight();
    }
    boolean hasAnyLoadData(){
        // CCommonLoad checks existence in its own grid, without checking the save version.
        for(Entry entry:cached.values())if(entry.slot>=0&&entry.slot<loadCount&&entry.exists)return true;
        return false;
    }
    Integer lastSelectedSlot(){
        Integer slot=lastSelected;
        if(slot==null)return null;
        Entry selected=entry(slot);
        return selected.exists&&!selected.corrupt?slot:null;
    }
    void remember(int slot) throws IOException {
        if(slot==AUTO||!valid(slot))throw new IllegalArgumentException("Invalid last save slot");
        lastSelected=slot;
        ByteBuffer bytes=ByteBuffer.allocate(8);
        bytes.putInt(LAST_MAGIC).putInt(slot);
        writeIndex(LAST_SELECTION,bytes);
    }
    void recordWritten(int slot,long savedAt,Integer art,int marker) throws IOException {
        if(slot==AUTO)return;
        SlotUi previous=slotUi;
        int last=previous.lastSaved;
        if(marker!=0)last=marker==-1?AUTO:slot;
        long time=last==AUTO?0:last==slot?savedAt:previous.lastSavedAt;
        Map<Integer,Artwork> images=new TreeMap<>(previous.artwork);
        if(art==null)images.remove(slot);
        else images.put(slot,new Artwork(savedAt,art));
        slotUi=new SlotUi(last,time,images);
        ByteBuffer bytes=ByteBuffer.allocate(20+images.size()*16);
        bytes.putInt(SLOT_UI_MAGIC).putInt(last).putLong(time).putInt(images.size());
        for(Map.Entry<Integer,Artwork> entry:images.entrySet()){
            Artwork image=entry.getValue();
            bytes.putInt(entry.getKey()).putLong(image.savedAt).putInt(image.number);
        }
        writeIndex(SLOT_UI,bytes);
    }
    private SlotUi readSlotUi(){
        File file=paths.file(SLOT_UI);
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(Files.newInputStream(file.toPath(),LinkOption.NOFOLLOW_LINKS)))){
            int magic=in.readInt();
            if(magic!=SLOT_UI_MAGIC)return SlotUi.EMPTY;
            int last=in.readInt();
            long time=in.readLong();
            int count=in.readInt();
            if(!valid(last)||time<0||count<0||count>MAX_MANUAL_SLOTS+1)return SlotUi.EMPTY;
            Map<Integer,Artwork> images=new TreeMap<>();
            for(int i=0;i<count;i++){
                int slot=in.readInt();
                long savedAt=in.readLong();
                int number=in.readInt();
                if(slot==AUTO||!valid(slot)||savedAt<0||images.putIfAbsent(slot,new Artwork(savedAt,number))!=null)
                    return SlotUi.EMPTY;
            }
            return in.read()==-1?new SlotUi(last,time,images):SlotUi.EMPTY;
        }catch(IOException|RuntimeException ignored){return SlotUi.EMPTY;}
    }
    private void writeIndex(String suffix,ByteBuffer bytes) throws IOException {
        Path file=paths.file(suffix).toPath();
        Path pending=paths.file(suffix+".new").toPath();
        bytes.flip();
        try {
            try(FileChannel out=FileChannel.open(pending,StandardOpenOption.WRITE,StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,LinkOption.NOFOLLOW_LINKS)){
                while(bytes.hasRemaining())out.write(bytes);
                out.force(true);
            }
            Files.move(pending,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(pending);}
    }
    private Integer readLastSelection(){
        Integer selected=readLastSelection(paths.file(LAST_SELECTION));
        return selected!=null?selected:readLastSelection(paths.file(LAST_SELECTION+".bak"));
    }
    private Integer readLastSelection(File file){
        try(DataInputStream in=new DataInputStream(Files.newInputStream(file.toPath(),LinkOption.NOFOLLOW_LINKS))){
            int magic=in.readInt();
            if(magic!=LAST_MAGIC)return null;
            int slot=in.readInt();
            return slot!=AUTO&&valid(slot)&&in.read()==-1?slot:null;
        }catch(IOException|RuntimeException ignored){return null;}
    }
    private static Entry empty(int slot){return new Entry(slot,false,false,0,"",null);}
    private static Entry pcEntry(int slot,PcStorySave.Preview preview){
        return new Entry(slot,true,false,preview.savedAt,preview.summary,preview.picture,preview.artwork,false,preview.gameDate);
    }
    private Entry readEntry(int slot,int remainingPixels) {
        File file=pcFile(slot);
        boolean present=exists(file);
        File backup=present?null:PcStorySave.backupWithoutBase(file);
        if(!present&&backup==null)return empty(slot);
        File timestamp=backup==null?file:backup;
        try{
            byte[] bytes=PcStorySave.read(file);
            return pcEntry(slot,PcStorySave.preview(bytes,timestamp.lastModified(),decoder,remainingPixels,gameDateVars));
        }catch(IOException|RuntimeException e){
            return new Entry(slot,true,true,Math.max(0,timestamp.lastModified()),"",null);
        }
    }
    static boolean valid(int slot) { return slot==AUTO || slot==QUICK || slot>=0 && slot<MAX_MANUAL_SLOTS; }
    static boolean exists(File file) { return file.isFile() || new File(file.getPath()+".bak").isFile(); }
    static Integer slotForSuffix(String suffix){
        if(suffix.equals(".resume"))return AUTO;
        if(suffix.equals(".quick.sav"))return QUICK;
        if(suffix.equals(".sav"))return 0;
        if(!suffix.startsWith(".slot")||!suffix.endsWith(".sav")||suffix.length()<11||suffix.length()>13)return null;
        try{
            int slot=Integer.parseInt(suffix.substring(5,suffix.length()-4));
            if(slot>0&&valid(slot)&&suffix.equals(suffix(slot)))return slot;
        }catch(NumberFormatException ignored){}
        return null;
    }
    static int slotForFile(File file) {
        String name=file.getName();
        if (name.endsWith(".resume")) return AUTO;
        if (name.endsWith(".quick.sav")) return QUICK;
        int mark=name.lastIndexOf(".slot");
        if (mark>=0 && name.endsWith(".sav")) {
            Integer slot=slotForSuffix(name.substring(mark));
            if(slot!=null)return slot;
        }
        return 0;
    }
    static String labelFor(Context context,int slot) {
        Context localized=EngineUiText.localizedContext(context);
        if (slot==QUICK) return localized.getString(R.string.engine_systemnnn_ctl_slot_quick);
        return localized.getString(R.string.engine_systemnnn_ctl_slot_number,(slot<9?"0":"")+(slot+1));
    }
}
