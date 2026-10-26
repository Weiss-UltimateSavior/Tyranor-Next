# WebDAV Android 设备验收记录

日期：2026-10-27，Asia/Shanghai。对应 PR #132，业务修复基线 `aad49a2`。

## 环境与隔离边界

| 项目 | 本次记录 |
| --- | --- |
| 设备 | PPG-AN00，arm64-v8a |
| 系统 | Android 17 / API 37 |
| 系统构建 | `PPG-AN00 11.0.0.110(SP5C00E110R104P1)` |
| 安全补丁 | 2026-09-01 |
| 原始屏幕 | 1264 × 2800，density 560，无 override |
| 原始字体比例 | 0.85 |
| 验收包 | `com.tyranor.next.webdavacceptance20261027` |
| 测试包 | `com.tyranor.next.webdavacceptance20261027.test` |
| 样本 | 唯一 UUID 的合成 ONS 存档、Artemis 资源及 ZIP，不含商业游戏数据 |
| WebDAV | 测试 APK 内 `127.0.0.1` 服务，合成账号，无外部服务器凭据 |

验收包为新安装的专用包，没有安装或清除正式包、旧的 `webdavtest` 包及其游戏存档。
测试必须同时满足专用 application ID 和 `-e webdav_device_acceptance true`，否则跳过；
UI 测试另要求初始游戏库为空、没有既有 WebDAV 加密载荷。各独立 profile 导出截图后再清理专用包。

`-PwebdavDeviceProbe=true` 仅给专用包的 debug 变体加入非导出的辅助 Activity：
它在独立进程触发真实 Application 生命周期回调，数秒后销毁 Activity，进程保持缓存。
它没有加载原生游戏运行库，也不代表 Siglus/FVP 等真实游戏兼容性验收。
未启用该参数的 Debug 和正式 Release manifest 均确认不含辅助 Activity。

## 实测结果

### 存档与生命周期

`SaveSyncDeviceTest` 6 项、`SaveRecoveryDeviceTest` 3 项，最终同轮 **9 通过、0 失败、0 跳过**，
耗时 39.568 秒。使用实际 Android Context、AndroidKeyStore、GameSaveManager、
SaveSyncService、SaveSyncRuntime、WorkManager、OkHttp 与 Android XML 解析器。

| 用例 | 断言 |
| --- | --- |
| 加密设置与显式切换 | 真实 Keystore 保存/解密；PULL 不覆盖活动存档；只有显式激活才导入 |
| S 先上传后继续游玩为 N | 保留 S→N 的来源时间；发布 N 后替代云端 S；其他版本保留 |
| 上传失败与缓存淘汰 | PUT 500 后成功重试；已见云端最新 ZIP 丢失后重新 GET 并校验 |
| 损坏 journal 救档 | 关闭 WebDAV 后仍可恢复；保护 ZIP 先救回历史，活动存档保持原样 |
| 启动失败 | 真实 prepare/finish 回滚 session 与启动宽限，不产生上传 |
| 首次启动前切换 | 从未产生宿主记录也可完成恢复版本上传；不会永久 busy |
| 缓存宿主退出与 Worker | Activity 活跃时 busy；退出后 PID 仍存在、Activity 数为 0，经过宽限变为空闲；无变化零 PUT，有变化由实际 Worker 上传并清除 session |
| Artemis 无提交证据恢复 | 当前新存档保留，缺失的 `system/data.pfs` 补回；旧存档保留到 `import_orphan_*` |
| 真实文件权限失败 | 对测试目录施加 0500 后复制确实失败；原备份和事务记录保留，无半个目标文件；恢复 0700 后重试成功 |
| 丢失目录与已证明提交 | 缺失目标回滚备份；匹配 marker/token 的提交完成资源收尾并清理 sidecar |

缓存宿主/Worker 用例覆盖“退出宿主后返回应用”的流程，并确认退出立即链出现 `SUCCEEDED`。
完全没有主界面 Activity 的后台试跑中，该 OEM 的 JobScheduler 显示 RARE 待机分组和未满足的
TIMING_DELAY，测试进程计时也出现长时间停滞。因此不能把本次结果解释为所有 OEM、
熄屏/省电状态下均可在固定秒数内上传。持久化 fallback 和下次前台补偿仍是必要边界。
没有为通过测试修改设备的全局省电策略。

### 界面

普通手机 profile 的 6 个方法中 **5 通过、1 按条件跳过**；跳过的唯一方法要求真实系统字体 2.0。
随后将设备字体暂设为 2.0，单独执行该方法，**1 通过、0 跳过**。因此六个不同 UI 用例均已有实际通过记录。

- 密码输入有正确 label、遮罩和 `Password` 语义，语义树不显示合成明文密码。
- 整库手动同步方向在 Activity 重建后保持。
- 初始不自动选择历史版本，激活按钮不可用；显式选中后才启用。
- 确认弹窗展示与列表一致的 S→N 两个时间。
- WebDAV 关闭后仍显示未完成切换的恢复入口。
- 两处页面均采集默认浅色、默认深色、复古玻璃、高级玻璃。
- 真正的 2.0 字体比例、横屏下，确认正文的滚动范围大于零；执行 ScrollBy 后正文位置改变，
  确认按钮仍可见且坐标保持不变。截图为滚动后的正文。

