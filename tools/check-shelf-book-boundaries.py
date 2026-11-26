#!/usr/bin/env python3
"""Offline structural boundary check ensuring ShelfOpenBook entries stay inside book leaves."""
import argparse
from bisect import bisect_right
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
SRC_MAIN = Path("app/src/main/java/com/tyranor/next")

MASK = re.compile(r'''//[^\n]*|/\*|"""[\s\S]*?(?:"""|\Z)|"(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*' ''', re.X)
IMPORT = re.compile(r"(?m)^[ \t]*import[ \t]+[\w.*]+(?:[ \t]+as[ \t]+\w+)?[ \t]*;?")

# 受「翻开的书」书页约束的文件：全文件范围内禁止拉起全局 Activity、全局弹窗或底部抽屉。
BOOK_ONLY_FILES = (
    "ui/game/ShelfBook.kt",
    "ui/game/ShelfBookMotion.kt",
    "ui/game/GameQuickActionsDialog.kt",
    "ui/game/PlayStatusDialog.kt",
    "ui/game/ArtemisPatchChoiceDialog.kt",
    "ui/game/RpgSaveFormatDialog.kt",
    "ui/game/GameShortcutCropDialog.kt",
    "ui/runtime/RuntimeModuleFileImport.kt",
)

# 双宿主复用文件（既用于书页又用于二级页/抽屉）中，属于书内路径或共同正文的函数切片。
BOOK_SHARED_FUNCTIONS = {
    "ui/game/GameScreen.kt": (
        "GameActions",
        "LaunchFileDialog",
        "CoverSourcePickerDialog",
        "CoverSearchDialog",
    ),
    "ui/save/SaveManagementActivity.kt": (
        "SaveManagementDialog",
        "SaveManagementContent",
    ),
    "ui/patch/KrkrOnlinePatchActivity.kt": (
        "KrkrOnlinePatchDialog",
        "KrkrOnlinePatchContent",
    ),
    "ui/settings/PerGameSettingsScreen.kt": (
        "PerGameSettingsDialog",
        "PerGameSettingsView",
    ),
    "ui/settings/SettingsScreen.kt": (
        "WinlatorGlobalSettingsDialog",
        "WinlatorGlobalSettingsCard",
    ),
    "ui/runtime/RuntimeModuleSettingsScreen.kt": (
        "RuntimeModuleSettingsDialog",
        "RuntimeModuleSettingsView",
    ),
}

# 允许先经过 LocalAppDialogHost.current 接管并 return 后，才为抽屉/常规入口保留 1 个自绘全屏 Dialog() fallback 的函数。
HOSTED_RAW_DIALOG_FALLBACKS = {
    ("ui/game/GameScreen.kt", "CoverSearchDialog"),
    ("ui/game/GameShortcutCropDialog.kt", "GameShortcutCropDialog"),
}

# 禁止在书内及共用正文中出现的全局跳转与全局窗口调用。
FORBIDDEN_GLOBAL_UI = (
    (re.compile(r"\bstartActivityWithPageTransition\s*[({]"), "书内条目不得调用 startActivityWithPageTransition，请走 GameActionsHost.openDetail 或 AppAlertDialog"),
    (re.compile(r"\bstartActivity\s*[({]"), "书内条目不得直接调用 startActivity，请留在书页内实现"),
    (re.compile(r"\b(?:AppBottomSheet|ModalBottomSheet)\s*[({]"), "书内条目不得拉起全局底部抽屉（AppBottomSheet / ModalBottomSheet）"),
)
RAW_DIALOG_RE = re.compile(r"(?<![.\w])Dialog\s*[({]")


def blank(text: str) -> str:
    return re.sub(r"[^\n]", " ", text)


def code_only(source: str) -> str:
    """Strip comments and string literals while preserving offsets and newlines."""
    parts = []
    pos = 0
    while match := MASK.search(source, pos):
        end = match.end()
        if match[0] == "/*":
            depth = 1
            for mark in re.finditer(r"/\*|\*/", source[end:]):
                depth += 1 if mark[0] == "/*" else -1
                if depth == 0:
                    end += mark.end()
                    break
            else:
                end = len(source)
        parts.extend((source[pos:match.start()], blank(source[match.start():end])))
        pos = end
    return "".join(parts) + source[pos:]


