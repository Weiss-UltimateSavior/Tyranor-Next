# 共用画质滤镜链

> **更新（当前实现）**：Kirikiroid2 1.2.6 内核已从随包运行库中移除，本文涉及 1.2.6 的适配、身份哈希与显存预算仅作历史记录；当前随包版本为 auto / 1.3.9 / 1.3.4，以及 krkrsdl3 内核。

## 发布说明：画质增强入口与支持范围

- 全局和单游戏的单模式“画面超分”入口由“画质滤镜链”的有序滤镜链替代；原有 Anime4K 配置只读迁移，原偏好不删除。
- 随包支持 **krkrsdl3 OpenGL** 的尺寸限制舞台输出，并保留 **Kirikiroid2 1.3.4 / 1.3.9**（Kirikiroid2 1.2.6 已移除，详见本文顶部「更新（当前实现）」说明）。不恢复旧屏幕级 Anime4K，不将缩放后的屏幕帧冒充原始输入。实现、二进制身份和验证边界见 [KR 接入报告](krkr-local-raw-filters.md)。
- 设置页按实际内核及渲染器判定能力；krkrsdl3 软件渲染仍标为不支持滤镜，已有自定义链和分析器偏好继续保存。SDL3 的能力不受 Kirikiroid2 版本选择影响。
- 自研 Artemis 的画质增强接入待 artemis-compat 上游合并。官方 Artemis 支持范围见下表；不能将所有 Artemis 版本统称为 V1–V6。
- `AGENTS.md` 已完整恢复为 PR 基线；输入兼容、无图标入口和弹窗取色仅在实际代码处说明，不修改项目规范。krkrsdl3 OpenGL 接入与 Kirikiroid2 1.2.6 移除后的清理已按维护约定整理；无图标跳转入口仍待维护者批准，局部注释不等同于规范豁免。

## 功能与设置

全局和单游戏使用同一套有序滤镜链，最多八个节点；可添加、移除、上下移动和长按拖拽排序。单游戏切换跟随全局时保留自定义链，切回可继续使用。菜单按算法分类，带多个预设的分类提供二级选择，单个滤镜直接添加。界面继续使用项目公共组件和 Miuix；弹窗复用 `AppAlertDialog`、`SlidingTabRow`、`AppSearchField` 与 `DialogMiuixSlider`。后者是 Miuix 0.9.2 无法关闭拇指按压动效的兼容适配，交互由外层承接以遵守弹窗无反馈规范。滤镜行操作项将缩放设定（支持缩放调节时显示）与参数设定（支持算法参数调节时显示）解耦为独立弹窗，操作按钮采用圆角线框样式，并在删除按钮与移动按钮间以短竖线分割。

适应缩放按照输入比例计算当前屏幕内可容纳的最大尺寸；固定原生倍率的模型保留训练输出倍率，超出屏幕时由显示层 Resize 等比适配。自定义缩放仅提供一个 0.25–4 倍的等比倍率；旧版横纵不同的配置按较小倍率统一，沿用已有存储和启动协议字段。CAS 额外提供锐度参数（缺省 0.40）；Bicubic 拆分为独立的 B 与 C 双参数调节（缺省 Mitchell 基准 B=0.33, C=0.33）；Lanczos 提供独立的抗振铃强度滑杆（缺省 0.50，可抑制高对比文字与边缘振铃，设为 0 时关闭抗振铃处理）。抗振铃把重采样结果钳制到采样窗内输入值的范围再按强度混合，放大与缩小同样生效，避免缩小高对比图案时对比外扩或细线压暗。Bilinear / Bicubic / Lanczos 为纯重采样节点，按性能开销排序，可作 CNN 滤镜在性能预算不足时的轻量放大替代；输入与目标 1:1（几何无变化）时这些节点自动跳过，显示层 Resize 行为不变。

性能分析器使用顶部或底部居中的单行条：GPU / 帧率与输入尺寸 → 带简称、非 1X 倍率 `(2X)` 和 GPU 用时的滤镜色卡 → 必要时灰色 Resize 色卡 → 最终显示尺寸。仅在滤镜启用且所选内核支持时可选；数据刷新间隔使用预设菜单，默认两秒。颜色来自各滤镜定义，与排序无关；蓝色用于线条重建，紫色用于 VN-SR，绿色用于降噪，橙色用于锐化，棕色用于重采样三件套（Bilinear / Bicubic / Lanczos），灰色 Resize 表示未计时的默认显示处理。未提供可靠 GPU 时间或运行出现异常时显示缺失值，不代用 CPU 提交时间；任何宿主在无新 GPU 样本且状态/计时正常地刷新区间内，性能分析器保持显示上次有效的 GPU 计时统计值而不刷新为缺失值（`—`），仅在输入或输出尺寸、节点拓扑改变，或出现状态失败、尺寸失效、计时查询异常或管线重置时清除并显示缺失值。单节点及总 GPU 耗时丢弃超过 1000 ms 的异常数据，不计入均值或清空已有正常样本；汇总超过同一上限时显示缺失值。计时失效事件仍清空对应样本，零值与 1000 ms 边界正常统计。

