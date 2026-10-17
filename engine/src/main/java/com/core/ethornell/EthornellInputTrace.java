package com.core.ethornell;

import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Rust-owner agreed codes 1001/1002 only; a second shared host-side 64-event budget. */
final class EthornellInputTrace {
    static final String TAG = "EthornellInputTrace";
    private static final Pattern ROUTE = Pattern.compile("input_route object=([0-9]{1,10}) group=([0-9]{1,10}) item=([0-9]{1,10}) root=([0-9]{1,10}) flags=([0-9]{1,10}) current=([0-9]{1,10}) pending=([0-9]{1,10}) pointer=[01] hit=[01] press=[01] up=[01] event6=[01]");
    private static final Pattern GATE = Pattern.compile("input_gate pc=([0-9]{1,10}) frame=([0-9]{1,10}) event=([0-9]{1,10}) payload=([0-9]{1,10}) parameter=([0-9]{1,10}) group=([0-9]{1,10}) item=([0-9]{1,10}) running=([0-9]{1,10}) gate=([0-9]{1,10})");
    private final Consumer<String> log;
    private int delivered;
    EthornellInputTrace(Consumer<String> log) { this.log = log; }
    void observe(EthornellSession.Event event) {
        if (delivered >= 64 || event.kind != 1 || (event.code != 1001 && event.code != 1002) || event.requestId != 0
                || event.text == null || event.text.length() > 384) return;
        Matcher match = (event.code == 1001 ? ROUTE : GATE).matcher(event.text);
        if (!match.matches()) return;
        for (int i = 1; i <= match.groupCount(); i++) if (Long.parseLong(match.group(i)) > 4294967295L) return;
        delivered++; log.accept(event.text);
    }
}
