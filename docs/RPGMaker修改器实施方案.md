# RPG Maker MV/MZ 游戏修改器实施方案

## 1. 目标

在 Tyranor Next 现有 RPG Maker MV/MZ WebView 宿主中加入内置游戏修改器，覆盖常用运行时修改能力，同时满足以下约束：

- 仅对已识别为 RPG Maker MV/MZ 的本地游戏启用。
- 不修改游戏目录，不复制第三方解密脚本，不依赖额外 native 动态库。
- MV/MZ 使用同一套公开 API，通过兼容层处理版本差异。
- 修改器开关和持久状态按游戏隔离，不依赖随机端口对应的 `localStorage`。
- 菜单打开时隔离触摸、键盘和滚轮输入；Android 返回键优先关闭菜单。
- 修改行为尽量调用 RPG Maker 公共运行时方法，确保窗口刷新、事件联动和存档一致性。

## 2. 总体架构

```text
EngineLauncher
  ├─ 全局设置 + 单游戏覆盖
  └─ Intent: rpgMakerModEnabled / rpgMakerModGameId
                │
                ▼
TyranoActivity (仅 RPG_MV / RPG_MZ)
  ├─ RpgMakerSaveBridge：Save / Load / Exists / Remove
  ├─ RpgMakerModBridge：读取、写入按游戏隔离的修改器状态
  ├─ 注入现有 MV/MZ 兼容补丁
  └─ 注入 TyranorMod Core + UI + CSS
                │
                ▼
RPG Maker JavaScript 运行时
  ├─ window.TyranorMod：统一修改器 API
  ├─ window.TyranorModUI：浮层菜单
  └─ 原型 Hook：战斗、穿墙、事件加速、对话快进
```

## 3. 功能范围

### 3.1 快捷功能

- 无敌
- 一击必杀
- 必定暴击
- 穿墙
- 事件加速
- 对话快进
- 金币设置/拉满
- 队伍全恢复

### 3.2 数据修改

- 物品、武器、护甲查询与数量设置
- 角色等级、HP、MP、基础参数加成修改
- 技能学习/遗忘
- 状态附加/移除
- 装备查看与更换
- 游戏开关、变量搜索与修改

### 3.3 地图、战斗、存档

- 当前地图/坐标查看
- 当前地图坐标移动、跨地图传送
- 敌我战斗快照、敌方 HP 修改、消灭全部敌人
- 强制胜利、强制逃跑
- 存档槽查询、保存、读取、删除

## 4. 实施步骤

- [ ] 新增 `__rpgmaker_mod_core.js`，实现统一 API、MV/MZ 兼容层和幂等 Hook。
- [ ] 新增 `__rpgmaker_mod_ui.js` 与 `__rpgmaker_mod.css`，实现移动端浮层菜单和输入隔离。
- [ ] 扩展 HTML 注入器，支持独立 CSS/JS 资源标签，不写入游戏目录。
- [ ] 扩展 `RpgMakerSaveBridge`，补齐删除存档能力。
- [ ] 新增 `RpgMakerModBridge`，用 Android SharedPreferences 按游戏保存修改器状态。
- [ ] 修改 Android 返回键流程：菜单打开时先关闭，否则沿用双击退出。
- [x] 增加独立“RPG Maker 引擎设置”入口及设置页，提供外部网络、独立存档和默认开启的修改器开关。
- [ ] 增加 RPG MV/MZ 单游戏覆盖开关，并由启动器传入宿主。
- [ ] 为注入 HTML、启动设置合并和关键 JS 结构增加自动化测试。
- [ ] 完成 Debug 构建、单元测试、连接设备安装和基础启动验证。

## 5. 兼容性策略

- 通过 `Utils.RPGMAKER_NAME`、核心类和 API 特征识别 MV/MZ，不依赖单一版本号。
- Hook 保存原始方法并使用唯一标记，避免页面重载或插件重复安装造成递归。
- 游戏对象未就绪时定时等待；新游戏/读取存档后重新应用穿墙等持久状态。
- MZ 的 Promise 存档 API和 MV 的同步 API统一包装为 Promise。
- 数据库列表过滤空槽，名称和描述作为搜索字段，避免一次性渲染全部数据。
- 对不存在的类、数据库或方法返回可展示错误，不使游戏主循环崩溃。

## 6. 安全边界

- 只在 `WebGameType.RPG_MV/RPG_MZ` 且设置开启时注入。
- Bridge 只暴露状态读写与存档删除，不暴露任意文件路径或命令执行。
- 状态键由宿主固定为当前游戏 ID，JavaScript 无法选择其他游戏。
- 所有修改器资源来自 APK assets，不从网络下载代码。
- 普通 Tyrano、VN、WebOther 页面不注册修改器 Bridge。

## 7. 验收标准

- MV/MZ 游戏进入标题和地图场景时均不因修改器注入报错。
- 浮动按钮可打开菜单，关闭后游戏输入恢复。
- 返回键先关闭菜单，菜单关闭时保持原双击退出行为。
- 金币、物品、角色、开关、变量修改即时生效。
- 战斗 Hook 可独立开关，关闭后恢复原始行为。
- 修改器状态跨 Activity 重启保持，并且不同游戏互不影响。
- 存档删除通过应用私有/游戏目录存档桥生效。
- 全局关闭或单游戏关闭后不注入修改器资源和 Bridge。

## 8. 实施记录

实施完成后在此记录实际改动、测试结果、设备验证结果及尚存限制。
