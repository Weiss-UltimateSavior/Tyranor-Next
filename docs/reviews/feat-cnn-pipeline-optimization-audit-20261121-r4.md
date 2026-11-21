# 审计报告 — feat/cnn-pipeline-optimization @ 09396ad4(四期审改循环)

- 日期:2026-11-21(四期 R1''' 审计轮)
- 审计对象:分支 `feat/cnn-pipeline-optimization` @ `09396ad4`,相对 merge-base `d1584d0d`(= origin/main)的 7 提交贡献(60 文件,+14500/-4974)。分支历史第四次重写(三期 11 提交重组;三期修复内容经主控抽查全部存活且演进——deny 59 项、P1-A MAIN 拒绝、contextDestroyed 复位、流程标签清零、P2-B 记账三形态、tile 守卫)。
- 方法:5 视角独立盲审(逻辑/架构/健壮性/回归/规范,串行),零上下文;本期单源(外部报告不随重写保留,`docs/reviews` 仅 README)。
- 门禁:R0''' 实跑 G1–G15 + G-Motion 全绿;E2'(:engine **812**/18/5)IDENTICAL;E3''(:app **2061**/32/21)精确匹配(E2'/E3'' 为二/三期锁定集合,origin/main 演进带来的 +20/+39 用例全过)。
- 证据存档:`local-notes/audit-cnn-09396ad4-20261121/`(5 份盲审 audit-R1p4-*.md、baseline-gates.md、门禁日志、取证脚本 verify-survival.py/verify-p4.py)。

## 一、定级汇总(合并去重后)

- **P0:0 条**
- **P1:1 簇(两处代码点位)**——三视角交叉印证
- **P2:3 簇**
- **P3:43 条原始发现合并为 16 项批准修复 + 18 项维持/接受挂账**

## 二、P1 / P2 详情(主控亲核证实)

### P1-A 两处无界 glGetError 排空循环:上下文丢失时渲染线程永久挂起(逻辑 L-1 + 健壮 R-1/R-2 + 架构 A-1,三视角交叉)

- 证据(主控亲核,当前工作树):
  1. `artemis_official_graphics.cpp` `StageGlState` 构造器(:248 起,约 40 项状态捕获后)以 `while (glGetError() != GL_NO_ERROR) {}` 无界排空(约 :300)——**该构造器在 swap 每帧执行**;
  2. `CnnPipeline.java:161`(DefaultIndexedDrawBufferOps.getDrawBufferCount):`while (GLES30.glGetError() != GLES30.GL_NO_ERROR) {}`——非 ES3.2 + `GL_EXT_draw_buffers_indexed` 探测路径。
  3. 语义:GL_KHR_context_lost 下 `glGetError` 可持续返回 `GL_CONTEXT_LOST_KHR`(粘滞)——两处循环在上下文丢失后**永不退出**,渲染线程永久挂起(ANR)。
  4. 自相矛盾:同文件 `drainStageGlErrors()`(:963-967)自立「Bounded drain, never latches」纪律(16 次上限);Java 侧 `GlErrors.MAX_GL_ERRORS=16` 同纪律——两处新代码绕过了本分支自己建立的规范。
- 触发面:上下文丢失(驱动重置/后台回收)是 Android 真机常规事件;每次 swap 都执行该构造器,命中概率随运行时长累积。
- **修复要求(合入前必须)**:两处均改为有界排空(16 次上限,与 `drainStageGlErrors`/`GlErrors.MAX_GL_ERRORS` 口径一致);上下文丢失场景的后续状态由既有失活/直出路径承接(native 侧上下文丢失已有 abandon 链,Java 侧 GlErrors 已分类)——修复轮须论证「排空有界后,丢失上下文的残余错误标志不会引发二次缺陷」。

### P2-A deny 集推导口径缺口:GLES 3.1 program-uniform/program-pipeline 族未挂钩未 deny(逻辑 L-2)

