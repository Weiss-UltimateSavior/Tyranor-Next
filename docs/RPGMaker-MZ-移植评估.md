# TynMV-0.2 方案移植到 RPG Maker MZ 的可行性评估

> 目的：回答「现有 TynMV-0.2（MV v2 会话）的运行方案，能否移植到 MZ」。
> 本文是**调研与评估**，不代表已实现；当前行为以 [SPEC.md](../SPEC.md) 与
> [ARCHITECTURE.md](../ARCHITECTURE.md) 为准。
>
> 取证方式：本仓库只读代码盘点 + `D:\code\joiplay` 逆向成果逐文件核对 + MZ corescript
> 源码（社区镜像 `stak/rmmz-corescript` v1.1.0）逐行比对 + 公开资料交叉验证。凡未取到
> 实证的都标注「未证实」。

---

## 0. 结论

**可以移植，而且移植面比预期小得多；但"能跑起来"和"MV v2 那套能力全都在"是两件事。**

三句话总结：

1. **宿主壳（Kotlin / WebView / 本地服务器 / 桥 / 存档格式 / 修改器 / 输入）几乎 100% 与引擎无关，
   直接复用。** 全仓注入脚本里**没有任何** `RPGMAKER_NAME` / `Utils.RPGMAKER_VERSION` 判断，
   MV/MZ 分支全部集中在 `RpgMakerActivity.buildInjectionBundle()` 的 4 行判定 + 3 个极小的 MZ hook
   （`__rmmz__.js` 1 行、`__hook_rmmz_core.js` 8 行、`__hook_rmmz_managers.js` 22 行）。
2. **真正的 MZ 专有工作是四块，且都不在"宿主骨架"上**：加密资源后缀（`_` 而非 `.rpgmv*`）、
   存档管线（`StorageManager` 全异步 + pako deflate + localforage，且 MZ **已删除 localStorage**）、
   音频（只认 `.ogg` + `vorbisdecoder.wasm` + `WebAudio` 独占）、Effekseer（wasm 粒子，低端机致命）。
3. **MZ 侧目前是"通用兼容层 + 空壳 hook"**：`__rmmz__.js` 只有一行 `overflow:hidden`。
   MV v2 的 PC 存档兜底、渲染器交还、`isThisGameFile` 兜底在 MZ 上**没有等价物**，
   这不是遗漏——MZ 的对应 API 根本不存在（见 §3）。

**建议的路线**：不新开"MZ v2"，而是把 TynMZ-0.1 升级为「MZ 版 v2」（复用 `v2Session` 分支），
按 §6 的 6 个阶段补齐 MZ 专有件。其中 §4.1（加密后缀）和 §4.2（存档）是**必须先做、否则必崩**
的两块，其余可以按证据缺失程度分档落地。

---

## 1. 现状：TynMV-0.2 到底包含了什么

### 1.1 会话矩阵（三个布尔决定一切）

`RpgMakerActivity.buildInjectionBundle()` 只做四行判定
（[`RpgMakerActivity.kt:282-287`](../engine/src/main/java/com/core/rpgmaker/RpgMakerActivity.kt)）：

```kotlin
v12Session        = MV×v1 || MV×v2 || MZ×v2   // 是否注入 NW.js 兼容层
v2Session         = MV×v2 || MZ×v2            // 是否注册真 fs/env 桥、fuzzyNames
useCoreScriptOverlay = MV×v1                  // 是否覆盖游戏自带 corescript（MZ 永远 false）
```

**MZ v2 与 MV v2 走的是同一条 `v12Session && v2Session` 分支**——也就是说，
"MZ 移植"这件事在宿主层**已经做了 80%**：`TynMZ-0.1` 就是 MZ × v2。

### 1.2 四个注入通道（v2 会话的实际内容）

| 通道 | 落点 | v2 内容 | MZ 现状 |
| --- | --- | --- | --- |
| `earlyHtml` | `</head>` 前 | `window.__tyranorRpg={engine,version}` + 外链 `nwjs_polyfill.js` + `nwjs_polyfill_v1.js` + `nwjs_polyfill_v2.js` + 完整性哨兵 | ✅ 已注入（与 MV v2 完全相同） |
| `lateHook` | `</body>` 前 | MV：`rpgmaker/__rpg_v12.js`；MZ：`__rmmz__.js` | ⚠️ MZ 的 hook **只有 1 行** |
| `scriptAppends` | 指定脚本末尾 | MV：空；MZ：`js/rmmz_core.js`、`js/rmmz_managers.js` | ⚠️ 只有 8 行 + 22 行 |
| `internalResources` | `/__tyranor__/**` | 修改器 4 件套 + 兼容层外链本体 | ✅ 复用 |

证据：`RpgMakerActivity.kt:311-324`（hook 与 appends 选择）、`:342-362`（polyfill 装配顺序
base→v1_extra→v2_extra，v2_extra 在 `isRpgMvV2 || isRpgMzV2` 时加入）、`:369-382`（会话配置与哨兵）、
`RpgMakerLocalHttpServer.kt:980-1021`（两段式注入的实现）。

