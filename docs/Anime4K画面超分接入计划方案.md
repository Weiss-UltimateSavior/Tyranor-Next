# Anime4K 画面超分接入计划方案

> 状态：M1–M4 已实现（编译通过，待真机验证帧率与画质）
> 范围：KR2/Kirikiroid2 路径（一期 + 二期完整移植），krkrsdl3 / ONS 留作三期专项
> 许可证：Anime4K（bloc97）MIT —— 可移植改造；移植产物归入本仓库 GPL-2.0 分发

## 实施记录（2026-09-06）

实际落地与原计划的两处修正：

1. **模式体系修正**：Anime4K v4.0 全部为 CNN 着色器，不存在 v3 时代的"无 CNN 轻量模式"。
   游戏帧已在屏幕原生分辨率，无需 Upscale 链，实际采用 **Restore 链**：
   `Restore_CNN S/M/L`（线条重建三档）+ `Restore_CNN_Soft S/M/L`（轻柔变体）+ `Deblur_DoG`（去模糊，RGBA8 安全）。
   Clamp_Highlights 依赖 mpv 专有 `STATSMAX`/`PREKERNEL` hook，暂不移植。
2. **设置传递通道修正**：不经 `krkr_engine_prefs` JSON / Kirikiroid2Preference.xml 注入，
   改为**专用 intent extra `tyranor_anime4k`** 直接传递——模式必须在 GL SurfaceView 构造
   （EGL 上下文版本定型）前可知，extra 通道更直接且规避 XML 键所有权问题。

关键实现决策：

- **条件 ES3 上下文**：仅当超分启用时 `setEGLContextClientVersion(3)`（CNN 中间值可为负，
  ReLU 编码需 RGBA16F 渲染目标；ES3 上下文向后兼容引擎 ES2 渲染路径，未启用时零回归）
- **能力探测三层**：设备 GLES3 特性（`reqGlEsVersion`）→ RGBA16F FBO 完整性 → 着色器编译，
  任一失败静默禁用本会话（不崩溃）
- **运行时解析 mpv hook 格式**：`MpvHookShader` 解析 `//!DESC/BIND/SAVE/WIDTH` 指令，
  `Anime4kPostProcessor` 生成 ES 3.00 包装着色器（`NAME_tex/NAME_texOff/NAME_pos/NAME_pt`
  语义等价 mpv 内建宏），单一管线支持全部 7 个着色器文件，无需逐个手工转写
- **自引用 SAVE 双缓冲**：`SAVE` 目标同时被 `BIND` 的 pass（如 Deblur 的 MMKERNEL）
  以双纹理交换避免同帧读写冲突
- **注入点**：`Cocos2dxRenderer.onDrawFrame()` 的 `nativeRender()` 之后、GLSurfaceView
  隐式 swap 之前；GL 状态保存/恢复（viewport/program/blend/scissor/FBO/纹理绑定）

落地文件：

| 文件 | 职责 |
|---|---|
| `engine/src/main/assets/anime4k/*.glsl` | bloc97 MIT 着色器 ×7（头部版权保留） |
| `engine/.../com/core/gl/Anime4kRuntime.java` | 配置（extra 解析、GLES3 探测、模式→着色器映射） |
| `engine/.../com/core/gl/MpvHookShader.java` | mpv hook 格式解析器 |
| `engine/.../com/core/gl/Anime4kPostProcessor.java` | GL 运行器（FBO 池、着色器生成、每帧执行、状态保护） |
| `engine/.../org/cocos2dx/lib/Cocos2dxRenderer.java` | 三处注入点（created/changed/drawFrame） |
| `engine/.../org/cocos2dx/lib/Cocos2dxGLSurfaceView.java` | 条件 ES3 上下文 |
| `engine/.../KirikiroidLauncherBaseActivity.java` | onCreate 读 extra（super 前）、onDestroy reset |
| `app/.../EngineLauncher.kt` | KR2 路径注入 extra（单游戏覆盖 > 全局，白名单校验） |
| `app/.../EngineSettingsStore.kt` | 全局键 `kr_anime4k_mode`（off/s/m/l/soft_s/soft_m/soft_l/deblur） |
| `app/.../PerGameSettingsStore.kt` | 单游戏覆盖字段 `anime4k_mode` |
| `app/.../SettingsScreen.kt` + `EngineSettingsText.kt` | 渲染卡片"画面超分"下拉（仅 kirikiri2 内核显示） |
| `app/.../PerGameSettingsScreen.kt` | 渲染卡片三态覆盖（跟随全局/强制） |

