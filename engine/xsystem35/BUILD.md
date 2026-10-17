# xsystem35（System 3.x）内置引擎 —— 预构建与构建剧本说明

本目录是 TyranorNext 内置 **System35（xsystem35-sdl2）** 引擎的预构建剧本与溯源材料
（M0 阶段产物）。方案见 `docs/System35-xsystem35-引擎接入方案.md`。

- 引擎运行库（`libxsystem35.so` 等）**预构建入库** `engine/src/main/jniLibs/arm64-v8a/`，
  与 `librfvp.so` / `libsiglus.so` 同模式；Gradle 不参与原生构建。
- **xsystem35 源码与依赖源码不随主仓分发**：源码来源为 fork
  `Weiss-UltimateSavior/xsystem35-sdl2`（固定 commit），依赖由 `scripts/build-xsys35.sh`
  按 SHA1 下载到**仓库外**构建目录（默认 `${TMPDIR:-/tmp}/xsys35-build`，可用 `--build-dir` 覆盖）。
- 主仓只保留：预构建 `.so`、构建剧本（`android-host/`）、`patches/`、app32 生成脚本（`scripts/`）、
  字体与编解码器许可文本（`licenses/`）、内置字体资产（`engine/src/main/assets/`）与本文档。

主仓目录布局：

```
engine/xsystem35/
├── android-host/CMakeLists.txt  ← 本项目 Android 构建剧本（等价上游 android/app/jni/CMakeLists.txt）
├── patches/                     ← 对 SDL2 依赖的最小 patch（构建时对干净源顺序重放）
├── scripts/build-xsys35.sh      ← 一键可复现构建（依赖下载 + 源码 + patch + 构建 + 入库）
├── scripts/sync-app32-java.sh   ← 生成 org.libsdl.app32 Java 层
├── licenses/                    ← 随二进制分发的字体/编解码器许可文本
└── BUILD.md                     ← 本文档
```

---

## 1. 上游基线

| 项 | 值 |
| --- | --- |
| 本地构建来源（fork） | <https://github.com/Weiss-UltimateSavior/xsystem35-sdl2> |
| 固定 commit | `18af9be92dbe811b783c81393ca2307ccc50efd5`（2026-10-10，"Route effect and dDemo rendering through gfx_updateScreen()"） |
| 上游仓库 | <https://github.com/kichikuou/xsystem35-sdl2>（fork 当前与该 commit 内容一致，不含源码改动） |
| 引擎版本 | `XSYSTEM35_VERSION = "2.20.0"`（根 `CMakeLists.txt`） |
| 许可 | GPL-2.0-or-later（`COPYING`，随 fork 提供） |

> **修改声明**：App 侧对上游的所有改动都落在 `patches/`（SDL2 JNI 前缀 app32 化）与构建参数
> （产物重命名 / 依赖裁剪 / libwebp 静态化，见 §5.4），fork 本身不承载修改；完整源码与修改声明
> 见 fork 仓库对应 commit。

---

## 2. 源码与依赖来源（均不随主仓分发）

`scripts/build-xsys35.sh` 全自动完成到仓库外构建目录（默认 `${TMPDIR:-/tmp}/xsys35-build`）：

### 2.1 源码获取

- `--src <path>`：使用本地 xsystem35 checkout（审核/复现时复用已有仓库）；
- 缺省：`git clone https://github.com/Weiss-UltimateSavior/xsystem35-sdl2`
  到 `<build-dir>/xsystem35-src` 并 `git checkout --detach 18af9be…`；
  目录已存在时改为 `git fetch origin` + checkout 同一固定 commit。

### 2.2 依赖下载（SHA1 校验，失败即退出；已存在且校验通过则跳过）

全部来自官方发布 tar 包，tar 包缓存在 `<build-dir>/downloads/`，解压为
`<build-dir>/third_party/<目录>`：

