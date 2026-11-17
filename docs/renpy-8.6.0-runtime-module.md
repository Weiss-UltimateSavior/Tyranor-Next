# Ren’Py 8.6.0 运行时接入记录

记录日期：2026-11-17。

JoiPlay 目前只提供到 Ren’Py 8.5.3 的插件（2026-05-28 发布），而 Ren’Py 8.6.0 把底层从 SDL2 换成了 SDL3。为了运行 8.6 游戏，本项目接入了一个独立的运行时 APK：它用官方 8.6.0 RAPT（Android 打包工具）和 SDK 原样组装，从共享存储里的游戏目录加载游戏。

## 运行时身份

| 项目 | 值 |
| --- | --- |
| 源码 | [t-satoru-Studio/renpy-android-runtime](https://github.com/t-satoru-Studio/renpy-android-runtime)（GPL-3.0） |
| 包名 | `studio.tsatoru.renpy86` |
| 启动 action | `studio.tsatoru.renpy86.RUN` |
| 接入版本 | 8.6.0-r2，versionCode `8060002` |
| APK | `renpy-android-runtime-8.6.0-r2.apk`，SHA-256 `4904b038259bbff193b14e034d7b1aacd7806901175416697c0361e2d51f2e51` |
| 签名证书 SHA-256 | `1c485c2f36f7e5e453202f6da4d9a854fc481dace2965373728c7a58d402fed7` |
| Ren’Py | 8.6.0.26111403（SDL3、Python 3.12），官方 `librenpython.so` 未修改 |
| ABI / 系统 | arm64-v8a，Android 6.0（API 23）及以上 |

## 协议核对

| 检查点 | 运行时行为 | 对本项目的影响 |
| --- | --- | --- |
| 启动入口 | 导出的 `LaunchActivity` 接受 `studio.tsatoru.renpy86.RUN` | `RenPy86ExternalEngineModule` 只覆写 action 与包名，Intent 拼装复用 `RenPyRuntimeModule` |
| 游戏载荷 | 读取 `game` JSON 的 `folder`、`title`、`id`；`folder` 可以是基目录或其下的 `game/` | 现有 `buildGameJson` 不改；游戏 ID 与 JoiPlay 模块相同，只用作运行时日志目录名 |
| 设置载荷 | 读取 `settings.renpy` 下的 `renpy_*`，值可带 `{"boolean": ...}` 包装；忽略 `renpy_dont_use_gl2` 与 `app.cheats` | 现有 `buildSettingsJson` 不改；8.6 在 Android 上只有 gles2 渲染器，「禁用模型渲染」与金手指对 8.6 无效 |
| configuration.json | 不读 | 8.6 模块的 `prepareForLaunch` 不同步该文件 |
| 其他 extras | `orientation`、`rootUri`、`launchTarget` 被忽略 | 无需区分版本发送 |
| 存档 | Ren’Py 以 `--savedir <folder>/saves` 运行 | 与现有 Ren’Py 存档管理的目录一致 |
| 权限 | 自身申请「所有文件访问」（Android 11+）或存储权限 | 与 JoiPlay 模块相同，App 的 SAF 授权不能替代 |
| 进程 | 每个游戏运行在独立的 `:game` 进程；切换游戏会结束旧进程，再次启动同一游戏则切回原进程 | 无 |

最低 versionCode 设为 `8060002`：8.6.0-r1 在游戏切到后台时主线程会被卡住 8 秒，Ren’Py 也要等回到前台才保存（原因见运行时仓库 README 的上游问题说明），r2 已修复。

## 版本选择

- 显式设置「8.6.0」：始终使用该运行时。
- 自动：`RenPyVersionDetector` 把版本码 ≥ 80600 的游戏判定为 `8.6.0`，其余 8.x 与未知版本仍为 `8.5`（8.5.3 模块），7.x 为 7.7.1。
- 启动时，如果能直接读取游戏目录中的 `game/script_version.txt` 或 `.rpy`，以它为准，用来修正接入前扫描写入的旧记录；读不到（例如尚未授予所有文件访问）时沿用扫描记录，不做 Python 2.7 / 默认推断。
- 自动选中 8.6.0 而运行时未安装时提示安装，不回退到 8.5.3：8.6 构建的 `.rpyc` 与旧运行时不兼容。
- 启动时的版本文件读取发生在所有文件访问授权之前；尚未授权时沿用扫描记录，授权后下一次启动才会改选 8.6。
- Ren’Py 8.6.0 构建写入的 `script_version.txt` 原文为 `(8, 6, 0)`，解析为 80600。

## 验证

- `./gradlew :app:testDebugUnitTest --tests 'com.tyranor.next.core.engine.external.*' --tests 'com.tyranor.next.core.settings.RenPySettingsKeysTest' --tests 'com.tyranor.next.core.game.scan.RenPyVersionDetectorTest' --tests 'com.tyranor.next.core.game.scan.EngineScannerRenPyTest' --no-daemon`：124 个测试，0 失败，0 错误。新增覆盖：8.6 模块身份与路由、显式设置优先于探测结果、`(8, 6, 0)` 解析、只读版本文件的探测不做推断。
- `./gradlew testDebugUnitTest --no-daemon`：app 与 engine 全量 JVM 回归通过。
- `./gradlew :app:lintDebug --no-daemon`：0 错误。`:engine:lintDebug` 报 54 个错误（`MissingPermission` 48、`NewApi` 3、`UnspecifiedRegisterReceiverFlag` 2、`MissingSuperCall` 1），均在本次未改动的 engine 模块存量代码中（如 `org/libsdl/app32`）。
- `python tools/check-hardcoded-ui-strings.py`、`python tools/check-motion-boundaries.py`：通过。
- 设备（Android 16，arm64，隔离包名 `com.tyranor.next.ethornelltest` 的调试包）：
  - 引擎页 Ren’Py 弹窗列出 8.6.0，已安装 r2 时显示「模块已安装」。
  - 扫描共享存储中的 Ren’Py 8.6.0 商业游戏（约 5.6 GB RPA，含视频）与 SDK 自带示例游戏，版本设置为「自动」时启动 8.6 运行时，进入游戏，存档位于 `<游戏目录>/saves`。
  - HyperOS 会弹出系统的跨应用启动确认框，这与拉起 JoiPlay 模块时相同。
- 运行时本身的设备验证（返回键回退、切后台存档、切换游戏、权限引导）记录在运行时仓库。

未执行：Android 10 及以下的设备验证，以及 8.5 及更早游戏在 8.6 运行时上的兼容性测试（自动模式不会把它们交给 8.6）。运行时仓库目前是私有的，在公开之前，用户点「去下载」会打不开。
