# 《RPGMaker-MZ-移植评估》与《RPGMaker-MZ-移植实施方案》审阅意见

> 审阅对象：[RPGMaker-MZ-移植评估.md](RPGMaker-MZ-移植评估.md)（458 行，下称「评估稿」）、
> [RPGMaker-MZ-移植实施方案.md](RPGMaker-MZ-移植实施方案.md)（552 行，下称「方案稿」）。
> 审阅方式：逐条对照本仓库当前代码（`fix/nwjs-umd-global` 分支 HEAD）核实文档中的行号引用与事实论断；
> 不复现文档引用的外部取证（JoiPlay 逆向、GameNative 源码、MZ corescript 镜像），这部分按「文档自述」采信。
> 审阅分支：`docs/eval-rpgmaker-mz-migration`，2026-11-28。

---

## 0. 总评

**两份文档质量高于本仓库一般的设计文档，可以进入实施；但有 3 处事实错误必须先改，6 处方案缺口建议在开工前补上。**

- 评估稿的核心判断——「宿主壳几乎 100% 复用，MZ 专有工作集中在 4 块」——**与代码事实完全一致**。
  `buildInjectionBundle()` 的三布尔矩阵、`hookAsset` / `scriptAppends` 分支、polyfill 装配顺序，
  以及「全仓注入脚本零 `RPGMAKER_NAME` 硬编码」的论断，逐一核实无误。
- 评估稿 §2.1 对「加密素材必 404」的**自我修正**是诚实的：`_` 追加在完整 URL 后，请求路径与磁盘同名，
  `canonicalIfValid()` 直接命中，问题只剩缓存降级与混合加密工程 404 两条。核实 `isImmutableAsset()`
  按最后一个 `.` 取后缀（`RpgMakerLocalHttpServer.kt:489-491`），`.png_` 确实落到 `else -> false`。
- 方案稿的「不新增版本值、只改显示名」是对的方向。`EngineSettingsStore.RPG_MZ_V2 = "v2"`、
  归一化白名单（`:1119-1120`）、三语言 `engine_option_rpg_mz_v2 = TynMZ-0.1` 均核实无误。

下面按「必须改 → 建议补 → 可选」分级列出。

---

## 1. 必须修正的事实错误（3 处）

### 1.1 方案稿阶段 C 第 3 条：「`RpgMakerActivity` 没有音频暂停/恢复」——**已有实现**

方案稿 `:303-304` 写：

> `AudioManager` 暂停/恢复：……当前 `RpgMakerActivity` **没有**这段（JoiPlay 有）。

**事实**：[`RpgMakerActivity.kt:891-925`](../engine/src/main/java/com/core/rpgmaker/RpgMakerActivity.kt) 已经在
`onPause()` 里 `WebAudio._context.suspend()`、`onResume()` 里 `resume()`（首帧跳过），
注释明写「MV/MZ 的 WebAudio 主上下文挂起……此处为 MV/MZ 等价实现」。

**影响**：阶段 C 的工作量被高估约 0.5 天，且会把一条已经正确的逻辑列为待办，误导实施者重写。
**修法**：删除该条，或在阶段 C 里改为「已有，无需改动；需确认 MZ 下 `WebAudio._context` 路径一致」。

### 1.2 方案稿阶段 D 第 4 条：Effekseer stub 注入到 `__mz_core_hook.js` 的**时机不对**

方案稿 `:346` 写 stub 放在 `__mz_core_hook.js`，并在括号里注明「`</head>` 前执行，早于 `js/libs/effekseer.min.js`」。

**事实**：`__mz_core_hook.js` 按方案稿 §2.3 自己的定义是「`js/rmmz_core.js` 追加段」，即 `scriptAppends` 通道。
`scriptAppends` 是**追加到游戏脚本末尾**（`RpgMakerLocalHttpServer.kt:552-559` `appendedBytes()`），
而 `rmmz_core.js` 由 `main.js` 动态注入，**必然晚于** `effekseer.min.js` 的求值（MZ 的 `main.js` 先注入
`js/libs/`，再注入 `rmmz_*.js`）。所以 stub 放在 core hook 里是**来不及的**——库已经把 `window.effekseer`
赋成对象了。

