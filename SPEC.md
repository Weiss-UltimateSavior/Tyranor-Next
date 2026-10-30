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
- 游戏身份、引擎、目录或入口文件 URI、启动目标与封面元数据落入 Room；记录既可来自目录，也可来自单个文件（ROM / SWF）。无法识别为受支持引擎的目录不作为普通扫描候选入库。
- 游戏页提供搜索、排序、卡片样式、改名、封面操作、启动文件选择及适用的单游戏设置/存档操作；可清理已不存在的游戏记录。
- 快捷启动最多 **3** 项；最近游戏最多读取 **20** 项。桌面快捷方式经独立入口回到统一启动链。
- PC 游戏由用户选择目录和启动 exe 手动添加；安卓游戏从已安装可启动应用选择，以 `androidapp://<包名>` 标识，卸载后启动返回缺失结果。
- 第三方 APK 运行时模块游戏只能经扫描入库：模块须先安装并在「引擎模块管理」启用，且声明扫描特征；主 App 不提供该类型的手动添加、模块选择、目录导入或拖入入口。

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
| Ruffle / Flash | 顶层 `.swf`；根 `index.html/index.htm` + 唯一 SWF 的叶子目录 | 外置 Ruffle APK；一 SWF 一条记录 |
| 第三方运行时模块 | 已安装的 `com.tyranor.runtime.*` APK + 模块 `res/raw` 扫描描述 | 显式组件交棒游戏文件夹，模块自行运行 |
| YU-RIS / CatSystem2 | YPF/DLL/配置或 INT/CST/HG3 等组合 | 外置 Winlator |
| PC / 安卓游戏 | 手动添加目录+exe / 应用包名 | 外置 Winlator / 直接启动应用 |
| PSP / Nintendo Switch | `.pbp/.cso/.iso/.chd` / `.nsp/.xci/.nca/.nro` | 外置 PPSSPP（标准/黄金）/ Eden，一 ROM 一条记录 |

JoiPlay 外置模块与模拟器必须先安装；主 App 显示安装状态及获取入口。第三方 APK 运行时模块同样由用户自行安装，但主 App 不提供下载/安装器，只在「引擎模块管理」中列出并管理启用状态。启动前检查目标、权限和目录，失败由类型化结果映射 UI 文案。设置中的“自动”按当前运行时规则解析，不承诺回退能修复不受支持的脚本或资源。

外置模拟器跳转使用显式组件启动目标应用：YU-RIS / CatSystem2 / PC 通过 Winlator 的 `dir_path` 与 `exe_path` 传递游戏目录和主程序；PSP / Nintendo Switch 则传递 ROM URI 及读取授权。具体协议见 [外置模拟器接入说明](docs/外置主机模拟器跳转接入方案.md)。

Ruffle（SWF / Flash）由外置 Ruffle APK 模块承载，当前扫描与启动边界如下：

- 扫描目录的直接子项中，每个顶层 `.swf` 默认是一条记录：uri 为文件 URI，`launchTarget` 为文件名，别名 `internal.flash`。
- 包装目录：叶子目录、根含 `index.html/index.htm`、恰有一个顶层 `.swf`，且没有子目录、KRKR/ONS/XSYS4/主机 ROM 或 Tyrano/RPGM/VN/Node 特征时，整个目录作为一条记录（uri 为目录 URI，`launchTarget` 为 SWF 文件名）。
- 无根 index 的单 SWF 仍按文件记录；同目录多个 SWF 逐条入库；含子目录的目录不自动识别为包装游戏。单 SWF 标题取父目录名（缺失回退文件主名），多个 SWF 取文件主名。
- 启动：文件记录先解析入口所在目录，模块收到 `game`（title/id/folder/execFile，`type=flash`）与 `settings`（`app.screenTimeout.int` 及 `ruffle.backend/quality/scaleMode/letterbox/loadBehavior.string`）。未安装返回类型化失败；安装入口打开项目托管的 RufflePlugin-1.02.00.apk 直链。
- 设置：全局与单游戏覆盖，默认 `opengl / high / showall / on / streaming / 0`（0 = 不自动关闭）；允许值分别为 auto/opengl/vulkan、low/medium/high/best、exactfit/noborder/noscale/showall、on/off、streaming/delayed/blocking、0/5/10/15/30/45/60 分钟。
- `configuration.json`：游戏目录位于主外部存储根的子目录时，插件优先读取 `<游戏目录>/configuration.json`；其它情况（含存储根、可移动存储）读取 `/storage/emulated/0/JoiPlay/games/<id>/configuration.json`。文件存在时覆盖 intent settings；主 App 仅在文件已存在且为合法 JSON 对象时同步受管键，不创建/删除，跳过共享多 SWF 目录与存储根，原子写并限制 1 MiB。
- 无统一存档管理，插件自管运行时/存档；不提供虚拟按键重映射；插件固定横屏。设备/插件运行验证尚未执行。

### 第三方 APK 运行时模块

来源：`core/engine/runtime/`、`ui/runtime/`。

