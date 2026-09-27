# FVP 自定义字体（用户更换字体）接入方案

> 状态：**已实施（M0–M3 完成；构建/单测通过，真机交互验收待设备连接，见文末「实施记录」）**
> 关联：`docs/FVP引擎接入方案.md`（FVP 内置引擎，已实施）；上游 `xmoezzz/rfvp`（MPL-2.0，fork 基线 `304e773` + FVP 接入改动）
> 结论摘要：**App 私有字体 + 新 ABI「强制默认字体」路线（方案 B）**。
> 字体文件复用现有 `FontImport` 复制到 `filesDir/fonts/`；启动时经 `FVP_FONT_PATH` extra 下发，宿主在 create 后调用新增的
> `rfvp_android_add_font` / `rfvp_android_set_forced_font`；效果 = 用户所选字体全局替换游戏默认字体（含拉丁与汉字），
> 缺字继续走「系统字体回退 → 内置 MS 字体」链路。一期不做字体子集化、TTC 字面选择与字体下载。

---

## 0. 术语与命名

| 项 | 取值 |
|---|---|
| 功能名 | 自定义字体（Custom Font）/ 强制默认字体（Force Default Font） |
| 引擎设置键 | `fvp_font`（String：空 = 跟随游戏默认；否则为 App 私有字体绝对路径） |
| 单游戏覆盖键 | `fvp_font`（null = 跟随全局；`""` = 跟随游戏默认；路径 = 自定义） |
| 分区键 | `KEY_FVP_FONT`（随 tyrano 分区持久化，避免 Room 迁移） |
| Intent extra | `LaunchContract.FVP_FONT_PATH = "fvp_font_path"` |
| 新增 C ABI | `rfvp_android_add_font(handle, path) -> i32`、`rfvp_android_set_forced_font(handle, font_id)` |
| 字体存放 | `filesDir/fonts/<原文件名>`（复用 `FontImport.importToPrivate`，不新开目录） |
| 宿主方法 | `NativeRfvp.addFont(long, String) -> int`、`NativeRfvp.setForcedFont(long, int)` |
| 生效时机 | 引擎 create 之后、首帧之前（与 `setTextHidpi` / `setSystemFont` 同点位） |

---

## 1. 背景与目标

### 1.1 现状

rfvp 的字体能力已经较完整（详见 §2.1），当前 TyranorNext 里 FVP 用户可用的字体手段有三条：

1. **游戏自带 `font/` 目录**：把 TTF/OTF/TTC 放入 `<游戏根>/font|Font|FONT/` 即自动加载，无需任何设置；
2. **系统字体回退**（FVP 引擎设置已有开关）：缺字时用 Android 系统 CJK 字体（Noto Sans CJK 等）补字形；
3. **游戏内字体菜单**：FVP 脚本可通过 `TextFontCount/TextFontName/TextFontSet` 枚举并选择字体（部分游戏自带）。

实测（桌面 rfvp，`/tmp/hm_chs/font/HiraginoSansGB.ttc` → 指向 Hiragino Sans GB）：

```
Loaded custom font face 'ヒラギノ角ゴ 簡体中文' from /tmp/hm_chs/font/HiraginoSansGB.ttc
Font scan done: loaded 1 custom font(s) from /tmp/hm_chs/font
```

即「丢字体进 `font/` 即生效」已被验证，字体内部名、文件名、文件词干三者都可作为匹配键
（`text_manager.rs:312 matches_name`）。

### 1.2 问题

- 用户无法在 App 内选字体：要么翻找游戏目录、要么依赖系统回退，体验门槛高；
- 游戏目录字体无法做到「全局替换」：脚本显式请求 MS Gothic/MS Mincho 时，拉丁/数字/标点仍走内置字体
  （内置面只在 CJK 码点上被游戏字体优先覆盖）；
- 单游戏与全局无法分层：希望在设置里选一次、所有 FVP 游戏生效，个别游戏再单独覆盖。

### 1.3 目标与非目标

**目标**

- FVP 引擎设置（全局）与单游戏设置（覆盖）中提供「自定义字体」：跟随游戏默认 / 从文件选择器导入 / 重置；
- 选中后**全局强制生效**（覆盖脚本对内置字体的请求），缺字走系统回退与内置兜底；
- 字体文件存 App 私有目录，不改动游戏目录，不依赖 all-files 权限；
- 与现有「系统字体回退」「文本 HiDPI」开关彼此独立。

**非目标（一期）**

