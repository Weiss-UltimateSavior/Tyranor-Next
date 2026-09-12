# 外置 RPGM 模块配置接入引擎设置清单计划

> 来源：JoiPlay 逆向分析（`/Users/weiss/Desktop/decompiled/joiplay_jadx`）中 RPG Maker 外置 APK 模块（`cyou.joiplay.runtime.rpgmaker`）的配置链路。
> 目标：把 JoiPlay 对 RGSS/mkxp 系外置模块的设置能力，落到 TyranorNext 现有「应用 / 引擎 / 单游戏」三级设置体系中，通过启动 Intent 的 `settings` extra 下发给外置 RPMG 模块。
> 范围：`EngineType.RPGMAKER`（`rpgmxp` / `rpgmvx` / `rpgmvxace` / `mkxp-z`）。RPG Maker MV/MZ 继续走内置 Web 宿主，不纳入本计划。
>
> 实施状态：**已完成**（2026-09-12）。键集按 RPGM 插件反编译 `cyou.joiplay.commons.parser.MKXPConfigurationParser`（`/Users/weiss/Desktop/decompiled/rpgm_jadx`）实际解析字段核对后落地：
> - 移除插件不解析的 `useMiniz` / `updateCoreScript` / `animateAutotiles`（`animateAutotiles` 被插件启动时强制置 true）；
> - 补齐插件实际解析的 `verticalScreenAlign` / `fastPathEnum` / `useCJKFont`；
> - `cheats` 改发到 `app` 节（插件只从 `app.cheats` 读取）；
> - `configuration.json` 改为与生效 `useRuby18` 联动（插件 `loadFromFile` 在 intent `settings` 之后加载，文件优先，旧值会覆盖用户意图）。

---

## 1. 背景

JoiPlay 的做法（见 `SettingsFragment`、`utilities/v.java:123`、`SettingsFactory.java:116`）：

1. 全局与单游戏共用同一个设置页，按 `rpg` 节维护约 20 个键；
2. 启动时把整包 `Settings` 序列化为 JSON，通过 `settings` extra 下发给外置 RPGM APK；
3. 另有 RTP 导入/清理、`configuration.json` 兜底（仅 XP 需要 `useRuby18`）、模块安装状态/下载管理等外围配置。

TyranorNext 当前状态：

- 已有三级设置框架（`EngineSettingsStore` / `PerGameSettingsStore` / `EngineSettingsResolver` + `EffectiveEngineSettings` + `ResolvedEngineSettings`）；
- 已有外置模块注册与启动（`core/engine/external/RpgMakerExternalEngineModule.kt`），但 `settings` JSON 仅硬编码 XP 的 `useRuby18`（`buildSettingsJson`，第 93 行）；
- `RPG_MAKER` 引擎设置页目前只有 MV/MZ 的 6 项（`SettingsScreen.kt:1018`），单游戏页只有一段说明文字（`PerGameSettingsScreen.kt:399`）。

---

## 2. JoiPlay 功能盘点与现状对照

