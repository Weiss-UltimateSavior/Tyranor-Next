# Ethornell device instrumentation

R8 release-native 动态验证使用可选的纯Java runner，见 [`../releaseProbe/README.md`](../releaseProbe/README.md)。不得把默认debug AndroidX测试APK与release target混装；默认本页命令仍针对debug，省略 `ethornellReleaseProbe` 属性即可。

这些是 **真实 Android JNI/VM/GPU** 测试，不使用 mock Backend，不更改生产 Activity 的 exported 或添加 Intent backdoor。默认合成数据由测试 Java 在专用 app 私有目录生成，不含商业资产。测试代码直接访问 engine 是仪器层边界测试，非 UI 业务依赖。

## 构建与安装（由主验收者执行）

沿用版本目录现有 runner 1.7.0 / ext-junit 1.3.0 / core 1.7.0，无新增 Maven 依赖。Gradle Variant API 自动生成 androidTest-only arm64 JNI probe，NDK 默认 r28 `28.0.13004108`，可用 `ANDROID_NDK_HOME` 覆盖。生成目录不会进入生产 APK；无需修改 `gradle.properties`。

```bash
TYRANOR_APPLICATION_ID=com.tyranor.next.ethornelltest ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
android --sdk=/tmp/androidsdk install --device='adb-AUUEUT3C22008394-f7wkhv._adb-tls-connect._tcp' --apks=app/build/outputs/apk/debug/app-debug.apk
android --sdk=/tmp/androidsdk install --device='adb-AUUEUT3C22008394-f7wkhv._adb-tls-connect._tcp' --apks=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

**先核对 APK applicationId 确为 `com.tyranor.next.ethornelltest`，不要覆盖正式 `com.tyranor.next`。** 两个 APK 属于不同包，必须分两次 install；逗号传入一次会被当作同一包 split，出现 `Split null` 重复错误。测试库只提供 arm64-v8a；真实 API 36 / 4KB 设备与 API35 arm64 模拟器可执行，但不等于 API26 / 16KB 验证。

## 自动合成夹具测试

```bash
/tmp/androidsdk/platform-tools/adb -s 'adb-AUUEUT3C22008394-f7wkhv._adb-tls-connect._tcp' shell am instrument -w -r \
  -e class com.core.ethornell.EthornellDeviceTest \
  -e encoding sjis \
  com.tyranor.next.ethornelltest.test/androidx.test.runner.AndroidJUnitRunner
