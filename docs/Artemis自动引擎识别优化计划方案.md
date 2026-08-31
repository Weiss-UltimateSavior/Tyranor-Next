# Artemis 自动引擎识别优化计划方案

> 目标：完善 Tyranor Next 中 Artemis 引擎设置的“自动”选项，让它不再只依赖固定顺序盲试，而是根据游戏目录、`boot.ini`、`system.ini`、PFS 包表、Emote 资源等特征，优先选择更可能成功的 Artemis 引擎版本，同时保留现有旧游戏兼容性。

## 1. 背景

当前 Tyranor Next 已内置多套 Artemis 运行库，并通过应用级/单游戏设置提供“引擎版本”选择。

现有自动逻辑已经具备基础能力：

- 支持手动指定 Artemis 版本。
- 支持自动模式下读取历史成功版本。
- 支持部分 Android Artemis 新壳目录指纹，命中 `boot.ini + root.pfs` 时优先直达 TyranorNext V1。
- 支持启动早退后的兼容回退链。
- 支持缺少松散启动文件的 PFS 游戏，在启动前应用基础补丁。

但目前自动项仍然偏粗：

- `boot.ini + root.pfs` 只粗略指向 V4/TyranorNext V1，没有区分 Rev.2958 与 Rev.3288。
- 没有独立识别 Emote 游戏，导致无 Emote 的新版库可能被优先尝试。
- 没有读取 PFS 文件表来判断包内是否存在 `system.ini`、`system/first.iet`、Emote 资源、Android 启动段。
- 没有把官方 Artemis Android 文档中提到的 OBB / PAD / 下载数据 / assets 资源分发形态纳入评分。
- 对传统 Windows Artemis 目录、Android 移植目录、商业 Android 壳目录的优先级还不够清晰。

## 2. 当前 Artemis 版本矩阵

当前项目中 Artemis 版本显示名、配置值和实际库的对应关系如下：

| 显示名 | 配置值 | Activity | engineLibName | 实际 so | Rev | Emote | 定位 |
|---|---|---|---|---|---:|---|---|
| `v1（Tyranor/Rev.2762）` | `2` | `ArtemisActivityV2` | `artemis-compatible` | `libartemis-compatible.so` | 2762 | 否 | 旧 Tyranor 兼容线 |
| `v2（Tyranor/Rev.3049/emote）` | `1` | `ArtemisActivityV1` | `artemis` | `libartemis.so` | 3049 | 是 | 旧 Tyranor Emote 线 |
| `v3（Tyranor/Rev.3201/emote）` | `3` | `ArtemisActivityV3` | `artemis-compatible-v2` | `libartemis-compatible-v2.so` | 3201 | 是 | 旧 Tyranor 高版本 Emote 线 |
| `V1（TyranorNext/Rev.2958）` | `4` | `ArtemisActivityV4` | `artemis-v4` | `libartemis-v4.so` | 2958 | 否 | TyranorNext 新壳兼容线 |
| `V3（TyranorNext/Rev.3288）` | `5` | `ArtemisActivityV5` | `artemis-v5` | `libartemis-v5.so` | 3288 | 否 | TyranorNext 新商业 Android 壳线 |

注意：

- 配置值不能仅按显示名理解，因为历史兼容原因，旧值 `1/2/3/4` 已经被用户设置和单游戏覆盖使用。
- 自动逻辑应基于配置值和库能力路由，不应随意迁移已有偏好。
- Rev.2958 与 Rev.3288 均未检测到 Emote 符号，不应作为 Emote 游戏的第一优先。

## 3. 从外部资料得到的可用识别信息

### 3.1 `boot.ini` 是 Android Artemis 移植壳强特征

官方 Android 模板说明中，`boot.ini` 位于：

```text
app/src/main/assets/boot.ini
```

`boot.ini` 主要用于描述 Android 端资源分发方式。可识别字段包括：

```ini
[RESOURCE]
PLAY_ASSET_DELIVERY_NAMES = root_pfs_000,root_pfs_001,root_pfs_002
APK_EXPANSION_FILES_MAIN = 1
APK_EXPANSION_FILES_PATCH = 1
APK_EXPANSION_FILES_KEY =

[DOWNLOAD]
URL = http://server.com/path/to/root.pfs|http://server.com/path/to/root.pfs.000

[ID]
NAME = com.ies_net.artemis_test
```

