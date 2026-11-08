package com.tyranor.runtime.systemnnn;

import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

// Input replay and its display metadata commit together in one AtomicFile.
final class GameSave {
    static final int TIMED_OUT=-1;
    private static final int TRIM_BACKLOG=-2;
    private static final int RESET_SYSTEM_VARIABLES=-3, SET_SYSTEM_VARIABLE=-4;
    private static final int NATIVE_BASELINE=-5;
    private static final int RANDOM_STATE=-6, CLOCK_SAMPLE=-7, RESET_GALLERY=-8, GALLERY_BITS=-9;
    private static final int RESET_SCRIPT_PROGRESS=-10, SET_SCRIPT_PROGRESS=-11;
    private static final int MAX_GALLERY_KEYS=150000;
    static final int MAGIC_NS1=0x4e4e5331, MAGIC_NS2=0x4e4e5332;
    private static final int MAX_RUNS=200000, MAX_TICKS=24000000, MAX_CONTENT_BYTES=1024*1024;
    // A native checkpoint still validates its ID and cursor. This token can
    // never be mistaken for an offset into a complete input journal.
    private static final int SNAPSHOT_CURSOR=MAX_TICKS+1;
    private static final int LEGACY_RUN_BYTES=5*Integer.BYTES+1;
    private static final int SNAPSHOT_TAG=0x534e5031, MAX_SNAPSHOT=48*1024*1024;
    static final class Run {
        int repeats;
        final int ms,x,y,choice;
        final boolean advance,skip;
        Run(int repeats,int ms,int x,int y,boolean advance,int choice) {
            this(repeats,ms,x,y,advance,choice,false);
        }
        Run(int repeats,int ms,int x,int y,boolean advance,int choice,boolean skip) {
            this.repeats=repeats; this.ms=ms; this.x=x; this.y=y;
            this.advance=advance; this.choice=choice; this.skip=skip;
        }
    }
    static final class Metadata {
        final int slot,wait,word,line;
        final long savedAt;
        final String script,speaker,summary;
        final boolean legacy;
        Metadata(int slot,long savedAt,String script,int wait,int word,int line,String speaker,String summary,boolean legacy) {
            this.slot=slot; this.savedAt=savedAt; this.script=script; this.wait=wait;
            this.word=word; this.line=line; this.speaker=speaker; this.summary=summary; this.legacy=legacy;
        }
    }
    static final class Loaded {
        final GameSave save;
        final Metadata metadata;
        final byte[][] content;
        final byte[] nativeSnapshot;
        Loaded(GameSave save,Metadata metadata,byte[][] content) {
            this(save,metadata,content,null);
        }
        Loaded(GameSave save,Metadata metadata,byte[][] content,byte[] nativeSnapshot) {
            this.save=save; this.metadata=metadata; this.content=content;this.nativeSnapshot=nativeSnapshot;
        }
    }
    // Allows a JVM format/replay test without loading the native library.
    interface Replay {
        void setSkip(boolean skip);
        void tick(int ms,int x,int y,boolean advance,int choice,int[] meta);
        byte[][] content();
        default void trimBacklog(int keep) throws IOException { throw new IOException("无法恢复回看记录"); }
        default void systemVariables(int[] values) throws IOException { throw new IOException("无法恢复脚本系统变量"); }
        default void scriptProgress(int[] values) throws IOException { throw new IOException("无法恢复结果记录"); }
        default void gallery(int[] keys) throws IOException { throw new IOException("无法恢复鉴赏记录"); }
        default void randomState(int state) throws IOException { throw new IOException("无法恢复随机数状态"); }
        default void clock(int date,int milliseconds) throws IOException {
            if(date!=0)throw new IOException("无法恢复系统时间样本");
        }
    }
    final ArrayList<Run> runs=new ArrayList<>();
    int ticks;

