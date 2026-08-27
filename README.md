# Tyranor Next

<p align="center">
  <img src="screenshots/index.png" alt="Tyranor Next" width="850" />
</p>

基于 **Tyranor 模拟器逆向重写**的多引擎视觉小说（Galgame）聚合启动器，面向 Android 平台。内置 Kirikiri / ONScripter / Tyrano / Artemis 四套引擎运行环境，并支持 Ren'Py 外置 APK 引擎模块，可识别和启动多类游戏，提供游戏库管理、封面获取、存档镜像、引擎参数调节等一体化体验。

主打轻便、简单、快捷，不引入其他冗余功能的简约设计思路

## 支持范围

### 引擎与游戏类型

| 游戏类型 | 典型识别特征 | 运行环境 |
| --- | --- | --- |
| Kirikiri / Kirikiri2 | `.xp3`、`startup.tjs` | Kirikiroid2 / krkrsdl3 原生运行时 |
| ONScripter | `nscript.dat`、`.nsa` | ONScripter 原生运行时 |
| Artemis | `system.ini`、`.pfs` | Artemis 原生运行时 |
| TyranoBuilder | `index.html`、`tyrano/` | 内置 Tyrano Web 运行环境 |
| RPG Maker MV | `www/`、`js/rpg_core.js` | 内置 Web 运行环境 |
| RPG Maker MZ | `www/`、`js/rmmz_core.js` | 内置 Web 运行环境 |
| VN | `globalData.vndata` | 内置 Web 运行环境 |
| WebOther | 通用 `index.html` 网页游戏 | 内置 Web 运行环境 |
| Ren'Py | `.rpa`、`game/script.rpy`、`game/options.rpy`、`renpy/` + `.rpy/.rpyc` | 外置 RenPy APK 模块 |

内置 Web 运行环境同时支持部分以 `app.asar` 打包的 NW.js 游戏；启动时会根据归档内容进一步识别具体类型。
Ren'Py 当前通过外置 APK 模块运行：主 App 默认启用该能力，仅在引擎页检查目标模块是否已安装；未安装时引擎 item 显示打叉并提示下载安装。

### 平台与文件要求

- Android 8.0（API 26）及以上。
- 当前原生引擎库仅提供 `arm64-v8a`，设备需为 64 位 ARM 架构。
- 游戏目录需位于 Android 可访问的本地存储，并通过系统文件选择器（SAF）授权；启动时目录必须能够映射为真实文件路径，外置存储上的部分引擎可能需要“所有文件访问”权限。
- 实际兼容性取决于游戏使用的引擎版本、封包/加密方式和脚本特性；特殊修改版可能需要调整引擎设置或补丁。

## 参与贡献

欢迎参与项目开发与维护！

在提交 Pull Request 前，请先阅读务必阅读遵守 [贡献指南](./CONTRIBUTING.md)。
如果不符合贡献指南的要求，可能会被拒绝合并。
代码务必符合项目整体风格，避免引入新的问题。
提交前请务必使用项目内AGENT.md文件内的审核方式进行提交前审核。
如果代码一眼丁真，一定拒绝合并。

## 技术架构

### 模块划分

| 模块 | 职责 |
| --- | --- |
| `app` | Android 应用壳：Compose UI、功能抽象层、配置、封面、存档、授权、后台更新等应用侧能力 |
| `engine` | 底层引擎运行时核心：SDL2/SDL3、Kirikiri TVP、krkrsdl3、ONScripter、Artemis、Tyrano、Native/JNI 与引擎宿主 Activity |

### 三层目录架构

当前项目按职责归类为三层，依赖方向固定为：

```
界面 UI 交互层 -> 功能抽象层 -> 底层引擎层
```

| 层级 | 目录 | 职责 |
| --- | --- | --- |
| 底层引擎层 | `engine/` | KRKR/Kirikiroid、krkrsdl3、ONS、Artemis、Tyrano、SDL/Cocos/IJK、Native/JNI、引擎宿主 Activity、引擎资源与 Native 插件底层加载 |
| 功能抽象层 | `app/src/main/java/com/tyranor/next/core/` | 游戏扫描、游戏模型、启动编排、封面抓取、存档管理、在线补丁、应用/引擎/单游戏配置、授权、后台更新 |
| 界面 UI 交互层 | `app/src/main/java/com/tyranor/next/ui/` | Compose 页面、Activity 壳、弹窗、导航、顶部栏、搜索框、用户输入、加载态与错误态 |

功能抽象层按领域继续拆分：

- `core/game`：游戏模型、扫描、启动、存档管理
- `core/engine`：引擎类型、内置引擎插件启动与安装编排、外置 APK 引擎模块注册与启动协议
- `core/cover`：封面抓取、来源聚合、批量任务
- `core/patch`：KRKR 在线补丁
- `core/settings`：应用级配置、引擎级配置、单游戏配置
- `core/auth`：Hikarinagi OAuth 授权与 token 管理
- `core/updater`：后台更新检查与通知
- `core/unpack`：引擎相关封包解包辅助

