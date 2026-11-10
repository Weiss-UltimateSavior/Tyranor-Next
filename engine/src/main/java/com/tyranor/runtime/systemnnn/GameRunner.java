package com.tyranor.runtime.systemnnn;

import android.os.Handler;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import com.core.engine.R;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** One worker owns the session, controls and deterministic input journal. */
final class GameRunner {
    static final class Frame {
        static final int DRAW_NO_MESSAGE=0,DRAW_WINDOW=1,DRAW_RETAINED_MESSAGE=2,DRAW_LONG_BACKGROUND=3;
        final int wait,width,height,line,word,messageId,windowType,fontType,scriptCursor,command,layoutCommand,drawMessage,frameMs,placeMode;
        final boolean windowVisible,nameWindow,optionsAllowed;
        final String script,speaker,body;
        final DialogueMarkup displayBody;
        final String[] choices,choiceMessages;
        final int[] choiceInfo,places,placeCharacters;
        final Bitmap[] placePortraits;
        final Modal modal;
        final boolean alreadyRead;
        Frame(int wait,int width,int height,String script,int line,int word,String speaker,String body,DialogueMarkup displayBody,String[] choices,boolean read,
              int messageId,int windowType,int fontType,int scriptCursor,boolean windowVisible,boolean nameWindow,boolean optionsAllowed,int command,int layoutCommand,int drawMessage,int frameMs,
              String[] choiceMessages,int[] choiceInfo,int[] places,int placeMode,int[] placeCharacters,Bitmap[] placePortraits,Modal modal) {
            this.wait=wait;this.width=width;this.height=height;this.script=script;this.line=line;this.word=word;
            this.speaker=speaker;this.body=body;this.choices=choices;this.alreadyRead=read;
            this.displayBody=displayBody;
            this.messageId=messageId;this.windowType=windowType;this.fontType=fontType;this.scriptCursor=scriptCursor;this.windowVisible=windowVisible;
            this.nameWindow=nameWindow;this.optionsAllowed=optionsAllowed;this.command=command;this.layoutCommand=layoutCommand;this.drawMessage=drawMessage;this.frameMs=frameMs;
            this.choiceMessages=choiceMessages;this.choiceInfo=choiceInfo;this.places=places;
            this.placeMode=placeMode;this.placeCharacters=placeCharacters;this.placePortraits=placePortraits;this.modal=modal;
        }
        boolean hasDialogueText(){return wait==NativeGame.DIALOGUE||drawMessage==DRAW_RETAINED_MESSAGE;}
        boolean showsDialogue(){return hasDialogueText()||drawMessage==DRAW_WINDOW;}
        String legacyReadKey(byte[] rawScript,String legacyBody){
            return new String(rawScript,StandardCharsets.UTF_8)+"\u0001"+word+"\u0001"+
                (legacyBody.length()>8192?legacyBody.substring(0,8192):legacyBody);
        }
    }
    static final class Modal {
        static final int TITLE=4,OMAKE=15,SELECT_ZUKAN=24,NAME_INPUT=29,SELECT_HEROIN=31,PRINT_RESULT=33,SELECT_CHART=34,
            SELECT_PLACE2=NativeGame.SELECT_PLACE2_MODE,SELECT_HINT=36,PRINT_STATUS=38,PRINT_HINT=39,
            SELECT_OBJECT=41,PRINT_ACHIEVEMENT=42,PRINT_TERM=43,LISTEN_VOICE=44,KANJI_INPUT=46,PRINT_ITEM=47,SHOP=48,PRINT_PARTY=49;
        static final int BACK=0,CHOOSE=1,PAGE=2,HOVER=3,CONFIRM=4,GROUP=5,RESUME=6,SCROLL=7,COMMIT=8;
        static final int LEAVE_READY=4,ENTER_STORY=5;
        static final int ENABLED=1,NEW=2,SELECTED=4,LOCKED=8,SOLD_OUT=16,PURCHASE_ENABLED=32;
        static final class Row {
            final int id,flags;
            final String label,asset,detail;
            Row(int id,int flags,String label,String asset,String detail){
                this.id=id;this.flags=flags;this.label=label;this.asset=asset;this.detail=detail;
            }
            boolean enabled(){return (flags&ENABLED)!=0;}
        }
        final int request,mode,returnMode,page,pages,group,selection,balance;
        final int[] arguments,byteLimits;
        final byte[][] text;
        final Row[] rows;
        final GameUiAssets.ModeAssets assets;
        final GameTextDecoder decoder;
        final ChartView chart;
        final String problem;
        Modal(int[] info,int[] byteLimits,byte[][] text,int[] view,Row[] rows,GameUiAssets.ModeAssets assets,
              GameTextDecoder decoder,ChartView chart,String problem){
            request=info[0];mode=info[1];returnMode=info[2];
            page=view[0];pages=view[1];group=view[2];selection=view[3];balance=view[4];
            arguments=Arrays.copyOfRange(info,3,info.length);
            this.byteLimits=byteLimits;this.text=text;this.rows=rows;this.assets=assets;this.decoder=decoder;this.chart=chart;this.problem=problem;
        }
        boolean textInput(){return mode==NAME_INPUT||mode==KANJI_INPUT;}
        boolean catalog(){return mode==PRINT_TERM||mode==LISTEN_VOICE;}
        boolean progressView(){return mode==PRINT_RESULT||mode==PRINT_ACHIEVEMENT;}
        boolean advancedSelection(){return mode==SELECT_HEROIN||mode==SELECT_OBJECT;}
        boolean inventory(){return mode==PRINT_ITEM||mode==SHOP;}
        boolean statusView(){return mode==PRINT_STATUS||mode==PRINT_PARTY;}
        static boolean systemMenuMode(int mode){
            switch(mode){
                case SELECT_ZUKAN:case NAME_INPUT:case SELECT_HEROIN:case PRINT_RESULT:case SELECT_CHART:case SELECT_PLACE2:
                case SELECT_HINT:case PRINT_STATUS:case PRINT_HINT:case SELECT_OBJECT:case PRINT_ACHIEVEMENT:
                case PRINT_TERM:case LISTEN_VOICE:case KANJI_INPUT:case PRINT_ITEM:case SHOP:case PRINT_PARTY:return true;
                default:return false;
            }
        }
    }
    static final class ModalUpdate {
        final int status,wait,returnMode;
        final Modal modal;
        ModalUpdate(int status,int wait,int returnMode,Modal modal){
            this.status=status;this.wait=wait;this.returnMode=returnMode;this.modal=modal;
        }
    }
    static final class BacklogLine {
        final String speaker,body;
        final List<byte[]> voices;
        final int color;
        final long jump;
        BacklogLine(String speaker,String body,List<byte[]> voices,int color,long jump){
            this.speaker=speaker;this.body=body;this.voices=voices;this.color=color;this.jump=jump;
        }
    }
    static final class Capture {
        final int[] pixels;
        final int width,height;
        Capture(int[] pixels,int width,int height){this.pixels=pixels;this.width=width;this.height=height;}
        Bitmap bitmap(){return Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888);}
        Bitmap mutableBitmap(){
            Bitmap frame=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
            frame.setPixels(pixels,0,width,0,0,width,height);
            return frame;
        }
    }
    interface Listener {
        void frame(Frame frame,int initialVisible);
        void screenLayerChanged();
        void logo(List<LogoPanel.Slide> slides,int width,int height,int frameMs);
        void opening(GameUiAssets.Config config,int frameMs,Capture initial,Capture previous);
        void title(TitleMenu title,int width,int height,Capture initial,Capture previous);
        void titleRestart(TitleMenu title,Capture background);
        void style(DialogueStyle style);
        void panels(GameUiAssets assets);
        void parameters(int[] raw,int[] display,int[] auxiliary);
        void text(Frame frame,int visible,int total);
        void choiceTime(Frame frame,int remaining);
        void mode(boolean auto,boolean skip);
        void slotsChanged();
        void quickSlot(boolean available);
        void quickNotice(int number,int frameMs);
        void backlog(List<BacklogLine> lines);
        void gallery(GalleryCatalog.Page page,long request);
        void spectrum(int[] bands);
        void galleryReplayStarted();
        void galleryReplayFinished();
        void movie(File file,int volume,boolean canSkip,boolean startup);
        void loaded();
        void statusOverlays(int mode,Modal[] overlays);
        void titleReturnCancelled();
        void notice(String message);
        void resourcesEvicted();
        void progressCorrupt();
        void error(Throwable reason);
    }
    private final HandlerThread worker=new HandlerThread("SystemNNN-Game");
    private final AtomicLong pendingScreenLayer=new AtomicLong();
    private final Handler tasks;
    private final File root,legacySource,readFile,chartDirectory,legacyFiles;
    private final GameSavePaths savePaths;
    private GameSavePaths.Access saveAccess;
    private final SaveSlots slots;
    private static final ExecutorService SAVE_FILES=Executors.newSingleThreadExecutor(r->new Thread(r,"SystemNNN-Save"));
    // The latest lifecycle commit per game, retained on failure so reopening can recover it.
    private static final ConcurrentHashMap<File,CompletableFuture<Void>> PROGRESS_HANDOFFS=new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<File,CompletableFuture<Void>> SAVE_RELEASES=new ConcurrentHashMap<>();
    private static final class ProgressWriteFailure extends IOException {
        final GameProgress.State state;
        volatile boolean recovered;
        ProgressWriteFailure(GameProgress.State state,Exception cause){super(cause.getMessage(),cause);this.state=state;}
    }
    private Future<?> autoWrite;
    private volatile boolean closing;
    private volatile boolean audioForeground;
    private final GalleryStore galleryStore;
    private final GameProgress progressStore;
    private final CompletableFuture<Void> previousProgress;
    private final CompletableFuture<Void> previousRelease;
    private final Listener ui;
    private final Context context;
    private final SharedPreferences preferences,userPreferences;
    private final int rate=DeviceAudio.rate();
    private final int[] meta=new int[NativeGame.META_SIZE];
    private final LinkedHashSet<String> readLines=new LinkedHashSet<>();
    private GameProgress.State initialProgress;
    private Modal menuModal;
    private boolean systemMenuModal;
    private volatile long systemMenuModes;
    private int modalTickRequest,modalTickRemainder;
    private boolean systemStateEstablished;
    private volatile boolean initialized;
    private PlayerSettings settings;
    private int shownWait=-1,shownWord=-1,shownLine=-1,x,y;
    private long handle,deadline,lastTickAt;
    private final Object surfaceOwner=new Object();
    private volatile long surfaceBinding;
    private boolean active,menuOpen,failed,onTitle,autoMode,skipMode,textDone;
    private int visibleText=-1;
    private DeviceAudio audio;
    private GameSave history;
    private GameTextDecoder codec;
    private Frame current;
    private DialogueStyle dialogueStyle;
    private GameUiAssets uiAssets;
    private RuntimeHandle runtime;
    private TitleMenu currentTitle;
    private boolean titleBackgroundHeld,startupTitleFramePending;
    private StartupStage titleRestartTarget;
    private long titleFrames;
    private boolean titleRestartPending,titleRestartReady;
    private volatile TitleMenu titleInteraction;
    private enum StartupStage {
        LOGO("logo"),OPENING("opening"),MOVIE("printMovie"),TITLE("title");
        final String config;
        StartupStage(String config){this.config=config;}
        static StartupStage fromMode(String value){
            String mode=value.trim().split("\\s+",2)[0];
            if(mode.equalsIgnoreCase("logo")||mode.equals("2"))return LOGO;
            if(mode.equalsIgnoreCase("opening")||mode.equals("3"))return OPENING;
            if(mode.equalsIgnoreCase("printmovie")||mode.equals("27"))return MOVIE;
            if(mode.equalsIgnoreCase("title")||mode.equals("4"))return TITLE;
            return null;
        }
    }
    private final EnumSet<StartupStage> startupVisited=EnumSet.noneOf(StartupStage.class);
    private StartupStage startupStage;
    private GameUiAssets.Config startupConfig;
    private boolean startupLogoFinished;
    private boolean choiceClicking;
    private GalleryCatalog gallery;
    private GalleryCatalog.MovieList movieList;
    private GalleryCatalog.Page galleryPage;
    private long galleryRequest,galleryWritten;
    private boolean replaying,galleryMusicPlaying,galleryMusicPaused;
    private MusicSpectrum gallerySpectrum;
    private boolean moviePlaying;
    private int galleryCue=-1,gallerySong=-1,galleryVolume=100;
    private volatile int configMask;
    private int overlayMode,overlaysSentMode=-1;
    private final byte[][][] overlayRows=new byte[2][][];
    private final Modal[] overlayModels=new Modal[2];
    private final GameUiAssets.ModeAssets[] overlayAssets=new GameUiAssets.ModeAssets[2];

    private long openNative(){
        long token=runtime==null?0:runtime.candidate();
        try{
            byte[] path=root.getPath().getBytes(StandardCharsets.UTF_8);
            long candidate=token==0?NativeGame.open(path,rate):NativeGame.openWithRuntime(path,rate,token);
            if(candidate==0)throw new IllegalStateException("Native game open returned a zero handle");
            try{
                NativeGame.setChartStorage(candidate,chartDirectory.getPath().getBytes(StandardCharsets.UTF_8));
                return candidate;
            }catch(RuntimeException|LinkageError e){
                NativeGame.close(candidate);
                throw e;
            }
        }
        finally{if(token!=0)NativeGame.releaseContext(token);}
    }
    boolean systemMenuModeEnabled(int mode){return (systemMenuModes&(1L<<mode))!=0;}
    private void refreshSystemMenuModes(){
        if(uiAssets!=null&&uiAssets.systemExtensionModes!=0)
            systemMenuModes=NativeGame.systemMenuModes(handle,uiAssets.systemExtensionModes);
    }
    private void commitRuntime(long candidate){
        if(runtime==null)runtime=new RuntimeHandle(root,NativeGame.retainContext(candidate));else runtime.commit(candidate);
        if(uiAssets!=null)uiAssets.runtime=runtime;
    }

    private final GameSavePaths.Owner saveOwner;

    GameRunner(Context context,File folder,File legacySource,GameSavePaths paths,File legacyFiles,SharedPreferences preferences,
               SharedPreferences userPreferences,PlayerSettings initial,Listener listener) {
        this.context=context;
        this.legacySource=legacySource;
        root=folder;ui=listener;this.preferences=preferences;this.userPreferences=userPreferences;settings=initial;
        savePaths=paths;this.legacyFiles=legacyFiles;slots=new SaveSlots(paths);
        readFile=paths.file(GameSavePaths.READ);
        chartDirectory=paths.file(GameSavePaths.CHART);
        galleryStore=new GalleryStore(paths.file(GameSavePaths.GALLERY));
        progressStore=new GameProgress(readFile);
        previousProgress=PROGRESS_HANDOFFS.get(readFile);
        previousRelease=SAVE_RELEASES.get(readFile);
        saveOwner=GameSavePaths.acquireOwner();
        try {
            worker.start();tasks=new Handler(worker.getLooper());
            if(!tasks.post(() -> {
                try{initialize();}catch(Exception|LinkageError e){broken(e);}
            }))throw new IllegalStateException("Unable to initialize the SystemNNN worker");
        }catch(RuntimeException|Error e){
            try{worker.quitSafely();}finally{saveOwner.close();}
            throw e;
        }
    }
    boolean initialized(){return initialized;}
    private void initialize() throws IOException {
        if(previousRelease!=null)try{previousRelease.get(10,TimeUnit.SECONDS);}
        catch(TimeoutException e){throw new SystemNnnHost.SaveBusyException();}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
        catch(ExecutionException e){throw new IOException(e.getCause());}
        ProgressWriteFailure recovery=null;
        if(previousProgress!=null)try{saveResult(previousProgress);}catch(ProgressWriteFailure failure){recovery=failure;}
        ProgressWriteFailure pending=recovery;
        boolean resourcesReady=saveResult(SAVE_FILES.submit(()->{
            if(saveAccess==null)saveAccess=savePaths.open(legacyFiles,legacySource);
            if(SystemNnnGameImport.resourcesEvicted(root)){
                GameSavePaths.Access access=saveAccess;
                saveAccess=null;
                access.close();
                return false;
            }
            if(pending==null)readReadLines();else recoverProgress(previousProgress,pending);
            slots.preload(SaveSlots.configuredManualCount(root,codec));
            return true;
        }));
        if(!resourcesReady){ui.resourcesEvicted();return;}
        codec=GameTextDecoder.load(root);
        openTitle();
        initialized=true;
    }
    private void configure(long candidate){
        NativeGame.setParameters(candidate,RuntimeSettingsAdapter.persistedOverrides(userPreferences,legacySource));
        if(runtime==null&&initialProgress!=null){
            if(initialProgress.read!=null)NativeGame.restoreReadState(candidate,initialProgress.read);
            if(initialProgress.variables!=null)NativeGame.setSystemVariables(candidate,initialProgress.variables);
            if(initialProgress.results!=null)NativeGame.setScriptProgress(candidate,initialProgress.results);
            if(initialProgress.text!=null&&!NativeGame.restoreRuntimeText(candidate,initialProgress.text))
                throw new IllegalStateException("System text progress rejected by game runtime");
            if(initialProgress.optional!=null)NativeGame.restoreOptionalProgress(candidate,initialProgress.optional);
            if(initialProgress.chart!=null)NativeGame.restoreChartProgress(candidate,initialProgress.chart);
        }
        java.util.ArrayList<Integer> aux=new java.util.ArrayList<>();
        for(int i=0;i<34;i++)if(userPreferences.contains(RuntimeSettingsAdapter.ownerKey(legacySource,i))){
            aux.add(i);
            aux.add(userPreferences.getInt(RuntimeSettingsAdapter.ownerKey(legacySource,i),0));
        }
        if(!aux.isEmpty()){int[] pairs=new int[aux.size()];for(int i=0;i<pairs.length;i++)pairs[i]=aux.get(i);NativeGame.setAuxiliaryState(candidate,pairs);}
        NativeGame.setSkip(candidate,false);
    }
    private void publishParameters(){if(handle!=0)ui.parameters(NativeGame.parameters(handle),NativeGame.displayParameters(handle),NativeGame.auxiliaryState(handle));}
    void settingsVoiceRoles(GameUiAssets.Config config,int count,java.util.function.Consumer<boolean[]> ready){
        tasks.post(()->{
            if(closing||handle==0)return;
            boolean[] visible=new boolean[count];
            if(count>0)visible[0]=true;
            for(int theme=1;theme<count;theme++){
                String gate="systemVoice"+theme+"On";
                int character=config.number(gate,0);
                String variable=config.value(gate+"VarName","");
                visible[theme]=character==0||character>0&&NativeGame.voiceRoleHeard(handle,character)||
                    !variable.isEmpty()&&variable.length()<=255&&NativeGame.menuVariable(handle,resourceName(variable))!=0;
            }
            ready.accept(visible);
        });
    }
    void settings(String key,PlayerSettings value){
        final int[] requested=uiAssets==null?new int[0]:RuntimeSettingsAdapter.changedParameter(preferences,uiAssets,key);
        final Object desired=key==null?null:preferences.getAll().get(key);
        tasks.post(() -> {
        settings=value;
        if(handle==0||uiAssets==null)return;
        if(requested.length>0){
            NativeGame.setParameters(handle,requested);int[] raw=NativeGame.parameters(handle),accepted=new int[requested.length];
            for(int i=0;i<requested.length;i+=2){accepted[i]=requested[i];for(int j=0;j<raw.length;j+=2)if(raw[j]==accepted[i]){accepted[i+1]=raw[j+1];break;}}
            RuntimeSettingsAdapter.persistRawOverrides(userPreferences,legacySource,accepted);
        }else{
            int index=-1,changed=0;
            if("systemVoiceSelection".equals(key)&&desired instanceof Integer){index=0;changed=(Integer)desired;}
            else if("messageWindowIndex".equals(key)&&desired instanceof Integer){index=1;changed=(Integer)desired;}
            else if(key!=null&&key.startsWith("character.")&&key.endsWith(".enabled")){
                OriginalSettingsModel model=uiAssets.settingsModel(meta[3],meta[4]);int[] aux=NativeGame.auxiliaryState(handle);
                for(OriginalSettingsModel.CharacterControl group:model.characters)for(int id:group.ids)if(key.equals("character."+id+".enabled")){
                    int word=group.index&31,bit=1<<(group.index/32);index=2+word;changed=Boolean.TRUE.equals(desired)?aux[index]&~bit:aux[index]|bit;
                }
            }
            if(index>=0){NativeGame.setAuxiliaryState(handle,new int[]{index,changed});int[] aux=NativeGame.auxiliaryState(handle);
                userPreferences.edit().putInt(RuntimeSettingsAdapter.ownerKey(legacySource,index),aux[index]).apply();}
            else return;
        }
        publishParameters();
    });}
    private long restoreGallery(long candidate) {
        try { NativeGame.mergeGallery(candidate,galleryStore.read()); }
        catch (IOException | RuntimeException e) { Log.w("SystemNNN","Unable to read gallery progress",e); }
        return NativeGame.galleryRevision(candidate);
    }
    private final Runnable flushGallery=this::writeGallery;
    private boolean galleryWritePending;
    private void queueGalleryWrite() {
        if (!galleryWritePending && handle!=0 && galleryWritten!=NativeGame.galleryRevision(handle)) {
            galleryWritePending=true; tasks.postDelayed(flushGallery,900);
        }
    }
    private void writeGallery() {
        tasks.removeCallbacks(flushGallery); galleryWritePending=false;
        if (handle==0) return;
        long revision=NativeGame.galleryRevision(handle);
        if (revision==galleryWritten) return;
        try {
            int[] saved=galleryStore.merge(NativeGame.galleryState(handle));
            NativeGame.mergeGallery(handle,saved);
            galleryWritten=revision;
        } catch (IOException | RuntimeException e) { Log.w("SystemNNN","Unable to save gallery progress",e); }
    }
    private void openTitle() throws IOException {
        openTitle(null);
    }
    private void openTitle(Capture previous) throws IOException {
        tasks.removeCallbacks(ticker);stopSound();autoMode=false;skipMode=false;ui.mode(false,false);
        writeGallery();
        boolean unsaved=handle!=0 && galleryWritten!=NativeGame.galleryRevision(handle);
        boolean launching=runtime==null;
        long candidate=openNative();
        int[] state=new int[NativeGame.META_SIZE];TitleMenu title=null;long stored;
        List<LogoPanel.Slide> logos=java.util.Collections.emptyList();
        try{
            configure(candidate);
            stored=restoreGallery(candidate);
            if (replaying) NativeGame.mergeGallery(candidate,NativeGame.galleryState(handle));
            if(launching){
                startupStage=StartupStage.LOGO;
                startupVisited.add(startupStage);
                startupConfig=readStartupConfig(startupStage);
                if(startupConfig!=null)try{logos=LogoPanel.load(startupConfig,candidate);}
                catch(RuntimeException e){Log.w("SystemNNN","Startup logos unavailable",e);}
                // Initialize the menu surface without running mode 4 before the startup script.
                NativeGame.configureMenuBackground(candidate,2,null,null,false);
            }else title=loadTitle(candidate);
            if(uiAssets==null)uiAssets=GameUiAssets.load(root,candidate);
            if(dialogueStyle==null)dialogueStyle=DialogueStyle.load(root,codec,uiAssets);
            NativeGame.tick(candidate,0,0,0,false,0,false,state);
        }catch(Exception|LinkageError e){NativeGame.close(candidate);if(handle!=0)schedule();throw e;}
        if(handle!=0)NativeGame.close(handle);
        offerScreenLayer(-1);
        handle=candidate;commitRuntime(candidate);onTitle=true;menuOpen=false;menuModal=null;systemMenuModal=false;
        choiceClicking=false;failed=false;history=null;current=null;shownWait=-1;
        configMask=0;
        replaying=false; galleryPage=null; currentTitle=title; galleryWritten=unsaved?-1:stored;
        galleryCue=title==null?-1:title.musicNumber();
        gallerySong=-1; galleryMusicPlaying=galleryMusicPaused=false; queueGalleryWrite();
        System.arraycopy(state,0,meta,0,meta.length);bindSurface(true);
        ui.style(dialogueStyle);ui.panels(uiAssets);publishQuickSlot();publishParameters();
        if(launching){
            titleBackgroundHeld=true;
            ui.logo(logos,meta[3],meta[4],meta[5]);
            if(logos.isEmpty()&&completeStartupLogo())return;
        }else publishTitle(previous);
        schedule();
    }
    private TitleMenu loadTitle(long player) throws IOException {
        SaveSlots.Entry resume=slots.entry(SaveSlots.AUTO);
        boolean lastLoad=saveResult(SAVE_FILES.submit(slots::lastSelectedSlot))!=null;
        return new TitleMenu(root,player,resume.exists&&!resume.corrupt,lastLoad);
    }
    private void publishTitle(Capture previous){
        publishTitle(previous,false);
    }
    private void publishTitle(Capture previous,boolean afterStartup){
        titleBackgroundHeld=true;
        titleFrames=0;
        titleRestartPending=titleRestartReady=false;
        String restart=currentTitle.config.value("restartNextMode","");
        titleRestartTarget=StartupStage.fromMode(restart);
        if(titleRestartTarget==null&&!restart.isEmpty()&&
                (currentTitle.autoRestartTime>0||currentTitle.restartOnBackgroundEnd))
            Log.w("SystemNNN","Unsupported title restartNextMode: "+restart);
        Capture initial=captureEntranceFrame(currentTitle.config);
        startupTitleFramePending=afterStartup&&initial!=null;
        if(afterStartup&&!startupTitleFramePending)bindSurface(true);
        ui.title(currentTitle,meta[3],meta[4],initial,previous);
        Log.i("SystemNNN","Title "+root.getName()+" buttons="+currentTitle.items.size());
        UiPreviewPool.Request settings=UiPreviewPool.Request.read(uiAssets.config,12,1);
        if(settings!=null){
            UiPreviewPool pool=runtime.previews();
            if(pool!=null)pool.preload(settings);
        }
    }
    private Capture captureEntranceFrame(GameUiAssets.Config config){
        if(!UiOpening.hasEnterFade(config))return null;
        try{return captureTitleFrame();}
        catch(RuntimeException e){Log.w("SystemNNN","Unable to capture menu start screen",e);return null;}
    }
    private Capture captureTitleFrame(){
        double scale=Math.min(1,1280.0/Math.max(meta[3],meta[4]));
        int w=Math.max(1,(int)Math.round(meta[3]*scale)),h=Math.max(1,(int)Math.round(meta[4]*scale));
        return new Capture(NativeGame.saveThumbnail(handle,w,h),w,h);
    }
    void reenterTitle(){tasks.post(()->{
        if(!onTitle||startupStage!=null||handle==0||failed)return;
        try{
            NativeGame.restartTitle(handle);
            menuOpen=false;publishTitle(null);schedule();
        }catch(Exception e){broken(e);}
    });}
    void startTitleBackground(){tasks.post(()->{
        if(onTitle&&startupStage==null&&titleBackgroundHeld){titleBackgroundHeld=false;schedule();}
    });}
    void titleShown(){tasks.post(()->{
        if(!startupTitleFramePending||closing)return;
        startupTitleFramePending=false;
        bindSurface(true);
    });}
    void titleInteraction(TitleMenu title,boolean busy){
        if(busy)titleInteraction=title;
        else if(titleInteraction==title)titleInteraction=null;
    }
    private void checkTitleRestart(int frames){
        if(startupStage!=null||currentTitle==null||titleRestartTarget==null||failed||closing)return;
        titleFrames+=frames;
        if(titleRestartPending||titleInteraction==currentTitle||galleryPage!=null||menuModal!=null)return;
        boolean elapsed=currentTitle.autoRestartTime>0&&titleFrames>=currentTitle.autoRestartTime;
        boolean ended=currentTitle.restartOnBackgroundEnd&&meta[NativeGame.TITLE_BACKGROUND_ENDED]!=0;
        if(!elapsed&&!ended)return;
        titleRestartPending=true;
        Capture background=null;
        GameUiAssets.Config config=currentTitle.config;
        if(config.number("createExitScreenFlag",1)!=0||config.number("exitFadeOutType",0)!=0){
            try{background=captureTitleFrame();}
            catch(RuntimeException|OutOfMemoryError e){Log.w("SystemNNN","Unable to capture outgoing title",e);}
        }
        ui.titleRestart(currentTitle,background);
    }
    void finishTitleRestart(TitleMenu title,boolean proceed){tasks.post(()->{
        if(closing||failed||!onTitle||title!=currentTitle||!titleRestartPending)return;
        if(!proceed){titleRestartPending=false;return;}
        titleRestartReady=true;
        if(active)restartTitleSequence();
    });}
    private void restartTitleSequence(){
        titleRestartReady=false;
        if(closing||failed)return;
        startupVisited.clear();
        Log.i("SystemNNN","Title restart "+titleRestartTarget.config);
        runStartup(titleRestartTarget,null);
    }
    void finishLogo(){tasks.post(()->{if(startupStage==StartupStage.LOGO)completeStartupLogo();});}
    private boolean completeStartupLogo(){
        startupLogoFinished=true;
        if(!active)return false;
        advanceStartup(null);
        return true;
    }
    void openingShown(){tasks.post(()->{
        if(startupStage==StartupStage.OPENING&&titleBackgroundHeld){titleBackgroundHeld=false;schedule();}
    });}
    void finishOpening(){tasks.post(()->{
        if(startupStage==StartupStage.OPENING){
            Log.i("SystemNNN","Startup opening skipped");
            advanceStartup(null);
        }
    });}
    private GameUiAssets.Config readStartupConfig(StartupStage stage){
        try{return GameUiAssets.readConfig(root,stage.config,codec);}
        catch(IOException e){Log.w("SystemNNN","Startup configuration unavailable: "+stage.config,e);return null;}
    }
    private StartupStage nextStartupStage(){
        if(startupConfig==null)return null;
        String name=startupConfig.value("nextMode","title");
        StartupStage next=StartupStage.fromMode(name);
        if(next==null&&!name.isEmpty())Log.w("SystemNNN","Unsupported startup nextMode: "+name);
        return next;
    }
    private void advanceStartup(Capture previous){
        if(closing||failed||startupStage==null)return;
        runStartup(nextStartupStage(),previous);
    }
    private void runStartup(StartupStage start,Capture previous){
        tasks.removeCallbacks(ticker);
        try{
            for(StartupStage next=start;next!=null&&next!=StartupStage.TITLE;next=nextStartupStage()){
                if(!startupVisited.add(next)){
                    Log.w("SystemNNN","Repeated startup mode: "+next.config);
                    break;
                }
                startupStage=next;
                startupConfig=readStartupConfig(next);
                if(startupConfig==null)break;
                if(next==StartupStage.LOGO){
                    if(startStartupLogo())return;
                }else if(next==StartupStage.OPENING){
                    if(startOpening(previous))return;
                }else{
                    if(startStartupMovie())return;
                    Capture exit=movieExitScreen();
                    if(exit!=null)previous=exit;
                }
            }
            startupStage=null;startupConfig=null;
            currentTitle=loadTitle(handle);galleryCue=currentTitle.musicNumber();
            meta[5]=currentTitle.frameMs;
            publishTitle(previous,true);
            schedule();
        }catch(Exception|LinkageError e){broken(e);}
    }
    private boolean startStartupLogo(){
        List<LogoPanel.Slide> logos;
        try{logos=LogoPanel.load(startupConfig,handle);}
        catch(RuntimeException e){Log.w("SystemNNN","Startup logos unavailable",e);return false;}
        if(logos.isEmpty())return false;
        NativeGame.configureMenuBackground(handle,2,null,null,false);
        startupLogoFinished=false;
        titleBackgroundHeld=true;
        ui.logo(logos,meta[3],meta[4],meta[5]);
        schedule();
        return true;
    }
    private static byte[] resourceName(String name){return name.getBytes(StandardCharsets.ISO_8859_1);}
    private boolean startOpening(Capture previous){
        GameUiAssets.Config cfg=startupConfig;
        boolean scripted=cfg.number("backScriptFlag",0)!=0;
        String script=scripted?cfg.value("backScriptName",""):"";
        String image=scripted?"":cfg.value("filenameBG","");
        int bgm=cfg.number("bgmNumber",-1);
        if(bgm>=0)try{
            NativeGame.menuMusic(handle,bgm,cfg.number("bgmLoop",0),cfg.number("bgmVolume",0),
                cfg.number("bgmFadeInTime",-1),cfg.number("bgmRestartFlag",1)!=0);
        }catch(RuntimeException e){Log.w("SystemNNN","Startup opening music unavailable",e);}
        try{
            if(!NativeGame.configureMenuBackground(handle,3,resourceName(script),resourceName(GameUiAssets.systemImageKey(image)),
                cfg.number("backScriptLoopFlag",0)!=0)){
                Log.w("SystemNNN","Startup opening background unavailable");
                return false;
            }
        }catch(RuntimeException e){Log.w("SystemNNN","Startup opening background unavailable",e);return false;}
        titleBackgroundHeld=true;
        NativeGame.tick(handle,0,0,0,false,0,false,meta);
        ui.opening(cfg,meta[5],captureEntranceFrame(cfg),previous);
        Log.i("SystemNNN","Startup opening "+(script.isEmpty()?image:script));
        schedule();
        return true;
    }
    private boolean startStartupMovie(){
        GameUiAssets.Config cfg=startupConfig;
        NativeGame.stopScriptAudio(handle);
        NativeGame.menuMusic(handle,-1,0,0,-1,true);
        int number=-1;
        int select=cfg.number("selectByVarFlag",0);
        if(select!=0)try{
            String name=cfg.value("selectMovieVarName","");
            byte[] variable=name.isEmpty()?"ムービー番号".getBytes(Charset.forName("windows-31j")):resourceName(name);
            number=NativeGame.openingVariable(handle,variable);
        }catch(RuntimeException e){Log.w("SystemNNN","Startup movie variable unavailable",e);}
        if(select==-1&&number==0){
            Log.i("SystemNNN","Startup movie disabled by game variable");
            return false;
        }
        GalleryCatalog.Movie movie=null;
        if(number>0)try{movie=movies().find(number);}
        catch(IOException e){Log.w("SystemNNN","Startup movie list unavailable",e);}
        if(movie==null)try{movie=GalleryCatalog.openingMovie(root,cfg);}
        catch(IOException e){Log.w("SystemNNN","Startup movie file unavailable",e);}
        if(movie==null||movie.file==null){Log.w("SystemNNN","Startup movie file missing");return false;}
        moviePlaying=true;
        stopSound();
        Log.i("SystemNNN","Startup movie requested "+movie.file.getName());
        ui.movie(movie.file,NativeGame.movieVolume(handle),cfg.number("noSkipFlag",0)==0,true);
        return true;
    }
    private Capture movieExitScreen(){
        if(startupConfig.number("fillExitScreenFlag",1)==0)return null;
        int r=colorChannel(startupConfig.number("exitFadeOutColorR",0));
        int g=colorChannel(startupConfig.number("exitFadeOutColorG",0));
        int b=colorChannel(startupConfig.number("exitFadeOutColorB",0));
        return new Capture(new int[]{0xff000000|r<<16|g<<8|b},1,1);
    }
    void returnToTitle(){tasks.post(() -> {
        if(onTitle||closing)return;
        if(replaying){finishReplay();return;}
        Future<?> previousWrite=autoWrite;
        autoWrite=null;
        try{
            CompletableFuture<Void> currentWrite=queueSlotWrite(SaveSlots.AUTO,false,null,0);
            if(currentWrite!=null){
                writeReadLines();
                saveResult(currentWrite);
            }else if(previousWrite!=null){
                // The earlier background save already reports failure to the player.
                try{saveResult(previousWrite);}catch(IOException ignored){}
            }
        }catch(IOException|RuntimeException e){
            saveFailed(e);
            ui.titleReturnCancelled();
            schedule();
            return;
        }
        try{
            openTitle();
        }catch(Exception e){broken(e);}
    });}
    void enter(TitleMenu.Action action,Runnable onFailure){
        if(!tasks.post(()->{
            if(!onTitle||failed||closing){onFailure.run();return;}
            try{
                Integer slot=null;
                if(action==TitleMenu.Action.CONTINUE)slot=SaveSlots.AUTO;
                else if(action==TitleMenu.Action.LAST_LOAD){
                    slot=slots.lastSelectedSlot();
                    if(slot==null)throw new FileNotFoundException("Last selected save is unavailable");
                }
                open(slot==null?null:slots.file(slot),false);
            }
            catch(Exception|LinkageError e){
                schedule();
                noticeFailure(R.string.engine_systemnnn_host_enter_failed,e);
                onFailure.run();
            }
        }))onFailure.run();
    }
    private void open(File save,boolean holdStory) throws IOException {
        open(save,holdStory,null);
    }
    private void open(File save,boolean holdStory,byte[] pcSave) throws IOException {
        long started=SystemClock.uptimeMillis();
        tasks.removeCallbacks(ticker);
        GameSave.Loaded loaded=save==null?null:saveResult(SAVE_FILES.submit(()->GameSave.read(save)));
        int[] state=new int[NativeGame.META_SIZE];
        GameSave next=pcSave!=null?GameSave.nativeBaseline():loaded==null?new GameSave():loaded.save;
        boolean live=loaded!=null&&handle!=0&&!onTitle&&!replaying&&!failed;
        boolean restoredHere=live&&GameSave.restoreSnapshot(loaded,handle,state,true,false);
        if(loaded!=null&&loaded.nativeSnapshot==null)next.requireReplayable();
        if(restoredHere){
            NativeGame.setParameters(handle,RuntimeSettingsAdapter.persistedOverrides(userPreferences,legacySource));
            NativeGame.setSkip(handle,false);
        }else{
            writeGallery();
            boolean unsaved=handle!=0&&galleryWritten!=NativeGame.galleryRevision(handle);
            long candidate=openNative();
            long stored;
            try{
                configure(candidate);
                stored=restoreGallery(candidate);
                if(pcSave!=null){
                    if(handle!=0&&!onTitle&&!replaying)
                        NativeGame.setScriptRandomState(candidate,NativeGame.scriptRandomState(handle));
                    NativeGame.restorePcSave(candidate,pcSave,state);
                }else if(loaded!=null){
                    if(!GameSave.restoreSnapshot(loaded,candidate,state,false,onTitle&&!systemStateEstablished)){
                        next.requireReplayable();
                        int[] globals=NativeGame.systemVariables(candidate);
                        int[] progress=NativeGame.scriptProgress(candidate),galleryState=NativeGame.galleryState(candidate);
                        if(!next.hasSystemBaseline())NativeGame.seedLegacyReplayVariables(candidate);
                        GameSave.replay(loaded,candidate,state);
                        NativeGame.setSystemVariables(candidate,globals);
                        NativeGame.setScriptProgress(candidate,progress);
                        NativeGame.replaceGallery(candidate,galleryState);
                    }
                }else{
                    recordReplayBaseline(next,candidate,NativeGame.scriptRandomState(candidate));
                    NativeGame.tick(candidate,0,0,0,false,0,false,state);
                    next.appendWithClock(0,0,0,false,0,false,state[NativeGame.CLOCK_DATE],state[NativeGame.CLOCK_MILLISECONDS]);
                }
                configure(candidate);
            }catch(Exception|LinkageError e){NativeGame.close(candidate);throw e;}
            stopSound();
            if(handle!=0)NativeGame.close(handle);
            offerScreenLayer(-1);
            handle=candidate;commitRuntime(candidate);
            bindSurface(true);
            galleryWritten=unsaved?-1:stored;
        }
        startupStage=null;startupConfig=null;titleBackgroundHeld=false;startupTitleFramePending=false;
        moviePlaying=false;
        onTitle=false;menuOpen=holdStory;systemMenuModal=false;choiceClicking=false;history=next;System.arraycopy(state,0,meta,0,meta.length);
        if(loaded!=null)recordReplayBaseline(next,handle,NativeGame.scriptRandomState(handle));
        if(finishEndedRun())return;
        if(!systemStateEstablished)writeReadLines();
        queueGalleryWrite();
        GameSave.Run last=next.lastInput();x=last.x;y=last.y;
        failed=false;shownWait=-1;current=null;autoMode=false;skipMode=false;syncModes();
        if(!restoredHere)ui.style(dialogueStyle);
        publishParameters();if(save!=null||pcSave!=null){NativeGame.captureBacklogJump(handle,0);ui.loaded();}
        if(meta[0]==NativeGame.MOVIE)startStoryMovie();else updateUi();
        schedule();
        Log.i("SystemNNN","Opened "+root.getName()+(save==null?"":" save="+save.getName()+" inPlace="+restoredHere+
            " elapsedMs="+(SystemClock.uptimeMillis()-started)));
    }
    private static void recordReplayBaseline(GameSave journal,long owner,int random){
        if(journal.requiresSnapshot())return;
        journal.recordReplayBaseline(NativeGame.systemVariables(owner),NativeGame.scriptProgress(owner),
            NativeGame.galleryState(owner),random);
    }
    static final class PcSaveTooLarge extends IOException {}

    void importPcSave(java.util.concurrent.Callable<byte[]> read,java.util.function.Consumer<Boolean> done){
        tasks.post(()->{
            boolean success=false;
            try{
                if(handle==0||failed||replaying||moviePlaying){
                    ui.notice(text(R.string.engine_systemnnn_host_cannot_import_pc));
                    schedule();
                    return;
                }
                open(null,true,saveResult(SAVE_FILES.submit(read)));
                success=true;
                Log.i("SystemNNN","PC save imported at "+current.script+" word="+meta[1]);
            }catch(IOException|RuntimeException e){
                Log.w("SystemNNN","Unable to import PC save",e);
                noticeFailure(e instanceof PcSaveTooLarge?R.string.engine_systemnnn_host_pc_save_too_large:
                    R.string.engine_systemnnn_host_pc_load_failed,e);
                schedule();
            }
            finally{done.accept(success);}
        });
    }
    void preparePcExport(java.util.function.Consumer<byte[]> ready){
        tasks.post(()->{
            byte[] data=null;
            try{
                if(handle==0||failed||onTitle||replaying||moviePlaying)
                    ui.notice(text(R.string.engine_systemnnn_host_cannot_export_pc));
                else data=NativeGame.exportPcSave(handle);
            }catch(RuntimeException e){noticeFailure(R.string.engine_systemnnn_host_pc_export_failed,e);}
            ready.accept(data);
        });
    }
    void writePcExport(java.util.concurrent.Callable<Void> write,Runnable done){
        SAVE_FILES.execute(()->{
            try{write.call();ui.notice(text(R.string.engine_systemnnn_host_pc_save_exported));}
            catch(Exception e){noticeFailure(R.string.engine_systemnnn_host_pc_export_failed,e);}
            finally{done.run();}
        });
    }
    void surface(Surface value){
        if(closing)return;
        long binding;
        try{binding=value==null?0:NativeGame.createSurface(value);}
        catch(RuntimeException|LinkageError e){
            replaceSurface(0);
            tasks.post(()->{if(!closing)broken(e);});
            return;
        }
        replaceSurface(binding);
        // Read the latest binding on the worker, even when this callback was queued
        // before another Surface was created or destroyed.
        tasks.post(()->{
            if(closing)return;
            try{bindSurface(!startupTitleFramePending);schedule();}
            catch(RuntimeException|LinkageError e){broken(e);}
        });
    }
    private void replaceSurface(long binding){
        long old;
        synchronized(surfaceOwner){
            if(closing&&binding!=0)old=binding;
            else{old=surfaceBinding;surfaceBinding=binding;}
        }
        if(old!=0){
            NativeGame.deactivateSurface(old);
            if(!tasks.post(()->NativeGame.releaseSurface(old)))NativeGame.releaseSurface(old);
        }
    }
    private void bindSurface(boolean allowAttach){
        if(handle==0)return;
        synchronized(surfaceOwner){
            if(!allowAttach&&surfaceBinding!=0)return;
            // Only copy ownership under this monitor. Drawing can block on buffers.
            NativeGame.bindSurface(handle,surfaceBinding);
        }
        NativeGame.presentSurface(handle);
    }
    void windowFocused(){tasks.post(()->{if(playing()&&audio!=null)audio.requestFocus();});}
    void active(boolean value){
        audioForeground=value;
        if(value){
            tasks.post(()->{
                active=true;
                if(playing()&&audio!=null)audio.requestFocus();
                if(titleRestartReady)restartTitleSequence();
                else if(startupStage==StartupStage.LOGO&&startupLogoFinished)advanceStartup(null);
                else schedule();
            });
            return;
        }
        postProgressFlush(()->{
            active=false;
            tasks.removeCallbacks(ticker);stopSound();writeGallery();
            CompletableFuture<Void> saving=save(SaveSlots.AUTO,false,null,0);
            return saving==null?writeReadLines():saving;
        });
    }
    private boolean postProgressFlush(Supplier<CompletableFuture<Void>> flush){
        CompletableFuture<Void> handoff=new CompletableFuture<>();
        CompletableFuture<Void> previous=PROGRESS_HANDOFFS.put(readFile,handoff);
        handoff.thenRun(()->PROGRESS_HANDOFFS.remove(readFile,handoff));
        boolean posted=tasks.post(()->{
            try{
                CompletableFuture<Void> saving=flush.get();
                forwardProgress(saving==null?previous:saving,handoff);
            }catch(RuntimeException|LinkageError e){
                Log.w("SystemNNN","Unable to collect the final global progress",e);
                handoff.completeExceptionally(e);
            }
        });
        if(!posted)forwardProgress(previous,handoff);
        return posted;
    }
    private static void forwardProgress(CompletableFuture<Void> source,CompletableFuture<Void> handoff){
        if(source==null){handoff.complete(null);return;}
        source.whenComplete((done,error)->{
            if(error==null||error instanceof ProgressWriteFailure&&((ProgressWriteFailure)error).recovered)
                handoff.complete(null);
            else handoff.completeExceptionally(error);
        });
    }
    void menu(boolean open){tasks.post(() -> {
        menuOpen=open;
        if(open&&handle!=0&&!onTitle)refreshSystemMenuModes();
        Modal modal=activeModal();
        if(open){
            if(modal==null||modal.mode!=Modal.SELECT_CHART)setModes(false,false);
        }
        else {
            if(handle!=0&&!onTitle)NativeGame.restartLoopScriptAudio(handle);
            syncModes();
        }
        Log.i("SystemNNN","menu="+open+" wait="+meta[0]+" word="+meta[1]+" line="+meta[2]+" remaining="+meta[6]);
        publishParameters();schedule();
    });}
    void statusMode(int mode){tasks.post(()->{
        overlayMode=mode;
        publishStatusOverlays();
    });}
    private void publishStatusOverlays(){
        if(overlayMode<=0||handle==0||uiAssets==null||!uiAssets.freeStatus||failed)return;
        boolean changed=overlayMode!=overlaysSentMode;
        try{
            for(int i=0;i<overlayRows.length;i++){
                int type=i==0?Modal.PRINT_STATUS:Modal.PRINT_PARTY;
                byte[][] raw=NativeGame.statusOverlay(handle,type,overlayMode);
                if(Arrays.deepEquals(raw,overlayRows[i]))continue;
                overlayRows[i]=raw;changed=true;
                if(raw==null){overlayModels[i]=null;continue;}
                if(overlayAssets[i]==null)overlayAssets[i]=uiAssets.statusAssets(handle,codec,i!=0);
                int count=(raw.length-1)/2;
                ByteBuffer values=ByteBuffer.wrap(raw[0]).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                if(raw.length!=1+2*count||values.remaining()!=8L+8L*count)throw new IOException("Invalid status layer fields");
                int group=values.getInt(),selection=values.getInt();
                Modal.Row[] rows=new Modal.Row[count];
                for(int row=0;row<count;row++)
                    rows[row]=new Modal.Row(values.getInt(),values.getInt(),decode(raw[1+2*row],true),"",decode(raw[2+2*row],true));
                overlayModels[i]=new Modal(new int[]{0,type,0},null,null,new int[]{0,1,group,selection,0},
                    rows,overlayAssets[i],codec,null,null);
            }
            if(changed){overlaysSentMode=overlayMode;ui.statusOverlays(overlayMode,overlayModels.clone());}
        }catch(IOException|RuntimeException e){
            Log.w("SystemNNN","Unable to render independent status",e);
            ui.statusOverlays(overlayMode,new Modal[2]);overlayMode=0;
        }
    }
    void suspendScriptAudio(){tasks.post(()->{
        if(handle!=0&&!onTitle&&!failed)NativeGame.suspendScriptAudio(handle);
    });}
    void resumeScriptAudio(){tasks.post(()->{
        if(handle!=0&&!onTitle&&!failed)NativeGame.restartLoopScriptAudio(handle);
    });}
    void capture(GameUiAssets.Config effect,boolean keepPrevious,BiConsumer<Capture,Capture> callback){tasks.post(()->{
        if(handle==0){callback.accept(null,null);return;}
        Capture previous=null,background=null;
        try{
            int w=meta[3],h=meta[4];
            int[] pixels=NativeGame.saveThumbnail(handle,w,h);
            if(keepPrevious)previous=new Capture(pixels,w,h);
            if(effect!=null){
                int[] filtered=keepPrevious?pixels.clone():pixels;
                filter(filtered,effect);
                background=new Capture(filtered,w,h);
            }
        }catch(RuntimeException|OutOfMemoryError e){Log.w("SystemNNN","Unable to capture menu background",e);}
        callback.accept(previous,background);
    });}
    void filterPanel(Bitmap picture,GameUiAssets.Config config,java.util.function.Consumer<Capture> done){
        tasks.post(()->{
            Capture scene=null;
            try{
                int width=meta[3],height=meta[4];
                Bitmap logical=Bitmap.createScaledBitmap(picture,width,height,true);
                int[] pixels=new int[width*height];
                logical.getPixels(pixels,0,width,0,0,width,height);
                filter(pixels,config);
                scene=new Capture(pixels,width,height);
            }catch(RuntimeException|OutOfMemoryError e){Log.w("SystemNNN","Unable to filter the menu background",e);}
            done.accept(scene);
        });
    }
    private static void filter(int[] pixels,GameUiAssets.Config cfg){
        boolean grey=cfg.number("backScreenEffectGrey",0)!=0,negative=cfg.number("backScreenEffectNega",0)!=0;
        boolean add=cfg.number("backScreenEffectAddSub",0)!=0,multi=cfg.number("backScreenEffectMulti",0)!=0;
        int dr=add?colorDelta(cfg.number("backScreenColorR",0)):0;
        int dg=add?colorDelta(cfg.number("backScreenColorG",0)):0;
        int db=add?colorDelta(cfg.number("backScreenColorB",0)):0;
        int percentR=multi?cfg.number("backScreenMultiR",100):100;
        int percentG=multi?cfg.number("backScreenMultiG",100):100;
        int percentB=multi?cfg.number("backScreenMultiB",100):100;
        if(percentG==-1)percentG=percentR;
        if(percentB==-1)percentB=percentG;
        int mr=colorMultiplier(percentR),mg=colorMultiplier(percentG),mb=colorMultiplier(percentB);
        for(int i=0;i<pixels.length;i++){
            int r=(pixels[i]>>>16)&255,g=(pixels[i]>>>8)&255,b=pixels[i]&255;
            if(negative){r=255-r;g=255-g;b=255-b;}
            if(grey)r=g=b=(r*76+g*150+b*29)>>8;
            pixels[i]=0xff000000|colorChannel((r*mr>>8)+dr)<<16|
                colorChannel((g*mg>>8)+dg)<<8|colorChannel((b*mb>>8)+db);
        }
    }
    private static int colorChannel(int value){return Math.max(0,Math.min(255,value));}
    private static int colorMultiplier(int percent){return Math.max(0,Math.min(100,percent))*256/100;}
    private static int colorDelta(int value){return Math.max(-255,Math.min(255,value));}
    void pointer(int atX,int atY){tasks.post(()->{
        if(!onTitle&&!menuOpen&&!failed){x=atX;y=atY;}
    });}
    void input(int atX,int atY,int choice){tasks.post(() -> {
        if(choice>0)choiceClicking=false;
        if(onTitle||menuOpen||!playing()||meta[0]==NativeGame.MODAL||
            (choice==0&&meta[0]==NativeGame.CHOICE)||(choice>0&&meta[0]!=NativeGame.CHOICE))return;
        if(choice==0)setModes(false,false);
        if(choice==0&&(meta[0]==NativeGame.DRAW||meta[0]==NativeGame.OVERLAP)&&!NativeGame.requestAdvance(handle))return;
        if(choice==0&&meta[0]==NativeGame.DIALOGUE&&!NativeGame.requestAdvance(handle)){publishText();return;}
        markRead();x=atX;y=atY;step(0,choice==0,choice);
    });}
    void choicePressed(int word){tasks.post(()->{
        if(!onTitle&&!menuOpen&&meta[0]==NativeGame.CHOICE&&meta[1]==word)choiceClicking=true;
    });}
    void choosePlace(int place,java.util.function.Consumer<Boolean> done){tasks.post(()->{
        if(handle==0||onTitle||failed||meta[0]!=NativeGame.PLACE){done.accept(false);return;}
        int[] states=NativeGame.placeStates(handle);
        if(place<1||place>states.length||states[place-1]!=2){done.accept(false);return;}
        shownWait=-1;step(0,false,place);done.accept(!failed);
    });}
    private Modal activeModal(){return onTitle?menuModal:meta[0]==NativeGame.MODAL&&current!=null?current.modal:null;}
    private boolean acceptsModal(Modal modal){return modal!=null&&!closing&&handle!=0&&!failed&&activeModal()==modal;}
    void openMenuModal(int mode,int caller,java.util.function.Consumer<Modal> done){
        openMenuModal(mode,caller,true,done);
    }
    void openSystemMenuModal(int mode,java.util.function.Consumer<Modal> done){
        openMenuModal(mode,0,false,done);
    }
    private void openMenuModal(int mode,int caller,boolean title,java.util.function.Consumer<Modal> done){
        tasks.post(()->{
            if(closing||handle==0||onTitle!=title||failed||menuModal!=null||systemMenuModal||!title&&replaying){
                done.accept(null);return;
            }
            boolean previousMenu=menuOpen,entered=false;
            try{
                int previous=title?caller:NativeGame.runtimeMode(handle);
                entered=NativeGame.openModal(handle,mode,previous);
                if(entered){
                    menuOpen=true;
                    if(title)menuModal=readModal();
                    else{
                        systemMenuModal=true;
                        refreshModalFrame();
                    }
                }
                done.accept(entered?activeModal():null);
            }catch(RuntimeException e){
                if(entered){
                    int[] info=NativeGame.modalInfo(handle);
                    if(info!=null)NativeGame.abortModal(handle,info[0],"Invalid modal data");
                    if(title)menuModal=null;
                    else {systemMenuModal=false;refreshModalFrame();}
                }
                menuOpen=previousMenu;
                Log.w("SystemNNN","Unable to open configured mode "+mode,e);done.accept(null);
            }
        });
    }
    private void refreshModalFrame(){
        NativeGame.frameInfo(handle,meta);
        shownWait=-1;
        updateUi();
    }
    void completeModal(Modal modal,boolean accepted,int elapsed,byte[][] values,java.util.function.Consumer<ModalUpdate> done){
        tasks.post(()->{
            if(!acceptsModal(modal)){done.accept(rejectedModal());return;}
            try{
                if(!NativeGame.completeModal(handle,modal.request,accepted,elapsed,values)){
                    ui.notice(text(R.string.engine_systemnnn_host_input_invalid));done.accept(rejectedModal());return;
                }
                done.accept(finishModal(modal.returnMode));
            }catch(RuntimeException e){
                Log.w("SystemNNN","Script modal completion failed",e);
                noticeFailure(R.string.engine_systemnnn_host_submit_failed,e);done.accept(rejectedModal());
            }
        });
    }
    void actModal(Modal modal,int action,int value,java.util.function.Consumer<ModalUpdate> done){
        tasks.post(()->{
            Modal latest=activeModal();
            Modal request=action==Modal.RESUME&&latest!=null&&latest.request==modal.request?latest:modal;
            if(!acceptsModal(request)){done.accept(rejectedModal());return;}
            try{
                int result=NativeGame.actModal(handle,request.request,action,value);
                if(result==Modal.LEAVE_READY){done.accept(new ModalUpdate(result,meta[0],0,request));return;}
                if(result==Modal.ENTER_STORY){done.accept(resumeStoryFromModal());return;}
                if(result==2){done.accept(finishModal(request.returnMode));return;}
                if(result==3){
                    int caller=NativeGame.abortModal(handle,request.request,"No usable entries remain");
                    done.accept(caller<0?rejectedModal():finishModal(caller));return;
                }
                if(result<0){done.accept(rejectedModal());return;}
                scriptUiEvents();
                Modal next;
                if(onTitle)next=menuModal=readModal();
                else{
                    shownWait=-1;
                    updateUi();
                    next=current.modal;
                }
                done.accept(new ModalUpdate(result,meta[0],0,next));
            }catch(RuntimeException e){
                Log.w("SystemNNN","Script modal action failed",e);
                noticeFailure(R.string.engine_systemnnn_host_action_failed,e);done.accept(rejectedModal());
            }
        });
    }
    void abortModal(Modal modal,String reason,java.util.function.Consumer<ModalUpdate> done){
        tasks.post(()->{
            if(!acceptsModal(modal)){done.accept(rejectedModal());return;}
            try{
                int caller=NativeGame.abortModal(handle,modal.request,reason);
                done.accept(caller<0?rejectedModal():finishModal(caller));
            }catch(RuntimeException e){Log.w("SystemNNN","Unable to cancel unavailable mode",e);done.accept(rejectedModal());}
        });
    }
    private ModalUpdate rejectedModal(){return new ModalUpdate(-1,meta[0],0,activeModal());}
    private ModalUpdate resumeStoryFromModal(){
        systemMenuModal=false;
        if(onTitle){
            onTitle=false;currentTitle=null;titleBackgroundHeld=false;menuModal=null;
            galleryPage=null;++galleryRequest;
            history=GameSave.nativeBaseline();
            x=y=0;
            ui.style(dialogueStyle);
            ui.loaded();
        }else if(history!=null)history.recordModalBoundary(x,y);
        menuOpen=true;shownWait=-1;current=null;choiceClicking=false;
        step(0,false,0);
        return failed?rejectedModal():new ModalUpdate(2,meta[0],0,current==null?null:current.modal);
    }
    private ModalUpdate finishModal(int returnMode){
        scriptUiEvents();
        if(onTitle){
            menuModal=null;
            return new ModalUpdate(2,meta[0],returnMode,null);
        }
        if(history!=null)history.recordModalBoundary(x,y);
        if(returnMode!=0){
            systemMenuModal=false;
            refreshModalFrame();
            return new ModalUpdate(2,meta[0],returnMode,current==null?null:current.modal);
        }
        shownWait=-1;step(0,false,0);
        return failed?rejectedModal():new ModalUpdate(2,meta[0],0,current==null?null:current.modal);
    }
    void auto(boolean enabled){tasks.post(() -> setModes(enabled&&!onTitle&&meta[0]!=NativeGame.CHOICE,false));}
    void skip(boolean enabled){tasks.post(() -> {
        setModes(false,enabled&&!onTitle&&meta[0]!=NativeGame.CHOICE);
    });}
    private void setModes(boolean auto,boolean skip){
        autoMode=auto;skipMode=skip;if(handle!=0)NativeGame.setSkip(handle,skip);ui.mode(auto,skip);
        if(handle!=0)NativeGame.setParameters(handle,new int[]{6,auto?1:0});
    }
    private void syncModes(){
        if(handle==0)return;boolean automatic=false;int[] values=NativeGame.parameters(handle);
        for(int i=0;i<values.length;i+=2)if(values[i]==6){automatic=values[i+1]!=0;break;}
        boolean skipping=NativeGame.skipActive(handle);
        if(autoMode!=automatic||skipMode!=skipping){autoMode=automatic;skipMode=skipping;ui.mode(autoMode,skipMode);}
    }
    void playClick(){tasks.post(() -> {if(handle!=0)NativeGame.playSystemSe(handle,0);});}
    void playClick(int se,int voice){tasks.post(()->{if(handle==0)return;if(se>=0)NativeGame.playSystemSe(handle,se);if(voice>=0)NativeGame.playButtonVoice(handle,voice);});}
    void playSettingsClick(int se,int voice,int volumeType,boolean ignoreVoiceSwitch,boolean ignoreCharacterMute){
        if(se<0&&voice<0)return;
        tasks.post(()->{
            if(handle!=0)NativeGame.playSettingsClick(handle,se,voice,volumeType,ignoreVoiceSwitch,ignoreCharacterMute);
        });
    }
    void playConfigFeedback(int slider,int position,int steps){
        tasks.post(()->{if(handle!=0)NativeGame.playConfigFeedback(handle,slider,position,steps);});
    }
    void requestGallery(GalleryCatalog.Screen screen,int group,int page,int index,long request) {
        tasks.post(() -> {
            if (!onTitle || handle==0 || failed || moviePlaying) return;
            try {
                if (gallery==null) gallery=new GalleryCatalog(root,codec,handle,movies());
                if (screen==GalleryCatalog.Screen.MUSIC && !isMusicGallery()) {
                    int[] parameters=NativeGame.parameters(handle);
                    for (int i=0;i<parameters.length;i+=2) if (parameters[i]==100) galleryVolume=parameters[i+1];
                    GameUiAssets.Config cfg=gallery.config(screen);
                    int min=Math.max(0,Math.min(100,cfg.number("volumeMin",0)));
                    int max=Math.max(min,Math.min(100,cfg.number("volumeMax",100)));
                    galleryVolume=Math.max(min,Math.min(max,galleryVolume));
                }
                GalleryCatalog.Page next=gallery.page(screen,group,page,screen==GalleryCatalog.Screen.MUSIC?gallerySong:index,handle,galleryVolume);
                if (galleryPage==null || galleryPage.screen!=screen) enterGalleryScreen(next);
                galleryRequest=request; publishGallery(next);
            } catch (IOException | RuntimeException e) {
                noticeFailure(R.string.engine_systemnnn_host_gallery_failed,e);
            }
        });
    }
    void requestCgFrame(GalleryCatalog.Page page,int frame,java.util.function.Consumer<android.graphics.Bitmap> done){
        tasks.post(()->done.accept(active&&onTitle&&!failed&&!moviePlaying&&handle!=0&&gallery!=null&&galleryPage==page
            ?gallery.cgFrame(page,frame,handle):null));
    }
    private void enterGalleryScreen(GalleryCatalog.Page next) {
        try {
            if (next.screen==GalleryCatalog.Screen.MUSIC) {
                NativeGame.stopGalleryMusic(handle);
                gallerySpectrum=next.config.number("spectrumPrintFlag",0)!=0?new MusicSpectrum(next.config,rate):null;
                galleryCue=-1;
                return;
            }
            if (isMusicGallery()) {
                NativeGame.stopGalleryMusic(handle);
                galleryMusicPlaying=galleryMusicPaused=false; gallerySong=-1;
                gallerySpectrum=null;
            }
            boolean omake=next.screen==GalleryCatalog.Screen.OMAKE;
            int number=next.config.number("bgmNumber",omake?1:-1);
            boolean restart=next.config.number("bgmRestartFlag",omake?1:0)!=0;
            if (restart || (number>0 && number!=galleryCue)) {
                NativeGame.menuMusic(handle,number,next.config.number("bgmLoop",0),next.config.number("bgmVolume",0),
                    next.config.number("bgmFadeInTime",-1),restart);
                galleryCue=number>0?number:-1;
            }
        } catch (RuntimeException e) { Log.w("SystemNNN","Gallery menu music unavailable",e); }
    }
    void playGalleryMovie(int number){tasks.post(()->{
        if(!onTitle||moviePlaying||galleryPage==null||galleryPage.screen!=GalleryCatalog.Screen.MOVIE)return;
        GalleryCatalog.Movie movie=gallery.movie(number);
        if(movie==null||!movie.unlocked(NativeGame.galleryState(handle)))return;
        if(movie.file==null){ui.notice(text(R.string.engine_systemnnn_host_movie_missing));return;}
        NativeGame.stopGalleryMusic(handle);
        NativeGame.stopScriptAudio(handle);
        galleryCue=-1;
        startMovie(movie);
    });}
    private GalleryCatalog.MovieList movies() throws IOException {
        if(movieList==null)movieList=new GalleryCatalog.MovieList(root,codec);
        return movieList;
    }
    private void startStoryMovie() {
        try{startMovie(movies().find(NativeGame.movieNumber(handle)));}
        catch(IOException e){Log.w("SystemNNN","Movie configuration unavailable",e);startMovie(null);}
    }
    private void startMovie(GalleryCatalog.Movie movie){
        moviePlaying=true;
        tasks.removeCallbacks(ticker);stopSound();
        if(movie==null||movie.file==null){
            tasks.post(()->completeMovie(MoviePlayback.Outcome.FAILED,text(R.string.engine_systemnnn_host_movie_missing)));
            return;
        }
        Log.i("SystemNNN","Movie requested "+movie.file.getName()+" gallery="+onTitle);
        ui.movie(movie.file,NativeGame.movieVolume(handle),movieList.print.number("noSkipFlag",0)==0,false);
    }
    void finishMovie(MoviePlayback.Outcome outcome,String failure){tasks.post(()->completeMovie(outcome,failure));}
    private void completeMovie(MoviePlayback.Outcome outcome,String failure){
        if(!moviePlaying||closing)return;
        moviePlaying=false;
        if(outcome==MoviePlayback.Outcome.FAILED){
            Log.w("SystemNNN","Movie playback failed: "+failure);
            ui.notice(text(R.string.engine_systemnnn_host_movie_failed));
        }else Log.i("SystemNNN","Movie playback "+outcome);
        if(startupStage==StartupStage.MOVIE){
            Capture exit=movieExitScreen();
            long wait=DialogueLayout.clamp(startupConfig.number("lastWait",5),0,1000)*(long)Math.max(1,meta[5]);
            tasks.postDelayed(()->advanceStartup(exit),wait);
            return;
        }
        if(onTitle){
            if(galleryPage!=null)enterGalleryScreen(galleryPage);
        }else try{
            // The movie interrupted an existing journal tick. Resume its VM
            // continuation without adding a second input or advancing time.
            boolean needsClock=meta[NativeGame.CLOCK_DATE]==0;
            NativeGame.tick(handle,0,x,y,false,0,false,meta);
            if(history!=null&&needsClock&&meta[NativeGame.CLOCK_DATE]!=0)
                history.recordMovieContinuationClock(meta[NativeGame.CLOCK_DATE],meta[NativeGame.CLOCK_MILLISECONDS]);
            queueGalleryWrite();
            if(finishEndedRun())return;
            if(meta[0]==NativeGame.MOVIE)startStoryMovie();
            else{syncModes();updateUi();}
        }catch(Exception e){broken(e);}
        schedule();
    }
    void closeGallery() {
        tasks.post(() -> {
            if (!onTitle || galleryPage==null) return;
            if (isMusicGallery()) NativeGame.stopGalleryMusic(handle);
            int titleCue=currentTitle.musicNumber();
            if (currentTitle.musicRestarts() || (titleCue>0 && galleryCue!=titleCue)) {
                try { currentTitle.restoreMusic(handle); galleryCue=titleCue>0?titleCue:-1; }
                catch (RuntimeException e) { Log.w("SystemNNN","Unable to restore title music",e); }
            }
            galleryPage=null;
            galleryMusicPlaying=galleryMusicPaused=false; gallerySong=-1;gallerySpectrum=null;
            gallery.releaseImages();
        });
    }
    private boolean isMusicGallery() { return onTitle && galleryPage!=null && galleryPage.screen==GalleryCatalog.Screen.MUSIC; }
    private void publishGallery(GalleryCatalog.Page page){
        page.musicState=galleryMusicPaused?2:galleryMusicPlaying?1:0;
        galleryPage=page;ui.gallery(page,galleryRequest);
    }
    private void refreshMusic(int page){
        try{publishGallery(gallery.page(GalleryCatalog.Screen.MUSIC,0,page,gallerySong,handle,galleryVolume));}
        catch(IOException e){Log.w("SystemNNN","Unable to refresh music gallery",e);}
    }
    void playGallerySong(int index) { tasks.post(() -> playSong(index)); }
    private void playSong(int index) {
        if (!isMusicGallery() || index<0 || index>=gallery.songCount()) return;
        GalleryCatalog.Song song=gallery.song(index);
        if (!song.unlocked(NativeGame.galleryState(handle),handle,galleryPage.config.number("checkListen",0)!=0)) return;
        try {
            if (gallerySong==index && galleryMusicPlaying) NativeGame.pauseGalleryMusic(handle,false);
            else NativeGame.playGalleryMusic(handle,GalleryCatalog.raw(song.name),
                galleryPage.config.number("bgmLoopFlag",0)!=0?0:1,galleryVolume);
            gallerySong=index; galleryMusicPlaying=true; galleryMusicPaused=false;
            refreshMusic(index/galleryPage.rows);
        } catch (RuntimeException e) { noticeFailure(R.string.engine_systemnnn_host_music_failed,e); }
    }
    void moveGallerySong(int direction) {
        tasks.post(() -> {
            if (!isMusicGallery() || gallery.songCount()==0) return;
            int[] keys=NativeGame.galleryState(handle);
            boolean check=galleryPage.config.number("checkListen",0)!=0;
            int count=gallery.songCount();
            int start=gallerySong>=0?gallerySong:galleryPage.page*galleryPage.rows-direction;
            for (int i=1;i<=count;i++) {
                int index=Math.floorMod(start+i*direction,count);
                if (gallery.song(index).unlocked(keys,handle,check)) { playSong(index); return; }
            }
        });
    }
    void pauseGallerySong() { tasks.post(() -> {
        if (isMusicGallery() && galleryMusicPlaying) {
            galleryMusicPaused=!galleryMusicPaused; NativeGame.pauseGalleryMusic(handle,galleryMusicPaused);
            refreshMusic(galleryPage.page);
        }
    }); }
    void stopGallerySong() { tasks.post(() -> {
        if (isMusicGallery()) { NativeGame.stopGalleryMusic(handle); galleryMusicPlaying=galleryMusicPaused=false;refreshMusic(galleryPage.page); }
    }); }
    void gallerySongVolume(int volume) { tasks.post(() -> {
        if (isMusicGallery()) { galleryVolume=volume; NativeGame.galleryMusicVolume(handle,volume); }
    }); }
    void replayGallery(int group,int index) {
        tasks.post(() -> {
            if (!onTitle || galleryPage==null || galleryPage.screen!=GalleryCatalog.Screen.SCENE) return;
            long candidate=0;
            int[] state=new int[NativeGame.META_SIZE];
            long stored;
            try {
                GalleryCatalog.Scene scene=gallery.scene(group,index);
                if (!scene.unlocked(NativeGame.galleryState(handle))) return;
                writeGallery();
                long token=runtime.candidate();
                try {
                    candidate=NativeGame.openReplay(root.getPath().getBytes(StandardCharsets.UTF_8),rate,token,
                        GalleryCatalog.raw(scene.script),scene.filmLabels(),sceneLayers(scene));
                } finally { if (token!=0) NativeGame.releaseContext(token); }
                configure(candidate);
                NativeGame.setParameters(candidate,new int[]{6,0});
                stored=restoreGallery(candidate);
                NativeGame.tick(candidate,0,0,0,false,0,false,state);
            } catch (RuntimeException e) {
                if (candidate!=0) NativeGame.close(candidate);
                noticeFailure(R.string.engine_systemnnn_host_replay_failed,e); return;
            }
            tasks.removeCallbacks(ticker); stopSound(); NativeGame.close(handle);
            offerScreenLayer(-1);
            handle=candidate; galleryWritten=stored; onTitle=false; replaying=true; menuOpen=false; history=null;
            current=null; shownWait=-1; x=y=0;
            System.arraycopy(state,0,meta,0,meta.length);
            bindSurface(true);
            ui.galleryReplayStarted(); queueGalleryWrite();
            if (meta[0]==NativeGame.END) finishReplay();
            else { updateUi(); schedule(); }
        });
    }
    private int sceneLayers(GalleryCatalog.Scene scene) {
        GameUiAssets.Config cfg=galleryPage.config;
        int policy=cfg.number("overWriteLayerOff",0);
        if (policy==1) {
            int mask=0;
            for (int layer=0;layer<32;layer++) {
                String name=uiAssets.game.value("layerOffVar"+layer,"");
                if (!name.isEmpty() && NativeGame.galleryVariable(handle,GalleryCatalog.raw(name))!=0) mask|=1<<layer;
            }
            return mask;
        }
        if (policy==2) {
            String name=cfg.value("overwriteLayerOffVar","");
            if (!name.isEmpty() && NativeGame.galleryVariable(handle,GalleryCatalog.raw(name))==0) return 0;
        }
        return scene.hiddenLayers;
    }
    void endGalleryReplay() { tasks.post(() -> { if (replaying) finishReplay(); }); }
    private void finishReplay() {
        try { openTitle(); ui.galleryReplayFinished(); }
        catch (Exception e) { broken(e); }
    }
    private boolean finishEndedRun() throws IOException {
        if(meta[0]!=NativeGame.END)return false;
        if(replaying){finishReplay();return true;}
        if(!NativeGame.storyExited(handle))return false;
        Capture previous=null;
        try{previous=captureTitleFrame();}
        catch(RuntimeException e){Log.w("SystemNNN","Unable to capture the final story frame",e);}
        String location=GameResourcePaths.displayScript(NativeGame.content(handle)[0])+" word="+meta[1];
        try{
            saveResult(writeReadLines());
            Log.i("SystemNNN","Story EXIT persisted system progress at "+location);
        }catch(IOException e){saveFailed(e);}
        openTitle(previous);
        return true;
    }
    void requestBacklog(){tasks.post(()->{
        if(handle==0||onTitle||failed)return;
        NativeGame.suspendScriptAudio(handle);
        byte[][] raw=NativeGame.backlog(handle);List<BacklogLine> lines=new ArrayList<>(raw.length/7);
        long[] jumps=NativeGame.backlogJumpIds(handle);
        for(int i=0;i+6<raw.length;i+=7){
            List<byte[]> voices=new ArrayList<>(4);
            for(int j=i+2;j<i+6;j++)if(raw[j].length!=0)voices.add(raw[j]);
            byte[] style=raw[i+6];
            int color=style.length>=3?(style[0]&255)<<16|(style[1]&255)<<8|(style[2]&255):-1;
            lines.add(new BacklogLine(decode(raw[i]),decode(raw[i+1],style.length==4),voices,color,jumps[i/7]));
        }
        ui.backlog(lines);
    });}
    void replayBacklogVoice(byte[] name){tasks.post(()->{
        if(handle==0||!menuOpen)return;
        try{NativeGame.replayBacklogVoice(handle,name);}
        catch(RuntimeException e){Log.w("SystemNNN","Backlog voice unavailable",e);}
    });}
    void closeBacklog(){tasks.post(()->{
        if(handle==0)return;
        playPanelSound(uiAssets.backlog,"returnSoundNumber",0);
    });}
    int configMask(){return configMask;}
    void backlogJumpCue(){tasks.post(()->{
        if(handle==0||!menuOpen)return;
        GameUiAssets.Config cfg=uiAssets.backlog;
        int theme=cfg.number("jumpVoiceFlag",0)>0?NativeGame.auxiliaryState(handle)[0]:0;
        if(theme<=0)playPanelSound(cfg,"jumpButtonClickSound",0);
        else {
            int voice=cfg.number("jumpClickVoiceNumber"+theme,0);
            if(voice>0)NativeGame.playButtonVoice(handle,voice-1);
        }
    });}
    void jumpBacklog(long id,java.util.function.Consumer<Boolean> done){tasks.post(()->{
        GameSave prefix;
        try{
            int[] point=handle!=0&&menuOpen&&!onTitle&&!replaying&&!failed&&history!=null
                ?NativeGame.backlogJump(handle,id):null;
            if(point==null||point.length!=5){
                ui.notice(text(R.string.engine_systemnnn_host_backlog_expired));
                done.accept(false);
                return;
            }
            prefix=history.rewind(point[0],point[1],point[3],point[4]);
            recordReplayBaseline(prefix,handle,point[2]);
            if(!NativeGame.restoreBacklogJump(handle,id,point[0],meta))throw new IOException(text(R.string.engine_systemnnn_host_backlog_restore_failed));
        }catch(IOException|RuntimeException e){
            noticeFailure(R.string.engine_systemnnn_host_backlog_restore_failed,e);
            done.accept(false);
            return;
        }
        // Both the engine and the staged journal have passed validation.
        history=prefix;
        GameSave.Run last=history.lastInput();x=last.x;y=last.y;
        choiceClicking=false;shownWait=-1;current=null;setModes(false,false);
        publishParameters();ui.loaded();updateUi();
        Log.i("SystemNNN","Backlog jump restored line="+meta[2]+" word="+meta[1]+" cursor="+history.cursor());
        done.accept(true);
    });}
    SaveSlots.Entry[] slotEntries(){return slots.entries();}
    Integer lastSelectedSlot(){return slots.lastSelectedSlot();}
    void saveSlot(int slot){tasks.post(() -> save(slot,true,()->playPanelSound(uiAssets.save,"saveSound",0),1));}
    void loadSlot(int slot){loadSlot(slot,null);}
    void loadSlot(int slot,java.util.function.Consumer<Boolean> done){tasks.post(()->loadSlot(slot,false,done));}
    private void loadSlot(int slot,boolean quick,java.util.function.Consumer<Boolean> done){
        boolean success=false;
        try{
            open(slots.file(slot),done!=null);
            success=true;
            if(slot!=SaveSlots.AUTO)SAVE_FILES.execute(()->rememberSlot(slot));
            if(quick)quickNotice(false);else if(done==null)playPanelSound(uiAssets.load,"loadSound",0);
        }catch(FileNotFoundException e){
            if(!quick)ui.notice(text(R.string.engine_systemnnn_host_slot_missing));
        }catch(Exception e){
            noticeFailure(R.string.engine_systemnnn_host_load_failed,e);
        }finally{
            if(!success){
                if(done==null&&menuOpen)menu(false);else schedule();
            }
            if(done!=null)done.accept(success);
        }
    }
    void quickSave(){tasks.post(() -> {
        if(!onTitle&&quickEnabled(true))
            save(SaveSlots.QUICK,true,()->quickNotice(true),uiAssets.save.number("quickSetLastFlag",0));
    });}
    void quickLoad(){tasks.post(() -> {if(!onTitle&&quickEnabled(false))loadSlot(SaveSlots.QUICK,true,null);});}
    private boolean quickEnabled(boolean saving){
        int enabled=uiAssets.game.number("quickSaveEnable",0);
        return (saving?enabled:uiAssets.game.number("quickLoadEnable",enabled))!=0;
    }
    private void publishQuickSlot(){
        SaveSlots.Entry slot=slots.entry(SaveSlots.QUICK);
        ui.quickSlot(slot.exists&&!slot.corrupt);
    }
    private void quickNotice(boolean saving){
        if(uiAssets.game.number("noticeFlag",0)==0||uiAssets.game.number(saving?"quickSavePrint":"quickLoadPrint",1)==0)return;
        int number=uiAssets.game.number(saving?"saveNoticeNumber":"loadNoticeNumber",saving?2:1);
        ui.quickNotice(number,meta[5]);
    }
    private void playPanelSound(GameUiAssets.Config config,String key,int fallback){
        int cue=config.number(key,fallback);
        if(cue>0)NativeGame.playSystemSe(handle,cue-1);
    }
    void restart(boolean replaceResume){tasks.post(()->{
        if(closing)return;
        try{
            if(!initialized)initialize();
            else if(replaceResume){open(null,false);save(SaveSlots.AUTO,false,null,0);}
        }catch(Exception|LinkageError e){broken(e);}
    });}
    void recoverCorruptProgress(){tasks.post(()->{
        if(closing||initialized||saveAccess==null)return;
        try{
            saveResult(SAVE_FILES.submit(()->{GameProgress.recoverCorruptBase(readFile);return null;}));
            initialize();
        }catch(Exception|LinkageError e){broken(e);}
    });}
    private CompletableFuture<Void> save(int slot,boolean notify,Runnable feedback,int marker){
        try{
            CompletableFuture<Void> write=queueSlotWrite(slot,notify,feedback,marker);
            if(write==null)return null;
            if(slot==SaveSlots.AUTO)autoWrite=write;
            write.whenComplete((unused,error)->{if(error!=null)saveFailed(error);});
            return writeReadLines();
        }catch(IOException|RuntimeException e){saveFailed(e);return null;}
    }
    private CompletableFuture<Void> queueSlotWrite(int slot,boolean notify,Runnable feedback,int marker) throws IOException {
        if(handle==0||failed||onTitle||history==null||!history.writable()||meta[0]==NativeGame.MOVIE||
            (meta[0]==NativeGame.MODAL&&!NativeGame.modalSavable(handle))||meta[0]==NativeGame.END){
            if(notify)ui.notice(text(R.string.engine_systemnnn_host_cannot_save));
            return null;
        }
        long started=SystemClock.uptimeMillis();
        if(feedback!=null)feedback.run();
        GameSave.Loaded data=history.capture(slot,handle,meta,current==null?"":current.speaker,current==null?"":current.body);
        Integer art=slotArtwork(slot);
        Capture picture=slotPicture();
        long captureMs=SystemClock.uptimeMillis()-started;
        return CompletableFuture.runAsync(()->{
            try{writeSlot(data,picture,captureMs,art,marker);}
            catch(IOException e){throw new CompletionException(e);}
        },SAVE_FILES);
    }
    private Integer slotArtwork(int slot){
        if(slot==SaveSlots.AUTO||uiAssets==null||uiAssets.dataFile.number("addPicPrintFlag",0)==0)return null;
        try{return NativeGame.saveSlotArt(handle);}
        catch(RuntimeException|LinkageError e){Log.w("SystemNNN","Unable to read save slot artwork number",e);return null;}
    }
    private Capture slotPicture(){
        try{
            int w=Math.max(1,Math.min(512,uiAssets.dataFile.number("picSizeX",182)));
            int h=Math.max(1,Math.min(512,uiAssets.dataFile.number("picSizeY",102)));
            return new Capture(NativeGame.saveThumbnail(handle,w,h),w,h);
        }catch(RuntimeException e){Log.w("SystemNNN","Unable to capture slot preview",e);return null;}
    }
    private void writeSlot(GameSave.Loaded data,Capture picture,long captureMs,Integer art,int marker) throws IOException {
        long started=SystemClock.uptimeMillis();
        GameSave.Metadata at=data.metadata;
        GameSave.write(slots.file(at.slot),data);
        Bitmap thumbnail=null;
        if(picture!=null)try{
            thumbnail=slots.writePicture(at.slot,at.savedAt,picture.width,picture.height,picture.pixels);
        }catch(IOException|RuntimeException e){Log.w("SystemNNN","Unable to save slot preview",e);}
        slots.saved(at,thumbnail);
        if(at.slot!=SaveSlots.AUTO){
            try{slots.recordWritten(at,art,marker);}
            catch(IOException|RuntimeException e){Log.w("SystemNNN","Unable to save slot display metadata",e);}
            rememberSlot(at.slot);
        }
        if(!closing){
            if(at.slot==SaveSlots.QUICK)publishQuickSlot();
            ui.slotsChanged();
        }
        Log.i("SystemNNN","Saved slot="+at.slot+" script="+at.script+" line="+at.line+" word="+at.word+
            " captureMs="+captureMs+" writeMs="+(SystemClock.uptimeMillis()-started)+
            " snapshotBytes="+(data.nativeSnapshot==null?0:data.nativeSnapshot.length));
    }
    private void rememberSlot(int slot){
        try{slots.remember(slot);}
        catch(IOException|RuntimeException e){Log.w("SystemNNN","Unable to remember last selected slot",e);}
    }
    private void saveFailed(Throwable error){
        if(error instanceof CompletionException&&error.getCause()!=null)error=error.getCause();
        Log.w("SystemNNN","Unable to save progress",error);
        if(!closing)ui.notice(text(R.string.engine_systemnnn_host_save_failed));
    }
    private void noticeFailure(int resource,Throwable failure){
        String message=text(resource);
        Log.w("SystemNNN",message,failure);
        ui.notice(message);
    }
    private String text(int resource,Object... arguments){return context.getString(resource,arguments);}
    private <T> T saveResult(Future<T> work) throws IOException {
        try{return work.get();}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(text(R.string.engine_systemnnn_host_save_wait_interrupted),e);}
        catch(ExecutionException e){
            if(e.getCause() instanceof IOException)throw (IOException)e.getCause();
            throw new IOException(text(R.string.engine_systemnnn_host_save_io_failed),e.getCause());
        }
    }
    // The original switches out of the story mode while a modal is active.
    // Keep the audio consumer running, but do not step or journal story time.
    private boolean playing(){
        return active&&surfaceBinding!=0&&!failed&&!moviePlaying&&startupStage!=StartupStage.MOVIE&&handle!=0&&meta[0]!=NativeGame.END;
    }
    private void schedule(){
        tasks.removeCallbacks(ticker);
        if(!playing()){stopSound();return;}
        if(audio==null)audio=new DeviceAudio(context,tasks,handle,rate,()->audioForeground&&!closing,
            ()->ui.notice(text(R.string.engine_systemnnn_host_audio_focus_unavailable)),(source,error)->tasks.post(()->{
                if(audio==source&&!closing)broken(new IOException(error));
            }));
        deadline=lastTickAt=SystemClock.uptimeMillis();tasks.post(ticker);
    }
    private final Runnable ticker=new Runnable(){public void run(){
        if(!playing())return;
        int period=Math.max(1,meta[5]);long nowTick=SystemClock.uptimeMillis();int elapsed=(int)Math.min(1000,Math.max(0,nowTick-lastTickAt));lastTickAt=nowTick;boolean advance=false;
        tickModal(elapsed,period);
        if(!onTitle&&!menuOpen){
            if(meta[0]==NativeGame.DIALOGUE){NativeGame.textCharacters(handle,elapsed);publishText();}
            boolean autoReady=meta[0]==NativeGame.DIALOGUE&&NativeGame.autoReady(handle,elapsed,textDone);
            syncModes();
            boolean skipping=skipMode&&NativeGame.skipEffective(handle);
            if(skipping)advance=meta[0]==NativeGame.DIALOGUE||meta[0]==NativeGame.DRAW||meta[0]==NativeGame.OVERLAP;
            else if(meta[0]==NativeGame.DIALOGUE)advance=autoReady;
        }
        int titleSteps=0;
        if(onTitle){
            long late=Math.max(0,nowTick-deadline);
            titleSteps=(int)Math.min(UiPreviewPool.stepLimit(uiAssets.game),1+late/period);
            deadline=nowTick-late%period+period;
        }
        if(startupStage==StartupStage.OPENING&&!titleBackgroundHeld){
            int nextPeriod;
            try{nextPeriod=NativeGame.advanceOpening(handle,titleSteps);}
            catch(RuntimeException e){Log.w("SystemNNN","Startup opening interrupted",e);nextPeriod=0;}
            if(nextPeriod==0){
                Log.i("SystemNNN","Startup opening finished");
                advanceStartup(null);
                return;
            }
            if(nextPeriod!=period){meta[5]=nextPeriod;deadline=nowTick+nextPeriod;}
        }else if(!menuOpen&&!choiceClicking&&meta[0]!=NativeGame.MODAL&&
                (!onTitle||!titleBackgroundHeld&&!titleRestartPending)){
            long before=handle;
            step(onTitle?period*titleSteps:elapsed,advance,0);
            if(handle!=before)return; // The new title session scheduled its own first tick.
            if(onTitle)checkTitleRestart(titleSteps);
        }
        if(isMusicGallery()&&galleryMusicPlaying&&!galleryMusicPaused&&gallerySpectrum!=null){
            if(NativeGame.musicWave(handle,gallerySpectrum.wave))ui.spectrum(gallerySpectrum.levels());
            else{galleryMusicPlaying=false;refreshMusic(galleryPage.page);}
        }
        publishStatusOverlays();
        if(!playing()){stopSound();return;}
        if(!onTitle)deadline=Math.max(deadline+period,SystemClock.uptimeMillis()-period);
        tasks.postAtTime(this,deadline);
    }};
    private void tickModal(int elapsed,int period){
        Modal modal=activeModal();
        if(modal==null||(modal.mode!=Modal.PRINT_RESULT&&!modal.statusView())){modalTickRequest=0;return;}
        if(modal.request!=modalTickRequest){modalTickRequest=modal.request;modalTickRemainder=0;}
        modalTickRemainder+=elapsed;
        int frames=modalTickRemainder/period;
        modalTickRemainder%=period;
        if(frames>0)try{NativeGame.advanceModal(handle,modal.request,frames);}
        catch(RuntimeException e){Log.w("SystemNNN","Unable to advance optional mode clock",e);}
    }
    private void step(int ms,boolean advance,int choice){
        try{
            if(!onTitle&&!replaying&&advance)markRead();
            boolean recordedSkip=NativeGame.skipActive(handle);
            boolean timedSelection=meta[0]==NativeGame.CHOICE&&current!=null&&current.choiceInfo[0]>0;
            NativeGame.tick(handle,ms,x,y,advance,choice,false,meta);
            queueGalleryWrite();
            if(onTitle)return;
            if(finishEndedRun())return;
            scriptUiEvents();
            if(history!=null)history.appendWithClock(ms,x,y,advance,
                timedSelection&&NativeGame.choiceTimedOut(handle)?GameSave.TIMED_OUT:choice,recordedSkip,
                meta[NativeGame.CLOCK_DATE],meta[NativeGame.CLOCK_MILLISECONDS]);
            if(meta[0]==NativeGame.MOVIE){startStoryMovie();return;}
            syncModes();updateUi();
        }catch(Exception e){
            if(replaying){noticeFailure(R.string.engine_systemnnn_host_replay_interrupted,e);finishReplay();}
            else broken(e);
        }
    }
    private void scriptUiEvents(){
        long events=NativeGame.takeScriptUiEvents(handle);
        if((events>>>32)!=0)writeReadLines();
        if((int)events>0)ui.quickNotice((int)events,meta[5]);
    }
    private String decode(byte[] raw){return decode(raw,false);}
    private String decode(byte[] raw,boolean configuration){
        try{return (configuration?codec.decodeConfiguration(raw):codec.decode(raw)).replace("\r\n","\n").replace('\r','\n');}
        catch(IOException e){Log.w("SystemNNN","Unable to decode dialogue",e);return new String(raw,Charset.forName("windows-31j"));}
    }
    private void publishText(){
        if(current==null||meta[0]!=NativeGame.DIALOGUE)return;int[] state=NativeGame.textState(handle);textDone=state[2]!=0;
        if(visibleText!=state[0]){visibleText=state[0];ui.text(current,state[0],state[1]);}
    }
    private Modal readModal(){
        int[] info=NativeGame.modalInfo(handle);
        int[] view=NativeGame.modalView(handle);
        byte[][] raw=NativeGame.modalRows(handle);
        if(info==null||info.length<3||view==null||view.length<5||((view.length-5)&1)!=0||
            raw==null||raw.length!=3*((view.length-5)/2))throw new IllegalStateException("Script modal metadata missing");
        Modal.Row[] rows=new Modal.Row[raw.length/3];
        List<String> images=new ArrayList<>();
        for(int i=0;i<rows.length;i++){
            String image=new String(raw[3*i+1],StandardCharsets.ISO_8859_1);
            rows[i]=new Modal.Row(view[5+2*i],view[6+2*i],decode(raw[3*i],true),image,decode(raw[3*i+2],true));
            if(!image.isEmpty())images.add(image);
        }
        GameUiAssets.ModeAssets data=null;
        ChartView chart=null;
        String problem=null;
        Modal previous=onTitle?menuModal:current==null?null:current.modal;
        int mode=info[1];
        try{
            if(mode==Modal.SELECT_CHART){
                chart=new ChartView(NativeGame.chartGeometry(handle),NativeGame.chartText(handle),rawText->decode(rawText,true));
            }
            boolean sameArt=previous!=null&&previous.mode==mode&&previous.assets!=null&&mode!=Modal.PRINT_HINT&&
                (mode!=Modal.PRINT_RESULT||previous.page==view[0]&&previous.selection==view[3])&&
                (mode!=Modal.SELECT_OBJECT||previous.group==view[2])&&
                (mode!=Modal.PRINT_ITEM&&mode!=Modal.SHOP||sameRowImages(previous.rows,rows))&&
                (mode!=Modal.SELECT_CHART||previous.chart!=null&&previous.chart.images().equals(chart.images()));
            if(sameArt)data=previous.assets;
            else switch(mode){
            case Modal.NAME_INPUT:data=uiAssets.modeAssets(handle,codec,"nameInput","ta_sei_dialog","ta_mei_dialog");break;
            case Modal.KANJI_INPUT:data=uiAssets.modeAssets(handle,codec,"kanjiInput");break;
            case Modal.SELECT_HINT:data=uiAssets.modeAssets(handle,codec,"selectHint","ta_backButton");break;
            case Modal.PRINT_HINT:
                images.addAll(Arrays.asList("ta_backButton","ta_upButton","ta_downButton"));
                data=uiAssets.modeAssets(handle,codec,"printHint",key->!key.matches("hint[0-9]+filename[0-9]+"),images);
                break;
            case Modal.SELECT_ZUKAN:data=uiAssets.modeAssets(handle,codec,"selectZukan","ta_backButton","ta_anime");break;
            case Modal.SELECT_HEROIN:
                images.addAll(Arrays.asList("bg_selectheroin",uiAssets.game.value("UpFileName","ta_upButton"),
                    uiAssets.game.value("DownFileName","ta_downButton"),"ta_upButton","ta_downButton"));
                data=uiAssets.modeAssets(handle,codec,"selectHeroin",key->true,images);
                break;
            case Modal.SELECT_OBJECT:
                images.add("idou_bg");
                data=uiAssets.variantModeAssets(handle,codec,"selectObject","selectobjecttype"+view[2],images);
                break;
            case Modal.PRINT_TERM:case Modal.LISTEN_VOICE:
                String config=info[1]==Modal.PRINT_TERM?"printTerm":"listenVoice";
                data=uiAssets.modeAssets(handle,codec,config,uiAssets.game.value("BackFileName","ta_backButton"),
                    uiAssets.game.value("UpFileName","ta_upButton"),uiAssets.game.value("DownFileName","ta_downButton"),"ta_anime");
                break;
            case Modal.PRINT_ACHIEVEMENT:
                data=uiAssets.modeAssets(handle,codec,"printAchievement",uiAssets.game.value("BackFileName","ta_backButton"),"ta_anime");
                break;
            case Modal.PRINT_RESULT:
                String plate="";
                for(Modal.Row row:rows)if((row.flags&Modal.NEW)==0&&!row.asset.isEmpty()){plate=row.asset;break;}
                images.addAll(Arrays.asList(uiAssets.game.value("BackFileName","ta_backButton"),
                    uiAssets.game.value("UpFileName","ta_upButton"),uiAssets.game.value("DownFileName","ta_downButton"),"ta_font_sl3"));
                data=uiAssets.modeAssets(handle,codec,"printResult",key->true,images,plate);
                break;
            case Modal.PRINT_ITEM:case Modal.SHOP:
                boolean shop=mode==Modal.SHOP;
                images.addAll(Arrays.asList(shop?"ta_noshopitem":"ta_noitem",shop?"ta_shopitem_cursor":"ta_haveitem_cursor",
                    shop?"ta_shopitem_plate":"ta_haveitem_plate",uiAssets.game.value("BackFileName","ta_backButton"),
                    uiAssets.game.value("UpFileName","ta_upButton"),uiAssets.game.value("DownFileName","ta_downButton")));
                data=uiAssets.modeAssets(handle,codec,shop?"shop":"printItem",key->true,images);
                break;
            case Modal.PRINT_STATUS:case Modal.PRINT_PARTY:
                data=uiAssets.statusAssets(handle,codec,mode==Modal.PRINT_PARTY);
                break;
            case Modal.SELECT_CHART:
                data=uiAssets.chartAssets(handle,codec,chart,previous!=null&&previous.mode==mode?previous.assets:null);
                break;
            case Modal.SELECT_PLACE2:
                data=new GameUiAssets.ModeAssets(uiAssets.place(mode),java.util.Collections.emptyMap());
                break;
            default:problem="Unknown original mode "+info[1];
            }
            if(data!=null)problem=missingModalResource(mode,view[3],rows,data);
        }catch(IOException|RuntimeException e){
            Log.w("SystemNNN","Unable to load mode "+info[1],e);
            problem="Cannot load mode setup: "+e.getMessage();
        }
        int[] limits=NativeGame.modalLimits(handle);
        byte[][] texts=NativeGame.modalText(handle);
        Modal loaded=new Modal(info,limits,texts,view,rows,data,codec,chart,problem);
        if(problem==null&&loaded.mode==Modal.PRINT_RESULT&&loaded.selection!=0)
            problem=GameProgressViewsPanel.confirmationFailure(uiAssets,loaded);
        if(problem==null&&loaded.advancedSelection())problem=GameAdvancedSelectionPanel.selectionFailure(uiAssets,loaded);
        if(problem==null&&loaded.inventory())problem=GameInventoryPanel.inventoryFailure(uiAssets,loaded);
        if(problem==null&&loaded.statusView())problem=GameStatusPartyPanel.statusPartyFailure(uiAssets,loaded);
        if(problem==null&&loaded.mode==Modal.SELECT_CHART)problem=GameChartPanel.chartFailure(uiAssets,loaded,meta[3],meta[4]);
        if(problem!=null&&loaded.problem==null)return new Modal(info,limits,texts,view,rows,data,codec,chart,problem);
        return loaded;
    }
    private static boolean sameRowImages(Modal.Row[] before,Modal.Row[] after){
        if(before.length!=after.length)return false;
        for(int i=0;i<before.length;i++)if(!before[i].asset.equals(after[i].asset))return false;
        return true;
    }
    private Bitmap modalImage(GameUiAssets.ModeAssets data,String name){
        Bitmap sprite=data.images.get(GameUiAssets.systemImageKey(name));
        return sprite==null?uiAssets.image(name):sprite;
    }
    private String missingModalResource(int mode,int selected,Modal.Row[] rows,GameUiAssets.ModeAssets data){
        GameUiAssets.Config config=data.config;
        if(mode==Modal.SELECT_PLACE2&&config.number("backScriptFlag",0)!=0&&UiPreviewPool.Request.read(config,mode,0)==null)
            return "Missing place background script";
        if(mode==Modal.PRINT_HINT){
            for(Modal.Row row:rows)if(modalImage(data,row.asset)!=null)return null;
            if(rows.length>0&&modalImage(data,config.value("nopicFilename",""))!=null)return null;
            return "No displayable hint image on this page";
        }
        if(mode==Modal.SELECT_ZUKAN){
            Bitmap seat=modalImage(data,config.value("filenameSeat",""));
            int width=DialogueLayout.clamp(config.number("zukanSizeX",16),1,4096);
            int height=DialogueLayout.clamp(config.number("zukanSizeY",16),1,4096);
            if(seat==null||seat.getWidth()<width||seat.getHeight()<height)return "Missing reference atlas";
            if(UiPreviewPool.Request.read(config,mode,selected)==null)return "Missing reference detail script";
        }
        if(mode==Modal.PRINT_RESULT){
            if(selected==0&&rows.length==0)return null;
            for(Modal.Row row:rows)if((selected==0||row.id==selected)&&modalImage(data,row.asset)!=null)return null;
            return selected==0?"Missing result plate":"Missing result acknowledgement image";
        }
        if(mode==Modal.PRINT_ACHIEVEMENT){
            for(Modal.Row row:rows){
                String key="filenameAchievement"+row.id+((row.flags&Modal.SELECTED)!=0?"ok":"not");
                if(modalImage(data,config.value(key,"ta_anime"))!=null)return null;
            }
            Bitmap digits=modalImage(data,config.value("filenameSuuji",""));
            if(rows.length!=0&&config.number("percentPrintFlag",0)!=0&&digits!=null&&
                digits.getWidth()>=10L*Math.max(1,config.number("suujiFontSizeX",16))&&
                digits.getHeight()>=Math.max(1,config.number("suujiFontSizeY",16)))return null;
            return "No displayable achievement artwork or percentage";
        }
        return null;
    }
    private Bitmap[] placePortraits(int mode,int[] characters){
        GameUiAssets.Config config=uiAssets.place(mode);
        String[] names=new String[characters.length];
        LinkedHashSet<String> requested=new LinkedHashSet<>();
        for(int i=0;i<characters.length;i++){
            names[i]=GamePlacePanel.portraitAsset(config,characters[i]);
            if(!names[i].isEmpty())requested.add(GameUiAssets.systemImageKey(names[i]));
        }
        java.util.Map<String,Bitmap> loaded=uiAssets.modeSprites(handle,requested);
        Bitmap[] pictures=new Bitmap[characters.length];
        for(int i=0;i<pictures.length;i++)if(!names[i].isEmpty()){
            pictures[i]=loaded.get(GameUiAssets.systemImageKey(names[i]));
            if(pictures[i]==null)pictures[i]=uiAssets.image(names[i]);
        }
        return pictures;
    }
    long takeScreenLayer(){return pendingScreenLayer.getAndSet(0);}
    private void offerScreenLayer(long layer){
        if(layer==0)return;
        long stale=pendingScreenLayer.getAndSet(layer);
        if(stale>0)NativeGame.releaseScreenLayer(stale);
        ui.screenLayerChanged();
    }
    private void publishScreenLayer(){
        // Keep the outgoing frame until the modal has captured its backdrop.
        if(shownWait!=NativeGame.MODAL)offerScreenLayer(NativeGame.takeScreenLayer(handle));
    }
    private void updateUi(){
        configMask=NativeGame.configMask(handle);
        refreshSystemMenuModes();
        int scriptCursor=meta[NativeGame.SCRIPT_CURSOR];
        int drawMessage=meta[NativeGame.DRAW_MESSAGE];
        if(current!=null&&shownWait==meta[0]&&shownWord==meta[1]&&shownLine==meta[2]&&
            current.scriptCursor==scriptCursor&&current.drawMessage==drawMessage){
            if(shownWait==NativeGame.CHOICE&&current.choiceInfo[0]>0)ui.choiceTime(current,meta[6]);
            publishScreenLayer();
            return;
        }
        shownWait=meta[0];shownWord=meta[1];shownLine=meta[2];
        byte[][] raw=NativeGame.content(handle);
        String script=GameResourcePaths.displayScript(raw[0]);
        int[] properties=NativeGame.dialogueProperties(handle);
        boolean retained=drawMessage==Frame.DRAW_RETAINED_MESSAGE;
        String decoded=shownWait==NativeGame.DIALOGUE||retained?decode(raw[1]):"";
        DialogueLayout.Message message=dialogueStyle.split(decoded,retained||properties[6]==0x22220001);
        String speaker=message.name,body=message.body;
        DialogueMarkup displayBody=dialogueStyle.markup(body);
        String[] options=new String[raw.length-2];for(int i=0;i<options.length;i++)options[i]=decode(raw[i+2]);
        byte[][] extra=shownWait==NativeGame.CHOICE?NativeGame.choiceMessages(handle):new byte[0][];
        String[] messages=new String[extra.length];for(int i=0;i<extra.length;i++)messages[i]=decode(extra[i]);
        int[] choice=shownWait==NativeGame.CHOICE?NativeGame.choiceInfo(handle):new int[4];
        int placeMode=shownWait==NativeGame.PLACE||shownWait==NativeGame.MODAL?NativeGame.placeMode(handle):0;
        int[] places=placeMode!=0?NativeGame.placeStates(handle):new int[0];
        int[] placeCharacters=placeMode!=0?NativeGame.placeCharacters(handle):new int[0];
        Bitmap[] portraits=placeMode!=0?placePortraits(placeMode,placeCharacters):new Bitmap[0];
        Modal modal=shownWait==NativeGame.MODAL?readModal():null;
        Frame next=new Frame(shownWait,meta[3],meta[4],script,shownLine,shownWord,speaker,body,displayBody,options,false,properties[0],properties[1],properties[2],scriptCursor,
            properties[3]!=0,message.nameWindow,properties[4]!=0,properties[5],properties[6],drawMessage,meta[5],messages,choice,places,placeMode,placeCharacters,portraits,modal);
        if(!replaying&&shownWait==NativeGame.DIALOGUE&&!readLines.isEmpty()){
            String previousBody=decode(NativeGame.dialogue(handle)[1]);
            if(readLines.remove(next.legacyReadKey(raw[0],previousBody)))NativeGame.markRead(handle,next.messageId);
        }
        boolean read=next.hasDialogueText()&&
            (retained&&current!=null&&current.hasDialogueText()&&current.messageId==next.messageId?
                current.alreadyRead:NativeGame.isRead(handle,next.messageId));
        current=new Frame(next.wait,next.width,next.height,next.script,next.line,next.word,next.speaker,next.body,next.displayBody,next.choices,
            read,next.messageId,next.windowType,next.fontType,next.scriptCursor,
            next.windowVisible,next.nameWindow,next.optionsAllowed,next.command,next.layoutCommand,next.drawMessage,next.frameMs,messages,choice,places,placeMode,placeCharacters,portraits,modal);
        textDone=false;visibleText=0;
        if(shownWait==NativeGame.DIALOGUE){
            int prefix=-1;
            if(properties[5]==0x22220003){
                String previous=decode(NativeGame.appendPrefix(handle));
                if(!previous.isEmpty()&&decoded.startsWith(previous)){
                    int oldBodyEnd=Math.max(0,previous.length()-(decoded.length()-body.length()));
                    prefix=dialogueStyle.markup(body.substring(0,oldBodyEnd)).glyphCount;
                }
            }
            NativeGame.setTextLength(handle,displayBody.glyphCount,prefix);
            int[] initial=NativeGame.textState(handle);
            visibleText=initial[0];textDone=initial[2]!=0;
            if(history!=null)NativeGame.captureBacklogJump(handle,history.cursor());
        }
        syncModes();
        if(shownWait==NativeGame.DIALOGUE||shownWait==NativeGame.CHOICE)
            Log.i("SystemNNN","script="+script+" line="+shownLine+" word="+shownWord+" speaker="+speaker+" choices="+options.length);
        Log.i("SystemNNN","frame wait="+shownWait+" word="+shownWord+" line="+shownLine+" remaining="+meta[6]+
            " options="+(current.optionsAllowed?1:0)+" window="+current.windowType+" font="+current.fontType);
        if(!systemMenuModal||shownWait!=NativeGame.MODAL||placeMode!=0)ui.frame(current,visibleText);
        publishScreenLayer();
    }
    private void markRead(){if(!replaying&&handle!=0&&current!=null&&current.wait==NativeGame.DIALOGUE)NativeGame.markRead(handle,current.messageId);}
    private void readReadLines() throws IOException {
        setInitialProgress(progressStore.read());
    }
    private void setInitialProgress(GameProgress.State state){
        initialProgress=state;
        readLines.clear();systemStateEstablished=false;
        if(state==null)return;
        readLines.addAll(state.legacy);
        systemStateEstablished=state.variables!=null;
    }
    private void recoverProgress(CompletableFuture<Void> predecessor,ProgressWriteFailure failure) throws IOException {
        if(failure.recovered)readReadLines();
        else{
            progressStore.write(failure.state);
            failure.recovered=true;
            setInitialProgress(failure.state);
        }
        PROGRESS_HANDOFFS.remove(readFile,predecessor);
    }
    private CompletableFuture<Void> writeReadLines(){
        if(handle==0||replaying)return null;
        GameProgress.State state=new GameProgress.State(NativeGame.readState(handle),NativeGame.systemVariables(handle),
            NativeGame.scriptProgress(handle),NativeGame.runtimeText(handle),NativeGame.optionalProgress(handle),
            NativeGame.chartProgress(handle),new ArrayList<>(readLines));
        systemStateEstablished=true;
        CompletableFuture<Void> saved=new CompletableFuture<>();
        CompletableFuture<Void> pending=PROGRESS_HANDOFFS.get(readFile);
        SAVE_FILES.execute(()->{
            try{progressStore.write(state);}
            catch(IOException|RuntimeException e){
                saved.completeExceptionally(new ProgressWriteFailure(state,e));
                Log.w("SystemNNN","Global progress save failed",e);
                if(!closing)ui.notice(text(R.string.engine_systemnnn_host_global_progress_failed));
                return;
            }
            if(pending!=null&&pending.isCompletedExceptionally())PROGRESS_HANDOFFS.remove(readFile,pending);
            saved.complete(null);
        });
        return saved;
    }
    private void stopSound(){
        DeviceAudio stopped=audio;
        audio=null;
        if(stopped!=null)stopped.close();
    }
    private void broken(Throwable reason){
        tasks.removeCallbacks(ticker);failed=true;stopSound();
        Log.e("SystemNNN","Game session failed",reason);
        if(!initialized&&reason instanceof GameProgress.InvalidBase)ui.progressCorrupt();
        else ui.error(reason);
    }
    private void releaseSaveAccess(Closeable playerTask,CompletableFuture<Void> release){
        SAVE_FILES.execute(()->{
            try{if(saveAccess!=null)saveAccess.close();}
            catch(IOException e){Log.w("SystemNNN","Unable to release save directory lock",e);}
            finally{
                saveAccess=null;
                try{if(playerTask!=null)playerTask.close();}
                catch(IOException e){Log.w("SystemNNN","Unable to release player task",e);}
                finally{saveOwner.close();release.complete(null);}
            }
        });
    }
    void close(Closeable playerTask){
        if(closing)return;
        closing=true;
        CompletableFuture<Void> release=new CompletableFuture<>();
        CompletableFuture<Void> handoff=SAVE_RELEASES.merge(readFile,release,CompletableFuture::allOf);
        handoff.whenComplete((done,error)->SAVE_RELEASES.remove(readFile,handoff));
        audioForeground=false;
        replaceSurface(0);
        if(!postProgressFlush(() -> {
            try{
                active=false;tasks.removeCallbacks(ticker);stopSound();writeGallery();
                return writeReadLines();
            }finally{
                try{
                    long unused=takeScreenLayer();
                    if(unused>0)NativeGame.releaseScreenLayer(unused);
                    if(handle!=0){NativeGame.close(handle);handle=0;}
                    if(runtime!=null)runtime.close();
                }finally{
                    try{releaseSaveAccess(playerTask,release);}finally{worker.quitSafely();}
                }
            }
        }))releaseSaveAccess(playerTask,release);
    }
}