| # | JoiPlay 能力 | JoiPlay 实现 | TyranorNext 现状 | 缺口 |
| --- | --- | --- | --- | --- |
| 1 | `settings` 全量下发 | `v.d` 序列化整个 `Settings` 到 Intent | 仅硬编码 XP `useRuby18`（`RpgMakerExternalEngineModule.kt:93`） | 缺 |
| 2 | RPG 全局/单游戏共用设置页 | `SettingsFragment`（`rpgmSetLay`） | 三级设置框架已有，但 RPGMAKER 页无 RGSS 项 | 缺 UI |
| 3 | `useRuby18`（RGSS1 用 Ruby 1.8） | 默认 `true`，`libmkxp18.so` | 已接入：全局/单游戏开关 + XP 默认 true | 完成 |
| 4 | `useMiniz`（Java zlib 替代） | `javaZlibSwitch` | 插件 `MKXPConfigurationParser` 不解析 | 不纳入（已核实） |
| 5 | `debug` | 默认 `false` | 已接入全局/单游戏（高级区） | 完成 |
| 6 | `smoothScaling` | 默认 `true` | 已接入全局/单游戏 | 完成 |
| 7 | `vsync` | 默认 `false` | 已接入全局/单游戏 | 完成 |
| 8 | `frameSkip` | 默认 `false` | 已接入全局/单游戏 | 完成 |
| 9 | `solidFonts` | 默认 `false` | 已接入全局/单游戏 | 完成 |
| 10 | `pathCache` / `prebuiltPathCache` | 默认 `false` / `true` | 已接入全局/单游戏（高级区） | 完成 |
| 11 | `copyText` | 默认 `false` | 已接入全局/单游戏（高级区） | 完成 |
| 12 | `cheats` | 默认 `true` | 已接入；按插件解析位置改发 `app.cheats` | 完成 |
| 13 | `customFont` | 默认空串，字体文件路径 | 已接入：导入共享目录 `/sdcard/JoiPlay/fonts`（插件可读） | 完成 |
| 14 | `windowSize` | 9 档：512x384…1920x1080 | 已接入全局/单游戏 | 完成 |
| 15 | `speedUp` | 1..9，默认 `1` | 已接入全局/单游戏 | 完成 |
| 16 | `fontScale` | 0.25..2.00，默认 `0.75` | 已接入全局/单游戏 | 完成 |
| 17 | `animateAutotiles` | 默认 `false` | 插件启动时强制 true，不解析该键 | 不纳入（已核实） |
| 18 | `enablePostloadScripts` | 与 postloadScripts extras 配合 | 已接入全局/单游戏（高级区） | 完成 |
| 19 | `updateCoreScript` | 无默认值（按需开启） | 属 MV/MZ Web 路径，RPGM 插件不解析 | 不纳入（已核实） |
| 20 | Web 系键 `usePIXI6` / `useWebGL2` / `downscaleBitmaps` / `fastPathEnum` / `verticalScreenAlign` | 插件解析 `fastPathEnum` / `verticalScreenAlign`；其余属 MV/MZ | `fastPathEnum`（高级区）与 `verticalScreenAlign` 已接入；`usePIXI6`/`useWebGL2`/`downscaleBitmaps` 走 MV/MZ 入口 | 完成 |
| 21 | RTP 导入/清理（RPGXP/RPGVX/RPGVXACE） | `i0`：InnoExtract 解 `xp_rtp104e.exe` / zip 到 `/sdcard/JoiPlay/RTP/<NAME>/` | 已接入：`RpgMakerRuntimeEnvironment` 支持 zip/目录导入、清理与状态检测 | 完成 |
| 22 | `configuration.json`（扁平 `useRuby18`，仅 XP） | 双保险写游戏目录 / `JoiPlay/games/<id>/` | 已有并改为与生效 `useRuby18` 联动（JSON 文件仅更新该键） | 完成 |
| 23 | 模块安装状态/下载引导 | `runtimes.json` + RuntimeFragment | 引擎设置页卡片已展示状态与下载入口（引擎总页原有状态保留） | 完成 |
| 24 | 模块详情 `assets/html/info.html` / `license.html` | 运行时详情弹窗读取 | 无 | 可选 P2，未实施 |
| 25 | 模块 metadata 自动发现（`cyou.joiplay.runtime.types/version`） | `RuntimeManager.a()` 扫包 + 比版本 | 固定单包名 + 固定版本号，无候选选择 | 可选 P2，未实施 |
| 26 | `preloadScripts` / `postloadScripts`（pokefix 等） | 由 `essentials` 生成 extras | 无脚本注入机制 | 暂缓 |

不复刻项：JoiPlay 的「better runtime available」提示只在 Ren'Py 分支触发（`v.d`），RPGM 无此逻辑。

---

## 3. 目标设置模型

沿用现有三级设置链路，新增 RPGM 一组键：

```text
EngineSettingsStore(全局默认)
  + PerGameSettingsStore(单游戏覆盖, null=跟随全局)
    → EffectiveEngineSettings 合并
      → ResolvedEngineSettings(rpg* 字段)
        → EngineLauncher.launchInternal 构造 ExternalEngineLaunchRequest(携带 settings)
          → RpgMakerExternalEngineModule.buildSettingsJson(settings)
            → Intent extra "settings" (嵌套 JSON)
            → 同步 configuration.json (扁平 JSON, 仅 XP)
```

关键改造点：

