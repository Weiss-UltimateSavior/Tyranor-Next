# Tyranor Next

<p align="center">
  <img src="screenshots/index.png" alt="Tyranor Next" width="850" />
</p>

基于 **Tyranor 模拟器逆向重写**的多引擎视觉小说（Galgame）聚合启动器，面向 Android 平台。内置 Kirikiri / ONScripter / Tyrano / Artemis / Siglus / RealLive / AVG32 / UK2 / FVP / System35 / System4 等原生与 Web 运行环境，另含 Ethornell / BGI 实验接入，并支持 Ren'Py、RPG Maker RGSS、Ruffle（SWF / Flash）外置 APK 引擎模块、第三方 APK 运行时模块与外置模拟器跳转（PPSSPP / Eden / Winlator），提供游戏库管理、封面获取、存档镜像、引擎参数调节等一体化体验。具体功能与兼容边界见 [SPEC.md](SPEC.md)。

mac原生版本如下：

- [Tyranor Next for macOS](https://github.com/Weiss-UltimateSavior/Tyranor-Mac)

iOS版本计划中...
鸿蒙设备可使用卓易通安装

主打轻便、简单、快捷，不引入其他冗余功能的简约设计思路

企鹅群：364439133

感谢 [@安音](https://www.pixiv.net/users/8182248) 为本项目绘画的logo娘！

## 感谢各位贡献者们！一起成为光吧！

<a href="https://github.com/Weiss-UltimateSavior/Tyranor-Next/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=Weiss-UltimateSavior/Tyranor-Next" />
</a>

## 支持范围

### 引擎与游戏类型

| 游戏类型                 | 典型识别特征                                                              | 运行环境                         |
| -------------------- | ------------------------------------------------------------------- | ---------------------------- |
| Kirikiri / Kirikiri2 | `.xp3`、`startup.tjs`                                                | Kirikiroid2 / krkrsdl3 原生运行时 |
| ONScripter           | `nscript.dat`、`.nsa`                                                | ONScripter 原生运行时             |
| Artemis              | `system.ini`、`.pfs`                                                 | Artemis 原生运行时                |
| SiglusEngine         | `Gameexe.dat`/`Gameexe.ini`、`Scene.pck`（根或 `Data/`）                | Siglus 原生运行时（siglus_rs）      |
| RealLive             | `Gameexe.ini` + `SEEN.TXT`（10000 项 TOC）或 `SEEN####.TXT`             | 内置视觉小说运行时（game_launcher）  |
| AVG32                | `Gameexe.ini` + `SEEN.TXT`（`PACL`）或 `SEEN###.TXT`（`TPC32`）          | 内置视觉小说运行时（game_launcher）  |
| UK2（PC-98）          | `UK2.CFG`、`*.MES`（`<< UK2 TEXT Ver1.00 >>` 头）                       | 内置视觉小说运行时（game_launcher）  |
| FVP                  | `*.hcb`/`*.bch` 脚本、`graph.bin`/`voice.bin` 等资源包                   | 内置 FVP 原生运行时（rfvp）          |
| System35             | `*.ALD`（`SA.ALD` 等；根目录或 `GAMEDATA/`）、`System39.ain`/`System39.ini`/`*.wai`/`*.bgi`/`*.alk` | 内置 System3.x 运行时（xsystem35） |
| System4              | `System40.ini` 或 `AliceStart.ini`（根目录；`*.ain` 为辅助特征）                    | 内置 System4 运行时（xsystem4）      |
| YU-RIS               | `yscfg.dat`、`pac/*.ypf`、`YS*.DLL`、`.ymv`                          | 外置 Winlator（winlator-cn）        |
| CatSystem2           | `config/startup.xml`、`*.int`、`*.cst`、`*.hg3`、`*.kcs` 等组合         | 外置 Winlator（winlator-cn）        |
| PC（手动添加）        | 游戏页顶栏「添加 PC 游戏」选择目录并指定启动 exe                          | 外置 Winlator（winlator-cn）        |
| TyranoBuilder        | `index.html`、`tyrano/`                                              | 内置 Tyrano Web 运行环境           |
| RPG Maker XP         | `.rgssad`、`Game.ini` + `Data/*.rxdata`                              | 外置 RPGM APK 模块               |
| RPG Maker VX         | `.rgss2a`、`Game.ini` + `Data/*.rvdata`                              | 外置 RPGM APK 模块               |
| RPG Maker VX Ace     | `.rgss3a`、`Game.ini` + `Data/*.rvdata2`                             | 外置 RPGM APK 模块               |
| mkxp-z               | 用户选择/内部别名 `internal.mkxp-z`                                         | 外置 RPGM APK 模块               |
| RPG Maker MV         | `www/`、`js/rpg_core.js`                                             | 内置 Web 运行环境                  |
| RPG Maker MZ         | `www/`、`js/rmmz_core.js`                                            | 内置 Web 运行环境                  |
| VN                   | `globalData.vndata`                                                 | 内置 Web 运行环境                  |
| WebOther             | 通用 `index.html` 网页游戏                                                | 内置 Web 运行环境                  |
| Ren'Py               | `.rpa`、`game/script.rpy`、`game/options.rpy`、`renpy/` + `.rpy/.rpyc` | 外置 RenPy APK 模块              |
| Ruffle / Flash       | 顶层 `.swf`；根 `index.html/index.htm` + 唯一 SWF 的叶子目录                   | 外置 Ruffle APK 模块              |
| PSP                  | `.pbp`、`.cso`、`.iso`、`.chd`                                         | 外置 PPSSPP 模拟器                 |
| Nintendo Switch      | `.nsp`、`.xci`、`.nca`、`.nro`                                         | 外置 Eden 模拟器                   |

CatSystem2 游戏按 `config/startup.xml`、`.int/.cst/.hg3/.kcs` 等目录特征组合评分识别（`cs2.exe` 仅作辅助，不单独判定），启动同样经外置 Winlator（目录 + 主程序；Runtime 允许 `.bin` 且优先 `cs2.exe`）。

**实验接入：Ethornell / BGI（ethornell-rs）** 已接入扫描、内置宿主启动与 SJIS/GBK、字体设置；原创像素、基础 ABI、退出重入与合成VM持久化已有设备证据，合法样本的三语言选择、正常标题、中文菜单与两句真实中文推进已视觉验证。但商业存档槽位尚无实际保存，精确ABI与完整兼容仍待验收，不能统称可玩。API26当前测试硬件无可用GPU adapter，不代表所有API26设备不支持。App 存档管理默认关闭；完整 IME、BMV、WMV、实时 SWF 与电影音轨尚未支持。进度见 [移植计划](docs/Ethornell-BGI-Android化移植计划方案.md) 和 [测试验收记录](docs/Ethornell测试验收记录.md)。

YU-RIS（Windows）游戏通过外置 Winlator（winlator-cn）运行：扫描按 `yscfg.dat`、`pac/*.ypf`、引擎 DLL 与 `.ymv` 特征识别，启动时以「游戏目录 + 主程序 exe」经 Winlator 外置启动协议拉起（目录由 Winlator 自动分配空闲盘符临时挂载，`save=false` 不写回容器配置）；主程序自动选择，可在游戏详情「启动文件」手动覆盖。主 App 可统一管理其游戏目录 `save/` 中的存档；Winlator 的运行环境仍由外部应用负责。

内置 Web 运行环境同时支持部分以 `app.asar` 打包的 NW\.js 游戏；启动时会根据归档内容进一步识别具体类型。
Ren'Py 与 RPG Maker XP/VX/VX Ace/mkxp-z 当前通过外置 APK 模块运行：Ren'Py 支持 8.5 / 7.7.1 版本，可在全局或单游戏设置中选择引擎版本；自动模式会读取 Ren'Py `script_version` 与 Python2 运行库特征，在 8.5 / 7.7.1 模块间匹配。主 App 默认启用该能力，仅在引擎页检查目标模块是否已安装；未安装时引擎 item 显示打叉并提示下载安装。RPG Maker MV/MZ 属于 Web runtime，继续使用内置 Web 运行环境。

Ruffle（SWF / Flash）同样通过外置 APK 模块运行：扫描默认把每个顶层 `.swf` 作为一条游戏记录（文件 URI，启动时取所在目录）；仅当目录是叶子目录、根含 `index.html/index.htm` 且恰有一个顶层 `.swf`，并且没有子目录、KRKR/ONS/XSYS4/主机 ROM 或 Tyrano/RPGM/VN/Node 特征时，才把整个目录识别为一条包装游戏。无根 index 的单 SWF、同目录多个 SWF 均按文件逐条入库，包装目录含子目录时不会自动展开；单 SWF 标题取父目录名，多 SWF 取文件主名。模块需单独安装，引擎页展示安装状态（分类显示时归入「网页」），未安装时入口打开项目托管的 RufflePlugin-1.02.00.apk 直链；启动向模块下发 `game`（`type=flash`）与带 `int`/`string` 包装的 `settings`。设置支持全局与单游戏覆盖渲染后端、画质、缩放、黑边、加载方式与自动关闭；`configuration.json` 仅在文件已存在且为合法 JSON 对象时同步受管键（不创建、不删除，跳过共享多 SWF 目录与存储根）。插件自管运行时与存档，主 App 不提供统一存档管理与虚拟按键重映射；插件固定横屏，首次启动可能需要主 App「所有文件访问」以解析真实路径。设备/插件运行验证尚未执行，不能据此声明 SWF 兼容。协议与边界见 [外置 Ruffle 模块接入方案](docs/外置Ruffle模块接入方案.md)。

第三方 APK 运行时模块（包名 `com.tyranor.runtime.*`，声明 `com.tyranor.runtime.action.LAUNCH`）不会自动启用：安装后在侧边栏「妙妙工具 → 引擎模块管理」中开启，且模块需在 `res/raw/tyranor_runtime.json` 声明 `extensions` / `markers` 才能参与扫描；扫描是唯一入库入口。

PC 游戏（任意 Windows 程序）通过游戏页顶栏「添加 PC 游戏」手动入库（不参与扫描）：选择目录并从目录检索 exe、指定启动程序；启动经外置 Winlator，启动文件可在游戏详情中随时切换。PC 游戏不纳入统一存档管理；单游戏 Winlator 设置提供「跟随全局 / 跟随容器 / 自定义」三种运行模式，自定义下可逐项覆盖参数；容器同时填写 ID 与名称时以 ID 为准，只填名称时会清除继承的容器 ID。**从 1.61 升级时注意**：只填过容器名的旧记录会改为按名称选择容器，不再沿用全局容器 ID；六项参数全空的旧记录会归为「跟随容器」，其 `save` 固定为不写回容器（1.61 会继承记录或全局的 `save`）。 切换运行模式并保存不会清空另一种模式已填的参数，切回即可恢复；若把数据降级到忽略 `winlator_mode` 的旧版本，这些隐藏值会重新生效。

PSP 与 Nintendo Switch 游戏通过外置模拟器跳转运行：扫描按 ROM 扩展名识别并逐条入库（.iso/.cso/.pbp/.chd、.nsp/.xci/.nca/.nro），启动时按类型拉起已安装的 PPSSPP / Eden（显式组件 + 读取授权，使用 SAF 内容 URI；文件回退路径经 FileProvider 转换）。这两类游戏的存档与设置由模拟器自行管理，主 App 不接管；引擎页「外置跳转支持」入口可查看安装状态并跳转下载页。引擎页可在应用设置中开启「引擎页分类显示」，按 GAL / RPGM / 主机 / 网页 分页展示引擎项。

### Winlator 图形加速

全局 Winlator 设置与单游戏覆盖可选「跟随容器配置」、DXVK、WineD3D（跟随容器渲染器）、WineD3D + OpenGL、WineD3D + GDI。需要 GDI 的游戏请选择明确的 GDI 预设；旧 WineD3D 设置不迁移、不强制改为 GL 或 GDI。

显式 GL/GDI 预设通过现有 `dxwrapperConfig` 协议整项覆盖 WineD3D 配置，其它子参数使用 Winlator 默认值；开启「写入容器配置」时也会保存该预设。Box64 预设可同时下发。该能力适用于带外置启动协议的 winlator-cn，不代表任意 Winlator 分支均支持。

### 存档同步（WebDAV）

在隐藏侧边栏「妙妙工具」中启用并配置 WebDAV。总开关默认关闭，关闭时设置不可用、单游戏版本选择、同步与身份配置隐藏（未完成切换恢复入口保留），已保存的配置和历史保留。启用后默认手动同步，可在设置页按拉取、上传或双向同步整个游戏库；单游戏存档管理提供「WebDAV 同步」和「云端游戏标识」。同步提示默认开启，可关闭。

可选自动拉取、自动上传或双向：打开应用时，每个游戏只检查并拉取云端当前最新版本；退出游戏仅上传内容有变化的存档；自动同步仅使用非计费网络，手动同步不受此限制。拉取只把该最新版本加入本地存档版本，不会批量下载云端历史，也不能直接浏览或恢复尚未拉取的云端版本。始终由用户手动选择本地版本并确认切换，切换前先保留当前存档。游戏运行中禁止上传和切换，拉取仍可用。

下载前会按远端归档大小检查本地可用空间；空间不足会直接提示“存储空间不足”，不进入自动网络重试。普通写入、刷新、关闭或原子替换失败按本地 IO 返回，损坏的临时下载文件会清理。

游戏首次启动前也可切换已拉取的版本；启用自动上传时，该次切换可正常同步，无需先启动游戏来生成宿主记录。

WebDAV 关闭时，游戏启动不会解析同步存档目录，也不会进入同步目录锁。启用后，启动记账所需的可选目录解析若失败，按 `Unresolved` 只走 mutation 门禁与启动宽限，不解析目录锁，也不阻断引擎启动；真正的同步任务仍严格检查实际存档目录。

自动上传任务绑定游戏启动时的实际存档目录。任务待上传期间修改独立存档设置，会以“存档位置已变化”暂停并保留原任务；切回原设置后可重试。切换版本前，应用会为当前存档目录建立持久“目录恢复标记”，共用该目录或使用其父子目录的不同游戏入口都会等待版本恢复和旧同步任务清理完成，避免把恢复后的存档当成另一游戏的新进度。原目录的存档文件不会被迁移或删除。

旧版未完成切换会显示“处理未完成切换”。当 legacy/stale promotion 的目录或 revision 缺失、为空或与当前目录不匹配时，处理会保存当前正在使用的存档（即使它还没有出现在版本列表）、保留仍可验证的旧版本并清除无法确认归属的旧恢复信息，但不会导入任何存档；之后可正常同步当前进度，旧云端版本也不会被误认为已被当前档替换。该处理适用于所有已接入统一存档管理的引擎；MV/MZ 互通仍受其独立开关与格式兼容边界的约束。用户不需要先临时切换旧版本；如果当前版本已经出现在列表中，也可以选择它重新导入以建立新的恢复链。关闭 WebDAV 时，应用会记下当时尚未完成的旧同步任务；后续清理只结束这些旧任务。即使很快重新开启或应用重启，之后启动的游戏也会重新建立基线，不继承关闭前遗留的待上传任务。启动前忙碌判定还会按持久 generation 忽略旧 ticket 与旧启动宽限，但仍会探测独立的宿主进程；真正或无法确认仍在运行的宿主仍按保守规则阻止上传与切换；没有有效 native 会话时，即使系统不给进程列表，也会依据持久宿主记录判断，缺失或确认已退出的记录不会永久阻塞同步。损坏的宿主缓存会保留并隔离，无法确认退出时继续保护存档；正常重新启动并退出游戏可重建宿主记录。

同步期间修改服务器、用户名、口令、模式或保留策略，会让旧传输在下一请求边界停止，但保留待上传任务、session 和启动宽限；下一轮使用新配置继续。此类本地状态变化显示独立的“本地状态已变化，请重试同步”，不会再冒充“游戏运行中”。删除游戏时也保留具体同步错误原因，例如仍在运行、本地 IO 失败或选择未完成，而不是统一显示存档目录不可用。

本地每个游戏最多保留 **5** 个不同内容的存档版本。新增版本、历史维护和恢复淘汰前都会保护当前所选版本、切换前版本，以及仍可能用于恢复 legacy/stale promotion 的有效 source。云端保留上限是单一全局设置，可选 **10（默认）/ 20 / 无上限**，并按每个游戏分别计算。活动存档目录中的「当前存档文件 N」是游戏内部文件数，不计入版本上限。如果先切换到旧版本 S，再继续游玩并保存出新版本 N，界面仍把这次操作显示为「S 时间→N 时间」；同步后用 N 替换云端中的旧 S（及同内容副本），不把两者拆成两段独立历史。同内容不重复保留，时间以 `yy.MM.dd HH:mm` 显示。

每游戏每轮最多删除 **20** 个远程旧对象。超过上限时会显示“云端历史清理尚未完成，将在后续同步继续”并安排后台 fallback；打开/退出 fallback 各用稳定记录累计本链存储不足的游戏 URI，新排程不取消在途传输，旧请求也不能清掉新请求的排除集，等待网络期间不因七天到期而丢失；待裁剪 checkpoint、pending session 和同内容清理意图都会保留，下轮重新确认新版本有效后继续，不会重新上传相同 ZIP，也不会拆断既有 S→N 链。清理前会先重新验证云端档案；即使当前存档为空或无法创建上传副本也可续清，但验证空间与 checkpoint 写入必须成功，验证失败不删旧版本；20 次预算耗尽不会阻挡同轮发布新存档，临时文件空间不足统一显示为存储不足并停止自动重试。

新版本存入 `TYN-saves/<引擎>/<可读标识>--<12 位目录摘要>/`，旧 `TYN-saves/v1/` 布局继续可读，不作批量迁移。跨设备默认按引擎和游戏目录名匹配；存档管理页显示为「云端游戏标识」（即同步身份），可自定义。升级已有 Debug 安装时保留保留量迁移和早期本地选择标记迁移，不要求重传历史。服务器与凭据加密保存，并排除系统备份和设备迁移；无法解密时可确认重置配置后重新填写，历史存档保留。

配置快照使用独立的 `config_revision`。保存或重置配置时，加密载荷变更与 revision 递增在同一次 prefs 提交中完成，并在本地事务入口和真实 HTTP 请求入队前复核；revision 变化不会推进关闭功能的 generation，因此不会清掉仍然有效的 session。

存档管理页的普通 ZIP 文件导入继续使用原有导入行为，不写入存档版本激活所用的手动选择标记，也不承诺受保护的定向导入。

目录 owner 会一直保留到待处理记录清理成功；清理前先持久化本次完成结果，因此即使进程在记录已删除、owner 尚未释放之间退出，也能按已知结果收尾。未提交的失败会恢复先前目录 epoch；旧入口遗留的 token 只能续接自己的当前 epoch 或无 revision 的 legacy 空 epoch，不能把后来 alias 的 revision 回退。版本切换在目录提交前报错时，先保留所选版本并完成必要回滚；清理成功时清除本次待处理记录，避免后续同步被失败尝试阻塞。清理失败时保留激活记录，并将清理错误附加到原异常。未生效的新选择不会仅因临时恢复文件不匹配而清除已完成的旧恢复链。目录已提交后的重试只完成资源与元数据收尾，不会自动再次导入活动存档。上次切换长期未完成时，可明确放弃其版本信息后再切换；“处理未完成切换”会保护当前存档和可校验的历史版本，五版历史已满也会同时保留两者，救档失败时保留恢复材料供重试。该入口关闭 WebDAV 后仍可用。

切换暂存 ZIP 损坏或丢失时，恢复可复用独立校验有效的同内容历史副本；同一次已提交选择的 S→N 链仍保留。历史超出五版时先隔离损坏 ZIP，再按有效版本淘汰，坏文件不会挤掉可恢复的旧版本。

如果继续游玩后再次明确选择 S，系统仍会实际切换回 S，不会把旧事务收尾当作切换成功；先上传恢复版 S 再保存 N 也保留 S→N 更新链。网络等待不持有本地版本锁；每个 DAV exchange（包括传输内部发起的嵌套请求）在发出前重新校验持久 generation、关键 revision 与目录归属，已准入的单次请求可自然完成，后续请求再次准入。移除游戏同时清理该游戏本地同步历史与状态，不删除云端版本；清理失败保留游戏库条目供重试。导入中断后无法证明已提交的旧备份，以及全零文件导入前的旧档，会保留为相邻 `.import_orphan_<UUID>`，不会仅因目标目录存在而删除。恢复副本最多新增三份不同内容，相同内容经校验后合并；三份均不同时，新的保留操作会提示存档目录不可用，并保留旧备份供重试，全零导入在交换当前存档前即检查此限制。遇到该情况，先用具备存档目录访问权限的文件或调试工具完整备份这些恢复目录，核实后移走一份不再需要的副本，再重试；当前没有专门管理这些恢复副本的界面。既有超过三份的不同内容副本仍保留。删除游戏只清理门禁内当前生效的应用专属存档目录及其精确匹配的 orphan，旧独立目录、镜像父目录、索引与其它根继续保留；游戏目录中的文件仍由用户管理。也可在完整备份后明确删除存档，该操作先回补恢复副本中唯一的引擎资源，再清除存档及精确匹配的恢复副本。

仅支持已接入统一存档管理的游戏；PC、安卓游戏及第三方 APK 运行时模块不参与，也不会因同名目录造成整库同步身份冲突。受支持游戏的存档暂不可读时仍保留其云端身份占用。YU-RIS 使用外部 Winlator。请先退出 Winlator 中的游戏，再到 TYN 的存档管理页明确确认已退出；确认前继续保护存档，阻止上传、版本切换与删除游戏。外置运行标记跨返回前台和主进程重建保留，切回 TYN 本身不会被当作退出，也不会自动完成待上传会话；该确认入口不依赖 WebDAV 总开关。

### 平台与文件要求

- Android 8.0（API 26）及以上。
- 当前原生引擎库仅提供 `arm64-v8a`，设备需为 64 位 ARM 架构。
- 游戏目录需位于 Android 可访问的本地存储，并通过系统文件选择器（SAF）授权；启动时目录必须能够映射为真实文件路径，外置存储上的部分引擎可能需要“所有文件访问”权限。
- 实际兼容性取决于游戏使用的引擎版本、封包/加密方式和脚本特性；特殊修改版可能需要调整引擎设置或补丁。


## 参与贡献

欢迎参与项目开发与维护！

在提交 Pull Request 前，请先阅读务必阅读遵守 [贡献指南](./CONTRIBUTING.md)。
如果不符合贡献指南的要求，可能会被拒绝合并。
代码务必符合项目整体风格，避免引入新的问题。
提交前请务必使用项目内AGENTS.md文件内的审核方式进行提交前审核。
如果代码一眼丁真，一定拒绝合并。

## 技术架构

开发与维护文档：[仓库规范](AGENTS.md) · [架构说明](ARCHITECTURE.md) · [功能规格与验收清单](SPEC.md) · [领域术语](CONTEXT.md) · [变更记录](CHANGELOG.md)。专题方案与 `.archify/` 图表保留其生成时的上下文；当前实现以源码及上述架构/规格说明为准。

### 模块划分

| 模块       | 职责                                                                                            |
| -------- | --------------------------------------------------------------------------------------------- |
| `app`    | Android 应用壳：Compose UI、功能抽象层、配置、封面、存档、授权、后台更新等应用侧能力                                           |
| `engine` | 底层引擎运行时核心：SDL2/SDL3、Kirikiri TVP、krkrsdl3、ONScripter、Artemis、Siglus、FVP（rfvp）、Tyrano、Native/JNI 与引擎宿主 Activity |

### 三层目录架构

当前项目按职责归类为三层，依赖方向固定为：

```
界面 UI 交互层 -> 功能抽象层 -> 底层引擎层
```

| 层级        | 目录                                         | 职责                                                                                                     |
| --------- | ------------------------------------------ | ------------------------------------------------------------------------------------------------------ |
| 底层引擎层     | `engine/`                                  | KRKR/Kirikiroid、krkrsdl3、ONS、Artemis、Siglus、FVP（rfvp）、Tyrano、SDL/Cocos/IJK、Native/JNI、引擎宿主 Activity、引擎资源与 Native 插件底层加载 |
| 功能抽象层     | `app/src/main/java/com/tyranor/next/core/` | 游戏扫描、游戏模型、启动编排、封面抓取、存档管理、在线补丁、应用/引擎/单游戏配置、授权、后台更新                                                      |
| 界面 UI 交互层 | `app/src/main/java/com/tyranor/next/ui/`   | Compose 页面、Activity 壳、弹窗、导航、顶部栏、搜索框、用户输入、加载态与错误态                                                       |

功能抽象层按领域继续拆分：

- `core/game`：游戏模型、扫描、启动、存档管理
- `core/engine`：引擎类型、内置引擎插件启动与安装编排、外置 APK 引擎模块注册与启动协议；`core/engine/runtime` 负责第三方 APK 运行时模块的发现、启用与交棒
- `core/cover`：封面抓取、来源聚合、批量任务
- `core/patch`：KRKR 在线补丁
- `core/settings`：应用级配置、引擎级配置、单游戏配置
- `core/auth`：Hikarinagi OAuth 授权与 token 管理
- `core/updater`：后台更新检查与通知
- `core/unpack`：Artemis 启动封包解包辅助；XP3 解包/封包独立工具的核心逻辑与 SAF 中转

UI 层按页面域继续拆分：

- `ui/main`：主入口、底部导航与全局页面状态
- `ui/home`：首页
- `ui/game`：游戏库、游戏卡片、封面操作、游戏动作弹窗
- `ui/engine`：引擎页
- `ui/settings`：应用设置、引擎设置入口、引擎/单游戏设置页面
- `ui/runtime`：引擎模块管理页（第三方 APK 运行时模块的发现与启用）
- `ui/cover`：封面来源与批量抓取设置
- `ui/patch`：KRKR 在线补丁页面
- `ui/save`：存档管理页面
- `ui/archive`：XP3 解包 / 封包独立工具页
- `ui/auth`：OAuth 回调 Activity
- `ui/common`：公共 UI 组件、弹窗、占位页、顶部栏/输入框等复用组件

### 技术栈

- **语言**：Kotlin（引擎层含 Java 桥接代码）
- **UI**：Jetpack Compose + Material 3 + [Miuix](https://github.com/compose-miuix-ui/miuix)
- **导航**：底部导航 `NavigationBar`；主 Tab 内容页使用水平移动切换，详情/设置等独立 Activity 进入使用向上翻页、退出使用向下翻页
- **顶栏与外观**：顶栏与正文恢复上下分离，不叠加顶栏渐进模糊（[#133](https://github.com/Weiss-UltimateSavior/Tyranor-Next/issues/133)）；默认背景渐变与底栏/侧栏/悬浮按钮的玻璃效果不变
- **构建**：Gradle 9.5.1 / AGP 9.2.1 / Kotlin 2.x + Compose Compiler，`compileSdk 37`、`minSdk 26`、`targetSdk 36`
- **持久化**：Room v3（游戏库、扫描根、最近打开时间、快捷启动、单游戏覆盖、识别缓存与运行时模块绑定）+ SharedPreferences（应用/引擎设置、跨进程配置及旧库恢复镜像）
- **文件访问**：Storage Access Framework（SAF）管理外部游戏目录，`documentFile` 库辅助

### 引擎集成设计

- 引擎原生插件（`kirikiroid2` / `ons` / `artemis` 的 `.so`）以 assets 形式随 APK 打包（`nativeplugins/`），首次启动由 `NativePluginManager` 自动解压安装到应用私有目录
- 共享 Native 插件 `.so` 的源头位于 `engine/src/main/nativeplugins`；app 侧仅维护插件 `manifest.json` 和 app-only 插件。构建时由 Gradle 合并生成 `app/build/generated/assets/nativeplugins/*.zip`
- 共享 RPG Maker 注入脚本源头位于 `engine/src/main/assets`；app 侧只保留应用专属注入脚本，构建时由 Gradle 同步生成到 app assets，避免两边手工维护重复文件
- `app` 模块通过 `core/game/launch/EngineLauncher` 将扫描结果映射到对应引擎 Activity 启动（SAF URI → 真实路径转换）
- Ren'Py、RPG Maker RGSS、Ruffle 等外置 APK 引擎模块由 `core/engine/external` 统一注册、检测安装状态并按 intent 协议拉起；主 App 不维护手动启用开关，模块安装即视为可用。Ruffle 使用 `launchEntryIsFile` 的文件型入口，启动前把 SWF 文件记录解析为父目录再下发
- 第三方 APK 运行时模块由 `core/engine/runtime` 按 `com.tyranor.runtime.` 前缀与 `LAUNCH` action 动态发现，在侧边栏「妙妙工具 → 引擎模块管理」默认关闭、按模块启用；扫描仅使用模块 `res/raw/tyranor_runtime.json` 声明的特征，启动经显式组件只传入游戏文件夹路径，模块自管运行、设置与存档
- PSP / Nintendo Switch 由 `core/engine/external` 的 `ExternalEmulatorRegistry` / `ExternalEmulatorLauncher` 跳转外置 PPSSPP / Eden：扫描识别 ROM 后逐条入库（一 ROM 一条），运行时在目录解析前分流、按显式组件与读取授权启动；引擎页「外置跳转支持」项展示安装状态并可跳下载页，Manifest `<queries>` 已声明对应包名
- Tyrano 运行环境内置本地 HTTP 服务器、Asar 归档解析与 JS 钩子脚本（`__tyrano__.js` 等），无需外部依赖即可运行网页式脚本游戏
- FVP（rfvp）宿主为 Java `SurfaceView` + `Choreographer` 主循环驱动 Rust 引擎（`librfvp.so` + 自研桥接库），随 APK 打包；支持 `*.hcb` 原版与 `*.bch` 汉化脚本，文本编码（Shift-JIS/GBK/UTF-8）、系统字体回退与文本 HiDPI 可在引擎设置中按全局/单游戏调整
- RealLive / AVG32 / UK2 由 `game_launcher` 运行库承载（与 Siglus 同一 `libsiglus.so`，由 `libgames_bridge.so` dlopen），宿主 `FramebufferGameActivity`（`:fbgames` 进程）每帧驱动 `game_fb_*` 帧缓冲：单指=左键、双指点按=右键、双指上下滑=滚轮、Back=右键，文本编码（自动/Shift-JIS/GBK/Big5/UTF-8/韩语）可按全局/单游戏调整
- 原生库仅提供 `arm64-v8a` 架构

## 可视化文档

`.archify/` 下随仓库提供一组基于当前代码生成、经自动化校验（schema 校验 + 真实浏览器渲染检查）的交互式图表：均为自包含 HTML，浏览器直接打开即可缩放、检索/聚焦节点、切换深浅主题并导出图片；每个目录内同时保留可编辑的 `candidate.json` 源数据与校验回执，可配合 archify skill 重新渲染。

| 图表 | 类型 | 覆盖内容 |
| --- | --- | --- |
| [系统总览](.archify/架构图-系统总览-20261006-022735/architecture-overview.html) | 架构图 | 三层架构、主进程 / 引擎进程族边界、外置模块与模拟器、在线服务集成 |
| [引擎运行时与原生插件体系](.archify/架构图-引擎运行时-20261006-022735/architecture-engine-runtime.html) | 架构图 | 插件供给链、宿主与 JNI 桥接、预编译运行库、Rust XP3 与 Web 壳 |
| [应用功能地图](.archify/功能图-应用功能地图-20261006-022735/architecture-feature-map.html) | 功能图 | 六大功能域 × 18 项能力及对应实现组件 |
| [游戏扫描与入库流程](.archify/流程图-游戏扫描入库-20261006-022735/workflow-scan-import.html) | 流程图 | SAF 授权、目录遍历、引擎识别优先链、差量合并与 Room 落库 |
| [游戏启动编排流程](.archify/流程图-游戏启动编排-20261006-022735/workflow-launch-orchestration.html) | 流程图 | 四级分流（安卓 / 模拟器 / 外置模块 / 内置引擎）与启动前确认 |
| [KiriKiri 启动请求时序](.archify/时序图-KiriKiri启动-20261006-022735/sequence-kirikiri-launch.html) | 时序图 | 插件保障 → SAF 镜像 → Intent → JNI 桥接 → TVP 就绪全链路 |
| [游戏库数据与封面管线](.archify/数据流图-游戏库与封面-20261006-022735/dataflow-library-covers.html) | 数据流图 | 采集、识别、Room 权威存储、prefs 镜像、封面缓存与消费方 |
| [RPG Maker MV/MZ 存档生命周期](.archify/生命周期图-存档互通-20261006-022735/lifecycle-save-interop.html) | 生命周期图 | 互通同步、格式转化、延迟回前台与中止等状态 |

## 构建

```bash
# 编译 Debug APK
./gradlew assembleDebug --no-daemon
```

产物位于 `app/build/outputs/apk/debug/`。需要 Android SDK（本地平台 `android-37.0`）环境。

CI 使用 JDK 17；还需 engine 的 Android 36 平台、Build Tools 37.0.0、NDK 28.0.13004108 与 CMake 3.22.1。Windows 使用 `./gradlew.bat`。JVM 回归运行 `./gradlew testDebugUnitTest --no-daemon`，完整检查命令与设备验收边界见 [AGENTS.md](AGENTS.md#构建)。普通 Gradle 构建使用已提交的运行库，不自动重编所有原生引擎。

## 目录结构

```
app/
├── src/main/java/com/tyranor/next/
│   ├── core/      功能抽象层：扫描、启动、引擎模块、封面、存档、补丁、拆封包、配置、授权、更新
│   ├── ui/        界面 UI 交互层：主界面、游戏页、设置页、弹窗、公共组件
│   └── theme/     Compose / Miuix 主题、色调、深浅色适配
├── src/main/assets/rtp/    App 自有 RTP 资源
├── src/main/nativeplugins/ 插件 manifest 与 app-only 插件源头
├── build/generated/assets/ 构建生成的 engine/ 共享脚本与 nativeplugins/ 插件 zip
├── schemas/                Room schema（随代码提交）
└── src/{test,androidTest,releaseProbe}/ JVM、设备与 release probe 测试

engine/
├── src/main/java/          底层引擎宿主、桥接代码与运行时入口
├── src/main/cpp/           本地编译的 Native/JNI 与图形桥
├── src/main/assets/        底层引擎资源与共享引擎脚本源头
├── src/main/nativeplugins/ 共享 Native 插件 so 源头
├── src/main/jniLibs/       预编译原生运行库（arm64-v8a）
├── src/test/              JVM 回归与 Node Web 桥 harness
└── rust/                   XP3 解封包 Rust 源码（xp3 解析与 KSD mode-2 隐性解码；许可证见 LICENSE.usefulunpack）

buildSrc/、tools/、.github/workflows/  构建校验、工具与 CI
docs/、.archify/                     专题方案、审查记录与可视化
```

## 许可证

本项目基于 **GNU General Public License v2.0** 发布，详见 [LICENSE](LICENSE)（GPL-2.0-only）。

- `engine/` 引擎运行时基于 Tyranor 模拟器逆向重写，上游涉及 Kirikiroid2 / ONScripter 等 GPL-2.0 项目，因此整个项目以 GPL-2.0 授权分发
- 基于本项目发布的衍生作品须遵循 GPL-2.0 条款，并随发行物提供完整源码
- Miuix 等第三方依赖按各自许可证引入
- Siglus / RealLive / AVG32 / UK2 引擎运行库来自 [siglus_rs](https://github.com/xmoezzz/siglus_rs)（MPL-2.0，上述引擎的非官方 Rust 重写/移植），以其自身许可证引入；相应源码可于上游或其 fork 仓库获取
- FVP 引擎运行库来自 [rfvp](https://github.com/xmoezzz/rfvp)（MPL-2.0，FVP 引擎的非官方 Rust 重写/移植），以其自身许可证引入；相应源码可于上游或其 fork 仓库获取
- System35 引擎运行库来自 [xsystem35-sdl2](https://github.com/kichikuou/xsystem35-sdl2)（GPL-2.0-or-later，AliceSoft System 3.x 开源重实现的 SDL2 移植），引擎源码与修改声明见 fork [xsystem35-sdl2](https://github.com/Weiss-UltimateSavior/xsystem35-sdl2)；本仓库仅保留预构建运行库、构建剧本与补丁记录（见 `engine/xsystem35/BUILD.md`）；其依赖 SDL2 / SDL2_mixer（zlib）、freetype（FTL/GPLv2）、libwebp（BSD-3-Clause）按各自许可证引入，许可证文本随 `engine/xsystem35/licenses/` 保留
- System4 引擎运行库来自 [xsystem4](https://github.com/nunuhara/xsystem4)（GPL-2.0-or-later，AliceSoft System 4 引擎的开源重实现），引擎源码与修改声明见 fork [xsystem4](https://github.com/Weiss-UltimateSavior/xsystem4)；其依赖 libsys4（GPL-2.0-or-later，上游 [libsys4](https://github.com/nunuhara/libsys4)）同样取自 fork [libsys4](https://github.com/Weiss-UltimateSavior/libsys4)。Android 构建仅参考 `kichikuou/xsystem4-android` 的事实信息，未拷贝其任何文件。静态链入的依赖包括 libsndfile fork [libsndfile](https://github.com/Weiss-UltimateSavior/libsndfile)（`xsystem4` 分支，LGPL-2.1-or-later）、libogg / libvorbis / libwebp（BSD-3-Clause）、libjpeg-turbo（BSD-3-Clause / IJG）、libpng、libffi / libdeflate / cglm（MIT）及 freetype（同 System35）；SDL2（zlib）与 System35 共用 `libSDL2x.so`；xsystem4 与 libsys4 内的第三方例外（如 cJSON、pl_mpeg（MIT）、queue.h、base64.c（BSD）、kvec.h（MIT；`vector.h` 与之同声明）、sts_mixer（Unlicense，公有领域）、mt19937int（libsys4，LGPL-2.0-or-later 声明））按各自许可证引入。预构建运行库、构建剧本与补丁记录见 `engine/xsystem4/BUILD.md`，许可证文本随 `engine/xsystem4/licenses/` 保留
- Anime4K GLSL 着色器（`engine/src/main/assets/anime4k/`）来自 [bloc97/Anime4K](https://github.com/bloc97/Anime4K)，按其 MIT 许可证引入（版权声明保留于各着色器文件头部）

## 致谢
- [Kirikiroid2](https://github.com/zeas2/Kirikiroid2): Kirikiroid2引擎
- **Tyranor 模拟器**：本项目引擎运行时与核心架构的逆向重写基础
- [Artemis-Compat](https://github.com/Weiss-UltimateSavior/artemis-compat)：自研兼容内核
- [siglus_rs](https://github.com/xmoezzz/siglus_rs)（MPL-2.0）：SiglusEngine / RealLive / AVG32 / UK2 的 Rust 重写与多平台移植，四个引擎的运行时
- [rfvp](https://github.com/xmoezzz/rfvp)（MPL-2.0）：FVP 引擎的 Rust 重写与多平台移植，FVP 运行时
- [xsystem35-sdl2](https://github.com/kichikuou/xsystem35-sdl2)（GPL-2.0-or-later）：AliceSoft System 3.x 引擎的开源重实现（SDL2 移植），System35 运行时；依赖 [SDL2](https://github.com/libsdl-org/SDL) / [SDL2_mixer](https://github.com/libsdl-org/SDL_mixer)（zlib）、[freetype](https://freetype.org/)（FTL/GPLv2）、[libwebp](https://chromium.googlesource.com/webm/libwebp)（BSD-3-Clause）
- [xsystem4](https://github.com/nunuhara/xsystem4) 与 [libsys4](https://github.com/nunuhara/libsys4)（GPL-2.0-or-later）：AliceSoft System 4 引擎的开源重实现及其底层库，System4 运行时；Android 构建参考 [xsystem4-android](https://github.com/kichikuou/xsystem4-android)（kichikuou），仅作事实参考。依赖 [libsndfile](https://github.com/kichikuou/libsndfile)（LGPL-2.1-or-later，`xsystem4` 分支）等，见「许可证」
- **RinneMobile**：游戏扫描识别/SAF路径映射逻辑/独立存档映射/krkrsdl3 等多个功能的参考与实现
- [Miuix](https://github.com/compose-miuix-ui/miuix)：设置界面组件库
- [Anime4K](https://github.com/bloc97/Anime4K):（bloc97，MIT）：KRKR 游戏画面实时超分（线条重建 CNN 着色器）
- [OnscripterYuri](https://github.com/YuriSizuku/OnscripterYuri): ONS引擎
- [UsefulUnpack](https://github.com/znso4pa/usefulunpack)（znso4pa，MIT）：XP3 / KSD 解封包 Rust 实现（`engine/rust` 的 `lib.rs` / `common.rs` / `ksd.rs` 来源，许可证见 `engine/rust/LICENSE.usefulunpack`）
- 各引擎运行时均基于其开源许可引入