**修法**：Effekseer 的 setter 拦截必须走 **`earlyHtml` 通道**（`</head>` 前，与 `sessionConfig` 同段，
或作为 polyfill 的 v2_extra 之外再加一个 MZ 专有 early 脚本）。方案稿阶段 D 第 3 条已经把
`effekseer` 标志塞进了 `sessionConfig`，那么 stub 就应该紧跟 `sessionConfig` 之后、任何 polyfill 之前。
`__mz_hook.js`（lateHook）里的第二道保险可以保留。

### 1.3 方案稿 §2.3 的新目录名与现有资产布局冲突

方案稿 `:103-107` 把新脚本放在 `engine/src/main/assets/mz/`，与 `rpgmv-v1` 并列。

**事实**：`rpgmv-v1` 位于 `engine/src/main/assets/rpgmaker/rpgmv-v1/`（见
[`__nwjs_polyfill.js:2`](../engine/src/main/assets/rpgmaker/__nwjs_polyfill.js) 与 grep 命中的
`assets/rpgmaker/rpgmv-v1/js/rpg_core.js`），**不是** `assets/rpgmv-v1/`。`assets/` 根目录下只有共享脚本
（`__rmmz__.js`、`__tyranor_mouse.js` 等），`rpgmaker/` 子目录才是会话专有资产的家。

**修法**：新目录改为 `engine/src/main/assets/rpgmaker/mz/`，或直接放在 `assets/rpgmaker/` 下以
`__mz_*.js` 命名，与 `__rpg_v12.js`、`__nwjs_polyfill*.js` 同级。这是纯路径问题，但不改会让
「共享脚本由 Gradle 同步到 app」的资源归属规则（AGENTS.md「资源归属」节）对不上。

---

## 2. 建议开工前补上的方案缺口（6 处）

### 2.1 存档 B-2 方案的「推荐」下得太早，缺一个前置事实

方案稿 B.0 推荐 B-2（pako 兼容格式），理由是 `RpgSaveFormat` 已把 `.bin ↔ .rmmzsave` 改名做完。
但 B-2 的成立依赖一个**方案稿自己没验证的前提**：MZ 原生 `saveToLocalFile` 写出的文件
**内容就是 pako 的 raw deflate 输出**，没有额外封装。评估稿 §2.2 的表格说「二进制」，
方案稿 B.2 的注释说「binary string」，但都没有给出「`file1.rmmzsave` = `pako.deflate(json,{to:"string"})`
逐字节落盘」的源码级证据（`fs.writeFileSync(filePath, zip, {encoding:"binary"})` 这一行）。

**建议**：阶段 B 开工第一步就是在 MZ corescript 镜像里核实 `saveToLocalFile` / `loadFromLocalFile`
这两行，并把证据行号写进方案稿。如果 MZ 在 zip 外还包了别的（例如写了一个长度头），
B-2 的 Kotlin 侧 `Deflater` 复刻就要跟着改。这比 R3「pako 是 binary string」更上游。

### 2.2 B-2 与现有 `.bin` 存档的**迁移**没有写

现状 MZ 存档是**裸 JSON 的 `file1.bin`**（`__hook_rmmz_managers.js:2-5` 跳过 `jsonToZip`）。
切到 B-2 后，`loadObject` 会把老文件的裸 JSON 当成 deflate 流去 `zipToJson`，**必然解析失败**。

方案稿只在 B.4 提了 `.bin_` 中间态，没有提**老格式迁移**。`RpgMakerStorage.read()` 已经是
「读全文 → UTF-8 解码」，可以在 JS 侧 `loadObject` 里做一个嗅探：载荷以 `{` 开头（JsonEx 的 `makeDeepCopy`
输出以 `{` 起）则视为旧裸 JSON 直通，否则走 `zipToJson`。这比在 Kotlin 侧做格式迁移更稳，
也不需要触碰用户文件。

