# patches/ —— 对 SDL2 依赖的最小 patch

这些 patch 用于把官方 SDL2 2.32.10 源码改到「内置到 TyranorNext」所需的状态。
依赖源码**不随主仓分发**：`scripts/build-xsys35.sh` 会把官方 tar 解压到仓库外构建目录
（`<build-dir>/third_party/SDL2`），并按文件名顺序对**干净解压源**逐个应用以下 patch
（先 `git apply --check` / `patch --dry-run` 校验，通过后应用；重复运行且内容复核通过时跳过）。
下表「目标文件」路径相对 `<build-dir>/third_party/`。

| patch | 目标文件 | 作用 |
| --- | --- | --- |
| `0001-sdl2-jni-prefix-app32.patch` | `third_party/SDL2/src/core/android/SDL_android.c` | `SDL_JAVA_PREFIX` 宏 + `JNI_OnLoad` 中 4 处 register_methods 类路径字符串 `org/libsdl/app` → `org/libsdl/app32` |
| `0002-sdl2-hidapi-jni-prefix-app32.patch` | `third_party/SDL2/src/hidapi/android/hid.cpp` | HIDAPI 的 `SDL_JAVA_PREFIX` 宏 `org_libsdl_app` → `org_libsdl_app32`（HIDDeviceManager 以符号名导出 JNI 方法，必须同步，否则 app32 Java 层启动即 `UnsatisfiedLinkError`） |

背景：ONS 插件的 SDL2 2.26.3 Java 层（`org.libsdl.app`）与 2.32.10 native 不可混用
（`onCreate` 版本硬校验），因此本引擎的 Java 层整包改名 `org.libsdl.app32`（生成脚本
`../scripts/sync-app32-java.sh`），native 侧前缀必须同步改名。详见 `../BUILD.md` §3。

## 重新应用

在**未修改**的官方 SDL2 2.32.10 源码上（<build-dir> 为 `build-xsys35.sh` 的构建目录，
默认 `${TMPDIR:-/tmp}/xsys35-build`）：

```bash
cd <build-dir>/third_party/SDL2
patch -p1 < <repo>/engine/xsystem35/patches/0001-sdl2-jni-prefix-app32.patch
patch -p1 < <repo>/engine/xsystem35/patches/0002-sdl2-hidapi-jni-prefix-app32.patch
```

## 复核（改名面穷尽性）

```bash
cd <build-dir>/third_party/SDL2
grep -rn "org/libsdl/app" src/ include/     # 只应出现 org/libsdl/app32/…
grep -rn "org\.libsdl\.app" src/            # 应为 0 行
grep -rn "org_libsdl_app" src/ include/     # 只应是 2 个宏（值为 …app32）+ 1 处注释
```

## 不落 patch 的改名

`libSDL2x.so`（SONAME = `libSDL2x.so`）与 `libSDL2_mixer_xs35.so` 的输出名重命名在
`../android-host/CMakeLists.txt` 里用 `set_target_properties(... OUTPUT_NAME ...)` 完成，
不改上游源码（实测 SONAME 与文件名一致，无需 `-Wl,-soname` 修正），故不产生 patch 文件。
