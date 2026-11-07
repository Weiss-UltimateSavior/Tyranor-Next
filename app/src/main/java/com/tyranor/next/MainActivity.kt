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
import com.tyranor.next.core.engine.plugin.EnginePluginBootstrap
import com.tyranor.next.ui.common.ProvideAppLocale
import com.tyranor.next.theme.AppThemeColors
import androidx.compose.ui.Modifier
import com.tyranor.next.theme.GlassBackground
import com.tyranor.next.ui.common.motion.ProvideMotionEnvironment
import com.tyranor.next.ui.common.motion.pageTransitionScrim
import com.tyranor.next.theme.TyranorNextTheme
import com.tyranor.next.core.updater.UpdateNotificationManager
import com.tyranor.next.ui.common.observeSaveSyncFeedback

class MainActivity : ComponentActivity() {
  private val notificationPermissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    observeSaveSyncFeedback()

    if (UpdateNotificationManager.shouldRequestPermission(this)) {
      UpdateNotificationManager.markPermissionRequested(this)
      notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // 首启自动安装引擎原生插件（幂等，放在后台线程避免首次复制阻塞 UI）
    Thread {
      EnginePluginBootstrap.provisionIfNeeded(applicationContext)
    }.apply { isDaemon = true }.start()

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
          // 动效环境（Q 弹越界回弹）+ 推入二级页时本页压暗（静止时透明）
          ProvideMotionEnvironment {
            GlassBackground(Modifier.pageTransitionScrim()) { MainNavigation() }
          }
        }
      }
    }
  }
}
