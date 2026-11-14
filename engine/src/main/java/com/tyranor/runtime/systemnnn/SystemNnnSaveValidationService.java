package com.tyranor.runtime.systemnnn;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Validates saves and manages save format configuration in a private worker process. */
public final class SystemNnnSaveValidationService extends Service {
    static final String ZIP_PREFIX = "save_zip_";
    private static final String TAG = "SystemNNNSave";
    private static final int VALIDATE = 1, VALID = 1, INVALID = 2, UNAVAILABLE = 3;
    private static final int LAYOUT_CONFIG = 4;
    private static final long TIMEOUT_NS = TimeUnit.SECONDS.toNanos(60);
    private HandlerThread worker;
    private Messenger messenger;

    @Override public void onCreate() {
        super.onCreate();
        worker = new HandlerThread("SystemNNN-SaveValidation");
        worker.start();
        messenger = new Messenger(new Handler(worker.getLooper(), this::handleRequest));
    }

    @Override public IBinder onBind(Intent intent) { return messenger.getBinder(); }

    @Override public void onDestroy() {
        worker.quit();
        super.onDestroy();
    }

    private boolean handleRequest(Message request) {
        if (request.sendingUid != Process.myUid()) return false;
        Messenger reply = request.replyTo;
        if (reply == null) return true;
        if (request.what == LAYOUT_CONFIG) {
            handleLayoutConfig(request.getData(),reply);
            return true;
        }
        if (request.what != VALIDATE) return false;
        int result;
        try {
            Bundle data = request.getData();
            Map<String, File> files = stagedFiles(data.getString("stage"));
            String path=data.getString("game");
            if(path==null)throw new IOException("Missing target game directory");
            File game=new File(path);
            if(!SystemNnnHost.canOpen(game))throw new IOException("Unreadable target game directory");
            NativeGame.load(this);
            try {
                result = SystemNnnHost.canReplaceSaveSnapshot(game,files, data.getBoolean("requiresStory")) ? VALID : INVALID;
            } catch (IOException failure) {
                result = failure.getCause() instanceof LinkageError || failure.getCause() instanceof OutOfMemoryError
                    ? UNAVAILABLE : INVALID;
            }
        } catch (IOException | RuntimeException | LinkageError | OutOfMemoryError failure) {
            Log.w(TAG, "Could not validate a staged SystemNNN save", failure);
            result = UNAVAILABLE;
        }
        reply(reply,result,null);
        return true;
    }

    private void handleLayoutConfig(Bundle data,Messenger reply) {
        try {
            String path=data.getString("game");
            if(path==null)throw new IOException("Missing SystemNNN game directory");
            File game=new File(path);
            if(!SystemNnnHost.canOpen(game))throw new IOException("Unreadable SystemNNN game directory");
            NativeGame.load(this);
            SaveLayoutConfig config=SaveLayoutConfig.open(game,data.getBoolean("regenerate"));
            if(config==null){reply(reply,INVALID,null);return;}
            Bundle result=new Bundle();
            result.putString("path",config.path);
            result.putIntArray("layout",config.fields());
            reply(reply,VALID,result);
        } catch (IOException | RuntimeException | LinkageError | OutOfMemoryError failure) {
            Log.w(TAG,"Could not access the SystemNNN layout config",failure);
            reply(reply,UNAVAILABLE,null);
        }
    }

    private static void reply(Messenger target,int status,Bundle data) {
        Message response=Message.obtain(null,status);
        if(data!=null)response.setData(data);
        try { target.send(response); }
        catch (RemoteException ignored) { /* The caller already went away. */ }
    }

    private Map<String, File> stagedFiles(String stage) throws IOException {
        if (stage == null || !stage.startsWith(ZIP_PREFIX) || stage.length() > 100 ||
            stage.indexOf('/') >= 0 || stage.indexOf('\\') >= 0) {
            throw new IOException("Invalid save staging identity");
        }
        Path root = getCacheDir().getCanonicalFile().toPath().resolve(stage);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Missing save staging directory");
        return stagedFiles(root);
    }