| 目录 | 上游 tar 包 | URL | SHA1（校验通过） |
| --- | --- | --- | --- |
| `SDL2/` | `SDL2-2.32.10.tar.gz` | <https://github.com/libsdl-org/SDL/releases/download/release-2.32.10/SDL2-2.32.10.tar.gz> | `ce98fa93e31836a751feca374ab28a0770b63c16` |
| `freetype/` | `freetype-2.14.3.tar.gz` | <https://downloads.sourceforge.net/project/freetype/freetype2/2.14.3/freetype-2.14.3.tar.gz>（备选 <https://download.savannah.gnu.org/releases/freetype/freetype-2.14.3.tar.gz>） | `a911f65c6355ddcf7aaba46e250dbc00f67e1678` |
| `SDL2_mixer/` | `SDL2_mixer-2.8.0.tar.gz` | <https://github.com/libsdl-org/SDL_mixer/releases/download/release-2.8.0/SDL2_mixer-2.8.0.tar.gz> | `a58c69f9d00e44833b9e00e1adb58d85759ca499` |
| `libwebp/` | `libwebp-1.5.0.tar.gz` | <https://storage.googleapis.com/downloads.webmproject.org/releases/webp/libwebp-1.5.0.tar.gz> | `b21aa842136dc59a72a38776a5aa73f4d0b00ac5` |

依赖就位后整个构建过程不再联网（`android-host/CMakeLists.txt` 不使用 FetchContent 下载，
libwebp 由 `FETCHCONTENT_SOURCE_DIR_LIBWEBP` 指向本地目录，见 §5.4）。

### 2.3 patch 应用

构建脚本对 `<build-dir>/third_party/SDL2` 按文件名顺序应用 `patches/*.patch`：
补丁只对「从缓存 tar 包重新解压出的 pristine 源」重放（先 `git apply --check`，无 git 时
`patch --dry-run`），应用后做落点内容复核并写 marker（patch 内容 + SDL2 tar SHA1 的哈希）；
重复运行时 marker 与内容复核均通过才跳过。

> 不用 `patch` 的「已应用」探测：macOS 自带 Apple patch 在批处理模式下会把不适用的方向
> 自动翻转、`--dry-run` 也返回成功（实测），无法可靠判断源是否已打补丁；`git apply --check`
> 无此问题（本流程实测会正确拒绝已应用/未应用的错误方向）。

### 2.4 工具链

| 工具 | 版本/路径（脚本默认，可用参数覆盖） |
| --- | --- |
| NDK | `~/Library/Android/sdk/ndk/28.0.13004108`（r28；`--ndk`） |
| CMake | `~/Library/Android/sdk/cmake/3.31.1/bin/cmake`（SDK 自带；`--cmake`；**不要**用 homebrew cmake 4.x，旧工程策略会失败） |
| Ninja | 同上目录（SDK 自带，脚本自动加入 PATH） |
| SDK 根 | `$ANDROID_SDK_ROOT` / `$ANDROID_HOME` / `~/Library/Android/sdk`（`--sdk`） |

---

## 3. patch 清单（`patches/`）

构建时这些 patch 由 `build-xsys35.sh` 应用到仓库外的干净 SDL2 源；本目录文件即唯一定义，
不会在源码树里「已应用后再分发」。

### 3.1 `0001-sdl2-jni-prefix-app32.patch` —— SDL2 JNI 类前缀 app32 化

改 `third_party/SDL2/src/core/android/SDL_android.c`：

1. `#define SDL_JAVA_PREFIX org_libsdl_app` → `org_libsdl_app32`（JNI 符号名统一由该宏派生）；
2. `JNI_OnLoad` 中 4 处硬编码 `"org/libsdl/app/…"` register_methods 字符串 →
   `"org/libsdl/app32/…"`（SDLActivity / SDLInputConnection / SDLAudioManager / SDLControllerManager）。

原因：APK 内 ONS 插件使用 SDL2 **2.26.3** 的 `org.libsdl.app` Java 层，其 `onCreate` 版本硬校验
与 2.32.10 native 必然冲突；本引擎的 Java 层整包改名为 `org.libsdl.app32`（见 §4），native 侧前缀
必须同步改，否则 `RegisterNatives` 找不到类。

### 3.2 `0002-sdl2-hidapi-jni-prefix-app32.patch` —— HIDAPI JNI 前缀同步