另外在同一手机上临时将 density 设为 240，约 842dp 宽，再跑两页四种主题截图用例，**1 通过**。
这是宽屏/平板宽度模拟，**不是实体平板验收**。结束后再次读取设备设置：
`font_scale=0.85`，`Physical density: 560`，无 density override。

### 构建与回归

- Windows 同步包 JVM 回归：**108 通过、0 失败、0 跳过**。
- `:app:assembleDebug`、`:app:assembleDebugAndroidTest` 通过，包含 Windows NDK 编译测试探针。
- 无验收参数的 `:app:assembleRelease` 通过，包含 R8 与 `lintVitalRelease`；未安装正式包。
- `python tools/check-hardcoded-ui-strings.py` 通过。
- `aad49a2` 的 Linux Android CI 与 macOS 全量 app JVM 均通过；macOS XML 汇总
  **921 项，920 通过、1 跳过、0 失败/错误**。
- 本次没有重跑完整 Android lint；此前报告的仓库既有 29 项 lint 错误不因 `lintVitalRelease`
  通过而被视为消失。

## 原始截图

PNG 直接来自设备 UiAutomation，没有裁切、缩放或重绘；`images/webdav-20261027/sha256.json`
列出所附 13 张原图的 SHA-256 与字节数。截图上的账号、游戏名和时间均为合成夹具。

| 主题 | WebDAV 设置 | 存档管理 |
| --- | --- | --- |
| 默认浅色 | ![WebDAV 默认浅色](images/webdav-20261027/phone-webdav-theme-default-light.png) | ![存档管理默认浅色](images/webdav-20261027/phone-save-management-theme-default-light.png) |
| 默认深色 | ![WebDAV 默认深色](images/webdav-20261027/phone-webdav-theme-default-dark.png) | ![存档管理默认深色](images/webdav-20261027/phone-save-management-theme-default-dark.png) |
| 复古玻璃 | ![WebDAV 复古玻璃](images/webdav-20261027/phone-webdav-theme-retro-glass.png) | ![存档管理复古玻璃](images/webdav-20261027/phone-save-management-theme-retro-glass.png) |
| 高级玻璃 | ![WebDAV 高级玻璃](images/webdav-20261027/phone-webdav-theme-advanced-glass.png) | ![存档管理高级玻璃](images/webdav-20261027/phone-save-management-theme-advanced-glass.png) |

| 场景 | 截图 |
| --- | --- |
| 版本下拉展开 | ![版本下拉](images/webdav-20261027/phone-save-management-dropdown-default-light.png) |
| S→N 切换确认 | ![切换确认](images/webdav-20261027/phone-webdav-activation-confirm-restored-times.png) |
| 真实 2 倍字体、横屏、正文滚动后 | ![2 倍字体长文案](images/webdav-20261027/phone-font2x-webdav-reset-policy-landscape-font2x.png) |
| 宽屏模拟：WebDAV 设置 | ![宽屏 WebDAV](images/webdav-20261027/tablet-width-simulation-webdav-theme-default-light.png) |
| 宽屏模拟：存档管理 | ![宽屏存档管理](images/webdav-20261027/tablet-width-simulation-save-management-theme-default-light.png) |

## 复现入口

不要用已经包含个人配置或游戏库的包执行。准备新的专用安装：

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon `
  -PwebdavDeviceProbe=true `
  -PTYRANOR_APPLICATION_ID=com.tyranor.next.webdavacceptance20261027

adb -s $device install -r app/build/outputs/apk/debug/app-debug.apk
adb -s $device install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

adb -s $device shell am instrument -w -r `
  -e webdav_device_acceptance true `
  -e class com.tyranor.next.core.game.save.sync.SaveSyncDeviceTest,com.tyranor.next.core.game.save.SaveRecoveryDeviceTest `
  com.tyranor.next.webdavacceptance20261027.test/androidx.test.runner.AndroidJUnitRunner
```

UI 类为 `com.tyranor.next.ui.save.WebDavUiDeviceTest`，截图输出到专用包的
`getExternalFilesDir("webdav-acceptance-evidence")`。切换 profile 前先导出图片并清理该专用包；
不要清理正式包或用户既有的测试包。`webdav_evidence_profile` 参数用于区分截图文件名。

2 倍字体专项方法为 `longResetPolicyDialogScrollsAtTwiceFontInLandscape`。
驱动端必须先保存原 `settings get system font_scale`，在 `try` 中设为 2.0 并执行专项，
在 `finally` 恢复原值；原值为 `null` 时删除覆盖值而不是写入字符串 `null`。恢复后重新读取确认。
宽屏 profile 同理先读取 `wm density`，临时设为 240，结束后恢复原 override 或执行 `wm density reset`。

## 未被本记录覆盖

外部 WebDAV 提供商、双实体设备并发、实体平板、API 26/27、TalkBack 实际朗读，
以及合法真实游戏在 Siglus/FVP/Framebuffer/ONS 等运行库中的保存/读取兼容性，
均不在本次通过声明内。密码语义断言、合成资源恢复、宿主辅助 Activity 和 Release 构建
不能代替这些项目的验收。
