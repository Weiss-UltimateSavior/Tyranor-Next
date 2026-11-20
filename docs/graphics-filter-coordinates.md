# 滤镜纹理方向接口

> **更新（当前实现）**：Kirikiroid2 1.2.6 内核已从随包运行库中移除，本文涉及 1.2.6 的适配、身份哈希与显存预算仅作历史记录；当前随包版本为 auto / 1.3.9 / 1.3.4，以及 krkrsdl3 内核。

输入方向指纹理第0行在图像中的位置，不由OpenGL坐标原点或Android版本单独决定。
Surface中转并按显示UV归一化的官方Artemis、Siglus及帧缓冲截取的krkrsdl3采用底左输入。
KR2 1.3.4/1.3.9通过宿主拦截桥读取Cocos四边形的局部位置与UV对应关系；
只有轴对齐、四角齐全且方向一致的输入才能参与滤镜。旋转UV、重复角点、非有限值等跳过。
以sprite局部图像为准，不将后续有意的显示旋转当作图像存储方向变化。
历史版本中 1.2.6 的独立 PaintBox 位图接口曾声明顶左行序并保留原 UV（该版本支持现已随 1.2.6 内核一并移除）。
这些改动只编译宿主桥，不修改或替换KR2预编译引擎库。

描述符可声明 `topLeftCoordinates: true`，缺省保持原来的底左逻辑坐标。
公共执行器按“着色器逻辑方向是否不同于输入方向”设置 `u_FLIP_Y`；
同时转换 `NAME_pos` 与 `NAME_tex()`，`NAME_texOff()`使用逻辑坐标偏移。
每个pass和每个节点保持输入的物理行序，包括分块、重采样及最终显示；
不增加整帧翻转复制，也不改引擎的显示UV。

Anime4K导出声明顶左逻辑坐标，保留原始GLSL字节及其来源哈希。
VN-SR同样声明顶左；打包资产直接引用上游 [AAAA1111-Lab/VN-SR](https://github.com/AAAA1111-Lab/VN-SR) 在 `shaders/gles-mrt/` 下发布的 GLES 3.0 MRT 融合导出（经 `tools/generate-vnsr-filters.py` 校验 `retained` 字节与哈希后原样随包，保留其原始采样与 PixelShuffle 相位逻辑），方向适配全部由本接口在运行时设置 `u_FLIP_Y` 完成，资产本身不作预翻转。
未来新增滤镜应按来源的导出约定声明此字段，不能按算法名称猜测。

验证包括宿主NDK编译、方向识别的C++编译期断言、图形JVM回归和离线模型数值检查。
CPU检查以原始导出为参考，不等同于设备GPU/FP16或训练检查点验收；本文件所述坐标变换本身未做实机专项验证，
已完成的实机记录（宿主脏帧静态复用与滤镜链负载）单独见 [VN-SR 说明](vnsr-filters.md) 的实机复核节。

### 设备验收缺口清单

已记录的实机样本仅覆盖 `Adreno (TM) 840`（`1280×720`，宿主脏帧静态复用与滤镜链负载，见 [VN-SR 说明](vnsr-filters.md) 的「宿主脏帧静态复用与 Adreno 840 实机复核」节）；该记录采集于最终变基清理之前，当前最终修订尚未完成真机重跑回归。以下缺口仍开放，不得把上述历史样本外推：

- **Mali 及其他厂商 GPU**：krkrsdl3 / Siglus / producer 代理宿主的全量每帧滤镜路径未在
  Mali（或 ARM Mali 系列驱动）等未实测设备上验收；瓦片渲染 GPU 上的带宽/时序行为以验收为准。
- **宿主脏帧静态复用桥接**：官方 Artemis 单上下文与 Kirikiroid2 桥的 `frameDirty` 判定仅在 Adreno 840
  一台设备上过真实游戏负载；其他 GPU/驱动组合未验收。
- 新设备验收完成后应更新本清单，再解除对应外推限制。

### GLES3 config 升级边界

官方 Artemis 单上下文路线在安装桥时会按两段独立条件尝试把引擎的 EGL 请求升级到 GLES 3：
- **`hookEglChooseConfig`**：若传入属性要求 `EGL_OPENGL_ES2_BIT` 且未带 `EGL_OPENGL_ES3_BIT_KHR`，会先补上 ES3 位筛选 config；仅当未筛出匹配 config（或调用失败）时，才回退为使用调用方原始 `attribList` 重试 `eglChooseConfig()`。
- **`hookEglCreateContext`**：先查询最终选中的 `config` 是否声明支持 `EGL_OPENGL_ES3_BIT_KHR`，仅当支持且请求 `EGL_CONTEXT_CLIENT_VERSION == 2` 时才尝试将其升至 `3`；若升级后的 `eglCreateContext()` 失败，则记录 `es3UpgradeFailed` 并将 **context 创建属性** 回退为原始 `attribList`、同时 **保留同一已选中的 `config`** 重试 `eglCreateContext()`（不会重新执行 `eglChooseConfig()` 或更换 config）。
两步均不会改动任何引擎二进制；`eglChooseConfig()` 回退恢复的是原始 config 筛选属性，而 `eglCreateContext()` 回退仅恢复原始 context 属性（因此若第一步选中的 config 仅兼容或同时兼容 ES2/ES3，重试直接沿用该 config；两步失败后原有渲染/producer 路径保持不变）。该升级只作用于受支持内核自己的 EGL 调用，不影响其他引擎或宿主的上下文。
同一单上下文路线上，当宿主 `Display` 已设置有效的正值逻辑尺寸（`lw > 0 && lh > 0`）时，引擎对舞台 surface 的 `eglQuerySurface(EGL_WIDTH/EGL_HEIGHT)` 上报该游戏逻辑舞台尺寸（stage 输入宽高），而不是 producer 队列的物理缓冲尺寸；引擎据此重建自己的缩放与输入映射，滤镜链的显示适配由宿主侧另行完成。逻辑尺寸尚未初始化前、直出恢复或 producer 路径均不应用该覆盖，此时 `eglQuerySurface` 透传真实 surface 尺寸。