def function_span(code: str, name: str) -> tuple[int, int] | None:
    """Find (header_start, body_end) for top-level `fun <name>(...) { ... }`."""
    match = re.search(rf"(?m)^\s*(?:internal\s+|private\s+|public\s+)?fun\s+{re.escape(name)}\s*\(", code)
    if not match:
        return None
    start = match.end() - 1
    paren_depth = 1
    end_sig = -1
    for idx in range(start + 1, len(code)):
        ch = code[idx]
        if ch == "(":
            paren_depth += 1
        elif ch == ")":
            paren_depth -= 1
            if paren_depth == 0:
                end_sig = idx + 1
                break
    if end_sig < 0:
        return None
    brace = re.search(r"\{", code[end_sig:])
    if not brace:
        return None
    body_start = end_sig + brace.start()
    brace_depth = 1
    for idx in range(body_start + 1, len(code)):
        ch = code[idx]
        if ch == "{":
            brace_depth += 1
        elif ch == "}":
            brace_depth -= 1
            if brace_depth == 0:
                return (match.start(), idx + 1)
    return None


def line_number(line_ends: list[int], pos: int) -> int:
    return bisect_right(line_ends, pos) + 1


def check_segment(
    rel_path: str,
    code: str,
    start: int,
    end: int,
    line_ends: list[int],
    allow_root_dialog: bool = False,
    allow_hosted_fallback_dialogs: tuple[str, ...] = (),
) -> list[tuple[int, str]]:
    errors: list[tuple[int, str]] = []
    segment = code[start:end]
    for pattern, message in FORBIDDEN_GLOBAL_UI:
        for hit in pattern.finditer(segment):
            errors.append((line_number(line_ends, start + hit.start()), f"{rel_path}: {message}"))
    if not allow_root_dialog:
        allowed_ranges: list[tuple[int, int]] = []
        for fn_name in allow_hosted_fallback_dialogs:
            span = function_span(code, fn_name)
            if not span:
                continue
            fn_start, fn_end = span
            fn_code = code[fn_start:fn_end]
            raw_hits = list(RAW_DIALOG_RE.finditer(fn_code))
            if raw_hits and len(raw_hits) == 1:
                prefix = fn_code[:raw_hits[0].start()]
                if "LocalAppDialogHost.current" in prefix and (".Present(" in prefix or "AppAlertDialog(" in prefix):
                    allowed_ranges.append((fn_start, fn_end))
        for hit in RAW_DIALOG_RE.finditer(segment):
            abs_pos = start + hit.start()
            if any(lo <= abs_pos < hi for lo, hi in allowed_ranges):
                continue
            errors.append((
                line_number(line_ends, abs_pos),
                f"{rel_path}: 书内或共用界面不得直接调用 Compose Dialog() 另开全局窗口（未先通过 LocalAppDialogHost 接管），请使用 AppAlertDialog",
            ))
    return errors