```

也应以 `-e encoding gbk` 重跑；该参数只证明配置分支，不证明对白编码/字形覆盖。

| 方法 | 必须观察的事实 |
|---|---|
| `loadAbiBuildInfoAndRejectInvalidTokens` | 真实 `dlopen(RTLD_NOW\|RTLD_LOCAL)`、14 required exports、ABI=1、权威头尺寸/offset 静态断言、build_info buffer size-query/copy、设备 page size；生产 JNI invalid token 拒绝 |
| `supplementaryUtf8PathsAndWrongThreadAreSafe` | 带 🚀 / 𝄞 的真实资源/用户目录成功 create，证明标准 UTF8 跨 JNI；NUL/孤立 surrogate 明确拒绝；非 owner 调用抛异常且 owner 仍可用；destroy 后 token 拒绝 |
| `gpuPresentDetachReattachAndGeneration` | 打包的原创 render fixture 经真实 VM 执行，1280×720 Surface 由独立 EGL consumer 取 buffer、应用 SurfaceTexture transform、采样至 RGBA framebuffer 后 `glReadPixels`；四色+黑底五个探针的全部3×3像素/四通道容差≤3；detach后停止、generation2再次匹配、旧detach/attach不破坏像素 |
| `scriptExecutesNativeCloseAndProducesClosed` | 无 Java `requestClose`，仅真实 BP VM 执行 close mode + request-close 后 poll 收到 kind=3；收到 kind=2 按同 ID 仅应答一次；fatal diagnostic 失败 |
| `suppliedGameDirectRuntime` | 无 game_path 时 JUnit assumption skip；有参数则真实原生 create/attach/step/poll，至少三个 GPU buffer、真实事件日志、显式 host requestClose（不伪称一定 Closed） |

`ret` 单独没有 VM 完成事件，close fixture 独立证明脚本关闭路径。GPU 生命周期测试已经升级为 **render fixture 像素断言**，不再仅按 buffer 数或黑图判断通过；实际游戏直接 JNI 测试仍只验证真实 buffer/event，不宣称文本、音频、存档或可玩性。

原创 render assets 为 `assets/ethornell-render/system.arc`（1235B，SHA256 `f0d1d96ef30135c95bc722414a9d9572ec3f34952fb57ad9a1bfcf6adb8c06d8`）及其 `fixture.json`；来源为工具 owner 提供的 `render` profile，内部 BP 122B SHA256 `2bcc87a51db064d0163bf81cbf724589f00b3cbe440a6eebcf2cb5a3feb04217`，无商业资产。测试每次校验固定 archive hash 和 manifest，再拷入本轮私有 resource 根，user 根另建。

预期逻辑画布1280×720，Sprite `[512,232,256,256]`；顶部原点探针 `(576,296)=红`、`(704,296)=绿`、`(576,424)=蓝`、`(704,424)=白`、`(128,128)=黑`，alpha均255。3×3每像素逐通道容差3，不用平均值掩盖错色。Consumer 对最新 buffer 使用 SurfaceTexture 原始 transform matrix（含生产端方向/crop），渲染至相同尺寸 RGBA FBO，`glReadPixels(GL_RGBA, GL_UNSIGNED_BYTE)` 统一 producer BGRA/RGBA，再用 `height-1-y` 解释 GL 底部原点；**不尝试交换红蓝或翻转预期来凑通过**。关闭 dithering/blending，shader/FBO/GL错误立即失败。

解除暂停后用真实 `System.nanoTime()` 连续 step，跨16ms门限；每代最多12s等匹配像素，超时输出实际五探针RGBA与真实事件，不因拿到两帧黑图通过。重挂新 generation 后再次读取像素，并在拒绝旧 generation attach 后继续取新 buffer、复验像素。工具结构测试不保证真实VM呈现；如本测试不通过应保留实际RGBA/事件并协调fixture owner，不改生产引擎或放松expected。两代均1280×720，独立shader/EGL consumer。

每轮每方法硬截止 70s；原生调用无法安全 Thread.interrupt，超时会记录 FAIL 并仅终止仪器所在 pid，runner 应报告进程崩溃而非通过。正常路径在 finally 同 owner destroy ACK 后才释放借用 Surface。若 destroy 不返回，由硬截止回收整个进程而非提前 release Surface。暂存小数据保留用于调查，不递归删除任何目录。

## 私有生产 Activity / 真实游戏

### 原创 VM 全局用户数据闭环（独立测试，无真实游戏资产）

```bash
/tmp/androidsdk/platform-tools/adb -s '<serial>' shell am instrument -w -r \
  -e class com.core.ethornell.EthornellDeviceTest#vmGlobalUserDataPersistsAcrossNewSessionsAndRejectsEmptyRoot \
  -e encoding sjis \
  com.tyranor.next.ethornelltest.test/androidx.test.runner.AndroidJUnitRunner
