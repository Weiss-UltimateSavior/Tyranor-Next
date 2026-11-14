# xsystem4（System4）内置引擎 —— 预构建与构建剧本说明

本目录是 TyranorNext 内置 **System4（xsystem4）** 引擎的预构建剧本与溯源材料
（方案 `docs/System4-xsystem4-引擎接入方案.md` §5.4 / §3.3）。

- 引擎运行库 `libxsystem4.so` **预构建入库** `engine/src/main/jniLibs/arm64-v8a/`，
  与 `libxsystem35.so` / `libSDL2x.so` 同模式；Gradle 不参与原生构建。
- 着色器 53 个**原样复制**入 `engine/src/main/assets/shaders/`（保留 `effects/` 子目录）。
- **xsystem4 / libsys4 / libsndfile 源码与全部静态依赖不随主仓分发**：源码来自本账号 fork 的固定 commit，
  依赖按 SHA 校验下载到**仓库外**构建目录（默认 `${TMPDIR:-/tmp}/xsys4-build`，`--build-dir` 覆盖）。
- 本仓库不包含 `patches/`：本次构建未对上游源码打任何补丁（见 §5 偏差与说明）。

主仓目录布局：

```
engine/xsystem4/
├── android-host/cmake/     ← 自研 find 模块：FindSDL2.cmake / FindFreetype.cmake / FindSndFile.cmake
├── scripts/build-xsys4.sh  ← 一键可复现构建（源码 + 依赖 + staging + 构建 + 门禁 + 入库）
├── licenses/               ← 随二进制分发的许可文本（§7）
└── BUILD.md                ← 本文档
```

---

## 1. 上游基线

| 项 | 值 |
| --- | --- |
| xsystem4 fork | <https://github.com/Weiss-UltimateSavior/xsystem4> @ `46f7c253d63d7b513516f42af027419314cbf047`（`master`，含 `--encoding`；2026-11-14 基线，上一基线 `6a94a0c5…`） |
| 上游 | <https://github.com/nunuhara/xsystem4>（fork 内容与其一致，不含源码改动） |
| libsys4 fork | <https://github.com/Weiss-UltimateSavior/libsys4> @ `e2bf438d0156a2ca70fc6018fddf7c870794a2ca`（脚本 clone 到 `subprojects/libsys4` 并检出；不改 `.gitmodules`） |
| libsndfile fork | <https://github.com/Weiss-UltimateSavior/libsndfile>，**`xsystem4` 分支**，@ `be82f4be73c77f1e759bda9dfe1625204441465a`（不在默认分支） |
| 许可 | xsystem4 / libsys4：GPL-2.0-or-later；libsndfile：LGPL-2.1-or-later |

> **修改声明**：fork 本身不承载修改；构建参数（§5）与自研 find 模块（§4）位于主仓。
> 壳仓库 `kichikuou/xsystem4-android` 及其 fork **未作为构建输入**，其 Kotlin / CMake / `cmake/Find*.cmake` 均未拷贝（无 LICENSE）。

---

## 2. 源码与依赖来源（不随主仓分发）

`scripts/build-xsys4.sh` 全自动完成到仓库外构建目录。下载 tar 包缓存在 `<build-dir>/downloads/`，
解压到 `<build-dir>/third_party/<目录>`；校验失败即退出。若 `${TMPDIR}/xsys35-build/downloads/` 存在同名且校验通过的
tar 包，直接复用（SDL2 / freetype 与 System35 共用）。

### 2.1 依赖校验表（实际下载并校验通过）

