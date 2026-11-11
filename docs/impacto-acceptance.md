# impacto 接入验收记录

状态：本地构建与独立代码复审已完成；真实设备与游戏兼容性未验收，PR 保持草稿。此记录仅覆盖本次固定版本，不是上游全部 profile 的可玩性声明。

## 固定输入与环境

- TYN 基线：`345be7a45905d99c00f1af5b9a231bb36d58601e`。
- impacto：`ba0b6e0884b9016fcd7dfd3e50024c5af1e8aef7`；私有 SDL 3.4.10。完整源锁、补丁和实际构建工具版本见 `engine/impacto/source-lock.json` 与 `runtime-provenance.json`。
- 验证日期：2026-11-11（UTC），隔离 Linux x86_64 云工作区。没有修改用户其他项目或现有引擎二进制。
- Android 编译：JDK 17.0.21、仓库 Gradle Wrapper 9.5.1、App SDK 37.0 / engine SDK 36、NDK 28.0.13004108。原生主构建使用 CMake 3.31.1，vcpkg 为部分依赖下载的 CMake 版本单独记录。
- 物理设备、设备系统版本、实际 GPU、合法匹配游戏样本：均未提供。没有设备运行截图，不将源码分析或合成数据记为设备通过。环境没有 KVM；未下载约 0.35 GB 模拟器和额外 1.1–1.9 GB x86_64 镜像进行软件模拟，不将其替代 ARM64 / GLES 3.2 实机验收。

## 已完成的原生分发检查

- `libimpacto.so`：35,497,544 字节，SHA-256 `75ac723fd351fd85cf7bd8ed3e5a14a8910f0f93a349ac97b50b49032ba9e7f4`。
- AArch64 ELF / `SDL_main` / 私有 Java-JNI 命名空间通过；仅依赖记录中的 Android 系统库。三个 LOAD 段满足 16 KiB 对齐。这不等同于 16 KiB 页设备运行验证。
- 运行资源包：499 项、未压缩内容 94,140,560 字节；`resources.zip.xz` 为 79,268,492 字节，SHA-256 `38295bfbeca1a40e3445e257a78c00b193d2546bae3deff85eb834c71ff1cecf`。
- 对应源码是实际分发归档，包含运行时、实际依赖、配方/补丁和应用源码快照；不是仅列远程链接。每份归档小于 100,000,000 字节，精确清单和哈希见 `engine/impacto/sources/index.json` 与 `SHA256SUMS`。
- 修改 vendored mspack 源码并替换静态库后，独立重新链接成功，生成不同哈希且保留测试符号；原始发布库没有被测试变体覆盖。证据见 `engine/impacto/relink-report.json`。此项没有运行 Android，也不是从全新离线环境重建全部依赖。
- 运行资源保留上游 ISC 声明及明确的第三方例外，Noto 使用 OFL。部分预生成图集/图标的原始素材来源没有独立核实；未据此声明全部权利链已核验。

## 检查命令与结果

以下命令从 TYN 仓库根目录运行。SDK/NDK、Java 和原生构建目录应按当前机器配置，不使用本次工作区的绝对路径。

