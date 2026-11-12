package com.tyranor.next.webdavprobe;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

/** Opt-in debug host: exercises real Application lifecycle hooks without loading an engine. */
public final class SaveSyncProbeActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable close = this::finish;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!getPackageName().startsWith("com.tyranor.next.webdavacceptance")) {
            finish();
            return;
        }
        TextView text = new TextView(this);
        text.setText("WebDAV acceptance lifecycle host (not a native game)");
        setContentView(text);
        long duration = Math.max(1_000L, Math.min(10_000L,
                getIntent().getLongExtra("acceptance_duration_ms", 4_000L)));
        handler.postDelayed(close, duration);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(close);
        super.onDestroy();
    }
}
