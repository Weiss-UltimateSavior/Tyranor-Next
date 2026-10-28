# Tyranor Next — 领域上下文

Tyranor Next 是面向 Android 的多引擎视觉小说（Galgame）聚合启动器。本上下文定义游戏库、运行环境及应用侧工具使用的项目术语。

## 引擎与运行时

**引擎（EngineType）**:
游戏库用于区分游戏运行时家族与外部启动类别的分类。
_Avoid_: 游戏类型、格式

**内置引擎**:
随 App 分发、无需外置安装的引擎运行时（Kirikiroid2 / krkrsdl3、ONScripter、Artemis、Siglus（siglus_rs Rust 运行时）、RealLive / AVG32 / UK2（siglus_rs game_launcher 软渲染运行时）、FVP（rfvp Rust 运行时）、System35（xsystem35）、System4（xsystem4，与 System35 共用 libSDL2x）、Ethornell / BGI（实验接入，兼容性未验收）、Tyrano 网页壳）。RPG Maker MV/MZ 由内置 Web 运行环境承载。
_Avoid_: 预制引擎、捆绑引擎

**Ethornell / BGI（ETHORNELL）**:
以 ethornell-rs 重实现承载的内置引擎家族，已接入扫描、启动与设置，目前仍属实验运行时，不能据此声明全家族可玩。App 存档管理尚未开放，不能把运行时用户数据与已验证的存档镜像或 PC 存档互通等同。
_Avoid_: Winlator BGI 插件、完整 BGI 支持

**System3.x / System4（XSYS35 / XSYS4）**:
AliceSoft 的两代引擎，各为独立的引擎类型（XSYS35 对应 System 3.x，XSYS4 对应 System 4）：目录特征、启动参数与设置互不相同，不合并为一类。
_Avoid_: System3/4、AliceSoft 引擎

**视觉小说引擎组（Framebuffer 引擎）**:
Siglus 之外由 game_launcher 运行库承载的三个软件渲染引擎（RealLive、AVG32、UK2）的统称：引擎逐帧输出 RGBA 帧缓冲，宿主 `FramebufferGameActivity` 拷进 Bitmap 呈现并转发指针/键盘/文本输入；文本编码（NLS）按全局/单游戏设置。
_Avoid_: 软渲染引擎、game_fb 引擎

**外置 APK 引擎模块（External Engine Module）**:
以独立 Android 应用形式分发、由用户另行安装的引擎运行时，用于承载 Ren'Py 与 RPG Maker RGSS 等游戏。
_Avoid_: 插件、引擎 DLC

**PC 游戏（手动添加）**:
用户手动加入游戏库、经外置 Winlator 运行的 Windows 程序，不限定为已识别的视觉小说引擎家族。其运行参数属于 Winlator 设置域。
_Avoid_: 未知引擎、Winlator 游戏

**安卓游戏（手动添加）**:
用户从已安装应用中手动加入游戏库的 Android 游戏记录，属于外部应用入口。
_Avoid_: 应用快捷方式、外置模拟器游戏

**外置模拟器跳转（External Emulator Jump）**:
由用户另行安装的独立模拟器或兼容运行环境承接游戏的启动方式，包括 PPSSPP、Eden 与 Winlator。
_Avoid_: 外置模块（指 APK 引擎模块）、内置引擎

**引擎网页壳（Tyranor Web 运行环境）**:
内置 Web 运行时，承载 TyranoBuilder、WebOther、VN、RPG Maker MV/MZ 等网页形态游戏；各引擎共用同一颗网页壳版本（Tyranor-2.3.4，为原逆向 app 内置）。
_Avoid_: Chrome、NW.js 环境

**TynMV / TynMZ**:
RPG Maker MV / MZ 在 TyranorNext 中新引入的引擎版本代号（TynMV-0.1/0.2、TynMZ-0.1），区别于原逆向内置的网页壳版本。引擎页弹窗中重实现系列以「TyranorNext-0.1/0.2」合并显示，引擎设置下拉则以 Tyn 命名逐项列出。
_Avoid_: v0/v1/v2（旧编号）

**引擎内核（Kernel）**:
同一引擎家族下的可替换底层运行时（KiriKiri 家族：Kirikiroid2 与 krkrsdl3），可在引擎设置中选择。
_Avoid_: 渲染器、后端

