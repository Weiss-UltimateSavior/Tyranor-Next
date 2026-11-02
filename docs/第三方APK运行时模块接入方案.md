# 第三方 APK 运行时模块接入方案（计划）

> 范围：第三方用户自行安装的 APK 运行时模块的发现、扫描识别、游戏绑定、启动与卸载降级。
> 实施状态：**代码已实现**（2026-10-30）；`assembleDebug`、JVM 回归与字符串检查通过；`app`/`engine` lint 仍有仓库既有报错，设备/真机模块验收尚未执行。本文中的计划清单保留，已完成的代码项勾选，未执行的验收项明确留空。
> 基线：`main` @ `a3e01362`。
> 关联文档：[第三方APK运行时模块接入指南.md](第三方APK运行时模块接入指南.md)（面向模块开发者）、[外置APK引擎模块接入方案.md](外置APK引擎模块接入方案.md)、[外置主机模拟器跳转接入方案.md](外置主机模拟器跳转接入方案.md)、[SPEC.md](../SPEC.md)、[CONTEXT.md](../CONTEXT.md)。

---

## 1. 背景与目标

### 1.1 现状

TyranorNext 目前已有三套“把游戏交给外部应用”的机制：

| 机制 | 发现方式 | 启动载荷 | 是否参与扫描 |
| --- | --- | --- | --- |
| 外置 APK 引擎模块（JoiPlay） | 固定包名 + 静态注册表 `core/engine/external` | `game` / `settings` JSON、`rootUri`、`launchTarget` | 是，按内置引擎特征识别后绑定子运行时 |
| 外置模拟器跳转 | 固定包名 + 静态注册表 `ExternalEmulatorRegistry` | ROM URI 或 Winlator 目录 + exe | 部分（PSP/Switch ROM 参与扫描；PC 手动添加） |
| 手动添加安卓游戏 | `queryIntentActivities(MAIN/LAUNCHER)` | 包名直接跳转 | 否，手动入库 |

缺口：

- 用户安装一个新的 APK 运行时后，主 App 无法自动发现它。
- 没有用户自定义的扫描特征，主 App 不知道某个目录属于哪个第三方运行时。
- 模块卸载后，已入库游戏没有明确的降级规则。
- 现有 `ExternalEngineModule` 是固定包名、固定协议的注册表；`ExternalEmulatorRegistry` 是固定模拟器目标；两者都不适合承载任意第三方 APK。

### 1.2 目标

1. **动态发现**：模块包名以 `com.tyranor.runtime.` 开头，并声明固定 action；主 App 自动发现，不维护白名单。
2. **独立体系**：不复用 `core/engine/external` 的 JoiPlay 体系与模拟器体系，新建独立功能模块。
3. **文件夹路径启动**：主 App 通过显式组件 Intent 把游戏文件夹的绝对路径交给模块。
4. **扫描特征只由模块声明**：自动扫描只使用模块声明的 `extensions` / `markers`；主 App 不猜、不内置第三方特征库。没有 `scan` 描述的模块不参与自动扫描。
5. **卸载降级**：模块卸载后，绑定到它的游戏降级为 `EngineType.UNKNOWN`，保留最后已知包名用于重扫合并；用户重新扫描后按新识别结果入库。不做“模块未安装”状态机，不做手动重绑。
6. **不强制验证**：不校验签名、版本号、协议兼容性或包内容；只要包名前缀和 action 符合即可被发现和使用。
7. **扫描是唯一入库入口**：第三方运行时游戏只能由扫描入库；主 App 不提供手动添加、模块选择器、目录导入或拖入入口。
8. **默认关闭、显式启用**：新发现模块只出现在「引擎模块管理」页，默认关闭；只有在该页启用后，下一次用户主动扫描才会使用它。发现/启用都不会直接加入扫描或写入游戏库。

### 1.3 非目标

- 不自动启用新发现的模块；不在安装/发现时自动扫描或写入游戏库。
- 不接管存档、虚拟按键、手柄映射、引擎设置；这些由第三方模块自己负责。
- 不提供主 App 侧的模块下载、安装器或更新器。
- 不执行模块代码做扫描；扫描只解释模块声明的静态 JSON。
- 不使用 `QUERY_ALL_PACKAGES` 枚举任意应用。
- 第三方运行时游戏不提供任何手动添加入库入口（添加游戏、选择模块、目录导入、拖入），也不提供手动绑定/重绑、模块缺失修复页；扫描是唯一入库入口。现有 PC / 安卓手动添加保持不变。
- 不在主 App 内置第三方模块的特征库，也不根据包名、应用名或图标猜游戏特征。
- 不替代 JoiPlay 外置模块（Ren'Py / RPGM / Ruffle）和外置模拟器跳转；四套机制并存，互不影响。
- 不保证模块真的把游戏跑起来：`startActivity` 成功只代表 Intent 已交棒。

---

## 2. 与现有体系的关系

### 2.1 四套机制对比

| 维度 | 外置 APK 引擎模块（JoiPlay） | 外置模拟器跳转 | 手动安卓游戏 | 本方案：第三方 APK 运行时模块 |
| --- | --- | --- | --- | --- |
| 发现 | 固定包名注册表 | 固定包名注册表 | `MAIN/LAUNCHER` 查询 | 协议 action + 包名前缀过滤 |
| 模块数量 | 固定 | 固定 | 任意可启动应用 | 任意符合协议的 APK |
| 扫描识别 | 内置引擎特征 + `externalModuleAlias` | ROM 特征（PC 手动） | 不扫描 | 模块描述文件（唯一扫描来源） |
| 启动载荷 | `game`/`settings` JSON | ROM URI / Winlator extras | 包名 | 文件夹绝对路径 |
| 模块卸载 | 保留游戏，提示下载 | 保留游戏，提示安装 | 保留游戏，提示安装 | **降级为 UNKNOWN，用户重新扫描** |
| 验证 | 只查包名 | 只查包名/入口 | 只查启动入口 | **不验证**，只做容错解析 |

### 2.2 代码边界

- 新体系属于**功能抽象层**：`core/engine/runtime`（Android Framework + `PackageManager`，不依赖 Compose/UI）。
- UI 只调用 core 门面，不直接拼 `PackageManager` 查询或 Intent。
- 不进入 `engine/` 模块，不走 JNI，不经过 `EnginePluginBootstrap`。
- 与 JoiPlay 体系共享的只有底层工具（如包安装探测思路），不共享协议类型、注册表和错误码。

---

## 3. 设计原则

1. **启动与识别分离**：发现模块不等于能识别它的游戏；扫描特征只来自模块声明的第二协议。
2. **扫描特征单一来源**：只认模块声明的 `extensions` / `markers`；不做手动绑定，不做主 App 侧猜测。
3. **包名是身份**：绑定只认 `runtimeModulePackage`；不存 label 快照，不按显示名模糊匹配。
4. **卸载即降级**：模块卸载后游戏变 `UNKNOWN`，保留最后已知包名；用户重新扫描，不做重绑 UI。
5. **容错读取，不做业务验证**：不校验签名/版本/协议；但限制输入大小、路径和模式数量，避免崩溃和资源滥用。
6. **不申请宽权限**：用 action 查询获得包可见性，不用 `QUERY_ALL_PACKAGES`。
7. **显式组件启动**：`setComponent(package, activity)`，避免隐式 Intent 被其它应用接收。
8. **扫描不执行第三方代码**：只读模块声明的静态 JSON，匹配文件名/目录结构。
9. **失败类型化**：core 返回错误码/密封结果，文案由 UI 组装，遵守 AGENTS 的 core→ui 错误协议。
10. **默认关闭**：发现只更新「引擎模块管理」页的可见列表，不进入扫描索引；启用只写开关，不自动扫描。

