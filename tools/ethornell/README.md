# Ethornell / BGI 验证工具

仅使用 Python 3.9+ 标准库；不修改 App / 引擎、不下载任何游戏、不调用商业资产。
从 TyranorNext 根目录执行。验收分为静态 native 产物门禁、公开格式夹具、设备/真实游戏验证；三者不能互相替代。

## 1. 工具单测（无 NDK / 无设备）

```bash
python3 -m unittest discover -s tools/ethornell/tests -v
```

覆盖单头解析、未知声明 fail-closed、真实 ELF 字节 note/program-header 解码、readelf/nm 文本解析与交叉核对、16KB LOAD/RELRO 数值计算、依赖 allowlist/缺包、NDK Linux CI host 路径、CLI 成功/失败状态、APK 字节比对/递归依赖闭环、BP/PackFile 结构/损坏输入、PNG CRC/像素、夹具禁止覆盖。
测试中的人工 ELF 和假工具输出只用于验证验证器本身，不是 Android 库或可加载产物，不得用这些测试替代真实产物检查。

## 2. 正式 native 产物门禁

```bash
python3 tools/ethornell/verify_native.py \
  --library /path/to/dist/arm64-v8a/libethornell.so \
  --header '/path/to/ethornell-rs/platform/android/include/ethornell_android.h' \
  --source-root '/path/to/ethornell-rs' \
  --ndk "$ANDROID_NDK_HOME" \
  --host-tag linux-x86_64 \
  --manifest /path/to/native-acceptance.json
```

- macOS 官方 NDK 常用 `--host-tag darwin-x86_64`（含 Apple Silicon 的 NDK 包名）；Windows 用 `windows-x86_64`。
- 可用 `--tool-bin /path/to/ndk/toolchains/llvm/prebuilt/<host>/bin` 显式覆盖工具定位；未传 `--ndk` 时读取 `ANDROID_NDK_HOME`。不猜 SDK 或工作区外资产目录。
- 未传 `--manifest` 时 JSON 写 stdout。**退出码 0 才是本工具静态门禁通过；1 表示检查失败/输入或工具错误**。不依靠固定 grep 或“命令运行完”判通过。
- 头文件是 required ABI 唯一来源：去掉注释后解析所有 `uint32_t/int32_t ethornell_android_*(...);`（允许多行）。任何 ABI 前缀标识符未被该语法识别、重复声明或版本宏缺失都会失败；这不是通用 C parser。头文件新增其他返回类型/注解时必须更新解析器及测试，不能静默漏检。
- 使用 `llvm-readelf -h -lW -dW --dyn-syms -n` 和 `llvm-nm --dynamic --extern-only --format=posix`；两者均必须成功且无 stderr 诊断。required 导出必须是**未版本化的 GLOBAL DEFAULT、已定义 FUNC**，nm 也须报告 `T`。仅名字相同的数据符号/未定义符号不算；出现单头未声明的 ABI 前缀导出也失败，避免验收错版契约。
- 独立解析真实 ELF64 LE ET_DYN/AArch64 二进制 program-header 和 Android owner/type=1 note；与 readelf 段信息交叉核对。主库要求恰好一个 **API 26** note，闭环中的打包依赖要求恰好一个 **API ≤26** note。可通过 `--api` 明确切换测试基线，默认 26。
- 所有 LOAD 的 `p_align` 须为 ≥16384 的 2 次幂；同时检查 `p_vaddr ≡ p_offset (mod p_align)`、`filesz ≤ memsz`。不能错误要求每个 offset/vaddr 单独是 16KB 整数倍。
- 每个 GNU_RELRO 按 **`(VirtAddr + MemSiz) % 16384 == 0`** 计算；不是检查 RELRO `p_align`。项目门禁还要求存在 RELRO（这是额外安全策略；官方文档不把“没有 RELRO”本身当作页大小不兼容）。
- 在导入和导出两侧拒绝 `android_main`、`ANativeActivity_*`、`GameActivity_*`、`android_app_*` 及可识别的 winit/android-activity glue 符号；`ANativeWindow_*` 属于正常 Surface 宿主依赖。符号检查不能证明所有隐藏/剥离后的 winit 代码都不存在，还应审查 Cargo feature/依赖树。

### 包内依赖闭环

默认把 `--library` 同级目录视为一个平铺 arm64-v8a 包；可用 `--package-dir` 指定最终合并后的 arm64-v8a 目录。不要用不完整 dist 目录伪称已验收 APK。

固定系统库 allowlist 见 `verify_native.py::SYSTEM_LIBRARIES`。`libc++_shared.so` **不是**系统库；其他非系统依赖必须逐个 `--allow-bundled` 批准且实际存在于同 ABI 包中，否则失败。只批准名字、不把库打进包同样失败；允许的依赖会继续递归检查 ELF/note/16KB/其 DT_NEEDED（包括依赖环去重）。不是无条件允许所有 `lib*.so`。

```bash
python3 tools/ethornell/verify_native.py \
  --library /path/to/dist/arm64-v8a/libethornell.so \
  --header /path/to/ethornell_android.h \
  --source-root /path/to/ethornell-rs \
  --tool-bin /path/to/ndk-llvm/bin \
  --allow-bundled libc++_shared.so \
  --apk app/build/outputs/apk/debug/app-debug.apk \
  --manifest /path/to/apk-native-acceptance.json
```

