#!/usr/bin/env python3
"""Offline motion ownership check; see tools/motion-boundaries.md for scope and exceptions."""
import argparse
from bisect import bisect_right
from collections import Counter
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com/tyranor/next/"
LIST_OWNER = "ui/common/motion/ListItemMotion.kt"
MOTION = "com.tyranor.next.ui.common.motion."

# Reviewed implementation files, not naming conventions. A new FooMotion.kt is
# still checked until it is explicitly registered. These do NOT bypass list rules.
OWNERS = set("""
ui/archive/ArchiveMotion.kt
ui/engine/EngineStorageMotion.kt
ui/game/CoverFlowMotion.kt
ui/game/CropGuides.kt
ui/game/GameOverlayMotion.kt
ui/main/MainTabMotion.kt
ui/onboarding/CoachSpotlight.kt
ui/onboarding/CoachStepMotion.kt
ui/settings/GraphicsChainMotion.kt
""".split())
OWNERS.update("ui/common/" + f + ".kt" for f in """
AppAlertDialog AppNavItem AppScreen AppSidePanel ArrowPreference ControlMotion
DialogMiuixSlider MotionLinearProgressIndicator MotionSlider MotionSwitch
NoPressFeedbackButtons OverlayDropdownPreference SlidingTabRow TabPagerState
TopBarIcon notice/NoticeHost
""".split())
OWNERS.update("ui/common/motion/" + f + ".kt" for f in """
BlurText ContentMotion DeleteEffect Entrance FeedbackMotion GameCoverMotion
ListItemMotion MotionMode MotionSprings MotionTokens PagerMotion PageTransitions
ParallaxCardPager PressSquish ReorderPlacementMotion ReplayEntranceSprings
ScrollMotion SheetMotion TabPageMotion
""".split())

# The existing navigation implementations are outside this refactor. New
# siblings (including files in common/glass) do not acquire an exemption.
PROTECTED = {"ui/common/" + f + ".kt" for f in """
AppNavigationRail FloatingGlassNavButton FloatingNavGesture FloatingNavIcon
FloatingNavOrb FloatingNavOutsideTapObserver LiquidGlassNavigation NavigationTabIcon
glass/EnhancedLiquidGlassNavigation glass/GlassBottomBarColors glass/GlassBottomBarGesture
glass/GlassBottomBarSpec glass/GlassNavigationThemeAdapter glass/GlassShaderSupport
glass/LensMotionController glass/PressGlowHighlight
""".split()}
MAIN = "ui/main/MainScreen.kt"

# path + block-bodied function + resolved callee + maximum existing calls.
# Never let a new function, or a second call in the old one, inherit the slot.
EXISTING = {
    # The shared page clock feeds the named per-page effects in both modes.
    (MAIN, "MainScreen", "androidx.compose.animation.core.updateTransition"): 1,
}

# Intentionally check constructions, not imports, types, high-level containers,
# or every method whose name starts with "animate".
PRIMITIVES = re.compile(
    r"androidx\.compose\.animation\.(?:core\.)?(?:"
    r"Animatable|AnimationState|TargetBasedAnimation|DecayAnimation|"
    r"animate(?:Float|Dp|Color|Int|Offset|Size|Rect|IntSize|IntOffset|Value)(?:AsState)?|"
    r"animate|updateTransition|rememberTransition|rememberInfiniteTransition|"
    r"spring|tween|snap|keyframes(?:WithSpline)?|(?:infiniteR|r)epeatable|"
    r"SpringSpec|TweenSpec|CubicBezierEasing|"
    r"fade(?:In|Out)|(?:slide|scale)(?:In|Out)(?:Horizontally|Vertically)?|"
    r"(?:expand|shrink)(?:In|Out|Horizontally|Vertically)|animateContentSize)"
)
ID = r"[A-Za-z_]\w*"
DOT = r"\s*\.\s*"
IMPORT = re.compile(r"(?m)^[ \t]*import[ \t]+(\w+(?:\.\w+)*(?:\.\*)?)(?:[ \t]+as[ \t]+(\w+))?[ \t]*;?")
CALL = re.compile(
    rf"\b({ID}(?:{DOT}{ID})*)(?:\s*<[\w\s.,?*<>]+>)?\s*(?=[({{])"
)
LIST_SPEC = re.compile(rf"\b(?:{ID}{DOT})+spec\b")
PLACEMENT_SPECS = {MOTION + "ListItemPlacement." + variant + ".spec" for variant in ("Layout", "Reflow")}
MASK = re.compile(r'''//[^\n]*|/\*|"""[\s\S]*?(?:"""|\Z)|"(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*' ''', re.X)