| 依赖 | 版本 | 实际 URL（首选；失败时尝试备用） | 校验 | 许可 | 形态 |
| --- | --- | --- | --- | --- | --- |
| SDL2 | 2.32.10（**仅头文件**，与 System35 同 tar） | github.com/libsdl-org/SDL release-2.32.10 `SDL2-2.32.10.tar.gz` | SHA1 `ce98fa93e31836a751feca374ab28a0770b63c16` | zlib | 运行库复用 `libSDL2x.so` |
| freetype | 2.14.3（与 System35 同 tar） | sourceforge `freetype-2.14.3.tar.gz`（备用 savannah） | SHA1 `a911f65c6355ddcf7aaba46e250dbc00f67e1678` | FTL / GPLv2 | 静态 |
| libogg | 1.3.5 | downloads.xiph.org `libogg-1.3.5.tar.xz` | SHA1 `5a368421a636f7faa4c2f662857cb507dffd7c99` | BSD-3 | 静态 |
| libvorbis | 1.3.7 | downloads.xiph.org `libvorbis-1.3.7.tar.xz` | SHA1 `0a2dd71a999656b8091506839e8007a61a8fda1f` | BSD-3 | 静态 |
| libjpeg-turbo | 3.1.0 | github.com/libjpeg-turbo release 3.1.0 `libjpeg-turbo-3.1.0.tar.gz` | SHA1 `ba3c0152f14a504018de19c9f62250d8f3351525` | BSD-3 / IJG | 静态（`turbojpeg-static`） |
| libwebp | 1.3.2 | storage.googleapis.com webp releases `libwebp-1.3.2.tar.gz` | SHA1 `f941661a869df7347d7ca9208acb4e6d41f614de` | BSD-3 | 静态 |
| libpng | 1.6.47（`.tar.xz`） | sourceforge `libpng-1.6.47.tar.xz`（备用 download.sourceforge.net） | **SHA256** `b213cb381fbb1175327bd708a77aab708a05adde7b471bc267bd15ac99893631` | libpng | 静态 |
| libffi | 3.4.6 | github.com/libffi release v3.4.6 `libffi-3.4.6.tar.gz` | SHA1 `19251dfee520dff42acefe36bfe76d7168071e01` | MIT（libffi 许可） | 静态 |
| libdeflate | 1.25 | github.com/ebiggers/libdeflate release v1.25 `libdeflate-1.25.tar.gz` | SHA1 `7e70557d094b54c3ee16f6c012976dc8d685b22a` | MIT | 静态 |
| cglm | 0.9.2 | github.com/recp/cglm `archive/refs/tags/v0.9.2.tar.gz` | SHA1 `cb8472aa8c2ab67b66378dbaf10c2c7368d4e4c3` | MIT | 静态（经 FetchContent 本地目录） |
| libsndfile | xsystem4 分支 `be82f4b…` | §1 fork | commit | LGPL-2.1-or-later | 静态 |
| libsys4 | `e2bf438…` | §1 fork | commit | GPL-2.0-or-later | 静态（xsystem4 子项目） |
| LGPL-2.0 全文 | — | gnu.org `old-licenses/lgpl-2.0.txt` | **SHA256** `cc535c21133c895b56b374c8a1dc1eb948d99003ed2b47372069456b62f42b24` | — | 仅许可文本（§7） |

> 实测：SHA1 / SHA256 全部一致；首次下载时 freetype 的 sourceforge 源经 curl 内部重试后成功（备用源未使用）。

### 2.2 `libSDL2x.so`（复用，不重建）

`engine/src/main/jniLibs/arm64-v8a/libSDL2x.so` 为 System35 已入库产物，构建前脚本校验其 SHA-256 必须为
`cee53fbc5fd41a895658ce98401cd6aa033ad6039b426d4401f9ac1b3caba0c1`（即 `engine/xsystem35/BUILD.md:251`）。
本次构建**未改动**该文件（`git status` 无变化）。

---

## 3. 工具链

| 工具 | 版本 / 路径 | 备注 |
| --- | --- | --- |
| NDK | `~/Library/Android/sdk/ndk/28.0.13004108`（r28） | clang 19.0.0（Android 12896553） |
| CMake | `~/Library/Android/sdk/cmake/3.31.1/bin/cmake`（cmake 3.31.1） | 不用 homebrew cmake 4.x |
| Ninja | 同目录（1.13.2） | 脚本自动加入 PATH |
| bison | `/opt/homebrew/opt/bison/bin/bison`（3.8.2） | 系统 `/usr/bin/bison` 为 2.3，不可用；脚本显式 `-DBISON_EXECUTABLE` |
| flex | `/usr/bin/flex`（2.6.4） | 脚本显式 `-DFLEX_EXECUTABLE`；`brew --prefix flex` 不存在时回落系统 flex |
| llvm-readelf / llvm-nm / llvm-strip | NDK `toolchains/llvm/prebuilt/darwin-x86_64/bin` | 产物核对与 strip |

---

## 4. 自研 find 模块（`android-host/cmake/`）

上游 `CMakeLists.txt` 需要 `find_package(SDL2)`、`find_package(Freetype)`、`find_package(SndFile)`。
这三个模块由本项目自行编写，**不拷贝**壳仓库的 `cmake/Find*.cmake`：

| 模块 | 提供的目标 | 要点 |
| --- | --- | --- |
| `FindSDL2.cmake` | `SDL2::SDL2`（IMPORTED SHARED） | 头文件取 SDL2 2.32.10 tar 的 `include/`（`XSYS4_SDL2_INCLUDE_DIR`）；库指向 `libSDL2x.so`（`XSYS4_SDL2_LIBRARY`，`IMPORTED_SONAME libSDL2x.so`）；由 `SDL_version.h` 解析版本 |
| `FindFreetype.cmake` | `Freetype::Freetype`（IMPORTED STATIC） | 仅在 staging 内查找 `libfreetype.a` 与 `ft2build.h` / `freetype2` 头 |
| `FindSndFile.cmake` | `SndFile::sndfile`（IMPORTED STATIC） | 静态 `libsndfile.a`；`INTERFACE_LINK_LIBRARIES` 按序带上 `vorbisenc vorbis ogg` |

libsys4 自身的 `find_package(libdeflate / libjpeg-turbo / WebP / PNG / FLEX / BISON)` 与 `ZLIB` 不经本目录模块：
前四者走各自 CMake config 包（PNG 走 CMake 内置 FindPNG），ZLIB 来自 NDK sysroot。

