# Web 壳固定端口与存档域（Tyrano / WebOther / VN）

> 问题：WebView 的 `localStorage` / `IndexedDB` 按**来源域（origin）**隔离，而本地 HTTP 服务此前用
> `ServerSocket(0)` 随机端口 → 每次启动 origin 都不同 → 网页侧存档（localStorage/IndexedDB）读不到，
> 表现为「存档丢失」。原版 Tyranor 用固定端口（`webGamePort`，默认 23333）规避了该问题。

## 实现

| 层 | 位置 | 行为 |
| --- | --- | --- |
| 设置 | App：设置 → 引擎设置 → Tyrano（Tyrano / WebOther / VN 共用同一 WebView 宿主设置）新增「Web 服务端口」 | 1–65535，默认 **23333**（与原版 `webGamePort` 一致，便于沿用旧存档域）；非法输入弹窗内禁存 |
| 存储 | `EngineSettingsStore.KEY_WEB_SHELL_PORT`（`web_shell_port`） | `normalizeWebShellPort`：1..65535 合法，空/0/越界/非数字回退 `LaunchContract.WEB_SHELL_PORT_DEFAULT` |
| 解析 | `EngineSettingsResolver` → `ResolvedEngineSettings.webShellPort` | 三级设置：`EffectiveEngineSettings.resolveWebShellPort`（单游戏覆盖合法值优先，非法/空回退全局） |
| 单游戏覆盖 | 单游戏设置 → Tyrano / WebOther / VN 卡片「Web 服务端口」 | `PerGameSettingsStore.F_WEB_SHELL_PORT`（`web_shell_port`）；输入 1–65535，非法/空在弹窗内禁存，可切回「跟随全局」 |
| 下发 | `EngineLauncher` Web 启动分支 | 仅 `TYRANO / WEB_OTHER / VN` 注入 `LaunchContract.WEB_SHELL_PORT`（RPG MV/MZ 保持既有的随机端口行为） |
| 宿主 | `TyranoActivity`（`intent.getIntExtra(WEB_SHELL_PORT, 0)`） | 端口参与 `behaviorSignature`：改设置后单游戏重启会重建会话 |
| 服务 | `TyranoLocalHttpServer(preferredPort)` → `com.core.web.WebShellServerSocket.bind` | 先绑期望端口（`127.0.0.1`，`setReuseAddress` 由 ServerSocket 默认处理）；被占用/非法则回退随机端口并置 `usedFallbackPort` |
| 提示 | `engine_web_shell_port_fallback`（引擎模块三语言） | 回退时 Toast「端口 X 被占用，本次改用 Y，网页存档可能不会延续」 |

## 说明与边界

- `RpgMakerLocalHttpServer` 同样支持 `preferredPort`（宿主暂不下发，保持两端对称与可扩展）；MV/MZ 存档走文件桥不受影响。
- RPGM 服务的每实例随机 Cookie 鉴权 token 与端口选择无关，固定端口不会削弱该防护。
- 端口被占用（例如另一个应用占用或上次会话残留占用）时为保证游戏可运行会回退临时端口，此时本次存档域与固定端口不同。