待验证项（真机）：

- [ ] 锐化效果目测（建议 720p 素材游戏 + S/M 档对比）
- [ ] 帧率（`adb shell dumpsys gfxinfo`，中端机 S 档 60fps 预期达标，L 档可能需 30fps 锁定）
- [ ] 切屏 / 后台 / 息屏恢复后管线重建（上下文丢失路径）
- [ ] ES3 上下文对 cocos2d-x 2.x 渲染的实际兼容性（重点回归：半透明、混合模式、旧设备）

---

## 1. 背景与目标

大量 VN 游戏为 720p 素材被引擎双线性拉伸至 2K/3K 屏幕，线条模糊。Anime4K 的线条重构算法正是针对"低分辨率动漫画面被放大"场景设计。

**总目标**：在 KR2 引擎每帧渲染完成后、缓冲区交换前，插入 Anime4K GLSL 后处理链，实现游戏画面实时超分。

**不做的事**：
- 不走 mpv / mpvlibAndroid 路线（只能超分 mpv 自己解码的视频帧，无法作用于引擎渲染）
- 不使用 Anime4K 官方 mpv `//--HOOK` 语法着色器（绑定 mpv 渲染阶段，不可直接用）
- 一期不碰 krkrsdl3 / ONS / Tyrano 路径

## 2. 原理：注入点

KR2 使用 `GLSurfaceView`（Java 驱动渲染循环），`Cocos2dxRenderer.onDrawFrame()` 中调用 `nativeRender()` 后、`eglSwapBuffers` 前，EGL 上下文正处于 current 状态 —— 可直接插入 GL 调用，**无需任何 native hook**。

```
引擎 C++ 绘制游戏帧（默认帧缓冲）
        ↓
★ 注入点（Java 层，onDrawFrame 内）
   1. default framebuffer → FBO 纹理拷贝
   2. Anime4K 多 pass 着色器链（FBO ping-pong）
   3. 全屏四边形绘制回默认帧缓冲
        ↓
eglSwapBuffers 上屏
```

关键文件：
- `engine/src/main/java/org/cocos2dx/lib/Cocos2dxRenderer.java`（注入点）
- `engine/src/main/java/org/cocos2dx/lib/Cocos2dxGLSurfaceView.java`（EGL 上下文版本确认，见 §5）

## 3. 阶段划分

```
POC（阶段一）→ Mode B 完整移植（阶段二A）→ Mode A/C CNN（阶段二B）→ 设置接入（阶段三）
                                                      ↘ krkrsdl3/ONS SDL hook（三期，另行立项）
```

---

## 4. 阶段一：POC —— 单 pass 锐化验证管线

**目标**：注入管线跑通 + 帧率达标。不追求画质效果。

**验收标准**：
1. 游戏画面出现可见锐化效果（Anime4K Restore M 单级）
2. 60fps 场景帧率降幅 ≤ 10%（中端机型参考：骁龙 7 系）
3. 开关即时生效，关闭时零 GL 开销（不留空跑 pass）
4. 无闪烁、无黑屏、横竖屏切换 / 暂停恢复后管线正常重建

### 4.1 任务分解

