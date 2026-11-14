# BGI 存档读取修复实施计划

## 当前边界

原先只在 JNI 宿主拦截 `openat` 的方案经 VM 回归核对后不成立，宿主试作已撤回。用户已允许限定范围的最小内核修复；当前补丁链通过 `0004-virtual-user-file-reads.patch` 实现该修复，不将其描述为宿主文件打开兼容层。

限制模式默认资源根为 `bgi-resource` 虚拟目录。补丁 0004 让 VM 读取与存在性入口只接受带分隔符的精确 `bgi-resource/`、`bgi-user/` 虚拟根，避免任意相对路径放行；并让受限 `RuntimeTraceApi.read_disk_file`、`disk_file_exists` 仅对 `user_data_alias` 结果以 `bgi-user/` 开头的路径使用映射，其它路径保持原样。`FilePolicy` raw API 未改，普通资源读取语义保持原样。游戏内真实槽位验收涵盖保存、界面识别、立即读回与重开读档。

现有 ABI v1 不提供文件系统回调或虚拟根配置。仅修改 Java/JNI 路径参数、异步镜像、权限或文件打开钩子不能解决 VM 内的提前返回，因此本次允许并采用源码补丁与运行库重建；修改运行中的内核机器码不在方案内。

## 已确认的最小方案

用户已允许修改并重新构建内核，实施范围限定为以下步骤；原始内核仓库保留不动，在独立构建目录应用补丁。

1. VM：已在补丁 0004 中让读取/存在性入口识别带分隔符的精确虚拟根 `bgi-resource/` 与 `bgi-user/`（正反斜杠）；仍将文件访问交给原文件 API，不把任意相对路径放行。
2. 游戏文件 API：已在补丁 0004 中让受限 `RuntimeTraceApi.read_disk_file` 与 `disk_file_exists` 在 `user_data_alias(path)` 以 `bgi-user/` 开头时使用映射结果；受限 `load_file_bytes` 只对获准的非命名归档调用使用作用域用户别名，映射缺失不回退到 raw resource；其它 `read_runtime_bytes` 和普通命名归档仍使用原路径。不得改写 `FilePolicy` raw helper。
3. 保留安全边界：仅 `UserData/**`、根级 `BGI.gdb` 与 `BGI####.cad`（四位数字）；一层重复前缀、大小写、符号链接拒绝、路径逃逸拒绝与写入者锁不退化。
4. 补丁 0004 已加入 VM 与 RuntimeTraceApi 源码回归；固定补丁来源、顺序及哈希，重建 arm64 库并同步 provenance。不得把本地补丁产物标为 clean 或 release-ready。
5. Android VM 夹具覆盖 `Sys80:32` 写入后 `30/31/34/35` 完整读取、范围读取、存在性与大小查询，以及新会话重开；另以 `Sys81:32/35` 覆盖 generic Header 加载和大小查询。增加缺档、普通归档资源和别名负例。
6. 真实游戏只在授权副本中测试：空槽保存、立即识别、读档、退出后重开读档。随后执行原有启动/关闭 ACK/存档管理回归；生成文件或合成夹具通过不能代替商业游戏验收。

## 分工与产物

- 实现 agent：最小内核补丁与可复现构建，禁止顺带更改渲染、音频或游戏脚本。
- 独立审查 agent：核对虚拟路径判定及原始资源/用户文件 API 边界，检查安全回归。
- 回归 agent：原创 VM 夹具与失败判据，不通过改变 primary root 绕开原问题。
- 主 agent：审查、固定运行库身份并负责设备与真实游戏验收。

内核基线 revision 为 `0a52a7970673c4f64f59149a7062df1c92c4b51f`；原有三项补丁保持原状，新修复作为第四项补丁单独维护，涉及 `crates/ethornell-vm/src/resource_io.rs` 与 `crates/ethornell-app/src/lib.rs`。Android VM 验收覆盖 Sys80 loose-file 与 Sys81 generic Header 路径；真实游戏验收范围为授权副本中的槽位保存、界面识别、立即读回及重开读档。不以源码夹具、文件生成或构建替代真实游戏兼容性结论。截图、日志及备份放在 `/tmp`，不提交验证记录。
