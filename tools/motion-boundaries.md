# 动效边界离线检查

在仓库根运行以下命令。只需 Python 3.9+ 标准库，无需 Android SDK、Gradle、设备或网络。Android CI 的 `regression-checks` 在 checkout 后执行相同检查。

```sh
python3 -B tools/test_motion_boundaries.py
python3 -B tools/check-motion-boundaries.py
```

脚本检查当前工作树，不依赖 Git 基线；失败会报告仓库相对路径、行号和原因，退出码为 1。可用 `--root <checkout>` 检查另一份工作树。

| 边界 | 拦截 | 允许 |
| --- | --- | --- |
| 列表条目 | 直接调用 `animateItem` / `animateItemPlacement`；在同一组参数里手配 `fadeInSpec`、`placementSpec`、`fadeOutSpec`；直接拿 `ListItemPlacement.Layout.spec` / `Reflow.spec` | `listItemMotion()`、`listItemMotion(ListItemPlacement.Reflow)`、传 `enabled`、在两种公开变体中选择、叠加具名入场效果 |
| 业务页面 | 直接构造 `Animatable`、`animate*AsState`、`rememberInfiniteTransition`、`tween`、`spring`、进退场原语，直接调用 `MotionTokens` / `MotionSprings` 的规格函数，或使用隐含默认动画的 `AnimatedContent` / `AnimatedVisibility` / `Crossfade` | 调用具名效果、传状态/动作；现有归档 `AnimatedContent` 仅在显式使用已登记的 `archivePaneTransition` 时允许。普通布局、绘制、滚动 API 和单纯的类型/import 不会因名称里有 animate 而报错 |

列表规则覆盖 `app/src/main/{java,kotlin}/**/*.kt`，包括已登记的效果实现；由 `ui/common/motion/ListItemMotion.kt` 独占原始条目动画和变体配置。业务页面规则覆盖这两个源码根中 `com/tyranor/next/ui/` 下的 Kotlin 文件及 `MainActivity.kt`。动效实现文件在脚本的 `OWNERS` 中按完整相对路径登记；放进 `common/motion`、文件起名叫 `*Motion.kt` 都不会自动放行。登记的判据是「这个文件里确实持有动画原语（或直接的逐帧/绘制图层实现）」，不是「它放在动效目录」：`MotionMode`（档位标志）、`MotionHaptics`（触觉门面）、`MotionIntent`（意图枚举）没有原语，因此故意不登记，将来往里面加裸 `Animatable` 仍会被拦下。进入 `OWNERS` 等于整文件豁免、不再按业务页面规则检查，所以只登记确实持有原语或逐帧/图层实现的文件：`DialogDimStack`（叠层记账）、`SystemThemeSync`（系统深浅色接管）、`OvershootRoom`（布局余量）、`PageVisibility`（绘制门控，`drawWithContent` 但无动画）、`GridReflowMotion`（动画实体在 `Entrance.kt`，这里只有静态 alpha 层与排期）都不在其中。

`WaterfallReflowState` 保留登记，理由是它自己就有逐帧驱动：`withFrameNanos` 做 4ms 分片恢复、`graphicsLayer` 承担恢复期遮蔽与输入门控，属于「直接的逐帧/绘制图层实现」。注意这条判据是**人工判据**——脚本的 `PRIMITIVES` 只覆盖 `Animatable`／`animate*AsState`／`tween`／`spring`／`MotionTokens` 这类构造，`withFrameNanos` 与 `graphicsLayer` 都不在其中，所以对本文件是整文件豁免。也就是说：今后若在这里新增裸 `Animatable` 不会被门禁拦下，只能靠评审。`ui/common/OverlayDropdownPreference.kt` 同样在 `OWNERS` 里却已无原语（交互都走 `withRowPressSquish`／`BlurTextWith`），这是 `origin/main` 的存量登记，本 PR 未动它；同类存量请在单独提交里清理，不要和功能改动混在一起。给 `common/motion` 加新文件后按此判据决定登记，不要整目录照抄。

维护时先找已有 owner。例如归档普通档沿用的 220ms 淡入淡出归 `archivePaneTransition`，引擎的存储环归 `rememberStorageSweep`。若确实需要独立实现，登记实现文件，不登记调用它的业务页面。仍留在宿主的调用由 `EXISTING` 按**文件、函数、底层符号、最多次数**放行：目前只有 `MainScreen` 内一次 `updateTransition`，给增强档和普通档共享切页时钟。迁走时应删除该条目；引导退场已由 `CoachOverlayVisibility` 接管，因此 `MainActivity` 没有豁免。

保护的导航实现按文件完整路径排除。`MainScreen.kt` 中仅 `DefaultBottomNavigationBar` 的函数体排除，同文件后面的新增函数仍受检。这个门禁并不验证保护区内容是否被修改。

**局限：**这是源码规则，不是 Kotlin 编译器或类型检查器。它跳过注释和字面量，识别常见标识符、跨行调用、显式 import、别名、包前缀与常用星号 import。列表三参数按实参名组合识别，不推断接收者类型。它不跟踪变量、函数引用、反射、字符串模板中的代码、任意自制的逐帧循环，或其他框架的动画。Java、XML、引擎模块、其他 source set、运行时行为和实际帧率均不在覆盖范围内。已有调用的次数限制也不能证明其参数和普通档表现不变，这些仍需审查或运行验证。