def check_file(rel_path: str, source: str) -> list[tuple[int, str]]:
    code = IMPORT.sub(lambda m: blank(m[0]), code_only(source))
    line_ends = [m.start() for m in re.finditer("\n", code)]
    errors: list[tuple[int, str]] = []

    if rel_path in BOOK_ONLY_FILES:
        allow_root_dialog = rel_path == "ui/game/ShelfBook.kt"
        hosted_fallbacks = tuple(
            fn for file_key, fn in HOSTED_RAW_DIALOG_FALLBACKS if file_key == rel_path
        )
        errors.extend(
            check_segment(
                rel_path,
                code,
                0,
                len(code),
                line_ends,
                allow_root_dialog=allow_root_dialog,
                allow_hosted_fallback_dialogs=hosted_fallbacks,
            )
        )
        if rel_path == "ui/game/ShelfBook.kt":
            raw_dialogs = list(RAW_DIALOG_RE.finditer(code))
            open_book_span = function_span(code, "ShelfOpenBook")
            for hit in raw_dialogs:
                if not open_book_span or not (open_book_span[0] <= hit.start() < open_book_span[1]):
                    errors.append((
                        line_number(line_ends, hit.start()),
                        "ShelfBook.kt: 仅 ShelfOpenBook 根节点允许使用 Dialog() 承载整本书窗口",
                    ))
            if len(raw_dialogs) > 1:
                errors.append((
                    line_number(line_ends, raw_dialogs[1].start()),
                    "ShelfBook.kt: 不得新增额外 Dialog()，书内次级交互请使用 AppAlertDialog",
                ))
            # 确保 ShelfOpenBook 不直接接收跳转 Activity 的回调参数。
            if open_book_span:
                sig_slice = code[open_book_span[0]:code.find("{", open_book_span[0])]
                if "onEngineSettings" in sig_slice:
                    errors.append((
                        line_number(line_ends, open_book_span[0]),
                        "ShelfOpenBook 不得接收 onEngineSettings 等外部跳转回调，详情跳转须走 GameDetailDestination",
                    ))

    if rel_path in BOOK_SHARED_FUNCTIONS:
        for fn_name in BOOK_SHARED_FUNCTIONS[rel_path]:
            span = function_span(code, fn_name)
            if span is None:
                errors.append((1, f"{rel_path}: 找不到受保护函数 {fn_name}()，请检查重构是否绕开了边界守卫"))
                continue
            start, end = span
            hosted_fallbacks = (fn_name,) if (rel_path, fn_name) in HOSTED_RAW_DIALOG_FALLBACKS else ()
            errors.extend(
                check_segment(
                    rel_path,
                    code,
                    start,
                    end,
                    line_ends,
                    allow_hosted_fallback_dialogs=hosted_fallbacks,
                )
            )
            if rel_path == "ui/game/GameScreen.kt" and fn_name == "GameActions":
                fn_code = code[start:end]
                for forbidden in (
                    (r"\bLocalAppDialogHost\b", "GameActions 内不得逐条判断 LocalAppDialogHost，请改用 GameActionKind 与 host.openDetail"),
                    (r"\bonEngineSettings\b", "GameActions 不得接收或传递 onEngineSettings 外部回调，请使用 GameDetailDestination.EngineSettings"),
                ):
                    for hit in re.finditer(forbidden[0], fn_code):
                        errors.append((line_number(line_ends, start + hit.start()), forbidden[1]))

    if rel_path == "ui/game/GameScreen.kt":
        # GameActionsHost 不得恢复开放式 openDetails(() -> Unit) 逃生口。
        host_match = re.search(r"interface\s+GameActionsHost\s*\{[\s\S]*?\}", code)
        if not host_match:
            errors.append((1, "ui/game/GameScreen.kt: 缺少 GameActionsHost 接口"))
        elif re.search(r"\bfun\s+openDetails\b", host_match[0]):
            errors.append((
                line_number(line_ends, host_match.start()),
                "GameActionsHost 不得提供无约束的 openDetails 回调，详情必须注册为 GameDetailDestination",
            ))

    if rel_path == "ui/game/GameSheetNavigation.kt":
        enum_match = re.search(r"enum\s+class\s+GameDetailDestination\s*\{([^}]*)\}", code)
        if not enum_match:
            errors.append((1, "ui/game/GameSheetNavigation.kt: 缺少 GameDetailDestination 枚举"))

    return sorted(errors)


def check_book_covers_all_destinations(root: Path) -> list[str]:
    base = root / SRC_MAIN
    nav_src = code_only((base / "ui/game/GameSheetNavigation.kt").read_text(encoding="utf-8"))
    book_src = code_only((base / "ui/game/ShelfBook.kt").read_text(encoding="utf-8"))
    enum_match = re.search(r"enum\s+class\s+GameDetailDestination\s*\{([^}]*)\}", nav_src)
    if not enum_match:
        return ["缺少 GameDetailDestination 枚举"]
    entries = [e.strip() for e in enum_match[1].split(",") if e.strip()]
    errors = []
    for entry in entries:
        if f"GameDetailDestination.{entry}" not in book_src:
            errors.append(f"ShelfBook.kt 尚未处理 GameDetailDestination.{entry} 的书页渲染分支")
    return errors


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT, help="repository root (defaults to this checkout)")
    root = parser.parse_args(argv).root
    base = root / SRC_MAIN
    if not base.is_dir():
        print(f"Missing Android source root: {base}", file=sys.stderr)
        return 1

    targets = sorted(set(BOOK_ONLY_FILES) | set(BOOK_SHARED_FUNCTIONS.keys()) | {"ui/game/GameSheetNavigation.kt"})
    checked = 0
    violations = 0
    for rel_path in targets:
        file_path = base / rel_path
        if not file_path.is_file():
            print(f"{file_path}: missing required shelf-book boundary file", file=sys.stderr)
            violations += 1
            continue
        checked += 1
        for line, msg in check_file(rel_path, file_path.read_text(encoding="utf-8")):
            print(f"{SRC_MAIN.as_posix()}/{rel_path}:{line}: {msg}", file=sys.stderr)
            violations += 1

    for msg in check_book_covers_all_destinations(root):
        print(f"{SRC_MAIN.as_posix()}/ui/game/ShelfBook.kt:1: {msg}", file=sys.stderr)
        violations += 1

    print(f"Shelf-book boundaries: {checked} Kotlin files, {violations} violations.")
    return int(bool(violations))


if __name__ == "__main__":
    sys.exit(main())
