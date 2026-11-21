# 审改循环终止报告(四期)— feat/cnn-pipeline-optimization(全程总账)

- 终止日期:2026-11-21
- 分支:`feat/cnn-pipeline-optimization`,四期审计起点 `09396ad4`(用户指令:对最新 HEAD 重启完整审改轮,并发 1),终止 HEAD `e02463d3`;merge-base = `origin/main` = `d1584d0d`
- 终止条件核验:**0 P0 / 0 P1 / 0 未整改 P2;全部 P3 处于已明确裁决状态(修复或挂账登记)** → 循环终止
- 前情:一/二/三期循环均已终止;分支在其间经历四次历史重写,历轮修复内容经逐期存活抽查确认演进存续(deny 集最终 100 项、P1-A MAIN 拒绝、contextDestroyed 复位、记账三形态、tile 守卫等);一期 H1(AppIconSwitcher stash)已由用户在 Manifest 层解决(`${applicationId}` 别名,`ef0fcf1f`)。

## 一、四期全程总账

| 轮次 | 内容 | 结果 | 关键产物 |
| --- | --- | --- | --- |
| R0''' 准备 | 判定第四次重写(7 提交,60 文件,+14500/-4974,代码 diff 21647 行 13 段);门禁 G1–G15+G-Motion 实跑全绿(E2' 18 IDENTICAL@engine 812;E3'' 32 精确@app 2061);三期修复存活抽查全部在位且演进 | 完成 | scope-*、verify-survival.py |
| R1''' 审计轮 | 5 视角盲审串行(并发 1,单源——外部报告不随重写保留);49 条原始发现(3 P1/3 P2/43 P3);主控亲核全部 P 级证据 | **P0=0 / P1=1 簇(两处点位) / P2=3 簇 / P3=16 修 + 18 维** | audit-R1p4-{logic,architecture,robustness,regression,conventions}.md;审计报告(已入库) |
| R2''' 修复轮 | P1-A 两点位有界化(cpp StageGlState 构造器 + CnnPipeline getDrawBufferCount,16 上限 + GL_CONTEXT_LOST 短路)+ P2-A deny 59→100(glProgramUniform 全族 + pipeline 生命周期,dynsym 四内核零导入)+ P2-B FailureKind 类型化 + P2-C op-tag 全表唯一 + F-1..F-16(F-10/F-14 维持留证、F-5 核实已在分支) | 14 修/2 维持留证/1 核实已在分支;5 提交至 `e02463d3`;门禁双集合 IDENTICAL 0 新增 | fix-R2p4-report.md |
| R3''' 复核轮 | 19 处置**全部成立**(全部亲自取证,不采信修复摘要);「上下文丢失无二次缺陷」论证独立重建(下游显式 GL_NO_ERROR 复查 + abandon/fail 闩锁承接链逐点亲证);deny 100 项清点 + 四内核新 41 符号零导入 + clean 内核「不达安装门」链亲证;P2-B 14 个 TUE 抛点分类映射零漂移;op-tag 全表唯一;K-p4-18 零改动;增量审计 **0 P0/P1/P2,1 条 P3 注释口径**(GL_CONTEXT_LOST 粘滞表述 vs KHR 规范一次性语义——两种语义下代码均安全);门禁全量实跑双集合 IDENTICAL | **建议终止** | review-R3p4-report.md(含 KHR 规范引用) |

**四期累计提交 5 个**(全英文祈使句):`ca3d9611` `793f3afd` `117c9c9a` `0082ca02` `e02463d3`(+收口提交)。

## 二、门禁终态

- G1–G15 + G-Motion 全部实跑通过(G14 含 `buildCMakeDebug[arm64-v8a]` 实编;G12 lint-diff 改动行 0,全量 55 存量)。
- G10 失败豁免集合终态:**E2'(:engine 18)+ E3''(:app 32)** 双集合逐一相同、0 新增;采集口径永久化(`:app` 显式单跑取当轮 XML)。
- 环境性缺失如实登记:G8(Linux-only)、G16(clang++)、G17(cargo 不适用)、G18(ethornell provenance)——CI 覆盖。
- 证据存档:`local-notes/audit-cnn-09396ad4-20261121/`(5 份盲审 + fix-R2p4/review-R3p4 + 门禁日志 + 取证脚本)。

## 三、未决项清单(挂账/移交,逐条附触发条件与复核入口)