- **发现**：Manifest `<queries>` 声明 `com.tyranor.runtime.action.LAUNCH`（含 `CATEGORY_DEFAULT`），主 App 按 action 查询可见应用并只保留包名前缀 `com.tyranor.runtime.`；不申请 `QUERY_ALL_PACKAGES`，也不校验签名、版本或包内容。一个包只取一个入口 Activity：优先带 `com.tyranor.runtime.MANIFEST` meta-data 的，否则按 Activity 名排序取第一个。
- **启用默认关**：发现结果只用于侧边栏「妙妙工具 → 引擎模块管理」列表与模块名展示，不写入扫描索引或游戏库；开关默认关闭。游戏库卡片对这类游戏统一显示「Plugin」，不显示模块名。启用只把包名写入 `AppSettingsStore.KEY_RUNTIME_MODULE_ENABLED`（StringSet，默认空集），不触发扫描、不立即写库。同包名更新/重装保留开关；包名变更视为新模块，默认关闭。
- **扫描描述**：模块可选提供 `res/raw/tyranor_runtime.json`，声明 `extensions` / `markers` / `match`（`any` 或 `all`）；这是自动扫描的唯一特征来源，主 App 不根据包名、应用名、图标或内置第三方特征库猜测，也不执行模块代码；扩展名只匹配目录直接子文件，marker 支持直接子项与单层路径（如 `data/*.pak`），不递归。描述缺失、读取/解析失败或没有有效模式时只关闭该模块的自动扫描能力，模块仍可被发现、启用和启动。
- **扫描接入**：扫描开始时快照「已发现 + 已启用 + 有扫描描述」的模块；仅当内置识别结果为 `UNKNOWN` 或 `WEB_OTHER` 时才尝试模块匹配，KRKR / ONS / RPGM / Ren'Py 等强特征结果不被覆盖。多模块命中按「`all` 优先于 `any` → marker 数量多 → 包名」选择；重扫对同一 URI 优先保留旧绑定，扫描深度沿用现有上限。
- **唯一入库入口**：扫描是第三方运行时游戏唯一入库入口；管理页、添加游戏对话框和游戏页均不提供手动添加、模块选择、目录导入或拖入，也不提供手动绑定/重绑或模块缺失修复页。
- **绑定与降级**：`ScanGame.runtimeModulePackage`（Room `games.runtime_module_package`，v3）记录「游戏 → 模块包名」；模块卸载、禁用、入口不再可发现或包名为空时，绑定游戏降级为 `EngineType.UNKNOWN`，但保留最后已知包名（用于重扫合并，不作为启动依据），保留标题、封面与最近时间。发现查询失败不降级。重新安装/启用后重新扫描可恢复，不支持自动或手动改绑。
- **启动交棒**：`EngineType.RUNTIME_MODULE` 由 `RuntimeModuleLauncher` 以显式 `ComponentName` 启动，只传当前游戏文件夹绝对路径、标题与协议版本；不传 `game` / `settings` JSON，也不附加 URI 读写授权。`Dispatched` 只表示 Intent 已交棒，不保证模块进入游戏或未崩溃；模块缺失、未启用、入口缺失、路径无法解析、安全拒绝与其它异常返回类型化结果。模块缺失或未启用（存在绑定包名时）会触发该游戏降级；路径无法解析时不请求「所有文件访问」，存储权限由模块自行负责。
- **能力边界**：`RUNTIME_MODULE` 不进入 `EngineLauncher.supportedEngines`，不出现在引擎页引擎列表；不接管存档（`GameSaveManager` 无统一接口）、虚拟按键/手柄重映射、引擎或单游戏设置。运行时、设置与存档均由模块自身管理。管理页的刷新只重新发现模块，不触发游戏库扫描；禁用仍有绑定游戏的模块前先确认，确认后降级。

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
- **Kirikiri SD 卡（可移动存储）强制镜像存档**：SD 卡 Kirikiri 游戏的存档路径被强制接管到应用专属 SAF 镜像目录，与「独立存档目录」开关无关；游戏目录不生成 savedata，手动放入的存档不被引擎读取。新镜像使用与标题无关的 `<数据根>/games/game-<key>/savedata`；同 key 的多个目录按「含非空存档优先、最新存档修改时间优先、同时间优先稳定目录、再按目录名」选一个作为当前根；mtime 只是启发式，日志写入或恢复可能影响排序，不保证一定代表最新游戏进度，其余目录不自动合并或删除，游戏内和存档管理只显示当前根。首次选定后以索引旁标记固定当前根（原子写入并校验归属），标记损坏或指向目录消失时失效重选，删除游戏时保留索引与 sticky 标记。应用数据清理只删除门禁内当前生效的 `savedata` 及其事务副本，保留镜像父目录、资源与未选中的旧根，其他根的存档须由具备应用私有目录访问能力的调试/恢复工具单独提取，再经存档管理导入，应用内暂不提供旧根选择入口。镜像构建跳过源 `savedata` 子树；只清理能由旧索引证明为源文件占位的空存档残骸，未索引或非空存档保留；完整根列表下还须经源文档查询二次确认缺失后才清理旧资源占位，未经确认的已知旧行保守保留并携带；光标迭代正常结束本身不作为删除证据。从旧版本升级的镜像同样跳过源 `savedata`：旧源存档不会被自动读取，须经存档管理导入——该迁移边界为设计行为（第九轮 #17 记录）。存档管理对该强制镜像形态在列表与导出中过滤 0 字节占位残骸（导入源 ZIP 例外：用户选择的文件全部保留，含合法空文件）；全空/全 0 字节 ZIP 的导入按用户选择生效交换，但先前存档经持久保留标记保护，并停靠在 `savedata.import_orphan_<UUID>`；交换后中断的 `.import_backup` 在收尾时同样转为 orphan，不会静默销毁；每个实际存档目录最多新增三份不同内容的 UUID 恢复副本；按相对路径、目录结构与文件内容 SHA-256 校验相同后才合并副本，提交 marker 不参与内容比较，不追随符号链接。槽位满且备份内容不同则返回存档目录不可用，保留 indexed backup 与事务记录供重试；全零导入会在交换活动目录前检查容量。旧安装已超过三份的不同内容副本不会为达上限而删除，也不再新增。用户须先用具备该目录访问权限的文件/调试工具把恢复目录完整备份到其它位置，核实副本后移走或删除不再需要的一份，再重试；当前没有专门的恢复槽管理 UI。（该保留策略位于共享导入事务，对 ARTEMIS/AVG32/UK2/MV/MZ 等导入后回补引擎资源的形态同样生效）；其他引擎的合法空文件保留；删除存档前先保护导入事务恢复目录中的非存档资源，恢复冲突或不可读时保留恢复目录并返回类型化失败，不将删除存档视为删除游戏资源的授权；设置页显示 SAF 引导并置灰无关开关；存档位置解析失败仅作为提示展示，独立存档开关与内核选择保持可用（改回默认正是解除失败的途径），不形成自锁。
- **Kirikiri SD 卡访问条件**：保留可移动存储 KRKR 的 SAF-only 启动通路，不强制新增「所有文件访问」授权。SAF 树授权用于目录索引与真实内容访问，MediaStore/真实路径直列是可选的尽力补全，无权限或 ROM 不支持时保留已有 SAF 结果；不能保证发现 provider 未列出的任意源文件。
- **Kirikiri SAF 镜像读取协议**：镜像以「0 字节占位 + 索引（v2 记录真实尺寸，兼容 v1）」运行，源 SAF 文档只读打开。普通只读请求优先本地覆写，否则读取索引指向的源内容，不创建磁盘锁或要求控制目录可写；索引升级与本地控制状态解析兼容真实根及其规范化别名；这不等于 native 源读取前缀及运行时 open/stat 已贯通任意别名，运行时仍使用启动载荷约定的镜像根拼写。`O_RDWR`/`r+` 对已索引占位先复制完整源内容到应用私有本地副本，再提供可写本地描述符，不写穿 SD 源文件；复制采用有界缓冲，单文件上限 64 MiB，源尺寸变化记录诊断并以实际完整读取结果为准。超过上限或复制失败（含源不可读）时非只读打开返回打开失败（native 侧 EIO），不降级为只读描述符、不成功读取空占位；引擎据此感知「保存不可用」而不是静默丢数据。标准 `savedata` 的纯本地文件操作不进入资源事务、不创建资源锁；资源事务无法建立跨进程锁时仍返回错误而不是无锁执行，避免重新引入并发丢更新。标准 `savedata` 优先经存档重定向（重定向锚定 `<游戏根>/savedata`，嵌套 `data/savedata` 等游戏资源不再被劫持）。
- **镜像本地覆写生命周期**：提升的本地副本及故意清空后的文件，通过镜像根内 `.tyranor-mirror-state/` 标记维持本地优先语义，源文件更新不会自动覆盖本地副本；若文件操作已成功但控制标记落盘失败，本会话仍优先本地，跨次启动的所有权可能丢失，尤其无法靠非空长度识别的空文件，不保证自动恢复。已本地化普通文件删除/改名及真实临时文件覆盖后的控制状态随相应操作处理，准备新占位前也移除目标已消失的陈旧标记；故意保留的零字节本地文件仍是有效覆写。真实 rename/unlink/remove 系统调用、状态同步与 copy-up 发布共同使用固定顺序的分片锁事务，避免已成功发布的新文件被慢源复制覆盖。写入/copy-up 使用固定分片锁，避免每个资源留下一个锁文件；已有旧锁文件不会在其他进程可能使用时直接删除。尚未复制源内容的占位文件改名不提供完整源文件移动语义，目录树整体改名的后代标记迁移也未覆盖，不能将单文件同步等同整树迁移。复制与回滚临时文件放在保留的 `.tyranor-mirror-state/tmp/<分片>/`，同分片锁内回收孤儿，避免引擎枚举；旧资源目录中无来源证明的历史同名临时文件不按前缀猜测删除。多个历史镜像根仍按当前根策略保留，不擅自合并或删除用户数据。
- **SAF 镜像完整性判定**：每次启动按「本次观察到的源条目数 + 上轮索引 + 查询失败/跳过/深度限制/无效根列表信号」决策（纯函数 `decideMirrorSources`）：有源条目且无任何不完整信号 → 全量重建索引；有源条目但存在查询失败、跳过、深度限制或无效根列表（子树部分失败）→ 携带上轮索引中本次缺失的行合并重写，失败子树不被静默清空；本次 0 条源条目但存在上轮索引或本地覆写 → 保留旧索引原样继续启动（不改写、不做占位清理）；仅当既无源条目、也无旧索引和本地覆写时抛类型化 `EMPTY_MIRROR` 阻断启动。
- **镜像写入 copy-up**：非只读打开（含截断/追加/仅写）对已索引占位先复制完整源内容到本地（上限 64 MiB、临时文件 + 原子发布 + fsync，失败回滚占位并不留所有权标记），再提供可写本地描述符；保护范围限于尚未交付可写描述符的 copy-up 准备/发布事务：失败不发布半成品或新所有权。可写描述符已交付后，合法截断及随后写入按本地语义执行，崩溃或空间不足可能留下空/部分覆写与标记，不自动用源内容覆盖这些本地结果。追加日志类文件同样先复制（其源通常为引擎自建文件，代价小）。
- **SAF 镜像备份取舍**：`<数据根>/games/` 同时包含可重建占位树、真实存档/本地覆写文件及对应控制标记，控制标记随同一镜像根参加备份与清理。恢复仅含 exclude 的默认备份范围，保留 root、files、sharedpref、database、external 等域的默认候选；不添加只覆盖 games 的单独 include，以免把应用设置、游戏库 prefs 镜像及其他引擎存档排除。鉴权与 Room 数据库排除项不变，索引仍位于 noBackupFilesDir。系统备份仍受容量、设备与传输策略限制，不保证保存或恢复这些存档（`bmgr` 实测待办），用户仍应主动导出 ZIP。
- ZIP 导入检查路径安全，最多 **20,000 个条目（含目录）**、展开内容 **1 GiB**；MV/MZ 可剥除最多 **3 层**单目录包装。适用的会话/操作门禁阻止运行中冲突。
- **YU-RIS 为外置跳转中的存档例外**：统一管理其游戏根 `save/`。PSP、Switch、CatSystem2、PC、安卓游戏、Ren'Py、RPG Maker RGSS、Ruffle、第三方运行时模块、VN 与 WebOther 当前不提供该统一文件存档接口；外置启动不意味着所有引擎都不能管理存档。
- MV/MZ 标准格式与 Tyranor 格式仅作文件名映射，不重新编码内容；MV 支持哈希名及历史键名，MZ 使用 `fileN.bin` 等 ASCII 键名。
- 启动时发现适用标准存档可询问转化，已有目标不覆盖，原件留在 `original/`。
- MV/MZ 存档互通默认关闭，支持全局/单游戏设置；开启后启动前与回前台进行双向同步，并等待引擎会话结束。按槽位比较时间与哈希；删除副本隔离到 `deleted/`，同步清单损坏时中止文件修改。
- Ethornell 运行时用户数据不等于已开放存档镜像；当前 App 存档入口仍关闭。