---

## 5. 构建参数与偏差说明

按方案 §5.4 逐条落实；以下为实施中**新增或与方案字面不同**的点（均已在脚本内固定）：

1. **顶层 `-S`**：xsystem4 fork 检出（不使用 wrapper）。`--src` 模式下脚本先 `git clone --no-hardlinks` 本地 checkout 到
   `<build-dir>/src/xsystem4` 再检出目标 commit（`--ref`，缺省固定基线），**不修改源仓库**（也不在源仓库内放置 libsys4）。
   克隆保留 `.git`，因此上游 `git rev-parse --short HEAD` 得到检出 commit 的短哈希（本次为 `46f7c25`，`strings` 实测：`xsystem4 46f7c25`）。
2. **工具链参数**：`CMAKE_FIND_ROOT_PATH=<staging>` 并把 `CMAKE_FIND_ROOT_PATH_MODE_{LIBRARY,INCLUDE,PACKAGE}=ONLY`
   （依赖只从 staging 与 NDK sysroot 解析，避免误用宿主 `/usr`、Homebrew 头库）；公共参数见脚本 `COMMON_CMAKE_ARGS`。
3. **`CMAKE_STAGING_PREFIX` = `CMAKE_INSTALL_PREFIX` = staging**：实测 install 落点为 `staging/lib`、`staging/include`，无路径叠加。
4. **`-ffile-prefix-map=<build-dir>=/xsys4-build`**（`CMAKE_C_FLAGS`；libffi 的 `CFLAGS` 同）：把编译期 `__FILE__` 路径规范化，使产物不依赖构建目录位置。
5. **`-Wl,--build-id=none`（xsystem4 链接，方案外新增）**：见 §10 复现性。lld 计算 `.note.gnu.build-id` 时哈希的是**未 strip** 的输出，
   其中 `STT_FILE` 符号携带绝对源文件路径（strip 后虽被删，但已进入哈希），导致不同构建目录的 build-id 不同（实测仅这 20 字节不同）。
   关闭 build-id 后两次构建逐字节一致。代价：产物无 build-id（对 ndk-stack / 符号化影响有限；如需可改为由脚本固定值，本次未做）。
6. **依赖构建参数补充**（方案未列出的工具项，均为关闭可执行程序以避免为 Android 目标编译工具/测试）：
   libjpeg-turbo `-DBUILD=xsys4 -DWITH_TOOLS=OFF -DWITH_TESTS=OFF -DWITH_JAVA=OFF -DWITH_TURBOJPEG=ON -DENABLE_SHARED=OFF -DENABLE_STATIC=ON`（`BUILD` 见第 12 条）；
   libpng `-DPNG_SHARED=OFF -DPNG_STATIC=ON -DPNG_TESTS=OFF -DPNG_TOOLS=OFF -DPNG_ARM_NEON=on`；
   libdeflate `-DLIBDEFLATE_BUILD_STATIC_LIB=ON`（其余按方案）；
   libsndfile 在方案参数基础上显式 `-DENABLE_MPEG=OFF -DENABLE_EXTERNAL_LIBS=ON`（`ENABLE_MPEG` 原默认 ON，但 mpg123/lame 未找到时会自动关闭，结果相同，显式化以保证可复现）；
   libogg / libvorbis `-DBUILD_SHARED_LIBS=OFF -DINSTALL_DOCS=OFF -DINSTALL_PKG_CONFIG_MODULE=OFF`。
7. **libffi**：按方案 §5.4-3 参数；`AR/RANLIB/STRIP/LD` 使用 NDK 绝对路径，`CFLAGS="-O2 -ffile-prefix-map=…"`。release 包自带 configure，未 autoreconf。
8. **cglm**：`FETCHCONTENT_FULLY_DISCONNECTED=ON` + `FETCHCONTENT_SOURCE_DIR_CGLM` 指向本地 0.9.2；`CGLM_SHARED=OFF CGLM_STATIC=ON`（两项都传）。
   cglm 0.9.2 的 `cmake_minimum_required` 为旧策略，CMake 报告 `CMP0069` 下 `INTERPROCEDURAL_OPTIMIZATION` 对 `cglm` **被忽略**（仅该静态库不做 LTO，不影响功能；已记录）。
