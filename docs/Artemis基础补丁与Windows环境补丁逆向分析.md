# Artemis「添加基础补丁 / 添加Windows环境补丁」逆向分析

> 分析对象：Tyranor v2.3.4 原逆向包（`/Users/weiss/Desktop/decompiled/Tyranor_v2.3.4.apk`）
> 静态材料：apktool 产物 `/Users/weiss/Desktop/decompiled/tyranor_out`（smali）+ jadx 还原（`/tmp/tyranor_jadx`，本文引用 jadx 行号）
> 目的：还原两个补丁菜单项的完整调用链与文件改写逻辑，作为 TyranorNext Artemis 补丁能力的实现/对齐依据

---

## 1. 结论速览

| 项 | 添加基础补丁（add_patch） | 添加Windows环境补丁（add_patch2） |
|---|---|---|
| 核心实现 | `p048f5.F.J(game)`（smali `f5.1/F.smali:2292`） | `p048f5.F.K(game)`（smali `f5.1/F.smali:4550`） |
| 输入 | 游戏目录内 `*.pfs` / `*.pfs.NNN` 归档 | 同上 |
| 从封包提取 | `system.ini`、`list_windows*`、`movie*`（视频扩展名） | `system.lua`、`init.lua` |
| 文件改写 | `system.ini` 整体重写为最小 `[ANDROID]` 段；`list_windows*` 改名 `list_android*`，`.tbl` 翻转 tablet 开关 | 逐行把 `game.os = ...` 强制为 `game.os = "windows"` |
| 额外副作用 | 把任意 `<x>.pfs(.NNN)` **重命名为 `root.pfs(.NNN)`**（Android 运行时只认 root.pfs） | 同左（K 也走同一 `I()` 归一） |
| 触发入口 | Artemis 游戏详情抽屉菜单项；自动补丁（见 §6） | 仅手动菜单项 |
| 幂等性 | 重复执行会重新解包覆盖同名文件（`system.ini` 会被原封包内容再次重写） | 同上（`game.os` 行再次归一） |

一句话：**基础补丁把 PFS 里的启动所需文件"免封包"落盘并做 Android 化改写；Windows 环境补丁把 Lua 启动脚本的平台判定改成 Windows。**

---

## 2. 符号与文件对照

| 语义 | jadx（Java） | smali | 说明 |
|---|---|---|---|
| 游戏模型 | `M3/g.java` | `M3/g.smali` | `f5489b`=游戏目录路径，`f5493f`=类型字符串（`"Artemis"`），`f5500n`= `type == "Artemis"`，`f5503q`= fake game |
| 详情抽屉列表 | `p092l4/t.java:150-161` | `l4.1/t.smali` | `when(type)` 分支：`Artemis` 时追加 5 个条目 |
| 菜单项渲染 | `p098m4/b.java` | `m4.1/b.smali` | case 1 = `R.string.add_patch`（添加基础补丁）；case 2 = `R.string.add_patch2`（添加Windows环境补丁） |
| 点击→协程 | `M3/d.java:100-106` | `M3/d.smali` | case 9 → `p098m4.c`（基础补丁）；case 10 → `p098m4.d`（Windows 环境补丁）；均在 `z6.d.f23306c`（IO 调度器）上执行 |
| 补丁任务 | `p098m4/c.java` / `p098m4/d.java` | `m4.1/c.smali` / `d.smali` | 执行前弹持久 Toast「正在补丁……」，执行后 Toast 成功/失败 |
| 核心实现 | `p048f5/F.java` | `f5.1/F.smali` | 见下表 |
| 自动补丁 | `Q3/l.java:370-393`、`Q3/k.java`、`Q3/h.java` | `Q3/l.smali` 等 | 启动 Artemis 游戏时按设置询问/自动执行 `F.J` |

`F` 内部关键函数：