- 字体子集化/压缩、按字面（TTC face）选择、按字重选择；
- 字体下载/商店、云同步；
- 「仅替换 CJK 码点、保留内置拉丁」的柔和模式（P2 备选）；
- 游戏内字体菜单的联动展示（P2）。

---

## 2. 上游勘察结论（实测）

### 2.1 rfvp 字体栈

| 模块 | 行为 | 来源 |
|---|---|---|
| 自定义字体目录 | `<游戏根>/font`（大小写兼容 `Font`/`FONT`），扫描 `*.ttf/otf/ttc`，逐个 `load_font_file` 追加到 `fonts: Vec<LoadedFont>` | `text_manager.rs:748-798`、`:397-445` |
| 字体名解析 | 优先字体 `name` 表（`extract_font_face_name`），回落文件词干/文件名 | `text_manager.rs:425-431`、`:638` |
| 名称匹配 | 内部名 / 文件名 / 文件词干 三选一，大小写不敏感 | `text_manager.rs:312-320` |
| 字体枚举（脚本可见） | `TextFontCount` / `TextFontName(id)` / `TextFontGet` / `TextFontSet(id)`；用户字体 id ≥ 0，内置固定 id < 0（MS Gothic=-2 等） | `syscalls/text.rs:267-308` |
| 缺字回退 | `get_font_fallback_set(id)`：主字体 + `preferred_cjk`（请求内置面时优先游戏/系统 CJK 字体）+ fallbacks（自定义 → 系统回退 → 4 个内置面） | `text_manager.rs:946-985` |
| 当前字体解析 | `FONTFACE_CURRENT` 按 `current_font_name` 匹配内置名/已加载字体 | `text_manager.rs:873-922` |
| 渲染取字体 | 渲染时用 `text_font_idx1/2`（主/注音）调 `get_font_fallback_set` | `text_manager.rs:2524-2525` |
| 字形栅格化 | ab_glyph 懒加载（仅解析头部，字形按需栅格化） | `font.rs` 头部注释 |
| TTC | 仅加载首个字面（ttf-parser face 0），不提供 face 选择 | `Font::from_vec` |

### 2.2 缺口（需要 fork 的原因）

| # | 缺口 | 说明 |
|---|---|---|
| G1 | 无「运行时追加字体文件」API | `load_font_file` 为模块内私有，`FontEnumerator` 也未暴露追加入口；`fonts` 只在 `init_fontface()` 时填充 |
| G2 | 无「强制默认字体」概念 | 脚本 `TextFontSet` 选内置面后，游戏字体只在 CJK 码点被优先；拉丁/标点无法替换 |
| G3 | Android ABI 无字体相关入口 | 现有 ABI 仅 `set_text_hidpi` / `set_system_font`，没有 add/force |
| G4 | 宿主无字体 extra | `LaunchContract` 没有 FVP 字体键，`FvpActivity` 不感知字体 |

### 2.3 TyranorNext 现状与可复用件

| 组件 | 现状 | 复用方式 |
|---|---|---|
| `FontImport.importToPrivate(ctx, uri)` | 统一的扩展名白名单（`.ttf/.ttc/.otf/.otc`）+ 复制到 `filesDir/fonts/<原文件名>` | **直接复用**，FVP 不新增导入实现 |
| `FontPreference(label, value, followLabel, onFollow, onPick, valueInSummary)` | KRKR 全局/单游戏共用的字体选择行（Miuix `ArrowPreference` + 弹窗） | 直接复用；`followLabel` 传「跟随游戏默认」/「跟随全局」 |
| KRKR 先例 | 全局 `F_DEFAULT_FONT` + `F_FORCE_DEFAULT_FONT`；`engine_settings_default_font` / `engine_settings_use_builtin_font` 文案 | 参考语义与文案风格 |
| FVP 现有设置 | `fvp_nls` / `fvp_system_font` / `fvp_text_hidpi`（引擎设置卡片 + 单游戏分支） | 在同一卡片/分支追加字体行 |
| 实测数据 | Hiragino Sans GB.ttc 23.5 MB；`fs::read` 一次性载入内存，ab_glyph 懒栅格化，常驻 ≈ 文件体积 + 字形缓存 | 风险与耗时评估依据 |

---

## 3. 总体设计

### 3.1 路线选择

