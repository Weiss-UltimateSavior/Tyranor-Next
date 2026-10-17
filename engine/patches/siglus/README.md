# Siglus 原始舞台帧下游补丁

本目录为滤镜处理增加可选的原始舞台几何接口。2026-10-13 重新读取并 clone 只读参考仓库 `xmoezzz/siglus_rs`，其最新公开 `main` 仍为 `5f358ef1360b0439e92aed2b749e8f25d8721954`。补丁只应用于本项目 `build/` 下的独立构建副本，不修改参考仓库。

本轮从该公开源码重新构建完整 `game_launcher`，而非用旧 PR 的补丁二进制覆盖最新 TYN 库。TYN `87615940bbdd379d9ef22f58747a59278a365c15` 的原始库 SHA-256 为 `fdef411e1f30550ef7adb7ab4ff1f383433aee94be7609a481eb18c13c3563df`；其编译元数据为 Rust 1.96 / NDK r28 / API 21，公开提交未提供完整构建来源。这里使用固定 Rust 1.99 / NDK 30 / API 26 重建最新公开源码，不宣称复现该维护者本机产物。

## 接口与呈现路径

`siglus_android_get_stage_geometry_v1(handle, out_geometry, out_count)` 是用于滤镜输入核验和帧提交节流的可选 C ABI。调用者必须在引擎所属线程传入仍存活的 handle，以及至少十个可写 `uint32_t` 的输出空间。返回 1 表示成功，空指针或数量不足返回 0；JNI bridge 另检查自身持有的 live handle。

| 数组索引 | 含义 |
| --- | --- |
| 0–1 | VM 逻辑舞台宽、高 |
| 2–3 | renderer 实际配置的生产 Surface 宽、高 |
| 4–7 | renderer viewport 的 x、y、宽、高 |
| 8–9 | render serial 的低、高 32 位，无符号位模式 |

生产 Surface 和 viewport 必须与逻辑舞台同尺寸且无偏移，宿主才启用滤镜。路径为完整舞台合成 → 同尺寸 SurfaceTexture → 按变换矩阵 OES 转 RGBA8 → 公共有序 GLES 滤镜链 → 等比屏幕 viewport。完整舞台包含文本、UI 与转场；不导出内部 wgpu 纹理，也不报告素材分辨率。

原有 `set_surface` 实际返回 `i32`，补丁同步修正头文件声明。JNI 持有 `ANativeWindow_fromSurface` 的引用直到 Rust Surface 成功替换或销毁；替换失败释放新引用。handle 调用留在 Activity 线程，消费端使用独立 GLES 线程。

## CI 构建与交付

专属 `.github/workflows/siglus-graphics-ci.yml` 在 Ubuntu 24.04 上构建，允许手动运行，并由入库的 `libsiglus.so`、补丁、manifest、构建工具或该 workflow 的 push 触发。它同时作为可复用 workflow 接入 Android CI 的 PR / main push / 手动运行，以及版本变化后的 Beta 发布；Debug 打包和 Beta 发布均须等待该来源门禁通过。不同调用方的并发组相互独立，不会互相取消门禁。流程为：

1. 校验固定源码归档、Cargo.lock、下游补丁及最新 TYN 55 个旧 ABI 清单的 SHA-256。
2. 将归档解到独立 `build/siglus-ci/source`，核验 Git baseline 的所有 blob 与归档一致；先构建无补丁 `game_launcher --lib` 对照库。
3. 应用三个文件的 geometry 补丁；使用临时 Git index 反向还原并比较整个 baseline tree，拒绝额外源码修改，再构建完整补丁库。
4. 核对 Rust 完整 commit 与 NDK 精确版本、全部旧 ABI、仅新增 geometry ABI、16 KiB ELF LOAD 对齐；使用固定 `llvm-nm` 与独立 ELF 解析交叉检查导出。
5. 固定核对 `engine/src/main/jniLibs/arm64-v8a/libsiglus.so`：manifest 必须指向该入库库、状态必须为 `ci-rebuilt-patched`，SHA 必须一致，并逐字节比较入库库与已验证的固定来源补丁构建产物；不再接受等待重建的 baseline 状态。此处验证入库库，不宣称 Gradle strip 后的 APK 库字节完全相同。
6. 上传 baseline / patched 两个库和来源、SHA、ABI、ELF 报告。workflow 不替换 `jniLibs`、不改 manifest、不提交代码。

构建指纹包括实际源码、依赖锁、补丁、工具链、ABI 锚点、工具和 workflow，排除安装库 SHA 等交付元数据。缓存只保存已验证的两个库和报告，使用精确指纹，无模糊回退；命中后再次核验文件 SHA、来源字段、导出及对齐，避免仅更新交付 SHA 时重复编译。

本次已固定[原生 CI #38421811796](https://github.com/AAAA1111-Lab/Tyranor-Next/actions/runs/38421811796)的完整补丁库，`libraryStatus=ci-rebuilt-patched`，SHA-256 为 `d206e7e104dcf794767de120d0264374afed42735de16d121875ec523de02612`。下载产物已核对 GitHub ZIP SHA、来源/构建指纹及库报告；该库保留 55 个旧导出并新增舞台接口。最终 App CI 对包含此库的候选运行，具体提交和结果在 PR 中记录；此前 Windows 补丁二进制不作为本轮产物。

## 构建工具

工具支持 Windows 与 Linux；需 Python、Git、CMake、固定 Android NDK `30.0.16248370`、Rust `1.99.0` / `b940084d7eb6a299eb4bfeb8e34901bc051e7ac4` 和 `aarch64-linux-android` target。Rust 1.99.0 的 Linux 发行可从官方固定版本清单取得；不会把浮动 stable 当成版本依据。Cargo/Rustup home 可使用独立目录，不修改本机全局配置。

```bash
python3 tools/build-siglus-graphics.py \
  --ndk "$ANDROID_SDK_ROOT/ndk/30.0.16248370" --cmake-bin /usr/bin \
  --cargo-home "$CARGO_HOME" --rustup-home "$RUSTUP_HOME" \
  --build-dir build/siglus-ci --baseline-only
python3 tools/build-siglus-graphics.py \
  --ndk "$ANDROID_SDK_ROOT/ndk/30.0.16248370" --cmake-bin /usr/bin \
  --cargo-home "$CARGO_HOME" --rustup-home "$RUSTUP_HOME" \
  --build-dir build/siglus-ci
```

Windows 可传入 SDK 的 CMake bin 路径、独立 Cargo/Rustup home，必要时显式指定 `--toolchain 1.99.0-x86_64-pc-windows-gnu`；仍核对完整 compiler commit。`--prepare-only` 只准备并验证源码和工具链，`--verify-staged` 仅检查已有 CI 产物，均不接受与 `--install` 联用。`--install` 仅接受与 manifest 最终 SHA 完全匹配的补丁构建库，通过临时副本核验后原子替换；baseline 不可安装。

库包含 Siglus 与 RealLive / AVG32 / UK2 的全部既有导出，不能用仅含 `siglus_scene_vm` 的库替换。ABI 一致、CI 编译通过不等于共享引擎全部真机回归；本轮仅执行 CI 验证。

## 许可证与源码

Siglus 上游声明 MPL-2.0。修改后的 MPL 文件按 manifest 的固定源码提交与本目录下游补丁取得；对应完整上游源码可从 `https://github.com/xmoezzz/siglus_rs/tree/5f358ef1360b0439e92aed2b749e8f25d8721954` 获取。第三方包按各自许可证，不据根许可证推定全部子包授权。
