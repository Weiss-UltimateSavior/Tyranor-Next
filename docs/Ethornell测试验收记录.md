# Ethornell 测试验收记录

## 当前验收快照（2026-10-16，实施中）

**结论：当前`9e2fef…`中文标题/两句正文/正常退出已过；真实slot数据准备/保存流程未闭环，无date/thumbnail/file且未重启读回，仍禁管理。** 空slot无确认不判失败；producer复核指向跨VM procedure双输出/共享heap待查，不把堆指针当bool、不硬写ready。protocol=true、DEVICE_SAVE_IO=false/available=false，发布仍blocked。

### 当前verified摘要（历史候选仅见下方时间线）

| 项目 | 当前结果 | 证据边界 |
|---|---|---|
| Rust 来源 | 外部 ethornell-rs `5556c36b53c9af29a19ba507ffa431b19300f0e7` + dirty 实施改动 | revision 单独不能复现当前产物；最终 hash、构建 manifest 与修改源码仍待固定 |
| 最新诊断候选 | `9e2fef…`已取得真实gate trace；code1002集成时父exact APK static与full JVM构建14s通过、cache reused | 诊断只读、不等商业修复；旧E0599仅编辑中历史，最终关trace后按固定hash重验 |
| Rust 六crate回归 | 父统一确认：651 passed、0 failed、2 ignored、2 skip（旧missing fixture） | 取代早期代理612项及此前filtered口径，不累加；ignored/skip不算通过，不代表设备兼容验收 |
| 验证工具 / archive budget | CI tools最新75项、archive budget15项通过，diagnostic C++ assert真实通过 | 取代旧计数，不累加；不是游戏兼容结果 |
| C++ 诊断断言 | 父独立执行 diagnostic C++ assert，真实通过 | 仅对应诊断断言覆盖范围，不替代GPU、生命周期或游戏兼容验收 |
| App/engine full tests与strings | 父最新unit：App567tests、0fail/1skip；engine250、0fail；protocol flag后full unit+strings构建已有24s/cache stored成功证据 | 新快照不加旧计数；落地/回归通过不等用户完整兼容目标已证实 |
| Rust最新聚焦回归 | 父独立script22、VM209通过；tools75通过 | 新增profile测试晚于候选，不能据测试通过推断该源码已进入库；不累加旧快照 |
| configuration cache 重复检查 | 最新完整构建明确 `REUSED` 并成功，TaskProvider/序列化问题已解决 | 取代此前 reused 但编译失败的旧快照；不等于 native 或设备生命周期验收 |
| 隔离 APK / test APK 安装 | `com.tyranor.next.ethornelltest` 与其 test APK 分别通过 Android CLI install | 原用户 App 未覆盖；两包分别安装，不用同一次 `--apks` 逗号传入（会触发重复 `Split null`）；安装不代表 JNI/GPU 成功 |
| API36 / 16KB 核心 fixture suite | API36/SJIS、16KB/SJIS与16KB/GBK各 **4 pass / 1 assumption skip**：UTF-8/非BMP、线程/token、BP Closed、RTLD_NOW/14ABI、RGBW/黑色3×3像素与重挂/旧generation拒绝均通过 | runner `OK (5 tests)` 不报为5项全通过；无参数 optional real-game skip不是成功，另行提供样本的短冒烟见下表 |
| API26 动态与 GPU | RTLD_NOW/create/线程通过；该测试硬件GPU为stage2 `adapter_unavailable` | 两项图形未过，仅表明当前硬件无可用adapter，不能写成所有Android26不支持；不提高minSdk绕过验收 |
| Production Activity 正常退出/重入 | 新dispatch-token harness并清理CLI UiAutomation后，实机`closeFixtureNormallyAndReenterAfterAcknowledgedExit` 1 test PASS，1.95s；真实两轮Closed→destroy ACK→PID exit→new PID，读取TERMINAL，中间未由测试强杀；日志`ethornell-close-reenter-dispatch.log` | 仅该原创close fixture正常退出/重入链通过，不代表全部生命周期压力、issue/revoke协议或真实游戏退出通过；旧长hang/force-stop与并发harness结果作废，不算产品crash |
| 16KB Surface重复压力 | `gpuPresentDetachReattachAndGeneration` **20/20轮通过**，每轮完整四色像素/重挂/旧generation拒绝；日志`ethornell-16kb-surface-stress.log` | 合成框架loop，不等真实游戏生命周期或小时级soak |
| 实机正常close重复压力 | **10轮×两会话，共20次**Closed→destroy ACK→PID exit→new PID通过，轮间无test kill；日志`ethornell-device-close-stress.log` | 正常close路径重复成功，不外推真实游戏退出、焦点/慢调用故障或小时级长稳 |
| 暂停/时钟/CANCEL设备仪器 | 父实机与16KB均 **3/3 PASS**，最新约3.3s；真VM fractional clock BP序列/CANCEL、独立pause原因、新generation恢复RGBW；实机首轮2.838s日志`ethornell-pause-device.log` | 直接JNI+真实Kira init/pause API；不含硬件录音、Activity onPause集成或路由实测，不外推真实游戏生命周期 |
| 三语言与私有宿主 | 父独立确认 BGI App 11 / engine 5 个键各自三语言齐平，Manifest `exported=false` | 只是对应静态检查，不代表完整权限、Intent 或 native 安全审查通过 |
| 桌面旧 binary 对照基线 | 旧 desktop release binary，GBK headless 1200 frames，exit 0；父视觉确认《巧克甜恋》正常标题画面与汉化菜单 | 仅旧 binary 的桌面对照基线，非当前源码、非 Android，也未验证对白或存档；300 frames 黑图是等待不足的启动过渡，不能据此判游戏不支持 |
| 合法样本 direct-runtime 短冒烟 | 实机 `suppliedGameDirectRuntime`，GBK，真实 create/attach/step≥3帧通过，3.12s | 仅短冒烟，不是标题/对白/音画/保存读取或完整 App 启动链验收；不得外推全游戏可玩 |
| 当前真实SAVE route | 实机object10039、group2/item0 slot及group7/item0 top-save，均捕获presshit=1和BF event6=1 | click已收到；仍无date/thumbnail/file、未读回。空slot无需确认可属正常，不以无confirm定性，不将诊断当修复成功 |
| SAVE只读排除与下一门 | 真实90BF（非91BF）三DWORD packed事件正确；rawhit01实际`[code,group,item]`，统一packed解码会误显示group0/item2或7。父真trace已见event6被script pop | 这是trace解释问题，非产品BF bug。反编译BE4误名selectedSlot bool，实际call3FF4 UserData FileExists返回，已纠正、不硬修slot0；下一跟踪guestbuf写回/global1092/BC word0等待门，slot仍未过 |
| 资源三critical文件只读核验 | 父adb SHA-256逐一对比原ZIP：system.arc（前缀c04cb…）、原BGI.gdb（4e636…）、原UserData/AmairoChocolate101.sud（81e0…）均一致；检查发生于新游戏及私有UserData写入后 | 只证明这3个critical文件未改，不声称全1.3GB重hash或整个树只读；前缀不替代完整hash日志；商业slot保存/重启读回未验 |
| DA精确ABI来源 | 真实EXE研究的UTF-8 strlen/dest=NULL query修复现已进入`dc5dc2abe…` | 不再写source-only；源码入库/字节核验不替代新候选全套设备ABI测试 |
| 输入只读复核与SAVE待定位 | 独审确认实现差异：rootflags4激活后的selectNative在Rust缺失、rawhit/hoverhit合并偏差、Enter没有icon action map；same-frame FIFO无丢已有证据，已交唯一native写owner | 待真实SAVE descriptor trace，不称已找到最终slot根因；Enter无效不是独立可验证slot动作，不能将其当额外失败路径；商业slot仍未过 |
| 当前候选16KB组合回归 | `0e1b2d…`父复验 **14pass/1 optional skip、0fail，4.814s**，tools75通过；GPU/VMpersist/pause/CANCEL/private ZIP后端覆盖 | 当前hash自有证据，不借旧474（4.805s）；skip不算通过，商业slot未过，新候选须再验 |
| 真实样本手动正常退出 | 父手动Back→宿主确认→游戏自绘YES/NO确认→YES→App通过，非kill | 仅正常退出已验证；旧不明回App不能直接定性native crash，不证明slot保存成功 |
| 真实退出后的正常再启动 | 父已在手动正常game exit后再次从普通App正常启动，可持续用、不再busy | 不等slot保存/退出重开读档；SAVE事件链仍在owner回放与独立input-path复核中，无商业slot通过证据 |
| 跨进程protocol设备闭环 | 实机3/3 PASS 4.541s（`ethornell-dispatch-bootrace-device.log`），16KB3/3 PASS 12.971s；锁内metadata/owner身份、claim/终态/退出新token、revoke late拒绝、missing-resource重入均过；flag聚焦4suites/42tests零失败 | 两平台protocol层已闭环、非待适配；HOST_DISPATCH_PROTOCOL已true，DEVICE_SAVE_IO=false、available=false，管理不开放。旧harness竞态不当产品bug或安全放宽 |
| 空语言选择独立startup复核 | clean unrestricted环境无BGIgdb时同样空LanguageSelect；旧desktop已有BGIgdb，跳过此分支才显示正常标题 | 排除将其笼统归因为restricted文件/GPU；这是初始样本状态差异暴露的真实作品兼容缺口，不否认已有Android底层/用户数据/marker等证据 |
| 版本ABI/dispatch精确缺口 | Amairo 81DA builtin Int0被native ABI output0丢弃，紧接move_arg读取旧ring造成空text；81D8两参数句子表未建ABI/handler、框架不消费；81:21已有builtin但output/strict注册冲突 | 已派version-aware ABI与full-dispatch测试修复，尚未新候选通过；不导原BGIgdb、不跳LanguageSelect，必须修复实际分支 |
| 合成VM globalSDC持久化 | writer→destroy→同userRoot reader、empty-root negative Title三会话，16KB/SJIS 0.909s、实机/GBK 0.563s均父PASS；Java未造档 | 真正VM写读通过，但不等商业slot/管理闭环，cap保持false |
| Save device后端测试 | 父两环境均 **6/6 pass**：实机0.356s、16KB0.298s，覆盖私有树ZIP、fsync/inode、rollback/cancel、claim race、capability closed与恶意ZIP typed failure | 不包含商业native槽位，`DEVICE_SAVE_IO_VERIFIED=false`；不累加成不同功能12项，不开放管理 |
| Release R8 / 打包静态检查 | 含native Release R8/精确APK库/zipalign已有通过证据；父在正确Release probe闭环后再次核验exact APK native与zipalign通过 | 对应已测产物，不自动外推新候选；仍非公开发布许可通过 |
| Release设备probe | `-PethornellReleaseProbe=true`真实Release R8+resourceShrink构建44s通过；16KB安装matching纯Java test APK（23KB），NUMTEST3/FAILURES0/passed=true：release identity、RTLD_NOW/14ABI/build_info、productionJNI create/poll/destroy均PASS；日志`ethornell-releaseprobe-16kb.log` | scope精确为`release-native-load-create-only`，不含GPU/title/商业slot；未全keep或关闭R8，不能外推游戏兼容/M5通过 |
| CI strict 来源检查 | 正确拒绝 dirty 来源 | 当前不做发布；初版 hash/字节静态检查仍仅是中间产物证据 |
| 当前静态重复检查 | 父exact current Debug APK native静态、tools75与actionlint重复通过 | 仅对应当前字节/工具检查，不能外推slot、长稳或公开许可 |
| 实机扫描与编码设置 | 父在实机真实扫描库看到《巧克甜恋》识别为 Ethornell / BGI，并在 UI 确认 GBK 编码设置保存 | 扫描/设置成功不等于 native 初始化、标题或存档通过 |
| App 二次只读复审 | 原 7 项均闭环，无新增 P1 | 仅对应 App 审查范围，不外推 Rust/native、设备运行或整体安全通过 |
| Safe save 复审 | 原3项与流取消泄漏问题已闭环，capability仍为 false | 不开放管理；代码复审闭环不代表设备 native 存读档通过 |
| Rust 文件安全复审进展 | 旧P1修复成立；此前6项P2中的多数已有代码/测试并经二复核闭环 | 不将旧“6项均待修”沿用为现状；剩余项须按逐项复核记录确认，尚不外推整体安全最终通过 |
| ABI 二次只读复核 | 原 4 项（退出 cached busy、attach 错误借用、时间余数/音频 raw、cancel 手势）源码均闭环，无剩余确定 P1 | 审查结论，不是设备 ABI、Surface 或音频运行结果 |
| Workflow 静态检查 | 父使用 `go run ...actionlint@v1.7.7` 检查三份 workflow，全部 exit 0 | 只证明 workflow 静态检查通过，不代表 CI 执行或发布门槛通过 |
| 本地 source snapshot 边界检查 | 父再次核查`dist/source`173 entries，含Cargo.lock，无禁止内容（raw/testcase/target/字体/游戏二进制/不安全路径或链接） | 仅来源边界，不是可复现构建、clean public release或许可认定 |