`--apk` 与 `--package-dir` 互斥；从 ZIP 读取 `lib/arm64-v8a/*.so`（不执行 ZIP 路径解压），拒绝重复库条目，主库必须与 `--library` **逐字节一致**。其他不相关引擎 `.so` 不纳入 Ethornell 依赖闭环，本工具不声称整个多引擎 APK 全部 16KB 通过。

### Manifest 与证据边界

报告含库 SHA256/字节数、头 SHA256、头 ABI 版本/required symbols、运行验证器时引擎 checkout 的 revision/dirty/porcelain status、工具版本、策略、每段数值、DT_NEEDED、全部动态导入/导出及 readelf/nm 原文；APK 模式另含 APK SHA256。

**源码信息是当前 checkout 观测，不是库由该 checkout 构建的证明**：验证器不会重建库，也不会伪造 build attestation。发布必须把此报告与构建时的 revision/dirty、Cargo.lock、编译命令/feature/toolchain、引擎 build_info 和未 strip 符号关联核对；dirty=true 不能当成可复现的干净 revision。

静态通过仍不证明 API 26 系统符号全部可解析、RTLD_NOW 成功、ABI 结构体布局/调用行为、GPU/音频/VM/生命周期、16KB 真设备执行、ZIP 对齐或许可。还必须运行真实 API26 / 16KB 设备检查和 `zipalign -c -P 16 -v 4 <apk>`；NDK 文件名/Android note 都不能代替加载实测。官方数值依据：Android CLI `android --sdk=/tmp/androidsdk docs fetch kb://android/guide/practices/page-sizes`。

## 3. 自生成公开 smoke 数据

```bash
python3 tools/ethornell/generate_fixture.py --output /path/to/new/bgi-smoke-ret
python3 tools/ethornell/generate_fixture.py --profile request-close \
  --output /path/to/new/bgi-smoke-close
```

输出目录**必须尚不存在**，即使已有数据恰好同名也不会覆盖。只生成 `system.arc` 和 `fixture.json`，不生成/复制 EXE、商业脚本、字体或音频。

格式依据来自当前引擎公开源码：

- `ethornell-archive/src/lib.rs::read_packfile_index`：`PackFile    ` 12 字节 + LE u32 count；32 字节索引项 = 16 字节 NUL 填充名 + 相对 data-base 的 u32 offset + u32 size + 8 零字节。data-base = 16 + count×32。
- `ethornell-script/src/lib.rs::detect_code_range` 及 `bp_alignment_padding_after_the_final_return_is_not_code`：16 字节 BP 头，前两个 LE u32 = header_size、module_size；其和须等于总长度。
- `ethornell-script/src/vm_opcode.rs`：`0x17 ret`，无调用返回记录时结束顶层 VM。
- `ethornell-vm/src/native_call.rs::SYS_REQUEST_WINDOW_CLOSE`：`0x80 0x69` 无参数/无返回值，提出窗口关闭请求，宿主 close-mode 决定实际退出路径。

两档都在 archive 内提供 `ipl._bp`，不是把任意文本文件叫 BP：

| profile | code | 能测什么 | 不能测什么 |
|---|---|---|---|
| `ret`（默认） | `17` | archive index / bootstrap BP / 顶层 VM 返回的最小路径 | 不保证 host Closed 事件，更不保证真实游戏可玩 |
| `request-close` | `80 69 17` | 在 VM 执行后的脚本关闭请求 | 不保证确认弹窗结果；harness 必须处理 event/request |

另外 archive 内含本工具数学构造的 4×4 四色 RGBA `checker.png`（PNG IHDR/IDAT/IEND、zlib、CRC），供后续纹理/viewport harness 使用。**当前 BP 没引用它，不宣称已显示纹理、clear、redraw/present，更不是游戏截图**。

生成后执行独立格式校验并写入 fixture manifest（每个文件/条目 size + SHA256、BP 头/指令、预期行为/局限）。单测还独立检查索引原始偏移、截断/重叠/尾字节、PNG CRC/像素，不只比较生成函数自身输出。

### 留给 HostRuntime / Android harness 的接线要求

1. 明确资源根 = 夹具输出目录，用户根 = 另建的空可写目录；字体由合法用户/系统字体提供，显式 silent 模式可避免把无音频夹具当音频验证。
2. create → 设置真实 Surface → step（单调时钟、至少跨过 16ms 调度门限）→ poll events；无 Surface 的暂停不是 VM 执行证据。
3. `ret` 要取真实 VM stop/report 验证，不能因 Activity 没崩就计通过；当前 C ABI 若不暴露 VM completion，使用引擎侧 HostRuntime 测试/明确诊断，不通过截图猜测。
4. `request-close` 验证脚本真实执行并在关闭请求/确认完成后收到 kind=3；若收到 kind=2，按 ABI 对同 request_id 恰好完成一次，再验证 Closed。不凭固定延时或启动成功判定退出。
5. 后续需要新增能显示 checker 的脚本时，先根据已审 native opcode 参数/栈效应写对应 fixture profile 与结构测试，再用真实 VM/renderer 报告证明；本工具不会为了凑画面改引擎业务实现。

这些数据是**合成格式/宿主冒烟夹具，不是原版 BGI 游戏，也不是兼容性样本**。真实作品的标题/对白/音频/存读档和 GPU 矩阵仍是独立验收门槛。