- 证据(主控亲核):deny 集 59 项中 glProgramUniform1i/1f/4fv/Matrix4fv、glUseProgramStages、glBindProgramPipeline、glGenProgramPipelines、glDeleteProgramPipelines、glValidateProgramPipeline、glGetProgramPipelineiv、glProgramParameteri **全部缺失**;hook 表亦为 0。deny 集自述推导为「GLES 3.x 全入口 − hooks − 无害类」,该族(可承载逐帧内容数据 + 管线对象生命周期)恰好落空——与二三期 deny 集补全的同一分类学。
- 影响面:随包内核当前未导入(dynsym 由修复轮复核留档);未来内核经 program-pipeline 写 uniform 时,uniform 可见性静默失效 → 错误方向为错帧复用(陈帧)。
- **修复要求(必须整改)**:deny 集补 glProgramUniform\*(1i/1f/1iv/1fv/2i/2iv/2fv/3i/3iv/3fv/4i/4iv/4fv/Matrix2fv/Matrix3fv/Matrix2x3fv/Matrix3x2fv/Matrix4x2fv/Matrix2x4fv/Matrix4x3fv/Matrix3x4fv 等全族)与 program-pipeline 生命周期入口(glGenProgramPipelines/glDeleteProgramPipelines/glBindProgramPipeline/glUseProgramStages/glActiveShaderProgram/glValidateProgramPipeline/glGetProgramPipelineiv/glProgramParameteri);dynsym 复核四内核零导入(不误拒);SPEC §12 推导口径同步。

### P2-B MRT 运行期回退分类依赖异常消息字符串前缀(规范 C-1)

- 证据(主控亲核):`CnnPipeline.isMrtTargetFailure`(:656-662)以 4 个 `msg.startsWith("device MRT capacity exceeded"/"MRT FBO incomplete"/"MRT program link failed"/"MRT fragment shader compile failed")` 对 `TargetUnavailableException` 做分类,结果控制运行期 MRT 回退(:796)与 `mLastTargetRejectionIsMrt`(:1188)——消息措辞调整即**静默失去运行期回退**(回退缺失 = 该上下文永久旁路,方向安全但功能退化)。
- **修复要求(必须整改)**:类型化标记(异常携带分类字段/枚举,或共享字符串常量供抛出与判定两侧引用),消除「措辞即协议」;行为零变化,现有测试回归。

### P2-C FrameDirtyTracker op-tag 跨域复用(规范 C-2 + 架构 A-2 + 健壮 R-4 + 回归 RG-01 四视角)

- 证据(主控亲核):`0x109` 由「纹理版本混入(draw hash :843)」与「BindBuffer(:1511)」共用;`0x12C` 由「:874 mix64」与「FramebufferTexture2D hashOp(:1256)」共用;`0x133` 三用(:1728 cullFace 事件/:1959 hashDrawCommon cull 段/:2111 DrawArrays op)。不同域事件共享标签削弱哈希输入的域分隔——理论上存在「不同命令序列产生相同哈希输入」的极低概率碰撞,错误方向为误判 clean(陈帧)。
- **修复要求(必须整改)**:三对复用各自分配独立 op-tag(全表唯一);哈希输入改变会迁移缓存判定基线,属可接受(缓存本帧重建);改动以「identical 命令流→identical 哈希」不变量复核收尾。

### P2-D 其余 P2 项归并说明

- 架构 A-1(cpp:300)并入 P1-A;逻辑 L-2 并入 P2-A;规范 C-2 并入 P2-C。无其它独立 P2。

## 三、矛盾裁决记录

1. **回归视角「Artemis 内核 .so 不入库/无法独立复核」(RG-10) vs 事实**:主控核实 `engine/src/main/nativeplugins/artemis/arm64-v8a/` 四个 .so 健在(与二三期一致)——代理检索方式失误,RG-10 的「不可复核」前提推翻;deny/ELF 结论可仓内核验(列入 P2-A 修复要求)。
2. **P1 定级一致性**:逻辑 L-1(P1)、健壮 R-1/R-2(P1)、架构 A-1(P2)——采纳 P1:三视角独立命中、上下文丢失为真机常规事件、每帧执行、且违反分支自立的排空纪律(非风格问题,是挂死级缺陷)。
3. **robust R-8(discardTargets 吞宿主未消费错误标志)**:标注【存量偏差】——与基线 Anime4kPostProcessor 逐字等价的行为,基线既有且未恶化。

