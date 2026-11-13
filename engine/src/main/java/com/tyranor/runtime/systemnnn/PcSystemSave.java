package com.tyranor.runtime.systemnnn;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Calendar;
import java.util.Collections;
import java.util.Locale;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/** Per-game Windows system archive. Edits progress in place in the decoded blocks. */
final class PcSystemSave {
    private static final Logger LOG=Logger.getLogger(PcSystemSave.class.getName());
    private static final Pattern SYSTEM_NAME=Pattern.compile("t?sysfile[23]?\\.sav",Pattern.CASE_INSENSITIVE);
    private static final int HEADER=32, RESULT_WORDS=1024, TERM_WORDS=1024, MAX_BYTES=147456;
    private static final int DATA=1, READ=5, VAR=6, CG=7, FILM=9, VOICE=10, MOVIE=11, MUSIC=12,
        TEXT=13, ACHIEVEMENT=14, TERM=15, VOICE_LINE=16, FLAG2=17, CHOICE=18;
    // FILE INFO counts itself. Classic games can end at MUSICFLAG.
    private static final int[] CODES={0,1,5,6,7,8,9,10,11,12,13,14,15,16,17,18};
    private static final int[] SIZES={64,2048,32800,4128,1312,2080,16416,1056,1056,1056,6432,4128,4128,65568,1056,4128};
    private static final String[] LABELS={"FILE INFO","SYSTEM DATA","MES FLAG","SYSTEMVAR","CGFLAG","SCENEFLAG",
        "FILMFLAG","VOICEFLAG","MOVIEFLAG","MUSICFLAG","OKIKAE","ACHIEVEMENT","TERM","CHARAVOICEFLAG","SYSTEMFLAG2","SELECTMESSAGE"};
    // Negative offsets belong to SYSTEMFLAG2. The IDs are the native runtime parameter IDs.
    private static final int[][] SYSTEM_FIELDS={
        {2,35},{4,21},{6,41},
        {100,44},{101,46},{102,45},{103,47},{104,53},{105,34},{106,483},{107,55},{108,492},{109,-12},
        {200,48},{201,50},{202,49},{203,51},{204,54},{208,493},{209,-13},
        {210,-10},{211,-16},{212,-11},{213,-15},{214,-9}};

    private final Path file, factory, result;
    private final int version, variables, scripts, albums, messageStride, filmStride, cgStride, movieStride;
    private boolean damaged,damagedResult;

    static PcSystemSave open(File root,File directory,GameUiAssets.Config game,int[] format) throws IOException {
        if(format==null || format.length!=2 || format[0]<1 || format[0]>3 || (format[1]!=0 && format[1]!=1))
            throw new IOException("Invalid cached PC system save format");
        int version=format[0];
        String stem=(format[1]==1?"tsysfile":"sysfile")+(version==1?"":Integer.toString(version));
        int scripts=1;
        if(game.number("noScriptFlag",0)==0 && GameResourcePaths.file(root,"spt","project.xtx")!=null){
            scripts=GalleryCatalog.tokens(root,"spt","project.xtx").size();
            if(scripts==0)scripts=64;
        }
        int albums=0;
        for(int i=1;i<=256;i++){
            String suffix=i<100?String.format(Locale.ROOT,"%02d",i):""+(char)('A'+(i-100)/10)+(i%10);
            if(GameResourcePaths.file(root,"spt","cglist"+suffix+".xtx")==null)break;
            albums++;
        }
        return new PcSystemSave(path(directory,stem+".sav"),path(directory,stem+".org"),
            game.number("resultFlag",0)==0?null:path(directory,"result.sav"),version,scripts,albums);
    }