### 存档同步（WebDAV）

来源：`core/game/save/sync/`、`ui/save/WebDavSettingsActivity.kt`、`ui/save/SaveVersionsPanel.kt`，以及位于 `ui/common/` 的 `SaveSyncFeedback.kt` / `SaveSyncMessages.kt`（一次性结果观察与错误文案映射）。服务器配置在 WebDAV 设置页（妙妙工具侧栏直达，设置主清单不加项），使用与应用设置一致的 `MiuixCard` + `OverlayDropdownPreference`。**总开关** `WebDavConfig.enabled` 默认 false，作为独立 prefs 布尔值持久化、不与加密凭据捆绑，关闭不会重置服务器或凭据；有效状态 = enabled 且已配置。关闭时依赖设置全部不可用，每游戏 `SaveVersionsPanel` 的版本选择、切换、同步与身份配置隐藏，但保留未完成切换恢复入口，后台任务取消、入口守卫拦截，手动与自动都不发起网络请求。存档管理页只提供紧凑版本选择器 + 切换按钮、「WebDAV 同步」（固定双向）与「云端游戏标识」，没有方向下拉或 WebDAV 设置跳转；每个 DAV exchange（包括传输内部发起的嵌套请求）在发出前重新执行本事务准入，复核总开关、持久 generation、独立 `config_revision`、session/current 条件及已固定的关键 revision 与目录归属。准入后网络 I/O 不持有本地版本/目录锁，已发出的单次请求可自然完成，后续与嵌套请求再次准入。

- **启动隔离**：`EngineLauncher` 以 Disabled / Unmanaged / Unresolved / Directory 类型路由。无统一文件存档接口的游戏直接启动；受管理游戏在 WebDAV 关闭或开关不可读时不解析同步目录、不进同步目录锁，但与解析失败的启动一起使用 mutation 门禁及启动宽限。已解析目录使用实际目录锁→mutation；等锁后重读准入，类型/目录变化时重新准入，再以固定目录读取 baseline。宽限在实际 dispatch 前登记，即使可选 ticket 失败也不会漏掉；手动/仅拉取或关闭同步时的 YURIS 也登记独立外置运行标记；它不随配置 generation 失效、不按短宽限到期。已 dispatch 的标记在返回前台、Activity 重建与主进程重建后仍保留，只能由用户退出 Winlator 后在存档管理页明确确认已退出，按该 URI 的标记定向清除；确认入口在 WebDAV 关闭时仍可用，普通前台事件不推进退出会话。宽限按 token 独立保留，失败只移除本次 token；Intent 已发出后的最近记录失败不会冒充启动失败，随后取消也不撤销已发出的保护。正常同步仍严格校验实际目录。
- **等锁后的复核**：激活早期收尾、导入前捕获、失败回滚、同步收尾与自动会话内容捕获均在取得 mutation 门禁后复核运行态和实际目录；需要开启功能的事务同时复核开关。取消后的必要回滚连同等锁在 `NonCancellable` 中执行，允许关闭状态下收尾；若游戏已运行或目录改变，则保留 journal/staging 供退出后重试，不写活动目录。