## 四、P3 处置裁决(16 批准修复 + 18 维持/接受)

### 批准修复

| # | 内容(来源) | 改动面与理由 |
| --- | --- | --- |
| F-1 | P1-A 主修复(见上) | cpp StageGlState 构造器 + CnnPipeline.java:161;有界化 |
| F-2 | P2-A deny 补全 + dynsym 留档 + SPEC 同步 | cpp deny 表;四内核零导入复核 |
| F-3 | P2-B 类型化 MRT 分类 | CnnPipeline 异常/常量;行为零变化 |
| F-4 | P2-C op-tag 去重 | cpp 三对;不变量复核 |
| F-5 | hookGlActiveTexture 越界即 frameValid=false(逻辑 L-2 姊妹项/二期 F-9 同族)+ SPEC 措辞 | cpp;P3 |
| F-6 | 文档批:graphics-filters.md「同样地」错别字(架构 A-3/规范 C-5)+ :8 悬空指引(规范 C-6)+ 2xFP16 数字口径统一(规范 C-7)+ 设备清单「未重跑真机」如实化(RG-11) | docs;P3 |
| F-7 | prepare() 处置旧 singlePipeline + 重建键含 chain 身份(健壮 R-3) | ArtemisOfficialBridge.java;P3,防御性 |
| F-8 | renderStage 熔断耗尽分支更新 state(逻辑 L-4) | 一处;P3 |
| F-9 | release()/abandonContext() 对 mRuntimeFusionFallback 的对称复位(逻辑 L-6) | 一处;P3,或以注释论证留证 |
| F-10 | hookEglChooseConfig 属性对上界处理顺序交换(逻辑 L-7/rob-L4 同源) | cpp;P3 |
| F-11 | GlOps.glIsEnabled 死成员删除(规范 C-4) | 一处;P3 |
| F-12 | renderCroppedTexture 冗余 invalidateMeasurements 清理(规范 C-9;rob-R-7 TUE 逃逸面一并核实) | 一处;P3 |
| F-13 | gpu/nodeGpu 哨兵语义注释锚定 + timingError/timingGeneration 命名一致化(规范 C-12/C-8) | 注释/一处重命名;P3 |
| F-14 | GlErrors KDoc 声明「绘制期错误一律永久失败」边界(外部 P3-2 遗产)+ Scheme B 复用键不变量注释(外部 P3-15)+ ARCHITECTURE 滤镜目录层补录(架构 A-6)+ LanczosScale 字节助手职责注(架构 A-5)+ MpvHookShader multiSaveWritesAllTargets out/inout 评估(逻辑 L-9:可区分则修复,否则文档化留证) | 文档/注释/小改批;P3 |
| F-15 | parse 收紧升级说明(回归 RG-04:第三方资产复验提示) | docs;P3 |
| F-16 | hashDrawCommon 的 programUniforms 排序缓存或按帧缓存评估(回归 RG-08 性能面)——评估后实施或维持留证 | cpp;P3 |

### 维持/接受挂账(触发条件 + 复核入口)

