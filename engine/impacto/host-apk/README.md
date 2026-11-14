# Impacto 宿主 APK

承载**未改动**的 [CommitteeOfZero/impacto](https://github.com/CommitteeOfZero/impacto)（固定提交见 `../source-lock.json`）。
Tyranor Next 通过 Intent 启动本 APK；本 APK 不属于主 App 的 Gradle 构建，需要单独构建。

## 为什么是独立 APK

上游 `android/` 自带 SDL3 的 `org.libsdl.app` Java 层，与主 APK 内置的 SDL2 同名类冲突。独立进程消除不了同一 APK 内的类名冲突，
而改名、生成修改后的副本或替换符号都属于被禁止的引擎改动。所以它是单独的 APK：包内只有

- 上游 Java 源码（`ImpactoActivity` 与 SDL3 Java 层），构建时直接引用已校验的检出，**不复制、不修改**；
- 上游库 `libimpacto.so`（由 `tools/impacto/build-upstream.sh` 构建的候选库）；
- 上游的 `profiles/`、`resources/`、着色器和 `gamedefinitions.lua`，构建时暂存到 `build/`，不写回源码目录；
- TYN 自有的两个类：`ImpactoLaunchActivity`、`TyranorImpactoActivity`。

## 宿主做了什么

只使用上游已公开的钩子，没有补丁：

| 上游钩子 | 宿主行为 |
| --- | --- |
| `SDLActivity.getArguments()` | 传原版命令行 `-g <profile> -bp <basepaths.lua> -gc <gamedefinitions.lua> -uc <userconfig.toml>` |
| `ImpactoActivity.getChosenDirectory()` | 返回每个游戏独立的数据根 `<内部私有目录>/games/<gameKey>` |
| `ImpactoActivity.chooseDirectory()` / `resetChosenDir()` | 空实现：游戏目录由 Tyranor Next 选择，禁用上游目录选择器 |

原生代码经 JNI 在 Activity 实例上调用后三者，因此重写会被命中。

数据根下由上游自己创建 `gamedata/`、`patches/`、`saves/`；宿主只在 `gamedata/<profile>` 建一个指向游戏目录的符号链接，
上游即按 `RootGamedataDir/<profile>/<档案>` 找到数据，无需复制。存档与 `userconfig.toml` 因此按游戏隔离。
链接位置已存在真实目录或文件时拒绝替换。

入口 Activity 对外导出，所有字段都按不可信输入校验：profile 必须在固定白名单内；目录必须是共享存储（`/storage/`、`/sdcard/`）
下的规范化路径，且不得位于其他应用的 `Android/data`、`Android/obb`；`gameKey` 由宿主按与主 App 相同的方式
（SHA-256 前 12 字节，输入为 `profile NUL 目录`）重新计算，不符即拒绝，因此调用方无法让某个游戏的数据根指向别的目录。
读取 extras 时任何运行时异常都按拒绝处理，不会让承载游戏的进程崩溃。校验通过且已授予「所有文件访问」后才启动不导出的游戏 Activity。
游戏 Activity 运行在独立的 `:game` 进程，并关闭预测性返回（`SDLActivity` 依赖 `onBackPressed()`，targetSdk 36 默认会吞掉它）。

## 构建

需要 Python 3（非 Windows 使用 `python3`）、Android SDK（compileSdk 36）、JDK 17，以及：

1. 已校验的上游检出（`tools/impacto/verify-upstream.py` 通过），
2. 候选库 `libimpacto.so`（目前仅 Linux 构建，见 `docs/impacto-upstream-only.md`）。

```sh
./gradlew -p engine/impacto/host-apk :app:assembleDebug \
  -PimpactoSource=/path/to/verified/impacto \
  -PimpactoCandidate=/path/to/build/candidate
```

只编译 Java、运行 JVM 单测时不需要候选库：

```sh
./gradlew -p engine/impacto/host-apk :app:testDebugUnitTest -PimpactoSource=/path/to/verified/impacto
```

构建任务（`preBuild`，覆盖编译、lint、单测）会先运行 `verify-upstream.py`，检出不干净或版本不对即失败；打包前还会核对候选库：`candidate.json` 必须来自 `../source-lock.json`，且 `libimpacto.so` 的 SHA-256 与报告一致。

仅验证 Java 层（请求校验、权限流程、数据目录与符号链接）时，可加 `-PimpactoHostOnly=true`：不打包 `libimpacto.so`，
版本名带 `-hostonly`，**该 APK 起不了引擎，不得分发**。

## 限制

- 仅 Android 11（API 30）及以上：需要「所有文件访问」才能经路径读取游戏目录。
- 每个进程只跑一个游戏；游戏 Activity 结束时宿主结束进程，避免 SDL 的进程级状态残留。
- 数据根与存档在宿主的内部私有目录内（`files/games/<gameKey>/`），主 App 无法管理或同步。外部存储（含 `Android/data`）是 FUSE，不允许建符号链接，已在真机上确认，所以数据根必须放在内部存储。
- 尚未发布 APK，也没有下载地址；主 App 在未安装时只提示缺失。
- **引擎本体未运行过，也未与真实游戏数据验证。** 宿主 Java 层（请求校验、授权流程、数据根与链接、`:game` 进程）已用诊断包在一台真机上验证，详见 `docs/impacto-upstream-only.md`。
