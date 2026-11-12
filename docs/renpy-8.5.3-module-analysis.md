# Ren’Py 8.5.3 外置模块接入分析

分析日期：2026-11-12。分析对象由用户提供，未安装到设备，未运行游戏。

## 样本身份与分析范围

| 项目 | 值 |
| --- | --- |
| 文件 | `RenPyPlugin-8.5.3-1.02.00.apk` |
| 文件大小 | 55,897,560 字节 |
| SHA-256 | `dc81e5bb891ff868c3bddba679f3759a42511fcdffb731a41e41584e4933f0a5` |
| 包名 | `cyou.joiplay.runtime.renpy.v8d4d1` |
| APK versionCode / versionName | `10200` / `1.02.00-patreon` |
| 引擎版本元数据 | `cyou.joiplay.runtime.version = 80503` |
| minSdk / targetSdk | 21 / 34 |
| 获取地址 | [用户提供的下载地址](https://github.com/Weiss-UltimateSavior/RinneMobile/releases/download/test/RenPyPlugin-8.5.3-1.02.00.apk) |

使用本机 apktool 与 JADX 拆解，输出在临时目录 `/tmp/tyranor-renpy853-analysis`。JADX 报告部分方法反编译失败；关键入口、解析器与路径处理已交叉检查 apktool smali。临时输出与第三方完整反编译源码不纳入仓库。

本次对照基线是当前项目已实现的外置模块协议及历史分析记录，未取得旧版 8.5 APK 做二进制差分。因此下文“兼容”表示新 APK 能消费当前项目载荷，不能据此声称新旧模块的所有内部行为相同。

## 对项目协议的核对

| 检查点 | APK 中的实际行为 | 对当前项目的影响 |
| --- | --- | --- |
| 安装身份 | 包名与原 8.5 模块一致 | 两版不能作为两个独立包并存；更新现有 8.x 模块入口 |
| 启动入口 | 导出的 `PermissionActivity` 接受 `cyou.joiplay.runtime.renpy.run`，另接受 `cyou.joiplay.runtime.renpy8.run` | 继续使用当前 `renpy.run` action |
| 权限交棒 | 入口请求存储权限，Android 11+ 检查所有文件访问，随后把 extras 交给 `PythonSDLActivity` | 外置 APK 自身仍需取得权限；App 的 SAF 授权不代替该权限 |
| 游戏载荷 | `GameParser` 读取 `title/id/folder/execFile/type` | 当前 `game` JSON 完整匹配；保持路径 hash ID，避免改变配置和存档定位 |
| 设置载荷 | `RenPyConfigurationParser` 读取嵌套的 `app.cheats.boolean` 与 8 个 `renpy_* .boolean` | 当前 9 项配置无需改格式或新增必填项 |
| 配置优先级 | 解析 Intent 后读取扁平 `configuration.json`，文件同键覆盖 Intent | 保留启动前同步已有配置文件的策略，未知键保留、不创建新文件 |
| 配置路径 | 游戏位于主外部存储根下时取游戏目录，否则取主外部存储 `JoiPlay/games/<id>/configuration.json` | 与 `RenPyRuntimeEnvironment.configFileFor` 一致 |
| 屏幕方向 | 两个 Activity 清单均为 `sensorLandscape`；未在检查的播放器业务代码中找到读取 `orientation` extra | 保留兼容 extra；不能将其描述为该版本的有效方向控制接口 |
| 自动关闭 | 解析器另认识可选 `app.screenTimeout`；大于 0 才启动计时器 | 当前项目未下发，无新增必需字段，不在本次新增设置 |

8 个引擎字段仍映射到 `JOIPLAY_HW_VIDEO`、`JOIPLAY_AUTOSAVE`、`JOIPLAY_VARIANT_PHONE`、`RENPY_GL_VSYNC`、`RENPY_LESS_MEMORY`、`RENPY_LESS_UPDATES`、`RENPY_DONT_USE_GL2`、`RENPY_RECOMPILE`。前四项显式写入 `1/0`，后四项仅在开启时设置 `1`。`cheats` 用于 JoiPad 的金手指工具显隐，不是这些环境变量之一。

存档路径由模块自己决定：Android 10 及以下且游戏不在主外部存储根下时，使用模块的 `getExternalFilesDir()/ <game.id>/saves`；其他情况使用 `<game.folder>/saves`。该行为不等于 TyranorNext 接入统一存档管理，本次也不新增该能力。

## 拆解证据定位

以下路径相对于临时输出根目录，可从同一样本重新拆解核查：

- `apktool/AndroidManifest.xml`：包名、版本元数据、权限、两个启动 action、Activity 方向。
- `apktool/apktool.yml`：APK 版本与 SDK。
- `jadx/sources/cyou/joiplay/runtime/renpy/v8d4d1/PermissionActivity.java` 的 `startGame()`：extras 交棒。
- `jadx/sources/cyou/joiplay/commons/parser/GameParser.java`：游戏字段解析。
- `jadx/sources/cyou/joiplay/commons/parser/RenPyConfigurationParser.java`：嵌套与扁平配置；对应 `apktool/smali/cyou/joiplay/commons/parser/RenPyConfigurationParser.smali`。
- `jadx/sources/org/renpy/android/PythonSDLActivity.java` 的 `parseConfig()`、`loadConfig()`、`preparePython()`：配置顺序、环境变量及存档路径；对应 `apktool/smali/org/renpy/android/PythonSDLActivity.smali`。

## 项目接入行为

- 原 8.x 注册项升级为 8.5.3，保留模块 ID、包名及历史别名；下载地址换成新 APK。无需新增 Manifest package query，因为包名未变。
- 设置界面显示“自动 / 8.5.3 / 7.7.1”。已有全局和单游戏 `8.5` 值继续映射到升级后的模块。
- 自动选择的 8.x 和未知版本目标随该模块升级为 8.5.3；检测到 7.x 时仍选择 7.7.1。扫描缓存不需重写。
- 新增最低 APK versionCode `10200` 检查。旧包即使同名已安装，也不能视为 8.5.3 已就绪；启动返回类型化的更新失败，提供新下载地址。其他外置模块默认不增加版本门槛。
- 不打包外置 APK，不改动游戏 ID、游戏目录和配置协议，不接管外置模块的存档。

静态协议检查不构成游戏兼容性验收。旧 8.5 与本样本的签名是否相同、设备上是否能直接覆盖安装、真实游戏运行和存档兼容性均未验证。

## 本次验证结果

- `./gradlew :app:testDebugUnitTest --tests 'com.tyranor.next.core.engine.external.*' --tests 'com.tyranor.next.core.settings.RenPySettingsKeysTest' --tests 'com.tyranor.next.core.game.scan.RenPyVersionDetectorTest' --tests 'com.tyranor.next.core.game.scan.EngineScannerRenPyTest' --no-daemon`：串行重跑成功，113 个测试、0 失败、0 错误，包含最低版本边界、历史版本值映射、配置协议与扫描回归。首轮并行 Gradle 写入结果目录冲突失败，不计作通过结果。
- `python3 tools/check-hardcoded-ui-strings.py`：通过。
- `git diff --check`：通过。
- `./gradlew :app:lintDebug --no-daemon`：未通过，报告 29 个错误、371 个警告、10 个提示。错误集中在本次未改动的玻璃导航 API 兼容性逻辑和设置页既有 `LocalContextGetResourceValueCall` 用法；首项为 `EnhancedLiquidGlassNavigation.kt:641` 的 API 33 `getModifier`，不宣称全仓 lint 通过。
- 两个独立子 agent 复核了协议、配置兼容、版本门槛与 UI 映射，未发现需返修问题。
- 未进行设备安装、截图、UI 操作、旧 APK 签名对照或真实游戏验收；未生成发布 APK。
