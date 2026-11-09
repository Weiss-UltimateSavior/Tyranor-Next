package com.tyranor.runtime.systemnnn;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UTFDataFormatException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/** NNR4 stays readable by the previous player. New fields are paired with its exact contents. */
final class GameProgress {
    private static final int NNR1=0x4e4e5231,NNR2=0x4e4e5232,NNR3=0x4e4e5233,NNR4=0x4e4e5234,
        NNE1=0x4e4e4531,NNE2=0x4e4e4532,NNE3=0x4e4e4533;
    private static final int MAX_READ=100000,TEXT_FIELDS=102,OPTION_WORDS=NativeGame.OPTIONAL_PROGRESS_WORDS,FINGERPRINT_BYTES=32;
    private static final int MAX_CHART_NODES=256*1024,MAX_CHART=8+2*MAX_CHART_NODES;
    private static final long MAX_FILE=32L*1024*1024,MAX_EXTENSION=2L*1024*1024;
    private static final Logger LOG=Logger.getLogger(GameProgress.class.getName());
    private static final MoveFile ATOMIC_MOVE=(source,target)->
        Files.move(source,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    private final Path file,extension;
    private final MoveFile move;

    interface MoveFile { void move(Path source,Path target) throws IOException; }
    private interface Encoder { void write(OutputStream output) throws IOException; }

    private static final class InvalidExtension extends IOException {
        InvalidExtension(String message){super(message);}
        InvalidExtension(String message,Throwable cause){super(message,cause);}
    }
    static final class InvalidBase extends IOException {
        InvalidBase(String message){super("全局进度主文件损坏，已保留原文件 · "+message);}
        InvalidBase(String message,Throwable cause){super("全局进度主文件损坏，已保留原文件 · "+message,cause);}
    }
    private static final class AccessFailure extends IOException {
        AccessFailure(String part,Exception cause){super("无法读取"+part+"进度，请恢复存储访问后重试",cause);}
    }

    static final class State {
        final int[] read,variables,results,optional;
        final byte[][] text;
        final byte[] chart;
        final List<String> legacy;
        State(int[] read,int[] variables,int[] results,byte[][] text,int[] optional,byte[] chart,List<String> legacy){
            this.read=read;this.variables=variables;this.results=results;this.text=text;this.optional=optional;this.chart=chart;this.legacy=legacy;
        }
    }

    private static final class Committed {
        final State state;
        final byte[] fingerprint;
        Committed(State state,byte[] fingerprint){this.state=state;this.fingerprint=fingerprint;}
    }

    GameProgress(File path){this(path,ATOMIC_MOVE);}
    GameProgress(File path,MoveFile move){file=path.toPath();extension=sibling(file,".ext");this.move=move;}

    State read() throws IOException {
        Committed saved=readCommitted(false);
        return saved==null?null:saved.state;
    }
    private Committed readCommitted(boolean repairExtension) throws IOException {
        Committed committed;
        try{
            committed=selectBase(file,sibling(file,".bak"),move);
        }catch(InvalidBase e){throw e;}
        catch(IOException|SecurityException e){throw new AccessFailure("基础",e);}
        if(committed==null)return null;
        try{
            State state=readExtension(extension,committed,repairExtension);
            if(state==committed.state)state=readExtension(sibling(extension,".bak"),committed,repairExtension);
            return new Committed(state,committed.fingerprint);
        }catch(IOException|SecurityException e){throw new AccessFailure("扩展",e);}
    }

    /** Snapshot validation shares the same preference without altering the staging directory. */
    static boolean hasReadableBase(File primary,File backup) throws IOException {
        return selectBase(primary==null?null:primary.toPath(),backup==null?null:backup.toPath(),null)!=null;
    }
    private static Committed selectBase(Path path,Path legacy,MoveFile repair) throws IOException {
        InvalidBase damaged=null;
        Committed primary=null;
        try{primary=readBase(path);}catch(InvalidBase e){damaged=e;}
        if(primary!=null){
            if(repair!=null)quarantineBadBackup(legacy,repair);
            return primary;
        }

        Committed backup;
        try{backup=readBase(legacy);}
        catch(InvalidBase e){
            if(damaged==null)throw e;
            damaged.addSuppressed(e);
            throw damaged;
        }
        if(backup==null){
            if(damaged!=null)throw damaged;
            return null;
        }
        if(repair!=null){
            if(damaged!=null)repair.move(path,sibling(path,".corrupt-"+UUID.randomUUID()));
            // Keep the backup at its original path until promotion can complete.
            repair.move(legacy,path);
        }
        return backup;
    }
    private static void quarantineBadBackup(Path backup,MoveFile repair){
        try{
            try{readBase(backup);}
            catch(InvalidBase e){repair.move(backup,sibling(backup,".corrupt-"+UUID.randomUUID()));}
        }catch(IOException|SecurityException e){
            LOG.log(Level.WARNING,"Unable to inspect or isolate the legacy global progress backup",e);
        }
    }

    private static Committed readBase(Path path) throws IOException {
        if(path==null||!regularFile(path))return null;
        MessageDigest digest=digest();
        try(FileChannel raw=FileChannel.open(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);
            DataInputStream in=new DataInputStream(new BufferedInputStream(new DigestInputStream(Channels.newInputStream(raw),digest)))){
            if(raw.size()>MAX_FILE)throw new InvalidBase("Global progress exceeds size limit");
            State state=decode(in);
            return new Committed(state,digest.digest());
        }
    }
    private static Path sibling(Path path,String suffix){return path.resolveSibling(path.getFileName()+suffix);}
    private static boolean regularFile(Path path) throws IOException {
        try{
            if(!Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).isRegularFile())
                throw new IOException("Cannot access progress file: "+path.getFileName());
            return true;
        }catch(NoSuchFileException e){return false;}
    }

