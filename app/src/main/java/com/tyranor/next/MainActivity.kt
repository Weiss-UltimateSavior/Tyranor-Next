package com.tyranor.next

import android.Manifest
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.core.settings.OnboardingWriteResult
import com.tyranor.next.ui.onboarding.CoachOverlayVisibility
import com.tyranor.next.ui.onboarding.OnboardingCoachOverlay
import com.tyranor.next.ui.common.CoachAnchor
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.tyranor.next.core.engine.plugin.EnginePluginBootstrap
import com.tyranor.next.ui.common.ProvideAppLocale
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.ui.common.PageTransitionBackground
import com.tyranor.next.core.privacy.AppLockController
import com.tyranor.next.ui.common.motion.skipDrawWhenHidden
import com.tyranor.next.ui.common.collectSettingAsState
import com.tyranor.next.ui.common.motion.LocalPageDepth
import com.tyranor.next.ui.common.motion.ProvideMotionEnvironment
import com.tyranor.next.ui.common.motion.ScreenRotationSync
import com.tyranor.next.ui.common.motion.SystemThemeSync
import com.tyranor.next.ui.common.motion.rememberPageDepth
import com.tyranor.next.theme.TyranorNextTheme
import com.tyranor.next.core.updater.UpdateNotificationManager
import com.tyranor.next.ui.common.observeSaveSyncFeedback
import com.tyranor.next.ui.common.privacy.FlagSecureEffect
import com.tyranor.next.ui.common.privacy.PrivacyLockGate
import com.tyranor.next.ui.common.privacy.blockAppInteractionKeys

private fun coachPageOf(index: Int): Int = when (index) {
  CoachAnchor.HOME -> 0
  in CoachAnchor.GAME_FOLDER..CoachAnchor.GAME_SCAN -> 1
  CoachAnchor.ENGINE -> 2
  else -> 4
}

// FragmentActivity（ComponentActivity 的子类）：应用锁 BiometricPrompt 要求宿主提供 FragmentManager
class MainActivity : FragmentActivity() {
  private val notificationPermissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

  private lateinit var systemThemeSync: SystemThemeSync
  private lateinit var screenRotationSync: ScreenRotationSync

  override fun onConfigurationChanged(newConfig: Configuration) {
    // 先截下旧主题 / 旧画面（平板横竖屏转动过渡），再让 Compose 接收新配置。
    systemThemeSync.onConfigurationChanged(newConfig)
    screenRotationSync.onConfigurationChanged(newConfig)
    super.onConfigurationChanged(newConfig)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    observeSaveSyncFeedback()
    systemThemeSync = SystemThemeSync(this)
    screenRotationSync = ScreenRotationSync(this)

    // 「显示首页」决定主导航条目与可见页集合，必须在首帧组合前同步加载：
    // 留到 MainScreen 的异步 init 会让冷启动先按默认值（关）绘制、随后再纠正，产生闪动。
    AppSettingsStore.initHomePageEnabled(this)
    // 首页样式同样在首帧前加载：应用锁的「跳过绘制」判定要知道当前是否 Web 首页
    // （WebView 靠原生绘制回调输出，不能被 skipDrawWhenHidden 罩住）
    AppSettingsStore.initHomeStyle(this)
    // 导航位置和样式共同决定首帧布局，组合前读取用户保存的值。
    AppSettingsStore.initSideRail(this)
    AppSettingsStore.initNavStyle(this)

    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
    )

    // 状态栏/导航栏透明沉浸由 enableEdgeToEdge(transparent) 处理，无需再设置已弃用的 window.statusBarColor