def blank(text):
    return re.sub(r"[^\n]", " ", text)


def code_only(source):
    """Keep offsets and newlines; skip literals and Kotlin's nested block comments."""
    parts = []
    pos = 0
    while match := MASK.search(source, pos):
        end = match.end()
        if match[0] == "/*":
            depth = 1
            for mark in re.finditer(r"/\*|\*/", source[end:]):
                depth += 1 if mark[0] == "/*" else -1
                if not depth:
                    end += mark.end()
                    break
            else:
                end = len(source)
        parts.extend((source[pos:match.start()], blank(source[match.start():end])))
        pos = end
    return "".join(parts) + source[pos:]


def block_span(code, name):
    """Only for the few registered 'fun name(...) { ... }' exceptions; fail closed."""
    match = re.search(rf"\bfun\s+{name}\s*\(", code)
    if not match:
        return (0, 0)
    start = match.end() - 1
    for opening, closing in (("(", ")"), ("{", "}")):
        depth = 1
        for mark in re.finditer(f"[{re.escape(opening + closing)}]", code[start + 1:]):
            depth += 1 if mark[0] == opening else -1
            if not depth:
                end = start + 1 + mark.end()
                break
        else:
            return (0, 0)
        if opening == "{":
            return start, end
        body = re.match(r"\s*\{", code[end:])
        if not body:
            return (0, 0)
        start = end + body.end() - 1


def resolve(name, imports, stars):
    """Candidates for an explicit import/alias, a package-qualified or an extension call."""
    first, *rest = name.split(".")
    leaf = rest[-1] if rest else first
    if first in ("androidx", "com"):
        return [name]
    targets = ([".".join([imports[first], *rest])] if first in imports else
               [ns + "." + name for ns in stars])
    # Imported extension on a receiver (possibly itself imported, e.g. Modifier).
    # A member foo.spring() is not the imported top-level Compose spring().
    if rest:
        extensions = [imports[leaf]] if leaf in imports else [ns + "." + leaf for ns in stars]
        targets += [s for s in extensions if s.rsplit(".", 1)[-1] in EXTENSIONS]
    return targets


EXTENSIONS = {"animateContentSize", "animateFloat", "animateDp", "animateColor", "animateInt",
              "animateOffset", "animateSize", "animateRect", "animateIntSize", "animateIntOffset",
              "animateValue", "animateItem", "animateItemPlacement"}


# All three names on the SAME argument list are the lazy-item spec protocol.
# A single named argument, or labels on nested, unrelated calls, are not sufficient.
LIST_ARGS = {"fadeInSpec", "placementSpec", "fadeOutSpec"}
ARG_TOKEN = re.compile(r"\(|\)|\{|\}|\b(fadeInSpec|placementSpec|fadeOutSpec)\s*=(?!=)")


def spec_triples(code):
    frames = []
    for token in ARG_TOKEN.finditer(code):
        if token[0] in ("(", "{"):
            frames.append(set() if token[0] == "(" else None)
        elif token[0] in (")", "}"):
            if frames:
                frames.pop()
        elif frames and frames[-1] is not None:
            args = frames[-1]
            args.add(token[1])
            if args == LIST_ARGS:
                yield token.start()
                args.add("reported")