9. **增量 stamp**：每个依赖在 `staging/.stamps/` 记录输入指纹（tar SHA + 全部参数 + CMake 版本），一致即跳过；`--clean` 清空 `build/`、`staging/`、`third_party/`（保留 downloads 与源码克隆）。
10. **门禁**：`build.ninja` 中必须含 `-DHAVE_LIBDEFLATE`（实测 36 处命中，libsys4 未静默回落 ZLIB）；构建树与 staging 中除 `libxsystem4.so` 外出现任何 `.so` 即报错（实测 0 个）。
11. **未打补丁**：上游 xsystem4 / libsys4 / libsndfile / 依赖源码均未修改，`patches/` 未创建。
12. **libjpeg-turbo 构建字符串固定（复现性修复）**：libjpeg-turbo 3.1.0 的 `BUILD` 缓存变量默认为 `string(TIMESTAMP "%Y%m%d")`（`CMakeLists.txt:82-83`），
    经 `src/jconfigint.h.in` 写入 `src/jcmaster.c:800` 的版本串 `libjpeg-turbo version 3.1.0 (build YYYYMMDD)`。
    **问题**：同一脚本隔天重建时 `libxsystem4.so` 的 SHA-256 随之变化（字符串由 `(build 20261017)` 变为 `(build 20261018)`，已查明为唯一文本差异），
    导致「两次 `--clean` 构建逐字节一致」只在同一天成立。**修法**：脚本 `build_cmake_dep jpeg` 固定 `-DBUILD=xsys4`（常量，取项目名，不含日期）。
    该参数改变 jpeg 的 stamp 指纹，已触发 jpeg 重建。版本串变为 `libjpeg-turbo version 3.1.0 (build xsys4)`。
    **排查其它时间戳/日期/主机名嵌入**：源码无 `__DATE__` / `__TIME__` / `__TIMESTAMP__`；CMake 中仅 jpeg 使用 `string(TIMESTAMP)`；
    xsystem4 / libsys4 中 `time()` / `localtime()` 均为运行期调用（截图文件名、文件 mtime 等），不写入产物；
    产物 `strings` 检索 `20[0-9]{6}`、`YYYY-MM-DD` / `DD-MM-YYYY`、月份名日期格式、`__TIME__` 形式的 `HH:MM:SS`、用户名/主机名/`/Users/`/`/Volumes/` 路径，仅命中上述 jpeg 串与源码内固定常量
    （`Xiph.Org libVorbis I 20200704`、libpng 版权年份等），无其它构建时间或主机信息；`/var/tmp` 来自 libffi `src/closures.c` 的临时目录表。

---

## 6. 构建命令

```bash
# 默认：clone fork + 依赖下载到 ${TMPDIR:-/tmp}/xsys4-build，产物入库 jniLibs / assets / licenses
bash engine/xsystem4/scripts/build-xsys4.sh

# 从零重建（清空 build/ staging/ third_party/）
bash engine/xsystem4/scripts/build-xsys4.sh --clean

# 指定引擎版本（--ref 接受 commit / 分支 / tag，在 --src 克隆内解析；2026-11-14 入库即用此命令）
bash engine/xsystem4/scripts/build-xsys4.sh --src /Volumes/HUAWEI-M2LITE/app/xsystem4 \
    --ref 46f7c253d63d7b513516f42af027419314cbf047

# 复用本地 checkout，全部输出到仓库外（审核 / 复现用）
bash engine/xsystem4/scripts/build-xsys4.sh --src /Volumes/HUAWEI-M2LITE/app/xsystem4 \
    --build-dir "$TMPDIR/xsys4-verify" --out "$TMPDIR/xsys4-verify/so" \
    --shaders-out "$TMPDIR/xsys4-verify/shaders" --licenses-out "$TMPDIR/xsys4-verify/licenses"
```

脚本参数：`--build-dir` `--out` `--shaders-out` `--licenses-out` `--src` `--ref` `--sdk` `--cmake` `--ndk` `--jobs` `--clean` `--help`。

---

## 7. 许可（`licenses/`）

随 `libxsystem4.so` 分发。文件由脚本从固定源码与固定 commit 复制或按文件头截取（不手写）。

| 文件 | 来源 | 许可 |
| --- | --- | --- |
| `xsystem4-COPYING-GPL-2.0.txt` | xsystem4 `COPYING` | GPL-2.0 |
| `libsys4-COPYING-GPL-2.0.txt` | libsys4 `COPYING` | GPL-2.0 |
| `libsndfile-COPYING-LGPL-2.1.txt` | libsndfile（xsystem4 分支）`COPYING` | LGPL-2.1 |
| `LGPL-2.0.txt` | gnu.org（SHA-256 校验） | LGPL-2.0（`mt19937int` 声明引用） |
| `cJSON-MIT.txt` | `src/cJSON.c` 文件头 | MIT |
| `pl_mpeg-MIT.txt` | `include/pl_mpeg.h` 许可段 | MIT |
| `queue-BSD-3-Clause.txt` | `include/queue.h` 文件头 | BSD-3-Clause（UC Regents） |
| `base64-BSD.txt` | `src/base64.c` 文件头 | BSD（Jouni Malinen 声明） |
| `sts_mixer-Unlicense.txt` | `include/sts_mixer.h` 末尾 unlicense 段 | 公有领域（Unlicense） |
| `kvec-MIT.txt` | libsys4 `src/kvec.h` 文件头（`include/system4/vector.h` 同声明） | MIT |
| `mt19937int-LGPL-notice.txt` | libsys4 `src/mt19937int.c` 文件头 | LGPL-2.0-or-later（`src/dungeon/mtrand43.c` 注明派生自此） |
| `libffi-LICENSE.txt` / `libogg-COPYING.txt` / `libvorbis-COPYING.txt` / `libjpeg-turbo-LICENSE.md` / `README.ijg` / `libwebp-COPYING.txt` / `libwebp-PATENTS.txt` / `libpng-LICENSE.txt` / `libdeflate-COPYING.txt` / `freetype-FTL.txt` / `freetype-GPLv2.txt` / `SDL2-LICENSE.txt` / `cglm-LICENSE.txt` | 各依赖 tar 包内的许可文件（`README.ijg` 为 libjpeg-turbo 的 IJG 条款） | 各自许可 |

