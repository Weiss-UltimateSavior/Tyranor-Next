# 第三方 APK 运行时模块接入指南

> 面向第三方模块开发者：告诉你怎么做一个能被 TyranorNext 发现、启用、扫描入库并启动的 APK 模块。
> 协议版本：**1**。主 App 基线：schema v3。
> 内部实现与设计取舍见 [第三方APK运行时模块接入方案.md](第三方APK运行时模块接入方案.md)；领域术语见 [CONTEXT.md](../CONTEXT.md)。

---

## 1. 先理解三件事

1. **模块是一个独立 APK**，不是主 App 的插件或动态代码。
2. 主 App 只做四件事：**发现模块、让用户启用、按模块声明扫描游戏、把游戏文件夹路径交给模块**。
3. 游戏运行、设置、存档、按键、画面都由模块自己负责；主 App 不接管。

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

主 App 启动模块时使用**显式组件**，只传三个 extra：

| extra | 类型 | 说明 |
| --- | --- | --- |
| `com.tyranor.runtime.extra.FOLDER_PATH` | String | 游戏文件夹绝对路径，必填 |
| `com.tyranor.runtime.extra.GAME_TITLE` | String | 游戏标题，可选 |
| `com.tyranor.runtime.extra.PROTOCOL_VERSION` | Int | 主 App 协议版本，当前为 1 |

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
            // 没有文件夹路径，直接结束；主 App 不会传 settings/存档/按键参数
            finish()
            return
        }

        // 校验目录存在、可读，然后启动你自己的运行时
        // 注意：模块需要自己的存储权限，主 App 的权限不能转授给你
    }
}
```

主 App 不会传：

- `game` / `settings` JSON；
- 存档路径、按键映射、画面参数；
- SAF URI 的持久授权。

所以模块自己的设置、存档、输入、画面策略都要在模块内完成。

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

- 先跑内置引擎识别；**KRKR / ONS / RPGM / Ren'Py / Ruffle / Siglus 等强特征结果不会被模块覆盖**。
- 只有内置结果为 `UNKNOWN` 或 `WEB_OTHER` 时才尝试模块匹配。
- 扫描根目录本身不参与模块匹配；游戏必须位于扫描根下的子目录。
- 含主机 ROM（PSP/Switch）或散装 SWF 的目录会交给对应机制，模块扩展名不能吞并它们。
- 多模块同时命中时，主 App 按「`all` 优先于 `any` → marker 数量多优先 → 包名排序」确定性选择，不会在扫描时弹窗。
- 如果两个模块都很宽泛，建议缩小 marker；用户无法手动改绑。

---

## 4. 启用、扫描、入库

1. 安装模块 APK。
2. 主 App 侧边栏「妙妙工具 → 引擎模块管理」出现该模块，**默认关闭**。
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

- `game` / `settings` JSON；
- 存档目录；
- 虚拟按键/手柄配置；
- 画面过滤参数；
- 模块版本/签名校验结果。

模块自己的设置请用自己的 SharedPreferences/数据库/文件维护。

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
2. 打开「妙妙工具 → 引擎模块管理」，确认模块被发现，开关默认关闭。
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
| 启用后扫描不到假游戏 | 模块是否已启用；是否手动触发扫描；游戏是否在扫描根的子目录而不是根本身；目录是否含主机 ROM/SWF；marker 是否只匹配一层；内置引擎是否已经识别为强特征 |
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
- 不覆盖内置强特征引擎的识别结果；
- 不执行模块代码做扫描；
- 不支持正则、文件内容魔数、递归 `**` 扫描；
- 不传 SAF URI 持久授权；
- 不接管模块的存档、设置、按键、画面；
- 不提供模块结果回调；
- 不自动迁移包名变更后的游戏绑定。

### 8.3 给模块作者的建议

- 使用尽量**独特**的扩展名和 marker，避免 `.dat`、`*` 这类宽泛规则。
- 优先用 `match: "all"` + 一个独特 marker，降低误判。
- 保持包名、action、入口 Activity 稳定。
- 模块自己处理存储权限、错误提示、存档和设置。
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
- [ ] 模块不依赖主 App 传 settings/存档/按键；
- [ ] 在真机上走通“发现 → 默认关 → 启用 → 手动扫描 → 启动 → 禁用/卸载 → 重扫恢复”。

---

## 10. 参考

- 内部设计：[第三方APK运行时模块接入方案.md](第三方APK运行时模块接入方案.md)
- 当前行为边界：[SPEC.md](../SPEC.md)
- 术语：[CONTEXT.md](../CONTEXT.md)
- 主 App 的模块管理入口：侧边栏「妙妙工具 → 引擎模块管理」
