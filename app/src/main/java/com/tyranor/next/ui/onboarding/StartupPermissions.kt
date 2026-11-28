package com.tyranor.next.ui.onboarding

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.tyranor.next.R
import com.tyranor.next.core.game.storage.AllFilesAccess
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.core.updater.UpdateNotificationManager
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.DialogTextButton
import com.tyranor.next.ui.common.notice.AppNotice

@Composable
internal fun StartupPermissions(locked: Boolean, canRequestNotifications: Boolean) {
    val context = LocalContext.current
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    var allFilesPromptHandled by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R || AppSettingsStore.hasHandledAllFilesPrompt(context),
        )
    }
    var awaitingSettings by rememberSaveable { mutableStateOf(false) }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        awaitingSettings = false
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun finishAllFilesPrompt() {
        AppSettingsStore.markAllFilesPromptHandled(context)
        allFilesPromptHandled = true
    }

    val needsAllFilesAccess = !allFilesPromptHandled && AllFilesAccess.isMissing()
    LaunchedEffect(
        resumed, locked, allFilesPromptHandled, needsAllFilesAccess, awaitingSettings, canRequestNotifications,
    ) {
        if (!resumed || locked || awaitingSettings) return@LaunchedEffect
        if (!allFilesPromptHandled) {
            if (!needsAllFilesAccess) finishAllFilesPrompt()
            return@LaunchedEffect
        }
        if (canRequestNotifications && UpdateNotificationManager.shouldRequestPermission(context)) {
            UpdateNotificationManager.markPermissionRequested(context)
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    if (!locked && needsAllFilesAccess) {
        AppAlertDialog(
            onDismissRequest = ::finishAllFilesPrompt,
            title = {
                Text(stringResource(R.string.storage_access_prompt_title), style = MaterialTheme.typography.titleMedium)
            },
            text = {
                Text(stringResource(R.string.storage_access_prompt_message), style = MaterialTheme.typography.bodyMedium)
            },
            textScrollEnabled = true,
            confirmButton = {
                DialogTextButton(stringResource(R.string.storage_access_prompt_open_settings)) {
                    if (allFilesPromptHandled) return@DialogTextButton
                    finishAllFilesPrompt()
                    if (AllFilesAccess.isMissing()) {
                        awaitingSettings = true
                        if (!AllFilesAccess.openSettings(context) { settingsLauncher.launch(it) }) {
                            awaitingSettings = false
                            AppNotice.makeText(
                                context, R.string.storage_access_settings_unavailable, Toast.LENGTH_LONG,
                                AppNotice.Kind.Failure,
                            ).show()
                        }
                    }
                }
            },
            dismissButton = {
                DialogTextButton(stringResource(R.string.storage_access_prompt_not_now), onClick = ::finishAllFilesPrompt)
            },
        )
    }
}
