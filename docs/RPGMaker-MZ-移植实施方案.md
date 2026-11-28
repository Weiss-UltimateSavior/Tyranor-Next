# RPG Maker MZ 运行时移植实施方案（TynMZ-0.2）

> 目标：把 `TynMV-0.2`（MV v2 会话）的运行方案移植到 MZ，交付 `TynMZ-0.2`。
> 本文是**实施计划**，不代表已实现；落地后须同步 [SPEC.md](../SPEC.md)、
> [ARCHITECTURE.md](../ARCHITECTURE.md)、[CONTEXT.md](../CONTEXT.md)、[CHANGELOG.md](../CHANGELOG.md)。
>
> 依据：本仓库只读代码盘点 + `D:\code\joiplay` 逆向逐文件核对 +
> MZ corescript 社区镜像 `stak/rmmz-corescript` v1.1.0 逐行比对 + 公开资料交叉验证。
> 可行性结论与差异取证见 [RPGMaker-MZ-移植评估.md](RPGMaker-MZ-移植评估.md)，本文只讲**怎么做**。
>
> 状态：**已落地（A / B / C / D / E / H）**；F（性能补丁）与 G（注入配置数据化）按本文定性为
> 「可选、可延后」，本期**只落地了 F 里的 `MAX_TEXTURE_SIZE` 降采样**（见 `8c9caff0d`，常开无开关），其余补丁未做。最后更新 2026-11-29（第 4 版：回写 F 的降采样已落地）。
> 真机验收项（加密样本 304、PC 真实存档双向互通、WebView ≥/< 124 对照、长 BGM）
> 仍需按 §5.3 在设备上执行，不能以 JVM/JS 回归代替。

---

## 0. 一句话方案

**不新开"MZ v2"分支，而是把 `TynMZ-0.1` 就地升级为 `TynMZ-0.2`**：复用现有
`v12Session && v2Session` 分支（MZ 已经在里面跑），只补 MZ 真缺的 4 组能力
（加密素材缓存 / 存档管线 / 音频解码 / Effekseer 闸门），并把 injection 装配从硬编码抽成配置表。

选这条路的理由：

1. **代码事实**：`RpgMakerActivity.kt:282-287` 的三布尔矩阵里，`MZ×v2` 已经
   `v12Session=true && v2Session=true`，与 `MV×v2` 走**完全同一条**注入链。MZ 缺的不是"一套新壳"，是"几块内容"。
2. **不自造版本号**：`EngineSettingsStore` 已有 `RPG_MZ_V2 = "v2"` 常量、归一化白名单、
   设置页选项 `engine_option_rpg_mz_v2`（当前显示为 `TynMZ-0.1`）。改**显示名**即可，
   不需要新增版本值、不需要迁移已存设置。
3. **回滚成本低**：所有改动按 `v2Session && engine==MZ` 门控，MV 三条链路
   （`Tyranor-2.3.4` / `TynMV-0.1` / `TynMV-0.2`）行为零变化。

---

## 1. 现状基线（开工前必须成立的事实）

### 1.1 会话矩阵与注入通道

| 项 | 位置 | 现状 |
| --- | --- | --- |
| 版本判定 | [`RpgMakerActivity.kt:280-287`](../engine/src/main/java/com/core/rpgmaker/RpgMakerActivity.kt) | `normalizedVersion` 裸比较 `"v1"`/`"v2"`；三布尔 `v12Session`/`v2Session`/`useCoreScriptOverlay`。**〔v2〕** MZ 的 `"v1"` 是占位值：不在任何专用分支里，落到 `:288-291` 的「has no dedicated runtime」兜底，按 v0 资源策略跑；设置页也没有 `engine_option_rpg_mz_v1` 选项（只有 v0 / v2），但 `EngineSettingsStore.RPG_MZ_V1` 常量在归一化白名单里。本计划不动它 |
| lateHook 选择 | `RpgMakerActivity.kt:311-316` | MZ → `__rmmz__.js`（**只有 1 行**） |
| scriptAppends | `RpgMakerActivity.kt:317-324` | MZ → `js/rmmz_core.js`（8 行）+ `js/rmmz_managers.js`（22 行） |
| polyfill 装配 | `RpgMakerActivity.kt:342-362` | base → v1_extra → v2_extra；MZ 与 MV v2 相同 |
| 会话配置 | `RpgMakerActivity.kt:369-371` | `window.__tyranorRpg={engine:'MZ',version:'v2'}` |
| 注入落点 | [`RpgMakerLocalHttpServer.kt:980-1021`](../engine/src/main/java/com/core/rpgmaker/RpgMakerLocalHttpServer.kt) | early 段插 `</head>` 前，late 段插 `</body>` 前（缺标记时尾插保序） |
| 桥注册 | `RpgMakerActivity.kt:453-473` | 存档桥全版本；`TyranorFs`/`TyranorEnv` 仅 `v2Session` |
| MZ hook 资产 | [`__rmmz__.js`](../engine/src/main/assets/__rmmz__.js)、[`__hook_rmmz_core.js`](../engine/src/main/assets/__hook_rmmz_core.js)、[`__hook_rmmz_managers.js`](../engine/src/main/assets/__hook_rmmz_managers.js) | 1 + 8 + 22 行，MZ 全部"深度"就这些 |

### 1.2 已经做对、不要动的部分

- [`RpgSaveFormat.kt`](../app/src/main/java/com/tyranor/next/core/game/save/RpgSaveFormat.kt)：
  `.rpgsave` / `.rmmzsave` 双全、MV 有 `.bak` 而 MZ 无、MV 走 sha256 哈希名而 MZ 保持原名、
  槽位键归一。**这块不需要改**，MZ 的存档格式语义已经吃透。
- [`__nwjs_polyfill.js`](../engine/src/main/assets/rpgmaker/__nwjs_polyfill.js)（547 行）与
  [`__nwjs_polyfill_v2.js`](../engine/src/main/assets/rpgmaker/__nwjs_polyfill_v2.js)（4289 行）：
  **零 `RPGMAKER_NAME` 硬编码**（不含 `rpgmv-v1/` 覆盖包本体——`rpgmv-v1/js/rpg_core.js` 里的
  `RPGMAKER_NAME = 'MV'` 是 MV 1.6.1 核心脚本自身的内容，不是注入脚本），全部按能力探测（`typeof window.Utils.isNwjs === "function"`、
  `JsonEx._decode` 源码特征）。MZ 直接吃这一层，不需要 fork。