    private PcSystemSave(Path file,Path factory,Path result,int version,int scripts,int albums){
        this.file=file;this.factory=factory;this.result=result;this.version=version;
        variables=version==1?100:1000;
        this.scripts=Math.max(2,Math.min(256,scripts));
        this.albums=albums==0?20:albums;
        int scriptSlots=Integer.highestOneBit(this.scripts-1)<<1;
        messageStride=262144/scriptSlots;filmStride=131072/scriptSlots;
        int albumSlots=this.albums;
        if(albumSlots>2){
            albumSlots=5;
            while(albumSlots<this.albums)albumSlots*=2;
        }
        cgStride=albumSlots==1?8192:albumSlots==2?4096:10240/albumSlots;
        movieStride=256/this.albums*32;
    }

    private static Path path(File directory,String name){
        File found=GameResourcePaths.file(directory,name);
        return (found==null?new File(directory,name):found).toPath();
    }
    static boolean isSystemFile(String name){
        return SYSTEM_NAME.matcher(name).matches();
    }
    static boolean isResultFile(String name){return "result.sav".equalsIgnoreCase(name);}

    static boolean valid(File file) throws IOException {
        String name=file.getName().toLowerCase(Locale.ROOT);
        try{
            byte[] bytes=read(file.toPath());
            if(bytes==null)return false;
            if(isResultFile(name))return bytes.length==RESULT_WORDS*4;
            if(!isSystemFile(name))return false;
            new Archive(bytes,name.endsWith("file.sav")?1:name.endsWith("2.sav")?2:3);
            return true;
        }catch(InvalidFile invalid){return false;}
    }

    static final class Initial {
        final Archive archive;
        final boolean live;
        final int[] results;
        Initial(Archive archive,boolean live,int[] results){this.archive=archive;this.live=live;this.results=results;}
        boolean hasDisplayOptions(){return archive!=null&&archive.has(DATA);}
        boolean noSkipFrame(){
            int offset=archive==null?-1:archive.wordOffset(DATA,38);
            return offset>=0&&archive.data.getInt(offset)!=0;
        }
        int messageCursor(){
            int offset=archive==null?-1:archive.wordOffset(DATA,481);
            return offset>=0?archive.data.getInt(offset):0;
        }
        int mouseCursor(){
            int offset=archive==null?-1:archive.wordOffset(DATA,482);
            return offset>=0?archive.data.getInt(offset):0;
        }
        int[] audioParameters(){
            if(!live||archive==null)return new int[0];
            int[] pairs=new int[(SYSTEM_FIELDS.length+32+72)*2];int at=0;
            for(int[] field:SYSTEM_FIELDS){
                int offset=systemFieldOffset(archive,field[1]);
                if(offset>=0){pairs[at++]=field[0];pairs[at++]=archive.data.getInt(offset);}
            }
            for(int i=0;i<16;i++){
                int modeOffset=archive.wordOffset(DATA,448+i);
                if(modeOffset>=0){pairs[at++]=300+i;pairs[at++]=archive.data.getInt(modeOffset);}
                int checkOffset=archive.wordOffset(DATA,464+i);
                if(checkOffset>=0){pairs[at++]=400+i;pairs[at++]=archive.data.getInt(checkOffset);}
            }
            for(int group=0;group<72;group++){
                int offset=characterGainOffset(archive,group);
                if(offset<0)continue;
                int packed=archive.data.getInt(offset);
                int gain=(packed>>((group%6)*5))&31;
                pairs[at++]=500+group;pairs[at++]=gain<16?gain:gain-32;
            }
            return Arrays.copyOf(pairs,at);
        }
        int[] audioOwners(){
            if(!live||archive==null)return new int[0];
            int[] pairs=new int[66];int at=0;
            int windowOffset=archive.wordOffset(DATA,480);
            if(windowOffset>=0){pairs[at++]=1;pairs[at++]=archive.data.getInt(windowOffset);}
            for(int word=0;word<32;word++){
                int offset=archive.wordOffset(DATA,384+word);
                if(offset>=0){pairs[at++]=word+2;pairs[at++]=archive.data.getInt(offset);}
            }
            return Arrays.copyOf(pairs,at);
        }
        int audioTheme(){
            if(!live||archive==null)return -1;
            int offset=archive.wordOffset(FLAG2,14);
            return offset>=0?archive.data.getInt(offset):-1;
        }
    }

