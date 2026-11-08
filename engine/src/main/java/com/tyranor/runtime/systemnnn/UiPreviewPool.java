package com.tyranor.runtime.systemnnn;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/** A worker and decoded asset cache for one game directory. Active views own separate VM leases. */
final class UiPreviewPool {
    private static final int MAX_SESSIONS=2; // One displayed background and one outgoing or prepared peer.
    static final class Request {
        private static final String ZUKAN_RESULT=new String("図鑑番号".getBytes(Charset.forName("windows-31j")),
            StandardCharsets.ISO_8859_1);
        final String script,variable;
        final int mode,page;
        final boolean restartOnPage,loop;
        private Request(String script,String variable,int mode,int page,boolean restartOnPage,boolean loop){
            this.script=script;this.variable=variable;this.mode=mode;this.page=page;
            this.restartOnPage=restartOnPage;this.loop=loop;
        }
        static Request read(GameUiAssets.Config cfg,int mode,int page){
            if(cfg.number("backScriptFlag",0)==0)return null;
            String script=cfg.value("backScriptName","");
            if(script.isEmpty())return null;
            boolean object=mode==GameRunner.Modal.SELECT_OBJECT;
            boolean place=mode==NativeGame.SELECT_PLACE_MODE||mode==NativeGame.SELECT_PLACE2_MODE;
            boolean zukan=mode==GameRunner.Modal.SELECT_ZUKAN;
            boolean restart=zukan||(place||object?(cfg.number("onButtonChangeToRestartBackScript",0)!=0&&
                (!object||!cfg.value("onButtonVarName","").isEmpty()))||cfg.number("autoPicRestartBackScript",0)!=0:
                cfg.number("changePageRestartScriptFlag",0)!=0);
            String variable=zukan?cfg.value("returnVarName",ZUKAN_RESULT):cfg.value("pageNumberVarName","");
            return new Request(script,variable,mode,page,restart,cfg.number("backScriptLoopFlag",1)!=0);
        }
        boolean sameScript(Request other){
            return mode==other.mode&&restartOnPage==other.restartOnPage&&loop==other.loop&&
                script.equals(other.script)&&variable.equals(other.variable);
        }
    }
    static final class Warmup {
        private final Request request;
        private Warmup(Request request){this.request=request;}
    }
    static int stepLimit(GameUiAssets.Config game){
        return Math.max(1,Math.min(64,game.number("maxSkipFrameBackScript",game.number("maxSkipFrame",2))));
    }
    private static final class Entry {
        final long handle;
        Surface surface;
        Lease lease;
        Warmup warmup;
        long revision;
        Entry(long handle){this.handle=handle;}
    }
    final class Lease {
        private final Entry entry;
        private Lease(Entry entry){this.entry=entry;}
        long handle(){return !closed&&entry.lease==this?entry.handle:0;}
        void surface(Surface surface){
            long handle=handle();
            if(handle==0){if(surface!=null)surface.release();return;}
            Surface previous=entry.surface;
            entry.surface=surface;
            try{NativeUiPreview.surface(handle,surface);}
            finally{if(previous!=null)previous.release();}
        }
        void release(boolean reusable){
            if(entry.lease!=this)return;
            try{if(entry.surface!=null)surface(null);}
            catch(RuntimeException|LinkageError e){reusable=false;Log.w("SystemNNN","Unable to detach menu surface",e);}
            finally{
                entry.lease=null;
                if(!reusable)dispose(entry);
            }
        }
    }

    private final RuntimeHandle runtime;
    private final byte[] root;
    private final HandlerThread worker=new HandlerThread("SystemNNN-Menu");
    final Handler tasks;
    private final ArrayList<Entry> entries=new ArrayList<>();
    private boolean preloaded;
    private volatile boolean closed;

    UiPreviewPool(File root,RuntimeHandle runtime){
        this.root=root.getPath().getBytes(StandardCharsets.UTF_8);this.runtime=runtime;
        worker.start();tasks=new Handler(worker.getLooper());
    }
    void preload(Request request){
        if(request==null||closed)return;
        tasks.post(()->{
            if(closed||preloaded)return;
            preloaded=true;
            prepareIdle(request,null);
            tasks.post(this::reservePeer);
        });
    }
    Warmup warm(Request request){
        if(request==null||closed)return null;
        Warmup warmup=new Warmup(request);
        return tasks.post(()->prepareIdle(request,warmup))?warmup:null;
    }
    private void reservePeer(){
        if(closed||entries.size()!=1)return;
        try(RuntimeHandle.Borrow context=runtime.borrowPreview()){
            if(context!=null)create(context.token);
        }catch(RuntimeException|LinkageError e){Log.w("SystemNNN","Unable to reserve menu preview peer",e);}
    }
    private void prepareIdle(Request request,Warmup warmup){
        Entry entry=null;
        try(RuntimeHandle.Borrow context=runtime.borrowPreview()){
            if(closed||context==null)return;
            entry=idle(context.token);
            if(entry==null)return;
            // A canceled click must not apply script variables or unlock gallery content.
            prime(entry,request,context.token,true,null,0,0);
            entry.warmup=warmup;entry.revision=context.revision;
        }catch(RuntimeException|LinkageError e){
            if(entry!=null)dispose(entry);
            Log.w("SystemNNN","Unable to warm menu background",e);
        }
    }
    Lease acquire(Request request,Warmup warmup,int[] pixels,int width,int height){
        try(RuntimeHandle.Borrow context=runtime.borrowPreview()){
            if(closed||context==null)return null;
            if(pixels==null&&warmup!=null&&request.page==warmup.request.page&&request.sameScript(warmup.request)){
                for(Entry entry:entries){
                    if(entry.lease==null&&entry.warmup==warmup&&entry.revision==context.revision&&
                       NativeUiPreview.claim(entry.handle,context.token))return lease(entry);
                }
            }
            Entry entry=idle(context.token);
            if(entry==null)return null;
            try{
                prime(entry,request,context.token,false,pixels,width,height);
                return lease(entry);
            }catch(RuntimeException|LinkageError e){dispose(entry);throw e;}
        }
    }
    private Lease lease(Entry entry){
        entry.warmup=null;
        return entry.lease=new Lease(entry);
    }
    private Entry idle(long token){
        for(Entry entry:entries)if(entry.lease==null){entry.warmup=null;return entry;}
        return entries.size()<MAX_SESSIONS?create(token):null;
    }
    private Entry create(long token){
        long peer=entries.isEmpty()?0:entries.get(0).handle;
        Entry entry=new Entry(NativeUiPreview.create(root,token,peer));
        entries.add(entry);
        return entry;
    }
    private static void prime(Entry entry,Request request,long token,boolean speculative,int[] pixels,int width,int height){
        if(!NativeUiPreview.reset(entry.handle,request.script.getBytes(StandardCharsets.ISO_8859_1),request.mode,
            request.variable.getBytes(StandardCharsets.ISO_8859_1),request.page,request.restartOnPage,request.loop,token,speculative))
            throw new IllegalStateException("Unable to start menu background: "+request.script);
        if(pixels!=null)NativeUiPreview.savedScreen(entry.handle,pixels,width,height);
        NativeUiPreview.prime(entry.handle);
    }
    private void dispose(Entry entry){
        entries.remove(entry);entry.lease=null;
        try{NativeUiPreview.close(entry.handle);}
        finally{if(entry.surface!=null)entry.surface.release();}
    }
    boolean isClosed(){return closed;}
    void close(){
        closed=true;
        tasks.post(()->{
            while(!entries.isEmpty())dispose(entries.get(entries.size()-1));
            worker.quitSafely();
        });
    }
}
