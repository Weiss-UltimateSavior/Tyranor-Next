package com.core.ethornell;

import android.view.KeyEvent;
import com.core.input.CanonicalKeys;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class EthornellInputSinkTest {
    @Test public void canonicalKeysTranslateToWindowsDescriptorsNotAndroidValues() {
        assertEquals(13, EthornellInputSink.descriptor(KeyEvent.KEYCODE_ENTER));
        assertEquals(27, EthornellInputSink.descriptor(KeyEvent.KEYCODE_ESCAPE));
        assertEquals(17, EthornellInputSink.descriptor(KeyEvent.KEYCODE_CTRL_RIGHT));
        assertEquals(0x25, EthornellInputSink.descriptor(KeyEvent.KEYCODE_DPAD_LEFT));
        assertEquals('Z', EthornellInputSink.descriptor(KeyEvent.KEYCODE_Z));
        assertEquals(0, EthornellInputSink.descriptor(KeyEvent.KEYCODE_VOLUME_UP));
        assertEquals(0, EthornellInputSink.descriptor(KeyEvent.KEYCODE_BACK));
    }
    @Test public void sharedPhysicalAndVirtualSourcesRetainEdgesUntilFinalRelease() throws Exception {
        List<EthornellSession.Input> inputs = new ArrayList<>();
        CountDownLatch drained = new CountDownLatch(1), closed = new CountDownLatch(1);
        EthornellSession session = new EthornellSession(1, new EthornellSession.Backend() {
            @Override public long create() { return 1; }
            @Override public void attach(long h, Object s, int w, int height, long g) {}
            @Override public void detach(long h, long g) {}
            @Override public void pause(long h, int r) {}
            @Override public void input(long h, EthornellSession.Input input) { inputs.add(input); }
            @Override public void step(long h, long now) {}
            @Override public EthornellSession.Event poll(long h) { return null; }
            @Override public void respond(long h, long id, int answer) {}
            @Override public void requestClose(long h) { drained.countDown(); }
            @Override public void destroy(long h) { closed.countDown(); }
        }, new EthornellSession.Listener() {
            @Override public void event(long token, EthornellSession.Event event) {}
            @Override public void failed(long token) { fail(); }
            @Override public void closed(long token) {}
        }, () -> fail("fatal"));
        session.attach(new Object(), 800, 600, 1);
        session.pause(1, false); session.pause(2, false); session.pause(4, false);
        EthornellInputSink sink = new EthornellInputSink(session);
        sink.send(KeyEvent.KEYCODE_CTRL_LEFT, true); sink.send(KeyEvent.KEYCODE_CTRL_RIGHT, true);
        sink.send(KeyEvent.KEYCODE_CTRL_LEFT, false); sink.send(KeyEvent.KEYCODE_CTRL_RIGHT, false);
        sink.send(CanonicalKeys.MOUSE_RIGHT, true); sink.send(CanonicalKeys.MOUSE_RIGHT, false);
        sink.send(CanonicalKeys.SCROLL_UP, true); sink.send(CanonicalKeys.SCROLL_UP, false);
        sink.releaseAll(); session.requestClose();
        assertTrue(drained.await(3, TimeUnit.SECONDS));
        assertEquals(1, inputs.stream().filter(i -> i.kind == 4 && i.code == 17).count());
        assertEquals(1, inputs.stream().filter(i -> i.kind == 5 && i.code == 17).count());
        assertEquals(1, inputs.stream().filter(i -> i.kind == 2 && i.code == 2).count());
        assertEquals(1, inputs.stream().filter(i -> i.kind == 3 && i.code == 2).count());
        assertEquals(1, inputs.stream().filter(i -> i.kind == 6 && i.value == 1).count());
        assertTrue(inputs.stream().anyMatch(i -> i.kind == 7));
        session.close(); assertTrue(closed.await(3, TimeUnit.SECONDS));
    }
}
