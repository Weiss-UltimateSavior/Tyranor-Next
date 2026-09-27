package com.core.siglus;

import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

/**
 * 软键盘接收视图：不显示任何内容，仅承载 {@link InputConnection} 把 IME 事件
 * 转成引擎事件（commit → text_input，组合中 → ime_preedit，退格 → 键事件）。
 *
 * <p>与 engine 既有 KRKR 宿主（{@code org.tvp.kirikiri2.KrTextInputView}）保持同一实现模式。
 */
final class SiglusTextInputView extends View {

    interface Listener {
        void onCommitText(String text);

        /** text 为 null 表示组合取消。 */
        void onPreedit(String text, int cursorStart, int cursorEnd);

        void onImeKeyEvent(int keyCode, boolean down);
    }

    private final Listener listener;

    SiglusTextInputView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setFocusable(true);
        setFocusableInTouchMode(true);
        setBackgroundColor(0x00000000);
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        // Keep suggestions off without requesting a password variation: a
        // VISIBLE_PASSWORD field makes IMEs (e.g. Baidu) switch to a hidden
        // "secure" window that never becomes visible, so the soft keyboard
        // never appeared for in-game name entry.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE
                | EditorInfo.IME_FLAG_NO_FULLSCREEN
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            outAttrs.initialCapsMode = 0;
        }
        return new Connection(this, true);
    }

    /** IME 光标区域同步（用于悬浮输入法定位）。 */
    void updateCursorAnchor(Rect rectPx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP || rectPx == null) {
            return;
        }
        try {
            android.view.inputmethod.CursorAnchorInfo.Builder builder =
                    new android.view.inputmethod.CursorAnchorInfo.Builder();
            builder.setInsertionMarkerLocation(
                    rectPx.left, rectPx.top, rectPx.bottom, rectPx.bottom, 0);
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.updateCursorAnchorInfo(this, builder.build());
            }
        } catch (Throwable ignored) {
            // 光标锚点仅影响输入法定位，失败不影响文本输入。
        }
    }

    private final class Connection extends BaseInputConnection {

        Connection(View targetView, boolean fullEditor) {
            super(targetView, fullEditor);
        }

        @Override
        public boolean commitText(CharSequence text, int newCursorPosition) {
            if (listener != null && text != null && text.length() > 0) {
                listener.onCommitText(text.toString());
            }
            return super.commitText(text, newCursorPosition);
        }

        @Override
        public boolean setComposingText(CharSequence text, int newCursorPosition) {
            if (listener != null) {
                int length = text == null ? 0 : text.length();
                listener.onPreedit(text == null ? "" : text.toString(), 0, length);
            }
            return super.setComposingText(text, newCursorPosition);
        }

        @Override
        public boolean finishComposingText() {
            if (listener != null) {
                listener.onPreedit(null, -1, -1);
            }
            return super.finishComposingText();
        }

        @Override
        public boolean deleteSurroundingText(int beforeLength, int afterLength) {
            if (beforeLength == 1 && afterLength == 0 && listener != null) {
                listener.onImeKeyEvent(KeyEvent.KEYCODE_DEL, true);
                listener.onImeKeyEvent(KeyEvent.KEYCODE_DEL, false);
                return true;
            }
            return super.deleteSurroundingText(beforeLength, afterLength);
        }

        @Override
        public boolean sendKeyEvent(KeyEvent event) {
            if (listener != null) {
                boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
                if (down && event.isPrintingKey()) {
                    int unicode = event.getUnicodeChar();
                    if (unicode != 0) {
                        listener.onCommitText(String.valueOf((char) unicode));
                        return true;
                    }
                }
                listener.onImeKeyEvent(event.getKeyCode(), down);
                return true;
            }
            return super.sendKeyEvent(event);
        }
    }
}