**建议**：阶段 B 增加一条「旧 `.bin` 裸 JSON 嗅探直通」，并写进 5.2 的 JS harness 测试项。

### 2.3 「不互通」选项被静默丢弃，但 `rpg_mz_save_native` 设置项又保留了它

方案稿 §2.2 定义了 `rpg_mz_save_native`（默认 `false`）来切换 B-1/B-2，但 B.0 又说「推荐 B-2」，
阶段 B 的实施内容全部按 B-2 写。两者矛盾：如果 B-2 是唯一实现，`rpg_mz_save_native=false` 时
走什么？如果保留 B-1 作为降级，那它的默认值就不该是 `false`。

**建议**：二选一——要么删掉 `rpg_mz_save_native`，B-2 成为唯一路径（配合 2.2 的嗅探做兼容）；
要么把它定义为「`false` = 自动嗅探（新写用 deflate，读旧裸 JSON），`true` = 强制 deflate」。
按 KISS，推荐前者：**一个开关只控制一件事时才值得存在**，这里其实不需要开关。

### 2.4 `isMobileDevice` 钉 `false` 的副作用没有评估完

方案稿阶段 E 把 `Utils.isMobileDevice` 钉 `false`，理由是「让引擎走桌面分支」。
但评估稿 §2.4 自己写了：`Sprite_Animation.shouldWaitForPrevious()` 在 MZ 硬编码 `return Utils.isMobileDevice()`，
注释是「Effekseer is very heavy on some mobile devices」。钉成 `false` 后 MZ 会**同时播放多个粒子动画**，
这与阶段 D 的 Effekseer 闸门是**相反方向**的优化。

另外 `Graphics._defaultStretchMode = isNwjs() || isMobileDevice()`（方案稿阶段 E 自己列了），
钉 `false` 后画面是否铺满方案稿自己也不确定（「若不留，改为 `return true`」）。

**建议**：阶段 E 的 `isMobileDevice` 项降级为「待真机验证后再决定」，不要默认钉 `false`。
若钉，必须与 Effekseer 模式联动（`effekseer=on` 时保持 `isMobileDevice` 原值）。

### 2.5 阶段 A 的 `isImmutableAsset()` 改法有递归语义问题

方案稿 `:135-136` 给出的代码：

```kotlin
val mzEncrypted = path.substringBeforeLast('.')  // 去掉尾段后再判一次
if (path.endsWith("_") && isImmutableAsset(mzEncrypted)) return true
```

`substringBeforeLast('.')` 对 `img/a.png_` 返回 `img/a`，**没有扩展名**，递归进去 `dot` 会取到
`img/a` 里没有 `.`（假设目录名无点）→ `return false`。这段代码**不会按注释描述那样工作**。
正确写法应是 `path.dropLast(1)`（去掉 `_`）再判。

**修法**：

```kotlin
if (path.endsWith("_")) return isImmutableAsset(path.dropLast(1))
```

`a.png_` → `a.png` → 命中白名单；`a.ogg_` → `a.ogg`。递归一层即收敛（`dropLast` 后不再以 `_` 结尾）。
这条要进 5.1 的 `RpgMakerServerMzAssetTest`。

### 2.6 「全仓注入脚本零 `RPGMAKER_NAME` 硬编码」的论断要补一个限定

评估稿 §0 与方案稿 §1.2 都说注入脚本「零 `RPGMAKER_NAME` 硬编码」。grep 核实：`assets/rpgmaker/` 下
`RPGMAKER_NAME` 只命中 `rpgmv-v1/js/rpg_core.js`——那是 MV 1.6.1 的**覆盖包本体**，不是注入脚本。
论断成立，但建议在两处都加一句「不含 `rpgmv-v1/` 覆盖包本体」，否则读者 grep 到会困惑。