**无许可头、需作者确认的文件**（记录，不阻塞构建）：

- `libsys4/include/system4/s2utbl.h`：Shift-JIS / CP932 ↔ Unicode 映射表，无许可声明；随 libsys4（GPL-2.0-or-later）分发。
- `libsys4/src/ini_parser.y`：无文件头；随 libsys4 分发。
- `src/debug-ffi.c`：由 `src/debug-ffi.stub`（GPL-2.0-or-later 文件头）经 chibi-ffi 生成；仅在调试器路径引用，不在默认编译列表中。
- `src/dungeon/mtrand43.c`：无独立许可头，注释声明派生自 libsys4 `mt19937int.c`（LGPL-2.0-or-later 声明，见上）。

---

## 8. 产物实测（`libxsystem4.so`，arm64-v8a，`--strip-unneeded`）

| 项 | 实测值 |
| --- | --- |
| 体积 | **5 719 640 B（≈5.45 MiB）** |
| SHA-256 | **`7489a041b35c234aa41a24a4b1100d19a70eb479ac21a999b60ff7997d7686c3`**（2026-11-14，xsystem4 `46f7c25`；旧值 `56a4063e…` 为 `6a94a0c` 基线，见 §10） |
| 构建 | Release、LTO（`CMAKE_INTERPROCEDURAL_OPTIMIZATION=ON`），`-DANDROID_PLATFORM=android-26`，`c++_static` |
| ELF | `ELF 64-bit LSB shared object, ARM aarch64, dynamically linked, stripped` |

`llvm-readelf -d`（NEEDED / SONAME）：

```
0x0000000000000001 (NEEDED)       Shared library: [libm.so]
0x0000000000000001 (NEEDED)       Shared library: [libz.so]
0x0000000000000001 (NEEDED)       Shared library: [libSDL2x.so]
0x0000000000000001 (NEEDED)       Shared library: [libGLESv3.so]
0x0000000000000001 (NEEDED)       Shared library: [libdl.so]
0x0000000000000001 (NEEDED)       Shared library: [liblog.so]
0x0000000000000001 (NEEDED)       Shared library: [libc.so]
0x000000000000000e (SONAME)       Library soname: [libxsystem4.so]
```

- NEEDED 集合 = `{libm, libz, libSDL2x, libGLESv3, libdl, liblog, libc}`，**不含** `libSDL2.so` / `libcglm.so`（门禁通过）。
- `llvm-nm -D | grep -w SDL_main` → `000000000025f1bc T SDL_main`（门禁通过）。
- `llvm-readelf -l` 的 LOAD 段（3 个）：`p_align = 0x4000`（16 KB 对齐，门禁通过）：

```
LOAD  0x000000 0x0000000000000000 0x0000000000000000 0x52b920 0x52b920 R E 0x4000
LOAD  0x52b920 0x000000000052f920 0x000000000052f920 0x024dc0 0x0256e0 RW  0x4000
LOAD  0x5506e0 0x00000000005586e0 0x00000000005586e0 0x0236c0 0x053734 RW  0x4000
```

- 版本串：`strings` 含 `xsystem4 46f7c25`（`VCS_TAG` 正确）；`strings` 含 `libjpeg-turbo version 3.1.0 (build xsys4)`（第 12 条）。
- 2026-11-14 重编（`46f7c25`）：NEEDED 集合与 SONAME 不变（`SDL_main` 为 `T`，`LOAD p_align = 0x4000` ×3，计划外 `.so` 0 个）；
  体积由 5 671 944 B 增至 5 719 640 B（+47 696 B），增量来自 `6a94a0c..46f7c25` 的 parts activity、音频元数据与 HLL 导出。
- 2026-10-18 重建复核：NEEDED 集合 = `{libm, libz, libSDL2x, libGLESv3, libdl, liblog, libc}`（与上文一致，`SDL_main` 为 `T`，`LOAD p_align = 0x4000` ×3）；
  `libSDL2x.so` SHA-256 仍为 `cee53fbc5fd41a895658ce98401cd6aa033ad6039b426d4401f9ac1b3caba0c1`；体积 5 671 944 B 不变。
- 构建树 / staging 中计划外 `.so`：0 个（门禁通过）。

**与方案估算的偏差**：方案 §2.1 估算 2.5–3.5 MB（未实测）。实测 5.72 MB（strip 后）。方案 §3.1 提及壳产物解压 7.2 MB 含符号表。
体积主要来自静态链入的 libwebp（含 demux/mux 与 sharpyuv）、libjpeg-turbo（含 turbojpeg）、libsndfile+vorbis、freetype 与 xsystem4 的大量 HLL 模块。**未做按模块裁剪**，留待编排者决策（见 §11）。