- [x] `ExternalEngineLaunchRequest` 增加 `resolvedSettings: ResolvedEngineSettings?`（当前只有 game/gameDirectoryPath/launchTarget，`EngineLauncher.kt:123` 已拿到 `settings` 但没传）。
- [x] `RpgMakerExternalEngineModule.buildSettingsJson` 从「按类型硬编码」改为「消费 ResolvedEngineSettings」，用 `JSONObject` 组装。
- [x] XP 兼容规则：`useRuby18` 未覆盖时强制 `true`；用户显式关闭时下发 `false` 并同步 `configuration.json`。

---

## 4. 键位与默认值总表

### 4.1 全局键（EngineSettingsStore，建议命名）

| 引擎设置项 | settings JSON 键 | 默认值（对齐 JoiPlay） | 全局键名 | UI 控件 |
| --- | --- | --- | --- | --- |
| Ruby 1.8 运行时 | `useRuby18` | `true` | `rpg_use_ruby18` | Switch |
| 调试模式 | `debug` | `false` | `rpg_debug` | Switch（高级区） |
| 平滑缩放 | `smoothScaling` | `true` | `rpg_smooth_scaling` | Switch |
| 垂直同步 | `vsync` | `false` | `rpg_vsync` | Switch |
| 跳帧 | `frameSkip` | `false` | `rpg_frame_skip` | Switch |
| 实心字体 | `solidFonts` | `false` | `rpg_solid_fonts` | Switch |
| 路径缓存 | `pathCache` | `false` | `rpg_path_cache` | Switch（高级区） |
| 预构建路径缓存 | `prebuiltPathCache` | `true` | `rpg_prebuilt_path_cache` | Switch（高级区） |
| 快速路径枚举 | `fastPathEnum` | `true` | `rpg_fast_path_enum` | Switch（高级区） |
| 复制文本 | `copyText` | `false` | `rpg_copy_text` | Switch（高级区） |
| 金手指 | `cheats`（发到 `app` 节） | `true` | `rpg_cheats` | Switch（高级区） |
| 使用 CJK 字体 | `useCJKFont` | `false` | `rpg_use_cjk_font` | Switch（高级区） |
| 自定义字体 | `customFont` | `""` | `rpg_custom_font` | 字体选择（共享目录导入） |
| 窗口尺寸 | `windowSize` | `640x480` | `rpg_window_size` | 下拉（9 档） |
| 加速倍率 | `speedUp` | `1` | `rpg_speed_up` | 下拉（1..9） |
| 字体缩放 | `fontScale` | `0.75` | `rpg_font_scale` | 下拉（0.25..2.00，步进 0.25） |
| 竖屏对齐 | `verticalScreenAlign` | `top-center` | `rpg_vertical_screen_align` | 下拉（top / top-center / center） |
| 脚本后置注入 | `enablePostloadScripts` | `false` | `rpg_enable_postload_scripts` | Switch（高级区） |

不纳入：`useMiniz` / `updateCoreScript`（插件不解析）、`animateAutotiles`（插件强制 true）。

取值集合（对齐 JoiPlay `utilities/f.java` 与插件 `MKXPConfiguration`）：

```text
windowSize:          512x384 / 512x768 / 544x416 / 640x480 / 800x600 / 1024x768 / 1280x720 / 1280x960 / 1920x1080
speedUp:             1..9
fontScale:           0.25 / 0.50 / 0.75 / 1.00 / 1.25 / 1.50 / 1.75 / 2.00
verticalScreenAlign: top / top-center / center
```

### 4.2 单游戏覆盖键（PerGameSettingsStore）

与全局一一对应，`F_` 前缀；布尔用 `getBool/setBool`，字符串用 `getStr/setStr` 且经 `toRpgMakerOverride` + `mergeRpgMaker` 白名单校验：

- [x] `F_RPG_USE_RUBY18`、`F_RPG_DEBUG`、`F_RPG_SMOOTH_SCALING`、`F_RPG_VSYNC`、`F_RPG_FRAME_SKIP`、`F_RPG_SOLID_FONTS`
- [x] `F_RPG_PATH_CACHE`、`F_RPG_PREBUILT_PATH_CACHE`、`F_RPG_FAST_PATH_ENUM`、`F_RPG_COPY_TEXT`、`F_RPG_CHEATS`、`F_RPG_USE_CJK_FONT`
- [x] `F_RPG_ENABLE_POSTLOAD_SCRIPTS`
- [x] `F_RPG_CUSTOM_FONT`、`F_RPG_VERTICAL_SCREEN_ALIGN`、`F_RPG_WINDOW_SIZE`、`F_RPG_SPEED_UP`、`F_RPG_FONT_SCALE`