---

## 3. 可选改进（不影响开工）

- **评估稿 §4.4「WebView < 100 直接拒绝启动」**：方案稿没有对应任务。要么删掉评估稿这条建议，
  要么在方案稿加一个「版本门槛」任务（它比 Effekseer 闸门更基础）。目前两份文档在这点上不对齐。
- **方案稿 §7 排期**：B 标 2 天、关键路径，但 B.1 的 `RpgMakerStorage` 字节流改造其实很小
  （现有 `write()` 的原子写逻辑可以原样复用，只换载体），真正耗时的是 B.4 的互通验证。
  建议把「等 PC 版真实存档」列为阶段 B 的**外部依赖**而非工时。
- **方案稿阶段 H**：`requestStartupVisualReveal` 目前用 `postVisualStateCallback`（`:734`），
  这是 WebView 侧「首帧已绘制」的信号，与 MZ 的 `SceneManager._scene` 不是同一层。
  改成 JS 轮询后要保留原来的 `initialMainFramePending` / `startupNavigationGeneration` 守卫，
  否则会引入「页面跳转后旧轮询回调 reveal 新页面」的竞态。方案稿应提醒这一点。
- **两份文档的行号引用**：抽查的 20 余处全部命中（`RpgMakerActivity.kt:282-287/311-324/342-362/369-382`、
  `RpgMakerLocalHttpServer.kt:488-503/561-621/947`、`RpgMakerStorage` 常量、`RpgSaveKeyMapping`），
  质量很高，不必逐条复核。

---

## 4. 两份文档之间的一致性核对

| 项 | 评估稿 | 方案稿 | 结论 |
| --- | --- | --- | --- |
| 版本路线 | 「升级 TynMZ-0.1 为 MZ 版 v2」 | 「不新增版本值，只改显示名」 | ✅ 一致 |
| 加密后缀 | 「`_` 直接命中，只差缓存 + 混合加密 404」 | 阶段 A 按此实施 | ✅ 一致 |
| 存档 | 「B-1/B-2 二选一」 | 「推荐 B-2」+ 保留 `rpg_mz_save_native` 开关 | ⚠️ 见 §2.3 |
| Effekseer | 「按 WebView 主版本 < 124 分流」 | 阶段 D `auto/on/off` | ✅ 一致（但注入通道错，见 §1.2） |
| `isMobileDevice` | 未提 | 钉 `false` | ⚠️ 见 §2.4 |
| WebView < 100 拒绝启动 | 建议 | 无任务 | ⚠️ 见 §3 |
| 音频暂停/恢复 | 未提 | 「当前没有，需新增」 | ❌ 事实错误，见 §1.1 |

---

## 5. 结论与建议动作

**结论**：方案可执行。评估稿的事实底座扎实，方案稿的阶段划分与回滚设计（按 `v2Session && MZ` 门控、
MV 三链路零变化）符合本仓库「最小必要修改」的原则。

**开工前必须做（按顺序）**：

1. 修正 §1.1（删除音频暂停待办）、§1.2（Effekseer stub 改走 earlyHtml）、§1.3（目录改为 `assets/rpgmaker/mz/`）。
2. 修正 §2.5（`isImmutableAsset` 的 `dropLast(1)` 写法），并把测试用例补进 5.1。
3. 按 §2.1 核实 `saveToLocalFile` 的落盘字节证据；按 §2.2/§2.3 决定 `rpg_mz_save_native` 的去留
   并补「旧 `.bin` 嗅探」。
4. 按 §2.4 把 `isMobileDevice` 钉 `false` 降级为待验证项。

**开工后可以边做边改**：§3 的四项。

**不需要动**：两份文档的版本路线、阶段顺序（A → B 为关键路径）、测试计划的「真机必做」原则、
R1–R7 风险登记（R3 应把 §2.1 的源码核实列为缓解措施的第一条）。