- 模式四选一，**默认手动**（`WebDavConfig.mode = MANUAL`）：手动模式下应用打开不自动拉取、退出不自动上传；整库手动同步在 WebDAV 设置中提供拉取 / 上传 / 双向三种方向，单游戏只有固定双向的「WebDAV 同步」。只有用户显式选择自动拉取 / 自动上传 / 双向后，才启用打开拉取与退出上传。任何模式下，切换都须用户在存档管理页确认，确认后先保留当前存档版本再走既有 ZIP 导入链；自动拉取同样**从不自动导入**。
- **恢复后的单一更新链**：用户激活旧版本 S 时，系统先保留当前存档，再把 S 作为恢复起点。若用户随后继续游戏并保存出新版本 N，N 成为这条恢复链的新云端头，界面显示「S 时间→N 时间」；同步成功后云端用 N 替换旧 S 及同内容副本，不把 S、N 拆成两条独立恢复历史。若激活后尚未产生新内容，则 S 自身的恢复版本进入待同步状态。版本时间显示 `yy.MM.dd HH:mm`；同日新时间缩写 `HH:mm`，旧时间缺失显示「未知」。`SaveVersion` / `RemoteSaveVersion` 的 `previousCreatedAt` 可空；恢复版本记录 S 的时间并传播到云端，云端文件名带 `Restored-as-new_` 前缀。恢复文件名可带 `Restored-as-new_<19 位旧毫秒>~<当前时间>_<哈希>.zip` 的旧时间前缀，无前缀的旧恢复文件仍可读，普通文件名与同步身份不变。本地同一内容只刷新时间戳与身份（同一内容档案单份，不产生重复项）。**手动模式下只落本地**，云端待上传留在队列中；自动上传 / 双向模式排程一次上传，随后没有新的内容变化，进出游戏都不再触发同步。`promotionActiveKey` 与 `promotionDirectoryRevision` 把尚未消费的恢复链绑定到实际目录及其选择 revision；另一 URI 在同一目录成功完成选择后，旧 alias 的 promotion 因 revision 不匹配而暂停。从其它目录进行手动或自动收尾时以 `SAVE_LOCATION_CHANGED` 暂停，回到原目录且 revision 匹配后才继续。本地哈希在发布前校验，云端确认新目标名称与大小后才删除对应的旧源或同内容版本；云端优先用 MOVE 把同内容对象改名更新，服务端不支持 MOVE 时回退 PUT 重新上传。其它内容不同的档案不受影响，按每游戏保留上限保留。
- 自动上传只在游戏会话结束且存档内容确有变化时发生；手动上传不受此约束，当前存档待传时随时可发起。
- 自动上传的 session 与 baseline 缓存绑定启动时的规范化实际存档目录。修改独立存档设置后，原任务保留并以 `SAVE_LOCATION_CHANGED` 暂停，不读取新目录、不把空目录当作已完成，也不在再次启动时把旧 baseline 改绑到新目录；切回原设置后可重试。旧 session 缺少目录记录时同样暂停。关闭总开关会同步捕获当时的 session token；异步清理逐项比较该 token 且不依赖清理时开关是否已重新开启，只删除旧 ticket，后启动产生的 replacement 保留。session 同时保存 `enabledGeneration`；新 launch 只继承与该持久 generation 相同且目录匹配的 pending baseline，关闭前遗留任务在重启后也不会被当作当前进度继承。修改服务器配置会推进独立的 `config_revision` 来阻止旧事务继续，但不推进 `disable_generation`，所以既有 session 与启动宽限保留，下一轮按新配置重试。实际切换其它版本也会为该次选择更新 session；仅选择已处于活动状态的相同内容仍为无变化，不当作重建任务。上述操作不迁移原目录文件。
- 版本身份只取存档条目相对路径 + 内容字节的 SHA-256 聚合，忽略 mtime 与打包顺序；同一内容只存一份。云端对象不可变，新版目录为 `TYN-saves/<EngineType 名>/<可读标识>--<12 位游戏键>/`，文件名 `<yyyy-MM-dd_HH-mm-ss-SSS+时区偏移>_<完整 SHA-256>.zip`，时区取设备当前偏移。
- **旧布局兼容读取**：新写入不再使用 `TYN-saves/v1/`，同时读取旧 `TYN-saves/v1/<完整游戏键>/`（19 位毫秒时间戳文件名）与新版游戏目录；旧文件不作批量迁移、不为升级重传；例外是显式「旧存档更新为新存档」的云端清理与保留上限裁剪可删除旧条目；新旧条目按 `createdAt` 与内容哈希比较，**不按混合文件名做字典序**。
- 本地历史每游戏最多 **5** 个版本，新增写入、维护与恢复淘汰前都先保护 journal 中待处理的 `selected`、`beforeHash`，以及 `promotionAwaitingChange` 或 `promotionNeedsRecovery` 仍需要恢复的 `promotionFromHash` 有效 source；新增写入前检查可用空间并预留 16 MiB，不足时保留既有版本并提示清理空间，不提前删除旧版本腾空间。仅在新增有效版本成功后清理最旧；这 5 个按不同内容去重，与活动存档目录显示的「当前存档文件 N」计数无关。本地 ZIP 仍沿用原稳定命名格式，不随云端布局变化。拉取保留云端版本时间；新拉取的有效版本不会因设备时钟差异在同一轮被裁剪，超限时清理最旧的已有版本。云端保留上限是设置中的**单一全局值**，只能三选一：**10（默认）/ 20 / 无上限**，修改即时保存、对每个游戏各自独立生效；旧自定义值按 ≤10→10、11–20→20、>20→无上限 迁移，迁移只改设置值，不删除任何已有云端或本地版本。
- 云端保留只在该游戏本轮确有变化上传且新对象内容验证通过后执行，且对该游戏的新旧目录合并计数；不是按游戏分别配置，也不会波及无关游戏。若该游戏检查点带有上轮未完成的 `needsPrune` 且云端已超过所选上限，仅在游戏空闲且重新校验待发布目标确有完整存档后才重试裁剪；忙碌时跳过本轮上传与裁剪，无有效本地版本时不发布新内容但可在验证后继续清理；若待裁剪目标已缺失且无有效本地版本，清除过期裁剪标记并按无变化返回，避免永久失败；删除仍失败则不追加新 ZIP。每个游戏每个同步轮次中，所有经 `SaveSyncService` 发起的远程 DELETE 合计最多 **20** 次；达到上限时保留 `needsPrune` / `publicationConfirmed` 检查点，以 `cleanupPending` 结束本游戏本轮并安排自动 fallback，待下一轮重新确认有效替换后继续裁剪，不破坏已有 S→N 链。未确认目标时重试同一发布意图，只能凭强 ETag 条件修复或退役已证实损坏的目标，不能先删旧的有效源版本，避免错误响应或 DELETE 权限异常下丢失历史或无限增长；上限 0 仍是显式不设上限。清理失败在下次写入前保持惰性，IO 或取消期间不承诺瞬时物理最大值。Service 已确认的 `needsPrune` 会在 capture/pin 前重新验证云端档案，active 为空或上传 pin 创建失败仍可继续清理，但验证所需空间与 checkpoint 持久化必须成功、验证失败不得删除旧版本；Runtime 以 pending cleanup 优先于非空 hash 差异判断，20 次 DELETE 预算耗尽时保留 marker，新 M 可在同轮发布并由后续轮次继续清理，临时文件 create/copy 的 ENOSPC 统一为不重试的 `STORAGE_FULL`。
- **拉取边界**：每次按游戏只获取云端当前最新版本（按 `createdAt` 与内容哈希排序），仅在该版本尚未被检查点标记为已见时下载。不会批量下载远端全部历史，也不提供直接浏览或恢复未拉取云端版本的入口；本地版本选择器只展示已经存在于本地历史中的版本。
- 跨设备同一游戏默认以「引擎 + 游戏目录名」识别，不用绝对路径或可变标题；目录名冲突或不同时可在存档管理页自定义标识。该标识的领域术语为「同步身份（Sync Identity）」，UI 固定显示为「云端游戏标识」。本地历史与检查点以游戏 URI 私有关联。
- 参与同步的前提是该引擎已有统一文件存档接口，且该游戏的存档当前可用（`resolveSaveLocation().available`）。YU-RIS 经 Winlator 运行但存档落游戏目录 `save/`，可以同步；PC、安卓游戏及第三方 APK 运行时模块等无文件存档接口的条目，在整库同步的云端身份分组前排除，不因同名目录误报身份冲突。受支持引擎的存档暂不可读时仍保留其云端身份占用，避免另一条目覆盖它。
- 自动检查按单游戏串行（协程锁），手动可整库或单游戏并指定方向。判定为运行时，**上传与切换一律拒绝**：切换前的内部备份捕获一并拒绝，需备份的上传/同步轮次计入 busy，切换返回「游戏运行中」错误；**拉取仍允许**——只把最新云端不可变 ZIP 存入本地历史，不触碰当前存档目录。存档页不提供单独的「保存当前存档版本」入口，本地版本由拉取、上传与切换过程自动保留。传输走 OkHttp（5.5.0）。
- **结果提示**：每次手动或自动同步只经简短 Toast 告知「成功 / 无变化 / 失败：原因」，不展示完整计数或全部游戏失败清单；自动结果由进程内一次性待提示状态流，只由当前 `RESUMED` Activity 消费一次；仅「仍在运行、尚未完成」的空转轮询保持静默。配置 revision、session 或目录归属在事务中变化时返回独立的类型化 `LOCAL_STATE_CHANGED`，不冒充 `GAME_RUNNING`；`cleanupPending` 也有独立提示，不显示成游戏运行中。提示只在已配置 WebDAV 时出现；持久化开关 `showSyncFeedback`（默认 true，界面名「同步提示」）同时控制手动与自动结果，关闭只静音提示，不改变同步。单游戏手动同步无法读取加密配置时改在面板内显示凭据错误，不绕过静音设置强制弹 Toast；原有导出、导入、删除和 MV/MZ 互通结果仍保留长时提示。
- 服务器地址、用户名、口令、模式、保留量与提示开关构成**单个加密载荷**，存于独立 prefs `webdav_sync_secure`，由 AndroidKeyStore AES/GCM 256 位密钥保护（与 Hikarinagi 令牌同一做法），并写入 `fullBackupContent` 与 `dataExtractionRules` 的云备份/设备迁移排除；解密失败保留密文待下次重试，也可经用户确认重置配置后重新填写；重置不清理存档历史。`WebDavConfig` 快照另携带独立的 `config_revision`；保存新载荷或重置配置时，payload 变更与 revision 递增在同一 prefs `commit()` 中完成，本地事务入口和真实 HTTP 请求 enqueue 前都核对快照 revision。该 revision 与关闭开关的 `disable_generation` 分离，配置修改不会模拟关闭/重开。
- 版本、检查点、会话、宿主记录与目录选择状态落在 `noBackupFilesDir/save_sync/{versions,checkpoints,sessions,hosts,selections,directory_selections}`（不进系统备份）；待处理激活记录与 staging ZIP 落在 `noBackupFilesDir/save_sync/pending/{records,archives}`。临时 ZIP 落在 cacheDir。
- **本地升级迁移**：保留早期本地 Debug APK 产生的兼容数据。早期 `filesDir/rpg_save_sync/<hash>.manual.json` 手动选择标记迁移到 `noBackupFilesDir/save_sync/selections`，迁移不移动同步清单或存档版本；旧保留量值和旧 v1 云端布局分别按前述规则归一、读取，不删除或重传已有历史。
- **存储维护**：`SaveSnapshotStore.maintain` 按游戏限定在自身历史目录内，修剪上轮中断留下的有效 ZIP 溢出、删除孤儿 `.cloud` 标记与 `snapshot_*.tmp`；若已超过 5 且清理失败，则阻止新写入以免无限增长。缓存中的 `save_sync_*.zip` 与 `dav_collision_*.zip` 由 `SaveSyncTemporaryFiles` 登记保护在途文件后清理，常规路径在 finally 释放；同内容恢复不再复制源 ZIP 正文。维护只作用于该游戏自身的有效归档与私有历史目录，不做跨游戏全局 GC，不清理其它游戏或无主历史目录，不扫描旧引擎目录，也不触碰活动游戏的原始存档。5 版上限只约束有效 ZIP 版本，瞬态暂存与临时文件不计入，也不承诺严格物理文件数。
- 超过五版且需要淘汰历史时，先校验候选 ZIP 的内容及名称哈希；损坏项改名为同目录 `.zip.corrupt`（重名追加编号），退出可选历史与配额，保留故障字节供排查，再按有效版本淘汰。普通列表及未超限维护不全量重读 ZIP，选用或传输时仍按需校验；校验取消或真实 IO 错误向上传播，不冒充损坏。隔离材料由移除该游戏时的本地历史清理一起回收。
- **同步任务链**：App 侧使用四条相互独立的唯一 WorkManager 链——打开立即、退出立即、打开 fallback、退出 fallback，不把 fallback 合并为共享链。立即链使用 `APPEND_OR_REPLACE` 与 1 秒初始延迟，fallback 链使用 `APPEND_OR_REPLACE` 与 30 秒初始延迟，不取消正在执行的传输；打开/退出链各用稳定键保存累计排除集和最新 owner UUID，过时排队请求直接成功结束且不做 IO，旧 owner 不能清理后继状态。fallback 的终止错误先记录到同步报告，再以 WorkManager success 结束图节点，避免失败传播取消已排队的后继请求；前台按打开模式排打开链，宿主正常退出或监测到不再运行排退出链，busy 或可重试失败补排对应 fallback，混合报告中的其它游戏永久失败不提前终止可重试项。每轮先补写持久化会话，打开链再按模式执行最新版本拉取。关闭功能在同一 `webdav_sync_secure` prefs 提交中写入 `enabled=false` 并推进持久 `disable_generation`；session 记录启动时的 generation，旧 ticket 在 sweep 取得锁前即可因 generation 不匹配被拒绝，进程重启后仍无效。启动前忙碌判定也按当前 generation 忽略旧 ticket 与旧启动宽限；旧快照尊重较新的启动宽限，失败启动只清理自身 generation/token 的宽限；实际或无法确认的独立宿主进程仍参与忙碌探测，不随旧 session 一起被忽略。`whileEnabled` 每次同时复核持久 generation 与 enabled，正在进行的启动不能跨关闭边界继续写入有效 ticket。
- 手动激活版本产生的待同步记录不代表游戏已经启动；即使该游戏从未产生宿主记录，也能在空闲时完成上传。真实启动会话在宿主证据缺失时仍保持保守阻止，不能仅凭手动切换的记账状态认定有游戏运行。
- **YU-RIS 经外置 Winlator 运行，没有可靠的退出回调**：已 dispatch 的持久运行标记跨前台事件及主进程重建保留。返回 Tyranor 不构成退出证据；用户须先退出 Winlator 中的游戏，再到存档管理页明确确认已退出，才清除该 URI 的外置标记并允许会话收尾。未确认时上传、切换与删除仍按 busy 拒绝，拉取只增加版本历史、不修改活动存档。损坏或非普通标记也经明确确认入口定向清理，不通过普通前台事件或删除前清守卫来放行；WebDAV 关闭时仍提供确认。
- 前后台事件等待目录锁后必须与原 session token 比较，且前台返回条件以锁内当前记录重新判定；旧事件不能把新启动的 YU-RIS 标记为已返回或离开应用。
- **待处理激活与提交证据**：`SaveActivationJournal` 对每个游戏最多保留一个记录和一个私有 staging ZIP；`prepare` 先校验私有 ZIP，再移入 durable archive。同一实际目录另有持久化 `SaveDirectoryActivationStore`：导入前在目录锁内写入 owner URI/token、仅在首次 begin 保存 `previousRevision`、清空旧 owner 状态并推进 `revision`。重试只能 `resume` 自己的当前 token epoch，或兼容没有 revision 的 legacy 空 epoch；目录已推进到另一 alias 时失败关闭，不能回写旧 revision。所有别名在 journal 恢复、元数据收尾和按 `activeKey` 扫描的 session/baseline 清理全部成功前 fail closed；journal 删除前先持久化 `completionCommitted`，清理失败时继续保留 owner/token，若崩溃发生在 journal 已删除之后，也可按该结果释放 owner。committed completion 保留新 revision，使旧别名已固定的传输继续失效；已证明的提交前 abort 以 `complete(..., committed = false)` 恢复 `previousRevision`，保留更早 completed S→N 链的目录 epoch。目录提交成功后，所选存档先重新登记到本地版本历史，再按导入后规范化的活动存档捕获恢复元数据，避免归一化改变字节或路径时丢失 S。未标记提交时，`canFinalize` 只在当前哈希等于所选 S 且不同于导入前哈希时成立；相同字节在导入前已经处于活动状态不能证明目录提交，必须继续等待提交证据。提交标记证明活动目录已切换，`completedActivation` token 防止重复登记 promotion；未提交的失败尝试在用户明确重试时重新执行选择，即使字节已相同也不提前跳过。
- **版本激活与普通 ZIP 导入边界**：只有从存档同步版本选择器激活版本时，才写入不参与系统备份的一次性「手动选择」标记，供下一轮 MV/MZ 存档互通以 Tyranor 侧为准。存档管理页的普通 ZIP 文件导入保持原有导入行为，不写该标记，也不承诺受保护的定向导入。切换或恢复中断后的重试只完成已提交导入的资源/元数据收尾和持久化 token 校验，不会自动再次导入活动存档。
- **提交前失败清理**：已捕获的目录提交前异常（含准备后的取消或关闭功能）会先完成必要目录回滚、把所选版本保留到受 5 版上限约束的历史，再清除激活记录与 staging ZIP；清理失败时保留记录，并把清理错误附到原异常。目录已提交或进程直接终止时不按此分支丢弃恢复记录。
- **手动选择标记恢复**：仅明确的路径不存在才视为无标记，属性读取失败、非普通文件或超过 64 KiB 均失败关闭。下一次显式选择可替换损坏/过大的普通标记；无法保存原字节时，提交前失败仍恢复为不可读状态，不放行常规 mtime 同步。目录或链接等非普通路径原样保留，并通过类型化错误的 detail 报告具体元数据路径，修复该路径后再显式选择；不递归删除其中内容。游戏移除时的标记迁移/清理失败必须上报。
- 同步复用既有 ZIP 导出/导入引擎规则与存档限制（条目、体积、路径安全）；校验同时核对中央目录与导入使用的本地条目视图，拒绝隐藏、遗漏或内容不一致的条目；失败以类型化错误码上报 UI 映射文案。上传 pin 的私有副本若复制失败，会先从在途临时文件登记中释放并删除，再传播原错误。页面按当前 AGENTS 的透明 MiuixScaffold + AppTopBar 上下分离规范接入，不恢复已撤回的顶栏模糊宿主。
- **写入门禁与身份**：重叠 SAF 根指向同一规范化存档目录时，共享启动/激活/捕获互斥与宿主关联，历史仍按 URI 保存；关闭功能时启动不解析同步目录，但仍与在途目录变更共享变更门禁。明确换版后由共享目录 fence 阻止所有 alias 的启动与捕获，并按持久 `activeKey` 清理 session/baseline，不依赖当前别名能否重新解析。Siglus 异步销毁须等待 owner 释放确认，再开始普通宿主空闲宽限。云端身份冲突对整个游戏库检查，暂时不可用的游戏仍保留身份；排队同步在采用身份时重新检查。
- **面板状态**：激活、身份修改与恢复任务期间弹窗禁止遮罩/返回关闭；已接受的退场完成后清除父状态，不遗留透明模态窗口。版本信息待补齐使用普通提示，只有真实失败显示错误色；版本占位项不可选，已恢复版本时间只显示一次。
- **验收边界**：本地 JVM 回归覆盖文件保留、命名兼容、WebDAV 传输边界与 MV/MZ 手动切换。历史设备结果锁定在业务基线 `aad49a2` + 测试快照 `60690ad4`：当时 `SaveSyncDeviceTest` 为 6 项，另有 `SaveRecoveryDeviceTest` 3 项，后文设备记录保留该轮真机、API 26/27、UI、TalkBack、独立服务器结果与截图哈希。详见[设备记录](docs/WebDAV-device-acceptance.md)；`a528183` 起 `SaveSyncDeviceTest` 新增 native 与 YU-RIS quick-reenable 2 项、总数增至 8 项，该提交及后续修复只完成编译，尚未执行设备测试，不能计入历史通过数，也不能替代真实服务器、多设备并发或真机验收。