| # | 内容(来源) | 处置 |
| --- | --- | --- |
| K-p4-1 | GLES<3.2 且 EXT 探测失败 → 整链确定性旁路(RG-03/rob 同源) | 方向安全;验证清单覆盖 |
| K-p4-2 | KR2 复用键信任边界(RG-05) | 沿二期 K-p3-11 |
| K-p4-3 | FB0 读入口逻辑/物理视图差(RG-06) | 未证实引擎使用;内核导入面核查轮 |
| K-p4-4 | swap 直通一帧陈旧/混合(R-5/RG-07) | 瞬态方向安全;真机专项覆盖 |
| K-p4-5 | ES2 INVALID_ENUM 误归因(R-6) | 降级方向安全 |
| K-p4-6 | discardTargets 吞未消费错误标志(R-8) | 【存量偏差】基线等价 |
| K-p4-7 | 窗外直写+窗内绘制并存呈现覆盖(R-9) | 设计边界,未证实 |
| K-p4-8 | StageGlState::restore EBO/VAO 隐含前提(R-10) | 当前幂等成立;触碰时加防御 |
| K-p4-9 | renderTexture finally 恢复失败路径(R-11) | N3 注释已论证 |
| K-p4-10 | hashDrawCommon CPU 顶点/索引裸读(RG-02/沿一期 K-1) | 判定语义必需;真机 profiling |
| K-p4-11 | GraphicsShaderTargets 多 SAVE 槽位预算推高(RG-09) | 方向安全 |
| K-p4-12 | StageGlState.restore 闭环依赖 hook+deny(RG-10 残余) | P2-A/F-2 落地后复核;内核更新轮 |
| K-p4-13 | 设备验收清单「最终修订未重跑真机」(RG-11) | 发布前验收(沿二期 K-p3-1 清单) |
| K-p4-14 | 测试反射 setField(规范 C-3,沿二期 K-4/K-p3-16) | 测试重构轮 |
| K-p4-15 | 提交 a8e3439e 聚焦度(规范 C-10/外部 sty 同源) | 历史不可改;PR 描述标注 |
| K-p4-16 | GLES32 glGetIntegeri_v 对 COLOR_WRITEMASK 的规范口径(逻辑 L-3) | 有 probe 错误回退,方向安全;设备差异轮 |
| K-p4-17 | scale-only blit 错误归类 draw-site 边界(逻辑 L-5) | 纪律自洽;触碰时评估 |
| K-p4-18 | VNSR 共享 9 抽头坐标溯源(三期 L-5 遗产) | 三形态安全;混合绑定+分块落地时处理 |

## 五、门禁终态(R0''' 实跑,2026-11-21)

- G1–G7/G9/G11/G13/G14/G15 + **G-Motion(396 文件 0 违规)** 全 PASS;G12 lint-diff(--base d1584d0d)改动行 0(全量 55 存量);G8 E1 签名豁免。
- G10:`:engine` **812/18/5**(E2' IDENTICAL,0 新增);`:app` 显式单跑 **2061/32/21** = E3''(32)精确集合。
- G16/G17/G18 环境性缺失如实登记(CI 覆盖)。

## 六、盲审覆盖与未覆盖声明(汇总)

- 5 视角全部读完 13 段代码 diff(21647 行);六份 vnsr GLSL 的 assets diff 经逻辑视角脚本逐行比对确认与 sources diff 逐字节一致;三份资产 SHA-256 由回归视角实算与 manifest 一致;CnnPipeline/artemis cpp/测试等深文件均有视角全文精读。
- 主要未覆盖:内核 .so 导入面(回归棒误判不入库,主控已核实在仓;dynsym 复核列入 F-2)、真机/设备行为、GLSL 逐位数值(哈希+FP64 守护)、构建/测试执行(禁令)。
- 方法学:串行派发(并发 1,用户指令);本期单源(外部报告不随重写保留);健壮棒提示词定向追问 glGetError 有界性,促成 P1 聚簇三视角收敛。

## 七、结论

四期 R1''' 审计轮:**0 P0 / 1 P1(两处点位)/ 3 P2 / 16 P3 修复 + 18 P3 维持**。P1-A 为上下文丢失挂死级缺陷(合入前必须修复);P2-A/B/C 为合入前整改。门禁与 E2'/E3'' 豁免集合逐一相同、0 新增。修复轮按本文档逐项闭环,复核轮对修复增量做新鲜视角审计(logic+robustness 深度优先,重点 glGetError 有界化后的上下文丢失路径)。
