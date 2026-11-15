# docs 目录索引

`docs/` 下的文档是专题方案与历史记录，保留生成时的上下文与结论；当前实现行为以仓库根 [SPEC.md](../SPEC.md) 与 [ARCHITECTURE.md](../ARCHITECTURE.md) 为准。文档未标注状态不代表其内容仍然有效，引用前请对照当前代码与上述两份文档。

本索引按类别列出 `docs/*.md`（不含 `docs/adr/`、`docs/reviews/`、`docs/skills/` 与本文件），子目录在末尾单独列出。

## 引擎接入

- [AVG32 / RealLive / UK2 三引擎接入方案（framebuffer 引擎组）](AVG32-RealLive-UK2引擎接入方案.md)
- [Artemis 自动引擎识别优化计划方案](Artemis自动引擎识别优化计划方案.md)
- [Artemis「添加基础补丁 / 添加Windows环境补丁」逆向分析](Artemis基础补丁与Windows环境补丁逆向分析.md)
- [Artemis Ogg 音频兼容修复（Issue #111）](Artemis-Ogg音频兼容修复.md)
- [BGI 存档读取修复实施计划](BGI宿主存档读取修复实施计划.md)
- [CMVS（cmvs-rs）引擎接入方案](CMVS-cmvs-rs引擎接入方案.md)
- [Ethornell / BGI（ethornell-rs）Android 化与内置引擎接入计划](Ethornell-BGI-Android化移植计划方案.md)
- [Ethornell / BGI 宿主重构计划方案](Ethornell-BGI-宿主重构计划方案.md)
- [FVPEngine（rfvp）内置引擎接入方案](FVP引擎接入方案.md)
- [FVP 自定义字体（用户更换字体）接入方案](FVP自定义字体接入方案.md)
- [PC 游戏类型接入方案（手动添加 + 外置 Winlator 启动）](PC游戏类型接入方案.md)
- [SiglusEngine（siglus_rs）内置引擎接入方案](Siglus引擎接入方案.md)
- [SoftPAL（sena-rs）引擎接入方案](SoftPAL-sena-rs引擎接入方案.md)
- [System3.x（xsystem35-sdl2）内置引擎接入方案](System35-xsystem35-引擎接入方案.md)
- [System4（xsystem4）内置引擎接入方案](System4-xsystem4-引擎接入方案.md)
- [krkrsdl3 引擎壳配置能力清单与 TyranorNext 完善方案](krkrsdl3-engine-config-plan.md)
- [Ren’Py 8.5.3 外置模块接入分析](renpy-8.5.3-module-analysis.md)
- [外置 APK 引擎模块接入方案](外置APK引擎模块接入方案.md)
- [外置 Ren'Py 模块配置接入引擎设置清单计划](外置RenPy模块配置接入引擎设置清单计划.md)
- [外置 RPGM 模块配置接入引擎设置清单计划](外置RPGM模块配置接入引擎设置清单计划.md)
- [外置 Ruffle 模块接入方案（SWF / Flash）](外置Ruffle模块接入方案.md)
- [外置主机模拟器跳转接入方案（PPSSPP / Eden / Winlator）](外置主机模拟器跳转接入方案.md)
- [第三方 APK 运行时模块接入方案（计划）](第三方APK运行时模块接入方案.md)
- [第三方 APK 运行时模块接入指南](第三方APK运行时模块接入指南.md)
- [统一引擎宿主启动加载界面实施计划](统一引擎宿主启动加载界面实施计划.md)
- [汉化补丁字体映射移植适配说明](汉化补丁字体映射移植适配说明.md)

## 架构与 UI

- [设置页 Miuix 化改造实施方案](miuix-settings-migration-plan.md)
- [三层架构目录归类优化方案](三层架构目录归类优化方案.md)
- [TyranorNext App 内三语言文本切换支持计划方案](三语言文本切换支持计划方案.md)
- [输入重映射与手柄映射方案（可编辑虚拟按键 + 手柄按键重映射）](输入重映射与手柄映射方案.md)
- [平板侧栏导航适配方案](平板侧栏导航适配方案.md)
- [应用层页面切换性能优化方案](应用层页面切换性能优化方案.md)
- [TyranorNext 应用持久化存储迁移与性能优化计划方案](应用持久化存储迁移与性能优化计划方案.md)
- [TyranorNext 架构优化整理方案](架构优化整理方案.md)
- [玻璃风格主题计划方案](玻璃风格主题计划方案.md)
- [液态玻璃增强计划方案](液态玻璃增强计划方案.md)
- [高级玻璃外观风格计划方案（含「玻璃 → 复古玻璃」更名）](高级玻璃外观风格计划方案.md)
- [滤镜纹理方向接口](graphics-filter-coordinates.md)
- [共用画质滤镜链](graphics-filters.md)
- [Kirikiroid2 1.2.6 与 krkrsdl3 的画质滤镜接入](krkr-local-raw-filters.md)
- [VN-SR 滤镜集](vnsr-filters.md)

## 存档与网络

- [Web 壳固定端口与存档域（Tyrano / WebOther / VN）](Web壳固定端口与存档域.md)
- [TyranorNext 应用持久化数据清单](应用持久化数据清单.md)

## 工具与构建

- [RPG Maker MV/MZ 游戏修改器实施方案](RPGMaker修改器实施方案.md)
- [XP3 拆封包功能方案（解包 / 封包独立工具页）](XP3拆封包功能方案.md)
- [Native Plugin Release Hardening Plan](native-plugin-release-hardening-plan.md)

## 验收 / 历史记录

- [Ethornell 测试验收记录](Ethornell测试验收记录.md)
- [WebDAV Android 设备验收记录](WebDAV-device-acceptance.md)
- [应用层代码审查问题清单](应用层代码审查问题清单.md)

## ADR

- [0001 SDL2 运行库由 System35 与 System4 共用（libSDL2x + app32）](adr/0001-sdl2-shared-libsdl2x-app32.md)
- [0002 内置原生引擎库以预构建 .so 入库，源码与修改声明放 fork](adr/0002-prebuilt-engine-libs-source-in-fork.md)

## 审核模板

- [审核模板目录说明](reviews/README.md)
- [PR 审核回复模板](reviews/PR-REVIEW-TEMPLATE.md)