改 `third_party/SDL2/src/hidapi/android/hid.cpp` 的 `SDL_JAVA_PREFIX`（同上改 `org_libsdl_app32`）。

**为什么必须改**（方案 §3.4 的「宏 + 4 处字符串」清单之外，但属同一改名面）：
`src/hidapi/android/hid.cpp` 用**符号名**（非 RegisterNatives）导出
`Java_org_libsdl_app_HIDDeviceManager_*`；而 `SDLActivity.onCreate` 无条件调用
`HIDDeviceManager.acquire(this)`，其构造函数第一行即 `HIDDeviceRegisterCallback()`（native）。
若前缀不改，app32 Java 层启动即 `UnsatisfiedLinkError` 崩溃。SDL2 2.32.10 的完整改名面为
**2 个宏 + 4 处字符串**（`grep -rn 'org_libsdl_app' <build-dir>/third_party/SDL2/src/` 复核穷尽，
仅剩 `src/video/android/SDL_androidevents.c` 一处**注释**提及旧名，无功能影响）。

### 3.3 重新应用（手工）

对官方 pristine 源（`<build-dir>` 为构建目录，默认 `${TMPDIR:-/tmp}/xsys35-build`）：

```bash
cd <build-dir>/third_party/SDL2
patch -p1 < <repo>/engine/xsystem35/patches/0001-sdl2-jni-prefix-app32.patch
patch -p1 < <repo>/engine/xsystem35/patches/0002-sdl2-hidapi-jni-prefix-app32.patch
```

或直接重跑 `scripts/build-xsys35.sh`（自动按序重放；已应用即跳过）。

### 3.4 不落 patch 的改名（在 `android-host/CMakeLists.txt` 里做）

- `libSDL2.so` → **`libSDL2x.so`**：`set_target_properties(SDL2 PROPERTIES OUTPUT_NAME SDL2x)`。
  实测 SONAME = `libSDL2x.so`（`llvm-readelf -d`），无需额外 `-Wl,-soname` 修正。
- `libSDL2_mixer.so` → **`libSDL2_mixer_xs35.so`**：同法改 `OUTPUT_NAME`，实测 SONAME 一致。

原因：APK 内 ONS 插件自带 SONAME `libSDL2.so`（2.26.3），同名会导致动态链接解析歧义。

---

## 4. app32 Java 层（`engine/src/main/java/org/libsdl/app32/`）

由脚本从上游 SDL2 源生成，**不要手工编辑**：

```bash
engine/xsystem35/scripts/sync-app32-java.sh
```

- 源解析（按优先级）：
  1. `--sdl2-src <path>` / 环境变量 `SDL2_SRC`（本地 SDL2 源，含
     `android-project/app/src/main/java/org/libsdl/app/*.java`；也接受直接指向该 java 目录）；
  2. `engine/xsystem35/third_party/SDL2`（若本地存在）；
  3. 自动下载 `SDL2-2.32.10.tar.gz` 到临时目录（SHA1 `ce98fa93e31836a751feca374ab28a0770b63c16`
     校验通过后使用，用完清理）。
- 源文件为 9 个 `*.java`；
- 转换：`package org.libsdl.app;` → `org.libsdl.app32;`，全文 `org.libsdl.app` → `org.libsdl.app32`；
- 输出：`engine/src/main/java/org/libsdl/app32/*.java`（可重复执行，脚本先清空输出目录再生成）；
- 复核：`grep -rn "org\.libsdl\.app\b" engine/src/main/java/org/libsdl/app32/` → 0 行；
- 同步校验：`bash engine/xsystem35/scripts/sync-app32-java.sh --check` → 「check passed」
  （生成到临时目录逐文件比对，不写仓库文件；有差异即 exit 1；重构后实测经由「下载 fallback」路径通过）。

---

## 5. 构建（预构建入库）

### 5.1 一键构建（推荐）

