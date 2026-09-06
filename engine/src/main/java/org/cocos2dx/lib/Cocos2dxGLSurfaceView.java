package org.cocos2dx.lib;

import android.app.Activity;
import android.content.Context;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.inputmethod.InputMethodManager;
import java.lang.ref.WeakReference;

public class Cocos2dxGLSurfaceView extends GLSurfaceView {
    private static final int HANDLER_OPEN_IME_KEYBOARD = 2;
    private static final int HANDLER_CLOSE_IME_KEYBOARD = 3;
    private static final String TAG = "Cocos2dxGLSurfaceView";

    private static Cocos2dxGLSurfaceView mCocos2dxGLSurfaceView;
    private static Cocos2dxTextInputWraper sCocos2dxTextInputWraper;
    private static Handler sHandler;

    private Cocos2dxEditBox mCocos2dxEditText;
    private Cocos2dxRenderer mCocos2dxRenderer;
    private boolean mSoftKeyboardShown;

    private static final class ImeHandler extends Handler {
        private final WeakReference<Cocos2dxGLSurfaceView> viewRef;

        ImeHandler(Cocos2dxGLSurfaceView view) {
            super(Looper.getMainLooper());
            viewRef = new WeakReference<>(view);
        }

        @Override
        public void handleMessage(Message message) {
            Cocos2dxGLSurfaceView view = viewRef.get();
            if (view == null) return;
            Cocos2dxEditBox editText = view.mCocos2dxEditText;
            if (message.what == HANDLER_OPEN_IME_KEYBOARD) {
                if (editText == null || !editText.requestFocus()) return;
                editText.removeTextChangedListener(sCocos2dxTextInputWraper);
                editText.setText("");
                String text = (String) message.obj;
                editText.append(text);
                sCocos2dxTextInputWraper.setOriginText(text);
                editText.addTextChangedListener(sCocos2dxTextInputWraper);
                ((InputMethodManager) view.getContext().getSystemService(Context.INPUT_METHOD_SERVICE))
                        .showSoftInput(editText, 0);
                Log.d(TAG, "showSoftInput");
            } else if (message.what == HANDLER_CLOSE_IME_KEYBOARD && editText != null) {
                editText.removeTextChangedListener(sCocos2dxTextInputWraper);
                ((InputMethodManager) view.getContext().getSystemService(Context.INPUT_METHOD_SERVICE))
                        .hideSoftInputFromWindow(editText.getWindowToken(), 0);
                view.requestFocus();
                Log.d(TAG, "hideSoftInput");
            }
        }
    }

    public Cocos2dxGLSurfaceView(Context context) {
        super(context);
        this.mSoftKeyboardShown = false;
        initView();
    }

    public Cocos2dxGLSurfaceView(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
        this.mSoftKeyboardShown = false;
        initView();
    }

    public static Cocos2dxGLSurfaceView getInstance() {
        return mCocos2dxGLSurfaceView;
    }

    public static void closeIMEKeyboard() {
        Message message = new Message();
        message.what = HANDLER_CLOSE_IME_KEYBOARD;
        if (sHandler != null) sHandler.sendMessage(message);
    }

    public static void openIMEKeyboard() {
        Message message = new Message();
        message.what = HANDLER_OPEN_IME_KEYBOARD;
        message.obj = mCocos2dxGLSurfaceView != null ? mCocos2dxGLSurfaceView.getContentText() : "";
        if (sHandler != null) sHandler.sendMessage(message);
    }

    public static void queueAccelerometer(final float x, final float y, final float z, final long timestamp) {
        if (mCocos2dxGLSurfaceView == null) return;
        mCocos2dxGLSurfaceView.queueEvent(new Runnable() {
            @Override public void run() { Cocos2dxAccelerometer.onSensorChanged(x, y, z, timestamp); }
        });
    }

