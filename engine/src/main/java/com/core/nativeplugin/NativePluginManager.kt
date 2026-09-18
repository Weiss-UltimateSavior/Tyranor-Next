package com.core.nativeplugin

import android.content.Context
import android.content.res.Resources
import android.content.pm.PackageManager
import android.os.Build
import com.core.engine.EnginePrefs
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale

enum class NativePluginInstallState {
    NOT_INSTALLED,
    INSTALLED_ENABLED,
    INSTALLED_DISABLED,
    INVALID,
}

data class NativePluginReadyResult(
    val ready: Boolean,
    val state: NativePluginInstallState,
    val code: String,
)

/**
 * Manages installed native engine plugin state from app-private storage.
 *
 * All methods are safe to call from Java. File operations should run on an IO thread when
 * called from UI because validity checks touch app-private plugin files.
 */
object NativePluginManager {
    private const val ROOT_DIR = "engine_plugins"
    private const val CURRENT_DIR = "current"
    private const val MANIFEST_JSON = "manifest.json"

    @JvmStatic
    fun kirikiroid2RootDir(context: Context): File =
        File(File(context.applicationContext.filesDir, ROOT_DIR), NativePluginConstants.ENGINE_KIRIKIROID2)

    @JvmStatic
    fun kirikiroid2CurrentDir(context: Context): File = File(kirikiroid2RootDir(context), CURRENT_DIR)

    @JvmStatic
    fun isKirikiroid2Installed(context: Context): Boolean =
        prefs(context).getBoolean(EnginePrefs.KEY_NATIVE_PLUGIN_KIRIKIROID2_INSTALLED, false) &&
            validateKirikiroid2Directory(kirikiroid2CurrentDir(context))

    @JvmStatic
    fun isKirikiroid2Enabled(context: Context): Boolean =
        prefs(context).getBoolean(EnginePrefs.KEY_NATIVE_PLUGIN_KIRIKIROID2_ENABLED, false)