UI 层按页面域继续拆分：

- `ui/main`：主入口、底部导航与全局页面状态
- `ui/home`：首页
- `ui/game`：游戏库、游戏卡片、封面操作、游戏动作弹窗
- `ui/engine`：引擎页
- `ui/settings`：应用设置、引擎设置入口、引擎/单游戏设置页面
- `ui/cover`：封面来源与批量抓取设置
- `ui/patch`：KRKR 在线补丁页面
- `ui/save`：存档管理页面
- `ui/auth`：OAuth 回调 Activity
- `ui/common`：公共 UI 组件、弹窗、占位页、顶部栏/输入框等复用组件

### 技术栈

- **语言**：Kotlin（引擎层含 Java 桥接代码）
- **UI**：Jetpack Compose + Material 3 + [Miuix](https://github.com/compose-miuix-ui/miuix)
- **导航**：底部导航 `NavigationBar`；主 Tab 内容页使用水平移动切换，详情/设置等独立 Activity 进入使用向上翻页、退出使用向下翻页
- **构建**：Gradle 9.5.1 / AGP 9.2.1 / Kotlin 2.x + Compose Compiler，`compileSdk 37`、`minSdk 26`、`targetSdk 36`
- **持久化**：SharedPreferences（扫描结果、引擎全局设置、单游戏设置覆盖、最近记录）
- **文件访问**：Storage Access Framework（SAF）管理外部游戏目录，`documentFile` 库辅助

### 引擎集成设计

- 引擎原生插件（`kirikiroid2` / `ons` / `artemis` 的 `.so`）以 assets 形式随 APK 打包（`nativeplugins/`），首次启动由 `NativePluginManager` 自动解压安装到应用私有目录
- 共享 Native 插件 `.so` 的源头位于 `engine/src/main/nativeplugins`；app 侧仅维护插件 `manifest.json` 和 app-only 插件。构建时由 Gradle 合并生成 `app/build/generated/assets/nativeplugins/*.zip`
- 共享 RPG Maker 注入脚本源头位于 `engine/src/main/assets`；app 侧只保留应用专属注入脚本，构建时由 Gradle 同步生成到 app assets，避免两边手工维护重复文件
- `app` 模块通过 `core/game/launch/EngineLauncher` 将扫描结果映射到对应引擎 Activity 启动（SAF URI → 真实路径转换）
- Ren'Py 等外置 APK 引擎模块由 `core/engine/external` 统一注册、检测安装状态并按 intent 协议拉起；主 App 不维护手动启用开关，模块安装即视为可用
- Tyrano 运行环境内置本地 HTTP 服务器、Asar 归档解析与 JS 钩子脚本（`__tyrano__.js` 等），无需外部依赖即可运行网页式脚本游戏
- 原生库仅提供 `arm64-v8a` 架构

## 构建

```bash
# 编译 Debug APK
./gradlew assembleDebug --no-daemon
```

产物位于 `app/build/outputs/apk/debug/`。需要 Android SDK（本地平台 android-37）环境。

## 目录结构

```
app/
├── src/main/java/com/tyranor/next/
│   ├── core/      功能抽象层：扫描、启动、引擎模块、封面、存档、补丁、配置、授权、更新
│   ├── ui/        界面 UI 交互层：主界面、游戏页、设置页、弹窗、公共组件
│   └── theme/     Compose / Miuix 主题、色调、深浅色适配
├── src/main/assets/
│   ├── engine/        App 专属 Web 引擎注入脚本
│   └── nativeplugins/ Gradle 构建期生成的 Native 插件 zip
└── src/main/nativeplugins/ 插件 manifest 与 app-only 插件源头

engine/
├── src/main/java/          底层引擎宿主、桥接代码与运行时入口
├── src/main/cpp/           Native/JNI、SDL、引擎运行时代码
├── src/main/assets/        底层引擎资源与共享引擎脚本源头
└── src/main/nativeplugins/ 共享 Native 插件 so 源头

docs/   设计文档、逆向分析、功能计划与优化方案
```

## 许可证

本项目基于 **GNU General Public License v2.0** 发布，详见 [LICENSE](LICENSE)（GPL-2.0-only）。

- `engine/` 引擎运行时基于 Tyranor 模拟器逆向重写，上游涉及 Kirikiroid2 / ONScripter 等 GPL-2.0 项目，因此整个项目以 GPL-2.0 授权分发
- 基于本项目发布的衍生作品须遵循 GPL-2.0 条款，并随发行物提供完整源码
- Miuix 等第三方依赖按各自许可证引入

## 致谢

- **Tyranor 模拟器**：本项目引擎运行时与核心架构的逆向重写基础
- **RinneMobile**：游戏扫描识别/SAF路径映射逻辑/独立存档映射/krkrsdl3 等多个功能的参考实现
- [Miuix](https://github.com/compose-miuix-ui/miuix)：设置界面组件库
- 各引擎运行时均基于其开源许可引入
