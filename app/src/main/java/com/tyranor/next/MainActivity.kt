package com.tyranor.next

import android.Manifest
import android.graphics.Color
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.core.settings.OnboardingWriteResult
import com.tyranor.next.ui.onboarding.OnboardingCoachOverlay
import com.tyranor.next.ui.common.CoachAnchor
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.tyranor.next.core.engine.plugin.EnginePluginBootstrap
import com.tyranor.next.ui.common.ProvideAppLocale
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.GlassBackground
import com.tyranor.next.ui.common.motion.ProvideMotionEnvironment
import com.tyranor.next.ui.common.motion.pageTransitionScrim
import com.tyranor.next.theme.TyranorNextTheme
import com.tyranor.next.core.updater.UpdateNotificationManager
import com.tyranor.next.ui.common.observeSaveSyncFeedback

private fun coachPageOf(index: Int): Int = when (index) {
  CoachAnchor.HOME -> 0
  in CoachAnchor.GAME_ADD..CoachAnchor.GAME_SCAN -> 1
  CoachAnchor.ENGINE -> 2
  else -> 3
}

class MainActivity : ComponentActivity() {
  private val notificationPermissionState by viewModels<NotificationPermissionRequestState>()
  private val notificationPermissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
      notificationPermissionState.inFlight = false
      // A cancelled request has no permission entry; an explicit grant or denial has one.
      if (Manifest.permission.POST_NOTIFICATIONS in result) {
        UpdateNotificationManager.markPermissionRequested(this)
      }
    }

  /** Initializes the themed root and its motion environment before rendering the main navigation. */
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    observeSaveSyncFeedback()

    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
    )

    // 状态栏/导航栏透明沉浸由 enableEdgeToEdge(transparent) 处理，无需再设置已弃用的 window.statusBarColor

    setContent {
      ProvideAppLocale {
        TyranorNextTheme {
          // 系统栏图标跟随实际背景：深色模式/深色底图用浅色图标（SystemBarStyle.dark）
          val activity = this@MainActivity
          val dark = AppThemeColors.isDark || AppThemeColors.hasDarkBackdrop
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
          // 动效环境与二级页面压暗覆盖同一份真实主界面和引导层。
          ProvideMotionEnvironment {
            GlassBackground(Modifier.pageTransitionScrim()) { AppEntry() }
          }
        }
      }
    }
  }

  /** Keeps onboarding over the same navigation composition and defers notification permission until completion. */
  @Composable
  private fun AppEntry() {
    LaunchedEffect(Unit) {
      // 启动初始化后准备插件；失败时由独立快照保护首次安装判定不被基础设施数据改变。
      Thread {
        try {
          if (AppSettingsStore.prepareInstallationData(applicationContext)) {
            EnginePluginBootstrap.provisionIfNeeded(applicationContext)
          }
        } catch (failure: Exception) {
          android.util.Log.w("MainActivity", "Native plugin initialization failed", failure)
        }
      }.apply { isDaemon = true }.start()
    }
    var completed by remember { mutableStateOf(AppSettingsStore.isOnboardingCompleted(this)) }
    var coachStep by rememberSaveable { mutableStateOf(0) }
    val homeStyle by AppSettingsStore.homeStyleState.collectAsState()
    var saving by remember { mutableStateOf(false) }
    var saveFailed by rememberSaveable { mutableStateOf(false) }
    var saveRequest by rememberSaveable { mutableStateOf(0) }
    var coachAnchors by remember { mutableStateOf<Map<Int, Rect>>(emptyMap()) }
    val safeCoachStep = coachStep.coerceIn(CoachAnchor.HOME, CoachAnchor.SETTINGS)
    val coachPage = coachPageOf(safeCoachStep)
    LaunchedEffect(saveRequest) {
      if (saveRequest == 0) return@LaunchedEffect
      saving = true
      saveFailed = false
      val saved = withContext(Dispatchers.IO) {
        AppSettingsStore.setOnboardingCompleted(applicationContext)
      }
      saving = false
      saveRequest = 0
      if (saved == OnboardingWriteResult.SAVED) {
        completed = true
      } else {
        saveFailed = true
      }
    }
    LaunchedEffect(coachPage) {
      // 四页常驻组合会保留隐藏页；切页时丢弃旧页坐标，避免把屏外矩形用于新页面。
      coachAnchors = coachAnchors.filterKeys { coachPageOf(it) == coachPage }
    }
    val reportCoachAnchor: (Int, Rect) -> Unit = { index, rect ->
      if (coachPageOf(index) == coachPage) {
        coachAnchors = if (rect.isEmpty) {
          coachAnchors - index
        } else if (coachAnchors[index] != rect) {
          coachAnchors + (index to rect)
        } else {
          coachAnchors
        }
      }
    }

    LaunchedEffect(completed) {
      if (completed && !notificationPermissionState.inFlight &&
        UpdateNotificationManager.shouldRequestPermission(this@MainActivity)
      ) {
        notificationPermissionState.inFlight = true
        try {
          notificationPermissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        } catch (failure: Exception) {
          notificationPermissionState.inFlight = false
          android.util.Log.w("MainActivity", "Notification permission request failed", failure)
        }
      }
    }
    Box(Modifier.fillMaxSize()) {
      MainNavigation(
        coachPage = if (completed) null else coachPage,
        onCoachAnchorChanged = if (completed) { _, _ -> } else reportCoachAnchor,
      )
      if (!completed) {
        OnboardingCoachOverlay(
          step = safeCoachStep,
          homeStyle = homeStyle,
          saving = saving,
          saveFailed = saveFailed,
          targetBounds = coachAnchors[safeCoachStep],
          onNext = {
            if (safeCoachStep == CoachAnchor.SETTINGS) {
              if (!saving) saveRequest += 1
            } else {
              coachStep = (safeCoachStep + 1).coerceAtMost(CoachAnchor.SETTINGS)
            }
          },
          onSkip = {
            if (!saving) saveRequest += 1
          },
        )
      }
    }
  }
}

/** Retains an in-flight request across rotation; a new process may retry an unanswered request. */
internal class NotificationPermissionRequestState : ViewModel() {
  var inFlight = false
}
