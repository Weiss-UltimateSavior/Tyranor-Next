package com.core.ethornell;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.core.engine.EngineSessionRegistry;
import com.core.engine.EngineThemeColors;
import com.core.engine.EngineUiText;
import com.core.engine.LaunchContract;
import com.core.engine.R;
import com.core.input.CanonicalKeys;
import com.core.input.InputRemapController;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Fullscreen game host, not an application management page. VM/GPU/IO never run on UI. */
public final class EthornellActivity extends AppCompatActivity implements SurfaceHolder.Callback, Choreographer.FrameCallback {
    private static final AtomicLong TOKENS = new AtomicLong(1);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private EthornellSession session;
    private EthornellInputSink input;
    private InputRemapController remap;
    private SurfaceView surface;
    private FrameLayout root;
    private TextView loading;
    private Payload payload;
    private long generation, attachedGeneration;
    private boolean resumed, destroyed, frameScheduled, processVerified;
    private int primaryPointer = -1;
    private final Set<Integer> mouseButtons = new HashSet<>();
    private AudioManager audio;
    private AudioFocusRequest focusRequest;
    private AlertDialog dialog;
    private final ArrayDeque<EthornellSession.Event> requests = new ArrayDeque<>();
    private final Set<Long> uiPending = new HashSet<>();
    private Runnable pendingFocusAction;
    private final EthornellProcessExit processExit = new EthornellProcessExit();
    private String dispatchToken, dispatchIdentity, dispatchStart;
    private int dispatchPid;
    private boolean dispatchClaimed;
    private boolean noisyRegistered;
    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction()) || session == null) return;
            releaseInput(); session.pause(EthornellSession.PAUSE_AUDIO, true);
            if (audio != null && focusRequest != null) audio.abandonAudioFocusRequest(focusRequest);
        }
    };

    private static final class Payload {
        final String identity, root, user, font, nls;
        Payload(Intent intent, File filesDir) throws IOException {
            if (intent == null || !LaunchContract.LAUNCH_MODE_ETHORNELL.equals(intent.getStringExtra(LaunchContract.LAUNCH_MODE)))
                throw new IllegalArgumentException("mode");
            identity = required(intent, LaunchContract.ROOT_URI);
            root = required(intent, LaunchContract.PATH);
            user = required(intent, LaunchContract.ETHORNELL_USER_DATA_DIR);
            EthornellPaths.validateUserDataDir(filesDir, identity, user);
            String selected = intent.getStringExtra(LaunchContract.ETHORNELL_NLS);
            nls = selected == null ? "sjis" : selected;
            if (!nls.equals("sjis") && !nls.equals("gbk")) throw new IllegalArgumentException("encoding");
            String selectedFont = intent.getStringExtra(LaunchContract.ETHORNELL_FONT_PATH);
            font = selectedFont == null ? "" : selectedFont;
            if (font.length() > 8192 || font.indexOf('\0') >= 0) throw new IllegalArgumentException("font");
        }
        private static String required(Intent intent, String name) {
            String value = intent.getStringExtra(name);
            if (value == null || value.isEmpty() || value.length() > 8192 || value.indexOf('\0') >= 0)
                throw new IllegalArgumentException(name);
            return value;
        }
        boolean same(Payload other) {
            return identity.equals(other.identity) && root.equals(other.root) && user.equals(other.user)
                    && font.equals(other.font) && nls.equals(other.nls);
        }
    }
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        processVerified = isOwnEngineProcess();
        if (!processVerified) { finish(); return; }
        try {
            // Claim BEFORE user-data validation/creation or native initialization. Durable even on fast failure.
            Intent incoming = getIntent();
            dispatchToken = Payload.required(incoming, LaunchContract.ETHORNELL_DISPATCH_TOKEN);
            dispatchIdentity = Payload.required(incoming, LaunchContract.ROOT_URI);
            dispatchPid = Process.myPid(); dispatchStart = EthornellDispatchRegistry.processStart(dispatchPid);
            dispatchClaimed = EthornellDispatchRegistry.claim(getFilesDir(), dispatchToken, dispatchIdentity, dispatchPid, dispatchStart);
            if (!dispatchClaimed) {
                processExit.afterCleanup(processVerified, true, this::finish, this::terminateOwnProcess);
                return; // revoked/terminal/missing/old Recents dispatch never creates a runtime
            }
            payload = new Payload(incoming, getFilesDir());
        }
        catch (RuntimeException | IOException e) {
            toast(R.string.engine_ethornell_invalid_config);
            finishDispatch(); // If claim never happened, leave ISSUED for the parent's atomic revoke.
            processExit.afterCleanup(processVerified, true, this::finish, this::terminateOwnProcess);
            return;
        }
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        root = new FrameLayout(this); root.setBackgroundColor(Color.BLACK);
        surface = new SurfaceView(this); surface.setKeepScreenOn(true);
        surface.setFocusableInTouchMode(true);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1));
        loading = new TextView(this); loading.setText(text(R.string.engine_starting_game));
        loading.setTextColor(Color.WHITE); loading.setTextSize(14); loading.setGravity(android.view.Gravity.CENTER);
        root.addView(loading, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        long sessionToken = TOKENS.getAndIncrement();
        // Payload validation already bound this exact basename to SHA-256(ROOT_URI UTF-8).
        EthornellLifecycle lifecycle = new EthornellLifecycle(new File(payload.user).getName(), sessionToken,
                message -> android.util.Log.i(EthornellLifecycle.TAG, message));
        EthornellInputTrace inputTrace = new EthornellInputTrace(
                message -> android.util.Log.i(EthornellInputTrace.TAG, message));
        session = new EthornellSession(sessionToken, backend(lifecycle), new EthornellSession.Listener() {
            @Override public void event(long token, EthornellSession.Event event) {
                lifecycle.observe(event); // Worker observed the actual Rust event, before UI dispatch.
                inputTrace.observe(event); // Only exact bounded numeric codes 1001/1002, never arbitrary text.
                ui.post(() -> hostEvent(token, event));
            }
            @Override public void failed(long token) {
                lifecycle.failed();
                ui.post(() -> { if (alive(token)) toast(R.string.engine_ethornell_failed); });
            }
            @Override public void failedDetail(long token, String safeDetail) { lifecycle.failedDetail(safeDetail); }
            @Override public void forcedExit(long token, String safeReason) { lifecycle.forcedExit(safeReason); }
            @Override public void closed(long token) { ui.post(() -> {
                finishDispatch(); // Only reached after destroy ACK, or failed creation with no runtime.
                EngineSessionRegistry.clear(EthornellActivity.this, EngineSessionRegistry.HOST_ETHORNELL);
                // Listener.closed is emitted only after native destroy ACK (including failed init cleanup).
                // Android otherwise caches this process and launcher conservatively treats it as BUSY.
                lifecycle.cleanupExit();
                processExit.afterCleanup(processVerified, true,
                        () -> { dismissDialogs(); if (!isFinishing()) finish(); }, EthornellActivity.this::terminateOwnProcess);
            }); }
        }, this::terminateOwnProcess);
        input = new EthornellInputSink(session);
        remap = InputRemapController.Companion.install(this, root, payload.identity,
                EngineThemeColors.fromIntent(getIntent()), input, false);
        surface.setOnTouchListener((view, event) -> touch(event));
        surface.setOnGenericMotionListener((view, event) -> pointerMotion(event));
        surface.getHolder().addCallback(this);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(change -> {
                    if (session != null && !destroyed) session.pause(EthornellSession.PAUSE_AUDIO, change != AudioManager.AUDIOFOCUS_GAIN);
                }, ui).build();
        ContextCompat.registerReceiver(this, noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED);
        noisyRegistered = true;
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (remap != null && remap.handleBack()) return;
                showMenu();
            }
        });
        immersive();
    }
    private EthornellSession.Backend backend(EthornellLifecycle lifecycle) {
        final Context application = getApplicationContext();
        final Payload config = payload;
        return new EthornellSession.Backend() {
            @Override public long create() throws Exception {
                if (!new File(config.root).isAbsolute() || !new File(config.user).isAbsolute()
                        || (!config.font.isEmpty() && !new File(config.font).isAbsolute())) throw new IOException("absolute paths required");
                File resource = new File(config.root).getCanonicalFile();
                File user = EthornellPaths.validateUserDataDir(application.getFilesDir(), config.identity, config.user);
                if (!resource.isDirectory() || !resource.canRead() || config.root.startsWith("content:")) throw new IOException("resource");
                // Identity-bound private user root only; never make resource or external roots writable.
                File privateRoot = application.getFilesDir().getCanonicalFile();
                if (user.equals(resource) || within(resource, user) || within(user, resource)) throw new IOException("overlapping roots");
                if (!user.isDirectory() && !user.mkdirs()) throw new IOException("user directory");
                EthornellPaths.validateUserDataDir(application.getFilesDir(), config.identity, config.user);
                File probe = File.createTempFile(".ethornell-probe-", ".tmp", user);
                if (!probe.delete()) throw new IOException("user write");
                String font = "";
                if (!config.font.isEmpty()) {
                    File chosen = new File(config.font).getCanonicalFile();
                    File fontRoot = new File(privateRoot, "fonts");
                    if (!fontRoot.equals(fontRoot.getCanonicalFile()) || !within(chosen, fontRoot) || !chosen.isFile()
                            || !chosen.canRead() || chosen.length() == 0 || chosen.length() > 64L * 1024 * 1024) throw new IOException("font policy");
                    font = chosen.getPath();
                }
                NativeEthornell.load();
                long handle = NativeEthornell.create(application, resource.getPath(), user.getPath(), font, config.nls.equals("gbk") ? 1 : 0);
                if (handle != 0) EngineSessionRegistry.record(application, EngineSessionRegistry.HOST_ETHORNELL, config.identity);
                return handle;
            }
            @Override public void attach(long h, Object s, int w, int height, long g) { NativeEthornell.setSurface(h, (android.view.Surface) s, w, height, g); }
            @Override public void detach(long h, long g) { NativeEthornell.detachSurface(h, g); }
            @Override public void pause(long h, int reasons) { NativeEthornell.setPaused(h, reasons); }
            @Override public void input(long h, EthornellSession.Input i) { NativeEthornell.input(h, i.kind, i.code, i.x, i.y, i.value); }
            @Override public void step(long h, long now) { NativeEthornell.step(h, now); }
            @Override public EthornellSession.Event poll(long h) { return NativeEthornell.pollEvent(h); }
            @Override public void respond(long h, long id, int result) { NativeEthornell.completeRequest(h, id, result); }
            @Override public void requestClose(long h) { NativeEthornell.requestClose(h); }
            @Override public void destroy(long h) { lifecycle.destroy(() -> NativeEthornell.destroy(h)); }
        };
    }
    private static boolean within(File file, File directory) {
        return file.toPath().startsWith(directory.toPath()) && !file.equals(directory);
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        try {
            if (dispatchClaimed && !dispatchToken.equals(intent.getStringExtra(LaunchContract.ETHORNELL_DISPATCH_TOKEN)))
                throw new IllegalArgumentException("different dispatch");
            Payload candidate = new Payload(intent, getFilesDir());
            if (payload != null && payload.same(candidate) && !destroyed && session != null
                    && session.state() != EthornellSession.State.CLOSED && session.state() != EthornellSession.State.FAILED) {
                surface.requestFocus(); return;
            }
        } catch (RuntimeException | IOException ignored) { /* Reject invalid and different payloads alike. */ }
        toast(R.string.engine_another_game_running);
    }
    @Override protected void onResume() {
        super.onResume(); resumed = true;
        if (session == null) return;
        if (remap != null) remap.onResume();
        session.pause(EthornellSession.PAUSE_ACTIVITY, false);
        requestAudio(); scheduleFrame(); immersive();
    }
    private void requestAudio() {
        boolean granted = false;
        try { granted = audio != null && audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED; }
        catch (RuntimeException ignored) { /* Permission/device errors suspend, never pretend silence. */ }
        session.pause(EthornellSession.PAUSE_AUDIO, !granted);
    }
    @Override protected void onPause() {
        resumed = false; stopFrames();
        if (remap != null) remap.onPause();
        releaseInput();
        if (session != null) {
            session.pause(EthornellSession.PAUSE_ACTIVITY, true);
            session.pause(EthornellSession.PAUSE_AUDIO, true);
        }
        if (audio != null && focusRequest != null) audio.abandonAudioFocusRequest(focusRequest);
        super.onPause();
    }
    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (!focus) {
            if (remap != null) remap.onPause();
            releaseInput();
        }
        if (session != null) session.pause(EthornellSession.PAUSE_FOCUS, !focus);
        if (focus) {
            immersive();
            if (pendingFocusAction != null && resumed && !destroyed) {
                Runnable action = pendingFocusAction; pendingFocusAction = null; action.run();
            }
        }
    }
    @Override protected void onDestroy() {
        destroyed = true; stopFrames(); dismissDialogs();
        if (noisyRegistered) { unregisterReceiver(noisyReceiver); noisyRegistered = false; }
        if (remap != null) remap.onDestroy();
        releaseInput();
        if (session != null) session.close();
        // Never clear the registry until native destroy has actually completed.
        super.onDestroy();
    }
    @Override public void surfaceCreated(@NonNull SurfaceHolder holder) { /* actual physical dimensions arrive in changed */ }
    @Override public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        if (session == null || destroyed) return;
        if (attachedGeneration != 0) session.detachAndWait(attachedGeneration);
        attachedGeneration = 0;
        if (width > 0 && height > 0) {
            attachedGeneration = ++generation;
            session.attach(holder.getSurface(), width, height, attachedGeneration);
            // Touch-free right-click/backlog controls must also work before the first pointer event.
            input.position(width / 2f, height / 2f);
            scheduleFrame();
        }
    }
    @Override public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        stopFrames(); releaseInput();
        if (session != null && attachedGeneration != 0) session.detachAndWait(attachedGeneration);
        attachedGeneration = 0;
    }
    @Override public void doFrame(long timeNs) {
        frameScheduled = false;
        if (!resumed || destroyed || attachedGeneration == 0 || session == null) return;
        session.frame(timeNs, attachedGeneration);
        if (loading != null && session.state() == EthornellSession.State.RUNNING) {
            root.removeView(loading); loading = null;
        }
        scheduleFrame();
    }
    private void scheduleFrame() {
        if (resumed && !destroyed && attachedGeneration != 0 && !frameScheduled) {
            frameScheduled = true; Choreographer.getInstance().postFrameCallback(this);
        }
    }
    private void stopFrames() { Choreographer.getInstance().removeFrameCallback(this); frameScheduled = false; }
    private boolean touch(MotionEvent event) {
        if (input == null) return false;
        if (event.isFromSource(android.view.InputDevice.SOURCE_MOUSE)) return pointerMotion(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_CANCEL) { releaseInput(); return true; }
        if (action == MotionEvent.ACTION_DOWN && primaryPointer == -1) {
            primaryPointer = event.getPointerId(0); input.position(event.getX(), event.getY()); input.send(CanonicalKeys.MOUSE_LEFT, true);
        } else {
            int index = event.findPointerIndex(primaryPointer);
            if (index < 0) return true;
            input.position(event.getX(index), event.getY(index));
            if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP)
                    && event.getPointerId(event.getActionIndex()) == primaryPointer) {
                input.send(CanonicalKeys.MOUSE_LEFT, false); primaryPointer = -1;
            } else if (event.getX(index) < 0 || event.getY(index) < 0 || event.getX(index) >= surface.getWidth()
                    || event.getY(index) >= surface.getHeight()) { input.send(CanonicalKeys.MOUSE_LEFT, false); primaryPointer = -1; }
        }
        return true;
    }
    private boolean pointerMotion(MotionEvent event) {
        if (input == null || !event.isFromSource(android.view.InputDevice.SOURCE_MOUSE)) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) { releaseInput(); return true; }
        input.position(event.getX(), event.getY());
        if (event.getActionMasked() == MotionEvent.ACTION_SCROLL) input.wheel(event.getAxisValue(MotionEvent.AXIS_VSCROLL));
        int[] flags = {MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_SECONDARY, MotionEvent.BUTTON_TERTIARY};
        int[] keys = {CanonicalKeys.MOUSE_LEFT, CanonicalKeys.MOUSE_RIGHT, CanonicalKeys.MOUSE_MIDDLE};
        for (int i = 0; i < flags.length; i++) {
            boolean down = (event.getButtonState() & flags[i]) != 0;
            if (down && mouseButtons.add(keys[i])) input.send(keys[i], true);
            if (!down && mouseButtons.remove(keys[i])) input.send(keys[i], false);
        }
        return true;
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (remap != null && remap.dispatchKeyEvent(event)) return true;
        if (input != null && event.getKeyCode() != KeyEvent.KEYCODE_BACK && EthornellInputSink.descriptor(event.getKeyCode()) != 0) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) input.send(event.getKeyCode(), true);
            else if (event.getAction() == KeyEvent.ACTION_UP) input.send(event.getKeyCode(), false);
            return true;
        }
        return super.dispatchKeyEvent(event); // volume/system buttons belong to Android
    }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (remap != null && remap.dispatchGenericMotionEvent(event)) return true;
        return super.dispatchGenericMotionEvent(event);
    }
    private void releaseInput() {
        primaryPointer = -1; mouseButtons.clear();
        if (input != null) input.releaseAll();
    }
    private boolean alive(long token) { return !destroyed && !isFinishing() && session != null && session.token() == token; }
    private void hostEvent(long token, EthornellSession.Event event) {
        if (!alive(token)) return;
        if (event.kind == 2) {
            if (!uiPending.add(event.requestId)) return;
            requests.add(event); showNextRequest();
        } else if (event.kind == 3) { dismissDialogs(); }
        else if (event.kind == 4) setTitle(event.text);
        else if (event.kind == 1 && event.code < 0) toast(R.string.engine_ethornell_failed);
    }
    private void showNextRequest() {
        if (dialog != null || requests.isEmpty() || destroyed) return;
        EthornellSession.Event request = requests.remove();
        final long token = session.token();
        showDialog(text(R.string.engine_ethornell_host_request), request.text,
                new String[]{text(R.string.engine_yes), text(R.string.engine_no)}, new Runnable[]{
                    () -> answer(token, request.requestId, 1), () -> answer(token, request.requestId, 0)},
                () -> answer(token, request.requestId, 0));
    }
    private void answer(long token, long requestId, int answer) {
        if (uiPending.remove(requestId) && alive(token)) session.respond(token, requestId, answer);
    }
    private void showMenu() {
        if (session == null || destroyed || dialog != null || !uiPending.isEmpty()) return;
        showDialog(text(R.string.engine_prompt), "", new String[]{text(R.string.engine_ethornell_game_menu),
                        text(R.string.engine_ethornell_backlog), text(R.string.engine_exit_game), text(R.string.engine_cancel)},
                new Runnable[]{() -> pulse(CanonicalKeys.MOUSE_RIGHT), () -> pulse(CanonicalKeys.SCROLL_UP),
                        this::showExitConfirmation, () -> {}}, () -> {});
    }
    private void pulse(int key) {
        // Dialog window focus may return after dismiss. Never discard the menu click as paused input.
        Runnable action = () -> { input.send(key, true); input.send(key, false); };
        if (hasWindowFocus()) action.run(); else pendingFocusAction = action;
    }
    private void showExitConfirmation() {
        // A request is not permission to finish: mode 0 must let the game process WM_CLOSE.
        // Normal finish is exclusively in Listener.closed, after the Closed event and destroy ACK.
        showDialog(text(R.string.engine_exit_game), text(R.string.engine_exit_game_message),
                new String[]{text(R.string.engine_ok), text(R.string.engine_cancel)},
                new Runnable[]{() -> session.requestClose(), () -> {}}, () -> {});
    }
    private void showDialog(String title, String message, String[] labels, Runnable[] actions, Runnable cancel) {
        session.pause(EthornellSession.PAUSE_DIALOG, true); releaseInput();
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        content.setPadding(pad, pad, pad, pad);
        GradientDrawable background = new GradientDrawable(); background.setColor(Color.WHITE);
        background.setCornerRadius(8 * getResources().getDisplayMetrics().density); content.setBackground(background);
        TextView heading = dialogText(title, 15); heading.setTypeface(null, android.graphics.Typeface.BOLD); content.addView(heading);
        if (!message.isEmpty()) content.addView(dialogText(message, 14));
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(false); scroll.addView(content);
        AlertDialog shown = new AlertDialog.Builder(this).setView(scroll).create();
        dialog = shown;
        for (int i = 0; i < labels.length; i++) {
            TextView button = dialogText(labels[i], 14); button.setPadding(0, pad / 2, 0, pad / 2); button.setBackground(null);
            button.setSoundEffectsEnabled(false); button.setHapticFeedbackEnabled(false);
            final Runnable action = actions[i];
            button.setOnClickListener(v -> {
                if (dialog != shown) return;
                // Clear pause before a menu action, but defer request queue promotion until action completed.
                dialog = null; shown.setOnDismissListener(null); shown.dismiss();
                session.pause(EthornellSession.PAUSE_DIALOG, false);
                action.run(); if (dialog == null) showNextRequest();
            }); content.addView(button);
        }
        shown.setOnCancelListener(d -> cancel.run());
        shown.setOnDismissListener(d -> {
            if (dialog == shown) { dialog = null; session.pause(EthornellSession.PAUSE_DIALOG, false); showNextRequest(); }
        });
        shown.show();
        if (shown.getWindow() != null) shown.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
    }
    private TextView dialogText(String value, int sp) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(sp); view.setTextColor(Color.BLACK); return view;
    }
    private void dismissDialogs() {
        pendingFocusAction = null;
        uiPending.clear(); requests.clear();
        if (dialog != null) { AlertDialog old = dialog; dialog = null; old.setOnCancelListener(null); old.setOnDismissListener(null); old.dismiss(); }
    }
    private void immersive() {
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        bars.hide(WindowInsetsCompat.Type.systemBars());
        bars.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }
    private boolean isOwnEngineProcess() {
        String expected = getPackageName() + ":ethornell";
        if (Build.VERSION.SDK_INT >= 28) return expected.equals(android.app.Application.getProcessName());
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (manager != null && manager.getRunningAppProcesses() != null) {
            for (ActivityManager.RunningAppProcessInfo process : manager.getRunningAppProcesses())
                if (process.pid == Process.myPid() && process.uid == Process.myUid()) return expected.equals(process.processName);
        }
        return false;
    }
    private void terminateOwnProcess() {
        // Pre-validated before worker creation. Never kill another pid or an unverified main process.
        if (processVerified) Process.killProcess(Process.myPid());
    }
    private void finishDispatch() {
        if (!dispatchClaimed) return;
        try {
            if (EthornellDispatchRegistry.finish(getFilesDir(), dispatchToken, dispatchIdentity, dispatchPid, dispatchStart))
                dispatchClaimed = false;
            // Failure stays CLAIMED. Process death remains independently provable; never fabricate terminal.
        } catch (IOException ignored) { /* Fail closed in durable metadata; process teardown still proceeds. */ }
    }
    private String text(int id) { return EngineUiText.get(this, id); }
    private void toast(int id) { Toast.makeText(this, text(id), Toast.LENGTH_LONG).show(); }
}
