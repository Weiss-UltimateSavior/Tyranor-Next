# Tyranor-Next 与 JoiPlay 的 MZ 存档对比研究

> 研究日期：2026-11-28。分支 `docs/eval-rpgmaker-mz-migration`。
> 取证：本仓库代码只读核对 + `D:\code\joiplay\joi-dex-逆向成果` 逐文件核对。
> 未取证部分标注「未证实」。

---

## 0. 结论先说

**不一样，而且不是"文件名不同"这种小差别——从存档模式到底层格式三层全部不同。**

| 层次 | Tyranor-Next | JoiPlay |
| --- | --- | --- |
| **存档模式** | **web 模式**（`isLocalMode()` 强制 `false`） | **local 模式**（`isNwjs()` 为 `true`，`isLocalMode()` 跟随） |
| **落盘机制** | 自建 JS 桥 `saveDataManager.Save/Load` → Kotlin `@JavascriptInterface` | 原生 `fs` 走 `NWJSApi` → 真文件系统 |
| **文件格式** | **裸 JSON**（跳过 `jsonToZip`） | **pako deflate 二进制流**（原生 `jsonToZip`） |
| **文件名** | `file1.bin` / `global.bin`（`.bin`） | `file1.rmmzsave` / `global.rmmzsave`（`.rmmzsave`） |
| **与 PC 版互通** | ❌ **当前不能**（内容是明文 JSON） | ✅ **能**（格式与 PC 版逐字节同构） |

**一句话**：Tyranor 的 MZ 存档是"自己一套、能读能存、但 PC 版读不出"；JoiPlay 的 MZ 存档是"PC 版原样搬到手机上"。

这也直接印证了 [移植实施方案](RPGMaker-MZ-移植实施方案.md) 阶段 B 的结论——**现状是 B-1 自洽格式，必须改成 B-2 才能互通**。

---

## 1. 存档模式：两条相反的路线

### Tyranor：强制 web 模式

