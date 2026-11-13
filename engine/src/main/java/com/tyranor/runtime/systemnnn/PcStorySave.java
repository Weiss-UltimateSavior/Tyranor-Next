package com.tyranor.runtime.systemnnn;

import android.graphics.Bitmap;
import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A numbered slot shared with the PC game. The native codec owns the save block format. */
final class PcStorySave {
    private static final int MAX_BYTES=16*1024*1024;
    private static final int HEADER_BYTES=32;
    private static final int MAX_BLOCKS=8+3+256+32;
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

    /** Exact upstream CCalcuWeek::CalcuWeek(year, month, day) normalized to 0 (Sun) .. 6 (Sat). */
    static int calcuWeek(int year,int month,int day) {
        long month2=month,year2=year;
        if(month2<=2){
            year2-=1;
            month2+=12;
        }
        int raw=(int)((year2+year2/4-year2/100+year2/400+(13L*month2+8)/5+day)%7);
        return raw<0?raw+7:raw;
    }

    static final class GameDate {
        final int year,date,month,day,week;

        GameDate(int year,int date){
            this.year=year;
            this.date=date;
            this.month=date/100;
            this.day=date%100;
            this.week=calcuWeek(year,this.month,this.day);
        }
    }

    static final class GameDateVars {
        static final GameDateVars NONE=new GameDateVars(-1,-1);
        private static final int MAX_VAR_BYTES=1024*1024;
        private static final int MAX_VAR_COUNT=2224;
        final int yearIndex,dateIndex;

        GameDateVars(int yearIndex,int dateIndex){
            this.yearIndex=yearIndex;
            this.dateIndex=dateIndex;
        }

        boolean enabled(){
            return yearIndex>=0||dateIndex>=0;
        }

        private static File findVarFile(File root,String stem,boolean encrypted){
            String name=stem+(encrypted?".fxf":".xtx");
            File file=GameResourcePaths.file(root,"spt",name);
            return file!=null?file:GameResourcePaths.file(root,"init",name);
        }

        static GameDateVars load(File root,GameUiAssets.Config game) throws IOException {
            if(root==null)return NONE;
            File var2=findVarFile(root,"var2",true);
            boolean var2Enc=var2!=null;
            if(!var2Enc)var2=findVarFile(root,"var2",false);
            File var1=findVarFile(root,"var",true);
            boolean var1Enc=var1!=null;
            if(!var1Enc)var1=findVarFile(root,"var",false);
            int varType=game==null?1:game.number("varType",1);
            boolean useVar2=var2!=null&&(varType!=0||var1==null);
            File file=useVar2?var2:var1;
            if(file==null)return NONE;
            if(file.length()>MAX_VAR_BYTES)throw new IOException("Variable list too large: "+file.getName());
            byte[] raw=Files.readAllBytes(file.toPath());
            if(raw.length>MAX_VAR_BYTES)throw new IOException("Variable list too large: "+file.getName());
            boolean notUseYear=game!=null&&game.number("notUseYearVar",0)!=0;
            return parse(raw,useVar2?var2Enc:var1Enc,notUseYear);
        }

        static GameDateVars parse(byte[] raw,boolean encrypted,boolean notUseYear){
            if(raw==null||raw.length==0)return NONE;
            byte[] decoded=encrypted?raw.clone():raw;
            if(encrypted)for(int i=0;i<decoded.length;i++)decoded[i]^=(byte)0xff;
            String text=new String(decoded,StandardCharsets.ISO_8859_1);
            int year=-1,date=-1,index=0;
            for(int lineStart=0;lineStart<text.length()&&index<MAX_VAR_COUNT;){
                int nl=text.indexOf('\n',lineStart);
                int lineEnd=nl<0?text.length():nl;
                int end=lineEnd;
                while(end>lineStart){
                    char ch=text.charAt(end-1);
                    if(ch!='\r'&&ch!=' '&&ch!='\t')break;
                    end--;
                }
                if(end>lineStart){
                    for(int pos=lineStart;pos<end&&index<MAX_VAR_COUNT;){
                        int comma=text.indexOf(',',pos);
                        int tokenEnd=comma<0||comma>end?end:comma;
                        String name=text.substring(pos,tokenEnd);
                        if(!notUseYear&&year<0&&name.equalsIgnoreCase("year"))year=index;
                        if(date<0&&name.equalsIgnoreCase("date"))date=index;
                        index++;
                        if(comma<0||comma>=end)break;
                        pos=comma+1;
                    }
                }
                if(nl<0)break;
                lineStart=nl+1;
            }
            return year<0&&date<0?NONE:new GameDateVars(year,date);
        }
    }