上述测试计数对应交接时的特定源码快照与套件，不将不同时间的局部或全量统计累加；最终以父验收实际重跑记录为准。

版本ABI修复须采用显式会话profile，保留Tayutama695的null handlers，不全局篡改native table。该约束已由native owner与VM只读复核确认，实际修复验收仍待新候选。

当前验收问题简记为“真实slot数据准备/保存流程未闭环，仍禁管理”。独立scene producer复核确认准备值是8MiB堆指针而非bool：过程完成双输出后publish，未见Sys81:44调用，不写empty-placeholder根因。跨VM procedure/two-output/sharedheap继续只读复核，不硬置ready。详细脚本定位只保下方时间线，不堆在当前摘要。

语言/标题与两句正文已父验；DA精确ABI、商业slot提交未过，不沿用旧数字key/退出作现状。protocol gate已授权；存档gate仍false，合成globalSDC/ZIP不等商业slot。

### 设备与样本准备

- 已连接实机 HONOR MAA-AN10：Android 16（API36）、arm64、系统页大小 4096。
- API35_16KB 模拟器 `emulator-5554` 的 `PAGE_SIZE=16384`、API26 模拟器 `emulator-5556` 的 sdk26 已由父确认；隔离包已安装，候选库的动态加载/基础 ABI 已在两者及 API36 实机通过。**这不等于16KB/API26完整游戏运行通过**。
- 用户已提供合法持有的《巧克甜恋》zip；约1.3GB测试副本已push，GBK实机 direct-runtime ≥3帧短冒烟已通过，仍无标题/对白/存档或完整App链路通过结论。不公开样本商业内容、资源或对白。
- 自生成夹具已在API36与16KB完成上述BP/像素验证，16KB/GBK重跑也通过；仍只代表该夹具覆盖范围，不代表商业游戏完整兼容性。
- 桌面 1200 帧截图仅在本机临时路径 `/private/var/folders/f7/8jzwqrf15g56w446cgw2txb40000gn/T/opencode/amairo-desktop-1200.png` 留作父验收视觉对照，不复制进仓库或自动公开；临时文件不是长期发布证据。

