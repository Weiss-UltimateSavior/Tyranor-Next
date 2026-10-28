# 变更记录

## 未发布

### 修复

- 修复部分 RPG Maker MV/MZ 游戏使用 DKTools 时，语言目录存在却因缺少文件索引而报找不到 `locales/en.json` 的问题；覆盖 Tyranor-2.3.4 与 TynMV/TynMZ 宿主，保留原有索引，并修正 unpacked ASAR 流式读取。

- 修复 [#133](https://github.com/Weiss-UltimateSavior/Tyranor-Next/issues/133)：撤回默认风格顶栏渐进模糊及其宿主接入（`c71f57d`、`3dd5d02`），恢复顶栏与正文上下分离布局，以处理切页性能回归。底栏/侧栏/悬浮按钮的玻璃采样、默认主题渐变与 WebView 采样修复保留。
