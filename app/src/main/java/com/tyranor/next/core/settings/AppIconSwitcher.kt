package com.tyranor.next.core.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * 启动器图标切换：Android 不支持运行时直接替换应用图标，manifest 中为新/旧图标各声明一个
 * `activity-alias`（`.LauncherDefault` / `.LauncherClassic`），这里通过启用目标别名、禁用另一个
 * 来切换桌面入口。
 *
 * 注意：切换会通知系统桌面刷新；部分 OEM 桌面可能短暂移除/重新添加入口，桌面位置或文件夹归属
 * 可能被重置，属于桌面实现差异，应用侧无法规避。
 */
object AppIconSwitcher {
    // Manifest aliases use ${applicationId}; their class names follow the installed
    // package, including isolated builds. The target Activity keeps the source namespace.
    private const val ALIAS_DEFAULT = ".LauncherDefault"
    private const val ALIAS_CLASSIC = ".LauncherClassic"

    /** 按设置启用对应别名；幂等，启动时调用一次可纠正升级或切换失败导致的不一致。 */
    fun apply(context: Context, useClassicIcon: Boolean) {
        val packageName = context.packageName
        val defaultAlias = ComponentName(packageName, packageName + ALIAS_DEFAULT)
        val classicAlias = ComponentName(packageName, packageName + ALIAS_CLASSIC)
        setEnabled(context, if (useClassicIcon) classicAlias else defaultAlias, true)
        setEnabled(context, if (useClassicIcon) defaultAlias else classicAlias, false)
    }

    private fun setEnabled(context: Context, component: ComponentName, enabled: Boolean) {
        val packageManager = context.packageManager
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        // 只在状态确实需要变化时写入，避免每次启动都触发桌面刷新
        if (packageManager.getComponentEnabledSetting(component) != state) {
            packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
        }
    }
}
