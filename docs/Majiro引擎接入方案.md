# Majiro 第三方运行时模块接入方案

Majiro 通过独立的 `majiro-emulator` APK 作为 TyranorNext 第三方运行时模块运行。TyranorNext 不打包 Majiro 原生运行库，不实现专用 `EngineType`，也不接管模块的设置、输入、存档或存储权限。

## 模块身份

| 项目 | 值 |
| --- | --- |
| package | `com.tyranor.runtime.majiro` |
| action | `com.tyranor.runtime.action.LAUNCH` |
| entry Activity | `org.majiro.player.MainActivity` |
| protocol | `1` |
| launch extra | `com.tyranor.runtime.extra.FOLDER_PATH` |
| SAF root extra | `com.tyranor.runtime.extra.GAME_ROOT`（仅当 `Intent.data` 为 tree URI 时使用） |
| 已核对的发行 APK | `majiro-emulator` v0.5（versionCode 26，含 SAF 接收逻辑） |

模块由 TyranorNext 的「引擎模块管理」页发现，初始为关闭状态。用户启用后手动扫描游戏目录，识别到的记录统一以 `EngineType.RUNTIME_MODULE` 入库，游戏卡显示 `Plugin`。模块协议不提供版本门禁；旧的 `org.majiro.player` APK 不能被发现，仅有路径接收逻辑的构建不能消费 SAF 授权。v0.5 发行 APK 虽能解析 SAF 载荷，但未为 `content:` 单独声明启动过滤器；严格匹配 Intent 的系统需要包含该过滤器的模块构建。

## 扫描描述

模块在 `res/raw/tyranor_runtime.json` 中声明：

```json
{
  "protocol": 1,
  "label": "Majiro Emulator",
  "scan": {
    "match": "any",
    "extensions": [".mjo", ".arc"],
    "markers": ["majiro.env"]
  }
}
```

这是第三方模块协议允许的静态名称特征。TyranorNext 不读取 ARC/MJO 文件头，也不把 Majiro 识别规则加入内置扫描器；模块启用后，较宽的 `.arc` 特征可能需要用户通过模块开关控制扫描范围。

## 启动流程

TyranorNext 使用 `RuntimeModuleLauncher` 显式启动模块，只传：

- 当前游戏目录的绝对路径（本地路径可用时）；
- 当前游戏目录自身的 SAF tree URI、其根 document ID 与临时只读前缀授权（主 App 持有相应授权时）；
- 游戏标题；
- 协议版本 `1`。

Majiro 模块不声明 TyranorNext 的单游戏设置或存档能力，不接收配置 JSON、存档路径、按键映射或画面参数。收到两种入口时，`majiro-emulator` 优先使用 SAF tree；未收到 tree 时才使用绝对路径，路径本身不授予文件访问权限。

共享存储游戏应保留父级扫描根用于入库，并在 TyranorNext 的系统目录选择器中另外选中游戏目录本身。只选择包含多款游戏的父级目录不会转授该目录；缺失或失效的游戏授权仅在有可解析路径时回退，模块仍须能读取该路径。模块应保留不带 data 的发现/路径过滤器，同时声明接受 `content:` 的同 action 过滤器。真实游戏能否启动仍需设备验收。

## 边界

- 模块卸载或禁用后，绑定游戏降级为 `Unknown`，保留最后已知包名，重新启用并扫描后恢复。
- TyranorNext 不提供 Majiro 单游戏设置入口或统一存档管理，不授予写入或持久访问，也不转授父级扫描树。
- 模块版本、渲染、音频、触摸、存档和真实游戏兼容性由 `majiro-emulator` 自己负责。

相关规范：[第三方 APK 运行时模块接入指南](第三方APK运行时模块接入指南.md)。