    Initial load() throws IOException {
        Archive live=null;
        int[] results=null;
        try{live=liveArchive();}
        catch(InvalidFile invalid){damaged=true;LOG.warning(invalid.getMessage());}
        try{results=loadResults();}
        catch(InvalidFile invalid){damagedResult=true;LOG.warning(invalid.getMessage());}
        return new Initial(live==null?factoryArchive():live,live!=null,results);
    }

    GameProgress.State restore(Initial initial,GameProgress.State old){
        Archive archive=initial.archive;
        if(archive==null && initial.results==null)return old;
        int[] read=old==null?null:old.read;
        int[] vars=old==null?null:old.variables;
        int[] results=old==null?null:old.results;
        byte[][] text=old==null?null:old.text;
        int[] optional=old==null?null:old.optional;
        if(archive!=null){
            read=restoreRead(archive,read,initial.live);
            // The native runtime already loads settings and other defaults from the .org.
            if(initial.live){
                vars=vars==null?new int[NativeGame.SYSTEM_VARIABLES]:vars.clone();
                archive.getWords(VAR,vars,0,variables);
                text=text==null?new byte[102][]:text.clone();
                text[0]=archive.getText(archive.at(DATA)+56*4,15);
                text[1]=archive.getText(archive.at(DATA)+60*4,15);
                for(int i=0;i<100;i++){
                    if(archive.has(TEXT))text[i+2]=archive.getText(archive.payload(TEXT)+i*64,32);
                    else if(text[i+2]==null)text[i+2]=new byte[0];
                }
                optional=optional==null?new int[NativeGame.OPTIONAL_PROGRESS_WORDS]:
                    Arrays.copyOf(optional,NativeGame.OPTIONAL_PROGRESS_WORDS);
                if(archive.has(TERM))archive.getWords(TERM,optional,0,TERM_WORDS);
                if(archive.has(VOICE_LINE))archive.getWords(VOICE_LINE,optional,TERM_WORDS,16384);
                int roles=NativeGame.LEGACY_OPTIONAL_PROGRESS_WORDS;
                Arrays.fill(optional,roles,optional.length,0);
                for(int i=1;i<256;i++)if(archive.data.getInt(archive.payload(VOICE)+i*4)!=0)
                    optional[roles+i/32]|=1<<(i%32);
            }
        }
        if(initial.results!=null || initial.live && archive.has(ACHIEVEMENT)){
            results=results==null?new int[NativeGame.SCRIPT_PROGRESS]:results.clone();
            if(initial.results!=null)System.arraycopy(initial.results,0,results,0,RESULT_WORDS);
            if(initial.live && archive.has(ACHIEVEMENT))archive.getWords(ACHIEVEMENT,results,RESULT_WORDS,1024);
        }
        return new GameProgress.State(read,vars,results,text,optional,old==null?null:old.chart,
            old==null?Collections.emptyList():old.legacy);
    }

    private int readBit(int key){
        int script=key>>>16,message=key&0xffff;
        return script<scripts && message<messageStride && message!=0xffff?script*messageStride+message:-1;
    }

    private int[] restoreRead(Archive archive,int[] previous,boolean live){
        BitSet read=archive.bits(READ,32768);
        int[] ids=new int[Math.min(GameProgress.MAX_READ,read.cardinality()+(previous==null?0:previous.length))];
        int count=0;
        for(int bit=read.nextSetBit(0);bit>=0 && bit<scripts*messageStride;bit=read.nextSetBit(bit+1)){
            int serial=bit%messageStride;
            if(serial<0xffff)ids[count++]=(bit/messageStride<<16)|serial;
        }
        if(previous!=null)for(int id:previous){
            int bit=readBit(id);
            if(count==ids.length)break;
            if(bit<0 || !live && !read.get(bit))ids[count++]=id;
        }
        return Arrays.copyOf(ids,count);
    }

