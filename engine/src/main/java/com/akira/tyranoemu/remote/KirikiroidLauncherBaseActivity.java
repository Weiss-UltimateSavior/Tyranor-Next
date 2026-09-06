package com.akira.tyranoemu.remote;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.Xml;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;

import bridge.NativeBridge;
import com.core.engine.R;
import org.tvp.kirikiri2.KR2Activity;

/**
 * Base activity for Kirikiroid134/139 KRKR engine launches.
 *
 * ARCHITECTURE NOTE:
 * This class historically used reflection to call com.apps.LauncherActivity for theme
 * colors (primary color, dark mode). This creates a reverse dependency from the engine
 * library module to the app application module, which violates Gradle module dependency
 * direction. We are migrating to passing these values via Intent extras:
 *   - "primaryColor" (int): theme primary color
 *   - "darkMode" (boolean): whether dark mode is active
 *
 * The reflection calls are retained as deprecated fallbacks for backward compatibility
 * with com.apps callers that have not yet been updated to pass these extras. Once all
 * callers are migrated, the reflection paths will be removed.
 */
public abstract class KirikiroidLauncherBaseActivity extends KR2Activity {
    private static final String TAG = "Kirikiroid2";
    private static final long SAFE_FALLBACK_REVEAL_MS = 20_000L;
    @SuppressLint("StaticFieldLeak") // Dedicated process activity; cleared immediately before process termination.
    public static Context app;
    private FrameLayout mask;
    private TextView maskMessage;
    private TextView maskHint;
    private ProgressBar loadingSpinner;
    private volatile boolean nativeBridgeInitialized;
    private volatile boolean destroyed;
    private volatile boolean firstFrameRendered;
    private volatile boolean launchDispatched;
    private volatile boolean launchSucceeded;
    private volatile boolean maskRevealRequested;
    private volatile boolean launchOrientationGuardEnabled;
    private volatile String resolvedGameLibrary;
    private int launchReadinessFrames;
    private String pendingGamePath;
    private boolean pendingMaps;

    @Override
    protected void attachBaseContext(Context newBase) {
        // The bundled KRKR shell only ships its startup scene resources for zh-CN. App-level
        // locales are also applied to the dedicated :kirikiri2 process, so selecting English or
        // Japanese made the native shell start without its file-selector form and immediately
        // exit before startupFrom could receive the game path. Keep this engine-internal locale
        // independent from the launcher's display language.
        Locale engineLocale = Locale.SIMPLIFIED_CHINESE;
        Locale.setDefault(engineLocale);
        Configuration configuration = new Configuration(newBase.getResources().getConfiguration());
        configuration.setLocale(engineLocale);
        super.attachBaseContext(newBase.createConfigurationContext(configuration));
    }