可用于自动识别的含义：

- 出现 `[RESOURCE]`：高概率为 Android Artemis 壳配置。
- 出现 `APK_EXPANSION_FILES_*`：高概率为 OBB / Google Play expansion file 分发。
- 出现 `PLAY_ASSET_DELIVERY_NAMES`：高概率为 Play Asset Delivery 分发。
- 出现 `[DOWNLOAD]` + `URL`：高概率为自托管下载数据分发。
- 出现 `[ID] NAME`：旧版兼容配置，官方文档称 Rev.1425 后废弃，但仍可用于识别老 Android 壳。

### 3.2 Android 资源读取顺序可作为分发形态判断

官方 Android readme 明确说明资源读取顺序：

```text
APK Expansion Files
→ 下载数据
→ Play Asset Delivery
→ APK assets
```

对应到用户提供的游戏目录场景，可能出现几类结构：

1. 从 APK/OBB 拆出来的目录：

```text
boot.ini
main.xx.package.obb
patch.xx.package.obb
```

2. 下载完成后的 Android data 目录：

```text
root.pfs
root.pfs.000
system.ini
```

3. APK assets 直接打包游戏：

```text
boot.ini
root.pfs
```

4. PC 原始 Artemis 游戏目录：

```text
system.ini
system/first.iet
*.pfs
```

这些结构对自动引擎选择有直接价值。

### 3.3 `system.ini` 的 `[ANDROID]` 段是平台适配强特征

官方规格文档说明：

- Windows 读取 `[WINDOWS]`
- iOS 读取 `[IOS]`
- Android 读取 `[ANDROID]`
- WebAssembly 读取 `[WASM]`

因此：

- `system.ini` 中存在 `[ANDROID]`，说明该游戏已经具备 Android 平台配置。
- `system.ini` 只有 `[WINDOWS]` 或公共段，说明可能是 PC 原始资源，需要补丁或兼容链处理。
- `system.ini` 缺失但存在 `root.pfs`，说明启动文件可能在包内，需要 PFS 轻量表扫描。

可识别的关键项：

```ini
[ANDROID]
WIDTH =
HEIGHT =
BOOT =
CHARSET =
SIDECUT =
POWER_SAVING =
NO_SAVE =
```

其中：

- `BOOT` 可用于判断启动脚本路径。
- `CHARSET` 可用于后续补丁编码处理。
- `WIDTH/HEIGHT` 可用于确认配置段完整性。

### 3.4 PFS 包表可用于轻量识别

官方 PFS 文档说明：

- 根包文件通常是 `root.pfs`。
- 补丁包是 `root.pfs.000` 到 `root.pfs.999`。
- patch 包优先级高于 root 包。
- 包内文件可像普通文件一样被引擎读取。
- Android 不使用 APK Expansion Files 时，根包仍为 `root.pfs`。

因此自动识别不需要完整解包大资源，只需读取 PFS 文件表，判断是否存在关键文件：

- `system.ini`
- `system/first.iet`
- `*.iet`
- `*.lua`
- `*.mp4`
- Emote 相关资源或脚本引用
- Android 启动脚本
- patch 包数量

这能在保持扫描速度的同时显著提升自动版本选择准确率。

### 3.5 `s.engineversion` 等系统变量不适合启动前识别

`system_variables.md` 记录了：

- `s.engineversion`
- `s.minimumsupportversion`
- `s.savedataversion`
- `s.builddate`
- `s.versioncode`
- `s.versionname`

这些变量由运行中的 Artemis 引擎生成。它们适合用于启动后诊断或日志上报，但无法在启动前读取，因此不作为自动引擎选择的一手依据。

## 4. 目标能力

### 4.1 自动项行为目标

当用户选择“自动”时，应满足：