### 4.3 不纳入的 JoiPlay 键

`useMiniz`、`updateCoreScript`、`animateAutotiles` 已核实不被 RPGM 插件解析（见文件头实施状态）；
`usePIXI6`、`useWebGL2`、`downscaleBitmaps` 属 MV/MZ Web 运行时，继续走 MV/MZ 入口。

---

## 5. settings JSON 契约

外置模块 `MKXPConfigurationParser.parse(String)` 读取的是嵌套格式（值带类型包装），不能与 `configuration.json` 的扁平格式混用；`cheats` 只从 `app` 节读取：

```json
{
  "app": {
    "cheats": { "boolean": true }
  },
  "rpg": {
    "useRuby18": { "boolean": true },
    "debug": { "boolean": false },
    "smoothScaling": { "boolean": true },
    "vsync": { "boolean": false },
    "frameSkip": { "boolean": false },
    "solidFonts": { "boolean": false },
    "pathCache": { "boolean": false },
    "prebuiltPathCache": { "boolean": true },
    "fastPathEnum": { "boolean": true },
    "copyText": { "boolean": false },
    "useCJKFont": { "boolean": false },
    "enablePostloadScripts": { "boolean": false },
    "customFont": { "string": "" },
    "verticalScreenAlign": { "string": "top-center" },
    "windowSize": { "string": "640x480" },
    "speedUp": { "string": "1" },
    "fontScale": { "string": "0.75" }
  }
}
```

- [x] 用 `org.json.JSONObject` 构造，废弃手写字符串拼接（现状 `buildSettingsJson` 为常量串）。
- [x] 保持 `RPGXP` 强制回退：解析结果缺 `useRuby18` 时以 `true` 下发。
- [x] `configuration.json`（扁平，仅 XP）与开关联动：`{"useRuby18":true|false}`；已有 JSON 文件仅更新该键（保留用户其余键），非 JSON 文件不覆盖。
- [x] 单测覆盖 JSON 结构（字段名、类型包装、XP 默认、显式关闭不回退）。

---

## 6. 引擎设置 UI 清单

### 6.1 全局引擎设置页（`SettingsScreen.kt` 的 `EngineSettingsKind.RPG_MAKER`）

- [x] 拆分为两张卡片：
  - “RPG Maker MV/MZ”（现状保留：外链资源、Scoped 存档、修改器、旧渲染器、MV/MZ 版本）
  - “RPG Maker RGSS 外置模块”（新增 `RpgMakerSettingsCard.kt`）
- [x] 模块状态行：显示 `RpgMakerExternalEngineModule` 安装状态（复用 `ExternalEngineLauncher.isPackageInstalled`），未安装给下载入口。
- [x] 渲染/性能区：`smoothScaling`、`vsync`、`frameSkip`、`solidFonts`；`pathCache`、`prebuiltPathCache`、`fastPathEnum` 归入高级区。
- [x] 兼容性区：`useRuby18`（默认开）。
- [x] 显示区：`windowSize`、`speedUp`、`fontScale`、`verticalScreenAlign`、`customFont`；`copyText`、`cheats`、`useCJKFont`、`debug`、`enablePostloadScripts` 归入高级区。
- [x] RTP 管理区：三类 RTP 状态 + 导入/清理入口（见第 7 节）。
- [x] “恢复默认”入口（对齐 JoiPlay `resetSettingsButton`），仅重置 rpg 节。
- [x] 高级项默认收起，避免设置页一次性铺 18 行。

### 6.2 单游戏设置页（`PerGameSettingsScreen.kt` 的 `EngineType.RPGMAKER` 分支）

- [x] 在现有说明文字下方增加覆盖行，语义统一为「未覆盖跟随全局」：
  - `OverrideSwitch`：useRuby18、debug、smoothScaling、vsync、frameSkip、solidFonts、pathCache、prebuiltPathCache、fastPathEnum、copyText、cheats、useCJKFont、enablePostloadScripts；
  - `OverrideChoice`：windowSize、speedUp、fontScale、verticalScreenAlign；
  - `customFont` 用 `FontPreference` 三态（跟随全局 / 默认字体 / 选择文件）。