- [`__tyranor_mouse.js:22-29`](../engine/src/main/assets/__tyranor_mouse.js)：
  已经在给 MZ 补 `pageX/pageY`（MZ 的 `TouchInput` 读 page 坐标）。这是全仓**唯一一处不分会话、
  无条件执行**的 MZ 适配；`__hook_rmmz_core.js` 的 `_stretchHeight`、`RpgMakerActivity.kt:536-542` 的
  入口探测、`RpgSaveFormat` 的 `.rmmzsave` 则是按 MZ 会话门控的专有适配。**〔v2〕**
- [`RpgMakerLocalHttpServer.kt:947`](../engine/src/main/java/com/core/rpgmaker/RpgMakerLocalHttpServer.kt)：
  `.wasm → application/wasm` 已正确，MZ 的 `effekseer.wasm` 不需要额外处理。

### 1.3 已知的正确"不可移植"项（不要试图复刻）

| MV v2 能力 | 为什么 MZ 上做不了 |
| --- | --- |
| 渲染器交还 core（`__rpg_v12.js:44-52`） | MZ 用 `Graphics._createPixiApp()` + `PIXI.Application`，没有 `_createRenderer` / `_rendererType`；MZ 本来就自己建 app，无需交还 |
| `?android-legacy=1` PIXI 兼容渲染 | MZ 无 `PIXI.CanvasRenderer` 路径，`_rendererType` 不存在 |
| `DataManager.isThisGameFile` 标题兜底 | MZ 把 `_globalId` / `isThisGameFile` **整体删除**，改用 `globalInfo` 的 `forageKey` 命名空间 |
| 存档 `.bak` 备份链 | MZ 核心不含 `backup/restoreBackup/cleanBackup` |
| `textureGC.maxIdle` 策略 | MZ 由 core 自管，本来就没这个补丁 |

> 这五项在计划里**不设任务**。看到别人提"补上"时要先回来读这张表。

---

## 2. 目标形态

### 2.1 版本与命名

| 项 | 值 |
| --- | --- |
| 引擎设置值 | 复用 `EngineSettingsStore.RPG_MZ_V2 = "v2"`（**不新增常量**） |
| 显示名 | `engine_option_rpg_mz_v2`：`TynMZ-0.1` → **`TynMZ-0.2`**（三语言同步） |
| `CONTEXT.md` 术语条 | 更新为「TynMV-0.1/0.2、**TynMZ-0.1/0.2**」 |
| README 引擎表 | MZ 行补 `TynMZ-0.2` |

> `TynMZ-0.1` 这个显示名是历史遗留（值一直是 `v2`）。本次只改文案，**不改存储值**，
> 因此不需要设置迁移，也不影响 `PerGameSettingsStore.F_RPG_MZ_VERSION`。

### 2.2 新增设置项（2 个，全部进三级设置链路）

| 键 | 作用域 | 取值 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `rpg_effekseer_mode` | 全局 + 单游戏 | `auto` / `on` / `off` | `auto` | `auto` = WebView 主版本 < 124 时关闭；`off` = 强制 stub |
| `rpg_audio_streaming` | 全局 + 单游戏 | bool | `true` | 钉 `WebAudio._shouldUseDecoder = true`，长 BGM 流式解码 |

**〔v2/v3〕** 原第 3 项 `rpg_mz_save_native` **永久删除**：存档互通改为在**转换层**做格式转码
（导出/导入时自动，见阶段 B），引擎侧存档方式不变，用户无需也不应选择格式——因此没有任何开关需要暴露。

**注意**：这两项**只对 MZ 显示**。MV 的 `SettingsScreen` 卡片不动。

### 2.3 变更新增文件

**〔v2〕** 目录由 `assets/mz/` 改为 `assets/rpgmaker/mz/`：`assets/` 根目录只放**不分会话的共享脚本**
（`__rmmz__.js`、`__tyranor_mouse.js` 等），会话专有资产归 `assets/rpgmaker/`（与 `rpgmv-v1/` 同级），
这样 AGENTS.md「资源归属」节的 Gradle 同步规则才对得上。

```
engine/src/main/assets/rpgmaker/mz/            # MZ v2 专有注入脚本（新目录，与 rpgmv-v1 并列）
  __mz_early.js                                # earlyHtml 段（由 Kotlin 在 </head> 前拼入）：Effekseer 全局桩，必须早于 effekseer.min.js
  __mz_hook.js                                 # lateHook：取代空壳 __rmmz__.js
  __mz_core_hook.js                            # js/rmmz_core.js 追加段（音频解码）
  __mz_managers_hook.js                        # js/rmmz_managers.js 追加段（存档）
  __mz_overrides.js                            # 可选的性能补丁（tilemap RT / highp / blur 恒等）
```

保留 `__rmmz__.js` / `__hook_rmmz_core.js` / `__hook_rmmz_managers.js` 作为 v0 路径
（`TyranoActivity` 的 MZ 分支仍在用，见 [`TyranoActivity.kt:201-211`](../engine/src/main/java/com/core/tyrano/TyranoActivity.kt)），
**不要删**。

---

## 3. 实施阶段

每个阶段都是**可独立发布、可独立回滚**的。标 🔴 的是"不做的直接后果"，标 🟡 是性能，标 🟢 是体检。

---

### 阶段 A 🔴 加密素材缓存治理（0.5 天）

**问题**：`isImmutableAsset()` 按最后一个 `.` 取后缀，`.png_` 取出 `".png_"` 不在白名单
→ 落到 `REWRITABLE_CACHE_CONTROL`（no-cache + **不发 ETag**）→ 每张加密图、每段加密音频
**每次请求整块重传**。加密 MZ 作品会出现明显场景切换卡顿。

**改动**：

