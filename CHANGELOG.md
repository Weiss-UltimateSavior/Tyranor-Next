# 变更记录

## 未发布

### 新增

- 新增第三方 APK 运行时模块接入：按 `com.tyranor.runtime.` 包名前缀与 `com.tyranor.runtime.action.LAUNCH` 动态发现，侧边栏「妙妙工具」新增「引擎模块管理」，模块默认关闭；启用只写开关，自动扫描仅使用模块 `res/raw/tyranor_runtime.json` 声明的 `extensions` / `markers`，且只在内置识别为 `UNKNOWN` / `WEB_OTHER` 时介入，扫描是唯一入库入口。模块卸载或禁用后，绑定游戏降级为未知并保留最后已知包名（保留标题/封面），重装或启用后重新扫描可恢复；启动以显式组件交棒文件夹路径，不接管存档、按键与设置。游戏库 schema 升至 v3。设备/真机模块验收尚未执行。
- 新增 Ruffle（SWF / Flash）外置 APK 引擎模块接入：引擎页「网页」分类与安装状态、默认每个顶层 `.swf` 一条记录（根 `index.html/index.htm` + 唯一 SWF 的叶子目录识别为包装游戏）、`game` / `settings` 启动协议、全局与单游戏设置（渲染后端 / 画质 / 缩放 / 黑边 / 加载方式 / 自动关闭）及 `configuration.json` 受管键同步；不提供统一存档管理与虚拟按键重映射。设备与插件运行验证尚未执行。

### 修复

- 修复 [#129](https://github.com/Weiss-UltimateSavior/Tyranor-Next/issues/129)：完善 Kirikiri SD 卡 SAF 镜像元数据与内容路由，实现写入 copy-up 与分片所有权标记维护，增加 sticky 镜像根目录与存档导入恢复保护，并修复 libc stdio 与 open GOT 钩子对缺失路径的错误码保留和未索引写入路由。
- 修复 [#133](https://github.com/Weiss-UltimateSavior/Tyranor-Next/issues/133)：撤回默认风格顶栏渐进模糊及其宿主接入（`c71f57d`、`3dd5d02`），恢复顶栏与正文上下分离布局，以处理切页性能回归。底栏/侧栏/悬浮按钮的玻璃采样、默认主题渐变与 WebView 采样修复保留。