## 引擎接入与支持范围

### Kirikiroid2

保留项目随附的引擎二进制。宿主根据实际加载符号所在 ELF 的身份核对已知内核，并在合成纹理进入屏幕缩放前通过桥接执行滤镜；输入尺寸取实际纹理，不按屏幕尺寸推算。无法确认入口时保留正常呈现，不将屏幕尺寸伪装成原始输入。不同游戏的舞台尺寸可能是 720p 或 1080p；这些数值不代表单张背景素材尺寸。

历史版本中 Kirikiroid2 1.2.6 曾使用独立的公共 Cocos 接口适配（现已随 1.2.6 内核一并移除）：其 `UpdateDrawBuffer` 参数与 1.3.x 不同，且关键绘制调用没有可替换的 PLT 入口。当时适配器在可信的单个窗口舞台上处理纹理，通过引擎自己的 `Texture2D` 暂借处理结果，待原生渲染队列完成后还原纹理及 UV；不更改舞台几何或触摸映射。旧屏幕级 Anime4K 入口仍不执行。

当前随包支持 Kirikiroid2 1.3.4 / 1.3.9（1.2.6 已移除）；自动选择走同样的受支持路径，设置与启动使用同一库名门禁。启动阶段只规范化库路径并核对库名，不重新读取整库计算 SHA-256；GL 上下文建立后由 native 核对已加载映射的 GNU Build ID、符号、虚表及重定位入口。ELF note 仅从可读加载段读取，并检查地址、对齐和长度边界。这是兼容版本识别，不是文件完整性校验；外置插件安装的 ZIP SHA-256 校验保持不变。原生桥（`krkr_raw_frame_bridge.cpp`）持续追踪 `drawBufferUpdated` 并向 Java 侧传入 `frameDirty`（宿主脏帧静态复用）：仅在 clean 帧（`frameDirty=false`）且命中有效输出缓存（输入纹理名、源尺寸、绑定尺寸、裁剪 UV 与物理行序均未变化）时，`GraphicsFilterPipeline.renderCroppedTexture` 跳过 CNN 与重采样绘制直接复用 `lastCachedOutput`（但若性能分析器存在有效 GPU 计时器，仍可调用 `timer.poll(stats)` 查询已有计时样本，不保证零 GL 调用）；缓存未命中时仍须重新执行滤镜——若仅裁剪 UV 改变且源尺寸、绑定尺寸及裁剪缓冲字节数（计划输入）未变，则沿用现有计划；只有计划输入变化等条件时才触发重新规划。

### krkrsdl3（OpenGL）

只支持当前固定 OpenGL 库；软件渲染和未知库身份保留原路径。OpenGL 模式下适配器不替换 Live2D 使用的呈现 Hook，也不读取渲染后端的私有纹理句柄；在完整舞台合成期间，将默认帧缓冲重定向到按游戏逻辑像素尺度建立的画布。

画布保留物理窗口中可能出现的全部叠加内容，包括帧内新增或改变显隐的叠加层。内容均位于主舞台内时，滤镜输入通过整数裁剪恢复为主舞台尺寸；叠加内容延伸到主舞台外时，处理完整逻辑画布，分析器显示实际画布尺寸。两种路径均保留引擎原有的物理输入映射。

提交者已确认这两个新增接入的编译和实机验收通过；本次整合未重复实机测试。该验收不等于所有外部插件均已认证：绕过合成器、直接操作 GL 的插件仍不在保证范围，未知视口契约会停止滤镜处理。详见 [接入与审查报告](krkr-local-raw-filters.md)。

### Siglus

保持上游 main `740d7c13` 打包的 `libsiglus.so` 原文件。启动时根据生效开关和解析后的有效
滤镜链选定路径，同一游戏实例运行期间不迁移句柄或切换原生调用线程。

- **关闭滤镜、空链或无效链**：恢复上游 SurfaceView 与主线程同步 JNI/Choreographer 逻辑。
  创建、窗口重挂、resize、步进与输入在主线程执行；窗口销毁只停帧并保留游戏 VM，Activity
  销毁才销毁引擎。没有新增 TextureView、宿主 GLES 管线、私有 producer、显示拷贝或舞台配置
  读取任务；继续保留上游同步调用可能阻塞 UI 的既有特征。
