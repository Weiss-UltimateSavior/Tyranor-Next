package com.tyranor.runtime.systemnnn;

import android.graphics.Bitmap;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Game-owned gallery lists and the assets for the currently visible page. */
final class GalleryCatalog {
    private static final int MAX_ANIMATION_FRAMES=16384, MAX_CG_TILES=32;
    private static final String ANIMATION_TAG="bg\\bganime";
    private static final java.nio.charset.Charset CP932=java.nio.charset.Charset.forName("windows-31j");
    enum Screen {
        OMAKE(15,"omake","title_bg2"), CG_GROUP(16,"selectcgchara","cgm_bg"),
        CG(17,"selectcg","cg_bg"), SCENE(18,"selectscene","scene_bg"),
        SCENE_GROUP(20,"selectscenechara","scenem_bg"), VIEW(23,"printcg",""),
        MUSIC(8,"listenBGM","bgm_bg"), MOVIE(26,"selectMovie","movie_bg");
        final int mode;
        final String config,background;
        Screen(int mode,String config,String background) {
            this.mode=mode; this.config=config; this.background=background;
        }
    }
    static final class Scene {
        final int scriptId;
        final String script;
        final int[] films;
        String thumbnail="";
        int hiddenLayers;

        Scene(int scriptId,String script,int[] films) {
            this.scriptId=scriptId; this.script=script; this.films=films;
        }
        boolean unlocked(int[] keys) {
            for (int film:films) if (film>0 && !contains(keys,2,scriptId,film-1)) return false;
            return true;
        }
        int[] filmLabels() {
            int[] labels=new int[films.length];
            for (int i=0;i<labels.length;i++) labels[i]=Math.abs(films[i])-1;
            return labels;
        }
    }
    static final class Song {
        final String title,name,lockedTitle,variable,duration;
        final int unlockNumber;

        Song(List<String> fields,int row,GameTextDecoder codec,long handle) throws IOException {
            title=decode(codec,fields.get(row)); name=fields.get(row+1);
            int minute=integer(fields.get(row+2),0),second=integer(fields.get(row+3),0);
            duration=String.format(Locale.ROOT,"%02d:%02d",minute,second);
            lockedTitle=fields.get(row+4).equals("@") ? "" : decode(codec,fields.get(row+4));
            String key=fields.get(row+5);
            unlockNumber=key.equals("*") ? 0 : (integer(key,0)>0 ? integer(key,0) : NativeGame.musicNumber(handle,raw(name)));
            variable=fields.get(row+6).equals("@") ? "" : fields.get(row+6);
        }
        boolean unlocked(int[] keys,long handle,boolean checkListen) {
            if (!variable.isEmpty()) return NativeGame.galleryVariable(handle,raw(variable))!=0;
            return !checkListen || unlockNumber<=0 || contains(keys,3,0,unlockNumber-1);
        }
    }
    static final class Movie {
        final int number;
        final File file;
        final String title;
        Movie(int number,File file,String title){this.number=number;this.file=file;this.title=title;}
        boolean unlocked(int[] keys){return number==0||contains(keys,4,0,number-1);}
    }
    static final class MovieList {
        final GameUiAssets.Config print;
        final Movie opening;
        final List<Movie> entries=new ArrayList<>();
        MovieList(File root,GameTextDecoder codec) throws IOException {
            print=GameUiAssets.readConfig(root,"printMovie",codec);
            opening=openingMovie(root,print);
            byte[] bytes=listBytes(root,"spt","movielist.xtx");
            if(bytes==null)return;
            String data;
            boolean unicode=true;
            try { data=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(); }
            catch(CharacterCodingException e){data=new String(bytes,StandardCharsets.ISO_8859_1);unicode=false;}
            if(data.startsWith("\ufeff"))data=data.substring(1);
            List<String> fields=tokens(data,!unicode);
            if(fields.size()%2!=0||fields.size()/2>4096)throw new IOException("动画鉴赏列表格式无效");
            for(int i=0;i<fields.size();i+=2){
                String name=fields.get(i),title=fields.get(i+1);
                entries.add(new Movie(i/2+1,movieFile(root,unicode?name:GameResourcePaths.decodeFilename(raw(name))),unicode?title:decode(codec,title)));
            }
        }
        Movie find(int number){return number==0?opening:number>0&&number<=entries.size()?entries.get(number-1):null;}
    }
    static Movie openingMovie(File root,GameUiAssets.Config print) throws IOException {
        String name=GameResourcePaths.decodeFilename(raw(print.value("movieFileName","default.wmv")));
        return new Movie(0,movieFile(root,name),"开场动画");
    }
    static final class ScrollAxis {
        final int direction,tiles,time,wait;
        ScrollAxis(int direction,int tiles,int time,int wait) {
            this.direction=direction;this.tiles=tiles;this.time=time;this.wait=wait;
        }
    }
    static final class CgVariant {
        final String name;
        final int unlockIndex,start,frames;
        final boolean difference,gore;
        final ScrollAxis x,y;