### 已实施的产品边界

- 源码已接入 `EngineType.ETHORNELL`、强证据扫描、显式宿主启动、SJIS/GBK 与字体的全局/单游戏设置；`LaunchResult.Success` 仅代表 Activity 分派成功。
- 资源根与用户数据根分离；App 派生用户根为 `filesDir/ethornell/userdata/<完整小写 SHA-256(ROOT_URI UTF-8)>`。路径预检与独立 `:ethornell` 进程均不等于安全沙箱。
- `GameSaveManager` 和游戏页仍显式关闭 Ethornell 存档管理。没有整根镜像、假定 `save/` 的回退，也没有 PC 存档互通、导入/导出或故障原子性验收结论。
- 完整 IME、BMV、WMV、实时 SWF 与电影音轨尚未实现/接通；依赖这些能力的游戏或场景不得列为已支持。
- 完整发布门槛未完成：Surface/暂停/输入/退出压力、音频焦点、真实样本保存/读回、多GPU、长稳、最终候选release R8、API26/16KB完整运行、最终APK依赖与许可/SBOM仍需独立证据。计划§12.5已拆分verified子项与未完成门槛，不把已实现写成未做，也不提前勾整体完成。
- 安全复审：App原7项与ABI原问题闭环；Rust旧P1修复成立，原6项P2多数已有代码/测试且二复核闭环，不沿用“6项仍在推进”的旧快照。**整体安全最终验收尚不宣称通过**，剩余项按逐项复核记录而非旧数量判断。
- 当前诊断拿到真正slot/top-save presshit与BF事件，非只toolbar；script payload/state门待owner定位，尚无保存。trace固定数字64条、私有sentinel debug-only/Release无效，之后验收禁用；不记录商业正文，不将收到click当作slot闭环。
- SafeStore两环境6/6、VM globalSDC三会话、dispatch3/3均有父设备证据；仅protocol gate获授权，DEVICE_SAVE_IO=false/available=false，商业slot与管理闭环未过。source许可与公开发布独立blocked。
- callee/profile与精确DA返回的最终source hash须freeze后统一build并核验候选/入包；现有视觉通过不自动证明后续源码已在库中。三critical文件hash证据不扩大成全游戏资源树证明。