- **显式恢复与新选择**：已提交激活无法收尾时，同一选择返回元数据待补齐，不同选择返回“本次未执行”，须再次确认放弃上次信息才执行新选择。`needsSelectionRecovery` 对 journal、目录 owner、手动恢复标记及 `promotionAt > 0` 但 `promotionActiveKey` / `promotionDirectoryRevision` 缺失、为空或与当前目录/revision 不匹配的 completed promotion 返回 true，因此“处理未完成切换”也会出现在 legacy unbound 或 stale promotion 上；该入口关闭 WebDAV 后仍可用，且不联网、不导入任何版本。显式恢复先把当前活动存档 N 捕获到本地历史（即使 N 尚未存在于历史列表），保留可独立校验的历史 S（含 `promotionFromHash` 指向的有效 source，以及可解析但哈希错误的记录），并保护 `beforeHash`/当前 N；无法证明归属的 promotion ancestry 随后清除。恢复完成后可按普通内容变化同步 N，不能把远端 S 伪装成已被该 N 恢复链替换。该恢复契约位于同步层，适用于所有已接入统一存档管理的引擎；MV/MZ 互通仍受其独立开关与格式兼容边界约束。用户无需先临时激活 S；若当前同一版本已存在于历史中，显式重选仍可实际重新导入并建立新的目录/revision 恢复链。五版历史已满时须确认保留项均有效，救档失败保留恢复材料供重试。
- staging ZIP 缺失或损坏时，可用独立校验通过、内容哈希与所选 S 一致的历史副本完成恢复；活动存档仍为 S 时也可导出留存。没有可验证的源时保留中断记录并报错。同一 completed token、同源且时间匹配的有效恢复链在清理残留 journal 时保留，继续保存 N 后仍发布单一 S→N；不同已提交 token 或不可信来源的旧链继续按放弃规则清除。新 journal 同样持久化实际目录，设置变更后先切回原目录再收尾，不把旧选择绑定到新目录；清理先删除 staging，再移除 token，删除失败保留记录供重试。
- 早期缺少目录绑定的 journal 不自动收尾或继续新选择，提示先“处理未完成切换”；该显式操作仅保留可验证的源与当前设置目录中的存档、放弃未知归属的旧切换记账，不重新导入，也不从旧 journal 建立当前目录的恢复链。早期已 completed 但 `promotionActiveKey` / `promotionDirectoryRevision` 缺失、为空或与当前目录/revision 不匹配的 promotion 同样显示恢复入口：恢复直接保存当前活动 N 与仍可验证的旧 S，清除未知 ancestry，随后普通 N 同步不会错误替换远端 S。显式重选当前同一版本是可选路径；若执行则即使内容未变也实际重新导入，并以当前目录及 revision 建立新的恢复链。未记录位置的旧目录文件保持原样，不推断或扫描其它目录。
- **启动与后台开销**：按 URI 持久化已知内容哈希及相对路径/size/mtime 的轻量签名，签名一致时复用启动 baseline；尚未上传的 session 包括空树 baseline 均跨重启继承，首次或外部变化时重新计算，退出仍按内容哈希判断。宿主 URI 随对应 Activity 销毁移除；未知宿主仍保守判忙。AM 不可用时，有效 native session 仍需宿主证据则保持忙碌；没有该 session 时查持久 host，缺失/已证明退出可空闲，无效 PID 或未知身份保持忙碌。损坏 host 缓存留存原件与隔离副本，逐记录按未知宿主持续保护，不能靠移除坏记录推断退出；创建/销毁/恢复/暂停生命周期重写有效记录后重新判断，不影响严格 journal/checkpoint。自动任务统一使用 UNMETERED，fallback 的零基 runAttemptCount 达到 5 后停止本链重试，保留 session 并以成功节点收尾以放行后继，等待下次前台或手动触发。`cleanupPending` 可独立触发 fallback，即使字节未变也保留 session 与清理意图；游戏按 URI 区分，同名游戏互不抑制。一次 fallback 链跳过已报 `STORAGE_FULL` 的 URI，并在整库拉取和待上传迭代前过滤；排除集在后继排程及运行中补写时合并，当前打开/退出链各最多一份持久记录，不按七天过期，完成/耗尽/关闭后清理，旧 UUID 记录才按七天回收；其它游戏仍可重试，下一次立即或手动请求重新检查空间。下载开始前按远端大小与目录可用空间预检；写、flush、close 或原子替换中的本地 IO 分类为 `STORAGE_FULL` 或 `LOCAL_IO`，空间不足不进入自动网络重试。
- **异常云端对象**：未完成的普通上传和恢复上传均可退役已证实损坏、且具有强 ETag 的目标。无强 ETag 的损坏目标记录 name/ETag 阻塞状态，后续不反复整包下载或追加版本；对象删除或 ETag 改变后重试。远端时间超过当前时间 24 小时或超出可表示范围的条目不接受；下载先写受保护的临时文件，完整校验成功后替换目标。