**SDL2 共享运行库（libSDL2x / app32）**:
System35 与 System4 共用的 SDL2 2.32.10 原生库（`libSDL2x.so`）与重命名 Java 层（`org.libsdl.app32`），与 ONS 的 SDL2 2.26.3（`libSDL2.so` / `org.libsdl.app`）并存、互不替代。
_Avoid_: SDL 补丁版、第二套 SDL

**补丁 overlay（Patch Overlay）**:
启动 KiriKiri 游戏时为兼容 Steam 插件等场景生成的虚拟 `patch.tjs` overlay 目录；行为由「自动/强制/关闭」模式控制。
_Avoid_: 补丁、Hook

**autopatch 策略**:
Artemis 启动前对必要文件（system.ini、list_windows、movie 等）进行幂等修补的决策策略：「启动时询问 / 自动 / 关闭」，由共享确认弹窗承载；**默认关闭**（`ART_PATCH_DEFAULT`，避免无弹窗路径下等价于自动补丁）。
_Avoid_: 自动补丁、提问开关

**标题回写（Title Feedback）**:
引擎宿主在引擎进程内解析游戏标题并写入共享 prefs（`<engine>_title.<pathHash>`），主 App 在库加载时条件导入为游戏标题；仅当标题仍等于启动前登记的目录名（未被用户改名）时覆盖；Siglus 与 System35 均使用该协议；System4 不使用该协议，标题在扫描期解析 ini 的 `GameName`。
_Avoid_: 自动改名、元数据抓取

**画质滤镜链**:
各引擎共用的画质处理装配方式，由用户按顺序组合修复、锐化与放大滤镜，并可移除单个滤镜或配置其参数；实际执行能力取决于所选内核的帧输出入口，单游戏自定义链在切换跟随全局时保留。
_Avoid_: Anime4K 模式（指整条链时）、固定超分预设

**滤镜预设分类**:
将可装配的滤镜按算法家族分组，带预设的家族提供二级选择，单滤镜直接添加；滤镜链保存具体预设或自定义参数，以及顺序。
_Avoid_: 滤镜组（指单个算法分类时）、分类节点

**滤镜贡献**:
一项可独立加入或移除的滤镜能力，包含具体预设、用户可调参数和展示标识；共享滤镜链按照已提供的贡献装配。
_Avoid_: 注册枚举、模型规模（指整个贡献时）

**游戏原始帧**:
游戏舞台合成后、按显示区域缩放前的画面，包含当时已合成的文本和界面；获取不到可信尺寸时，不将屏幕帧标为原始帧。
_Avoid_: 屏幕截图、显示分辨率（指原始输入时）

**尺寸限制舞台输出**:
以游戏逻辑像素尺度输出完整合成画面，再由显示层执行滤镜链并适配屏幕的接入方式；保留舞台外叠加内容时可包含画布留白，滤镜输入尺寸以实际处理画布为准。尺寸可信前的屏幕帧不计为游戏原始帧。
_Avoid_: 降采样截图、仅修改监测分辨率

**等比放大目标**:
按照输入帧比例、在当前屏幕表面范围内容纳的最大处理输出尺寸；适应模式直接使用该尺寸；固定原生倍率的滤镜可由显示层另行缩放。
_Avoid_: 固定两倍、分别拉伸宽高

**Artemis 画质增强**:
在受支持的 Artemis 内核已合成的完整舞台帧上执行画质滤镜链的能力；输入包含当时已合成的文本和界面，支持范围按内核版本区分。
_Avoid_: CNN 超分、素材替换

**Siglus 画质增强**:
在 Siglus 已合成、尚未按屏幕缩放的完整舞台帧上执行共用画质滤镜链的能力；输入包含文本和界面，舞台尺寸与素材分辨率分别理解。
_Avoid_: 素材超分、引擎替换

**画质性能监测**:
显示滤镜输入、实际处理输出和显示区域尺寸，以及显示帧率和可用的处理耗时；仅在当前内核支持且滤镜链启用时可选。
_Avoid_: 配置分辨率、引擎循环帧率


## 游戏库

**游戏扫描（EngineScanner）**:
对授权目录遍历并判定引擎类型、生成扫描候选（ScanGame）的过程；结果落库为游戏库。
_Avoid_: 导入、索引

**扫描候选（ScanGame）**:
一次扫描/加载产出的单条游戏记录：标题、目录 URI、引擎、启动目标，以及封面/元数据/外置模块别名等附属字段。
_Avoid_: 游戏条目、GameItem

