# Ethornell CI 与源码来源门禁

这是 `README.md` 的 CI 补充，不替代静态验证器说明或设备验收。所有命令从 TyranorNext 根目录执行。Python 工具只用标准库；没有修改 App/engine/Rust、发版版本规则或 Gradle 配置缓存策略。

## 两种结果，不能混淆

| 入口 | 实际检查 | 外部源码 | 未提交构建 | 缺正式库 |
| --- | --- | --- | --- | --- |
| `Android CI` | Python 单测；构建 manifest/header/library hash；真实 ELF/依赖闭环；构建后的 debug APK 精确字节/闭环 | 不下载、不依赖开发者绝对路径 | 静态通过仍写 `source_status=pending`、`release_ready=false` | **失败** |
| `Android Beta Release` | 发布前的严格源码 + ELF 门禁；最终每个 release APK 字节/闭环 + `zipalign -c -P 16 -v 4` | 从固定公开 fork 获取 manifest 中的完整 commit SHA；独立枚举版本化策略范围内全部文件 | **失败，禁止自动发布** | **失败** |
| 手动 `Ethornell Native Acceptance` | 同严格源码 + ELF 门禁（不组装 APK） | 同上 | **失败** | **失败** |

`.github/workflows/ethornell-native.yml` 是复用门禁，`strict-release` 默认 `true`；只有普通 `Android CI` 显式传 `false`，这不是发布通道。发布 job 同时依赖版本变更判定和严格门禁成功；不使用 `continue-on-error`、缺文件条件 skip 或下载 HEAD 兜底。普通 CI 不因 dirty 引擎源码尚未公开而额外拉取失败，但静态本身、正式交付路径缺库、APK 字节变化仍必须失败。

证据报告通过 `actions/upload-artifact` 的 `always()` 保留。`if-no-files-found: warn` **仅**控制失败之前尚未生成 JSON 时的证据上传，不改变验证命令失败状态。Gradle 仍使用现有配置缓存设置，无 `--no-configuration-cache` 绕过。

普通 CI 的文案检查/JVM 单测位于独立 `regression-checks` job，不依赖 native 门禁；缺正式库时仍能提供原有回归诊断。APK 组装依赖两者成功，任何门禁失败仍使整体 CI 失败，无 `continue-on-error`。debug/regression job 显式设置 `contents: read`；本次涉及的所有 checkout（含复用工具测试、版本检测与发布）均关闭 `persist-credentials`，发布仅通过原有 `GH_TOKEN` 调用 GitHub API。

## 正式交付路径与真实 manifest

默认路径固定为：

```text
engine/src/main/jniLibs/arm64-v8a/libethornell.so
engine/src/main/jniLibs/arm64-v8a/libethornell.provenance.json
engine/src/main/cpp/ethornell_android.h
```

`verify_provenance.py` 消费引擎 `platform/android/build.sh` 的真实 JSON 字段，不创建假 manifest，不回填 `dirty=false`。strict 额外要求 builder 声明下述版本化 inventory policy；旧产物缺该字段只可做本地静态检查，不能进入 strict 成功路径。必需字段：

- `abi: 1`、`target: "aarch64-linux-android"`、`api: 26`、`ndk: "28.0.13004108"`、非空 `rustc` 版本。
- `source_revision`：完整 40 位小写 Git SHA；`dirty`：真正的 JSON boolean。
- `source_inventory_policy: "cargo-workspace-build-inputs-v1"`：strict 必须显式声明，未知 policy 失败。本地 legacy 检查缺省该字段时明确记为 `undeclared-legacy-pending`，不声称已核验源码。
- `source_file_sha256`：构建时按 policy 选取的完整文件清单，包含根 `Cargo.toml` / `Cargo.lock` 和 `platform/android/include/ethornell_android.h`，每项为小写 SHA256。
- `cargo_lock_sha256`、`library_sha256`、`library_bytes`：与清单/实际交付字节一致。

头文件按**原始字节 SHA256**关联到源码清单，而不只是比较 ABI 版本或导出名。JSON 重复键、越界/非规范相对路径、缺失/类型不符字段失败。非系统依赖只批准 `libc++_shared.so`，且必须同 ABI 包中真实存在并递归检查；没有泛化的 `lib*.so` allowlist。