    int[] restoreGallery(Initial initial,int[] previous){
        Archive archive=initial.archive;
        if(archive==null)return previous;
        BitSet cg=archive.bits(CG,1280), film=archive.bits(FILM,16384), movies=archive.bits(MOVIE,1024);
        BitSet choices=archive.has(CHOICE)?archive.bits(CHOICE,4096):new BitSet();
        int[] keys=new int[Math.min(GalleryStore.LIMIT,
            cg.cardinality()+film.cardinality()+movies.cardinality()+choices.cardinality()+256+previous.length)];
        int count=0;
        count=appendGallery(cg,1,cgStride,albums,keys,count);
        count=appendGallery(film,2,filmStride,scripts,keys,count);
        for(int i=0;i<256;i++)if(archive.data.getInt(archive.payload(MUSIC)+i*4)>0)keys[count++]=3<<28|i;
        // PC counts movies from one. Emberfall's gallery key is zero-based.
        for(int i=movies.nextSetBit(1);i>=0 && i<movieStride && i<=4096;i=movies.nextSetBit(i+1))
            keys[count++]=4<<28|(i-1);
        count=appendGallery(choices,5,32,1024,keys,count);
        int pcCount=count;
        for(int key:previous){
            if(count==keys.length)break;
            if(initial.live?!represented(archive,key):Arrays.binarySearch(keys,0,pcCount,key)<0)keys[count++]=key;
        }
        return Arrays.copyOf(keys,count);
    }

    private static int appendGallery(BitSet flags,int kind,int stride,int groups,int[] keys,int count){
        for(int bit=flags.nextSetBit(0);bit>=0 && bit<groups*stride;bit=flags.nextSetBit(bit+1))
            keys[count++]=kind<<28|(bit/stride<<16)|(bit%stride);
        return count;
    }

    private int galleryBit(int key){
        int group=key>>>16&0xfff, index=key&0xffff;
        switch(key>>>28){
        case 1:return group<albums && index<cgStride?group*cgStride+index:-1;
        case 2:return group<scripts && index<filmStride?group*filmStride+index:-1;
        case 3:return group==0 && index<256?index:-1;
        case 4:return group==0 && index+1<movieStride?index+1:-1;
        case 5:return group<1024 && index<32?group*32+index:-1;
        default:return -1;
        }
    }

    private boolean represented(Archive archive,int key){
        return galleryBit(key)>=0 && (key>>>28!=5 || archive.has(CHOICE));
    }

