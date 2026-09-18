package com.core.ons

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.Locale

class OnsSettings {
    @JvmField var stretchFull = false
    @JvmField var ignoreCutout = true
    @JvmField var sharpness = false
    @JvmField var sharpnessValue = "2"
    @JvmField var disableVideo = false
    @JvmField var encoding = "gbk"
    @JvmField var scopedSaveDir = true
    @JvmField var allowEditArgs = true
    // ===== 以下为对齐 onsyuri 0.7.7 补全的参数 =====
    /** --no-vsync：关闭垂直同步。部分机型上开着会掉帧，关掉更流畅但可能撕裂。 */
    @JvmField var noVsync = false
    /** --fontcache：缓存默认字体，长文本渲染更快，代价是多占一点内存。 */
    @JvmField var fontCache = false
    /** --render-font-outline：描边渲染文字，替代默认的投影效果。 */
    @JvmField var renderFontOutline = false
    /** --disable-rescale：不缩放档案内图片，保持像素原样。 */
    @JvmField var disableRescale = false
    /** --force-button-shortcut：忽略脚本的 useescspc / getenter，强制启用按键快捷操作。 */
    @JvmField var forceButtonShortcut = false
    /** --enable-wheeldown-advance：滚轮下滚推进文本。 */
    @JvmField var wheelDownAdvance = false
    /** --debug:1：输出引擎调试日志，排查脚本问题时才需要开。 */
    @JvmField var debugLog = false

    companion object {
        const val PREF_NAME = "onsyuri"

        /** 共享偏好中的 ONS 配置 JSON 键（与 Intent extra gameargs 同名字段的历史沿用）。 */
        const val EXTRA_GAME_ARGS = "gameargs"
        private const val TAG = "OnsSettings"
        private const val KEY_ENCODING_MIGRATED_GBK = "encoding_migrated_gbk_v2"

        @JvmStatic
        fun load(context: Context): OnsSettings {
            val settings = OnsSettings()
            try {
                val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                val json = sp.getString(EXTRA_GAME_ARGS, null)
                if (json != null && json.trim().isNotEmpty()) settings.readJson(JSONObject(json))
                if (!sp.getBoolean(KEY_ENCODING_MIGRATED_GBK, false)) {
                    val isUpgradeFromLegacy = sp.contains(EXTRA_GAME_ARGS)
                    val editor = sp.edit().putBoolean(KEY_ENCODING_MIGRATED_GBK, true)
                    if (isUpgradeFromLegacy && "sjis" == settings.encoding) {
                        settings.encoding = "gbk"
                        editor.putString(EXTRA_GAME_ARGS, settings.toJson().toString())
                    }
                    editor.apply()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "load failed", t)
            }
            return settings
        }

        @JvmStatic
        fun defaults(): OnsSettings = OnsSettings()

        @JvmStatic
        fun resolveScopedSaveDirectory(context: Context?, gameDir: String?): File? {
            if (context == null) return null
            val base = context.getExternalFilesDir(null) ?: return null
            return File(File(base, "save"), guessName(gameDir))
        }

        @JvmStatic
        fun resolveGameSaveDirectory(gameDir: String?): File? {
            if (gameDir == null || gameDir.trim().isEmpty()) return null
            return try {
                val root = File(gameDir).canonicalFile
                if (!root.isAbsolute || !root.isDirectory) return null
                val save = File(root, "save").canonicalFile
                if (!save.path.startsWith(root.path + File.separator)) return null
                save
            } catch (_: Throwable) {
                null
            }
        }

        @JvmStatic
        fun normalizeEncoding(value: String?): String {
            val v = if (value == null) "gbk" else value.trim().lowercase(Locale.ROOT)
            if ("gbk" == v || "utf8" == v || "sjis" == v) return v
            if ("utf-8" == v) return "utf8"
            if ("shift-jis" == v || "shift_jis" == v) return "sjis"
            return "gbk"
        }

        private fun guessName(path: String?): String {
            if (path.isNullOrEmpty()) return "ONSGame"
            val p = if (path.endsWith("/")) path.substring(0, path.length - 1) else path
            val slash = p.lastIndexOf('/')
            return if (slash >= 0 && slash + 1 < p.length) p.substring(slash + 1) else p
        }
    }