CI 安装官方 **NDK 28.0.13004108**，使用 **`linux-x86_64`** host tag；不是 `linux-aarch64`，也不是开发机 `darwin-x86_64`。macOS 本地命令需显式传后者。

## 本地静态检查：不需要引擎 checkout

```bash
python3 -m unittest discover -s tools/ethornell/tests -v

python3 tools/ethornell/verify_provenance.py \
  --local-static \
  --ndk "$ANDROID_NDK_HOME" \
  --host-tag linux-x86_64 \
  --report ethornell-local-static.json

# 再检验最终 APK（主库必须逐字节等于正式交付库）：
python3 tools/ethornell/verify_provenance.py \
  --local-static \
  --ndk "$ANDROID_NDK_HOME" \
  --host-tag linux-x86_64 \
  --apk app/build/outputs/apk/debug/app-debug.apk \
  --report ethornell-debug-apk.json
```

`--local-static` 必须提供 NDK 并执行真实 ELF 检查，不能仅凭 manifest 哈希返回“native 通过”。该模式不调用原 CLI 的 `--source-root`，而是复用 `verify_native.py` 的 ELF/符号/16KB/API/依赖检查原语；没有拿 App checkout 冒充引擎。`ok=true` 仅指本次静态范围通过，**永远 `release_ready=false`**，即使 manifest 自称 `dirty=false` 也不改变这个结论。

若需要检查工作区外的开发产物，可显式 `--library /path/to/libethornell.so`、`--provenance /path/to/libethornell.provenance.json`；vendored header 默认仍来自 App 的正式头文件。CI 不配置这些开发机路径。

## 严格源码检查与发布门禁

```bash
# 新目录必须尚不存在；仅下载固定 fork 的指定 commit，不执行其中代码。
python3 tools/ethornell/verify_provenance.py \
  --fetch-source "$RUNNER_TEMP/ethornell-source" \
  --ndk "$ANDROID_NDK_HOME" \
  --host-tag linux-x86_64 \
  --report ethornell-native-acceptance.json
```

批准源固定为 `https://github.com/Weiss-UltimateSavior/ethornell-rs.git`，不从 manifest 接受任意下载 URL、tag 或 branch。下载前就拒绝 `dirty=true`；下载完整 SHA 不存在/不可公开获取时失败，不回退基线或 HEAD。新 checkout 的 root、origin、HEAD、clean status、按 policy 独立枚举的 tracked regular 文件路径/哈希均须匹配构建 manifest；拒绝 symlink、submodule、冲突状态。Git hooks/全局配置被关闭，不执行下载后的构建脚本。

### Runtime builder 的 policy 对接协议

`cargo-workspace-build-inputs-v1` 固定为：路径任何一段属于 `target/dist/raw/.git/testcase/reverse/out`，或扩展名（忽略大小写）属于 `.so/.dll/.exe/.ttf/.ttc/.otf/.arc/.sud/.gdb/.cad/.bcd/.zip/.7z` 时排除，其他 regular 文件全部纳入。builder 可在 dirty 开发模式从 `git ls-files --cached --others --exclude-standard` 收集，**发布仍必须 clean**；strict 从干净 Git tracked 文件独立枚举同一规则，不按 manifest 自报的文件名决定范围。规则变化必须换 policy 版本并一起审查；不要仅更改字段名。

另外 strict 有保守安全断言：

- `Cargo.toml` / `Cargo.lock`、所有 `.rs`（包含 `build.rs`）、`.sh`、`.h/.hpp` 不得藏入排除目录；发现即要求 policy review，不悄悄略过。真实 `raw/*.c` 逆向参考仍排除，不将其冒充 Cargo 编译源码。
- 对 inventory 中所有 Rust 文件扫描 `include_bytes!` / `include_str!` / `include!`；普通/原始字面量路径均需解析到同一根目录内、清单中已独立核验的 regular 文件。shader、任意 `.bin` 等非源码扩展名也要纳入，不能仅按 `.rs` 等后缀白名单收集。
- 引用排除目录/商业资产扩展名、根目录外文件、绝对路径、未 tracked/缺文件或动态 `concat!` / `env!` 路径直接失败，不从构建机借文件，不接受 manifest 自行加白名单。扫描是保守限制语法而非 Rust 宏解释器；注释中的类似文本可能导致需人工审查的误拒绝。

