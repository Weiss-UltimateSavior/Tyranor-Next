# KRKR 游戏语言环境设置 · 技术设计

更新时间：2026-10-06
状态：设计稿（阶段 0 取证已完成，尚未实现）
适用范围：TyranorNext `tyranornext` 分支，PR #95

---

## 1. 背景

需求：在模拟器中提供「游戏底层语言环境」设置，支持全局默认 + 单游戏独立覆盖，取值包括日语、英语、简体中文、繁体中文。目标是让日文原版（Shift-JIS）与部分依赖日语环境的汉化版（GBK）不再出现乱码、闪退或脚本报错，同时避免用户修改手机系统语言。

---

## 2. 阶段 0 取证结论

### 2.1 命令行参数

| 内核 | `-render` / `-savedir` | `-enc` / `-encoding` |
| --- | --- | --- |
| krkrsdl3（`libkrkrsdl3.so`） | 独立参数，内核确实解析 | 全部是 `multibyte-encoding`、`pcm-encoding` 的子串，不是参数 |
| kirikiroid2（`libgame.so` / `libgame134.so` / `libgame126.so`） | 仅 `-render` | 同样是 Oniguruma 错误信息 `multibyte-encoding` 的子串 |

结论：**不存在编码类命令行参数**，「路线 A：追加一个 `-enc=` 参数」被排除。

### 2.2 动态符号表

`libkrkrsdl3.so` 的 `.dynsym`（25502 条）中：

| 符号 | 状态 | 地址 | 大小 |
| --- | --- | --- | --- |
| `_Z25TVPSetDefaultReadEncodingRKN3TJS10tTJSStringE` | DEFINED | `0x9973a4` | 840 |
| `_Z25TVPGetDefaultReadEncodingv` | DEFINED | `0x997898` | 32 |
| `_Z27TVPResetDefaultReadEncodingv` | DEFINED | `0x997778` | 48 |
| `_Z20krkr_setTextEncodingPN3TJS11tTJSVariantEiPS1_PNS_13iTJSDispatch2E` | DEFINED | `0xd7af9c` | 216 |
| `ncbFunctionTag_Storages_setTextEncoding` 注册 / 注销 | DEFINED | — | — |

`libgame.so`、`libgame134.so`、`libgame126.so` 中上述符号**全部不存在**（文件与 `.dynstr` 均无）。

补充：`System/Initialize.tjs` 字符串只出现在 krkrsdl3，且不在 `.dynstr`，说明它是引擎内部启动脚本路径，不是导出符号。

### 2.3 结论

- kirikiroid2 三个内核：**没有任何运行时编码入口**（无参数、无符号）。
- krkrsdl3：**有**可被 `dlsym` 调用的导出函数，是唯一有正面证据的通道。
- 因此本设计按内核分流：krkrsdl3 先落地，kirikiroid2 另案。

---

## 3. 能力矩阵与第一版范围

| 内核 | 编码参数 | 编码符号 | 第一版 |
| --- | --- | --- | --- |
| krkrsdl3 | 无 | 有（导出） | **支持** |
| kirikiroid2 1.3.9 / 1.3.4 / 1.2.6 | 无 | 无 | **不支持**，UI 隐藏或标注 |

第一版目标：krkrsdl3 内核下，用户选择语言后引擎按对应编码读取脚本；kirikiroid2 不展示该选项，避免误导。

非目标：

- 不改系统语言、不改进程 Locale（会破坏 KR2 壳层 zh-CN 资源）。
- 不写任何引擎偏好 XML（`default_encoding` 已证伪）。
- 不在本 PR 内实现 kirikiroid2 的 patch overlay / native hook。

---

## 4. 总体架构

设置层保持不变（已实现）：