| 函数 | jadx 行 | smali 行 | 作用 |
|---|---|---|---|
| `H(File)` | ~370 | 2097 | PFS 文件名匹配：正则 `.*?\.pfs(\.[0-9]{3})*` |
| `I(File)` | 379 | 2179 | PFS 归一：把 `<x>.pfs(.NNN)` 重命名为 `root.pfs(.NNN)`，返回归一后的 File |
| `J(g)` | 392 | 2292 | 基础补丁 |
| `K(g)` | 698 | 4550 | Windows 环境补丁 |
| `M(RAF)` | 919 | 5933 | 小端 int32 读取（`b0 | b1<<8 | b2<<16 | b3<<24`） |
| `N(BufferedReader)` | 925 | 6001 | `readLines()`（逐行读文本） |
| `R(String, byte[])` | 996 | 6405 | 写文件（自动 `mkdirs`） |
| `W(g)` | — | 6712 | 「解包游戏」全量解包（另一菜单项，非本文重点） |

---

## 3. PFS 归档解析（两条补丁的公共底座）

### 3.1 归档收集与改名

```
H(name): name.lowercase() matches  .*?\.pfs(\.[0-9]{3})*      // root.pfs / x.pfs / x.pfs.001
I(file): if (!H(file.name)) return null
         base = file.name.substringBefore(".") + ".pfs"        // x.pfs.001 → x.pfs；root.pfs.002 → root.pfs
         target = File(file.absolutePath.replace(base, "root.pfs", ignoreCase = true))
         file.renameTo(target); return target
```

要点：
- **原版会把游戏目录里的 `x.pfs` 家族整体改名为 `root.pfs` 家族**（Android 版 Artemis 只加载 `root.pfs`）。`root.pfs` 家族自身是 no-op。
- 目录内**存在多个 pfs 家族时改名会互相覆盖**（原版未做冲突检查）；实际可运行的游戏一般只有一个启动封包 + 分卷。
- 补丁的遍历基于 `File.listFiles()` 快照；每个候选文件先 `I()` 改名再打开读写。

### 3.2 头部与密钥表

```
magic:  'p' 'f' <versionChar>        // 第 3 字节是字符数字，如 '8' → version = 8
tableLen = M()                        // 小端 int32
table    = read(tableLen bytes)       // 归档条目表原始数据
key      = SHA-1(table)               // 20 字节，用于数据 XOR
```

### 3.3 条目表

```
count = M()
repeat count:
    nameLen = M()
    name    = read(nameLen) as UTF-8，反斜杠 → 当前平台分隔符
    _       = M()                     // 保留字段（原版与 TyranorNext 均跳过）
    offset  = M()                     // 数据在归档内的偏移
    size    = M()                     // 数据长度
```

校验：`offset > 归档文件长度` → 直接 `return false`（整次补丁失败）。

### 3.4 数据解密与落盘

```
seek(offset); data = read(size)
if (version >= 8) for i in data: data[i] ^= key[i % 20]     // ★ XOR 条件 = 归档版本号 >= 8
str6 = 游戏目录 + sep + 条目相对路径
R(str6, data)                                                // 写文件（自动建目录）
```

> 说明：XOR 条件用**归档版本号**判断，不是数据长度。TyranorNext 现有实现用的是「数据长度 ≥ 8」，见 §8 差异表。

### 3.5 失败语义

| 情况 | 返回 |
|---|---|
| 游戏目录无法列出（`listFiles() == null`） | `true`（视为无事可做） |
| 目录内没有 PFS | `true` |
| 命中的 PFS 魔数不是 `pf` | `false` |
| 条目 offset 超出文件长度 | `false` |
| 正常结束 | `true` |

`J/K` 返回布尔值 → UI 显示 `add_patch_success` / `add_patch_failed`。

---

## 4. 基础补丁（`F.J`）

### 4.1 提取集合（仅这些条目会被写出）