    /** Called with the game's save lock held, after explicit confirmation. */
    static void recoverCorruptBase(File file) throws IOException {
        Path base=file.toPath(),backup=sibling(base,".bak"),ext=sibling(base,".ext");
        Committed primary=null,legacy=null;
        boolean corruptPrimary=false,corruptBackup=false;
        try{primary=readBase(base);}catch(InvalidBase e){corruptPrimary=true;}
        try{legacy=readBase(backup);}catch(InvalidBase e){corruptBackup=true;}
        if(!corruptPrimary&&!corruptBackup)return;
        Path[] candidates;
        if(legacy!=null)candidates=new Path[]{base};
        else if(primary!=null)candidates=new Path[]{backup};
        else candidates=new Path[]{ext,sibling(ext,".new"),sibling(ext,".bak"),sibling(base,".new"),backup,base};
        List<Path> sources=new ArrayList<>();
        for(Path candidate:candidates)if(regularFile(candidate))sources.add(candidate);
        String suffix=".corrupt-"+UUID.randomUUID();
        List<Path> moved=new ArrayList<>();
        try{
            for(Path source:sources){
                Files.move(source,sibling(source,suffix),StandardCopyOption.ATOMIC_MOVE);
                moved.add(source);
            }
        }catch(IOException e){
            for(int i=moved.size()-1;i>=0;i--){
                Path source=moved.get(i);
                try{Files.move(sibling(source,suffix),source,StandardCopyOption.ATOMIC_MOVE);}
                catch(IOException rollback){e.addSuppressed(rollback);}
            }
            throw e;
        }
    }
    private State readExtension(Path path,Committed committed,boolean repair) throws IOException {
        try{return decodeExtension(path,committed.state,committed.fingerprint);}
        catch(InvalidExtension e){
            if(repair)move.move(path,sibling(path,".corrupt-"+UUID.randomUUID()));
            LOG.log(Level.WARNING,"Malformed global progress extension ignored",e);
            return committed.state;
        }
    }
    private static State decodeExtension(Path path,State base,byte[] fingerprint) throws IOException {
        if(!regularFile(path))return base;
        try(FileChannel raw=FileChannel.open(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);
            DataInputStream in=new DataInputStream(new BufferedInputStream(Channels.newInputStream(raw)))){
            if(raw.size()>MAX_EXTENSION)throw new InvalidExtension("Global progress extension exceeds size limit");
            int version=in.readInt();
            if(version!=NNE1&&version!=NNE2&&version!=NNE3)throw new InvalidExtension("Invalid global progress extension version");
            int count=in.readUnsignedByte();
            if(count<1||count>2)throw new InvalidExtension("Invalid global progress extension count");
            State result=base;
            for(int i=0;i<count;i++){
                byte[] key=new byte[FINGERPRINT_BYTES];in.readFully(key);
                byte[][] text=readText(in);
                int[] optional=readOptional(in,version==NNE3?OPTION_WORDS:NativeGame.LEGACY_OPTIONAL_PROGRESS_WORDS);
                byte[] chart=version==NNE1?null:readChart(in);
                if(Arrays.equals(key,fingerprint)){
                    if(result!=base)throw new InvalidExtension("Duplicate global progress extension record");
                    result=new State(base.read,base.variables,base.results,text,optional,chart,base.legacy);
                }
            }
            if(in.read()!=-1)throw new InvalidExtension("Unexpected global progress extension payload");
            return result;
        }catch(EOFException e){
            throw new InvalidExtension("Truncated global progress extension",e);
        }
    }