| | A. 导入到游戏 `font/` 目录 | **B. App 私有字体 + 强制默认字体（选定）** |
|---|---|---|
| 引擎改动 | 无 | fork 新增 2 个 ABI（重建 `.so`） |
| 字体位置 | `<游戏根>/font/`（污染游戏目录、需 all-files 权限） | `filesDir/fonts/`（App 私有，无权限要求） |
| 生效范围 | CJK 码点优先 + 游戏字体菜单可选 | **全局强制替换**（含拉丁/数字/标点） |
| 重置 | 需删除游戏目录文件 | 设置回空即可，不动游戏数据 |
| 全局/单游戏 | 单游戏天然隔离，全局需遍历所有游戏 | 一处设置全游戏生效，单游戏可覆盖 |
| 工作量 | ~0.5–1 天 | ~2–3 天（含 fork + `.so` + 真机回归） |

结论：走 **B**。A 的「游戏目录字体」作为现状能力保留（无需开发），在文档与设置说明中提示。

### 3.2 数据流

```
设置（引擎设置全局 / 单游戏覆盖）
  └─ fvp_font（空 = 跟随游戏默认；路径 = filesDir/fonts/xxx.ttf）
       └─ EngineLauncher.buildFvpIntent
            extras: FVP_FONT_PATH（仅非空时下发）
       └─ FvpActivity.ensureEngine()（create 成功之后、首帧之前）
            ├─ NativeRfvp.setTextHidpi / setSystemFont            // 既有
            ├─ int id = NativeRfvp.addFont(handle, path)          // 新增：加载并返回字体 id
            └─ if (id >= 0) NativeRfvp.setForcedFont(handle, id)  // 新增：设为强制默认字体
       └─ rfvp: FontEnumerator::add_font_file → set_forced_font
            └─ 渲染时 get_font_fallback_set(id)：forced 字体作为主字体
```

### 3.3 强制语义与优先级链

```
主字体（primary）:
  强制字体（若设置且加载成功）
  └─ 否则：脚本当前字体（现状逻辑：内置面 CJK 优先游戏 font/ 字体）
缺字回退（fallback，按序）:
  其它已加载字体（游戏 font/ + App 私有）
  → 系统 CJK 回退字体（fvp_system_font 开启时扫 /system/fonts）
  → 内置 MS Gothic / MS Mincho / MS PGothic / MS PMincho
```

- **强制开启时，脚本 `TextFontSet` 选内置面将被覆盖**（这是「用户换字体」的预期语义，与 KRKR「强制默认字体」一致）；
- 脚本若显式选择某个**用户字体 id**（≥0），一期同样被强制字体覆盖，保证设置所见即所得；
- 关闭强制（设置回「跟随游戏默认」）后行为与现状完全一致。

### 3.4 关键决策

1. **私有字体 + 不污染游戏目录**：字体经 `FontImport` 复制到 `filesDir/fonts/`（与 KRKR 同目录同实现）；
2. **新增 ABI 而非扩展 create 签名**：保持 create 稳定；字体路径可选、失败不阻塞启动；
3. **`forced_font` 由字体 id 指定**：`add_font` 返回 id，`set_forced_font(-1)` 关闭；后续扩展多字体切换/字面选择不需要再改 ABI 结构；
4. **不主动删除字体文件**：全局与单游戏可能引用同一路径，替换/重置仅改设置（一期不做引用扫描清理，P2 提供「清理未使用字体」）；
5. **单游戏三态**：跟随全局 / 跟随游戏默认 / 自定义（复用 KRKR 覆盖模式）；
6. **TTC 仅首个字面**：一期接受，设置说明中标注；如需按字面选择，P2 扩展 `add_font(path, face_index)`；
7. **兼容旧 `.so`**：桥接层对缺少新符号只记日志；宿主仅在 extra 非空时调用，旧库下功能自动降级为「不启用」。

---

## 4. App 侧改动清单

### 4.1 设置存储

| 文件 | 改动 |
|---|---|
| `core/settings/EngineSettingsStore.kt` | `KEY_FVP_FONT = "fvp_font"`；`getFvpFont(c): String`（默认 `""`）、`setFvpFont(c, path)`；空串归一 |
| `core/settings/EngineSettingsResolver.kt` + `ResolvedEngineSettings` | 新增 `fvpFont: String`（全局值 + 单游戏覆盖：`resolve(override, global)`；空串合法，表示「跟随游戏默认」） |
| `core/settings/PerGameSettingsStore.kt` | `F_FVP_FONT = "fvp_font"` |
| `core/game/storage/GameOverridePartitions.kt` | `KEY_FVP_FONT` 常量并加入 `TYRANO_KEYS`（与 `KEY_FVP_NLS` 同策略） |
| `core/settings/EffectiveEngineSettings.kt` | 复用 `resolve`（路径无白名单；仅判空） |