- 已上传恢复版 S 不会消耗恢复链：首次产生新内容 N 后继续以 S→N 发布，成功确认后才消费该链起点；无变化的重试不刷新 promotion 时间或重复上传。
- 网络同步与本地版本操作分锁；每个顶层及嵌套 DAV exchange 都重新执行事务准入并复核持久 generation、`config_revision` 与已固定的 revision/目录归属，检查与请求入队在同一短临界区内完成，网络响应等待不持有本地锁，后续请求再次准入。上传使用受保护的私有 ZIP 副本；用户切换、修改配置或身份、删除游戏后，旧传输的后续请求不再回写本地元数据或继续裁剪。自动回传同时绑定原 session token，在等待目录锁后再次复核，已被换版或别名操作撤销的会话不捕获或上传新选择内容。
- 旧 journal 已提交但活动存档已前进时，再次明确选择同一 S 必须实际重新导入 S，不能把完成 N 的元数据登记当作切换成功。journal 损坏先从独立 archive 救入历史，救档失败不删除 archive。状态目录（journal 记录、检查点、session、baseline、hosts、目录选择）本身被普通文件占位时按 `LOCAL_IO` 失败关闭，Windows 的子项「路径不存在」不等同于无状态；待处理激活仍把已校验的 ZIP 保留在专属 archive 中供恢复流程救回。
- 删除游戏同时清理该 URI 的本地版本、检查点、pending、session、baseline 与同步身份，不删除云端版本或游戏目录文件；先持久化新 revision 使在途传输失效；子项清理失败保留身份和检查点归属索引，恢复游戏库条目，允许退出游戏/修复路径后重试。清理保留原始 `SaveSyncError`，UI 按 `GAME_RUNNING`、`LOCAL_IO`、`SELECTION_INCOMPLETE` 等代码映射提示，不再统一伪装成存档目录不可用。关闭 WebDAV 不影响该清理。
- ZIP 导入的 backup 仅在匹配 URI/token/保留标记的 staging 提交标记证明成功且不要求保留旧档时删除；中断后 destination 被重建而缺少证据时，旧存档留在相邻 `.import_orphan_<UUID>`，不会静默丢弃。全零文件导入在目录交换前持久化保留旧档标记，回调或资源恢复中断后的收尾仍保留旧档。不确定的备份不计为同步历史，不自动裁剪。