    static State decode(DataInputStream in) throws IOException {
        try{
            int version=in.readInt(),count=readCount(in,MAX_READ);
            if(version<NNR1||version>NNR4)throw new InvalidBase("Unknown global progress format");
            int[] read=version>=NNR2?readInts(in,count):null;
            int[] variables=version>=NNR3?readInts(in,NativeGame.SYSTEM_VARIABLES):null;
            int[] results=version>=NNR4?readInts(in,NativeGame.SCRIPT_PROGRESS):null;
            if(version>=NNR2)count=readCount(in,MAX_READ);
            List<String> legacy=new ArrayList<>();
            for(int i=0;i<count;i++)legacy.add(in.readUTF());
            if(in.read()!=-1)throw new InvalidBase("Unexpected global progress payload");
            return new State(read,variables,results,null,null,null,legacy);
        }catch(EOFException|UTFDataFormatException e){throw new InvalidBase("Malformed global progress fields",e);}
    }

    void write(State state) throws IOException {
        Committed previous=readCommitted(true);
        if(previous!=null&&sameBase(state,previous.state)){
            if(!Arrays.deepEquals(state.text,previous.state.text)||!Arrays.equals(state.optional,previous.state.optional)||
                !Arrays.equals(state.chart,previous.state.chart))
                writeExtension(state,previous.fingerprint,null);
            return;
        }
        // Publish both extension generations before committing the changed NNR4 file.
        // An interrupted commit still finds the previous generation using the old NNR4 fingerprint.
        MessageDigest digest=digest();
        Path pending=stage(file,MAX_FILE,raw->{
            DataOutputStream out=new DataOutputStream(new BufferedOutputStream(new DigestOutputStream(raw,digest)));
            encode(out,state);
            out.flush();
        });
        try{
            writeExtension(state,digest.digest(),previous);
            retireLegacyBackup(file);
            move.move(pending,file);
        }catch(IOException|RuntimeException e){
            discardPending(pending,e);
            throw e;
        }
    }

    // AtomicFile.startWrite() may promote a legacy .bak before writing. Stage separately
    // so a failed write cannot replace the selected generation with that older backup.
    private static Path stage(Path target,long maximum,Encoder encoder) throws IOException {
        Path pending=sibling(target,".new");
        regularFile(pending);
        try{
            try(FileChannel raw=FileChannel.open(pending,StandardOpenOption.WRITE,StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,LinkOption.NOFOLLOW_LINKS)){
                encoder.write(Channels.newOutputStream(raw));
                if(raw.size()>maximum)throw new IOException("Global progress exceeds size limit: "+target.getFileName());
                raw.force(true);
            }
            return pending;
        }catch(IOException|RuntimeException e){
            discardPending(pending,e);
            throw e;
        }
    }
    private static void discardPending(Path pending,Exception failure){
        try{Files.deleteIfExists(pending);}
        catch(IOException|SecurityException e){failure.addSuppressed(e);}
    }
    private void retireLegacyBackup(Path target) throws IOException {
        Path backup=sibling(target,".bak");
        if(regularFile(backup))move.move(backup,sibling(backup,".previous-"+UUID.randomUUID()));
    }

    static void encode(DataOutputStream out,State state) throws IOException {
        if(state.read.length>MAX_READ||state.legacy.size()>MAX_READ||
            state.variables.length!=NativeGame.SYSTEM_VARIABLES||state.results.length!=NativeGame.SCRIPT_PROGRESS)
            throw new IOException("Invalid global progress field count");
        out.writeInt(NNR4);
        out.writeInt(state.read.length);
        writeInts(out,state.read);
        writeInts(out,state.variables);
        writeInts(out,state.results);
        out.writeInt(state.legacy.size());
        for(String key:state.legacy)out.writeUTF(key);
    }