- [x] `customFont` 选择后回填文件名摘要；导入落在共享目录保证外置插件可读。
- [x] 三语言文案：zh/ja/en `strings.xml` 同步新增，`tools/check-hardcoded-ui-strings.py` 通过。

### 6.3 文案键

实际新增 50 个 `engine_settings_rpgm_*` 键（含分组标题、RTP 状态/动作、恢复默认），三套 `strings.xml` 键集一致。

---

## 7. RTP 管理清单

JoiPlay 提供 RPGXP/RPGVX/RPGVXACE 三类 RTP 的导入与清理，TyranorNext 目前只有目录/`sf.sf2` 兜底；已实现 `core/engine/external/RpgMakerRuntimeEnvironment.kt`。

- [x] 补充状态检测：`/sdcard/JoiPlay/RTP/<RPGXP|RPGVX|RPGVXACE>/app` 是否存在且非空。
- [x] 导入入口（全局设置）：SAF 选择 RTP zip/目录 → 解包到对应目录；兼容官方包内多一层根目录（自动拍平）；`.exe` 暂不支持，UI 提示从官网获取 zip/目录并链接来源。
- [x] 清理入口：二次确认后删除对应 RTP 目录（仅限 `app` 层级，避免误删用户目录）。
- [x] `mkxp-z` 子类型沿用现有 `mkxp-z` RTP 目录名，不单独出 UI。
- [x] 导入/清理失败仅 Toast 提示，不阻断启动路径（与 `ensureRtpEnvironment` 的非致命策略一致）。

---

## 8. 模块版本/发现（可选 P2）

- [ ] `RpgMakerExternalEngineModule` 详情弹窗展示已安装版本（`PackageInfo.versionName`）与 `external_rpgm_module_name` 对照。
- [ ] 若要支持多候选模块/版本，引入 JoiPlay 式 metadata 扫描（`cyou.joiplay.runtime.types` / `cyou.joiplay.runtime.version`）与版本比较；当前单包名 + 固定版本可先不做。
- [ ] `assets/html/info.html` / `license.html` 详情读取为可选项，无资源时隐藏。

---

## 9. 分阶段实施清单

### 阶段 0：设置模型与下发协议

- [x] `EngineSettingsStore.kt`：新增第 4.1 节全局键、`getter/setter`、默认值、白名单集合、`resetRpgMaker`。
- [x] `PerGameSettingsStore.kt`：新增第 4.2 节覆盖键与 `toRpgMakerOverride`。
- [x] `EffectiveEngineSettings.kt`：新增 `RpgMakerOverride` 数据类与 `mergeRpgMaker(global, override)`（风格对齐 `mergeOns`）。
- [x] `EngineSettingsResolver.kt`：`resolve()` 产出 `rpg` 字段并加入 `ResolvedEngineSettings`。
- [x] `ExternalEngineLaunchRequest.kt`：增加 `resolvedSettings: ResolvedEngineSettings? = null`。
- [x] `EngineLauncher.kt`：构造请求时携带 `settings`。
- [x] `RpgMakerExternalEngineModule.kt`：`buildSettingsJson` 改为消费 `resolvedSettings`，用 `JSONObject` 组装；`syncGameConfiguration` 与 `useRuby18` 生效值联动。
- [x] 纯 JVM 单测：合并规则、JSON 结构、XP 默认回退。

### 阶段 1：全局引擎设置 UI

- [x] `SettingsScreen.kt` RPG_MAKER 分支拆卡 + `RpgMakerSettingsCard.kt` RGSS 卡片与全部控件。
- [x] `EngineSettingsText.kt`：新增 `rpgWindowSizeOptions()` / `rpgSpeedUpOptions()` / `rpgFontScaleOptions()` / `rpgVerticalAlignOptions()` 及三语映射函数。
- [x] `customFont` 选择：新增共享目录导入 `RpgMakerRuntimeEnvironment.importCustomFont`（App 私有目录外置插件不可读，不复用 `FontImport`）。
- [x] RTP 状态/导入/清理入口。
- [x] 恢复默认动作（`resetRpgMaker` 语义，本地状态重置 + 顶部保存落盘）。

### 阶段 2：单游戏覆盖 UI

