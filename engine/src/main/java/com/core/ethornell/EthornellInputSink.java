package com.core.ethornell;

import android.view.KeyEvent;
import com.core.input.CanonicalKeys;
import com.core.input.InputSink;
import java.util.HashMap;
import java.util.Map;

/** Shared virtual pad, gamepad and physical keyboard all reach the same FIFO. */
public final class EthornellInputSink implements InputSink {
    private final EthornellSession session;
    private float x, y;
    private final Map<Integer, Integer> held = new HashMap<>();
    public EthornellInputSink(EthornellSession session) { this.session = session; }
    public void position(float x, float y) {
        this.x = x; this.y = y;
        session.input(new EthornellSession.Input(1, 0, x, y, 0));
    }
    public void wheel(float delta) {
        if (Float.isFinite(delta) && delta != 0) session.input(new EthornellSession.Input(6, 0, x, y, delta));
    }
    @Override public void send(int key, boolean down) {
        int button = key == CanonicalKeys.MOUSE_LEFT ? 1 : key == CanonicalKeys.MOUSE_RIGHT ? 2
                : key == CanonicalKeys.MOUSE_MIDDLE ? 4 : 0;
        if (button != 0) {
            if (transition(256 + button, down)) session.input(new EthornellSession.Input(down ? 2 : 3, button, x, y, 0));
            return;
        }
        if (key == CanonicalKeys.SCROLL_UP || key == CanonicalKeys.SCROLL_DOWN) {
            if (down) wheel(key == CanonicalKeys.SCROLL_UP ? 1 : -1);
            return;
        }
        int vk = descriptor(key);
        if (vk != 0 && transition(vk, down)) session.input(new EthornellSession.Input(down ? 4 : 5, vk, x, y, 0));
    }
    private boolean transition(int descriptor, boolean down) {
        int count = held.getOrDefault(descriptor, 0);
        if (down) { held.put(descriptor, count + 1); return count == 0; }
        if (count == 0) return false;
        if (count == 1) held.remove(descriptor); else held.put(descriptor, count - 1);
        return count == 1;
    }
    @Override public void releaseAll() { held.clear(); session.input(EthornellSession.Input.cancel()); }
    @Override public boolean performAction(int action) { return false; }
    /** Android keyCode is never passed directly to Rust's Windows descriptor interface. */
    public static int descriptor(int key) {
        if (key >= KeyEvent.KEYCODE_A && key <= KeyEvent.KEYCODE_Z) return 'A' + key - KeyEvent.KEYCODE_A;
        if (key >= KeyEvent.KEYCODE_0 && key <= KeyEvent.KEYCODE_9) return '0' + key - KeyEvent.KEYCODE_0;
        if (key >= KeyEvent.KEYCODE_F1 && key <= KeyEvent.KEYCODE_F12) return 0x70 + key - KeyEvent.KEYCODE_F1;
        switch (key) {
            case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER: return 0x0D;
            case KeyEvent.KEYCODE_ESCAPE: return 0x1B;
            case KeyEvent.KEYCODE_SPACE: return 0x20;
            case KeyEvent.KEYCODE_CTRL_LEFT: case KeyEvent.KEYCODE_CTRL_RIGHT: return 0x11;
            case KeyEvent.KEYCODE_SHIFT_LEFT: case KeyEvent.KEYCODE_SHIFT_RIGHT: return 0x10;
            case KeyEvent.KEYCODE_ALT_LEFT: case KeyEvent.KEYCODE_ALT_RIGHT: return 0x12;
            case KeyEvent.KEYCODE_DPAD_LEFT: return 0x25;
            case KeyEvent.KEYCODE_DPAD_UP: return 0x26;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 0x27;
            case KeyEvent.KEYCODE_DPAD_DOWN: return 0x28;
            case KeyEvent.KEYCODE_PAGE_UP: return 0x21;
            case KeyEvent.KEYCODE_PAGE_DOWN: return 0x22;
            case KeyEvent.KEYCODE_TAB: return 0x09;
            case KeyEvent.KEYCODE_DEL: return 0x08;
            case KeyEvent.KEYCODE_FORWARD_DEL: return 0x2E;
            case KeyEvent.KEYCODE_MOVE_HOME: return 0x24;
            case KeyEvent.KEYCODE_MOVE_END: return 0x23;
            default: return 0;
        }
    }
}
