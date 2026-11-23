package com.core.engine;

import android.app.Activity;
import android.app.Dialog;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import org.tvp.kirikiri2.KrDialogStyle;

/** Delivers the original SDL button ID to the waiting native thread. */
public final class SdlMessageBox {
    private static final int DISMISSED = -1;
    private final Activity activity;
    private Request pending;
    private boolean closed;

    public SdlMessageBox(Activity activity) {
        this.activity = activity;
    }

    public int show(String title, String message, int[] flags, int[] ids, String[] labels,
                    boolean allowSingleButtonCancel, int failureResult) {
        if (Looper.myLooper() == Looper.getMainLooper() || flags == null || ids == null
                || labels == null || labels.length == 0 || flags.length != labels.length
                || ids.length != labels.length) return DISMISSED;

        Request request = new Request();
        synchronized (this) {
            if (closed || pending != null) return DISMISSED;
            pending = request;
        }

        activity.runOnUiThread(() -> {
            synchronized (this) {
                if (pending != request) return;
            }
            if (activity.isFinishing() || activity.isDestroyed()) {
                complete(request, DISMISSED);
                return;
            }
            try {
                String[] texts = new String[labels.length];
                for (int i = 0; i < labels.length; i++) texts[i] = localize(labels[i]);
                boolean cancelable = allowSingleButtonCancel && labels.length == 1;
                Dialog dialog = KrDialogStyle.createDialog(activity, title, message, null, texts,
                        cancelable, cancelable, (d, index) -> complete(request, ids[index]));
                request.dialog = dialog;
                dialog.setOnDismissListener(d -> complete(request, DISMISSED));
                dialog.setOnKeyListener((d, key, event) -> {
                    int flag = key == KeyEvent.KEYCODE_ENTER ? 1 : key == KeyEvent.KEYCODE_ESCAPE ? 2 : 0;
                    if (flag == 0) return false;
                    for (int i = flags.length - 1; i >= 0; i--) {
                        if ((flags[i] & flag) == 0) continue;
                        if (event.getAction() == KeyEvent.ACTION_UP) {
                            dialog.dismiss();
                            complete(request, ids[i]);
                        }
                        return true;
                    }
                    return false;
                });
                dialog.show();
            } catch (RuntimeException e) {
                Log.w("SdlMessageBox", "Cannot display SDL message box", e);
                close(request, failureResult);
            }
        });

        try {
            request.resultReady.await();
            return request.result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            activity.runOnUiThread(() -> close(request, DISMISSED));
            return DISMISSED;
        }
    }

    /** Close on the main thread before joining the native thread. */
    public void close() {
        Request request;
        synchronized (this) {
            closed = true;
            request = pending;
        }
        if (request != null) close(request, DISMISSED);
    }

    private void close(Request request, int result) {
        complete(request, result);
        if (request.dialog != null) request.dialog.dismiss();
    }

    private synchronized void complete(Request request, int result) {
        if (pending != request) return;
        pending = null;
        request.result = result;
        request.resultReady.countDown();
    }

    private String localize(String label) {
        if (label == null) return "";
        switch (label.trim().toLowerCase(Locale.ROOT)) {
            case "ok": return EngineUiText.get(activity, R.string.engine_ok);
            case "cancel": return EngineUiText.get(activity, R.string.engine_cancel);
            case "yes": return EngineUiText.get(activity, R.string.engine_yes);
            case "no": return EngineUiText.get(activity, R.string.engine_no);
            default: return label;
        }
    }

    public static void showStartupError(Activity activity, String error) {
        String detail = EngineUiText.localizedContext(activity)
                .getString(R.string.engine_sdl_start_failed_detail, error);
        KrDialogStyle.showMessageBox(activity, EngineUiText.get(activity, R.string.engine_launch_failed),
                detail, null, (which, text) -> activity.finish());
    }

    private static final class Request {
        final CountDownLatch resultReady = new CountDownLatch(1);
        int result;
        Dialog dialog;
    }
}