- **开启有效滤镜链**：才创建 TextureView、私有 producer 和独立 engine/GLES owner。
  native 从创建开始只接触私有 producer，已接管的显示窗口使用有界租约并承担最终释放。
  不能将该线程持有的显示窗口重新混入上游 SurfaceHolder 路径。销毁已接管显示纹理时，先清框架帧监听器再归还 UI 租约，避免永久保留输出窗口时连带保留旧界面；不提前释放 EGL 所需资源。Android 16 的独立帧率监听器可能继续持有显示 View；因此滤镜显示使用应用 Context，Activity 终结时先正常移出父布局、再清宿主回调及窗口关联缓存，不通过隐藏 API 清监听器。

两种宿主共用进程级原生实例准入；未结束或永久阻塞的旧 owner 不允许另一模式启动第二个
原生引擎。消息框先异步投递主线程，校验当前 owner/句柄后处理，避免同步 native 回调中重入。
输入、IME、标题回写和应用设置协议仍在同一 Activity 复用，不复制整份宿主页面。

滤镜路径在 create 成功后后台只读可选 `key.toml` 和首启动实际选中的 Gameexe 配置，取得最终
`SCREEN_SIZE`；需要游戏密钥的模式缺 key 时拒绝，明文或固定 mask 模式不强制 key。
通过现有 `set_surface/resize` 请求同尺寸输出后执行共享滤镜链。舞台候选交接前先由旧 GLES
owner 确认断开显示，再由 engine owner 确认替换私有 producer，最后连接当时最新的显示窗口。
生命周期控制不占普通输入配额，Ready/首帧回调核验世代，原先三项 P2 修复继续适用于滤镜路径。

仅已启用的滤镜路径增加 GLES3/external OES 依赖及拷贝开销。缺少可信配置时保持该路径的
私有启动拷贝，不在运行中退回原版或迁移句柄线程；启动拷贝尺寸不标为原始舞台。
启动消费者不可用时提示初始化失败并结束；未交给 native 的舞台候选准备失败可继续健康的
启动拷贝。永久驱动失败仅保留有界资源，不承诺自动恢复。

宿主不恢复密钥、读取 EXE/汉化 `.chs`，不写游戏目录，也不猜默认舞台尺寸。
尺寸来自配置和原接口契约，不宣称回读库内部 viewport；不重编引擎、不新增舞台 ABI。
详见 [原库接入说明](../engine/patches/siglus/README.md)。本轮没有新的实机验收。

### 官方 Artemis

采用尺寸限制舞台输出路线；桥接使用宿主应用 ClassLoader 注册，设备重置后保留舞台输出尺寸。不会替换官方内核或修改其二进制。

宿主核对已加载 ELF 的 GNU Build ID 与已知库名，读取的 note 必须完整位于可读加载段中；所需符号、重定位入口和槽值仍逐项核验，身份或入口不匹配时保留原路径。这是默认打包版本的识别门槛，不是文件完整性校验，也不在启动主线程重新读取整库计算 SHA-256；外置插件安装的既有 SHA-256 流程保持不变。JNI 方法查找逐次处理异常；状态回报类和方法已取得时，注册失败通过该入口上报。取得回报入口之前的类加载等早退故障只能记录日志，不宣称分析器能显示全部安装故障。

| 用户界面版本名称 | 支持滤镜 |
| --- | --- |
| v1（Tyranor/Rev.2762） | 否：缺少所需输出入口 |
| v2（Tyranor/Rev.3049/emote） | 是 |
| V4（TyranorNext/Rev.3294/emote） | 是 |

> **历史内核记录（已移除）**：`v3（Tyranor/Rev.3201/emote）`、`V1（TyranorNext/Rev.2958）` 与 `V3（TyranorNext/Rev.3288）` 曾支持画质滤镜，但已随早先运行库精简自随包目录移除，当前安装包仅保留 `auto` / `v1` / `v2` / `V4` 对映版本。

启动、兼容回退与实际能力判断共用 engine 的 `ArtemisVersion` 映射表，库名沿用 `NativePluginConstants`。设置中的自动版本尚未解析游戏候选，显示“自动选择：滤镜支持取决于实际内核”并保留用户请求，不把 auto 预览成固定受支持版本；实际启动及每次回退仍按最终库严格门禁。内部版本值只是库选择标识，不能将用户界面版本统称为 V1–V6。自动回退到不支持的版本时不启用滤镜。尺寸可信前不统计为原始帧。

官方 Surface 注册表使用短临界区与共享状态所有权；每个 Surface 的 query / makeCurrent / swap / resize / destroy 串行化，允许符合 EGL 约束的线程迁移。锁顺序为状态操作锁 → Surface 生命周期锁 → 注册表锁，注册表锁不跨 JNI / EGL 调用，原 `Display` 调用前释放状态锁。直接输出恢复时保留引擎持有的 producer EGLSurface 活别名直至销毁，立即释放桥持有的 producer ANativeWindow 引用；不能留下已销毁句柄作为注册键。该策略避免句柄复用，但旧 EGLSurface 会存活到引擎销毁，厂商驱动行为及官方实际线程模型仍未实机验证。