| 命中条件 | 判据 |
|---|---|
| `system.ini` | **包含**（`contains`，大小写不敏感）"system.ini"（可命中子目录路径） |
| `list_windows` | 路径包含 "list_windows" |
| `movie` | 路径包含 "movie" **且**扩展名 ∈ {`.dat`, `.mp4`, `.ogv`, `.wmv`, `.mpg`, `.webm`} |

其它条目一律跳过（不落盘，也不影响 PFS 改名）。

### 4.2 `system.ini` 重写（覆盖式）

原版逻辑（`p048f5/F.java:505-540`）：

1. 用 `BufferedReader` 读原文件（此时文件是从 PFS 刚解出的原始内容）；
2. 逐行扫描，记录：
   - 是否出现过 `CHARSET`（任意行包含）→ 决定输出是否带 `CHARSET = UTF-8`；
   - `[WINDOWS]` 段的进入/退出（遇到 `[ANDROID]`/`[IOS]`/`[WASM]`/`[SWITCH]` 段头结束）；
   - 在 `[WINDOWS]` 段内记录最后一次 `WIDTH ...` / `HEIGHT ...` 整行（默认 `WIDTH = 1280` / `HEIGHT = 720`）；
3. **用新内容整体覆盖原文件**：

```ini
[ANDROID]
WIDTH = <来自 [WINDOWS] 的 WIDTH 行，缺省 1280>
HEIGHT = <来自 [WINDOWS] 的 HEIGHT 行，缺省 720>
SIDECUT = 0
BOOT = system/first.iet
FONT_CACHE_SIZE = 8388608
CHARSET = UTF-8        ; 仅当原文件出现过 CHARSET
```

即：**原文件其余所有段（含 `[WINDOWS]` 本体、键位映射等）全部丢弃**，只保留这一个 Android 引导段。

### 4.3 `list_windows*` 改名与 `.tbl` 改写

1. 文件名（路径）中 `list_windows` → `list_android`（大小写敏感替换）；
2. 若改名后文件名恰为 `list_android.tbl`，逐行处理：
   - 行 `trim().lowercase()` 后等于 `config_tablet=0` → 输出 `config_tablet=1,`；
   - 等于 `config_tabletui=0` → 输出 `config_tabletui=1,`；
   - 其它行原样输出（每行补 `\n`）。

作用：让 Android 端走平板 UI/触控布局。

### 4.4 视频文件

`movie*` 条目按扩展名过滤后**原样落盘**（不清洗、不改名），供 Android 端播放；`system.ini` 的 `[ANDROID]` 段并不引用它们（由引擎按路径自行查找）。

### 4.5 整体效果

- 免封包：启动所需的 `system.ini` / `list_android*` / 视频从 `root.pfs` 解出到游戏目录；
- 归档归一：`x.pfs(.NNN)` → `root.pfs(.NNN)`；
- 配置 Android 化：分辨率沿用 Windows 段、`SIDECUT=0`、`BOOT=system/first.iet`、字体缓存 8MB、可选 UTF-8。

---

## 5. Windows 环境补丁（`F.K`）

### 5.1 提取集合

| 命中条件 | 判据 |
|---|---|
| `system.lua` | 路径包含 "system.lua"（大小写不敏感） |
| `init.lua` | 路径包含 "init.lua" |

其余条目跳过；`I()` 的 PFS 归一改名同样生效（这是该补丁的副作用）。

### 5.2 文本改写

逐行匹配并替换（正则，`matches()` 全行匹配）：

```regex
[\t\s]+?game\.os[\s\t]+?=[^=].+
```

命中行整体替换为：

```lua
game.os = "windows"
```

其余行原样保留（`N()` 读行 → `StringBuilder` 拼接 → 覆盖写回）。作用是让 PC 移植脚本中的平台分支走 Windows 路径（Android 端默认 `game.os = "android"`，Lua 侧可能有差异化逻辑，例如存档目录、文件系统路径等）。

---