---

## 4. 协议设计

### 4.1 发现

#### 4.1.1 模块侧声明

模块 APK 必须声明一个 **exported Activity**，同时作为发现入口和启动入口：

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.tyranor.runtime.example">

    <application ...>
        <activity
            android:name=".TyranorRuntimeActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="com.tyranor.runtime.action.LAUNCH" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>

            <!-- 扫描描述；没有则不参与自动扫描 -->
            <meta-data
                android:name="com.tyranor.runtime.MANIFEST"
                android:resource="@raw/tyranor_runtime" />
        </activity>
    </application>
</manifest>
```

约束：

- 包名必须以 `com.tyranor.runtime.` 开头（大小写敏感，至少多一段）。
- Activity 必须 `android:exported="true"`。
- 必须声明 `com.tyranor.runtime.action.LAUNCH` 和 `CATEGORY_DEFAULT`。
- 一个包只取一个启动 Activity：优先带 `com.tyranor.runtime.MANIFEST` meta-data 的；否则按 Activity 名排序取第一个。
- 描述文件放在 `res/raw/`，通过 meta-data 的资源 ID 读取，不按文件名猜。

#### 4.1.2 主 App 侧查询

在现有 `<queries>` 中增加：

```xml
<queries>
    <intent>
        <action android:name="com.tyranor.runtime.action.LAUNCH" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent>
</queries>
```

发现流程：

```kotlin
val intent = Intent(RuntimeModuleContract.ACTION_LAUNCH)
    .addCategory(Intent.CATEGORY_DEFAULT)
val flags = PackageManager.GET_META_DATA
val modules = packageManager.queryIntentActivities(intent, flags)
    .asSequence()
    .filter { it.activityInfo.packageName.startsWith(RuntimeModuleContract.PACKAGE_PREFIX) }
    .distinctBy { it.activityInfo.packageName }
    .map { resolveDescriptor(it) }
    .toList()