    private static int systemFieldOffset(Archive archive,int word){
        return archive.wordOffset(word<0?FLAG2:DATA,Math.abs(word));
    }
    private static int characterGainOffset(Archive archive,int group){return archive.wordOffset(DATA,356+group/6);}
    boolean storesAudioParameter(int id){
        if(id>=300&&id<316||id>=400&&id<416||id>=500&&id<572)return true;
        for(int[] field:SYSTEM_FIELDS)if(id==field[0])return field[1]>0||version==3;
        return false;
    }
    boolean storesAudioOwner(int id){return id==0?version==3:id>=1&&id<34;}
    boolean writeAudioSettings(int[] parameters,int[] owners) throws IOException {
        return writeSystemSettings(parameters,owners,Integer.MIN_VALUE,Integer.MIN_VALUE);
    }
    boolean writeSystemSettings(int[] parameters,int[] owners,int messageCursor,int mouseCursor) throws IOException {
        if(damaged)return false;
        try{
            Archive archive=liveArchive();
            boolean fresh=archive==null;
            if(fresh){
                archive=factoryArchive();
                if(archive==null)archive=create();
            }
            byte[] before=archive.bytes.clone();
            for(int i=0;i<parameters.length;i+=2){
                int id=parameters[i],value=parameters[i+1];
                if(id>=300&&id<316){
                    int offset=archive.wordOffset(DATA,448+id-300);
                    if(offset>=0)archive.data.putInt(offset,value);
                }else if(id>=400&&id<416){
                    int offset=archive.wordOffset(DATA,464+id-400);
                    if(offset>=0)archive.data.putInt(offset,value);
                }else if(id>=500&&id<572){
                    int group=id-500,offset=characterGainOffset(archive,group),shift=(group%6)*5;
                    if(offset>=0){
                        int packed=archive.data.getInt(offset);
                        archive.data.putInt(offset,(packed&~(31<<shift))|((value&31)<<shift));
                    }
                }else for(int[] field:SYSTEM_FIELDS)if(id==field[0]){
                    int offset=systemFieldOffset(archive,field[1]);
                    if(offset>=0)archive.data.putInt(offset,value);
                }
            }
            for(int i=0;i<owners.length;i+=2){
                int id=owners[i],value=owners[i+1];
                int offset=id==0?archive.wordOffset(FLAG2,14):id==1?archive.wordOffset(DATA,480):
                    id>=2&&id<34?archive.wordOffset(DATA,382+id):-1;
                if(offset>=0)archive.data.putInt(offset,value);
            }
            if(messageCursor!=Integer.MIN_VALUE){
                int offset=archive.wordOffset(DATA,481);
                if(offset>=0)archive.data.putInt(offset,messageCursor);
            }
            if(mouseCursor!=Integer.MIN_VALUE){
                int offset=archive.wordOffset(DATA,482);
                if(offset>=0)archive.data.putInt(offset,mouseCursor);
            }
            if(fresh||!Arrays.equals(before,archive.bytes)){archive.stamp();commit(file,archive.bytes);}
            return true;
        }catch(InvalidFile invalid){
            damaged=true;
            LOG.warning(invalid.getMessage());
            return false;
        }
    }

    void write(GameProgress.State state,int[] gallery) throws IOException {
        if(!damaged)try {
            Archive archive=liveArchive();
            boolean fresh=archive==null;
            if(fresh){
                archive=factoryArchive();
                if(archive==null)archive=create();
            }
            byte[] before=archive.bytes.clone();
            update(archive,state,gallery);
            if(fresh || !Arrays.equals(before,archive.bytes)){
                archive.stamp();
                commit(file,archive.bytes);
            }
        }catch(InvalidFile invalid){
            // Let the Android progress store keep working. The PC file is kept intact.
            damaged=true;
            LOG.warning(invalid.getMessage());
        }
        if(result!=null && !damagedResult){
            try {
                int[] previous=loadResults();
                int[] next=Arrays.copyOf(state.results,RESULT_WORDS);
                if(!Arrays.equals(previous,next)){
                    ByteBuffer bytes=ByteBuffer.allocate(RESULT_WORDS*4).order(ByteOrder.LITTLE_ENDIAN);
                    bytes.asIntBuffer().put(next);
                    commit(result,bytes.array());
                }
            }catch(InvalidFile invalid){damagedResult=true;LOG.warning(invalid.getMessage());}
        }
    }