1. [`RpgMakerLocalHttpServer.kt:488-503`](../engine/src/main/java/com/core/rpgmaker/RpgMakerLocalHttpServer.kt)
   `isImmutableAsset()`：
   ```kotlin
   // MZ 加密素材：<原文件名>_（追加在完整 URL 后），如 img/pictures/a.png_、audio/bgm/x.ogg_
   // 它们是只读素材，与 .rpgmvp/.rpgmvo/.rpgmvm 同档（max-age + ETag）。
   // 去掉尾随 "_" 后按基础扩展名再判一次：a.png_ → a.png。递归一层即收敛（去 "_" 后不再以 "_" 结尾）。
   if (path.endsWith("_")) return isImmutableAsset(path.dropLast(1))
   ```
   > **〔v2〕** 不要写成 `substringBeforeLast('.')`：对 `img/a.png_` 它返回 `img/a`，扩展名已被削掉，
   > 递归进去 `lastIndexOf('.')` 取不到（或取到目录名里的点），永远返回 `false`，与注释描述不符。
   > 必须是 `dropLast(1)` 只去尾随的 `_`。

2. `mime()`（[`:924-951`](../engine/src/main/java/com/core/rpgmaker/RpgMakerLocalHttpServer.kt)）：
   在末尾 `application/octet-stream` 之前加
   ```kotlin
   if (n.endsWith("_")) return mime(n.dropLast(1))   // .png_ → image/png
   ```

3. `resolveRequestedFile()`（`[:561-621]`）加**反向**回退（**只在 MZ 会话 + 直接命中失败后**）：
   ```kotlin
   // 混合加密工程：游戏只加密了 img/，后加补丁素材是明文；MZ 仍按 _ 请求 → 回退明文
   if (mzSession && lower.endsWith("_")) {
       val alt = uri.dropLast(1)
       canonicalIfValid(alt)?.let { ... return ResolvedFile(it, null) }
   }
   ```
   > **不要**加正向回退（`.png → .png_`）。MZ 的 `_` 追加在完整 URL 后，
   > 请求路径与磁盘文件名逐字符相同，`canonicalIfValid` 直接命中，加正向回退只会制造噪声。

4. 构造参数新增 `mzSession: Boolean`（由 `RpgMakerActivity.startLocalServer` 传入
   `webGameType == RPG_MZ`），默认 `false` 保证 v0/Tyrano 路径不变。

**测试**：
- JVM：[`RpgMakerServer*Test`](../engine/src/test/java/com/core/rpgmaker/) 新增
  `cacheControlFor("img/a.png_") == ASSET_CACHE_CONTROL`、`cacheControlFor("audio/bgm/x.ogg_") == ASSET_CACHE_CONTROL`、
  `cacheControlFor("img/a.txt_") == REWRITABLE_CACHE_CONTROL`（基础扩展名不在白名单时**不能**被误放行）、
  `cacheControlFor("img/a.png__") == REWRITABLE_CACHE_CONTROL`（递归只剥一层 `_`，双下划线不是合法加密名）、
  `mime("a.png_") == "image/png"`、`mime("a.ogg_") == "audio/ogg"`、
  `resolve("img/a.png_")` 在只有 `a.png` 时命中、在两者都有时**优先 `a.png_`**。
- 回归：确认 `.rpgmvp` 三个旧扩展名行为不变。

**验收**：加密 MZ 样本进场景，logcat 中该素材第二次请求出现 `304`（或至少不再每次 200 全量）。

---

### 阶段 B 🔴 存档互通（转换层转码，0.5 天）

> **〔v3 决策变更〕** 原方案 B-2 要把引擎侧改成 deflate 落盘（`RpgMakerStorage` 加 `writeBytes`、
> hook 补 `jsonToZip`、桥改 base64）。**现改为：引擎侧存档方式完全不动**，继续存明文 JSON `.bin`；
> 互通由**转换层**在导出/导入时做一次格式转码。理由见 [MZ存档格式对比](MZ存档格式-Tyranor与JoiPlay对比.md)：
> Tyranor 与 JoiPlay/PC 的差异在「内容格式」，而转换层本来就是唯一处理「Tyranor 格式 ↔ 标准格式」
> 的地方（`RpgSaveFormatConverter` / `exportRename`），把转码放这里不需要动引擎、不需要迁移存量存档、
> 也不需要改 `RpgMakerStorage` 的字符编码契约。

#### B.0 现状与目标

| | Tyranor 引擎侧（**不动**） | PC 版 / JoiPlay 标准（导出目标） |
| --- | --- | --- |
| 文件名 | `global.bin` / `fileN.bin` | `global.rmmzsave` / `fileN.rmmzsave` |
| 内容 | **明文 JSON**（hook 跳过 `jsonToZip`） | **zlib 容器 deflate 流**（`pako.deflate` level 1） |

互通 = 两步：**改名**（已有 `RpgSaveFormat.tyranorToStandard` / `standardToTyranor`）
+ **转码**（本次新增）。改名逻辑早已就位，缺的只是转码。

#### B.1 载荷编解码（`RpgSaveFormat`）— **已实现**

```kotlin
fun needsPayloadTranscode(engine: EngineType): Boolean  // 仅 MZ 需要；MV 两版都是 LZString 文本
fun encodeMzPayload(plainJson: ByteArray): ByteArray    // JSON → deflate 流（导出用）
fun decodeMzPayload(packed: ByteArray): ByteArray?      // deflate 流 → JSON（导入用，失败返回 null）
```

三条硬约束（已由单测守住）：

1. **幂等**：`encodeMzPayload` 对已是 deflate 流的入参原样返回，避免重复压缩成「压缩过的 JSON」；
   `decodeMzPayload` 对已是明文的入参原样返回（旧版导出、人工改名的文件）。
2. **判别零歧义**：明文 JSON 以 `{`（`0x7B`）起；zlib 首字节固定 `0x78`，raw deflate 首字节低 3 位为
   BFINAL+BTYPE，模 8 ∈ {0,1,2,4,5,6}，**不可能**等于 3。所以只判首字节，不需要试探解压。
3. **坏数据拒绝**：解压失败返回 `null`，调用方计 failed 并**保留源文件**，绝不写出半成品。

