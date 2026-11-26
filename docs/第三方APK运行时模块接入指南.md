# 第三方 APK 运行时模块接入指南

> 面向第三方模块开发者：发现、启用、扫描入库、启动，以及可选的单游戏设置和游戏内文件存档接入。
> 启动协议版本：**1**（可选能力分别使用自己的 version）。配置复用现有覆盖记录，无新增 schema 迁移。
> 内部实现与设计取舍见 [第三方APK运行时模块接入方案.md](第三方APK运行时模块接入方案.md)；领域术语见 [CONTEXT.md](../CONTEXT.md)。

---

## 1. 先理解三件事

1. **模块是一个独立 APK**，不是主 App 的插件或动态代码。
2. 主 App 负责**发现、启用、按声明扫描和启动交棒**；模块还可声明设置项及游戏内存档子目录，接入对应管理能力。
3. 游戏运行、存档格式、按键与画面由模块负责；主 App 保存并传递用户配置，统一管理已声明的文件存档，不接管模块私有数据。

一条完整的接入链路：

```text
安装模块 APK
  → 主 App 通过 action + 包名前缀发现
  → 用户在「引擎模块管理」页启用（默认关闭）
  → 用户手动扫描游戏库
  → 主 App 按模块声明的 extensions / markers 识别游戏
  → 用户启动游戏
  → 主 App 用显式组件把文件夹路径交给模块
  → 模块自己运行游戏
```

---

## 2. 最小接入：一个 Activity + 一个 action

### 2.1 包名

模块包名必须以 **`com.tyranor.runtime.`** 开头，至少多一段：

```text
com.tyranor.runtime.example     ✅
com.tyranor.runtime             ❌ 没有后缀
com.example.runtime             ❌ 前缀不对
```

包名是模块的稳定身份。**不要随意改包名**：主 App 用它绑定游戏；改包名会被视为另一个模块，已有游戏不会自动迁移。

### 2.2 AndroidManifest.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.tyranor.runtime.example">

    <application
        android:label="My Runtime"
        android:icon="@mipmap/ic_launcher">

        <activity
            android:name=".LaunchActivity"
            android:exported="true">

            <intent-filter>
                <action android:name="com.tyranor.runtime.action.LAUNCH" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>

            <!-- 可选：扫描描述。没有它模块仍能被发现/启用，但无法自动入库。 -->
            <meta-data
                android:name="com.tyranor.runtime.MANIFEST"
                android:resource="@raw/tyranor_runtime" />
        </activity>
    </application>