### 构建失败的保留记录

首次 Gradle 集成因当时正在编辑的测试 asset Provider 来源问题失败，修复后已有一次 16s 完整成功构建（cache stored）。随后 cache 重复检查确认复用，但因并行 engine 编辑遇到新的编译失败；engine 单测报告先有 220 项、后有 231 项通过。最新父完整构建已 22s 成功且 cache reused，覆盖上表任务；各次快照计数不累加，也不把 up-to-date 报告说成独立重新执行测试。

### 本轮候选与验收时间线（2026-10-15至16日，按执行先后）

| 顺序 | 产物/执行 | 当时结论与后续替代关系 |
|---|---|---|
| 1. 静态初版 | 36,952,736 B，SHA-256 `60b1ec7861aaf88c49191a41e99f9eeca39349fed597c0a7ce834ee9b7f0c395` | 14ABI/系统依赖/LOAD与RELRO16KB静态通过，仅中间产物；无设备运行结论 |
| 2. 首次入包与动态加载 | strip 26,063,304 B，hash前缀`d84bfc1e…`；当时provenance/source inputs与APK精确字节验证通过 | 三环境加载探针通过；API36/16KB各3项create `-5`失败，GPU/BP未执行，后被O_PATH/fixture canonical修复结果替代 |
| 3. 核心fixture候选 | SHA-256 `5c15c4219b61206487335df3d6c509016317c123f404b89c7c1210350de91d64` | API36/SJIS、16KB/SJIS与GBK各4pass/1skip；API26加载/create/thread通过但2项GPU`-7`仍失败 |
| 4. 生产宿主与样本 | close fixture正常两轮退出/重入1.95s通过；真实样本GBK direct-runtime≥3帧3.12s通过 | 35s生产观察约7s VM`-9`失败；已确认预算根因并修源码，待新候选。3s黑图不计标题，旧桌面1200帧标题仅作对照 |
| 5. 存档与当前回归 | Save device 5pass/1fail；Rust651pass/2ignored/2skip、App564tests含1skip、engine242pass，工具72pass | Android36恶意ZIP typed-error映射待复验；native槽位存读档未验证，capability仍false；不以测试统计覆盖真实游戏失败 |
| 6. Save后端复验 | 父设备6/6 pass、0.356s，恶意ZIP已typed；完整Debug/tests/strings构建22s成功、cache reused | 替代第5轮Save失败，不替代native槽位验收；JUnit最新总数待最终run/XML。生产样本预算正按512/768MiB设备profile/usage修正，标题未过 |
| 7. 新候选35s视觉复验 | `fd5b43ce…` Activity35s测试通过，但截图为保存失败host confirmation；私有用户根无BGIgdb/UserData | 当前根路由待修，正常标题/对白/存档未过；API26同候选stage2 adapter_unavailable，不提高minSdk规避 |
| 8. 路由定位与Release复验 | 受限本机repro定位UserData writable probe→PermissionDenied→B080，尚未write；父含native Release R8 1m59s、exact APK native与zipalign通过 | 路由最窄映射实施中，资源保持只读、不自动导入旧档；下一候选全部重建重验，SafeSave6pass不代表VM槽位写入，capability false |
| 9. Release harness与alias复核 | Debug测试variant依赖被R8删除导致before-test框架失败，native未加载；用户file alias保持资源只读获二审确认 | 纯Java Release-only probe待执行；通用资源读alias P2按入口拆分中。预算profile已有648MB/14VM实测依据，但正常标题/存档未通过，公开gate不变 |
| 10. UserData与合成VM持久化 | `faafda…`实机35s无警告，UserData文件407,323B落盘；16KB VM三会话write/destroy/read与空根negative通过0.909s，Java未创建save | 视觉黑底3空选项、对白UI无正文，不是title；scrslct首分岔修复中。合成globalSDC不是商业slot，资源根未写hash待固定，两个cap false |
| 11. GBK持久化与最新回归 | 实机/GBK同VM writer/destroy/reader/empty-negative三会话0.563s父PASS，Java未造档；App/engine full tests+strings父再8s通过，CI tools75pass | 不累加旧JUnit总数；release自包含Java probe待交，真实首标题/正文/商业slot仍未闭环 |
| 12. 合成框架重复压力 | 16KB像素/重挂/旧generation拒绝20/20轮；实机10轮双会话共20次正常close/destroy ACK/PID退出/新PID通过，无轮间test kill；source bundle173项再验无禁止内容 | 仅合成loop压力，不等真实游戏生命周期或小时级soak；空选项首分岔仍由owner调查，公开gate不变 |
| 13. 空选择首分岔定位 | clean unrestricted无BGIgdb同样空LanguageSelect，旧desktop有BGIgdb跳过分支；81DA输出丢弃/81D8双参数句子表未消费/81:21注册冲突已定位 | version-aware ABI/full-dispatch修测已派；真实作品兼容缺口，不倒退否认已验证底层，不用导入旧BGIgdb或跳过选择掩盖 |
| 14. 新增真实仪器覆盖与probe构建 | 暂停/guest clock/CANCEL三例已有独立javac/BP263指令解析；Release Java probe测试源隔离已修，父第二构建中 | 均未新增设备PASS；first-run语言ABI仍关键剩项，当前为draft，不锁整计划complete |
| 15. 正确Release probe设备闭环 | R8+resourceShrink build44s通过，16KB matching 23KB纯Java仪器3/3PASS；identity/load14ABI/build_info/productionJNI create-poll-destroy，日志`ethornell-releaseprobe-16kb.log` | 解决旧Debug跨variant/test源泄漏harness问题，不曾证明产品native crash；scope仅release-native-load-create-only，GPU/title/商业slot未验 |
| 16. 暂停三例设备闭环 | 实机3/3PASS 2.838s，fractional guest clock/CANCEL、独立pause原因、暂停detach新generation恢复RGBW，日志`ethornell-pause-device.log`；Release APK native/zipalign再次通过 | 直接JNI与真实Kira API，不含录音/Activity onPause集成；首语言ABI仍修复中，空UI不能称可玩 |
| 17. 两环境暂停与语言修复代理报告 | 父实机+16KB暂停均3/3PASS，最新约3.3s；owner另报告受限PNG语言选项恢复 | 暂停属父设备证据；语言PNG/候选/实机选择尚未父验，000001留compat，不混入verified或标题通过 |
| 18. 10-16普通App UI标题独立通过 | `ef516…`新用户看见三语言→选简中→正常标题/中文菜单父截图PASS；从头进入街景/speaker柚希、zoom推进与中文十槽SAVE UI可见 | 正文000000/000001仍D8/DA lookup P1；slot1点击宿主退出回App，保存不能算成功，owner复现中；两个cap false，不统称可玩 |
| 19. 10-16真实两句正文通过 | `474d3073…`街景/柚希/首句与点击后第二句真实中文父视觉PASS；SAVE与hover可见 | slot无confirm/date/thumbnail/新file，Enter无effect，不认定crash，owner追输入/script return；DA返回length非fixed1独立研究待最小修，不因当前>0脚本显示成功宣称精确ABI通过 |
| 20. 10-16真实正常退出与契约复核 | 父手动双确认正常退出回App、非kill；EXE研究进一步确认DA UTF-8 strlen/dest=NULL query | 修后候选待测，SAVE仍只有hover未提交；旧不明回App不定性nativecrash；protocol跨进程3例构建好、设备结果待补 |
| 21. 跨进程protocol首跑 | 3/3FAIL，cmdline空值导致前两例early assert、第三瞬态残留；事后PID8435确已退出 | proc state/starttime重复观察修复中，非已确认产品bug；不开放HOST_DISPATCH_PROTOCOL verified，既有20会话正常close和真实手动退出PASS保留 |
| 22. 当前库/源码与测试分界 | 当前`474d307333f748…`26,127,400B含D8注册/lookup；DA最终strlen及profile新增测试晚于库；父script22/VM209、tools75通过，App566/engine250见XML | 显示正文不等精确DA返回已入库，须final rebuild；slot10000006 click activation静态定位后真trace中；dispatch前两轮harness竞态失败、第三轮待结果 |
| 23. Dispatch跨进程设备闭环 | 3/3PASS、4.541s；锁内metadata进程身份、正常claim/终态/退出新token、revoke late拒绝及missing-resource失败后重入通过，日志`ethornell-dispatch-bootrace-device.log` | 父仅授权HOST_DISPATCH_PROTOCOL_VERIFIED=true；DEVICE_SAVE_IO=false导致available=false，管理不开放；前两轮harness竞态已修，不是安全策略放宽 |
| 24. 16KB dispatch与flag闭环 | 16KB同三例3/3 PASS 12.971s；saveagent已protocol=true/device-save=false/available=false，flag聚焦4suites42tests零失败 | App/native base主要完成；剩商业slot激活主P1，DA length source-only须最终重建。音频听感/蓝牙、小时soak、多GPU、公开license仍未过，fullplan不done |
| 25. 新候选入包与真实SAVE复验 | 19:00Z `dc5dc2abe…`26,128,136B，构建sourcehash无漂移、父static exact APK通过；DA strlen/rootflag4 select/父alpha修复入库，正文退出仍正常 | SAVE短点/双次200ms/action/500ms长按仍hover-only无档；0BF6非实际slot group，debug opt-in有界数字Android route采集中，M4 available=false |
| 26. 20:00Z真实SAVE数字route | `0e1b2d…`诊断候选入包；object10039 group2/item0 slot、group7/item0 top-save均presshit1/BFevent6=1；最终685 host tests/构建通过 | 无确认/新档，click已收到转查payload/state门；64条固定数字trace仅私有sentinel debug、Release无效，验收后禁用，不计商业slot修复 |
| 27. SAVE事件与反编译排除 | 父采到event6被script pop；90BF三DWORD packed正确、rawhit01解码误显示已查清；BE4实际FileExists返回非selectedSlot | 不当产品BF bug、不硬修slot0；guestbuf/global1092/BC word0门待追，商业slot仍fail/not complete；0e1b2d trace64条debug-only无PII不等保存通过 |
| 28. 当前hash回归与定点trace | `0e1b2d…`16KB组合14pass/1skip 0fail、4.814s，tools75pass；PID32439同SAVE会话；下一code1002只读90BC写回后状态 | 等新候选slot trace，不改guest、不加其他能力；终态disable trace重建，slot未过，不解除experimental/license阻塞 |
| 29. code1002真实gate排除 | `9e2fef…`slotgroup2/topsavegroup7 BF6 payload与guestlocal正确，running1/gate0/global1092无拦；集成exact APK static/full JVM14s cache reused通过 | 排除buffer写回与BC等待门，仍无确认/新档，转查保存调用返回contract；未定确因或已修，debug私有门/Release无trace，scope不扩 |
| 30. 保存成功定义与等待生产者 | 原script空slot不一定confirm，仅覆盖valid save才yesnownd；raw38D8@3959等global450240 sceneSerializedReady，3A43才userdata1write | 生产者只读复核/owner追踪，不硬set flag；当前失败仅无date/thumbnail/file与重启读回未成，不再以没confirm定性；标题/中文对白/正常退出证据不变 |
| 31. Producer语义纠正 | 独立复核：450240是Alloc8MiB堆指针，唯一非零encdusdt77写；usdtwnd596在SAVE UI预先Sys80:44启动，userdata(mode0)C4/C0 procedure双输出完成后publish，1664 processing mark | 无Sys81:44call，不把堆指针当bool或empty-placeholder；跨VM procedure/双输出/sharedheap待查，不硬写ready；真实slot无新file仍未闭环 |

