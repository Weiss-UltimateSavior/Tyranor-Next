package com.tyranor.next.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow

/**
 * 设置状态流（`AppSettingsStore.*State` 等）在界面侧的**统一订阅入口**：随生命周期订阅（页面 STARTED 以上才收集）。
 *
 * 为什么不用 `collectAsState()`：设置页是另一个 Activity，盖在主界面之上时主界面只是 STOPPED，
 * 但它的组合并没有停——设置页里每拨一个开关，后台的主界面都会**立刻**按新值重组
 * （增删首页、增删引擎页四个分页、重排游戏卡片……），这些活跑在同一个主线程上，把设置页开关的动画挤掉，
 * 表现为「有的开关卡、有的不卡」（卡的恰好是主界面重组重的那几个：实测显示首页 99 分位 57~93ms、引擎页分类 32~46ms，
 * 改后都降到 6ms）。随生命周期订阅后，主界面在后台不再重组，返回时再用最新值组合——
 * 变化带着各自的过渡动画在返回主界面时出现（如引擎页分类栏的弹簧展开 / 收起）。
 *
 * 规则：界面里读取设置类 StateFlow 一律用它，不要裸用 `collectAsState()`；设置页自己的页面也用它（STARTED 时与前者等效）。
 * 例外：必须在后台也立刻生效的（界面语言 [ProvideAppLocale]、动效档 `ProvideMotionEnvironment`，导航样式 / 导航按钮位置在受保护的导航文件里）继续用 `collectAsState()`。
 */
@Composable
fun <T> StateFlow<T>.collectSettingAsState(): State<T> = collectAsStateWithLifecycle()