1. **K-p4-1..18 维持挂账**(内容+触发条件+复核入口详见四期审计报告第四节与 review-R3p4-report.md §五),要点:GLES<3.2 确定性旁路(K-p4-1)、KR2 复用键信任边界(K-p4-2)、FB0 读入口视图差(K-p4-3)、swap 直通瞬态(K-p4-4)、ES2 枚举误归因(K-p4-5)、discardTargets 吞错【存量偏差】(K-p4-6)、窗外直写边界(K-p4-7)、StageGlState EBO/VAO 隐含前提(K-p4-8)、finally 失败路径(K-p4-9)、顶点内存裸读(K-p4-10)、多 SAVE 槽位预算(K-p4-11)、restore 闭环依赖 hook+deny(K-p4-12,四期已 dynsym 重证)、设备验收(K-p4-13,**Adreno 740 本轮已补跑,非 Adreno/Siglus/krkrsdl3 仍待条件具备后补测**)、测试反射(K-p4-14)、提交聚焦度(K-p4-15)、GLES32 规范口径(K-p4-16)、scale-only blit 错误归类(K-p4-17)、VNSR 坐标溯源(K-p4-18)。
2. **Z-R3p4-1(P3 注释口径 → 已闭环)**:已将 `artemis_official_graphics.cpp`(`drainGlErrorsBounded()` 与 `StageGlState` 构造器)以及 `CnnPipeline.java`(`DefaultIndexedDrawBufferOps`)中关于 `GL_CONTEXT_LOST_KHR` 无限重复返回的注释统一修正为「按 `GL_KHR_robustness` 规范,多错误标志可按任意顺序逐个清除(读取 `GL_CONTEXT_LOST_KHR` 不保证是首次返回、也不保证其后必为 `GL_NO_ERROR`),单个 context-lost 标志通常按单次报告清除但部分驱动或持续错误流仍可能连续上报,故 16 次上限 + 遇 `GL_CONTEXT_LOST_KHR` 提前返回在两种场景下均保证 render thread 不挂死」。
3. **真机验收进展与仍开放缺口**(沿二期/三期 K-p3-1 清单):
   - **已完成(Adreno 740 / `SM-X710` / OpenGL ES 3.2, 2026-11-21)**:隔离包 `com.tyranor.next.glverify` 在最终收口修订上实机跑通 Kirikiroid2 `1.3.9`(`VNC16 2X`, `59–62 FPS / GPU 6.4–12.9 ms`)、Kirikiroid2 `1.3.4`(`VNC16 2X`, `59–62 FPS / GPU 7.4–11.4 ms`, 补齐 `1.3.4` 空白)、Artemis 单上下文 GLES3(`VNC16 2X + Resize`, 7 passes, `62 FPS / GPU 12.7–13.1 ms` 与 `Anime4K_Restore_CNN_L` 运行期 MRT 融合 5 passes, `60–61 FPS / GPU 10.7–12.1 ms`),ES3 升级日志正常且零 GL/JNI 致命错误(见 `docs/vnsr-filters.md` 与 `docs/graphics-filter-coordinates.md`)。
   - **因硬件/环境暂无条件仍挂账**:非 Adreno 设备(Mali 优先)满载测试、GLES 3.0/3.1 `GL_EXT_draw_buffers_indexed` 与无 half-float 设备旁路、Siglus/krkrsdl3 当前修订重跑、纯静态停帧(`GPU —ms`)专项及逃生门用户可达性——发布或具备非 Adreno 真机条件时再按清单补测回填。
4. **历轮沿袭挂账**:二期 K-p2-1..11、一期 K-1..K-8 中未被四期演进覆盖的条目(逐条触发条件+复核入口见各期台账/审计报告);其中 deny/记账类已在四期演进中部分闭合(K-p4-12 重证)。
5. **工作区卫生(已清理)**:已清理 `tools/__pycache__/` 并在本次收口验证全程使用 `python -B`;`docs/reviews/` 当前保留 README/模板及四期审计/收口报告。

## 四、流程纪律执行记录

- 主控全程未亲自审计/修改代码;实质工作全部由全新隔离子代理执行(盲审 5 + 修复 1 + 复核 1,四期);子代理零上下文、串行后台派发(并发 1,用户指令)。
- **流程变更(用户指令,四期生效)**:审/改结果的核对全交复核子代理,主控不做亲自抽查——本已按此执行(R2''' 返回后未抽查,直接派发 R3''' 全面取证)。R3''' 取证发现并纠正了两处主控台账疏漏(四期 baseline-gates.md 未单独落盘,已补正;E3'' 集合引用路径),隔离复核价值再次实证。
- 主控亲核纪律在 R1''' 审计轮照常执行(P1 双无界循环/P2 三簇逐条);盲审禁读纪律、未证实降级纪律、先验证后提交纪律——均执行并留痕。
- 子代理逾越白名单记录:零。

## 五、历期循环索引

- 一期:2026-11-17~18,起点 `1fa1774f`,收口 `f2f6e6fb`(报告 loop-closure-20261118.md)
- 二期:2026-11-18~19,起点 `85bbc4ed`(重写后),收口 `c2d70fdd`(报告 loop-closure-20261119.md)
- 三期:2026-11-19~20,起点 `85bbc4ed`,被用户裁定可废弃并吸收进第四次重写(台账保留)
- 四期(本报告):2026-11-21,起点 `09396ad4`,收口 `e02463d3`(+收口提交)