**启动目标（launchTarget）**:
按引擎语义解释的启动载荷：自动探测的文件路径 / ROM 文件名 / 手动添加 PC 的目录哨兵 / 手动添加安卓游戏的包名；文件型入口可被单游戏设置的「启动文件」（launchFile）手动覆盖。
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
引擎设置页的分类入口（KRKR / ONS / ARTEMIS / RPG_MAKER / TYRANO / RENPY / SIGLUS / FRAMEBUFFER / FVP / XSYS35 / XSYS4 / ETHORNELL / PPSSPP / WINLATOR），各自提供该引擎族专属参数项。
_Avoid_: 引擎设置 Tab

**运行模式（Winlator Run Mode）**:
单游戏 Winlator 设置的三态取值：「跟随全局」（默认，直接用全局参数）、「跟随容器」（以全局为基线，清空六个图形/运行参数、固定不写回容器，容器 ID/名称先继承再覆盖）、「自定义」（按填写的参数覆盖）。模式键为 `winlator_mode`；旧记录按 1.61 的 `null / "" / 值` 语义推断后即以保存值为准（空串 = 显式不下发，与「未覆盖」不同）；切换模式保存不会删除另一种模式的已填值。参数行里的「按容器配置」是单项空值语义，不是运行模式。
_Avoid_: 全局/容器/自定义三档（易与外观档位混淆）、跟随容器配置（参数级文案）

**导航样式（Nav Style）**:
底部导航栏样式的四档取值：「经典 MD」（原「默认」，Material3 导航栏；玻璃系外观下为悬浮圆角玻璃条）、「液态玻璃 · 经典」（小圆角玻璃栏）、「液态玻璃 · 透镜」（折射透镜）、「悬浮按钮」；未手动选择时，仅默认外观采用透镜（Android 13 以下回退液态玻璃经典），复古/高级玻璃保留经典 MD 的玻璃条形态。手动选择优先于外观风格，切换外观不覆盖选择；平板/大窗口的侧边形态见「平板侧栏」。
_Avoid_: 底部栏外观、主题、默认导航（已更名为「经典 MD」）

**外观风格（Appearance Style）**:
应用设置的视觉风格选择：「默认」、「复古玻璃」（原「玻璃」）、「高级玻璃」三档；两档玻璃均使用深色背景、半透明毛玻璃容器与 0.5dp 描边，并禁用外观模式/色调切换（色调轮盘可用），以 `AppThemeColors.appearanceStyle` / `isGlass` 为唯一事实源。
_Avoid_: 玻璃主题、Theme（与导航样式/外观模式混用）

**复古玻璃（Retro Glass）**:
外观风格第二档（持久化值仍为 `glass`，显示名由「玻璃」更名）：黑灰三档竖向渐变 + 白色顶部光晕 + 主题色对角环境光；卡片为 80% 深色玻璃膜。
_Avoid_: 玻璃（单独出现时指本档）

**高级玻璃（Advanced Glass）**:
外观风格第三档（持久化值 `glass_advanced`）：背景为游戏封面 2×3 拼贴后的多轮盒式模糊底图（真实图片模糊，无封面时退化为主题色软色斑）+ 压暗 + 四角暗角；卡片为浅色磨砂膜（14% 白）+「上亮下暗」渐变描边 + 顶边高光 + 柔光投影（`glassShadow`，与悬浮默认导航条同款光学组合）；悬浮元素（默认导航条、液态玻璃底栏）在 Android 12+ 叠加真 backdrop 采样模糊，弹窗/抽屉等独立窗口不参与采样，面板底色改由 `rememberAdvancedGlassPanelSurface()` 从页面背景取色生成渐变（向深色基底压暗、保留少量透色，配遮罩）；抽屉渐变须画在内容层。
_Avoid_: 高级玻璃主题、液态玻璃（导航样式专属）

**平板侧栏（Side Rail）**:
平板/大窗口（sw600dp 或宽 ≥ 840dp）下主导航的侧边形态（应用设置「平板侧边栏」开关控制，默认开）：按外观风格取该主题「默认导航栏」形态（默认 = Material3 `NavigationRail`、复古/高级玻璃 = 悬浮玻璃柱），液态玻璃两档不适配侧栏（平板下降级为主题默认形态）；侧栏占布局宽度，`glassNavBottomInset()` 返回 0，高级玻璃用单独录制的纯背景层 `railBackdrop` 做真采样。
_Avoid_: 导航抽屉（NavigationDrawer）、侧边栏（与抽屉混淆时）

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
获取封面的数据源（VNDB / 未萌NextMoe / Hikarinagi / Bangumi / Steam / Local / Custom），可自定义顺序；Hikarinagi 需 OAuth Client ID，未萌NextMoe 匿名无登录。
_Avoid_: 图源、壁纸源