当前外部脚本已经使用上述排除规则，但观察时**尚未声明 policy 字段**；需要 runtime owner 修改其脚本并重建生成，不由 App gate 代填。任意 `build.rs` 文件 I/O、Rust 宏展开生成的依赖、`OUT_DIR` 生成输入和外部 Cargo 依赖源码闭环无法靠这套静态 inventory 证明，明确列为 `not_verified`。含这些用法时，完整可复现验收仍需独立构建输入捕获/隔离重建；这不是无条件自动证明输入闭环。

本地已有精确干净引擎 checkout 时可用 `--source-root /path/to/ethornell-rs` 代替 `--fetch-source`，仍验证上述 root/origin/revision/inventory，拒绝 App 根目录。**仅 `--source-root` 且不传 NDK 是来源一致性检查，绝不是 ELF 验收**；报告没有 `native`、`release_ready=false`。发布 workflow 始终传 NDK 并使用联网获取模式。

`release_ready=true` 仅表示本工具定义的**来源一致性 + ELF 静态发布门禁**均通过，不证明已重建二进制或做过设备验收。新版构建 manifest 已记录命令/rustflags/host/strip 信息，但仍缺完整 pinned Rust toolchain/feature 重现验证、签名 build attestation 和运行时 `build_info` 对照，工具如实列入 `not_verified`；不可把来源哈希吻合写成“可复现构建已验证”。API26/16KB 真设备 RTLD_NOW、ABI 行为、GPU/音频、真实游戏、许可证仍须独立验证。

## 交付限制（2026-10-15 本地观察）

- 开发产物曾通过 `--local-static` 的真实 NDK 检查：库 SHA256 `60b1ec7861aaf88c49191a41e99f9eeca39349fed597c0a7ce834ee9b7f0c395`；vendored header SHA256 `14765ba11710ea62c7f23b1679514a12eaa81e6ab2be0bb2c41f2625d2dbd2f2`。这是当时工作区外产物观测，后续重建需重新验，不是正式最终产物承诺。
- 同一 manifest 的 revision 为 `5556c36b53c9af29a19ba507ffa431b19300f0e7`、`dirty=true`：严格模式已实际返回 1，并在联网前拒绝。不能把基线 revision 当成带未提交移植改动的可公开重建来源。
- 当时正式 jniLibs 路径尚缺库：默认路径已实际返回 1 并报 `missing formal runtime ... no skip is permitted`。不会复制临时测试探针或生成假库让门禁转绿。
- APK 验收要求正式库与包内库**逐字节相同**。若 AGP 再 strip 导致变化，应由交付方提供最终已 strip 的正式库/匹配 manifest（另存未 strip 符号），或由构建负责人明确保持该库字节；不能通过去掉 hash 校验掩盖。最终 APK 与 Linux GitHub runner 本轮尚未在这里执行。
- 本轮可运行测试：原有 32 个 + provenance 34 个 = **66 tests passed**；三份 workflow 经 `actionlint v1.7.7` 校验通过；范围内 `git diff --check` 通过。新增 strict + NDK mock 测试使用真实临时干净引擎 Git 仓库/完整 inventory，只 mock native 校验，断言实际 engine root 而非 App root 传入；dirty manifest、dirty checkout、缺清单文件或 native 失败均不允许 `release_ready=true`，且不改写 manifest。policy 测试覆盖未知/缺 policy、独立枚举 Cargo/build/Rust/shell/header/shader 输入、字面量 include 闭环、商业/输出排除边界、动态/越界 include 拒绝。单测的合成 ELF/假工具输出只验证验证器，不是 native 产物。
- 已读取 Android CLI 和其可发现的权限安全审查 skill；尝试两路独立子 agent 审核被 harness `subagent depth limit (1)` 拒绝，须由父会话另行独立复核，未声称完成多 agent 审核。