编译告警（上游 / 依赖代码，未修补）：`src/java_raw_api.c`（libffi 弃用 API）、`src/dlmalloc.c`（未用变量）、`src/hll/DrawSnow.c:60`（`RAND_MAX` 转 float）、`libsys4/src/ald.c:211`（`optimize` 属性）。

---

## 9. 着色器（`engine/src/main/assets/shaders/`）

- 来源：xsystem4 `shaders/`（固定 commit），**53 个文件**，原样复制（`cp -R`），保留 `effects/` 子目录（脚本门禁：数量 = 53）。
- 实测 GLSL 总字节数：79 320 B（方案 §2.1 估算 ≈79 KB）。
- 文件 SHA-256 清单（`shasum -a 256`，相对 `shaders/`）：

<details>
<summary>53 个文件 SHA-256</summary>

```
342ed33b9a0269533d00ef0851dd0bfc62f55f6b779bc903c5a8edd66c951eb3  amap_saturate.f.glsl
205cf6ef1607a917a315eab7789514472a6fcd49c60798e9ebc48fe03489b805  blend_amap_alpha_bright.f.glsl
2dddee5848d3dda6d59ff0621fe8102244650108a53b1fce9e3ce504d0ab6028  blend_amap_color.f.glsl
48db3d0c06a25a059b8e3316ef5f830bf8f10ecbe22d13814b3dec2f70270550  blend_rmap_color.f.glsl
9582aad2aa0b898bb93be2be753cdd323fd093dac5e49aa5b1557f5df8e0eaae  blend_use_amap_color.f.glsl
81eb53ca9e18ffb9e225ee733dab9cd5a0945251551465af3f1f20feeeb0020b  copy.f.glsl
b17bd3c70bd0fe6b5747fcde716a78c02ec59269598c980dcecc0db21b381386  copy_alpha_key.f.glsl
a2f7a98177f193cf1c1c72a6ccb862ce43b20cb598bc302552db1f3dc7daf632  copy_color_reverse.f.glsl
b8879092f02729eba96a8b8a4d529e2e5d43530100b029cabee95e0ddaa56d48  copy_grayscale.f.glsl
a03005a8a9e0cbeca171a861a00a13bcc34a5f40b2b0121319ad0b51a90918ac  copy_key.f.glsl
695107de96ca9875d2523481c5fc4c68eff38678289a1592a6a88fd205dcc784  copy_use_amap_border.f.glsl
d32770cd5db4144c13c867f40ff140bdaf49461f694301ea7050468dee3d0deb  copy_use_amap_under.f.glsl
07b1e18eb64770fa3f9bf830411cfb899e51d9d00ccc0817489de54664816f6f  dilate.f.glsl
84d4b392b8f58ea1b7fdb781642bec9e20d1243008c1004555d6e1f9e9be116e  dungeon.f.glsl
ad8e4287eb89da41f8ced06a9fa571fd8c3a481e6a1fe154138257eeb3afdde8  dungeon.v.glsl
5711b40360a5e5acb0565c49115b634d7f9b1964ac56449a024cba283874b8c2  dungeon_raster.f.glsl
f36502cb321ffad74951d799b7c9b2b3b19327775bfff6a4433d6fe338bba326  dungeon_skybox.f.glsl
1a10a28ef5a12b2eb0bf5a55ea4381980233f015a205f59b529a076e4721fca6  dungeon_skybox.v.glsl
f54477b82a2bc8e2fae9da0ae4e0c14496a8e090ab9d49a722d6a5aa14506e8f  effects/blind_down.f.glsl
474c0217f8833a07e4b1fed5c65638c688fd3cf328180610af14a20538186ec2  effects/blind_lr.f.glsl
3907ca76a4db2f35405de5cc90f5cab7d670c6048b607455a21a237aebc9f850  effects/blur_crossfade.f.glsl
022563b6454291be97ea2fa18f98cad72ffba819df717aecd1442c3a5d66deb4  effects/blur_fadeout.f.glsl
7895a0fe5eea0abba134267d451135e227b82ff93a79fd8650182ebf9a658192  effects/crossfade.f.glsl
ada2e96aab62618cb5fb3573d7d7b5553543b5947ef1597626669a447b806f71  effects/crossfade_down_up.f.glsl
963cb3a13e9f8c7b6c97c2ad03d6d69dbf9b72a09c1c7d6f22d338d1b035b6c3  effects/crossfade_lr.f.glsl
d79884ea0173beff3780b650700d9d10f4e39fea5328ff3e412451474aaf7273  effects/crossfade_up_down.f.glsl
d1489a3fdbd3fba5d3da6fee5f181c17786984dae3144212d8536dcb610d2b95  effects/linear_blur.f.glsl
bbc846f6d1bd2d2968a4630c1c4ca5cc7b453dbdc8116257c7338dfaf2af16c5  effects/mosaic.f.glsl
eb879eb98a70a1d31c7231633cc86210778d6b1e2c47623f0dfbce4956aa265f  effects/noise_crossfade.f.glsl
cc824b184ebbd917af94afd01fc84ef736a1c651f173704eb4b829288bf2dadd  effects/sepia_noise_crossfade.f.glsl
ea375fde920425695adb943c06ebb02c4c914925aa661db5f4ccad6d2ef3363b  effects/turn_page.f.glsl
43fe38e1b0a9bb651c1e7de694c1759bc323eff71f61ab83e8cd31786ae10d2b  effects/vwave_crossfade.f.glsl
ac019e97ad8f16d419032c6f400634bc2013672f44abf84f6dd43bd944266307  effects/vwave_scroll_crossfade.f.glsl
8da09eb81119a9728d17ddb229a9462fdb68d6a8046dc4e631cad059cf82f50c  effects/zigzag_crossfade.f.glsl
1e7aa591b5ed44ff5f759dd427d8f21804676143eb48537a2b9be37725450618  fill.f.glsl
6ef4e7932301dd476a05f4ebcbd7e019d79dd151cc5f850a126085a6909f2d45  fill_amap_gradation_ud.f.glsl
b734cf3137fea8a1da5492e89efc1d6cacde57affafd9e1ea1bf49bf92a1145e  fill_amap_over_border.f.glsl
6aa01ef019172bd0003aa3b4e907b29608e0e487f3393133bda48848b34c39bd  fill_amap_under_border.f.glsl
00a1d9a3d63edbf7a04c850dbf44d52e7a2ce432801c89e6c8f4ec477a9fbde0  fill_angle.f.glsl
b402c615320968dc9733ce86266365546416428066ac6d31e79114cd6ee68206  hitbox.f.glsl
79c61862188bdfe84a807e1dcc678abe47a777dd933ec488b5cd2501e6946858  hitbox_noblend.f.glsl
bec59221a2a086aab0fb559b2d07c2438dfff286fcb3a036f24664f2225ae324  movie.f.glsl
b2f33d96335dac7bdf9487dd68587bd8c73669755e843f51a53fb50ee20d9331  parts.f.glsl
37888fda0129631fd00a03269a4ee4fb6748188113864d6c25a5ca1732207e77  parts.v.glsl
21a6e16000bb517a0d427e235935e3762939e7180db4cef8cf728728e80e5eca  reign.f.glsl
520c3d52bf2a85353790b5e9b5620702950410f51670de60649b3e7c6a5eb573  reign.v.glsl
7df6a581164727e3e32d4fe1d8ec4175a8adc5afa0bc6934f091303459484d30  reign_outline.f.glsl
498c73fdca59cf7598526643e10413b0c7a2462304294c1a5dd43d0ef2faf4d0  reign_outline.v.glsl
a6b11b8c1ec7b6e6478186eb741d7e4b2bf9f224e30e1727124b9c685124f158  reign_shadow.f.glsl
92f26078cca67887f5bd38c851377da06fbf69e35f6baaae7864f951de1447d9  reign_shadow.v.glsl
81eb53ca9e18ffb9e225ee733dab9cd5a0945251551465af3f1f20feeeb0020b  render.f.glsl
7eab56dffaf7bbb385eeb574024aa9178307ff3f77612b132b2ac9ebc454d0f8  render.v.glsl
95262b76d3fbe32b9ac2ea384c5377f4ed4ca3b55cacd25fcc1713a8d729781d  sprite.f.glsl
```