1. 已有历史成功版本时，优先使用历史版本。
2. 没有历史记录时，先快速扫描游戏目录/PFS 表，生成 Artemis 指纹。
3. 根据指纹决定初始版本。
4. 如果启动失败或早退，继续使用兼容回退链。
5. 成功版本写入历史记录，下一次直达。
6. 手动指定版本不受自动逻辑影响。
7. 单游戏覆盖优先级高于应用全局设置。

### 4.2 兼容目标

- 不破坏现有旧游戏。
- 不破坏已经记录历史成功版本的游戏。
- 不把无 Emote 库优先用于 Emote 游戏。
- 不强制把 Rev.3288 放到所有 Android Artemis 游戏前面。
- 不在扫描阶段完整解包大 PFS 或 OBB。
- 不修改用户游戏资源，除非已有“基础补丁”流程被用户允许或策略为自动。

## 5. 新增核心抽象

建议新增一个独立识别器：

```kotlin
object ArtemisEngineFingerprintDetector
```

位置建议：

```text
app/src/main/java/com/tyranor/next/core/game/launch/ArtemisEngineFingerprintDetector.kt
```

也可以放在：

```text
app/src/main/java/com/tyranor/next/core/game/scan/ArtemisFingerprint.kt
```

推荐不要塞进 `EngineLauncher.kt`，因为自动识别会越来越复杂，单文件会变成“意大利面森林”。

### 5.1 指纹数据结构

建议定义：

```kotlin
data class ArtemisGameFingerprint(
    val hasLooseBootIni: Boolean = false,
    val hasLooseSystemIni: Boolean = false,
    val hasLooseAndroidSection: Boolean = false,
    val hasLooseWindowsSection: Boolean = false,
    val hasLooseBootKey: Boolean = false,
    val hasLooseFirstIet: Boolean = false,

    val hasRootPfs: Boolean = false,
    val hasPatchPfs: Boolean = false,
    val hasAnyPfs: Boolean = false,
    val pfsEntryScanned: Boolean = false,
    val pfsHasSystemIni: Boolean = false,
    val pfsHasAndroidSection: Boolean = false,
    val pfsHasFirstIet: Boolean = false,
    val pfsHasLua: Boolean = false,
    val pfsHasIet: Boolean = false,
    val pfsHasMp4: Boolean = false,
    val pfsHasEmoteAsset: Boolean = false,

    val bootUsesApkExpansion: Boolean = false,
    val bootUsesPlayAssetDelivery: Boolean = false,
    val bootUsesDownload: Boolean = false,
    val bootUsesLegacyId: Boolean = false,
    val bootMentionsRootPfs: Boolean = false,

    val hasObbLikeFile: Boolean = false,
    val hasPf8LikeArchive: Boolean = false,

    val confidence: Int = 0,
    val reasons: List<String> = emptyList(),
)
```

### 5.2 推荐版本计划结构

建议定义：

```kotlin
data class ArtemisEnginePlan(
    val initialVersion: String,
    val fallbackVersions: List<String>,
    val reason: String,
)
```

这样 `EngineLauncher` 只消费结果，不关心具体打分细节。

## 6. 识别规则设计

### 6.1 历史成功版本优先

当前已有：

```text
artemis_engine.<pathHash>
```

建议保留，并增强兼容：

- 既识别旧的 `internal.artemis.*`
- 也识别新的 `ART_ENGINE_V1/V2/V3/V4/V5`
- 若历史值对应的库已不存在或不合法，则丢弃历史值，重新指纹识别

优先级：

```text
单游戏手动覆盖
→ 应用全局手动覆盖
→ 历史成功版本
→ 指纹自动选择
→ 默认兼容链
```

### 6.2 Emote 识别规则

Emote 是最重要的分流条件之一。

命中条件：

- 松散文件名或路径包含：
  - `emote`
  - `d3demote`
  - `iemote`
- PFS 表中存在类似路径：
  - `D3DEmote`
  - `emote`
  - `*.mtn`
  - `*.psb`
  - `motion`
- 脚本/ini 轻量文本中出现：
  - `Emote`
  - `IEmote`
  - `D3DEmote`

命中 Emote 后推荐链：

