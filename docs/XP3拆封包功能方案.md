# XP3 拆封包功能方案（解包 / 封包独立工具页）

> 状态：**已实施**；实现说明见文末「实施记录」
> 目标：在应用设置内提供独立的「解包 / 封包」工具页（`ui/archive`）：解包=选目录扫描 XP3 封包、主从预览条目并整体解包到归档同名文件夹（同名拒绝，不自动改名）；封包=选目录压成同级同名 `.xp3`（0=明文存放，1–9 zlib 等级）。核心解封包逻辑为 Rust 实现（`engine/rust`，源自 UsefulUnpack，MIT）。
> 关联：`CONTEXT.md`「拆封包」术语节、`README.md` 致谢（UsefulUnpack）、`docs/Artemis基础补丁与Windows环境补丁逆向分析.md`（启动链 PFS 解包，与本功能互不依赖）。

---

## 1. 需求与范围

| 项 | 要求 |
|---|---|
| 入口 | 应用设置页新增「解包 / 封包」跳转条目（`ArrowPreference` → `ArchiveUnpackActivity`，`exported=false`） |
| 解包 | 选目录 → 递归扫描 `.xp3`（深度 ≤6、数量 ≤500）→ 左右主从预览（左归档、右条目）→ 解到归档同名文件夹（已存在则**拒绝并提示**，不自动改名） |
| 封包 | 选目录 → 压成同级同名 `.xp3`（已存在则**拒绝并提示**）；SAF 专有 provider 下封到 cache 后交系统保存框 |
| 进度/取消 | 字节级进度（独立轮询线程读 Rust 全局进度槽），随时取消（哨兵异常 `ArchiveCancelledException`） |
| 文案 | 三语言（values / values-en / values-ja） |
| 后端 | **仅 XP3**；native 库仅 `arm64-v8a`（与全 App 一致） |

**非目标（不做）**：PFS/PF6/PF8 拆封包（见 §4 决策 1）；KSD 独立功能入口；XP3 分卷（`.xp3.001` 系列分卷包）；解包条目的选择性勾选（一期为整体解包）；xp3 内加密条目（COSTOM/krkrz 加密头）。

---

## 2. 架构

```
ui/archive（ArchiveUnpackActivity + ArchiveViewModel）
        │  仅依赖功能抽象层
        ▼
core/unpack（app）
  ArchiveScanner     目录树递归扫描（真实路径优先，SAF DocumentFile 兜底）
  ArchiveDetection   按名/按魔数判定 XP3（`58 50 33`）
  ArchiveStaging     SAF 双轨桥：可映射直用，否则拷入 cacheDir/archive_staging 中转，
                     产物经 publishDir 写回目录树（输出目录为本次新建，写入前校验为空）
  Xp3Archive         阻塞式门面：listEntries / extractAll / pack（调用方切 Dispatchers.IO）
  NativeArchiveOp    阻塞 native 调用 + 轮询进度 + 取消转译的公共脚手架
        │  JNI
        ▼
engine/src/main/java/com/core/archive（Xp3Core，懒加载 System.loadLibrary）
        ▼
engine/rust（单 crate cdylib，产物 libarchive_xp3_core.so）
  lib.rs             XP3 列条目/全量解包/选择性解包/打包 的 JNI 入口（panic → IOException）
  common.rs          进度槽（extract/compress 双 store）、ProgressWriter/Reader（写入点检查取消，
                     ErrorKind::Other 防止 io::copy 对 Interrupted 无限重试）、safe_join 路径逃逸防护
  ksd.rs             KSD mode-2 隐性解码（无独立入口，见 §4 决策 3）
```

## 3. 关键实现要点