    @Override
    public void onCreate(Bundle bundle) {
        launchOrientationGuardEnabled = !getIntent().getBooleanExtra("originMode", false);
        applyKrkrRequestedOrientation();
        doSetSystemUiVisibility();
        NativeBridge.configureSafMirror(getIntent().getStringExtra("safMirrorIndex"));
        // Anime4K 后处理：必须在 super.onCreate（GL SurfaceView 构造，EGL 版本定型）前配置
        com.core.gl.Anime4kRuntime.configure(this, getIntent().getStringExtra(com.core.gl.Anime4kRuntime.EXTRA_MODE));
        // Must run before super.onCreate (native library loading and preference singleton construction).
        applyFontPreferences();
        applyEnginePreferences();
        super.onCreate(bundle);
        app = this;
        // KR2 宿主不是 SDLActivity（SDL.setContext 从未被调用），须显式注册耳机
        // 热插拔监听，否则游戏中插拔耳机后静态 AudioTrack 失联、永久静音。
        org.libsdl.app.AudioRouteWatcher.ensureRegistered(this);
        if (getIntent().getBooleanExtra("originMode", false)) {
            return;
        }
        int primaryColor = launcherPrimaryColor();
        int backgroundColor = launcherColor("launcher_bg_color", Color.rgb(244, 245, 245));
        int textColor = launcherColor("launcher_text_color", Color.rgb(20, 34, 27));
        int mutedTextColor = launcherColor("launcher_text_muted_color", Color.rgb(130, 144, 138));

        FrameLayout launchMask = new FrameLayout(this);
        launchMask.setBackgroundColor(backgroundColor);
        configureLandscapeLoadingWindow(backgroundColor);
        // Never expose the KRKR shell scene while the selected game is being
        // started. The overlay is removed only after the game's first frames.
        FrameLayout safeContent = new FrameLayout(this);
        launchMask.addView(safeContent, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout loadingPanel = new LinearLayout(this);
        loadingPanel.setOrientation(LinearLayout.VERTICAL);
        loadingPanel.setGravity(Gravity.CENTER_HORIZONTAL);
        loadingPanel.setPadding(dp(22), dp(20), dp(22), dp(16));

        ProgressBar spinner = new ProgressBar(this);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(primaryColor));
        spinner.getIndeterminateDrawable().setColorFilter(primaryColor, PorterDuff.Mode.SRC_IN);
        spinner.setContentDescription(uiString(R.string.engine_game_loading));

        TextView title = new TextView(this);
        title.setText(uiString(R.string.engine_starting_game));
        title.setTextColor(textColor);
        title.setTextSize(16.0f);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.topMargin = dp(0);
        loadingPanel.addView(title, titleParams);

        LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(dp(32), dp(32));
        spinnerParams.gravity = Gravity.CENTER_HORIZONTAL;
        spinnerParams.topMargin = dp(14);
        loadingPanel.addView(spinner, spinnerParams);

        TextView hint = new TextView(this);
        hint.setText(uiString(R.string.engine_preparing_game));
        hint.setTextColor(mutedTextColor);
        hint.setTextSize(11.0f);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, -2);
        hintParams.topMargin = dp(10);
        loadingPanel.addView(hint, hintParams);

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        safeContent.addView(loadingPanel, panelParams);
        safeContent.setOnApplyWindowInsetsListener((view, insets) -> {
            // Match PadUi: the background can occupy the whole display, while
            // interactive/readable content stays clear of cutouts and bars.
            safeContent.setPadding(
                    insets.getSystemWindowInsetLeft(),
                    insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(),
                    insets.getSystemWindowInsetBottom());
            return insets;
        });
        safeContent.requestApplyInsets();
        launchMask.setLayoutParams(new ViewGroup.LayoutParams(-1, -1));
        this.mask = launchMask;
        this.maskMessage = title;
        this.maskHint = hint;
        this.loadingSpinner = spinner;
        this.mFrameLayout.addView(launchMask);
        NativeBridge.setKrkrGameReadyListener(this::revealGame);
        String path = getIntent().getStringExtra("path");
        if (path != null && path.length() != 0) {
            requestGameLaunch(path, false);
        } else {
            finish();
        }
    }

    /**
     * Persists font preferences from the launch Intent into the XML preference files the
     * native engine actually reads. Confirmed from krkr2 sources: FontImpl/FontSystem read
     * default_font / force_default_font via IndividualConfigManager, which first checks the
     * game directory's Kirikiroid2Preference.xml and falls back to
     * {filesDir}/.preference/GlobalPreference.xml for missing keys. SharedPreferences
     * (including Cocos2dxPrefsFile) has no effect on font keys. Must run before native
     * singleton construction (super.onCreate loads the libraries).
     *
     * Both keys have independent scopes (font_scope_default / font_scope_force extras):
     *   game   -> write the game directory XML (per-game override)
     *   global -> write the global XML and clear the stale key from the game directory XML
     * Per-key scopes prevent freezing the other key's global fallback value into the game
     * directory when only one key is overridden.
     *
     * Ownership markers: the launcher is the source of truth on writes — an injected
     * value always overwrites whatever the file holds, including values the user set
     * through the engine's own preference UI. Only the unset path is protected: when
     * the launcher wants a key removed (empty font / restore), a global XML entry is
     * removed only while it still holds the value we injected last time (tracked via
     * a hidden _rinne_injected_* Item); a value the user changed in the engine UI is
     * left alone. Game directory files are launcher-managed and cleared unconditionally.
     * Known limitation: keys written by pre-marker builds carry no marker and survive
     * one restore cycle; picking a launcher font again re-establishes the marker.
     */
    private void applyFontPreferences() {
        Intent intent = getIntent();
        if (intent == null) return;
        File globalFile = globalPreferenceFile();
        if (globalFile == null) {
            Log.w(TAG, "font pref global file unavailable");
            return;
        }
        File gameFile = gamePreferenceFile();
        // Keys fail independently: a read-only game directory must not block the other key.
        if (intent.hasExtra("default_font")) {
            try {
                String font = safeTrim(intent.getStringExtra("default_font"));
                File target = fontTarget(intent, "font_scope_default", gameFile, globalFile);
                boolean changed = applyPreferenceItem(
                        target, "default_font", markerFor("default_font"), font.isEmpty() ? null : font);
                Log.i(TAG, "font pref default_font='" + font + "' -> " + target
                        + (changed ? "" : " (unchanged)"));
                clearStalePreferenceItem(target, gameFile, "default_font", markerFor("default_font"));
            } catch (Exception error) {
                Log.w(TAG, "apply default_font failed", error);
            }
        }
        if (intent.hasExtra("force_default_font")) {
            try {
                boolean force = intent.getBooleanExtra("force_default_font", false);
                File target = fontTarget(intent, "font_scope_force", gameFile, globalFile);
                boolean changed = applyPreferenceItem(
                        target, "force_default_font", markerFor("force_default_font"), force ? "1" : "0");
                Log.i(TAG, "font pref force_default_font=" + force + " -> " + target
                        + (changed ? "" : " (unchanged)"));
                clearStalePreferenceItem(target, gameFile, "force_default_font", markerFor("force_default_font"));
            } catch (Exception error) {
                Log.w(TAG, "apply force_default_font failed", error);
            }
        }
    }

    /**
     * Applies the engine-level rendering/memory preferences carried in the single
     * <code>krkr_engine_prefs</code> extra (a JSON object mapping engine preference key to
     * {"v": value, "s": scope}, scope being game/global). This is the generalization of
     * [applyFontPreferences]: same XML injection channel, same marker-based ownership tracking,
     * except the marker is derived per split key and the scope comes from the JSON instead of a
     * per-key extra. NaN/absent values or scope default to global; a key launcher does not manage
     * is simply not present in the JSON and its XML entries are left untouched.
     */
    private void applyEnginePreferences() {
        Intent intent = getIntent();
        if (intent == null) return;
        String enginePrefsJson = intent.getStringExtra("krkr_engine_prefs");
        if (enginePrefsJson == null || enginePrefsJson.isEmpty()) return;
        File globalFile = globalPreferenceFile();
        if (globalFile == null) {
            Log.w(TAG, "engine pref global file unavailable");
            return;
        }
        File gameFile = gamePreferenceFile();
        try {
            JSONObject json = new JSONObject(enginePrefsJson);
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                try {
                    JSONObject pref = json.getJSONObject(key);
                    String value = safeTrim(pref.optString("v", ""));
                    boolean gameScope = "game".equals(pref.optString("s", "global"));
                    File target = scopeTarget(gameScope, gameFile, globalFile);
                    boolean changed = applyPreferenceItem(
                            target, key, markerFor(key), value.isEmpty() ? null : value);
                    Log.i(TAG, "engine pref " + key + "='" + value + "' scope="
                            + (gameScope ? "game" : "global") + " -> " + target
                            + (changed ? "" : " (unchanged)"));
                    clearStalePreferenceItem(target, gameFile, key, markerFor(key));
                } catch (Exception error) {
                    Log.w(TAG, "apply engine pref " + key + " failed", error);
                }
            }
        } catch (JSONException error) {
            Log.w(TAG, "parse engine prefs json failed", error);
        }
    }

    /** Hidden Item key that records the value this launcher injected for the given engine pref key. */
    private static String markerFor(String key) {
        return "_rinne_injected_" + key;
    }

    /** {filesDir}/.preference/GlobalPreference.xml; null when the directory cannot be created. */
    private File globalPreferenceFile() {
        File dir = new File(getFilesDir(), ".preference");
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        return new File(dir, "GlobalPreference.xml");
    }

    /** Game directory Kirikiroid2Preference.xml; null when projectRoot is unusable (content:// or origin mode). */
    private File gamePreferenceFile() {
        String root = gameRootDir();
        if (root.isEmpty()) return null;
        return new File(root, "Kirikiroid2Preference.xml");
    }

    /** Resolves the target file for one key: the game directory when scoped to game, else global. */
    private static File fontTarget(Intent intent, String scopeExtra, File gameFile, File globalFile) {
        return scopeTarget("game".equals(intent.getStringExtra(scopeExtra)), gameFile, globalFile);
    }

    /** Chooses the game directory XML for game scope (when available), otherwise the global XML. */
    private static File scopeTarget(boolean gameScope, File gameFile, File globalFile) {
        return gameScope && gameFile != null ? gameFile : globalFile;
    }

    /**
     * When a key lands in the global file, removes its stale copy (including any
     * leftover _rinne_injected_* marker) from the game directory XML so the game
     * follows the global value. Game files are launcher-managed: removal is
     * unconditional (legacy pre-marker builds wrote keys without markers).
     */
    private static void clearStalePreferenceItem(File target, File gameFile, String name, String markerName)
            throws Exception {
        if (target == gameFile || gameFile == null || !gameFile.isFile()) return;
        applyPreferenceItem(gameFile, name, markerName, null, false);
    }

    private String gameRootDir() {
        Intent intent = getIntent();
        if (intent == null) return "";
        String root = safeTrim(intent.getStringExtra("projectRoot"));
        if (root.isEmpty() || root.startsWith("content://")) return "";
        return root;
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * Writes one preference key into a tinyxml2-format XML file, preserving all other
     * Item/Custom/KeyMap entries. Boolean-style keys are stored as "1"/"0" integer strings
     * because the engine parses them with GetValue&lt;bool&gt; via int conversion.
     *
     * value == null means the launcher wants the key unset. With trackOwnership the file
     * is shared with the engine's own preference UI: a non-null value always overwrites
     * the current entry (the launcher is the source of truth on writes), while the unset
     * path removes the key only while it still equals the value recorded in markerName;
     * the marker is kept in sync on writes. Without ownership tracking the key is
     * written/removed directly.
     *
     * @return whether the file content changed.
     */
    private static boolean applyPreferenceItem(
            File file, String name, String markerName, String value) throws Exception {
        return applyPreferenceItem(file, name, markerName, value, true);
    }

    private static boolean applyPreferenceItem(
            File file, String name, String markerName, String value, boolean trackOwnership)
            throws Exception {
        LinkedHashMap<String, String> items = new LinkedHashMap<>();
        List<String[]> customs = new ArrayList<>();
        LinkedHashMap<Integer, Integer> keyMaps = new LinkedHashMap<>();
        if (file.isFile()) {
            XmlPullParser parser = Xml.newPullParser();
            try (FileInputStream in = new FileInputStream(file)) {
                parser.setInput(in, null);
                for (int event = parser.getEventType();
                        event != XmlPullParser.END_DOCUMENT;
                        event = parser.next()) {
                    if (event != XmlPullParser.START_TAG) continue;
                    String tag = parser.getName();
                    String key = parser.getAttributeValue(null, "key");
                    String attr = parser.getAttributeValue(null, "value");
                    if ("Item".equals(tag)) {
                        if (key != null && attr != null) items.put(key, attr);
                    } else if ("Custom".equals(tag)) {
                        if (key != null && attr != null) customs.add(new String[]{key, attr});
                    } else if ("KeyMap".equals(tag) && key != null && attr != null) {
                        try {
                            int keyCode = Integer.parseInt(key);
                            int mapped = Integer.parseInt(attr);
                            if (keyCode != 0 && mapped != 0) keyMaps.put(keyCode, mapped);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
        }
        boolean changed = false;
        if (value != null) {
            changed |= !value.equals(items.put(name, value));
            if (trackOwnership) {
                changed |= !value.equals(items.put(markerName, value));
            }
        } else if (trackOwnership) {
            String current = items.get(name);
            String marker = items.get(markerName);
            if (marker != null) {
                if (current == null || current.equals(marker)) {
                    // Still our injected value (or already gone): remove key and marker.
                    changed |= items.remove(name) != null;
                    changed |= items.remove(markerName) != null;
                } else {
                    // The user changed the value via the engine UI after our injection: disown.
                    changed |= items.remove(markerName) != null;
                }
            }
            // marker == null: never injected by us; the entry belongs to the engine UI. Untouched.
        } else {
            changed |= items.remove(name) != null;
            changed |= items.remove(markerName) != null;
        }
        if (!changed) return false;
        writePreferenceXml(file, items, customs, keyMaps);
        return true;
    }

    /**
     * Serializes the preference file atomically: content goes to a temp file next to the
     * target which is then renamed over it, so an interrupted write cannot truncate the
     * engine's whole preference file (renderers, key maps, etc.).
     */
    private static void writePreferenceXml(
            File file,
            LinkedHashMap<String, String> items,
            List<String[]> customs,
            LinkedHashMap<Integer, Integer> keyMaps) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            Log.w(TAG, "font pref cannot create parent dir " + parent);
            return;
        }
        File tmp = new File(parent, file.getName() + ".tmp");
        XmlSerializer serializer = Xml.newSerializer();
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            serializer.setOutput(out, "UTF-8");
            serializer.startDocument("UTF-8", true);
            serializer.startTag("", "GlobalPreference");
            for (Map.Entry<String, String> item : items.entrySet()) {
                serializer.startTag("", "Item");
                serializer.attribute("", "key", item.getKey());
                serializer.attribute("", "value", item.getValue());
                serializer.endTag("", "Item");
            }
            for (String[] custom : customs) {
                serializer.startTag("", "Custom");
                serializer.attribute("", "key", custom[0]);
                serializer.attribute("", "value", custom[1]);
                serializer.endTag("", "Custom");
            }
            for (Map.Entry<Integer, Integer> keyMap : keyMaps.entrySet()) {
                serializer.startTag("", "KeyMap");
                serializer.attribute("", "key", String.valueOf(keyMap.getKey()));
                serializer.attribute("", "value", String.valueOf(keyMap.getValue()));
                serializer.endTag("", "KeyMap");
            }
            serializer.endTag("", "GlobalPreference");
            serializer.endDocument();
            serializer.flush();
        }
        if (!tmp.renameTo(file)) {
            // renameTo over an existing target can fail on some filesystems: retry after delete.
            if (file.exists() && file.delete()) {
                if (tmp.renameTo(file)) return;
                // The target is gone and tmp holds the only complete copy: keep it for a
                // later recovery attempt instead of deleting (never destroy both copies).
                Log.w(TAG, "font pref rename retry failed, kept temp " + tmp);
                return;
            }
            // Target intact, only the swap failed: tmp is garbage and safe to remove.
            Log.w(TAG, "font pref cannot replace " + file);
            tmp.delete();
        }
    }

    @Override
    public void onLoadNativeLibraries() {
        String gameLibrary = gameLibraryForBridge();
        boolean initialized = NativeBridge.initialize(gameLibrary);
        nativeBridgeInitialized = initialized;
        recordBridgeDiagnostic("initialize=" + initialized + " library=" + gameLibrary);
        Log.i(TAG, "native initialize result=" + initialized + " so=" + gameLibrary);
        if (!initialized) {
            Log.e(TAG, "native bridge initialization failed; skip KRKR hook setup");
            return;
        }
        // Do not bypass TVPMainScene's internal delay. It serializes teardown of
        // the selector UI before the game UI is created; forcing it to zero can
        // leave the KRKR shell above an otherwise running game.
        Log.i(TAG, "direct game launch waits for native scene transition so=" + gameLibrary);
        Intent intent = getIntent();
        boolean scopedSaveDir = intent != null && intent.getBooleanExtra("scopedSaveDir", false);
        boolean safFileFallback = intent != null && intent.getBooleanExtra("safFileFallback", false);
        String patchOverlayTarget = intent == null ? null : intent.getStringExtra("krPatchOverlayTarget");
        String patchOverlayPath = intent == null ? null : intent.getStringExtra("krPatchOverlayPath");
        boolean patchOverlay = patchOverlayTarget != null && !patchOverlayTarget.trim().isEmpty()
                && patchOverlayPath != null && !patchOverlayPath.trim().isEmpty();
        String steamConfigOverlayTarget = intent == null ? null : intent.getStringExtra("krSteamConfigOverlayTarget");
        String steamConfigOverlayPath = intent == null ? null : intent.getStringExtra("krSteamConfigOverlayPath");
        boolean steamConfigOverlay = steamConfigOverlayTarget != null && !steamConfigOverlayTarget.trim().isEmpty()
                && steamConfigOverlayPath != null && !steamConfigOverlayPath.trim().isEmpty();
        if (intent == null) {
            Log.i(TAG, "native interceptor skipped: no launch intent");
            return;
        }

        NativeBridge.configurePatchOverlay(patchOverlayTarget, patchOverlayPath);
        NativeBridge.configureSteamConfigOverlay(steamConfigOverlayTarget, steamConfigOverlayPath);

        if (!scopedSaveDir && !safFileFallback && !patchOverlay && !steamConfigOverlay) {
            Log.i(TAG, "native interceptor skipped: save redirect, SAF fallback and read-only overlay disabled");
            return;
        }
        String prefix = null;
        try {
            String rawPath = intent.getStringExtra("projectRoot");
            if (rawPath == null || rawPath.trim().isEmpty()) rawPath = intent.getStringExtra("gamedir");
            if (rawPath == null || rawPath.trim().isEmpty()) rawPath = intent.getStringExtra("path");
            if (rawPath != null && !rawPath.trim().isEmpty()) {
                String resolved = normalizeKrPath(rawPath);
                File root = new File(resolved);
                if (root.isFile()) root = root.getParentFile();
                if (root != null) {
                    if (scopedSaveDir) {
                        String explicitSaveRoot = normalizeKrPath(intent.getStringExtra("scopedSaveRoot"));
                        File saveRoot = explicitSaveRoot.isEmpty() ? null : new File(explicitSaveRoot);
                        if (saveRoot == null || (!saveRoot.isDirectory() && !saveRoot.mkdirs())) {
                            Log.e(TAG, "KRKR scoped save directory unavailable: " + explicitSaveRoot);
                            nativeBridgeInitialized = false;
                            return;
                        }
                    }
                    // KRKR normalizes storage names to lowercase before some native open()
                    // calls. A mixed-case game directory therefore cannot be matched by an
                    // exact savedata prefix. Hook the stable volume root and let KrPathUtils
                    // redirect only savedata paths to the app-scoped directory.
                    String mirrorRoot = normalizeKrPath(intent.getStringExtra("safMirrorRoot"));
                    prefix = mirrorRoot.isEmpty()
                            ? storagePrefix(root.getAbsolutePath())
                            : mirrorRoot.toLowerCase(Locale.ROOT);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "resolve scoped hook prefix failed", t);
        }
        if (prefix != null) {
            try {
                NativeBridge.interceptor(prefix);
                int relocateStatus = NativeBridge.relocate();
                boolean relocated = relocateStatus != 0;
                recordBridgeDiagnostic("relocate=" + relocateStatus + " prefix=" + prefix
                        + " scoped=" + scopedSaveDir + " saf=" + safFileFallback
                        + " patchOverlay=" + patchOverlay + " steamConfigOverlay=" + steamConfigOverlay);
                Log.i(TAG, "native interceptor enabled prefix=" + prefix
                        + " scoped=" + scopedSaveDir + " saf=" + safFileFallback
                        + " patchOverlay=" + patchOverlay
                        + " steamConfigOverlay=" + steamConfigOverlay
                        + " relocated=" + relocated);
                if (!relocated) {
                    Log.e(TAG, "native interceptor could not patch libgame file imports");
                    if (scopedSaveDir) nativeBridgeInitialized = false;
                }
            } catch (Throwable t) {
                Log.e(TAG, "enable native interceptor failed", t);
                if (scopedSaveDir) nativeBridgeInitialized = false;
            }
        } else {
            Log.w(TAG, "native interceptor skipped: empty prefix");
            if (scopedSaveDir) nativeBridgeInitialized = false;
        }
    }

    private void recordBridgeDiagnostic(String value) {
        try {
            getSharedPreferences("krkr_bridge_diagnostics", MODE_PRIVATE)
                    .edit().putString("last", value).commit();
        } catch (Throwable ignored) { }
    }

    private synchronized void requestGameLaunch(String path, boolean maps) {
        if (!nativeBridgeInitialized) {
            Log.e(TAG, "skip launch because native bridge was not initialized");
            showLaunchFailure(uiString(R.string.engine_krkr_initialization_failed));
            return;
        }
        pendingGamePath = path;
        pendingMaps = maps;
        dispatchPendingLaunchOnGlThread();
    }

    @Override
    protected void onCocosRendererReady() {
        // nativeInit only constructs the scene.  Its initial UI tasks are still
        // queued until the first render pass, so starting a game here lets the
        // shell initialise after (and above) the game scene.
    }

    @Override
    protected void onCocosFrameRendered() {
        if (!firstFrameRendered) firstFrameRendered = true;
        // The file selector is registered a few frames after the renderer. Keep
        // checking until startupFrom can actually dismiss it.
        if (!launchDispatched) dispatchPendingLaunchOnGlThread();
    }

    private synchronized void dispatchPendingLaunchOnGlThread() {
        if (!firstFrameRendered || launchDispatched || pendingGamePath == null || destroyed || isFinishing()) return;
        if (!NativeBridge.isLaunchSceneReady(gameLibraryForBridge())) {
            launchReadinessFrames++;
            return;
        }
        launchDispatched = true;
        try {
            launchSucceeded = NativeBridge.launch(gameLibraryForBridge(), pendingGamePath, pendingMaps);
            Log.i(TAG, "renderer-ready launch result=" + launchSucceeded + " path=" + pendingGamePath
                    + " frames=" + launchReadinessFrames);
        } catch (Throwable t) {
            Log.e(TAG, "renderer-ready launch failed", t);
        }
        if (!launchSucceeded) {
            showLaunchFailure(uiString(R.string.engine_launch_failed));
        } else if (mask != null) {
            // Keep the KRKR shell hidden until the native update hook reports
            // that doStartup and the menu transition completed. The fallback
            // includes the engine's original ten-second scene handoff.
            mask.postDelayed(this::revealGame, SAFE_FALLBACK_REVEAL_MS);
        }
    }

    private void revealGame() {
        if (destroyed || mask == null || maskRevealRequested) return;
        // 弹窗未确认时不隐藏启动遮罩，防止引擎在用户确认前就显示游戏画面
        if (KR2Activity.isDialogShowing()) {
            mask.postDelayed(this::revealGame, 500);
            return;
        }
        maskRevealRequested = true;
        mask.post(() -> {
            if (!destroyed && mask != null) {
                Log.i(TAG, "hide KRKR launch mask after game-ready signal");
                mask.animate().alpha(0.0f).setDuration(150L).withEndAction(() -> {
                    if (mask != null) mask.setVisibility(android.view.View.GONE);
                    launchOrientationGuardEnabled = false;
                    applyKrkrRequestedOrientation();
                }).start();
            }
        });
    }

    private void showLaunchFailure(String message) {
        runOnUiThread(() -> {
            if (destroyed || isFinishing() || mask == null) return;
            if (loadingSpinner != null) loadingSpinner.setVisibility(View.GONE);
            if (maskMessage != null) maskMessage.setText(message);
            if (maskHint != null) maskHint.setText(uiString(R.string.engine_return_and_retry));
        });
    }

    protected final void setResolvedGameLibrary(String library) {
        resolvedGameLibrary = library;
    }

    protected final String gameLibraryForBridge() {
        String library = resolvedGameLibrary;
        return library != null && library.length() != 0 ? library : soName();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void configureLandscapeLoadingWindow(int backgroundColor) {
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(backgroundColor);
        window.setNavigationBarColor(backgroundColor);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(attributes);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        if (!isLauncherDarkMode()) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    private int launcherPrimaryColor() {
        Intent intent = getIntent();
        if (intent != null && intent.hasExtra("primaryColor")) {
            return intent.getIntExtra("primaryColor", Color.rgb(24, 185, 120));
        }
        // Deprecated reflection fallback: com.apps should pass "primaryColor" via Intent extra.
        // TODO: remove reflection once com.apps migration is complete.
        try {
            Object value = Class.forName("com.apps.LauncherActivity")
                    .getMethod("launcherPrimaryColor", Context.class)
                    .invoke(null, this);
            if (value instanceof Integer) return (Integer) value;
        } catch (Throwable ignored) {
            // 反射失败时忽略，回退 Intent extra 主色（兼容兜底）
        }
        return launcherColor("launcher_primary_color", Color.rgb(24, 185, 120));
    }

    @SuppressLint("DiscouragedApi") // Engine cannot compile against the app module's generated R class.
    private int launcherColor(String name, int fallback) {
        Context uiContext = launcherUiContext();
        int id = uiContext.getResources().getIdentifier(name, "color", getPackageName());
        return id == 0 ? fallback : uiContext.getColor(id);
    }

    private Context launcherUiContext() {
        // Deprecated reflection fallback: com.apps should pass "darkMode" via Intent extra.
        // Note: this Context does NOT automatically resolve values-night resources based on
        // the "darkMode" Intent extra; wrapLauncherUiMode in com.apps wraps the Context with
        // a UiModeManager override to force day/night resource resolution. When the reflection
        // fallback is removed, callers passing "darkMode" extra must also apply the night mode
        // override via AppCompatDelegate.setDefaultNightMode() or similar before retrieving colors.
        try {
            Object value = Class.forName("com.apps.LauncherActivity")
                    .getMethod("wrapLauncherUiMode", Context.class)
                    .invoke(null, this);
            if (value instanceof Context) return (Context) value;
        } catch (Throwable ignored) {
            // 反射失败时忽略，回退默认 Context（兼容兜底）
        }
        return this;
    }

    private String uiString(int resourceId) {
        String languageTag = getIntent() == null ? null : getIntent().getStringExtra("uiLanguageTag");
        if (languageTag == null || languageTag.trim().isEmpty()) return getString(resourceId);
        try {
            Configuration configuration = new Configuration(getResources().getConfiguration());
            configuration.setLocale(Locale.forLanguageTag(languageTag));
            return createConfigurationContext(configuration).getString(resourceId);
        } catch (Throwable ignored) {
            return getString(resourceId);
        }
    }

    private boolean isLauncherDarkMode() {
        Intent intent = getIntent();
        if (intent != null && intent.hasExtra("darkMode")) {
            return intent.getBooleanExtra("darkMode", false);
        }
        // Deprecated reflection fallback: com.apps should pass "darkMode" via Intent extra.
        try {
            Object value = Class.forName("com.apps.LauncherActivity")
                    .getMethod("isLauncherDarkMode", Context.class)
                    .invoke(null, this);
            if (value instanceof Boolean) return (Boolean) value;
        } catch (Throwable ignored) {
            // 反射失败时忽略，回退系统 uiMode 判断（兼容兜底）
        }
        return (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private static String normalizeKrPath(String path) {
        if (path == null) return "";
        String p = path.trim();
        if (p.startsWith("file://")) p = p.substring("file://".length());
        while (p.startsWith("./")) p = p.substring(2);
        if (p.startsWith("storage/")) p = "/" + p;
        return p;
    }

    @SuppressLint("SdCardPath") // Normalizes legacy KR paths before redirecting them through scoped storage.
    private static String storagePrefix(String path) {
        String p = normalizeKrPath(path);
        String lower = p.toLowerCase(Locale.ROOT);
        if (lower.startsWith("/storage/emulated/0/")) return "/storage/emulated/0";
        if (lower.startsWith("/sdcard/")) return "/sdcard";
        if (lower.startsWith("/storage/")) {
            String rest = p.substring("/storage/".length());
            int slash = rest.indexOf('/');
            if (slash > 0) return "/storage/" + rest.substring(0, slash);
        }
        return p;
    }

    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Intent oldIntent = getIntent();
        if (oldIntent == null || intent == null) return;
        String oldPath = oldIntent.getStringExtra("path");
        String newPath = intent.getStringExtra("path");
        if (newPath != null && !newPath.equals(oldPath)) {
            Toast.makeText(this, uiString(R.string.engine_another_game_running), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        applyKrkrRequestedOrientation();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyKrkrRequestedOrientation();
        doSetSystemUiVisibility();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        String focus = getIntent().getStringExtra("focus");
        boolean forceFocus = focus != null && Boolean.parseBoolean(focus);
        super.onWindowFocusChanged(hasFocus || forceFocus);
        if (hasFocus || forceFocus) doSetSystemUiVisibility();
    }

    @Override
    @SuppressLint("MissingSuperCall")
    public void onDestroy() {
        try {
            destroyed = true;
            mask = null;
            maskMessage = null;
            maskHint = null;
            loadingSpinner = null;
            NativeBridge.setKrkrGameReadyListener(null);
            com.core.gl.Anime4kRuntime.reset();
            if (app == this) app = null;
        } catch (Throwable ignored) {
            // 清理阶段部分成员已为 null/已回收，失败可安全忽略（进程即将被 kill）
        }
        // This Activity has a dedicated process. Do not enter Cocos teardown first:
        // its RenderThread can still lock native state after that state is destroyed.
        Log.i(TAG, "terminate dedicated KR process before Cocos teardown");
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    public abstract String soName();

    private void applyKrkrRequestedOrientation() {
        try {
            setRequestedOrientation(currentKrkrRequestedOrientation());
        } catch (Throwable t) {
            Log.w(TAG, "apply KRKR orientation failed", t);
        }
    }

    private int currentKrkrRequestedOrientation() {
        int requested = getIntent() == null
                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : getIntent().getIntExtra("orientation", ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        if (!launchOrientationGuardEnabled) return requested;
        // Kirikiroid2/Cocos is fragile while startupFrom/doStartup is still loading XP3/TJS.
        // During the launch mask, always use a concrete landscape orientation.  Do not use
        // SCREEN_ORIENTATION_LOCKED here: some tablets lock the transient portrait state and
        // leave the loading overlay mismatched with the touch/surface coordinates.
        if (requested == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE) {
            return ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
        }
        return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
    }
}
