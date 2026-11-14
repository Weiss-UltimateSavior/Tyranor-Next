package com.tyranor.next.ui.common

import android.graphics.Color
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.OnBackPressedCallback
import androidx.compose.animation.core.tween
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.GlassBackground
import com.tyranor.next.theme.TyranorNextTheme
import com.tyranor.next.theme.WithoutPressIndication
import com.tyranor.next.ui.common.motion.PageTransitions
import com.tyranor.next.ui.common.motion.PushStartLead
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.ProvideMotionEnvironment
import com.tyranor.next.ui.common.motion.SystemThemeSync
import com.tyranor.next.ui.common.motion.EntranceGate
import com.tyranor.next.ui.common.motion.LocalEntranceGate
import com.tyranor.next.ui.common.motion.LocalSheetBackdrop
import com.tyranor.next.ui.common.motion.PageDepth
import com.tyranor.next.ui.common.motion.PageEnter
import com.tyranor.next.ui.common.motion.depthFoldStack
import com.tyranor.next.ui.common.motion.rememberSheetBackdrop
import com.tyranor.next.ui.common.motion.MotionSprings
import com.tyranor.next.ui.common.motion.pageTransitionScrim
import com.tyranor.next.ui.common.motion.rememberPageDepth

/**
 * 二级页面统一宿主（P1-4）：收敛各 Activity 重复的
 * 「isDarkEffective → enableEdgeToEdge → 透明系统栏 → 主题包裹 → finish 转场」样板。
 *
 * 用法：Activity 继承 [AppScreenActivity]，在 `onCreate` 中校验入参后调用
 * [setAppScreenContent] 提供正文；页面自身需要的 Miuix 子主题照常在正文内声明。
 */
abstract class AppScreenActivity : ComponentActivity() {

    private var systemBarsDark: Boolean? = null
    private var closeTransitionMode: Boolean? = null

    private fun syncCloseTransition(enhanced: Boolean) {
        if (closeTransitionMode != enhanced) {
            PageTransitions.registerCloseTransition(this)
            closeTransitionMode = enhanced
        }
    }

    // ---- 从多任务 / 桌面切回后「立刻返回」：把返回放行到启动器的拉起动画结束之后 ----
    //
    // 根因（真机 logcat 实测）：从最近任务点回本应用时，系统用桌面的「远程转场」播放拉起动画（约 0.63s）；
    // 这期间本页 finish() 产生的 CLOSE 转场会被系统**合并进**这个还没结束的远程转场（`Transition was merged: (#CLOSE) into (#TO_FRONT)`），
    // 合并掉的转场没有任何动画——于是二级页一帧就消失，露出一级页。动画本身没写错，是返回发生得太早。
    // 所以回到前台后、拉起动画结束之前，系统返回先记下来，等 onEnterAnimationComplete（拉起动画真正结束，实测比远程转场结束晚约 3ms）
    // 再放行，之后系统照常播放返回动画；不依赖具体机型 / 系统的动画时长。信号万一没来，HotStartGuardTimeoutMillis 兜底放行。
    private val mainHandler = Handler(Looper.getMainLooper())
    private var resumeCount = 0
    private var childLaunched = false
    private var deferredBackPending = false