直出恢复区分“完成、解绑待完成、失败”。worker 有限延迟导致解绑仍在排队时，保留 producer、EGL 上下文和注册别名，跳过当前 swap 而不向官方引擎返回失败；确认实际解绑后才连接屏幕，后续帧可继续恢复。真正解绑或直出创建失败仍返回失败，不以待完成伪装恢复成功。

注册失败原因在直出恢复后保留，只有新启动准备清除；重复安装已成功的桥不会重复核验改写后的 PLT。默认关闭滤镜时版本验证返回拒绝，不执行 PLT / 输入重定向；这条短路经过静态核对，实机检查仍属于下方验证边界。

单上下文 GLES3 路线的静态帧判定（`frameDirty`）按被 hook 的 GL 命令流哈希推导：帧开始需先经全表面清除（`GL_COLOR_BUFFER_BIT`、全通道写入与覆盖舞台区域的裁剪范围；启用深度/模板测试时同样要求对应的全表面清除否则 fail-closed 判脏），随后在被跟踪纹理单元的绑定与内容版本、绑定/状态类命令（含每次 `glClear` 实际生效的清除颜色/模板/深度清除值）、uniform 与 draw/clear 命令均进哈希；内容变更入口按 mutate 语义递增版本并进哈希，附件挂载（含重挂载与 `glFramebufferRenderbuffer`）与程序生命周期（`glDeleteProgram`/`glLinkProgram`）均纳入追踪，`glCopyTexSubImage2D` 在逻辑 framebuffer 0 仅惰性重定向绘制端时临时从阶段 FBO 读取并恢复原读取绑定，非 2D 纹理目标绑定与窗口内未知 FBO 目标绘制因无法纳入版本模型而保守判脏，删除类 hook 同步清理版本映射以避免驱动句柄复用污染。stage 窗口外的内容变更同样记账：blit、clear 与 draw 族对已知 RTT 目标递增版本、未知目标与清除状态/已挂钩 uniform/视口/剔除/卷绕/混合/`glEnable`/`glDisable`/模板/深度函数值变化走单调外部计数器，帧哈希仍是窗口内命令流的纯函数。安装期 fail-closed：强制 hook 全有全无安装（缺失项记 WARN 日志），且内核 PLT 导入任何「未挂钩内容变更/状态修改入口」（deny 集，共 117 项）即拒绝进入单上下文模式、回退 producer 路径并留 WARN 日志。受支持内核 `libartemis.so` / `libartemis-v6.so` 的导入面经 ELF dynsym 核对：其导入的内容变更与状态修改入口均已被 hook 且不在 deny 集内（deny 命中为 0）。强制/optional hook 与 deny 集的逐项清单、推导口径、无害豁免与残余覆盖姿态以 SPEC 第 12 节「宿主脏帧静态复用」条目及其指向的 `artemis_official_graphics.cpp` 安装表为唯一权威源，本节不重复维护逐项清单。判定判 clean 且缓存命中时，在连续复用上限（`MAX_CONSECUTIVE_STATIC_REUSES = 60` 帧）内跳过 CNN 与重采样绘制并复用上一帧输出，达到上限后强制重跑 1 帧自愈（开启性能分析且存在有效 GPU 计时器时仍可调用 `timer.poll(stats)` 查询已有计时样本；方向安全，不输出错误内容）。