        CgVariant(String name,int unlockIndex,boolean difference,boolean gore,int start,int frames,ScrollAxis x,ScrollAxis y) {
            this.name=name;this.unlockIndex=unlockIndex;this.difference=difference;this.gore=gore;
            this.start=start;this.frames=frames;this.x=x;this.y=y;
        }
        boolean unlocked(int[] keys,int group) {return contains(keys,1,group,unlockIndex);}
        String picture(boolean goreEnabled,String goreTag,int tile) {
            String path=GameUiAssets.imageKey(name);
            if(path.isEmpty())return path;
            // CommonPrintCG prefixes bare image names with their first two bytes.
            if(path.length()>=2&&path.indexOf('/')<0)path=path.substring(0,2)+"/"+path;
            return path+(gore&&goreEnabled?goreTag:"")+(tile==0?"":"_"+(tile+1));
        }
        String animationPicture(boolean goreEnabled,String goreTag,int frame) {
            return ANIMATION_TAG+(gore&&goreEnabled?goreTag:"")+(start+frame);
        }
        int frameAt(long tick,int frameHold,int bufferMax) {
            if(frames==0)return -1;
            int offset=(int)(tick/frameHold%frames);
            // The original clamps loads and reads to the last allocated slot.
            // If the sequence exceeds that capacity, the last load owns that slot.
            return offset>=bufferMax-1&&frames>bufferMax?frames-1:offset;
        }
    }
    private static final class Cg {
        final List<CgVariant> variants=new ArrayList<>();
        List<CgVariant> unlocked(int[] keys,int group) {
            List<CgVariant> result=new ArrayList<>();
            for (CgVariant variant:variants) if (variant.unlocked(keys,group)) result.add(variant);
            return result;
        }
    }
    static final class CgImage {
        final CgVariant variant;
        final int frameHold,bufferMax,firstFrame,columns;
        final boolean animated;
        final List<Bitmap> tiles;

        CgImage(CgVariant variant,boolean animated,int frameHold,int bufferMax,int firstFrame,int columns,List<Bitmap> tiles) {
            this.variant=variant;this.animated=animated;this.frameHold=frameHold;this.bufferMax=bufferMax;
            this.firstFrame=firstFrame;this.columns=columns;this.tiles=Collections.unmodifiableList(tiles);
        }
        int frameAt(long tick){return variant.frameAt(tick,frameHold,bufferMax);}
        int nextFrame(long tick){return frameAt(tick-tick%frameHold+frameHold);}
    }
    static final class Cell {
        final int index;
        final String label,picture,detail;
        final boolean enabled,animated;

        Cell(int index,String label,String picture,String detail,boolean enabled) {
            this(index,label,picture,detail,enabled,false);
        }
        Cell(int index,String label,String picture,String detail,boolean enabled,boolean animated) {
            this.index=index; this.label=label; this.picture=picture; this.detail=detail; this.enabled=enabled;this.animated=animated;
        }
    }
    static final class Page {
        final Screen screen;
        final GameUiAssets.Config config;
        final int group,page,pages,index,columns,rows,percent,musicVolume;
        int musicState;
        final boolean cgAvailable,sceneAvailable,musicAvailable,movieAvailable,cgGroups,sceneGroups;
        final String background;
        final List<Cell> cells;
        final List<MenuModeButton> modeButtons;
        final Map<String,Bitmap> images;
        final CgImage cg;

