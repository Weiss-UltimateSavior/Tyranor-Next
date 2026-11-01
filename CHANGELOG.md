# 变更记录

## 未发布

### 新增

- 新增第三方 APK 运行时模块接入：按 `com.tyranor.runtime.` 包名前缀与 `com.tyranor.runtime.action.LAUNCH` 动态发现，侧边栏「妙妙工具」新增「引擎模块管理」，模块默认关闭；启用只写开关，自动扫描仅使用模块 `res/raw/tyranor_runtime.json` 声明的 `extensions` / `markers`，且只在内置识别为 `UNKNOWN` / `WEB_OTHER` 时介入，扫描是唯一入库入口。模块卸载或禁用后，绑定游戏降级为未知并保留最后已知包名（保留标题/封面），重装或启用后重新扫描可恢复；启动以显式组件交棒文件夹路径，不接管存档、按键与设置。游戏库 schema 升至 v3。设备/真机模块验收尚未执行。
- 新增 Ruffle（SWF / Flash）外置 APK 引擎模块接入：引擎页「网页」分类与安装状态、默认每个顶层 `.swf` 一条记录（根 `index.html/index.htm` + 唯一 SWF 的叶子目录识别为包装游戏）、`game` / `settings` 启动协议、全局与单游戏设置（渲染后端 / 画质 / 缩放 / 黑边 / 加载方式 / 自动关闭）及 `configuration.json` 受管键同步；不提供统一存档管理与虚拟按键重映射。设备与插件运行验证尚未执行。
- 游戏页新增「筛选与排序」抽屉（[#118](https://github.com/Weiss-UltimateSavior/Tyranor-Next/issues/118)）：顶栏筛选入口（有筛选或非默认排序时带圆点提示）内提供排序方式（字母 / 括号标签 / 引擎 / 最近游玩）、引擎类型与游玩状态（全部 / 已游玩 / 未游玩）过滤，抽屉内「重置」恢复字母序 + 全部引擎 + 全部状态；抽屉关闭统一走遮罩或返回键（点按把手不再关闭）。条件写入 `AppSettingsStore` 持久化，与搜索命中集叠加生效；设置页「游戏排序」同步支持新增的两种排序。筛选出 0 结果时，空态直接提供「重置」入口。

### 重构

- 底部抽屉外壳抽取为公共组件 `ui/common/AppBottomSheet`（面板色 / 遮罩 / `AppSheetTopShape` / 玻璃描边 / 关闭手势 / 导航栏间距收敛到单点），`GameActionsSheet` 与新的筛选抽屉共用；同时修复抽屉内容被系统导航栏遮挡 31px 的问题。
- 游戏页排序/过滤的纯函数下沉到 `core/game/model`，消除 `ui/home → ui/game` 的横向依赖；抽屉遮罩色收口到 `theme/Color.kt`。

### 修复

- 修复 [#129](https://github.com/Weiss-UltimateSavior/Tyranor-Next/issues/129)：完善 Kirikiri SD 卡 SAF 镜像元数据与内容路由，实现写入 copy-up 与分片所有权标记维护，增加 sticky 镜像根目录与存档导入恢复保护，并修复 libc stdio 与 open GOT 钩子对缺失路径的错误码保留和未索引写入路由。
- 首页最近列表右滑删除的语义明确为「清除游玩记录」（无障碍文案同步）：该动作把 `openTime` 归零，因此同时影响游戏页「已游玩」筛选与「最近游玩」排序，不只是隐藏列表项；同一次更新里也把库内该游戏的游玩时间归零，避免持久化快照发布前仍显示为已游玩。详见 `SPEC.md`。
- 游戏页筛选收尾（[#118](https://github.com/Weiss-UltimateSavior/Tyranor-Next/issues/118)）：空态「重置」同时清空搜索词（此前只清筛选，列表仍空且按钮消失）；切换筛选/排序后网格回到顶部、封面流回到第一页；抽屉打开时把已选引擎标签滚入视口，重置回「全部」时滚回行首；引擎 chip 与选择卡触控区补到 48dp；引擎过滤值在 UI 可交互前加载，避免冷启动把旧值写回覆盖用户选择；库里已不存在的引擎条件由游戏页自动清空。
- 修复 [#133](https://github.com/Weiss-UltimateSavior/Tyranor-Next/issues/133)：撤回默认风格顶栏渐进模糊及其宿主接入（`c71f57d`、`3dd5d02`），恢复顶栏与正文上下分离布局，以处理切页性能回归。底栏/侧栏/悬浮按钮的玻璃采样、默认主题渐变与 WebView 采样修复保留。
