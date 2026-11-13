package com.tyranor.runtime.systemnnn;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.DialogInterface;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.provider.DocumentsContract;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.util.TypedValue;
import android.util.Log;
import android.view.*;
import android.widget.*;
import com.core.engine.EngineUiText;
import com.core.engine.EngineStartupOverlay;
import com.core.engine.LaunchContract;
import com.core.engine.R;
import java.io.Closeable;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class PlayerActivity extends Activity implements GameRunner.Listener {
    @Override
    public void setRequestedOrientation(int requestedOrientation) {
        super.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    }

    private File game;
    private GameRunner runner;
    private FrameLayout root,contentHost,stage;
    private EngineStartupOverlay startupOverlay;
    private final EngineStartupGeneration startupGeneration=new EngineStartupGeneration();
    private TextureView screen;
    private Surface storySurface;
    private Button panelActionButton,otherSavesButton;
    private TextView status;
    private View longPrintBackdrop;
    private volatile StoryScreen storyScreen;
    private DialoguePanel dialoguePanel;
    private GameStoryControls controls;
    private FrameLayout independentStatus;
    private final GameStatusPartyPanel[] independentParts=new GameStatusPartyPanel[2];
    private int independentMode;
    private GameNotice quickNoticeView;
    private DialogueStyle dialogueStyle=DialogueStyle.fallback();
    private GameChoicePanel choices;
    private TitlePanel titlePanel;
    private LogoPanel logoPanel;
    private OpeningScreen startupOpening;
    private boolean launchingGame;
    private TitleMenu titleModel;
    private GameUiAssets uiAssets;
    private GameUiPanel gamePanel,preparingPanel;
    private UiPreviewPool.Warmup panelWarmup;
    private boolean returningToTitle;
    private Bitmap titlePrevious;
    private SceneEntrance sceneEntrance;
    private MoviePlayback moviePlayback;
    private GameSlotsPanel gameSlots;
    private GalleryCatalog.Page galleryPage,replayReturn;
    private long galleryRequest;
    private int galleryCgPage;
    private boolean sceneReplay;
    private Runnable panelBack;
    private boolean fromTyn;
    private int width=1280,height=720,lastX=0,lastY=0;
    private final int[] storyOrigin=new int[2];
    private float storyDownX,storyDownY;
    private int storyTouchSlop;
    private boolean storyDrag;
    private boolean foreground,permissionPending;
    private boolean uiHidden,autoMode,skipMode,typing,refreshAfterLoad;
    private int panelsOpen,revealed;
    private String fullBody="";
    private GameRunner.Frame currentFrame;
    private GameRunner.Frame drawnStoryFrame;
    private GameRunner.Modal visibleModal,modeUnderPanel;
    private Bitmap optionalGalleryPrevious;
    private boolean openGalleryAfterTitle;
    private SharedPreferences preferences,userPreferences;
    private Closeable playerTask;
    private Context uiContext;
    private PlayerSettings settings;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ArrayList<AlertDialog> openedPanels=new ArrayList<>();
    private AlertDialog slotsDialog;
    private LinearLayout slotRows;
    private boolean savingSlots;
    private boolean syncingRuntime;
    private int[] pendingDisplay,pendingAuxiliary;
    private int mouseVariant=Integer.MIN_VALUE;
    private boolean pcSaveBusy;
    private byte[] pendingPcExport;
    private final SharedPreferences.OnSharedPreferenceChangeListener settingsListener=(p,key) -> {
        if(syncingRuntime||p!=preferences)return;
        settings=PlayerSettings.read(p,userPreferences);
        if (dialoguePanel!=null) dialoguePanel.settings(settings);
        selectDialogueVariant();
        if (runner!=null) runner.settings(key,settings);
        immersive();
        updateStatus();
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener readingListener=(p,key)->{
        if(preferences==null||!PlayerSettings.READING_SIZE.equals(key))return;
        settings=PlayerSettings.read(preferences,p);
        if(dialoguePanel!=null)dialoguePanel.settings(settings);
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        uiContext=EngineUiText.localizedContext(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if(Build.VERSION.SDK_INT>=28){
            WindowManager.LayoutParams window=getWindow().getAttributes();
            window.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(window);
        }
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        immersive();
        root=new FrameLayout(this); root.setBackgroundColor(Color.BLACK);
        contentHost=new FrameLayout(this);
        root.addView(contentHost,new FrameLayout.LayoutParams(-1,-1));
        setContentView(root);
        root.setOnApplyWindowInsetsListener((view,insets)->{
            int left,top,right,bottom;
            if(Build.VERSION.SDK_INT>=30){
                android.graphics.Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
                left=safe.left;top=safe.top;right=safe.right;bottom=safe.bottom;
            }else{
                left=insets.getSystemWindowInsetLeft();top=insets.getSystemWindowInsetTop();
                right=insets.getSystemWindowInsetRight();bottom=insets.getSystemWindowInsetBottom();
                if(Build.VERSION.SDK_INT>=28&&insets.getDisplayCutout()!=null){
                    DisplayCutout cutout=insets.getDisplayCutout();
                    left=Math.max(left,cutout.getSafeInsetLeft());top=Math.max(top,cutout.getSafeInsetTop());
                    right=Math.max(right,cutout.getSafeInsetRight());bottom=Math.max(bottom,cutout.getSafeInsetBottom());
                }
            }
            if(view.getPaddingLeft()!=left||view.getPaddingTop()!=top||view.getPaddingRight()!=right||view.getPaddingBottom()!=bottom){
                view.setPadding(left,top,right,bottom);layoutStage();
            }
            return insets;
        });
        root.requestApplyInsets();
        userPreferences=PlayerSettings.preferences(this);
        userPreferences.registerOnSharedPreferenceChangeListener(readingListener);
        Intent i=getIntent();
        String path=i.getStringExtra(LaunchContract.RUNTIME_MODULE_FOLDER_PATH);
        fromTyn=LaunchContract.RUNTIME_MODULE_ACTION.equals(i.getAction());
        if (!fromTyn) {
            if (saved!=null) path=saved.getString("selectedGame");
            if (path==null || path.isEmpty()) { chooseHome(null); return; }
        } else if (path==null || path.isEmpty()) {
            chooseHome(text(R.string.engine_systemnnn_host_launch_path_missing)); return;
        }
        if (i.getIntExtra(LaunchContract.RUNTIME_MODULE_PROTOCOL_VERSION,LaunchContract.RUNTIME_MODULE_VERSION)!=LaunchContract.RUNTIME_MODULE_VERSION) {
            message(text(R.string.engine_systemnnn_host_protocol_unsupported),text(R.string.engine_systemnnn_host_check_versions),null,null); return;
        }
        try {
            File input=new File(path);
            if(!input.isAbsolute()){
                message(text(R.string.engine_systemnnn_host_open_failed),text(R.string.engine_systemnnn_host_path_not_absolute),null,null);
                return;
            }
            game=input.getCanonicalFile();
        }catch(IOException|SecurityException e){
            Log.w("SystemNNN","Unable to resolve the game path",e);
            message(text(R.string.engine_systemnnn_host_open_failed),text(R.string.engine_systemnnn_host_unreadable_directory),null,null);
            return;
        }
        inspectAccess();
    }
    private void chooseHome(String reason) {
        String hint=text(R.string.engine_systemnnn_host_pick_hint);
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            hint += "\n" + text(R.string.engine_systemnnn_host_pick_copy_hint);
        }
        message(getApplicationInfo().loadLabel(getPackageManager()).toString(),reason==null?hint:reason+"\n"+hint,text(R.string.engine_systemnnn_host_pick_directory),
            v -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),32));
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==33){
            if(result==RESULT_OK&&data!=null&&data.getData()!=null)importPcSave(data.getData());
            else setPcSaveBusy(false);
            return;
        }
        if(request==34){
            byte[] contents=pendingPcExport;pendingPcExport=null;
            if(result==RESULT_OK&&data!=null&&data.getData()!=null&&contents!=null&&runner!=null){
                Uri uri=data.getData();
                runner.writePcExport(()->{
                    try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){
                        if(out==null)throw new IOException(text(R.string.engine_systemnnn_host_open_selected_failed));
                        out.write(contents);
                    }
                    return null;
                },()->runOnUiThread(()->setPcSaveBusy(false)));
            }else setPcSaveBusy(false);
            return;
        }
        if (request!=32 || result!=RESULT_OK || data==null || data.getData()==null) return;
        try {
            Uri uri=data.getData();
            if(!"com.android.externalstorage.documents".equals(uri.getAuthority())){
                chooseHome(text(R.string.engine_systemnnn_host_local_directory_only));
                return;
            }
            String[] id=DocumentsContract.getTreeDocumentId(uri).split(":",2);
            if(id.length!=2){
                chooseHome(text(R.string.engine_systemnnn_host_unreadable_directory));
                return;
            }
            File volume=null;
            if ("primary".equalsIgnoreCase(id[0])) volume=Environment.getExternalStorageDirectory();
            else for (StorageVolume v:getSystemService(StorageManager.class).getStorageVolumes())
                if (id[0].equalsIgnoreCase(v.getUuid())) {
                    volume=Build.VERSION.SDK_INT>=30?v.getDirectory():new File("/storage",v.getUuid()); break;
                }
            if(volume==null){
                chooseHome(text(R.string.engine_systemnnn_host_missing_volume));
                return;
            }
            File selected=new File(volume,id[1]).getCanonicalFile();
            if(!selected.toPath().startsWith(volume.getCanonicalFile().toPath())){
                chooseHome(text(R.string.engine_systemnnn_host_invalid_directory));
                return;
            }
            game=selected; inspectAccess();
        }catch(IOException|IllegalArgumentException|SecurityException e){
            Log.w("SystemNNN","Unable to resolve the selected game directory",e);
            chooseHome(text(R.string.engine_systemnnn_host_unreadable_directory));
        }
    }
    private void choosePcSave(){
        if(runner==null||pcSaveBusy)return;
        Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        setPcSaveBusy(true);
        try{startActivityForResult(picker,33);}
        catch(ActivityNotFoundException e){setPcSaveBusy(false);notice(text(R.string.engine_systemnnn_host_picker_failed));}
    }
    private void choosePcExport(){
        GameRunner owner=runner;
        if(owner==null||pcSaveBusy)return;
        setPcSaveBusy(true);
        owner.preparePcExport(contents->runOnUiThread(()->{
            if(contents==null||owner!=runner||isDestroyed()||isFinishing()){setPcSaveBusy(false);return;}
            pendingPcExport=contents;
            Intent picker=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream")
                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"data1.sav");
            try{startActivityForResult(picker,34);}
            catch(ActivityNotFoundException e){pendingPcExport=null;setPcSaveBusy(false);notice(text(R.string.engine_systemnnn_host_picker_failed));}
        }));
    }
    private void setPcSaveBusy(boolean busy){pcSaveBusy=busy;updatePanelActionEnabled();}
    private byte[] readPcSave(Uri uri) throws IOException {
        try(InputStream input=getContentResolver().openInputStream(uri)){
            if(input==null)throw new IOException(text(R.string.engine_systemnnn_host_open_selected_failed));
            ByteArrayOutputStream data=new ByteArrayOutputStream();byte[] buffer=new byte[8192];
            for(int n;(n=input.read(buffer))!=-1;){
                if(n>16*1024*1024-data.size())throw new GameRunner.PcSaveTooLarge();
                data.write(buffer,0,n);
            }
            return data.toByteArray();
        }
    }
    private void importPcSave(Uri uri){
        GameRunner owner=runner;
        if(owner==null){setPcSaveBusy(false);return;}
        GameUiPanel panel=gamePanel;
        setPcSaveBusy(true);
        owner.importPcSave(()->readPcSave(uri),success->runOnUiThread(()->{
            setPcSaveBusy(false);
            if(!success||owner!=runner||isDestroyed()||isFinishing())return;
            if(panel!=null&&panel==gamePanel)panel.leave(uiAssets.load,false,(picture,filled)->enterStory(picture,filled));
            else enterStory(null);
        }));
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (!fromTyn && game!=null) state.putString("selectedGame",game.getPath());
    }
    private void inspectAccess() {
        if (game==null || runner!=null) return;
        File script=GameResourcePaths.file(game,"spt","sys.spt");
        if (script!=null) {
            // Scoped storage may allow stat() and directory listings while denying open().
            try (FileInputStream probe=new FileInputStream(script)) {
                File mapping=GameResourcePaths.file(game,"sjis_ext.bin");
                if (mapping!=null) try (FileInputStream map=new FileInputStream(mapping)) { map.read(); }
                if (probe.read()!=-1) { startGame(); return; }
            } catch (IOException ignored) { /* Show the appropriate storage action below. */ }
        }
        if(!requestStorageAccess(false))chooseHome(text(R.string.engine_systemnnn_host_marker_missing));
    }
    /** The caller passes the original directory as the legacy settings/save-migration key. */
    private File legacyIdentity() {
        String path=getIntent().getStringExtra(LaunchContract.RUNTIME_MODULE_SOURCE_IDENTITY_PATH);
        if(path==null||path.isEmpty())return game;
        try{
            File candidate=new File(path);
            return candidate.isAbsolute()?candidate.getCanonicalFile():game;
        }catch(IOException|SecurityException ignored){
            return game;
        }
    }
    private boolean requestStorageAccess(boolean writing) {
        String permission=writing?Manifest.permission.WRITE_EXTERNAL_STORAGE:Manifest.permission.READ_EXTERNAL_STORAGE;
        boolean needsGrant=Build.VERSION.SDK_INT>=30 ? !Environment.isExternalStorageManager() :
                checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED;
        if (needsGrant) {
            message(text(R.string.engine_systemnnn_host_storage_permission),text(R.string.engine_systemnnn_host_storage_permission_hint),
                text(R.string.engine_systemnnn_host_open_permissions),v -> {
                    permissionPending=true;
                    if (Build.VERSION.SDK_INT>=30) {
                        Intent settings=new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:"+getPackageName()));
                        try { startActivity(settings); }
                        catch (android.content.ActivityNotFoundException e) { startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); }
                    } else requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE},21);
                });
        }
        return needsGrant;
    }
    private void startGame() {
        GameSavePaths saves;
        File legacySource;
        try{
            saves=GameSavePaths.forGame(game);
            legacySource=legacyIdentity();
        }
        catch(IOException|SecurityException e){
            Log.w("SystemNNN","Unable to open the save directory",e);
            message(text(R.string.engine_systemnnn_host_save_directory_failed),text(R.string.engine_systemnnn_host_check_save_access),
                text(R.string.engine_systemnnn_host_retry),v->inspectAccess());
            return;
        }
        File directory=saves.directory();
        if(!(directory.isDirectory()?directory:game).canWrite() && requestStorageAccess(true))return;
        try{NativeGame.load(this);}
        catch(IOException|LinkageError e){
            Log.w("SystemNNN","Unable to load the runtime pack",e);
            message(text(R.string.engine_systemnnn_host_game_interrupted),text(R.string.engine_systemnnn_host_check_versions),
                text(R.string.engine_systemnnn_host_exit_to_tyn),v->finish());
            return;
        }
        startupOverlay=new EngineStartupOverlay(this);
        startupOverlay.attachTo(root);
        contentHost.removeAllViews();
        storyTouchSlop=ViewConfiguration.get(this).getScaledTouchSlop();
        stage=new FrameLayout(this){
            @Override public void onDescendantInvalidated(View child,View target){
                super.onDescendantInvalidated(child,target);
                StoryScreen foreground=storyScreen;
                if(foreground==null)return;
                if(child==dialoguePanel||child==choices||child==longPrintBackdrop){
                    boolean separateCursor=dialoguePanel!=null&&dialoguePanel.isMessageCursor(target);
                    boolean separateTimer=choices!=null&&choices.isTimer(target);
                    foreground.messageChanged(!separateCursor&&!separateTimer);
                }
            }
        };
        launchingGame=true;
        stage.setForeground(new ColorDrawable(Color.BLACK));
        contentHost.addView(stage,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
        panelActionButton=new Button(this);
        panelActionButton.setSingleLine(true);
        panelActionButton.setSoundEffectsEnabled(false);
        panelActionButton.setVisibility(View.GONE);
        panelActionButton.setOnClickListener(v->{
            if(!panelActionReady())return;
            if(gamePanel instanceof GameSettingsPanel)showReadingSettings();
            else if(gamePanel instanceof GameSlotsPanel)((GameSlotsPanel)gamePanel).performPcTransfer();
        });
        FrameLayout.LayoutParams actionPosition=new FrameLayout.LayoutParams(-2,dp(48),Gravity.TOP|Gravity.END);
        actionPosition.setMargins(dp(4),dp(4),dp(4),0);
        contentHost.addView(panelActionButton,actionPosition);
        otherSavesButton=new Button(this);
        otherSavesButton.setText(text(R.string.engine_systemnnn_ctl_other_saves));
        otherSavesButton.setSingleLine(true);
        otherSavesButton.setSoundEffectsEnabled(false);
        otherSavesButton.setVisibility(View.GONE);
        otherSavesButton.setOnClickListener(v->{if(panelActionReady())showOtherSaves();});
        FrameLayout.LayoutParams otherPosition=new FrameLayout.LayoutParams(-2,dp(48),Gravity.TOP|Gravity.END);
        otherPosition.setMargins(dp(4),dp(52),dp(4),0);
        contentHost.addView(otherSavesButton,otherPosition);
        root.getViewTreeObserver().addOnPreDrawListener(()->{updatePanelActionEnabled();return true;});
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> layoutStage());
        screen=new TextureView(this); screen.setContentDescription(text(R.string.engine_systemnnn_host_game_display));screen.setSoundEffectsEnabled(false);
        stage.addView(screen,new FrameLayout.LayoutParams(-1,-1));
        screen.setOnTouchListener((view,event) -> {
            if (event.getActionMasked()==MotionEvent.ACTION_UP) {advance();view.performClick();}
            return true;
        });
        longPrintBackdrop=new View(this){
            private final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);
            private final RectF destination=new RectF();
            @Override protected void onDraw(Canvas canvas){
                Bitmap bitmap=uiAssets==null?null:uiAssets.longPrintImage;
                if(bitmap==null)return;
                float sx=getWidth()/(float)width,sy=getHeight()/(float)height;
                destination.set(0,0,bitmap.getWidth()*sx,bitmap.getHeight()*sy);
                canvas.drawBitmap(bitmap,null,destination,paint);
            }
        };
        longPrintBackdrop.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        longPrintBackdrop.setVisibility(View.GONE);
        stage.addView(longPrintBackdrop,new FrameLayout.LayoutParams(-1,-1));
        dialoguePanel=new DialoguePanel(this,v -> advance());
        stage.addView(dialoguePanel,new FrameLayout.LayoutParams(-1,-1)); dialoguePanel.setVisibility(View.GONE);
        storyScreen=new StoryScreen();
        stage.addView(storyScreen,new FrameLayout.LayoutParams(-1,-1));
        status=label("",12); status.setGravity(Gravity.CENTER); status.setPadding(dp(8),dp(3),dp(8),dp(3));
        status.setBackground(rounded(0xb51a2030,6));
        FrameLayout.LayoutParams statusParams=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.START);
        statusParams.setMargins(dp(8),dp(8),0,0); stage.addView(status,statusParams); status.setVisibility(View.GONE);
        preferences=PlayerSettings.displayPreferences(this,legacySource);
        settings=PlayerSettings.read(preferences,userPreferences);
        dialoguePanel.settings(settings);
        preferences.registerOnSharedPreferenceChangeListener(settingsListener);
        if(fromTyn)try{playerTask=SystemNnnHost.registerPlayerTask(this,game,getIntent().getStringExtra(LaunchContract.ROOT_URI));}
        catch(IOException|RuntimeException e){android.util.Log.w("SystemNNN","Unable to register player task",e);}
        try{runner=new GameRunner(uiContext,game,legacySource,saves,getFilesDir(),preferences,userPreferences,settings,this);}
        catch(RuntimeException|Error e){closePlayerTask();throw e;}
        GameRunner surfaceOwner=runner;
        screen.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            private Surface attached;
            @Override public void onSurfaceTextureAvailable(SurfaceTexture texture,int width,int height){
                attached=new Surface(texture);
                storySurface=attached;
                surfaceOwner.surface(attached);
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture,int width,int height){
                if(attached!=null)surfaceOwner.surface(attached);
            }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture){
                Surface old=attached;
                attached=null;
                if(storySurface==old)storySurface=null;
                surfaceOwner.surface(null);
                // Abandon the consumer after TextureView detaches it. A blocked producer wakes
                // without making the UI thread wait for the current native buffer submission.
                new Handler(Looper.getMainLooper()).post(()->{
                    try{texture.release();}
                    finally{if(old!=null)old.release();}
                });
                return false;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture texture){}
        });
        if (foreground) runner.active(true);
        layoutStage();
    }
    private void systemClick() {if(runner!=null)runner.playClick();}
    private View.OnClickListener clicked(View.OnClickListener action) {return v -> {systemClick();if(action!=null)action.onClick(v);};}
    private DialogInterface.OnClickListener dialogClick(DialogInterface.OnClickListener action) {
        return (dialog,which) -> {systemClick();if(action!=null)action.onClick(dialog,which);};
    }
    private void layoutStage() {
        if(stage==null)return;
        if(longPrintBackdrop!=null)longPrintBackdrop.invalidate();
        if(dialoguePanel!=null)dialoguePanel.canvas(width,height);
        if(controls!=null)controls.canvas(width,height);
        if(quickNoticeView!=null)quickNoticeView.canvas(width,height);
        if(gamePanel!=null)gamePanel.canvas(width,height);
        reserveCommandSpace();
        if(root.getWidth()==0 || root.getHeight()==0)return;
        int availableWidth=contentHost.getWidth()-contentHost.getPaddingLeft()-contentHost.getPaddingRight();
        int availableHeight=contentHost.getHeight()-contentHost.getPaddingTop()-contentHost.getPaddingBottom();
        if(availableWidth<=0||availableHeight<=0)return;
        float scale=Math.min((float)availableWidth/width,(float)availableHeight/height);
        int w=Math.max(1,Math.round(width*scale)),h=Math.max(1,Math.round(height*scale));
        int left=(availableWidth-w)/2;
        int top=(availableHeight-h)/2;
        FrameLayout.LayoutParams params=(FrameLayout.LayoutParams)stage.getLayoutParams();
        int gravity=Gravity.TOP|Gravity.LEFT;
        if(params.width!=w||params.height!=h||params.gravity!=gravity||params.leftMargin!=left||params.topMargin!=top){
            params.width=w;params.height=h;params.gravity=gravity;params.leftMargin=left;params.topMargin=top;
            stage.setLayoutParams(params);
        }
    }
    @Override public void loaded() {runOnUiThread(() -> {refreshAfterLoad=true;modeUnderPanel=null;});}
    private void revealStartupOverlayAfterLayout(View content) {
        if (startupOverlay==null || !startupOverlay.isLoading() || content==null) return;
        EngineStartupOverlay expected=startupOverlay;
        ViewTreeObserver observer=content.getViewTreeObserver();
        if (!observer.isAlive()) return;
        observer.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                ViewTreeObserver current=content.getViewTreeObserver();
                if (current.isAlive()) current.removeOnPreDrawListener(this);
                content.post(() -> {
                    if (!isDestroyed()&&!isFinishing()&&startupOverlay==expected) expected.reveal();
                });
                return true;
            }
        });
        content.requestLayout();
        content.invalidate();
    }
    @Override public void frame(GameRunner.Frame frame,int initialVisible,long generation) {
        runOnUiThread(() -> {
            if (!startupGeneration.accepts(generation)) return;
            if (isDestroyed() || stage==null) return;
            if (titlePanel!=null) {stage.removeView(titlePanel); titlePanel=null; titleModel=null;}
            clearStartupForStory();
            if (frame.width>0&&frame.height>0&&(width!=frame.width || height!=frame.height)) {width=frame.width;height=frame.height;layoutStage();}
            if(frame.wait==NativeGame.MODAL){
                currentFrame=frame;
                // Preserve the last story composition until the modal has captured it.
                if(gamePanel==null&&preparingPanel==null)showScriptModal(null);
                revealStartupOverlayAfterLayout(stage);
                return;
            }
            modeUnderPanel=null;
            boolean changed=refreshAfterLoad || currentFrame==null || currentFrame.wait!=frame.wait || currentFrame.line!=frame.line ||
                currentFrame.word!=frame.word || !currentFrame.script.equals(frame.script) ||
                !currentFrame.body.equals(frame.body) || !currentFrame.speaker.equals(frame.speaker) || currentFrame.nameWindow!=frame.nameWindow;
            currentFrame=drawnStoryFrame=frame;
            dialoguePanel.scriptCursorVariant(frame.scriptCursor);
            dialoguePanel.storyBody(frame.hasDialogueText());
            dialoguePanel.passive(frame.wait==NativeGame.DRAW);
            selectDialogueVariant();
            if (changed) {
                refreshAfterLoad=false;
                typing=false; fullBody=frame.displayBody.text;
                dialoguePanel.speaker(frame.speaker,frame.nameWindow);dialoguePanel.read(frame.alreadyRead);
                if(choices!=null){stage.removeView(choices);choices=null;}
                boolean dialogue=frame.wait==NativeGame.DIALOGUE;
                typing=dialogue&&initialVisible<frame.displayBody.glyphCount;
                revealed=dialogue?fullBody.offsetByCodePoints(0,initialVisible):fullBody.length();
                dialoguePanel.message(frame.displayBody,revealed);
                if(frame.wait==NativeGame.CHOICE&&uiAssets!=null){
                    choices=new GameChoicePanel(this,uiAssets,frame,preferences.getBoolean("changeSelectColor",false),
                        ()->{
                            if(runner==null||controls!=null&&controls.clicking())return false;
                            runner.choicePressed(frame.word);
                            return true;
                        },
                        number->{if(runner!=null)runner.input(lastX,lastY,number);});
                    choices.sound((se,voice)->{if(runner!=null)runner.playClick(se,voice);});
                    stage.addView(choices,stage.indexOfChild(storyScreen),new FrameLayout.LayoutParams(-1,-1));
                }
                if(frame.wait==NativeGame.END){
                    String end=text(R.string.engine_systemnnn_host_story_ended);
                    dialoguePanel.speaker("",false);dialoguePanel.message(end,end.length());
                }
            }
            updateVisibility(); updateStatus();
            if(frame.wait==NativeGame.PLACE&&gamePanel==null&&preparingPanel==null)showPlace(null);
            revealStartupOverlayAfterLayout(stage);
        });
    }
    @Override public void screenLayerChanged(){
        StoryScreen view=storyScreen;
        if(view!=null)view.postInvalidate();
    }
    @Override public void style(DialogueStyle style) {runOnUiThread(() -> {dialogueStyle=style;if(dialoguePanel!=null){dialoguePanel.style(style);reserveCommandSpace();}});}
    @Override public void choiceTime(GameRunner.Frame frame,int remaining){runOnUiThread(()->{
        if(choices!=null&&currentFrame==frame)choices.remaining(remaining);
    });}
    private void selectDialogueVariant(){
        if(dialoguePanel==null||preferences==null)return;
        boolean line=currentFrame!=null&&currentFrame.wait==NativeGame.DIALOGUE;
        boolean draw=currentFrame!=null&&currentFrame.wait==NativeGame.DRAW;
        dialoguePanel.variant(currentFrame==null?0:currentFrame.windowType,currentFrame==null?0:currentFrame.fontType,
            preferences.getInt("messageWindowIndex",0),line&&currentFrame.layoutCommand==0x22220002,
            draw?currentFrame.showsDialogue():!line||currentFrame.windowVisible);
        reserveCommandSpace();
    }
    private void reserveCommandSpace(){
        if(dialoguePanel!=null&&controls!=null)dialoguePanel.contentRight(controls.contentRight(dialoguePanel.bodyX(),dialoguePanel.bodyY(),
            dialoguePanel.bodyY()+dialoguePanel.bodyHeight(),dialoguePanel.bodyX()+dialoguePanel.bodyWidth()));
    }
    @Override public void panels(GameUiAssets assets) {runOnUiThread(() -> {
        uiAssets=assets;if(isDestroyed()||stage==null)return;
        longPrintBackdrop.invalidate();
        dialoguePanel.cursorAssets(assets);
        if(controls!=null)stage.removeView(controls);
        controls=new GameStoryControls(this,assets,new GameStoryControls.Listener(){
            public void begin(GameStoryControlsModel.Action action){
                if(runner!=null){runner.menu(true);warmStoryPanel(action);}
            }
            public void sound(int se,int voice){if(runner!=null)runner.playClick(se,voice);}
            public void action(GameStoryControlsModel.Action action,boolean target){
                if(runner==null)return;
                switch(action){
                case MENU:showMenu();break;
                case CONFIG:runner.suspendScriptAudio();showSettings();break;
                case BACKLOG:showBacklog();break;
                case SAVE:runner.suspendScriptAudio();showSlots(true);break;
                case LOAD:runner.suspendScriptAudio();showSlots(false);break;
                case QUICK_SAVE:runner.quickSave();break;
                case QUICK_LOAD:runner.quickLoad();break;
                case AUTO:runner.auto(target);break;
                case SKIP:runner.skip(target);break;
                case HIDE:hideUi();break;
                }
                if(panelsOpen==0&&preparingPanel==null)runner.menu(false);
            }
        });
        stage.addView(controls,new FrameLayout.LayoutParams(-1,-1));controls.canvas(width,height);
        if(quickNoticeView!=null)stage.removeView(quickNoticeView);
        quickNoticeView=new GameNotice(this,assets,cue->{if(runner!=null)runner.playClick(cue,-1);});
        stage.addView(quickNoticeView,new FrameLayout.LayoutParams(-1,-1));quickNoticeView.canvas(width,height);
        reserveCommandSpace();updateVisibility();updateStatus();
    });}
    @Override public void quickSlot(boolean available){runOnUiThread(()->{if(controls!=null)controls.quickSlot(available);});}
    @Override public void quickNotice(int number,int frameMs){runOnUiThread(()->{
        if(quickNoticeView!=null&&titlePanel==null&&panelsOpen==0&&!uiHidden)quickNoticeView.show(number,frameMs);
    });}
    @Override public void mode(boolean automatic,boolean skipping) {runOnUiThread(() -> {
        autoMode=automatic;skipMode=skipping;
        updateVisibility();updateStatus();
    });}
    @Override public void text(GameRunner.Frame frame,int visible,int total){runOnUiThread(()->{
        if(currentFrame==null||currentFrame.line!=frame.line||currentFrame.word!=frame.word||!currentFrame.script.equals(frame.script))return;
        int count=Math.min(Math.max(0,visible),fullBody.codePointCount(0,fullBody.length()));
        revealed=fullBody.offsetByCodePoints(0,count);typing=count<total;dialoguePanel.reveal(revealed);
        updateVisibility();
    });}
    @Override public void parameters(int[] raw,int[] display,int[] auxiliary){runOnUiThread(()->{
        pendingDisplay=display;pendingAuxiliary=auxiliary;ui.removeCallbacks(syncRuntimeDisplay);ui.post(syncRuntimeDisplay);
    });}
    private final Runnable syncRuntimeDisplay=new Runnable(){public void run(){
        if(uiAssets==null||pendingDisplay==null||isDestroyed())return;
        if(gamePanel!=null&&gamePanel.isAdjusting()){ui.postDelayed(this,40);return;}
        int[] display=pendingDisplay,aux=pendingAuxiliary;pendingDisplay=null;pendingAuxiliary=null;syncingRuntime=true;
        try{
            OriginalSettingsModel model=uiAssets.settingsModel(width,height);
            RuntimeSettingsAdapter.syncDisplay(preferences,model,display);
            if(aux!=null&&aux.length>=34){SharedPreferences.Editor edit=preferences.edit().putInt("systemVoiceSelection",aux[0]).putInt("messageWindowIndex",aux[1]);
                for(OriginalSettingsModel.CharacterControl group:model.characters){boolean enabled=(aux[2+(group.index&31)]&(1<<(group.index/32)))==0;
                    for(int id:group.ids)edit.putBoolean("character."+id+".enabled",enabled);}
                edit.apply();}
            settings=PlayerSettings.read(preferences,userPreferences);if(dialoguePanel!=null)dialoguePanel.settings(settings);
            if(aux!=null&&aux.length>=36){
                if(dialoguePanel!=null)dialoguePanel.cursorVariant(aux[34]);
                if(stage!=null&&mouseVariant!=aux[35]){mouseVariant=aux[35];stage.setPointerIcon(uiAssets.pointer(mouseVariant));}
            }
            if(choices!=null)choices.changeColor(preferences.getBoolean("changeSelectColor",false));
            selectDialogueVariant();immersive();updateStatus();
        }finally{syncingRuntime=false;}
    }};
    private void advance() {
        if(runner==null || launchingGame || panelsOpen>0)return;
        if(autoMode)runner.auto(false);
        if(skipMode)runner.skip(false);
        autoMode=false;skipMode=false;updateStatus();
        if(uiHidden){uiHidden=false;updateVisibility();return;}
        if(currentFrame!=null && currentFrame.wait==NativeGame.END){showMenu();return;}
        runner.input(lastX,lastY,0);
    }
    private void hideUi() {if(runner==null)return;runner.auto(false);runner.skip(false);if(quickNoticeView!=null)quickNoticeView.clear();uiHidden=true;updateVisibility();}
    private boolean panelActionReady(){
        return runner!=null&&preparingPanel==null&&!returningToTitle&&!pcSaveBusy&&gamePanel!=null&&
            gamePanel.activeFrameReady()&&!gamePanel.openingRunning()&&!gamePanel.controlsBusy();
    }
    private void updatePanelActionEnabled(){
        if(panelActionButton==null)return;
        boolean ready=panelActionReady();
        if(panelActionButton.getVisibility()==View.VISIBLE&&panelActionButton.isEnabled()!=ready)
            panelActionButton.setEnabled(ready);
        if(otherSavesButton.getVisibility()==View.VISIBLE&&otherSavesButton.isEnabled()!=ready)
            otherSavesButton.setEnabled(ready);
    }
    private void updatePanelAction(){
        String label=gamePanel instanceof GameSettingsPanel?text(R.string.engine_systemnnn_host_reading_size):
            gamePanel instanceof GameSlotsPanel?((GameSlotsPanel)gamePanel).pcTransferLabel():null;
        if(label!=null&&!label.contentEquals(panelActionButton.getText()))panelActionButton.setText(label);
        panelActionButton.setVisibility(label==null?View.GONE:View.VISIBLE);
        otherSavesButton.setVisibility(gamePanel instanceof GameSlotsPanel&&
            ((GameSlotsPanel)gamePanel).hasOtherSaves()?View.VISIBLE:View.GONE);
        updatePanelActionEnabled();
    }
    private void updateVisibility() {
        if(dialoguePanel==null)return;
        updatePanelAction();
        if(gamePanel==null&&isModalPanel(preparingPanel))return;
        boolean storyVisible=panelsOpen==0||gamePanel!=null&&gamePanel.awaitingCapturedBackdrop();
        boolean inGame=!launchingGame&&titlePanel==null&&storyVisible&&moviePlayback==null;
        storyScreen.setVisibility(inGame?View.VISIBLE:View.GONE);
        storyScreen.windowChanged();
        boolean line=currentFrame!=null&&currentFrame.wait==NativeGame.DIALOGUE;
        boolean showBackdrop=inGame&&line&&currentFrame.layoutCommand==0x22220002&&uiAssets!=null&&
            uiAssets.longPrintImage!=null&&(!uiHidden||!uiAssets.longPrintImageOffWithWindow);
        longPrintBackdrop.setVisibility(showBackdrop?View.VISIBLE:View.GONE);
        boolean hasDialogue=currentFrame!=null&&(currentFrame.showsDialogue()&&(!line||!fullBody.isEmpty())||currentFrame.wait==NativeGame.END);
        dialoguePanel.setVisibility(inGame&&!uiHidden&&hasDialogue?View.VISIBLE:View.GONE);
        dialoguePanel.waiting(inGame&&!uiHidden&&hasDialogue&&!typing&&!skipMode&&currentFrame.wait==NativeGame.DIALOGUE,
            autoMode,currentFrame==null?0:currentFrame.layoutCommand,currentFrame==null?20:currentFrame.frameMs);
        if(controls!=null){if(inGame&&!uiHidden&&!sceneReplay)controls.frame(currentFrame,typing,choices);else controls.setVisibility(View.GONE);}
        if(choices!=null)choices.setVisibility(inGame&&!uiHidden?View.VISIBLE:View.GONE);
        if(uiHidden||!storyVisible)status.setVisibility(View.GONE);
        syncIndependentStatus();
    }
    private int visiblePrintMode(){
        if(moviePlayback!=null)return 27;
        if(logoPanel!=null)return 2;
        if(startupOpening!=null)return 3;
        if(gamePanel!=null)return gamePanel.printMode;
        if(titlePanel!=null)return 4;
        if(currentFrame==null)return 1;
        switch(currentFrame.wait){
            case NativeGame.DIALOGUE:return 10;
            case NativeGame.CHOICE:return 9;
            case NativeGame.DRAW:return 11;
            case NativeGame.OVERLAP:return 14;
            case NativeGame.PLACE:return currentFrame.placeMode;
            default:return 1;
        }
    }
    private void syncIndependentStatus(){
        if(runner==null||uiAssets==null||!uiAssets.freeStatus||stage==null)return;
        int mode=visiblePrintMode();
        if(mode!=independentMode){
            independentMode=mode;
            if(independentStatus!=null)independentStatus.setVisibility(View.GONE);
            runner.statusMode(mode);
        }
        placeIndependentStatus();
    }
    private void placeIndependentStatus(){
        if(independentStatus==null)return;
        View anchor=moviePlayback!=null?moviePlayback:logoPanel!=null?logoPanel:
            gamePanel!=null?gamePanel:titlePanel!=null?titlePanel:sceneEntrance;
        int index=anchor!=null?stage.indexOfChild(anchor)+1:controls==null?stage.getChildCount():stage.indexOfChild(controls);
        int old=stage.indexOfChild(independentStatus);
        if(old>=0&&old<index)index--;
        if(old!=index){
            if(old>=0)stage.removeView(independentStatus);
            stage.addView(independentStatus,Math.max(0,index),new FrameLayout.LayoutParams(-1,-1));
        }
        if(gamePanel!=null)gamePanel.statusForCapture(independentStatus);
    }
    @Override public void statusOverlays(int mode,GameRunner.Modal[] overlays){
        runOnUiThread(()->{
            if(stage==null||uiAssets==null||mode!=independentMode||isDestroyed()||isFinishing())return;
            if(independentStatus==null){
                if(overlays[0]==null&&overlays[1]==null)return;
                independentStatus=new FrameLayout(this);
                independentStatus.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            }
            for(int i=0;i<overlays.length;i++){
                GameStatusPartyPanel part=independentParts[i];
                if(overlays[i]==null){
                    if(part!=null)independentStatus.removeView(part);
                    independentParts[i]=null;
                }else if(part==null){
                    part=new GameStatusPartyPanel(this,uiAssets,overlays[i],width,height);
                    independentParts[i]=part;
                    independentStatus.addView(part,i==1?independentStatus.getChildCount():0,new FrameLayout.LayoutParams(-1,-1));
                }else {part.canvas(width,height);part.update(overlays[i]);}
            }
            independentStatus.setVisibility(independentStatus.getChildCount()==0?View.GONE:View.VISIBLE);
            placeIndependentStatus();
            statusActive(foreground);
        });
    }
    private void statusActive(boolean active){
        for(GameStatusPartyPanel panel:independentParts)if(panel!=null)panel.uiActive(active);
    }
    private void updateStatus() {
        if(status==null)return;
        if(controls!=null)controls.modes(autoMode,skipMode);
        String text=skipMode?(settings.skipReadOnly?text(R.string.engine_systemnnn_host_skip_read):text(R.string.engine_systemnnn_host_skip_all)):
            autoMode?text(R.string.engine_systemnnn_host_auto):currentFrame!=null&&currentFrame.alreadyRead?text(R.string.engine_systemnnn_host_already_read):"";
        status.setText(text);status.setVisibility((controls==null||!controls.original())&&!uiHidden&&panelsOpen==0&&titlePanel==null&&!text.isEmpty()?View.VISIBLE:View.GONE);
    }
    private void clearStartupViews(){
        if(logoPanel!=null){stage.removeView(logoPanel);logoPanel=null;}
        if(startupOpening!=null){stage.removeView(startupOpening);startupOpening=null;}
        stage.setForeground(null);
    }
    private void removeTitleForStartup(){
        if(titlePanel==null)return;
        stage.removeView(titlePanel);
        titlePanel=null;
        titleModel=null;
        launchingGame=true;
    }
    private void clearStartupForStory(){
        if(!launchingGame)return;
        launchingGame=false;
        clearStartupViews();
        titlePrevious=null;
        MoviePlayback previous=moviePlayback;moviePlayback=null;
        if(previous!=null){previous.dispose();stage.removeView(previous);}
    }
    @Override public void logo(List<LogoPanel.Slide> slides,int w,int h,int frameMs,long generation){
        runOnUiThread(()->{
            if (!startupGeneration.accepts(generation)) return;
            GameRunner owner=runner;
            if(isDestroyed()||isFinishing()||stage==null||owner==null)return;
            if(w>0&&h>0){width=w;height=h;}layoutStage();
            if(slides.isEmpty())return;
            clearStartupViews();
            logoPanel=new LogoPanel(this,slides,frameMs,()->{
                if(isDestroyed()||isFinishing()||runner!=owner||logoPanel==null)return;
                titlePrevious=logoPanel.exitScreen();
                owner.finishLogo();
            });
            stage.addView(logoPanel,new FrameLayout.LayoutParams(-1,-1));
            removeTitleForStartup();
            updateVisibility();
            revealStartupOverlayAfterLayout(logoPanel);
        });
    }
    @Override public void opening(GameUiAssets.Config config,int frameMs,GameRunner.Capture initial,GameRunner.Capture previous,long generation){
        runOnUiThread(()->{
            if (!startupGeneration.accepts(generation)) return;
            GameRunner owner=runner;
            if(isDestroyed()||isFinishing()||stage==null||owner==null)return;
            clearStartupViews();
            if(previous!=null)titlePrevious=previous.bitmap();
            startupOpening=new OpeningScreen(owner,config,frameMs,initial);
            stage.addView(startupOpening,new FrameLayout.LayoutParams(-1,-1));
            removeTitleForStartup();
            updateVisibility();
            revealStartupOverlayAfterLayout(startupOpening);
        });
    }
    private final class OpeningScreen extends View {
        private final GameRunner owner;
        private final UiOpening transition;
        private boolean shown;
        OpeningScreen(GameRunner owner,GameUiAssets.Config config,int frameMs,GameRunner.Capture initial){
            super(PlayerActivity.this);
            this.owner=owner;
            transition=new UiOpening(config,frameMs,false);
            transition.previous(titlePrevious);
            transition.target(initial==null?null:initial.bitmap());
            setSoundEffectsEnabled(false);
            setContentDescription(text(R.string.engine_systemnnn_host_opening));
            setOnClickListener(v->owner.finishOpening());
        }
        @Override protected void onDraw(Canvas canvas){
            transition.begin();
            transition.drawTarget(canvas,this);
            transition.overlay(canvas,this,width,height);
            if(transition.fading())postInvalidateOnAnimation();
            else if(!shown){shown=true;owner.openingShown();}
        }
        @Override public boolean onTouchEvent(MotionEvent event){
            if(transition.touch(event)){invalidate();return true;}
            return super.onTouchEvent(event);
        }
        void skip(){
            if(transition.fading()){transition.skip();invalidate();}
            else owner.finishOpening();
        }
        @Override protected void onDetachedFromWindow(){transition.close();super.onDetachedFromWindow();}
    }
    @Override public void titleRestart(TitleMenu model,GameRunner.Capture background){
        runOnUiThread(()->{
            GameRunner owner=runner;
            TitlePanel panel=titlePanel;
            if(owner==null)return;
            if(!foreground||isDestroyed()||isFinishing()||model!=titleModel||panel==null||
                panelsOpen!=0||preparingPanel!=null||gamePanel!=null||galleryPage!=null||returningToTitle||
                !panel.leaveForRestart(background,picture->{
                    if(owner!=runner||panel!=titlePanel||isDestroyed()||isFinishing())return;
                    titlePrevious=picture;
                    owner.finishTitleRestart(model,true);
                }))owner.finishTitleRestart(model,false);
        });
    }
    @Override public void title(TitleMenu model,int w,int h,GameRunner.Capture initial,GameRunner.Capture previous,long generation) {
        runOnUiThread(() -> {
            if (!startupGeneration.accepts(generation)) return;
            if(isDestroyed() || isFinishing() || stage==null)return;
            if(titleModel!=model||returningToTitle)modeUnderPanel=null;
            if(returningToTitle){returningToTitle=false;closeOriginalPanel(false);}
            if(previous!=null){closeOriginalPanel(false);titlePrevious=previous.bitmap();}
            typing=false;currentFrame=drawnStoryFrame=null;fullBody="";uiHidden=false;refreshAfterLoad=false;
            if(titlePanel!=null)stage.removeView(titlePanel);
            titleModel=model;if(w>0&&h>0){width=w;height=h;}layoutStage();
            storyScreen.clear();
            storyScreen.setVisibility(View.GONE);
            longPrintBackdrop.setVisibility(View.GONE);
            dialoguePanel.setVisibility(View.GONE);if(controls!=null)controls.setVisibility(View.GONE);status.setVisibility(View.GONE);
            if(choices!=null){stage.removeView(choices);choices=null;}
            launchingGame=false;
            clearStartupViews();
            showTitlePanel(model,initial);
            revealStartupOverlayAfterLayout(titlePanel);
        });
    }
    private void showTitlePanel(TitleMenu model,GameRunner.Capture initial){
        titlePanel=new TitlePanel(this,game.getName(),model,width,height,runner,initial==null?null:initial.bitmap(),this::titleAction,
            item->warmTitlePanel(item.action),
            (se,voice)->{if(runner!=null)runner.playClick(se,voice);});
        titlePanel.previous(titlePrevious);
        titlePrevious=null;
        stage.addView(titlePanel,new FrameLayout.LayoutParams(-1,-1));
        syncIndependentStatus();
        if(openGalleryAfterTitle){
            openGalleryAfterTitle=false;
            if(gamePanel!=null)gamePanel.bringToFront();
            openGallery(GalleryCatalog.Screen.OMAKE,0,0,-1);
        }
    }
    private void warmTitlePanel(TitleMenu.Action action){
        if(action==TitleMenu.Action.SETTINGS)warmStoryPanel(GameStoryControlsModel.Action.CONFIG);
        else if(action==TitleMenu.Action.LOAD)warmStoryPanel(GameStoryControlsModel.Action.LOAD);
    }
    private void warmStoryPanel(GameStoryControlsModel.Action action){
        if(uiAssets==null||uiAssets.runtime==null)return;
        UiPreviewPool.Request request;
        switch(action){
        case CONFIG:request=UiPreviewPool.Request.read(uiAssets.config,12,1);break;
        case SAVE:case LOAD:
            boolean saving=action==GameStoryControlsModel.Action.SAVE;
            GameUiAssets.Config config=saving?uiAssets.save:uiAssets.load;
            SaveSlots.Layout layout=new SaveSlots.Layout(config,uiAssets.game,uiAssets.dataFile,saving);
            request=UiPreviewPool.Request.read(config,saving?6:5,layout.openingPage(runner.lastSelectedSlot())+1);
            break;
        default:return;
        }
        UiPreviewPool pool=request==null?null:uiAssets.runtime.previews();
        panelWarmup=pool==null?null:pool.warm(request);
    }
    private UiPreviewPool.Warmup takePanelWarmup(){
        UiPreviewPool.Warmup warmup=panelWarmup;panelWarmup=null;return warmup;
    }
    private void titleAction(TitleMenu.Item item) {
        if(runner==null || titleModel==null)return;
        TitleMenu.Action action=item.action;
        switch(action) {
        case START:case CONTINUE:case LAST_LOAD:
            TitlePanel source=titlePanel;
            GameRunner owner=runner;
            owner.enter(action,()->runOnUiThread(()->{
                if(source!=null&&source==titlePanel&&owner==runner&&!isDestroyed())source.entryFailed();
            }));
            break;
        case LOAD:showSlots(false);break;
        case SETTINGS:showSettings();break;
        case EXTRAS:openGallery(GalleryCatalog.Screen.OMAKE,0,0,-1);break;
        case EXIT:finish();break;
        case WEBSITE:
            try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(titleModel.website)));}
            catch(android.content.ActivityNotFoundException e){notice(text(R.string.engine_systemnnn_host_browser_missing));}break;
        case MODE:openMenuMode(item.mode,GameRunner.Modal.TITLE);break;
        default:break;
        }
    }
    private void openGallery(GalleryCatalog.Screen screen,int group,int page,int index) {
        if (runner==null || titlePanel==null) return;
        if (screen==GalleryCatalog.Screen.VIEW && galleryPage!=null && galleryPage.screen==GalleryCatalog.Screen.CG)
            galleryCgPage=galleryPage.page;
        runner.requestGallery(screen,group,page,index,++galleryRequest);
    }
    private final GameGalleryPanel.Actions galleryActions=new GameGalleryPanel.Actions() {
        public void open(GalleryCatalog.Screen screen,int group,int page,int index) { openGallery(screen,group,page,index); }
        public void mode(int mode) {openMenuMode(mode,GameRunner.Modal.OMAKE);}
        public void back() { galleryBack(); }
        public void replay(int group,int index) {
            if (runner!=null) { replayReturn=galleryPage; runner.replayGallery(group,index); }
        }
        public void movie(int index) { if(runner!=null)runner.playGalleryMovie(index); }
        public void play(int index) { if (runner!=null) runner.playGallerySong(index); }
        public void moveSong(int direction) { if (runner!=null) runner.moveGallerySong(direction); }
        public void pause() { if (runner!=null) runner.pauseGallerySong(); }
        public void stop() { if (runner!=null) runner.stopGallerySong(); }
        public void volume(int volume) { if (runner!=null) runner.gallerySongVolume(volume); }
        public void cgFrame(GalleryCatalog.Page page,int frame,java.util.function.Consumer<Bitmap> done){
            GameRunner owner=runner;
            if(owner==null){done.accept(null);return;}
            owner.requestCgFrame(page,frame,bitmap->runOnUiThread(()->done.accept(owner==runner?bitmap:null)));
        }
    };
    @Override public void gallery(GalleryCatalog.Page page,long request) {
        runOnUiThread(() -> {
            if (request!=galleryRequest || isDestroyed() || isFinishing() || titlePanel==null || uiAssets==null) return;
            galleryPage=page;
            if (gamePanel instanceof GameGalleryPanel && ((GameGalleryPanel)gamePanel).screen()==page.screen)
                ((GameGalleryPanel)gamePanel).update(page);
            else {
                GameGalleryPanel panel=new GameGalleryPanel(this,uiAssets,page,this::systemClick,galleryActions,width,height);
                Bitmap previous=optionalGalleryPrevious;optionalGalleryPrevious=null;
                if(previous==null)showOriginalPanel(panel,this::galleryBack);
                else {runner.menu(true);panel.previous(previous);mountOriginalPanel(panel,this::galleryBack);}
            }
        });
    }
    @Override public void spectrum(int[] bands){runOnUiThread(()->{
        if(gamePanel instanceof GameGalleryPanel)((GameGalleryPanel)gamePanel).spectrum(bands);
    });}
    private void galleryBack() {
        if(galleryPage==null||gamePanel==null)return;
        GalleryCatalog.Page page=galleryPage;
        switch (page.screen) {
        case VIEW:
            runner.playClick(page.config.number("backSound",2)-1,-1);
            openGallery(GalleryCatalog.Screen.CG,page.group,galleryCgPage,-1); break;
        case CG:
            openGallery(page.cgGroups?GalleryCatalog.Screen.CG_GROUP:GalleryCatalog.Screen.OMAKE,0,0,-1); break;
        case SCENE:
            openGallery(page.sceneGroups?GalleryCatalog.Screen.SCENE_GROUP:GalleryCatalog.Screen.OMAKE,0,0,-1); break;
        case OMAKE:
            leaveOriginalPanel(page.config); break;
        default:
            openGallery(GalleryCatalog.Screen.OMAKE,0,0,-1); break;
        }
    }
    @Override public void galleryReplayStarted() {
        runOnUiThread(() -> {
            if (isDestroyed() || isFinishing()) return;
            sceneReplay=true; galleryPage=null; ++galleryRequest;
            closeOriginalPanel();
        });
    }
    @Override public void movie(File file,int volume,boolean canSkip,boolean startup){runOnUiThread(()->{
        if(isFinishing()||isDestroyed()||stage==null||runner==null)return;
        GameRunner owner=runner;
        if(!startup)clearStartupForStory();
        if(gamePanel!=null)gamePanel.uiActive(false);
        for(int i=0;i<stage.getChildCount();i++)
            stage.getChildAt(i).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        moviePlayback=new MoviePlayback(this,file,volume,canSkip,(source,outcome,failure)->{
            if(owner!=runner||source!=moviePlayback)return;
            moviePlayback=null;
            if(startup)stage.setForeground(new ColorDrawable(Color.BLACK));
            stage.removeView(source);
            for(int i=0;i<stage.getChildCount();i++)
                stage.getChildAt(i).setImportantForAccessibility(gamePanel==null||stage.getChildAt(i)==gamePanel
                    ?View.IMPORTANT_FOR_ACCESSIBILITY_AUTO:View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            if(gamePanel!=null)gamePanel.uiActive(foreground);
            owner.finishMovie(outcome,failure);
            updateVisibility();
        });
        if(!foreground)moviePlayback.pause();
        stage.addView(moviePlayback,new FrameLayout.LayoutParams(-1,-1));
        if(startup){
            clearStartupViews();
            removeTitleForStartup();
        }
        updateVisibility();immersive();
    });}
    @Override public void galleryReplayFinished() {
        runOnUiThread(() -> {
            if (isDestroyed() || isFinishing()) return;
            sceneReplay=false;
            GalleryCatalog.Page page=replayReturn; replayReturn=null;
            if (page!=null) openGallery(GalleryCatalog.Screen.SCENE,page.group,page.page,-1);
        });
    }
    private void showMenu() {
        if(runner==null){finish();return;}
        boolean modalOptions=visibleModal!=null&&(visibleModal.advancedSelection()||
            visibleModal.mode==GameRunner.Modal.SELECT_CHART||visibleModal.mode==GameRunner.Modal.SELECT_PLACE2);
        if(currentFrame!=null&&currentFrame.wait==NativeGame.MODAL&&!modalOptions)return;
        if(!modalOptions&&choices!=null){
            if(choices.confirming()||controls!=null&&controls.clicking())return;
            if(!choices.menuAllowed()){if(choices.backlogAllowed())showBacklog();return;}
        }else if(currentFrame!=null&&!currentFrame.optionsAllowed)return;
        if(controls!=null)controls.cancelClick();
        if(uiAssets!=null&&uiAssets.system.has("Menu1Button1FileName")){showOriginalMenu();return;}
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(text(R.string.engine_systemnnn_host_game_menu))
            .setItems(new String[]{text(R.string.engine_systemnnn_host_continue_game),text(R.string.engine_systemnnn_host_save_progress),text(R.string.engine_systemnnn_host_load_save),text(R.string.engine_systemnnn_host_settings),text(R.string.engine_systemnnn_host_backlog),text(R.string.engine_systemnnn_host_title),fromTyn?text(R.string.engine_systemnnn_host_exit_to_tyn):text(R.string.engine_systemnnn_host_exit_game)},(d,item) -> {
                systemClick();
                switch(item){case 1:showSlots(true);break;case 2:showSlots(false);break;case 3:showSettings();break;
                case 4:showBacklog();break;case 5:runner.returnToTitle();break;case 6:finish();break;default:break;}
            }).setNegativeButton(text(R.string.engine_systemnnn_host_close),dialogClick(null)).create();
        showPanel(dialog);
        runner.suspendScriptAudio();
    }
    private void showPanel(AlertDialog dialog) {
        panelsOpen++;openedPanels.add(dialog);if(runner!=null)runner.menu(true);updateVisibility();
        dialog.setOnDismissListener(d -> {
            openedPanels.remove(dialog);panelsOpen=Math.max(0,panelsOpen-1);
            if(isOverlayMode(gamePanel)&&modeUnderPanel!=null&&openedPanels.isEmpty()){
                if(resumeModeAfterPanel(null))return;
                lockModalOverlay(gamePanel,false);
            }
            if(runner!=null&&panelsOpen==0&&!isFinishing())runner.menu(false);
            updateVisibility();updateStatus();immersive();
        });
        dialog.show();
        for(int which:new int[]{DialogInterface.BUTTON_POSITIVE,DialogInterface.BUTTON_NEGATIVE,DialogInterface.BUTTON_NEUTRAL})
            if(dialog.getButton(which)!=null)dialog.getButton(which).setSoundEffectsEnabled(false);
        if(dialog.getListView()!=null)dialog.getListView().setSoundEffectsEnabled(false);
        if(dialog.getWindow()!=null)dialog.getWindow().setLayout(Math.min(dp(720),Math.max(dp(280),root.getWidth()-dp(40))),-2);
    }
    private void showOriginalPanel(GameUiPanel panel,Runnable back) {
        showOriginalPanel(panel,back,null);
    }
    private void showOriginalPanel(GameUiPanel panel,Runnable back,GameUiAssets.Config sceneEffect) {
        if(preparingPanel!=null){panel.discardBackground();return;}
        boolean needsPrevious=panel.wantsPrevious();
        GameRunner owner=runner;
        if(owner!=null)owner.menu(true);
        if(owner==null||!needsPrevious&&sceneEffect==null){
            mountOriginalPanel(panel,back);
            return;
        }
        // Keep the outgoing view visible until both transition and menu images are ready.
        preparingPanel=panel;
        if(sceneEffect!=null&&(captureStoryUi(sceneEffect)||needsPrevious)&&
            (gamePanel instanceof GameAdvancedSelectionPanel||gamePanel instanceof GamePlacePanel)){
            boolean withStoryUi=captureStoryUi(sceneEffect);
            gamePanel.capture(before->{
                if(preparingPanel!=panel)return;
                if(before==null){captureForPanel(panel,back,sceneEffect,needsPrevious,owner);return;}
                if(withStoryUi)filterCapturedPanel(panel,back,sceneEffect,needsPrevious,owner,before);
                else captureNativeBackground(panel,back,sceneEffect,owner,before);
            });
            return;
        }
        if(needsPrevious&&gamePanel!=null&&sceneEffect==null){
            gamePanel.capture(before->{
                if(!acceptPreparedPanel(panel,owner))return;
                panel.previous(before);
                mountOriginalPanel(panel,back);
            });
            return;
        }
        if(needsPrevious&&sceneEffect==null&&titlePanel!=null&&copyTitleForPanel(panel,back,owner))return;
        captureForPanel(panel,back,sceneEffect,needsPrevious,owner);
    }
    private boolean copyTitleForPanel(GameUiPanel panel,Runnable back,GameRunner owner){
        TitlePanel title=titlePanel;
        int w=title.getWidth(),h=title.getHeight();
        Surface source=storySurface;
        if(w<=0||h<=0||source==null||!source.isValid())return false;
        float scale=UiOpening.snapshotScale(w,h);
        Bitmap before=Bitmap.createBitmap(Math.max(1,Math.round(w*scale)),Math.max(1,Math.round(h*scale)),Bitmap.Config.ARGB_8888);
        before.setHasAlpha(false);
        try{
            // Read the presented title frame without waiting behind its next script step.
            PixelCopy.request(source,before,result->{
                if(result!=PixelCopy.SUCCESS){
                    before.recycle();
                    if(preparingPanel==panel)captureForPanel(panel,back,null,true,owner);
                    return;
                }
                if(!acceptPreparedPanel(panel,owner)){before.recycle();return;}
                UiOpening.drawOnto(title,before,c->drawTitleCapture(title,c));
                panel.previous(before);
                mountOriginalPanel(panel,back);
            },ui);
            return true;
        }catch(IllegalArgumentException e){
            before.recycle();
            return false;
        }
    }
    private boolean captureStoryUi(GameUiAssets.Config config){
        if(config==null||titlePanel!=null||currentFrame==null||uiAssets==null)return false;
        if(config.number("backScriptFlag",0)!=0&&config.number("getWithMessageFlag",1)==0)return false;
        String eraseFlag=currentFrame.wait==NativeGame.CHOICE?"selectMiniCGNoMessageFlag":"messageMiniCGNoMessageFlag";
        return uiAssets.game.number(eraseFlag,0)==0;
    }
    private void captureForPanel(GameUiPanel panel,Runnable back,GameUiAssets.Config sceneEffect,boolean needsPrevious,GameRunner owner){
        boolean withStoryUi=captureStoryUi(sceneEffect);
        owner.capture(withStoryUi?null:sceneEffect,needsPrevious||withStoryUi,(previous,scene)->runOnUiThread(()->{
            if(preparingPanel!=panel)return;
            Bitmap before=null;
            try{
                TitlePanel title=titlePanel;
                if(previous!=null)before=title==null?snapshotStory(previous,true,withStoryUi):
                    UiOpening.snapshot(title,previous.bitmap(),canvas->drawTitleCapture(title,canvas));
            }catch(RuntimeException|OutOfMemoryError e){
                Log.w("SystemNNN","Unable to capture the full menu screen",e);
            }
            if(withStoryUi&&before!=null)filterCapturedPanel(panel,back,sceneEffect,needsPrevious,owner,before);
            else if(withStoryUi)captureNativeBackground(panel,back,sceneEffect,owner,null);
            else finishCapturedPanel(panel,back,sceneEffect,owner,before,scene);
        }));
    }
    private void filterCapturedPanel(GameUiPanel panel,Runnable back,GameUiAssets.Config effect,boolean needsPrevious,
                                     GameRunner owner,Bitmap before){
        owner.filterPanel(before,effect,filtered->runOnUiThread(()->{
            if(preparingPanel!=panel)return;
            Bitmap previous=needsPrevious?before:null;
            if(filtered!=null)finishCapturedPanel(panel,back,effect,owner,previous,filtered);
            else captureNativeBackground(panel,back,effect,owner,previous);
        }));
    }
    private void captureNativeBackground(GameUiPanel panel,Runnable back,GameUiAssets.Config effect,GameRunner owner,Bitmap before){
        owner.capture(effect,false,(unused,scene)->runOnUiThread(()->finishCapturedPanel(panel,back,effect,owner,before,scene)));
    }
    private void finishCapturedPanel(GameUiPanel panel,Runnable back,GameUiAssets.Config effect,GameRunner owner,
                                     Bitmap before,GameRunner.Capture scene){
        if(!acceptPreparedPanel(panel,owner))return;
        if(before!=null)panel.previous(before);
        if(effect!=null)installCapturedBackground(panel,effect,scene);
        mountOriginalPanel(panel,back);
    }
    private void installCapturedBackground(GameUiPanel panel,GameUiAssets.Config config,GameRunner.Capture scene){
        if(scene==null){panel.uiBackground(config,13,1);return;}
        panel.capturedBackground(scene.bitmap(),()->{if(gamePanel==panel)updateVisibility();});
        panel.uiBackground(config,13,1,scene.pixels,scene.width,scene.height);
    }
    private void drawTitleCapture(TitlePanel title,Canvas canvas){
        title.draw(canvas);
        if(independentMode==4&&independentStatus!=null&&independentStatus.getVisibility()==View.VISIBLE)
            independentStatus.draw(canvas);
    }
    private Bitmap snapshotStory(GameRunner.Capture picture,boolean withUi){
        return snapshotStory(picture,withUi,false);
    }
    private Bitmap snapshotStory(GameRunner.Capture picture,boolean withUi,boolean logicalSize){
        if(logicalSize){
            Bitmap base=picture.mutableBitmap();
            if(stage.getWidth()>0&&stage.getHeight()>0)
                UiOpening.drawOnto(stage,base,canvas->drawStorySnapshot(canvas,withUi,false));
            return base;
        }
        return UiOpening.snapshot(stage,picture.bitmap(),canvas->drawStorySnapshot(canvas,withUi,true));
    }
    private void drawStorySnapshot(Canvas canvas,boolean withUi,boolean withFloatingUi){
        boolean screenComposited=storyScreen.paint(canvas,withUi,withFloatingUi,false);
        for(int i=0;i<stage.getChildCount();i++){
            View child=stage.getChildAt(i);
            if(child==dialoguePanel&&!withFloatingUi){
                // The PC removes the wait cursor and quick buttons before capturing the menu background.
                if(!screenComposited&&withUi)dialoguePanel.drawMessagePlane(canvas);
            }else if(child==longPrintBackdrop||child==dialoguePanel||child==choices){
                if(!screenComposited&&(withUi||child==longPrintBackdrop))drawStageChild(canvas,child);
            }else if(withUi&&withFloatingUi&&(child==controls||child==status||child==quickNoticeView||child==independentStatus)){
                drawStageChild(canvas,child);
            }
        }
    }
    private static void drawStageChild(Canvas canvas,View child){
        if(child==null||child.getVisibility()!=View.VISIBLE||child.getAlpha()<=0)return;
        int saved=canvas.save();
        canvas.translate(child.getLeft(),child.getTop());
        canvas.concat(child.getMatrix());
        canvas.clipRect(0,0,child.getWidth(),child.getHeight());
        if(child.getAlpha()<1)canvas.saveLayerAlpha(0,0,child.getWidth(),child.getHeight(),Math.round(child.getAlpha()*255));
        child.draw(canvas);
        canvas.restoreToCount(saved);
    }

    // Slot 31 is a screen effect. It sees the game and message art together,
    // while the option buttons keep their original drawing and hit positions.
    private final class StoryScreen extends View {
        private final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);
        private final RectF destination=new RectF();
        private GameRunner owner;
        private long layer;
        private Bitmap image;
        private Canvas messagePlane;
        private GameRunner.Frame composedFrame;
        private boolean composedWithUi,composedWithBackdrop,composedForStory,compositionDirty=true;

        StoryScreen(){
            super(PlayerActivity.this);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        private void releaseLayer(){
            if(layer>0)NativeGame.releaseScreenLayer(layer);
            layer=0;
        }
        void clear(){
            releaseLayer();
            owner=null;image=null;messagePlane=null;composedFrame=null;compositionDirty=true;
        }
        void messageChanged(boolean recomposite){
            if(recomposite)compositionDirty=true;
            if(layer>0)invalidate();
        }
        void windowChanged(){
            if(layer!=0&&composedWithBackdrop==uiHidden)invalidate();
        }
        @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){
            super.onSizeChanged(w,h,oldw,oldh);
            compositionDirty=true;
        }
        boolean paint(Canvas canvas,boolean withUi,boolean withFloatingUi,boolean live){
            if(owner!=runner){clear();owner=runner;}
            if(owner==null)return false;
            long update=owner.takeScreenLayer();
            if(update!=0){
                releaseLayer();
                layer=update>0?update:0;
                compositionDirty=true;
                if(layer==0){image=null;messagePlane=null;}
            }
            GameRunner.Frame visible=drawnStoryFrame;
            if(layer==0||visible==null||getWidth()<=0||getHeight()<=0)return false;
            try{
                if(image==null||image.getWidth()!=width||image.getHeight()!=height){
                    image=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
                    messagePlane=new Canvas(image);
                    compositionDirty=true;
                }
                boolean withBackdrop=withUi&&!uiHidden;
                if(compositionDirty||composedWithUi!=withUi||composedWithBackdrop!=withBackdrop||
                    composedFrame!=visible||live&&!composedForStory){
                    compositionDirty=false;
                    image.eraseColor(Color.TRANSPARENT);
                    int saved=messagePlane.save();
                    messagePlane.scale(width/(float)getWidth(),height/(float)getHeight());
                    drawStageChild(messagePlane,longPrintBackdrop);
                    if(withUi){
                        dialoguePanel.drawMessagePlane(messagePlane);
                        if(choices!=null)choices.drawOptions(messagePlane);
                    }
                    messagePlane.restoreToCount(saved);
                    if(!NativeGame.compositeScreenLayer(layer,image,visible.wait,visible.word,visible.line,withBackdrop,live)){
                        compositionDirty=true;
                        return false;
                    }
                    composedFrame=visible;
                    composedWithUi=withUi;
                    composedWithBackdrop=withBackdrop;composedForStory=live;
                }
                destination.set(0,0,getWidth(),getHeight());
                canvas.drawBitmap(image,null,destination,paint);
                if(withUi){
                    if(choices!=null)choices.drawTimer(canvas);
                    if(withFloatingUi)dialoguePanel.drawMessageCursor(canvas);
                }
                return true;
            }catch(RuntimeException|OutOfMemoryError e){
                clear();
                Log.w("SystemNNN","Unable to compose the message screen layer",e);
                return false;
            }
        }
        @Override protected void onDraw(Canvas canvas){
            paint(canvas,true,true,true);
        }
        @Override protected void onDetachedFromWindow(){clear();super.onDetachedFromWindow();}
    }
    private boolean acceptPreparedPanel(GameUiPanel panel,GameRunner owner){
        if(preparingPanel!=panel)return false;
        preparingPanel=null;
        if(owner==runner&&!isDestroyed()&&!isFinishing()&&moviePlayback==null)return true;
        panel.discardBackground();
        if(owner==runner&&!isDestroyed()&&!isFinishing()&&panelsOpen==0)owner.menu(false);
        return false;
    }
    private void mountOriginalPanel(GameUiPanel panel,Runnable back) {
        if(quickNoticeView!=null)quickNoticeView.clear();
        if(gamePanel!=null)stage.removeView(gamePanel);else panelsOpen++;
        for(int i=0;i<stage.getChildCount();i++)stage.getChildAt(i).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        gamePanel=panel;if(!isModalPanel(panel))visibleModal=null;
        panel.canvas(width,height);panelBack=back;stage.addView(panel,new FrameLayout.LayoutParams(-1,-1));
        panel.sound((se,voice)->{if(runner!=null)runner.playClick(se,voice);});panel.uiActive(foreground);
        updateVisibility();immersive();
    }
    private void discardPanelPreparation() {
        if(preparingPanel==null)return;
        preparingPanel.discardBackground();
        preparingPanel=null;
        if(isOverlayMode(gamePanel)&&modeUnderPanel!=null){
            lockModalOverlay(gamePanel,false);modeUnderPanel=null;
        }
    }
    private void closeOriginalPanel() {
        closeOriginalPanel(true);
    }
    private void closeOriginalPanel(boolean resume) {
        panelWarmup=null;
        discardPanelPreparation();
        if(gamePanel==null)return;
        stage.removeView(gamePanel);gamePanel=null;gameSlots=null;panelBack=null;visibleModal=null;
        updatePanelAction();
        for(int i=0;i<stage.getChildCount();i++)stage.getChildAt(i).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        panelsOpen=Math.max(0,panelsOpen-1);
        if(resume&&runner!=null&&panelsOpen==0){
            if(resumeModeAfterPanel(null))return;
            if(currentFrame!=null&&currentFrame.wait==NativeGame.MODAL){showScriptModal(null);return;}
            if(currentFrame!=null&&currentFrame.wait==NativeGame.PLACE){showPlace(null);return;}
            runner.menu(false);
        }
        updateVisibility();updateStatus();immersive();
    }
    private void leaveOriginalPanel(GameUiAssets.Config config){
        GameUiPanel panel=gamePanel;
        if(panel==null||runner==null)return;
        panel.leave(config,false,(picture,filled)->{
            if(panel!=gamePanel||isDestroyed()||isFinishing())return;
            if(resumeModeAfterPanel(picture))return;
            if(titlePanel==null){
                boolean childPanel=config==uiAssets.config||config==uiAssets.save||config==uiAssets.load;
                enterStory(picture,filled&&childPanel);return;
            }
            if(galleryPage!=null){++galleryRequest;galleryPage=null;runner.closeGallery();}
            titlePrevious=picture;returningToTitle=true;
            runner.reenterTitle();
        });
        updatePanelActionEnabled();
    }
    private void enterStory(Bitmap previous){enterStory(previous,false);}
    private void enterStory(Bitmap previous,boolean filledChildPanel){
        if(currentFrame!=null&&currentFrame.wait==NativeGame.MODAL){closeOriginalPanel(false);showScriptModal(previous);return;}
        if(currentFrame!=null&&currentFrame.wait==NativeGame.PLACE){closeOriginalPanel(false);showPlace(previous);return;}
        if(previous==null||currentFrame==null){closeOriginalPanel(false);runner.menu(false);return;}
        boolean choice=currentFrame.wait==NativeGame.CHOICE;
        GameUiAssets.Config config=choice?uiAssets.selection:uiAssets.printMessage;
        if(choice&&filledChildPanel&&config.number("enterFadeInType",0)==0)config=uiAssets.printMessage;
        UiOpening opening=new UiOpening(config,currentFrame.frameMs,false);
        if(!opening.hasFade()){closeOriginalPanel(false);runner.menu(false);return;}
        if(opening.needsWipeTarget()){
            GameRunner owner=runner;GameUiPanel panel=gamePanel;
            owner.capture(null,true,(picture,ignored)->runOnUiThread(()->{
                if(owner==runner&&panel==gamePanel&&!isFinishing()&&!isDestroyed())finishEnteringStory(opening,previous,picture);
            }));
        }else finishEnteringStory(opening,previous,null);
    }
    private void finishEnteringStory(UiOpening opening,Bitmap previous,GameRunner.Capture picture){
        closeOriginalPanel(false);
        if(picture!=null)opening.target(snapshotStory(picture,!opening.delaysControls()));
        sceneEntrance=new SceneEntrance(opening,previous);
        stage.addView(sceneEntrance,new FrameLayout.LayoutParams(-1,-1));
        placeIndependentStatus();
        runner.resumeScriptAudio();
    }
    private void showScriptModal(Bitmap previous){
        GameRunner.Modal modal=currentFrame==null?null:currentFrame.modal;
        showModePanel(modal,previous);
    }
    private static boolean isModalPanel(GameUiPanel panel){
        return panel instanceof GameInputPanel||panel instanceof GameReferenceSelectionPanel||
            panel instanceof GameOptionalCatalogPanel||panel instanceof GameProgressViewsPanel||panel instanceof GamePlacePanel||
            isOverlayMode(panel)||panel instanceof GameInventoryPanel||panel instanceof GameStatusPartyPanel;
    }
    private static boolean isOverlayMode(GameUiPanel panel){
        return panel instanceof GameAdvancedSelectionPanel||panel instanceof GameChartPanel;
    }
    private static void lockModalOverlay(GameUiPanel panel,boolean locked){
        if(panel instanceof GameAdvancedSelectionPanel)((GameAdvancedSelectionPanel)panel).overlayInput(locked);
        else if(panel instanceof GameChartPanel)((GameChartPanel)panel).overlayInput(locked);
    }
    private void overlayModal(Runnable action){
        if(!isOverlayMode(gamePanel)||visibleModal==null)return;
        lockModalOverlay(gamePanel,true);
        modeUnderPanel=visibleModal;
        action.run();
    }
    private boolean resumeModeAfterPanel(Bitmap previous){
        GameRunner.Modal pending=modeUnderPanel;
        if(pending==null||runner==null)return false;
        modeUnderPanel=null;
        GameRunner owner=runner;
        owner.actModal(pending,GameRunner.Modal.RESUME,0,update->runOnUiThread(()->{
            if(owner!=runner||isDestroyed()||isFinishing())return;
            owner.resumeScriptAudio();
            if(update.status<0){
                if(update.modal!=null)showModePanel(update.modal,previous);else enterStory(previous);
            }
            else if(update.status==0)showModePanel(update.modal,previous);
            else finishModePanel(owner,update,previous);
        }));
        return true;
    }
    private void openMenuMode(int mode,int caller){
        GameRunner owner=runner;
        GameUiPanel before=gamePanel;
        if(owner==null||titlePanel==null)return;
        owner.openMenuModal(mode,caller,modal->runOnUiThread(()->{
            if(modal==null||owner!=runner||before!=gamePanel||isDestroyed()||isFinishing())return;
            if(modal.problem!=null||before==null||galleryPage==null){showModePanel(modal,null);return;}
            before.leave(galleryPage.config,false,picture->{
                if(owner==runner&&before==gamePanel&&!isDestroyed()&&!isFinishing())showModePanel(modal,picture);
            });
        }));
    }
    private void showModePanel(GameRunner.Modal modal,Bitmap previous){
        GameRunner owner=runner;
        if(owner==null||uiAssets==null||modal==null)return;
        if(modal.problem!=null){abortModePanel(modal,previous);return;}
        visibleModal=modal;
        GameUiPanel panel;
        if(modal.textInput()){
            panel=new GameInputPanel(this,uiAssets,modal.assets.config,modal.assets.images,modal.decoder,modal.mode,
                width,height,modal.arguments,modal.byteLimits,modal.text,this::systemClick,(accepted,elapsed,values,picture)->{
                    GameUiPanel active=gamePanel;
                    if(!(active instanceof GameInputPanel)||owner!=runner||visibleModal!=modal)return;
                    owner.completeModal(modal,accepted,elapsed,values,update->runOnUiThread(()->{
                        if(active!=gamePanel||owner!=runner||isDestroyed()||isFinishing())return;
                        if(update.status<0){active.cancelLeave();return;}
                        finishModePanel(owner,update,picture);
                    }));
                });
        }else if(modal.catalog()){
            panel=new GameOptionalCatalogPanel(this,uiAssets,modal,width,height,this::systemClick,this::actModePanel);
        }else if(modal.progressView()){
            panel=new GameProgressViewsPanel(this,uiAssets,modal,width,height,this::systemClick,this::actModePanel);
        }else if(modal.inventory()){
            panel=new GameInventoryPanel(this,uiAssets,modal,width,height,this::systemClick,this::actModePanel);
        }else if(modal.statusView()){
            panel=new GameStatusPartyPanel(this,uiAssets,modal,width,height,this::systemClick,this::actModePanel);
        }else if(modal.mode==GameRunner.Modal.SELECT_PLACE2){
            panel=new GamePlacePanel(this,uiAssets,currentFrame,this::systemClick,this::showMenu,
                place->actModePanel(GameRunner.Modal.CHOOSE,place));
        }else if(modal.mode==GameRunner.Modal.SELECT_CHART){
            boolean options=titlePanel==null&&(currentFrame==null||currentFrame.optionsAllowed);
            panel=new GameChartPanel(this,uiAssets,modal,width,height,this::systemClick,this::actModePanel,
                options?()->overlayModal(this::showMenu):null);
        }else if(modal.advancedSelection()){
            GameAdvancedSelectionPanel selection=new GameAdvancedSelectionPanel(this,uiAssets,modal,width,height,this::systemClick,this::actModePanel);
            boolean object=modal.mode==GameRunner.Modal.SELECT_OBJECT;
            boolean options=currentFrame==null||currentFrame.optionsAllowed;
            selection.optionActions(options?()->overlayModal(this::showMenu):null,
                options&&object?()->overlayModal(this::showSettings):null,
                object&&titlePanel==null&&modal.assets.config.number("backLogOk",0)!=0?()->overlayModal(this::showBacklog):null);
            panel=selection;
        }else{
            panel=new GameReferenceSelectionPanel(this,uiAssets,modal,width,height,this::systemClick,this::actModePanel);
        }
        if(modal.mode==GameRunner.Modal.SELECT_ZUKAN)panel.onBackdropFailure(error->{
            if(owner==runner&&panel==gamePanel&&visibleModal!=null)
                abortModePanel(visibleModal,"Cannot render reference detail: "+error.getMessage(),null);
        });
        else if(modal.mode==GameRunner.Modal.SELECT_PLACE2)panel.onBackdropFailure(error->{
            if(owner==runner&&panel==gamePanel&&visibleModal==modal)
                abortModePanel(modal,"Cannot render place background: "+error.getMessage(),null);
        });
        if(previous==null)showOriginalPanel(panel,null);
        else {owner.menu(true);panel.previous(previous);mountOriginalPanel(panel,null);}
    }
    private void actModePanel(int action,int value){
        GameUiPanel active=gamePanel;
        GameRunner owner=runner;
        GameRunner.Modal request=visibleModal;
        if(!isModalPanel(active)||owner==null||request==null)return;
        if((request.mode==GameRunner.Modal.SELECT_OBJECT||request.mode==GameRunner.Modal.SELECT_PLACE2)&&
            action==GameRunner.Modal.CHOOSE){
            active.leave(request.assets.config,false,picture->{
                if(active!=gamePanel||owner!=runner||isDestroyed()||isFinishing())return;
                owner.actModal(request,action,value,update->runOnUiThread(()->{
                    if(active!=gamePanel||owner!=runner||isDestroyed()||isFinishing())return;
                    if(update.status<0)active.cancelLeave();else finishModePanel(owner,update,picture);
                }));
            });
            return;
        }
        owner.actModal(request,action,value,update->runOnUiThread(()->{
            if(active!=gamePanel||owner!=runner||isDestroyed()||isFinishing())return;
            if(update.status<0){active.cancelLeave();return;}
            if(update.status==GameRunner.Modal.LEAVE_READY){
                commitChartAfterLeave((GameChartPanel)active,owner,request);
                return;
            }
            if(update.status==0){
                if(update.modal.problem!=null){abortModePanel(update.modal,null);return;}
                visibleModal=update.modal;
                if(active instanceof GameOptionalCatalogPanel)((GameOptionalCatalogPanel)active).update(visibleModal);
                else if(active instanceof GameProgressViewsPanel)((GameProgressViewsPanel)active).update(visibleModal);
                else if(active instanceof GameAdvancedSelectionPanel)((GameAdvancedSelectionPanel)active).update(visibleModal);
                else if(active instanceof GameInventoryPanel)((GameInventoryPanel)active).update(visibleModal);
                else if(active instanceof GameStatusPartyPanel)((GameStatusPartyPanel)active).update(visibleModal);
                else if(active instanceof GameChartPanel)((GameChartPanel)active).update(visibleModal);
                else ((GameReferenceSelectionPanel)active).update(visibleModal);
                return;
            }
            active.leave(request.assets.config,false,picture->{
                if(active==gamePanel&&owner==runner&&!isDestroyed()&&!isFinishing())finishModePanel(owner,update,picture);
            });
        }));
    }
    private void commitChartAfterLeave(GameChartPanel panel,GameRunner owner,GameRunner.Modal request){
        panel.leave(request.assets.config,false,picture->{
            if(panel!=gamePanel||owner!=runner||isDestroyed()||isFinishing())return;
            owner.actModal(request,GameRunner.Modal.COMMIT,0,update->runOnUiThread(()->{
                if(panel!=gamePanel||owner!=runner||isDestroyed()||isFinishing())return;
                if(update.status==0&&update.modal!=null){
                    if(update.modal.problem!=null){abortModePanel(update.modal,picture);return;}
                    visibleModal=update.modal;
                    panel.update(visibleModal);
                    panel.cancelLeave();
                }else if(update.status<0)panel.cancelLeave();
                else finishModePanel(owner,update,picture);
            }));
        });
    }
    private void abortModePanel(GameRunner.Modal modal,Bitmap previous){
        abortModePanel(modal,modal.problem,previous);
    }
    private void abortModePanel(GameRunner.Modal modal,String reason,Bitmap previous){
        GameRunner owner=runner;
        owner.abortModal(modal,reason,update->runOnUiThread(()->{
            if(owner==runner&&!isDestroyed()&&!isFinishing()&&update.status>=0)finishModePanel(owner,update,previous);
        }));
    }
    private void finishModePanel(GameRunner owner,GameRunner.ModalUpdate update,Bitmap picture){
        if(update.modal!=null){showModePanel(update.modal,picture);return;}
        if(update.returnMode==GameRunner.Modal.OMAKE){
            optionalGalleryPrevious=picture;
            if(titlePanel==null){openGalleryAfterTitle=true;owner.returnToTitle();}
            else openGallery(GalleryCatalog.Screen.OMAKE,0,0,-1);
        }else if(update.returnMode==GameRunner.Modal.TITLE){
            titlePrevious=picture;returningToTitle=true;
            if(titlePanel==null)owner.returnToTitle();else owner.reenterTitle();
        }else if(update.wait==NativeGame.MOVIE||update.wait==NativeGame.TITLE){
            closeOriginalPanel(false);
            owner.menu(false);
        }else enterStory(picture);
    }
    private void showPlace(Bitmap previous){
        GameRunner owner=runner;
        if(owner==null||uiAssets==null||currentFrame==null||currentFrame.wait!=NativeGame.PLACE)return;
        GameUiAssets.Config config=uiAssets.place(currentFrame.placeMode);
        GamePlacePanel panel=new GamePlacePanel(this,uiAssets,currentFrame,this::systemClick,this::showMenu,place->{
            GameUiPanel active=gamePanel;
            if(!(active instanceof GamePlacePanel))return;
            active.leave(config,false,picture->{
                if(active!=gamePanel||owner!=runner||isDestroyed()||isFinishing())return;
                owner.choosePlace(place,success->runOnUiThread(()->{
                    if(active!=gamePanel||owner!=runner||isDestroyed()||isFinishing())return;
                    if(success)enterStory(picture);else active.cancelLeave();
                }));
            });
        });
        if(previous==null)showOriginalPanel(panel,null);
        else {owner.menu(true);panel.previous(previous);mountOriginalPanel(panel,null);}
    }
    private final class SceneEntrance extends View {
        private final UiOpening opening;
        private final GameRunner owner;
        SceneEntrance(UiOpening opening,Bitmap previous){
            super(PlayerActivity.this);this.opening=opening;owner=runner;opening.previous(previous);setClickable(true);
        }
        void skip(){opening.skip();invalidate();}
        @Override protected void onDetachedFromWindow(){opening.close();super.onDetachedFromWindow();}
        @Override public boolean onTouchEvent(MotionEvent event){
            if(opening.touch(event))invalidate();
            return true;
        }
        @Override protected void onDraw(Canvas canvas){
            opening.begin();
            if(opening.overlay(canvas,this,width,height))postInvalidateOnAnimation();
            else if(sceneEntrance==this){
                sceneEntrance=null;
                setVisibility(INVISIBLE);
                if(owner==runner&&!isDestroyed()&&!isFinishing())owner.menu(false);
                FrameLayout container=stage;
                // Defer the child-list mutation until this draw traversal is done.
                container.post(()->{
                    if(getParent()==container)container.removeView(this);
                    placeIndependentStatus();
                });
            }
        }
    }
    private void showOriginalMenu() {
        GameUiAssets.Config cfg=uiAssets.system;
        boolean scripted=cfg.number("backScriptFlag",0)!=0;
        int backgroundType=scripted?3:cfg.number("backScreenType",2);
        GameSystemMenuPanel panel=new GameSystemMenuPanel(this,uiAssets,backgroundType==1?cfg.value("backScreenFileName",null):null,this::systemClick);
        if(backgroundType!=1)panel.transparentBackground();
        panel.opening(cfg,true);
        boolean capture=backgroundType==2||backgroundType==3&&cfg.number("screenToGetPutFlag",0)!=0;
        if(!capture)panel.uiBackground(cfg,13,1);
        String base=cfg.value("filenameMenuBase","");
        Bitmap baseImage=uiAssets.image(base);
        if(baseImage!=null)panel.picture(base,cfg.number("basePrintX",0),cfg.number("basePrintY",0),baseImage.getWidth(),baseImage.getHeight());
        String[] keys={"saveButton","loadButton","configButton","windowButton","messageSkipButton","backlogButton","autoModeButton","cancelButton","exitButton"};
        String[] labels={text(R.string.engine_systemnnn_host_save_progress),text(R.string.engine_systemnnn_host_load_save),text(R.string.engine_systemnnn_host_settings),text(R.string.engine_systemnnn_host_hide_dialogue),text(R.string.engine_systemnnn_host_skip),text(R.string.engine_systemnnn_host_backlog),text(R.string.engine_systemnnn_host_auto),text(R.string.engine_systemnnn_host_continue_game),text(R.string.engine_systemnnn_host_title)};
        int[] ys={67,115,163,259,307,211,357,404,525};
        Runnable back=()->leaveOriginalPanel(cfg);
        Runnable[] actions={()->showSlots(true),()->showSlots(false),this::showSettings,
            ()->{closeOriginalPanel();hideUi();},()->{closeOriginalPanel();runner.skip(true);},this::showBacklog,
            ()->{closeOriginalPanel();runner.auto(true);},back,()->confirmOriginalExit(panel)};
        Set<Integer> standardSlots=new HashSet<>();
        for(int i=0;i<keys.length;i++){
            int n=cfg.number(keys[i],i+1);if(n<=0||i==5&&choices!=null&&!choices.backlogAllowed())continue;
            if(currentFrame!=null&&currentFrame.placeMode!=0&&
                (i==3||i==4||i==6||i==5&&uiAssets.place(currentFrame.placeMode).number("backLogOk",0)==0))continue;
            if(modeUnderPanel!=null&&(i==3||i==4||i==6||i==5&&modeUnderPanel.assets.config.number("backLogOk",0)==0))continue;
            GameButton button=panel.button(cfg,"Menu1Button"+n,"Menu1Button1",labels[i],actions[i],940,ys[i],320,72);
            standardSlots.add(n);
            if(i==0)button.prepare(()->warmStoryPanel(GameStoryControlsModel.Action.SAVE));
            if(i==1)button.prepare(()->warmStoryPanel(GameStoryControlsModel.Action.LOAD));
            if(i==2)button.prepare(()->warmStoryPanel(GameStoryControlsModel.Action.CONFIG));
        }
        panel.addExtendedModes(standardSlots,GameRunner.Modal::systemMenuMode,mode->!sceneReplay&&runner!=null&&runner.systemMenuModeEnabled(mode),
            mode->openSystemMenuMode(panel,mode));
        showOriginalPanel(panel,back,capture?cfg:null);
        if(runner!=null)runner.suspendScriptAudio();
    }
    private void openSystemMenuMode(GameSystemMenuPanel menu,int mode){
        GameRunner owner=runner;
        if(owner==null||menu!=gamePanel||menu.preparingExtension())return;
        menu.preparingExtension(true);
        owner.openSystemMenuModal(mode,modal->runOnUiThread(()->{
            menu.preparingExtension(false);
            if(owner!=runner||menu!=gamePanel||isDestroyed()||isFinishing()||modal==null)return;
            modeUnderPanel=null;
            if(modal.problem!=null){abortModePanel(modal,null);return;}
            menu.leave(uiAssets.system,false,picture->{
                if(owner==runner&&menu==gamePanel&&!isDestroyed()&&!isFinishing())showModePanel(modal,picture);
            });
        }));
    }
    private void confirmOriginalExit(GameSystemMenuPanel panel) {
        if(gamePanel!=panel||runner==null)return;
        Runnable confirm=()->{
            if(sceneReplay){closeOriginalPanel();runner.returnToTitle();return;}
            if(returningToTitle)return;
            panel.leave(uiAssets.system,true,picture->{
                if(isDestroyed()||isFinishing()||gamePanel!=panel||runner==null)return;
                titlePrevious=picture;returningToTitle=true;
                runner.returnToTitle();
            });
        };
        if(sceneReplay||uiAssets.system.number("warningPrintFlag",1)==0){confirm.run();return;}
        panel.confirmExit(confirm,()->leaveOriginalPanel(uiAssets.system));
    }
    private LinearLayout column() {LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(18),dp(8),dp(18),dp(14));return box;}
    private void showSettings() {
        if(uiAssets!=null&&(uiAssets.config.has("modename")||uiAssets.settingsModel(width,height).original)){
            Runnable back=()->leaveOriginalPanel(uiAssets.config);
            showOriginalPanel(new GameSettingsPanel(this,uiAssets,preferences,runner,this::systemClick,back,width,height,runner.configMask(),takePanelWarmup()),back);return;
        }
        LinearLayout box=column();
        TextView hint=label(text(R.string.engine_systemnnn_host_settings_hint),14);hint.setPadding(0,0,0,dp(10));box.addView(hint);
        volumeSetting(box,text(R.string.engine_systemnnn_host_music),PlayerSettings.MUSIC,settings.musicVolume);
        volumeSetting(box,text(R.string.engine_systemnnn_host_sound),PlayerSettings.SOUND,settings.soundVolume);
        volumeSetting(box,text(R.string.engine_systemnnn_host_voice),PlayerSettings.VOICE,settings.voiceVolume);
        volumeSetting(box,text(R.string.engine_systemnnn_host_effect_voice),PlayerSettings.EFFECT_VOICE,settings.effectVoiceVolume);
        choiceSetting(box,text(R.string.engine_systemnnn_host_text_speed),PlayerSettings.TEXT_SPEED,new String[]{text(R.string.engine_systemnnn_host_slow),text(R.string.engine_systemnnn_host_normal),text(R.string.engine_systemnnn_host_fast),text(R.string.engine_systemnnn_host_instant)},settings.textSpeed,false);
        choiceSetting(box,text(R.string.engine_systemnnn_host_auto_speed),PlayerSettings.AUTO_SPEED,new String[]{text(R.string.engine_systemnnn_host_slow),text(R.string.engine_systemnnn_host_normal),text(R.string.engine_systemnnn_host_fast)},settings.autoSpeed,false);
        choiceSetting(box,text(R.string.engine_systemnnn_host_skip_range),PlayerSettings.SKIP_READ_ONLY,new String[]{text(R.string.engine_systemnnn_host_read_only),text(R.string.engine_systemnnn_host_all_text)},settings.skipReadOnly?0:1,true);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(box);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(text(R.string.engine_systemnnn_host_settings)).setView(scroll)
            .setNeutralButton(text(R.string.engine_systemnnn_host_reading_size),null).setPositiveButton(text(R.string.engine_systemnnn_host_done),dialogClick(null)).create();
        showPanel(dialog);
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(v->showReadingSettings());
        if(dialog.getWindow()!=null)dialog.getWindow().setLayout(Math.min(dp(760),root.getWidth()-dp(32)),Math.max(dp(200),root.getHeight()-dp(32)));
    }
    private void showReadingSettings() {
        int[] sizes=PlayerSettings.READING_SIZES;
        String[] labels=new String[sizes.length];
        int selected=0;
        for(int i=0;i<sizes.length;i++){
            int size=sizes[i];
            labels[i]=size+"%";
            if(size==PlayerSettings.DEFAULT_READING_SIZE)labels[i]=text(R.string.engine_systemnnn_reading_default,labels[i]);
            else if(size==100)labels[i]=text(R.string.engine_systemnnn_reading_original,labels[i]);
            if(size==settings.readingSize)selected=i;
        }
        showPanel(new AlertDialog.Builder(this).setTitle(text(R.string.engine_systemnnn_host_reading_title))
            .setSingleChoiceItems(labels,selected,(d,i)->userPreferences.edit().putInt(PlayerSettings.READING_SIZE,sizes[i]).apply())
            .setNeutralButton(text(R.string.engine_input_reset),(d,i)->userPreferences.edit().remove(PlayerSettings.READING_SIZE).apply())
            .setPositiveButton(text(R.string.engine_systemnnn_host_done),null).create());
    }
    private void volumeSetting(LinearLayout box,String title,String key,int current) {
        TextView value=label(title+"  "+current+"%",15);box.addView(value);
        SeekBar slider=new SeekBar(this);slider.setMax(100);slider.setProgress(current);slider.setContentDescription(text(R.string.engine_systemnnn_host_volume_label,title));slider.setSoundEffectsEnabled(false);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar seek,int progress,boolean user){value.setText(title+"  "+progress+"%");if(user)preferences.edit().putInt(key,progress).apply();}
            public void onStartTrackingTouch(SeekBar seek){systemClick();}public void onStopTrackingTouch(SeekBar seek){}
        });box.addView(slider,new LinearLayout.LayoutParams(-1,dp(40)));
    }
    private void choiceSetting(LinearLayout box,String title,String key,String[] labels,int selected,boolean bool) {
        TextView heading=label(title,15);heading.setPadding(0,dp(8),0,0);box.addView(heading);
        RadioGroup group=new RadioGroup(this);group.setOrientation(LinearLayout.HORIZONTAL);
        for(int i=0;i<labels.length;i++){RadioButton button=new RadioButton(this);button.setId(View.generateViewId());button.setText(labels[i]);button.setTextColor(Color.WHITE);button.setTextSize(14);button.setSoundEffectsEnabled(false);button.setOnClickListener(clicked(null));button.setTag(i);group.addView(button);if(i==selected)button.setChecked(true);}
        group.setOnCheckedChangeListener((g,id) -> {View selectedView=g.findViewById(id);if(selectedView==null)return;int index=(Integer)selectedView.getTag();
            if(bool)preferences.edit().putBoolean(key,index==0).apply();else preferences.edit().putInt(key,index).apply();});
        box.addView(group);
    }
    private void showBacklog() {
        if(uiAssets==null||runner==null)return;
        if(choices!=null&&(choices.confirming()||!choices.backlogAllowed()))return;
        if(currentFrame!=null&&currentFrame.placeMode!=0&&uiAssets.place(currentFrame.placeMode).number("backLogOk",0)==0)return;
        Runnable back=()->{if(runner!=null)runner.closeBacklog();leaveOriginalPanel(uiAssets.backlog);};
        showOriginalPanel(new GameBacklogPanel(this,uiAssets,dialogueStyle,width,height,back,
            name->{if(runner!=null)runner.replayBacklogVoice(name);},this::jumpBacklog),back);
        runner.requestBacklog();
    }
    private void jumpBacklog(long id){
        GameUiPanel panel=gamePanel;
        GameRunner owner=runner;
        if(!(panel instanceof GameBacklogPanel)||owner==null)return;
        owner.backlogJumpCue();
        panel.leaveForBacklogJump(uiAssets.backlog,picture->{
            if(panel!=gamePanel||owner!=runner||isDestroyed()||isFinishing())return;
            owner.jumpBacklog(id,success->runOnUiThread(()->{
                if(panel!=gamePanel||owner!=runner||isDestroyed()||isFinishing())return;
                if(success)enterStory(picture);else panel.cancelLeave();
            }));
        });
    }
    @Override public void backlog(List<GameRunner.BacklogLine> lines){runOnUiThread(()->{
        GameUiPanel panel=preparingPanel instanceof GameBacklogPanel?preparingPanel:gamePanel;
        if(panel instanceof GameBacklogPanel)((GameBacklogPanel)panel).entries(lines);
    });}
    private void showSlots(boolean saving) {
        if(runner==null)return;savingSlots=saving;
        if(uiAssets!=null){
            GameUiAssets.Config config=saving?uiAssets.save:uiAssets.load;
            Runnable back=()->leaveOriginalPanel(config);
            gameSlots=new GameSlotsPanel(this,uiAssets,saving,runner.slotEntries(),runner.lastSelectedSlot(),this::systemClick,back,slot->{
                if(saving)runner.saveSlot(slot);
                else{
                    GameUiPanel panel=gamePanel;
                    panel.leave(config,false,(picture,filled)->{
                        if(panel!=gamePanel||isDestroyed()||isFinishing())return;
                        runner.loadSlot(slot,success->runOnUiThread(()->{
                            if(panel!=gamePanel||isDestroyed()||isFinishing())return;
                            if(success)enterStory(picture,filled);else panel.cancelLeave();
                        }));
                    });
                }
            },saving?this::choosePcExport:this::choosePcSave,takePanelWarmup());showOriginalPanel(gameSlots,back);return;
        }
        slotRows=column();ScrollView scroll=new ScrollView(this);scroll.addView(slotRows);
        AlertDialog.Builder slotDialog=new AlertDialog.Builder(this).setTitle(saving?text(R.string.engine_systemnnn_host_save_progress):text(R.string.engine_systemnnn_host_load_save)).setView(scroll).setNegativeButton(text(R.string.engine_systemnnn_host_back),dialogClick(null));
        slotDialog.setNeutralButton(GameSlotsPanel.pcTransferLabel(this,saving),(dialog,which)->{
            if(saving)choosePcExport();else choosePcSave();
        });
        slotsDialog=slotDialog.create();
        showPanel(slotsDialog);if(slotsDialog.getWindow()!=null)slotsDialog.getWindow().setLayout(Math.min(dp(850),root.getWidth()-dp(32)),Math.max(dp(200),root.getHeight()-dp(32)));
        slotsChanged();
    }
    private void showOtherSaves(){
        if(!(gamePanel instanceof GameSlotsPanel))return;
        GameSlotsPanel panel=(GameSlotsPanel)gamePanel;
        List<SaveSlots.Entry> entries=panel.otherSaves();
        if(entries.isEmpty())return;
        CharSequence[] labels=new CharSequence[entries.size()];
        DateFormat format=DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT);
        for(int i=0;i<entries.size();i++){
            SaveSlots.Entry entry=entries.get(i);
            labels[i]=entry.label(this)+" · "+format.format(new Date(entry.savedAt))+"\n"+entry.displaySummary(this);
        }
        showPanel(new AlertDialog.Builder(this).setTitle(text(R.string.engine_systemnnn_ctl_other_saves)).setItems(labels,(dialog,index)->{
            if(panel==gamePanel){systemClick();panel.loadOther(entries.get(index).slot);}
        }).setNegativeButton(text(R.string.engine_systemnnn_host_back),dialogClick(null)).create());
    }
    @Override public void slotsChanged() {runOnUiThread(() -> {
        if(runner==null||isDestroyed()||isFinishing())return;
        if(gameSlots!=null&&(gamePanel==gameSlots||preparingPanel==gameSlots)){
            gameSlots.entries(runner.slotEntries());updatePanelAction();return;
        }
        if(slotRows==null || slotsDialog==null || !slotsDialog.isShowing())return;slotRows.removeAllViews();
        TextView hint=label(savingSlots?text(R.string.engine_systemnnn_host_save_hint):text(R.string.engine_systemnnn_host_load_hint),14);hint.setPadding(0,0,0,dp(10));slotRows.addView(hint);
        for(SaveSlots.Entry entry:runner.slotEntries()){
            String detail=entry.corrupt?text(R.string.engine_systemnnn_host_save_corrupt):!entry.exists?text(R.string.engine_systemnnn_host_save_empty):DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(entry.savedAt))+
                "\n"+text(R.string.engine_systemnnn_host_save_location,entry.script,entry.line)+
                (entry.speaker.isEmpty()?"":"  ·  "+entry.speaker)+(entry.displaySummary(this).isEmpty()?"":"\n"+entry.displaySummary(this));
            Button button=new Button(this);button.setAllCaps(false);button.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
            button.setPadding(dp(14),dp(8),dp(14),dp(8));button.setText(entry.label(this)+"\n"+detail);
            button.setEnabled(savingSlots?entry.slot!=SaveSlots.AUTO:entry.exists&&!entry.corrupt);
            button.setSoundEffectsEnabled(false);button.setOnClickListener(clicked(v -> {
                if(savingSlots && entry.exists){AlertDialog confirm=new AlertDialog.Builder(this).setTitle(text(R.string.engine_systemnnn_host_overwrite_prompt,entry.label(this))).setMessage(detail)
                    .setNegativeButton(text(R.string.engine_cancel),dialogClick(null)).setPositiveButton(text(R.string.engine_systemnnn_host_overwrite_save),dialogClick((d,which) -> runner.saveSlot(entry.slot))).create();showPanel(confirm);}
                else if(savingSlots)runner.saveSlot(entry.slot);
                else {runner.loadSlot(entry.slot);slotsDialog.dismiss();}
            }));slotRows.addView(button,new LinearLayout.LayoutParams(-1,-2));
        }
    });}
    @Override public void titleReturnCancelled(){runOnUiThread(()->{
        if(isDestroyed()||isFinishing())return;
        returningToTitle=false;
        titlePrevious=null;
        openGalleryAfterTitle=false;
        optionalGalleryPrevious=null;
        if(gamePanel instanceof GameSystemMenuPanel)gamePanel.cancelLeave();
        else if(gamePanel!=null)closeOriginalPanel();
        updateVisibility();
    });}
    @Override public void notice(String text) { runOnUiThread(() -> {
        if(!isDestroyed()) Toast.makeText(this,text,Toast.LENGTH_SHORT).show();
    }); }
    @Override public void progressCorrupt(){runOnUiThread(()->{
        GameRunner owner=runner;
        if(isDestroyed()||isFinishing()||owner==null)return;
        new AlertDialog.Builder(this).setTitle(text(R.string.engine_systemnnn_host_progress_corrupt))
            .setMessage(text(R.string.engine_systemnnn_host_progress_recovery_hint))
            .setCancelable(false)
            .setNegativeButton(text(R.string.engine_systemnnn_host_back),dialogClick((d,w)->finish()))
            .setPositiveButton(text(R.string.engine_systemnnn_host_progress_recover),
                dialogClick((d,w)->{if(owner==runner)owner.recoverCorruptProgress();}))
            .show();
    });}
    @Override public void error(Throwable reason,long generation) { runOnUiThread(() -> {
        if (!startupGeneration.fail(generation)) return;
        if (isDestroyed() || isFinishing()) return;
        if(startupOverlay!=null&&startupOverlay.isLoading())
            startupOverlay.fail(null);
        if(returningToTitle){returningToTitle=false;titlePrevious=null;closeOriginalPanel(false);}
        String detail=reason.getMessage();
        if(detail!=null&&detail.contains("spt/sys.spt")&&
            (detail.contains("SPT header")||detail.contains("SPTHEADER0")||detail.contains("Record length"))) {
            new AlertDialog.Builder(this).setTitle(text(R.string.engine_systemnnn_host_invalid_script_title))
                .setMessage(text(R.string.engine_systemnnn_host_invalid_script_hint))
                .setNegativeButton(text(R.string.engine_systemnnn_host_back),dialogClick((d,w) -> finish())).setPositiveButton(text(R.string.engine_systemnnn_host_pick_other),dialogClick((d,w) -> {
                    startActivity(new Intent(this,PlayerActivity.class).setAction(Intent.ACTION_MAIN)
                        .putExtra(LaunchContract.UI_LANGUAGE_TAG,getIntent().getStringExtra(LaunchContract.UI_LANGUAGE_TAG)));
                    finish();
                })).show(); return;
        }
        GameRunner owner=runner;
        boolean startOver=owner!=null&&owner.initialized();
        String message=text(reason instanceof SystemNnnHost.SaveBusyException?R.string.engine_systemnnn_host_save_busy:
            startOver?R.string.engine_systemnnn_host_runtime_interrupted:R.string.engine_systemnnn_host_startup_retry_hint);
        if(startOver)message+="\n\n"+text(R.string.engine_systemnnn_host_start_over_warning);
        new AlertDialog.Builder(this).setTitle(text(R.string.engine_systemnnn_host_game_interrupted)).setMessage(message)
            .setCancelable(false)
            .setNegativeButton(text(R.string.engine_systemnnn_host_exit_to_tyn),dialogClick((d,w) -> finish()))
            .setPositiveButton(startOver?text(R.string.engine_systemnnn_host_start_over):text(R.string.engine_systemnnn_host_retry_load),
                dialogClick((d,w) -> {if(owner!=null&&owner==runner){resetStartupOverlayForRetry();owner.restart(startOver);}})).show();
    }); }
    private String text(int resource,Object... arguments){return uiContext.getString(resource,arguments);}
    private void resetStartupOverlayForRetry() {
        startupGeneration.advance();
        if(startupOverlay!=null)startupOverlay.dispose();
        startupOverlay=new EngineStartupOverlay(this);
        startupOverlay.attachTo(root);
    }
    private int dp(int n) {return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView label(String s,int size) {
        TextView t=new TextView(this); t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP,size); t.setText(s); return t;
    }
    private GradientDrawable rounded(int color,int radius) {
        GradientDrawable bg=new GradientDrawable(); bg.setColor(color); bg.setCornerRadius(dp(radius)); return bg;
    }
    private void message(String title,String body,String action,View.OnClickListener listener) {
        contentHost.removeAllViews(); LinearLayout box=new LinearLayout(this); box.setGravity(Gravity.CENTER);
        box.setPadding(dp(32),dp(16),dp(32),dp(16)); box.setOrientation(LinearLayout.VERTICAL);
        TextView heading=label(title,22); heading.setTypeface(Typeface.DEFAULT,Typeface.BOLD); box.addView(heading);
        TextView description=label(body,16); description.setPadding(0,dp(12),0,dp(12)); box.addView(description);
        if (action!=null) {Button b=new Button(this); b.setText(action);b.setSoundEffectsEnabled(false);b.setOnClickListener(clicked(listener)); box.addView(b);}
        Button back=new Button(this); back.setText(text(R.string.engine_systemnnn_host_back));back.setSoundEffectsEnabled(false);back.setOnClickListener(clicked(v -> finish())); box.addView(back);
        contentHost.addView(box,new FrameLayout.LayoutParams(-1,-1));
    }
    private void immersive() {
        boolean full=settings==null||settings.fullscreen;
        View decor=getWindow().getDecorView();
        if(full)getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        // Handle insets ourselves on every supported version. Android15+
        // enforces edge-to-edge for target35, including the non-fullscreen mode.
        if(Build.VERSION.SDK_INT>=30){
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller=decor.getWindowInsetsController();
            if(controller!=null){
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                if(full)controller.hide(WindowInsets.Type.systemBars());else controller.show(WindowInsets.Type.systemBars());
            }
        }
        decor.setSystemUiVisibility(full?View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION:View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        if(root!=null)root.requestApplyInsets();
    }
    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if(focus){
            immersive();
            retryAudioFocus();
        }
    }
    @Override public void onUserInteraction(){
        super.onUserInteraction();
        if(hasWindowFocus())retryAudioFocus();
    }
    private void retryAudioFocus(){
        if(!foreground)return;
        if(moviePlayback!=null)moviePlayback.retryFocus();
        else if(runner!=null)runner.windowFocused();
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if(pcSaveBusy||returningToTitle||preparingPanel!=null)return true;
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){storyDownX=event.getRawX();storyDownY=event.getRawY();storyDrag=false;}
        if(storyPointerActive()){
            if((action==MotionEvent.ACTION_MOVE||action==MotionEvent.ACTION_UP)&&
                Math.hypot(event.getRawX()-storyDownX,event.getRawY()-storyDownY)>storyTouchSlop)storyDrag=true;
            if(action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_MOVE||action==MotionEvent.ACTION_UP)storyPointer(event);
        }
        if(action==MotionEvent.ACTION_UP&&storyDrag){
            storyDrag=false;
            MotionEvent cancel=MotionEvent.obtain(event);cancel.setAction(MotionEvent.ACTION_CANCEL);
            try{super.dispatchTouchEvent(cancel);}finally{cancel.recycle();}
            return true;
        }
        if(action==MotionEvent.ACTION_CANCEL)storyDrag=false;
        return super.dispatchTouchEvent(event);
    }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent event){
        if(event.getActionMasked()==MotionEvent.ACTION_HOVER_MOVE&&storyPointerActive())storyPointer(event);
        return super.dispatchGenericMotionEvent(event);
    }
    private boolean storyPointerActive(){
        return runner!=null&&stage!=null&&titlePanel==null&&logoPanel==null&&panelsOpen==0&&moviePlayback==null;
    }
    private void storyPointer(MotionEvent event){
        stage.getLocationOnScreen(storyOrigin);
        int x=Math.max(0,Math.min(width-1,(int)((event.getRawX()-storyOrigin[0])*width/Math.max(1,stage.getWidth()))));
        int y=Math.max(0,Math.min(height-1,(int)((event.getRawY()-storyOrigin[1])*height/Math.max(1,stage.getHeight()))));
        if(x!=lastX||y!=lastY){lastX=x;lastY=y;runner.pointer(x,y);}
    }
    @Override public void onBackPressed() {
        if(pcSaveBusy)return;
        panelWarmup=null;
        if(moviePlayback!=null){moviePlayback.skip();return;}
        if(logoPanel!=null){logoPanel.skip();return;}
        if(startupOpening!=null){startupOpening.skip();return;}
        if(titlePanel!=null&&titlePanel.skipRestartExit())return;
        if(launchingGame&&runner!=null)return;
        if(sceneEntrance!=null){sceneEntrance.skip();return;}
        if(returningToTitle)return;
        if(preparingPanel!=null){
            if(preparingPanel instanceof GamePlacePanel||isModalPanel(preparingPanel))return;
            discardPanelPreparation();
            if(runner!=null&&panelsOpen==0)runner.menu(false);
            return;
        }
        if(gamePanel!=null){
            if(gamePanel instanceof GameSystemMenuPanel&&((GameSystemMenuPanel)gamePanel).preparingExtension())return;
            if(gamePanel.awaitingCapturedBackdrop()){closeOriginalPanel();return;}
            if(gamePanel.skipTransition())return;
            if(gamePanel.backOverlay())return;
            if(!(gamePanel instanceof GameBacklogPanel)&&
                !(gamePanel instanceof GameGalleryPanel&&((GameGalleryPanel)gamePanel).screen()==GalleryCatalog.Screen.VIEW))systemClick();
            if(panelBack!=null)panelBack.run();else closeOriginalPanel();
        }else if(sceneReplay){if(runner!=null)runner.endGalleryReplay();}
        else if(uiHidden){uiHidden=false;updateVisibility();}
        else if(runner==null||titlePanel!=null)finish();
        else showMenu();
    }
    @Override protected void onResume() { super.onResume(); foreground=true; immersive();
        if(gamePanel!=null)gamePanel.uiActive(true);
        statusActive(true);
        if(permissionPending) {permissionPending=false; inspectAccess();}
        if(runner!=null) runner.active(true);
        if(moviePlayback!=null)moviePlayback.resume();
    }
    @Override protected void onPause() {foreground=false;if(gamePanel!=null)gamePanel.uiActive(false);statusActive(false); if(runner!=null) runner.active(false);
        if(moviePlayback!=null)moviePlayback.pause();super.onPause();}
    @Override protected void onDestroy() {
        startupGeneration.dispose();
        if(startupOverlay!=null)startupOverlay.dispose();
        startupOverlay=null;
        if(moviePlayback!=null){moviePlayback.dispose();moviePlayback=null;}
        ui.removeCallbacksAndMessages(null);
        discardPanelPreparation();
        if(preferences!=null)preferences.unregisterOnSharedPreferenceChangeListener(settingsListener);
        if(userPreferences!=null)userPreferences.unregisterOnSharedPreferenceChangeListener(readingListener);
        for(AlertDialog dialog:new ArrayList<>(openedPanels))dialog.dismiss();
        closeRunner();
        super.onDestroy();
    }
    private void closeRunner(){
        if(runner==null){closePlayerTask();return;}
        runner.close(playerTask);
        playerTask=null;
        runner=null;
    }
    private void closePlayerTask(){
        if(playerTask==null)return;
        try{playerTask.close();}catch(IOException e){android.util.Log.w("SystemNNN","Unable to release player task",e);}
        playerTask=null;
    }
    @Override public void onRequestPermissionsResult(int code,String[] perms,int[] grants) {
        super.onRequestPermissionsResult(code,perms,grants);
        if(code==21 && runner==null) {permissionPending=false; inspectAccess();}
    }
}
