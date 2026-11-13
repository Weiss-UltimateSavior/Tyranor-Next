# Third-party notices

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