本时间线保留旧证据，不表示旧hash、字节数或来源核验适用于后续候选。初始调查日期保持2026-10-15，跨10-16执行的证据day以日志时间为准。当前draft区分已落地/已验证子项与用户完整目标仍未证实部分，不因耗时降低验收；商业slot激活/提交仍是关键remaining。

### 当前未完成门槛（不得以局部成功代勾）

- [ ] API26图形完整通过：目前两项GPU初始化仍失败。
- [ ] 完整真实游戏生命周期/音频路由/小时soak；两平台dispatch三例及正常close压力已过，不重复写protocol待适配，也不代勾其他范围。
- [ ] 真实Android完整对白/选择、音画/长稳通过；标题与两句真实正文已PASS，DA精确返回与商业slot未闭环，不以局部成功代勾完整兼容。
- [ ] 商业native槽位保存/读回与App管理闭环；合成VM持久化已过，capability仍false。
- [ ] 电影等实际依赖能力完成或明确排除：完整IME、BMV、WMV、实时SWF与电影音轨限制仍在。
- [ ] 最终产物release/来源/许可与公开发布门槛闭环；当前仍blocked。

用户目标是整计划实施，不将上述真实阻塞隐去或把原创fixture通过改写成全部complete；后续仅凭新增verified证据更新。