| # | 任务 | 文件 | 说明 |
|---|---|---|---|
| P1 | 确认 `EGL_CONTEXT_CLIENT_VERSION` ≥ 3 | Cocos2dxGLSurfaceView 的 setEGLContextClientVersion | 不足则 CNN 阶段（二B）half-float 受阻；POC 单 pass 用 ES 2.0 也够，但直接确认到 3 省返工 |
| P2 | 新建 `Anime4KPostProcessor` 类（engine 模块） | `engine/.../gl/Anime4KPostProcessor.java` | 封装：FBO 创建/销毁、纹理管理、着色器编译、单 pass 绘制。只依赖 `GLES30` API，零新增 native 依赖 |
| P3 | Restore M 着色器移植（单文件） | `engine/src/main/assets/anime4k/restore_m.frag`（或 raw res） | 从 mpv hook 语法改为标准全屏四边形片元着色器：入口 `main()`，输入纹理 uniform，删掉 `//--HOOK`/`//--BIND` 指令 |
| P4 | 注入 onDrawFrame | Cocos2dxRenderer.java L132-L177 | `nativeRender()` 之后调用 `postProcessor.process()`；处理器的启用状态需原子读（渲染线程每帧查询） |
| P5 | 临时开关（调试用） | 临时用本地广播 / intent extra | 阶段三才接正式设置体系，POC 期间用最简方式开关 |
| P6 | 帧率与正确性验证 | — | `adb shell dumpsys gfxinfo`、真机目测、开关 A/B 对比截图 |

### 4.2 关键实现细节

**帧缓冲拷贝**：默认帧缓冲不可作为纹理采样，需要 `glCopyTexSubImage2D`（简单但有带宽成本）或屏幕外 FBO 重定向引擎渲染目标（更优但侵入大）。POC 用 `glCopyTexSubImage2D`，完整移植阶段评估切换。

**生命周期**：GLSurfaceView 的 GL 上下文可能销毁重建（onPause/onResume、切屏），所有 FBO/纹理/program 句柄必须在 `onSurfaceChanged`/上下文丢失时释放重建。`Anime4KPostProcessor` 需暴露 `releaseGlResources()`。

**线程**：`onDrawFrame` 在 GL 线程，设置开关来自主线程 —— 用 `volatile`/`AtomicBoolean` 即可，不做跨线程 GL 调用。

---

## 5. 阶段二A：Mode B 完整移植（轻量多 pass）

**目标**：Anime4K Mode B（Restore + Upscale + Final，无 CNN）完整链路，作为正式发布的最小可用版本。

**验收标准**：1080p→2K 场景线条重构肉眼可辨；中端机 60fps 帧率降幅 ≤ 20%；低端机自动提示关闭。

### 5.1 任务分解

| # | 任务 | 说明 |
|---|---|---|
| B1 | FBO ping-pong 管理器 | 双 FBO 交替作输入/输出；尺寸 = 屏幕分辨率；每 pass 一次绑定切换 |
| B2 | Restore L/M、Upscale、Final 着色器移植 | Mode B 全套 4-5 个 pass；统一为"纹理输入→FBO 输出"标准结构，公共 uniform（srcSize/dstSize） |
| B3 | pass 链构建器 | 按模式读取配置的 pass 序列，运行时按序执行；为二B 的 CNN pass 预留接口 |
| B4 | `glCopyTexSubImage2D` → 引擎渲染目标重定向评估 | 带宽优化；若收益 < 10% 则放弃，保留拷贝方案 |
| B5 | 帧间变化检测（可选优化） | VN 大部分时间静态：比较帧 hash（CPU 读回成本高，改为 GPU 侧降采样差异检测 + `glReadPixels` 1×1 探针），静止帧跳过整条链，只重绘缓存结果 |

---

## 6. 阶段二B：Mode A/C（CNN 卷积 pass）

**目标**：高质量模式，需 half-float 纹理。

**验收标准**：旗舰机 Mode A @ 60fps 或锁 30fps 可用；设备能力探测正确回退。

| # | 任务 | 说明 |
|---|---|---|
| C1 | 设备能力探测 | `GLES30` 版本 + `EXT_color_buffer_half_float` / `OES_texture_half_float` 扩展查询；不支持则隐藏 Mode A/C 选项 |
| C2 | CNN 着色器移植（Restore CNN L/M、Upscale CNN） | 卷积权重以 `const` 数组嵌入着色器（MIT 许可允许）；half-float FBO |
| C3 | 性能分级与回退策略 | 运行时帧率监控（滑动平均），连续低于阈值自动降级 Mode B，再降关闭；分级规则待真机标定后定 |
| C4 | 功耗标注 | CNN 模式在设置页标注"高功耗"；默认模式定为 Mode B |

---

## 7. 阶段三：设置接入

复用现有设置体系与注入通道，不新造轮子。

