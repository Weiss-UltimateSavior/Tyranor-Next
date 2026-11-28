# RPG Maker MZ 运行时移植实施方案（TynMZ-0.2）

> 目标：把 `TynMV-0.2`（MV v2 会话）的运行方案移植到 MZ，交付 `TynMZ-0.2`。
> 本文是**实施计划**，不代表已实现；落地后须同步 [SPEC.md](../SPEC.md)、
> [ARCHITECTURE.md](../ARCHITECTURE.md)、[CONTEXT.md](../CONTEXT.md)、[CHANGELOG.md](../CHANGELOG.md)。
>
> 依据：本仓库只读代码盘点 + `D:\code\joiplay` 逆向逐文件核对 +
> MZ corescript 社区镜像 `stak/rmmz-corescript` v1.1.0 逐行比对 + 公开资料交叉验证。
> 可行性结论与差异取证见 [RPGMaker-MZ-移植评估.md](RPGMaker-MZ-移植评估.md)，本文只讲**怎么做**。
>
> 状态：**计划中**（尚未开工）。最后更新 2026-11-28（第 2 版：已按
> [审阅意见](RPGMaker-MZ-移植方案审阅意见.md) 修正 3 处事实错误、补 6 处方案缺口；改动处标 **〔v2〕**）。

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

**〔v2〕** 原第 3 项 `rpg_mz_save_native` 已删除：存档格式只有一个实现（deflate 兼容格式 + 旧裸 JSON 嗅探直通），
没有它要切换的两个目标，开关没有存在意义（见阶段 B）。

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

### 阶段 B 🔴 存档管线（2 天）

#### B.0 决策：B-2 兼容格式是**唯一**路径，不再保留开关

| 方案 | 落盘 | 优点 | 缺点 |
| --- | --- | --- | --- |
| ~~B-1 自洽格式~~（现状） | `<name>.bin`，内容 = 未压缩 JSON | 零改动、已验证 | 与原生 `.rmmzsave` **不兼容**；用户手动拷入读不出；存档互通只能走 Tyranor 内部 |
| **B-2 兼容格式**（采纳） | `<name>.bin`，内容 = **deflate 流**（与 pako 逐字节等价） | 与原生 `.rmmzsave` 内容一致，只差扩展名；`RpgSaveFormat` 的改名映射立刻生效 | 需要 Kotlin 侧 zlib + 字节流改造 |

**〔v2〕** 原稿把 B-1/B-2 做成 `rpg_mz_save_native` 开关，但 B-1 作为降级路径没有任何触发条件
（deflate 失败不会「退回裸 JSON」，只会 reject），开关就没有两个可切换的目标。现改为：
**新写入一律 deflate；读取时按内容嗅探**，旧裸 JSON 文件直接直通。开关删除（§2.2 已同步）。

#### B.0′ 开工第一步：核实 `saveToLocalFile` 的落盘字节（**前置，未核实不开工**）

B-2 的成立依赖一个原稿没给出证据的前提：MZ 原生 `saveToLocalFile` 落盘的**就是** `jsonToZip` 的
raw 输出，外面没有长度头、没有再包一层。目前「二进制」只是评估稿表格里的一个词。

开工前必须在 MZ corescript 镜像里找到并记录这两行的行号：

- `StorageManager.saveToLocalFile(saveName, zip)` → `fs.writeFileSync(filePath, zip, { encoding: "binary" })`（预期形态）
- `StorageManager.loadFromLocalFile(saveName)` → `fs.readFileSync(filePath, { encoding: "binary" })`（预期形态）

同时核实 `jsonToZip` 的实现是 `pako.deflate(json, { to: "string", level: 1 })`，
`zipToJson` 是 `pako.inflate(zip, { to: "string" })`。若实际有任何一层封装（头、校验和、不同 level），
B.1 的 Kotlin 复刻要跟着改，并把差异写回本节。

> **〔v2〕** 这条比 R3「pako 是 binary string」更上游：R3 关心的是传输编码，
> 这条关心的是落盘格式本身。证据行号补齐后，把 R3 的缓解措施第一条改为「已核实，见 B.0′」。

#### B.1 桥与存储层一起改字节流（B-2 前提）

