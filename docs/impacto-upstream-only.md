# Impacto 原版上游接入草稿

此草稿面向 `Weiss-UltimateSavior/Tyranor-Next` 的 `main`，从当前上游基线
`e04f9a15` 建立。它取代已关闭的 [PR #161](https://github.com/Weiss-UltimateSavior/Tyranor-Next/pull/161)
的引擎补丁路线。本 PR 先建立引用、编译和来源验证工具，宿主接入在同一 PR 内继续推进。

## 约束与当前行为

- 直接引用 [CommitteeOfZero/impacto](https://github.com/CommitteeOfZero/impacto)，用完整 commit SHA 固定版本。
- 不修改 Impacto 源码、profile、着色器，不应用 TYN 引擎补丁，不以生成修改副本或符号替换绕过此约束。
- Android 生命周期、权限、目录、启动参数和存档隔离由 TYN 宿主适配；宿主不能解决的上游问题明确记录，不维护引擎分叉。
- 不增加 TYN 的 SDL 或其它第三方源码补丁。上游自身依赖构建配方保持原样，并在来源记录中说明。
- 原版构建产物先留在独立候选目录。当前 PR 没有把旧补丁库、旧源码归档、私有 SDL 类或商业游戏数据带入应用，也尚未新增可启动的 Impacto 引擎入口。

## 测试样本

本次选定 **Memories Off 6 ~T-Wave~（秋之回忆6）**，对应上游 `mo6tw` profile。
[上游支持列表](https://github.com/CommitteeOfZero/impacto/issues/1) 明确列出 **PS3 版**；PS Vita 仅注明可能可用且未测试。
用户正在下载，尚未确认平台和语言；不能仅凭游戏标题判断兼容。

上游 profile 要求数据目录内有 `BG.CPK`、`BGM.CPK`、`CHARA.CPK`、`MASK.CPK`、
`MOVIE.CPK`、`SCRIPT.cls`、`SCRIPT/`、`SE.CPK`、`SYSTEM.CPK` 和 `VOICE.CPK`。
不改写用户的商业游戏数据。上游 README 认为该游戏可推进全部结局，同时说明没有游戏完全实现；这不等于 TYN Android 端已验收。

## 构建和更新

`engine/impacto/source-lock.json` 固定引擎和构建依赖版本。
源码必须是独立 Git checkout：校验器拒绝父仓库冒充、错误 HEAD、暂存或未暂存修改、
未跟踪或忽略文件、稀疏检出、隐藏 index 标记及未初始化/已修改的子模块。
这是固定 revision 和 Git 洁净性检查，不是游戏兼容性测试。

```sh
python3 tools/impacto/verify-upstream.py --source /path/to/impacto
python3 tools/impacto/test-upstream-integrity.py
```

升级流程：更新完整 SHA 和必要的依赖锁，使用新构建目录，重新编译原版库，核对 JNI/CLI/资源契约，
更新运行库与配套源码，执行真实游戏和生命周期测试。任何阶段失败都保留日志并停止替换运行库。

## 宿主迁移待办

- [x] 从 TYN 上游共同祖先建立独立分支，不合入私有实验仓库的独立根历史。
- [x] 固定来源，增加原版 checkout 校验和真实 Git 仓库回归。
- [ ] 完成原版 ARM64 编译；记录工具链、完整源码及产物身份。
- [ ] 适配原版 `-g`、`-bp`、`-gc`、`-uc` 参数，不发送自定义 `--tyranor-resource-root`。
- [ ] 在宿主提供 `getChosenDirectory`、`chooseDirectory`、`resetChosenDir` 回调；控制原版 CWD、配置回退及 ImGui 相对写入位置。
- [ ] 解决原版 SDL3 `org.libsdl.app` 与 TYN 已有 SDL2 同名 Java 类的冲突。独立进程本身不能消除同一 APK 中的类名冲突。
- [ ] 完成资源验证、按游戏隔离的存档和持久 TOML 管理，不继承旧引擎补丁的假设。
- [ ] 接入扫描、启动、运行库分片及三语言文案，并验证原有 SDL 引擎。
- [ ] 完成 PS3 游戏数据的标题、正文、声音、输入、存读档、前后台及退出重启验收。
- [ ] 完成 API 28、16 KiB 页设备与 minified Release 检查。

原版库编译、宿主接入和真实游戏测试未完成前，此 PR 保持 Draft，不作为可发布功能。