- [x] `PerGameSettingsScreen.kt` RPGMAKER 分支新增 18 项覆盖行。
- [x] 覆盖保存/清除逻辑接入现有 `save()` 流程（null 删除覆盖键）。

### 阶段 3：文案与测试

- [x] zh/ja/en 三套 `strings.xml` 同步。
- [x] `tools/check-hardcoded-ui-strings.py` 通过。
- [ ] 实机验收（见第 10 节；需设备与 RPGM 插件/样本，待执行）。

### 阶段 4（可选）：模块版本/详情

- [ ] 版本展示与下载引导增强。
- [ ] metadata 扫描与多候选（暂缓，确认需求后再排）。

---

## 10. 测试与验收清单

### 10.1 单元测试

- [x] `EffectiveEngineSettingsTest#mergeRpgMaker*`：单游戏覆盖优先、null 跟随全局、非法字符串回退、显式空串字体。
- [x] `RpgMakerExternalEngineModuleTest`：
  - XP 未覆盖 → `useRuby18=true`；
  - XP 显式关闭 → `false`；
  - 非 XP 子类型不强制 useRuby18；
  - 全部布尔/字符串键名与类型包装正确；`app.cheats` 位置正确。
- [x] `RpgMakerSettingsKeysTest`：覆盖键常量、默认值、白名单归一、`toRpgMakerOverride` 解析。
- [ ] `configuration.json` 文件写入/更新为私有方法（依赖 `Environment`），由实机验收覆盖。

### 10.2 构建验证

```bash
git diff --check
./gradlew testDebugUnitTest --no-daemon
./gradlew assembleDebug --no-daemon
python3 tools/check-hardcoded-ui-strings.py
```

以上命令在实施提交前已全部通过（`:app:testDebugUnitTest`、`:app:assembleDebug`、硬编码 UI 字符串检查）。

### 10.3 实机验收

1. 未安装 RPGM 模块：引擎设置页显示未安装与下载入口。
2. 安装模块后：设置页显示已安装；修改任一开关重进页面值保留。
3. XP 样本：默认 `useRuby18=true` 可启动；全局关闭后启动不再加载 1.8 运行时（验证 `configuration.json` 同步为 `false`）。
4. 单游戏覆盖：仅对单个游戏关闭 `useRuby18`，其余游戏仍跟随全局。
5. `windowSize` / `speedUp` / `fontScale` / `customFont` 修改后插件行为变化可感知。
6. RTP 导入后游戏不再报缺失；清理后状态回退。
7. 删除 `settings.json` 覆盖后回退全局（三级语义正确）。

---

## 11. 风险与决策点

| 风险 | 说明 | 缓解 |
| --- | --- | --- |
| 插件实际解析键未证实 | 已对 `rpgm_jadx` 反编译的 `MKXPConfigurationParser` 逐键核对：`useMiniz` / `updateCoreScript` / `animateAutotiles` 不解析已移除，`verticalScreenAlign` / `fastPathEnum` / `useCJKFont` 已补齐 | 已解除；后续插件升级需重新核对 parser |
| JSON 结构不一致 | 嵌套 `{"boolean": ...}` 与扁平 `configuration.json` 混用会静默失效 | builder 单测 + `configuration.json` 同值联动 |
| XP 默认回退被误关 | 关闭 `useRuby18` 后 RGSS1 脚本可能语法报错 | 默认 `true`；`configuration.json` 同步生效值 |
| 设置项过多 | 设置页膨胀、单游戏覆盖成本高 | 高级项默认收起，卡片分区 |
| 手工 JSON 拼接 | 现状常量拼接不可维护 | 已统一 `JSONObject` + 单测 |
| RTP 导入来源与格式 | `.exe` 需要 InnoExtract 能力，本期不支持 | zip/目录导入 + 拍平单层根目录；提示官网来源 |

---

## 12. 完成标准

- [x] `RPG_MAKER` 全局设置页含 RGSS 外置模块完整选项与 RTP 管理。
- [x] 每个选项都可在单游戏页覆盖，且遵循「null=跟随全局」。
- [x] `settings` extra 按嵌套协议下发，`configuration.json` 仅在 XP 下同步。
- [x] 三语言一致、单测通过、`assembleDebug` 通过。
- [x] 不改动 MV/MZ 既有设置入口与外置模块安装状态链路。
- [ ] 实机验收（第 10.3 节）待连接设备后执行。