```

也应按需重跑gbk。此为默认debug harness，不属于release-only probe的三项有限验证。测试assets `ethornell-user-data/write` 与 `read` 均只有工具owner原创system.arc/fixture.json；固定archive SHA256分别为 `0a3455d3b5165d64d59a8f16d7f805983c626dc2af418633096d1f7d18d22a26`（402B）和 `75c558c5fe797fb522d6d0baadeff0e9471fd13f67bc612e674dc8b8831f4ab0`（435B）。运行时核对profile、固定hash与manifest size/hash，Java只将这些资源拷入专用resource根，**不会创建、替换、修补或合成BGI.gdb**。

测试一次创建全新私有user根，原生JNI三个会话顺序执行，各自真实Surface/unpause/单调step/poll并finally owner-thread destroy ACK后再释放consumer：

1. writer调用真实VM Sys80:81序列化；必须精确Title `ETHORNELL_USER_DATA_WRITE_OK` **先于Closed**，仅Closed不通过。destroy后检查VM实际创建 `user_root/BGI.gdb`、大小>32且≤8MiB、SDC magic；resource根只有原archive且逐字节不变。
2. NEW reader会话使用不同read资源、同一未改user根，真实VM Sys80:80/83读取并比较16字节marker，必须精确Title `ETHORNELL_USER_DATA_READ_OK` 再Closed；JNI token须不同。Java读取但不解码/生成save，比较read前后文件字节不变。
3. 同reader资源配另一个全新空user根，必须精确Title `ETHORNELL_USER_DATA_READ_LOAD_FAILED` 再Closed，不能出现READ_OK；该会话不能创建/修复save，正例保存字节仍不变。

任一错误Title、缺Title、Title顺序不对、fatal diagnostic、confirmation request、超时/原生失败均失败，不自动接受确认掩盖序列化错误。每phase15s等待，整个方法70s硬截止，目录不递归删除。预期marker为fixture manifest的 `4247492d534d4f4b452d563100a55a01`，由真实reader BP四DWORD比较，测试不自行写marker/save。仅验证合成全局user-data序列化/重开和空根反例，不宣称真实作品slot、PC存档互通或App存档管理导入导出。工具结构测试通过不等于真实VM通过；失败须保留USER_DATA事件与临时文件回报fixture/runtime owner，不放宽expected。

本次实施仅复制原创assets并编写仪器逻辑；设备结果由父验收者运行后记录，不提前声明VM存档闭环通过。

生产宿主在独立 `:ethornell` 进程，`ActivityScenario` 无法观察跨进程对象；测试从 **instrumented target UID** 显式 startActivity，不使用 adb am start，不需要导出组件。

两个Activity测试在每次启动前通过生产 `EthornellDispatchRegistry.issue(filesDir, ROOT_URI)` 持久化一次性dispatch，并将其放入 `LaunchContract.ETHORNELL_DISPATCH_TOKEN`；不绕过claim前置校验。真实游戏观察期要求metadata为同identity/实际host PID的CLAIMED。两轮合成正常关闭测试要求ISSUED→（允许快速关闭时轮询错过CLAIMED）→TERMINAL，核对TERMINAL的PID/starttime及已观测CLAIMED owner一致；仍独立要求真实Closed→destroyACK日志和进程死亡证据。第二轮issue后断言首轮TERMINAL按生产契约被prune。测试不调用finish伪造正常终态；失败teardown仅可revoke未claim dispatch或在正向owner-death证据后调用retireDeadClaim，证据不可读保留记录并报错。

只读诊断连接在 `@BeforeClass` 中一次获取 `UiAutomation.FLAG_DONT_USE_ACCESSIBILITY`，shell和截图复用同一对象并共用mutex，不为每条logcat/dumpsys反复调用 `getUiAutomation()`、不注册无障碍服务，也不在命令间disconnect或切换flags。runner负责连接生命周期，读取pipe仅由AutoCloseInputStream单次关闭。连接初始化失败会在启动host前失败，不删减Closed/destroyACK证据或悄悄换成权限不足的app-user logcat。验收时同一serial仍须串行，避免外部CLI layout/uiautomator并发占用automation；若已有外部残留连接，请父验收者在重跑前调查清理，本agent不操作设备。

连接初始化（含BeforeClass）、每次shell命令/读流、截图都在daemon diagnostic worker执行，独立Future限时8s，输出 `DIAGNOSTIC begin/end <operation>`；所有操作仍使用同一UiAutomation对象与mutex。读流硬上限2MiB，超限明确失败，不把截断日志当完整生命周期证据。Binder/pipe挂住不能保证被interrupt，因此任一诊断超时直接报FAIL并终止仪器自身PID，不等待worker锁或先调用host/metadata cleanup。65s整体硬截止同样只记录并直接kill仪器自身；它不再先进入可能阻塞的killOwnHost路径。正常/普通失败finally仍尝试受原硬截止保护的定向host teardown。超时可能留生产子进程，父验收者须确认精确包名/UID/PID后单独清理，不应为回收子进程拖住硬截止。

卡住时父验收者可在8s诊断截止前（或调查旧APK时）对**已确认的仪器PID**发SIGQUIT以获取ART线程dump：`adb -s '<serial>' shell run-as com.tyranor.next.ethornelltest kill -3 <instrumentation-pid>`；同时保存 `adb -s '<serial>' logcat -d -v threadtime --pid=<instrumentation-pid>`，必要时配合设备bugreport中的ART trace。不要向system_server/用户正式App发信号，不要用CLI layout再建立另一automation连接。harness硬截止会尽力异步输出Java线程栈，但不会等待dump完成而延误终止；外部SIGQUIT通常更利于保留完整证据。

先由验收者给**隔离测试包**存储权限（原生产权限不变）：

```bash
/tmp/androidsdk/platform-tools/adb -s 'adb-AUUEUT3C22008394-f7wkhv._adb-tls-connect._tcp' shell appops set com.tyranor.next.ethornelltest MANAGE_EXTERNAL_STORAGE allow
/tmp/androidsdk/platform-tools/adb -s 'adb-AUUEUT3C22008394-f7wkhv._adb-tls-connect._tcp' shell am instrument -w -r \
  -e class com.core.ethornell.EthornellPrivateActivityTest#suppliedGamePrivateActivityLaunch \
  -e game_path "'/sdcard/Download/EthornellValidation-amairo-20261015/巧克甜恋（あまいろショコラータ）'" \
  -e encoding gbk -e observe_seconds 15 \
  com.tyranor.next.ethornelltest.test/androidx.test.runner.AndroidJUnitRunner