    private void update(Archive archive,GameProgress.State state,int[] gallery){
        for(int id:state.read){
            int bit=readBit(id);
            if(bit>=0)archive.setBit(READ,bit);
        }
        for(int key:gallery){
            int bit=galleryBit(key);
            if(bit<0)continue;
            switch(key>>>28){
            case 1:archive.setBit(CG,bit);break;
            case 2:archive.setBit(FILM,bit);break;
            case 3:
                int offset=archive.payload(MUSIC)+bit*4;
                if(archive.data.getInt(offset)<=0)archive.data.putInt(offset,1);
                break;
            case 4:archive.setBit(MOVIE,bit);break;
            case 5:if(archive.has(CHOICE))archive.setBit(CHOICE,bit);break;
            default:break;
            }
        }
        archive.putWords(VAR,state.variables,0,variables);
        if(archive.has(ACHIEVEMENT))archive.putWords(ACHIEVEMENT,state.results,RESULT_WORDS,1024);
        archive.putText(archive.at(DATA)+56*4,16,15,state.text[0]);
        archive.putText(archive.at(DATA)+60*4,16,15,state.text[1]);
        if(archive.has(TEXT))for(int i=0;i<100;i++)
            archive.putText(archive.payload(TEXT)+i*64,64,32,state.text[i+2]);
        if(archive.has(TERM))archive.putWords(TERM,state.optional,0,TERM_WORDS);
        if(archive.has(VOICE_LINE))archive.putWords(VOICE_LINE,state.optional,TERM_WORDS,16384);
        int roles=NativeGame.LEGACY_OPTIONAL_PROGRESS_WORDS;
        for(int i=1;i<256;i++)if((state.optional[roles+i/32]&(1<<(i%32)))!=0){
            int offset=archive.payload(VOICE)+i*4;
            if(archive.data.getInt(offset)==0)archive.data.putInt(offset,1);
        }
    }

    private Archive liveArchive() throws IOException {
        byte[] bytes=read(file);
        if(bytes==null)return null;
        try{return new Archive(bytes,version);}
        catch(InvalidFile invalid){throw new InvalidFile(file,invalid);}
    }
    private Archive factoryArchive() throws IOException {
        try{
            byte[] bytes=read(factory);
            return bytes==null?null:new Archive(bytes,version);
        }catch(InvalidFile invalid){
            LOG.warning("Invalid PC factory defaults: "+factory.getFileName());
            return null;
        }
    }
    private int[] loadResults() throws IOException {
        if(result==null)return null;
        byte[] bytes=read(result);
        if(bytes==null)return null;
        if(bytes.length!=RESULT_WORDS*4)throw new InvalidFile(result,null);
        int[] words=new int[RESULT_WORDS];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(words);
        return words;
    }

    private Archive create() throws InvalidFile {
        int count=version==1?10:version==2?14:16;
        int size=0;
        for(int i=0;i<count;i++)size+=blockSize(i,version);
        ByteBuffer buffer=ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        for(int i=0,offset=0;i<count;i++){
            int length=blockSize(i,version);
            buffer.putInt(offset,length).putInt(offset+4,CODES[i]);
            byte[] label=label(i);
            System.arraycopy(label,0,buffer.array(),offset+16,label.length);
            offset+=length;
        }
        buffer.putInt(32,count);
        Archive archive=new Archive(buffer.array(),version);
        int base=archive.at(DATA);
        int[][] defaults={{18,800},{19,600},{20,16},{24,30},{25,70},{26,60},{27,80},{28,60},{29,80},{30,70},{31,80},
            {32,1},{33,1},{34,2},{44,90},{45,90},{46,90},{47,90},{48,1},{49,1},{50,1},{51,1},{53,90},{54,1},{55,50},
            {484,2},{486,2},{492,90},{493,1}};
        for(int[] field:defaults)archive.data.putInt(base+field[0]*4,field[1]);
        byte[] font={(byte)0x82,(byte)0x6c,(byte)0x82,(byte)0x72,32,(byte)0x83,0x53,(byte)0x83,0x56,(byte)0x83,0x62,(byte)0x83,0x4e};
        System.arraycopy(font,0,archive.bytes,base+64*4,font.length);
        return archive;
    }