## 6. 自动补丁流程（启动时）

`Q3/l.c(game, skipCheck)` 是 Artemis 游戏的启动前置（`p048f5.F.J` 调用点之一）：

```
if (!skipCheck && game.type == "Artemis" && !File(game.path, "system.ini").exists()):
    strategy = prefs["artemisAutoPatch"]        // 设置项 artemis_auto_patch
    when (strategy):
        "true"  -> 自动补丁（Q3/l.d）
        "ask"   -> 弹「是否执行自动补丁」（should_make_auto_patch）
                    确认  -> Q3/l.d   （执行 F.J 后继续启动）
                    取消  -> Q3/l.c(game, true)（跳过补丁直接启动）
        "false" -> 直接启动
```

- 自动补丁只执行**基础补丁 `F.J`**，不含 Windows 环境补丁；
- 执行期间显示 `add_patch_auto_running`（「自动补丁中……」）；
- 触发条件基于 `system.ini` 是否存在：基础补丁成功后不会再触发（除非游戏目录被清理/重装）。

设置项（`p023c4/a.java:50`）：`artemisAutoPatch` 显示名 `artemis_auto_patch`（「自动补丁」），取值 `ask`/`true`/`false`，默认 `ask`。

---

## 7. Artemis 详情菜单项全集（上下文）

`p092l4/t.java:150-161`（`type == "Artemis"` 时按序追加）：

| 序 | 资源 | 文案 | 点击处理 |
|---|---|---|---|
| 1 | `artemis_version` | Artemis 版本 | 版本选择器（`p098m4.b` case 0） |
| 2 | `add_patch` | **添加基础补丁** | `M3/d` case 9 → `F.J` |
| 3 | `add_patch2` | **添加Windows环境补丁** | `M3/d` case 10 → `F.K` |
| 4 | `unpack_game` | 解包游戏 | `M3/d` case 11 → `F.W`（全量解包，非本文重点） |
| 5 | `delete_save` | 清除存档 | 存档清理（`p098m4.b` case 4 分支） |

---

## 8. 与 TyranorNext 现有实现的对照

TyranorNext 现有对应实现：`app/src/main/java/com/tyranor/next/core/unpack/ArtemisPfsUnpacker.kt` + `EngineLauncher` 的 autopatch 策略（`needsArtemisPatchConfirm` / `applyArtemisBasePatchIfNeeded`）。

| 维度 | 原版（Tyranor v2.3.4） | TyranorNext 现状 | 建议 |
|---|---|---|---|
| 基础补丁提取集合 | `system.ini` / `list_windows*` / `movie*`（6 种视频扩展） | `system.ini` / `system/*`（iet/lua/asb/tbl/glsl/ini/json/txt）/ `movie*`（同 6 种扩展） | 保留现状（`system/*` 对免封包更完整），可选对齐原版语义 |
| `system.ini` 处理 | **整体覆盖**为单个 `[ANDROID]` 段（丢弃原文件其余内容） | 在 `[ANDROID]` 段内**写入托管块**（`; TYRANOR_NEXT_ARTEMIS_SETTINGS_BEGIN/END`），保留文件其它内容并可还原 | 保留现状（可回滚、支持引擎设置覆盖） |
| `list_windows` 改名 | 有（`list_android*` + `.tbl` 翻转） | 有（`renameListWindows` + `flipListAndroidTblConfig`） | 已对齐 |
| PFS 归档改名 `root.pfs` | **有**（`I()`，含 `x.pfs.001` → `root.pfs.001`） | **无**（按原文件名读取，不重命名） | 按需：若遇到 "只有非 root 命名的 pfs 才可启动" 的机型/内核，再补；注意多家族冲突风险 |
| 数据 XOR 条件 | `归档版本号 >= 8` | `数据长度 >= 8` | 用真实样本核对两种条件是否等价；若发现旧版本归档（'0'~'7'）未加密，应改为版本号判断 |
| Windows 环境补丁 | 有（`system.lua`/`init.lua`，`game.os = "windows"`） | **无** | 建议实现（单游戏菜单/设置项 + 幂等改写） |
| 自动补丁 | 启动时按 `artemisAutoPatch` = ask/true/false，仅基础补丁 | 已有等价策略（ask 弹窗 → auto/off；含 clean 内核跳过），仅基础补丁 | 若要"自动补 Windows 环境补丁"需新增独立开关，不建议默认开 |
| 失败语义 | 魔数不符/越界 → `false`；无 PFS → `true` | 校验更严（表长/条目数/名字长度/边界/总量上限），单项失败跳过继续 | 保留现状（更安全） |

