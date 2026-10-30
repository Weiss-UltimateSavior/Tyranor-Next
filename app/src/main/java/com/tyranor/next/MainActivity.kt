package com.tyranor.next

import android.Manifest
import android.graphics.Color
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.core.settings.OnboardingWriteResult
import com.tyranor.next.ui.onboarding.OnboardingCoachOverlay
import com.tyranor.next.ui.onboarding.OnboardingStorageError
import com.tyranor.next.ui.onboarding.CoachAnchor
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.tyranor.next.core.engine.plugin.EnginePluginBootstrap
import com.tyranor.next.ui.common.ProvideAppLocale
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.GlassBackground
import com.tyranor.next.theme.TyranorNextTheme
import com.tyranor.next.core.updater.UpdateNotificationManager

class MainActivity : ComponentActivity() {
  private val notificationPermissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
    )

    // 状态栏/导航栏透明沉浸由 enableEdgeToEdge(transparent) 处理，无需再设置已弃用的 window.statusBarColor

    setContent {
      ProvideAppLocale {
        TyranorNextTheme {
          // 系统栏图标跟随外观模式：深色模式用浅色图标（SystemBarStyle.dark）
          val activity = this@MainActivity
          val dark = AppThemeColors.isDark
          SideEffect {
            if (dark) {
              activity.enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
              )
            } else {
              activity.enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
              )
            }
          }
          GlassBackground { AppEntry() }
        }
      }
    }
  }

  @Composable
  private fun AppEntry() {
    val app = application as TyranorNextApplication
    var startupReady by remember { mutableStateOf(app.startupReady) }
    if (!startupReady) {
      OnboardingStorageError(onRetry = { startupReady = app.initializeStartup() })
      return
    }
    LaunchedEffect(Unit) {
      // 标记初始化后才准备插件，避免失败重试期间生成旧安装特征。
      Thread {
        EnginePluginBootstrap.provisionIfNeeded(applicationContext)
      }.apply { isDaemon = true }.start()
    }
    var completed by remember { mutableStateOf(AppSettingsStore.isOnboardingCompleted(this)) }
    var coachStep by rememberSaveable { mutableStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var coachAnchors by remember { mutableStateOf<Map<Int, Rect>>(emptyMap()) }
    val coachPage = when (coachStep) {
      CoachAnchor.HOME -> 0
      in CoachAnchor.GAME_ADD..CoachAnchor.GAME_SCAN -> 1
      CoachAnchor.ENGINE -> 2
      else -> 3
    }
    val scope = rememberCoroutineScope()
    fun pageForAnchor(index: Int): Int = when (index) {
      CoachAnchor.HOME -> 0
      in CoachAnchor.GAME_ADD..CoachAnchor.GAME_SCAN -> 1
      CoachAnchor.ENGINE -> 2
      else -> 3
    }
    LaunchedEffect(coachPage) {
      // 四页常驻组合会保留隐藏页；切页时丢弃旧页坐标，避免把屏外矩形用于新页面。
      coachAnchors = coachAnchors.filterKeys { pageForAnchor(it) == coachPage }
    }
    val reportCoachAnchor: (Int, Rect) -> Unit = { index, rect ->
      if (pageForAnchor(index) == coachPage && coachAnchors[index] != rect) {
        coachAnchors = coachAnchors + (index to rect)
      }
    }

    if (completed) {
      LaunchedEffect(Unit) {
        app.initializeMainLibrary()
        if (UpdateNotificationManager.shouldRequestPermission(this@MainActivity)) {
          UpdateNotificationManager.markPermissionRequested(this@MainActivity)
          notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
      }
      MainNavigation(onCoachAnchorChanged = {})
    } else {
      LaunchedEffect(Unit) {
        // 教练层展示真实主界面，因此先确保其游戏库依赖按正常入口初始化。
        app.initializeMainLibrary()
      }
      Box(Modifier.fillMaxSize()) {
        MainNavigation(
          coachPage = coachPage,
          onCoachAnchorChanged = reportCoachAnchor,
        )
        OnboardingCoachOverlay(
          step = coachStep,
          saving = saving,
          saveFailed = saveFailed,
          targetBounds = coachAnchors[coachStep],
          onNext = {
            if (coachStep == CoachAnchor.SETTINGS) finishOnboarding(
              saving = saving,
              setSaving = { saving = it },
              setSaveFailed = { saveFailed = it },
              setCompleted = { completed = it },
              scope = scope,
            ) else {
              coachStep += 1
            }
          },
          onSkip = {
            finishOnboarding(
              saving = saving,
              setSaving = { saving = it },
              setSaveFailed = { saveFailed = it },
              setCompleted = { completed = it },
              scope = scope,
            )
          },
        )
      }
    }
  }

  private fun finishOnboarding(
    saving: Boolean,
    setSaving: (Boolean) -> Unit,
    setSaveFailed: (Boolean) -> Unit,
    setCompleted: (Boolean) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
  ) {
    if (!saving) {
      setSaving(true)
      setSaveFailed(false)
      scope.launch {
        val saved = withContext(Dispatchers.IO) {
          AppSettingsStore.setOnboardingCompleted(applicationContext)
        }
        setSaving(false)
        if (saved == OnboardingWriteResult.SAVED) {
          setCompleted(true)
        } else {
          setSaveFailed(true)
        }
      }
    }
  }
}
