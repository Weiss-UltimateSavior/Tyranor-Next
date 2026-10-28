# Specification

本规格描述 Tyranor Next 当前代码的可见行为与验收边界，用于开发与回归，不承诺所有识别到的游戏均可运行。模块实现见 [ARCHITECTURE.md](ARCHITECTURE.md)，术语与界面规范见 [CONTEXT.md](CONTEXT.md)、[AGENTS.md](AGENTS.md)。

## 1. 产品与平台范围

- Android 多引擎视觉小说聚合启动器，提供游戏库、运行环境选择、封面、存档和维护工具；不分发商业游戏资产。
- 最低 Android 8.0 / API 26；当前 Native 产物仅 `arm64-v8a`。最低系统版本不等于每个运行时在该版本和所有 GPU 上都已通过验收。
- 游戏通常通过 SAF 授权目录；多数原生/外置 APK 启动仍需要可解析的真实路径，部分共享存储需要“所有文件访问”权限。ROM 跳转支持 SAF 内容 URI；安卓游戏不解析目录。
- 应用提供中文、日文、英文；app 与 engine 两个模块各自的字符串键在中文、日文、英文资源中保持一致。游戏自身脚本语言与文本编码由引擎及单游戏设置决定。

## 2. 导航与外观

| 能力 | 当前行为 |
| --- | --- |
| 主导航 | 首页 / 游戏 / 引擎 / 设置四页，保留页面组合与列表状态；二级页系统返回采用统一转场 |
| 首页 | 默认原生首页（最近游戏 + 快捷启动）、网页首页、分类仓库三种；分类仅列出库中已有引擎 |
| 网页首页 | 内嵌 http/https 页面，下载交外部浏览器；非 http(s) 主框架跳转受 scheme 白名单限制；离开首页或应用退后台时暂停 JS/媒体 |
| 外观 | 默认、复古玻璃、高级玻璃；默认风格支持明/暗/跟随系统、色调切换与可关闭的主题渐变；玻璃风格固定深色，外观模式与色调切换禁用，主题色仍可调 |
| 导航样式 | 经典 MD、液态玻璃经典、液态玻璃透镜、悬浮按钮；未手选时默认外观取透镜，API < 33 回退经典液态玻璃；玻璃风格默认经典 MD |
| 平板 | sw600dp 或宽 ≥ 840dp 且侧栏开关启用时使用侧栏；默认启用。悬浮按钮保留圆球导航，其余样式按主题侧栏规则处理 |

页面统一为顶栏在上、正文在下的上下分离布局（Material 页为 `AppTopBar` + `Column`，Miuix 页为透明 `MiuixScaffold` + `AppTopBar`），不启用顶栏模糊叠层。全屏按键编辑器固定横屏、不设顶栏，其它页面使用公共顶栏、输入框、标签栏与弹窗组件。

## 3. 游戏库

来源：[EngineScanner](app/src/main/java/com/tyranor/next/core/game/scan/EngineScanner.kt)、[GameLibraryDao](app/src/main/java/com/tyranor/next/core/game/storage/GameLibraryDao.kt)、`ui/game/`。

- 用户添加扫描根后递归识别游戏；默认扫描深度 3，可设置 1–5。支持差量扫描与重新扫描，手动添加的 PC/安卓记录在重扫后保留。
- 游戏身份、引擎、目录 URI、启动目标与封面元数据落入 Room；记录既可来自目录，也可来自单个 ROM。无法识别为受支持引擎的目录不作为普通扫描候选入库。
- 游戏页提供搜索、排序、卡片样式、改名、封面操作、启动文件选择及适用的单游戏设置/存档操作；可清理已不存在的游戏记录。
- 快捷启动最多 **3** 项；最近游戏最多读取 **20** 项。桌面快捷方式经独立入口回到统一启动链。
- PC 游戏由用户选择目录和启动 exe 手动添加；安卓游戏从已安装可启动应用选择，以 `androidapp://<包名>` 标识，卸载后启动返回缺失结果。

## 4. 引擎识别与运行路线

来源：[EngineType](app/src/main/java/com/tyranor/next/core/engine/EngineType.kt)、`EngineScanner`、[EngineLauncher](app/src/main/java/com/tyranor/next/core/game/launch/EngineLauncher.kt)。下列特征为摘要；真实判定还包括文件组合、内容头和优先链，不能仅凭扩展名认定所有游戏兼容。