```

上例路径内层单引号是传给设备 shell 的引用（`adb shell` 会重新拼接参数）；路径不同应同样安全引用。只可指向合法且允许测试的副本；本轮真实样本只用 `/sdcard/Download/EthornellValidation-amairo-20261015/` 下副本，不动原游戏包。Activity 测试按本轮 `ROOT_URI` 的标准 UTF8 **全量 SHA256、小写64hex** 独立派生 `files/ethornell/userdata/<sha256>`，严格遵循生产路径安全校验，不用任意私有目录绕过安全；资源目录不用于保存。直接 JNI 测试仍独立创建私有 user 根，原生测试入口本身没有 ROOT_URI 参数。

同路径也可对直接 JNI 执行 `-e class com.core.ethornell.EthornellDeviceTest#suppliedGameDirectRuntime`。两者分别运行，避免前后台焦点干扰。

Activity 测试自动验证：未导出、正确独立进程、生产 native-create ACK 后真实 registry identity、持续存活、ActivityManager 有目标 Activity、抓取该 pid 的真实 logcat（fatal/GPU lost/VM panic 报失败），截图保存。报告路径见 `EthornellHarness` log：`activity-logcat.txt` / `activity.png` 位于本轮私有 user 目录，可 `adb exec-out run-as com.tyranor.next.ethornelltest cat '<相对files路径>'` 拉出。**这只证明 create ACK/观察期存活，不宣称跨进程 Closed/VM 完成或真实游戏可玩；截图必须人工检查。** 真 VM + GPU 自动证据来自上面的直接 JNI 测试。

真实游戏观察期任何异常/断言（含registry提前清空、create失败）都会在finally清理host**之前**立即尽力采证：优先已记录host PID，尚未记录则从该dispatch owner恢复PID；即使host已退出也读保留logcat，保存 `activity-logcat.txt`、`activity.png`、`activity-evidence.txt`。成功结束同样采集一次，不周期性截图/写盘。错误/警告及生命周期行通过instrumentation stream输出 `HOST ...`，去除文件路径、最多100行、每行1600字符；完整未脱敏日志只保留在本轮私有目录。截图可能已是退回后的界面，仍保留，不伪称为退出前游戏画面。

采证shell/截图各有8s限时，但失败采证**不会**立即杀仪器或替换原始Assertion：超时/IO错误作为suppressed诊断附到原失败，仍尝试另一项并进入cleanup。正常结束若采证不完整则测试明确失败，不把缺证据当通过。真实游戏整体硬截止为90s（涵盖启动、最长45s观察、失败采证及清理）；合成两轮仍65s。正常诊断/BeforeClass的8s超时仍沿用直接终止策略；唯独失败证据收集使用保留原错误的best-effort模式，daemon worker卡住也不等待其锁。尚未加入自动点击/开始游戏action，以免改变当前标题页提前退出的复现条件；先归因真实生产session退出。