```text
v3（Tyranor/Rev.3201/emote）
→ v2（Tyranor/Rev.3049/emote）
→ v1（Tyranor/Rev.2762）
→ V1（TyranorNext/Rev.2958）
→ V3（TyranorNext/Rev.3288）
```

原因：

- Rev.3201 是当前最高旧 Emote 线。
- Rev.3049 作为次级 Emote 兼容。
- Rev.2958/3288 没有 Emote，不应先试。

### 6.3 Android 商业壳 / 新壳识别规则

强特征：

- 存在 `boot.ini`
- `boot.ini` 中出现：
  - `APK_EXPANSION_FILES_MAIN`
  - `APK_EXPANSION_FILES_PATCH`
  - `APK_EXPANSION_FILES_KEY`
  - `PLAY_ASSET_DELIVERY_NAMES`
  - `[DOWNLOAD]`
  - `URL =`
- 存在 `.obb`
- `.obb` 文件名符合：
  - `main.<version>.<package>.obb`
  - `patch.<version>.<package>.obb`
- 归档头类似 `pf8`
- 存在 `root.pfs` 或 `root.pfs.000`
- `system.ini` 有 `[ANDROID]`

推荐链：

```text
V3（TyranorNext/Rev.3288）
→ V1（TyranorNext/Rev.2958）
→ v1（Tyranor/Rev.2762）
→ v3（Tyranor/Rev.3201/emote）
→ v2（Tyranor/Rev.3049/emote）
```

原因：

- Rev.3288 来自较新的商业 Android 壳，适合新式 OBB/PAD/boot.ini 形态。
- Rev.2958 是 TyranorNext 另一条新壳线。
- 旧 Tyranor Rev.2762 无 Emote，作为传统无 Emote fallback。

### 6.4 Android assets / 转制目录识别规则

中强特征：

- 存在 `boot.ini`
- 存在 `root.pfs`
- `boot.ini` 未明显启用 OBB/PAD/download
- `system.ini` 可能在 root 目录或 PFS 内
- `system.ini` 包含 `[ANDROID]`

推荐链：

```text
V1（TyranorNext/Rev.2958）
→ V3（TyranorNext/Rev.3288）
→ v1（Tyranor/Rev.2762）
→ v3（Tyranor/Rev.3201/emote）
→ v2（Tyranor/Rev.3049/emote）
```

原因：

- 这类更像当前已经做过补丁的 Android 移植资源，不一定需要最新商业壳。
- Rev.2958 已经是当前 TyranorNext 对新壳直达的默认选择。

### 6.5 传统 PC Artemis 目录识别规则

强特征：

- 存在 `system.ini`
- 存在 `system/first.iet`
- 存在 `root.pfs` 或其他 `.pfs`
- 没有 `boot.ini`
- `system.ini` 主要是 `[WINDOWS]` 或公共段，没有 `[ANDROID]`

推荐链：

```text
v1（Tyranor/Rev.2762）
→ v2（Tyranor/Rev.3049/emote）
→ v3（Tyranor/Rev.3201/emote）
→ V1（TyranorNext/Rev.2958）
→ V3（TyranorNext/Rev.3288）
```

如果命中 Emote，则改用 Emote 链。

### 6.6 只有 PFS 的目录

特征：

- 有 `root.pfs` / `root.pfs.000`
- 没有松散 `system.ini`
- 没有松散 `system/first.iet`

处理：

1. 先进行 PFS 表轻量扫描。
2. 如果 PFS 表内能确认 `system.ini + [ANDROID]`，走 Android assets/转制链。
3. 如果 PFS 表内能确认 Emote，走 Emote 链。
4. 如果只有 `system.ini` / `system/first.iet`，走传统链。
5. 启动前仍沿用现有基础补丁确认机制。

推荐默认链：

```text
v1（Tyranor/Rev.2762）
→ v2（Tyranor/Rev.3049/emote）
→ v3（Tyranor/Rev.3201/emote）
→ V1（TyranorNext/Rev.2958）
→ V3（TyranorNext/Rev.3288）
```

原因：

- 只有 PFS 时信息不足，不宜直接跳新版。
- 补丁后再结合提取出的 `system.ini` 可以二次确认。