```

API 33+ 使用 `PackageManager.ResolveInfoFlags.of(...)`；低版本用旧的重载，沿用项目现有的版本分支风格。

**发现结果只用于「引擎模块管理」页展示**：不写启用状态、不进入扫描索引、不触发扫描、不写入游戏库。只有用户在该页打开开关后，下一次扫描才会读取到该模块。

#### 4.1.3 为什么不用纯包名前缀

Android 11+ 的包可见性过滤不支持“前缀通配查询”：

- `getInstalledPackages()` / `getInstalledApplications()` 只返回对当前应用可见的包。
- `<queries><package android:name="..."/></queries>` 只能写精确包名，不能写 `com.tyranor.runtime.*`。
- 唯一能按前缀枚举任意包的方式是申请 `QUERY_ALL_PACKAGES`，属于宽权限，且与项目现有“最小可见性”策略冲突。

因此：

- **action 是发现键**；包名前缀是命名约定和二次过滤。
- 不声明 action 的包即使前缀正确也不会被发现；声明 action 但前缀不正确的包会被忽略。
- 这两条是协议要求，不是安全验证。

### 4.2 扫描描述文件（自动扫描的唯一来源）

自动扫描只使用这里声明的 `extensions` / `markers`；主 App 不根据包名、应用名、图标或内置第三方特征库猜测。

#### 4.2.1 位置与读取

- 路径：模块自己的 `res/raw/tyranor_runtime.json`。
- 声明：`<meta-data android:name="com.tyranor.runtime.MANIFEST" android:resource="@raw/tyranor_runtime" />`。
- 读取：

```kotlin
val moduleContext = context.createPackageContext(packageName, 0)
val resId = activityInfo.metaData.getInt(RuntimeModuleContract.META_MANIFEST)
val json = moduleContext.resources.openRawResource(resId).use { readAtMost(it, 64 * 1024) }
```

- 读取失败、JSON 非法、字段越界：**只关闭自动扫描能力**，模块仍可被发现和启动，但不参与自动扫描。
- `ActivityInfo.metaData` 为空或没有 `META_MANIFEST`：同样视为无扫描描述，不报错。
- 不执行模块代码，不读取模块 assets 之外的内容。

#### 4.2.2 Schema

```json
{
  "protocol": 1,
  "label": "My Engine",
  "scan": {
    "match": "any",
    "extensions": [".mge"],
    "markers": ["game.dat", "data/*.pak"]
  }
}
```

| 字段 | 类型 | 必填 | 语义 |
| --- | --- | --- | --- |
| `protocol` | int | 否 | 协议版本，缺省 1；大于主 App 支持版本时只提示，不阻断已入库游戏的启动 |
| `label` | string | 否 | 展示名；缺省用 `ApplicationInfo.loadLabel()` |
| `scan` | object | 否 | 缺省表示“不参与自动扫描”；仍可被发现/启动 |
| `scan.match` | `"any"` / `"all"` | 否 | 缺省 `any` |
| `scan.extensions` | string[] | 否 | 扩展名，必须带前导点；只匹配直接子文件；大小写不敏感 |
| `scan.markers` | string[] | 否 | 相对游戏根目录的标记路径；文件或目录；中间段必须字面量，最后一段可含 `*` / `?`；最多一层 `/`；不支持 `**` |

硬限制：

- 描述文件 ≤ 64 KiB。
- `extensions` ≤ 32 项，每项 ≤ 16 字符。
- `markers` ≤ 64 项，每项 ≤ 256 字符。
- 绝对路径、`..`、反斜杠、空模式：丢弃该条模式；`**` 同样丢弃该条并记录一次日志。
- 若 `scan` 最终没有任何有效模式，视为没有自动扫描能力，模块仍可被发现/启动。
- 不支持正则、脚本、网络请求、文件内容读取。

#### 4.2.3 匹配语义

对候选目录的直接子项求值：

- **扩展名**：任一直接子文件的后缀命中 `extensions`，即扩展名谓词为真。
- **标记**：`markers` 中的相对路径存在即命中；文件或目录都算；`data/*.pak` 只检查 `data/` 下一层，不递归。
- `match = "any"`：扩展名谓词为真 **或** 任一 marker 命中。
- `match = "all"`：扩展名谓词为真（若有扩展名）**且** 所有 marker 命中。
- 名称比较统一大小写不敏感，避免 FAT/SAF 大小写差异导致漏判。
- 不做 `exclude`：误判用更精确的 markers 和 `match = "all"` 控制。

不做的事：

- 不读取文件头/魔数；需要内容特征的引擎不适合自动扫描。
- 不递归任意深度；扫描深度仍由现有 `maxDepth` 控制。
- 不把模块的扫描结果直接覆盖强特征内置识别（见 6.3）。

### 4.3 启动契约

#### 4.3.1 Intent

```kotlin
val intent = Intent(RuntimeModuleContract.ACTION_LAUNCH).apply {
    component = ComponentName(packageName, activityName)
    putExtra(RuntimeModuleContract.EXTRA_FOLDER_PATH, folderPath)
    putExtra(RuntimeModuleContract.EXTRA_GAME_TITLE, game.title)
    putExtra(RuntimeModuleContract.EXTRA_PROTOCOL_VERSION, RuntimeModuleContract.PROTOCOL_VERSION)
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
context.startActivity(intent)
```

| extra | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `com.tyranor.runtime.extra.FOLDER_PATH` | String | 是 | 游戏文件夹绝对路径 |
| `com.tyranor.runtime.extra.GAME_TITLE` | String | 否 | 游戏标题，便于模块展示 |
| `com.tyranor.runtime.extra.PROTOCOL_VERSION` | Int | 否 | 主 App 协议版本，当前 1 |

规则：

- **显式组件**，不用隐式 action 启动。
- 只传当前游戏文件夹，不传扫描根、其它游戏或主 App 私有目录。
- 不传 `game` / `settings` JSON；模块自己的设置由模块自己管理。
- 不添加 `FLAG_GRANT_READ/WRITE_URI_PERMISSION`；v1 只传路径。
- 模块需要自己的存储权限；主 App 的 `MANAGE_EXTERNAL_STORAGE` 不能转授。

#### 4.3.2 结果语义

`startActivity` 成功只表示 **Intent 已交棒（dispatched）**，不表示游戏已进入、未崩溃、未停在权限页。最近游戏记录可以在 dispatched 后写入，但 UI/文档不得宣称“启动成功”。

| 结果 | 触发条件 | UI 处置 |
| --- | --- | --- |
| `Dispatched` | `startActivity` 未抛异常 | 记录最近游戏；不承诺运行成功 |
| `ModuleMissing` | 包不在发现/可用集合 | 游戏降级为 `UNKNOWN`；提示重新扫描 |
| `ModuleDisabled` | 包在，但未在「引擎模块管理」启用 | 游戏降级为 `UNKNOWN`；提示启用后重新扫描 |
| `EntryMissing` | 包在，但 Activity 不可解析/被禁用 | 显示“模块入口不可用” |
| `PathUnresolved` | 无法把 URI 解析为绝对路径 | 提示路径限制 |
| `SecurityDenied` | `SecurityException` | 显示权限/入口错误 |
| `StartFailed` | 其它异常 | 显示通用失败；detail 仅用于日志/排障 |

`ModuleMissing` / `ModuleDisabled` 会触发一次 `RuntimeModuleBindings.degradeGame(...)`：把该游戏改为 `UNKNOWN`，保留最后已知 `runtimeModulePackage` 用于重扫恢复。没有重绑按钮。

`EngineType.UNKNOWN` 条目启动时直接返回 `LaunchResult.Failure.EngineUnknown`，提示“引擎未知或运行时模块不可用，请重新扫描”，不再落入内置 Tyrano 宿主兜底。

协议版本较新不是启动失败：显示警告，仍允许启动。

### 4.4 “不验证”的边界

本方案不做：

- 签名校验、包内容哈希、版本号下限、协议版本强匹配。
- 模块白名单、开发者证书、安装来源校验。
- 扫描描述的业务正确性校验（只做结构与资源上限校验）。

信任模型：

- 用户主动安装模块；主 App 只把扫描到的游戏文件夹交给显式组件。
- 包名前缀是命名空间约定，不是安全边界；任何应用都可以声明该前缀。
- 若未来需要信任门禁，应作为独立需求（例如首次启用确认），不混入本协议。

---

## 5. 数据模型

### 5.1 新引擎类型

在 `core/engine/EngineType.kt` 增加：

```kotlin
RUNTIME_MODULE("第三方运行时", "RT")
```

- `isManual = false`：它参与扫描；卸载降级后变成 `UNKNOWN`。
- 不加入 `EngineLauncher.supportedEngines` 的引擎页列表；模块管理放在独立设置页。
- 仍必须补齐所有 `when (EngineType)` 分支（编译器会强制）：
  - `EngineScreen.engineTabOf` / `engineDescription`
  - `GameScreen.coverColor` / `shouldShowSaveManagement`
  - `EngineLauncher.buildEngineIntent`
  - `EnginePluginBootstrap.ensureForLaunch`
  - `GameSaveManager` 的存档路径 `when`
  - `PerGameSettingsScreen` 的引擎分支
- 封面底色用中性色，新增常量集中到 `GameScreen.coverColor()` 或 `theme/Color.kt`，不要散落硬编码。

### 5.2 `ScanGame` 新字段

```kotlin
data class ScanGame(
    // ...既有字段
    /** 绑定的第三方运行时包名；null 表示非本体系。卸载/禁用后引擎降级为 UNKNOWN，但保留该最后已知包名用于重扫恢复。 */
    val runtimeModulePackage: String? = null,
)
```

不存 label 快照，不存手动绑定标记。显示名在模块在线时从注册表查；模块不在线时游戏已经是 `UNKNOWN`。

不要复用 `externalModuleAlias`：它已经承载 RPGM 子运行时，混用会让两套生命周期互相污染。

### 5.3 Room v3

`GameEntity` 增加一列并声明索引：

```kotlin
@ColumnInfo(name = "runtime_module_package") val runtimeModulePackage: String?,
```

`GameLibraryDatabase` 从 v2 升到 v3，注册显式 `Migration(2, 3)`：

```sql
ALTER TABLE games ADD COLUMN runtime_module_package TEXT;
CREATE INDEX IF NOT EXISTS index_games_runtime_module_package
    ON games(runtime_module_package);