```
全局 EngineSettingsStore.KEY_KR_LANGUAGE
单游戏 PerGameSettingsStore.F_KR_LANGUAGE
        ↓ EngineSettingsResolver（覆盖 ?: 全局 + 白名单）
ResolvedEngineSettings.krLanguage
        ↓ EngineLauncher.buildKrkrsdl3Args / Intent extra
LaunchContract.KR_LANGUAGE
        ↓
Krkrsdl3Activity.onCreate（super 之后）
        ↓
Krkrsdl3TextEncodingBridge.setDefaultReadEncoding(encoding)
        ↓ JNI
libkrkr_bridge.so → dlopen(libkrkrsdl3.so) + dlsym(TVPSetDefaultReadEncoding)
        ↓
TVP 默认读取编码变更 → 后续脚本按新编码解码
```

新增组件只有一个：**引擎侧编码桥**（Java + native 各一小段）。其余全部复用现有骨架。

---

## 5. 详细设计

### 5.1 语言 → 编码映射

| 设置值 | 编码 | 说明 |
| --- | --- | --- |
| `auto` | 不调用 | 保持引擎默认，对存量用户零副作用 |
| `jp` | `SJIS` | 日文原版 |
| `zh` | `GBK` | 简体汉化 |
| `en` | `UTF-8` | 英文 / 通用 |
| `zhtw` | **第一版移除** | 仓库内 TVP 只实现 `sjis_mbtowc` / `gbk_mbtowc`，无 big5，调用有触发 `TVPUnsupportedEncoding` 的风险 |

编码字符串的准确取值（`SJIS` / `Shift_JIS` / `sjis`）需在 POC 中以 `TVPGetDefaultReadEncoding` 读回确认，映射集中在一个函数内，便于修正。

### 5.2 JNI 接口

Java 侧（放在 `engine/src/main/java/bridge/` 下，与 `NativeBridge` 并列）：

```kotlin
object KrkrTextEncodingBridge {
    /** 返回是否成功下发；失败不抛异常，由调用方记录日志。 */
    @JvmStatic external fun setDefaultReadEncoding(so: String?, encoding: String?): Boolean
}
```

native 侧（`libkrkr_bridge.so`）：

1. `dlopen("libkrkrsdl3.so", RTLD_NOW)`，失败返回 `false`；
2. `dlsym` 取 `_Z25TVPSetDefaultReadEncodingRKN3TJS10tTJSStringE`；
3. 构造 `tTJSString`：
   - 首选 `dlsym` 取 `tTJSString` 的构造函数（如 `_ZN3TJS10tTJSStringC1EPKc`），在栈上分配足够大的缓冲区后构造；
   - 若构造函数不可导出，退化为 hook 方案（见 5.6）；
4. 调用后 `dlsym` 取 `_Z25TVPGetDefaultReadEncodingv` 读回，校验是否生效；
5. 返回 `true/false`，不修改任何持久化文件。

### 5.3 调用时机与线程

- 位置：`Krkrsdl3Activity.onCreate` 中 `super.onCreate(savedInstanceState)` 之后、方向锁释放调度之前。
- 原因：`super.onCreate` 链路会加载 native 库并初始化 TJS 运行时，编码 API 依赖 TJS 类型系统；过早调用会崩。
- 线程：UI 线程即可，调用耗时为微秒级。
- 频率：每次进程启动调用一次；编码是进程级状态。

### 5.4 作用域语义

| 场景 | 行为 |
| --- | --- |
| 全局设置 | 写入全局 prefs，启动时解析 |
| 单游戏覆盖 | 写入覆盖层，解析时优先 |
| 运行时切换 | 需重启游戏；UI 文案明确提示 |

由于编码是进程级，不存在「上一轮注入残留」问题——这正是移除 XML 方案后获得的简化。

### 5.5 失败回退

| 失败点 | 处理 |
| --- | --- |
| `dlopen` 失败 | 返回 false，`Log.w`，不阻断启动 |
| `dlsym` 失败 | 返回 false，`Log.w`，不阻断启动 |
| 构造 `tTJSString` 失败 | 返回 false，`Log.w`，不阻断启动 |
| 读回校验不一致 | `Log.w` 输出期望值与实际值，仍不阻断 |

原则：编码设置是增强能力，任何失败都不能影响游戏启动。

### 5.6 备选通道（POC 失败时启用）

若 `tTJSString` 无法安全构造，改用 hook：

