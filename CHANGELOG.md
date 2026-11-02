# 变更记录

## 未发布

### 新增

- 新增第三方 APK 运行时模块接入：按 `com.tyranor.runtime.` 包名前缀与 `com.tyranor.runtime.action.LAUNCH` 动态发现，侧边栏「妙妙工具」新增「引擎模块管理」，模块默认关闭；启用只写开关，自动扫描仅使用模块 `res/raw/tyranor_runtime.json` 声明的 `extensions` / `markers`，且只在内置识别为 `UNKNOWN` / `WEB_OTHER` 时介入，扫描是唯一入库入口。模块卸载或禁用后，绑定游戏降级为未知并保留最后已知包名（保留标题/封面），重装或启用后重新扫描可恢复；启动以显式组件交棒文件夹路径，不接管存档、按键与设置。游戏库 schema 升至 v3。设备/真机模块验收尚未执行。
- 新增 Ruffle（SWF / Flash）外置 APK 引擎模块接入：引擎页「网页」分类与安装状态、默认每个顶层 `.swf` 一条记录（根 `index.html/index.htm` + 唯一 SWF 的叶子目录识别为包装游戏）、`game` / `settings` 启动协议、全局与单游戏设置（渲染后端 / 画质 / 缩放 / 黑边 / 加载方式 / 自动关闭）及 `configuration.json` 受管键同步；不提供统一存档管理与虚拟按键重映射。设备与插件运行验证尚未执行。
- 升级 Ethornell / BGI 内置宿主到上游 `0cfff1d` ABI v1：15 个导出（含 `request_payload`）、kind 5 输入对话框与 JSON 回答、engine 原生四类对话框；同步 ABI header、provenance 与 Release R8 probe。当前随包库为 `043fef05…`（27,259,224 B，provenance `dirty=true`、1 个本地补丁；`--local-static` 与 debug/release APK 精确库字节检查通过，严格来源审计未通过）。App 存档管理开放为 App 私有 user-root 备份/恢复/删除（单文件 64 MiB；不导入资源根 `UserData`，不承诺商业游戏槽位或 PC 存档互通）。设备核心 6/6（1.426s）、暂停 3/3（2.999s）、存档 9/9（`pm clear` 后，2.047s）、release probe SJIS/GBK 各 3/3；真实样本语言点击后宿主仍存活并生成 `BGI.gdb`（约 123,802 B），仅验证启动期全局数据写入，16KB/API26 完整重跑未执行。
- 新增 VN-SR C08 / C12 / C16 三种原生 2× 滤镜预设。
- 新增 Bilinear / Bicubic 缩放处理滤镜（独立菜单项，按性能开销排序于 Lanczos 之前）：Bicubic 支持 B/C 独立参数双滑块调节（缺省 Mitchell 基准 B=0.33, C=0.33），Lanczos 提供独立抗振铃强度滑块（缺省 0.50，可抑制文字/边缘振铃，设为 0 时关闭）；二者与 Lanczos 同为纯重采样节点，可作 CNN 滤镜在性能预算不足时的轻量放大替代。滤镜链操作栏将缩放设定与参数设定拆分为独立弹窗，按钮采用圆角线框与短竖线分割布局。自动 Resize 行为不变。

### 修复

- 宿主传递纹理行序，Anime4K按输入方向适配卷积采样；KR2引擎库保持不变。
- 修复 Ethornell 启动期全局数据写入路径：`Sys80:32` 把已含 `bgi-resource\` 前缀的 `UserData` 路径再次拼上资源根；在语言选择页点击语言还会触发 `SaveGlobalUserData` 写 `bgi-resource\BGI.gdb`（旧 `7ee` 写裸 `BGI.gdb`），而旧策略只放行 `UserData`，写入因此落到只读资源根并以 `PermissionDenied` 失败。本地补丁 `0001-user-data-double-bgi-resource.patch`（SHA-256 `77b99d93…`）在 `file_policy.rs` 中收敛一层重复前缀，并仅把顶层 `bgi-resource/BGI.gdb` 与 `bgi-resource/BGI####.cad`（四位数字、大小写不敏感）映射到私有 `bgi-user/`；其它资源名、子目录与路径逃逸仍拒绝。当前库 `043fef05…`（27,259,224 B）基于 `0cfff1d` + 该补丁，provenance `dirty=true`；`--local-static` 通过，严格来源审计因 dirty 不能通过。上一版 `541bf6` 只覆盖 `UserData`，点击语言后仍失败，已由本库取代。Amairo 私有 Activity 30s/45s 含语言点击存活、无 `failed_detail`，私有 user-root 生成 `BGI.gdb`（约 123,802 B）；新夹具下 `UserData` 可能为空，不声称槽位。标题/对白/槽位保存与读回、`Sys80:34`、`Sys80:30/31/35` 等存在性读取未验证。
- 修复 [#129](https://github.com/Weiss-UltimateSavior/Tyranor-Next/issues/129)：完善 Kirikiri SD 卡 SAF 镜像元数据与内容路由，实现写入 copy-up 与分片所有权标记维护，增加 sticky 镜像根目录与存档导入恢复保护，并修复 libc stdio 与 open GOT 钩子对缺失路径的错误码保留和未索引写入路由。
- 修复 [#133](https://github.com/Weiss-UltimateSavior/Tyranor-Next/issues/133)：撤回默认风格顶栏渐进模糊及其宿主接入（`c71f57d`、`3dd5d02`），恢复顶栏与正文上下分离布局，以处理切页性能回归。底栏/侧栏/悬浮按钮的玻璃采样、默认主题渐变与 WebView 采样修复保留。