```

要求：

- 提交 `app/schemas/.../3.json`。
- 不扩大 v1→v2 的破坏性重建例外；v2→v3 必须显式迁移。
- `GameEntity.from` / `toModel` 双向映射；旧行为 null。

### 5.4 字段传播清单

| # | 位置 | 要做的事 |
| --- | --- | --- |
| 1 | `ScanGame` | 增加 `runtimeModulePackage` |
| 2 | `ScanGameIntents.putGame/getGame` | 增加 extra，详情页往返不丢 |
| 3 | `GameEntity.from/toModel` | 双向映射 |
| 4 | `GameLibraryDatabase` | v3 + `Migration(2,3)` + schema 导出 |
| 5 | `GameRecordCodec.serializeGame/parseGame` | 追加字段 12，旧行 `getOrElse` 兼容 |
| 6 | `EngineScanner.rescanLibrary` | 旧包名仅在旧记录为 RUNTIME_MODULE 时优先保留；降级条目保留最后已知包名 |
| 7 | `MainLibraryViewModel.mergeChangedGameFields` | 改名/换封面不丢绑定 |
| 8 | `GameLibraryRepository` / `GameLibraryFacade` | 随实体自动落库；prefs 镜像走 codec |
| 9 | `GameScreen` | 卡片名、角标、存档管理、操作抽屉 |
| 10 | `PerGameSettingsScreen` | 新引擎分支：只显示模块信息 |
| 11 | `GameSaveManager` | `RUNTIME_MODULE` 返回“无统一存档接口” |

`mergeScannedWithManual` 为运行时绑定条目（`RUNTIME_MODULE` 或降级为 `UNKNOWN` 但保留最后已知包名）加保护：本次扫描识别不到时保留条目（仍受活动扫描根约束），避免模块描述暂时失效/发现失败导致整条游戏被删除。

### 5.5 绑定与降级

- **绑定身份**：`runtimeModulePackage`。
- **启用状态**：`AppSettingsStore` 保存已启用包名集合；默认空集，所有新发现模块关闭。
- **可用性**：模块必须“已发现 + 已启用 + Activity 可解析”才可扫描/启动。
- **显示名**：从 `RuntimeModuleRegistry` 实时查；模块不在线时游戏应为 `UNKNOWN`。
- **降级**：`RuntimeModuleBindings.reconcile(context)` 批量检查库中所有 `RUNTIME_MODULE` 条目：
  - 包仍在“已发现 + 已启用”集合 → 保持不变。
  - 包不在集合（卸载、停用、被用户关闭）→ `engine = UNKNOWN`（保留最后已知 `runtimeModulePackage`），保留标题/封面。
  - 发现查询本身失败（PM 异常）→ **跳过本轮降级**，避免把全部游戏误打成 `UNKNOWN`。
- `reconcile` 在应用启动/回前台、模块管理页刷新、启动前检查时调用；扫描入口在构建索引前也先调用一次，避免用旧绑定扫出新结果。IO 线程 + `GameLibraryFacade.updateGames`。
- 不删除条目、不自动重绑、不做“模块未安装”状态。

---

## 6. 扫描识别

### 6.1 为什么包名前缀不够

包名只能回答“这个 APK 是模块”，不能回答“这个文件夹是不是它的游戏”。任意引擎的游戏特征（文件结构、扩展名、标记文件）只有模块自己知道。主 App 不应尝试从包名、应用名或图标猜测。

因此**只采用**模块描述文件（声明式）；手动绑定和模块扫描服务都不做：

| 方案 | 模块要做什么 | 主 App 要做什么 | 结论 |
| --- | --- | --- | --- |
| 模块描述文件（声明式） | 提供 `res/raw/tyranor_runtime.json` | 解释扩展名/标记并匹配 | **唯一扫描特征来源** |
| 手动绑定 | 无 | 选目录 + 选模块 | 不做 |
| 模块扫描服务 | 提供 Service/Activity 扫描接口 | 逐目录 IPC 调用第三方代码 | 不做：慢、易崩、权限面大 |

**入库唯一入口**：第三方运行时游戏只能通过扫描进入游戏库。扫描不到就是没有入库，不提供“手动补加”或“选择模块”兜底；模块作者需要修正 `extensions` / `markers` 后让用户重扫。

### 6.2 模块描述文件是唯一自动扫描来源

- 没描述文件：不参与自动扫描，也没有手动补加入口；因此无法把游戏加入库。
- 描述文件非法/超限：关闭自动扫描；模块仍可被发现/启动，但无法新增游戏。
- 描述文件合法：模块进入“可自动扫描”集合；还必须由用户在「引擎模块管理」页启用，才真正参与扫描。
- 发现 ≠ 参与扫描：发现只更新模块列表；扫描索引在每次扫描开始时按“已启用”集合快照构建。
- 模块卸载：从可用集合移除；绑定游戏按第 7 节降级为 `UNKNOWN`。
- 模块被禁用：从已启用集合移除；下一次扫描不再包含它；绑定游戏同样降级为 `UNKNOWN`。
- 入库唯一入口：只通过扫描；没有匹配的目录不会出现在游戏库。

### 6.3 与 `EngineScanner` 的接入点

现有 `EngineScanner.detectEngine` 是一个按优先级 early-return 的大函数。**不重排内置优先级**，用包装层接入：

```text
detectEngineWithRuntime(dir):
  builtIn = 现有 detectEngine(dir)          // 强特征内置引擎优先
  if builtIn.engine !in { UNKNOWN, WEB_OTHER }:
      return builtIn
  custom = runtimeModules.match(dir)         // 只读名称/结构
  return custom ?: builtIn
