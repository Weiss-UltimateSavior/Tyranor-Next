# PR195 平板真实页面证据

设备：Lenovo TB323FU，Android 16 / API 36，隔离包 `com.tyranor.next.pr195fix`。每轮新增 100 条私有原创游戏记录，以真实 `MainActivity` 和生产导航、瀑布流、游戏抽屉执行。素材为测试绘制的山形、星轨与编号，不是商业游戏素材。背景粒子关闭。

| 主题 | 横屏 | 竖屏 | 返回画面观察上界（横/竖 ms） | 正确抽屉观察上界（横/竖 ms） |
| --- | --- | --- | --- | --- |
| 默认浅色 | [截图](default-light-landscape.png) | [截图](default-light-portrait.png) | 573 / 521 | 1053 / 985 |
| 默认深色 | [截图](default-dark-landscape.png) | [截图](default-dark-portrait.png) | 626 / 569 | 1116 / 1064 |
| 复古玻璃 | [截图](retro-glass-landscape.png) | [截图](retro-glass-portrait.png) | 634 / 568 | 1647 / 1171 |
| 白雾玻璃 | [截图](milk-glass-landscape.png) | [截图](milk-glass-portrait.png) | 626 / 543 | 1446 / 1157 |

四轮均通过两方向隐藏旋转、末项完整卡片、触底、系统截图封面颜色及单次系统触摸打开正确游戏抽屉的断言。清理后原有 100 条隔离夹具集合保持一致，本轮记录剩余 0。

导航通过生产条目的语义点击动作，卡片验证使用单次原生触摸。Compose 测试规则自动推进动画时钟；表中由系统单调时钟记录的值包含空闲等待、断言、截图和抽屉响应，属于观察上界，不代表自然显示帧下的首个可交互时刻、硬件延迟或发布包流畅度。截图为实际屏幕合成的 PNG 原件，像素断言已执行；本轮工具未支持人工图像审看，未据此宣称视觉排版验收完成。

此处仅有修复后平板截图；手机矩阵、修复前对照、Activity 重建和逐帧零闪现仍未验收。执行说明及完整边界见 [返修记录](../pr195-revision.md)。

## 第三轮复跑

表中截图与数值来自第二轮构建。第三轮（合并 `4704d757` 后）在同一设备重跑四主题各两方向，断言全部通过且 `anchorIndex=99`、`singleCardTap=true`、`cleanupAfterCount=100 existingCount=100 ownedRemainingCount=0`；返回画面观察上界 599 / 532 ms（默认浅色）、618 / 531 ms（默认深色）、629 / 559 ms（复古玻璃）、621 / 586 ms（白雾玻璃），正确抽屉 1046 / 959、1088 / 946、1656 / 1131、1364 / 1179 ms。本轮改动只涉及恢复兜底、保存态校验、滑条取消回滚与解码档位口径，穷举确认网格选档结果与上一轮逐宽度一致，因此没有再次入库截图，避免仓库继续膨胀。四类设备回归同轮登记 52 项，实测 48 通过、4 条件跳过（3 个未传 `waterfall_diagnostics` 的诊断用例、1 个增强档专属参数组合），另有四主题各一轮 `GameScreenMainActivityAcceptanceDeviceTest` 通过。

## 第四轮复跑

表中截图仍来自第二轮构建，第三、四轮均未重新入库（第四轮只改注释、文档与恢复异常捕获，UI 代码零变更）。第四轮（合并 `ecbe80e1` 后）在同一设备重跑：四主题各两方向的画面与抽屉观察上界为 572 / 495 ms 与 1035 / 915 ms（默认浅色）、639 / 530 ms 与 1105 / 939 ms（默认深色）、634 / 534 ms 与 1576 / 1117 ms（复古玻璃）、630 / 542 ms 与 1362 / 1152 ms（白雾玻璃），四组均 `anchorIndex=99`、`singleCardTap=true`、`engineLaunch=false`、`cleanupAfterCount=100 existingCount=100 ownedRemainingCount=0`。本轮除四主题各 1 项外，还重跑了 `MotionSliderDeviceTest` 16 项、`GridReflowDeviceTest` 12 项、`WaterfallRotationScrollDeviceTest` 20 项、`WaterfallRestoreResponsivenessDeviceTest` 4 项，合计登记 56 项：52 通过、4 条件跳过（3 个未传 `waterfall_diagnostics` 的诊断用例、1 个增强档专属参数组合）、0 失败。
