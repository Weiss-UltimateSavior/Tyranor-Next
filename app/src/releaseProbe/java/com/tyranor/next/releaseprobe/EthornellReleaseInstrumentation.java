package com.tyranor.next.releaseprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Test APK only; framework + Java APIs, no AndroidX/JUnit/Kotlin and no production class copies. */
public final class EthornellReleaseInstrumentation extends Instrumentation {
    private static final String TAG = "EthornellReleaseProbe";
    private static final int NUM_TESTS = 3;
    private Bundle arguments;
    private Class<?> transport;
    private static native String probe(String absoluteLibrary);

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        arguments = args == null ? new Bundle() : new Bundle(args);
        start();
    }

    private interface Check { void run() throws Exception; }

    @Override public void onStart() {
        ScheduledExecutorService supervisor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread worker = new Thread(r, "release-probe-deadline"); worker.setDaemon(true); return worker;
        });
        ScheduledFuture<?> deadline = supervisor.schedule(() -> {
            Log.e(TAG, "FAIL release probe exceeded 60s; kill only instrumented pid=" + Process.myPid());
            Process.killProcess(Process.myPid());
        }, 60, TimeUnit.SECONDS);
        int failures = 0;
        StringBuilder details = new StringBuilder();
        try {
            emit(0, "NUMTEST=" + NUM_TESTS + " scope=release-native-load-create-only; no GPU/game/save acceptance", null, 0);
            failures += runCheck(1, "releaseTargetIdentity", this::releaseTargetIdentity, details);
            failures += runCheck(2, "rtldNowAbiBuildInfo", this::rtldNowAbiBuildInfo, details);
            failures += runCheck(3, "productionJniCreatePollDestroy", this::productionJniCreatePollDestroy, details);
        } finally {
            deadline.cancel(false);
            supervisor.shutdownNow();
        }
        Bundle result = new Bundle();
        result.putInt("numtests", NUM_TESTS);
        result.putInt("failures", failures);
        result.putBoolean("passed", failures == 0);
        result.putString("scope", "release-native-load-create-only");
        result.putString("stream", "\nNUMTEST=" + NUM_TESTS + " FAILURES=" + failures
                + "\n" + (failures == 0 ? "PASS" : "FAIL") + " release native probe\n" + details);
        finish(failures == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private int runCheck(int index, String name, Check check, StringBuilder details) {
        emit(1, "START " + name, name, index);
        try {
            check.run();
            emit(0, "PASS " + name, name, index);
            return 0;
        } catch (Exception | Error error) {
            String stack = Log.getStackTraceString(error);
            details.append(name).append(": ").append(stack).append('\n');
            Bundle status = status(name, index);
            status.putString("stack", stack);
            status.putString("stream", "\nFAIL " + name + "\n" + stack);
            sendStatus(-2, status);
            Log.e(TAG, "FAIL " + name, error);
            return 1;
        }
    }

    private Bundle status(String name, int index) {
        Bundle status = new Bundle();
        status.putString("id", TAG);
        status.putString("class", getClass().getName());
        if (name != null) status.putString("test", name);
        status.putInt("current", index); status.putInt("numtests", NUM_TESTS);
        return status;
    }

    private void emit(int code, String text, String name, int index) {
        Bundle status = status(name, index); status.putString("stream", "\n" + text + "\n");
        sendStatus(code, status); Log.i(TAG, text);
    }

    private void releaseTargetIdentity() {
        Context app = getTargetContext();
        require((app.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0,
                "Refuse debuggable target: install the matching R8 release APK");
        require(app.getApplicationInfo().uid == Process.myUid(), "Target UID mismatch");
        String expected = arguments.getString("expected_package", "com.tyranor.next.ethornelltest");
        require(expected.equals(app.getPackageName()), "Unexpected target package; never overwrite the user's production package");
        Log.i(TAG, "TARGET package=" + app.getPackageName() + " debuggable=false page_size="
                + Os.sysconf(OsConstants._SC_PAGESIZE));
        // R8/shrinking is a build/artifact check, not inferred solely from FLAG_DEBUGGABLE.
    }

    private void rtldNowAbiBuildInfo() {
        releaseTargetIdentity();
        System.loadLibrary("ethornell_test_probe");
        String report = probe(new File(getTargetContext().getApplicationInfo().nativeLibraryDir, "libethornell.so").getPath());
        require(report != null && report.startsWith("OK RTLD_NOW") && report.contains("build_info=ethornell"), report);
        emit(0, "LOAD " + report, "rtldNowAbiBuildInfo", 2);
    }

    private Object call(String name, Class<?>[] types, Object... args) throws Exception {
        if (transport == null) transport = getTargetContext().getClassLoader().loadClass("com.core.ethornell.NativeEthornell");
        require(transport.getClassLoader() == getTargetContext().getClassLoader(), "Transport must come from target APK, not a test shadow");
        Method method = transport.getMethod(name, types);
        try { return method.invoke(null, args); }
        catch (InvocationTargetException wrapped) {
            Throwable error = wrapped.getCause();
            if (error instanceof Error) throw (Error) error;
            if (error instanceof Exception) throw (Exception) error;
            throw new AssertionError(error);
        }
    }

    private void productionJniCreatePollDestroy() throws Exception {
        releaseTargetIdentity();
        String encoding = arguments.getString("encoding", "sjis");
        require(encoding.equals("sjis") || encoding.equals("gbk"), "encoding must be sjis or gbk");
        File base = new File(getTargetContext().getFilesDir().getCanonicalFile(), "ethornell-release-probe-" + UUID.randomUUID());
        File resource = new File(base, "resource-\uD83D\uDE80"), user = new File(base, "user-\uD834\uDD1E");
        require(resource.mkdirs() && user.mkdir(), "Fixture directory creation failed");
        require(!resource.toPath().startsWith(user.toPath()) && !user.toPath().startsWith(resource.toPath()), "Overlapping roots");
        // Original PackFile: 16-byte index header + one 32-byte entry + 16-byte BP + ret.
        ByteBuffer archive = ByteBuffer.allocate(65).order(ByteOrder.LITTLE_ENDIAN);
        archive.put("PackFile    ".getBytes(StandardCharsets.US_ASCII)).putInt(1);
        byte[] entry = new byte[16]; System.arraycopy("ipl._bp".getBytes(StandardCharsets.US_ASCII), 0, entry, 0, 7);
        archive.put(entry).putInt(0).putInt(17).putInt(0).putInt(0);
        archive.putInt(16).putInt(1).putInt(0).putInt(0).put((byte) 0x17);
        File input = new File(resource, "system.arc"); Files.write(input.toPath(), archive.array());
        call("load", new Class<?>[0]);
        long handle = ((Long) call("create", new Class<?>[]{Context.class, String.class, String.class, String.class, int.class},
                getTargetContext().getApplicationContext(), resource.getPath(), user.getPath(), "", encoding.equals("gbk") ? 1 : 0));
        require(handle != 0, "Native create returned zero");
        int events = 0;
        try {
            long end = SystemClock.elapsedRealtime() + 1000;
            while (SystemClock.elapsedRealtime() < end && events < 64) {
                Object event = call("pollEvent", new Class<?>[]{long.class}, handle);
                if (event == null) break;
                Class<?> type = event.getClass();
                int kind = type.getField("kind").getInt(event), code = type.getField("code").getInt(event);
                require(kind >= 1 && kind <= 4 && !(kind == 1 && code < 0), "Fatal/invalid event kind=" + kind + " code=" + code);
                Log.i(TAG, "EVENT kind=" + kind + " code=" + code + " text=" + type.getField("text").get(event));
                events++;
            }
            require(events > 0 && events < 64, "Expected bounded bootstrap diagnostic event");
            call("setPaused", new Class<?>[]{long.class, int.class}, handle, 1);
        } finally {
            call("destroy", new Class<?>[]{long.class}, handle); // Owner thread; no Surface to release.
        }
        require(java.util.Arrays.equals(archive.array(), Files.readAllBytes(input.toPath())), "Resource archive changed");
        boolean rejected = false;
        try { call("pollEvent", new Class<?>[]{long.class}, handle); }
        catch (IllegalStateException expected) { rejected = true; }
        require(rejected, "Destroyed token accepted");
        Log.i(TAG, "CREATE_POLL_DESTROY events=" + events + " nonBMP=true data retained=" + base
                + " no Surface/step: NOT VM execution/GPU evidence");
    }

    private static void require(boolean condition, String text) {
        if (!condition) throw new AssertionError(text);
    }
}