> MV 直接返回 `null`（不转码）——MV 的 `StorageManager.save` 用 `LZString.compressToBase64`，
> Tyranor 与 PC 版内容本就逐字节相同，纯改名即可。

#### B.2 导入方向（`RpgSaveFormatConverter`）— **已实现**

`copyAtomically(source, target, engine)` 在写盘前对 MZ 做一次 `decodeMzPayload`，
写出引擎可直接读的明文 JSON。解压失败按 `failed` 计、源文件留在活动目录、不生成半成品目标。
MV 走原路径（逐字节复制，保持流式）。

#### B.3 导出方向（`GameSaveManager`）— **已实现**

`writeZipContents` 增加可选参数 `encodePayload: ((ByteArray) -> ByteArray)?`：

- `exportPayloadEncoder(engine, format)` 仅在 `format == STANDARD && engine == MZ` 时返回转码函数；
- 无转码时走原来的 `FileInputStream.copyTo`（流式，大文件不整读进内存）；
- 有转码时才 `readBytes()`（MZ 存档通常几十 KB～几 MB，可接受）。

#### B.4 不改的东西（明确边界）

- `__hook_rmmz_managers.js`：**不动**。继续走 `saveDataManager.Save/Load` 存明文 JSON。
- `RpgMakerStorage`：**不动**。继续 UTF-8 读写 String，不需要 `writeBytes`。
- `RpgMakerActivity.RpgMakerSaveBridge`：**不动**。不新增 `SaveBase64`/`LoadBase64`。
- 存量存档：**不需要迁移**。Tyranor 侧格式不变，升级后照常读写。

> 代价（要写进 SPEC）：MZ 存档仍是「Tyranor 私有格式」——只在导出/导入那一刻转成标准格式。
> `Docs` 里那句「两种格式内容字节级一致」对 MZ 不成立，应限定为 MV（见对比文档 §4）。

#### B.5 与 `saveToLocalFile` 取证的关系

原 B.0′ 要求核实的「`saveToLocalFile` 是否原样落盘 zip」**不再是阻塞项**——
我们不再复刻 MZ 的落盘路径，只是在转换层产出「PC 版能 inflate 的流」。
但仍建议核对一次 `jsonToZip` 是否为 `pako.deflate(json, {to:"string", level:1})`，
以确认**压缩级别不影响互通**（zlib 流不携带级别，任何级别都能 inflate；级别只影响体积）。

#### B.6 已验证

| 项 | 结果 |
| --- | --- |
| `encode → decode` 往返 | ✅ `RpgSaveFormatPayloadTest` |
| 编码幂等 / 解码对明文幂等 | ✅ |
| 空载荷、损坏载荷（拒绝返回 null） | ✅ |
| 压缩确实变小（真实量级载荷） | ✅ |
| 导入：`.rmmzsave` → 引擎可读 `.bin` | ✅ `convertsMzSaves` |
| 导入：坏载荷不写半成品、源文件保留 | ✅ `rejectsCorruptMzPayloadInsteadOfWritingGarbage` |
| MV 全部旧用例（纯改名语义）零回归 | ✅ 22 项 `RpgSaveFormatTest` 全过 |

**仍需真机验证（不能只跑单测）**：
1. MZ 游戏内存档 → 导出「标准格式」→ 拷到 PC 版 `save/` → PC 版读档成功（**真互通判据**）；
2. PC 版 `.rmmzsave` → 导入 → 手机读档成功（反向）；
3. 导出后 Tyranor 自己仍能读（转码只作用于导出副本，不落地回引擎目录）。

---

### 阶段 C 🟡 音频解码治理（1 天）

**问题**：
- MZ 的 `audioFileExt()` **硬编码 `return ".ogg"`**，`Html5Audio` 整个删除，只有 `WebAudio` 一路。
- Android WebView 的 `canPlayType('audio/ogg; codecs="vorbis"')` 通常返回 `"maybe"`，
  于是 `WebAudio._shouldUseDecoder()` 为 `false` → 走 `decodeAudioData` 整段解码。
- 实测（GameNative 记录）：慢机上长 BGM 要 20s+ 才出声；`decodeAudioData` 并发还会 `EncodingError` 或永不 settle。

**改动**（`__mz_core_hook.js`）：

1. **钉死流式解码**（受 `rpg_audio_streaming` 开关控制）：
   ```js
   if (window.Utils && Utils.canUseWebAudioAPI && Utils.canUseWebAudioAPI() && window.VorbisDecoder) {
       WebAudio.prototype._shouldUseDecoder = function () { return true; };
   }
   ```
   > 前置条件必须有 `VorbisDecoder`：`js/libs/vorbisdecoder.js` 是游戏自带资产，
   > 若被裁剪则退回原生路径（否则 BGM 全哑）。

2. **`decodeAudioData` 串行化**：两条 lane（< 256 KiB 一条、大文件一条），各串行 + 失败重试一次。
   ```js
   // 伪代码：把 WebAudio._onXhrLoad 里的 decodeAudioData 包进队列
   ```
   这条是**兜底**——钉了 decoder 后大多数情况走不到，但 SE/ME 仍会用它。