        Page(Screen screen,GameUiAssets.Config config,int group,int page,int pages,int index,
             int columns,int rows,int percent,int musicVolume,String background,List<Cell> cells,
             Map<String,Bitmap> images,CgImage cg,List<MenuModeButton> modeButtons,GalleryCatalog catalog) {
            this.screen=screen; this.config=config; this.group=group; this.page=page; this.pages=pages; this.index=index;
            this.columns=columns; this.rows=rows; this.percent=percent; this.musicVolume=musicVolume;
            this.background=background; this.cells=cells; this.images=images;this.cg=cg;this.modeButtons=modeButtons;
            cgAvailable=catalog.cgAvailable(); sceneAvailable=catalog.sceneAvailable(); musicAvailable=catalog.musicAvailable();
            movieAvailable=catalog.movieAvailable();
            cgGroups=catalog.cgGroups(); sceneGroups=catalog.sceneGroups();
        }
    }

    private final EnumMap<Screen,GameUiAssets.Config> configs=new EnumMap<>(Screen.class);
    private final List<List<Cg>> albums=new ArrayList<>();
    private final List<List<Scene>> scenes=new ArrayList<>();
    private final List<String> sceneNames=new ArrayList<>();
    private final List<Song> songs=new ArrayList<>();
    private final MovieList movies;
    private final boolean showOpening;
    private final boolean animeEnabled;
    private final int animeBufferMax;
    private final LinkedHashMap<String,Bitmap> sprites=new LinkedHashMap<>(64,.75f,true);
    private final Set<String> missingSprites=new LinkedHashSet<>();
    private static final long SPRITE_CACHE_BYTES=24L*1024*1024;
    private long spriteBytes;

    GalleryCatalog(File root,GameTextDecoder codec,long handle,MovieList movies) throws IOException {
        for (Screen screen:Screen.values()) configs.put(screen,GameUiAssets.readConfig(root,screen.config,codec));
        this.movies=movies;
        GameUiAssets.Config game=GameUiAssets.readConfig(root,"game",codec);
        animeEnabled=game.number("animeEffectEnable",0)!=0;
        animeBufferMax=bound(game.number("animeBufferMax",60),1,MAX_ANIMATION_FRAMES);
        showOpening=config(Screen.MOVIE).number("UseOpeningMovie",0)!=0;
        if (config(Screen.OMAKE).number("cgModeExistFlag",1)!=0) readCg(root);
        if (config(Screen.OMAKE).number("sceneModeExistFlag",1)!=0) readScenes(root,codec);
        if (config(Screen.OMAKE).number("bgmModeExistFlag",1)!=0) {
            List<String> rows=tokens(root,"nya","listenbgmlist.xtx");
            if (rows.size()%8!=0 || rows.size()/8>4096) throw new IOException("音乐鉴赏列表格式无效");
            for (int row=0;row<rows.size();row+=8) songs.add(new Song(rows,row,codec,handle));
        }
    }
    GameUiAssets.Config config(Screen screen) { return configs.get(screen); }
    private boolean cgAvailable() { return !albums.isEmpty(); }
    private boolean sceneAvailable() { return !scenes.isEmpty(); }
    private boolean musicAvailable() { return !songs.isEmpty(); }
    private boolean movieAvailable() {
        return config(Screen.OMAKE).number("movieModeExistFlag",0)!=0 &&
            config(Screen.OMAKE).number("basicButtonNumber",4)>=5 && (showOpening || !movies.entries.isEmpty());
    }
    private boolean cgGroups() { return albums.size()>1 && config(Screen.OMAKE).number("autoCGCharaFlag",0)==0; }
    private boolean sceneGroups() { return scenes.size()>1 && config(Screen.OMAKE).number("autoSceneCharaFlag",0)==0; }
    Scene scene(int group,int index) { return scenes.get(group).get(index); }
    Song song(int index) { return songs.get(index); }
    Movie movie(int number) { return number==0&&!showOpening?null:movies.find(number); }
    int songCount() { return songs.size(); }
    void releaseImages() { sprites.clear(); missingSprites.clear(); spriteBytes=0; }

