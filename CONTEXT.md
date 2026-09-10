# Tyranor Next — 领域上下文

基于 Tyranor 模拟器逆向重写、面向 Android 的多引擎视觉小说（Galgame）聚合启动器：识别并启动 KiriKiri / ONScripter / Tyrano / Artemis / RPG Maker / Ren'Py 等多类引擎编排的游戏，提供游戏库管理、封面获取、存档镜像、引擎参数调节。本上下文定义该领域内约定成俗的核心术语。

## 引擎与运行时

**引擎（EngineType）**:
游戏所属的运行时家族，扫描时按目录特征（脚本/资源文件）判定：KIRIKIRI、ONS、TYRANO、RPG Maker（RGSS 系列）、RPG_MV、RPG_MZ、VN、WebOther、ARTEMIS、RENPY、UNKNOWN。
_Avoid_: 游戏类型、格式

**内置引擎**:
随 App 分发、无需外置安装即可运行的引擎运行时（Kirikiroid2 / krkrsdl3、ONScripter、Artemis、Tyrano 网页壳）。RPG Maker MV/MZ 由内置 Web 运行环境承载。
_Avoid_: 预制引擎、捆绑引擎

**外置 APK 引擎模块（External Engine Module）**:
以独立 APK 形式分发、需用户安装/下载的引擎运行时（Ren'Py 8.5/7.7.1、RPG Maker XP/VX/VX Ace/mkxp-z），由注册表（ExternalEngineModuleRegistry）按引擎 + 版本解析目标模块，启动时通过 Intent 协议拉起。
_Avoid_: 插件、引擎 DLC

**引擎网页壳（Tyranor Web 运行环境）**:
内置 Web 运行时，承载 TyranoBuilder、WebOther、VN、RPG Maker MV/MZ 等网页形态游戏；各引擎共用同一颗网页壳版本（Tyranor-2.3.4，为原逆向 app 内置）。
_Avoid_: Chrome、NW.js 环境

**TynMV / TynMZ**:
RPG Maker MV / MZ 在 TyranorNext 中新引入的引擎版本代号（TynMV-0.1/0.2、TynMZ-0.1），区别于原逆向内置的网页壳版本。引擎页弹窗中重实现系列以「TyranorNext-0.1/0.2」合并显示，引擎设置下拉则以 Tyn 命名逐项列出。
_Avoid_: v0/v1/v2（旧编号）

**引擎内核（Kernel）**:
同一引擎家族下的可替换底层运行时（KiriKiri 家族：Kirikiroid2 与 krkrsdl3），可在引擎设置中选择。
_Avoid_: 渲染器、后端

**补丁 overlay（Patch Overlay）**:
启动 KiriKiri 游戏时为兼容 Steam 插件等场景生成的虚拟 `patch.tjs` overlay 目录；行为由「自动/强制/关闭」模式控制。
_Avoid_: 补丁、Hook

**autopatch 策略**:
Artemis 启动前对必要文件（system.ini、list_windows、movie 等）进行幂等修补的决策策略：「启动时询问 / 自动 / 关闭」，由共享确认弹窗承载。
_Avoid_: 自动补丁、提问开关

## 游戏库

**游戏扫描（EngineScanner）**:
对授权目录遍历并判定引擎类型、生成扫描候选（ScanGame）的过程；结果落库为游戏库。
_Avoid_: 导入、索引

**扫描候选（ScanGame）**:
一次扫描/加载产出的单条游戏记录：标题、目录 URI、引擎、启动目标，以及封面/元数据/外置模块别名等附属字段。
_Avoid_: 游戏条目、GameItem

**启动目标（launchTarget）**:
认定为主入口的启动文件路径（自动探测）；可被单游戏设置的「启动文件」（launchFile）手动覆盖。
_Avoid_: 入口文件（与 launchFile 混淆时）

**外置模块别名（externalModuleAlias）**:
同一引擎下不同子运行时的内部标识（如 `internal.rpgmxp`），用于解析具体外部模块；Ren'Py 改由单游戏版本设置决定，不写该字段。
_Avoid_: 模块 ID

**最近游戏 / 快捷启动**:
游戏库首页的两类聚合列表：最近打开（按时间戳展示）与用户固定（quickLaunch，数量上限由 DAO 常量约束）。
_Avoid_: 历史、收藏

**排序键（GameSortKeys）**:
游戏标题排序的双路径共用实现：小写化标题键 + 可选的【】/[]标签键；Room 预计算列与 UI 内存排序共用保证结果一致。
_Avoid_: 排序方式（与 GAME_SORT_* 枚举混用）

**已删清理（cleanupDeletedGame）**:
扫描后对磁盘上已不存在的游戏库记录执行移除的清理动作。
_Avoid_: 删除、清理

**游戏库门面（GameLibraryFacade）**:
从 EngineScanner 拆出的游戏库集成门面：持有游戏/最近/快捷/扫描根的内存缓存与修订号，提供同步「缓存先行、落库排队」写；EngineScanner 只保留扫描与引擎识别。
_Avoid_: Scanner 缓存、图库仓库（Repository 指落库层）

## 设置体系

**应用/引擎/单游戏三级设置**:
设置按作用域分三层：应用设置（AppSettingsStore，全局）、引擎设置（EngineSettingsStore，按引擎家族）、单游戏设置（PerGameSettingsStore，按游戏覆盖）；启动时单游戏覆盖先于全局生效。
_Avoid_: 设置优先级、全局设置

**生效设置解析（EngineSettingsResolver / EffectiveEngineSettings）**:
三级设置的统一解析入口：Resolver 从两个 Store 读取全局与单游戏覆盖，纯函数层 EffectiveEngineSettings 做白名单/内核回退等合并规则，启动与存档只消费 ResolvedEngineSettings 结果。
_Avoid_: 设置合并、覆盖层