现状 [`RpgMakerActivity.kt:1026-1039`](../engine/src/main/java/com/core/rpgmaker/RpgMakerActivity.kt)：

```kotlin
inner class RpgMakerSaveBridge(private val saveDirectory: File?) {
    fun Save(key: String?, base64Data: String?) = RpgMakerStorage.write(..., RPG_MV_SAVE_EXTENSION)
    fun Load(key: String?): String = RpgMakerStorage.read(...)
    ...
}
```

而 [`RpgMakerStorage.write`](../engine/src/main/java/com/core/rpgmaker/RpgMakerStorage.kt) 内部是
`text.toByteArray(StandardCharsets.UTF_8)`（`:47`），`read` 是 `String(bytes, UTF_8)`（`:27`）。
**〔v2〕** 这意味着：**只给桥加 base64 入口、不改存储层是不够的**——`SaveBase64` 解出的字节若再塞进
现有 `write(String)`，会被按 UTF-8 二次编码，deflate 流里的 `0x80–0xFF` 字节会被扩成两字节，存档损坏且
**只在读档时暴露**。改造必须两层同时做：

- `RpgMakerStorage` 新增 `readBytes/writeBytes/existsBytes/removeBytes`：
  - `writeBytes` 复用现有 `write()` 的**全部**原子性逻辑（预检上限 → tmp → `fd.sync()` → `renameTo`，
    失败退回直接覆盖），只把「String → UTF-8 bytes」换成「入参就是 bytes」；
  - `existsBytes` / `removeBytes` 与现有 `exists` / `remove` 完全同语义（同一个文件、同一个
    `resolveFile`），可以直接委托，**不要**复制一份；
  - 最好把 `write()` 重构成对 `writeBytes()` 的薄封装（`write(dir, key, value, ext) = writeBytes(dir, key, value.toByteArray(UTF_8), ext)`），
    避免「原子写」逻辑出现第二份实现。
- 桥接口改 base64 传输（`@JavascriptInterface` 只能传 String）：
  ```kotlin
  @JavascriptInterface fun SaveBase64(key: String?, base64: String?): Boolean
  @JavascriptInterface fun LoadBase64(key: String?): String
  ```
  **保留旧 `Save`/`Load`**（MV 与 v0/v1 路径仍在用），只新增不删除。
- 上限：MZ 存档 deflate 后通常远小于 8 MiB；`MAX_SAVE_BYTES` 维持 8 MiB 不变（按**解码后的字节数**计），
  base64 入参膨胀 4/3 → 入参字符串长度上限预检用 `MAX_SAVE_BYTES * 4 / 3`。超限记日志拒绝。

#### B.2 JS 侧 hook（`__mz_managers_hook.js`）

现状 [`__hook_rmmz_managers.js`](../engine/src/main/assets/__hook_rmmz_managers.js) 22 行，
把 `saveObject/loadObject` 改成 `window.saveDataManager.Save(name, json)` ——
**跳过了 `jsonToZip`**，所以存的是裸 JSON。

新 hook 要按 `isLocalMode()===false`（我们钉死的）走一条**完整复刻**，并带旧格式嗅探：

```js
// 旧版 TynMZ-0.1 写出的 .bin 是裸 JSON（跳过了 jsonToZip）；deflate 流的首字节不可能 是 '{'
// （zlib 头 0x78 或 raw deflate 的首字节按 BFINAL/BTYPE 取值，低 3 位不会使整字节等于 0x7B）。
// 详见 B.2′。
function isLegacyRawJson(zip) {
    return typeof zip === "string" && zip.length > 0 && zip.charCodeAt(0) === 0x7B;
}

StorageManager.saveObject = function (saveName, object) {
    return this.objectToJson(object)
        .then(json => this.jsonToZip(json))          // pako deflate，与原生一致
        .then(zip => new Promise((resolve, reject) => {
            const ok = window.saveDataManager.SaveBase64(saveName, btoa(zip));
            ok ? resolve() : reject(new Error("save failed: " + saveName));
        }));
};
StorageManager.loadObject = function (saveName) {
    return new Promise((resolve, reject) => {
        const b64 = window.saveDataManager.LoadBase64(saveName);
        if (!b64) return reject(new Error("Savefile not found"));
        resolve(atob(b64));
    }).then(zip => isLegacyRawJson(zip) ? zip : this.zipToJson(zip))
      .then(json => this.jsonToObject(json));
};
StorageManager.exists = function (saveName) {
    return window.saveDataManager.Exists(saveName) === true;
};
```