    private static void dumpMotionEvent(MotionEvent event) {
        StringBuilder sb = new StringBuilder("event ACTION_");
        int action = event.getAction();
        int actionCode = action & MotionEvent.ACTION_MASK;
        // ACTION_DOWN/UP/MOVE/CANCEL/OUTSIDE/POINTER_DOWN/POINTER_UP; 7=HOVER_MOVE, 8=SCROLL, 9=HOVER_ENTER
        String[] names = new String[]{"DOWN", "UP", "MOVE", "CANCEL", "OUTSIDE", "POINTER_DOWN", "POINTER_UP", "7?", "8?", "9?"};
        sb.append(actionCode < names.length ? names[actionCode] : actionCode);
        if (actionCode == MotionEvent.ACTION_POINTER_DOWN || actionCode == MotionEvent.ACTION_POINTER_UP) {
            sb.append("(pid ").append(action >> MotionEvent.ACTION_POINTER_INDEX_SHIFT).append(")");
        }
        sb.append("[");
        for (int i = 0; i < event.getPointerCount(); i++) {
            sb.append("#").append(i).append("(pid ").append(event.getPointerId(i)).append(")=")
                    .append((int) event.getX(i)).append(",").append((int) event.getY(i));
            if (i + 1 < event.getPointerCount()) sb.append(";");
        }
        sb.append("]");
        Log.d(TAG, sb.toString());
    }

    private String getContentText() {
        return this.mCocos2dxRenderer != null ? this.mCocos2dxRenderer.getContentText() : "";
    }

    public void initView() {
        // Anime4K 启用时需要 GLES3 上下文（CNN 中间值可为负，需 RGBA16F 渲染目标）。
        // ES3 上下文向后兼容引擎的 ES2 渲染路径；未启用时保持原样，零回归风险。
        setEGLContextClientVersion(com.core.gl.Anime4kRuntime.useGles3Context() ? 3 : 2);
        setFocusableInTouchMode(true);
        mCocos2dxGLSurfaceView = this;
        sCocos2dxTextInputWraper = new Cocos2dxTextInputWraper(this);
        sHandler = new ImeHandler(this);
    }

    public Cocos2dxEditBox getCocos2dxEditText() {
        return this.mCocos2dxEditText;
    }

    public void setCocos2dxEditText(Cocos2dxEditBox editText) {
        this.mCocos2dxEditText = editText;
        if (editText != null && sCocos2dxTextInputWraper != null) {
            editText.setOnEditorActionListener(sCocos2dxTextInputWraper);
        }
        requestFocus();
    }

    public void setCocos2dxRenderer(Cocos2dxRenderer renderer) {
        this.mCocos2dxRenderer = renderer;
        setRenderer(renderer);
    }

    public void setSoftKeyboardShown(boolean shown) {
        this.mSoftKeyboardShown = shown;
    }

    public boolean isSoftKeyboardShown() {
        return this.mSoftKeyboardShown;
    }

    public void insertText(final String text) {
        queueEvent(new Runnable() {
            @Override public void run() { if (mCocos2dxRenderer != null) mCocos2dxRenderer.handleInsertText(text); }
        });
    }

    public void deleteBackward() {
        queueEvent(new Runnable() {
            @Override public void run() { if (mCocos2dxRenderer != null) mCocos2dxRenderer.handleDeleteBackward(); }
        });
    }