### 4.2 字体导入

**零新增实现**：直接调用 `FontImport.importToPrivate(ctx, uri)`（`ui/settings/FontSettings.kt:31-53`），
扩展名白名单已覆盖 `.ttf/.ttc/.otf/.otc`，落点 `filesDir/fonts/<原文件名>`。

### 4.3 启动链路

| 文件 | 改动 |
|---|---|
| `engine/.../LaunchContract.kt` | `const val FVP_FONT_PATH = "fvp_font_path"` |
| `app/.../EngineLauncher.kt` `buildFvpIntent` | `if (settings.fvpFont.isNotBlank()) putExtra(LaunchContract.FVP_FONT_PATH, settings.fvpFont)` |

### 4.4 UI

| 文件 | 改动 |
|---|---|
| `ui/settings/SettingsScreen.kt` FVP 卡片 | 新增 `FontPreference`（`followLabel = engine_settings_fvp_font_follow`），`onFollow = { fvpFont = "" }`，`onPick = { fontLauncher.launch("*/*") }`；值展示用 `File(path).name`；`saveAll` 写 `setFvpFont` |
| `ui/settings/PerGameSettingsScreen.kt` FVP 分支 | 新增字体行（`valueInSummary = true`）：`followLabel = engine_settings_follow_global`，覆盖值 null/""/路径 三态；沿用该页现有 `fontLauncher` |
| 说明文案 | 卡片底部补「字体仅对 FVP 生效；TTC 使用首个字面；缺字由系统字体回退兜底」 |

### 4.5 文案（三语言严格一致）

新增键（键名见附录 B）：`engine_settings_fvp_font_title`、`engine_settings_fvp_font_follow`、
`engine_settings_fvp_font_hint`、`engine_settings_fvp_font_import_failed`。
复用已有：`engine_settings_select_font_file`、`engine_settings_follow_global`、
`engine_settings_follow_global_with_value`、`engine_settings_use_builtin_font`（不强制新增）。

### 4.6 其它

- `GameSaveManager` / 插件 / 扫描：无功能分支变化；
- 无需新增权限（字体在 App 私有目录）。

---

## 5. engine 侧改动清单

### 5.1 契约

`engine/src/main/java/com/core/engine/LaunchContract.kt`

```kotlin
// ---------- FVP ----------
/** 自定义字体路径（App 私有目录绝对路径；不传表示跟随游戏默认/不强制）。 */
const val FVP_FONT_PATH = "fvp_font_path"
```

### 5.2 宿主 `com/core/fvp/FvpActivity.java`

`ensureEngine()` 在既有 `setTextHidpi` / `setSystemFont` 之后追加：

```java
String fontPath = getIntent().getStringExtra(LaunchContract.FVP_FONT_PATH);
if (fontPath != null && !fontPath.trim().isEmpty()) {
    int fontId = NativeRfvp.addFont(handle, fontPath.trim());
    if (fontId >= 0) {
        NativeRfvp.setForcedFont(handle, fontId);
        Log.i(TAG, "forced font enabled: id=" + fontId + " path=" + fontPath);
    } else {
        Log.w(TAG, "custom font load failed, fall back to game default: " + fontPath);
    }
}
```

失败不 Toast、不阻塞（设置页可见当前路径，便于用户自查）。

### 5.3 JNI 桥 `engine/src/main/cpp/rfvp_bridge.cpp`

- `RfvpApi` 新增 `add_font: int32_t (*)(void*, const char*)`、`set_forced_font: void (*)(void*, int32_t)`；
- `load_api_once` 中作为**可选符号**加载：缺失仅 `LOGW`（兼容未升级的 `librfvp.so`）；
- JNI 包装：
  - `Java_com_core_fvp_NativeRfvp_addFont(JNIEnv*, jclass, jlong handle, jstring path) -> jint`
  - `Java_com_core_fvp_NativeRfvp_setForcedFont(JNIEnv*, jclass, jlong handle, jint fontId)`

### 5.4 `NativeRfvp.java`

```java
/** 追加字体文件并返回字体 id（≥0 成功；-1 失败/旧库不支持）。 */
public static native int addFont(long handle, String fontPathUtf8);

/** 设置/清除强制默认字体（fontId < 0 清除）。 */
public static native void setForcedFont(long handle, int fontId);
```

