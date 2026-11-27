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
IMPORT_ALIAS = re.compile(r"(?m)^[ \t]*import[ \t]+([\w.]+)[ \t]+as[ \t]+(\w+)[ \t]*;?")

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
        "CoverCandidateCard",
    ),
    "ui/save/SaveManagementActivity.kt": (
        "SaveManagementDialog",
        "SaveManagementContent",
    ),
    "ui/save/SaveVersionsPanel.kt": (
        "SaveVersionsPanel",
        "SaveCloudVersionsPanel",
        "SaveSelectionRecoveryPanel",
    ),
    "ui/patch/KrkrOnlinePatchActivity.kt": (
        "KrkrOnlinePatchDialog",
        "KrkrOnlinePatchContent",
        "PatchEntryCard",
    ),
    "ui/settings/PerGameSettingsScreen.kt": (
        "PerGameSettingsDialog",
        "PerGameSettingsView",
        "SectionCard",
        "OverrideFontPreference",
    ),
    "ui/settings/SettingsScreen.kt": (
        "WinlatorGlobalSettingsDialog",
        "WinlatorGlobalSettingsCard",
        "EngineCard",
    ),
    "ui/settings/FontSettings.kt": (
        "FontPreference",
    ),
    "ui/settings/GraphicsFilterPicker.kt": (
        "GraphicsFilterPicker",
        "FilterCategories",
        "FilterPresets",
    ),
    "ui/settings/GraphicsChainEditor.kt": (
        "GraphicsChainEditor",
        "GraphicsNodeAction",
    ),
    "ui/settings/GraphicsNodeParameters.kt": (
        "GraphicsNodeScale",
        "GraphicsNodeParameters",
    ),
    "ui/settings/GraphicsSettingsEditor.kt": (
        "PerGameGraphicsSettings",
    ),
    "ui/settings/SystemNnnSettingsScreen.kt": (
        "SystemNnnSaveLayoutSettings",
    ),
    "ui/runtime/RuntimeModuleSettingsScreen.kt": (
        "RuntimeModuleSettingsDialog",
        "RuntimeModuleSettingsView",
        "StatusCard",
    ),
    "ui/runtime/RuntimeModuleSettingControls.kt": (
        "RuntimeSettingItemCard",
        "RuntimeSettingsGroupCard",
        "RuntimeSettingContent",
    ),
    "ui/runtime/RuntimeModuleSettingPathPicker.kt": (
        "rememberRuntimeModuleSettingPathPicker",
    ),
}

# 允许先经过 LocalAppDialogHost.current 接管并 return 后，才为抽屉/常规入口保留 1 个自绘全屏 Dialog() fallback 的函数。
HOSTED_RAW_DIALOG_FALLBACKS = {
    ("ui/game/GameScreen.kt", "CoverSearchDialog"),
    ("ui/game/GameShortcutCropDialog.kt", "GameShortcutCropDialog"),
}

# 仅服务书外独立窗体 / 非书页形态的局部辅助函数，豁免书内纸墨样式约束（仍受跳转与窗口守卫检查）。
BOOK_STYLE_EXEMPT_FUNCTIONS = {
    ("ui/game/GameShortcutCropDialog.kt", "CropDialogCard"),
    ("ui/game/GameQuickActionsDialog.kt", "GameQuickActionsDialog"),
    ("ui/game/GameQuickActionsDialog.kt", "QuickActionItem"),
    ("ui/game/GameQuickActionsDialog.kt", "QuickActionDeleteButton"),
}

# 禁止在书内及共用正文中出现的全局跳转与全局窗口调用（支持 import as 别名同步拦截）。
FORBIDDEN_GLOBAL_UI_SPECS = (
    (("startActivityWithPageTransition",), "书内条目不得调用 startActivityWithPageTransition，请走 GameActionsHost.openDetail 或 AppAlertDialog"),
    (("startActivity",), "书内条目不得直接调用 startActivity，请留在书页内实现"),
    (("AppBottomSheet", "ModalBottomSheet"), "书内条目不得拉起全局底部抽屉（AppBottomSheet / ModalBottomSheet）"),
)
TRACKED_IMPORT_SYMBOLS = frozenset(
    sym for syms, _ in FORBIDDEN_GLOBAL_UI_SPECS for sym in syms
) | frozenset(("Dialog",))