> **关键**：`pako.deflate(json, {to:"string"})` 产出的是 **binary string**（每字符一个字节），
> 所以 `btoa(zip)` 正确；Kotlin 侧 base64 解码后**必须按原始字节写**（B.1 的 `writeBytes`），
> 不能再按 UTF-8 编码。`pako` 的 `to:"string"` 不是 UTF-8 字符串。
> 嗅探用的是 `charCodeAt(0) === 0x7B`（`'{'`），**不要**用 `zip[0] === "{"` 之外的任何正则——
> binary string 上跑正则会把 `0x80+` 的字节当成 UTF-16 代理对误判。

**`StorageManager.remove`**：**〔v2〕** 原稿漏了。MZ 的 `remove(saveName)` 在 web 模式下调 `localforage.removeItem`，
`isLocalMode()` 为 false 时我们钉死了 web 模式，但 `localforage` 在本宿主里**根本不存在**（没有注入）。
现有 `__hook_rmmz_managers.js` 没覆盖它，`DataManager.deleteSavefile` 调用时会抛 `ReferenceError`。
新 hook 必须补：
```js
StorageManager.remove = function (saveName) {
    return new Promise(resolve => { window.saveDataManager.Remove(saveName); resolve(); });
};
```

**降级策略**：若 `pako` 不存在（极老游戏或裁剪包），`jsonToZip` 自身就会抛，此时回退到裸 JSON
路径并记一条 warn，不要抛错（否则整个存档链死掉）。回退写出的文件会被 B.2 的嗅探识别为旧格式，
自洽。

**`SceneManager.isGameActive`**：MZ 原生是 `window.top.document.hasFocus()`，
在 WebView 里恒为 `true`（我们不在 iframe 里），现有 hook 的 `return true` 可以直接保留。

#### B.2′ 为什么用「首字节是否 `{`」而不是别的嗅探

**〔v2〕** 这个判断成立的前提是可以论证的，不是拍脑袋：

- deflate 流（无论 zlib 封装还是 raw）的**首字节**：
  - zlib 封装：CMF 字节 = `0x78`（CM=8, CINFO=7），固定；
  - raw deflate：首字节低 3 位是 BFINAL(1 bit) + BTYPE(2 bit)，BTYPE 只能是 00/01/10，
    所以首字节模 8 ∈ {0,1,2,4,5,6}，而 `'{' = 0x7B = 123`，123 mod 8 = 3 → **不可能**。
- JsonEx 的 `makeDeepCopy`/`objectToJson` 输出以 `{` 开头（存档是对象，不是数组）。
- 空文件、空串：`length === 0` 走 `zipToJson` 会抛，交给 reject 路径（与 MZ 原生「空文件 = 读档失败」一致）。

所以 `charCodeAt(0) === 0x7B` 对「裸 JSON vs deflate」是**零误判**的。

#### B.3 `RpgSaveFormat` 与互通的衔接

`RpgSaveFormat` 已把 `fileN.bin ↔ fileN.rmmzsave` 映射做好
（[`RpgSaveFormat.kt:285-296`](../app/src/main/java/com/tyranor/next/core/game/save/RpgSaveFormat.kt)）。
B-2 落地后：
- 导出「标准格式」→ 直接改名成 `.rmmzsave` 即可被 PC 版读取（内容已一致）；
- 导入 PC 存档 → 改名成 `.bin` 即可被引擎读取。
- **无需改 `RpgSaveFormat` 一行**。
- **〔v2〕** 一个要写进用户文档的边界：B-2 落地**之前**导出的 `.rmmzsave`（内容其实是裸 JSON）
  PC 版读不出；落地后从 App 重新导出一次即可。`RpgSaveFormat` 本身不用改，但 CHANGELOG 要提。