### 5.5 打包

无新增 `.so`；替换重建后的 `librfvp.so`（含新 ABI）。`consumer-rules` 已 keep `com.core.fvp.**`，无需改动。

---

## 6. fork（rfvp）改动

基线：`/Users/weiss/github- engine/rfvp`（FVP 接入改动之上）。

### 6.1 Android C ABI（`crates/rfvp/src/android_host.rs`）

```c
/// 追加一个字体文件；返回字体 id（≥0），失败返回 -1。
int32_t rfvp_android_add_font(void* handle, const char* font_path_utf8);

/// 设置强制默认字体；font_id < 0 表示清除。
void rfvp_android_set_forced_font(void* handle, int32_t font_id);
```

### 6.2 `FontEnumerator`（`subsystem/resources/text_manager.rs`）

```rust
// 字段
forced_font_id: Option<i32>,

// 新增公开 API
pub fn add_font_file(&mut self, path: &Path) -> Option<i32>;   // 复用 load_font_file，push 后返回 index
pub fn set_forced_font(&mut self, id: Option<i32>);             // 越界/无效 id 归一为 None

// 拦截点：get_font(id) 与 get_font_fallback_set(id)
// forced 生效时 primary = fonts[id]，preferred_cjk 清空（强制字体即主字体），
// fallbacks = 其它已加载字体 + 系统回退（若启用） + 4 个内置面
```

- `no_std` 分支提供同名空实现（保持编译面一致）；
- `init_fontface()` 重扫时清空 `forced_font_id`，避免悬空 id。

### 6.3 `App` 包装（`crates/rfvp/src/app.rs`）

```rust
pub fn add_font_file(&mut self, path: &std::path::Path) -> Option<i32>;
pub fn set_forced_font(&mut self, id: Option<i32>);
```

内部经 `gd_write(&self.game_data).fontface_manager`，与 `set_system_font_fallback_enabled` 同模式。

### 6.4 构建与版本

```bash
cd "/Users/weiss/github- engine/rfvp"
export ANDROID_NDK_HOME="$HOME/Library/Android/sdk/ndk/28.0.13004108"
CARGO_NDK_PLATFORM=26 cargo ndk -t arm64-v8a -o /tmp/rfvp-android-font build --release -p rfvp --lib
"$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-strip" --strip-unneeded \
  /tmp/rfvp-android-font/arm64-v8a/librfvp.so
cp /tmp/rfvp-android-font/arm64-v8a/librfvp.so \
   /Users/weiss/opencode/rma/TyranorNext/engine/src/main/jniLibs/arm64-v8a/
```

- 记录基线 commit；保留 MPL-2.0 声明；
- ABI 只增不改，旧宿主二进制不受影响。

---

## 7. 关键交互细节

### 7.1 生效时机

- 与 `setTextHidpi` / `setSystemFont` 同点位（create 成功后、首帧前），保证标题画面即为所选字体；
- 设置修改需重启本局生效（与文本编码一致），设置页信息卡注明。

### 7.2 缺字与回退

- 强制字体为主字体；缺字依次尝试：其它已加载字体 → 系统 CJK 回退（开关开启时）→ 内置 MS 面；
- 若用户所选字体缺简体字且系统回退关闭，会退回内置字体字形（可能不合口味），设置说明提示「建议保持系统字体回退开启」。

### 7.3 与游戏内字体菜单的关系

- 强制开启时，游戏内字体选择被覆盖（设计取舍）；
- 若用户想用游戏内菜单自行选择，把设置改为「跟随游戏默认」即可（不传 extra、不强制）。

### 7.4 全局与单游戏

| 层级 | 值 | 行为 |
|---|---|---|
| 全局 | `""` | 跟随游戏默认（现状行为） |
| 全局 | 路径 | 所有 FVP 游戏强制该字体 |
| 单游戏 | null | 跟随全局 |
| 单游戏 | `""` | 该游戏跟随游戏默认（无视全局） |
| 单游戏 | 路径 | 该游戏强制该字体（可指向与全局不同的字体文件） |

### 7.5 字体文件生命周期

- 导入：`FontImport` 原样复制到 `filesDir/fonts/<原文件名>`，同名覆盖；
- 替换/重置：只改设置，不删文件（可能与其它游戏/全局共享）；
- 卸载应用随私有目录清除；P2 提供「清理未使用字体」（扫描全局 + 所有单游戏覆盖引用后删除）。