~~3. **`AudioManager` 暂停/恢复**：WebView `onPause` 时 `WebAudio._context.suspend()`，
   `onResume` 时 `resume()`。当前 `RpgMakerActivity` **没有**这段（JoiPlay 有）。~~
   **〔v2 删除〕** 这条的事实陈述错误：[`RpgMakerActivity.kt:891-925`](../engine/src/main/java/com/core/rpgmaker/RpgMakerActivity.kt#L891-L925)
   **已经实现**了 `onPause` suspend / `onResume` resume（含首帧跳过），MZ 走同一个 Activity，无需新增。
   阶段 C 的实际工作量由此从 1.5 天降到 **1 天**。

**已知可借鉴但未采纳**：JoiPlay 对缺失音频回 `assets/rpgm/null.ogg` 静音占位。
本仓库不做——静默成功会掩盖"素材缺失"这一真实问题，应让它 404 并在 logcat 可见。

**〔v2〕** 这一条与阶段 A 的关系：A 的 `isImmutableAsset` 递归会放行 `a.png_`，而这里的回退逻辑是在**主请求 404 之后**主动拼出去尾 `_` 的路径再请求一次。两件事不冲突，但测试时要注意：**伪造请求路径要带尾随 `_`**，否则测的只是 404 本身，没覆盖回退分支。

**测试**：
- JS harness：断言 `_shouldUseDecoder()` 为 `true`、`VorbisDecoder` 缺失时**不改写**。
- 真机：≥5 分钟 BGM 切换场景不卡（对比开关前后）。

---

### 阶段 D 🟡 Effekseer 闸门（1.5 天）

**问题**（GameNative `EffekseerWasmGate.kt` 第一手记录）：
> Chromium WebView < 124 上，MZ 实例化 Effekseer WASM + WebAudio 输出，
> 会在 10–30s 后触发 renderer 里**确定性的 audio CHECK，直接杀进程**。
> 屏蔽 `AudioContext`、屏蔽渲染器初始化、不连 destination —— **都无效**。
> 唯一有效：阻止 WASM 实例化。

MZ 的 `Graphics._createEffekseerContext()` 对失败**是容忍的**（`try/catch` + `this._app` 判空），
`Sprite_Animation` 在 `Graphics._effekseer === null` 时直接不播。所以 stub 是安全的，代价只是没有粒子。

**改动**：

1. Kotlin 侧读 WebView 主版本：
   ```kotlin
   // browser.settings.userAgentString 形如 "...Chrome/124.0.6367.82..."
   private fun webViewChromeMajor(): Int
   ```
2. 判定：
   ```kotlin
   val effekseerMode = settings.rpgEffekseerMode   // auto/on/off
   val enabled = when (effekseerMode) {
       "on" -> true
       "off" -> false
       else -> webViewChromeMajor() >= 124        // auto
   }
   ```
3. 注入（earlyHtml 的 sessionConfig 里加一个字段，最省事）：
    ```kotlin
    val sessionConfig = "<script>window.__tyranorRpg={engine:'MZ',version:'v2',effekseer:$enabled};</script>"
    ```
4. **〔v2 修正〕桩必须放在 earlyHtml，而不是 `__mz_core_hook.js`**。
   原稿把第 4 步写在 `__mz_core_hook.js` 里并要求它"在 `</head>` 前执行"，这两句自相矛盾：
   core hook 是追加到 `js/rmmz_core.js` 末尾、由 `main.js` 动态注入的，而 `main.js` 先按
   `PluginManager` 之前的顺序加载 `js/libs/*.js`（含 `effekseer.min.js`），**core hook 的求值晚于
   `effekseer.min.js`**——到那时 `window.effekseer` 已被库赋成对象，setter 拦截失效。
   唯一早于 `effekseer.min.js` 的落点就是 sessionConfig 那段 `earlyHtml`（`</head>` 前），
   所以第 3、4 步应合并为**同一份 `earlyHtml`**，由 Kotlin 侧拼出：

    ```kotlin
    private fun buildEarlyHtml(enabled: Boolean): String = """
        <script>window.__tyranorRpg={engine:'MZ',version:'v2',effekseer:$enabled};</script>
        <script>
        if (window.__tyranorRpg && window.__tyranorRpg.effekseer === false) {
            Object.defineProperty(window, "effekseer", {
                configurable: true,
                get: function () { return undefined; },
                set: function () { /* 忽略 effekseer.min.js 的自我赋值 */ }
            });
        }
        </script>
    """.trimIndent()
    ```
    > 为什么必须是 **setter 拦截**而不是置一次 `undefined`：`effekseer.min.js` 求值时会把
    > `window.effekseer` 赋成对象，只在 hook 里写一次会被库覆盖回去。
    > 落地时在真机验证 `typeof window.effekseer === "undefined"`（在 `Graphics` 创建之后）。
    >
    > 另一个要防的点：`__nwjs_polyfill.js` 注入了 `module` / `define` / `require`，
    > 若 `effekseer.min.js` 是 UMD 包装，它会走 `module.exports = factory()` 分支而**不碰
    > `window.effekseer`**——此时 setter 拦截形同虚设，但效果反而更干净（库根本没挂到全局）。
    > 两种形态都要在真机确认，不要假设只有 IIFE 一种。

5. `__mz_hook.js`（lateHook）里再加一道保险，兜住上面任何一种漏网：
    ```js
    if (window.__tyranorRpg && window.__tyranorRpg.effekseer === false) {
        if (window.Graphics) Graphics._createEffekseerContext = function () { this._effekseer = null; };
    }
    ```
    > lateHook 在 `rmmz_core.js` 之后、`rmmz_managers.js` 之前求值，`Graphics` 已存在；
    > 而 `Graphics._createEffekseerContext` 在 MZ 1.1.0 由 `Graphics.initialize` 延迟调用（进入
    > 场景前），所以覆写时序成立。**这条要按能力探测写**（`typeof Graphics._createEffekseerContext === "function"`），
    > 与 R2 的原则一致：不要假设版本号。
    >
    > **〔v3 修正〕** 上面这段的时序前提是错的：`Graphics` 在 lateHook 求值时**并不存在**。
    > MZ 的 `index.html` 只直接引用 `js/main.js`，`js/libs/*.js` 与 `rmmz_core.js` 都是 `main.js`
    > 建 `<script>` 动态插入的（`async=false` 仍要等取回才执行），而 `</body>` 前的内联 lateHook
    > 在解析期就同步跑完。落地实现改为**能力探测 + `setTimeout` 有界重试等待
    > `window.Graphics` 出现**，再覆写 `_createEffekseerContext`（见
    > [`__mz_hook.js`](../engine/src/main/assets/rpgmaker/mz/__mz_hook.js)）。回归由
    > `RpgMakerMzAssetsTest` 与 JS harness 场景 9b 守住。

**设置项 UI**：`SettingsScreen` 的 RPG Maker MV/MZ 卡片加一个下拉（仅当选中 MZ 时可见，
或独立一行标注"仅 MZ"）。三语言文案同步。

**测试**：
- 单元：`webViewChromeMajor()` 解析 `Chrome/124.0.6367.82`、`Chrome/99.0.4844.88`、
  无 Chrome 段（返回 0）三种输入。
- 真机（**必做**）：WebView < 124 的设备上跑一段带 Effekseer 动画的 MZ 战斗，持续 5 分钟不闪退；
  对照组（`rpg_effekseer_mode=on`）应能复现闪退——**如果对照组不闪退，说明阈值不适用，
  需要把 `auto` 默认改回 `on` 并重评估**。

> 阈值 124 来自单方记录，未在本仓库设备复现。**这是本计划最大的不确定项**，
> 所以默认 `auto` 且提供 `on/off` 手动逃生口。

---

### 阶段 E 🟢 MZ hook 扩写（1 天）

把空壳 `__rmmz__.js` 扩成与 `__rpg_v12.js` 对位的版本，**但只保留 MZ 真实存在的 API**。

`__mz_hook.js` 内容清单（逐条都要有 MZ 侧依据）：

| 项 | 依据 | 做什么 |
| --- | --- | --- |
| `Utils.isMobileDevice` | MZ 正则含 `Mobile|Android|iPhone|iPad`，在真机上为 `true` | ~~钉 `false`~~。**〔v3 落地修正：未采纳〕** 原理由（与已被 polyfill 钉成 `false` 的 `isNwjs` 保持同步）不成立：二者语义本就不同，`_defaultStretchMode` 要的正是「移动端拉伸」，把 `isMobileDevice` 钉成 false 会**关掉**它。实际做法是保留 `isMobileDevice` 原样，由下一行的 `_stretchHeight` 覆写负责铺满——这已经消除了 `0.9` 缩放留黑边的原始症状，且不动 `_defaultStretchMode`（该函数继续返回 `true`，横屏按视口拉伸） |
| `Graphics._stretchHeight` | 现有 `__hook_rmmz_core.js` | 已落地：`__mz_core_hook.js` 改为直接返回 `document.documentElement.clientHeight`（MZ 原实现会乘 `Utils.isLocal() ? 1.0 : 0.9`） |
| `document.body.parentNode.style.overflow` | 现有 `__rmmz__.js` | 已落地：保留在 `__mz_hook.js` 的顶层（不依赖任何未定义全局） |
| `SceneManager.isGameActive` | MZ `window.top.document.hasFocus()` | 已落地：`__mz_managers_hook.js` 里保持 `return true`。**注意求值时机**：必须放在 managers hook（`SceneManager` 已定义），不能放 `__mz_hook.js`——后者求值时 `SceneManager` 还不存在 |
| `Graphics._defaultStretchMode` | MZ = `isNwjs() \|\| isMobileDevice()` | **〔v3 落地〕不碰**。覆写它会让 MZ 在横屏时按 `Graphics.width/height` 原尺寸居中而不拉伸，与「铺满」目标冲突；铺满由 `_stretchHeight` 单独负责即可 |
| PC 存档兜底 | `RpgSaveFormat` 的 `fileN.bin` 语义 | `loadObject("file1")` 失败时回退读 `RPG File1` 键（兼容从 MV 工程搬来的存档）—**可选，先不做，避免引入非预期行为** |

**不要**塞进去的：`Decrypter.*`（MZ 无）、`DataManager.isThisGameFile`（MZ 无）、
`StorageManager.webStorageKey`（MZ 无）、`PIXI.*` 的 MV4 参数（MZ 是 PIXI 5）。

---

### 阶段 F 🟢 性能补丁（可选，1.5 天）

按实测决定是否落地，全部放 `__mz_overrides.js`，**默认关闭**，由设置项控制：

| 补丁 | 来源 | 收益 | 风险 |
| --- | --- | --- | --- |
| `MAX_TEXTURE_SIZE` 降采样 + `resolution` 补偿 | JoiMZ / GameNative | 大图（6320px 立绘）不再上传失败变黑 | 缩放插值可能变糊 | **已落地**（仅降采样，`8c9caff0d`，常开） |
| tilemap 片元着色器强制 `highp` | GameNative | Mali fp16 精度不足导致的网格接缝 | 低（只是精度声明） |
| `PIXI.filters.BlurFilter` 的 `blur===0` 换恒等 | GameNative | 省 8 个 RGBA8 pass | 低 |
| `Tilemap.Renderer._createInternalTextures` 固定 2048 | JoiPlay `PluginOverridesMZ` | tilemap 显存 | 低 |

**落地前必须**：先在改动前后各录一段同场景，确认**画面无变化**、帧时间下降。
看不出来就不落——这些补丁都会改变渲染行为，收益不明确时是净负担。

---

### 阶段 G 🟢 注入配置数据化（2 天，可延后）

把 [`buildInjectionBundle()`](../engine/src/main/java/com/core/rpgmaker/RpgMakerActivity.kt) 里
`isRpgMvV1 / isRpgMvV2 / isRpgMzV2` 三个布尔 + 三处 `when` 抽成一份数据表
（参考 GameNative 的 `packs/rmmv.json` 思路，但**不抄代码，GPL-3.0**）：

```kotlin
private data class RpgSessionProfile(
    val engine: WebGameType,
    val version: String?,            // null = v0 兜底
    val coreScriptOverlay: String?,  // "rpgmaker/rpgmv-v1" 或 null
    val polyfillExtras: List<String>,
    val lateHook: String,
    val scriptAppends: Map<String, String>,
)
```

价值：将来 `MV v3` / `MZ v3` 变成加一行数据，而不是改 `when`。
**当前只有 3 个会话，收益有限**——排在最后，只在阶段 A–E 都稳定后再做。

---

### 阶段 H 🟢 启动就绪信号（0.5 天）

**问题**：MZ 的 `index.html` 只有 1 个 `<script src="js/main.js">`，
`main.js` 动态注入其余脚本 → `PluginManager.setup` → **`effekseer.initRuntime(wasm)` 成功后才** `SceneManager.run`。
现有 `EngineStartupOverlay` 用"首帧可见"判定，会**加载条消失但游戏还在黑屏解 wasm**。

**改动**：`RpgMakerActivity` 的 `requestStartupVisualReveal`
（[`:729-739`](../engine/src/main/java/com/core/rpgmaker/RpgMakerActivity.kt)）对 MZ 会话改为轮询：
```js
(function(){ return !!(window.SceneManager && SceneManager._scene) })()
```
成功后才 `startupOverlay?.reveal()`，超时（如 15s）则 reveal 并记 warn（不卡死）。

> 这一条风险很低、体验收益明显，建议**提前到阶段 C 一起做**。

---

## 4. 改动清单（按文件）

| 文件 | 阶段 | 改动 |
| --- | --- | --- |
| [`RpgMakerLocalHttpServer.kt`](../engine/src/main/java/com/core/rpgmaker/RpgMakerLocalHttpServer.kt) | A | `isImmutableAsset` / `mime` / `resolveRequestedFile` / 构造参数 `mzSession` |
| [`RpgMakerActivity.kt`](../engine/src/main/java/com/core/rpgmaker/RpgMakerActivity.kt) | D/H | `earlyHtml` 加 Effekseer 桩（sessionConfig 与 setter 拦截合并为同一份）、MZ 就绪轮询、hook 资产常量。~~桥改 base64~~**〔v3〕不动存档桥**；~~`onPause/onResume` 音频~~已有，不动 |
| [`RpgMakerStorage.kt`](../engine/src/main/java/com/core/rpgmaker/RpgMakerStorage.kt) | — | **〔v3〕不动**。存档方式保持明文 JSON + UTF-8，不新增 `writeBytes` |
| [`RpgSaveFormat.kt`](../app/src/main/java/com/tyranor/next/core/game/save/RpgSaveFormat.kt) | B | **〔v3〕已实现**：`needsPayloadTranscode` / `encodeMzPayload` / `decodeMzPayload`（幂等 + 坏数据拒绝） |
| [`RpgSaveFormatConverter.kt`](../app/src/main/java/com/tyranor/next/core/game/save/RpgSaveFormatConverter.kt) | B | **〔v3〕已实现**：导入时对 MZ 解压，失败计 failed 且不写半成品 |
| [`GameSaveManager.kt`](../app/src/main/java/com/tyranor/next/core/game/save/GameSaveManager.kt) | B | **〔v3〕已实现**：`writeZipContents` 增 `encodePayload` 参数；导出「标准格式」时对 MZ 压缩 |
| `engine/src/main/assets/mz/*.js` | B/C/D/E/F | 4 个新脚本 |
| [`EngineSettingsStore.kt`](../app/src/main/java/com/tyranor/next/core/settings/EngineSettingsStore.kt) | C/D | 3 个新键 + 读写 + 归一化 |
| [`PerGameSettingsStore.kt`](../app/src/main/java/com/tyranor/next/core/settings/PerGameSettingsStore.kt) | C/D | 3 个 `F_*` 覆盖字段 |
| [`EffectiveEngineSettings.kt`](../app/src/main/java/com/tyranor/next/core/settings/EffectiveEngineSettings.kt) + [`EngineSettingsResolver.kt`](../app/src/main/java/com/tyranor/next/core/settings/EngineSettingsResolver.kt) | C/D | 三级合并 |
| [`EngineLauncher.kt`](../app/src/main/java/com/tyranor/next/core/game/launch/EngineLauncher.kt) | C/D | 3 个 extra 下发 |
| [`LaunchContract.kt`](../engine/src/main/java/com/core/engine/LaunchContract.kt) | C/D | 3 个常量 |
| [`SettingsScreen.kt:1506-1516`](../app/src/main/java/com/tyranor/next/ui/settings/SettingsScreen.kt) | C/D | MZ 专有行 |
| `res/values{,-en,-ja}/strings.xml` | 全程 | 选项文案 + 显示名改 `TynMZ-0.2` |
| [`SPEC.md`](../SPEC.md) / [`ARCHITECTURE.md`](../ARCHITECTURE.md) / [`CONTEXT.md`](../CONTEXT.md) / [`CHANGELOG.md`](../CHANGELOG.md) | 收尾 | 同步 |

---

## 5. 测试计划

### 5.1 JVM（`app/src/test`、`engine/src/test`）

- `RpgMakerServerMzAssetTest`：`_` 后缀缓存策略、MIME、反向回退优先级。
- `RpgSaveFormatPayloadTest`：**〔v3〕** 编码/解码往返、双向幂等、空载荷、损坏载荷拒绝、压缩确实变小（已实现，8 项）。
- `RpgEffekseerModeTest`：`Chrome/124.x` / `Chrome/99.x` / 无 Chrome 段 → auto 判定。
- `RpgVersionSettingsTest`：MZ 版本值仍为 `"v2"`（**不要被"改名"诱导去改值**）。

### 5.2 JS harness（`engine/src/test/js`）

扩展 [`rpgmv-save-validation.harness.js`](../engine/src/test/js/rpgmv-save-validation.harness.js) 的
MZ 段（`:510-547`）：
- `saveObject → loadObject` 往返对象相等（**〔v3〕引擎侧不变**：仍存明文 JSON，断言不受影响）；
- `VorbisDecoder` 存在/缺失时 `_shouldUseDecoder` 的两种行为。

> **〔v3〕** 原「落盘载荷不是 UTF-8 可读（证明走了 deflate）」与「`pako` 缺失回退」两条**作废**：
> 引擎侧已确定不改，落盘本来就是可读 JSON。MZ 的格式转码改由 JVM 侧 `RpgSaveFormatPayloadTest` 覆盖（见 5.1）。

### 5.3 真机（必做，不能只跑单测）

| 样本 | 验收 |
| --- | --- |
| 加密 MZ 商业作品 | 图片/BGM/SE 全部正常；第二次请求走 304 |
| 混合加密（只加密 img/） | 明文补丁素材不 404 |
| 未加密 MZ 作品 | 无额外 404 噪声 |
| 存档：新建/读档/删除 | 三条链路通（**〔v3〕引擎侧格式不变，此为回归项**） |
| 存档：导出「标准格式」`.rmmzsave` → PC 版读取 | **真互通判据**（转换层转码生效） |
| 存档：PC 版 `.rmmzsave` → 导入 | 反向互通 |
| **〔v3〕导出后 Tyranor 自己仍能读** | 转码只作用于导出副本，不落地回引擎目录 |
| 存档：`typeof process === 'object'` 且 `Utils.isNwjs() === false` | MZ 与 MV 共用同一份 `__nwjs_polyfill.js`，无第二套 polyfill |
| WebView < 124 + 带 Effekseer 的战斗 | 5 分钟不闪退；`effekseer:on` 对照组应闪退 |
| ≥5 分钟长 BGM 切场景 | 不卡顿 |
| **MV 三条链路全回归** | `Tyranor-2.3.4` / `TynMV-0.1` / `TynMV-0.2` 行为零变化 |
| **〔v2〕PC 真实存档**：新建 → 导出 → PC 读取 → 保存 → 导入 → 手机读档 | 两轮往返后 `contents` 位级一致（`lzvalid` 基准，见 B.2） |

---

## 6. 风险登记

| # | 风险 | 影响 | 缓解 |
| --- | --- | --- | --- |
| R1 | **Effekseer 阈值 124 未在本仓库设备复现** | 默认 `auto` 可能误关或误开 | 提供 `on/off` 手动口；阶段 D 必须做对照组实验 |
| R2 | **MZ corescript 只能靠社区镜像取证**（官方仓库不存在，镜像无 LICENSE） | 结论按 v1.1.0，MZ 1.2+ 可能变 | 所有 hook 都做**能力探测**而非版本号判断；不打包镜像代码 |
| R3 | **〔v3 重估〕载荷转码写错会产出 PC 版读不出的存档** | 影响**仅限导出副本**——引擎侧存的是明文 JSON，Tyranor 自读不受影响，损坏面从"存档全丢"缩到"导出文件不可用" | 转码只发生在转换层（不碰引擎）；`RpgSaveFormatPayloadTest` 的往返 + 幂等 + 损坏拒绝三组守卫；真机用 PC 真实存档双向验证 |
| R3b | **〔v2〕WebView 版本号不是 Effekseer 的真实变量** | 阈值 124 可能在某些设备/内核上不准，`auto` 误判 | 提供 `on/off` 手动口；阶段 D 的对照实验按 **SoC/内核**记录而非只记 WebView 版本；日志里打印 `canUseWebAudioAPI()`、`WebGL2` 扩展与 `isMobileDevice` 实际值 |
| R4 | **`process` 函数/对象路线互斥** | 混用会导致 YEP 插件初始化崩 或 存档全丢 | **〔v2 修正理由〕** 本仓库 [`__nwjs_polyfill.js:30`](../engine/src/main/assets/rpgmaker/__nwjs_polyfill.js#L30) 写的是 `window.process = {}`（对象），再用 `pinIsNwjs` 强制 `isNwjs()===false`——与 GameNative"让 `process` 是函数"殊途同归。MZ **复用同一份 polyfill** 即可自然一致，这不是需要单独决策的事项；只需在阶段 B 验收里加一条"`typeof process === 'object'` 且 `isNwjs() === false`"的断言，防止有人为 MZ 另起一套 |
| R5 | `__rmmz__.js` 仍被 v0 路径（`TyranoActivity`）使用 | 误删会破坏 `Tyranor-2.3.4` 的 MZ 支持 | 新建 `mz/` 目录，**保留旧三件套不删** |
| R6 | GameNative 是 **GPL-3.0** | 拷代码会污染许可 | 只读架构，所有补丁自己重写；补丁来源在注释里写"思路参考"，不复制实现 |
| R7 | 阶段 F 的性能补丁改变渲染行为 | 可能引入画面回归 | 其余补丁未做；**已落地的 `MAX_TEXTURE_SIZE` 降采样是常开的**，只会让超限大图略糊（未超限不受影响），不再上传失败变黑 |

---

## 7. 排期与依赖

```
A 加密素材(0.5d) ──┐
                    ├─→ E hook扩写(1d) ─→ F 性能补丁(1.5d, 可选)
B 存档互通(0.5d,已实现) ┤
                    ├─→ H 启动就绪(0.5d)
C 音频(1d) ────────┤
                    └─→ D Effekseer(1.5d) ─→ G 数据化(2d, 可延后)
```

- **〔v3〕B 不再是关键路径**：代码已实现（`RpgSaveFormat` 编解码 + converter + export 三处）并单测通过，
  剩余只有真机互通验证（需 PC 真实存档样本）。
- A / C / H 互不依赖，可并行。
- D 与 C **不再有先后依赖**（〔v2〕Effekseer 桩已挪到 `earlyHtml`，先于 `main.js` 执行；C 的 decoder 改写走 `__mz_hook.js` lateHook）。两者都要在 `effekseer.min.js` 之前，但落点不同、互不阻塞。
- **最小可发布集**：A + B + H（约 1.5 天编码 + 验证），此时 MZ 的"跑得对、存得住、导得出"已经成立。
- 完整集（含 C/D/E）：约 4.5 天编码 + 验证。

---

## 8. 开工前检查表

- [ ] 确认 `EngineSettingsStore.RPG_MZ_V2` 的**值**保持 `"v2"`，只改显示名
- [ ] 确认 `__rmmz__.js` / `__hook_rmmz_*.js` 仍被 `TyranoActivity` 引用，**不删**
- [ ] 拉一个**未加密** + 一个**加密** + 一个**混合加密**的 MZ 样本各一
- [ ] **〔v2〕准备 PC 端真实 `.rmmzsave`**：用同一款样本在 PC 版 MZ 运行时新建、保存一份（至少 2 个存档位，含道具/开关/变量），这是阶段 B 的真互通判据；没有它 B 不算完成
- [ ] 准备一台 WebView ≥ 124 和一台 < 124 的设备
- [ ] ~~准备一个 `pako` 缺失的极端样本~~ **〔v3〕作废**：引擎侧不改存档方式，不依赖 `pako` 是否存在
- [ ] 读一遍 [AGENTS.md](../AGENTS.md) 的错误处理协议（core→ui 类型化，不把文案塞进 core）
- [ ] 跑 `python tools/check-hardcoded-ui-strings.py` 确认新增文案进三语言