```bash
# 默认：clone fork + 下载依赖到 ${TMPDIR:-/tmp}/xsys35-build，产物复制到 jniLibs
bash engine/xsystem35/scripts/build-xsys35.sh

# 复用本地 checkout、只构建到仓库外目录（审核/复现用，不动 jniLibs）
bash engine/xsystem35/scripts/build-xsys35.sh \
    --src /path/to/xsystem35-sdl2 --out "$TMPDIR/xsys35-verify-out"

# 其它参数：--build-dir / --sdk / --cmake / --ndk / --jobs / --clean（--help 查看全部）
```

脚本流程：依赖下载（SHA1）→ 对干净 SDL2 应用 patch → 源码（clone/checkout 固定 commit）→
cmake 配置 → 构建 → `llvm-strip --strip-unneeded` → 复制 3 个产物到 `--out`
（默认 `engine/src/main/jniLibs/arm64-v8a`）→ 打印每个产物 SONAME/NEEDED 与 SHA256。
构建产物（源码、依赖、构建树与中间件）全部在仓库外目录。

### 5.2 等价手工命令

先按 §2 自行准备依赖与源码（下载解压 + 应用 patch + clone/checkout fork），然后：

```bash
NDK=~/Library/Android/sdk/ndk/28.0.13004108
CM=~/Library/Android/sdk/cmake/3.31.1/bin/cmake
BUILD=/tmp/xsys35-build/build               # 任意仓库外目录
SRC=/path/to/xsystem35-sdl2                 # fork checkout（固定 commit）
DEPS=/path/to/third_party                   # 含 SDL2/freetype/SDL2_mixer/libwebp 的目录

PATH=~/Library/Android/sdk/cmake/3.31.1/bin:$PATH $CM -S engine/xsystem35/android-host -B $BUILD -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release \
  -DXSYS35_SRC=$SRC -DXSYS35_THIRD_PARTY_DIR=$DEPS

PATH=~/Library/Android/sdk/cmake/3.31.1/bin:$PATH $CM --build $BUILD -j

# strip
$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-strip --strip-unneeded \
  $BUILD/SDL2/libSDL2x.so \
  $BUILD/SDL2_mixer/libSDL2_mixer_xs35.so \
  $BUILD/xsystem35/src/libxsystem35.so

# 入库
cp $BUILD/SDL2/libSDL2x.so \
   $BUILD/SDL2_mixer/libSDL2_mixer_xs35.so \
   $BUILD/xsystem35/src/libxsystem35.so \
   engine/src/main/jniLibs/arm64-v8a/
```

### 5.3 构建剧本参数

| 变量 | 必填 | 说明 |
| --- | --- | --- |
| `XSYS35_SRC` | 是 | xsystem35 源码目录（fork checkout）。未提供时 `FATAL_ERROR`，提示用 `scripts/build-xsys35.sh` |
| `XSYS35_THIRD_PARTY_DIR` | 否 | 依赖源码根目录（含 `SDL2/freetype/SDL2_mixer/libwebp`）。默认 `${CMAKE_CURRENT_SOURCE_DIR}/../third_party`（存在才可用）；缺失报 `FATAL_ERROR` 并提示脚本 |

### 5.4 `android-host/CMakeLists.txt` 与上游剧本的差异

