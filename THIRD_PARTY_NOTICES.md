# Third-party notices

本文件记录代码/资产级第三方声明；引擎运行库与静态依赖的完整许可文本见 [README](README.md) 许可证表及各 `engine/*/licenses/` 目录。

## libjpeg-turbo（IJG / BSD-3-Clause）

System4 运行时内的 JPEG 编解码使用 libjpeg-turbo（IJG 许可 + Modified BSD 许可）。按 IJG 许可的分发条件，产品文档须包含以下声明：

> This software is based in part on the work of the Independent JPEG Group.

- IJG README（分发时必须原样保留）：[engine/xsystem4/licenses/README.ijg](engine/xsystem4/licenses/README.ijg)
- 上游许可说明与 Modified (3-clause) BSD 文本：[engine/xsystem4/licenses/libjpeg-turbo-LICENSE.md](engine/xsystem4/licenses/libjpeg-turbo-LICENSE.md)

## mochi-compose (MochiToast)

The in-app notice capsule (`app/src/main/java/com/tyranor/next/ui/common/notice/NoticeHost.kt`) follows the
interaction and motion design of `MochiToast` from **mochi-compose** by the mochi-compose contributors:
a capsule that grows out of a small top-center "island", continuous size morphing between consecutive notices
with blurred icon/text replacement, press to enlarge / glow / pause the timer, flick up to dismiss and a
rubber-banded downward pull.

- Project: [mochi-compose](https://github.com/feice11/mochi-compose)
- License: Apache License 2.0
- Copyright 2026, mochi-compose contributors

The Tyranor-Next implementation is a re-implementation on this project's own motion tokens, notice queue
(`NoticeCenter`) and theme system; constants such as the island size (120 x 36 dp), the press scale (1.04),
the follow distance (4 dp), the dismiss distance / velocity (24 dp / 600 px/s) and the exit timing (190 ms) are
taken from the original design. The original license text is available at the project URL above.

## Miuix (determinate progress indicator)

The drawing in `MotionLinearProgressIndicator.kt` is adapted from
`ProgressIndicator.kt` in [Miuix](https://github.com/compose-miuix-ui/miuix) 0.9.2.
Copyright 2025, compose-miuix-ui contributors. License: Apache License 2.0.
