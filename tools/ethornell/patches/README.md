# Ethornell 本地兼容补丁

补丁必须按下列顺序应用到 provenance 中固定的 `source_revision`；当前基线为 `7cfc2fba8261e59c68095ab1ddc4f1f3534c27d8`。库、ABI header、来源 inventory 与补丁哈希以 `engine/src/main/jniLibs/arm64-v8a/libethornell.provenance.json` 为准，不能把本地补丁产物描述为干净的上游发布版本。

## 补丁顺序与边界

1. `0001-user-data-double-bgi-resource.patch`：收敛一层重复 `bgi-resource` 前缀，将 `UserData/**`、顶层 `BGI.gdb` 和精确四位数字 `BGI####.cad` 路由到 `bgi-user/`；其它资源写入与路径逃逸仍拒绝。
2. `0002-game-local-savedata.patch`：允许经过文件描述符与 inode 校验的 `<resource>/savedata` 直接子目录作为 user-root；该子树不参与普通资源读取、存在性检查、枚举和资源扫描。其它重叠根及符号链接仍拒绝，分离的 user-root 继续供 JNI 回归夹具使用。
3. `0003-public-storage-ofd-writer-lock.patch`：当 `flock(LOCK_EX | LOCK_NB)` 仅返回 `ENOSYS`、`EOPNOTSUPP` 或 `ENOTSUP` 时，在 Android/Linux 尝试全文件非阻塞 `F_OFD_SETLK` 写锁。忙锁、权限错误与其他失败原样拒绝；OFD 失败也拒绝。OFD 锁随宿主持有的文件描述符关闭而释放。
4. `0005-amachoco-graph-startup-compat.patch`：仅对经资源指纹识别的 Amachoco ABI 恢复 `Graph91:00` 旧版空操作行为，避免启动时因上游未知调用处理收紧而退出；该原生调用的完整语义仍未核实。

历史补丁 `0004-virtual-user-file-reads.patch` 仅用于旧基线；新版上游已实现虚拟根与存档读取映射，不再应用该补丁。

```bash
cd /path/to/ethornell-rs
git checkout --detach 7cfc2fba8261e59c68095ab1ddc4f1f3534c27d8
git apply /path/to/TyranorNext/tools/ethornell/patches/0001-user-data-double-bgi-resource.patch
git apply /path/to/TyranorNext/tools/ethornell/patches/0002-game-local-savedata.patch
git apply /path/to/TyranorNext/tools/ethornell/patches/0003-public-storage-ofd-writer-lock.patch
git apply /path/to/TyranorNext/tools/ethornell/patches/0005-amachoco-graph-startup-compat.patch
cargo test -p ethornell-app --locked
cargo test -p ethornell-vm --locked
```

补丁顺序必须与 provenance 的 `local_patches` 一致；每份补丁后的源码哈希应与下一份补丁的输入对应。当前本地补丁变更 `file_policy.rs` 与 VM `lib.rs`，source inventory 必须覆盖所有构建输入，不能只用 `file_policy.rs` 单文件哈希代表整条补丁链。

锁回归应分别覆盖不支持操作与锁占用错误、同进程独立文件描述符及跨进程互斥、关闭和进程异常退出后的释放；公共共享存储必须单独测试，不能以应用专属外部目录替代。

## 构建与校验

使用 NDK `28.0.13004108`、API 26、cargo-ndk、锁文件及独立构建目录。精确参数见 provenance 的 `build_command`，链接必须保留 16KB 对齐、RELRO、NOW 与 `--no-undefined`。将产物 `libethornell_mobile.so` 经 NDK `llvm-strip --strip-unneeded` 后复制为本项目的 `libethornell.so`，同步更新库哈希、补丁清单、来源 inventory 和构建信息。

```bash
python3 tools/ethornell/verify_provenance.py --local-static \
  --ndk /path/to/android-sdk/ndk/28.0.13004108 --host-tag darwin-x86_64 \
  --report /tmp/ethornell-local-static.json
```

静态校验仅覆盖来源记录、ELF/ABI、依赖与包内库字节，不代表设备或游戏兼容性。带本地补丁的 `dirty=true` 产物不能通过要求精确已发布源码的严格来源审计，`release_ready` 保持 false。验证日志、截图与运行记录不提交代码库。