### 1.3 v2 相对 v1 的 14 项增量（这些就是"要移植的方案"）

1. 真文件系统桥 `TyranorFs`（v1 的 `fs` 是静默空桩）
2. 原生能力桥 `TyranorEnv`（hash/HMAC/random/KDF/AES/zlib/crc32）
3. 素材名宽松匹配 `fuzzyNames`（全/半角空格归一、四级放宽、4096 条目上限）
4. `localStorage` 落盘到 `AppData/Local Storage`（200ms 合并写 + 原生 journal + 退出 flush）
5. `Buffer` 换成 `Uint8Array` 底座（索引读写生效）
6. JsonEx 存档修复（原型 rehydrate + 1.3.x 标记转换 + 毒存档字段兜底）
7. `fs`/`path`/`require` 语义修正 + `evalNWBin` 降级
8. Node 内建模块长尾补齐（assert/stream/string_decoder/timers/vm/punycode/常数/fs.promises…）
9. `__dirname` / `process.cwd` / `nw.gui.App.dataPath` 指向真实游戏目录
10. 渲染器交还游戏 core（不再覆盖 `_createRenderer` 与 `textureGC`）
11. `screen.orientation` 兜底
12. 全局错误捕获探针（`[v2-err]` 前缀打进 logcat）
13. 去掉 v1 的核心脚本覆盖
14. 独立的 WebGL1-on-WebGL2 shim（**实际不启用**，见下）

关键：**第 1–9、11–13 项全部与引擎无关**。第 10 项是 MV 专有（`_createRenderer` 是 MV 的 API），
第 14 项已被 `NWJSApi.isTranspileEnabled` 门控成死代码
（[`__nwjs_polyfill_v2.js:59-63`](../engine/src/main/assets/rpgmaker/__nwjs_polyfill_v2.js) 注释明写
"Tyranor 无 NWJSApi：不劫持"）。

---

## 2. MZ 与 MV 的实质差异（移植必须改的清单）

以下逐条给出**结论 + 证据**，并标注对本方案的影响等级。

### 2.1 【必须改·致命】加密资源后缀规则完全不同

| | MV | MZ |
| --- | --- | --- |
| 图片 | `.png` → `.rpgmvp` | `.png` → **`.png_`** |
| 音频 | `.ogg` → `.rpgmvo`，`.m4a` → `.rpgmvm` | `.ogg` → **`.ogg_`**，`.m4a` → **`.m4a_`** |
| 实现 | `Decrypter.extToEncryptExt()` **替换**扩展名 | `Bitmap._startDecrypting()` / `WebAudio._realUrl()` 直接 **`url + "_"`** 追加下划线 |
| `Decrypter` 类 | 存在 | **整体删除**（`rpgmvp/rpgmvo/rpgmvm` 在 MZ 全域 0 命中） |
| 解密头 | `52 50 47 4D 56 00 00 00 00 03 01 00 00 00 00 00` + 16 字节 XOR | **逐字节相同** |

证据：
- 本仓库 [`RpgMakerLocalHttpServer.kt:583-597`](../engine/src/main/java/com/core/rpgmaker/RpgMakerLocalHttpServer.kt)
  的 `.png→.rpgmvp` / `.ogg→.rpgmvo` 回退**对 MZ 恒不命中**（MZ 请求的不是这个文件名）；
  [`RpgMakerLocalHttpServer.kt:492-499`](../engine/src/main/java/com/core/rpgmaker/RpgMakerLocalHttpServer.kt)
  的缓存白名单也不含 `_` 后缀。
- MZ 侧：`rmmz_core.js` 的 `_startDecrypting`（`xhr.open("GET", this._url + "_")`）与
  `WebAudio._realUrl()`（`this._url + (Utils.hasEncryptedAudio() ? "_" : "")`）；
  `Utils.decryptArrayBuffer()` 校验同一 16 字节头。