## 8. 补丁与 XP3 拆封包

- KRKR 提供在线补丁列表、过滤及应用能力；启动兼容 overlay 与在线下载是不同机制。
- Artemis 支持启动补丁策略（询问/自动/关闭）与手动补丁动作；默认关闭，自动修补须幂等。
- 独立拆封包工具递归发现 XP3，当前扫描上限 **500** 个、深度上限 **6**；支持条目预览、进度与取消。
- 解包到归档同名目录，遇到同名目标拒绝，不自动改名覆盖；封包使用 0（不压缩）或 1–9（zlib）等级。已有目标的处理由页面冲突检查/保存流程决定，不把底层覆写能力当作无条件允许覆盖。
- 无真实路径时通过 SAF staging 中转；Rust 库缺失返回明确错误。KSD mode-2 小条目可随解包隐性解码，不单独提供入口，也不承诺绕过任意 XP3 加密。

## 9. 授权与应用更新

Hikarinagi OAuth 支持授权、回调校验及 token 刷新。应用检查 GitHub Releases，提供正式/预发布候选、下载与系统安装器跳转；后台任务在应用进入后台时安排，并受网络和系统调度限制，不保证固定时间完成。通知及未知来源安装受系统授权控制；失败不应阻断离线游戏库功能。

## 10. 实验能力与证据边界

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
| Ruffle / SWF | 一 SWF 一条与包装目录边界（根 index、唯一 SWF、无子目录、既有引擎信号优先）、文件 URI→父目录、`game`/`settings` 契约与类型包装、模块未安装/入口缺失、全局与单游戏覆盖、`configuration.json` 只同步不创建、无存档入口与横屏限制；设备/插件验证待执行 |
| 第三方运行时模块 | action/前缀发现、默认关闭、启用只写开关、仅已启用模块参与扫描、描述匹配只补 `UNKNOWN`/`WEB_OTHER`、旧绑定优先、改名/封面更新不丢绑定、卸载/禁用降级与重扫恢复、显式组件交棒、无存档/按键/设置入口 |
| 宿主/JNI | 暂停恢复、Surface 重挂/旧世代、正常退出 ACK 后重入、owner 线程与资源释放 |
| 存档 | ZIP 路径/数量/体积限制、目标冲突、MV/MZ 名称与目录、互通删除/清单损坏、Ethornell gate 关闭 |
| 存档同步 | WebDAV 总开关默认关、关闭时不联网/隐藏版本同步身份但保留恢复入口/保留独立配置、关闭在同一 prefs 提交中持久化 `enabled=false` 与 `disable_generation`、session 记录 enabledGeneration 且旧 ticket 立即失效并跨重启保持失效、新 launch 不继承关闭前 pending baseline、启动前忙碌判定忽略旧 ticket/grace 但保留独立宿主探测、关闭同步捕获旧 token 且异步清理不误删 replacement、`whileEnabled` 复核持久 generation、每个顶层及嵌套 DAV exchange 按持久 generation 与当前 revision 重新准入且响应等待不持本地锁、整库手动三方向与单游戏固定双向、只拉取每个游戏最新云端版本且不批量恢复远端历史、打开/退出即时与 fallback 四条任务链相互独立、每游戏至多一份待处理记录与 staging ZIP、导入前持久目录 owner/token/revision、owner 保留到 journal 清理成功、journal 删除前持久 completionCommitted、journal 已删后的残留 owner 可按已知结果释放、旧 token 只续接自身或 legacy 空 epoch、同一及父子相交目录的入口等到清理成功后才放行、清理扫描持久 activeKey 包含不可解析 alias、committed 完成保留 revision 使旧传输失效、已证明 precommit abort 恢复 previousRevision 并保留旧链 epoch、promotionActiveKey + promotionDirectoryRevision 绑定目录与目录 epoch、另一 URI 成功选择后暂停旧 alias promotion、跨目录或旧 revision 以 SAVE_LOCATION_CHANGED 暂停、导入前同字节不算提交证据、不同 uncommitted token 不清除更早 completed S→N、完成 token 防重复 promotion、恢复重试只补元数据不自动导入、completed promotion 的目录/revision 缺失、为空或不匹配均暴露“处理未完成切换”、恢复捕获 active N 与已验证历史 S 且不导入、清除未知 ancestry 后普通 N 同步且不误替换远端 S、当前同版本显式重选作为可选重建路径、10/20/无上限保留与旧值迁移不删历史、新旧云端目录合并读取且按时间+哈希比较、内容哈希幂等去重、标识冲突拒答、运行中禁止上传/切换而拉取放行、5 版上限只计 ZIP 版本且维护/新增前保护 pending selected、beforeHash 与可恢复 promotion source、无独立保存版本入口、旧版本 S 恢复后保存 N 仍按 S→N 单一更新链处理、普通 ZIP 导入保持原行为、早期 Debug 选择标记与保留量迁移、结果 Toast 受同步提示开关控制、YU-RIS 需先退出外部游戏且 PC 不参与 |
| 存档同步增量 | WebDAV 关闭时不解析同步目录、不进同步目录锁，关闭或回退启动仍与在途激活/恢复的目录变更共享 mutation 门禁、类型化启动准入区分不受管理引擎与目录解析失败而正常同步仍严格校验目录、`config_revision` 与加密 payload 同次提交且在本地事务与真实 HTTP enqueue 前复核、配置保存/重置不推进 `disable_generation`、`LOCAL_STATE_CHANGED` 不冒充 `GAME_RUNNING`、删除保留类型化 `SaveSyncError` 并按码映射、下载空间预检与 `STORAGE_FULL`/`LOCAL_IO` 分类、上传 pin 复制失败清理临时文件、每游戏每轮经 `SaveSyncService` 发起的所有远程 DELETE 合计不超过 20 次以及超限 `cleanupPending` 保留 checkpoint/session 并由 fallback 继续 |
| 图形/外观 | 支持/不支持内核、无效/空链回退、关闭滤镜原路径、GPU 数据缺失、手机/平板和深浅色 |
| 封面/网络 | 未授权、离线、空结果、来源失败、候选绑定不覆盖无关字段 |
| 拆封包 | SAF 中转、同名冲突、空目录、取消/残留清理与缺 Native 库 |
| 发布 | 推导 versionCode 递增、签名、R8、实际 APK 内 Native 字节与来源清单 |

JVM 单测、Node harness、Rust 宿主测试、设备仪器及 release probe 各自覆盖不同层面；不能以一种结果代替其它层面的验收。
