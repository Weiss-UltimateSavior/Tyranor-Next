# Android SDK 与构建依赖升级计划

版本核对日期为 2026-11-19。计划待实施，应用最低支持版本继续为 API 26。每批升级单独提交，记录版本、验证命令和结果，便于定位与回退。

## 当前基线

| 项目 | 当前配置或解析结果 |
| --- | --- |
| 编译平台 | `:app` 为 37.0，`:engine` 为 36 |
| 应用目标版本 | `targetSdk 36` |
| 构建工具链 | AGP 9.3.3、Gradle 9.5.1、JDK 17、Build Tools 36.0.0（本机生效值） |
| Kotlin 相关工具 | App 编译器及 Compose、Serialization 插件为 2.3.20，KSP 为 2.3.6，App Release 标准库解析为 2.4.0 |
| 应用依赖 | Compose BOM 2026.05.01、Miuix 0.9.2、Room 2.7.2 |
| 原生工具链 | NDK 28.0.13004108、CMake 3.22.1、C++17、`arm64-v8a` |

配置见 [App Gradle 脚本](../app/build.gradle.kts)、[Engine Gradle 脚本](../engine/build.gradle)、[版本目录](../gradle/libs.versions.toml) 和 [Gradle Wrapper](../gradle/wrapper/gradle-wrapper.properties)。

## 1. 对齐编译平台和 CI

