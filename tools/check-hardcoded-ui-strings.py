#!/usr/bin/env python3
"""扫描硬编码的界面文案（app 与 engine 两个模块）。

三条规则：

1. 三语言 `strings.xml` 的键必须齐平（app 与 engine 各自内部齐平）；
2. Kotlin 源码不得出现含 CJK 的字符串字面量（界面文案必须走 `strings.xml`）；
3. drawable / XML 资源不得出现主题色（由各自模块的规范约束，此处不覆盖）。

engine 模块的扫描范围与边界：

- `engine/src/main/res/values{,-en,-ja}/strings.xml`：参与键齐平校验；
- `engine/src/main/java/**/*.kt`：参与 CJK 字面量扫描；
- `engine/src/main/assets/**/*.js`：**不在扫描范围**。assets 是随游戏注入的引擎脚本
  （`__touch_pad.js` 等的后继），其中的中文多为注释与调试文本，不属于 App 界面文案，
  且这些脚本不参与 Android 资源本地化。
- `engine/src/test/**` 同样不参与：测试里的中文是断言消息，不是界面文案。
"""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]

# (模块名, 源码根目录, [三语言 strings.xml])
MODULES = [
    (
        "app",
        ROOT / "app" / "src" / "main" / "java",
        [
            ROOT / "app" / "src" / "main" / "res" / "values" / "strings.xml",
            ROOT / "app" / "src" / "main" / "res" / "values-ja" / "strings.xml",
            ROOT / "app" / "src" / "main" / "res" / "values-en" / "strings.xml",
        ],
    ),
    (
        "engine",
        ROOT / "engine" / "src" / "main" / "java",
        [
            ROOT / "engine" / "src" / "main" / "res" / "values" / "strings.xml",
            ROOT / "engine" / "src" / "main" / "res" / "values-ja" / "strings.xml",
            ROOT / "engine" / "src" / "main" / "res" / "values-en" / "strings.xml",
        ],
    ),
]

VISIBLE_CJK_STRING = re.compile(r'"[^"\n]*[\u3040-\u30ff\u3400-\u9fff][^"\n]*"')
RESOURCE_KEY = re.compile(r'<string\s+name="([^"]+)"')

# 允许 CJK 字面量的例外：engine 的 KRKR 壳必须用游戏自身的语言词典匹配系统对话框文案，
# 这里的字面量是**匹配键**（游戏产出的文本），不是 App 界面文案。
LITERAL_ALLOWLIST = {
    ROOT / "engine" / "src" / "main" / "java" / "com" / "core" / "engine" / "EngineUiText.kt",
}


def strip_comments(source: str) -> list[tuple[int, str]]:
    """去掉块/行注释，返回 (行号, 代码) 列表（跨行块注释状态保留）。

    KDoc 里常出现成对的中文引号（如「同一游戏」写作 "同一游戏"），若不剥离注释会被
    误判为硬编码文案；而真正的硬编码文案只可能出现在字符串字面量里，因此只扫描代码
    部分是正确判据。
    """
    result: list[tuple[int, str]] = []
    in_block = False
    for index, line in enumerate(source.splitlines(), start=1):
        code_chars: list[str] = []
        position = 0
        in_string = False
        escaped = False
        while position < len(line):
            char = line[position]
            if in_block:
                if char == "*" and line.startswith("*/", position):
                    in_block = False
                    position += 2
                    continue
                position += 1
                continue
            if in_string:
                code_chars.append(char)
                if escaped:
                    escaped = False
                elif char == "\\":
                    escaped = True
                elif char == '"':
                    in_string = False
                position += 1
                continue
            if char == '"':
                in_string = True
                code_chars.append(char)
                position += 1
                continue
            if char == "/" and line.startswith("//", position):
                break
            if char == "/" and line.startswith("/*", position):
                in_block = True
                position += 2
                continue
            code_chars.append(char)
            position += 1
        result.append((index, "".join(code_chars)))
    return result


def resource_keys(path: Path) -> set[str]:
    return set(RESOURCE_KEY.findall(path.read_text(encoding="utf-8")))


def check_resource_parity(name: str, string_files: list[Path]) -> list[str]:
    errors: list[str] = []
    key_sets = {path: resource_keys(path) for path in string_files}
    all_keys = set().union(*key_sets.values())
    for path, keys in key_sets.items():
        missing = sorted(all_keys - keys)
        if missing:
            errors.append(f"[{name}] {path.relative_to(ROOT)} is missing {len(missing)} string keys:")
            errors.extend(f"  - {key}" for key in missing)
    return errors


def check_kotlin_cjk_literals(name: str, java_root: Path) -> list[str]:
    errors: list[str] = []
    for path in sorted(java_root.rglob("*.kt")):
        if path in LITERAL_ALLOWLIST:
            continue
        source = path.read_text(encoding="utf-8")
        for index, code in strip_comments(source):
            if VISIBLE_CJK_STRING.search(code):
                errors.append(f"[{name}] {path.relative_to(ROOT)}:{index}: {code.strip()}")
    return errors


def main() -> int:
    errors: list[str] = []
    for name, java_root, string_files in MODULES:
        errors += check_resource_parity(name, string_files)
        errors += check_kotlin_cjk_literals(name, java_root)
    if errors:
        print("Hardcoded UI string check failed:")
        print("\n".join(errors))
        return 1
    print("Hardcoded UI string check passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