### 7.6 兼容与失败降级

| 场景 | 行为 |
|---|---|
| 旧 `.so`（无新符号） | 桥接 `LOGW`，`addFont` 返回 -1；不启用强制，游戏按现状运行 |
| 字体文件被删除/损坏 | `addFont` 失败 → 回退游戏默认字体；日志可见 |
| 路径为空 | 不下发 extra，宿主不调用新 ABI |
| `.ttc` 多字面 | 仅首个字面生效（说明文案标注） |

---

## 8. 构建与集成步骤

```bash
# 1) fork：新增 ABI + 重建 librfvp.so（见 §6.4），strip 后入 jniLibs
# 2) 集成构建与校验
./gradlew :app:assembleDebug --no-daemon
./gradlew :app:testDebugUnitTest --no-daemon
python3 tools/check-hardcoded-ui-strings.py
git diff --check
```

CI 影响：无 Rust 工具链需求（`.so` 入库）；APK 体积不变（仅替换同名 `.so`）。

---

## 9. 测试与验收

### 9.1 单元测试

| 用例 | 内容 |
|---|---|
| `EffectiveEngineSettingsTest`（扩展） | `fvpFont` 空串/路径合并（覆盖优先、空串合法） |
| `GameOverridePartitionsTest`（扩展） | `F_FVP_FONT` 契约断言 + tyrano 分区往返 |
| 设置解析（带 Context） | 由真机验收覆盖，不新增 Robolectric |

### 9.2 真机验收（Huawei MAA-AN10，HappyMarguerite CHS 全量数据）

1. 全局导入中文字体（如霞鹜文楷 / 思源黑体）→ 启动日文原版与汉化（`gbk`/`sjis`）：
   汉字、全角标点、拉丁字母均为所选字体；
2. 关闭系统字体回退 + 强制字体缺某字形 → 观察回退链（内置字体兜底不崩溃）；
3. 设置为「跟随游戏默认」→ 与未接入前表现一致；
4. 单游戏覆盖：A 游戏自定义、B 游戏跟随全局，互不影响；
5. 重置/删除字体文件后启动 → 回落游戏默认，日志有记录；
6. 启动耗时与内存：导入 20–40 MB 字体前后对比首帧时间与常驻内存；
7. 游戏内字体菜单：强制开启时被覆盖、关闭后菜单恢复可选（验证优先级设计）；
8. 旧 `.so` 兼容演练（可选）：替换为接入前的 `librfvp.so`，应用不崩溃、功能降级。

### 9.3 回滚

- 移除 FVP 字体设置项与 `FVP_FONT_PATH` 下发即可回到现状（字体文件与键值滞留无害）；
- `.so` 中新增 ABI 为增量，无回滚风险。

---

## 10. 风险与待确认

| 风险 | 影响 | 缓解 |
|---|---|---|
| 字体版权 | 用户自带字体，App 不内置不传播 | 设置说明注明「请使用你有权使用的字体」 |
| 大字体内存/首帧耗时 | 20–40 MB 常驻 + 首帧解析 | ab_glyph 懒加载；验收含耗时对比；必要时 P2 字体子集化 |
| TTC 仅首字面 | 部分用户期望的 face 不生效 | 说明文案标注；P2 扩展 face 选择 |
| 全角/半角宽度差异 | 换字体后排版溢出/换行变化 | 属换字体固有行为；提供一键重置 |
| 与游戏内字体菜单冲突 | 游戏内选择「无效」 | 强制语义 + 可关闭；文档与设置说明 |
| 字体文件堆积 | 私有目录变大 | P2「清理未使用字体」 |
| 旧 `.so` 缺符号 | 调用失败 | 桥接可选符号 + 宿主降级（§7.6） |

待确认（评审时定）：

1. 选中自定义字体是否默认即强制（建议：是；「跟随游戏默认」即不强制）；
2. 是否 P2 提供「仅替换 CJK 字形」的柔和模式（保留内置拉丁）；
3. 单游戏是否需要「独立于全局的多字体选择」（一期单值，建议不做）；
4. 设置卡片是否显示字体文件的内部字体名（需读 name 表，建议一期只显示文件名）。

---

## 11. 里程碑（建议实施顺序）