    private fun readJson(o: JSONObject) {
        stretchFull = o.optBoolean("strechfull", stretchFull)
        ignoreCutout = o.optBoolean("ignorecutout", ignoreCutout)
        sharpness = o.optBoolean("sharpness", sharpness)
        sharpnessValue = o.optString("sharpness_value", sharpnessValue)
        disableVideo = o.optBoolean("disablevideo", disableVideo)
        encoding = normalizeEncoding(o.optString("encoding", encoding))
        scopedSaveDir = o.optBoolean("scopedsavedir", scopedSaveDir)
        allowEditArgs = o.optBoolean("alloweditargs", allowEditArgs)
        noVsync = o.optBoolean("novsync", noVsync)
        fontCache = o.optBoolean("fontcache", fontCache)
        renderFontOutline = o.optBoolean("renderfontoutline", renderFontOutline)
        disableRescale = o.optBoolean("disablerescale", disableRescale)
        forceButtonShortcut = o.optBoolean("forcebuttonshortcut", forceButtonShortcut)
        wheelDownAdvance = o.optBoolean("wheeldownadvance", wheelDownAdvance)
        debugLog = o.optBoolean("debuglog", debugLog)
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("strechfull", stretchFull)
        o.put("ignorecutout", ignoreCutout)
        o.put("sharpness", sharpness)
        o.put("sharpness_value", safeSharpness())
        o.put("disablevideo", disableVideo)
        o.put("encoding", normalizeEncoding(encoding))
        o.put("scopedsavedir", scopedSaveDir)
        o.put("alloweditargs", allowEditArgs)
        o.put("novsync", noVsync)
        o.put("fontcache", fontCache)
        o.put("renderfontoutline", renderFontOutline)
        o.put("disablerescale", disableRescale)
        o.put("forcebuttonshortcut", forceButtonShortcut)
        o.put("wheeldownadvance", wheelDownAdvance)
        o.put("debuglog", debugLog)
        return o
    }

    fun save(context: Context) {
        try {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putString(EXTRA_GAME_ARGS, toJson().toString()).apply()
        } catch (t: Throwable) {
            Log.w(TAG, "save failed", t)
        }
    }

    fun buildArgs(context: Context, gameDir: String): ArrayList<String> {
        val saveDir = if (scopedSaveDir) resolveScopedSaveDirectory(context, gameDir) else resolveGameSaveDirectory(gameDir)
        return buildArgs(context, gameDir, saveDir)
    }

    fun buildArgs(context: Context, gameDir: String, saveDir: File?): ArrayList<String> {
        val root = gameDir
        val args = ArrayList<String>()
        args.add("--root")
        args.add(root)
        args.add("--font")
        args.add(if (root.endsWith("/")) root + "default.ttf" else root + "/default.ttf")
        args.add(if (stretchFull) "--fullscreen2" else "--fullscreen")
        if (disableVideo) args.add("--no-video")
        args.add("--enc:" + normalizeEncoding(encoding))
        if (saveDir != null) {
            val ready = saveDir.isDirectory || saveDir.mkdirs()
            if (!ready) {
                Log.w(TAG, "save dir not created before launch, still passing --save-dir=${saveDir.absolutePath}")
            }
            args.add("--save-dir")
            args.add(saveDir.absolutePath)
        }
        if (sharpness) {
            args.add("--sharpness")
            args.add(safeSharpness())
        }
        // ===== onsyuri 0.7.7 支持的其余开关 =====
        if (noVsync) args.add("--no-vsync")
        if (fontCache) args.add("--fontcache")
        if (renderFontOutline) args.add("--render-font-outline")
        if (disableRescale) args.add("--disable-rescale")
        if (forceButtonShortcut) args.add("--force-button-shortcut")
        if (wheelDownAdvance) args.add("--enable-wheeldown-advance")
        if (debugLog) args.add("--debug:1")
        return args
    }

    private fun safeSharpness(): String {
        val v = if (sharpnessValue == null) "" else sharpnessValue.trim()
        if (v.isEmpty()) return "2"
        return try {
            val parsed = v.toDouble()
            if (parsed.isNaN() || parsed.isInfinite()) return "2"
            if (parsed < 0.1 || parsed > 10.0) return "2"
            v
        } catch (_: NumberFormatException) {
            "2"
        }
    }
}
