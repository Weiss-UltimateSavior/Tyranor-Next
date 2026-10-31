# VN-SR 滤镜集

VN-SR 分类提供 C08 / C12 / C16 三种通道规模，均为原生 **2X**。
预设名称、分类、固定紫色色条和执行约束来自独立 `engine/src/main/graphicsfilters/vnsr.json`；
构建生成的目录将它们提供给既有设置菜单、尺寸规划器、GLES执行器和性能分析器。

## 新增来源和许可证

引入 VN-SR 0.1.0 项目的三份 GLES 导出着色器，来源项目明确将代码、权重和生成的VN-SR着色器采用MIT许可。
完整版权声明与MIT文本随资源保存在 `engine/src/main/assets/vnsr/LICENSE-MIT`。
原始导出文件存于 `engine/patches/vnsr/sources/`，精确来源及打包文件SHA-256见
`engine/patches/vnsr-filters.json`。公开来源仓库为 [AAAA1111-Lab/VN-SR](https://github.com/AAAA1111-Lab/VN-SR)，
固定 revision 为 `014d9145b935b1ead465f795b91b7bdf68176fcf`。三份原始 GLES 导出及 MIT 许可证均已与该提交逐字节核对，SHA-256一致。
此核对确认所保留文件与公开版本一致，不替代设备兼容性或模型效果验收。

普通构建按清单中仓库根相对的 `sources[].files[].localPath` 校验已保留来源文件的SHA，缺失、修改或越出engine目录会失败；
VN-SR 声明 `verification: retained`，来源文件和许可证必须提供localPath并通过SHA校验；纯外部来源记录仍只作描述。
`license.path` 指上游路径，`license.localPath` 指仓库保留文本；生成器固定MIT标识、`license.path`、来源 `repository`/`revision`/`version`、`assetDirectory`、来源数量及清单内记录哈希，任一漂移都会使 `--check` 失败。两者的校验边界明确划分：`tools/generate-vnsr-filters.py --check` 作为专有生成器严格固定唯一的已保留来源条目、`license.path` 与所生成记录哈希；而 Gradle 的 `VerifyGraphicsResources` 任务负责通用的资源贡献清单结构合法性校验，对各滤镜贡献中可能追加的纯描述性外部来源保持兼容。

## 宿主适配

使用来源项目 `shaders/gles` 版本：原尺寸RGBA4特征、四个部分更新块、12通道RGB残差及2X重建。
C24/C32 不再提供，也不打包其资源或保留其生成输入。
旁路特征继续引用上一层纹理，不增加复制绘制；各规模分别需要10/15/20个片元pass，
C16最宽pass需要4个输入纹理绑定；既有 `compileChain()` 会与设备 `GL_MAX_TEXTURE_IMAGE_UNITS` 比较，超限走原有失败处理。

`tools/generate-vnsr-filters.py` 保留原始GLES字节；描述符声明顶左逻辑坐标，
由[公共方向接口](graphics-filter-coordinates.md)同时适配采样与PixelShuffle相位，避免资产预翻转。
保留系数、偏置、激活、旁路绑定和双线性基图加RGB残差。不混用mpv版本的显式2X尺寸表达式。
中间特征沿用RGBA16F，原生2X后由共享显示路径执行默认Resize；
纹理、内存预算、分块和回退继续由既有执行器处理，各引擎沿用原有能力门禁。

生成器仅依赖Python标准库，先核对固定来源与许可证SHA，再生成宿主文件：

```sh
python tools/generate-vnsr-filters.py
python tools/generate-vnsr-filters.py --check
python tools/test-vnsr-coordinates.py
python tools/test-vnsr-generator.py
```

普通Android构建直接使用已打包资源，不读取权重或执行Python，也不下载任何模型。
滤镜贡献可通过移除自己的描述符、资源、来源清单和生成器独立撤回，不需要修改公共执行器或预设枚举。

## 验证边界

本轮执行的验证为静态与CPU/JVM级别：来源与许可逐字节SHA核对、三档残差与最终合成坐标等价（CPU回归阈值为1e-10，当次运行残差误差为0.0、最终合成误差为2.22e-16，另含最终合成的逐像素8bit量化一致独立断言）、
Anime4K六份Restore导出的CPU方向负对照、生成器与来源清单篡改负例、`texture_row_origin`编译期断言、
资源来源回归、图形专项JVM测试、Debug宿主native构建与上游CI检查。
未执行完整Release/R8 APK构建，也未执行任何实机验收；早期提交者“运行测试通过”的反馈是历史结论，
对应早期提交的构建，不作为本轮验收证据，不借用早期其它模型的测试结果，
也不将来源项目桌面GPU成绩作为移动设备性能承诺。提交者未逐项提供设备型号、系统、运行库身份、真实样本、GPU用时和单模型数据。
方向变换的依据是上游 `src/vnsr/export.py` 将权重的行偏移直接写入采样偏移，
`model.py` 则按顶左NCHW进行卷积和PixelShuffle。底左输入需同时适配采样Y及2×2相位，顶左输入则不翻转；
不能仅由Anime4K的另一份导出写法推断两者具有相同训练/导出坐标约定。
离线非对称卷积/相位回归只检查这一坐标变换恒等式，不替代真实模型的GPU golden对比。
因此不宣称完成跨设备兼容性、压力下预算回退、独立Y方向数值核对或帧时间专项验证。
静态资源校验和运行时保护不等同于这些设备专项验收。
本次验收未修改应用包名、版本或签名的仓库配置；本地测试构建通过Gradle属性使用独立包名。
CI与PR中的验证命令及链接单独记录；没有新增或更换任何原生引擎运行库。
