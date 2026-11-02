# 变更记录

## 未发布

### 新增

- 新增 Ruffle（SWF / Flash）外置 APK 引擎模块接入：引擎页「网页」分类与安装状态、默认每个顶层 `.swf` 一条记录（根 `index.html/index.htm` + 唯一 SWF 的叶子目录识别为包装游戏）、`game` / `settings` 启动协议、全局与单游戏设置（渲染后端 / 画质 / 缩放 / 黑边 / 加载方式 / 自动关闭）及 `configuration.json` 受管键同步；不提供统一存档管理与虚拟按键重映射。设备与插件运行验证尚未执行。
- 新增 VN-SR C08 / C12 / C16 三种原生 2× 滤镜预设。
- 新增 Bilinear / Bicubic 缩放处理滤镜（独立菜单项，按性能开销排序于 Lanczos 之前）：Bicubic 支持 B/C 独立参数双滑块调节（缺省 Mitchell 基准 B=0.33, C=0.33），Lanczos 提供独立抗振铃强度滑块（缺省 0.50，可抑制文字/边缘振铃，设为 0 时关闭）；二者与 Lanczos 同为纯重采样节点，可作 CNN 滤镜在性能预算不足时的轻量放大替代。滤镜链操作栏将缩放设定与参数设定拆分为独立弹窗，按钮采用圆角线框与短竖线分割布局。自动 Resize 行为不变。

### 修复

- 宿主传递纹理行序，Anime4K按输入方向适配卷积采样；KR2引擎库保持不变。
- 修复 [#129](https://github.com/Weiss-UltimateSavior/Tyranor-Next/issues/129)：完善 Kirikiri SD 卡 SAF 镜像元数据与内容路由，实现写入 copy-up 与分片所有权标记维护，增加 sticky 镜像根目录与存档导入恢复保护，并修复 libc stdio 与 open GOT 钩子对缺失路径的错误码保留和未索引写入路由。
- 修复 [#133](https://github.com/Weiss-UltimateSavior/Tyranor-Next/issues/133)：撤回默认风格顶栏渐进模糊及其宿主接入（`c71f57d`、`3dd5d02`），恢复顶栏与正文上下分离布局，以处理切页性能回归。底栏/侧栏/悬浮按钮的玻璃采样、默认主题渐变与 WebView 采样修复保留。
