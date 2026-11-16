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
| compatible host | `majiro-emulator` 0.5+，versionCode 23+ |

模块由 TyranorNext 的「引擎模块管理」页发现，初始为关闭状态。用户启用后手动扫描游戏目录，识别到的记录统一以 `EngineType.RUNTIME_MODULE` 入库，游戏卡显示 `Plugin`。当前宿主实现最低要求为 `majiro-emulator` 0.5（versionCode 23）；第三方模块协议本身不提供版本门禁，用户应安装匹配版本。

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

- 当前游戏目录的绝对路径；
- 游戏标题；
- 协议版本 `1`。

不会传 SAF URI、URI 授权、`game`/`settings` JSON、存档路径、按键映射或画面参数。`majiro-emulator` 在模块进程内校验目录并读取文件，自己的 `GameBridge` 同时支持绝对路径目录和宿主自身的 SAF 游戏库。

## 边界

- 模块卸载或禁用后，绑定游戏降级为 `Unknown`，保留最后已知包名，重新启用并扫描后恢复。
- TyranorNext 不提供 Majiro 单游戏设置入口，不显示统一存档管理，也不请求或转授模块所需的文件权限。
- 模块版本、渲染、音频、触摸、存档和真实游戏兼容性由 `majiro-emulator` 自己负责。

相关规范：[第三方 APK 运行时模块接入指南](第三方APK运行时模块接入指南.md)。