- [ ] 将 `:engine` 的 `compileSdk` 从 36 调整到 37.0，与 `:app` 对齐。本阶段保持 `targetSdk 36`。
- [ ] 核对 Android CI、Beta 发布和 macOS WebDAV CI 的 SDK 安装清单。在三个流程都确认无任务依赖 API 36 平台包后，再移除该安装项。 [Android CI](../.github/workflows/android-ci.yml) · [Beta 发布](../.github/workflows/android-beta-release.yml) · [macOS WebDAV CI](../.github/workflows/webdav-macos.yml)
- [ ] 记录 CI 上 AGP 实际使用的 Build Tools。本机生效版本为 36.0.0，Beta 发布另外直接调用 37.0.0 下的 `zipalign` 和 `aapt2`，用于 APK 对齐和版本号校验。保留 Beta 流程的 37.0.0 安装项。若要调整这两个工具的固定版本，单独更新调用路径，并复跑两项产物检查。 [Beta 发布脚本](../.github/workflows/android-beta-release.yml)
- [ ] 运行 `./gradlew assembleDebug testDebugUnitTest :app:lintReportDebug :engine:lintReportDebug`，检查 AAR 元数据和 Manifest 合并。CI 继续运行 `tools/check-lint-diff.py`，同时比较升级前后两个模块的完整 lint 报告，检查未改动源码上的新增诊断。 [CI lint 配置](../.github/workflows/android-ci.yml)
- [ ] 如有依赖或新功能需要 37.2，单独验证两个模块的编译与 CI 安装。Google SDK 稳定渠道已提供 `platforms;android-37.2` 修订版 2，Android 17 的主 API 级别为 37。设备系统镜像的发布状态见 QPR2 发布说明。 [QPR2 发布说明](https://developer.android.com/about/versions/17/qpr2/release-notes)

[Android 17 SDK 设置](https://developer.android.com/about/versions/17/setup-sdk) · [Google SDK 包清单](https://dl.google.com/android/repository/repository2-3.xml) · [Android 17 次版本 API](https://developer.android.com/reference/android/os/Build.VERSION_CODES_FULL)

## 2. 升级 AGP 和 Gradle Wrapper

- [ ] 将 AGP 与 Wrapper 作为一批更新，首轮验证 **AGP 9.5.1 + Gradle 9.6.1**。AGP 9.5 要求 Gradle 9.6.0 或更高版本，JDK 继续用 17。
- [ ] 更新 Wrapper 文件及分发包校验值，确认本机与 CI 的 Wrapper 版本一致，Gradle 运行在 JDK 17 上。将 Gradle 9.9.1 留作独立验证，采用前核对实际 KGP 的兼容范围。
- [ ] 重跑阶段 1 的 Debug 构建、JVM 单测和 lint。在本地或非发布 CI 显式运行 `./gradlew :app:compileReleaseArtProfile :app:assembleRelease`，验证 R8、基线配置文件和实际 Release APK。复用 Beta 流程的运行库来源与依赖校验、`zipalign -c -P 16 -v 4` 和 APK 版本号检查。单改 AGP 或 Wrapper 无法触发 Beta 发布验证。全新安装可用本地默认的 Debug 签名构建 Release 包，覆盖安装须与旧 APK 签名一致。 [发布触发与验收](../.github/workflows/android-beta-release.yml) · [Release 签名配置](../app/build.gradle.kts)

[AGP 9.5 发布说明和兼容表](https://developer.android.com/build/releases/agp-9-5-0-release-notes) · [Gradle 发布记录与校验值](https://gradle.org/releases/)

## 3. 分批升级 Kotlin 和应用依赖

- [ ] **Kotlin / KSP**。将 Kotlin **2.4.21** 和 KSP **2.3.14** 作为候选。用 `./gradlew :app:dependencyInsight --configuration kotlinCompilerClasspath --dependency kotlin-compiler-embeddable` 核对 AGP 内置编译器，并记录实际 KGP、Compose 与 Serialization 插件以及 Debug / Release 标准库版本。JetBrains 为 KGP 2.4.21 列出的完全支持上限是 AGP 9.3.1 和 Gradle 9.7.0。超出范围的组合需单独通过 Debug / Release 编译、Release R8、KSP 生成和 JVM 单测后再采用。 [Kotlin 兼容表](https://kotlinlang.org/docs/gradle-configure-project.html#apply-the-plugin)
- [ ] **Room**。将 runtime、ktx、compiler 一并从 2.7.2 升到 **2.8.5**。核对 `app/schemas/`，运行 `GameLibraryDatabaseDeviceMigrationTest`，验证历史数据库 v2–v5 升到 v6。用已有数据覆盖安装，确认游戏条目、扫描目录、快捷启动记录仍可读写，已有游玩时长可读取和追加。Room 2.8 的最低 API 为 23。 [游戏库迁移测试](../app/src/androidTest/java/com/tyranor/next/core/game/storage/GameLibraryDatabaseDeviceMigrationTest.kt) · [游玩时长数据库](../app/src/main/java/com/tyranor/next/core/game/playtime/GamePlaytimeDatabase.kt)
- [ ] **Compose BOM**。从 2026.05.01 升到 **2026.09.00**。用 `dependencyInsight` 记录 Debug / Release 的 Compose UI、Foundation、Material 3 和 `material3-window-size-class` 基线与升级结果，运行 `./gradlew :app:checkDebugAarMetadata :app:checkReleaseAarMetadata`。
- [ ] **Miuix**。单独验证 **0.9.4**，复查 Debug / Release 的 Material 3、窗口尺寸类和 Kotlin 标准库。发布依赖包含 Kotlin 标准库 2.4.20，并可能将 AndroidX Material 3 拉到 **1.5.0-alpha22**。如解析到预发布依赖且本批未决定采用，保留 0.9.2。BOM 与 Miuix 两批各自验证抽屉、弹窗、设置页滑条、玻璃外观和返回动效，记录手机和平板的横竖屏表现。 [Miuix 发布元数据](https://repo.maven.apache.org/maven2/top/yukonga/miuix/kmp/miuix-ui-android/0.9.4/miuix-ui-android-0.9.4.module) · [AndroidX Material 3 传递约束](https://dl.google.com/dl/android/maven2/androidx/compose/material3/material3-window-size-class-android/1.5.0-alpha22/material3-window-size-class-android-1.5.0-alpha22.module)

[Kotlin 发布记录](https://kotlinlang.org/docs/plugin-releases.html) · [AGP 内置 Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin) · [KSP 2.3.14](https://github.com/google/ksp/releases/tag/2.3.14) · [Room 发布记录](https://developer.android.com/jetpack/androidx/releases/room) · [Compose BOM](https://developer.android.com/develop/ui/compose/bom) · [Miuix 0.9.4](https://github.com/compose-miuix-ui/miuix/releases/tag/v0.9.4)

## 4. 单独评估 NDK

- [ ] 先验证 **28.2.13676358**，再按需要评估最新 LTS **r30（30.0.16248370）**。首轮保留 CMake 3.22.1。
- [ ] 同步 App、Engine 和相关 CI 中用于编译 JNI 桥的 NDK 版本。Ethornell 来源和 ELF 校验固定使用 **28.0.13004108**，在需要的 CI 中并行安装旧版，保留校验的 `--ndk` 路径和既有来源记录。单独指定并记录 Ethornell 测试 JNI 探针使用的 NDK，它读取 `ANDROID_NDK_HOME`，未设置时回退到 28.0.13004108。运行 `python3 -B -m unittest discover -s tools/ethornell/tests -p test_workflow_policy.py`。 [Ethornell 校验实现](../tools/ethornell/verify_provenance.py) · [测试探针配置](../app/build.gradle.kts)
- [ ] 验证实际 Release APK 的 ZIP 16 KB 对齐、JNI 桥和随包预编译库的 ELF 段对齐及 `DT_NEEDED` 依赖闭环。检查范围包括运行库包解压出的库。 [Android 16 KB 指南](https://developer.android.com/guide/practices/page-sizes)
- [ ] 用 API 26 和 16 KB 页大小真机及真实游戏，验证 JNI 桥与预编译运行库组合，覆盖首次解包、冷启动和已安装运行库包复用。记录设备、系统、页大小、运行库身份与样本结果。

[Android NDK 下载页](https://developer.android.com/ndk/downloads) · [项目资源与原生构建链](../ARCHITECTURE.md#6-资源与原生构建链)

## 5. 单独迁移到 `targetSdk 37`

- [ ] **大屏与横屏**。检查合并后的 Manifest，确认是否适用系统的游戏类别豁免。在 Android 17 平板和折叠屏（`sw >= 600dp`）覆盖分屏、桌面窗口缩放、折叠内外屏切换及 Activity 重建，验证游戏视口、触控坐标、虚拟按键、按键方案布局编辑器和状态恢复。 [引擎 Manifest](../engine/src/main/AndroidManifest.xml) · [Android 17 大屏规则](https://developer.android.com/about/versions/17/changes/ff-restrictions-ignored)
- [ ] **原生库加载**。梳理全部 `System.load()` 路径，修正安装时设为只读失败的处理，并检查已有文件是否需要修复或重装。分别覆盖运行库包、已安装 Native 插件和 Artemis 派生库，验证全新安装、覆盖升级和缓存命中时实际加载的文件无写权限，且引擎能正常启动。 [运行库包](../engine/src/main/java/com/core/nativeplugin/NativeRuntimePack.kt) · [Native 插件安装](../engine/src/main/java/com/core/nativeplugin/NativePluginInstaller.kt) · [Artemis 派生库](../engine/src/main/java/com/core/nativeplugin/ArtemisVorbisRepair.kt) · [Android 17 原生加载规则](https://developer.android.com/about/versions/17/behavior-changes-17)
- [ ] **局域网 WebDAV 权限**。在目标版本迁移中声明 `ACCESS_LOCAL_NETWORK`，为用户主动连接局域网服务提供前台授权入口。后台因缺权无法同步时，暂停对应任务并提供可恢复的状态，避免按普通网络错误持续重试。 [后台同步实现](../app/src/main/java/com/tyranor/next/core/game/save/sync/SaveSyncWorker.kt) · [Android 局域网权限](https://developer.android.com/privacy-and-security/local-network-permission#android-17-enforcement)
- [ ] **局域网 WebDAV 验收**。覆盖新配置和已经配置 NAS 的用户从目标 API 36 覆盖升级，验证允许、拒绝、撤销及重新授权。检查连接、上传、下载和后台存档同步恢复，同时复测公网 WebDAV。 [WebDAV 设备验收记录](WebDAV-device-acceptance.md)
- [ ] **系统兼容**。在 Android 17 真机对比目标 API 36 与 API 37 的验证包，检查代表性引擎切换前后台时的音频焦点和播放恢复，以及大体积游戏的内存与退出表现。使用相同签名验证覆盖安装。上述真机验收通过后，将正式构建的 `targetSdk` 升到 37，并回归 API 26 安装与启动。 [Android 17 目标版本行为变化](https://developer.android.com/about/versions/17/behavior-changes-17) · [影响所有应用的行为变化](https://developer.android.com/about/versions/17/behavior-changes-all)
