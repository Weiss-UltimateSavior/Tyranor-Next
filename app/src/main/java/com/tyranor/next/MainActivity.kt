package com.tyranor.next

import android.Manifest
import android.graphics.Color
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import com.tyranor.next.core.engine.plugin.EnginePluginBootstrap
import com.tyranor.next.ui.common.ProvideAppLocale
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.TyranorNextTheme
import com.tyranor.next.core.updater.UpdateNotificationManager

class MainActivity : ComponentActivity() {
  private val notificationPermissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

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
          Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { MainNavigation() }
        }
      }
    }
  }
}