### 7.1 设置 UI

| 位置 | 内容 |
|---|---|
| 全局引擎设置 → KRKR 卡片新增"画面超分"卡片 | 开关 + 模式选择（关闭 / Mode B / Mode A / Mode C，按 C1 能力过滤） |
| PerGameSettingsScreen → KIRIKIRI 分支新增同名覆盖项 | 三态（跟随全局 / 强制开 / 强制关），复用 `OverrideChoice` |
| 遵循现有规范 | 两级字号规范（item 用 bodyMedium、标题 titleMedium Bold）；AppAlertDialog；搜索字段规范不涉及 |

相关文件：`app/src/main/java/com/tyranor/next/ui/settings/SettingsScreen.kt`（L703-L765 KRKR 卡片区）、`EngineSettingsText.kt`（选项文本源）、`PerGameSettingsScreen.kt`（L244-L320 KIRIKIRI 分支）。

### 7.2 设置存储与传递链路

```
EngineSettingsStore（全局键，如 kr_upscale_mode）
        ↓ EngineLauncher.buildKirikiriIntent()（L449-L458 现有合并逻辑）
PerGameSettingsStore 覆盖合并 → krkr_engine_prefs JSON extra
        ↓ KirikiroidLauncherBaseActivity.applyEnginePreferences()
Kirikiroid2Preference.xml 自有键（如 tyranor_anime4k_mode）
        ↓ 引擎进程启动时读取
Cocos2dxRenderer / Anime4KPostProcessor 启用
```

- 引擎侧运行时改键即时生效（壳内"全局设置"页与本设置共写同一 XML，需在 `applyEnginePreferences()` 的 `_rinne_injected_*` 所有权标记机制中登记新键，避免壳侧回写覆盖）
- 模式变更（如 B→A）需触发管线重建：设置页写键后通过现有壳进程通信通道通知重建（参照方向键注入的通知方式）

### 7.3 文档与合规

- README 致谢新增 bloc97/Anime4K（MIT）
- 引擎设置页"关于/开源许可"条目补充 Anime4K 许可文本链接

---

## 8. 风险清单

| 风险 | 等级 | 缓解 |
|---|---|---|
| GLSurfaceView 上下文版本 < 3 | 低 | P1 首先确认；可 setEGLContextFactory 自定义（KR2 源码在手） |
| 上下文销毁重建后句柄泄漏/崩溃 | 中 | P2 生命周期管理为验收项；覆盖切屏、后台、息屏场景 |
| `glCopyTexSubImage2D` 带宽瓶颈（高分屏） | 中 | B4 评估重定向；VN 静态帧多，B5 跳帧缓解 |
| 中低端机 CNN 帧率崩塌 | 高 | C3 自动降级 + 默认 Mode B |
| 壳设置页与启动器设置写同一键互相覆盖 | 中 | §7.2 所有权标记登记 |
| 着色器兼容性（个别 GPU 驱动编译失败） | 中 | 编译失败捕获 → 运行时禁用该模式并记录，不崩溃 |
| 与引擎内 UI（TVPGameMainMenu 等）叠加渲染冲突 | 低 | 后处理作用于整帧，菜单栏同样被锐化，视觉可接受；若不接受需在菜单展开时旁路（二期评估） |

## 9. 里程碑与顺序

| 里程碑 | 内容 | 出口条件 |
|---|---|---|
| M1 | POC（§4） | 单 pass 跑通 + 帧率达标 |
| M2 | Mode B（§5） | 画质验收 + 中端机达标 |
| M3 | Mode A/C（§6） | 旗舰机达标 + 回退策略生效 |
| M4 | 设置接入（§7） | 全局 + 单游戏覆盖全链路可用 |

阶段一/二可在无设置 UI 的前提下用调试开关独立交付验证；M4 依赖 M2 起可用（Mode B 为最小发布版本）。

## 10. 三期展望（不在本计划内）

- krkrsdl3 / ONS 的 SDL EGL hook（需 RE SDL 内部 `egl_data` 结构改函数指针，另行立项）
- Tyrano 路径不可行（系统 WebView 合成器，应用层无法插入）
- 外置 APK 引擎模块化的超分配置分发
