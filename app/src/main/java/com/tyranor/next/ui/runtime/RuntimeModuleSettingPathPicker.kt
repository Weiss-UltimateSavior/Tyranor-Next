package com.tyranor.next.ui.runtime

import android.content.ActivityNotFoundException
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.tyranor.next.R
import com.tyranor.next.core.engine.runtime.RuntimeModuleSettingItemSpec
import com.tyranor.next.core.engine.runtime.RuntimeModuleSettingItemSpec.Type
import com.tyranor.next.core.game.model.GamePathUtils
import com.tyranor.next.ui.common.notice.AppNotice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class RuntimeModuleSettingPathPickerActions(
    val pick: (RuntimeModuleSettingItemSpec) -> Unit,
    val invalidatePendingRequests: () -> Unit,
)

internal fun isRuntimeModuleSettingPathRequestCompatible(
    requestFingerprint: String?,
    requestType: Type?,
    currentFingerprint: String?,
    currentType: Type?,
): Boolean = requestFingerprint != null &&
    requestFingerprint == currentFingerprint &&
    requestType != null &&
    requestType == currentType

/** Owns a recoverable system-picker request and applies its result once the descriptor is ready. */
@Composable
internal fun rememberRuntimeModuleSettingPathPicker(
    gameUri: String,
    packageName: String,
    settingsReady: Boolean,
    descriptorFingerprint: String?,
    onPathSelected: (key: String, path: String, requestFingerprint: String, requestType: Type) -> Boolean,
): RuntimeModuleSettingPathPickerActions {
    val context = LocalContext.current
    val unavailableMessage = stringResource(R.string.runtime_settings_path_unavailable)
    var pendingKey by rememberSaveable(gameUri, packageName) { mutableStateOf<String?>(null) }
    var pendingFingerprint by rememberSaveable(gameUri, packageName) { mutableStateOf<String?>(null) }
    var pendingTypeName by rememberSaveable(gameUri, packageName) { mutableStateOf<String?>(null) }
    var requestGeneration by rememberSaveable(gameUri, packageName) { mutableStateOf(0L) }
    var pendingGeneration by rememberSaveable(gameUri, packageName) { mutableStateOf<Long?>(null) }
    var resolvingKey by rememberSaveable(gameUri, packageName) { mutableStateOf<String?>(null) }
    var resolvingFingerprint by rememberSaveable(gameUri, packageName) { mutableStateOf<String?>(null) }
    var resolvingTypeName by rememberSaveable(gameUri, packageName) { mutableStateOf<String?>(null) }
    var resolvingUri by rememberSaveable(gameUri, packageName) { mutableStateOf<String?>(null) }
    var resolvingGeneration by rememberSaveable(gameUri, packageName) { mutableStateOf<Long?>(null) }
    var resolvedKey by rememberSaveable(gameUri, packageName) { mutableStateOf<String?>(null) }
    var resolvedFingerprint by rememberSaveable(gameUri, packageName) { mutableStateOf<String?>(null) }
    var resolvedTypeName by rememberSaveable(gameUri, packageName) { mutableStateOf<String?>(null) }
    var resolvedPath by rememberSaveable(gameUri, packageName) { mutableStateOf<String?>(null) }
    var resolvedGeneration by rememberSaveable(gameUri, packageName) { mutableStateOf<Long?>(null) }
    val currentOnPathSelected by rememberUpdatedState(onPathSelected)

    fun showUnavailable() {
        AppNotice.makeText(context, unavailableMessage, Toast.LENGTH_LONG, AppNotice.Kind.Failure).show()
    }

    fun clearResolution() {
        resolvingKey = null
        resolvingFingerprint = null
        resolvingTypeName = null
        resolvingUri = null
        resolvingGeneration = null
    }

    fun clearResolvedResult() {
        resolvedKey = null
        resolvedFingerprint = null
        resolvedTypeName = null
        resolvedPath = null
        resolvedGeneration = null
    }

    // Keep the URI in SavedState before doing provider I/O. If the Activity is recreated while
    // resolving, cancellation leaves this request intact so the new composition retries it.
    LaunchedEffect(resolvingUri, resolvingGeneration, requestGeneration) {
        val uriText = resolvingUri ?: return@LaunchedEffect
        val generation = resolvingGeneration
        val key = resolvingKey
        val fingerprint = resolvingFingerprint
        val type = resolvingTypeName?.let { typeName -> Type.entries.firstOrNull { it.name == typeName } }
        if (generation == null || key == null || fingerprint == null || type == null) {
            showUnavailable()
            clearResolution()
            return@LaunchedEffect
        }
        if (generation != requestGeneration) {
            clearResolution()
            return@LaunchedEffect
        }

        try {
            val path = withContext(Dispatchers.IO) {
                GamePathUtils.resolveLocalPath(context, uriText)?.takeIf { it.startsWith('/') }
            }
            if (resolvingGeneration != generation || resolvingUri != uriText || requestGeneration != generation) {
                return@LaunchedEffect
            }
            if (path == null) {
                showUnavailable()
            } else {
                resolvedKey = key
                resolvedFingerprint = fingerprint
                resolvedTypeName = type.name
                resolvedPath = path
                resolvedGeneration = generation
            }
            clearResolution()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (resolvingGeneration == generation && resolvingUri == uriText && requestGeneration == generation) {
                showUnavailable()
                clearResolution()
            }
        }
    }

    LaunchedEffect(settingsReady, descriptorFingerprint, resolvedKey, resolvedPath, resolvedGeneration, requestGeneration) {
        val key = resolvedKey ?: return@LaunchedEffect
        val generation = resolvedGeneration ?: return@LaunchedEffect
        if (generation != requestGeneration) {
            clearResolvedResult()
            return@LaunchedEffect
        }
        if (!settingsReady) return@LaunchedEffect

        val fingerprint = resolvedFingerprint
        val type = resolvedTypeName?.let { typeName -> Type.entries.firstOrNull { it.name == typeName } }
        val path = resolvedPath
        if (fingerprint == null || type == null || path == null) {
            showUnavailable()
            clearResolvedResult()
            return@LaunchedEffect
        }

        if (!currentOnPathSelected(key, path, fingerprint, type)) showUnavailable()
        clearResolvedResult()
    }

    fun complete(uri: Uri?) {
        val key = pendingKey ?: return
        val generation = pendingGeneration ?: run {
            pendingKey = null
            pendingFingerprint = null
            pendingTypeName = null
            return
        }
        val fingerprint = pendingFingerprint
        val type = pendingTypeName?.let { typeName -> Type.entries.firstOrNull { it.name == typeName } }
        if (pendingGeneration == generation) {
            pendingKey = null
            pendingFingerprint = null
            pendingTypeName = null
            pendingGeneration = null
        }
        if (uri == null) {
            clearResolution()
            clearResolvedResult()
            return
        }
        if (generation != requestGeneration) return
        if (fingerprint == null || type == null) {
            showUnavailable()
            return
        }
        resolvingKey = key
        resolvingFingerprint = fingerprint
        resolvingTypeName = type.name
        resolvingUri = uri.toString()
        resolvingGeneration = generation
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> complete(uri) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> complete(uri) }

    fun invalidatePendingRequests() {
        requestGeneration += 1
        pendingKey = null
        pendingFingerprint = null
        pendingTypeName = null
        pendingGeneration = null
        clearResolution()
        clearResolvedResult()
    }

    val pick: (RuntimeModuleSettingItemSpec) -> Unit = { item ->
        val fingerprint = descriptorFingerprint
        if (pendingKey == null && resolvingUri == null && resolvedKey == null && fingerprint != null && item.type in setOf(Type.FILE, Type.FOLDER)) {
            requestGeneration += 1
            val generation = requestGeneration
            pendingKey = item.key
            pendingFingerprint = fingerprint
            pendingTypeName = item.type.name
            pendingGeneration = generation
            try {
                if (item.type == Type.FILE) filePicker.launch(arrayOf("*/*")) else folderPicker.launch(null)
            } catch (_: ActivityNotFoundException) {
                if (pendingGeneration == generation) {
                    pendingKey = null
                    pendingFingerprint = null
                    pendingTypeName = null
                    pendingGeneration = null
                }
                showUnavailable()
            } catch (_: SecurityException) {
                if (pendingGeneration == generation) {
                    pendingKey = null
                    pendingFingerprint = null
                    pendingTypeName = null
                    pendingGeneration = null
                }
                showUnavailable()
            }
        }
    }

    return RuntimeModuleSettingPathPickerActions(pick, ::invalidatePendingRequests)
}