| # | 差异 | 说明 |
| --- | --- | --- |
| 1 | 依赖改为 `XSYS35_THIRD_PARTY_DIR`（仓库外构建目录） | `add_subdirectory(${XSYS35_THIRD_PARTY_DIR}/SDL2|freetype|SDL2_mixer ...)`，替代上游 FetchContent 下载；源码由 `XSYS35_SRC` 指向 fork checkout |
| 2 | libwebp 走本地 | `FETCHCONTENT_SOURCE_DIR_LIBWEBP=${XSYS35_THIRD_PARTY_DIR}/libwebp`，让 xsystem35 根 `CMakeLists.txt` 的 `fetch_webp()` 消费本地目录；不联网 |
| 3 | 产物重命名 | SDL2 → `libSDL2x.so`；SDL2_mixer → `libSDL2_mixer_xs35.so` |
| 4 | freetype 裁剪 | 与上游逐条一致：`FT_DISABLE_ZLIB/BZIP2/PNG/HARFBUZZ/BROTLI = ON (CACHE BOOL "" FORCE)` |
| 5 | SDL2_mixer 裁剪 | 与上游一致：`SDL2MIXER_OPUS/FLAC/MOD/MIDI/WAVPACK/SAMPLES/INSTALL = OFF`；**新增 `SDL2MIXER_DEPS_SHARED=OFF`**（让 ogg/vorbis 等静态链入 mixer，避免额外 `.so`） |
| 6 | libwebp 静态化 | 在配置 xsystem35 本体前设 `set(BUILD_SHARED_LIBS OFF)`（目录作用域**普通变量**，不写 CMakeCache、不构成全局设置）+ `WEBP_LINK_STATIC=ON`。原因：`SDL2_mixer` 的 `option(BUILD_SHARED_LIBS … ON)` 会把 cache 变量置 ON，而 libwebp 的库 target 跟随 `BUILD_SHARED_LIBS`（`WEBP_LINK_STATIC` 只影响工具链接），不处理会多出 `libwebp.so` / `libsharpyuv.so` |
| 7 | 不写全局 cache `BUILD_SHARED_LIBS` | 各依赖形态用目录作用域普通变量显式钉住：freetype 静态 / SDL2_mixer 共享 / libwebp 静态（保证首配与重配一致，CMakeCache 残留不会改变产物形态）；SDL2 自身显式定义共享 + 静态两个 target（`libSDL2x.so` + `libSDL2.a`） |
| 8 | 计划内产物门禁 | 构建后由 `build-xsys35.sh` 扫描构建树：出现 3 个计划外 `.so` 即报错 |

上游 Android 构建参数对照：上游 `android/app/build.gradle` 用 `-DANDROID_APP_PLATFORM=android-19`
与 `abiFilters armeabi-v7a,arm64-v8a`；本项目只用 `arm64-v8a`、`ANDROID_PLATFORM=android-26`
（minSdk 26），`ANDROID_STL=c++_static` 与上游一致。

---

## 6. 产物与校验

入库位置 `engine/src/main/jniLibs/arm64-v8a/`：

| 产物 | 体积（strip 后） | SHA-256 |
| --- | --- | --- |
| `libxsystem35.so` | 1 692 320 B（≈1.69 MB） | `63072b36f71a4e59bda3d5074ea9490b44bd94048fc823ba73d98d68fbc3bf81` |
| `libSDL2x.so` | 1 476 240 B（≈1.48 MB） | `cee53fbc5fd41a895658ce98401cd6aa033ad6039b426d4401f9ac1b3caba0c1` |
| `libSDL2_mixer_xs35.so` | 201 408 B（≈0.20 MB） | `45123e7945ec780a19c41aea102ff893ed700d417b2ea86cc3b736a587afc2fe` |

合计 ≈ 3.37 MB（arm64，strip 后）。

> 入库产物为**本次重构前**的构建版本；重构后已用 `scripts/build-xsys35.sh` 新流程（fork 源码 +
> 外部依赖目录）从零复现验证，具体实测见 §6.4。

### 6.1 `llvm-readelf -d`（SONAME / NEEDED）

```
libxsystem35.so          SONAME libxsystem35.so
  NEEDED libm.so liblog.so libdl.so libz.so libSDL2_mixer_xs35.so libSDL2x.so libc.so
libSDL2x.so              SONAME libSDL2x.so
  NEEDED libm.so libOpenSLES.so libdl.so liblog.so libandroid.so libGLESv1_CM.so libGLESv2.so libc.so
libSDL2_mixer_xs35.so    SONAME libSDL2_mixer_xs35.so
  NEEDED libSDL2x.so libm.so libdl.so libc.so
```

- SONAME 与文件名逐一一致；
- `libxsystem35.so` 的 NEEDED 含 `libSDL2x.so` 与 `libSDL2_mixer_xs35.so`；
- 未出现 `libSDL2.so`；未出现绝对路径；未出现未入库的第三方名（`libwebp.so` / `libogg.so` /
  `libvorbis.so` / `libfreetype.so` 等）——freetype 与 libwebp **已静态链入 `libxsystem35.so`**。

### 6.2 符号校验