[`__nwjs_polyfill.js:11-24`](../engine/src/main/assets/rpgmaker/__nwjs_polyfill.js#L11-L24)：

```js
if (window.Utils.isNwjs() !== false) {
    if (src.indexOf("return false") === -1) window.Utils.isNwjs = function () { return false; };
}
if (window.StorageManager.isLocalMode() !== false) {
    window.StorageManager.isLocalMode = function () { return false; };
}
```

两层都钉死：`isNwjs` → `false`，`isLocalMode` → `false`。于是 MZ 走 `StorageManager` 的 **web 分支**
（`saveZip` → `saveToWebStorage` → `localforage`）。

### JoiPlay：真 local 模式

[`JoiMV.js:83-85`](D:\code\joiplay\joi-dex-逆向成果\assets_html\js\plugins\JoiMV.js)：

```js
Utils.isNwjs = function() {
    return true;
};
```

而 [`globals.js:1`](D:\code\joiplay\joi-dex-逆向成果\assets_html\node_wrapper\globals.js) 里
`var process = require('process')` —— 是**真实 Node process 对象**，`window.require` 也是真函数
（[`node.js:304`](D:\code\joiplay\joi-dex-逆向成果\assets_html\node_wrapper\node.js) 自己实现了 MiniRequire）。
所以 JoiPlay 的 `isNwjs()` **天然就是 true**，根本不需要改写；`StorageManager.isLocalMode()` 直接继承
`Utils.isNwjs()`，也是 true。

→ **JoiPlay 的 MZ 永远走 local 分支：`saveToLocalFile` / `loadFromLocalFile`，用真 `fs` 写到磁盘。**

> **重要细节**：JoiPlay 的 `JoiMZ.js`（200 行）**完全没有碰存档**。
> `StorageManager.isLocalMode = () => true` 那条改动在 `JoiMV.js:70-73` 里，是 **MV 专用**——
> 因为 MV 的 `isNwjs()` 原本不可靠，需要额外钉一次。MZ 靠 `isNwjs` 就够了。
> 这反过来说明：**JoiPlay 对 MZ 存档是"零适配"**，让它按原生 NW.js 行为跑。

---

## 2. 落盘机制与文件格式

### Tyranor：裸 JSON `.bin`

[`__hook_rmmz_managers.js`](../engine/src/main/assets/__hook_rmmz_managers.js) 全文（22 行）：

```js
StorageManager.saveObject = function(saveName, object) {
    return this.objectToJson(object)
        .then(json => window.saveDataManager.Save(saveName, json));   // ← 直接存 json
};
StorageManager.loadObject = function(saveName) {
    ... .then(json => StorageManager.jsonToObject(json));             // ← 直接读 json
};
```

**关键：`objectToJson` 之后没有 `jsonToZip`。** 链是：

```
objectToJson  →  JSON 字符串（明文）  →  saveDataManager.Save  →  Kotlin 按 UTF-8 写 .bin
```

Kotlin 侧 [`RpgMakerStorage.kt:47`](../engine/src/main/java/com/core/rpgmaker/RpgMakerStorage.kt#L47)：
`text.toByteArray(StandardCharsets.UTF_8)` —— 明文 UTF-8。

### JoiPlay / PC 版：deflate 二进制 `.rmmzsave`

MZ 原生 `StorageManager.saveObject` 是：

```
objectToJson → jsonToZip (pako.deflate) → saveZip → saveToLocalFile → fs.writeFileSync
```

`jsonToZip` 产出的是 **zlib deflate 流**（首字节 `0x78`），不是文本。
落盘文件名由 `saveToLocalFile` 拼成 `<saveName>.rmmzsave`。

> **注**：本机没有 MZ corescript 源码（全盘搜索 `rmmz_managers.js` / `rmmz_core.js` 零命中），
> JoiPlay 逆向产物里也没有内嵌 rmmz 核心脚本。所以「`saveToLocalFile` 用 `encoding:"binary"`
> 逐字节写」这一点属于**依据 MZ 公开行为的推断，未在本机取证**——与
> [实施方案 B.0′](RPGMaker-MZ-移植实施方案.md) 列为"开工第一步必须核实"的是同一件事。

---

## 3. 文件名层面

| | Tyranor | JoiPlay / PC |
| --- | --- | --- |
| 存档位 | `file1.bin` | `file1.rmmzsave` |
| 全局 | `global.bin` | `global.rmmzsave` |
| 设置 | `config.bin` | `config.rmmzsave` |
| 备份 | 无（MZ 核心不含备份） | 无 |

Tyranor 的 [`RpgSaveKeyMapping.kt:23`](../engine/src/main/java/com/core/rpgmaker/RpgSaveKeyMapping.kt#L23)
里 `DIRECT_FILE_KEY = Regex("[A-Za-z0-9._-]{1,128}")` —— MZ 的 `file1` 是纯 ASCII，命中直出，
落盘 `file1.bin`。MV 的 `RPG File1` 含空格，走 sha256 哈希名。

**改名映射已经做好了**：[`RpgSaveFormat.kt:30`](../app/src/main/java/com/tyranor/next/core/game/save/RpgSaveFormat.kt#L30)
注释里明确写了 `global.bin / config.bin / fileN.bin  ↔  global.rmmzsave / config.rmmzsave / fileN.rmmzsave`。

---

## 4. ⚠️ 发现一处文档错误

[`RpgSaveFormat.kt:11`](../app/src/main/java/com/tyranor/next/core/game/save/RpgSaveFormat.kt#L11) 写着：

> 两种格式内容**字节级一致**，仅文件名不同；转化是纯改名（不重编码）。

**这对 MV 成立，对 MZ 不成立。**

- MV：`StorageManager.save` → `LZString.compressToBase64(json)` → 文本。PC 版同格式。✅ 字节级一致。
- MZ：Tyranor 存**明文 JSON**，PC 版存 **deflate 二进制流**。❌ **内容完全不同**。

所以「转化是纯改名」这个说法用在 MZ 上是**错的**——把 `file1.bin` 改名成 `file1.rmmzsave` 后，
PC 版会尝试 inflate 一份明文 JSON，直接失败。

**这正好是 [实施方案阶段 B-2](RPGMaker-MZ-移植实施方案.md) 要修的东西**：
B-2 落地后（走 deflate），这句注释才对 MZ 也成立。在此之前，它是文档层面的一个坑——
如果按它去实现"导出标准格式"，MZ 存档导出会是坏的。

> 建议：在 B-2 落地前，把该注释限定为「MV 成立；MZ 需 B-2 后成立」，或直接等 B-2 一起改。

---

## 5. 与现有移植方案的关系

这次对比**验证了实施方案阶段 B 的核心判断**：

| 实施方案的说法 | 本次核实 |
| --- | --- |
| B-1 现状 = 自洽格式，与 `.rmmzsave` 不兼容 | ✅ 确认：明文 JSON vs deflate 二进制 |
| B-2 需要 `writeBytes` 字节流 | ✅ 确认：`RpgMakerStorage` 现在 UTF-8 读写，会破坏二进制 |
| 需要旧 `.bin` 嗅探直通 | ✅ 确认：现有存档全是裸 JSON，切格式后必须兼容读 |
| JoiPlay 是"patch 掉 StorageManager"而非桥接 | ✅ 确认：它**根本不动** MZ 存档，靠真 `fs` |

同时补充一条原方案没点明的事实：

**JoiPlay 能互通是"免费的"——因为它让 MZ 以 local 模式原生跑；Tyranor 要互通是"要补的"——因为我们选了 web 模式 + 自建桥。**
这是两条路线的必然结果，不是实现优劣问题。Tyranor 选 web 模式是为了给 `localStorage` 落盘、`fs` 桥等
v2 能力留出可控点（见[评估稿 §2.7](RPGMaker-MZ-移植评估.md) 的"互斥路线"讨论），代价就是 MZ 存档要自己做格式对齐。

---

## 6. 一句话回答

**不一样。** Tyranor 是 web 模式 + 裸 JSON + `.bin`；JoiPlay 是 local 模式 + pako deflate + `.rmmzsave`。
前者能自洽运行但不能与 PC 互通，后者就是 PC 格式原样。要让 Tyranor 兼容 JoiPlay / PC，
必须落地[实施方案阶段 B-2](RPGMaker-MZ-移植实施方案.md)（deflate + 字节流 + 旧格式嗅探）。