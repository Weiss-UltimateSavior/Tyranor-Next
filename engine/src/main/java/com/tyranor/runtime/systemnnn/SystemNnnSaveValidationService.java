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

/** Read-only save validation in a private worker process before replacing live saves. */
public final class SystemNnnSaveValidationService extends Service {
    static final String ZIP_PREFIX = "save_zip_";
    private static final String TAG = "SystemNNNSave";
    private static final int VALIDATE = 1, VALID = 1, INVALID = 2, UNAVAILABLE = 3;
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
        if (request.what != VALIDATE || request.sendingUid != Process.myUid()) return false;
        Messenger reply = request.replyTo;
        if (reply == null) return true;
        int result;
        try {
            Bundle data = request.getData();
            Map<String, File> files = stagedFiles(data.getString("stage"));
            NativeGame.load(this);
            try {
                result = SystemNnnHost.canReplaceSaveSnapshot(files, data.getBoolean("requiresStory")) ? VALID : INVALID;
            } catch (IOException failure) {
                result = failure.getCause() instanceof LinkageError || failure.getCause() instanceof OutOfMemoryError
                    ? UNAVAILABLE : INVALID;
            }
        } catch (IOException | RuntimeException | LinkageError | OutOfMemoryError failure) {
            Log.w(TAG, "Could not validate a staged SystemNNN save", failure);
            result = UNAVAILABLE;
        }
        try { reply.send(Message.obtain(null, result)); }
        catch (RemoteException ignored) { /* The caller already went away. */ }
        return true;
    }

    private Map<String, File> stagedFiles(String stage) throws IOException {
        if (stage == null || !stage.startsWith(ZIP_PREFIX) || stage.length() > 100 ||
            stage.indexOf('/') >= 0 || stage.indexOf('\\') >= 0) {
            throw new IOException("Invalid save staging identity");
        }
        Path root = getCacheDir().getCanonicalFile().toPath().resolve(stage);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Missing save staging directory");
        Map<String, File> files = new LinkedHashMap<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root, GameSavePaths.PREFIX + "*")) {
            for (Path file : entries) {
                if (!SystemNnnHost.isManagedSaveFile(file.getFileName())) continue;
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid staged save");
                files.put(file.getFileName().toString(), file.toFile());
            }
        }
        return files;
    }

    static boolean validate(Context context, File stage, boolean requiresStory, Runnable checkCancelled) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IOException("Save validation requires a worker thread");
        Context app = context.getApplicationContext();
        if (!app.getCacheDir().getCanonicalFile().equals(stage.getParentFile())) throw new IOException("Invalid save staging path");
        ArrayBlockingQueue<Integer> results = new ArrayBlockingQueue<>(1);
        Messenger reply = new Messenger(new Handler(Looper.getMainLooper(), response -> {
            results.offer(response.what);
            return true;
        }));
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                Message request = Message.obtain(null, VALIDATE);
                request.replyTo = reply;
                request.getData().putString("stage", stage.getName());
                request.getData().putBoolean("requiresStory", requiresStory);
                try { new Messenger(binder).send(request); }
                catch (RemoteException failure) { results.offer(UNAVAILABLE); }
            }
            @Override public void onServiceDisconnected(ComponentName name) { results.offer(UNAVAILABLE); }
            @Override public void onBindingDied(ComponentName name) { results.offer(UNAVAILABLE); }
            @Override public void onNullBinding(ComponentName name) { results.offer(UNAVAILABLE); }
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
                Integer result = results.poll(100, TimeUnit.MILLISECONDS);
                if (result == null) continue;
                checkCancelled.run();
                if (result == VALID) return true;
                if (result == INVALID) return false;
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