| 阶段 | 内容 | 依赖 | 预估 |
|---|---|---|---|
| M0 | fork：`add_font_file` / `set_forced_font` + 2 个 ABI + 重建 strip `.so` | 无 | 0.5–1 天 |
| M1 | engine：`LaunchContract.FVP_FONT_PATH` + `FvpActivity` 接线 + 桥接/NativeRfvp | M0 | 0.5 天 |
| M2 | app：Store/Resolver/Partition/Launcher + 全局与单游戏字体行 + 文案 ×3 | M1 | 0.5–1 天 |
| M3 | 单测 + 真机验收（§9.2）+ 方案文档实施记录 | M2 | 0.5–1 天 |
| 二期 | 多字体管理/清理、TTC face 选择、柔和模式、游戏内菜单联动 | M3 | 待排 |

---

## 附录 A：新增 ABI 对照表

| shim JNI（`com.core.fvp.NativeRfvp`） | C ABI | 返回值/语义 |
|---|---|---|
| `addFont(handle, path)` | `rfvp_android_add_font` | 字体 id（≥0）或 -1 |
| `setForcedFont(handle, id)` | `rfvp_android_set_forced_font` | id < 0 清除强制 |

## 附录 B：新增文案键（三语言）

| 键 | zh | ja | en |
|---|---|---|---|
| `engine_settings_fvp_font_title` | 自定义字体 | カスタムフォント | Custom font |
| `engine_settings_fvp_font_follow` | 跟随游戏默认 | ゲーム既定に従う | Follow game default |
| `engine_settings_fvp_font_hint` | 仅对 FVP 生效；TTC 使用首个字面；缺字由系统字体回退兜底；修改后需重新进入游戏 | FVP のみ有効。TTC は先頭フェイスのみ。欠け字形はシステムフォントで補完。変更後は再起動が必要 | FVP only. TTC uses the first face. Missing glyphs fall back to system fonts. Restart to apply |
| `engine_settings_fvp_font_import_failed` | 字体导入失败：请选择 ttf/otf/ttc 文件 | フォントの読み込みに失敗しました：ttf/otf/ttc を選択してください | Font import failed: choose a ttf/otf/ttc file |

## 附录 C：设置键与优先级

| 键 | 层级 | 取值 | 默认 |
|---|---|---|---|
| `fvp_font` | 全局 | `""` \| 绝对路径 | `""`（跟随游戏默认） |
| `fvp_font` | 单游戏 | `null` \| `""` \| 绝对路径 | `null`（跟随全局） |

生效解析顺序：单游戏覆盖 > 全局；`""` 显式表示「跟随游戏默认」；路径不存在时回落游戏默认（不写入设置）。

## 附录 D：与现有字体手段的关系

| 手段 | 优先级 | 作用范围 | 依赖 |
|---|---|---|---|
| 本方案「自定义字体」（强制） | 最高（强制开启时） | 全局替换（含拉丁） | App 私有 + 新 ABI |
| 游戏 `font/` 目录字体 | 中（请求内置面时 CJK 码点优先） | CJK 字形 | 游戏自带/用户手动放置 |
| 系统字体回退（`fvp_system_font`） | 回退层 | 缺字补齐 | Android 系统字体 |
| 内置 MS Gothic/Mincho 等 | 兜底 | 全码点 | rfvp 内置 |

---

## 实施记录（M0–M3）

### M0 fork 改动（`/Users/weiss/github- engine/rfvp`，未提交）

| 文件 | 改动 |
|---|---|
| `crates/rfvp/src/subsystem/resources/text_manager.rs` | `FontEnumerator` 新增 `forced_font_id` 字段与 `add_font_file(path) -> Option<i32>` / `set_forced_font(id)` / `forced_font_id()` / `is_font_forced()` / `forced_font()`；`get_font(id)` 前置强制字体返回；`get_font_fallback_set(id)` 在强制时关闭 `preferred_cjk` 重排；`init_fontface()` 开头清空强制 id；no_std 分支补空实现；新增单测 `font_enumerator_tests::add_font_file_returns_id_and_force_normalizes` |
| `crates/rfvp/src/app.rs` | `App::add_font_file(&Path) -> Option<i32>`、`App::set_forced_font(Option<i32>)`（经 `gd_write` 与 `set_system_font_fallback_enabled` 同模式） |
| `crates/rfvp/src/android_host.rs` | 新增导出 `rfvp_android_add_font(handle, path) -> i32`、`rfvp_android_set_forced_font(handle, id)` |

构建与入库（API 26）：