**引擎设置种类（EngineSettingsKind）**:
引擎设置页的分类入口（KRKR / ONS / ARTEMIS / RPG_MAKER / TYRANO / RENPY），各自提供该引擎族专属参数项。
_Avoid_: 引擎设置 Tab

**导航样式（Nav Style）**:
底部导航的两种形态：「默认」（Material3 NavigationBar）与「圆角液态玻璃」（LiquidGlassNavigationBar）；内存态即时切换。
_Avoid_: 底部栏外观、主题

**外观模式（Appearance）**:
明/暗/跟随系统的主题选择；实际生效值以 `AppSettingsStore.isDarkEffective()` 为唯一事实源。
_Avoid_: 深色模式（仅指其一）

**三语言支持**:
zh / ja / en 三套 `strings.xml` 由工具强制键值一致（`checkHardcodedUiStrings`），禁止 Kotlin 源码硬编码 CJK 文案。
_Avoid_: 多语言、国际化（本项目专指三语言硬一致）

**引擎版本选项（Version Options）**:
引擎设置中的版本下拉项，以 `id → 显示名` 列表（auto + 具体版本）提供；跳转页/弹窗共用同一数据源保证同序。
_Avoid_: 版本列表（与弹窗条目混淆时）

## 封面

**封面来源（Cover Source）**:
获取封面的数据源（VNDB / Hikarinagi / Bangumi / Steam / Local / Custom），可自定义顺序；Hikarinagi 需 OAuth Client ID。
_Avoid_: 图源、壁纸源

**封面抓取（Cover Scraping）**:
为游戏库批量或按需获取封面元数据的流程（CoverScraperService + 批量任务管理器 + 来源聚合），仅覆写 coverUri/coverSource 等封面字段。
_Avoid_: 下载封面、封面更新

**手动绑定（Bind Candidate）**:
用户从候选封面中选择并落库单游戏封面的动作；VNDB 候选同时携带 vndbId / metadataTitle。
_Avoid_: 选封面、替换

**封面缓存（CoverImageCache / CoverThumbnailLoader）**:
封面缩略图的内存 + 磁盘持久化缓存与受限并发解码器；滚动感知解码配合批量回填避免滑动卡顿。
_Avoid_: 图片加载器（通用概念不入表）

## 存档与数据

**存档位置解析（Save Location）**:
按「引擎家族 + 单游戏 scoped 开关 + 可用性回退」解析游戏存档目录的规则（GameSaveManager.resolveSaveLocation）。
_Avoid_: 存档路径

**存档镜像（Save Mirror）**:
将游戏存档目录导出 / 导入为 zip 的备份能力（exportToZip / importFromZip），含文件数与体积上限保护。
_Avoid_: 备份、云同步

## 界面规范

**功能跳转条目（AppNavItem）**:
统一的「功能跳转列」组件（左侧图标 + 标题/摘要 + 右箭头），禁止手写 Row/Column 拼装；页面条目容器用 NavWhite（灰底白卡），弹窗内条目传 PageGrey（白底灰卡）与弹窗背景反色。
_Avoid_: ListItem、手写条目

**二级字号规范**:
条目正文用 bodyMedium（小字号），标题用 titleMedium Bold（标题字号）；QuickLaunchCard 游戏名因带背景封面与暗色蒙层豁免，用 titleLarge Bold。
_Avoid_: 随意字号、item 用 title

**液态玻璃导航（LiquidGlassNavigationBar）**:
悬浮于内容之上、采样页面内容做背景模糊的圆角导航形态（Android 12+，通过 backdrop 录制内容层）。
_Avoid_: 毛玻璃、透明导航

**四页常驻组合**:
主界面四个 Tab（首页 / 游戏 / 引擎 / 设置）常驻组合、隐藏页置 alpha=0 保留节点，切换仅做水平 alpha 动画避免重建列表。
_Avoid_: 懒加载页、Fragment

## 架构约定

**三层架构**:
`界面 UI 交互层（ui）→ 功能抽象层（core）→ 底层引擎层（engine）`的固定依赖方向；core 不得依赖 Compose 组件，engine 不得关心应用页面。
_Avoid_: 平铺包、循环依赖

**统一弹窗（AppAlertDialog）**:
UI 弹窗强制使用的组件（Material3 AlertDialog 封装），禁止直接使用原生 AlertDialog。
_Avoid_: AlertDialog、Dialog

**启动契约（LaunchContract）**:
engine 模块定义的 App → 引擎宿主 Intent extras 键与固定取值的唯一常量源；App 侧 EngineLauncher 与引擎侧宿主 Activity 必须双侧引用，禁止裸字符串。
_Avoid_: extras 约定、Intent 协议

**启动结果（LaunchResult）**:
引擎启动的统一 sealed 结果（Success / 类型化 Failure）；core 只给错误类型，本地化文案由 UI 层映射，异常场景用带错误码的 GameSaveException 类协议。
_Avoid_: 错误字符串、返回值文案

**页面宿主（AppScreenActivity / AppScreenScaffold）**:
二级页面统一宿主：收敛主题包裹（ProvideAppLocale → TyranorNextTheme → Surface）、边到边与透明系统栏、退出转场；页面正文经 setAppScreenContent 提供。
_Avoid_: BaseActivity、Scaffold 壳

**FIFO 持久化命令队列**:
两道串行队列：MainLibraryViewModel 的 Channel 队列串行化 UI 发起的游戏库命令（扫描/删除/搜索等），避免组合期磁盘读写与后发覆盖先发；GameLibraryRepository.writeScope 单线程写队列是所有持久化写（含 GameLibraryFacade 的同步门面写）的唯一落库串行化保证点。
_Avoid_: 线程池、异步竞态