---

## 9. 关键代码索引

```
smali（tyranor_out）
  smali/f5.1/F.smali:2097   H(File)      PFS 名称匹配
  smali/f5.1/F.smali:2179   I(File)      PFS 改名 root.pfs
  smali/f5.1/F.smali:2292   J(M3.g)      基础补丁
  smali/f5.1/F.smali:4550   K(M3.g)      Windows 环境补丁
  smali/f5.1/F.smali:5933   M(RAF)       小端 int32
  smali/f5.1/F.smali:6001   N(Reader)    readLines
  smali/f5.1/F.smali:6405   R(String,B)  写文件
  smali/f5.1/F.smali:6712   W(M3.g)      解包游戏（全量）
  smali/m4.1/b.smali        UI 菜单项（add_patch / add_patch2）
  smali/Q3/l.smali          自动补丁决策 + 启动

jadx（/tmp/tyranor_jadx/sources）
  p048f5/F.java:392         J（基础补丁）
  p048f5/F.java:698         K（Windows 环境补丁）
  p048f5/F.java:379         I；p048f5/F.java:919 M；:925 N；:996 R
  Q3/l.java:370             自动补丁询问（should_make_auto_patch）
  Q3/l.java:455             Q3/l.d 自动补丁入口（add_patch_auto_running）
  Q3/k.java:49              自动补丁协程 → F.J
  M3/d.java:100/103         case 9/10 → p098m4.c / p098m4.d
  p098m4/b.java:101/122     两个菜单项渲染
  p092l4/t.java:150-161     Artemis 菜单项挂载顺序
  p023c4/a.java:50          artemisAutoPatch 设置项（ask/true/false）

字符串资源（res/values/strings.xml）
  add_patch            添加基础补丁 / Add base patch
  add_patch2           添加Windows环境补丁 / Patch As Windows Env
  add_patch_running    正在补丁……
  add_patch_success    补丁成功
  add_patch_failed     补丁失败
  add_patch_auto_running 自动补丁中……
  should_make_auto_patch 是否执行自动补丁
  artemis_auto_patch   自动补丁
```

---

## 10. 复现与验证建议

1. 取一个 PFS 打包的 Artemis 游戏样本，备份后分别执行原版两个补丁，比对：
   - 游戏根新增文件清单（应只有 §4.1 / §5.1 的集合）；
   - `system.ini` 内容（验证"覆盖式重写"与 `[WINDOWS]` WIDTH/HEIGHT 继承）；
   - `list_android.tbl` 的 `config_tablet/config_tabletui`；
   - `system.lua`/`init.lua` 的 `game.os` 行；
   - 原 `*.pfs` 的改名结果。
2. 用同一份样本在 TyranorNext 上跑基础补丁，比对差异（重点：`system.ini` 是否保留托管块、是否需要 `root.pfs` 改名、XOR 条件）。
3. 若要在 TyranorNext 增加「Windows 环境补丁」：建议作为单游戏菜单项 + `PerGameSettingsStore` 布尔覆盖（默认关），实现放在 `ArtemisPfsUnpacker`（复用 `unpackPfs` + `shouldExtractEntry` 回调即可，无需新解析器）。
