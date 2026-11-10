package com.tyranor.runtime.systemnnn;

import android.graphics.Bitmap;
import android.content.Context;
import android.util.AtomicFile;
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
    private static final int LEGACY_MANUAL_COUNT=20;
    private static final int LAST_MAGIC=0x4c535431; // LST1
    private static final int SLOT_UI_MAGIC=0x534c5531; // SLU1
    private static final int MAX_PICTURE_SIZE=512;
    private static final int MAX_CACHED_PICTURE_PIXELS=6*1024*1024;
    private final GameSavePaths paths;
    private volatile Map<Integer,Entry> cached=Collections.emptyMap();
    private volatile int manualCount=LEGACY_MANUAL_COUNT;
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
        final int columns,rows,pages,pageMode,perPage,manualCount,quickPosition;
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
            manualCount=count-(quickPosition>=0?1:0);
        }
        int slotAt(int position){
            if(position==quickPosition)return QUICK;
            return quickPosition>=0&&position>quickPosition?position-1:position;
        }
        int positionOf(int slot){
            if(slot==QUICK)return quickPosition;
            if(slot<0||slot>=manualCount)return -1;
            return quickPosition>=0&&slot>=quickPosition?slot+1:slot;
        }
        int openingPage(Integer slot){
            if(slot==null||pageMode==0)return 0;
            if(slot>=manualCount)return pages-1;
            return Math.max(0,positionOf(slot)/perPage);
        }
        boolean shows(int slot){
            int position=positionOf(slot);
            return position>=0&&(pageMode!=0||position<perPage);
        }
    }
    static final class Entry {
        final int slot,line;
        final boolean exists,corrupt,legacy;
        final long savedAt;
        final String script,speaker,summary;
        final Bitmap picture;
        final Integer artwork;
        final boolean lastSaved;
        Entry(int slot,boolean exists,boolean corrupt,boolean legacy,long savedAt,String script,int line,String speaker,String summary,Bitmap picture) {
            this(slot,exists,corrupt,legacy,savedAt,script,line,speaker,summary,picture,null,false);
        }
        private Entry(int slot,boolean exists,boolean corrupt,boolean legacy,long savedAt,String script,int line,String speaker,String summary,
                      Bitmap picture,Integer artwork,boolean lastSaved) {
            this.slot=slot; this.exists=exists; this.corrupt=corrupt; this.legacy=legacy; this.savedAt=savedAt;
            this.script=script; this.line=line; this.speaker=speaker; this.summary=summary;
            this.picture=picture;
            this.artwork=artwork;this.lastSaved=lastSaved;
        }
        private Entry withoutPicture(){
            return new Entry(slot,exists,corrupt,legacy,savedAt,script,line,speaker,summary,null,artwork,lastSaved);
        }
        private Entry withUi(SlotUi state){
            if(!exists||corrupt)return this;
            Artwork art=state.artwork.get(slot);
            Integer number=art!=null&&art.savedAt==savedAt?art.number:null;
            boolean marked=slot!=AUTO&&state.lastSaved==slot&&state.lastSavedAt==savedAt;
            if(number==null&&!marked)return this;
            return new Entry(slot,exists,corrupt,legacy,savedAt,script,line,speaker,summary,picture,number,marked);
        }
        String label(Context context) { return labelFor(context,slot); }
        String displaySummary(Context context) {
            if(corrupt)return EngineUiText.get(context,R.string.engine_systemnnn_ctl_slot_unreadable);
            return legacy?EngineUiText.get(context,R.string.engine_systemnnn_ctl_slot_legacy):summary;
        }
    }
    SaveSlots(GameSavePaths paths) {
        this.paths=paths;
    }
    static int configuredManualCount(File root,GameTextDecoder decoder) throws IOException {
        GameUiAssets.Config game=GameUiAssets.readConfig(root,"game",decoder);
        GameUiAssets.Config data=GameUiAssets.readConfig(root,"dataFile",decoder);
        Layout save=new Layout(GameUiAssets.readConfig(root,"save",decoder),game,data,true);
        Layout load=new Layout(GameUiAssets.readConfig(root,"load",decoder),game,data,false);
        return Math.max(save.manualCount,load.manualCount);
    }
    File file(int slot){return paths.file(suffix(slot));}
    static String suffix(int slot) {
        if (!valid(slot)) throw new IllegalArgumentException("无效的存档编号");
        if (slot==AUTO) return ".resume";
        if (slot==QUICK) return ".quick.sav";
        if (slot==0) return ".sav";
        return ".slot"+(slot<10?"0":"")+slot+".sav";
    }
    void preload(int manualCount) throws IOException {
        Set<Integer> found=new TreeSet<>();
        try(DirectoryStream<Path> files=Files.newDirectoryStream(paths.directory().toPath(),GameSavePaths.PREFIX+"*")){
            for(Path file:files){
                String name=file.getFileName().toString();
                Integer slot=committedSlot(name.substring(GameSavePaths.PREFIX.length()));
                if(slot!=null)found.add(slot);
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
        List<Entry> entries=new ArrayList<>(manualCount+2);
        for(int slot=0;slot<manualCount;slot++)entries.add(entry(snapshot,slot).withUi(decoration));
        for(Entry saved:snapshot.values())if(saved.slot>=manualCount)entries.add(saved.withUi(decoration));
        entries.add(entry(snapshot,AUTO));entries.add(entry(snapshot,QUICK).withUi(decoration));
        return entries.toArray(new Entry[0]);
    }
    Entry entry(int slot){return entry(cached,slot).withUi(slotUi);}
    private static Entry entry(Map<Integer,Entry> entries,int slot){
        Entry saved=entries.get(slot);
        return saved==null?empty(slot):saved;
    }
    void saved(GameSave.Metadata metadata,Bitmap picture){
        Map<Integer,Entry> next=new TreeMap<>(cached);
        next.remove(metadata.slot);
        long available=MAX_CACHED_PICTURE_PIXELS;
        for(Entry entry:next.values())available-=picturePixels(entry.picture);
        long pixels=picturePixels(picture);
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
        next.put(metadata.slot,entry(metadata.slot,metadata,pixels<=available?picture:null));
        cached=next;
    }
    private static long picturePixels(Bitmap picture){
        return picture==null?0:(long)picture.getWidth()*picture.getHeight();
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
    void recordWritten(GameSave.Metadata saved,Integer art,int marker) throws IOException {
        if(saved.slot==AUTO)return;
        SlotUi previous=slotUi;
        int last=previous.lastSaved;
        if(marker!=0)last=marker==-1?AUTO:saved.slot;
        long time=last==AUTO?0:last==saved.slot?saved.savedAt:previous.lastSavedAt;
        Map<Integer,Artwork> images=new TreeMap<>(previous.artwork);
        if(art==null)images.remove(saved.slot);
        else images.put(saved.slot,new Artwork(saved.savedAt,art));
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
            if(in.readInt()!=SLOT_UI_MAGIC)return SlotUi.EMPTY;
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
    private static Integer readLastSelection(File file){
        try(DataInputStream in=new DataInputStream(Files.newInputStream(file.toPath(),LinkOption.NOFOLLOW_LINKS))){
            if(in.readInt()!=LAST_MAGIC)return null;
            int slot=in.readInt();
            return slot!=AUTO&&valid(slot)&&in.read()==-1?slot:null;
        }catch(IOException|RuntimeException ignored){return null;}
    }
    private static Entry empty(int slot){return new Entry(slot,false,false,false,0,"",0,"","",null);}
    private static Entry entry(int slot,GameSave.Metadata m,Bitmap picture){
        return new Entry(slot,true,false,m.legacy,m.savedAt,m.script,m.line,m.speaker,m.summary,picture);
    }
    private Entry readEntry(int slot,int remainingPixels) {
        File file=file(slot);
        if (!exists(file)) return empty(slot);
        try {
            GameSave.Metadata m=GameSave.readMetadata(file);
            if (!m.legacy && m.slot!=slot) throw new IOException("存档编号不匹配");
            return entry(slot,m,m.legacy||remainingPixels<=0?null:readPicture(slot,m.savedAt,remainingPixels));
        } catch (IOException | RuntimeException e) {
            return new Entry(slot,true,true,false,Math.max(0,file.lastModified()),"",0,"","",null);
        }
    }
    private File pictureFile(int slot){return paths.file(suffix(slot)+GameSavePaths.THUMB);}
    private static boolean pictureSize(int w,int h){return w>0&&h>0&&w<=MAX_PICTURE_SIZE&&h<=MAX_PICTURE_SIZE;}
    private Bitmap readPicture(int slot,long savedAt,int remainingPixels) {
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(new AtomicFile(pictureFile(slot)).openRead()))){
            if(in.readLong()!=savedAt)return null;
            int w=in.readUnsignedShort(),h=in.readUnsignedShort();
            if(!pictureSize(w,h)||w*h>remainingPixels)return null;
            byte[] bytes=new byte[w*h*Integer.BYTES];
            in.readFully(bytes);
            if(in.read()!=-1)return null;
            int[] pixels=new int[w*h];
            ByteBuffer.wrap(bytes).asIntBuffer().get(pixels);
            return Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888);
        }catch(IOException|RuntimeException e){return null;}
    }
    Bitmap writePicture(int slot,long savedAt,int w,int h,int[] pixels) throws IOException {
        if(!pictureSize(w,h)||pixels==null||pixels.length!=w*h)throw new IOException("无效的存档截图");
        ByteBuffer bytes=ByteBuffer.allocate(pixels.length*Integer.BYTES);
        bytes.asIntBuffer().put(pixels);
        AtomicFile atomic=new AtomicFile(pictureFile(slot));FileOutputStream raw=atomic.startWrite();
        try{
            DataOutputStream out=new DataOutputStream(new BufferedOutputStream(raw));
            out.writeLong(savedAt);out.writeShort(w);out.writeShort(h);
            out.write(bytes.array());
            out.flush();atomic.finishWrite(raw);
        }catch(IOException|RuntimeException e){atomic.failWrite(raw);throw e;}
        return Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888);
    }
    static boolean valid(int slot) { return slot==AUTO || slot==QUICK || slot>=0 && slot<MAX_MANUAL_SLOTS; }
    static boolean exists(File file) { return file.isFile() || new File(file.getPath()+".bak").isFile(); }
    static Integer committedSlot(String suffix){
        if(suffix.endsWith(".bak"))suffix=suffix.substring(0,suffix.length()-4);
        return slotForSuffix(suffix);
    }
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
    private static String labelFor(Context context,int slot) {
        Context localized=EngineUiText.localizedContext(context);
        if (slot==AUTO) return localized.getString(R.string.engine_systemnnn_ctl_slot_auto);
        if (slot==QUICK) return localized.getString(R.string.engine_systemnnn_ctl_slot_quick);
        return localized.getString(R.string.engine_systemnnn_ctl_slot_number,(slot<9?"0":"")+(slot+1));
    }
}