    static Map<String,File> stagedFiles(Path root) throws IOException {
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid save staging directory");
        Map<String, File> files = new LinkedHashMap<>();
        collectStagedFiles(root,root,files);
        return files;
    }

    private static void collectStagedFiles(Path root,Path folder,Map<String,File> files) throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
            for (Path file : entries) {
                Path relative=root.relativize(file);
                if(SystemNnnHost.isManagedSaveDirectory(relative)){
                    if(!Files.isDirectory(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid staged save directory");
                    collectStagedFiles(root,file,files);
                    continue;
                }
                if (!SystemNnnHost.isManagedSaveFile(relative)) continue;
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid staged save");
                files.put(relative.toString().replace(File.separatorChar,'/'),file.toFile());
            }
        }
    }

    static boolean validate(Context context,File game,File stage,boolean requiresStory,Runnable checkCancelled) throws IOException {
        Context app = context.getApplicationContext();
        if (!app.getCacheDir().getCanonicalFile().equals(stage.getParentFile())) throw new IOException("Invalid save staging path");
        Bundle args=new Bundle();
        args.putString("stage",stage.getName());
        args.putString("game",game.getPath());
        args.putBoolean("requiresStory",requiresStory);
        return request(app,VALIDATE,args,checkCancelled).status==VALID;
    }

    static SaveLayoutConfig layoutConfig(Context context,File game,boolean regenerate) throws IOException {
        Bundle args=new Bundle();
        args.putString("game",game.getPath());
        args.putBoolean("regenerate",regenerate);
        Response result=request(context.getApplicationContext(),LAYOUT_CONFIG,args,()->{});
        if(result.status==INVALID)return null;
        return SaveLayoutConfig.fromFields(result.data.getString("path"),result.data.getIntArray("layout"));
    }

    private static final class Response {
        final int status;
        final Bundle data;
        Response(int status,Bundle data){this.status=status;this.data=data;}
    }

    private static Response request(Context app,int operation,Bundle args,Runnable checkCancelled) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IOException("SystemNNN worker request requires a background thread");
        ArrayBlockingQueue<Response> results = new ArrayBlockingQueue<>(1);
        Response unavailable=new Response(UNAVAILABLE,null);
        Messenger reply = new Messenger(new Handler(Looper.getMainLooper(), response -> {
            results.offer(new Response(response.what,new Bundle(response.getData())));
            return true;
        }));
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                Message request = Message.obtain(null, operation);
                request.replyTo = reply;
                request.setData(args);
                try { new Messenger(binder).send(request); }
                catch (RemoteException failure) { results.offer(unavailable); }
            }
            @Override public void onServiceDisconnected(ComponentName name) { results.offer(unavailable); }
            @Override public void onBindingDied(ComponentName name) { results.offer(unavailable); }
            @Override public void onNullBinding(ComponentName name) { results.offer(unavailable); }
        };
        checkCancelled.run();
        try {
            boolean bound;
            try {
                bound = app.bindService(new Intent(app, SystemNnnSaveValidationService.class), connection, Context.BIND_AUTO_CREATE);
            } catch (RuntimeException failure) {
                throw new IOException("Could not bind the save validator", failure);
            }
            if (!bound) throw new IOException("Save validator unavailable");
            long start = System.nanoTime();
            while (System.nanoTime() - start < TIMEOUT_NS) {
                checkCancelled.run();
                Response result = results.poll(100, TimeUnit.MILLISECONDS);
                if (result == null) continue;
                checkCancelled.run();
                if (result.status == VALID || result.status == INVALID) return result;
                throw new IOException("Save validator unavailable");
            }
            throw new IOException("Save validation timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Save validation interrupted", interrupted);
        } finally {
            try { app.unbindService(connection); }
            catch (IllegalArgumentException failure) { Log.w(TAG, "Could not unbind the save validator", failure); }
        }
    }
}