真实游戏的 finally 在 UID/精确进程名匹配、registry identity 属于本轮或当前无 live identity 时 kill isolated host；发现不同 live identity 则拒绝终止。允许复用没有 live session 的 cached idle 进程，进入前发现其它 live session 则失败。此为真实游戏测试 teardown，**不是优雅退出/存档验收**。不会 kill 主进程，也不清除其它已有游戏会话。Activity 启动失败、无 registry ACK、崩溃、超时均失败，不当作成功。

## 真实游戏手动接管（可选，非自动画面/存读档验收）

**已知限制：此参数不能保证runner结束后host存活，不应作为跨runner手动接管方案。** 已核对AOSP `ActivityManagerService.finishInstrumentationLocked()`：默认 `!instr.mNoRestart` 会调用 `forceStopPackageLocked(..., "finished inst")`，连同target包的 `:ethornell` 子进程一起结束。仅取消测试自身kill不改变系统行为。参考 [AOSP实现](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/am/ActivityManagerService.java)。本harness不改变系统instrumentation模式、不添加生产后门来绕过。可靠的手动方案是在仪器完全结束后，从普通App游戏库正常点击启动。

仅 `suppliedGamePrivateActivityLaunch` 支持 `-e keep_host_for_manual true`（默认false，严格只接受true/false），其精确语义只是**跳过harness自己的成功teardown**。在观察期与成功采证完成后，再确认本轮host仍存活且dispatch仍为CLAIMED，输出 `MANUAL_HANDOFF host_pid=... identity=... private_user_root=... dispatch=... survival_after_runner=NOT_GUARANTEED ... auto_acceptance=NONE` 并保存 `manual-handoff.txt`。该模式成功时不kill本轮host、不revoke/retire其dispatch，finally仍取消硬deadline并shutdown supervisor，不留后续定时杀进程任务。任何失败仍按默认采证+cleanup处理，不因为manual模式忽略错误。

手动标题/新游戏/存档/读档/正常退出验收应在runner结束后**正常打开App并从游戏库启动**；别依赖上述参数留下的host。当前 `EthornellSaveCapability.available=false` 只控制App存档管理准入（设备save-IO与host dispatch协议尚未标记验证），不阻止正常EngineLauncher走OperationGate.issue后的启动，也不等于游戏内存档成功。若系统finish后留下CLAIMED metadata，下一次正常操作应走生产正向owner-death恢复，不手改记录。已显示的host confirmation保持人工处理，harness不自动答复所有确认，不以“观察期liveness通过”声称标题/slot可用。截图可能停在资源/保存错误提示，必须按真实错误归因。不得误伤正式App或其它session。参数只影响测试自身teardown，不导出Activity、不添加生产backdoor。

## 正常关闭 ACK 后进程退出再重进（合成 fixture）

```bash
/tmp/androidsdk/platform-tools/adb -s 'adb-AUUEUT3C22008394-f7wkhv._adb-tls-connect._tcp' shell am instrument -w -r \
  -e class com.core.ethornell.EthornellPrivateActivityTest#closeFixtureNormallyAndReenterAfterAcknowledgedExit \
  com.tyranor.next.ethornelltest.test/androidx.test.runner.AndroidJUnitRunner
```

该方法用同一 `ROOT_URI` / 合法 SHA256 user 根 / 原创 close fixture，连续启动生产 Activity **两次**。生产既定策略是 Closed + destroy ACK 后仅终止自身 `:ethornell` 进程，不保留 cached idle。测试两次之间和成功结束均不 kill/force-stop；每轮必须观察真实正常关闭证据，再确认 host 已退出，才开始第二轮；第二轮要求新 PID 和新的 `(PID, token)` 会话标识。token 为进程内序号，新进程可能从 1 重置，所以不要求 token 数值跨进程不同。资源 archive 逐字节检查未修改。测试独立计算 SHA256 root，并与共享 `com.core.ethornell.EthornellPaths.userDataDir` 门面交叉核对，不绕过安全校验。

跨进程 registry 清空可能来自失败清理，不能单独证明 Closed。因此测试要求生产只读生命周期日志（非测试 backdoor），tag=`EthornellLifecycle`：

```text
closed_event identity_sha256=<64hex> token=<positive session token>
destroy_ack identity_sha256=<64hex> token=<same token>
failed identity_sha256=<64hex> token=<session token>
```

