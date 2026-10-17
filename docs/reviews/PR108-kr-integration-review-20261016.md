# PR #108：Kirikiroid2 1.2.6 / krkrsdl3 接入整合复核

## 范围与结论

按提交者要求，将源提交 `fb02d53`（Kirikiroid2 1.2.6）、`7b4fc41`（krkrsdl3 OpenGL）和 `efc441141e3288f737982934d4119bc1d3033863`（配套文档）合并到 PR 候选 `27bf529b737617e2f2389e62e3c4915f0734384d`。共同祖先为 `bec5d64bea110b19776b828e6b61a488f2c3c773`。采用普通合并，未重写既有 PR 历史，保留较新上游及此前全部审查修复。

合并源严格截止 efc4411，未合并源分支后续包含 CuNNy2 的本地版本。两项接入不替换引擎库、不添加滤镜上游或运行时外部依赖；软件 SDL3 仍不支持滤镜并保留配置。既有 Kirikiroid2 1.3.4/1.3.9、Artemis 和 Siglus 接入保持。

三路非作者独立审查及主 Agent 复核，在实际整合树内**未确认新增可操作缺陷**。代码未作额外修补；本次补充只更新“本地实验/未验收/不更新 PR”的过时文档，并区分源分支历史验证与本次整合结果。不保证所有运行时场景均无问题。

## 接入行为

|内核|处理入口与保留行为|
|---|---|
|Kirikiroid2 1.2.6|独立 bitmap ABI，在游戏窗口合成纹理进入屏幕缩放前处理；经公共 Cocos RenderTexture/Sprite 接口承载结果，flush 后恢复借用纹理、UV 和混合方式。不是仅扩白名单，也不恢复屏幕级锐化。|
|krkrsdl3 OpenGL|完整合成期建立游戏逻辑像素尺度画布，保留 Underlay/main/Overlay 及既有呈现回调。内容全部在主舞台内时整数裁剪回舞台；有舞台外叠加层时处理完整画布，分析器报告实际输入画布。物理输入映射不改。|
|krkrsdl3 software/sw|禁用滤镜及分析器请求，原画面和已保存链保留；SDL3 不借用 Kirikiroid2 的版本选项。|

## 独立审查矩阵

|分工|实际范围|结果与边界|
|---|---|---|
|1.2.6 native/共享 GL 审查者|完整 KR bridge 增量及既有 1.3.x 分派；bitmap ABI、身份/符号/vtable、安装事务、主窗口与 Sprite/纹理矩形、retain/release、结果租借和 flush 后恢复、POT 双副本预算、FBO/缓存状态、暂停及上下文丢失策略、Java pipeline|未确认新缺陷；未重新反汇编核对该闭源库的全部公共 ABI 或执行真实 flush/上下文丢失测试|
|SDL3 native/生命周期审查者|新增 native/geometry/Java/Activity 全文件；Build ID/304 字节 vtable/五项 PLT、指针事务、动态叠加层、输入与绘制分离、GL 状态、完整画布预算、线程和上下文代数、JNI/关闭回退；对实际固定 ELF 做关键只读反汇编|未确认新缺陷；不把关键入口核对扩展为全库或所有插件认证|
|架构/能力/构建审查者|renderer 的全局/单游戏预览与实际启动、可移动存储回退、软件禁用但保留链、CMake 所有目标和平台依赖、JNI 签名/Keep/R8、1.3.x 与 Artemis/Siglus 回归交集、全部测试源码|未确认新整合回归；独立 C++ 几何用例未纳入 Gradle 自动执行，不冒充 JVM 或 CI 已运行|
|主 Agent|确切源祖先/三项提交与暂存树、自动合并内容、能力/启动/布局调用点、Java/完整画布几何和关键 native 资源出口、三份现有库哈希、文档与用户验收、排除范围和 CI/PR/提交对应关系|保留原功能实现，修正文档的本地阶段表述；不把用户实测写成本轮自行测试|

各审查者复用已读的 AGENTS.md、CONTRIBUTING.md、CONTEXT.md；只读 PR 整合树与截至 efc4411 的源内容，未将本地分支后续 CuNNy2 树用作候选。审查者不编辑、提交或对外发消息；主 Agent 汇总提交与 PR 更新。

