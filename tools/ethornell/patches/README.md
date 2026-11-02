# Ethornell 本地兼容补丁

本目录保存随包 `libethornell.so` 使用的本地补丁。补丁不是上游发布内容，必须与库、ABI header 和 provenance 成套核对。

## 1. 当前补丁

| 项目 | 值 |
| --- | --- |
| 文件 | `0001-user-data-double-bgi-resource.patch` |
| SHA-256 | `77b99d9392f521a6d9b7313b6e4220e259e0634028fc01c6f74f395c37051138` |
| 基线 revision | `0cfff1d274ecc4554d0af8af1c7b1c7908595e96` |
| 目标文件 | `crates/ethornell-app/src/file_policy.rs` |
| 目标文件 SHA-256（应用后） | `be3547f738267281b6a53326067757554c59cf4e3895661273f9a9674593b423` |
| 随包库 SHA-256 | `043fef05d0485f027cd5b344a93ddfecd52f97e8e7f8a12fe2986dbcab376d3e`（27,259,224 B） |
| inventory SHA-256 | `d30b0774d1175e9bfb474024a8878a89d7f244d16591459a7dbe44129a23c494` |

### 作用与边界

补丁处理两条启动期全局数据路径：

1. `Sys80:32` 可能把已经带 `bgi-resource\` 前缀的 `UserData` 路径再次拼上资源根，形成
   `bgi-resource/bgi-resource/UserData/...`。原策略只识别单前缀，于是把这次写入送往只读资源根。
2. 在语言选择页点击语言会触发 `SaveGlobalUserData` 写入 `bgi-resource\BGI.gdb`；上游策略只放行
   常规 `UserData`，该写入同样落到只读资源根。旧 `7ee` 写的是裸 `BGI.gdb`，所以这是 `0cfff1d`
   的行为面变化，不是本补丁引入的回归。上一版 `541bf6` 只修了第 1 条，点击语言后仍以
   `status=-10 ... stop=8 ... group=128 id=95` 和 `bgi-resource\BGI.gdb` 的 `PermissionDenied` 失败。

补丁在 `user_data_alias()` 中先收敛**一层**重复 `bgi-resource/` 前缀，再套用下列别名：

- 重复前缀下的常规 `UserData` 子树映射到私有 `bgi-user/UserData`；
- 仅精确匹配顶层 `bgi-resource/BGI.gdb` 与 `bgi-resource/BGI####.cad`（四位数字，大小写不敏感），
  分别映射到私有 `bgi-user/BGI.gdb` 与 `bgi-user/BGI####.cad`；
- 其它任意资源路径、子目录、形似名（如 `BGI00001.cad`、`BGI.gdbx`、`foo/BGI.gdb`）与路径逃逸
  保持只读/拒绝；单前缀资源与 `relative()` 的逃逸拒绝不变。

该补丁只修**启动期/全局数据写入路径**。`Sys80:34`、`Sys80:30/31/35` 与 loose read 等存档存在性/读取 API **不在覆盖范围**，商业游戏内槽位保存/读回、日期/缩略图与 PC 存档互通也未因此通过验收。

## 2. 应用与检查

在批准 fork 的源码根目录执行（目录名按本机替换）：

```bash
git clone https://github.com/Weiss-UltimateSavior/ethornell-rs.git /path/to/ethornell-rs
cd /path/to/ethornell-rs
git checkout 0cfff1d274ecc4554d0af8af1c7b1c7908595e96

# 只检查能否应用，不写文件
git apply --check /path/to/TyranorNext/tools/ethornell/patches/0001-user-data-double-bgi-resource.patch

# 应用并核对补丁/目标文件
git apply /path/to/TyranorNext/tools/ethornell/patches/0001-user-data-double-bgi-resource.patch
shasum -a 256 /path/to/TyranorNext/tools/ethornell/patches/0001-user-data-double-bgi-resource.patch
shasum -a 256 crates/ethornell-app/src/file_policy.rs
```

`git status --short` 此时应只有 ` M crates/ethornell-app/src/file_policy.rs`；这是**故意 dirty** 的构建输入，不是可发布的 clean revision。

## 3. 单元测试

在源码根目录执行：

```bash
# 新增的双前缀用例
cargo test -p ethornell-app doubled_resource_prefix_only_aliases_the_conventional_userdata_subtree --locked

# 新增的全局数据与拒绝用例
cargo test -p ethornell-app resource_root_global_data_aliases_to_private_user_root --locked
cargo test -p ethornell-app resource_root_global_data_lookalikes_stay_read_only --locked

# file_policy 整组（当前 14/14）
cargo test -p ethornell-app file_policy --locked
```