**封面抓取（Cover Scraping）**:
为游戏库批量或按需获取封面元数据的流程（CoverScraperService + 批量任务管理器 + 来源聚合），仅覆写 coverUri/coverSource 等封面字段。
_Avoid_: 下载封面、封面更新

**手动绑定（Bind Candidate）**:
用户从候选封面中选择并落库单游戏封面的动作；VNDB 候选同时携带 vndbId / metadataTitle（未萌NextMoe 候选命中 VNDB ref 时同样携带）。
_Avoid_: 选封面、替换

**两级封面选择（Cover Drill-down）**:
部分来源（未萌NextMoe）一个作品有多张封面时，先在作品候选列表选中作品、再从可获取的封面中挑选的流程；不支持该流程的来源点选候选即直接绑定。
_Avoid_: 二段选择、多图选择

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

**标准存档格式 / Tyranor 存档格式（RPG Maker MV/MZ）**:
同一份存档内容的两种文件名形态：标准格式（JoiPlay/PC 兼容，`global|config|fileN` + `.rpgsave`/`.rmmzsave`）与 Tyranor 格式。MV 的 Tyranor 格式为引擎确定性哈希名 `key_<sha256(键)>.bin`（键如 `RPG File3`）；legacy 名 `RPG FileN.bin` 引擎读取时优先但仍兼容。MZ 的键为纯 ASCII（`fileN`），引擎固定读写 `fileN.bin`，**不能**用哈希名。二者内容字节级一致、仅文件名不同；哈希名键空间有限，导出可反解回标准名。
_Avoid_: 存档版本、编码

**存档格式转化（RPG Maker MV/MZ）**:
启动 MV/MZ 时若在**引擎生效的存档目录**（独立存档关闭为 `<游戏根>/savedata`，开启为应用外部目录，与 engine 宿主 resolveSaveDirectory 同源；另兼容历史 `Savedata/`）检测到标准格式存档，询问用户后按文件名映射纯改名（不重编码）为 Tyranor 格式并写回生效目录；目标已存在则跳过不覆盖，源文件移入其所在目录的 `original/` 留底，不可反解的哈希存档保留原名。仅在应用层实现（engine 存档读写不变）。
_Avoid_: 转码、迁移（迁移专指目录位置变更）、写死 `<游戏根>/savedata`（独立存档默认开启，写死会让功能在默认配置下失效）

**存档互通（Save Interop）**:
开启后 MV/MZ 的存档在「标准侧 `<内容根>/save`」与「Tyranor 侧生效存档目录」（独立存档开关决定，检测/转化/同步三处同源）间双向自动同步：启动前与回到前台各同步一次（退出后 500ms 强杀进程、无回调，故以前台兜底并等待会话进程退出）。标准侧兼容 `save` / `Save` 两种拼写（同槽位内容一致视为等价副本，不一致把未选中副本隔离进 `deleted/`）；导入压缩包会剥掉外层文件夹包装。逐槽位较新者胜，mtime 相同须内容哈希一致才跳过；Tyranor 侧已删除的槽位把标准侧对应文件移入 `<标准侧>/deleted/`；标准侧缺失则重新导出。以应用私有同步清单区分「新建」与「已删除」，清单损坏即中止同步（不做任何文件改动）。由全局/单游戏开关控制，默认关。
_Avoid_: 云同步、备份（与存档镜像不同）

## 输入重映射

**输入重映射（Input Remap）**:
engine 模块 `com.core.input` 下的统一输入组件：把虚拟按键 / 物理手柄 /（后续）物理键盘等输入源归一为 canonical 键位空间（键盘段=Android keycode、1000 段=鼠标键、2000 段=功能动作），再经每宿主一份的 `InputSink` 注入目标引擎。Web 系宿主经 `InputRemapController.installWeb` 装配，取代原 `__touch_pad.js` 注入实现（该资产已删除）。
_Avoid_: 触屏手柄（旧称，指已下线的 JS 实现）、虚拟键盘（指按键层外观时用「虚拟按键」）