## 重点核验

- **1.2.6 不污染 1.3.x**：bitmap 与 texture ABI、回调返回类型及安装入口独立。主 Sprite 与源对象在借用期间保留，渲染队列完成后还原；正常暂停当前上下文内清理，意外上下文丢失最多弃置一份有界旧承载并停用该进程的 1.2.6 处理，避免旧 GL 名称误删新对象。
- **SDL3 不占插件 Hook**：不替换既有 PrePresent/Underlay/Overlay。固定 ELF 的常规、预乘和 screen 绘制最终进入挂接 DrawWindowTextureEx；Begin/End vtable、TVPRenderOnce 与 SDL 上下文销毁入口已核对。未知直接 GL 插件仍不保证兼容。
- **完整画布保持原始像素尺度**：整数 padding 覆盖物理窗口内可能出现的内容，主舞台像素中心保持 1:1；按当帧实际 Draw 判定裁剪，避免帧内新增或显隐变化的叠加层漏捕。无法确认契约时呈现完整未过滤画布并暂停后续捕获，不将屏幕缩放后帧标成原始帧。
- **资源和失败路径**：1.2.6 的 POT 双份承载、SDL3 的 RGBA8+D24S8 与裁剪均计入共享预算；临时失败保留有界退避。GL 读写 FBO、viewport、私有 VAO、纹理/采样器、PBO、深度/stencil 掩码等被保护，借用纹理不被删除。指针还原无法证明时保留有界会话及代码引用，不卸载仍被调用的桥。
- **设置与构建**：renderer 经相同 trim/大小写及 software/sw 规则处理，预览与启动使用实际有效值，SDL3 不依赖 Kirikiroid 版本。新桥只依赖平台 EGL/GLESv3/log/dl、C++17；Artemis、Siglus、Ethornell 及原生来源门禁保留。新 JNI 回调签名与 Java 一致，@Keep 及现有 consumer/app 规则保护 Release 路径。

## 二进制与来源

主 Agent 对工作区实际文件只读核对 SHA-256，均与源报告一致；没有引擎二进制变更。1.2.6 实际文件位于 app 的既有 nativeplugins 目录，SDL 两库位于 engine 的既有 jniLibs 目录。

|库|SHA-256|
|---|---|
|libgame126.so|c7d05f84d45deb3906987da7d02af288c1ea3bb949e4d6e5cbe80c09441b2560|
|libkrkrsdl3.so|d5dff5f84b410576415ddbf2afcfdbad112ef9d71851e153584a759065e163ac|
|libSDL3.so|9b1ae950c5b90168193f474c3f53dbcf7eba7230729ae025dcdba83f34def790|

readelf 确认 1.2.6 Build ID，SDL 审查者确认两个 Build ID、虚表大小、重定位及关键调用路径。来源参考仍是源文档列出的固定公开 krkrsdl3 版本，未修改参考仓库或假设其等于当前打包库的全部源码。

## 验证与未覆盖项

提交者明确表示这两个新增接入的**编译和实机验收通过**。该结论按用户提供的验证记录列明，没有推定额外设备、游戏、滤镜排列或故障覆盖。本次整合不重复本地测试、编译、安装或实机操作；精确最终 head 的 CI 结果在 PR 正文及评论中记录，不用旧 PR 或源分支 CI 代替。

阅读了新增 renderer/能力/请求、库名、POT 预算及 C++ 画布几何测试源码；C++ 独立程序未在本轮执行，不计入 CI 的 JVM 数量。源码审查和 Debug CI 不能代替真实 OOM、EGL 句柄复用、厂商 GL 故障、全部 Live2D/直接 GL 插件组合或 Release 设备运行。

AGENTS 指定的 Android CLI 审查技能仍未找到，没有执行或冒充；多 Agent 静态复核不能等同于该技能。原无图标入口 P3 仍待维护者批准，AGENTS 无净差异。后台分析器位置、原版 KR2 纯色偏色、文本排除、着色器进程失败锁存和 CAS 来源生成复核等既有延期不变；本次解决此前延期的 1.2.6 与 krkrsdl3 OpenGL 接入。自研 Artemis 画质接入待上游合并，CuNNy2、自定义导入与本地调试工具继续排除。
