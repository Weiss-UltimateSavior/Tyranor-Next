package com.tyranor.runtime.systemnnn;

import android.graphics.Bitmap;
import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Calendar;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A numbered slot shared with the PC game. The native codec owns the save block format. */
final class PcStorySave {
    private static final int MAX_BYTES=16*1024*1024;
    private static final Pattern FILE_NAME=Pattern.compile("(t?data)(0|[1-9][0-9]{0,3})\\.sav(?:\\.bak)?",
        Pattern.CASE_INSENSITIVE);
    private final File directory;
    private final String prefix;

    PcStorySave(File directory,boolean trial) {
        this.directory=directory;
        prefix=trial?"tdata":"data";
    }

    static boolean isSlotFile(String name) {
        Matcher match=FILE_NAME.matcher(name);
        return match.matches()&&Integer.parseInt(match.group(2))<SaveSlots.MAX_MANUAL_SLOTS;
    }

    int number(String name) {
        Matcher match=FILE_NAME.matcher(name);
        if(!match.matches()||!prefix.equalsIgnoreCase(match.group(1)))return -1;
        int slot=Integer.parseInt(match.group(2));
        return slot<SaveSlots.MAX_MANUAL_SLOTS?slot:-1;
    }

    File file(int number) {
        String name=prefix+number+".sav";
        File existing=GameResourcePaths.file(directory,name);
        if(existing!=null)return existing;
        File backup=GameResourcePaths.file(directory,name+".bak");
        return new File(directory,backup==null?name:backup.getName().substring(0,backup.getName().length()-4));
    }

    static byte[] read(File file) throws IOException {
        checkEntries(file);
        try(InputStream in=new AtomicFile(file).openRead()){return read(in);}
    }

    static byte[] read(InputStream in) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        byte[] block=new byte[8192];
        for(int count;(count=in.read(block))!=-1;){
            if(count>MAX_BYTES-bytes.size())throw new IOException("PC save exceeds the supported size");
            bytes.write(block,0,count);
        }
        return bytes.toByteArray();
    }

    static void write(File file,byte[] bytes) throws IOException {
        if(bytes==null||bytes.length==0||bytes.length>MAX_BYTES)throw new IOException("Invalid PC save length");
        checkEntries(file);
        AtomicFile atomic=new AtomicFile(file);
        FileOutputStream stream=atomic.startWrite();
        try{
            stream.write(bytes);
            stream.getFD().sync();
            atomic.finishWrite(stream);
        }catch(IOException|RuntimeException e){atomic.failWrite(stream);throw e;}
        GameSavePaths.verifyCommitted(file);
    }

    private static void checkEntries(File file) throws IOException {
        checkFile(file);
        checkFile(new File(file.getPath()+".bak"));
        checkFile(new File(file.getPath()+".new"));
    }

    private static void checkFile(File file) throws IOException {
        if(Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS)&&
            !Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Invalid PC save entry "+file.getName());
    }

    static boolean valid(InputStream in) throws IOException {
        try{return NativeGame.inspectPcSave(read(in),0)!=null;}
        catch(RuntimeException invalid){return false;}
    }

    static final class Preview {
        final long savedAt;
        final int artwork;
        final String summary;
        final Bitmap picture;

        Preview(long savedAt,int artwork,String summary,Bitmap picture){
            this.savedAt=savedAt;this.artwork=artwork;this.summary=summary;this.picture=picture;
        }
    }

    static Preview preview(byte[] bytes,long modified,GameTextDecoder decoder,int pictureBudget) throws IOException {
        Object[] data;
        try{data=NativeGame.inspectPcSave(bytes,pictureBudget);}
        catch(OutOfMemoryError exhausted){
            if(pictureBudget==0)throw exhausted;
            data=NativeGame.inspectPcSave(bytes,0);
        }
        if(data==null)throw new IOException("PC save preview is unavailable");
        int[] fields=(int[])data[0];
        String summary="";
        try{summary=decoder.decode((byte[])data[1]).trim();}
        catch(IOException ignored){/* An unreadable comment is separate from the saved state. */}
        if(summary.codePointCount(0,summary.length())>40)
            summary=summary.substring(0,summary.offsetByCodePoints(0,40));
        Bitmap picture=null;
        if(data[2]!=null)try{
            picture=Bitmap.createBitmap((int[])data[2],fields[8],fields[9],Bitmap.Config.ARGB_8888);
        }catch(OutOfMemoryError ignored){/* The slot can still be loaded without its picture. */}
        return new Preview(timestamp(fields,modified),fields[7],summary,picture);
    }

    private static long timestamp(int[] fields,long fallback) {
        if(fields[0]<1970||fields[0]>9999)return fallback;
        Calendar time=Calendar.getInstance(Locale.ROOT);
        time.clear();
        time.setLenient(false);
        time.set(fields[0],fields[1]-1,fields[2],fields[3],fields[4],fields[5]);
        time.set(Calendar.MILLISECOND,fields[6]);
        try{return time.getTimeInMillis();}
        catch(IllegalArgumentException invalid){return fallback;}
    }
}