</details>

---

## 10. 复现性记录

同一基线各做两轮 `--clean` 全量构建（从零下载校验、从零编译全部依赖），**构建目录不同**：

| 轮次 | 构建目录 | 输出 | SHA-256 |
| --- | --- | --- | --- |
| A（2026-10-18 00:13，`6a94a0c`） | `${TMPDIR}/xsys4-build` | 入库（`engine/src/main/jniLibs/arm64-v8a/`） | `56a4063eedc9cbd3f72d1bc06e937e0239ecdb44fb61bc68348ee791cca7c6eb` |
| B（2026-10-18 00:16，`6a94a0c`） | `${TMPDIR}/xsys4-verify-B/build` | 仓库外 `xsys4-verify-B/so/` | `56a4063eedc9cbd3f72d1bc06e937e0239ecdb44fb61bc68348ee791cca7c6eb` |
| C（2026-11-14 22:26，`46f7c25`，入库） | `${TMPDIR}/xsys4-build` | 入库（`engine/src/main/jniLibs/arm64-v8a/`） | `7489a041b35c234aa41a24a4b1100d19a70eb479ac21a999b60ff7997d7686c3` |
| D（2026-11-14 22:42，`46f7c25`，独立重建） | `${TMPDIR}/xsys4-verify/build` | 仓库外 `xsys4-verify/so/` | `7489a041b35c234aa41a24a4b1100d19a70eb479ac21a999b60ff7997d7686c3` |