</manifest>
```

要点：

- Activity 必须 `android:exported="true"`。
- 必须声明 `com.tyranor.runtime.action.LAUNCH` 和 `CATEGORY_DEFAULT`。
- 一个包只取一个启动 Activity：优先带 `com.tyranor.runtime.MANIFEST` meta-data 的；否则按 Activity 名排序取第一个。
- 不建议一个包塞多个“模块”。一个包对应一个模块身份。
- 示例 Manifest 的 `package` 仅用于表达包名；新版本 AGP 请在 `build.gradle.kts` 的 `namespace` / `applicationId` 配置同样的包名。

### 2.3 接收启动参数

主 App 启动模块时使用**显式组件**，基础参数如下；声明有效设置能力时额外提供配置 JSON：

| extra | 类型 | 说明 |
| --- | --- | --- |
| `com.tyranor.runtime.extra.FOLDER_PATH` | String | 游戏文件夹绝对路径，必填 |
| `com.tyranor.runtime.extra.GAME_TITLE` | String | 游戏标题，可选 |
| `com.tyranor.runtime.extra.PROTOCOL_VERSION` | Int | 主 App 协议版本，当前为 1 |
| `com.tyranor.runtime.extra.SETTINGS` | String | 可选，只有有效设置声明时提供，格式见第 10 节 |

最小接收代码：

```kotlin
class LaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val folder = intent.getStringExtra("com.tyranor.runtime.extra.FOLDER_PATH")
        val title = intent.getStringExtra("com.tyranor.runtime.extra.GAME_TITLE")
        val protocol = intent.getIntExtra("com.tyranor.runtime.extra.PROTOCOL_VERSION", 1)

        Log.i("MyRuntime", "folder=$folder title=$title protocol=$protocol")

        if (folder.isNullOrBlank()) {
            // 没有文件夹路径则结束；不要把配置 JSON 当作文件路径或待执行命令
            finish()
            return
        }

        // 校验目录存在、可读，然后启动你自己的运行时
        // 注意：模块需要自己的存储权限，主 App 的权限不能转授给你
    }
}
```

主 App 不会传：

- JoiPlay 的 `game` / `settings` JSON；可选配置使用独立的命名空间 extra；
- 存档路径、按键映射、画面参数；
- SAF URI 的持久授权。

模块必须主动应用配置参数并按自己的 saves 声明读写存档，主 App 不会替模块修改其配置文件或存档格式。

---

## 3. 让游戏自动入库：扫描描述

### 3.1 为什么需要它

主 App 不知道你的引擎长什么样。它不会根据包名、应用名或图标猜游戏，也不执行你的扫描代码。

自动扫描的**唯一来源**是模块在 `res/raw/tyranor_runtime.json` 里声明的静态特征。

> 没有扫描描述 = 不能自动入库。由于主 App 不提供手动添加，模块也就无法把新游戏加入库。

### 3.2 一个完整示例

`res/raw/tyranor_runtime.json`：

```json
{
  "protocol": 1,
  "label": "My Runtime",
  "scan": {
    "match": "all",
    "extensions": [".mygame"],
    "markers": ["game.dat", "data/*.pak"]
  }
}
```

这个声明表示：目录里必须同时满足

- 有直接子文件以 `.mygame` 结尾；
- 根目录有 `game.dat`；
- `data/` 下有任意 `.pak` 文件。

### 3.3 字段说明

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `protocol` | 否 | 协议版本，当前填 `1`；缺省按 1 处理 |
| `label` | 否 | 模块展示名；缺省用 APK 的应用名。只在「引擎模块管理」页展示，游戏卡固定显示 `Plugin` |
| `scan` | 否 | 没有 `scan` 时不参与自动扫描 |
| `scan.match` | 否 | `"any"`（默认）或 `"all"` |
| `scan.extensions` | 否 | 扩展名数组，必须带前导点，如 `.mygame` |
| `scan.markers` | 否 | 相对游戏根目录的标记文件/目录路径 |

**上限**：

- JSON 文件 ≤ 64 KiB；
- `extensions` ≤ 32 项，每项 ≤ 16 字符；
- `markers` ≤ 64 项，每项 ≤ 256 字符；
- 非法模式会被丢弃；如果最终没有任何有效模式，视为没有扫描能力。

**`extensions` 规则**：

- 只匹配目录的**直接子文件**，不递归；
- 大小写不敏感；
- 支持多段后缀，如 `.tar.gz`；
- 不写 `*.mygame`，只写 `.mygame`。

**`markers` 规则**：

- 相对游戏根目录，可以指向文件或目录；
- 中间路径段必须是字面量；只有最后一段可以使用 `*` / `?`；
- 最多一层 `/`，例如 `data/*.pak`、`game/script.rpy`；
- 不支持 `**` 递归；
- 不允许绝对路径、`..`、反斜杠、空串；
- 纯通配 marker（如 `*`、`?`）会被拒绝；
- 大小写不敏感；
- `data/*.pak` 只检查 `data/` 下一层，不递归。

**`match` 规则**：

- `any`：扩展名谓词命中 **或** 任意 marker 命中；
- `all`：扩展名谓词命中（如果声明了扩展名）**且** 所有 marker 命中；
- 两者都为空：不匹配。

### 3.4 常用例子

| 游戏目录内容 | 声明 | 结果 |
| --- | --- | --- |
| `story.mygame`、`game.dat`、`data/pack.pak` | `match: all` + `.mygame` + `game.dat` + `data/*.pak` | 命中 |
| 只有 `story.mygame` | 同上 | 不命中（缺 marker） |
| 只有 `game.dat` 和 `data/pack.pak` | 同上 | 不命中（缺扩展名） |
| `game.dat` | `match: any` + `markers: ["game.dat"]` | 命中 |
| `data/pack.pak` | `match: any` + `markers: ["data/*.pak"]` | 命中 |
| `data/sub/pack.pak` | 同上 | 不命中（只查一层） |
| `renpy/` 目录 | `markers: ["renpy"]` | 命中（目录也算） |

### 3.5 主 App 的扫描优先级

- **优先匹配已启用第三方模块的扫描声明**；命中后采用模块结果，不再执行内置识别。
- 模块未命中时才执行内置识别；模块可优先于 KRKR / ONS / RPGM / Ren'Py / Siglus 等目录型游戏识别。
- 扫描根目录本身不参与模块匹配；游戏必须位于扫描根下的子目录。
- 含主机 ROM（PSP/Switch）或散装 SWF 的目录会交给对应机制，模块扩展名不能吞并它们。
- 多模块同时命中时，主 App 按「`all` 优先于 `any` → marker 数量多优先 → 包名排序」确定性选择，不会在扫描时弹窗。
- 模块声明优先于内置识别，应使用精确特征；如果两个模块都很宽泛，建议缩小 marker，用户无法手动改绑。

---

## 4. 启用、扫描、入库

1. 安装模块 APK。
2. 主 App 「工具 → 引擎模块管理」出现该模块，**默认关闭**。
3. 用户打开开关：只写入启用状态，**不自动扫描、不立即入库**。
4. 用户回游戏页手动扫描：只有已启用 + 有扫描描述的模块参与。
5. 识别到的目录以 `EngineType.RUNTIME_MODULE` 入库，游戏卡引擎名显示 **Plugin**。
6. 启动游戏时，主 App 把文件夹路径交给模块。

这意味着：

- 模块安装后不会自动产生游戏；
- 启用本身也不会自动扫描；
- 用户不重新扫描，就看不到新模块的游戏；
- 没有手动添加入库入口，扫描是唯一入口。

---

## 5. 生命周期与卸载

### 5.1 默认关闭

- 新发现模块默认关闭；
- 同包名更新/重装保留原启用状态；
- 包名变更视为新模块，默认关闭；
- 用户可在「引擎模块管理」页随时启用/禁用。

### 5.2 模块卸载或禁用

- 绑定到该模块的游戏降级为 `EngineType.UNKNOWN`；
- 游戏记录、标题、封面、最近时间保留；
- 最后已知包名会保留用于重扫合并，但不会用于启动；
- 用户重新安装/启用后，需要**重新扫描**才能恢复为 `RUNTIME_MODULE`；
- 没有自动重绑，也没有“更换模块”按钮。

### 5.3 模块更新

- 同包名更新：启用状态和绑定保留；启动前重新读取 Activity 和描述文件；
- 描述文件暂时失效：已有绑定不会因此被删除，但新游戏不会自动入库；
- Activity 改名/被禁用：启动会报“入口不可用”，并触发降级；请保持入口稳定；
- 包名变更：旧游戏不会自动迁移；用户需要重新扫描。

### 5.4 私有存档

模块私有的存档、缓存和数据库可能随卸载被系统删除。主 App 不接管、不备份。需要长期保存的数据请放在模块能控制的位置，并自行提示用户。

---

## 6. 启动协议细节

### 6.1 显式组件

主 App 使用：

```kotlin
Intent("com.tyranor.runtime.action.LAUNCH").apply {
    component = ComponentName(packageName, activityName)
    putExtra("com.tyranor.runtime.extra.FOLDER_PATH", folderPath)
    putExtra("com.tyranor.runtime.extra.GAME_TITLE", title)
    putExtra("com.tyranor.runtime.extra.PROTOCOL_VERSION", 1)
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
```

不会使用隐式 action 启动，也不会附加 URI 读写授权 flag。

### 6.2 “启动成功”的含义

主 App 的 `Dispatched` 只表示 **Intent 已经交给系统**：

- 不代表模块已经进入游戏；
- 不代表模块没有崩溃；
- 不代表模块已经拿到存储权限；
- 模块窗口停在加载页、权限页或直接崩溃，主 App 都不会收到回调。

请让模块自己处理错误提示和恢复。

### 6.3 路径与权限

- 主 App 传的是**绝对文件夹路径**，不是 SAF URI。
- 模块需要自己的存储权限；主 App 的 `MANAGE_EXTERNAL_STORAGE` 不能转授。
- 如果主 App 无法把游戏 URI 解析为真实路径，会返回 `PathUnresolved`，不会启动模块。
- 仅 SAF 授权的目录可能无法解析为真实路径；这种游戏无法通过本协议启动。

### 6.4 不要依赖的载荷

主 App 不会传：

- JoiPlay 的 `game` / `settings` JSON；
- 存档目录；
- 虚拟按键/手柄配置；
- 画面过滤参数；
- 模块版本/签名校验结果。

模块内部配置仍可自行维护；有效 `SETTINGS` 中的本次参数应优先于本地同名值。没有设置声明的旧模块沿用原来的配置方式。

---

## 7. 模块作者自测清单

### 7.1 准备一个假游戏

在设备上找一个已有的扫描根目录，在它下面创建子目录（不要在扫描根本身）：

```text
/sdcard/Games/MyRuntimeSmoke/
├── smoke.dat
└── story.mygame
```

对应描述文件可以声明：

```json
{
  "protocol": 1,
  "label": "My Runtime",
  "scan": {
    "match": "any",
    "extensions": [".mygame"],
    "markers": ["smoke.dat"]
  }
}
```

### 7.2 自测步骤

1. 安装主 App 和模块 APK。
2. 打开「工具 → 引擎模块管理」，确认模块被发现，开关默认关闭。
3. 打开开关；确认没有自动扫描、游戏数仍为 0。
4. 回游戏页手动扫描；确认假游戏入库，卡片引擎名显示 `Plugin`。
5. 启动游戏；确认模块收到正确的 `FOLDER_PATH`、`GAME_TITLE`、`PROTOCOL_VERSION`。
6. 禁用模块；确认有绑定游戏时出现确认弹窗，确认后游戏变 `UNKNOWN`。
7. 重新启用并重扫；确认游戏恢复。
8. 卸载模块；确认游戏变 `UNKNOWN`，且重新安装/启用/重扫后可恢复。

### 7.3 快速排障

| 现象 | 优先检查 |
| --- | --- |
| 模块管理页没有模块 | 包名是否以 `com.tyranor.runtime.` 开头；Activity 是否 `exported=true`；action 和 `CATEGORY_DEFAULT` 是否声明；模块是否真的安装；点右上角刷新 |
| 发现模块但显示“未声明扫描特征” | `res/raw/tyranor_runtime.json` 是否存在；meta-data 是否指向 `@raw/tyranor_runtime`；JSON 是否合法；`scan` 是否有有效模式；是否超过 64 KiB |
| 启用后扫描不到假游戏 | 模块是否已启用；是否手动触发扫描；游戏是否在扫描根的子目录而不是根本身；目录是否含主机 ROM/SWF；marker 是否只匹配一层 |
| 卡片引擎名不是模块名 | 这是预期行为：游戏卡固定显示 `Plugin`；模块名只在「引擎模块管理」页 |
| 启动报“路径无法解析” | 游戏是否只有 SAF 授权；是否能解析为真实路径 |
| 启动报“模块不可用/未启用” | 模块是否被卸载/禁用；重新安装/启用后是否重扫 |
| 启动后模块自己失败 | 主 App 只能确认 Intent 已交棒；请检查模块自己的日志、权限和入口 |

---

## 8. 兼容性与限制

### 8.1 主 App 不验证什么

- 不校验签名；
- 不校验版本号；
- 不校验包内容哈希；
- 不校验协议兼容性（`protocol` 目前只做展示/记录）；
- 不要求模块来自某个商店或开发者。

信任模型是：**用户主动安装模块，并在「引擎模块管理」页手动启用**。包名前缀是命名空间约定，不是安全边界。

### 8.2 不支持的能力

- 主 App 不提供手动添加/手动绑定；
- 不执行模块代码做扫描；
- 不支持正则、文件内容魔数、递归 `**` 扫描；
- 不传 SAF URI 持久授权；
- 不接管模块私有存档、运行实现、按键与画面；只有有效声明才提供单游戏配置和游戏内文件存档管理；
- 不提供模块结果回调；
- 不自动迁移包名变更后的游戏绑定。

### 8.3 给模块作者的建议

- 使用尽量**独特**的扩展名和 marker，避免 `.dat`、`*` 这类宽泛规则。
- 优先用 `match: "all"` + 一个独特 marker，降低误判。
- 保持包名、action、入口 Activity 稳定。
- 模块自己处理存储权限、运行错误、存档格式及设置应用；有统一存档需求时声明独立的游戏内子目录。
- 在模块里记录收到的 `FOLDER_PATH`，方便用户排障。
- 不要把 `com.tyranor.runtime.` 前缀用于非模块应用。

---

## 9. 最小检查表

发布前确认：

- [ ] 包名以 `com.tyranor.runtime.` 开头；
- [ ] 入口 Activity `android:exported="true"`；
- [ ] 声明 `com.tyranor.runtime.action.LAUNCH` + `CATEGORY_DEFAULT`；
- [ ] 可选 `res/raw/tyranor_runtime.json` 合法且 ≤ 64 KiB；
- [ ] `extensions` 带前导点，`markers` 只用合法相对路径；
- [ ] 模块能读取 `FOLDER_PATH`，并处理空路径/权限失败；
- [ ] 基础启动不依赖可选设置；声明 settings 后正确消费命名空间配置，声明 saves 后只在指定游戏内子目录读写受管存档；
- [ ] 在真机上走通“发现 → 默认关 → 启用 → 手动扫描 → 启动 → 禁用/卸载 → 重扫恢复”。

---

## 10. 单游戏设置（可选）

在原描述文件顶层追加 `settings`，不改变原 scan：

```json
{
  "settings": {
    "version": 1,
    "items": [
      {
        "key": "display", "type": "card", "title": "Display",
        "description": "Rendering options",
        "items": [
          { "key": "vsync", "type": "boolean", "title": "VSync", "default": true },
          { "key": "render_scale", "type": "slider", "title": "Render scale",
            "default": 1.0, "min": 0.5, "max": 2.0, "step": 0.1 }
        ]
      },
      { "key": "renderer", "type": "enum", "title": "Renderer", "default": "auto",
        "options": [{ "value": "auto", "title": "Auto" }, { "value": "software", "title": "Software" }] },
      { "key": "config_file", "type": "file", "title": "Configuration file", "default": "" },
      { "key": "data_folder", "type": "folder", "title": "Data folder", "default": "" },
      { "key": "player_name", "type": "text", "title": "Player name", "default": "", "maxLength": 64 },
      { "key": "fps_limit", "type": "integer", "title": "FPS limit", "default": 60, "min": 30, "max": 120 }
    ]
  }
}
```

主 App 在游戏抽屉显示「引擎设置」，按游戏 URI + 模块包名保存覆盖值。优先级为合法覆盖 → 模块默认值，没有模块全局配置。列表末尾由主 App 提供「恢复默认设置」行，确认后清除当前游戏的覆盖草稿，保存后生效；模块无需声明恢复按钮。模块升级导致声明变化时保留未保存草稿供用户核对。

声明约束：

- `version` 必须为整数 1；最多 32 项，key 为 `[A-Za-z][A-Za-z0-9_]{0,63}`，同名采用第一个合法项。
- 顶层 `items` 可混合设置项与 `type: "card"` 容器；卡片使用 `key`、`title`、可选 `description` 和子 `items`。卡片不嵌套，最多 16 张；设置项总数（含卡片内子项）最多 32。旧版平铺 `items` 仍可用。旧版 TYN 不认识 `card`，会跳过整个卡片及其中所有子项；也会跳过新增的 `slider`、`file` 和 `folder` 类型，不显示或下发这些值。模块若需兼容旧版 TYN，应将已有字段（例如 `vsync`）保留为顶层设置项，不要移入卡片。
- 每个设置项必需 `title`（1–128）和合法的 `default`，可选 `description`（最多 512）。TYN 自动统一设置项与卡片标题（15sp）、说明与值（14sp）的排版，模块声明不包含字体或字号字段。
- 布尔必须是 JSON 布尔，`default: false` 表示开关默认关闭；枚举最多 32 个选项，value 长度 1–64 且唯一，default 须在选项中。
- 整数须声明 Int 范围内的 min/max/default；不接受字符串或小数作整数。
- `slider` 的 min/max/default 是有限数字，min 必须小于 max。带 `step` 时，step 必须大于 0、范围须包含整数个步进间隔（最多 10,000 个），default 须落在步进网格上；滑条按刻度索引映射，原始值仍以 Double 校验并作为 JSON 数字传递，因此不受绝对值的 Float 精度限制。每个刻度转成 Double 后仍须落在原步进网格并映回同一索引；Double 范围计算溢出、刻度无法区分或转换后偏离网格时，该项会被丢弃。未声明 `step` 时按连续 Float 范围映射，min/max 必须能形成有限且大于 0 的 Float 跨度，否则该项会被丢弃。
- `file` / `folder` 的 default 是空字符串或绝对文件路径；选择器只接受 TYN 能映射为本地路径的 SAF 文件/目录，最大路径长度默认 1024，可用 `maxLength` 调整到 1–4096。主 App 不传 SAF URI 授权，模块仍须自行具备访问该路径的存储权限；不能映射为本地路径的云盘等 provider 不可用。
- 文本为单行合法 Unicode，maxLength 为 1–1024，缺省 256；保留首尾空格，拒绝 C0/C1、U+2028/U+2029 和孤立代理项。长度以 UTF-16 code unit 计算。
- 配置 JSON 上限 48 KiB；解析时按各项允许值的序列化上界累计预算，超限关闭设置能力，不影响合法 scan/saves。描述文件整体仍受 64 KiB 限制。
- 设置文案直接使用模块声明字符串，主 App 的固定操作文案提供三语言。字体排版由 TYN 负责，不进入模块协议或启动载荷。

收到的 extra 是完整生效配置。`values` 中的值按声明类型编码：`boolean` 为 JSON 布尔值，`enum` / `text` / `file` / `folder` 为 JSON 字符串，`integer` 为整数，`slider` 为 JSON 数字（可为整数或小数）；TYN 不会把它们统一转成字符串。例如：

```json
{
  "version": 1,
  "values": {
    "vsync": false,
    "render_scale": 1.0,
    "renderer": "auto",
    "config_file": "",
    "data_folder": "",
    "player_name": "",
    "fps_limit": 90
  }
}
```

卡片标题和说明只用于 TYN 设置页，不会出现在启动载荷中；卡片内的字段仍按 key 平铺。`file` / `folder` 的值是绝对路径字符串，`slider` 的值是数字。

模块读取新值时仍从同一个 `values` 对象读取，并先检查原始 JSON 类型，例如 `(values?.opt("render_scale") as? Number)?.toDouble() ?: 1.0` 和 `values?.opt("config_file") as? String ?: ""`。类型不符时使用模块声明的默认值。路径选择只保存路径，不会把 SAF URI 授权转交给模块。

接收示意：

```kotlin
val raw = intent.getStringExtra("com.tyranor.runtime.extra.SETTINGS")
val payload = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
val values = payload?.takeIf { it.opt("version") == 1 }?.optJSONObject("values")
val vsync = values?.opt("vsync") as? Boolean ?: true
// 同样按白名单、类型和约束读取其它字段；参数优先于模块本地同名配置。
```

未收到 extra 时沿用模块原有默认行为；JSON 损坏或 version 不支持时忽略整份，已知版本的未知 key 忽略，已知字段非法时采用模块声明默认值。不要对外部配置执行命令、反射加载类或任意路径写入。

## 11. 游戏内文件存档（可选）

同一描述文件可独立追加：

```json
{ "saves": { "version": 1, "directory": "data/save" } }
```

`directory` 相对 `FOLDER_PATH`，每个模块声明一个固定子目录。主 App 统一提供导入、导出、删除、版本管理和 WebDAV 同步；模块必须实际在该子目录保存，目录中不能混放游戏资源。只声明 saves、不声明 settings 也可使用存档管理。

- 路径最多 256 个 UTF-16 code unit、8 层，用 `/` 分隔并保留大小写。拒绝空/根路径、首尾 `/`、空段、`.`/`..`、绝对路径、盘符/URI、反斜杠、通配符及控制字符。
- 游戏根下符号链接被拒绝，文件操作只覆盖声明子目录；无法证明归属的事务相邻路径不得覆盖或删除。
- 首次尚无存档时不创建目录；不可读、权限不足、卷离线、路径无法验证与空存档是不同状态。
- 两个应用各自取得文件访问权限。主 App 不接管模块私有存档，不发送存档重定向参数，也不会替模块搬迁数据。
- 外置游戏启动后，导入/导出/删除/版本/同步操作须先确认游戏已退出；返回主 App 不等于退出。关闭 WebDAV 或主 App 重启也不解除保护。失效模块和已移除条目的旧会话仍可确认。
- 云端引擎命名空间按模块包名隔离，同名游戏仍沿用现有云端标识冲突处理。存档目录声明变化不自动迁移，不直接把旧版本还原到新位置。
- scan/settings/saves 各自容错；非法 saves 只关闭存档能力，整个 JSON 无效才同时失效。未声明有效 saves 的旧模块保持原行为。

可运行的基础设置与存档示例见 [tools/runtime-module-example](../tools/runtime-module-example/README.md)，不加入主 App 构建。真实模块需自行验收参数效果和存档格式兼容性。

## 12. 游戏目录内文件导入（可选）

在描述文件顶层增加独立的 `fileImports`，与 `scan`、`settings`、`saves` 互不依赖：

```json
{
  "fileImports": {
    "version": 1,
    "items": [
      { "key": "patches", "title": "导入补丁", "directory": "data/patches", "multiple": true },
      { "key": "config", "title": "替换配置", "directory": ".", "fileName": "game.ini" }
    ]
  }
}
```

- 最多 32 个有效声明项；`key` 格式同设置项，重复时取第一个有效项；`title` 为 1–128 字符。
- `directory` 是游戏目录内相对目录，`.` 表示游戏根；目录和文件名区分大小写地按声明使用，由底层文件系统决定是否区分大小写。拒绝绝对路径、空段、`..`、反斜杠和符号链接。
- 无 `fileName` 时使用系统选择器提供的文件名，`multiple` 默认为 false，可设为 true（一次最多 32 个，无重复目标）。提供 `fileName` 时只能是单个文件名，且只允许单选。
- 用户在游戏抽屉的「引擎设置」选取文件并确认实际新增／覆盖列表。子目录可在确认后创建；游戏目录必须能够映射为本地路径且允许 App 直接写入。每个文件暂存完整后才提交，批量不保证整体回滚。
- 不随启动 Intent 下发，不影响设置草稿；旧版主 App 忽略该字段。

## 13. 参考

- 内部设计：[第三方APK运行时模块接入方案.md](第三方APK运行时模块接入方案.md)
- 当前行为边界：[SPEC.md](../SPEC.md)
- 术语：[CONTEXT.md](../CONTEXT.md)
- 主 App 的模块管理入口：「工具 → 引擎模块管理」