## 2026-10-15：独立验证基础设施交付（静态工具 / 自生成夹具）

范围仅 `tools/ethornell/**` 与本记录；未修改 App / engine / 外部 Rust 仓库业务实现，未获取、生成或提交商业游戏资产。
已完整阅读 `Ethornell-BGI-Android化移植计划方案.md`、外部 `platform/android/include/ethornell_android.h`，以及 archive/script/opcode 的实际格式源码。
已通过 Android CLI 发现审查技能并阅读 `android-permissions-security`；此工作没有 Android Manifest / IPC / 权限变更，不创建面向第三方的测试组件。

### 交付物

- `tools/ethornell/verify_native.py`：Python stdlib-only native 静态门禁，NDK readelf/nm 与真实 ELF 字节交叉核对；required ABI 按单头明确语法解析、遇到未知语法失败；ELF64/AArch64/API26 note、activity glue、DT_NEEDED allowlist/包内递归闭环、LOAD16KB/RELRO 数值检查；输出 SHA256、header hash、源码 revision/dirty 和原始工具证据 JSON。
- `tools/ethornell/generate_fixture.py`：新目录中生成合法 PackFile `system.arc`（`ipl._bp` + 自绘四色 `checker.png`）及 fixture.json；默认 `ret`，可选 `request-close`，不覆盖已有目录。
- `tools/ethornell/tests/test_tools.py`：无设备/无 NDK 的工具解析、数值/错误分支、CLI host 路径、APK 闭环、fixture 结构测试。
- `tools/ethornell/README.md`：完整命令、策略、失败解释与未验证边界。