| 引擎/来源 | 典型特征或添加方式 | 运行路线 |
| --- | --- | --- |
| Kirikiri | `.xp3`、`startup.tjs` | 内置 Kirikiroid2 / krkrsdl3 |
| ONScripter | `nscript.dat`、`.nsa` 等 | 内置 ONS |
| Artemis | `system.ini`、`.pfs` 等组合 | 内置多版本宿主与补丁策略 |
| Siglus | `Gameexe.dat/ini`、`Scene.pck` | 内置 Siglus |
| RealLive / AVG32 / UK2 | `SEEN` 结构/头、`UK2.CFG` 与 `.MES` 等 | 内置 game_launcher + Framebuffer 宿主 |
| FVP | `.hcb/.bch` 脚本与资源包 | 内置 rfvp |
| System35 / System4 | ALD、System39 特征 / `System40.ini`、`AliceStart.ini` | 内置 xsystem35 / xsystem4，两类独立识别 |
| Ethornell / BGI | BGI 特征与资源组合 | 内置实验宿主，见第 10 节 |
| Tyrano / VN / WebOther | `tyrano/`、`globalData.vndata`、HTML 等 | 内置 Web 壳 |
| RPG Maker MV / MZ | `rpg_core.js` / `rmmz_core.js`、`www/` 等 | 内置 Web 运行环境，支持适用的 Asar 包 |
| RPG Maker RGSS | XP/VX/VX Ace 资源或 mkxp-z 模块选择 | 外置 RPGM APK 模块 |
| Ren'Py | `.rpa`、`.rpy/.rpyc` 等组合 | 外置 Ren'Py APK；自动或指定 8.5 / 7.7.1 |
| YU-RIS / CatSystem2 | YPF/DLL/配置或 INT/CST/HG3 等组合 | 外置 Winlator |
| PC / 安卓游戏 | 手动添加目录+exe / 应用包名 | 外置 Winlator / 直接启动应用 |
| PSP / Nintendo Switch | `.pbp/.cso/.iso/.chd` / `.nsp/.xci/.nca/.nro` | 外置 PPSSPP（标准/黄金）/ Eden，一 ROM 一条记录 |

外置模块与模拟器必须先安装；主 App 显示安装状态及获取入口。启动前检查目标、权限和目录，失败由类型化结果映射 UI 文案。设置中的“自动”按当前运行时规则解析，不承诺回退能修复不受支持的脚本或资源。

外置模拟器跳转使用显式组件启动目标应用：YU-RIS / CatSystem2 / PC 通过 Winlator 的 `dir_path` 与 `exe_path` 传递游戏目录和主程序；PSP / Nintendo Switch 则传递 ROM URI 及读取授权。具体协议见 [外置模拟器接入说明](docs/外置主机模拟器跳转接入方案.md)。

MV/MZ 的 Tyranor-2.3.4 与 TynMV/TynMZ 宿主为 DKTools 的 `MODE_NWJS_STAMP` 浏览器模式提供缺失文件索引兼容。游戏已有 `data/Stamp.json` 时继续交给原插件读取；缺失时使用会话内只读目录索引，支持磁盘及 ASAR 资源，不写回游戏文件。其他模式保留原行为，其他网页游戏不启用该兼容端点。

## 5. 设置、输入与图形