    private val hotStartBackGuard = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            deferredBackPending = true
        }
    }

    private val hotStartGuardTimeout = Runnable { releaseHotStartBackGuard() }

    /** 拉起动画结束（或兜底超时）：停止拦截，并把期间记下的那次返回交还给系统。 */
    private fun releaseHotStartBackGuard() {
        mainHandler.removeCallbacks(hotStartGuardTimeout)
        hotStartBackGuard.isEnabled = false
        if (deferredBackPending) {
            deferredBackPending = false
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun cancelHotStartBackGuard() {
        mainHandler.removeCallbacks(hotStartGuardTimeout)
        hotStartBackGuard.isEnabled = false
        deferredBackPending = false
    }

    /** 本页自己拉起了别的页面：之后的 onResume 是下层页面的正常返回（普通 CLOSE 转场），不是桌面拉起，不用等。 */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        childLaunched = true
        super.startActivityForResult(intent, requestCode, options)
    }

    override fun onResume() {
        super.onResume()
        // 首次 onResume 是本页自己的进场；之后没有拉起过子页就恢复前台的，只会是从多任务 / 桌面 / 别的应用切回
        val resumedFromOutside = resumeCount > 0 && !childLaunched
        resumeCount++
        childLaunched = false
        cancelHotStartBackGuard()
        if (resumedFromOutside && MotionMode.enhanced) {
            hotStartBackGuard.isEnabled = true
            mainHandler.postDelayed(hotStartGuardTimeout, HotStartGuardTimeoutMillis)
        }
    }

    override fun onEnterAnimationComplete() {
        super.onEnterAnimationComplete()
        releaseHotStartBackGuard()
    }

    override fun onPause() {
        // 又离开了前台：之前记下的返回已经没有意义
        cancelHotStartBackGuard()
        super.onPause()
    }

    override fun onDestroy() {
        cancelHotStartBackGuard()
        super.onDestroy()
    }

    private fun syncSystemBarStyle(dark: Boolean) {
        if (systemBarsDark == dark) return
        applySystemBarStyle(dark)
        systemBarsDark = dark
    }

    /** 系统深浅色变化时的自动换皮过渡（页面原地接收 uiMode 变化，不再被销毁重建）。 */
    private lateinit var systemThemeSync: SystemThemeSync

    /**
     * 本页自己进出的入场翻折进度（见 [PageEnter]）：进场由 [AppScreenScaffold] 在第一帧启动，退出在 [finish] 里倒放。
     * 被系统重建（旋转 / 折叠屏 / 字体变化 / 进程恢复）时没有窗口侧推，直接从「已就位」开始，不重播。
     */
    internal lateinit var pageEnter: PageEnter
        private set

    override fun onConfigurationChanged(newConfig: Configuration) {
        // 先保持旧主题并截下旧画面，再让 Compose 接收新配置
        systemThemeSync.onConfigurationChanged(newConfig)
        super.onConfigurationChanged(newConfig)
    }

    /** 在正文创建前同步动效档位与系统栏外观，使无效入参导致的提前返回也使用正确转场。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        systemThemeSync = SystemThemeSync(this)
        // 子类可能因参数无效而在 setContent 前 finish；返回动画同样必须使用持久化档位。
        AppSettingsStore.initMotionEnhanced(this)
        MotionMode.sync(AppSettingsStore.motionEnhancedState.value)
        syncCloseTransition(MotionMode.enhanced)
        PageTransitions.bindPushOwner(this)
        // 先于子类添加：子类里后加的返回处理（弹窗、未保存确认等）优先级更高，不受影响
        onBackPressedDispatcher.addCallback(this, hotStartBackGuard)
        // 「普通」档不播入场翻折（入场的协程直接返回），进度若还是 0，之后在本页里把「界面动效」切到「增强」时，
        // 翻折会按「还没进来」画出来：页面永远停在最大倾角（14°）并褪色（用户录屏复现过）。
        // 所以普通档创建的页面也从「已就位」开始；系统重建 / 恢复同理。必须在同步档位之后创建
        pageEnter = PageEnter(initial = if (savedInstanceState != null || !MotionMode.enhanced || transitionAnimationScale(this) == 0f) 1f else 0f)
        observeSaveSyncFeedback()
        // 深色背景（深色外观 / 复古玻璃 / 白雾玻璃）系统栏恒用浅色图标
        syncSystemBarStyle(
            AppSettingsStore.isDarkEffective(this) ||
                AppSettingsStore.getAppearanceStyle(this).hasDarkBackdrop,
        )
    }

    /** 统一主题包裹并提供页面正文。 */
    protected fun setAppScreenContent(content: @Composable () -> Unit) {
        setContent {
            // 应用设置可在本页切档；系统选择返回动画之前就要更新，而不是等手势提交。
            val enhanced = MotionMode.enhanced
            DisposableEffect(enhanced) {
                syncCloseTransition(enhanced)
                onDispose { }
            }
            // 外观模式/外观风格在页面内切换时（如应用设置页），系统栏图标实时跟随
            val dark = AppThemeColors.isDark || AppThemeColors.hasDarkBackdrop
            DisposableEffect(dark) {
                syncSystemBarStyle(dark)
                onDispose { }
            }
            AppScreenScaffold(content = content)
        }
    }

    /** 结束页面后应用当前档位的返回转场，与统一启动入口的进场配对。 */
    override fun finish() {
        syncCloseTransition(MotionMode.enhanced)
        // 退出：入场翻折原路倒放，与窗口的侧推退出同曲线同时长
        // 只在第一次 finish 时倒放（重复调用不重启动画）；系统动画倍率为 0（关闭动画）时直接归位
        if (!isFinishing) {
            if (MotionMode.enhanced) {
                val durationMillis = (MotionSprings.PushDurationMillis * transitionAnimationScale(this)).toInt()
                // 必须在页面组合的作用域里跑（带帧时钟）；lifecycleScope 没有帧时钟，animateTo 会直接抛异常崩进程
                pageEnter.playTo(0f, durationMillis)
            }
            // 下一层页面此刻就开始收回压暗 / 翻折（与窗口返回动画同一个起点，不等它 onResume）
            // 即使本页切到了普通档，也须消费它确实拥有的层；未登记的页面始终是空操作。
            PageTransitions.notifyPopStarted(this)
        }
        super.finish()
        // 返回：当前页向右整页退出，上一级从左侧回到原位（侧推的倒放）
        PageTransitions.applyPop(this)
    }
}

/**
 * 从桌面 / 多任务切回后，拦截返回的兜底时限：正常由 `onEnterAnimationComplete` 放行（实测约 0.63s 后触发），
 * 只有该信号没来（例如系统没有播放拉起动画）时才靠它，避免返回被一直压住。
 */
private const val HotStartGuardTimeoutMillis = 1200L

