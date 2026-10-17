package com.core.ethornell;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Single-owner runtime. Contains no Android dependencies, so ordering is JVM-testable. */
public final class EthornellSession {
    public static final int PAUSE_ACTIVITY = 1, PAUSE_FOCUS = 2, PAUSE_AUDIO = 4, PAUSE_DIALOG = 8;
    public enum State { NEW, INITIALIZING, READY_NO_SURFACE, RUNNING, SUSPENDED, CLOSING, CLOSED, FAILED }
    public interface Backend {
        long create() throws Exception;
        void attach(long handle, Object surface, int width, int height, long generation);
        void detach(long handle, long generation);
        void pause(long handle, int reasons);
        void input(long handle, Input input);
        void step(long handle, long timeNs);
        Event poll(long handle);
        void respond(long handle, long requestId, int result);
        void requestClose(long handle);
        void destroy(long handle);
    }
    public interface Listener {
        void event(long session, Event event);
        void failed(long session);
        default void failedDetail(long session, String safeDetail) {}
        default void forcedExit(long session, String safeReason) {}
        void closed(long session);
    }
    public static final class Input {
        public static final int MOVE = 1, DOWN = 2, UP = 3, KEY_DOWN = 4, KEY_UP = 5, WHEEL = 6, CANCEL = 7;
        public final int kind, code;
        public final float x, y, value;
        public Input(int kind, int code, float x, float y, float value) {
            this.kind = kind; this.code = code; this.x = x; this.y = y; this.value = value;
        }
        public static Input cancel() { return new Input(CANCEL, 0, 0, 0, 0); }
    }
    public static final class Event {
        public final int kind, code;
        public final long requestId;
        public final String text;
        public Event(int kind, long requestId, int code, String text) {
            this.kind = kind; this.requestId = requestId; this.code = code; this.text = text;
        }
    }
    /** No catch-up passes; retain real monotonic time and let Rust normalize >500ms. */
    public static final class Clock {
        private long last;
        public boolean due(long now) {
            if (now <= 0 || (last != 0 && now - last < 16_000_000L)) return false;
            last = now;
            return true;
        }
        public void reset() { last = 0; }
    }
    private interface Command { void run() throws Exception; }
    private final class InputCommand implements Command {
        Input input;
        InputCommand(Input input) { this.input = input; }
        @Override public void run() {
            if (input.kind == Input.CANCEL || input.kind == Input.UP || input.kind == Input.KEY_UP
                    || (attached && reasons == 0 && pending.isEmpty())) backend.input(handle, input);
        }
    }
    private final Object lock = new Object();
    private final ArrayDeque<Command> commands = new ArrayDeque<>();
    private final Set<Long> pending = new HashSet<>(); // worker only
    private final Set<Long> seenRequests = new HashSet<>(); // bounded replay protection
    private final Set<CountDownLatch> detachBarriers = new HashSet<>(); // under lock
    private final Backend backend;
    private final Listener listener;
    private final Runnable fatalProcess;
    private final ScheduledExecutorService supervisor;
    private final Thread worker;
    private final Clock clock = new Clock();
    private final long token;
    private volatile State state = State.NEW;
    private volatile boolean closing;
    private volatile long handle;
    private long generation, latestTime;
    private long acceptedGeneration, highestGeneration;
    private int reasons = PAUSE_ACTIVITY | PAUSE_FOCUS | PAUSE_AUDIO;
    private boolean attached, framePending;
    private volatile String operation = "idle";
    // Bootstrap/decoding budget is a bring-up fault threshold, not a performance claim.
    private static final long CALL_FAULT_MS = 15_000;
    private static final long DETACH_FAULT_MS = 1500;
    private final long callFaultMs, detachFaultMs;

