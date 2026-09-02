package com.core.engine;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class KrkrStartupDialogPolicyTest {
    private static final long START = 1_000L;

    @Test
    public void acceptsSingleButtonDuringStartupWindow() {
        assertTrue(KrkrStartupDialogPolicy.shouldAutoConfirm(true, START, START, new String[]{"OK"}));
        assertTrue(KrkrStartupDialogPolicy.shouldAutoConfirm(
                true, START, START + KrkrStartupDialogPolicy.STARTUP_WINDOW_MS - 1L,
                new String[]{"确定"}));
    }

    @Test
    public void treatsNullButtonsAsLegacyDefaultOk() {
        assertTrue(KrkrStartupDialogPolicy.shouldAutoConfirm(true, START, START + 1L, null));
    }

    @Test
    public void rejectsDisabledMultiButtonAndEmptyDialogs() {
        assertFalse(KrkrStartupDialogPolicy.shouldAutoConfirm(false, START, START, new String[]{"OK"}));
        assertFalse(KrkrStartupDialogPolicy.shouldAutoConfirm(true, START, START, new String[]{"OK", "Cancel"}));
        assertFalse(KrkrStartupDialogPolicy.shouldAutoConfirm(true, START, START, new String[0]));
    }

    @Test
    public void rejectsAtOrAfterWindowAndWhenClockMovesBackwards() {
        assertFalse(KrkrStartupDialogPolicy.shouldAutoConfirm(
                true, START, START + KrkrStartupDialogPolicy.STARTUP_WINDOW_MS,
                new String[]{"OK"}));
        assertFalse(KrkrStartupDialogPolicy.shouldAutoConfirm(true, START, START - 1L, new String[]{"OK"}));
    }

    @Test
    public void keepsActionableErrorDialogsVisible() {
        assertFalse(KrkrStartupDialogPolicy.shouldAutoConfirm(
                true,
                START,
                START + 1L,
                "Error",
                "Unable to find the game data file",
                new String[]{"OK"}));
        assertFalse(KrkrStartupDialogPolicy.shouldAutoConfirm(
                true,
                START,
                START + 1L,
                "エラー",
                "ファイルが見つかりません",
                new String[]{"確認"}));
    }
}
