# VN-SR 滤镜集

> **更新（当前实现）**：Kirikiroid2 1.2.6 内核及其画质滤镜链（原始帧滤镜桥接）支持已彻底移除；当前随包与画质滤镜支持版本为 auto / 1.3.9 / 1.3.4，以及 krkrsdl3 内核。

VN-SR 分类提供 C08 / C12 / C16 三种通道规模，均为原生 **2X**。
预设名称、分类、固定紫色色条和执行约束来自独立 `engine/src/main/graphicsfilters/vnsr.json`；
构建生成的目录将它们提供给既有设置菜单、尺寸规划器、GLES执行器和性能分析器。

## 新增来源和许可证

直接引用 VN-SR 0.1.0 项目的三份 GLES3-MRT 离线融合导出着色器（`shaders/gles-mrt/`），来源项目明确将代码、权重和生成的VN-SR着色器采用MIT许可。
完整版权声明与MIT文本随资源保存在 `engine/src/main/assets/vnsr/LICENSE-MIT`。
上游保留导出文件存于 `engine/patches/vnsr/sources/`，精确来源及打包文件SHA-256见
`engine/patches/vnsr-filters.json`。公开来源仓库为 [AAAA1111-Lab/VN-SR](https://github.com/AAAA1111-Lab/VN-SR)，
固定 revision 为 `4a9896612874ebfdc2a7393864445af32818cf4e`。三份 GLES3-MRT 导出及 MIT 许可证均已与该提交逐字节核对，SHA-256一致。
此核对确认所保留文件与公开版本一致，不替代设备兼容性或模型效果验收。

普通构建按清单中仓库根相对的 `sources[].files[].localPath` 校验已保留来源文件的SHA，缺失、修改或越出engine目录会失败；
VN-SR 声明 `verification: retained`，来源文件和许可证必须提供localPath并通过SHA校验；纯外部来源记录仍只作描述。
`license.path` 指上游路径，`license.localPath` 指仓库保留文本；生成器固定MIT标识、`license.path`、来源 `repository`/`revision`/`version`、`assetDirectory`、来源数量及清单内记录哈希，任一漂移都会使 `--check` 失败。两者的校验边界明确划分：`tools/generate-vnsr-filters.py --check` 作为专有校验/发布脚本严格固定唯一的已保留来源条目、`license.path` 与所生成记录哈希；而 Gradle 的 `VerifyGraphicsResources` 任务负责通用的资源贡献清单结构合法性校验，对各滤镜贡献中可能追加的纯描述性外部来源保持兼容。

## 宿主适配与上游 MRT 适用范围

直接引用上游仓库 `shaders/gles-mrt/` 版本结构（由上游 `python -m vnsr.export --target gles-mrt` 导出，保持 `shaders/gles/` 与 `shaders/mpv/` 向后兼容）：原尺寸RGBA4特征、四个部分更新块、12通道RGB残差及2X重建。
C24/C32 为本 PR 未引入的通道规模（不打包其资源或保留其生成输入）；C24/C32 未引入属本 PR 的范围与包体控制决策，如需其它规模应按同一描述符规范另行贡献。

`shaders/gles-mrt/` 在上游即基于 OpenGL ES 3.0 MRT（Multiple Render Targets）将共用输入的相邻卷积 Pass 进行层内融合，下游 `Tyranor-Next` 直接按 `retained` 校验并原样随包发布，**适用范围与运行时约束如下**：
- **前置能力要求（两项独立门禁）**：仅适用于同时满足 **OpenGL ES 3.0+ MRT 能力** 与 **`GL_RGBA16F` 颜色缓冲可渲染能力** 的设备与宿主管线：
  1. **GLES 3.0 MRT 容量**：支持 `glDrawBuffers` / `layout(location = k) out vec4 fragColor{k}` 且正确解析多 `//!SAVE` 声明；C08/C12 至少要求 `GL_MAX_DRAW_BUFFERS` 与 `GL_MAX_COLOR_ATTACHMENTS` ≥ 3，C16 至少要求 ≥ 4（上游 C24/C32 分别需 ≥ 6 / ≥ 8）。
  2. **`GL_RGBA16F` 颜色附件渲染**：在 OpenGL ES 3.2 及以上上下文中，半浮点颜色可渲染性由核心能力直接支持（无需驱动额外报告扩展）；对于 GLES 3.0 / 3.1 等较早上下文，设备驱动须在 `CnnPipeline.halfFloatRenderable()` 检查中报告 `GL_EXT_color_buffer_float` 或 `GL_EXT_color_buffer_half_float` 扩展以承载半浮点特征纹理与帧缓冲附件。
- **不支持硬件的降级行为**：GLES 2.0、上述两项任一能力缺失或仅支持单目标的播放器（如 `mpv`）不适用该资产；本宿主在 `CnnPipeline` 运行前逐项校验 MRT 容量与 `GL_RGBA16F` 可渲染性，任一不满足即通过 `TargetUnavailableException(retryAfterDelay=false)` 安全旁路，避免渲染损坏。
- **输入层卷积融合**：输入特征层（C16 为 4 目标、C12 为 3 目标、C08 为 2 目标）均输入自 `MAIN`。融合为单次 3×3 邻域采样并行输出，将输入层卷积采样次数分别降至 9 次（C16：`36 → 9`，减少 75%；C12：`27 → 9`，减少 66.7%；C08：`18 → 9`，减少 50%）。
- **残差更新块（Block 0..3）融合**：每个残差块的所有更新输出通道（C16 为 3 目标、C12 为 2 目标、C08 为 1 目标）共享同一组输入特征包。单 Pass 融合后每个残差块仅需遍历输入纹理一次（C16：`108 → 36`/块，减少 66.7%；C12：`54 → 27`/块，减少 50%；C08：`18 → 18`/块，减少 0%，统一结构复用寄存器与坐标）。
- **输出层卷积融合**：3 个残差输出通道（`vnsr_output_0..2`）融合为单一 MRT Pass（C16：`108 → 36`，减少 66.7%；C12：`81 → 27`，减少 66.7%；C08：`54 → 18`，减少 66.7%）。
- **整链 Pass 压缩与纹理提取削减**：
  以下数字按**各 Pass 着色器静态采样调用数之和（未按输出分辨率加权）**统计（含 Pass 6 最终 2× 重建 4 次 调用）：
  - C16：Pass 数量由 `shaders/gles/` 的 20 降至 7；各 Pass 静态采样调用数之和由 580 次降至 193 次（前 6 个 1× 卷积 Pass 的 `sample_k` 576 → 189，降幅 67.2%）；若按源像素统一折算 2× 重建的 $4 \times 4 = 16$ 次采样，C16 理论采样数由 592 降至 205。
  - C12：Pass 数量由 `shaders/gles/` 的 15 降至 7；各 Pass 静态采样调用数之和由 328 次降至 148 次（`sample_k` 324 → 144，降幅 55.6%；按源像素统一折算为 340 → 160）。
  - C08：Pass 数量由 `shaders/gles/` 的 10 降至 7；各 Pass 静态采样调用数之和由 148 次降至 103 次（`sample_k` 144 → 99，降幅 31.3%；按源像素统一折算为 160 → 115）。
  - 以上均为源码级静态计数口径，不代表真机 GPU 实测性能或显存带宽。
- **融合等价性与分级精度边界**：上游在针对 `shaders/gles-mrt/` 与单目标 `shaders/gles/` 的合成 CPU FP64 数值对比中验证残差与最终输出最大误差为 `0.0`（属特定合成验证口径，不作普遍恒等推广）；在本仓库内，两项 CPU 级验证分别对应不同基线与覆盖范围——Python `tools/test-vnsr-coordinates.py` 以 **FP64** 在 8×8 合成输入样本上比较 `engine/patches/vnsr/sources/` 顶左上游源码与运行时 `flip_coordinates=True` 底左坐标适配后的输出，残差误差为严格的 `0.0`、双线性重建误差 $\le 2.22\times 10^{-16}$（含逐像素 8-bit 量化一致）；JVM `CnnPipelineTest` 则以 **随包 MRT 资产本身** 为基线、以 **FP32** 比较运行期代码生成优化（P3-B）前后的 3×3 卷积窗口输出，并单独解析校验 Pass 6 四象限重建相位与残差路由。在 GLES 运行时，`CnnPipeline` 对卷积乘加段切换为 `mediump`（坐标与采样严格保持 `highp`），中间目标存储保持 `GL_RGBA16F`（多层卷积及 RGB2× 重建后的 VN-SR 设备端 `mediump` 最大量化误差尚待专门实机验证，不得直接由 Anime4K binary16 仿真结果推断）。可通过 `CnnPipeline.setTieredPrecisionEnabled(false)` 关闭卷积 `mediump` 回退 `highp`，但须在管线加载 pass 前调用（已加载管线须重建后生效），且该开关不会改变 `GL_RGBA16F` 中间纹理存储格式。

旁路特征继续引用上一层纹理，不增加复制绘制；
`tools/generate-vnsr-filters.py` 直接校验并原样发布来自上游 `shaders/gles-mrt/` 的 MRT 着色器；描述符声明顶左逻辑坐标，
由[公共方向接口](graphics-filter-coordinates.md)同时适配采样与PixelShuffle相位，避免资产预翻转。
保留系数、偏置、激活、旁路绑定和双线性基图加RGB残差。不混用mpv版本的显式2X尺寸表达式。
中间特征沿用RGBA16F，原生2X后由共享显示路径执行默认Resize；
纹理、内存预算、分块和回退继续由既有执行器处理，各引擎沿用原有能力门禁。

## 运行效率与代码层架构演进

### 已落实的架构与管线优化基准

在移动端 GPU（如 Adreno / Mali）上，VN-SR 已在 GLES 3.0 与 CnnPipeline 专用管线上建立起高吞吐的优化基线：

1. **GLES 3.0 MRT 原语级 7-Pass 融合**
   - 原生离散结构（C08/C12/C16 分别包含 10/15/20 个 Pass）已通过 GLES 3.0 MRT 重构融合至统一的 7 个 Pass；
   - 共享输入特征的相邻卷积 Pass 合并，片元寄存器级复用采样抽头，静态卷积纹理采样大幅削减 30%~66.7%。
2. **P1: 分级 `mediump` 精度重构（允许移动 GPU 选用较低精度 ALU）**
   - 片元着色器头部统一以 `precision highp float;`、`highp int` 与 `highp sampler2D` 起手，对 UV 坐标、视口与纹理尺寸、倒数向量（`u_*_pt`）及采样器实施 `highp` 强类型保护以确保高分辨率寻址零漂移；仅在检测到非注释卷积调用（`mat4(` / `mat3x4(`）时，于 `pass.body` 前切换为 `precision mediump float;`，在源码语义上允许驱动/GPU 将卷积乘加降为较低精度执行（GLSL 规范中 `mediump` 仅规定最小精度下限，是否真正以 FP16 执行或获得吞吐增益取决于具体 GPU 与驱动，仅对实测机型有效）。
   - **2xFP16 双份 CPU 仿真实现的共同阈值来源**：`2xFP16` 的数值验证由两份独立 CPU binary16 仿真模型共同支撑（用于建模精度边界而非保证 GPU 驱动逐操作 `mediump` 舍入）——JVM 侧 `CnnPipelineTest.tieredFp16AccuracyAcrossAllSixAnime4kRestoreCnnModels`（含纯 Java IEEE-754 binary16 舍入仿真）与离线逐层 `tools/test-anime4k-coordinates.py` 仿真（SPEC §12 记载的验证口径）；两者运行环境不同、有意不合并，但必须共享 SPEC §12 冻结的同一组阈值（Anime4K 模型下逐像素 8-bit 误差 $\le 1$ LSB、相对 FP64 的 PSNR 下限 60 dB；实测带按实现分别记录——Python 离线夹具为 71.10–74.75 dB，JVM 夹具实测带见 `CnnPipelineTest` 注释），任一侧不得私自放宽或另立阈值。
3. **P3-B: 运行期着色器指令流水优化 (Codegen)**
   - **Pass 0 `mat3x4` 零列剪裁**：识别 RGB 输入 Alpha 列权重为 0.0，改写全部 `mat4` 为 `mat3x4(...) * _sK.rgb`（C08 为 18 处、C12 为 27 处、C16 为 36 处），削减 Pass 0 中对应 Alpha 列的全零乘加开销；
   - **9 抽头共享坐标预计算**：确认 Pass 内全部绑定纹理共享分辨率后，在片元入口一次性预计算 9 抽头局部坐标并跨绑定通道共享，C16 Passes 1..5 单 Pass 至多节省 $36 - 9 = 27$ 次重复步长乘加（Pass 0 仅 9 抽头，节省 0 次）；
   - **3 抽头水平扫描行突发预取**：按扫描行预取合并 L1 Cache Line 采样，隐藏纹理延迟并将活跃寄存器严格压制在 3 个 `vec4` 暂存槽。
4. **宿主脏帧静态复用与统一执行器**
   - **统一执行器**：原 `Anime4kPostProcessor` 与重采样/CNN 逻辑已统一收口至 `CnnPipeline`，保持精简的无环分层与静态状态隔离；
   - **宿主脏帧静态复用（Kirikiroid2 1.3.4 / 1.3.9 与 Artemis 单上下文 GLES3）**：原生桥跟踪 `drawBufferUpdated` 或 Host GL 指令流哈希并向 `GraphicsFilterPipeline` 传入 `frameDirty`（Artemis 另要求帧开始对逻辑默认帧缓冲先完成全表面 `glClear` 颜色清除，否则判为依赖前帧内容而 `frameValid=false`）；`frameDirty=false` 且输入纹理、几何、裁剪与行序命中缓存时，由 `GraphicsFilterPipeline.renderTexture(...)` / `renderCroppedTexture(...)` 跳过 CNN 与重采样绘制并直接复用 `lastCachedOutput`（若开启性能分析且存在有效 GPU 计时器，仍可调用 `timer.poll(stats)` 轮询已有计时查询，不保证完全零 GL 调用；其余引擎全量直通执行，帧判定由宿主侧命令流/标志推导）。

### 长线前沿探索方向

在此基线之上，面向极限能效比与未来硬件架构的长线探索方向包括：

1. **Tile 片上内存复用与 Subpass / Framebuffer Fetch（显存带宽瓶颈）**
   - 当前管线每个**中间卷积 Pass**均输出到独立的 `GL_RGBA16F` 中间 FBO（最终 RGB2× 重建写入的 `mRestore` 与分块重建写入的 `mTileRestore` 均按 **RGBA8** 分配，不计入 `GL_RGBA16F` 写入量），高频 FBO 切换会导致移动 GPU 在 GMEM（片上缓存）与 DDR（系统显存）之间频繁发生 Flush 和 Resolve。
   - 探索方向：基于 Vulkan Subpass 或 GLES 扩展（如 `EXT_shader_framebuffer_fetch` / Pixel Local Storage），将相邻卷积层的中间特征保存在片上缓存直接传递，消除中间纹理的物理显存读写往返。

2. **GLES 3.1 Compute Shader + 片上共享内存 (LDS) 协作瓦片管线**
   - 探索方向：将全屏片元 Blit 转换为 OpenGL ES 3.1 Compute Shader，利用工作组片上共享内存（`shared mediump vec4`）协作加载消除重复外部纹理采样，并探索两级卷积 Pass 的片上融合，彻底打破 TMU 吞吐与系统内存墙瓶颈。

3. **端侧专用推理引擎与 NPU 硬件接入（架构级路线）**
   - 探索方向：引入专用轻量端侧推理引擎（如 NCNN / MNN / QNN），基于 `AHardwareBuffer` 零拷贝将前馈网络直接交由移动端 NPU 硬件执行，实现能效比与帧率的跨越。

生成器仅依赖Python标准库，先核对固定来源与许可证SHA，再生成宿主文件：

```sh
python tools/generate-vnsr-filters.py
python tools/generate-vnsr-filters.py --check
python tools/test-vnsr-coordinates.py
python tools/test-vnsr-generator.py
```

普通Android构建直接使用已打包资源，不读取权重或执行Python，也不下载任何模型。
VN-SR 滤镜贡献由其着色器资产（`engine/src/main/assets/vnsr/`）、描述符与来源清单（`engine/src/main/graphicsfilters/vnsr.json`、`engine/patches/vnsr-filters.json`）及离线生成校验脚本（`tools/generate-vnsr-filters.py`）独立构成；`CnnPipeline` 与 `GraphicsShaderTransforms` 为所有 mpv-hook 着色器（含 Anime4K 与 CAS）共用的底层执行与变换管线，若仅需移除 VN-SR 贡献，只需删除上述 VN-SR 资产、描述符清单与生成脚本（以及 VN-SR 专用测试与验证入口 `tools/test-vnsr-coordinates.py`、`tools/test-vnsr-generator.py`，还有 `CnnPipelineTest` 中 `VNSR_ASSETS` / `ALL_CNN_ASSETS` 内的 VN-SR 条目与 VN-SR 专用测试用例，`MpvHookShader` 与 `GraphicsShaderTransforms` 中的 VN-SR 专属识别/代码生成分支，同时保留 Anime4K、CAS 及共用 `CnnPipeline` 执行器的回归覆盖），无需回退共享的 `CnnPipeline` 执行器；保留 VN-SR 时应继续在构建与 CI 中运行上述 VN-SR 专用测试。

## 验证边界

本轮执行的验证为静态与CPU/JVM级别：来源与许可逐字节SHA核对、三档残差与最终合成坐标等价（CPU回归阈值为1e-10，当次运行残差误差为0.0、最终合成误差为2.22e-16，另含最终合成的逐像素8bit量化一致独立断言）、
Anime4K六份Restore导出的CPU方向负对照、生成器与来源清单篡改负例、`texture_row_origin`编译期断言、
资源来源回归、图形专项JVM测试、Debug宿主native构建与上游CI检查。
未执行完整Release/R8 APK构建，也未执行任何实机验收；早期提交者“运行测试通过”的反馈是历史结论，
对应早期提交的构建，不作为本轮验收证据，不借用早期其它模型的测试结果，
也不将来源项目桌面GPU成绩作为移动设备性能承诺。提交者未逐项提供设备型号、系统、运行库身份、真实样本、GPU用时和单模型数据。
方向变换的依据是上游 `src/vnsr/export.py` 将权重的行偏移直接写入采样偏移，
`model.py` 则按顶左NCHW进行卷积和PixelShuffle。底左输入需同时适配采样Y及2×2相位，顶左输入则不翻转；
不能仅由Anime4K的另一份导出写法推断两者具有相同训练/导出坐标约定。
离线非对称卷积/相位回归只检查这一坐标变换恒等式，不替代真实模型的GPU golden对比。
因此不宣称完成跨设备兼容性、压力下预算回退、独立Y方向数值核对或帧时间专项验证。
静态资源校验和运行时保护不等同于这些设备专项验收。
本次验收未修改应用包名、版本或签名的仓库配置；本地测试构建通过Gradle属性使用独立包名。
CI与PR中的验证命令及链接单独记录；没有新增或更换任何原生引擎运行库。

### 宿主脏帧静态复用与 Adreno 840 实机复核（2026-11-18）

> **时效说明**：以下 `Adreno (TM) 840` 真机样本与离屏微基准均采集于 2026-11-18 整理重基之前，不直接代表当前最终提交在 Siglus 与 `840` 上的回归结果；四期审改收口后的当前修订真机重跑见下文 `Adreno 740` 节。

将执行器统一收口至 `CnnPipeline` 并启用分级精度、运行期 MRT 融合与宿主脏帧静态复用后，基于 `Adreno (TM) 840` 完成真机样本与离屏微基准复核：

- **真机样本（`VNC16 (2X) → 2133×1200`）**：Siglus（`anemoi`，`1080p` 动态/字幕场景）稳定 `55–60 FPS · GPU 9.9–11.6 ms`；Artemis（`ATRI`，`720p` 单上下文 GLES3 + 宿主脏帧静态复用）动态 `52–58 FPS / 10.1–12.2 ms`、静态稳态 `60 FPS / GPU —ms`；Kirikiroid2 1.3.9（`9-nine-`，`720p` + 宿主脏帧静态复用）动态 `60 FPS / 4.4–11.1 ms`、静态稳态 `60 FPS / GPU —ms`。
- **离屏精度 / 融合优化验证**：`vnsr-c16-x2` 上 `720p→1440p` 从 `raw_fp32`（`gpuMean=16.016 ms`）降至 `p3b_fp16`（`gpuMean=10.957 ms`，`-31.6%`），`1080p→2160p` 下 `p3b_fp16` 为 `gpuMean=13.454 ms` / `wallMean=17.041 ms`；`Anime4K_Restore_CNN_L` 在运行期 MRT 融合 + CReLU 去重（`9 → 5` passes）后 `720p→1440p` 由 `14.701 ms` 降至 `9.822 ms`（`-33.2%`）。
- **单元测试回归**：`.\main\gradlew.bat -p main :engine:testDebugUnitTest --tests "com.core.gl.*" --no-daemon --console=plain` 全量通过。

### Adreno 740 当前修订实机复核（2026-11-21）

在完成四期审改与 `Z-R3p4-1` 注释口径收口后，使用隔离 Debug 包（`com.tyranor.next.glverify`，测后卸载清理）于 `SM-X710`（Qualcomm `Adreno (TM) 740`，Android 15，OpenGL ES 3.2 `V@0676.71`）完成真实游戏复核：

- **Kirikiroid2 1.3.9（`libgame.so`，`9-nine-九次九日九重色`，`1280×720 → VNC16 (2X) → 2560×1440`）**：`CnnPipeline` 构建 7 pass（`texture bytes 66355200`），WARNING 静帧 `60 FPS / GPU 12.9 ms`、主界面飘花 `59–60 FPS / GPU 8.3 ms`、新游戏弹窗 `61 FPS / GPU 6.4 ms`、对话场景 `59–62 FPS / GPU 6.8–11.0 ms`，渲染正常且零 GL/JNI 错误。
- **Kirikiroid2 1.3.4（`libgame134.so`，`9-nine-九次九日九重色`，`1280×720 → VNC16 (2X) → 2560×1440`）**：补齐 `1.3.4` 实机空白，`CnnPipeline` 构建 7 pass（`texture bytes 66355200`），WARNING 页 `60 FPS / GPU 11.4 ms`、主界面 `59 FPS / GPU 7.5 ms`、Config 页面 `62 FPS / GPU 7.4 ms`，渲染正常且零错误。
- **Artemis 官方单上下文 GLES3（`ArtemisActivityV1`，`HENPRI`，`1600×900 → 2560×1440`）**：日志实证 `Official stage hooks registered`、`Upgraded Artemis EGLContext to GLES3`、`Single-context GLES3 window created: screen=2560x1600 stage=1600x900`；其中 **`VNC16 (2X) + Resize (0.8X)`** 构建 7 pass（`texture bytes 103680000`）运行于 `62 FPS / GPU 12.7–13.1 ms`，**`Anime4K_Restore_CNN_L + Resize (1.6X)`** 运行期 MRT 融合成功降为 5 pass（`texture bytes 51840000`）且无回退或 GL 错误，实测 `60–61 FPS / GPU 10.7–12.1 ms`。
- **仍未覆盖范围（暂无测试条件）**：非 Adreno GPU（Mali 等）、Siglus / krkrsdl3 当前修订真机重跑、不支持 `GL_RGBA16F` 与 GLES 3.0/3.1 `GL_EXT_draw_buffers_indexed` 旧驱动分支。