- 应用设置、引擎设置、单游戏设置按作用域分离；单游戏未覆盖的字段跟随引擎全局设置，经 `EngineSettingsResolver` 统一解析。
- Winlator 全局与单游戏图形加速支持跟随容器配置、DXVK、WineD3D（跟随容器渲染器）、WineD3D + OpenGL / GDI。显式 GL/GDI 预设通过既有 `dxwrapperConfig` 整项替换 WineD3D 配置，其它子参数用接收端默认值；可与 Box64 同时下发，开启写入容器配置时持久化。旧 WineD3D 不迁移，空值与继承语义不变；需支持该协议的 winlator-cn。
- 单游戏 Winlator 设置提供三种运行模式：跟随全局（默认）、跟随容器、自定义。跟随容器以全局设置为基线，仅清空六个图形/运行参数，容器 ID/名称先继承全局、再由逐游戏值覆盖。**模式推断**按 1.61 的 `null / "" / 值` 语义（空串 = 显式不下发，不等同于未覆盖）；切换运行模式不会清除另一种模式已填的参数（以 `winlator_mode` 为准，切回可恢复）；「跟随容器」是本版本新增的显式语义，六空记录升级后 `save` 固定为 `false`（1.61 会继承记录或全局的 `save`，差异只在是否把配置写回容器），保存过的模式以保存值为准。
- 容器匹配遵循「ID 优先于名称」：两者都覆盖时以 ID 为准；**只覆盖名称时会清除继承的全局 ID**（置 0 不下发），否则继承的 ID 会让名称覆盖失效。该行为是 1.61 未定义、本版本明确的新规则。
- 不同引擎提供适用的内核/版本、编码、字体、渲染、存档位置或兼容参数；PC 游戏支持 Winlator 全局与单游戏参数覆盖，安卓游戏不提供引擎参数配置。
- Web 宿主支持统一虚拟按键与手柄映射、按键方案导入/导出与全屏编辑；方案按归一化视口保存。默认虚拟按键 MV/MZ 开、Tyrano/VN/WebOther 关；Web 手柄映射默认开。
- 共用画质滤镜链最多 **8** 节点，支持添加、移除、排序与参数配置；单游戏切换跟随全局后保留自定义链。
- 图形能力按内核、库身份及渲染器判断：Kirikiroid2 1.2.6/1.3.4/1.3.9、krkrsdl3 OpenGL、受支持的官方 Artemis 与 Siglus 走各自接入；krkrsdl3 软件渲染与自研 Artemis 当前不提供同等滤镜入口。
- 滤镜处理已合成画面（可能含文本和 UI），不承诺文本排除。性能分析器仅在支持且滤镜生效时可用；GPU 时间缺失时不得显示 CPU 时间充数。

具体范围与验证记录见 [共用画质滤镜链](docs/graphics-filters.md)；它不代表所有游戏与第三方插件都已认证。

## 6. 封面与元数据

来源：`core/cover/`、`ui/cover/`、`ui/game/`。

- 在线来源为 VNDB、未萌 NextMoe、Hikarinagi、Bangumi、Steam，另支持本地封面与自定义选择；来源可排序和启停。
- 批量抓取按启用来源顺序处理，支持仅补充缺失封面；任务跨 Tab 持续运行，逐游戏发布结果与失败/跳过统计。
- 手动搜索候选后绑定封面；NextMoe 的作品可进一步选择多张封面。适用候选携带 VNDB 关联 ID/元数据标题。
- 抓取不得覆盖无关游戏字段；System4 在本地与在线均缺封面时可用图标兜底。
- Hikarinagi 需要配置 OAuth Client ID 并完成授权；凭据由 Keystore 加密存储。离线、来源故障与未授权应显示对应结果，不把空搜索结果等同成功绑定。

## 7. 存档与互通

来源：[GameSaveManager](app/src/main/java/com/tyranor/next/core/game/save/GameSaveManager.kt)、`core/game/save/`。

- 按引擎和生效设置解析目录，为支持的引擎提供存档镜像导出、导入与删除。独立存档、Kirikiri 镜像等各有规则，不统一硬编码为 `<游戏根>/save`。
- ZIP 导入检查路径安全，最多 **20,000 个条目（含目录）**、展开内容 **1 GiB**；MV/MZ 可剥除最多 **3 层**单目录包装。适用的会话/操作门禁阻止运行中冲突。
- **YU-RIS 为外置跳转中的存档例外**：统一管理其游戏根 `save/`。PSP、Switch、CatSystem2、PC、安卓游戏、Ren'Py、RPG Maker RGSS、VN 与 WebOther 当前不提供该统一文件存档接口；外置启动不意味着所有引擎都不能管理存档。
- MV/MZ 标准格式与 Tyranor 格式仅作文件名映射，不重新编码内容；MV 支持哈希名及历史键名，MZ 使用 `fileN.bin` 等 ASCII 键名。
- 启动时发现适用标准存档可询问转化，已有目标不覆盖，原件留在 `original/`。
- MV/MZ 存档互通默认关闭，支持全局/单游戏设置；开启后启动前与回前台进行双向同步，并等待引擎会话结束。按槽位比较时间与哈希；删除副本隔离到 `deleted/`，同步清单损坏时中止文件修改。
- Ethornell 运行时用户数据不等于已开放存档镜像；当前 App 存档入口仍关闭。

## 8. 补丁与 XP3 拆封包