#### B.4 MZ 的写入中间态

MZ 原生 `saveToLocalFile` 会先 `rename` 到 `filePath + "_"` 作备份、成功再删。
我们是走桥不是走 `fs`，所以**不经过这段**；但要注意：
`<name>.bin` 与 `<name>.bin_` 可能同时存在（用户从 PC 拷贝过）。`resolveFile()` 只看 `.bin`，
不会误读 `.bin_`，**行为正确，无需处理**。

**测试**：
- JVM：`RpgMakerStorage.writeBytes/readBytes` 往返（含 0 字节、8 MiB 边界、超限拒绝）；
  **〔v2〕** 加一条「`writeBytes` 写入含 `0x80–0xFF` 的字节序列，`readBytes` 读回逐字节相等」——
  这是 B.1 那个「二次 UTF-8 编码」坑的回归守卫；再加「`write(String)` 与 `writeBytes(UTF_8)` 落盘字节相同」
  守卫重构后两条路径不漂移。
- JS harness：扩展现有 [`rpgmv-save-validation.harness.js`](../engine/src/test/js/rpgmv-save-validation.harness.js)
  的 MZ 烟测段（`:510-547`）。**〔v2〕** 现有 sandbox 里 `StorageManager` 只 mock 了
  `objectToJson/jsonToObject`，要补 `jsonToZip/zipToJson`（可直接 require 真实 `pako`，
  `engine/src/test/js` 下已有 Node 环境）和一个 base64 往返的 `saveDataManager` mock。断言：
  1. `saveObject → loadObject` 往返后对象相等，且中间产物**不是** UTF-8 可读 JSON（证明走了 deflate）；
  2. 喂一个首字节为 `{` 的旧裸 JSON 载荷给 `loadObject`，**直通**不报错（嗅探生效）；
  3. `pako` 缺失时回退裸 JSON 且不抛错；
  4. `StorageManager.remove` 调到 bridge 的 `Remove`。
- 兼容：用真实 `.rmmzsave` 文件（PC 版导出）喂给 `loadObject`，确认能读出。

**验收**：
1. MZ 游戏内存档 → 冷启动读档成功；
2. **〔v2〕** 用 B-2 落地**前**的 App 存一个档（裸 JSON `file1.bin`），升级后读档成功（嗅探生效）；
3. 导出 `.rmmzsave` → 拷贝到 PC 版游戏 `save/` 目录 → PC 版能读（**这是"真互通"的判据**）；
4. 反向同理。

**外部依赖（不是工时）**：验收 3/4 需要 PC 版 MZ 导出的真实 `.rmmzsave`。开工前就备好，别等阶段 B 做完再找。


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
5. `__mz_hook.js`（lateHook）里再加一道保险：
   ```js
   if (window.__tyranorRpg && window.__tyranorRpg.effekseer === false) {
       if (window.Graphics) Graphics._createEffekseerContext = function () { this._effekseer = null; };
   }
   ```

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
| `Utils.isMobileDevice` | MZ 正则含 `Mobile|Android|iPhone|iPad`，在真机上为 `true` | 钉 `false`。**〔v2 修正〕** 依据只有一条：MZ 的 `Graphics._defaultStretchMode` 是 `isNwjs() \|\| isMobileDevice()`，而 `isNwjs` 已被 polyfill 钉成 `false`，若 `isMobileDevice` 留 `true` 两者取值不同步。**原稿"不显示虚拟按键"的理由不成立**——虚拟按键是 MV 的 `Utils.isMobileDevice` 分支，MZ 核心脚本里没有；"不压缩画面"也只对 MV 的 `_createRenderer` 成立。落地时写成 `Utils.isMobileDevice = function () { return false; }`（MZ 里它是**方法**，不是 MV 的布尔属性） |
| `Graphics._stretchHeight` | 现有 `__hook_rmmz_core.js` | 保持现有实现（移动端用 `clientHeight`，避免 `0.9` 缩放留黑边） |
| `document.body.parentNode.style.overflow` | 现有 `__rmmz__.js` | 保留 |
| `SceneManager.isGameActive` | MZ `window.top.document.hasFocus()` | 保持 `return true`（WebView 里恒为 true，但避免 iframe 边界） |
| `Graphics._defaultStretchMode` | MZ = `isNwjs() \|\| isMobileDevice()` | 两者都钉成 `false` 后**此函数返回 `false`**——这会让 MZ 在横屏时按 `Graphics.width/height` 原尺寸居中而不拉伸。**〔v2〕** 这与下一行 `_stretchHeight` 的"铺满"目标冲突。正确做法是**整行删掉**（不要碰 `_defaultStretchMode`），只钉 `isMobileDevice`，让 `_stretchHeight` 的现有覆写负责铺满；或者反过来只覆写 `_defaultStretchMode = () => true` 而不钉 `isMobileDevice`。**二选一，不要两个都做**，真机验收以"横屏无黑边"为准 |
| PC 存档兜底 | `RpgSaveFormat` 的 `fileN.bin` 语义 | `loadObject("file1")` 失败时回退读 `RPG File1` 键（兼容从 MV 工程搬来的存档）—**可选，先不做，避免引入非预期行为** |