### 本轮证据与状态

1. 已实际使用 NDK r28 `llvm-readelf` / `llvm-nm` 只读检查旧调查探针 `libethornell_app.so`，确认其 ELF64 AArch64/API26 note，且存在 undefined `android_main`、defined `ANativeActivity_onCreate`、RELRO end `0xc3000` 不按 16KB 对齐、无 required C ABI。**该旧探针不是正式引擎，不得交付。** 未把它复制进项目。
2. 已实际通过 Android CLI 查询并读取官方 16KB 文档，核对 `(VirtAddr + MemSiz) % 0x4000 == 0`。工具要求存在 RELRO 属于本项目额外 hardening gate，而不是把“无 RELRO”错误解释为页大小不兼容。
3. 单测、正式产物验证、fixture 在真实 VM 的执行、APK 构建/安装与设备验收由主 agent 运行并追加结果；本节**不宣称这些已通过**。单测内人工 ELF/假工具是验证器测试，不是产品证据。
4. 合成 fixture 尚无画面、文字、音频、存档或真实游戏兼容结果；checker PNG 当前未被 BP 加载。`ret` 与 HostRuntime Closed 事件不同，必须按实际 VM / host 观测验收。
5. provenance 中源码 revision/dirty 是验证时 checkout 记录，不证明二进制来自该源码；需与构建时 manifest/build_info/Cargo.lock/feature/工具链核对。

### 主 agent 待执行

```bash
python3 -m unittest discover -s tools/ethornell/tests -v
python3 tools/ethornell/generate_fixture.py --output <不存在的新测试目录>
python3 tools/ethornell/verify_native.py \
  --library <正式arm64-v8a/libethornell.so> \
  --header '<引擎仓库>/platform/android/include/ethornell_android.h' \
  --source-root '<引擎仓库>' \
  --ndk "$ANDROID_NDK_HOME" --host-tag darwin-x86_64 \
  --manifest <native验收报告.json>
```

APK 交付后再用 `--apk <apk>` 重跑检查；非系统依赖须逐个 `--allow-bundled <soname>`。API26 RTLD_NOW、ABI 行为/布局、真实 16KB 环境/zipalign、Surface/暂停/输入/退出、Adreno/Mali、真实合法游戏样本与许可仍未因这些工具而完成。