## 7. 自动选择总流程

```text
buildArtemisIntent()
  |
  |-- 读取单游戏覆盖
  |-- 读取应用全局设置
  |
  |-- 如果不是 auto
  |     |-- 使用手动版本
  |
  |-- 如果是 auto
        |
        |-- 读取历史成功版本
        |     |-- 合法则使用历史版本
        |
        |-- 生成 ArtemisGameFingerprint
        |
        |-- 根据 fingerprint 生成 ArtemisEnginePlan
        |
        |-- 使用 initialVersion 启动
        |
        |-- 将 fallbackVersions 编码进 Intent
              或使用 stage 兼容链
```

## 8. 回退链改造方案

当前回退链使用 `artemisFallbackStage` 数字表示：

```text
0 → V2
1 → V3
2 → V4
3 → V5
```

这个结构对固定顺序还可以，但对“按指纹生成不同顺序”不够灵活。

建议改造为：

```text
artemisFallbackVersions = "2,1,3,4,5"
artemisFallbackIndex = 0
```

含义：

- `artemisFallbackVersions` 是配置值列表。
- `artemisFallbackIndex` 表示当前使用列表中的第几个。
- 早退后读取下一个配置值，映射到对应 Activity 和 engineLibName。

优点：

- 每类游戏可以有自己的回退顺序。
- 新增版本不用反复改 stage 数字判断。
- 历史成功版本也可以写成配置值，不再混用 `internal.artemis.*`。

兼容策略：

- 第一阶段先保留旧 `artemisFallbackStage`。
- 新增 `artemisFallbackVersions` 后，Activity 优先读取新字段。
- 新字段不存在时继续走旧 stage 逻辑。

## 9. PFS 轻量扫描方案

当前 `ArtemisPfsUnpacker` 已有 PFS 读表与选择性解包能力，可以抽出或复用其中的安全逻辑。

建议新增只读扫描接口：

```kotlin
object ArtemisPfsInspector {
    fun inspect(rootPath: String, maxEntries: Int = 2000): ArtemisPfsSummary
}
```

返回：

```kotlin
data class ArtemisPfsSummary(
    val scanned: Boolean,
    val entryCount: Int,
    val hasSystemIni: Boolean,
    val systemIniPreview: String?,
    val hasFirstIet: Boolean,
    val hasLua: Boolean,
    val hasIet: Boolean,
    val hasMp4: Boolean,
    val hasEmoteAsset: Boolean,
    val archiveNames: List<String>,
)
```

扫描限制：

- 单个 PFS 最多读取表和少量文本预览。
- 不读取大资源体。
- 不写入游戏目录。
- 不处理超过上限的异常包。
- PFS 读取失败时不影响启动，只降低 confidence。

可复用安全限制：

- `MAX_ENTRY_COUNT`
- `MAX_NAME_BYTES`
- 路径归一化
- `root.pfs.000` patch 排序

## 10. 评分模型

建议不直接写一堆互斥 `if`，而是建立评分。

### 10.1 版本族

```text
legacyNoEmote = v1 Rev.2762
legacyEmote = v3 Rev.3201/emote, v2 Rev.3049/emote
nextNoEmote = V1 Rev.2958, V3 Rev.3288
```

### 10.2 指纹评分示例

| 特征 | 分数 | 倾向 |
|---|---:|---|
| PFS/松散路径命中 Emote | +100 | legacyEmote |
| `system.ini` 有 `[ANDROID]` | +40 | nextNoEmote 或 Android 链 |
| `boot.ini` 有 OBB/PAD/download | +60 | Rev.3288 |
| 存在 `.obb` | +60 | Rev.3288 |
| PFS 头为 `pf8` | +50 | Rev.3288 |
| `boot.ini + root.pfs` | +40 | Rev.2958/3288 |
| `system.ini + system/first.iet` | +50 | legacy |
| 只有 `.pfs` 无 Android 信息 | +20 | legacy |
| 有 `[WINDOWS]` 且无 `[ANDROID]` | +30 | legacy |

最终不是只取最高分，而是生成有序候选链。

## 11. 推荐候选链

### 11.1 Emote 游戏

