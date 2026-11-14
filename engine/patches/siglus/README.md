# Siglus：读取运行时生成的密钥，保持上游原库

本轮按提交者要求改用宿主解析游戏配置的路线，`libsiglus.so` 恢复为 TYN main
`740d7c130fce019350e135066478de61fa9b439e` 的原文件，不编译或提交改造过的 Siglus 引擎库。
原库 SHA-256 为 `fdef411e1f30550ef7adb7ab4ff1f383433aee94be7609a481eb18c13c3563df`，
与该上游提交的库逐字节一致；登记见 [原库及配置参考清单](../siglus-bundled.json)。

## 尺寸取得与输出

开启有效滤镜链时，先通过原有 `siglus_android_create` 创建引擎，仅传宿主私有 producer；引擎按自身流程恢复资源密钥，必要时在游戏根目录生成
`key.toml`。创建成功后，宿主在后台只读该文件和引擎首启动实际选择的 Gameexe 配置，解密/解压后
读取最终的 `SCREEN_SIZE` 定义（最终定义非法则拒绝）。不通过屏幕尺寸、素材尺寸或默认 1280×720 猜测舞台尺寸。

配置尺寸可信后，通过原有 `siglus_android_set_surface/resize` 指定同尺寸离屏 Surface，
随后由宿主消费完整画面、执行共享滤镜链并适配屏幕。文本、UI 和转场仍属于合成画面。
宿主不读取 EXE、汉化 `.chs` 或 Scene 包恢复密钥，不向游戏目录写入任何文件。
需要游戏密钥的模式缺少 key、现存 key 非法、配置选择有歧义、不支持的解码覆盖或资源不安全时保留私有启动拷贝。明文和固定 mask 模式不强制 key。

这里的输入尺寸来自配置和原有输出接口的尺寸契约；原库没有导出运行时舞台/viewport 查询，
因此不再宣称对内部 renderer 的实际尺寸与有效画面区域做了几何回读验证。

## 同步与限制

删除 `siglus_android_get_stage_geometry_v1`、对应 JNI/Java 桥和 render serial 依赖。
开启有效滤镜链时，引擎创建、步进、输入、IME、窗口连接和销毁全部由同一专用线程串行执行；
UI 只提交有界请求，Choreographer 至多保留一个排队或执行中的 step，GLES 消费使用独立线程。
输出队列自身承担生产/消费背压，因此原库跳过绘制的正常读档或脚本等待不再被误判为滤镜失败，
不需要内部 render serial。性能统计只依据实际消费/呈现的新画面。

仅滤镜路径的显示由 TextureView 承接：已接管窗口的 `onSurfaceTextureDestroyed` 返回 false，客户端负责
最终 SurfaceTexture.release()。独立租约分别覆盖 UI、待执行/执行中命令、已连接 EGL 和故障保留。
这是 [Android TextureView 回调的所有权契约](https://developer.android.com/reference/android/view/TextureView.SurfaceTextureListener#onSurfaceTextureDestroyed(android.graphics.SurfaceTexture))，
并非用 Java Surface 快照延长 SurfaceHolder 的许可期。进程最多四个显示租约；显示租约与既有两个
GLES owner 资源席位同时限制正常、关闭中和永久故障保留资源。

滤镜路径的原生引擎始终仅连接私有 producer；开启滤镜但无可信配置时保留启动拷贝，不中途切到原版。启动拷贝请求尺寸不算原始舞台。
舞台候选准备成功后，旧 GLES owner 先确认断开显示 EGLSurface，再提交原接口的私有 producer
替换；ACK 成功后释放旧 producer 并连接最新显示窗口，不依赖开始交接的旧窗口世代。
候选未交给 native 前初始化失败，继续健康的启动拷贝；无法确认原生或 EGL 所有权时停止呈现，
保留相关资源直到有序关闭，不通过并发销毁或原生直连屏幕绕过错误。

控制命令使用一个独立合并槽，普通输入的 64 项配额不会挤掉 create/set_surface。
已执行命令自行完成及释放，待执行旧命令显式取消；普通重复解绑不能取消舞台交接屏障。
显示就绪和首帧回调在主线程再次检查世代，迟到 A 回调不能激活 B。
关闭先停止新请求，由 owner 在线程内销毁 VM 后再释放对应 producer；若 native 调用永久阻塞，
保持 UI 响应并保留有界 owner/consumer，不从另一线程强行销毁正在使用的句柄或无上限重建。
首次创建中的资源密钥恢复不设任意十秒失败期限；普通调用的忙碌提示仅为诊断，不永久停止帧循环。

兼容性取舍：只有开启有效滤镜链的路径新增 GLES 3.0 与 external OES 依赖及显示拷贝开销。关闭、空链和无效链均使用上游原版显示逻辑。
启动消费者不可用时显示既有初始化失败提示并结束；没有原生直连回退。永久故障驱动不保证自动恢复。
提交者 2026-10-18 的原库候选验收不覆盖这次 TextureView 改动；本轮只运行 CI，无新的实机结果。
当前修复详情见 [PR #108](https://github.com/Weiss-UltimateSavior/Tyranor-Next/pull/108)；此前窗口报告以其固定 head 为历史范围。

## 关闭滤镜的原版路径

启动前仅在生效开关打开且链含有效节点时选择滤镜路径。关闭、空链或无效链恢复上游
SurfaceView + 主线程同步 JNI + Choreographer：surfaceCreated 同步 create/setSurface，
surfaceChanged 同步 resize，surfaceDestroyed 仅停止步进并保留 VM，Activity 销毁才 destroy。
首个正常 step 后隐藏加载层；直接输入使用原始窗口坐标，不经过舞台缩放换算。

该分支不创建宿主 GLES、TextureView、私有 producer、滤镜工作线程或舞台配置读取任务。
原有引擎创建中的资源/密钥处理以及标题回写仍由上游逻辑完成；不添加格式解析或显示拷贝。
同步 native 调用仍可能阻塞主线程，这是保留的上游行为。

两个模式只共享小型宿主接口与进程级原生实例准入，不复制 Activity，不在运行期间切换线程。
旧异步 owner 尚未销毁或永久阻塞时，关闭滤镜的新启动也不能绕过全局单实例限制。
消息框统一异步投递并核验 owner/句柄，以免 UI 同步 native 回调中重入或回应已释放实例。

## 移除旧重编方案

本轮移除舞台几何 Rust 补丁、旧 ABI 锚点、重编 manifest、构建脚本及专属重编 CI，
Android/Beta 工作流不再编译 Siglus Rust 运行库。普通构建继续校验已打包原库和许可证 SHA，
执行宿主 JVM 回归并编译正常项目 JNI 桥；这不产生要提交的预编译引擎库。
旧重编方案的历史审查与 CI 不能替代本路线的验收；原库路线旧候选曾通过 CI 与提交者测试；本轮窗口修复的验证单独记录，见当前审查报告。

## 格式参考与许可证

配置选择/格式参考固定 `xmoezzz/siglus_rs` 提交
`5f358ef1360b0439e92aed2b749e8f25d8721954` 的 `resource.rs`、`host.rs` 和 `siglus_assets`。
Java 格式读取器独立实现；完整 GAMEEXE 固定 XOR 表来自其 `keys.rs`，独立文件保留 MPL-2.0
署名及来源，完整许可放在 `engine/src/main/assets/siglus-stage-size/LICENSE.MPL-2.0.txt`。
未引入新的原生辅助库、密钥破解工具或新增下载依赖。