def check_source(path, source):
    """path relative to com/tyranor/next (or a full foreign package path)."""
    page = (path.startswith("ui/") or path == "MainActivity.kt") and path not in OWNERS
    if path in PROTECTED or (not page and not any(
        needle in source for needle in ("animateItem", "placementSpec", "ListItemPlacement")
    )):
        return []
    code = code_only(source)
    imports, stars = {}, []
    package = re.search(r"(?m)^\s*package\s+([\w.]+)", code)
    if package:
        stars.append(package[1])
    for match in IMPORT.finditer(code):
        fq, alias = match.groups()
        if fq.endswith(".*"):
            stars.append(fq[:-2])
        else:
            imports[alias or fq.rsplit(".", 1)[-1]] = fq
    code = IMPORT.sub(lambda m: blank(m[0]), code)
    if path == MAIN:
        start, end = block_span(code, "DefaultBottomNavigationBar")
        code = code[:start] + blank(code[start:end]) + code[end:]

    line_ends = [m.start() for m in re.finditer("\n", code)]
    errors = []

    def report(pos, message):
        errors.append((bisect_right(line_ends, pos) + 1, message))

    if path != LIST_OWNER:
        for pos in spec_triples(code):
            report(pos, "lazy-item specs assembled together; choose a listItemMotion() placement")
        for match in LIST_SPEC.finditer(code):
            name = re.sub(r"\s+", "", match[0])
            if PLACEMENT_SPECS.intersection(resolve(name, imports, stars)):
                report(match.start(), "pass the ListItemPlacement variant to listItemMotion(), not its raw spec")

    spans = {fn: block_span(code, fn) for p, fn, _ in EXISTING if p == path}
    used = Counter()
    for hit in CALL.finditer(code):
        name = re.sub(r"\s+", "", hit[1])
        leaf = name.rsplit(".", 1)[-1]
        pos = hit.end(1) - len(leaf)
        targets = resolve(name, imports, stars)
        if path != LIST_OWNER and (leaf in ("animateItem", "animateItemPlacement") or
                                   any(t.endswith((".animateItem", ".animateItemPlacement")) for t in targets)):
            report(pos, "raw lazy-item animation; use listItemMotion() or ListItemPlacement.Reflow")
        if not page:
            continue
        for target in set(targets):
            if not (PRIMITIVES.fullmatch(target) or target.startswith((MOTION + "MotionTokens.", MOTION + "MotionSprings."))):
                continue
            allowed = False
            for fn, (start, end) in spans.items():
                key = (path, fn, target)
                if start <= hit.start() < end and used[key] < EXISTING.get(key, 0):
                    used[key] += 1
                    allowed = True
                    break
            if not allowed:
                report(pos, f"{target}: use a named effect or register its implementation owner")
    return sorted(errors)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT, help="repository root (defaults to this checkout)")
    root = parser.parse_args(argv).root
    files = errors = 0
    for source_set in ("java", "kotlin"):
        base = root / "app/src/main" / source_set
        for path in sorted(base.rglob("*.kt")):
            rel = path.relative_to(base).as_posix()
            policy_path = rel[len(PACKAGE):] if rel.startswith(PACKAGE) else "/" + rel
            files += 1
            for line, message in check_source(policy_path, path.read_text(encoding="utf-8")):
                print(f"{path.relative_to(root).as_posix()}:{line}: {message}", file=sys.stderr)
                errors += 1
    if not (root / "app/src/main/java" / PACKAGE / LIST_OWNER).is_file():
        print(f"Missing list effect owner {LIST_OWNER}; check the source root.", file=sys.stderr)
        return 1
    print(f"Motion boundaries: {files} Kotlin files, {errors} violations.")
    return int(bool(errors))


if __name__ == "__main__":
    sys.exit(main())