**虚拟按键（Virtual Pad）**:
叠加在游戏画面上的原生按键层（`VirtualPadView` 自绘 View）：按当前**按键方案**渲染按钮与方向控件，负责命中、按下态与编辑模式（拖拽 / 缩放 / 增删 / 改键 / 显隐），编辑 UI 亦为原生实现（`VirtualPadEditPanel`）。悬浮球点按切换显隐、长按进入编辑。
_Avoid_: 软键盘、IME、游戏自带按键

**按键方案（PadProfile）**:
一套虚拟按键布局（按钮列表 + 方向控件配置），文件存于 `<filesDir>/input/profiles/<id>.json`，游戏内编辑与设置页共用；坐标按全视口归一化（0..1，允许 -0.1..1.1 出界摆放），`size` 取屏幕短边比例。方案目录全 App 共享，因此旧触屏手柄的迁移产物用 `migrated-<游戏作用域>-*` 独立命名并按游戏写单游戏覆盖指向它；`default` 为内置方案（可编辑、不可删除）。
_Avoid_: 布局文件、preset（预设为旧实现术语）

**手柄映射（GamepadMap）**:
物理手柄 16 个逻辑按键（A/B/X/Y、START/SELECT、L1/R1、L2/R2、L3/R3、D-Pad 四向）与双摇杆四方向仿真 → 多键位输出（可含鼠标键）的映射表，文件存于 `<filesDir>/input/gamepad_map.json`；摇杆仿真带死区与滞回，`autoKeep` 为「按下保持」（再按一次释放）。宿主把事件交给 `InputRouter`，命中映射即消费。
_Avoid_: 控制器、JoyKey

**输入开关（input_pad_enabled / input_gamepad_enabled / input_profile_id）**:
虚拟按键、手柄映射与方案选择的全局/单游戏设置键：全局存 `tyranor_prefs`，单游戏覆盖存 `game_overrides` blob 同名字段（缺失=跟随全局）。「三处字面量锚定」只适用于这三**个**键（engine `InputConfigStore` 的 3 个 `KEY_*`、`EngineSettingsStore` / `PerGameSettingsStore`、`GameOverridePartitions`）；迁移标记 `input_legacy_migrated` 只在 app 侧定义。虚拟按键的全局默认按引擎分档（未显式设置时 MV/MZ 开、Tyrano/VN/WebOther 关），手柄映射默认对所有 Web 宿主开启。详见 `docs/输入重映射与手柄映射方案.md`。
_Avoid_: 触屏设置、按键开关

## 拆封包

**拆封包（Archive Unpack / Pack）**:
设置内独立工具页（`ui/archive`）：扫描所选目录下的 XP3 封包、主从预览条目并整体解包到归档同名文件夹（同名拒绝，不自动改名）；封包把所选目录压成同级同名 `.xp3`（0=明文存放，1–9 zlib 等级）。Rust 实现（`engine/rust`），与 Artemis 启动链的 `ArtemisPfsUnpacker`（仅启动补丁，纯 Kotlin）互不依赖。
_Avoid_: 解压（系统压缩包语义）、打包

**归档扫描（ArchiveScanner）**:
拆封包页对所选目录树的递归 XP3 探测：仅按扩展名 `.xp3` 判定，深度与数量有上限；真实路径优先（safUriToPath），映射失败退 DocumentFile 遍历。
_Avoid_: 游戏扫描（EngineScanner 语义）

**归档暂存（ArchiveStaging）**:
SAF `content://` 无法映射真实路径时，把输入拷入 `cacheDir/archive_staging` 中转、解包产物再写回目录树的桥接机制；页首清一次陈旧暂存。
_Avoid_: 缓存（泛化）

**KSD mode-2 隐性解码**:
XP3 小条目解包时顺带解开 Kirikiri KSD mode-2 内层包裹（`FE FE 02 FF FE` 头），使导出脚本文本可直接编辑；krkrsdl3 引擎 TextStream 原生支持该包裹，解开与否不影响游戏运行，无独立入口。
_Avoid_: KSD 解包功能（不存在独立功能）

## 界面规范

**性能分析器**:
可置于顶部或底部居中的单行浮层，显示输入与最终显示尺寸，滤镜简称和倍率置于按类别固定配色的占比条，默认后级缩放以相连的 Resize 灰色色卡呈现，数据按选定的预设间隔刷新；仅统计 GPU 用时，GPU 测量不可用时保留未取得数据的状态。
_Avoid_: 调试数据（指尺寸与接入状态诊断时）、CPU 耗时冒充 GPU 耗时