    public EthornellSession(long token, Backend backend, Listener listener, Runnable fatalProcess) {
        this(token, backend, listener, fatalProcess, CALL_FAULT_MS, DETACH_FAULT_MS);
    }
    EthornellSession(long token, Backend backend, Listener listener, Runnable fatalProcess,
                     long callFaultMs, long detachFaultMs) {
        this.token = token; this.backend = traced(backend); this.listener = listener; this.fatalProcess = fatalProcess;
        this.callFaultMs = callFaultMs; this.detachFaultMs = detachFaultMs;
        supervisor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Ethornell-supervisor"); t.setDaemon(true); return t;
        });
        worker = new Thread(this::loop, "Ethornell-engine");
        commands.add(() -> {
            state = State.INITIALIZING;
            handle = this.backend.create();
            if (handle == 0) throw new IllegalStateException("create");
            this.backend.pause(handle, reasons);
            updateState();
            poll();
        });
        worker.start();
    }
    public State state() { return state; }
    public long token() { return token; }

    private void enqueue(Command command, boolean priority) {
        synchronized (lock) {
            if (closing) return;
            if (commands.size() >= 4096) {
                // Never silently discard key/button edges. Fail the isolated session instead.
                supervisor.execute(() -> forceExit("queue_overflow", 0));
                return;
            }
            if (priority) commands.addFirst(command); else commands.addLast(command);
            lock.notifyAll();
        }
    }
    public void frame(long timeNs, long surfaceGeneration) {
        synchronized (lock) {
            if (closing || surfaceGeneration == 0 || surfaceGeneration != acceptedGeneration) return;
            latestTime = timeNs; // at most one outstanding tick, even while native is blocked
            framePending = true;
            lock.notifyAll();
        }
    }
    public void attach(Object surface, int width, int height, long newGeneration) {
        if (width <= 0 || height <= 0 || newGeneration <= 0) return;
        synchronized (lock) {
            if (newGeneration <= highestGeneration || closing) return;
            highestGeneration = newGeneration; acceptedGeneration = newGeneration; framePending = false;
        }
        enqueue(() -> {
            synchronized (lock) { if (newGeneration != acceptedGeneration) return; }
            backend.attach(handle, surface, width, height, newGeneration);
            generation = newGeneration; attached = true; clock.reset(); updateState();
        }, false);
    }
    /** Must not return without a real native ACK. Supervisor is independent of the worker. */
    public void detachAndWait(long oldGeneration) {
        CountDownLatch done = new CountDownLatch(1);
        synchronized (lock) {
            if (acceptedGeneration == oldGeneration) acceptedGeneration = 0;
            framePending = false;
            if (state == State.CLOSED) return;
            detachBarriers.add(done);
            // Even closing sessions must accept this barrier, behind destroy if already queued.
            Command detach = () -> {
                if (handle != 0) {
                    backend.input(handle, Input.cancel());
                    backend.detach(handle, oldGeneration);
                }
                if (generation == oldGeneration) { attached = false; clock.reset(); updateState(); }
                synchronized (lock) { detachBarriers.remove(done); }
                done.countDown();
            };
            // Never overtake bootstrap before it has acquired the owner handle.
            if (handle == 0) commands.addLast(detach); else commands.addFirst(detach);
            lock.notifyAll();
        }
        ScheduledFuture<?> watchdog = null;
        try { watchdog = supervisor.schedule(() -> forceExit("detach_timeout", detachFaultMs), detachFaultMs, TimeUnit.MILLISECONDS); }
        catch (java.util.concurrent.RejectedExecutionException ended) {
            // Shutdown is only after destroy ACK; its cleanup also releases every barrier.
            if (state != State.CLOSED) forceExit("supervisor_unavailable", 0);
        }
        boolean interrupted = false;
        for (;;) {
            try { done.await(); break; }
            catch (InterruptedException e) { interrupted = true; }
        }
        if (watchdog != null) watchdog.cancel(false);
        if (interrupted) Thread.currentThread().interrupt();
    }
    public void pause(int reason, boolean enabled) {
        enqueue(() -> {
            reasons = enabled ? reasons | reason : reasons & ~reason;
            if (enabled) backend.input(handle, Input.cancel());
            backend.pause(handle, reasons); clock.reset(); updateState(); poll();
        }, false);
    }
    public void input(Input input) {
        if (!Float.isFinite(input.x) || !Float.isFinite(input.y) || !Float.isFinite(input.value)) return;
        synchronized (lock) {
            // Only adjacent moves may be coalesced. A DOWN/UP between moves is never crossed.
            Command tail = commands.peekLast();
            if (!closing && input.kind == Input.MOVE && tail instanceof InputCommand
                    && ((InputCommand) tail).input.kind == Input.MOVE) {
                ((InputCommand) tail).input = input; return;
            }
            enqueue(new InputCommand(input), false);
        }
    }
    public void respond(long sessionToken, long requestId, int result) {
        if (sessionToken != token) return;
        enqueue(() -> {
            if (!pending.remove(requestId)) return;
            backend.respond(handle, requestId, result); clock.reset(); poll();
        }, false);
    }
    public void requestClose() { enqueue(() -> { backend.requestClose(handle); poll(); }, false); }
    public void close() {
        synchronized (lock) {
            if (closing) return;
            closing = true; framePending = false; acceptedGeneration = 0;
            commands.clear();
            commands.add(() -> { state = State.CLOSING; cleanup(); });
            lock.notifyAll();
        }
    }
    private void updateState() {
        if (closing || state == State.FAILED) return;
        state = !attached ? State.READY_NO_SURFACE : reasons == 0 && pending.isEmpty() ? State.RUNNING : State.SUSPENDED;
    }
    private void poll() {
        for (int i = 0; i < 64; i++) {
            Event event = backend.poll(handle);
            if (event == null) break;
            if (event.kind == 2) {
                if (event.requestId == 0 || seenRequests.contains(event.requestId)) continue;
                if (pending.size() >= 64) throw new IllegalStateException("host request budget");
                if (seenRequests.size() >= 65536) throw new IllegalStateException("request replay budget");
                seenRequests.add(event.requestId);
                pending.add(event.requestId);
            }
            listener.event(token, event);
            if (event.kind == 3) { close(); break; }
        }
        updateState();
    }
    private void cleanup() {
        pending.clear(); seenRequests.clear();
        if (handle != 0) {
            // destroy also ACKs every attached Surface before releasing native references.
            backend.destroy(handle); handle = 0;
        }
        attached = false;
        synchronized (lock) {
            state = State.CLOSED;
            for (CountDownLatch barrier : detachBarriers) barrier.countDown();
            detachBarriers.clear();
        }
        listener.closed(token);
    }
    private void loop() {
        for (;;) {
            Command command;
            synchronized (lock) {
                while (commands.isEmpty() && !framePending && state != State.CLOSED) {
                    try { lock.wait(); } catch (InterruptedException ignored) { /* close is explicit */ }
                }
                if (state == State.CLOSED) break;
                command = commands.pollFirst();
                if (command == null) {
                    long now = latestTime; framePending = false;
                    command = () -> {
                        synchronized (lock) { if (acceptedGeneration != generation) return; }
                        if (attached && reasons == 0 && pending.isEmpty() && clock.due(now)) backend.step(handle, now);
                        poll();
                    };
                }
            }
            ScheduledFuture<?> watchdog = supervisor.schedule(() -> forceExit("command_timeout", callFaultMs), callFaultMs, TimeUnit.MILLISECONDS);
            try { command.run(); }
            catch (Exception | LinkageError e) {
                state = State.FAILED;
                listener.failedDetail(token, "operation=" + operation + " pause_mask=" + reasons
                        + " attached=" + attached + " " + EthornellFailureDetail.safe(e));
                listener.failed(token);
                synchronized (lock) { closing = true; framePending = false; commands.clear(); }
                try { cleanup(); } catch (Exception | LinkageError cleanupFailure) {
                    listener.failedDetail(token, "operation=" + operation + " " + EthornellFailureDetail.safe(cleanupFailure));
                    forceExit("cleanup_failure", 0);
                }
            } finally { watchdog.cancel(false); }
        }
        supervisor.shutdown();
    }
    private void forceExit(String reason, long budgetMs) {
        try { listener.forcedExit(token, "reason=" + reason + " operation=" + operation + " budget_ms=" + budgetMs); }
        finally { fatalProcess.run(); }
    }
    /** Records the actual backend boundary, including failures/poll/pause rather than assuming step. */
    private Backend traced(Backend target) {
        return new Backend() {
            @Override public long create() throws Exception { operation = "create"; return target.create(); }
            @Override public void attach(long h, Object s, int w, int height, long g) { operation = "attach"; target.attach(h, s, w, height, g); }
            @Override public void detach(long h, long g) { operation = "detach"; target.detach(h, g); }
            @Override public void pause(long h, int r) { operation = "pause"; target.pause(h, r); }
            @Override public void input(long h, Input i) { operation = "input"; target.input(h, i); }
            @Override public void step(long h, long time) { operation = "step"; target.step(h, time); }
            @Override public Event poll(long h) { operation = "poll"; return target.poll(h); }
            @Override public void respond(long h, long id, int result) { operation = "respond"; target.respond(h, id, result); }
            @Override public void requestClose(long h) { operation = "request_close"; target.requestClose(h); }
            @Override public void destroy(long h) { operation = "destroy"; target.destroy(h); }
        };
    }
}