**不要**塞进去的：`Decrypter.*`（MZ 无）、`DataManager.isThisGameFile`（MZ 无）、
`StorageManager.webStorageKey`（MZ 无）、`PIXI.*` 的 MV4 参数（MZ 是 PIXI 5）。

---

### 阶段 F 🟢 性能补丁（可选，1.5 天）

按实测决定是否落地，全部放 `__mz_overrides.js`，**默认关闭**，由设置项控制：

| 补丁 | 来源 | 收益 | 风险 |
| --- | --- | --- | --- |
| `MAX_TEXTURE_SIZE` 降采样 + `resolution` 补偿 | JoiMZ / GameNative | 大图（6320px 立绘）不再上传失败变黑 | 缩放插值可能变糊 |
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
| [`RpgMakerActivity.kt`](../engine/src/main/java/com/core/rpgmaker/RpgMakerActivity.kt) | B/D/H | 桥改 base64 **+ 存储层新增 `writeBytes` 字节路径**、`earlyHtml` 加 Effekseer 桩（sessionConfig 与 setter 拦截合并为同一份）、MZ 就绪轮询、hook 资产常量。~~`onPause/onResume` 音频~~已有，不动 |
| [`RpgMakerStorage.kt`](../engine/src/main/java/com/core/rpgmaker/RpgMakerStorage.kt) | B | 新增 `readBytes/writeBytes/existsBytes/removeBytes`（复用原子写） |
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
- `RpgMakerStorageBytesTest`：字节流往返、0 字节、8 MiB 边界、超限拒绝、原子写不产生截断文件。
- `RpgEffekseerModeTest`：`Chrome/124.x` / `Chrome/99.x` / 无 Chrome 段 → auto 判定。
- `RpgVersionSettingsTest`：MZ 版本值仍为 `"v2"`（**不要被"改名"诱导去改值**）。

### 5.2 JS harness（`engine/src/test/js`）

扩展 [`rpgmv-save-validation.harness.js`](../engine/src/test/js/rpgmv-save-validation.harness.js) 的
MZ 段（`:510-547`）：
- `saveObject → loadObject` 往返对象相等；
- 落盘载荷**不是** UTF-8 可读（证明走了 deflate）；
- `pako` 缺失时回退裸 JSON 且不抛错；
- `VorbisDecoder` 存在/缺失时 `_shouldUseDecoder` 的两种行为。

### 5.3 真机（必做，不能只跑单测）