每轮按 logcat threadtime 真实 host PID 与本轮 identity 匹配先 `closed_event` 后同 PID/token 的 `destroy_ack`，任何 `failed` 都失败。直接读保留的日志缓冲，因此 host 快速正常退出、ActivityManager 轮询未捕获活进程时也不会漏证据。没有日志则 **fail-closed**，不拿固定 sleep、窗口消失或 registry 为空代替证据。日志必须由生产真实 kind=3 接收和 native destroy ACK 对应路径发出（由 engine agent 在其所有权内提供）；测试不会修改生产 Native，也不会添加 IPC 接口。正常完成后验证 registry 清空、host 进程退出，然后进入第二轮。报告 `close-reentry-round-1.log` / `close-reentry-round-2.log` 位于上述 SHA256 user 根。20s/轮等待证据、5s 等待退出、65s 整体硬截止；只有失败/超时才紧急回收本轮 isolated host。仅崩溃退出但没有 Closed→ACK 证据仍失败。

## 原创 fixture 的格式/语义依据

遵循 `tools/ethornell/generate_fixture.py` / README：PackFile 12字节 magic、LE count=1、16字节索引名 `ipl._bp`、relative offset=0、size、8零字节；BP 16字节 header + code。Java 每次从原始结构生成，检查资源根仍只有原 archive 且字节未变。

- ret code=`17`，与工具 ret profile 相同；未带 checker，因为没有脚本引用它。
- **本测试独有 close code**=`00 01 80 68 80 69 17`：并非直接把工具 `request-close` profile 当成保证 Closed。
  - `ethornell-script/src/vm_opcode.rs::push_byte`：`00 01` 压入 Int(1)。
  - `ethornell-vm/src/native_call.rs::SYS_SET_NATIVE_CLOSE_MODE`：`80 68` 消费一个 BOOL_VALUE，void，Continue。
  - `SYS_REQUEST_WINDOW_CLOSE`：`80 69` 无参数、void，Continue。
  - `ethornell-app/src/lib.rs::handle_host_window_close`：mode 非零才允许 close；默认零仅排 native event2，因此工具原 `request-close` 本身不保证 Closed。
  - `host_runtime.rs::step` 处理 pending close，`close` 排 kind=3；`17` 顶层 ret。

禁止用这些少量原创字节冒充原版/汉化游戏兼容样本。音频、文字/字体、存读档、API26、16KB、Adreno/Mali/Vulkan/GLES 独立矩阵、长稳与正常退出/确认弹窗仍需额外验收。生产 Native 不提供后端/静音参数，因此本 harness 沿用实际默认策略，不声称强制 Vulkan/GLES 或静音验证。

## 实施状态

render 像素增强交付：已从工具 owner 指定临时目录复制上述两个原创 assets，并核对 archive SHA256；新 consumer shader/FBO/readback 和测试源码独立 javac 编译通过。**尚未由本 agent 运行 Gradle 打包或真实 Android VM/GPU，fixture 预期能否实际呈现待父 session 执行**。单独执行增强测试可传 `-e class com.core.ethornell.EthornellDeviceTest#gpuPresentDetachReattachAndGeneration`，其余 runner/包名保持上文命令。

已阅读项目方案、生产 Activity/Session/JNI、外部权威 ABI header 与工具 fixture README；通过 Android CLI 发现并阅读 `android-permissions-security` 审查技能及官方 OpenGL 环境文档。独立 `javac -source 17 -target 17`（真实 android-37.0/android.jar、现有 engine 编译产物和 AndroidX/JUnit 缓存）编译三份 Java 测试源码成功；NDK r28 arm64/API26 `clang++ -fsyntax-only` 通过（包括 ABI size/offset 静态断言）。父 session 后续反馈主构建成功且 configuration cache stored、隔离 app APK 与 test APK 已安装；该反馈不代表本次修改后的设备测试通过。当前新增 ACK 退出后重进测试还需最终安全修复库及生产只读日志后由父验收者执行。未 spawn、未自行安装、未并发 assemble、未提交/push。**本文件是执行说明，不是已通过的设备验收记录；构建/执行结果由主验收者追加。**