    void append(int ms,int x,int y,boolean advance,int choice) { append(ms,x,y,advance,choice,false); }
    void append(int ms,int x,int y,boolean advance,int choice,boolean skip) {
        if (ms<0 || ms>1000 || choice<TIMED_OUT) throw new IllegalArgumentException("无效的游戏输入");
        if (requiresSnapshot()) { updateNativePointer(x,y); return; }
        Run last=runs.isEmpty()?null:runs.get(runs.size()-1);
        boolean extend=last!=null && last.ms==ms && last.x==x && last.y==y && last.advance==advance && last.choice==choice && last.skip==skip;
        if (ticks==MAX_TICKS || (!extend && runs.size()==MAX_RUNS)) { useNativeBaseline(x,y); return; }
        if (extend) last.repeats++;
        else runs.add(new Run(1,ms,x,y,advance,choice,skip));
        ticks++;
    }
    boolean writable(){return !runs.isEmpty();}
    int cursor(){return requiresSnapshot()?SNAPSHOT_CURSOR:ticks;}
    static GameSave nativeBaseline(){
        GameSave save=new GameSave();
        save.useNativeBaseline(0,0);
        return save;
    }
    void recordModalBoundary(int x,int y){
        // The legacy journal cannot reproduce a raw-text or multi-step reply.
        // Existing native Backlog checkpoints still validate their original cursor.
        if(!requiresSnapshot())useNativeBaseline(x,y);
    }
    boolean requiresSnapshot(){return !runs.isEmpty()&&runs.get(0).choice==NATIVE_BASELINE;}
    void requireReplayable() throws IOException {
        if(requiresSnapshot())throw new IOException("原生快照缺失或无效，此存档没有可回退的完整输入记录");
    }
    private void useNativeBaseline(int x,int y){
        runs.clear();runs.trimToSize();
        runs.add(new Run(1,0,0,0,false,NATIVE_BASELINE));
        updateNativePointer(x,y);
    }
    // A native-only file keeps the usual last-input coordinates for the next
    // live tick. The native baseline marker prevents this record being replayed.
    private void updateNativePointer(int x,int y){
        if(runs.size()==1)runs.add(new Run(1,0,x,y,false,0));
        else if(runs.get(1).x!=x||runs.get(1).y!=y)runs.set(1,new Run(1,0,x,y,false,0));
        ticks=2;
    }
    boolean hasSystemBaseline(){return !runs.isEmpty()&&runs.get(0).choice==RESET_SYSTEM_VARIABLES;}
    Run lastInput(){
        int tail=runs.size()-1;
        while(tail>0&&runs.get(tail).choice<TIMED_OUT)tail--;
        return runs.get(tail);
    }
    void recordSystemVariables(int[] values){
        recordIndexed(values,NativeGame.SYSTEM_VARIABLES,RESET_SYSTEM_VARIABLES,SET_SYSTEM_VARIABLE);
    }
    private void recordIndexed(int[] values,int length,int reset,int set){
        if(values.length!=length)throw new IllegalArgumentException("无效的全局脚本状态数量");
        if(!stateRun(reset,0,0))return;
        for(int i=0;i<values.length;i++)if(values[i]!=0&&!stateRun(set,i,values[i]))return;
    }
    private boolean reserve(int records,int additionalTicks){
        if(requiresSnapshot())return false;
        if(records>MAX_RUNS-runs.size()||additionalTicks>MAX_TICKS-ticks){
            Run last=lastInput();useNativeBaseline(last.x,last.y);return false;
        }
        return true;
    }
    private boolean stateRun(int type,int x,int y){
        if(!reserve(1,1))return false;
        runs.add(new Run(1,0,x,y,false,type));ticks++;
        return true;
    }
    void recordReplayBaseline(int[] system,int[] progress,int[] gallery,int random){
        if(requiresSnapshot())return;
        // Loading repeatedly without a new input replaces the trailing
        // baseline, while preserving the preceding input and its clock sample.
        int end=runs.size(),start=end;
        if(end>0&&runs.get(end-1).choice==RANDOM_STATE){
            while(start>0){
                int type=runs.get(start-1).choice;
                if(type>=TIMED_OUT||type==TRIM_BACKLOG||type==CLOCK_SAMPLE||type==NATIVE_BASELINE)break;
                start--;
            }
            runs.subList(start,end).clear();ticks-=end-start;
        }
        recordSystemVariables(system);
        recordIndexed(progress,NativeGame.SCRIPT_PROGRESS,RESET_SCRIPT_PROGRESS,SET_SCRIPT_PROGRESS);
        if(gallery.length>MAX_GALLERY_KEYS)throw new IllegalArgumentException("鉴赏记录超过上限");
        if(!stateRun(RESET_GALLERY,0,0))return;
        for(int i=0;i<gallery.length;){
            int startKey=gallery[i]&~31,mask=0;
            do{mask|=1<<(gallery[i++]&31);}
            while(i<gallery.length&&(gallery[i]&~31)==startKey);
            if(!stateRun(GALLERY_BITS,startKey,mask))return;
        }
        stateRun(RANDOM_STATE,random,0);
    }
    void appendWithClock(int ms,int x,int y,boolean advance,int choice,boolean skip,int date,int milliseconds){
        if(date!=0)stateRun(CLOCK_SAMPLE,date,milliseconds);
        append(ms,x,y,advance,choice,skip);
    }
    void recordMovieContinuationClock(int date,int milliseconds){
        if(requiresSnapshot()||runs.isEmpty())return;
        // The VM continues the same journal input after playback. A first
        // clock read in that continuation belongs before that input.
        int last=runs.size()-1;
        Run input=runs.get(last);
        if(input.choice<TIMED_OUT)throw new IllegalStateException("动画恢复缺少输入记录");
        if(!reserve(input.repeats==1?1:2,1))return;
        Run clock=new Run(1,0,date,milliseconds,false,CLOCK_SAMPLE);
        if(input.repeats==1)runs.add(last,clock);
        else{
            input.repeats--;
            runs.add(clock);runs.add(new Run(1,input.ms,input.x,input.y,input.advance,input.choice,input.skip));
        }
        ticks++;
    }
    GameSave rewind(int cursor,int keepBacklog,int mouseX,int mouseY) throws IOException {
        if(cursor<=0||cursor>SNAPSHOT_CURSOR||keepBacklog<0||keepBacklog>100)
            throw new IOException("回看检查点已失效");
        GameSave prefix=new GameSave();
        // The native owner still checks the original checkpoint ID and cursor.
        // Its snapshot restores the scene even when that cursor predates compaction.
        if(requiresSnapshot()) {prefix.useNativeBaseline(mouseX,mouseY);return prefix;}
        if(cursor>ticks)throw new IOException("回看检查点已失效");
        int left=cursor;
        for(Run r:runs){
            if(left<=r.repeats){
                prefix.runs.add(new Run(left,r.ms,r.x,r.y,r.advance,r.choice,r.skip));
                left=0;break;
            }
            prefix.runs.add(r);
            left-=r.repeats;
        }
        if(left!=0)throw new IOException("回看检查点已失效");
        prefix.ticks=cursor;
        if(keepBacklog!=0){
            // The rewound journal once included rows since evicted from the
            // live history. Replay must trim them before applying later input.
            prefix.stateRun(TRIM_BACKLOG,keepBacklog,0);
        }
        return prefix;
    }
    Loaded capture(int slot,long handle,int[] meta,String speaker,String body) throws IOException {
        if(!writable())throw new IOException("尚未开始游戏");
        byte[][] content=NativeGame.content(handle); checkContent(content);
        int[] savedMeta=meta;
        if(meta[0]==NativeGame.MODAL&&NativeGame.placeMode(handle)==NativeGame.SELECT_PLACE2_MODE){
            // The snapshot saves the active place screen and drops any suspended choice.
            savedMeta=meta.clone();savedMeta[0]=NativeGame.PLACE;
            content=Arrays.copyOf(content,2);
        }
        Metadata at=metadata(slot,System.currentTimeMillis(),GameResourcePaths.displayScript(content[0]),savedMeta,speaker,body);
        GameSave journal=new GameSave();
        Run tail=lastInput();
        if(meta[0]==NativeGame.MODAL)journal.useNativeBaseline(tail.x,tail.y);
        else{
            journal.runs.addAll(runs);journal.ticks=ticks;
            // The live journal can only extend its final run. Earlier runs are
            // shared read-only with the disk worker.
            int last=runs.size()-1;
            Run run=runs.get(last);
            journal.runs.set(last,new Run(run.repeats,run.ms,run.x,run.y,run.advance,run.choice,run.skip));
        }
        byte[] snapshot=NativeGame.snapshot(handle);
        if(journal.requiresSnapshot()&&(snapshot==null||snapshot.length==0||snapshot.length>MAX_SNAPSHOT))
            throw new IOException("无法生成完整的原生快照");
        return new Loaded(journal,at,content,snapshot);
    }
    static void write(File file,Loaded data) throws IOException {
        checkSlot(file,data.metadata);
        AtomicFile atomic=new AtomicFile(file); FileOutputStream raw=atomic.startWrite();
        try {
            writePayload(raw,data);
            raw.getFD().sync();
            atomic.finishWrite(raw);
            raw=null;
            GameSavePaths.verifyCommitted(file);
        }
        catch (IOException | RuntimeException e) { if(raw!=null)atomic.failWrite(raw); throw e; }
    }
    static void writePayload(OutputStream raw,Loaded data) throws IOException {
        data.save.writeTo(raw,data.metadata,data.content);
        if(!writeSnapshot(raw,data.nativeSnapshot)&&data.save.requiresSnapshot())
            throw new IOException("无法写入完整的原生快照");
    }
    static Metadata metadata(int slot,long savedAt,String script,int[] meta,String speaker,String body) throws IOException {
        if (meta==null || meta.length<3) throw new IOException("缺少存档位置");
        Metadata result=new Metadata(slot,savedAt,script,meta[0],meta[1],meta[2],
            clip(singleLine(speaker),256),clip(singleLine(body),40),false);
        validateMetadata(result); return result;
    }
    private void checkWritable(Metadata metadata,byte[][] content) throws IOException {
        if (!writable()) throw new IOException("尚未开始游戏");
        validateMetadata(metadata); checkContent(content);
        if (!matchesScript(metadata.script,content[0])) throw new IOException("存档摘要与当前脚本不一致");
        int total=0;boolean hasInput=false;
        for (Run r:runs) { validateRun(r,total); total+=r.repeats;hasInput|=r.choice>=TIMED_OUT||r.choice==NATIVE_BASELINE; }
        if (runs.size()>MAX_RUNS || total!=ticks || !hasInput) throw new IOException("存档记录不完整");
    }
    void writeTo(OutputStream raw,Metadata metadata,byte[][] content) throws IOException {
        checkWritable(metadata,content);
        CRC32 crc=new CRC32();
        DataOutputStream out=new DataOutputStream(new BufferedOutputStream(new CheckedOutputStream(raw,crc)));
        out.writeInt(MAGIC_NS2); out.writeInt(metadata.slot); out.writeLong(metadata.savedAt);
        out.writeUTF(metadata.script); out.writeInt(metadata.wait); out.writeInt(metadata.word); out.writeInt(metadata.line);
        out.writeUTF(metadata.speaker); out.writeUTF(metadata.summary); out.writeInt(content.length);
        for (byte[] field:content) { out.writeInt(field.length); out.write(field); }
        out.writeInt(runs.size());
        for (Run r:runs) {
            out.writeInt(r.repeats); out.writeInt(r.ms); out.writeInt(r.x); out.writeInt(r.y);
            out.writeBoolean(r.advance); out.writeInt(r.choice); out.writeBoolean(r.skip);
        }
        out.flush();
        DataOutputStream footer=new DataOutputStream(raw); footer.writeInt((int)crc.getValue()); footer.flush();
    }
    private static boolean writeSnapshot(OutputStream raw,byte[] snapshot) throws IOException {
        if(snapshot==null||snapshot.length==0||snapshot.length>MAX_SNAPSHOT)return false;
        ByteArrayOutputStream compressed=new ByteArrayOutputStream();
        Deflater deflater=new Deflater(Deflater.BEST_SPEED);
        try(DeflaterOutputStream zip=new DeflaterOutputStream(compressed,deflater,8192)){zip.write(snapshot);}
        finally{deflater.end();}
        if(compressed.size()>MAX_SNAPSHOT)return false;
        CRC32 crc=new CRC32();crc.update(snapshot);
        DataOutputStream out=new DataOutputStream(raw);
        out.writeInt(SNAPSHOT_TAG);out.writeInt(snapshot.length);out.writeInt(compressed.size());out.writeInt((int)crc.getValue());
        compressed.writeTo(out);out.flush();
        return true;
    }
    private static byte[] readSnapshot(DataInputStream in){
        try{
            int size=in.readInt(),compressedSize=in.readInt(),checksum=in.readInt();
            if(size<=0||size>MAX_SNAPSHOT||compressedSize<=0||compressedSize>MAX_SNAPSHOT)return null;
            byte[] compressed=new byte[compressedSize];in.readFully(compressed);
            if(in.read()!=-1)return null;
            byte[] snapshot=new byte[size];
            try(DataInputStream zip=new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(compressed)))){
                zip.readFully(snapshot);if(zip.read()!=-1)return null;
            }
            CRC32 crc=new CRC32();crc.update(snapshot);
            return (int)crc.getValue()==checksum?snapshot:null;
        }catch(IOException e){return null;}
    }
    static Metadata readMetadata(File file) throws IOException {
        try (InputStream in=new AtomicFile(file).openRead()) {
            Metadata metadata=readMetadata(in,file.lastModified(),SaveSlots.slotForFile(file));
            checkSlot(file,metadata); return metadata;
        }
    }
    static Metadata readMetadata(InputStream raw,long modified,int legacySlot) throws IOException {
        DataInputStream in=new DataInputStream(new BufferedInputStream(raw));
        int magic=in.readInt();
        if(magic==MAGIC_NS2)return readModernMetadata(in);
        if(magic!=MAGIC_NS1)throw new IOException("无法识别存档");
        // NS1 puts the metadata after its fixed-size input records.
        int count=in.readInt();
        if(count<1||count>MAX_RUNS)throw new IOException("存档长度无效");
        int bytes=count*LEGACY_RUN_BYTES;
        if(in.skipBytes(bytes)!=bytes)throw new EOFException("旧版存档不完整");
        return readLegacyMetadata(in,modified,legacySlot);
    }
    private static Metadata readModernMetadata(DataInputStream in) throws IOException {
        int slot=in.readInt();long savedAt=in.readLong();String script=in.readUTF();
        int wait=in.readInt(),word=in.readInt(),line=in.readInt();
        Metadata metadata=new Metadata(slot,savedAt,script,wait,word,line,in.readUTF(),in.readUTF(),false);
        validateMetadata(metadata);
        return metadata;
    }
    private static Metadata readLegacyMetadata(DataInputStream in,long modified,int slot) throws IOException {
        String script=in.readUTF();int wait=in.readInt(),word=in.readInt(),line=in.readInt();
        Metadata metadata=new Metadata(slot,Math.max(0,modified),script,wait,word,line,"","",true);
        validateMetadata(metadata);
        return metadata;
    }
    static Loaded readFrom(InputStream raw,long modified,int legacySlot,boolean keepRuns) throws IOException {
        BufferedInputStream buffered=new BufferedInputStream(raw); CRC32 crc=new CRC32();
        DataInputStream in=new DataInputStream(new CheckedInputStream(buffered,crc));
        int magic=in.readInt(); boolean modern=magic==MAGIC_NS2;
        if (!modern && magic!=MAGIC_NS1) throw new IOException("无法识别存档");
        Metadata metadata=null; byte[][] content=null;
        if (modern) {
            metadata=readModernMetadata(in);
            int fields=in.readInt();
            if (fields<2 || fields>1024) throw new IOException("存档文本数量无效");
            if (keepRuns) content=new byte[fields][];
            int bytes=0; byte[] scratch=keepRuns?null:new byte[4096];
            for (int i=0;i<fields;i++) {
                int length=in.readInt();
                if (length<0 || length>MAX_CONTENT_BYTES-bytes) throw new IOException("存档文本过大");
                bytes+=length;
                if (keepRuns) { content[i]=new byte[length]; in.readFully(content[i]); }
                else for (int left=length;left>0;) { int n=Math.min(left,scratch.length); in.readFully(scratch,0,n); left-=n; }
            }
        }
        int count=in.readInt();
        if (count<1 || count>MAX_RUNS) throw new IOException("存档长度无效");
        GameSave save=keepRuns?new GameSave():null; int total=0;boolean hasInput=false;
        for (int i=0;i<count;i++) {
            int repeats=in.readInt(),ms=in.readInt(),x=in.readInt(),y=in.readInt();
            boolean advance=readFlag(in); int choice=in.readInt(); boolean skip=modern && readFlag(in);
            if (!modern && choice<0) throw new IOException("旧版存档输入无效");
            Run run=new Run(repeats,ms,x,y,advance,choice,skip);
            validateRun(run,total); total+=repeats;hasInput|=choice>=TIMED_OUT||choice==NATIVE_BASELINE;
            if (keepRuns) save.runs.add(run);
        }
        if(!hasInput)throw new IOException("缺少存档输入");
        if (!modern) metadata=readLegacyMetadata(in,modified,legacySlot);
        if (modern) {
            long actual=crc.getValue(); long expected=Integer.toUnsignedLong(new DataInputStream(buffered).readInt());
            if (actual!=expected) throw new IOException("存档校验失败，文件可能已损坏");
        }
        byte[] snapshot=null;
        int first=buffered.read();
        if(first!=-1){
            if(!modern||first!=(SNAPSHOT_TAG>>>24))throw new IOException("存档尾部无效");
            DataInputStream extension=new DataInputStream(buffered);
            int second=extension.read(),third=extension.read(),fourth=extension.read();
            if(second>=0&&third>=0&&fourth>=0){
                if((first<<24|second<<16|third<<8|fourth)!=SNAPSHOT_TAG)throw new IOException("存档尾部无效");
                if(keepRuns)snapshot=readSnapshot(extension);
            }
        }
        if (save!=null) {
            if(metadata.wait==NativeGame.MODAL&&(!save.requiresSnapshot()||snapshot==null))
                throw new IOException("选择场景存档缺少有效原生快照");
            save.ticks=total;
            if(save.requiresSnapshot()){
                Run last=save.lastInput();save.useNativeBaseline(last.x,last.y);
            }
        }
        return new Loaded(save,metadata,content,snapshot);
    }
    static Loaded read(File file) throws IOException {
        Loaded loaded;
        try (InputStream in=new AtomicFile(file).openRead()) {
            loaded=readFrom(in,file.lastModified(),SaveSlots.slotForFile(file),true);
        }
        checkSlot(file,loaded.metadata);
        return loaded;
    }
    static boolean restoreSnapshot(Loaded loaded,long handle,int[] meta,boolean keepMusicPosition,boolean migrateLegacy) {
        Metadata at=loaded.metadata;
        return loaded.nativeSnapshot!=null&&NativeGame.restoreSnapshot(handle,loaded.nativeSnapshot,at.wait,at.word,at.line,
            loaded.content,meta,keepMusicPosition,migrateLegacy);
    }
    static void replay(Loaded loaded,long handle,int[] meta) throws IOException {
        if(meta==null||meta.length<7)throw new IOException("缺少恢复状态");
        int[] nativeMeta=meta.length>=NativeGame.META_SIZE?meta:new int[NativeGame.META_SIZE];
        replay(loaded,new Replay() {
            public void setSkip(boolean skip) { NativeGame.setSkip(handle,skip); }
            public void tick(int ms,int x,int y,boolean advance,int choice,int[] state) {
                NativeGame.tick(handle,ms,x,y,advance,choice,true,state);
            }
            public byte[][] content() { return NativeGame.content(handle); }
            public void trimBacklog(int keep) { NativeGame.trimReplayedBacklog(handle,keep); }
            public void systemVariables(int[] values) { NativeGame.setSystemVariables(handle,values); }
            public void scriptProgress(int[] values) { NativeGame.setScriptProgress(handle,values); }
            public void gallery(int[] keys) { NativeGame.replaceGallery(handle,keys); }
            public void randomState(int state) { NativeGame.setScriptRandomState(handle,state); }
            public void clock(int date,int milliseconds) {
                nativeMeta[NativeGame.CLOCK_DATE]=date;
                nativeMeta[NativeGame.CLOCK_MILLISECONDS]=milliseconds;
            }
        },nativeMeta);
        if(nativeMeta!=meta)System.arraycopy(nativeMeta,0,meta,0,meta.length);
    }
    static void replay(Loaded loaded,Replay engine,int[] meta) throws IOException {
        if (loaded.save==null || meta==null || meta.length<7) throw new IOException("缺少恢复状态");
        loaded.save.requireReplayable();
        int[] system=null,progress=null;
        ArrayList<Integer> gallery=null;
        boolean recorded=false;
        int clockDate=0,clockMilliseconds=0;
        for (Run r:loaded.save.runs) {
            if(clockDate!=0&&r.choice<TIMED_OUT)throw new IOException("系统时间样本缺少对应输入");
            switch(r.choice){
                case RESET_SYSTEM_VARIABLES: system=new int[NativeGame.SYSTEM_VARIABLES];continue;
                case SET_SYSTEM_VARIABLE:
                    if(system==null)throw new IOException("缺少系统变量起始记录");
                    system[r.x]=r.y;continue;
                case RESET_SCRIPT_PROGRESS: progress=new int[NativeGame.SCRIPT_PROGRESS];continue;
                case SET_SCRIPT_PROGRESS:
                    if(progress==null)throw new IOException("缺少结果起始记录");
                    progress[r.x]=r.y;continue;
                case RESET_GALLERY: gallery=new ArrayList<>();continue;
                case GALLERY_BITS:
                    if(gallery==null)throw new IOException("缺少鉴赏起始记录");
                    for(int bits=r.y;bits!=0;bits&=bits-1){
                        int key=r.x+Integer.numberOfTrailingZeros(bits);
                        if(gallery.size()==MAX_GALLERY_KEYS||!gallery.isEmpty()&&gallery.get(gallery.size()-1)>=key)
                            throw new IOException("无效的鉴赏起始记录");
                        gallery.add(key);
                    }
                    continue;
                case RANDOM_STATE: engine.randomState(r.x);recorded=true;continue;
                case CLOCK_SAMPLE:
                    if(!recorded)throw new IOException("缺少随机数起始记录");
                    clockDate=r.x;clockMilliseconds=r.y;continue;
                default: break;
            }
            if(system!=null){engine.systemVariables(system);system=null;}
            if(progress!=null){engine.scriptProgress(progress);progress=null;}
            if(gallery!=null){applyGallery(engine,gallery);gallery=null;}
            if(r.choice==TRIM_BACKLOG){engine.trimBacklog(r.x);continue;}
            if(clockDate!=0&&r.repeats!=1)throw new IOException("系统时间样本对应多条输入");
            for (int left=r.repeats;left>0;) {
                int n=1;
                // Skip may advance a dialogue per logical tick, so never combine skip ticks.
                if (clockDate==0 && !r.skip && !r.advance && r.choice==0 && r.ms>0) {
                    // Choice appearance and its countdown consume separate logical ticks.
                    if (meta[0]==NativeGame.DIALOGUE) n=Math.min(left,60000/r.ms);
                    else if ((meta[0]==NativeGame.DRAW || meta[0]==NativeGame.OVERLAP) && r.ms==meta[5])
                        n=Math.max(1,Math.min(left,Math.min(60000/r.ms,meta[6])));
                }
                engine.clock(clockDate!=0?clockDate:recorded?-1:0,clockMilliseconds);
                engine.setSkip(r.skip); engine.tick(r.ms*n,r.x,r.y,r.advance,r.choice,meta); left-=n;
                clockDate=clockMilliseconds=0;
            }
        }
        if(clockDate!=0)throw new IOException("系统时间样本缺少对应输入");
        if(system!=null)engine.systemVariables(system);
        if(progress!=null)engine.scriptProgress(progress);
        if(gallery!=null)applyGallery(engine,gallery);
        Metadata m=loaded.metadata; byte[][] actual=engine.content();
        if (actual==null || actual.length<1 || actual[0]==null || meta[0]!=m.wait || meta[1]!=m.word || meta[2]!=m.line ||
            !matchesScript(m.script,actual[0]) || (!m.legacy && !Arrays.deepEquals(loaded.content,actual)))
            throw new IOException("存档与此游戏目录中的脚本、对白或选项不匹配"+(recorded?"":"，旧输入日志没有随机数和本地时间记录"));
    }
    private static boolean matchesScript(String stored,byte[] raw){
        // Older summaries used UTF-8 replacement. NS2 still compares every raw field.
        return stored.equals(GameResourcePaths.displayScript(raw)) || stored.equals(new String(raw,StandardCharsets.UTF_8));
    }
    private static void applyGallery(Replay engine,ArrayList<Integer> gallery) throws IOException {
        int[] keys=new int[gallery.size()];
        for(int i=0;i<keys.length;i++)keys[i]=gallery.get(i);
        engine.gallery(keys);
    }
    private static boolean readFlag(DataInputStream in) throws IOException {
        int value=in.readUnsignedByte(); if (value>1) throw new IOException("存档开关无效"); return value!=0;
    }
    static void checkSlot(File file,Metadata metadata) throws IOException {
        if (!metadata.legacy && metadata.slot!=SaveSlots.slotForFile(file)) throw new IOException("存档编号与文件不匹配");
    }
    private static void validateRun(Run r,int total) throws IOException {
        if(r.repeats<=0||r.repeats>MAX_TICKS-total||r.ms<0||r.ms>1000||r.choice<SET_SCRIPT_PROGRESS)
            throw new IOException("存档输入无效");
        if(r.choice<TIMED_OUT){
            boolean valid;
            switch(r.choice){
                case TRIM_BACKLOG: valid=total>0&&r.x>=1&&r.x<=100&&r.y==0;break;
                case RESET_SYSTEM_VARIABLES: case RESET_SCRIPT_PROGRESS: case RESET_GALLERY: valid=r.x==0&&r.y==0;break;
                case SET_SYSTEM_VARIABLE: valid=r.x>=0&&r.x<NativeGame.SYSTEM_VARIABLES;break;
                case SET_SCRIPT_PROGRESS: valid=r.x>=0&&r.x<NativeGame.SCRIPT_PROGRESS;break;
                case RANDOM_STATE: valid=r.y==0;break;
                case CLOCK_SAMPLE:
                    valid=r.x>=10101&&r.x<=655351231&&r.x/100%100>=1&&r.x/100%100<=12&&
                        r.x%100>=1&&r.x%100<=31&&r.y>=0&&r.y<86400000;break;
                case GALLERY_BITS: valid=(r.x>>>28)>=1&&(r.x>>>28)<=5&&(r.x&31)==0&&r.y!=0;break;
                case NATIVE_BASELINE: valid=total==0&&r.x==0&&r.y==0;break;
                default: valid=false;
            }
            if(!valid||r.repeats!=1||r.ms!=0||r.advance||r.skip)throw new IOException("存档状态记录无效");
        }
    }
    private static void validateMetadata(Metadata m) throws IOException {
        if (!SaveSlots.valid(m.slot) || m.savedAt<0 || m.script==null || m.script.isEmpty() || m.script.length()>4096 ||
            m.script.indexOf('\0')>=0 || m.wait<0 || m.wait>NativeGame.TITLE&&m.wait!=NativeGame.PLACE&&m.wait!=NativeGame.MODAL || m.word<0 || m.line<0 ||
            m.speaker==null || m.summary==null || m.speaker.codePointCount(0,m.speaker.length())>256 ||
            m.summary.codePointCount(0,m.summary.length())>40 || m.speaker.indexOf('\0')>=0 || m.summary.indexOf('\0')>=0)
            throw new IOException("存档摘要无效");
    }
    private static void checkContent(byte[][] content) throws IOException {
        if (content==null || content.length<2 || content.length>1024) throw new IOException("缺少存档文本");
        int bytes=0;
        for (byte[] field:content) {
            if (field==null || field.length>MAX_CONTENT_BYTES-bytes) throw new IOException("存档文本过大"); bytes+=field.length;
        }
    }
    private static String singleLine(String text) {
        if (text==null) return "";
        StringBuilder out=new StringBuilder(); boolean space=false;
        for (int offset=0;offset<text.length();) {
            int cp=text.codePointAt(offset); offset+=Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) { space=out.length()>0; continue; }
            if (space) out.append(' '); out.appendCodePoint(cp); space=false;
        }
        return out.toString();
    }
    private static String clip(String text,int limit) {
        return text.codePointCount(0,text.length())<=limit?text:text.substring(0,text.offsetByCodePoints(0,limit-1))+"…";
    }
}