| 样本 | 验收 |
| --- | --- |
| 加密 MZ 商业作品 | 图片/BGM/SE 全部正常；第二次请求走 304 |
| 混合加密（只加密 img/） | 明文补丁素材不 404 |
| 未加密 MZ 作品 | 无额外 404 噪声 |
| 存档：新建/读档/删除 | 三条链路通 |
| 存档：导出 `.rmmzsave` → PC 版读取 | **真互通判据** |
| 存档：PC 版 `.rmmzsave` → 导入 | 反向互通 |
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
| R3 | **pako `to:"string"` 是 binary string 不是 UTF-8** | 写错会产出损坏存档，且**只在读档时暴露** | 阶段 B 的第一条测试就是"载荷不是可读 JSON"；用真实 PC 存档双向验证；B.2 的 `lzvalid` 位级基准是回归守卫 |
| R3b | **〔v2〕WebView 版本号不是 Effekseer 的真实变量** | 阈值 124 可能在某些设备/内核上不准，`auto` 误判 | 提供 `on/off` 手动口；阶段 D 的对照实验按 **SoC/内核**记录而非只记 WebView 版本；日志里打印 `canUseWebAudioAPI()`、`WebGL2` 扩展与 `isMobileDevice` 实际值 |
| R4 | **`process` 函数/对象路线互斥** | 混用会导致 YEP 插件初始化崩 或 存档全丢 | **〔v2 修正理由〕** 本仓库 [`__nwjs_polyfill.js:30`](../engine/src/main/assets/rpgmaker/__nwjs_polyfill.js#L30) 写的是 `window.process = {}`（对象），再用 `pinIsNwjs` 强制 `isNwjs()===false`——与 GameNative"让 `process` 是函数"殊途同归。MZ **复用同一份 polyfill** 即可自然一致，这不是需要单独决策的事项；只需在阶段 B 验收里加一条"`typeof process === 'object'` 且 `isNwjs() === false`"的断言，防止有人为 MZ 另起一套 |
| R5 | `__rmmz__.js` 仍被 v0 路径（`TyranoActivity`）使用 | 误删会破坏 `Tyranor-2.3.4` 的 MZ 支持 | 新建 `mz/` 目录，**保留旧三件套不删** |
| R6 | GameNative 是 **GPL-3.0** | 拷代码会污染许可 | 只读架构，所有补丁自己重写；补丁来源在注释里写"思路参考"，不复制实现 |
| R7 | 阶段 F 的性能补丁改变渲染行为 | 可能引入画面回归 | 默认关闭；落地前录屏比对 |

---

## 7. 排期与依赖

```
A 加密素材(0.5d) ──┐
                    ├─→ E hook扩写(1d) ─→ F 性能补丁(1.5d, 可选)
B 存档(编码1d+验证) ┤
                    ├─→ H 启动就绪(0.5d)
C 音频(1d) ────────┤
                    └─→ D Effekseer(1.5d) ─→ G 数据化(2d, 可延后)
```

- **关键路径**：B（存档）→ 其余。B 的编码可当天完成，但**真互通判据要等 PC 真实存档**（见 B.0），排期时把"等样本"单独留出来。
- A / C / H 互不依赖，可并行。
- D 与 C **不再有先后依赖**（〔v2〕Effekseer 桩已挪到 `earlyHtml`，先于 `main.js` 执行；C 的 decoder 改写走 `__mz_hook.js` lateHook）。两者都要在 `effekseer.min.js` 之前，但落点不同、互不阻塞。
- **最小可发布集**：A + B + H（约 2 天编码 + 验证），此时 MZ 的"跑得对、存得住"已经成立。
- 完整集（含 C/D/E）：约 5 天编码 + 验证。

---

## 8. 开工前检查表

- [ ] 确认 `EngineSettingsStore.RPG_MZ_V2` 的**值**保持 `"v2"`，只改显示名
- [ ] 确认 `__rmmz__.js` / `__hook_rmmz_*.js` 仍被 `TyranoActivity` 引用，**不删**
- [ ] 拉一个**未加密** + 一个**加密** + 一个**混合加密**的 MZ 样本各一
- [ ] **〔v2〕准备 PC 端真实 `.rmmzsave`**：用同一款样本在 PC 版 MZ 运行时新建、保存一份（至少 2 个存档位，含道具/开关/变量），这是阶段 B 的真互通判据；没有它 B 不算完成
- [ ] 准备一台 WebView ≥ 124 和一台 < 124 的设备
- [ ] 准备一个 `pako` 缺失的极端样本（可用旧版 MZ 导出验证降级）
- [ ] 读一遍 [AGENTS.md](../AGENTS.md) 的错误处理协议（core→ui 类型化，不把文案塞进 core）
- [ ] 跑 `python tools/check-hardcoded-ui-strings.py` 确认新增文案进三语言