# 禁止在书内与书内共用组件路径里写死全局卡片底色、Material/Miuix 卡片块或全局按钮。
FORBIDDEN_BOOK_GLOBAL_STYLES = (
    (re.compile(r"\bDialogItemSurface\b"), "书页或共用正文中不得写死 DialogItemSurface，请改用自动适配 LocalShelfBookLeaf 的 AppSectionCard / AppNavItem / appDialogSurfaceColor"),
    (re.compile(r"\bNavWhite\b"), "书页或共用正文中不得写死 NavWhite，请改用 AppSectionCard / AppNavItem / appDialogSurfaceColor"),
    (re.compile(r"\b(?:MiuixCard|MiuixCardDefaults|CardDefaults)\b"), "书页或共用正文中不得直接构造 MiuixCard/Card，请改用会自动切为纸墨分隔的 AppSectionCard"),
    (re.compile(r"(?<![.\w])Card\s*[({]"), "书页或共用正文中不得直接绘制 Material Card() 色块，请改用 AppSectionCard"),
    (re.compile(r"\bMiuixTheme\.colorScheme\.surfaceContainer\w*"), "书页或共用正文中不得直接引用 Miuix surfaceContainer 底色作卡片块，请改用 AppSectionCard"),
    (re.compile(r"(?<![.\w])(?:TextButton|OutlinedButton|FilledTonalButton|ElevatedButton|Button)\s*[({]"), "书页或共用正文中不得使用全局 Material 按钮，请改用会自动适配书卷样式的 NoRippleButton / DialogTextButton / NoRippleTextButton"),
)


def extract_symbol_aliases(stripped_source: str) -> dict[str, set[str]]:
    """Extract explicit `import ...Symbol as alias` mappings before import statements are blanked."""
    aliases: dict[str, set[str]] = {sym: set() for sym in TRACKED_IMPORT_SYMBOLS}
    for match in IMPORT_ALIAS.finditer(stripped_source):
        symbol = match.group(1).rsplit(".", 1)[-1]
        if symbol in aliases:
            aliases[symbol].add(match.group(2))
    return aliases


def build_call_regex(names: tuple[str, ...], aliases: dict[str, set[str]], no_member_prefix: bool = False) -> re.Pattern[str]:
    all_names = sorted({n for n in names} | {alias for n in names for alias in aliases.get(n, ())})
    joined = "|".join(re.escape(name) for name in all_names)
    prefix = r"(?<![.\w])" if no_member_prefix else r"\b"
    return re.compile(rf"{prefix}(?:{joined})\s*[({{]")


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


def end_of_arguments(code: str, start: int) -> int | None:
    paren_depth = 1
    for idx in range(start + 1, len(code)):
        ch = code[idx]
        if ch == "(":
            paren_depth += 1
        elif ch == ")":
            paren_depth -= 1
            if paren_depth == 0:
                return idx + 1
    return None


def function_span(code: str, name: str) -> tuple[int, int] | None:
    """Find (header_start, body_end) for top-level `fun <name>(...) { ... }`."""
    match = re.search(rf"(?m)^\s*(?:internal\s+|private\s+|public\s+)?fun\s+{re.escape(name)}\s*\(", code)
    if not match:
        return None
    end_sig = end_of_arguments(code, match.end() - 1)
    if end_sig is None:
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


def matching_brace_end(code: str, open_pos: int) -> int | None:
    brace_depth = 1
    for idx in range(open_pos + 1, len(code)):
        ch = code[idx]
        if ch == "{":
            brace_depth += 1
        elif ch == "}":
            brace_depth -= 1
            if brace_depth == 0:
                return idx
    return None


def strip_outer_parens(expr: str) -> str:
    s = expr.strip()
    while s.startswith("(") and s.endswith(")"):
        end = end_of_arguments(s, 0)
        if end == len(s):
            s = s[1:-1].strip()
        else:
            break
    return s


def _has_braceless_control_prefix(before: str) -> bool:
    """Return True if `before` ends with an unbraced control-flow prefix like `else`, `->`, `if (...)`, `for (...)`, `while (...)`."""
    trimmed = before.rstrip()
    if not trimmed:
        return False
    if trimmed.endswith("->"):
        return True
    if re.search(r"\belse\Z", trimmed):
        return True
    if trimmed.endswith(")"):
        # Match back to the opening `(` of this parenthesized group.
        depth = 0
        open_idx = None
        for idx in range(len(trimmed) - 1, -1, -1):
            ch = trimmed[idx]
            if ch == ")":
                depth += 1
            elif ch == "(":
                depth -= 1
                if depth == 0:
                    open_idx = idx
                    break
        if open_idx is not None:
            head = trimmed[:open_idx].rstrip()
            if re.search(r"\b(?:if|for|while|when|catch)\Z", head):
                return True
    return False


