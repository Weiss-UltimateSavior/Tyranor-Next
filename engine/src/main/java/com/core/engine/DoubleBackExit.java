package com.core.engine;

import android.app.Activity;
import android.view.KeyEvent;

import java.util.Map;
import java.util.WeakHashMap;

public final class DoubleBackExit {
    private static final long EXIT_WINDOW_MS = 500L;
    private static final Map<Activity, Long> LAST_BACK_TIME = new WeakHashMap<>();
    private static final Map<Activity, Boolean> SUPPRESS_BACK_UP = new WeakHashMap<>();

    private DoubleBackExit() { }

    public interface ExitAction {
        void exit();
    }

    public static boolean shouldExit(Activity activity) {
        if (activity == null) return true;
        long now = android.os.SystemClock.elapsedRealtime();
        Long last = LAST_BACK_TIME.get(activity);
        if (last != null && now - last <= EXIT_WINDOW_MS) {
            LAST_BACK_TIME.remove(activity);
            return true;
        }
        LAST_BACK_TIME.put(activity, now);
        return false;
    }

    public static void handleBack(Activity activity, ExitAction action) {
        if (shouldExit(activity) && action != null) action.exit();
    }

    public static boolean dispatchBackKey(Activity activity, KeyEvent event, ExitAction action) {
        if (event == null || event.getKeyCode() != KeyEvent.KEYCODE_BACK) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                SUPPRESS_BACK_UP.put(activity, true);
                handleBack(activity, action);
            }
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_UP && SUPPRESS_BACK_UP.remove(activity) != null) {
            return true;
        }
        return event.getAction() == KeyEvent.ACTION_MULTIPLE;
    }

    public static void clear(Activity activity) {
        if (activity != null) {
            LAST_BACK_TIME.remove(activity);
            SUPPRESS_BACK_UP.remove(activity);
        }
    }
}