```bash
$NM -D engine/src/main/jniLibs/arm64-v8a/libSDL2x.so | grep -c org_libsdl_app32   # → 52
$NM -D engine/src/main/jniLibs/arm64-v8a/libSDL2x.so | grep -c org_libsdl_app_    # → 0
$NM -D engine/src/main/jniLibs/arm64-v8a/libxsystem35.so | grep -w SDL_main        # → 00000000000b8830 T SDL_main
```

### 6.3 改名面复核

```bash
grep -rn "org/libsdl/app" <build-dir>/third_party/SDL2/src/ <build-dir>/third_party/SDL2/include/  # 只有 org/libsdl/app32/…
grep -rn "org\.libsdl\.app" <build-dir>/third_party/SDL2/src/                                       # 0 行
grep -rn "org_libsdl_app"  <build-dir>/third_party/SDL2/src/ <build-dir>/third_party/SDL2/include/  # 仅 2 个宏 + 1 处注释
```

### 6.4 重构后新流程复现验证（2026-10-13）

`scripts/build-xsys35.sh`（`--src /Volumes/HUAWEI-M2LITE/app/xsystem35-sdl2`，产物输出到仓库外临时目录）从零构建：

- 产物集合 3 个（脚本内置「计划外 `.so`」门禁通过）；
- `llvm-readelf -d` 的 SONAME/NEEDED 与 §6.1 完全一致；
- `llvm-nm -D`：`libSDL2x.so` 的 `org_libsdl_app32` 符号 52 个、`org_libsdl_app_` 0 个；
  `libxsystem35.so` 的 `SDL_main` 在位（与 §6.2 一致）；
- 动态符号集合（defined/undefined）、`.dynsym`/`.dynstr` 语义与入库产物完全一致；
- 与入库产物的差异仅来自两处，均与代码语义无关：
  1. `.note.gnu.build-id`（链接器注记，随构建变化）；
  2. 编译进二进制的**源码绝对路径字符串**（`__FILE__`：`libSDL2x.so` 3 处 SDL2 源文件、
     `libxsystem35.so` 1 处 `src/microui/microui.c`），以及由其长度差引发的字符串表/地址布局位移。

**反证**：以 `-fmacro-prefix-map=<构建目录>=<重构前 vendored 路径>` 重新编译（把 `__FILE__`
映射回原路径）后，**3 个产物与入库产物逐字节一致**——仅 `.note.gnu.build-id` 不同（对齐该
section 后 `cmp` 通过），文件体积 1 692 320 / 1 476 240 / 201 408 B 也完全相同。即新流程与入库
产物在符号、NEEDED 与全部代码/数据上一致；因此 **jniLibs 产物不替换**，§6 表保持入库产物记录。

新流程（未做路径映射）实测 SHA-256，存档备查（连续两次 `--clean` 构建完全一致，可复现）：

| 产物 | 新流程 SHA-256 |
| --- | --- |
| `libxsystem35.so` | `98636a05b3ea7e337184d8a16d0c1905b0fd89b5215c55cef4da36439d258f13` |
| `libSDL2x.so` | `102477f478b640cd6fbcca0fddc50cbda5c7745304724e85136c943bf99a9f66` |
| `libSDL2_mixer_xs35.so` | `4d8a0210f2103230e749363dc10eb234697db6da1af375c4e4431ad3d53f1ec7` |

> `libSDL2_mixer_xs35.so` 不含任何源码路径字符串，其新流程产物与入库产物的差异**仅**
> `.note.gnu.build-id`。

---

## 7. 上游更新时的复现步骤

1. 拉取上游/fork 新 commit，记录 `git rev-parse HEAD` 与 `CMakeLists.txt` 的 `XSYSTEM35_VERSION`；
2. 更新 `scripts/build-xsys35.sh` 的 `FORK_COMMIT`（及 `BUILD.md` 基线表）；
3. 若依赖版本变化：更新脚本中的依赖清单（URL/SHA1）与本文档 §2.2；
4. 重新应用 patch —— 上游若已推进到新版 SDL2，先确认改名面
   （`grep -rn 'org_libsdl_app\|org/libsdl/app' <build-dir>/third_party/SDL2/src/`）后修正 patch；