本轮记录：`file_policy` Rust 测试 14/14 通过，含双前缀与 `BGI.gdb`/`BGI####.cad` 别名、形似名拒绝用例。测试通过只证明该策略分支，不代表设备存档或商业游戏槽位通过。

## 4. 构建 Android 库

构建需要 NDK `28.0.13004108`、cargo-ndk 与锁文件。以下路径按本机替换，链接参数与随包产物一致：

```bash
cd /path/to/ethornell-rs
export ANDROID_NDK_HOME=/path/to/android-sdk/ndk/28.0.13004108
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384 -C link-arg=-Wl,-z,relro -C link-arg=-Wl,-z,now -C link-arg=-Wl,--no-undefined"

cargo ndk -t arm64-v8a -P 26 -o /path/to/build/output build -p ethornell-mobile --release --locked

"$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-strip" --strip-unneeded \
  /path/to/build/output/arm64-v8a/libethornell_mobile.so

cp /path/to/build/output/arm64-v8a/libethornell_mobile.so \
  /path/to/TyranorNext/engine/src/main/jniLibs/arm64-v8a/libethornell.so
```

上游产物名是 `libethornell_mobile.so`，随包名是 `libethornell.so`。构建后必须重新生成/核对
`libethornell.provenance.json`：保留真实 `dirty=true`、`local_patches=1`、补丁路径/哈希、
`file_policy.rs` 哈希与 inventory，不能回填 `dirty=false`。

精确的构建命令、输出目录与日志也应写入 provenance 的 `build_command`/`build_log*` 字段；上例只是可执行模板，不替代当次 manifest。

## 5. 验证边界

```bash
python3 tools/ethornell/verify_provenance.py \
  --local-static \
  --ndk "$ANDROID_NDK_HOME" \
  --host-tag darwin-x86_64 \
  --report ethornell-local-static.json

python3 tools/ethornell/verify_provenance.py \
  --local-static \
  --ndk "$ANDROID_NDK_HOME" \
  --host-tag darwin-x86_64 \
  --apk app/build/outputs/apk/debug/app-debug.apk \
  --report ethornell-debug-apk.json

python3 tools/ethornell/verify_provenance.py \
  --local-static \
  --ndk "$ANDROID_NDK_HOME" \
  --host-tag darwin-x86_64 \
  --apk app/build/outputs/apk/release/app-release.apk \
  --report ethornell-release-apk.json
```

- `--local-static`（含 debug/release APK 精确库字节）通过时报告 `ok=true`，但**永远** `release_ready=false`；它只覆盖来源记录、ELF/符号/16KB/API/依赖与 APK 字节，不等于设备或游戏兼容验收。
- 严格来源审计（`--fetch-source` 或精确 clean `--source-root`）要求干净、可公开获取的源码；带本补丁的 `dirty=true` 产物会被拒绝，**不能**声称 strict provenance 通过。自动发布允许在 `--local-static` 通过时使用 dirty 本地产物，strict 仅作为可选审计。
- 16KB 真设备执行、`zipalign -c -P 16 -v 4`、API26/GPU、音频路由、字体/电影、长稳与许可/SBOM 仍须独立验证。

## 6. 当前证据与未验证项

- 已通过：`--local-static`（`ok=true`，仍 `release_ready=false`）与 debug/release APK 精确库字节；release zipalign（build-tools 36.0.0；37.0.0 未安装）；Amairo 私有 Activity 30s/45s 含语言点击均存活（点击后无 `failed_detail`），私有 user-root 生成 `BGI.gdb`（约 123,802 B）。新夹具下 `UserData` 可能为空，不据此声称存档槽位写入。
- 未通过/未验证：严格来源审计（dirty）、标题/对白、商业槽位保存/读回、`Sys80:34`、`Sys80:30/31/35`、loose read、16KB/API26 完整游戏重跑、PC 存档互通、音频/字体/长稳。
- 测试记录：`file_policy` Rust 14/14；tools 79 OK；app 910 / engine 583 为代码未变时的既有运行；`EthornellDeviceTest` 6/6（1.426s）、`EthornellPauseDeviceTest` 3/3（2.999s）、`EthornellSaveDeviceTest` 9/9（`pm clear` 后，2.047s）；Release R8 probe SJIS/GBK 各 3/3（`NUMTEST=3`、`FAILURES=0`）。
- Jewel 样本仍在 `system.arc:ipl._bp` 未知 opcode 停止；旧 `7ee` 同样失败，不是本补丁引入的回归。