- KRKR 提供在线补丁列表、过滤及应用能力；启动兼容 overlay 与在线下载是不同机制。
- Artemis 支持启动补丁策略（询问/自动/关闭）与手动补丁动作；默认关闭，自动修补须幂等。
- 独立拆封包工具递归发现 XP3，当前扫描上限 **500** 个、深度上限 **6**；支持条目预览、进度与取消。
- 解包到归档同名目录，遇到同名目标拒绝，不自动改名覆盖；封包使用 0（不压缩）或 1–9（zlib）等级。已有目标的处理由页面冲突检查/保存流程决定，不把底层覆写能力当作无条件允许覆盖。
- 无真实路径时通过 SAF staging 中转；Rust 库缺失返回明确错误。KSD mode-2 小条目可随解包隐性解码，不单独提供入口，也不承诺绕过任意 XP3 加密。

## 9. 授权与应用更新

Hikarinagi OAuth 支持授权、回调校验及 token 刷新。应用检查 GitHub Releases，提供正式/预发布候选、下载与系统安装器跳转；后台任务在应用进入后台时安排，并受网络和系统调度限制，不保证固定时间完成。通知及未知来源安装受系统授权控制；失败不应阻断离线游戏库功能。

## 10. 实验能力与证据边界

DKTools 索引兼容的自动化验证覆盖合成目录、packed/unpacked ASAR、鉴权、原索引优先与模式隔离；本地样本 DKTools 11.2.6 配置为 mode 0。该证据不等于 Android 实机运行通过，也不代表所有 DKTools 版本或所有 MV/MZ 插件已兼容。首次索引耗时和游戏画面、语言、存读档仍需按目标设备与样本单独验证。

Ethornell 已接入扫描、设置与内置宿主，但真实游戏存读档及完整兼容尚未闭环。`EthornellSaveCapability` 当前 `DEVICE_SAVE_IO_VERIFIED=false`、`HOST_DISPATCH_PROTOCOL_VERIFIED=true`，故 `available=false`。不因合成夹具、ABI 加载、像素检查或 release probe 通过而开放管理。

既有 [验收记录](docs/Ethornell测试验收记录.md) 只对其标注的产物/设备/样本有效；API26 GPU、16KB 页、字体、音频、电影及长稳需分别判断。本轮没有重新进行设备验证。预编译来源与哈希检查不能证明所有商业游戏可玩。

## 11. 回归验收清单

下表是修改相应功能时要执行的验收项，不是本轮已通过记录。命令与测试入口见 [AGENTS.md](AGENTS.md#构建)。

| 改动领域 | 验收场景 |
| --- | --- |
| 扫描/存储 | SAF/真实路径、深度边界、差量重扫、手动记录保留、旧 prefs 导入及空库修复 |
| 游戏库 UI | 搜索防抖与旧结果拒绝、快捷 3 项限制、最近记录、跨 Tab 封面同步 |
| 顶栏/导航 | 顶栏与正文上下分离、不叠加顶栏渐进模糊；主四 Tab 反复切换后内容与选中项一致；列表滚动不穿顶栏；游戏搜索框、分页/分类条位置正确；手机/平板、深浅色；复古/高级玻璃风格 |
| 启动/设置 | 缺权限/文件/模块、生效覆盖、取消后不启动、成功发起与首帧区分 |
| Winlator 运行模式 | 跟随全局/跟随容器/自定义三分支、`null / "" / 值` 迁移推断、只覆盖容器名时的 ID 清除、模式键分区往返 |
| Winlator 图形加速 | GL/GDI 第一配置槽映射、旧 WineD3D 保留容器渲染器、Box64 合并、全局/单游戏继承与空值、备份往返、save 开关及 GDI 游戏实机验证 |
| 宿主/JNI | 暂停恢复、Surface 重挂/旧世代、正常退出 ACK 后重入、owner 线程与资源释放 |
| 存档 | ZIP 路径/数量/体积限制、目标冲突、MV/MZ 名称与目录、互通删除/清单损坏、Ethornell gate 关闭 |
| 图形/外观 | 支持/不支持内核、无效/空链回退、关闭滤镜原路径、GPU 数据缺失、手机/平板和深浅色 |
| 封面/网络 | 未授权、离线、空结果、来源失败、候选绑定不覆盖无关字段 |
| 拆封包 | SAF 中转、同名冲突、空目录、取消/残留清理与缺 Native 库 |
| 发布 | 推导 versionCode 递增、签名、R8、实际 APK 内 Native 字节与来源清单 |

JVM 单测、Node harness、Rust 宿主测试、设备仪器及 release probe 各自覆盖不同层面；不能以一种结果代替其它层面的验收。