```bash
cd "/Users/weiss/github- engine/rfvp"
export ANDROID_NDK_HOME="$HOME/Library/Android/sdk/ndk/28.0.13004108"
CARGO_NDK_PLATFORM=26 cargo ndk -t arm64-v8a -o /tmp/rfvp-android-font build --release -p rfvp --lib
"$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-strip" --strip-unneeded \
  /tmp/rfvp-android-font/arm64-v8a/librfvp.so
cp /tmp/rfvp-android-font/arm64-v8a/librfvp.so \
   engine/src/main/jniLibs/arm64-v8a/
```

实测：`librfvp.so` strip 后 40,534,728 B（较接入前 +4,928 B）；`rfvp_android_add_font` / `rfvp_android_set_forced_font` 已确认导出。

### M1 engine 侧改动

- `LaunchContract.kt`：新增 `FVP_FONT_PATH = "fvp_font_path"`；
- `rfvp_bridge.cpp`：`RfvpApi` 新增 `add_font` / `set_forced_font` 两个**可选符号**（缺失仅 `LOGW`，兼容旧 `.so`）；新增 JNI `addFont` / `setForcedFont`；
- `NativeRfvp.java`：新增 `addFont(long, String) -> int`、`setForcedFont(long, int)`；
- `FvpActivity.java`：`ensureEngine()` 在 hidpi/system font 之后调用 `applyCustomFont()`（extra 非空才执行；加载失败静默回退游戏默认）。

### M2 app 侧改动

- 存储/解析：`EngineSettingsStore`（`KEY_FVP_FONT` + `getFvpFont/setFvpFont/normalizeFvpFont`）、`EngineSettingsResolver`/`ResolvedEngineSettings.fvpFont`、`PerGameSettingsStore.F_FVP_FONT`、`GameOverridePartitions.KEY_FVP_FONT`（加入 `TYRANO_KEYS`）；
- 启动：`EngineLauncher.buildFvpIntent` 非空时下发 `FVP_FONT_PATH`；
- UI：`SettingsScreen` FVP 卡片新增 `FontPreference` 行 + 独立 `fvpFontLauncher`（导入失败 Toast）+ `fvp_font_hint`；`PerGameSettingsScreen` FVP 分支新增三态 `OverrideFontPreference`（跟随全局 / 跟随游戏默认 / 选择字体文件）与 `fvpFontLauncher`；
- 文案 ×3：`engine_settings_fvp_font_title` / `..._follow` / `..._hint` / `..._import_failed`；
- 单测：`EffectiveEngineSettingsTest.fvpFontOverrideSupportsExplicitGameDefault`、`GameOverridePartitionsTest`（`F_FVP_FONT` 往返 + 契约断言）。

### 与方案的偏差

1. 单游戏字体行未改动共享 `FontPreference`（其仅支持「跟随 + 选择」两态），改为在 `PerGameSettingsScreen` 内新增私有 `OverrideFontPreference` 实现三态；共享组件与 KRKR 行为零影响。
2. 方案 §6.2「重扫清空强制 id」实现为 `init_fontface()` 入口无条件清空（含首次扫描），宿主在 create 后重新应用，语义一致且更简单。
3. 新增 `FontEnumerator::forced_font_id()` getter 仅用于单测断言。

### 验证结果

- `cargo check -p rfvp`：通过；
- `cargo test -p rfvp --lib font_enumerator_tests`：1 passed（加载返回 id=0、非法 id 归一、重扫清空、缺失文件返回 None）；
- `./gradlew :app:assembleDebug --no-daemon`：通过；APK 内 `librfvp.so` 导出 `rfvp_android_add_font/set_forced_font`，`librfvp_bridge.so` 导出 `Java_com_core_fvp_NativeRfvp_addFont/setForcedFont`（`llvm-nm` 实测）；
- `./gradlew :app:testDebugUnitTest --no-daemon`：通过（`EffectiveEngineSettingsTest` 17 例、`GameOverridePartitionsTest` 7 例）；
- `python3 tools/check-hardcoded-ui-strings.py`、`git diff --check`：通过；
- **真机验收（§9.2）：未执行**——执行时 adb 设备已断开（`adb devices` 为空，APK 已构建并含有新符号）。待设备恢复连接后按 §9.2 执行：导入中文字体 → 启动 HappyMarguerite（日文/汉化）验证全局字体、日志出现 `forced font enabled: id=.. path=..`、关闭/重置后恢复、单游戏覆盖与缺字回退。

### 待办

1. 设备恢复后执行 §9.2 真机验收并回填本节；
2. 二期候选（多字体管理/清理、TTC face 选择、柔和模式、游戏内菜单联动）未启动。