自研 Artemis 的画质增强接入等待 [artemis-compat PR #11](https://github.com/Weiss-UltimateSavior/artemis-compat/pull/11) 合并。

### RealLive / AVG32 / UK2（CPU 帧缓冲模式）

这三个引擎不是独立引擎族：它们与 Siglus 共用同一 `libsiglus.so`（`game_launcher` 运行库），只是前端 API 不同——Siglus 走 wgpu 舞台，它们走 `game_fb_*` C ABI 逐帧输出 CPU RGBA8 帧。因此**滤镜设置与单游戏覆盖沿用 Siglus 的 `GraphicsFamily.SIGLUS`**，不另立偏好键。

滤镜模式下宿主把 CPU 帧拷进复用的直接缓冲（3 槽轮转，latest-wins），交给 `FramebufferGraphicsProxy` 在独立 GLES 线程执行共用滤镜链，`TextureView` 承接呈现。与 Siglus 舞台路线的差异由数据流决定：

- **无 producer Surface**：引擎不持有任何显示窗口，输入纹理用 `glTexImage2D` 直接上传。引擎帧自顶向下，上传后是顶左行序纹理，滤镜链经 `renderCroppedTexture(..., topLeft=true)` 保持物理行序，呈现着色器做一次 V 翻转；方向约定同[公共方向接口](graphics-filter-coordinates.md)。
- **启动期一次决定**：与 Siglus 宿主策略一致，严格解析 wire 协议，禁用、空链、无活跃节点或解析失败都保留经典 Bitmap 路径；链配置在启动后不迁移。
- **失败回退而非暂停**：Siglus 滤镜路径失败时保持私有 producer 暂停呈现；这三者的经典 Bitmap 路径不依赖任何外部资源，因此终端失败（EGL/GLES3 初始化、上下文丢失、交换失败、开始产帧后仍无任何帧呈现的看门狗超时）回退经典 Bitmap 呈现并释放滤镜资源；attach 良性失败（窗口销毁竞态）由宿主按 1Hz 重挂自愈，不升级为回退。看门狗判据是「最近一次成功 `eglSwapBuffers` 的时间戳」而不是「输出面还在」：接上了却一帧不出（帧被池门拒绝、同尺寸 OOM 闩锁、引擎停摆）同样会超时回退。另有 60s 启动最终期限兜底「引擎始终不产帧」；看门狗在首帧提交时武装、暂停期间撤销。滤镜节点级失败沿用共享执行器语义：按预算/分块降级为未滤镜呈现并在分析器如实上报（`SIZE_UNAVAILABLE`），不整体回退。
- **视口为纯等比 fit**：经典路径的「接近整数倍取整」不进入滤镜路径；呈现视口与触摸映射共用同一 `FramebufferViewport`，口径单一。
- **不改动运行库**：`libsiglus.so`、`games_bridge.cpp`、`NativeGames.java` 均保持原样；不涉及 PLT/vtable hook 与内核身份校验。
- 能力在设置与启动侧恒可配置（与 Siglus 共用 `GraphicsFamily.SIGLUS`）；GLES 3.0 不可用不是独立预判，而是 EGL/GLES3 上下文创建失败在宿主侧涌现为经典路径回退。EGL 清理共享 `EglTeardown` 不变式，重试按有界指数退避执行共 5 次尝试（4 次退避间隔：1s / 2s / 4s / 8s）；重试耗尽时按 `GraphicsProxyResourceSlots` 契约一律保留驱动句柄与资源名额至进程退出（绝不跨线程析构仍在被驱动引用的 `ANativeWindow`，也不提前归还名额）。进程容量为 2，即同进程最多两次永久清理失败耗尽名额，满额后后续会话直接降级直出，保留表天然以进程容量为上界、不会无界增长；`FramebufferGraphicsProxy` / `OnsGraphicsProxy` / `SiglusGraphicsProxy` 三份代理与 `ArtemisOfficialSurfaceProxy` 保持同一口径。

验证边界：本轮完成 JVM 回归（app 侧滤镜策略/能力/编解码 + engine 侧宿主策略/视口/帧槽池/代理租约用例）、Debug 应用编译、`:engine:lintDebug` 与 `:app:lintDebug`（全量任务因 untouched `org.libsdl.app32` 与本机 `local.properties` 以非零状态退出，按改动文件过滤结果为 **0 errors / 0 新增 warnings**，非全量 Lint 通过）与多轮静态审查；**未执行 Release 构建**；通过 ADB 在连接设备 `Samsung SM-X710`（Android 15 / API 35、`Qualcomm Adreno (TM) 740`、`OpenGL ES 3.2 V@0676.71`、2560×1600，`com.tyranor.next.test` `1.73`）上完成短实机测试：**AVG32《Kanon》**（输入 `640×480` → `VN-SR C16 × 2` + `Resize 0.83X` → 输出 `2133×1600`，`90 FPS` / `GPU 10.4ms`，首帧揭层并触控推至标题画面，无 `FATAL`）与 **RealLive《Little Busters! EX》**（输入 `800×600` → `VN-SR C16` + `Resize 1.33X` → 输出 `2133×1600`，`51–80 FPS` / `GPU 6.4–10.4ms`，首帧揭层并稳定进入首屏选择界面，无 `FATAL`）；**设备端无 UK2 样本，UK2 未实测**（运行库 `libsiglus.so` 与 `games_bridge.cpp` 未改动，不替代全量 RealLive / AVG32 / UK2 样本兼容性、长时间游玩或各分支回退路径的独立专项覆盖）。TextureView 销毁回调时序已按 AOSP 源码裁决（`ViewGroup.removeViewInternal` 对子 View 同步派发 detach；`TextureView.onDetachedFromWindowInternal → releaseSurfaceTexture` 同步回调 listener，返回 `false` 时框架不释放 SurfaceTexture，所有权移交客户端，与显示租约契约一致；本地快照位于工作区外的主仓 `D:/Tyranor-Next/local-notes/aosp-src/`（未随仓库分发，`ViewGroup-main.java:4323`、`TextureView-main.java:494`），经系统代理拉取的 AOSP main 分支），不再依赖实机碰撞。

### ONScripter

采用**方案 B（vendored SDL2 胶水代理路线）**接入共用画质滤镜链：

- **无 native hook 与重编**：不改动 `libonsyuri.so`、`libSDL2.so` 及其它依赖库；借助仓库内 vendored SDL2 Java 胶水层（`org.libsdl.app`），通过重写 `ONScripter.createSDLSurface()` 返回扩展类 `OnsSurface`。
- **1:1 舞台缓冲供给**：SDL2 在 `initSDL()` 读取脚本后调用 `SDL_CreateWindow()`，底层经 JNI `Android_JNI_GetNativeWindow()` 触发 `SDLActivity.getNativeSurface()` → `OnsSurface.getNativeSurface()` → `ONScripter.provideProxySurface()`，由 `ONScripter` 调用 `nativeGetWidth()` / `nativeGetHeight()`（未就绪时回退 `getLogicalWidth()` / `getLogicalHeight()`）取得脚本实际逻辑尺寸（例如 640×480 或 800×600）；`OnsSurface` 仅通过 `SurfaceProvider` 接口回调宿主，本身不调用 native 方法。代理 `SurfaceTexture` 以该尺寸为默认缓冲大小，SDL2 内部 render rect 完全充满缓冲（`x=0, y=0, w=width, h=height`），实现 1:1 无黑边渲染。
- **消费与滤镜管线**：`OnsGraphicsProxy` 在专用 GLES 线程（`ONS-GLES`）监听 `SurfaceTexture` 帧可用信号，将 OES 外部纹理标准化采样到 2D 纹理，执行共用画质滤镜链（`GraphicsFilterPipeline`），并通过 `TextureView`（`TextureDisplayWindow`）完成等比 fit 呈现。
- **触点映射**：`TextureView` 拦截触控事件，通过 `OnsViewport` 将物理触点精确换算为 `[0.0, 1.0]` 归一化舞台坐标，再派发给 `SDLActivity.onNativeTouch()`，保证在任意屏幕比例与黑边填充下触控点与画面元素像素级对齐。
- **回退与输入边界**：全局与单游戏 ONS 画质设置直接复用既有超分前后端，保留引擎原有 `--sharpness` 设置与传递，不增加互斥逻辑或额外 UI；任何 GLES 故障或资源配额耗尽时，代理通知宿主回退至经典 Surface 直出路径：先摘除 `TextureView`（同步销毁回调归还 UI 引用、由显示租约释放纹理，避免二次 `release()`），再由 `OnsDirectFallback` 执行 `onNativeSurfaceDestroyed()` → `onNativeSurfaceCreated()`（若直出 Surface 有效，让 SDL 重取直出 `ANativeWindow`；随包 `libSDL2.so` 反汇编确认 `onNativeSurfaceDestroyed` 会将 `native_window` 置空而 `onNativeSurfaceChanged` 不会重取窗口）→ `OnsSurface.restoreDirectGeometry()`（将触点归一化分母与 SDL 逻辑分辨率一起写回**物理窗口尺寸**，取自 `surfaceChanged` 记录的物理值；回退后不再走 `SDLSurface.surfaceChanged`，不写回则触点整体偏移且无法自愈）→ `onNativeSurfaceChanged()`，最后释放代理。回退本身也是加载层的揭层点：滤镜失败、输出超时、或滤镜模式 60s 内始终没有首帧（OP 视频播放期间顺延并在播片结束后重置完整 60s 窗口），都先回退再 `reveal()`；不会停在永久加载层，也不会弹出不可关闭的「启动失败」面板（`fail()` 只留给滤镜与直出都救不回的终端失败）。ONS 看门狗分两阶段：stage 未配好或 OP 视频播放期间不计时；stage 就绪后、首帧呈现前按 10s 期限判活；首帧呈现后改判「输出面还在」，稳态下的瞬时断连从断连那刻重新起表（1Hz 重挂）。触点按 `(deviceId, pointerId)` 跟踪已派发 DOWN 及各自最后坐标（失视口吞掉 POINTER_DOWN 的后续 MOVE/UP 不发送，多指合成 UP 仅使用抬起手指自身坐标），鼠标释放保留真实 `0` 按钮掩码，滚轮与压力均过滤非有限值。直出回退的完整 SDL 序列已完成随包 `libSDL2.so` 反汇编确认与 JVM 编排测试，**设备触发看门狗回退仍待真机实测**。

验证边界：完成 JVM 单元测试回归（宿主策略、视口 letterbox 计算与触控/滚轮映射、窗口引用计数生命周期、App 侧能力/编解码/覆盖分区/启动参数）、Debug 构建与代码静态审查；通过 ADB 在连接设备 `Samsung SM-X710`（Android 15 / API 35、`Qualcomm Adreno (TM) 740`、`OpenGL ES 3.2 V@0676.71`，`com.tyranor.next.test` `1.73`）上完成 **ONScripter《美少女万花镜1》** 短实机测试：`OnsSurface` 拦截 `surfaceChanged`（`physical=2560×1600`），代理从初值 `640×480` 动态切至游戏原生舞台 `800×450`（`120Hz`），`VN-SR C16 + Resize (1.6X)` 输出 `2560×1440`（`10 FPS / GPU 6.5ms` → 稳定菜单 `245 FPS / GPU 3.0ms`），首帧揭层且点击舞台顺利推进至 `START/LOAD/OPTION` 标题菜单，0 `FATAL`（`libonsyuri.so` / `libSDL2.so` 未改动；ONS 看门狗真机回退、长时游玩及非 Adreno GPU 兼容尚未实测）。

## 独立滤镜贡献

`engine/src/main/graphicsfilters/*.json` 是各贡献的注册来源。构建生成纯 Java 目录，供协议、尺寸规划、GPU 执行、设置适配和性能色条读取。每个可选贡献独立携带稳定 ID、三语言菜单、简称/颜色、参数能力、原生倍率、内部输出限制、分块能力及旧配置迁移，移除该贡献不需要编辑共享枚举或名称映射（例外：VN-SR 的多 Pass 卷积终局重建识别与代码生成优化在 `MpvHookShader` 与 `GraphicsShaderTransforms` 中保留了 `VN-SR` / `vnsr_*` 前缀分支，详见 [VN-SR 说明](vnsr-filters.md)）。

基础 Anime4K 独立保留完整 MIT 许可 `anime4k/LICENSE.bundled.txt`，来源、固定 Git blob 与 SHA 登记在 bundled manifest；它不依赖可选扩展的许可文件或描述符。该修正补齐现有基础滤镜的许可归属，不引入新的算法或许可证类型。

新增滤镜按来源独立提交：

- VN-SR：独立描述符、资源、来源清单和离线生成器，来源及MIT许可见[说明](vnsr-filters.md)。
- Anime4K 五项扩展：双边均值 / 中值 / 众数降噪、Thin HQ、Darken HQ。固定来源与文件哈希见 `engine/patches/anime4k-extra-filters.json`；上游 MIT 许可文本随资源保留。
- CAS：固定 GPUOpen FidelityFX-CAS 来源、GLES 适配、锐度和缩放参数、六个旧预设映射、生成器及 MIT 许可；来源见 `engine/patches/cas-filters.json`。

CAS 生成器为 `tools/generate-cas-filters.py`，仅依赖 Python 3.10 以上的标准库。`--sources` 指向对应来源目录（含固定提交的 origin.json / tree.json 和所需文件）；来源目录按下方命令导出固定 Git blob 与元数据；使用 `--check` 核对已提交结果。普通 Android 构建直接使用已打包资源，不下载模型或执行 Python 生成器。许可证整理仅计本轮引入的上游，不重复统计原项目依赖；本机完整许可留档保留在项目外。

## 组合滤镜内存修正

问题原因：原生两倍模型之后接较大修复模型时，所有中间 RGBA16F 特征平面同时分配，整链超过预算并回退为默认 Resize。

修复方式：根据实际版本化读写生命周期复用中间平面；对声明了固定邻域的 CNN 使用带边界扩展的分块处理，并保留原生倍率和像素相位。整链预算包含输入预留与完整输出；同一 pass 的读平面不能作为写目标。真实 GPU 尺寸上限或完整输出超预算仍安全回退。

影响范围：共用 GLES 执行器及所有使用它的引擎。可选模型的分块能力与限制由自身定义提供。

测试结果：用户已在 Android 15 / 16 两台设备上完成多种预览滤镜组合及其他功能验收，结果通过。本次提交整理之后的编译和独立审查另记于本机整理记录，不将整理前的验收冒充新构建实机结果。

## 已知限制与审查边界

- 文本排除处理暂停；滤镜作用于完整已合成舞台。
- 调试独立包名、测试 APK 留档/压缩、逐轮交付和原始审计输出为纯本地资料，不在本可提交系列。
- 该系列按 CONTRIBUTING 的功能边界整理，新增上游滤镜分别作为可选贡献。大型功能是否合并由维护者决定。

## 固定来源与资源核验

CAS 的源码准备不依赖手工编造的 origin.json / tree.json，先在项目外 clone，再按 manifest 导出固定版本所需的 Git blob：

```sh
git clone https://github.com/GPUOpen-Effects/FidelityFX-CAS ../FidelityFX-CAS
python tools/prepare-filter-sources.py --repository ../FidelityFX-CAS --manifest engine/patches/cas-filters.json --output build/filter-sources/cas
python tools/generate-cas-filters.py --sources build/filter-sources/cas --check
```

`prepare-filter-sources.py` 从完整提交读取原始 blob，不受检出换行转换影响；CAS 生成器固定 revision 并验证 blob。普通构建的 `verifyGraphicsResources` 核对各贡献本地 SHA-256、Anime4K 补充资源、CAS 生成资源及许可、Siglus 原库及配置读取器的来源许可。它不下载上游，不替代生成器重建；原 Anime4K GLSL 全部采用字节保留属性。Android CI 执行 app / engine JVM 回归和普通原库哈希校验，不再重建 Siglus Rust 运行库；CAS 的来源生成复核仍按下方命令独立执行。

描述符的 `asset` 必须是完整的相对 `.glsl` 路径（例如 `anime4k/Anime4K_Restore_CNN_S.glsl`），不能依赖目录或扩展名拼接。生成器中的路径规则同时生成到运行时目录，普通构建检查路径、文件存在性与来源记录；贡献所有权目录及其子目录内的未登记 GLSL 阻止构建；所有权由清单的 `assetDirectory` 或已校验 generated GLSL 的父目录确定，按 canonical 路径边界判断。独立引擎着色器仍作为任务输入，但无需登记为滤镜贡献；描述器实际引用的每份资源仍必须有来源记录。Android CI 的 `graphicsResourcesRegression` 直接调用生产校验逻辑，覆盖目录、描述器、来源路径与哈希回归，并拒绝同时携带 `haloPerPass>0` 与 `outputSized:true` 的滤镜定义（分块处理要求固定原生倍率输出几何，构建期拒绝含该组合的描述符；运行时规划器不对此类定义尝试分块，整帧超预算时以 `targetRetry.failed(false)` 记录拒绝并保持 `planValid=false` 与 `temporaryFailed=true`，此类确定性超预算会持续旁路而不会仅因等待而退避重试，直到帧几何/裁剪尺寸变化或管线重置；这区别于 `planValid=true` 下临时显存/句柄压力触发的退避重试）。该校验针对已提交滤镜资源及补丁清单，不是全仓文件清点。Siglus 当前安装库由 `siglus-bundled.json` 的 `librarySha256` 核验，文件与固定 TYN 上游提交保持一致；旧补丁库与 ABI 锚点已撤回。

## 验证边界

既有滤镜设置、官方 Artemis、Kirikiroid2 和 krkrsdl3 OpenGL 接入由提交者运行多个样本验收；
官方 Artemis 开启画质处理实际游玩两小时未见问题。上述记录属于原候选功能的历史验收。
Siglus 原库、配置解析和专用 owner 线程路线候选在 2026-10-18 获提交者测试通过；该结论不覆盖后续 TextureView 窗口所有权修复，也不套用旧重编库结果。
2026-11-18（`Adreno 840`）与 2026-11-21（`Adreno 740`）两次实机样本均采集于 2026-11-22 审查修复之前，仅作历史实机参考（详见 [VN-SR 说明](vnsr-filters.md) 与 [方向接口](graphics-filter-coordinates.md)）。本分支在重整到上游主线并收口审查修改后，通过 JVM 专项回归、Debug 应用编译、`buildSrc` 资源回归、`:engine:lintDebug` 与 `:app:lintDebug`（全量 Lint 任务因基线 untouched `org.libsdl.app32` 与本机 `local.properties` 以非零状态退出；按改动文件过滤为 **0 errors / 0 新增 warnings**，不表述为全量 Lint 通过）及离线 Python 脚本校验；并通过 ADB 在连接设备 `Samsung SM-X710`（Android 15 / API 35、`Qualcomm Adreno (TM) 740`、`OpenGL ES 3.2 V@0676.71`，`com.tyranor.next.test` `1.73`）上完成 AVG32《Kanon》、RealLive《Little Busters! EX》与 ONScripter《美少女万花镜1》三款样本短实机测试（**UK2 暂无样本，未实测**；ONS 看门狗真机回退、长时游玩、Release 构建、全量非聚焦单测与非 Adreno GPU 未执行，不作未验证兼容声明）。

## VN-SR

独立贡献提供 C08/C12/C16 三种原生2X滤镜，采用MIT许可，公开来源与固定revision见清单；名称、倍率和颜色随描述符提供，保留来源及许可证按localPath校验，详见[来源与适配](vnsr-filters.md)。

纹理方向、KR2宿主识别和每贡献的逻辑坐标约定见[方向接口](graphics-filter-coordinates.md)。

尚未接入的引擎（FVP、Ethornell 等）的接入路线评估（方案 B/C 优先排序、稳定 `.so` 的 hook 备选与实施顺序）见[画质滤镜hook维护与接入评估](画质滤镜hook维护与接入评估.md)；各原生引擎 Hook 维护规范（KR2 / Artemis / krkrsdl3-SW 归档）亦见同文件。