    setContent {
      // 防止截图 / 应用锁生效期间强制 FLAG_SECURE（窗口级，挂在主题包裹之外）
      FlagSecureEffect(this)
      ProvideAppLocale {
        TyranorNextTheme {
          // 系统栏图标跟随实际背景：深色模式/深色底图用浅色图标（SystemBarStyle.dark）
          val activity = this@MainActivity
          val dark = AppThemeColors.isDark || AppThemeColors.hasDarkBackdrop
          DisposableEffect(dark) {
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
            onDispose { }
          }
          ProvideMotionEnvironment {
            val depth = rememberPageDepth()
            // 应用锁生效时**整块页面（含根背景 / 粒子）**都不参与绘制：锁门自带完整背景，
            // 下层继续画只会让粒子 / 背景图多一份 30fps 重绘。
            // Web 首页例外：WebView 靠原生绘制回调输出，不能被 skipDrawWhenHidden 罩住
            // （见 PageVisibility.kt），启用 Web 首页时锁定时保留整块绘制。
            val locked by AppLockController.lockedState.collectSettingAsState()
            val homeStyle by AppSettingsStore.homeStyleState.collectSettingAsState()
            val homePageEnabled by AppSettingsStore.homePageEnabledState.collectSettingAsState()
            val pageDrawable =
              !locked || (homeStyle == AppSettingsStore.HOME_STYLE_WEB && homePageEnabled)
            CompositionLocalProvider(LocalPageDepth provides depth) {
              PageTransitionBackground(
                depth = depth,
                modifier = Modifier.fillMaxSize().skipDrawWhenHidden { pageDrawable },
              ) {
                Box(
                  Modifier
                    .fillMaxSize()
                    // 锁定时页面内容退出无障碍树（门自身语义保留）
                    .then(if (locked) Modifier.clearAndSetSemantics { } else Modifier)
                    // 外接键盘 / 手柄：无障碍屏蔽只挡语义，已聚焦的控件仍能被 Enter 激活；
                    // 锁定时拦下会操作页面的按键（Back 放行给锁门，音量 / 媒体等系统键放行）
                    .then(Modifier.blockAppInteractionKeys(locked))
                ) {
                  AppEntry()
                }
              }
            }
            // 应用锁解锁门：进程内覆盖层（性质同弹窗，不设顶栏），盖在页面内容之上
            PrivacyLockGate()
          }
        }
      }
    }
  }


  @Composable
  private fun AppEntry() {
    LaunchedEffect(Unit) {
      // 启动初始化后准备插件；失败时由独立快照保护首次安装判定不被基础设施数据改变。
      Thread {
        EnginePluginBootstrap.provisionIfNeeded(applicationContext)
      }.apply { isDaemon = true }.start()
    }
    var completed by remember { mutableStateOf(AppSettingsStore.isOnboardingCompleted(this)) }
    var coachStep by rememberSaveable { mutableIntStateOf(0) }
    val homeStyle by AppSettingsStore.homeStyleState.collectSettingAsState()
    val homePageEnabled by AppSettingsStore.homePageEnabledState.collectSettingAsState()
    var saving by remember { mutableStateOf(false) }
    var saveFailed by rememberSaveable { mutableStateOf(false) }
    var saveRequest by rememberSaveable { mutableIntStateOf(0) }
    var coachAnchors by remember { mutableStateOf<Map<Int, Rect>>(emptyMap()) }
    val safeCoachStep = coachStep.coerceIn(
      if (homePageEnabled) CoachAnchor.HOME else CoachAnchor.GAME_FOLDER,
      CoachAnchor.SETTINGS,
    )
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
      // 主界面常驻组合会保留隐藏页；切页时丢弃旧页坐标，避免把屏外矩形用于新页面。
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
      if ((!AppSettingsStore.ONBOARDING_FEATURE_ENABLED || completed) &&
        UpdateNotificationManager.shouldRequestPermission(this@MainActivity)
      ) {
        UpdateNotificationManager.markPermissionRequested(this@MainActivity)
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
      }
    }
    val onboardingVisible = AppSettingsStore.ONBOARDING_FEATURE_ENABLED && !completed
    Box(Modifier.fillMaxSize()) {
      MainNavigation(
        coachPage = if (onboardingVisible) coachPage else null,
        onCoachAnchorChanged = if (onboardingVisible) reportCoachAnchor else { _, _ -> },
      )
      CoachOverlayVisibility(visible = onboardingVisible) {
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