- A、B 与对应入库产物三者 `cmp` 逐字节一致；C、D 同样 `cmp` 逐字节一致（体积均 5 719 640 B），shaders 目录 `diff -rq` 一致。
- A/B 与 C/D 之间 SHA-256 不同是**源码基线不同**（`6a94a0c` → `46f7c25`，26 个上游提交），不是构建环境漂移；同基线两轮（C/D，不同构建目录）仍逐字节一致。
- **C 轮修掉的一处脚本缺陷**：`licenses/` 的 `README.ijg`（libjpeg-turbo 的 IJG 条款，此前由文档提交手动入库）不在脚本的复制清单里，`rm -rf` 重建目录时会被删除；脚本已补 `lic_copy …/README.ijg README.ijg`，重建后与入库版本一致。
- **B 轮的限制**：脚本在收集许可文本的最后一步从 `www.gnu.org` 下载 `LGPL-2.0.txt` 时遇到网络 SSL 错误而退出（`.so` 与 shaders 此前已生成并复制到 `--out`），所以 B 轮的 licenses 输出目录缺这一个文件；入库目录里的 `LGPL-2.0.txt` 是此前下载并校验过的。该下载步骤依赖外网、遇网络抖动会让整次构建以非零退出，后续可考虑优先使用 `engine/xsystem4/licenses/` 内已有且 SHA 匹配的文本。
- 旧值对照（不再有效）：2026-10-17 构建（`BUILD` 未固定）为 `bc311662…`；同一未修复脚本于 2026-10-18 重建为 `5408f5a9…`，二者仅差 libjpeg-turbo 版本串里的构建日期。
- **证明力说明**：A、B 两轮同在 2026-10-18，不同构建目录；"与日期无关"的证据是产物字符串已是 `libjpeg-turbo version 3.1.0 (build xsys4)`、且不含其它构建日期（唯一类日期串 `libVorbis I 20200704` 是 libvorbis 上游固定发布号），而不是真的改系统日期重建。
- **过程记录**（为追溯保留）：未加 `--build-id=none` 时，两次构建仅 `.note.gnu.build-id` 的 20 字节不同（体积 5 672 024 B，SHA-256 `6978f503…`）。
  原因见 §5-5；加入参数后两次完全一致。
- 同一构建目录内的非 clean 重建也复现出同一 SHA-256（`6978f503…` 版本，build-id 形态下）。
- **隔日不一致及修复（2026-10-18）**：同一脚本于 2026-10-17 与 2026-10-18 重建，`libxsystem4.so` SHA-256 由 `bc311662…` 变为 `56a4063e…`，
  `strings` 唯一文本差异为 libjpeg-turbo 版本串的构建日期（`(build 20261017)` → `(build 20261018)`）。原因与修法见 §5 第 12 条：脚本固定 `-DBUILD=xsys4` 后，
  构建不再嵌入日期。新值的比对结果见上表。
- 验证方法：`cmp` 与 `shasum -a 256`；`--out` / `--shaders-out` / `--licenses-out` 全部指向仓库外，不覆盖入库产物。
  系统日期无法在 macOS 上安全改写，因此"与日期无关"的证明采用：源码与 CMake 中无日期宏 / 日期源（见 §5 第 12 条排查），且产物字符串不含 build 日期。

---

## 11. 注意事项

1. **体积**：`libxsystem4.so` 5.72 MB（strip 后；gzip -9 约 2.17 MiB，`useLegacyPackaging=true` 下 APK 增量约 2.3 MB），高于最初估算（2.5–3.5 MB）。
   如需压缩，可考虑：libwebp 只保留 decoder（`libwebpdecoder`，去 demux/mux）、确认 libsys4 对 libjpeg-turbo 的 `turbojpeg` 依赖是否可去。当前未裁剪，以保持与 §5 构建参数一致。
2. **`--build-id=none`**：为跨构建目录逐字节可复现而加（§5-5）。若要求保留 build-id，需接受其随构建目录路径变化。
3. **LTO**：xsystem4 与 libsys4 为 LTO；cglm（旧 cmake 策略）未 LTO，见 §5-8。
4. **libSDL2x.so**：由 System35 构建与入库，本目录不构建它，只校验 SHA-256（`cee53fbc…`，见 `engine/xsystem35/BUILD.md`）。该库同时被 System35 与 System4 依赖，
   升级 SDL2 或改动导出符号必须回归两者（`engine/xsystem35/BUILD.md` §7 已有反向声明）。运行期 SDL 版本差异（xsystem4 上游壳用 2.30.9，本项目 2.32.10）靠真机验收覆盖。
5. **外网依赖**：脚本在收集许可文本时会从 `www.gnu.org` 下载 `LGPL-2.0.txt`，网络异常会使整次构建以非零退出（`.so` 与 shaders 此时已生成）；`licenses/` 目录里已入库一份校验过的副本。
6. `patches/` 目录未创建：当前基线无需对 xsystem4 / libsys4 源码打补丁，所有适配都通过构建参数与自研 find 模块完成。