    @Override
    public boolean onKeyDown(final int keyCode, KeyEvent keyEvent) {
        if (keyCode != KeyEvent.KEYCODE_BACK && keyCode != KeyEvent.KEYCODE_ENTER
                && keyCode != KeyEvent.KEYCODE_MENU && keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                    break;
                default:
                    return super.onKeyDown(keyCode, keyEvent);
            }
        }
        queueEvent(new Runnable() {
            @Override public void run() { if (mCocos2dxRenderer != null) mCocos2dxRenderer.handleKeyDown(keyCode); }
        });
        return true;
    }

    @Override
    public boolean onKeyUp(final int keyCode, KeyEvent keyEvent) {
        if (keyCode != KeyEvent.KEYCODE_BACK && keyCode != KeyEvent.KEYCODE_ENTER
                && keyCode != KeyEvent.KEYCODE_MENU && keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                    break;
                default:
                    return super.onKeyUp(keyCode, keyEvent);
            }
        }
        queueEvent(new Runnable() {
            @Override public void run() { if (mCocos2dxRenderer != null) mCocos2dxRenderer.handleKeyUp(keyCode); }
        });
        return true;
    }

    @Override
    public void onPause() {
        queueEvent(new Runnable() {
            @Override public void run() { if (mCocos2dxRenderer != null) mCocos2dxRenderer.handleOnPause(); }
        });
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
    }

    @Override
    public void onResume() {
        super.onResume();
        setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        queueEvent(new Runnable() {
            @Override public void run() { if (mCocos2dxRenderer != null) mCocos2dxRenderer.handleOnResume(); }
        });
    }

    @Override
    public void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        if (isInEditMode()) return;
        if (this.mCocos2dxRenderer != null) this.mCocos2dxRenderer.setScreenWidthAndHeight(width, height);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int pointerCount = event.getPointerCount();
        final int[] ids = new int[pointerCount];
        final float[] xs = new float[pointerCount];
        final float[] ys = new float[pointerCount];
        if (this.mSoftKeyboardShown) {
            try {
                ((InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE))
                        .hideSoftInputFromWindow(((Activity) getContext()).getCurrentFocus().getWindowToken(), 0);
            } catch (Throwable ignored) { }
            requestFocus();
            this.mSoftKeyboardShown = false;
        }
        for (int i = 0; i < pointerCount; i++) {
            ids[i] = event.getPointerId(i);
            xs[i] = event.getX(i);
            ys[i] = event.getY(i);
        }
        int action = event.getAction() & MotionEvent.ACTION_MASK;
        if (action == MotionEvent.ACTION_DOWN) {
            final int id = event.getPointerId(0);
            final float x = xs[0];
            final float y = ys[0];
            queueEvent(new Runnable() { @Override public void run() { if (mCocos2dxRenderer != null) mCocos2dxRenderer.handleActionDown(id, x, y); } });
        } else if (action == MotionEvent.ACTION_UP) {
            final int id = event.getPointerId(0);
            final float x = xs[0];
            final float y = ys[0];
            queueEvent(new Runnable() { @Override public void run() { if (mCocos2dxRenderer != null) mCocos2dxRenderer.handleActionUp(id, x, y); } });
            performClick();
        } else if (action == MotionEvent.ACTION_MOVE) {
            queueEvent(new Runnable() { @Override public void run() { if (mCocos2dxRenderer != null) mCocos2dxRenderer.handleActionMove(ids, xs, ys); } });
        } else if (action == MotionEvent.ACTION_CANCEL) {
            queueEvent(new Runnable() { @Override public void run() { if (mCocos2dxRenderer != null) mCocos2dxRenderer.handleActionCancel(ids, xs, ys); } });
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            int index = event.getAction() >> MotionEvent.ACTION_POINTER_INDEX_SHIFT;
            final int id = event.getPointerId(index);
            final float x = event.getX(index);
            final float y = event.getY(index);
            queueEvent(new Runnable() { @Override public void run() { if (mCocos2dxRenderer != null) mCocos2dxRenderer.handleActionDown(id, x, y); } });
        } else if (action == MotionEvent.ACTION_POINTER_UP) {
            int index = event.getAction() >> MotionEvent.ACTION_POINTER_INDEX_SHIFT;
            final int id = event.getPointerId(index);
            final float x = event.getX(index);
            final float y = event.getY(index);
            queueEvent(new Runnable() { @Override public void run() { if (mCocos2dxRenderer != null) mCocos2dxRenderer.handleActionUp(id, x, y); } });
        }
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mCocos2dxGLSurfaceView == this) {
            if (sHandler != null) sHandler.removeCallbacksAndMessages(null);
            sHandler = null;
            sCocos2dxTextInputWraper = null;
            mCocos2dxGLSurfaceView = null;
        }
        super.onDetachedFromWindow();
    }
}
