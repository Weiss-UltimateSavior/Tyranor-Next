package com.core.ethornell;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class EthornellInputTraceTest {
    private static final String ROUTE = "input_route object=1 group=4294967295 item=2 root=1 flags=3 current=4 pending=4294967295 pointer=1 hit=0 press=1 up=0 event6=0";
    private static final String GATE = "input_gate pc=4294967295 frame=1 event=268435462 payload=2 parameter=3 group=4 item=7 running=1 gate=0";
    @Test public void acceptsOnlyExactNegotiatedNumericalProtocolAndBudget() {
        List<String> logs = new ArrayList<>(); EthornellInputTrace trace = new EthornellInputTrace(logs::add);
        for (int i = 0; i < 100; i++) trace.observe(new EthornellSession.Event(1, 0, 1001, ROUTE));
        assertEquals(64, logs.size()); assertEquals(ROUTE, logs.get(0));
    }
    @Test public void rejectsOtherEventsAndTextInjectionWithoutSpendingBudget() {
        List<String> logs = new ArrayList<>(); EthornellInputTrace trace = new EthornellInputTrace(logs::add);
        trace.observe(new EthornellSession.Event(2, 0, 1001, ROUTE));
        trace.observe(new EthornellSession.Event(1, 1, 1001, ROUTE));
        trace.observe(new EthornellSession.Event(1, 0, 1002, ROUTE));
        for (String invalid : new String[]{ROUTE + "\nforged", ROUTE + " /private/game", ROUTE.replace("object=1", "object=-1"),
                ROUTE.replace("object=1", "object=4294967296"), ROUTE.replace("pointer=1", "pointer=2"),
                ROUTE.replace("item=2", "item=对白"), "x".repeat(385)})
            trace.observe(new EthornellSession.Event(1, 0, 1001, invalid));
        assertTrue(logs.isEmpty());
        trace.observe(new EthornellSession.Event(1, 0, 1001, ROUTE)); assertEquals(1, logs.size());
    }
    @Test public void guestStateGateUsesExactU32FieldsAndSharedBudgetWithRoute() {
        List<String> logs = new ArrayList<>(); EthornellInputTrace trace = new EthornellInputTrace(logs::add);
        for (String invalid : new String[]{GATE + " /private/game", GATE + "\nforged", GATE.replace("frame=1", "frame=-1"),
                GATE.replace("gate=0", "gate=4294967296"), GATE.replace("group=4", "group=dialogue")})
            trace.observe(new EthornellSession.Event(1, 0, 1002, invalid));
        trace.observe(new EthornellSession.Event(1, 1, 1002, GATE));
        trace.observe(new EthornellSession.Event(1, 0, 1001, GATE));
        trace.observe(new EthornellSession.Event(1, 0, 1002, ROUTE));
        assertTrue(logs.isEmpty());
        for (int i = 0; i < 40; i++) {
            trace.observe(new EthornellSession.Event(1, 0, 1001, ROUTE));
            trace.observe(new EthornellSession.Event(1, 0, 1002, GATE));
        }
        assertEquals(64, logs.size()); assertEquals(GATE, logs.get(1));
    }
}
