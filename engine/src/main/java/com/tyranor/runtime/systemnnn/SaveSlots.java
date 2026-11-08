package com.tyranor.runtime.systemnnn;

import android.graphics.Bitmap;
import android.content.Context;
import android.util.AtomicFile;
import com.core.engine.EngineUiText;
import com.core.engine.R;
import java.io.*;
import java.nio.ByteBuffer;

final class SaveSlots {
    static final int MANUAL_COUNT=20, AUTO=-1, QUICK=-2, COUNT=MANUAL_COUNT+2;
    private static final int MAX_PICTURE_SIZE=512;
    private final GameSavePaths paths;
    private volatile Entry[] cached;
    static final class Entry {
        final int slot,line;
        final boolean exists,corrupt,legacy;
        final long savedAt;
        final String script,speaker,summary;
        final Bitmap picture;
        Entry(int slot,boolean exists,boolean corrupt,boolean legacy,long savedAt,String script,int line,String speaker,String summary,Bitmap picture) {
            this.slot=slot; this.exists=exists; this.corrupt=corrupt; this.legacy=legacy; this.savedAt=savedAt;
            this.script=script; this.line=line; this.speaker=speaker; this.summary=summary;
            this.picture=picture;
        }
        String label(Context context) { return labelFor(context,slot); }
        String displaySummary(Context context) {
            if(corrupt)return EngineUiText.get(context,R.string.engine_systemnnn_ctl_slot_unreadable);
            return legacy?EngineUiText.get(context,R.string.engine_systemnnn_ctl_slot_legacy):summary;
        }
    }
    SaveSlots(GameSavePaths paths) {
        this.paths=paths;
        Entry[] initial=new Entry[COUNT];
        for(int i=0;i<COUNT;i++)initial[i]=empty(slotAt(i));
        cached=initial;
    }
    File file(int slot){return paths.file(suffix(slot));}
    static String suffix(int slot) {
        if (!valid(slot)) throw new IllegalArgumentException("无效的存档编号");
        if (slot==AUTO) return ".resume";
        if (slot==QUICK) return ".quick.sav";
        if (slot==0) return ".sav";
        return ".slot"+(slot<10?"0":"")+slot+".sav";
    }
    void preload() {
        Entry[] entries=new Entry[COUNT];
        for(int i=0;i<COUNT;i++)entries[i]=readEntry(slotAt(i));
        cached=entries;
    }
    Entry[] entries(){return cached.clone();}
    Entry entry(int slot){return cached[index(slot)];}
    void saved(GameSave.Metadata metadata,Bitmap picture){
        Entry[] next=cached.clone();
        next[index(metadata.slot)]=entry(metadata.slot,metadata,picture);
        cached=next;
    }
    private static Entry empty(int slot){return new Entry(slot,false,false,false,0,"",0,"","",null);}
    private static Entry entry(int slot,GameSave.Metadata m,Bitmap picture){
        return new Entry(slot,true,false,m.legacy,m.savedAt,m.script,m.line,m.speaker,m.summary,picture);
    }
    private Entry readEntry(int slot) {
        File file=file(slot);
        if (!exists(file)) return empty(slot);
        try {
            GameSave.Metadata m=GameSave.readMetadata(file);
            if (!m.legacy && m.slot!=slot) throw new IOException("存档编号不匹配");
            return entry(slot,m,m.legacy?null:readPicture(slot,m.savedAt));
        } catch (IOException | RuntimeException e) {
            return new Entry(slot,true,true,false,Math.max(0,file.lastModified()),"",0,"","",null);
        }
    }
    private File pictureFile(int slot){return paths.file(suffix(slot)+GameSavePaths.THUMB);}
    private static boolean pictureSize(int w,int h){return w>0&&h>0&&w<=MAX_PICTURE_SIZE&&h<=MAX_PICTURE_SIZE;}
    private Bitmap readPicture(int slot,long savedAt) {
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(new AtomicFile(pictureFile(slot)).openRead()))){
            if(in.readLong()!=savedAt)return null;
            int w=in.readUnsignedShort(),h=in.readUnsignedShort();
            if(!pictureSize(w,h))return null;
            byte[] bytes=new byte[w*h*Integer.BYTES];
            in.readFully(bytes);
            if(in.read()!=-1)return null;
            int[] pixels=new int[w*h];
            ByteBuffer.wrap(bytes).asIntBuffer().get(pixels);
            return Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888);
        }catch(IOException e){return null;}
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
    private static int index(int slot){
        if(!valid(slot))throw new IllegalArgumentException("无效的存档编号");
        return slot>=0?slot:MANUAL_COUNT-slot-1;
    }
    private static int slotAt(int index){return index<MANUAL_COUNT?index:MANUAL_COUNT-index-1;}
    static boolean valid(int slot) { return slot==AUTO || slot==QUICK || slot>=0 && slot<MANUAL_COUNT; }
    static boolean exists(File file) { return file.isFile() || new File(file.getPath()+".bak").isFile(); }
    static int slotForFile(File file) {
        String name=file.getName();
        if (name.endsWith(".resume")) return AUTO;
        if (name.endsWith(".quick.sav")) return QUICK;
        int mark=name.lastIndexOf(".slot");
        if (mark>=0 && name.endsWith(".sav")) {
            try { int slot=Integer.parseInt(name.substring(mark+5,name.length()-4)); if (slot>=0 && slot<MANUAL_COUNT) return slot; }
            catch (NumberFormatException ignored) { }
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
