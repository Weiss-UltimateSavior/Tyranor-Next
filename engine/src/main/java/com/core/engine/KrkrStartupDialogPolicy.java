package com.core.engine;

import java.util.Locale;

/**
 * Shared policy for automatically confirming KRKR's startup information dialogs.
 *
 * <p>The native KRKR integrations expose startup messages as ordinary message
 * boxes, so the portable distinction available to both kernels is the button
 * count and a short launch-time window. Input boxes use a separate API and are
 * never passed to this policy.</p>
 */
public final class KrkrStartupDialogPolicy {
    /** Intent extra used by both the Kirikiroid2 and SDL3 hosts. */
    public static final String EXTRA_ENABLED = "krSkipStartupDialogs";
    /** Issue #66 specifies a thirty-second startup window. */
    public static final long STARTUP_WINDOW_MS = 30_000L;

    private KrkrStartupDialogPolicy() {
    }

    /**
     * Compatibility overload for callers that do not expose dialog text.
     * Text-aware integrations should use the overload below so obvious errors
     * remain visible to the user.
     */
    public static boolean shouldAutoConfirm(
            boolean enabled,
            long launchStartElapsedMs,
            long nowElapsedMs,
            String[] buttons) {
        return shouldAutoConfirm(enabled, launchStartElapsedMs, nowElapsedMs, null, null, buttons);
    }

    /**
     * Returns whether a message box should be confirmed as its first button.
     * A null button array is treated as the legacy KRKR default (one OK button).
     */
    public static boolean shouldAutoConfirm(
            boolean enabled,
            long launchStartElapsedMs,
            long nowElapsedMs,
            String title,
            String message,
            String[] buttons) {
        if (!enabled || launchStartElapsedMs < 0L || nowElapsedMs < launchStartElapsedMs) {
            return false;
        }
        if (nowElapsedMs - launchStartElapsedMs >= STARTUP_WINDOW_MS) {
            return false;
        }
        if (looksLikeError(title, message)) {
            return false;
        }
        return buttons == null || buttons.length == 1;
    }

    /**
     * Keep one-button informational notices quiet while preserving actionable
     * startup failures such as missing files, exceptions, or failed loads.
     * Matching is intentionally language-agnostic for the supported UI
     * languages and common English engine messages.
     */
    private static boolean looksLikeError(String title, String message) {
        String text = ((title == null ? "" : title) + " "
                + (message == null ? "" : message)).toLowerCase(Locale.ROOT);
        String[] errorMarkers = {
                "error", "failed", "failure", "exception", "missing", "not found",
                "cannot", "can't", "unable", "错误", "失败", "异常", "未找到", "无法",
                "エラー", "失敗", "見つかりません"
        };
        for (String marker : errorMarkers) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