5. 重跑 `scripts/sync-app32-java.sh`（必要时先 `--check` 看差异）；
6. 按 §5 重新构建、strip、入库，并更新 §6 的体积 / SHA-256 / readelf 记录；
7. 真机验收见方案 §7.2。

---

## 8. 许可

完整源码与修改声明见 fork（<https://github.com/Weiss-UltimateSavior/xsystem35-sdl2>，
固定 commit 见 §1）；主仓 `licenses/` 保留**随二进制分发**的字体/编解码器许可文本。

| 组件 | 许可 | 备注 |
| --- | --- | --- |
| xsystem35 | **GPL-2.0-or-later** | `COPYING` 与完整源码在 fork；本仓库预构建 `.so` 为其衍生分发 |
| SDL2 2.32.10 | zlib | 完整声明见 fork/依赖源（`LICENSE.txt`） |
| SDL2_mixer 2.8.0 | zlib | 同上 |
| freetype 2.14.3 | FTL / GPLv2 | 同上（`docs/FTL.TXT`、`GPLv2.TXT`） |
| libwebp 1.5.0 | BSD-3-Clause | 同上（`COPYING`，另有 `PATENTS`） |
| 内置字体 `MTLc3m.ttf` / `mincho.ttf` | 见 `licenses/MTLc3m.txt` / `licenses/mincho.txt` | 已提交于 `engine/src/main/assets/`（§9），随 APK assets 分发 |
| microui / nanojpeg（xsystem35 内置） | 见 `licenses/microui.txt` / `licenses/nanojpeg.txt` | 随二进制分发保留 |

---

## 9. 字体资产（主仓内置）

`MTLc3m.ttf`、`mincho.ttf` 是 xsystem35 的**硬依赖**（缺失会在启动时 SYSERROR 退出），
必须随 APK assets 根的两个文件存在。重构后**直接提交**在
`engine/src/main/assets/`（与既有 `DroidSansFallback.ttf` 同级；不再有 vendored `fonts/` 与
Gradle 同步任务）：

- `MTLc3m.ttf` 1 924 864 B（SHA-256 `b9e87fd7e4e5112b7bcc8c3486c19a23379e4520b36b5c049ecfd40731556a51`）
- `mincho.ttf` 4 775 296 B（SHA-256 `a57a34b464325bce7cb2bb6bbcf7ef81c2c5eea7897cbd8acbfbdc2eb8ad695c`）

`app/build.gradle.kts` 另有 `androidResources { noCompress += listOf("ttf", "otf") }`：
字体 asset 若被 AAPT 压缩，xsystem35 读取会极慢（上游明确要求）。

### 9.1 Gradle 链路实测（AGP 9.2.1）

```bash
./gradlew :app:mergeDebugAssets --no-daemon     # BUILD SUCCESSFUL
#   :engine:mergeDebugAssets → :app:mergeDebugAssets（字体直接来自 engine/src/main/assets）
#   产物：app/build/intermediates/assets/debug/mergeDebugAssets/{MTLc3m.ttf,mincho.ttf}

./gradlew assembleDebug --no-daemon             # BUILD SUCCESSFUL
unzip -v app/build/outputs/apk/debug/app-debug.apk | grep -E "MTLc3m|mincho|libSDL2x|libSDL2_mixer_xs35|libxsystem35"
#   1924864  Stored  1924864  assets/MTLc3m.ttf            ← noCompress 生效（Stored 而非 Defl）
#   4775296  Stored  4775296  assets/mincho.ttf
#   1476240  Defl    …        lib/arm64-v8a/libSDL2x.so
#    201408  Defl    …        lib/arm64-v8a/libSDL2_mixer_xs35.so
#   1692320  Defl    …        lib/arm64-v8a/libxsystem35.so
```

> 注：本仓库 AGP 9.2.1 已无 `:app:processDebugAssets` 任务（等价合并任务为
> `:app:mergeDebugAssets` / `:app:compressDebugAssets`），验证用前者 + 完整 `assembleDebug` 完成
> （含 APK 内字体为 Stored、第三方 `.so` 全部入库两条实据）。