    @JvmStatic
    fun setKirikiroid2Enabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(EnginePrefs.KEY_NATIVE_PLUGIN_KIRIKIROID2_ENABLED, enabled).apply()
    }

    @JvmStatic
    fun kirikiroid2InstallState(context: Context): NativePluginInstallState {
        val current = kirikiroid2CurrentDir(context)
        if (!current.exists()) return NativePluginInstallState.NOT_INSTALLED
        if (!validateKirikiroid2Directory(current)) return NativePluginInstallState.INVALID
        return if (isKirikiroid2Enabled(context)) {
            NativePluginInstallState.INSTALLED_ENABLED
        } else {
            NativePluginInstallState.INSTALLED_DISABLED
        }
    }

    @JvmStatic
    fun requireKirikiroid2Ready(context: Context): NativePluginReadyResult {
        val state = kirikiroid2InstallState(context)
        return when (state) {
            NativePluginInstallState.INSTALLED_ENABLED ->
                NativePluginReadyResult(true, state, "ready")
            NativePluginInstallState.INSTALLED_DISABLED ->
                NativePluginReadyResult(false, state, "disabled")
            NativePluginInstallState.INVALID ->
                NativePluginReadyResult(false, state, "invalid")
            NativePluginInstallState.NOT_INSTALLED ->
                NativePluginReadyResult(false, state, "not_installed")
        }
    }

    @JvmStatic
    fun deleteKirikiroid2(context: Context): Boolean {
        val root = kirikiroid2RootDir(context)
        prepareDirectoryForDelete(root)
        val deleted = !root.exists() || root.deleteRecursively()
        if (deleted) clearKirikiroid2Metadata(context)
        return deleted
    }

    @JvmStatic
    fun kirikiroid2LibPath(context: Context, libName: String?): String? {
        val safeName = libName?.trim() ?: return null
        if (!NativePluginConstants.KIRIKIROID2_REQUIRED_LIBS.contains(safeName)) return null
        val file = File(File(kirikiroid2CurrentDir(context), NativePluginConstants.ABI_ARM64), safeName)
        return if (file.isFile) file.absolutePath else null
    }

    @JvmStatic
    fun expectedKirikiroid2ZipSha256(context: Context): String? {
        val override = overridePrefs(context).getString(
            EnginePrefs.KEY_NATIVE_PLUGIN_KIRIKIROID2_EXPECTED_ZIP_SHA256,
            null,
        )
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
        if (override != null) return override
        return try {
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getApplicationInfo(
                    context.packageName,
                    PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            }
            expectedSha256FromMetaData(context, appInfo.metaData)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    // ----- ONS 外置 native zip 插件 -----

    @JvmStatic
    fun onsRootDir(context: Context): File =
        File(File(context.applicationContext.filesDir, ROOT_DIR), NativePluginConstants.ENGINE_ONS)

    @JvmStatic
    fun onsCurrentDir(context: Context): File = File(onsRootDir(context), CURRENT_DIR)

    @JvmStatic
    fun isOnsInstalled(context: Context): Boolean =
        prefs(context).getBoolean(EnginePrefs.KEY_NATIVE_PLUGIN_ONS_INSTALLED, false) &&
            validateOnsDirectory(onsCurrentDir(context))

    @JvmStatic
    fun isOnsEnabled(context: Context): Boolean =
        prefs(context).getBoolean(EnginePrefs.KEY_NATIVE_PLUGIN_ONS_ENABLED, false)

    @JvmStatic
    fun setOnsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(EnginePrefs.KEY_NATIVE_PLUGIN_ONS_ENABLED, enabled).apply()
    }

    @JvmStatic
    fun onsInstallState(context: Context): NativePluginInstallState {
        val current = onsCurrentDir(context)
        if (!current.exists()) return NativePluginInstallState.NOT_INSTALLED
        if (!validateOnsDirectory(current)) return NativePluginInstallState.INVALID
        return if (isOnsEnabled(context)) {
            NativePluginInstallState.INSTALLED_ENABLED
        } else {
            NativePluginInstallState.INSTALLED_DISABLED
        }
    }

    @JvmStatic
    fun requireOnsReady(context: Context): NativePluginReadyResult {
        val state = onsInstallState(context)
        return when (state) {
            NativePluginInstallState.INSTALLED_ENABLED ->
                NativePluginReadyResult(true, state, "ready")
            NativePluginInstallState.INSTALLED_DISABLED ->
                NativePluginReadyResult(false, state, "disabled")
            NativePluginInstallState.INVALID ->
                NativePluginReadyResult(false, state, "invalid")
            NativePluginInstallState.NOT_INSTALLED ->
                NativePluginReadyResult(false, state, "not_installed")
        }
    }

    @JvmStatic
    fun deleteOns(context: Context): Boolean {
        val root = onsRootDir(context)
        prepareDirectoryForDelete(root)
        val deleted = !root.exists() || root.deleteRecursively()
        if (deleted) clearOnsMetadata(context)
        return deleted
    }

    /**
     * 按引擎版本取 so 路径。
     *
     * ONS 插件目录在 arm64-v8a 下按版本分子目录：
     * - `arm64-v8a/` 根目录是随插件一起发布的基础版本（0.7.6），保持向后兼容；
     * - `arm64-v8a/v0.7.7/` 这类子目录是可选的新版本。
     *
     * @param version 版本目录名（如 "v0.7.7"）；传 null 或基础版本时用根目录。
     */
    @JvmStatic
    fun onsLibPath(context: Context, libName: String?, version: String?): String? {
        val safeName = libName?.trim() ?: return null
        if (!NativePluginConstants.ONS_REQUIRED_LIBS.contains(safeName)) return null
        val abiDir = onsVersionDir(context, version)
        val file = File(abiDir, safeName)
        return if (file.isFile) file.absolutePath else null
    }

    /** 某个版本的 so 所在目录（不存在时不负责创建）。 */
    @JvmStatic
    fun onsVersionDir(context: Context, version: String?): File {
        val abiDir = File(onsCurrentDir(context), NativePluginConstants.ABI_ARM64)
        return if (version.isNullOrBlank() || version == NativePluginConstants.ONS_BASE_VERSION) {
            abiDir
        } else {
            File(abiDir, version)
        }
    }

    /** 该版本是否已随插件安装好（8 个 so 齐全）。 */
    @JvmStatic
    fun isOnsVersionAvailable(context: Context, version: String?): Boolean {
        val dir = onsVersionDir(context, version)
        if (!dir.isDirectory) return false
        return NativePluginConstants.ONS_REQUIRED_LIBS.all { File(dir, it).isFile }
    }

    @JvmStatic
    fun expectedOnsZipSha256(context: Context): String? {
        val override = overridePrefs(context).getString(
            EnginePrefs.KEY_NATIVE_PLUGIN_ONS_EXPECTED_ZIP_SHA256,
            null,
        )
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
        if (override != null) return override
        return try {
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getApplicationInfo(
                    context.packageName,
                    PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            }
            expectedSha256FromMetaData(context, appInfo.metaData, NativePluginConstants.META_ONS_EXPECTED_ZIP_SHA256)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    internal fun validateOnsDirectory(directory: File): Boolean {
        if (!directory.isDirectory) return false
        val abiDir = File(directory, NativePluginConstants.ABI_ARM64)
        if (!abiDir.isDirectory) return false
        for (lib in NativePluginConstants.ONS_REQUIRED_LIBS) {
            if (!File(abiDir, lib).isFile) return false
        }
        return validateManifest(File(directory, MANIFEST_JSON), NativePluginConstants.ENGINE_ONS)
    }

    internal fun recordOnsInstall(
        context: Context,
        zipSha256: String,
        pluginVersion: Int,
        bridgeAbi: Int,
        installedAt: Long,
    ) {
        prefs(context).edit()
            .putBoolean(EnginePrefs.KEY_NATIVE_PLUGIN_ONS_INSTALLED, true)
            .putBoolean(EnginePrefs.KEY_NATIVE_PLUGIN_ONS_ENABLED, true)
            .putInt(EnginePrefs.KEY_NATIVE_PLUGIN_ONS_VERSION, pluginVersion)
            .putString(EnginePrefs.KEY_NATIVE_PLUGIN_ONS_ABI, NativePluginConstants.ABI_ARM64)
            .putString(EnginePrefs.KEY_NATIVE_PLUGIN_ONS_ZIP_SHA256, zipSha256)
            .putLong(EnginePrefs.KEY_NATIVE_PLUGIN_ONS_INSTALLED_AT, installedAt)
            .putInt(EnginePrefs.KEY_NATIVE_PLUGIN_ONS_BRIDGE_ABI, bridgeAbi)
            .apply()
    }

    internal fun clearOnsMetadata(context: Context) {
        prefs(context).edit()
            .remove(EnginePrefs.KEY_NATIVE_PLUGIN_ONS_INSTALLED)
            .remove(EnginePrefs.KEY_NATIVE_PLUGIN_ONS_ENABLED)
            .remove(EnginePrefs.KEY_NATIVE_PLUGIN_ONS_VERSION)
            .remove(EnginePrefs.KEY_NATIVE_PLUGIN_ONS_ABI)
            .remove(EnginePrefs.KEY_NATIVE_PLUGIN_ONS_ZIP_SHA256)
            .remove(EnginePrefs.KEY_NATIVE_PLUGIN_ONS_INSTALLED_AT)
            .remove(EnginePrefs.KEY_NATIVE_PLUGIN_ONS_BRIDGE_ABI)
            .apply()
    }

    private fun expectedSha256FromMetaData(context: Context, metaData: android.os.Bundle?): String? {
        return expectedSha256FromMetaData(context, metaData, NativePluginConstants.META_KIRIKIROID2_EXPECTED_ZIP_SHA256)
    }

    private fun expectedSha256FromMetaData(
        context: Context,
        metaData: android.os.Bundle?,
        key: String,
    ): String? {
        if (metaData == null) return null
        val directValue = metaData.getString(key)
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
        if (directValue != null) return directValue
        val resId = metaData.getInt(key, 0)
        if (resId == 0) return null
        return try {
            context.getString(resId)
                .trim()
                .lowercase(Locale.ROOT)
                .takeIf { it.matches(Regex("[0-9a-f]{64}")) }
        } catch (_: Resources.NotFoundException) {
            null
        }
    }

    internal fun validateKirikiroid2Directory(directory: File): Boolean {
        if (!directory.isDirectory) return false
        val abiDir = File(directory, NativePluginConstants.ABI_ARM64)
        if (!abiDir.isDirectory) return false
        for (lib in NativePluginConstants.KIRIKIROID2_REQUIRED_LIBS) {
            if (!File(abiDir, lib).isFile) return false
        }
        return validateManifest(File(directory, MANIFEST_JSON), NativePluginConstants.ENGINE_KIRIKIROID2)
    }

    internal fun recordKirikiroid2Install(
        context: Context,
        zipSha256: String,
        pluginVersion: Int,
        bridgeAbi: Int,
        installedAt: Long,
    ) {
        prefs(context).edit()
            .putBoolean(EnginePrefs.KEY_NATIVE_PLUGIN_KIRIKIROID2_INSTALLED, true)
            .putBoolean(EnginePrefs.KEY_NATIVE_PLUGIN_KIRIKIROID2_ENABLED, true)
            .putInt(EnginePrefs.KEY_NATIVE_PLUGIN_KIRIKIROID2_VERSION, pluginVersion)
            .putString(EnginePrefs.KEY_NATIVE_PLUGIN_KIRIKIROID2_ABI, NativePluginConstants.ABI_ARM64)
            .putString(EnginePrefs.KEY_NATIVE_PLUGIN_KIRIKIROID2_ZIP_SHA256, zipSha256)
            .putLong(EnginePrefs.KEY_NATIVE_PLUGIN_KIRIKIROID2_INSTALLED_AT, installedAt)
            .putInt(EnginePrefs.KEY_NATIVE_PLUGIN_KIRIKIROID2_BRIDGE_ABI, bridgeAbi)
            .apply()
    }

    internal fun clearKirikiroid2Metadata(context: Context) {
        prefs(context).edit()
            .remove(EnginePrefs.KEY_NATIVE_PLUGIN_KIRIKIROID2_INSTALLED)
            .remove(EnginePrefs.KEY_NATIVE_PLUGIN_KIRIKIROID2_ENABLED)
            .remove(EnginePrefs.KEY_NATIVE_PLUGIN_KIRIKIROID2_VERSION)
            .remove(EnginePrefs.KEY_NATIVE_PLUGIN_KIRIKIROID2_ABI)
            .remove(EnginePrefs.KEY_NATIVE_PLUGIN_KIRIKIROID2_ZIP_SHA256)
            .remove(EnginePrefs.KEY_NATIVE_PLUGIN_KIRIKIROID2_INSTALLED_AT)
            .remove(EnginePrefs.KEY_NATIVE_PLUGIN_KIRIKIROID2_BRIDGE_ABI)
            .apply()
    }

    // ----- Artemis 外置 native zip 插件 -----

    @JvmStatic
    fun artemisRootDir(context: Context): File =
        File(File(context.applicationContext.filesDir, ROOT_DIR), NativePluginConstants.ENGINE_ARTEMIS)

    @JvmStatic
    fun artemisCurrentDir(context: Context): File = File(artemisRootDir(context), CURRENT_DIR)

    @JvmStatic
    fun isArtemisInstalled(context: Context): Boolean =
        prefs(context).getBoolean(EnginePrefs.KEY_NATIVE_PLUGIN_ARTEMIS_INSTALLED, false) &&
            validateArtemisDirectory(artemisCurrentDir(context))

    @JvmStatic
    fun isArtemisEnabled(context: Context): Boolean =
        prefs(context).getBoolean(EnginePrefs.KEY_NATIVE_PLUGIN_ARTEMIS_ENABLED, false)

    @JvmStatic
    fun setArtemisEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(EnginePrefs.KEY_NATIVE_PLUGIN_ARTEMIS_ENABLED, enabled).apply()
    }

    @JvmStatic
    fun artemisInstallState(context: Context): NativePluginInstallState {
        val current = artemisCurrentDir(context)
        if (!current.exists()) return NativePluginInstallState.NOT_INSTALLED
        if (!validateArtemisDirectory(current)) return NativePluginInstallState.INVALID
        return if (isArtemisEnabled(context)) {
            NativePluginInstallState.INSTALLED_ENABLED
        } else {
            NativePluginInstallState.INSTALLED_DISABLED
        }
    }

    @JvmStatic
    fun requireArtemisReady(context: Context): NativePluginReadyResult {
        val state = artemisInstallState(context)
        return when (state) {
            NativePluginInstallState.INSTALLED_ENABLED ->
                NativePluginReadyResult(true, state, "ready")
            NativePluginInstallState.INSTALLED_DISABLED ->
                NativePluginReadyResult(false, state, "disabled")
            NativePluginInstallState.INVALID ->
                NativePluginReadyResult(false, state, "invalid")
            NativePluginInstallState.NOT_INSTALLED ->
                NativePluginReadyResult(false, state, "not_installed")
        }
    }

    @JvmStatic
    fun deleteArtemis(context: Context): Boolean {
        val root = artemisRootDir(context)
        prepareDirectoryForDelete(root)
        val deleted = !root.exists() || root.deleteRecursively()
        if (deleted) clearArtemisMetadata(context)
        return deleted
    }

    @JvmStatic
    fun artemisLibPath(context: Context, libName: String?): String? {
        val safeName = libName?.trim() ?: return null
        if (!NativePluginConstants.ARTEMIS_REQUIRED_LIBS.contains(safeName)) return null
        val file = File(File(artemisCurrentDir(context), NativePluginConstants.ABI_ARM64), safeName)
        return if (file.isFile) file.absolutePath else null
    }

    @JvmStatic
    fun expectedArtemisZipSha256(context: Context): String? {
        val override = overridePrefs(context).getString(
            EnginePrefs.KEY_NATIVE_PLUGIN_ARTEMIS_EXPECTED_ZIP_SHA256,
            null,
        )
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
        if (override != null) return override
        return try {
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getApplicationInfo(
                    context.packageName,
                    PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            }
            expectedSha256FromMetaData(
                context,
                appInfo.metaData,
                NativePluginConstants.META_ARTEMIS_EXPECTED_ZIP_SHA256,
            )
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    internal fun validateArtemisDirectory(directory: File): Boolean {
        if (!directory.isDirectory) return false
        val abiDir = File(directory, NativePluginConstants.ABI_ARM64)
        if (!abiDir.isDirectory) return false
        for (lib in NativePluginConstants.ARTEMIS_REQUIRED_LIBS) {
            if (!File(abiDir, lib).isFile) return false
        }
        return validateManifest(File(directory, MANIFEST_JSON), NativePluginConstants.ENGINE_ARTEMIS)
    }

    internal fun recordArtemisInstall(
        context: Context,
        zipSha256: String,
        pluginVersion: Int,
        bridgeAbi: Int,
        installedAt: Long,
    ) {
        prefs(context).edit()
            .putBoolean(EnginePrefs.KEY_NATIVE_PLUGIN_ARTEMIS_INSTALLED, true)
            .putBoolean(EnginePrefs.KEY_NATIVE_PLUGIN_ARTEMIS_ENABLED, true)
            .putInt(EnginePrefs.KEY_NATIVE_PLUGIN_ARTEMIS_VERSION, pluginVersion)
            .putString(EnginePrefs.KEY_NATIVE_PLUGIN_ARTEMIS_ABI, NativePluginConstants.ABI_ARM64)
            .putString(EnginePrefs.KEY_NATIVE_PLUGIN_ARTEMIS_ZIP_SHA256, zipSha256)
            .putLong(EnginePrefs.KEY_NATIVE_PLUGIN_ARTEMIS_INSTALLED_AT, installedAt)
            .putInt(EnginePrefs.KEY_NATIVE_PLUGIN_ARTEMIS_BRIDGE_ABI, bridgeAbi)
            .apply()
    }

    internal fun clearArtemisMetadata(context: Context) {
        prefs(context).edit()
            .remove(EnginePrefs.KEY_NATIVE_PLUGIN_ARTEMIS_INSTALLED)
            .remove(EnginePrefs.KEY_NATIVE_PLUGIN_ARTEMIS_ENABLED)
            .remove(EnginePrefs.KEY_NATIVE_PLUGIN_ARTEMIS_VERSION)
            .remove(EnginePrefs.KEY_NATIVE_PLUGIN_ARTEMIS_ABI)
            .remove(EnginePrefs.KEY_NATIVE_PLUGIN_ARTEMIS_ZIP_SHA256)
            .remove(EnginePrefs.KEY_NATIVE_PLUGIN_ARTEMIS_INSTALLED_AT)
            .remove(EnginePrefs.KEY_NATIVE_PLUGIN_ARTEMIS_BRIDGE_ABI)
            .apply()
    }

    internal fun prepareDirectoryForDelete(directory: File) {
        if (!directory.exists()) return
        directory.walkTopDown().forEach { file ->
            file.setWritable(true, true)
        }
    }

    internal fun parseManifest(directory: File): JSONObject? =
        try {
            JSONObject(File(directory, MANIFEST_JSON).readText(Charsets.UTF_8))
        } catch (_: IOException) {
            null
        } catch (_: JSONException) {
            null
        }

    internal fun validateManifest(file: File, engineId: String): Boolean {
        val manifest = try {
            JSONObject(file.readText(Charsets.UTF_8))
        } catch (_: IOException) {
            return false
        } catch (_: JSONException) {
            return false
        }
        if (manifest.optString("engineId") != engineId) return false
        if (manifest.optString("abi") != NativePluginConstants.ABI_ARM64) return false
        val bridgeAbi = when (engineId) {
            NativePluginConstants.ENGINE_ONS -> NativePluginConstants.ONS_BRIDGE_ABI
            NativePluginConstants.ENGINE_KIRIKIROID2 -> NativePluginConstants.KIRIKIROID2_BRIDGE_ABI
            NativePluginConstants.ENGINE_ARTEMIS -> NativePluginConstants.ARTEMIS_BRIDGE_ABI
            else -> return false
        }
        if (manifest.optInt("bridgeAbi", -1) != bridgeAbi) return false
        return true
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(NativePluginConstants.PREFS_NAME, Context.MODE_PRIVATE)

    private fun overridePrefs(context: Context) =
        context.applicationContext.getSharedPreferences(EnginePrefs.NATIVE_PLUGIN_OVERRIDE_PREFS, Context.MODE_PRIVATE)
}