    private static int blockSize(int index,int version){return CODES[index]==VAR && version==1?544:SIZES[index];}
    private static byte[] label(int index){
        return String.format(Locale.ROOT,"%-15s",LABELS[index]).getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] read(Path path) throws IOException {
        BasicFileAttributes attributes;
        try{attributes=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}
        catch(NoSuchFileException absent){return null;}
        if(!attributes.isRegularFile() || attributes.size()>MAX_BYTES)throw new InvalidFile(path,null);
        byte[] bytes=new byte[(int)attributes.size()];
        try(DataInputStream in=new DataInputStream(Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS))){
            in.readFully(bytes);
            if(in.read()!=-1)throw new IOException("PC save changed while reading: "+path.getFileName());
        }
        return bytes;
    }

    private static void commit(Path path,byte[] bytes) throws IOException {
        Path staged=Files.createTempFile(path.getParent(),".systemnnn-", ".tmp");
        try {
            try(FileOutputStream out=new FileOutputStream(staged.toFile())){
                out.write(bytes);
                out.getFD().sync();
            }
            Files.move(staged,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(staged);}
    }

    static final class InvalidFile extends IOException {
        InvalidFile(Path path,Throwable cause){super("Invalid PC global save: "+path.getFileName(),cause);}
        InvalidFile(){super("Invalid PC global save record");}
    }

    private static final class Archive {
        final byte[] bytes;
        final ByteBuffer data;
        final int[] offsets=new int[19];
        Archive(byte[] bytes,int version) throws InvalidFile {
            this.bytes=bytes;
            data=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            Arrays.fill(offsets,-1);
            if(bytes.length<64)throw new InvalidFile();
            int count=data.getInt(32);
            if(count<10 || count>CODES.length || version==2 && count>14 || version==3 && count!=16)throw new InvalidFile();
            int offset=0;
            for(int i=0;i<count;i++){
                int length=blockSize(i,version);
                if(length>bytes.length-offset || data.getInt(offset)!=length || data.getInt(offset+4)!=CODES[i])
                    throw new InvalidFile();
                byte[] label=label(i);
                for(int j=0;j<label.length;j++)if(bytes[offset+16+j]!=label[j])throw new InvalidFile();
                offsets[CODES[i]]=offset;
                offset+=length;
            }
            if(offset!=bytes.length)throw new InvalidFile();
        }
        boolean has(int code){return offsets[code]>=0;}
        int at(int code){return offsets[code];}
        int payload(int code){return at(code)+HEADER;}
        int wordOffset(int code,int word){
            if(!has(code)||word<HEADER/4)return -1;
            int base=at(code),length=data.getInt(base);
            return (long)word*4+4<=length?base+word*4:-1;
        }
        BitSet bits(int code,int size){return BitSet.valueOf(ByteBuffer.wrap(bytes,payload(code),size));}
        void setBit(int code,int bit){bytes[payload(code)+(bit>>>3)]|=(byte)(1<<(bit&7));}
        void getWords(int code,int[] target,int start,int count){
            for(int i=0,offset=payload(code);i<count;i++,offset+=4)target[start+i]=data.getInt(offset);
        }
        void putWords(int code,int[] source,int start,int count){
            for(int i=0,offset=payload(code);i<count;i++,offset+=4)data.putInt(offset,source[start+i]);
        }
        byte[] getText(int offset,int limit){
            int end=offset;
            while(end<offset+limit && bytes[end]!=0)end++;
            return Arrays.copyOfRange(bytes,offset,end);
        }
        void putText(int offset,int size,int limit,byte[] value){
            if(Arrays.equals(value,getText(offset,limit)))return;
            Arrays.fill(bytes,offset,offset+size,(byte)0);
            System.arraycopy(value,0,bytes,offset,Math.min(value.length,limit));
        }
        void stamp(){
            Calendar now=Calendar.getInstance();
            int[] date={now.get(Calendar.YEAR),now.get(Calendar.MONTH)+1,now.get(Calendar.DAY_OF_MONTH),
                now.get(Calendar.DAY_OF_WEEK)-1,now.get(Calendar.HOUR_OF_DAY),now.get(Calendar.MINUTE),
                now.get(Calendar.SECOND),now.get(Calendar.MILLISECOND)};
            for(int i=0;i<date.length;i++)data.putInt(payload(DATA)+i*4,date[i]);
            int count=at(DATA)+23*4;
            data.putInt(count,data.getInt(count)+1);
        }
    }
}
