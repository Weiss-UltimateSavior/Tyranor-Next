# Ethornell 本地兼容补丁

补丁必须按下列顺序应用到 provenance 中固定的 `source_revision`，当前基线为 `a6c925e566f6bc1f7d60ab31afaec1d04dbfa8ab`，已确认可从批准 fork 按该提交获取。库、ABI header、来源 inventory 与补丁哈希以 `engine/src/main/jniLibs/arm64-v8a/libethornell.provenance.json` 为准。

## 补丁顺序与边界

1. `0001-user-data-double-bgi-resource.patch`：收敛一层重复 `bgi-resource` 前缀，将 `UserData/**`、顶层 `BGI.gdb` 和精确四位数字 `BGI####.cad` 路由到 `bgi-user/`；其它资源写入与路径逃逸仍拒绝。
2. `0002-game-local-savedata.patch`：允许经过文件描述符与 inode 校验的 `<resource>/savedata` 直接子目录作为 user-root；该子树不参与普通资源读取、存在性检查、枚举和资源扫描。其它重叠根及符号链接仍拒绝，分离的 user-root 继续供 JNI 回归夹具使用。
3. `0003-public-storage-ofd-writer-lock.patch`：当 `flock(LOCK_EX | LOCK_NB)` 仅返回 `ENOSYS`、`EOPNOTSUPP` 或 `ENOTSUP` 时，在 Android/Linux 尝试全文件非阻塞 `F_OFD_SETLK` 写锁。忙锁、权限错误与其他失败原样拒绝；OFD 失败也拒绝。OFD 锁随宿主持有的文件描述符关闭而释放。
4. `0005-amachoco-graph-startup-compat.patch`：仅对经资源指纹识别的 Amachoco ABI 恢复 `Graph91:00` 旧版空操作行为，避免启动时因上游未知调用处理收紧而退出；该原生调用的完整语义仍未核实。
5. `0006-bgi-version-compat.patch` 通过 IPL 与标题双指纹，为核实过的 Cinderella 与 Pure HD 版本选择 HoukagoFd ABI、原版 16 项显示模式、128 个音效通道和主线程内存规格。PE 未检出显示表时保留资源指纹配置。保留图形文本归一化中越界整数的原有载荷，更新上游指令数量回归，并增加有界的 CBG_MT 分块图片解码及原创回归夹具。

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

本阶段运行库 SHA-256 为 `71785654916edc5b5a6d6f197fe776f3777b3f6177d3d0eaed4bcb2f22fa8a61`，26,738,496 字节。固定上游提交加五份补丁，来源清单为 408 项。Android ARM64 构建和测试 APK 内实际库字节校验通过。测试 APK 基于 Tyranor Next 1.65 替换运行库资产。

在 iQOO 12（vivo V2307A，Android 15 / API 35，ARM64）上已用原版 1.65 复现三个样本的启动错误。此前包含相同启动补丁和后续音频修复的候选 `772ba49f…` 在《放课后的灰姑娘女孩》《ピュア×コネクト フルHDリマスター》《一夜之后》体验版通过标题到正文流程，普通版 PURExCONNECT 通过标题、姓名确认和对白。此前图片模块 26 项回归通过。该真机记录属于历史候选，本阶段新构建的库尚待真机复验。

原版报错位置及 CBG_MT 回归输出见 [Issue #175](https://github.com/Weiss-UltimateSavior/Tyranor-Next/issues/175)。完整当前源码 APK 与严格来源验收尚待验证。
