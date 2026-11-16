# 第三方模块协议示例

独立 Android APK，仅用于配置参数与文件存档接入验收，不加入主 App 构建、不包含游戏运行时。四种设置类型和 `saves` 声明见 `src/main/res/raw/tyranor_runtime.json`。

从仓库根目录构建（使用已配置的 Android SDK 与 JDK 17）：

```sh
ANDROID_HOME=/path/to/sdk ./gradlew -p tools/runtime-module-example assembleDebug --no-daemon
```

安装前确认设备没有使用相同包名的自有应用。APK 位于 `build/outputs/apk/debug/`，包名为 `com.tyranor.runtime.example`。示例只接受 `Download/TyranorRuntimeExample` 的直接子目录，避免外部 Intent 让示例写入任意游戏。

1. 在上述父目录创建 `sample-a`、`sample-b` 两个子目录，各放一个空的 `game.tyranor-example` 文件；示例不会自动创建或删除游戏。
2. 在主 App 的「引擎模块管理」启用示例模块，将该父目录加入扫描根并扫描。
3. 分别打开两个游戏的「引擎设置」，修改四类设置并保存，检查两游戏配置独立。
4. 启动游戏，示例显示收到的原始配置 JSON。文件访问由模块自己申请，主 App 不转授权限；Android 10 及以下使用设备设置授予示例所需存储权限。
5. 点击「写入测试存档」创建 `saves/state.json`，结束示例并在主 App 确认外置游戏退出。
6. 从「存档管理」验证导出、删除、导入、版本创建与恢复；配置 WebDAV 后验证双向同步，比较恢复文件与原文件内容。

未提供 settings 的模块继续使用旧启动参数。该示例显示协议与读写链路，不能证明第三方真实引擎或商业游戏的兼容性。