- **单 flight 互斥**：ViewModel 同一时刻仅允许一个操作（`working` 门闩）；Rust 侧每格式只有一份全局进度槽，禁止并发调用同格式。
- **取消语义**：Kotlin `cancelFlag`/Job 取消 → 轮询线程点火 Rust `cancel()` → native 以 `cancelled` 错误返回 → 精确匹配后转 `ArchiveCancelledException`，尾检防「取消落在末条目被吞成成功」。
- **fail-closed**：封包失败/取消删除半成品输出；解包失败逐条目计数（`skipped`），致命错误抛 IOException 并本地化。
- **格式安全**：`safe_join` 拒绝绝对路径/`..`/NUL/盘符；解压上限（声明大小 + 解码 clamp）防 zlib 炸弹；打包文件大小与包体超 4GB 拒绝。
- **R8**：`consumer-rules.pro` keep `com.core.archive.**`（JNI 按字面名 FindClass）；构建产物验证保留。

## 4. 关键决策

1. **仅做 XP3，PFS 整条线移除**：PFS/PF6/PF8 的 Rust 实现（pfs-core）与 Kotlin 门面（PfsArchive/ArtemisPf6Packer/PfsCore）已删除，收窄维护面；Artemis 启动链的 PFS 解包属主线功能（`ArtemisPfsUnpacker`，纯 Kotlin），保持云端原样、与本功能零耦合。
2. **Rust 三 crate 合并为单 crate**：common/ksd-core/xp3-core 合并，`src/{lib,common,ksd}.rs`；删除 XP3 用不到的死代码（rar 分卷、BoundedWriter、KSD mode0/1 独立入口）。产物体积持平（477KB → 476KB），仓库少两个 crate 边界。
3. **KSD mode-2 隐性解码**：部分 KRKR 游戏把 XP3 内文本条目再包一层 KSD mode-2（`FE FE 02 FF FE` + 双 i64 长度 + raw deflate）。解包时对 ≤16MB 小条目缓冲探测、命中即顺带解成明文；依据是 krkrsdl3 `TextStream.cpp` 原生解码该包裹（mode0/1/2 皆支持），解开与否游戏都能跑，解开仅为了让导出文本可直接编辑。解不开一律原样写出。

## 5. 验收

- [x] 设置页入口进入工具页，解包/封包两模式切换
- [x] 扫描列出目录内 XP3（含子目录），空目录给本地化提示
- [x] 解包产物完整（打包→解包字节级往返，Rust 测试覆盖）
- [x] 封包产物可被再次解包（往返一致）
- [x] 取消中途操作，文案落「已取消」，半成品封包被清理
- [x] SAF 目录（无真实路径）经暂存中转解包/封包成功
- [x] `testDebugUnitTest`、`check-hardcoded-ui-strings.py`、`git diff --check` 通过

## 6. 实施记录

- **Rust**：`engine/rust` 单 crate（`Cargo.toml` cdylib + lto，`build-archives.sh` 跑 host 测试后 cargo-ndk 产 arm64 .so，NDK 版本对齐 engine/build.gradle）；进度槽测试与打包往返测试共用 `TEST_LOCK` 串行（合并单测试二进制后静态量互踩），并修 reader 测试漏清 cancel 毒化后续用例的问题
- **Kotlin**：`core/unpack` 新增 `ArchiveDetection/ArchiveScanner/ArchiveStaging/ArchiveCancelledException/ArchiveNativeMissingException/NativeArchiveOp/Xp3Archive`；`ui/archive` 新增 `ArchiveUnpackActivity/ArchiveViewModel`（ViewModel 常驻，旋转不丢扫描结果）；设置中心 `SettingsScreen` 增跳转条目（一级卡片）
- **文案**：三语言新增 `archive_*` 键（三侧齐全性已脚本核验）
- **文档**：`AGENT.md`（core/ui 域清单）、`README.md`（core/unpack 与 ui/archive 登记、engine/rust 目录、UsefulUnpack 致谢）、`CONTEXT.md`（拆封包术语节）
- **校验**：Rust `cargo test` 17/17；Kotlin `testDebugUnitTest` 346/346；`assembleDebug`（136M）与 `assembleRelease`（R8，112M）通过；release dex 确认 `com/core/archive/Xp3Core` 按名保留、`libarchive_xp3_core.so` 在包