```text
3 → 1 → 2 → 4 → 5
```

对应：

```text
v3 Rev.3201/emote
→ v2 Rev.3049/emote
→ v1 Rev.2762
→ V1 Rev.2958
→ V3 Rev.3288
```

### 11.2 新商业 Android 壳 / OBB / PAD / pf8

```text
5 → 4 → 2 → 3 → 1
```

对应：

```text
V3 Rev.3288
→ V1 Rev.2958
→ v1 Rev.2762
→ v3 Rev.3201/emote
→ v2 Rev.3049/emote
```

### 11.3 Android assets / boot.ini + root.pfs

```text
4 → 5 → 2 → 3 → 1
```

对应：

```text
V1 Rev.2958
→ V3 Rev.3288
→ v1 Rev.2762
→ v3 Rev.3201/emote
→ v2 Rev.3049/emote
```

### 11.4 传统 PC Artemis

```text
2 → 1 → 3 → 4 → 5
```

对应：

```text
v1 Rev.2762
→ v2 Rev.3049/emote
→ v3 Rev.3201/emote
→ V1 Rev.2958
→ V3 Rev.3288
```

### 11.5 信息不足的 PFS 游戏

```text
2 → 1 → 3 → 4 → 5
```

若 PFS 表扫描发现 Emote，则切换到 Emote 链。

## 12. 实施步骤

### 阶段一：抽象版本元数据

目标：把版本值、Activity、库名、Rev、Emote 能力集中维护。

新增：

```kotlin
data class ArtemisEngineVariant(
    val value: String,
    val displayName: String,
    val activityClassName: String,
    val engineLibName: String,
    val rev: Int,
    val hasEmote: Boolean,
)
```

或先用简单 `when` 封装：

```kotlin
private fun artemisActivityAndLib(version: String): Pair<Class<*>, String>
private fun artemisVersionFromInternalName(name: String): String?
private fun artemisInternalName(version: String): String
```

改动点：

- `EngineLauncher.kt`
- `ArtemisLauncherBaseActivity.java`

收益：

- 避免版本越多，`when` 越散。
- 为 fallback list 做准备。

### 阶段二：新增 Artemis 指纹扫描器

新增文件：

```text
app/src/main/java/com/tyranor/next/core/game/launch/ArtemisEngineFingerprintDetector.kt
```

职责：

- 读取松散文件结构。
- 读取 `boot.ini` 前 64KB。
- 读取 `system.ini` 前 64KB。
- 轻量读取 PFS 表。
- 输出 `ArtemisGameFingerprint`。

注意：

- `boot.ini` 多为 Shift_JIS，但字段名均为 ASCII，使用 `ISO_8859_1` 或字节级搜索即可。
- `system.ini` 可能是 Shift_JIS / UTF-8，字段名同样可先做 ASCII upper 搜索。
- SAF `content://` 路径暂不做 PFS 表扫描，避免复杂化；已有真实路径映射成功时再扫描。

### 阶段三：实现候选链生成

新增：

```kotlin
fun buildAutoPlan(path: String): ArtemisEnginePlan
```

输出：

- 初始版本
- fallback 列表
- reason 日志

日志示例：

```text
Artemis auto fingerprint path=/storage/... emote=false androidBoot=true obb=false pad=true pfs=true plan=5,4,2,3,1 reason=boot.ini uses PAD
```

### 阶段四：改造 fallback 传参

新增 Intent extra：

```text
artemisFallbackVersions
artemisFallbackIndex
```

示例：

```text
artemisFallbackVersions = "5,4,2,3,1"
artemisFallbackIndex = 0
```

`ArtemisLauncherBaseActivity` 早退时：

1. 读取列表。
2. index + 1。
3. 映射下一版本对应 Activity 和 `engineLibName`。
4. 写入历史成功候选。
5. 启动下一 Activity。

兼容：

- 若没有 `artemisFallbackVersions`，继续使用旧 `artemisFallbackStage`。

### 阶段五：历史记录写入优化

当前早退前就写入下一包名，语义更像“尝试中版本”，不是“成功版本”。

建议保守优化：