```

规则：

1. **内置强特征优先**：KRKR / ONS / RPGM / Ren'Py / Ruffle / Siglus 等识别结果不被第三方模块自动覆盖。
2. **只补弱结果**：`UNKNOWN` 和 `WEB_OTHER` 才尝试自定义模块；这样带 `index.html` 的自定义引擎也有机会。
3. **复用已取到的子项**：SAF 场景不要为了匹配再查一次目录，避免 Binder 往返翻倍。
4. **多模块命中**：按“`all` 优先于 `any` → marker 数量多优先 → 包名排序”选一个；不弹窗阻塞扫描。
5. **启用开关**：`AppSettingsStore` 保存已启用包名集合；默认关闭，仅已启用模块进入扫描索引。索引在扫描开始时快照，启用/禁用不影响正在进行的扫描。
6. **旧绑定优先**：`rescanLibrary` 对同 URI 的旧记录保留 `runtimeModulePackage`，不因本次匹配到别的模块而改绑。
7. **扫描深度**：沿用现有 `maxDepth`；描述文件的 marker 只支持直接子项/单层通配。
8. **ROM/SWF 与扫描根保护**：目录含主机 ROM（PSP/Switch）或散装 SWF 时交给 `romGamesForSaf/File` 逐文件入库，模块扩展名不得吞并；扫描根 level 0 不参与模块匹配，避免宽 marker 把整棵库识别成一个游戏。

传递给扫描器的索引：

- `scanAll`、`rescanLibrary`、`scanRoot`、`incrementalScan` 在开始时构建一份 `RuntimeModuleIndex`（已安装 + 已启用 + 有 scan spec）。
- `detectEngine` 增加可选参数，默认空索引，保证现有 JVM 测试不必改签名。
- 纯匹配逻辑放 `RuntimeModuleMatcher`，不依赖 Android，便于 JVM 单测。

### 6.4 扫描验收样例

- 目录含 `game.dat`，模块 A 声明 marker `game.dat` → 自动识别为 A。
- 目录含 `index.html`，内置判为 `WEB_OTHER`，模块 B 声明 `index.html` → B 覆盖弱结果。
- 目录含 `xp3`，内置判为 KRKR，模块 C 也声明 `.xp3` → 仍判 KRKR。
- 目录含两个模块都声明的 `data/*.pak` → 按优先级选一个。
- 禁用模块 A 的自动扫描 → 新目录不再匹配 A；已绑定游戏降级为 `UNKNOWN`，重新启用并重扫可恢复。
- 模块卸载 → 绑定游戏变 `UNKNOWN`；重装后重新扫描可恢复。

---

## 7. 卸载 / 禁用与降级

### 7.1 行为

模块卸载或被用户禁用后：

1. `reconcile` 发现该包不在“已发现 + 已启用”集合。
2. 把绑定它的游戏改为 `engine = UNKNOWN`，保留最后已知 `runtimeModulePackage`（供重扫合并，不作为启动依据）。
3. 保留标题、封面、最近时间等既有数据；卡片按 `UNKNOWN` 展示。
4. 用户重新扫描：
   - 模块已重装并启用 → 扫描识别后重新入库/恢复为 `RUNTIME_MODULE`。
   - 模块仍未安装或仍未启用 → 该目录识别不到；条目按运行时绑定保留（在活动扫描根内），不会因描述失效/发现失败被删除。
5. 不提供“模块未安装”角标、不提供重绑按钮、不提供修复页。

### 7.2 状态表

| 状态 | 判定 | `engine` | 包名 | UI / 用户操作 |
| --- | --- | --- | --- | --- |
| 可用 | 包在发现结果、已启用、Activity 可解析 | `RUNTIME_MODULE` | 保留 | 正常启动 |
| 模块卸载 | 包不在发现结果 | `UNKNOWN` | 保留最后已知包名 | 卡片显示未知；重新扫描 |
| 模块被禁用 | 包在，但用户关闭了启用开关 | `UNKNOWN` | 保留最后已知包名 | 卡片显示未知；重新启用后扫描 |
| 入口缺失/停用 | 包/Activity 不再可发现 | `UNKNOWN` | 保留最后已知包名 | 卡片显示未知；检查模块后重新扫描 |
| 协议较新 | `protocol > 1` | `RUNTIME_MODULE` | 保留 | 警告，仍可启动 |
| 模块更新 | 同包名，versionCode 变化 | `RUNTIME_MODULE` | 保留 | 正常；描述/Activity 重新解析 |
| 包名变更 | 旧包不在，新包不同 | `UNKNOWN` | 保留旧包名（重扫时采用新模块） | 重新扫描；不自动匹配新包 |

### 7.3 启用 / 禁用 / 重装 / 更新 / 重扫

- **首次发现**：默认关闭，不参与扫描、不可启动；用户在「引擎模块管理」页打开开关。
- **启用**：只把包写入已启用集合，不触发全库扫描、不立即写入游戏库、不改变正在进行的扫描；提示用户回游戏页重新扫描。之前降级的 `UNKNOWN` 游戏需重扫恢复。
- **禁用**：从已启用集合移除，下一次扫描不再包含它；若该模块仍有绑定游戏，确认后降级为 `UNKNOWN`。
- **同包名重装**：游戏已经被降级为 `UNKNOWN`；如果包仍在已启用集合，用户重新扫描后可恢复 `RUNTIME_MODULE`。
- **模块更新**：同包名，启用状态与绑定保留；描述和 Activity 在启动前重新解析，不写 DB。
- **包名变更**：新包视为新模块，默认关闭；旧游戏卸载时已降级为 `UNKNOWN`，需启用新模块后重新扫描。
- **重扫**：`rescanLibrary` 以 URI 对齐旧记录；能重新识别的条目恢复，识别不到但仍在活动扫描根内的运行时绑定条目保留（不因模块描述失效/发现失败删除）。
- **运行中卸载**：模块进程被系统杀掉，主 App 收不到回调；下次回前台 `reconcile` 降级。

### 7.4 数据影响

- 降级只改 `engine` 和 `runtimeModulePackage`，不动标题、封面、VNDB、最近时间。
- 重扫若重新识别，沿用同 URI 的旧记录，封面/元数据按现有 `rescanLibrary` 规则保留。
- 重扫若识别不到，运行时绑定条目仍保留（在活动扫描根内），封面/元数据不会因模块描述暂时失效而丢失；只有扫描根移除或用户显式删除才消失。
- 最近/快捷启动保留同一个 URI；`UNKNOWN` 状态启动会失败，重新扫描恢复后自然可用，用户也可自行移除。
- 模块私有存档可能随卸载被系统删除；主 App 不接管、不备份，需在文档/UI 说明。

### 7.5 用户提示

- `reconcile` 返回降级数量；主界面/「引擎模块管理」页可显示一次提示：“N 个游戏因运行时模块卸载或禁用已降级为未知，重新安装/启用后重新扫描可恢复。”
- 不弹阻塞式弹窗；不提供“立即重绑”。

---

## 8. UI / UX

### 8.1 入口：侧边工具栏「引擎模块管理」

- 侧边工具栏指主界面左缘右滑唤出的「妙妙工具」面板（`MainScreen.kt` 的 `AppSidePanel` content）。
- 新增一个 `AppNavItem`：标题「引擎模块管理」，`containerColor = DialogItemSurface`，图标优先复用已有 `ic_engine_manage`；若评审认为与「引擎设置」图标混淆，再新增 `ic_engine_module`。
- 位置放在「输入与手柄」之后，保持现有条目顺序不变。
- 点击后先关闭侧边栏，再 `startActivityWithPageTransition(context, EngineModuleManagementActivity.createIntent(context))`，使用二级页上翻动画。
- 新增三语言字符串：`side_panel_engine_module_title`、`engine_module_management_title`，文案均为「引擎模块管理」。

### 8.2 引擎模块管理页

- Activity：`EngineModuleManagementActivity : AppScreenActivity`，Manifest 中 `android:exported="false"`。
- 页面结构：`MiuixSettingsTheme` + `MiuixScaffold(containerColor = Color.Transparent, contentWindowInsets = WindowInsets(0.dp), topBar = AppTopBar(...))`；顶栏透明，正文整体垫在顶栏下方。
- 顶栏右侧 `TopBarIcon(ic_game_scan, "刷新模块列表")`，只刷新模块发现结果，不触发游戏库扫描。
- 正文用 `LazyColumn`，每个模块一张 `MiuixCard`（`glassShadow` + `glassBorder`，`AppComponentCornerRadius`），内部 `SwitchPreference`：
  - `title`：模块 label（缺失时用包名）。
  - `summary`：包名 · versionName/versionCode · protocol · 是否声明扫描特征 · 当前绑定游戏数量。
  - `checked`：是否启用，**默认 false**。
  - `onCheckedChange`：启用/禁用，见 8.3。
- 列表只展示已发现模块；未安装的模块直接不出现，不设“孤立游戏”分组。
- 空态：仅在页面水平居中显示「未发现模块」标题，不显示描述、协议说明或下载链接。
- 页面 `ON_RESUME` 刷新一次发现结果，安装/卸载模块后返回即可看到变化。
- 页面不提供任何手动添加、选择目录、选择模块或绑定入口。

### 8.3 默认关闭与启用语义

- 首次发现默认关闭；`AppSettingsStore` 新增 StringSet 键保存已启用包名集合，默认空集。
- **启用**：只写入已启用集合，不触发扫描、不立即入库、不影响正在进行的扫描；提示“已启用，请回游戏页重新扫描”。下一次扫描才会读取该模块。之前已降级为 `UNKNOWN` 的游戏需要重扫恢复。
- **禁用**：从已启用集合移除，下一次扫描不再包含它；若该模块仍有绑定游戏，先用 `AppAlertDialog` 确认“禁用后这些游戏将降级为未知，需要重新启用并扫描”，确认后调用 `reconcile` 降级。
- 启用状态按包名持久化：同包名更新/重装保留；包名变更视为新模块，默认关闭。
- 没有 `scan` 描述的模块仍可被发现、可启用，但无法自动入库；summary 标注“未声明扫描特征”，不提供手动补加。
- 页面只负责“发现 + 启用状态”，不负责扫描；扫描仍由游戏页的现有扫描入口触发。

### 8.4 游戏卡与操作抽屉

- 卡片引擎名：第三方运行时游戏统一显示「Plugin」（`engine_name_runtime_module`），不显示模块 label；模块 label 只在「引擎模块管理」页展示。模块不在线时游戏已是 `UNKNOWN`，走现有未知引擎展示。
- 角标可用 `RT`；沿用现有卡片角标字号。
- 操作抽屉保留启动与现有删除；不提供“更换模块/模块信息”等专属动作。
- 第三方运行时游戏不提供任何手动添加入口：添加游戏对话框、模块选择器、目录导入、拖入都不提供；扫描是唯一入库方式。现有 PC / 安卓手动添加不变。
- `shouldShowSaveManagement` 显式排除 `RUNTIME_MODULE`。
- 引擎设置入口对 `RUNTIME_MODULE` 隐藏（无主 App 侧设置）。

### 8.5 错误弹窗

`LaunchResult.Failure.RuntimeModuleFailed` → UI 映射：

- `ModuleMissing`：模块不可用，游戏已降级为未知，请重新扫描；只给“知道了”。
- `ModuleDisabled`：模块未启用，游戏已降级为未知；提示到「引擎模块管理」启用后重新扫描。
- `EntryMissing`：模块入口不可用，可能版本不兼容；只给“知道了”。
- `PathUnresolved`：说明模块只接受绝对文件夹路径。
- `SecurityDenied` / `StartFailed`：通用失败文案；detail 不直接展示给普通用户。

### 8.6 文案与规范

- zh / ja / en 三份 `strings.xml` 同步新增。
- 运行 `python tools/check-hardcoded-ui-strings.py`。
- core 不返回本地化文案；错误码/密封结果由 UI 映射。
- 页面顶部栏统一 `AppTopBar`；设置页用 `MiuixScaffold` + 透明顶栏；列表项 `AppNavItem`，弹窗 `AppAlertDialog` + `DialogTextButton`，开关用 Miuix `SwitchPreference`；圆角/颜色走 `AppComponentShape` / `theme/Color.kt`。

---

## 9. 代码落点

### 9.1 新增包

```text
app/src/main/java/com/tyranor/next/core/engine/runtime/
├── RuntimeModuleContract.kt       // action、extras、前缀、协议版本
├── RuntimeModuleDescriptor.kt     // 包名、label、版本、protocol、scan spec
├── RuntimeModuleIndex.kt          // 扫描索引与多模块优先级
├── RuntimeModuleDiscovery.kt      // queryIntentActivities + 前缀过滤 + 描述读取
├── RuntimeModuleMatcher.kt        // 纯 Kotlin 匹配器（JVM 可测）
├── RuntimeModuleLauncher.kt       // 显式组件 + folder_path + 类型化结果
├── RuntimeModuleBindings.kt       // reconcile / degradeGame
└── RuntimeModuleRegistry.kt       // 内存快照/StateFlow、可用性批量查询

app/src/main/java/com/tyranor/next/ui/runtime/
├── EngineModuleManagementActivity.kt
└── EngineModuleManagementScreen.kt
```

### 9.2 需要修改的现有文件

| 文件 | 改动 |
| --- | --- |
| `core/engine/EngineType.kt` | 增加 `RUNTIME_MODULE` |
| `core/game/model/ScanGame.kt` | `runtimeModulePackage` + `ScanGameIntents` extra |
| `core/game/storage/GameEntity.kt` | 一列 + 双向映射 + 索引 |
| `core/game/storage/GameLibraryDatabase.kt` | v3 + `Migration(2,3)` |
| `core/game/scan/GameRecordCodec.kt` | 追加字段 |
| `core/game/scan/EngineScanner.kt` | 索引参数、弱结果分流、重扫保留 |
| `core/settings/AppSettingsStore.kt` | 新增 `runtime_module_enabled` StringSet + 读写方法 |
| `core/game/launch/EngineLauncher.kt` | `RUNTIME_MODULE` 分支、`buildEngineIntent` 守卫 |
| `core/game/launch/LaunchResult.kt` | `RuntimeModuleFailed` |
| `core/game/save/GameSaveManager.kt` | 无存档接口分支 |
| `core/engine/plugin/EnginePluginBootstrap.kt` | `RUNTIME_MODULE -> null` |
| `ui/game/GameScreen.kt` | 卡片名/角标、操作抽屉、存档判定、封面色 |
| `ui/main/MainScreen.kt` | 侧边工具栏新增「引擎模块管理」`AppNavItem` |
| `ui/settings/PerGameSettingsScreen.kt` | 只读模块信息分支 |
| `ui/common/LaunchErrorMessages.kt` | 新失败类型映射 |
| `ui/engine/EngineScreen.kt` | `when` 完备性（不新增列表项） |
| `app/src/main/AndroidManifest.xml` | `<queries>` action + 模块管理 Activity |
| `res/drawable` | 复用 `ic_engine_manage`；如混淆则新增 `ic_engine_module` |
| `res/values*/strings.xml` | 三语言文案（入口、页面、开关、提示） |

### 9.3 依赖方向

```text
ui/runtime  ->  core/engine/runtime  ->  Android Framework
ui/game     ->  core/game/launch     ->  core/engine/runtime
core/game/scan -> core/engine/runtime（只依赖描述/匹配器，不依赖 UI）
```

- `core/engine/runtime` 不得 import `com.tyranor.next.ui.*` 或 Compose。
- `ui/*` 不得直接使用 `PackageManager` 拼装发现/启动逻辑；通过 core 门面。
- 模块列表用 `StateFlow` 暴露，UI `collectAsState`。

---

## 10. 施工计划（一次交付）

不拆阶段、不设 MVP/后续；按依赖顺序一次做完。粗估 5–7 个开发日（不含示例模块 APK 与真机验收）。

### 10.1 协议与纯逻辑

- [x] `RuntimeModuleContract`：action、包名前缀、meta-data、extras、协议版本、错误码。
- [x] `RuntimeModuleDescriptor`：解析 `res/raw/tyranor_runtime.json`，上限/路径/模式校验。
- [x] `RuntimeModuleMatcher`：`extensions` / `markers` / `any` / `all`，纯 JVM 测试。
- [x] `RuntimeModuleRegistry`：内存快照、可用/已启用包集合、label/版本查询、`StateFlow`。

### 10.2 发现与数据

- [x] `RuntimeModuleDiscovery`：`queryIntentActivities` + 前缀过滤 + 描述读取；查询失败可区分。
- [x] `EngineType.RUNTIME_MODULE` + 所有 `when` 分支。
- [x] `ScanGame.runtimeModulePackage`；`ScanGameIntents`、`GameRecordCodec`、`mergeChangedGameFields` 同步。
- [x] `GameEntity` 一列 + 索引；Room v3 + `Migration(2,3)` + `app/schemas`。
- [x] `AppSettingsStore` 新增 `runtime_module_enabled` StringSet（默认空集）与读写方法。
- [x] `RuntimeModuleBindings.reconcile` / `degradeGame`：模块缺失/未启用 → `UNKNOWN`，保留最后已知包名（供重扫合并）；查询失败跳过。

### 10.3 扫描与启动

- [x] `EngineScanner` 构建 `RuntimeModuleIndex`，传给 `scanAll` / `rescanLibrary` / `scanRoot` / `incrementalScan`。
- [x] `detectEngine` 包装层：内置强特征优先，仅 `UNKNOWN` / `WEB_OTHER` 尝试模块匹配；主机 ROM/散装 SWF 目录与扫描根不参与模块匹配。
- [x] `mergeScannedWithManual`：运行时绑定条目（含降级为 `UNKNOWN` 但保留包名）在扫描未命中时保留，受活动扫描根约束。
- [x] 多模块优先级、旧绑定优先、仅“已启用”模块进入扫描索引；索引在扫描开始时快照。
- [x] `EngineLauncher` 的 `RUNTIME_MODULE` 分支：显式组件 + `folder_path`；不进入设置/输入/插件链。
- [x] `UNKNOWN` 条目启动返回类型化“未知引擎，请重新扫描”，不再落入 Tyrano 宿主兜底。
- [x] `RuntimeModuleLauncher`：`Dispatched` / `ModuleMissing` / `ModuleDisabled` / `EntryMissing` / `PathUnresolved` / `SecurityDenied` / `StartFailed`。
- [x] `ModuleMissing` / `ModuleDisabled` 时降级该游戏并返回提示。
- [x] `LaunchResult.RuntimeModuleFailed` + UI 文案映射。

### 10.4 UI

- [x] 侧边工具栏「妙妙工具」新增「引擎模块管理」`AppNavItem`，点击进入模块管理页。
- [x] 模块管理页：已发现模块列表、`SwitchPreference` 默认关闭、刷新、空态、游戏数量。
- [x] 启用：写入 StringSet + 提示重新扫描；禁用：有绑定游戏时确认后降级。
- [x] 游戏卡 label/角标、操作抽屉、隐藏存档/引擎设置、封面色。
- [x] 确认没有第三方运行时手动添加入口（`AddGameDialog` / 模块选择器 / 目录导入 / 拖入）。
- [x] 降级数量提示（非阻塞）。
- [x] 三语言 `strings.xml` + `tools/check-hardcoded-ui-strings.py`。
- [x] 深色/玻璃风格/两档字号/统一组件检查。

### 10.5 验证与文档

- [x] JVM：描述解析、匹配、前缀过滤、字段传播、codec、错误映射。
- [ ] Room v2→v3 迁移测试。
- [x] 扫描回归：强内置不被覆盖、UNKNOWN/WEB_OTHER 可覆盖、旧绑定优先。
- [x] 卸载/禁用/重装/重扫/包名变更测试（JVM 合并与扫描回归；真机待验收）。
- [ ] 真机：示例模块安装 → 默认关闭 → 启用 → 扫描 → 启动 → 禁用/卸载 → 降级 → 重装 → 重扫恢复。
- [x] 更新 `SPEC.md`、`ARCHITECTURE.md`、`CONTEXT.md` 术语、`CHANGELOG.md`。
- [x] `./gradlew testDebugUnitTest --no-daemon`
- [ ] `:app:lintDebug` / `:engine:lintDebug`：仍有仓库既有报错；本次新增文件未出现在新增 error 中。

### 10.6 完成定义

- 模块声明 `extensions`/`markers` 后，扫描能自动识别并入库。
- 新发现模块默认关闭；侧边工具栏「引擎模块管理」可查看并启用；未启用不扫描、不启动。
- 发现/启用都不直接写库；启用后仍需用户手动扫描。
- 扫描是唯一入库入口；添加游戏对话框、模块选择器、目录导入、拖入都不提供。
- 模块卸载/禁用后游戏变 `UNKNOWN`，保留最后已知包名，无重绑 UI；重新安装/启用后重新扫描可恢复。
- 内置引擎、JoiPlay、外置模拟器、存档管理无回归。
- v2→v3 迁移不丢数据；三语言与页面规范通过。

---

## 11. 测试计划

### 11.1 JVM 单测

- 描述解析：合法、缺字段、未知 `protocol`、超大文件、非法 JSON、绝对路径、`..`、反斜杠、`**`。
- 匹配器：扩展名大小写、marker 直接命中、`data/*.pak` 单层通配、`any`/`all`、多模块优先级。
- 前缀过滤：正确前缀、缺后缀、大小写、非前缀包。
- 字段传播：`GameRecordCodec` 往返、旧行解析、`mergeChangedGameFields`。
- 启动结果映射：`ModuleMissing` / `ModuleDisabled` / `EntryMissing` / `PathUnresolved` / `SecurityDenied` / `StartFailed`。
- 扫描器：强内置不被覆盖、UNKNOWN/WEB_OTHER 可覆盖、旧绑定优先。

### 11.2 Android / Room

- `Migration(2,3)`：v2 库含内置/自定义混合数据，升级后 `runtimeModulePackage` 为 null，无数据丢失。
- `RuntimeModuleDiscovery`：fake `PackageManager` 返回模块 Activity + meta-data；验证前缀过滤、多 Activity 选择、描述读取失败降级、查询失败不降级。
- `RuntimeModuleLauncher`：显式组件、extras、缺失包、缺失 Activity、`SecurityException`。
- `RuntimeModuleBindings`：模块缺失/未启用 → `UNKNOWN` 且保留最后已知包名；模块在线且启用 → 保持；查询失败 → 跳过。
- `AppSettingsStore`：启用集合默认空、按包名增删、同包名重装保留。
- `EngineModuleManagementScreen`：默认关闭、启用提示、禁用确认（有绑定时）。

### 11.3 真机

准备一个示例模块 APK（debug 签名即可）：

- 固定 action + `exported` Activity。
- 读取 `folder_path` 并显示/校验。
- `res/raw/tyranor_runtime.json` 声明 `extensions`/`markers`。

用例：

1. 侧边工具栏「妙妙工具」出现「引擎模块管理」入口。
2. 安装示例模块 → 页面出现，开关默认关闭；此时扫描不识别该模块，游戏库无新增。
3. 启用 → 只写开关，不自动扫描、不立即入库；页面提示重新扫描。
4. 手动扫描 fixture → 自动入库，卡片引擎名显示「Plugin」。
5. 启动 → 模块收到正确 `folder_path`。
6. 禁用（有绑定游戏）→ 确认后游戏变 `UNKNOWN`；重新启用 + 重扫恢复。
7. 卸载 → 回到主 App（触发 `reconcile`）→ 游戏变 `UNKNOWN`，保留最后已知包名，提示重新扫描。
8. 重装 → 重新扫描 → 恢复 `RUNTIME_MODULE`。
9. 入口 Activity 停用 → 启动报 `EntryMissing`。
10. SAF-only 目录 → `PathUnresolved`。
11. 添加游戏对话框没有第三方运行时选项；无法手动选择模块或导入目录。
12. 降级为 `UNKNOWN` 的条目点击启动 → 提示重新扫描，不再拉起 Tyrano 宿主。
13. 深色/玻璃/三语言页面检查。

### 11.4 回归

- 现有 JoiPlay Ren'Py/RPGM/Ruffle 启动不受影响。
- 外置模拟器 PPSSPP/Eden/Winlator 不受影响。
- 手动 PC/安卓游戏、存档管理、引擎页、首页分类、最近/快捷启动不回归。
- 扫描内置引擎结果与改动前一致。

### 11.5 命令

```bash
./gradlew testDebugUnitTest --no-daemon
./gradlew :app:lintDebug :engine:lintDebug --no-daemon
python tools/check-hardcoded-ui-strings.py
```

设备验收按 `app/src/androidTest/README.md` 与 `app/src/releaseProbe/README.md` 执行；普通 CI 不自动跑设备测试。

---

## 12. 风险与缓解

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| 漏写 `<queries>` action | 一个模块都发现不了 | 单测校验；文档模板明确 |
| 纯前缀发现不可行 | 误以为 `getInstalledPackages` 能枚举 | 明确 action 查询；不申请 `QUERY_ALL_PACKAGES` |
| 模块不声明 `scan` | 无法自动入库 | 协议明确扫描特征只来自声明；提供模块模板与空态引导 |
| 描述文件过宽 | 抢走内置游戏 | 只在 UNKNOWN/WEB_OTHER 介入 + 每模块开关 |
| 恶意描述文件 | 扫描变慢/OOM/路径穿越 | 64 KiB、模式数量上限、拒绝 `..`/绝对路径、无正则/无代码执行 |
| 多模块冲突 | 绑定到错误模块 | 确定性优先级 + 旧绑定优先 |
| 发现查询失败误降级 | 全部游戏变 UNKNOWN | `reconcile` 区分“查询失败”和“确实没装”，失败跳过 |
| 模块更新窗口误降级 | 同包名替换瞬间包不可见 | 只在回前台稳定后 reconcile；异常/查询失败跳过 |
| 模块索引/描述暂时失效 | 重扫误删运行时条目 | `mergeScannedWithManual` 保留运行时绑定条目；发现失败/描述失效时仍保留（受活动扫描根约束） |
| 字段传播遗漏 | 重扫/重启后绑定丢失 | 第 5.4 节清单 + codec/迁移测试 |
| Room 迁移遗漏 | 启动崩溃或清库 | 显式 `Migration(2,3)` + schema + 迁移测试 |
| SAF-only 目录 | 无法解析绝对路径 | 明确错误；后续再评估 URI |
| 模块私有存档 | 卸载后存档丢失 | 文档/UI 说明主 App 不接管；不承诺备份 |
| 启动成功语义 | 用户误以为游戏已进入 | 统一用 `Dispatched`；记录最近但不宣称成功 |
| 第三方模块伪造前缀 | 收到游戏路径 | 用户主动安装；显式组件；只传扫描到的文件夹；不传主 App 私有路径 |

---

## 13. 开放决策

| # | 决策 | 推荐 | 备选 |
| --- | --- | --- | --- |
| 1 | 自定义模块与内置识别冲突 | **只在 UNKNOWN / WEB_OTHER 时介入** | 允许模块声明高优先级覆盖 |
| 2 | 启动载荷 | **只传绝对文件夹路径** | 同时传 SAF URI + 临时读授权 |
| 3 | 是否做信任门禁 | **不做签名/版本验证；每模块启用开关默认关** | 首次启用弹窗确认 |
| 4 | 模块管理入口 | **侧边工具栏「引擎模块管理」** | 应用设置二级页 / 引擎页新 Tab |
| 5 | 模块身份 | **只认包名** | 描述文件 `id` 辅助跨包名迁移 |
| 6 | 多模块命中 | **确定性优先级，不弹窗** | 扫描时弹窗让用户选 |

---

## 14. 总验收清单

- [x] 模块包名前缀 + action 可被发现，无需白名单。
- [x] 不申请 `QUERY_ALL_PACKAGES`。
- [x] 侧边工具栏有「引擎模块管理」入口，点击进入模块管理页。
- [x] 页面展示已发现模块，启用开关默认关闭；未启用模块不扫描、不启动。
- [x] 发现模块只进入管理页列表，不直接加入扫描、不写游戏库。
- [x] 启用只写开关，不自动扫描、不立即入库；必须由用户手动触发下一次扫描。
- [x] 自动扫描只使用模块声明的 `extensions`/`markers`；主 App 不根据包名/应用名猜。
- [x] 有描述文件的模块可自动扫描入库；无描述文件的模块不参与自动扫描。
- [x] 扫描是唯一入库入口；不提供手动添加、模块选择器、目录导入或拖入。
- [x] 不提供手动绑定/重绑入口。
- [x] 启动使用显式组件，只传选中游戏文件夹路径。
- [x] `startActivity` 成功只标记为 `Dispatched`，不宣称游戏运行成功。
- [x] 模块卸载/禁用后游戏变 `UNKNOWN`，保留最后已知包名，标题/封面保留。
- [x] 重新安装/启用后重新扫描可恢复 `RUNTIME_MODULE`。
- [x] 发现查询失败时不会把全部游戏误降级。
- [x] 重扫保留可用绑定；内置强特征引擎不被抢走。
- [x] Room v2→v3 显式迁移，`3.json` 已生成并纳入变更。
- [x] 字段传播 11 项全部覆盖。
- [x] 三语言文案与硬编码 UI 字符串检查通过。
- [ ] 深色/复古玻璃/高级玻璃/平板侧栏的实际页面截图验收。
- [ ] Room v2→v3 迁移测试、Discovery/Launcher/Bindings 设备或 fake-PM 测试。
- [ ] `:app:lintDebug` / `:engine:lintDebug` 全绿（仍有仓库既有报错）。
- [ ] 示例模块 APK 真机验收（安装/启用/扫描/启动/卸载/重装/重扫）。

---

## 附录 A：模块模板

### A.1 Manifest

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.tyranor.runtime.example">

    <application
        android:label="Example Runtime"
        android:icon="@mipmap/ic_launcher">

        <activity
            android:name=".TyranorRuntimeActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="com.tyranor.runtime.action.LAUNCH" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
            <meta-data
                android:name="com.tyranor.runtime.MANIFEST"
                android:resource="@raw/tyranor_runtime" />
        </activity>
    </application>
</manifest>
```

### A.2 `res/raw/tyranor_runtime.json`

```json
{
  "protocol": 1,
  "label": "Example Runtime",
  "scan": {
    "match": "any",
    "extensions": [".mge"],
    "markers": ["game.dat", "data/*.pak"]
  }
}
```

### A.3 接收端伪代码

```kotlin
class TyranorRuntimeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val folder = intent.getStringExtra("com.tyranor.runtime.extra.FOLDER_PATH")
        val title = intent.getStringExtra("com.tyranor.runtime.extra.GAME_TITLE")
        // 校验 folder 非空、是目录、可读；然后启动自己的运行时
    }
}
```

---

## 附录 B：主 App 发现伪代码

```kotlin
object RuntimeModuleDiscovery {
    fun discover(context: Context): List<RuntimeModuleDescriptor> {
        val pm = context.packageManager
        val intent = Intent(RuntimeModuleContract.ACTION_LAUNCH)
            .addCategory(Intent.CATEGORY_DEFAULT)
        val flags = PackageManager.GET_META_DATA
        return pm.queryIntentActivities(intent, flags)
            .asSequence()
            .filter { it.activityInfo.packageName.startsWith(RuntimeModuleContract.PACKAGE_PREFIX) }
            .distinctBy { it.activityInfo.packageName }
            .mapNotNull { info -> readDescriptor(context, info) }
            .sortedBy { it.packageName }
            .toList()
    }
}
```

`readDescriptor`：

1. 取 `activityInfo` 的 label/icon/version。
2. 读 `META_MANIFEST` 资源（失败则 `scan = null`）。
3. 解析 JSON 并做上限校验（失败则 `scan = null`）。
4. 返回 `RuntimeModuleDescriptor`；不因描述失败丢弃模块。

---

## 附录 C：与现有计划的衔接

- 现有 [外置APK引擎模块接入方案.md](外置APK引擎模块接入方案.md) 固定包名、`game/settings` 协议，保持不变。
- 现有 [外置主机模拟器跳转接入方案.md](外置主机模拟器跳转接入方案.md) 保持不变。
- 本方案的模块管理页、错误文案、扫描开关属于新功能，不修改上述两套协议。
- 实现时同步更新 `SPEC.md`、`ARCHITECTURE.md`、`CONTEXT.md` 术语、`CHANGELOG.md`。
