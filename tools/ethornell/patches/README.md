# Ethornell 本地兼容补丁

补丁必须按下列顺序应用到 provenance 中固定的 `source_revision`，当前基线为 `a6c925e566f6bc1f7d60ab31afaec1d04dbfa8ab`，已确认可从批准 fork 按该提交获取。库、ABI header、来源 inventory 与补丁哈希以 `engine/src/main/jniLibs/arm64-v8a/libethornell.provenance.json` 为准。

## 补丁顺序与边界

1. `0001-user-data-double-bgi-resource.patch`：收敛一层重复 `bgi-resource` 前缀，将 `UserData/**`、顶层 `BGI.gdb` 和精确四位数字 `BGI####.cad` 路由到 `bgi-user/`；其它资源写入与路径逃逸仍拒绝。
2. `0002-game-local-savedata.patch`：允许经过文件描述符与 inode 校验的 `<resource>/savedata` 直接子目录作为 user-root；该子树不参与普通资源读取、存在性检查、枚举和资源扫描。其它重叠根及符号链接仍拒绝，分离的 user-root 继续供 JNI 回归夹具使用。
3. `0003-public-storage-ofd-writer-lock.patch`：当 `flock(LOCK_EX | LOCK_NB)` 仅返回 `ENOSYS`、`EOPNOTSUPP` 或 `ENOTSUP` 时，在 Android/Linux 尝试全文件非阻塞 `F_OFD_SETLK` 写锁。忙锁、权限错误与其他失败原样拒绝；OFD 失败也拒绝。OFD 锁随宿主持有的文件描述符关闭而释放。
4. `0005-amachoco-graph-startup-compat.patch`：仅对经资源指纹识别的 Amachoco ABI 恢复 `Graph91:00` 旧版空操作行为，避免启动时因上游未知调用处理收紧而退出；该原生调用的完整语义仍未核实。
5. `0006-bgi-version-compat.patch` 通过 IPL 与标题双指纹，为核实过的 Cinderella 与 Pure HD 版本选择 HoukagoFd ABI、原版 16 项显示模式、128 个音效通道和主线程内存规格。PE 未检出显示表时保留资源指纹配置。保留图形文本归一化中越界整数的原有载荷，更新上游指令数量回归，并增加有界的 CBG_MT 分块图片解码及原创回归夹具。Sys80:30/31/CF 与 ModernFused 位图指针入口共用图片格式识别，MT 输入按所属分配范围解码。

历史补丁 `0004-virtual-user-file-reads.patch` 仅用于旧基线；新版上游已实现虚拟根与存档读取映射，不再应用该补丁。

```bash
cd /path/to/ethornell-rs
git checkout --detach a6c925e566f6bc1f7d60ab31afaec1d04dbfa8ab
git apply /path/to/TyranorNext/tools/ethornell/patches/0001-user-data-double-bgi-resource.patch
git apply /path/to/TyranorNext/tools/ethornell/patches/0002-game-local-savedata.patch
git apply /path/to/TyranorNext/tools/ethornell/patches/0003-public-storage-ofd-writer-lock.patch
git apply /path/to/TyranorNext/tools/ethornell/patches/0005-amachoco-graph-startup-compat.patch
git apply /path/to/TyranorNext/tools/ethornell/patches/0006-bgi-version-compat.patch
cargo test -p ethornell-app --locked
cargo test -p ethornell-image --locked
cargo test -p ethornell-script --locked
cargo test -p ethornell-vm --locked
```

补丁顺序必须与 provenance 的 `local_patches` 一致，每份补丁后的源码哈希应与下一份补丁的输入对应。多文件补丁的 `target_source_file_sha256` 记录各目标文件的结果，source inventory 覆盖全部构建输入、依赖锁文件及新增的 `crates/ethornell-image/src/cbg/mt.rs`。

锁回归应分别覆盖不支持操作与锁占用错误、同进程独立文件描述符及跨进程互斥、关闭和进程异常退出后的释放；公共共享存储必须单独测试，不能以应用专属外部目录替代。

## 构建与校验

使用 NDK `28.0.13004108`、API 26、锁文件及独立构建目录。Windows 本次使用 NDK clang 交叉编译，其他平台可使用 cargo-ndk。精确参数见 provenance 的 `build_command`，链接必须保留 16KB 对齐、RELRO、NOW 与 `--no-undefined`。将产物 `libethornell_mobile.so` 经 NDK `llvm-strip --strip-unneeded` 后复制为本项目的 `libethornell.so`，同步更新库哈希、补丁清单、来源 inventory 和构建信息。

```bash
python3 tools/ethornell/verify_provenance.py --local-static \
  --ndk /path/to/android-sdk/ndk/28.0.13004108 --host-tag darwin-x86_64 \
  --report /tmp/ethornell-local-static.json
```