def _is_unconditional_statement_pos(block: str, pos: int) -> bool:
    """Verify `pos` is at brace/paren depth 0 within `block` and not guarded by an unbraced control prefix."""
    before = block[:pos]
    brace_depth = 0
    paren_depth = 0
    for ch in before:
        if ch == "{":
            brace_depth += 1
        elif ch == "}":
            brace_depth -= 1
        elif ch == "(":
            paren_depth += 1
        elif ch == ")":
            paren_depth -= 1
        if brace_depth < 0 or paren_depth < 0:
            return False
    if brace_depth != 0 or paren_depth != 0:
        return False
    return not _has_braceless_control_prefix(before)


def _function_body_open(prefix: str) -> int | None:
    """Locate the opening `{` of the enclosing top-level function body in `prefix`."""
    sig_paren = prefix.find("(")
    if sig_paren < 0:
        return None
    end_sig = end_of_arguments(prefix, sig_paren)
    if end_sig is None:
        return None
    brace = prefix.find("{", end_sig)
    return brace if brace >= 0 else None


def returns_after_hosted_dialog(prefix: str) -> bool:
    """Only allow a raw Dialog() fallback after a top-level, unconditional non-null LocalAppDialogHost takeover + return."""
    if "LocalAppDialogHost.current" not in prefix:
        return False
    body_open = _function_body_open(prefix)
    if body_open is None:
        return False
    fn_body = prefix[body_open + 1:]

    host_vars = set(
        re.findall(r"\bval\s+(\w+)\s*(?::[^=\n]+)?=\s*LocalAppDialogHost\.current\b", fn_body)
    )
    host_exprs = [r"LocalAppDialogHost\.current"] + [rf"\b{re.escape(v)}\b" for v in sorted(host_vars)]
    host_union = "(?:" + "|".join(host_exprs) + ")"

    # 识别函数体顶层 `val overlay = if (bookHost == null) <non-null> else null` 或反向写法：
    # 仅当宿主非空时该派生变量才为 null。
    null_when_hosted_vars: set[str] = set()
    for m in re.finditer(
        rf"\bval\s+(\w+)\s*(?::[^=\n]+)?=\s*if\s*\(([^)\n]+)\)\s*([^\n]+?)\s+else\s+([^\n;]+)",
        fn_body,
    ):
        if not _is_unconditional_statement_pos(fn_body, m.start()):
            continue
        var_name, cond_raw, then_branch, else_branch = m.group(1), m.group(2), m.group(3).strip(), m.group(4).strip()
        cond = strip_outer_parens(cond_raw)
        is_host_null = bool(re.fullmatch(rf"(?:{host_union}\s*==\s*null|null\s*==\s*{host_union})", cond))
        is_host_non_null = bool(re.fullmatch(rf"(?:{host_union}\s*!=\s*null|null\s*!=\s*{host_union})", cond))
        if (is_host_null and else_branch == "null" and then_branch != "null") or (
            is_host_non_null and then_branch == "null" and else_branch != "null"
        ):
            null_when_hosted_vars.add(var_name)

    def is_non_null_host_condition(cond_raw: str) -> bool:
        cond = strip_outer_parens(cond_raw)
        if re.fullmatch(rf"(?:{host_union}\s*!=\s*null|null\s*!=\s*{host_union})", cond):
            return True
        if null_when_hosted_vars:
            null_union = "(?:" + "|".join(rf"\b{re.escape(v)}\b" for v in sorted(null_when_hosted_vars)) + ")"
            if re.fullmatch(rf"(?:{null_union}\s*==\s*null|null\s*==\s*{null_union})", cond):
                return True
        return False

    hosted_call_re = re.compile(rf"(?:{host_union}\??\.Present|\bAppAlertDialog)\s*\(")

    for if_match in re.finditer(r"\bif\s*\(", fn_body):
        # 宿主接管分支必须处于函数体顶层且无外层条件修饰，保证到达 fallback 前必定经过。
        if not _is_unconditional_statement_pos(fn_body, if_match.start()):
            continue
        cond_end = end_of_arguments(fn_body, if_match.end() - 1)
        if cond_end is None:
            continue
        cond_text = fn_body[if_match.end():cond_end - 1]
        if not is_non_null_host_condition(cond_text):
            continue
        rest = fn_body[cond_end:]
        brace_match = re.match(r"\s*\{", rest)
        if not brace_match:
            continue
        block_open = cond_end + brace_match.end() - 1
        block_close = matching_brace_end(fn_body, block_open)
        if block_close is None:
            continue
        block_body = fn_body[block_open + 1:block_close]
        # 托管调用与块尾 return 均必须无条件执行（排除块内嵌套或无花括号 `if (enabled) host.Present(...)`）。
        for call in hosted_call_re.finditer(block_body):
            if not _is_unconditional_statement_pos(block_body, call.start()):
                continue
            call_end = end_of_arguments(block_body, call.end() - 1)
            if call_end is not None and re.fullmatch(r"\s*;?\s*return\s*;?\s*", block_body[call_end:]):
                return True
    return False


