# Impacto 原版上游接入草稿

此草稿面向 `Weiss-UltimateSavior/Tyranor-Next` 的 `main`，从当前上游基线
`e04f9a15` 建立。它取代已关闭的 [PR #161](https://github.com/Weiss-UltimateSavior/Tyranor-Next/pull/161)
的引擎补丁路线。本 PR 先建立引用、编译和来源验证工具，宿主接入在同一 PR 内继续推进。

当前唯一开发入口：[上游 PR #165](https://github.com/Weiss-UltimateSavior/Tyranor-Next/pull/165)，
分支 `yuukana1:feat/impacto-upstream-only`。原独立私有仓库已弃用，不再承担源码、产物或后续交接依赖。

## 约束与当前行为

- 直接引用 [CommitteeOfZero/impacto](https://github.com/CommitteeOfZero/impacto)，用完整 commit SHA 固定版本。
- 不修改 Impacto 源码、profile、着色器，不应用 TYN 引擎补丁，不以生成修改副本或符号替换绕过此约束。
- Android 生命周期、权限、目录、启动参数和存档隔离由 TYN 宿主适配；宿主不能解决的上游问题明确记录，不维护引擎分叉。
- 不增加 TYN 的 SDL 或其它第三方源码补丁。上游自身依赖构建配方保持原样，并在来源记录中说明。
- 原版构建产物先留在独立候选目录。当前 PR 没有把旧补丁库、旧源码归档、私有 SDL 类或商业游戏数据带入应用，也尚未新增可启动的 Impacto 引擎入口。

## 测试样本

本次选定 **Memories Off 6 ~T-Wave~（秋之回忆6）**，对应上游 `mo6tw` profile。
[上游支持列表](https://github.com/CommitteeOfZero/impacto/issues/1) 明确列出 **PS3 版**；PS Vita 仅注明可能可用且未测试。
用户正在下载，尚未确认平台和语言；不能仅凭游戏标题判断兼容。

上游 profile 要求数据目录内有 `BG.CPK`、`BGM.CPK`、`CHARA.CPK`、`MASK.CPK`、
`MOVIE.CPK`、`SCRIPT.cls`、`SCRIPT/`、`SE.CPK`、`SYSTEM.CPK` 和 `VOICE.CPK`。
不改写用户的商业游戏数据。上游 README 认为该游戏可推进全部结局，同时说明没有游戏完全实现；这不等于 TYN Android 端已验收。

## 构建和更新

`engine/impacto/source-lock.json` 固定引擎和构建依赖版本。
源码必须是独立 Git checkout：校验器拒绝父仓库冒充、错误 HEAD、暂存或未暂存修改、
未跟踪或忽略文件、稀疏检出、隐藏 index 标记及未初始化/已修改的子模块。
这是固定 revision 和 Git 洁净性检查，不是游戏兼容性测试。

```sh
python3 tools/impacto/verify-upstream.py --source /path/to/impacto
python3 tools/impacto/test-upstream-integrity.py
python3 tools/impacto/test-git-environment.py
python3 tools/impacto/test-build-preflight.py
python3 tools/impacto/test-stage-upstream.py
```

候选库构建目前要求 Linux、Python 3.10+、Git、C/C++、make、Ninja、pkg-config、
Perl、autoconf、automake、libtool、m4、NASM、Bison、Flex、CMake 3.31.1+ 和 Android NDK 28.0.13004108：

```sh
export ANDROID_NDK_HOME=/path/to/android-ndk/28.0.13004108
export IMPACTO_CMAKE=/path/to/cmake/bin/cmake
export VCPKG_MAX_CONCURRENCY=4
bash tools/impacto/build-upstream.sh
```

默认写入 `.impacto-upstream-build/<revision>/`；可设置 `IMPACTO_UPSTREAM_BUILD_ROOT` 指向新的独立目录。
宿主 CMake 钩子固定 FetchContent 依赖，保留上游自己的 LibAtrac9 构建配方，不编辑上游文件。
使用单独的 vcpkg 缓存，构建前后和失败退出时检查引擎源码。禁止调用上游的 Android install 目标，因为它会写回源码目录。
Git 子进程会清除继承的仓库定位与配置注入环境，避免跨仓库调用误读或误写调用方仓库。
当前不复用已有 `build/_deps` 或 `build/CMakeCache.txt`：配置后再次构建须选新的构建目录，防止锁定 SHA 掩盖依赖缓存中的本地修改。
构建目录由 `.build.lock` 互斥；取得锁后先撤销旧候选状态，再检查 NDK、CMake 和 Ninja，失败记录为 `build-failed`。
独立暂存命令也获取同一 `.build.lock`；构建内部调用须验证直接父进程身份及锁 owner 后继承锁，避免重复获取或提前释放。
发布时另用 `candidate/.publish.lock`，对独占临时快照进行 ELF 检查和哈希计算，原子替换库后最后发布报告。
异常会释放锁；进程被强制终止留下的锁须确认没有相关进程后手动移除，不自动抢占。
成功后 `candidate/` 保存未集成的库、ELF 报告和身份记录；不会拷入 APK，也不代表已满足对应源码分发或设备验收要求。

升级流程：更新完整 SHA 和必要的依赖锁，使用新构建目录，重新编译原版库，核对 JNI/CLI/资源契约，
更新运行库与配套源码，执行真实游戏和生命周期测试。任何阶段失败都保留日志并停止替换运行库。

## 宿主迁移待办

- [x] 从 TYN 上游共同祖先建立独立分支，不合入私有实验仓库的独立根历史。
- [x] 固定来源，增加原版 checkout 校验和真实 Git 仓库回归。
- [x] 增加独立原版构建入口、宿主侧依赖锁定与候选库校验；尚未完成全量构建。
- [ ] 完成原版 ARM64 编译；记录工具链、完整源码及产物身份。
- [ ] 适配原版 `-g`、`-bp`、`-gc`、`-uc` 参数，不发送自定义 `--tyranor-resource-root`。
- [ ] 在宿主提供 `getChosenDirectory`、`chooseDirectory`、`resetChosenDir` 回调；控制原版 CWD、配置回退及 ImGui 相对写入位置。
- [ ] 解决原版 SDL3 `org.libsdl.app` 与 TYN 已有 SDL2 同名 Java 类的冲突。独立进程本身不能消除同一 APK 中的类名冲突。
- [ ] 完成资源验证、按游戏隔离的存档和持久 TOML 管理，不继承旧引擎补丁的假设。
- [ ] 接入扫描、启动、运行库分片及三语言文案，并验证原有 SDL 引擎。
- [ ] 完成 PS3 游戏数据的标题、正文、声音、输入、存读档、前后台及退出重启验收。
- [ ] 完成 API 28、16 KiB 页设备与 minified Release 检查。

原版库编译、宿主接入和真实游戏测试未完成前，此 PR 保持 Draft，不作为可发布功能。

## 本轮检查

- 20 项真实 Git 仓库回归通过，包含“父仓库冒充源码 checkout”和 Git replace refs 替换锁定提交的拒绝测试。
- 3 项 Git 环境隔离回归通过，真实临时调用方仓库的工作文件、配置、索引、引用及对象均保持不变。
- 7 项构建前置检查回归通过，覆盖缺失工具、错误 NDK、依赖缓存污染和重复构建；不会下载或编译依赖。
- 增加 Linux 专用父进程锁继承 CLI 集成检查；Windows 本地跳过，由 Linux CI 运行。
- 17 项候选检查和发布回归通过，覆盖 ELF 边界、源库并发替换、无效快照、构建/发布共用互斥、父进程锁继承及失败释放；不是完整运行库验收。
- CMake/Ninja 定位的 8 项回归、Bash 语法检查及 CMake 离线依赖声明覆盖检查通过。
- 官方固定 checkout 的 tree 为 `a2a0b7996a46c5d067efbeb8747cef103a69b157`，Git 洁净检查通过。
- 用 NDK 28 对原版 `src/io/vfs.cpp`、`src/io/fsfolderarchive.cpp` 执行语法与 ARM64 对象编译均通过。为隔离诊断，外部探针配置禁用了 OpenGL/ImGui 并补齐正常 PCH 标准头；不是完整 CMake 构建。外部直接析构 `FSFolderArchive` 的合成探针会触发不完整类型错误，但不能据此认定当前上游构建必然失败，不能作为恢复引擎补丁的理由。
- 尚未执行完整 ARM64 编译/链接、APK/JNI、真实游戏或 Android 设备验收。