静态校验仅覆盖来源记录、ELF/ABI、依赖与包内库字节，不代表设备或游戏兼容性。带本地补丁的 `dirty=true` 产物不能通过要求精确已发布源码的严格来源审计，`release_ready` 保持 false。原始验证日志与商业游戏截图保存在本地临时目录，摘要记录设备、产物身份与验收范围。



## 启动兼容验证

2026-11-16 复验运行库 SHA-256 为 `84376203015d1bbb0294dd5cbc3b80ac84d81d8b352e7560a4aba1306fb7bfb4`，26,738,304 字节。固定上游提交加五份补丁，来源清单为 408 项，整条补丁链重放后的文件逐字节匹配构建源码。Android ARM64 / API 26 构建、ELF/ABI、16KB 对齐及 APK 内库字节校验通过，`release_ready=false`。

测试设备为 iQOO 12（vivo V2307A，Android 15 / API 35，ARM64，4KB 页）。测试 APK 基于原版 Tyranor Next 1.65 替换运行库资产并以隔离包 `com.tyranor.test` 安装。第一轮启用测试 APK 的 debuggable 属性以读取解压库 SHA-256 与宿主 `/proc/<pid>/maps`，确认实际映射到 `files/engine_runtime/ethornell/libethornell.so`，哈希与本次产物一致。之后使用相同库的非 debuggable 测试 APK 运行原生探针。

| 样本 | 本轮观察 |
| --- | --- |
| 放课后的灰姑娘女孩 | 冷启动到标题，Start 后进入街景与室内对白，点击推进 |
| ピュア×コネクト フルHDリマスター | 冷启动到标题，New Game、默认姓名确认后进入对白，点击推进 |
| 一夜之后体验版 | 冷启动后进入开场动态场景与ナナ对白，用户确认测试成功 |

启动范围内仍有动态场景卡顿。音频恢复和渲染性能分别由后续独立补丁处理。原版三个样本的错误位置与日志见 [Issue #175](https://github.com/Weiss-UltimateSavior/Tyranor-Next/issues/175)。

本轮回归结果如下。

- `cargo +1.96.0 test -p ethornell-app -p ethornell-image -p ethornell-script -p ethornell-vm --lib --target x86_64-pc-windows-gnu --locked` 分别通过 532、26、29、357 项，共 944 项，11 项按既有条件忽略。
- 四个新增 VM 回归覆盖 Sys80:30 归档读取、Sys80:31 解码后切片、Sys80:CF 内存解码和 ModernFused 短计数位图注册，断言完整图片描述头、像素和返回值。恢复旧分派条件后四项全部失败，修正后全部通过。
- 仓库 `EthornellReleaseInstrumentation` 源码单独编译为框架探针，使用仓库 C++ probe 和生产 ABI header。SJIS、GBK 各 3/3，通过非 debuggable 目标身份、RTLD_NOW / 15 导出 / ABI / build_info，以及生产 JNI create/poll/destroy、无效 token 与请求校验。编译专用 API stub 未进入测试 DEX，目标生产类来自目标 APK。该探针只覆盖加载和会话创建范围。
- `python -m unittest discover -s tools/ethornell/tests` 在 UTF-8 与 `core.autocrlf=false` 环境下为 78 通过、1 跳过、1 失败。失败为既有 Windows 文本夹具写入 CRLF 后与 LF 常量比较 header SHA-256，当前改动未修改该工具或夹具。
- 两份独立只读审核核对分派逻辑、旧 CBG/DSC 回归风险、补丁重放与来源哈希，均无阻断发现。

完整当前源码 APK、AndroidX 设备测试、Surface/暂停/存档全套回归、API 26 / 16KB 设备及长稳尚未完成。本机 Gradle 在补齐 JDK 17 后仍无法识别已解压的 `android-37.0` 平台，故使用上述原有框架探针单独复验，未把历史设备通过数计入本轮。Android CLI 审查工具和适用技能在本会话未发现，设备操作使用 ADB。

本地证据的 SHA-256 如下，原始商业游戏截图保留在本地。

| 证据 | SHA-256 |
| --- | --- |
| `cinderella-body.png` | `cef211390924ea731615548a319dd617064d0733d08eb62b8b5e618918fdfd09` |
| `pure-body.png` | `0718fc368661561af3a84631bb8677299255f58e9eb53cca3dda8c79f568aeb2` |
| `onenight-title2.png` | `adaccc146c7889f136b4e4c1ef4d7472f463028bfd4381bfddce9e613dc38afb` |
| `probe-sjis.log` | `96370c7fe858ab8afe8dea0e5d0b5e4509ec53ec10a4353cc0199f061486f67c` |
| `probe-gbk.log` | `96370c7fe858ab8afe8dea0e5d0b5e4509ec53ec10a4353cc0199f061486f67c` |
