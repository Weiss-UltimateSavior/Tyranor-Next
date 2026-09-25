package com.core.games;

import android.content.Context;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

/**
 * 软键盘接收视图：不显示内容，仅承载 {@link InputConnection}，
 * 把 IME 提交文本转给 framebuffer 引擎（{@code game_fb_text}）。
 *
 * <p>与 Siglus 宿主同构：软件渲染引擎没有「是否需要输入法」的查询 ABI，
 * 一期由宿主按钮手动唤起/收起。
 */
final class FramebufferTextInputView extends View {

    interface Listener {
        void onCommitText(String text);

        void onBackspace();

        void onEnter();
    }

    private final Listener listener;

    FramebufferTextInputView(Context context, Listener listener) {
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
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE
                | EditorInfo.IME_FLAG_NO_FULLSCREEN
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI;
        return new Connection(this, true);
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
        public boolean deleteSurroundingText(int beforeLength, int afterLength) {
            if (beforeLength == 1 && afterLength == 0 && listener != null) {
                listener.onBackspace();
                return true;
            }
            return super.deleteSurroundingText(beforeLength, afterLength);
        }

        @Override
        public boolean sendKeyEvent(KeyEvent event) {
            if (listener != null) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_DOWN) {
                    listener.onEnter();
                    return true;
                }
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.isPrintingKey()) {
                    int unicode = event.getUnicodeChar();
                    if (unicode != 0) {
                        listener.onCommitText(String.valueOf((char) unicode));
                        return true;
                    }
                }
            }
            return super.sendKeyEvent(event);
        }
    }
}