    private static boolean sameBase(State left,State right){
        return Arrays.equals(left.read,right.read)&&Arrays.equals(left.variables,right.variables)&&
            Arrays.equals(left.results,right.results)&&left.legacy.equals(right.legacy);
    }
    private static MessageDigest digest(){
        try{return MessageDigest.getInstance("SHA-256");}
        catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
    private void writeExtension(State state,byte[] fingerprint,Committed previous) throws IOException {
        Path pending=stage(extension,MAX_EXTENSION,raw->{
            DataOutputStream out=new DataOutputStream(new BufferedOutputStream(raw));
            boolean preserve=previous!=null&&previous.state.text!=null;
            out.writeInt(hasVoiceRoles(state)||preserve&&hasVoiceRoles(previous.state)?NNE3:NNE2);
            out.writeByte(preserve?2:1);
            writeExtensionRecord(out,fingerprint,state);
            if(preserve)writeExtensionRecord(out,previous.fingerprint,previous.state);
            out.flush();
        });
        try{
            move.move(pending,extension);
        }catch(IOException|RuntimeException e){
            discardPending(pending,e);
            throw e;
        }
        try{retireLegacyBackup(extension);}
        catch(IOException|SecurityException e){
            LOG.log(Level.WARNING,"Unable to retire the legacy global progress extension backup",e);
        }
    }
    private static boolean hasVoiceRoles(State state){
        if(state.optional!=null)for(int i=NativeGame.LEGACY_OPTIONAL_PROGRESS_WORDS;i<state.optional.length;i++)
            if(state.optional[i]!=0)return true;
        return false;
    }
    private static void writeExtensionRecord(DataOutputStream out,byte[] fingerprint,State state) throws IOException {
        if(state.text.length!=TEXT_FIELDS||state.optional!=null&&state.optional.length!=OPTION_WORDS&&
            state.optional.length!=NativeGame.LEGACY_OPTIONAL_PROGRESS_WORDS)
            throw new IOException("Invalid global progress extension field count");
        out.write(fingerprint);
        for(int i=0;i<state.text.length;i++){
            byte[] field=state.text[i];
            validateText(field,i);
            out.writeByte(field.length);
            out.write(field);
        }
        int entries=0;
        if(state.optional!=null)for(int word:state.optional)if(word!=0)entries++;
        out.writeShort(entries);
        if(state.optional!=null)for(int i=0;i<state.optional.length;i++)if(state.optional[i]!=0){
            out.writeShort(i);
            out.writeInt(state.optional[i]);
        }
        int chartSize=state.chart==null?0:state.chart.length;
        if(chartSize!=0)validateChart(state.chart);
        out.writeInt(chartSize);
        if(chartSize!=0)out.write(state.chart);
    }

    private static int readCount(DataInputStream in,int maximum) throws IOException {
        int count=in.readInt();
        if(count<0||count>maximum)throw new InvalidBase("Invalid global progress count");
        return count;
    }
    private static int[] readInts(DataInputStream in,int count) throws IOException {
        int[] values=new int[count];
        for(int i=0;i<count;i++)values[i]=in.readInt();
        return values;
    }
    private static void writeInts(DataOutputStream out,int[] values) throws IOException {
        for(int value:values)out.writeInt(value);
    }
    private static int textLimit(int field){return field<2?15:32;}
    private static void validateText(byte[] text,int field) throws IOException {
        if(text==null||text.length>textLimit(field))throw new InvalidExtension("Invalid global text field");
        for(byte value:text)if(value==0)throw new InvalidExtension("Embedded terminator in global text");
    }
    private static byte[][] readText(DataInputStream in) throws IOException {
        byte[][] fields=new byte[TEXT_FIELDS][];
        for(int i=0;i<fields.length;i++){
            int size=in.readUnsignedByte();
            if(size>textLimit(i))throw new InvalidExtension("Invalid global text field size");
            in.readFully(fields[i]=new byte[size]);
            validateText(fields[i],i);
        }
        return fields;
    }
    private static int[] readOptional(DataInputStream in,int limit) throws IOException {
        int count=in.readUnsignedShort(),previous=-1;
        if(count>limit)throw new InvalidExtension("Invalid optional progress count");
        int[] values=new int[OPTION_WORDS];
        for(int i=0;i<count;i++){
            int index=in.readUnsignedShort(),word=in.readInt();
            if(index<=previous||index>=limit||word==0)throw new InvalidExtension("Invalid optional progress word");
            values[index]=word;
            previous=index;
        }
        return values;
    }
    private static byte[] readChart(DataInputStream in) throws IOException {
        int size=in.readInt();
        if(size<0||size>MAX_CHART)throw new InvalidExtension("Invalid chart progress length");
        byte[] result=new byte[size];
        in.readFully(result);
        if(size!=0)validateChart(result);
        return result;
    }
    private static void validateChart(byte[] chart) throws IOException {
        if(chart.length<8||chart.length>MAX_CHART)throw new InvalidExtension("Invalid chart progress length");
        ByteBuffer header=ByteBuffer.wrap(chart).order(ByteOrder.LITTLE_ENDIAN);
        int seen=header.getInt(),traversed=header.getInt();
        if(traversed<1||traversed>MAX_CHART_NODES||seen!=((traversed+3)&~3)||chart.length!=8+seen+traversed)
            throw new InvalidExtension("Invalid chart progress blocks");
    }
}