- 第三方工具交叉印证：[RPGMakerDecrypter](https://github.com/LynxShu/RPGMakerDecrypter)
  （"MV `.rpgmvp/.rpgmvo/.rpgmvm` 与 MZ `.png_/.ogg_/.m4a_`"）、
  [rpgm-asset-decrypter-rs](https://github.com/RPG-Maker-Translation-Tools/rpgm-asset-decrypter-rs)。

**⚠️ 修正（本文第一版曾误判为"必炸"）**：MZ 的 `_` 是**追加在完整 URL 之后**，
所以请求路径 `img/pictures/a.png` → `img/pictures/a.png_`，**与磁盘上的文件名逐字符相同**。
`resolveRequestedFile()` 的 `canonicalIfValid()` 直接命中，**不需要任何回退就能读到文件**。
本仓库不存在"加密 MZ 素材整批 404"的问题，第一版结论作废。

**真实影响只有两条，都不是致命项**：
1. **缓存降级（性能）**：`isImmutableAsset()` 按最后一个 `.` 取后缀，`.png_` 取出来是 `".png_"`，
   不在白名单 → 落到 `REWRITABLE_CACHE_CONTROL`（no-cache + **不发 ETag**）。
   于是**每一张加密图片、每一段加密音频每次请求都整块重传**，加密 MZ 作品会出现
   明显的场景切换卡顿与流量放大。
2. **混合加密工程的 404**：商业作品常见"只加密 `img/`、后加补丁素材不加密"。
   此时 MZ 仍会请求 `a.png_`，而盘上只有 `a.png` → 404。
   MV 侧有 `.png→.rpgmvp` 兜底，MZ 侧**没有反向兜底**（`a.png_→a.png`）。

**修法**：
- 在 `resolveRequestedFile()` 增加 **反向** 回退 `_` 后缀 → 去下划线（仅 MZ 会话、仅在直接命中失败后）；
- `isImmutableAsset()` 识别 `_` 结尾的加密扩展名（`.png_`/`.ogg_`/`.m4a_`），恢复 max-age + ETag；
- `mime()` 对 `_` 后缀返回基础类型（XHR 走 arraybuffer 时无关，但 `<img>` 直连与插件 fetch 需要）；
- 解密本身**不需要做**——MZ 在 JS 侧自己完成（`Utils.decryptArrayBuffer`），宿主只需把文件送到位。

### 2.2 【必须改·高】存档：MZ 删掉了 localStorage，换成 pako + localforage

| | MV | MZ |
| --- | --- | --- |
| 序列化 | `JsonEx.stringify` + `LZString.compressToBase64`（文本） | `objectToJson`（JsonEx.stringify）+ **`pako.deflate(json, {to:"string", level:1})`**（二进制） |
| local 模式文件 | `file1.rpgsave` / `global.rpgsave` / `config.rpgsave` | **`file1.rmmzsave`** / `global.rmmzsave` / `config.rmmzsave`（`filePath = dir + saveName + ".rmmzsave"`） |
| web 模式 | `localStorage`，键 `RPG File1` / `RPG Global` / `RPG Config` | **`localforage`（IndexedDB）**，键 `rmmzsave.<gameId>.<saveName>`；写测试键 `rmmzsave.test` |
| API 形态 | `saveToWebStorage(id, json)` **同步** | `saveObject(saveName, object)` / `loadObject(saveName)` **全异步 Promise** |
| `DataManager._globalId` / `isThisGameFile` | 有 | **全部删除**（改为 `globalInfo` 里的 `forageKey` 命名空间 + `removeInvalidGlobalInfo`） |
| 备份 | `backup/restoreBackup/cleanBackup` | **无**（核心不含备份） |

证据：
- MZ 源码（镜像 1.1.0）：`StorageManager.saveZip/loadZip/exists/remove` 按 `isLocalMode()` 二分叉；
  `isLocalMode = () => Utils.isNwjs()`；MZ 全域 `localStorage` **0 命中**。
- 本仓库已有的正确部分：
  [`RpgSaveFormat.kt`](../app/src/main/java/com/tyranor/next/core/game/save/RpgSaveFormat.kt)
  已区分 `.rpgsave` / `.rmmzsave`、MV 有 `.bak` 而 MZ 无、
  **MV 走 sha256 哈希名而 MZ 保持原名**（`:285-296`、`:377-392`）；
  [`RpgSaveKeyMapping.kt`](../engine/src/main/java/com/core/rpgmaker/RpgSaveKeyMapping.kt) 两边共用同一实现。
- 本仓库已有的**不足**：
  [`RpgMakerActivity.kt:1029-1038`](../engine/src/main/java/com/core/rpgmaker/RpgMakerActivity.kt)
  的 `RpgMakerSaveBridge` 写死 `RPG_MV_SAVE_EXTENSION = ".bin"`，MV/MZ 共用；
  [`__nwjs_polyfill_v2.js:3304-3807`](../engine/src/main/assets/rpgmaker/__nwjs_polyfill_v2.js)
  的 `localStorage` 落盘**对 MZ 是死代码**（MZ 不调用 `localStorage`）。

**影响**：
1. MZ 现有 hook（[`__hook_rmmz_managers.js`](../engine/src/main/assets/__hook_rmmz_managers.js)）
   把 `saveObject/loadObject/exists` 改走 `saveDataManager.Save/Load/Exists`，
   但 `saveDataManager` 是**按 MV 键形态设计**的（`RPG File1` 这类含空格键 → 哈希名）。
   MZ 传入的是 `"file1"`（纯 ASCII），恰好命中 `DIRECT_FILE_KEY`，落盘 `file1.bin`，
   与 MZ 标准名 `file1.rmmzsave` 不一致——**存档互通时会互不认识**（本仓库 `RpgSaveFormat` 已按
   `fileN.bin ↔ fileN.rmmzsave` 映射处理了这一点，属自洽设计，但不是 MZ 原生布局）。
2. pako 压缩是**二进制**，而 `RpgMakerSaveBridge` 读写的是 `String`（UTF-8）。
   现有 `StorageManager.saveObject` hook 走的是 `objectToJson` 后再 `Save(saveName, json)`，
   **跳过了 `jsonToZip`**——这意味着 MZ 存档会以**未压缩 JSON** 存成 `file1.bin`，
   MZ 自己的 `loadObject` 也会走同一条 hook 读回，所以自洽；但一旦用户把 `.rmmzsave`
   拷进导出，或存档互通读到**原生 MZ 存档**（zlib 压缩 + 16 字节头），就会解压失败。
3. `DataManager.isThisGameFile` 在 MZ **不存在**，MV 那套
   [`__rpg_v12.js:262-287`](../engine/src/main/assets/rpgmaker/__rpg_v12.js) 的标题兜底
   **无法照搬**（`isReady()` / hook 点都变了，见下条）。

### 2.3 【必须改·高】音频：MZ 只有 WebAudio，且 `.m4a` 被硬编码删除

- MZ 的 `AudioManager.audioFileExt()` **直接 `return ".ogg"`**（1.1.0），`Html5Audio` 类**整体删除**；
  MV 才有 `canPlayOgg() && !isMobileDevice() ? ".ogg" : ".m4a"` 的双格式逻辑。
- MZ 的 ogg 软解依赖 `js/libs/vorbisdecoder.js`（emscripten + 内嵌 base64 wasm），
  触发条件 `WebAudio._shouldUseDecoder = !Utils.canPlayOgg() && typeof VorbisDecoder === "function"`。
- **Android WebView 的 `canPlayType('audio/ogg; codecs="vorbis"')` 通常返回 `"maybe"`**，
  于是 MZ 走不到软解，直接 `decodeAudioData` 整段解码——这是低端机长 BGM 卡十几秒的根因。
- `decodeAudioData` 在并发解码时会 `EncodingError` 或永不 settle（GameNative 的
  `audio-decode-serial.js` 是第一手记录）。

**影响**：本仓库当前注入的 `.ogg ↔ .m4a` 双向回退
（[`RpgMakerLocalHttpServer.kt:565-577`](../engine/src/main/java/com/core/rpgmaker/RpgMakerLocalHttpServer.kt)）
**对 MZ 是空操作**（MZ 永不请求 `.m4a`），无害但无用；真正需要的是解码路径治理。

### 2.4 【必须改·中高】Effekseer：MZ 独有的 wasm 粒子引擎

- MZ `Graphics.initialize()` 的六步里多了 `_createEffekseerContext()`；
  失败则 **`this._app = null`**，整条渲染链降级。
- `Sprite_Animation.shouldWaitForPrevious()` 在 MZ **硬编码 `return Utils.isMobileDevice()`**，
  注释明写 "Effekseer is very heavy on some mobile devices"——移动端强制**同时只播一个**粒子动画。
- GameNative 的第一手结论：**Chromium WebView < 124 上，MZ 实例化 Effekseer WASM + WebAudio 输出
  会在 10–30s 后触发 renderer 里确定性的 audio CHECK，直接杀进程**；唯一有效的规避是
  stub `window.effekseer` 让 `createContext()` 返回 null（MZ 容忍），代价是粒子特效消失。

**影响**：这是 MZ 在 Android 上**最危险的稳定性问题**，且与现有 MV 方案零重叠。
需要一条按 WebView 主版本分流的开关（`< 124` 默认 stub）。

### 2.5 【需评估】渲染：PIXI 5.2.4 + `Graphics._createPixiApp`

- MZ 用 **PIXI 5.2.4**，MV 用 PIXI 4.5.4；MZ 用 `new PIXI.Application({view, autoStart:false})`
  并接管 ticker，MV 用 `PIXI.autoDetectRenderer`。
- 因此 MV v2 的"渲染器交还游戏 core"（`__rpg_v12.js:44-52`）在 MZ **没有对应 API**，
  也**不需要**——MZ 本来就由 core 自己建 app。
- `?android-legacy=1`（PIXI legacy / 强制 Canvas）在 MZ **不可用**：
  MZ 没有 `PIXI.CanvasRenderer` 路径，也没有 `_rendererType`。
  **设置页的「PIXI 兼容渲染」开关对 MZ 应是无效项**（当前 UI 文案已写"仅 TynMV 引擎 会话生效"，
  `strings.xml` 的 `engine_settings_legacy_renderer` 是对的）。

### 2.6 【需评估】启动时序：`index.html` 只有一个 script，游戏在 wasm 就绪后才起

- MV：`index.html` 里静态写 9 个 `<script>`，`main.js` 末尾 `PluginManager.setup` + `onload`。
- MZ：`index.html` **只有 1 个** `<script src="js/main.js">`，`main.js` 动态注入其余脚本
  （`async=false; defer=true`），全部 onload 后 `PluginManager.setup`，
  再 `effekseer.initRuntime("js/libs/effekseer.wasm", …)`，**只有 wasm 就绪才** `SceneManager.run`。

**影响**：
1. `scriptAppends`（往 `js/rmmz_core.js` 末尾追加）**仍然有效**（脚本仍是普通 `<script>` 注入）。
2. 但宿主**不能假设 `onPageFinished` 就等于游戏就绪**。现有 `EngineStartupOverlay` 的
   "首帧判定"在 MZ 下应改为等 `SceneManager._scene` 出现或 `Scene_Boot` 完成，
   否则会出现"加载条已经消失，游戏还在黑屏解 wasm"。
3. `wasm` 的 MIME 服务器已正确返回 `application/wasm`
   （[`RpgMakerLocalHttpServer.kt:947`](../engine/src/main/java/com/core/rpgmaker/RpgMakerLocalHttpServer.kt)），无需改。

### 2.7 【已兼容】`Utils.isNwjs()` 与 `isLocalMode()`

两版**逐字符相同**：`typeof require === 'function' && typeof process === 'object'`。
`StorageManager.isLocalMode()` 两版都是 `Utils.isNwjs()`。兼容层把两者钉成 `false`
（[`__nwjs_polyfill.js:11-28`](../engine/src/main/assets/rpgmaker/__nwjs_polyfill.js)）在 MZ 上**语义不变**，
这套策略可以直接沿用。

⚠️ 但要注意这是一条**互斥路线**：GameNative 反其道而行，故意让 `process` 是**函数**
（`typeof process === "function"`），从而让 `isNwjs()` 为 false 的同时 `process.platform` 仍可读，
避免 YEP/VisuMZ 的 `initNwjs` 去 `require('nw.gui')` 崩掉。本仓库选的是"伪 NW.js + 真 fs"，
**移植 MZ 时必须保持同一选择**，不能一半一半。

### 2.8 【已兼容】输入注入

`__tyranor_mouse.js` 已经在为 MZ 补 `pageX/pageY`
（[`__tyranor_mouse.js:22-29`](../engine/src/main/assets/__tyranor_mouse.js)，注释明写"MZ 的 TouchInput 读 pageX/pageY"），
且 `rclick` → `contextmenu`、`click` 用 50ms 按住再 up（否则 `isRepeated()` 对话不推进）都已处理。
**这是全仓唯一一处真实的 MZ 适配代码**，且是无条件执行，MV/MZ 都受益。

MZ 另外新增了 `Input._virtualButton` / `Input.virtualClick(name)` 通路，理论上更"正规"，
但现有合成 `keydown/keyup` 在 MZ 下仍工作（`Input._onKeyDown` 未被移除），**暂无必要改**。

### 2.9 【已兼容】存档格式层（app 侧）

`RpgSaveFormat` 已经把 MV/MZ 的差异全部吃掉了（扩展名、备份、哈希名、槽位键），
`RpgSaveFormatConverter` 是纯改名不重编码。**这块不需要改**。

唯一要确认的是：MZ 在 pako 压缩后是**二进制**，而 `.bin` 文件名 + String 读写会破坏二进制。
当前 hook 通过"跳过 `jsonToZip`"避开了这个问题（见 §2.2），
但如果将来要**双向互通原生 MZ 存档**（`.rmmzsave` 是 zlib 压缩的二进制），
`RpgMakerStorage.read/write` 必须改成字节流。这是移植时必须明确的取舍。

---

## 3. MZ 侧现有 hook 的真空区（为什么"MZ 只有 0.1"）

| 能力 | MV v2 实现 | MZ 现状 | MZ 是否可等价实现 |
| --- | --- | --- | --- |
| 渲染器交还 core | `__rpg_v12.js:44-52` | 不需要 | — |
| PIXI legacy 开关 | `?android-legacy=1` + `_rendererType` | 无对应 API | ❌ 不可（MZ 无 CanvasRenderer 路径） |
| PC 存档兜底（`global.bin/fileN.bin`） | `pcLocalSaveKey` + 三级回退 | 无 | ✅ 可（键形态换成 `fileN` 即可） |
| 存档备份 | `backup/restoreBackup` | MZ 核心无备份 | ❌ 不可（只能由宿主自建，属新增能力） |
| `isThisGameFile` 标题兜底 | `__rpg_v12.js:262-287` | MZ 无此函数 | ❌ 不可（改为 `globalInfo.forageKey` 命名空间） |
| `textureGC` 策略 | 交还 core | MZ hook 本就不含该补丁 | — |
| 存档写盘 | `saveToWebStorage`（同步） | `saveObject`（Promise） | ✅ 可（但需异步桥） |
| 音频双格式回退 | `.ogg/.m4a` 互转 | MZ 只认 `.ogg` | ⚠️ 改为解码治理 |
| 加密素材 | `.rpgmvp` 后缀替换 | `_` 后缀 | ✅ 可（改映射规则） |

**结论**：MZ 的"真空"里，**3 项是 API 不存在（不可移植，属预期）**，
**3 项是可移植但现在没做**，**2 项需要换形态**。

---

## 4. 参考实现能给什么

### 4.1 JoiPlay（`D:\code\joiplay`，逆向核对）

**最重要的发现：JoiPlay 对 MZ 的工程规模远小于对 MV，而且注入管线完全不分叉。**

- MZ 与 MV **共用同一个 `HTMLActivity`** 与同一套 `NWJSApi`（48 个 `@JavascriptInterface`），
  注入顺序（`node_wrapper/node.js` → `globals.js` 或 `nwjsapi.js` → `globals.js` → `webgl.js`）
  **没有任何引擎判断**，唯一开关是 `allowExternalModules`。
- 差异只有四处：
  1. **根目录探测**：MZ 找 `/js/rmmz_core.js`（存在 → `game.getFolder()`，否则退 `folder + "/www"`），
     MV 找 `folder + "/www/index.html"` 且多一层 `getParentFile()` 兜底。
  2. **`plugins.js` 追加的插件**：MZ 追加 `JoiMZ` + `Cheat_Menu` + `CopyText` + **`PluginOverridesMZ`**；
     MV 追加 `JoiMV` + 同前两者 + `PluginOverrides`（**带内联正文**）。
     插入位置规则相同：找到 `FOSSIL` 就插到它后面，否则插最前；`PluginOverridesMZ` **追加到末尾**。
  3. **`JoiMZ.js`**（200 行）：只做三件事——纹理降采样（`Bitmap._createBaseTexture` + `Sprite/TilingSprite/
     Sprite_StateIcon/Sprite_Balloon/Window_Base.drawIcon` 的反向缩放）、`Graphics._setupPixi`（PIXI 参数）、
     重写 `Graphics._createPixiApp`（`autoStart:false` + 接管 ticker），外加 `Weather` 限流。
     **MV 那份（`JoiMV.js`）里的一半内容 MZ 完全没有**：`Utils.isNwjs/isMobileDevice`、
     `StorageManager.isLocalMode`、`DataManager.loadGlobalInfo`、`Decrypter.checkImgIgnore`、
     `Bitmap.rotateHue/blur`、PIXI4 整段、帧率自适应——因为这些在 MZ 要么不存在，要么不需要。
  4. **`PluginOverridesMZ.js`**（15 行）：只把 `Tilemap.Renderer._createInternalTextures` 的
     RT 固定成 **2048×2048** + `NEAREST`，规避 MZ tilemap 分块显存问题。
- **反直觉的一点**：`webgl.js` 的 GLSL1→GLSL3 转译链路**只对 MV 生效**——
  `NWJSApi.isTranspileEnabled()` 里写死 `return game.getType().equals("rpgmmv")`。
  所以"JoiPlay 给 MZ 做了 WebGL2 垫片"这个猜测是**错的**，MZ 走原生 WebGL 路径。
- **JoiPlay 对 MZ 的兜底比 MV 更薄**：MZ 的 patcher 分组只有 **1 条**正则补丁
  （`Sprite_Frame.prototype.refresh` 按 `_scale` 算帧），MV 组有 8 条。
- **JoiPlay 完全没有处理 Effekseer**（jadx/smali/assets 全域零命中），
  也就是说它在 MZ 粒子这一块**没有可借鉴的实现**。
- 对 MZ 的音频：缺文件时统一回 `assets/rpgm/null.ogg` 静音占位；`.mp4` 请求重写成 `.webm`；
  后台 `onPause` 时 `WebAudio._context.suspend()`（两引擎共用）。
- **UA 伪装**：MZ 单独设成 `Chrome/80.0.3987.149` 的桌面 UA（MV 走另一条判断）。

**可借鉴**：① 根目录探测的 MZ 分支（本仓库已有等价实现，见 `RpgMakerActivity.kt:531-545`）；
② `PluginOverridesMZ` 的 2048 RT 补丁（窄、可验证）；③ `JoiMZ` 的纹理降采样思路
（本仓库 v2 兼容层无对应代码，可作为"大图上传失败"的备选修复）。

**不可借鉴**：注入管线（本仓库的两段式 `</head>`/`</body>` 注入更可控）、
Effekseer（它没做）、存档（它是 patch 掉 `StorageManager` 而非桥接）。

### 4.2 GameNative `HTML5Runtime`（GPL-3.0，只读不抄）

**这是 MZ 移动端最有价值的参考**，因为它做的正是同一件事（NW.js/Electron 垫片 + 解析期注入 +
本地 HTTP + 存档映射），而且踩过的坑都写在注释里：

- **解析期注入**：`IndexHtmlRewriter` 把 shim 插到**第一个游戏 `<script>` 之前**，找不到就 fail loud。
  三条硬约束直接适用：`process` 必须是**函数**（否则 `isNwjs()` 为 true）、**不要预置 `module.exports`**
  （否则 UMD 走 CommonJS）、顺序必须是"DPR/全局 → shims → preload → game"。
  > 本仓库的 `__nwjs_polyfill.js` 已经在第 3 条上踩过坑并改对了（CHANGELOG 里那条 UMD 垫片修复）。
- **本地方案**：loopback HTTP 而非 `file://`，动机是 Chromium 113+ 的 PlzDedicatedWorker 不再把
  worker 子资源走 `shouldInterceptRequest`，且媒体需要可 seek 的 `Range`/206——
  **与本仓库 `RpgMakerLocalHttpServer` 的取舍完全一致**。
  更优的一点：它用 `http://<safeId>.localhost:<固定端口>` 给**每个容器独立存储源**，端口写进 sentinel、绝不漂移。
- **MZ 专属硬补丁**（都值得抄）：
  1. `WebAudio._shouldUseDecoder` 钉 true → 强制流式 `VorbisDecoder`（实测某机 23s → 5s 出声）；
  2. `MAX_TEXTURE_SIZE` 4096 时的降采样 + `resolution = dw/w` 保住逻辑尺寸；
  3. tilemap 片元着色器强制 `highp`（Mali fp16 精度不足 → 网格接缝）；
  4. `PIXI.filters.BlurFilter` 的 `blur === 0` 直接换恒等 filter。
- **Effekseer 按 Chromium 版本分流**：< 124 stub（`createContext()` 返回 null），≥ 124 放行。
- **存档**：它的 `RmmvSaveMapper` 只覆盖 **MV**（`.rpgsave` ↔ LevelDB），
  **MZ 的 `.rmmzsave` + localforage/IndexedDB 它没做**——这正是本仓库要自己补的空白。
- **音频**：`audio-decode-serial.js` 两条串行 lane（<256KB 一条、大文件一条）+ 一次重试。
- **许可提醒**：GPL-3.0。**只能读架构，不能拷代码**（本仓库是 Apache/MIT 风格的开源项目）。

### 4.3 ludens / rpgmaker-to-android 等（价值有限）

- [ludens](https://github.com/yoimerdr/ludens)（Apache-2.0）：**要求游戏用"web 导出"模式**，
  由 WebView 直接读 Compose 资源，**没有 NW.js 垫片**、无 asar、无模糊名、无加密、无存档桥。
  它解决的是"给开发者打包自己的游戏"，不是"开箱即跑别人的 MZ 游戏"。
  唯一可借鉴：`offscreenPreRaster = true`（注释就写"avoid some rendering issues with WebView for MZ engine"）、
  `SDK < M` 时页面加载后延迟 300ms `invalidate()` 修黑屏、`onTrimMemory` 时清 WebView 缓存。
- [rpgmaker-to-android](https://github.com/cellicom/rpgmaker-to-android)（MIT）：
  105 行单文件。值得记一笔的是它用 `StorageManager.isLocalMode = function(){ return false; }`
  把 MZ 强行推到 web 模式——这**与本仓库路线相反**（我们选伪 NW.js + 真 fs），
  但对"只想让 MZ 跑起来、不要存档互通"的场景是最省事的解法。
- **官方 corescript 仓库不存在**：`rpgmaker/corescript` 是 404。
  MV 的社区核心在 [rpgtkoolmv/corescript](https://github.com/rpgtkoolmv/corescript)（MIT）；
  MZ 的 `rmmz_*.js` **无官方开源仓库**，可读镜像 `stak/rmmz-corescript`（v1.1.0，**无 LICENSE**，
  只宜当阅读材料，不可再分发）。

### 4.4 MZ 移动端硬约束汇总（带来源）

| 约束 | 数值 | 来源 |
| --- | --- | --- |
| WebAssembly 最低 Chromium | 57（`vorbisdecoder.js` / `effekseer.wasm`） | [火山引擎技术文](https://www.volcengine.com/article/3319119) |
| Effekseer + WebAudio 杀 renderer | < 124 | GameNative `EffekseerWasmGate.kt` |
| WebGL2 不可用 | Android 6 及以下 | [TapTap 官方说明](https://www.taptap.cn/secoop/moment_so/309650378436119565) |
| MZ 在 Chromium 56 及以下报 `WebAssembly is not defined` | — | [rpg.blue 实测帖](https://rpg.blue/forum.php?mod=viewthread&action=printable&tid=488287) |
| Effekseer 低端机性能（社区优化版） | `1.61Beta2`（社区经验，未官方声明） | [rpg.blue 讨论](https://rpg.blue/forum.php?mod=viewthread&tid=486380) |

**实践门槛建议**：WebView 主版本 **< 100 直接拒绝启动并提示**（GameNative 的 `MIN_MAJOR`），
**< 124 默认 stub Effekseer**，可在设置里放开。

---

## 5. 开源许可与合规

- `stak/rmmz-corescript` **无 LICENSE** → 只读不落地，不得打包进 APK。
- GameNative **GPL-3.0** → 只读架构，不得拷代码。
- `rpgtkoolmv/corescript`（MV）**MIT** → 可参考，但本仓库已自带 1.6.1 覆盖包，不需要。
- ludens / rpgmaker-to-android **Apache-2.0 / MIT** → 可参考可抄，但价值有限。
- 本仓库现有做法（`rpgmaker/rpgmv-v1` 覆盖包入库）与本条无关，属 MV 资产。

---

## 6. 建议的落地顺序

按「不做的后果」排序，而不是按工作量：

### 阶段 1（不做必崩）
1. **MZ 加密素材 `_` 后缀治理**（详见 §2.1 的修正）：
   - `isImmutableAsset()` 识别 `.png_`/`.ogg_`/`.m4a_`（否则加密素材每次全量重传）；
   - `mime()` 对 `_` 后缀返回基础类型；
   - `resolveRequestedFile()` 增加**反向**回退 `a.png_ → a.png`（混合加密工程）。
   **注意：正向回退不需要做**——MZ 的 `_` 追加在完整 URL 后，与磁盘文件名一致，直接命中。
2. **MZ 存档桥二进制化**：明确"是否要互通原生 `.rmmzsave`"。
   - 不互通（当前状态）：保持"跳过 pako、以 JSON 存 `.bin`"，但要在 SPEC 里写清这是**非标准布局**。
   - 要互通：`RpgMakerStorage.read/write` 改字节流 + 用 `java.util.zip.Deflater/Inflater` 复刻 pako，
     并处理"先 rename 到 `_` 备份、失败回滚"的 MZ 中间态。

### 阶段 2（低端机稳定性）
3. **Effekseer 版本闸门**：新增设置项（auto/on/off），`WebView` 主版本 < 124 时 stub `window.effekseer`。
4. **`decodeAudioData` 串行化 + `_shouldUseDecoder` 钉 true**（长 BGM 从"卡十几秒"到"5s 出声"）。

### 阶段 3（对齐 MV 的能力）
5. **MZ 版 hook 扩写**：把 `__rmmz__.js`（现 1 行）扩成与 `__rpg_v12.js` 对位的版本——
   但**只保留 MZ 真实存在的 API**（`StorageManager.saveObject` 异步、`globalInfo` 无 `isThisGameFile`、
   无 `Decrypter`），不要把 MV 的兜底硬搬。
6. **PC 存档兜底**（`fileN.bin` ↔ `fileN.rmmzsave`）：`RpgSaveFormat` 已具备，只需 hook 侧接上。

### 阶段 4（性能与体验）
7. 纹理降采样（`MAX_TEXTURE_SIZE` 时降 + `resolution` 补偿）；
8. tilemap `highp`、`BlurFilter blur===0` 恒等替换；
9. `PluginOverridesMZ` 的 2048 RT 补丁（可选，按实测决定）。

### 阶段 5（工程化）
10. **注入配置数据化**：把 `RpgMakerActivity.buildInjectionBundle()` 里的硬编码分支
    抽成 `rpgpack` 描述（参考 GameNative 的 `packs/rmmv.json`），
    让"MZ v3 / MV v3"变成加一份 JSON 而不是改 Kotlin。
11. **启动就绪信号**：MZ 改为等 `SceneManager._scene` / `Scene_Boot` 完成，
    而不是 `onPageFinished`（见 §2.6）。

### 验收（必须真机，不能只跑单测）
- 加密 MZ 样本：图片、BGM、SE 三类各一，确认从 404 变为正常。
- 未加密 MZ 样本：确认没有因 `_` 回退带来额外 404 噪声。
- 存档：新建/读档/存档互通（导出 `.rmmzsave` 后能读回）三条链路。
- 低端机（WebView < 124）：有 Effekseer 的战斗场景不闪退。
- 长 BGM（> 5 分钟）：切换场景不卡顿。
- **现有 MV v0/v1/v2 三条链路全部回归**——这是移植方案最容易踩的坑。

---

## 7. 风险与已知不确定

1. **MZ corescript 只能靠社区镜像取证**（官方仓库不存在，镜像无 LICENSE）。§2 的所有结论
   按镜像 **v1.1.0** 标注；MZ 1.2+ 若把 `.ogg` 换成 `.m4a` 或加回 `Html5Audio`，需重核。
2. **`process` 是函数还是对象**这条互斥路线一旦混用，会出现"YEP 插件初始化崩"或
   "存档全丢"两类极端症状。移植 MZ 时必须与 MV 保持同一选择。
3. **GPL-3.0 污染**：GameNative 的补丁思路可以自己重写，但**不能拷代码**。
4. **Effekseer 的版本阈值来自单方记录**（GameNative `AFFECTED_BELOW_MAJOR = 124`），
   未在本仓库设备上复现。
5. **`RpgMakerSaveBridge` 的 `.bin` 扩展名是 MV 遗留**，MZ 复用它是"自洽但非标准"的设计，
   一旦用户手动拷入原生 `.rmmzsave` 会读不出来——需要在 UI 或文档里说明。

---

## 8. 一句话回答

**能移植，且宿主骨架已经复用了 90%：`TynMZ-0.1` 本质就是"MZ × v2"。**
真正要补的是 3 块 MZ 专有件——**pako 存档、WebAudio 独占、Effekseer wasm 闸门**；
外加一块被误判过的**加密素材缓存治理**（`_` 后缀能直接命中，问题只是缓存降级与混合加密工程的 404）。
MV v2 那套 PC 存档兜底 / 渲染器交还 / `isThisGameFile` 兜底在 MZ 上**没有对应 API**，
属"不可移植"而非"未移植"。JoiPlay 的 MZ 实现比 MV 更薄（补丁 8 条 → 1 条，且**完全没做 Effekseer**），
只能借鉴根目录探测与 tilemap RT 补丁；真正值得读架构的是 GameNative 的 `HTML5Runtime`
（GPL-3.0，只读不抄）。