/** 系统「过渡动画缩放」（开发者选项）：0 = 关闭动画；上限 10。 */
private fun transitionAnimationScale(context: android.content.Context): Float =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f).coerceIn(0f, 10f)

/** 应用透明沉浸系统栏与深/浅图标（不透明背景由页面根组件向上延伸）。 */
private fun ComponentActivity.applySystemBarStyle(dark: Boolean) {
    enableEdgeToEdge(
        statusBarStyle = if (dark) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        },
        navigationBarStyle = if (dark) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        },
    )
}

/** Keep the scrim outside the Surface and its backdrop sources, but inside the theme reveal. */
@Composable
internal fun PageTransitionBackground(
    depth: PageDepth,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier, propagateMinConstraints = true) {
        GlassBackground(content = content)
        // Only the empty overlay observes the dim color. Keep the layer at identity: drawRect
        // still blends directly over the page, without fading or flattening its contents.
        Spacer(Modifier.matchParentSize().graphicsLayer { }.pageTransitionScrim(depth))
    }
}

/**
 * 二级页面统一内容壳：ProvideAppLocale → TyranorNextTheme → 页面根背景
 * （默认=页面背景色 / 玻璃=黑灰渐变，见 [GlassBackground]）→ 去除按压指示；
 * 正文即页面根内容。
 */
@Composable
fun AppScreenScaffold(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    ProvideAppLocale {
        TyranorNextTheme {
            ProvideMotionEnvironment {
                // 景深：推入下一页时本页随转场压暗并向后翻折、褪色，返回时复位（静止时不变）
                val context = LocalContext.current
                val depth = rememberPageDepth()
                // 侧推进场期间页面内容不再自己淡入 / 升起（见 EntranceGate）：窗口在滑入，内容保持实心
                val entranceGate = remember { EntranceGate(suppressed = true) }
                val transitionScale = remember(context) { transitionAnimationScale(context) }
                val pageEnter = (PageTransitions.findActivity(context) as? AppScreenActivity)?.pageEnter
                // 退出倒放（Activity.finish）要用页面组合的作用域，因为只有它带帧时钟
                val enterScope = rememberCoroutineScope()
                DisposableEffect(pageEnter, enterScope) {
                    pageEnter?.scope = enterScope
                    onDispose { if (pageEnter?.scope === enterScope) pageEnter.scope = null }
                }
                LaunchedEffect(pageEnter) {
                    // 入场翻折：与窗口侧推同时开始（新页第一帧画出时）、同曲线同时长（按系统动画倍率缩放，倍率为 0 直接就位）
                    pageEnter ?: return@LaunchedEffect
                    if (!MotionMode.enhanced || pageEnter.progress.value >= 1f) return@LaunchedEffect
                    // 不再多等一帧：效果在组合之后立刻起步，第一帧（已画出）之后的下一帧就开始转正
                    // （原来再等一帧，页面已经滑进来两三帧了还保持最大倾角，像「没有立刻抬起」）
                    // 第一帧之前就已经 finish（无效入参等）：不再播放入场
                    if (PageTransitions.findActivity(context)?.isFinishing == true) return@LaunchedEffect
                    val durationMillis = (MotionSprings.PushDurationMillis * transitionScale).toInt()
                    if (durationMillis <= 0) {
                        pageEnter.progress.snapTo(1f)
                    } else {
                        // 先垫一点起步进度，窗口侧推从第一帧可见起，倾角就已经在转正
                        pageEnter.progress.snapTo(PushStartLead)
                        pageEnter.progress.animateTo(1f, tween(durationMillis, easing = MotionSprings.PushEasing))
                    }
                }
                LaunchedEffect(Unit) {
                    // 新页第一帧即将画出（窗口侧推即将开始）：让被盖住的下层页面此刻才开始压暗 / 翻折，与侧推、新页的入场翻折同一个起点
                    PageTransitions.notifyPushStarted()
                }
                LaunchedEffect(entranceGate) {
                    // 侧推窗口动画在新页第一帧画出之后才开始：先等第一帧，再按系统动画倍率等一个转场时长
                    withFrameNanos { }
                    delay((MotionSprings.PushDurationMillis * transitionScale).toLong() + 280L)
                    entranceGate.open()
                }
                // 弹窗 / 抽屉只带动正文居中下沉与褪色，根背景不动。
                val overlayBackdrop = rememberSheetBackdrop()
                val enhanced = MotionMode.enhanced
                val pageLayers = remember(enhanced, overlayBackdrop, depth, pageEnter) {
                    Modifier.depthFoldStack(overlay = overlayBackdrop, panel = null, push = depth, enter = pageEnter)
                }
                PageTransitionBackground(depth, modifier) {
                    WithoutPressIndication {
                        // 翻折只作用在内容上：页面根背景不动
                        Box(Modifier.fillMaxSize().then(pageLayers)) {
                            CompositionLocalProvider(
                                LocalEntranceGate provides entranceGate,
                                LocalSheetBackdrop provides overlayBackdrop,
                            ) {
                                content()
                            }
                        }
                    }
                }
            }
        }
    }
}