    static Integer readGameVar(byte[] bytes,int index) {
        if(index<0)return null;
        int[] slice=findGameVarSlice(bytes);
        if(slice==null||index>=slice[1])return null;
        return i32le(bytes,slice[0]+index*4);
    }

    static GameDate readGameDate(int[] gameVars,GameDateVars vars) {
        if(gameVars==null||vars==null||!vars.enabled())return null;
        if(vars.yearIndex>=gameVars.length||vars.dateIndex>=gameVars.length)return null;
        int year=vars.yearIndex>=0?gameVars[vars.yearIndex]:2000;
        int date=vars.dateIndex>=0?gameVars[vars.dateIndex]:0;
        return new GameDate(year,date);
    }

    static GameDate readGameDate(byte[] bytes,GameDateVars vars) {
        if(vars==null||!vars.enabled())return null;
        int[] slice=findGameVarSlice(bytes);
        if(slice==null||vars.yearIndex>=slice[1]||vars.dateIndex>=slice[1])return null;
        int year=vars.yearIndex>=0?i32le(bytes,slice[0]+vars.yearIndex*4):2000;
        int date=vars.dateIndex>=0?i32le(bytes,slice[0]+vars.dateIndex*4):0;
        return new GameDate(year,date);
    }

    private static int[] findGameVarSlice(byte[] bytes) {
        if(bytes==null||bytes.length<HEADER_BYTES||bytes.length>MAX_BYTES)return null;
        int blocks=0,varOffset=-1,varCount=-1;
        HashSet<Long> seenCodes=new HashSet<>();
        for(int offset=0;offset<bytes.length;){
            if(bytes.length-offset<HEADER_BYTES||blocks>=MAX_BLOCKS)return null;
            long size=u32le(bytes,offset),code=u32le(bytes,offset+4);
            if(size<HEADER_BYTES||size>bytes.length-offset)return null;
            String name=blockName(bytes,offset+16);
            if(name==null||!seenCodes.add(code))return null;
            if(offset==0&&(size!=HEADER_BYTES||code!=0||name.isEmpty()))return null;
            if(code==4){
                if(!"GAMEVAR".equals(name)||(size!=HEADER_BYTES+1024L*4&&size!=HEADER_BYTES+2224L*4))return null;
                varOffset=offset+HEADER_BYTES;
                varCount=(int)((size-HEADER_BYTES)/4);
            }
            offset+=(int)size;
            blocks++;
        }
        return varOffset<0?null:new int[]{varOffset,varCount};
    }

    private static String blockName(byte[] bytes,int offset) {
        for(int i=0;i<16;i++){
            if(bytes[offset+i]==0)return new String(bytes,offset,i,StandardCharsets.ISO_8859_1);
        }
        return null;
    }

    private static long u32le(byte[] bytes,int offset) {
        return Integer.toUnsignedLong(i32le(bytes,offset));
    }

    private static int i32le(byte[] bytes,int offset) {
        return (bytes[offset]&0xff)|((bytes[offset+1]&0xff)<<8)|
            ((bytes[offset+2]&0xff)<<16)|((bytes[offset+3]&0xff)<<24);
    }

    static final class Preview {
        final long savedAt;
        final int artwork;
        final String summary;
        final Bitmap picture;
        final GameDate gameDate;

        Preview(long savedAt,int artwork,String summary,Bitmap picture){
            this(savedAt,artwork,summary,picture,null);
        }

        Preview(long savedAt,int artwork,String summary,Bitmap picture,GameDate gameDate){
            this.savedAt=savedAt;this.artwork=artwork;this.summary=summary;this.picture=picture;
            this.gameDate=gameDate;
        }
    }

    static Preview preview(byte[] bytes,long modified,GameTextDecoder decoder,int pictureBudget) throws IOException {
        return preview(bytes,modified,decoder,pictureBudget,GameDateVars.NONE);
    }

    static Preview preview(byte[] bytes,long modified,GameTextDecoder decoder,int pictureBudget,
                           GameDateVars dateVars) throws IOException {
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
        GameDate gameDate=null;
        if(dateVars!=null&&dateVars.enabled()){
            gameDate=data.length>=4&&data[3] instanceof int[]?
                readGameDate((int[])data[3],dateVars):readGameDate(bytes,dateVars);
        }
        return new Preview(timestamp(fields,modified),fields[7],summary,picture,gameDate);
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