    private void readCg(File root) throws IOException {
        int total=0;
        for (int group=1;group<=80;group++) {
            byte[] bytes=listBytes(root,"spt",String.format(Locale.ROOT,"cglist%02d.xtx",group));
            if(bytes==null)break;
            List<String> names=cgTokens(new String(bytes,StandardCharsets.ISO_8859_1));
            if (names.isEmpty()) break;
            total+=names.size();
            if (total>16384) throw new IOException("CG 列表超过容量");
            List<Cg> blocks=new ArrayList<>();
            for (int i=0;i<names.size();i++) {
                CgVariant variant=cgVariant(names.get(i),i);
                if (!variant.difference || blocks.isEmpty()) blocks.add(new Cg());
                blocks.get(blocks.size()-1).variants.add(variant);
            }
            albums.add(blocks);
        }
    }
    static CgVariant cgVariant(String raw,int unlockIndex) {
        boolean gore=raw.startsWith("*");
        boolean difference=raw.startsWith("@",gore?1:0);
        int pos=cgPrefixEnd(raw,0);
        int start=0,frames=0;
        if (raw.startsWith("#",pos)) {
            int slash=raw.indexOf('/',pos+1);
            if(slash>=0){
                int startEnd=digitsEnd(raw,pos+1),countStart=startEnd+1,countEnd=digitsEnd(raw,countStart);
                if(startEnd>pos+1&&countEnd>countStart&&countEnd<=slash){
                    int from=integer(raw.substring(pos+1,startEnd),-1),count=integer(raw.substring(countStart,countEnd),0);
                    if(from>=0&&count>0&&count<=MAX_ANIMATION_FRAMES&&(long)from+count-1<=Integer.MAX_VALUE){
                        start=from;frames=count;
                    }
                }
                pos=slash+1;
            }
        }
        String scroll="";
        if(raw.startsWith("[",pos)){
            int end=raw.indexOf(']',pos+1);
            if(end>=0){
                scroll=new String(raw(raw.substring(pos+1,end)),CP932);
                pos=end+1;
            }
        }
        return new CgVariant(raw.substring(pos),unlockIndex,difference,gore,start,frames,
            scrollAxis(scroll,true),scrollAxis(scroll,false));
    }
    private static ScrollAxis scrollAxis(String spec,boolean horizontal){
        int direction=-1,tiles=1,time=-1,wait=-1;
        boolean matches=false;
        int field=0;
        for(int i=0;i<spec.length();){
            char c=spec.charAt(i);
            int end=digitsEnd(spec,i);
            if(end>i){
                if(matches){
                    int value=integer(spec.substring(i,end),-1);
                    if(field==0&&value>=1)tiles=Math.min(value,MAX_CG_TILES);
                    else if(field==1&&value>=1)time=value;
                    else if(field==2&&value>=0)wait=value;
                }
                i=end;continue;
            }
            if(c==':'||c=='_'){field=c==':'?1:2;i++;continue;}
            boolean left=c=='<'||c=='\u2190',right=c=='>'||c=='\u2192';
            boolean up=c=='^'||c=='\u2191',down=c=='|'||c=='\u2193';
            if(left||right||up||down){
                matches=horizontal?left||right:up||down;
                if(matches)direction=right||down?1:0;
                field=0;
            }
            i++;
        }
        return new ScrollAxis(direction,tiles,time,wait);
    }
    private static int cgPrefixEnd(String raw,int start){
        int pos=start;
        if(raw.startsWith("*",pos))pos++;
        if(raw.startsWith("@",pos))pos++;
        if(raw.startsWith("!",pos))pos++;
        return pos;
    }
    private static int digitsEnd(String value,int from){
        int end=from;
        while(end<value.length()&&value.charAt(end)>='0'&&value.charAt(end)<='9')end++;
        return end;
    }
    private static int bound(int n,int low,int high){return Math.max(low,Math.min(high,n));}
    private void readScenes(File root,GameTextDecoder codec) throws IOException {
        File data=gameFile(root,"spt","scene.dat");
        if (data==null) return;
        List<String> project=tokens(root,"spt","project.xtx");
        byte[] raw=GameTextDecoder.readBounded(data,1024*1024);
        ByteBuffer in=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        if (raw.length<8 || raw.length%4!=0 || in.getInt()!=raw.length)
            throw new IOException("scene.dat 文件长度无效");
        int groups=count(in,80);
        for (int group=0;group<groups;group++) {
            int entries=count(in,4096);
            List<Scene> row=new ArrayList<>();
            for (int i=0;i<entries;i++) {
                if (in.remaining()<8) throw new IOException("scene.dat 场景记录不完整");
                long scriptId=Math.abs((long)in.getInt());
                int filmCount=count(in,4096);
                if (scriptId>=project.size() || scriptId>4095 || filmCount==0)
                    throw new IOException("scene.dat 场景引用无效");
                int[] films=new int[filmCount];
                for (int j=0;j<filmCount;j++) {
                    int film=in.getInt();
                    if (film==0 || film>65536 || film< -65536) throw new IOException("scene.dat 片段编号无效");
                    films[j]=film;
                }
                row.add(new Scene((int)scriptId,project.get((int)scriptId),films));
            }
            scenes.add(row);
            sceneNames.add("回想分类 "+(group+1));
        }
        if (in.hasRemaining()) throw new IOException("scene.dat 尾部无效");
        int group=-1,index=0;
        Scene current=null;
        for (String token:tokens(root,"nya","sceneVoice.xtx")) {
            if (token.startsWith("[")) {
                group++; index=0; current=null;
                if (group<sceneNames.size() && token.endsWith("]"))
                    sceneNames.set(group,decode(codec,token.substring(1,token.length()-1)));
            } else if (token.startsWith("@") && group>=0 && group<scenes.size()) {
                List<Scene> list=scenes.get(group);
                current=index<list.size()?list.get(index++):null;
                if (current!=null) current.thumbnail=token.substring(1);
            } else if (token.startsWith("#") && current!=null) {
                int layer=integer(token.substring(1),-1);
                if (layer>=0 && layer<32) current.hiddenLayers|=1<<layer;
            }
        }
    }
    private static int count(ByteBuffer in,int limit) throws IOException {
        if (in.remaining()<4) throw new IOException("scene.dat 记录不完整");
        int count=in.getInt();
        if (count<0 || count>limit || count>in.remaining()/4) throw new IOException("scene.dat 数量无效");
        return count;
    }
    static byte[] raw(String text) { return text.getBytes(StandardCharsets.ISO_8859_1); }
    private static String decode(GameTextDecoder codec,String raw) throws IOException { return codec.decodeConfiguration(raw(raw)); }
    private static int integer(String value,int fallback) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException e) { return fallback; }
    }
    private static boolean contains(int[] keys,int kind,int group,int index) {
        return group>=0 && group<=4095 && index>=0 && index<=65535 &&
            Arrays.binarySearch(keys,(kind<<28)|(group<<16)|index)>=0;
    }
    private static File gameFile(File root,String folder,String name) {
        return GameResourcePaths.file(root,folder,name);
    }
    private static File movieFile(File root,String name) throws IOException {
        if (name==null || new File(name).isAbsolute()) return null;
        File file=gameFile(root,"movie",name);
        if (file==null) return null;
        file=file.getCanonicalFile();
        return file.toPath().startsWith(root.getCanonicalFile().toPath()) && file.length()>0 &&
            file.length()<=2L*1024*1024*1024?file:null;
    }
    private static boolean spacing(char c) { return c==' ' || c=='\t' || c=='\r' || c=='\n'; }
    private static byte[] listBytes(File root,String folder,String name) throws IOException {
        File file=gameFile(root,folder,name);
        boolean encrypted=file==null;
        if (encrypted) file=gameFile(root,folder,name.substring(0,name.length()-4)+".fxf");
        if (file==null) return null;
        byte[] bytes=GameTextDecoder.readBounded(file,1024*1024);
        if (encrypted) for (int i=0;i<bytes.length;i++) bytes[i]^=(byte)0xff;
        return bytes;
    }
    private static List<String> tokens(File root,String folder,String name) throws IOException {
        byte[] bytes=listBytes(root,folder,name);
        return bytes==null?Collections.emptyList():tokens(new String(bytes,StandardCharsets.ISO_8859_1),true);
    }
    private static List<String> tokens(String data,boolean cp932) throws IOException {
        return tokens(data,cp932,false);
    }
    static List<String> cgTokens(String data) throws IOException {
        return tokens(data,true,true);
    }
    private static boolean animationComma(String data,int start,int comma) {
        int prefix=cgPrefixEnd(data,start);
        if(!data.startsWith("#",prefix)||digitsEnd(data,prefix+1)!=comma||prefix+1==comma)return false;
        int end=digitsEnd(data,comma+1);
        return end>comma+1&&end<data.length()&&data.charAt(end)=='/';
    }
    private static List<String> tokens(String data,boolean cp932,boolean cg) throws IOException {
        if (data.indexOf('\0')>=0) throw new IOException("鉴赏列表含有无效字节");
        List<String> fields=new ArrayList<>();
        int pos=0;
        while (pos<data.length()) {
            if (data.charAt(pos)==',') pos++;
            while (pos<data.length() && spacing(data.charAt(pos))) pos++;
            if (pos==data.length()) break;
            int start=pos;
            while (pos<data.length()) {
                char c=data.charAt(pos);
                if (spacing(c) || c==',' && (!cg || !animationComma(data,start,pos))) break;
                boolean lead=cp932&&(c>=0x81 && c<=0x9f || c>=0xe0 && c<=0xfc);
                pos+=lead && pos+1<data.length()?2:1;
            }
            if (pos-start>255 || fields.size()==65536) throw new IOException("鉴赏列表超出格式上限");
            fields.add(data.substring(start,pos));
        }
        return fields;
    }

    Page page(Screen screen,int group,int requested,int index,long handle,int musicVolume) throws IOException {
        GameUiAssets.Config cfg=config(screen);
        int[] keys=NativeGame.galleryState(handle);
        int columns=1,rows=1,size=0,unlocked=0;
        List<Cell> cells=new ArrayList<>();
        CgImage cgImage=null;
        String bg=GameUiAssets.systemImageKey(cfg.value("filenameBG",screen.background));
        if (screen==Screen.MOVIE) {
            columns=Math.max(1,Math.min(16,cfg.number("blockNumberX",1)));
            rows=Math.max(1,Math.min(16,cfg.number("blockNumberY",1)));
            size=movies.entries.size()+(showOpening?1:0);
        } else if (screen==Screen.CG || screen==Screen.SCENE) {
            boolean cg=screen==Screen.CG;
            columns=Math.max(1,Math.min(16,cfg.number("blockNumberX",cg?6:3)));
            rows=Math.max(1,Math.min(16,cfg.number("blockNumberY",cg?5:8)));
            size=cg?albums.get(group).size():scenes.get(group).size();
        } else if (screen==Screen.MUSIC) {
            size=songs.size(); rows=Math.max(1,Math.min(32,cfg.number("musicPrintNumber",18)));
        } else if (screen==Screen.CG_GROUP || screen==Screen.SCENE_GROUP) {
            size=screen==Screen.CG_GROUP?albums.size():scenes.size(); rows=Math.max(1,size);
        }
        int perPage=columns*rows,pages=Math.max(1,(size+perPage-1)/perPage);
        int page=Math.max(0,Math.min(pages-1,requested)),from=page*perPage,to=Math.min(size,from+perPage);
        if (screen==Screen.VIEW) {
            List<CgVariant> images=albums.get(group).get(index).unlocked(keys,group);
            if (images.isEmpty()) throw new IOException("这张 CG 尚未解锁");
            pages=images.size(); page=Math.floorMod(requested,pages);
            CgVariant variant=images.get(page);
            cgImage=cgImage(handle,variant,cfg);
            cells.add(new Cell(index,"CG",variant.name,"",true));
        } else if (screen==Screen.MOVIE) {
            for (int i=0;i<size;i++) {
                Movie movie=movie(i+(showOpening?0:1));
                boolean open=movie.unlocked(keys);
                if (open) unlocked++;
                if (i<from || i>=to) continue;
                String prefix=cfg.value(open?"prefixOn":"prefixOff",open?"ta_sm_movie_on":"ta_sm_movie_off");
                cells.add(new Cell(movie.number,movie.title,GameUiAssets.systemImageKey(prefix+movie.number),
                    open&&movie.file==null?"影片文件缺失":"",open&&movie.file!=null));
            }
        } else if (screen==Screen.CG) {
            List<Cg> album=albums.get(group);
            for (int i=0;i<size;i++) {
                Cg block=album.get(i);
                List<CgVariant> images=block.unlocked(keys,group);
                unlocked+=images.size();
                if (i<from || i>=to) continue;
                boolean open=!images.isEmpty();
                String name=(open?images.get(0):block.variants.get(0)).name;
                cells.add(new Cell(i,"CG "+(i+1),name.isEmpty()?"":"sys/sm/sm"+name,
                    images.size()+"/"+block.variants.size(),open,block.variants.get(0).frames>0));
            }
            size=album.stream().mapToInt(block -> block.variants.size()).sum();
        } else if (screen==Screen.SCENE) {
            List<Scene> groupScenes=scenes.get(group);
            for (int i=0;i<size;i++) {
                Scene scene=groupScenes.get(i);
                boolean open=scene.unlocked(keys);
                if (open) unlocked++;
                if (i>=from && i<to) cells.add(new Cell(i,"回想 "+(i+1),scene.thumbnail.isEmpty()?"":"sys/sc/"+scene.thumbnail,"",open));
            }
        } else if (screen==Screen.MUSIC) {
            boolean check=cfg.number("checkListen",0)!=0;
            for (int i=from;i<to;i++) {
                Song song=songs.get(i);
                boolean open=song.unlocked(keys,handle,check);
                String locked=cfg.number("useCannotByList",0)!=0&&!song.lockedTitle.isEmpty()?song.lockedTitle:cfg.text("cannotMusicName","???");
                cells.add(new Cell(i,open?song.title:locked,"",song.duration,open));
            }
        } else if (screen==Screen.CG_GROUP || screen==Screen.SCENE_GROUP) {
            for (int i=0;i<size;i++) cells.add(new Cell(i,screen==Screen.CG_GROUP?"CG 分类 "+(i+1):sceneNames.get(i),"","",true));
        }
        int basic=DialogueLayout.clamp(cfg.number("basicButtonNumber",4),0,64);
        List<MenuModeButton> modes=screen==Screen.OMAKE?MenuModeButton.read(cfg,handle,basic,basic>=5?5:4):Collections.emptyList();
        Set<String> names=new LinkedHashSet<>();
        names.add(bg);
        for(MenuModeButton mode:modes)names.add(GameUiAssets.systemImageKey(mode.setup.art.image));
        if (screen==Screen.CG && cfg.number("animePrintFlag",0)!=0)
            names.add(GameUiAssets.systemImageKey(cfg.value("fileNameAnime","ta_futa1")));
        names.addAll(cfg.imageNames());
        if(screen!=Screen.VIEW)
            for (Cell cell:cells) if (screen==Screen.MOVIE || cell.enabled || cfg.number("picMustPrintPercent",0)!=0) names.add(cell.picture);
        Map<String,Bitmap> images=new HashMap<>();
        long pageBytes=0;
        if(cgImage!=null)
            for(Bitmap tile:cgImage.tiles)pageBytes+=tile.getAllocationByteCount();
        for (String name:names) {
            if (pageBytes>=SPRITE_CACHE_BYTES) break;
            String key=GameUiAssets.imageKey(name);
            if (images.containsKey(key)) continue;
            Bitmap sprite=sprite(handle,name,screen==Screen.VIEW);
            if (sprite!=null && pageBytes+sprite.getAllocationByteCount()<=SPRITE_CACHE_BYTES) {
                images.put(key,sprite); pageBytes+=sprite.getAllocationByteCount();
            }
        }
        int percent=size==0?0:unlocked*100/size;
        return new Page(screen,cfg,group,page,pages,index,columns,rows,percent,musicVolume,bg,cells,images,cgImage,modes,this);
    }
    private CgImage cgImage(long handle,CgVariant variant,GameUiAssets.Config cfg) throws IOException {
        boolean animate=animeEnabled&&variant.frames>0;
        int frameHold=Math.max(1,cfg.number("animeFrame",1));
        int firstFrame=animate?variant.frameAt(0,frameHold,animeBufferMax):-1;
        String goreTag=cfg.value("goreCGTag","_gore");
        // Keep the asset naming metadata. The separate PC gore setting is
        // not stored by the Android runtime, so it uses the normal variants.
        Bitmap first=animate?sprite(handle,variant.animationPicture(false,goreTag,firstFrame),true):null;
        if(first==null){
            animate=false;
            first=sprite(handle,variant.picture(false,goreTag,0),true);
            firstFrame=-1;
        }
        if(first==null)throw new IOException("无法读取 CG 图像");
        List<Bitmap> tiles=new ArrayList<>();
        tiles.add(first);
        int columns=animate?1:variant.x.tiles;
        int rows=animate?1:Math.min(variant.y.tiles,MAX_CG_TILES/columns);
        long bytes=first.getAllocationByteCount();
        for(int tile=1;tile<columns*rows;tile++){
            Bitmap part=sprite(handle,variant.picture(false,goreTag,tile),true);
            if(part==null||bytes+part.getAllocationByteCount()>SPRITE_CACHE_BYTES){
                Log.w("SystemNNN","CG tiles unavailable or exceed image budget: "+variant.name);
                return new CgImage(variant,false,frameHold,animeBufferMax,-1,1,Collections.singletonList(first));
            }
            tiles.add(part);bytes+=part.getAllocationByteCount();
        }
        return new CgImage(variant,animate,frameHold,animeBufferMax,firstFrame,columns,tiles);
    }
    Bitmap cgFrame(Page page,int frame,long handle) {
        CgImage cg=page.cg;
        if(cg==null||!cg.animated||frame<0||frame>=cg.variant.frames)return null;
        return sprite(handle,cg.variant.animationPicture(false,page.config.value("goreCGTag","_gore"),frame),true);
    }
    private Bitmap sprite(long handle,String name,boolean large) {
        if (name.isEmpty()) return null;
        String key=GameUiAssets.imageKey(name);
        Bitmap cached=sprites.get(key);
        String missing=(large?"L:":"S:")+key;
        if (cached!=null || missingSprites.contains(missing)) return cached;
        try {
            int[] pixels=large?NativeGame.gallerySprite(handle,raw(name)):NativeGame.titleSprite(handle,raw(name));
            if (pixels==null || pixels.length<2) {
                if(missingSprites.size()>=1024){
                    Iterator<String> oldest=missingSprites.iterator();
                    oldest.next();oldest.remove();
                }
                missingSprites.add(missing);return null;
            }
            int w=pixels[0],h=pixels[1];
            if (w<=0 || h<=0 || (long)w*h!=pixels.length-2L) return null;
            Bitmap image=Bitmap.createBitmap(pixels,2,w,w,h,Bitmap.Config.ARGB_8888);
            sprites.put(key,image); spriteBytes+=image.getAllocationByteCount();
            Iterator<Bitmap> oldest=sprites.values().iterator();
            while (spriteBytes>SPRITE_CACHE_BYTES && oldest.hasNext()) {
                spriteBytes-=oldest.next().getAllocationByteCount(); oldest.remove();
            }
            return image;
        } catch (RuntimeException | OutOfMemoryError e) {
            Log.w("SystemNNN","Gallery asset unavailable: "+name,e);
            return null;
        }
    }
}