- 继续保留旧 key，避免破坏现有行为。
- 新增 key：

```text
artemis_engine_success.<pathHash>
```

写入时机：

- 若 Activity 存活超过 `EARLY_EXIT_WINDOW_MS`，说明没有立刻失败，可在 `onDestroy` 时记录当前版本为成功候选。
- 如果用户主动退出，也可以记录当前版本。

这样下次启动更接近“历史成功版本”，不会被一次失败重试污染。

### 阶段六：扫描器可选缓存

为了避免每次点击游戏都重复扫 PFS，可以缓存指纹。

缓存 key：

```text
artemis_fingerprint.<pathHash>
```

缓存内容：

- rootPath
- root lastModified
- root.pfs lastModified / size
- root.pfs.000 lastModified / size
- fingerprint JSON

失效条件：

- 目录 mtime 改变。
- root.pfs 大小或 mtime 改变。
- patch PFS 数量改变。
- app 版本升级导致 schema 变化。

第一版可以不做缓存，先控制扫描上限。

## 13. 验证用例

### 13.1 旧 Tyranor Rev.2762 普通游戏

目录：

```text
system.ini
system/first.iet
root.pfs
```

期望：

```text
v1 Rev.2762 → v2 Rev.3049/emote → v3 Rev.3201/emote → V1 Rev.2958 → V3 Rev.3288
```

### 13.2 旧 Emote 游戏

目录或 PFS 表：

```text
emote/
D3DEmote
*.mtn
```

期望：

```text
v3 Rev.3201/emote → v2 Rev.3049/emote → v1 Rev.2762 → V1 Rev.2958 → V3 Rev.3288
```

### 13.3 Android assets 形态

目录：

```text
boot.ini
root.pfs
```

`boot.ini` 没有 OBB/PAD/download 明确启用项。

期望：

```text
V1 Rev.2958 → V3 Rev.3288 → v1 Rev.2762 → v3 Rev.3201/emote → v2 Rev.3049/emote
```

### 13.4 新商业 Android OBB/PAD 形态

目录：

```text
boot.ini
main.16.package.obb
```

或：

```ini
[RESOURCE]
APK_EXPANSION_FILES_MAIN = 16
```

期望：

```text
V3 Rev.3288 → V1 Rev.2958 → v1 Rev.2762 → v3 Rev.3201/emote → v2 Rev.3049/emote
```

### 13.5 只有 PFS，没有 system.ini

目录：

```text
root.pfs
root.pfs.000
```

期望：

- 启动前仍触发已有基础补丁确认。
- PFS 表命中 Emote 则走 Emote 链。
- PFS 表命中 `[ANDROID]` 则走 Android 链。
- 信息不足则走传统链。

### 13.6 手动指定版本

用户选择任意版本：

```text
v1/v2/v3/V1/V3
```

期望：

- 不执行自动指纹分流。
- 不执行自动 fallback，除非当前项目明确保留手动 fallback。
- 启动失败直接返回，不悄悄改用户选择。

## 14. 日志与可观测性

建议新增日志 tag：

```text
ArtemisAuto
```

关键日志：

```text
fingerprint summary
selected initial version
fallback chain
history hit/miss
pfs scan skipped reason
```

示例：

```text
ArtemisAuto I path=/storage/... history=none fingerprint={boot=true, android=true, emote=false, pad=true} chain=5,4,2,3,1
ArtemisAuto W pfs scan skipped: content uri path not resolved
ArtemisAuto I history hit version=3 source=success pathHash=...
```

这样远程测试时只要截 logcat，就能知道自动项为什么选某个版本。

## 15. 风险点

### 15.1 PFS 变体风险

风险：

- 不同 Artemis PFS 版本可能表结构不同。
- 部分商业包可能混淆或加密。
- OBB 可能不是普通 PFS，而是 `pf8` 或其他变体。

缓解：

- PFS 扫描失败不阻断启动。
- 只降低 confidence。
- 保留 fallback 链。

### 15.2 误判 Emote

风险：

- 文件名里偶然包含 `emote`。

缓解：

- 单个弱命中只加低分。
- `D3DEmote`、`IEmote`、`.mtn`、脚本引用多条件命中才强判定。