- 在 `libkrkr_bridge.so` 内用仓库已有的 ShadowHook / ByteHook，hook `TVPGetDefaultReadEncoding`；
- 该函数按 Itanium ABI 返回 `tTJSString`（sret），hook 需在返回后覆写字符串内容，依赖 `tTJSString` 内存布局；
- 风险高于直接调用，因此仅作为备选。

第三种备选：patch overlay 覆盖 `System/Initialize.tjs` 注入 `Storages.setTextEncoding(...)`。该路径依赖 XP3 封包能力与具体游戏结构，通用性差，**不纳入本 PR**。

### 5.7 诊断日志

统一 tag `KrkrTextEncoding`：

```
request lang=jp encoding=SJIS
resolve TVPSetDefaultReadEncoding=0x... ok=true
readback defaultEncoding=SJIS match=true
```

评审与真机验证都以这段日志为准。

---

## 6. kirikiroid2 另案

| 方案 | 可行性 | 代价 |
| --- | --- | --- |
| 命令行参数 | 无参数 | — |
| 偏好 XML | 已证伪 | — |
| patch overlay + TJS | 可行但按游戏定制 | 需 XP3 封包 + 逐游戏适配 |
| native hook | 需重编 `libgame*.so` | 高，且要维护三份库 |

建议：单独立项评估，本 PR 只做 UI 门控（kirikiroid2 下隐藏语言项）。

---

## 7. UI 设计

| 位置 | 条件 | 控件 |
| --- | --- | --- |
| 全局引擎设置 · KRKR 卡片 | `!isSdl3` → 改为「内核 == krkrsdl3」 | 下拉：跟随默认 / 日语 / 英语 / 简体中文 |
| 单游戏设置 · KRKR 卡片 | 同上 | OverrideChoice：跟随全局 + 三种语言 |

文案：修改后需重启游戏生效。

注意：现有实现用的是 `if (!isSdl3)`，语义是「非 krkrsdl3 就显示」，与本设计的门控相反，需要调整为「仅 krkrsdl3 显示」。

---

## 8. 测试计划

### 8.1 POC 验证点

1. `dlsym` 能拿到 `TVPSetDefaultReadEncoding` 且地址非空；
2. 调用后 `TVPGetDefaultReadEncoding` 读回等于目标编码；
3. 日文原版游戏文本正常，logcat 无 `TVPUnsupportedEncoding`。

### 8.2 真机用例

| 用例 | 期望 |
| --- | --- |
| 日文原版 + jp | 文本正常 |
| 日文原版 + auto | 复现乱码（对照） |
| 汉化版 + zh | 文本正常 |
| 汉化版 + jp | 按需，验证「汉化但需日语环境」 |
| kirikiroid2 内核 | 语言项不可见 |
| auto 启动 | 无任何编码调用日志 |

### 8.3 回归

- 渲染、存档、字体、SDL3 门控不受影响；
- 无新增持久化写入。

---

## 9. 风险与未决问题

| 风险 | 影响 | 应对 |
| --- | --- | --- |
| `tTJSString` 构造不可行 | 主方案失败 | 切 5.6 hook 备选 |
| 编码取值名不符 | 设置无效 | POC 读回确认，映射集中一处 |
| 调用时机早于 TJS 初始化 | 崩溃 | POC 验证；必要时后移到首帧前 |
| krkrsdl3 OpenGL 本身不完整 | 与本文无关 | 沿用现有 software 默认 |
| 无测试游戏 | 无法闭环阻断项 | 需用户提供日文原版 + 汉化版各一个 |

---

## 10. 分阶段实施

| 阶段 | 内容 | 产出 |
| --- | --- | --- |
| P0 | 本文设计 | 设计稿 |
| P1 | POC：桥 + dlsym + 读回 | 可验证的最小实现 |
| P2 | 正式接入、UI 门控、日志、回退 | PR 追加提交 |
| P3 | kirikiroid2 方案评估 | 独立 issue |

P1 的验收标准是 8.1 三条全部通过；未通过则不进入 P2，避免再出现「机制空转」。