| 检查 | 结果与边界 |
| --- | --- |
| `python3 tools/check-hardcoded-ui-strings.py` 与其测试 | 已通过；三语言键检查，不替代设备视觉检查。 |
| `python3 engine/impacto/generate-profiles-java.py --check` | 已通过；8 个固定 profile 的 Java 投影一致。 |
| `python3 tools/impacto/test-native-adapter.py --source "$IMPACTO_BUILD_ROOT/source"` | 25 项通过；主机上的启动参数/路径边界测试，不运行 Android。 |
| `python3 tools/impacto/verify-java.py --source "$IMPACTO_BUILD_ROOT/source"` | 已通过；私有 SDL Java 可由固定来源和补丁重现。最终 13 份 Java 文件已重新验证。 |
| `python3 tools/impacto/verify-artifacts.py --ndk "$ANDROID_NDK_HOME"` | 已通过 ELF、499 项资源、8 个 profile、54 份源码归档及 Debug / Release APK 内容一致性检查。发布时须保持源码快照、索引和原生产物匹配。 |
| `./gradlew testDebugUnitTest --no-daemon` | 第一次完整运行受环境 `JAVA_TOOL_OPTIONS` 启动提示影响，一个既有子 JVM 测试失败。移除对子 JVM 的提示污染后，冻结后的最终源码 App 1466 项 / engine 681 项结果无失败，共 2147 项，其中 2146 项执行成功；1 项要求大小写不敏感文件系统的既有测试跳过。包含 42 项 impacto 宿主测试。 |
| `./gradlew :app:lintReportDebug :engine:lintReportDebug --no-daemon` | 全仓报告 83 个错误（App 29 / engine 54），不能写成全仓 lint 通过；已核实 engine 错误文件均未改动。最终改动行差异检查为 0，两个模块所有新增源码文件也均为 0 个错误。 |
| `python3 tools/impacto/test-patch-boundary.py --source "$IMPACTO_BUILD_ROOT/source"` | 16 项通过；编译并执行真实补丁选择函数的宿主/上游分支，验证旧补丁配置不会触发宿主错误。 |
| `bash tools/impacto/test-build-tools.sh` | 8 项通过；覆盖显式、相邻及 PATH 中 Ninja 的选择。 |
| Debug APK / Release R8 / APK 资源一致性 | 冻结源码的 Debug 与启用 R8 的 Release 均构建通过；两个 APK 保留 51 个私有 SDL 类、67 个 JNI 方法和宿主 Activity，运行库只位于专用资源分片，全部资源哈希一致。 |
| 设备启动、真实游戏与 UI 截图 | 未运行。 |

私有 SDL 初次导入产生的 27 个 lint 错误已通过权限异常处理、API 门禁及广播接收器标记修复，没有给整个包增加忽略注解。已有 SDL 命名空间不改动。第三方许可、补丁上下文、原始 ELF 报告及导入的 SDL 格式保持原样，`.gitattributes` 仅对这些路径关闭行尾/文件末尾空白检查，保留其他空白规则；自有 App、宿主和工具代码继续严格检查。

## APK 构建产物

这些是本地验证构建，不是官方发布签名或已通过游戏验收的版本。已重新生成 APK 容器，移除增量打包替换旧分片留下的空洞，没有删除项目或构建缓存。

- Debug：216,369,192 字节；SHA-256 `507a327808e40eabce639fc44a0ad440af69ed4c2ae635594bc592096b2132f9`。
- Release（R8）：190,349,253 字节；SHA-256 `67cb74d267d8c910c5d034ee280ab5cd8812ed95cee487302ee16628afda0625`。
- 两个 APK 的 v2 签名验证通过；合并后的 manifest 保留非导出 `:impacto`、API 26 全局最低版本和既有引擎进程。已有 SDL3 字节未改变。
- 最终完整 Gradle 批次用时 6 分 35 秒，起止构建输入哈希无变更。另行重新打包只改变容器布局，不改变已核对的源码、原生库和资源内容。

## 复审与未完成门槛

书面计划先经独立审查，再分离实现并进行逻辑、架构和回归复审。冻结代码的最终逻辑与架构复审没有未解决的代码级发现，产物/设备验收另列。已发现并修复的重点包括跨进程文件锁的重复 FD 问题、后台校验避免主线程大量 I/O、废弃暂存目录清理和冷启动资源安装不得消耗派发租约。最终退出修复由实际 JVM 线程和跨进程文件锁测试覆盖：原生线程存活时不清空 SDL 生命周期事件，短暂 UI 等待后再给 10 秒后台宽限，超时仅终止独立引擎进程且不提前释放锁。强制终止不保证待写存档完整。上游可持久化的补丁开关会与固定私有根冲突，已在宿主模式隐藏并忽略旧配置启用值，其他 TOML 设置保留。

草稿 PR 转为可合并之前，至少补齐以下真实设备证据：

1. API 28 边界和一个当前 Android ARM64 / GLES 3.2 设备；另测 16 KiB 页设备。
2. 每个声明已验证的 profile 必须写清游戏名称、平台、版本及合法样本来源范围，不上传商业资源。
3. 冷启动、标题/文字、触摸/键盘/手柄、音频和视频、游戏内存读档、切后台恢复、退出后重开、主进程存活。
4. 两个相同 profile 的游戏目录存档隔离、资源升级/损坏后的恢复、首次启动中途返回，以及 ONS / krkrsdl3 回归。
5. 引擎页与错误态的手机/平板、深浅色和所选语言截图。

未经这些实测，不宣称完整游戏、通用 MAGES. 支持、统一存档/WebDAV、虚拟按键重映射或 TYN 图形增强支持。