### 15.3 Rev.3288 覆盖面未知

风险：

- Rev.3288 是新商业壳线，但未必兼容所有 Android boot.ini 游戏。

缓解：

- 只在 OBB/PAD/download/pf8 等强新壳特征命中时优先 Rev.3288。
- 普通 `boot.ini + root.pfs` 仍优先 Rev.2958。

### 15.4 历史记录污染

风险：

- 当前早退 retry 逻辑可能把“下一次要试的版本”写成历史值。

缓解：

- 新增 success key。
- 旧 key 只作兼容读取。
- 记录成功版本时需要通过存活时长或用户主动退出判断。

## 16. 推荐落地顺序

### 第一批：低风险，先提升判断质量

1. 新增 `ArtemisEngineFingerprintDetector`。
2. 实现松散文件 `boot.ini/system.ini/root.pfs` 指纹。
3. 实现 Emote 文件名/路径检测。
4. 生成候选链，但暂时仍使用现有 stage fallback。
5. 增加详细日志。

### 第二批：中风险，增强 PFS 表扫描

1. 从 `ArtemisPfsUnpacker` 抽出只读 PFS 表扫描逻辑。
2. 识别 PFS 内 `system.ini/system/first.iet/Emote`。
3. 根据 PFS 内 `system.ini` 的 `[ANDROID]` 二次调整候选链。
4. 限制扫描上限，避免慢扫描。

### 第三批：中高风险，改造动态 fallback 链

1. 新增 `artemisFallbackVersions`。
2. Java Activity 支持按配置值列表回退。
3. 保留旧 `artemisFallbackStage` 兼容。
4. 增加成功版本 key。

### 第四批：验证和微调

1. 用已知旧游戏验证 Rev.2762/3049/3201。
2. 用 Tyn/Rev.2958 游戏验证 TyranorNext V1。
3. 用 ar-test/Rev.3288 类资源验证 TyranorNext V3。
4. 用 Emote 游戏验证不误走无 Emote 库。
5. 用只有 PFS 的游戏验证补丁确认和 PFS 表扫描。

## 17. 预期最终效果

完成后，Artemis 自动项会从：

```text
固定顺序盲试 + 少量 boot.ini 直达
```

升级为：

```text
历史成功版本
→ 资源指纹识别
→ Emote / Android 新壳 / 传统 PC / PFS-only 分流
→ 动态 fallback 链
→ 成功版本记忆
```

这样能明显减少首次启动试错次数，也能避免 Emote 游戏误用无 Emote 版本，同时给 Rev.3288 这类新商业 Android 壳一个合理的自动入口。

## 18. 实施状态

已完成：

1. 新增 `ArtemisEngineFingerprintDetector`，集中负责 Artemis 自动项的目录、`boot.ini`、`system.ini`、PFS 表、OBB/PAD/download、`pf8` 与 Emote 资源指纹识别。
2. 自动项优先读取新的成功版本记录 `artemis_engine_success.<pathHash>`，再兼容旧 key `artemis_engine.<pathHash>`；没有历史记录时才按指纹生成候选链。
3. `EngineLauncher` 已改为向 Artemis Activity 传入动态候选链：

```text
artemisFallbackVersions = "5,4,2,3,1"
artemisFallbackIndex = 0
artemisCurrentVersion = "5"
```

4. `ArtemisLauncherBaseActivity` 已支持动态回退链；旧 `artemisFallbackStage` 仍保留兼容。
5. 成功版本记录改为“未触发早退回退后再记录”，避免把失败尝试污染成历史成功版本。
6. `EngineScanner` 已补充识别 `root.pfs.000` 等 patch PFS，以及 `boot.ini + .obb` 这类 Android 商业壳目录。
7. Debug 打包已验证 Artemis 插件包包含五个运行库：

```text
libartemis.so
libartemis-compatible.so
libartemis-compatible-v2.so
libartemis-v4.so
libartemis-v5.so
```

当前没有落地扫描缓存；第一版通过扫描深度、扫描数量、PFS 表项数量和预览读取大小限制控制耗时。