**功能跳转条目（AppNavItem）**:
统一的「功能跳转列」组件（左侧图标 + 标题/摘要 + 右箭头），禁止手写 Row/Column 拼装；页面条目容器用 NavWhite（灰底白卡），弹窗内条目传 DialogItemSurface（与弹窗背景反色，玻璃风格下为亮玻璃面）。
_Avoid_: ListItem、手写条目

**二级字号规范**:
条目正文用 bodyMedium（小字号），标题用 titleMedium Bold（标题字号）；QuickLaunchCard 游戏名因带背景封面与暗色蒙层豁免，用 titleLarge Bold。
_Avoid_: 随意字号、item 用 title

**标签栏（SlidingTabRow）**:
横向单选标签栏的统一组件：选中项为独立背景指示器，切换时 200ms 线性滑动（宽度同步动画）；等分宽度与内容宽度横向滚动两种形态；指示器颜色/圆角/描边按外观风格由组件内部分派，调用方不传色值；弹窗内由 AppAlertDialog 提供颜色上下文，指示器取动态 DialogItemSurface，与面板形成反差。引擎页玻璃系与首页「分类仓库」分类栏使用；新增标签/分类栏必须复用。
_Avoid_: 手写指示器、自拼 TabRow

**液态玻璃导航（LiquidGlassNavigationBar）**:
悬浮于内容之上、采样页面内容做背景模糊的圆角导航形态（Android 12+，通过 backdrop 录制内容层）；为「液态玻璃 · 经典」档。
_Avoid_: 毛玻璃、透明导航

**液态玻璃 · 透镜（Liquid Glass · Lens）**:
导航样式的第三档（旧称「液态玻璃增强」）：`ui/common/glass/` 下为本样式**独立实现**的三层采样 + 折射透镜 + 按压拖动，光学 / 运动参数经本项目实测调校后固化于 `GlassBottomBarSpec`；Android 13 以下不提供该选项，读取到该值时归一化为「液态玻璃 · 经典」。
_Avoid_: 液态玻璃 2.0、增强玻璃

**悬浮按钮导航（Floating Button Nav）**:
导航样式的第四档（`ui/common/FloatingGlassNavButton.kt`）：可拖动的 64dp 圆形液态玻璃主按钮，点击或长按后朝屏幕内侧沿圆弧展开导航项，手机与平板共用（API 31+ 采样，低版本实底回退）。交互、材质与触觉细则见 AGENTS.md「圆角豁免（悬浮按钮导航）」。
_Avoid_: FAB 导航、悬浮菜单

**四页常驻组合**:
主界面四个 Tab（首页 / 游戏 / 引擎 / 设置）常驻组合、隐藏页置 alpha=0 保留节点，切换仅做水平 alpha 动画避免重建列表。
_Avoid_: 懒加载页、Fragment

**首页样式（Home Style）**:
首页的三种形态：「默认」（最近打开 + 快捷启动）、「网页」（内置 WebView 展示用户所选地址：预设鲲Gal/一起萌或自定义）与「分类仓库」（顶栏下方固定横向可滚动分类栏：全部/快捷/最近/各引擎类型，内容复用游戏页同款默认网格，引擎分类只列库中实际存在的引擎）。由应用设置切换、即时生效；仅 http/https 在 WebView 内加载，http(s) 下载交系统浏览器，主框架非 http(s) 链接仅白名单 scheme（mailto/tel 等）交系统、其余拦截；Web 首页在首页不在前台或应用退后台时暂停 JS 与页面媒体，避免后台耗电。
_Avoid_: 主页、起始页

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
二级页面共用的界面宿主，统一页面的语言、主题、背景、系统栏与退出行为。
_Avoid_: BaseActivity、Scaffold 壳

**FIFO 持久化命令队列**:
两道串行队列：MainLibraryViewModel 的 Channel 队列串行化 UI 发起的游戏库命令（扫描/删除/搜索等），避免组合期磁盘读写与后发覆盖先发；GameLibraryRepository.writeScope 单线程写队列是所有持久化写（含 GameLibraryFacade 的同步门面写）的唯一落库串行化保证点。
_Avoid_: 线程池、异步竞态