def check_segment(
    rel_path: str,
    code: str,
    start: int,
    end: int,
    line_ends: list[int],
    forbidden_global_ui: tuple[tuple[re.Pattern[str], str], ...],
    raw_dialog_re: re.Pattern[str],
    allow_root_dialog: bool = False,
    allow_hosted_fallback_dialogs: tuple[str, ...] = (),
    check_book_styles: bool = True,
) -> list[tuple[int, str]]:
    errors: list[tuple[int, str]] = []
    segment = code[start:end]
    for pattern, message in forbidden_global_ui:
        for hit in pattern.finditer(segment):
            errors.append((line_number(line_ends, start + hit.start()), f"{rel_path}: {message}"))
    raw_fallback_ranges: list[tuple[int, int]] = []
    allowed_ranges: list[tuple[int, int]] = []
    for fn_name in allow_hosted_fallback_dialogs:
        span = function_span(code, fn_name)
        if not span:
            continue
        fn_start, fn_end = span
        fn_code = code[fn_start:fn_end]
        raw_hits = list(raw_dialog_re.finditer(fn_code))
        if raw_hits and len(raw_hits) == 1:
            prefix = fn_code[:raw_hits[0].start()]
            if returns_after_hosted_dialog(prefix):
                allowed_ranges.append((fn_start, fn_end))
                raw_fallback_ranges.append((fn_start + raw_hits[0].start(), fn_end))
    if check_book_styles:
        exempt_style_ranges = raw_fallback_ranges + [
            span
            for file_key, fn_name in BOOK_STYLE_EXEMPT_FUNCTIONS
            if file_key == rel_path
            for span in (function_span(code, fn_name),)
            if span is not None
        ]
        for pattern, message in FORBIDDEN_BOOK_GLOBAL_STYLES:
            for hit in pattern.finditer(segment):
                abs_pos = start + hit.start()
                if any(lo <= abs_pos < hi for lo, hi in exempt_style_ranges):
                    continue
                errors.append((line_number(line_ends, abs_pos), f"{rel_path}: {message}"))
    if not allow_root_dialog:
        for hit in raw_dialog_re.finditer(segment):
            abs_pos = start + hit.start()
            if any(lo <= abs_pos < hi for lo, hi in allowed_ranges):
                continue
            errors.append((
                line_number(line_ends, abs_pos),
                f"{rel_path}: 书内或共用界面不得直接调用 Compose Dialog() 另开全局窗口（未先通过 LocalAppDialogHost 接管），请使用 AppAlertDialog",
            ))
    return errors


def check_file(rel_path: str, source: str) -> list[tuple[int, str]]:
    stripped = code_only(source)
    aliases = extract_symbol_aliases(stripped)
    forbidden_global_ui = tuple(
        (build_call_regex(names, aliases), message)
        for names, message in FORBIDDEN_GLOBAL_UI_SPECS
    )
    raw_dialog_re = build_call_regex(("Dialog",), aliases, no_member_prefix=True)
    code = IMPORT.sub(lambda m: blank(m[0]), stripped)
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
                forbidden_global_ui=forbidden_global_ui,
                raw_dialog_re=raw_dialog_re,
                allow_root_dialog=allow_root_dialog,
                allow_hosted_fallback_dialogs=hosted_fallbacks,
            )
        )
        if rel_path == "ui/game/ShelfBook.kt":
            raw_dialogs = list(raw_dialog_re.finditer(code))
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
                    forbidden_global_ui=forbidden_global_ui,
                    raw_dialog_re=raw_dialog_re,
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
