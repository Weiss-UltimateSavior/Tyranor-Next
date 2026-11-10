#!/usr/bin/env python3
"""扫描硬编码的界面文案（app 与 engine 两个模块）。

三条规则：

1. 三语言 `strings.xml` 和 SystemNNN 宿主资源的键必须齐平；
2. Kotlin 与 SystemNNN Java 的 CJK 字面量限于注明的游戏匹配键和诊断基线；
3. drawable / XML 资源不得出现主题色（由各自模块的规范约束，此处不覆盖）。

engine 模块的扫描范围与边界：

- `engine/src/main/res/values{,-en,-ja}/strings.xml`：参与键齐平校验；
- `engine/src/main/java/**/*.kt`：参与 CJK 字面量扫描；
- `engine/src/main/java/com/tyranor/runtime/systemnnn/*.java`：扫描字符串与文本块；
- `engine/src/main/assets/**/*.js`：**不在扫描范围**。原因是这些脚本随游戏页面注入、不参与
  Android 资源本地化（无法用 `strings.xml` 供文案），因此它们的文案只能就地写。
  注意：这不代表其中没有界面文案——例如 `__rpgmaker_mod_ui.js` 的「虚拟按键」卡片就是
  纯中文的游戏内 UI 文案；改动这些脚本时需人工保证三语言一致（引擎侧无法自动校验）。
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

CJK = re.compile(r"[\u3040-\u30ff\u3400-\u9fff]")
VISIBLE_CJK_STRING = re.compile(r'"[^"\n]*' + CJK.pattern + r'[^"\n]*"')
RESOURCE_KEY = re.compile(r'<string\s+name="([^"]+)"')
JAVA_UNICODE_ESCAPE = re.compile(r"\\u+([0-9a-fA-F]{4})")
# 注释和字符常量优先消费。字符串及文本块中的转义可包含引号或换行。
JAVA_TOKEN = re.compile(
    r'''
    //[^\n]*
    | /\* .*? \*/
    | '(?:\\[^\n]|[^'\\\n])*'
    | (?P<literal>
          """[ \t\f]*\n(?:\\.|"(?!"")|[^"\\])*"""
        | "(?:\\[^\n]|[^"\\\n])*"
      )
    ''',
    re.DOTALL | re.VERBOSE,
)

# 允许 CJK 字面量的例外：engine 的 KRKR 壳必须用游戏自身的语言词典匹配系统对话框文案，
# 这里的字面量是**匹配键**（游戏产出的文本），不是 App 界面文案。
# 粒度是「整文件豁免」，因此额外用数量基线兜底：允许清单里的文件一旦新增 CJK 字面量
# （可能夹带真实界面文案）就会失败，需要人来确认是匹配键还是文案。
LITERAL_ALLOWLIST = {
    ROOT / "engine" / "src" / "main" / "java" / "com" / "core" / "engine" / "EngineUiText.kt": 6,
}

ENGINE_RES = ROOT / "engine" / "src" / "main" / "res"
SYSTEMNNN_JAVA = ROOT / "engine" / "src" / "main" / "java" / "com" / "tyranor" / "runtime" / "systemnnn"
# 保留已有的日志诊断、原版变量名和全角假名表。每个 Java 字面量按出现次数核对。
SYSTEMNNN_LITERAL_BASELINE = {
    "GalleryCatalog.java": {
        '"动画鉴赏列表格式无效"': 1,
        '"音乐鉴赏列表格式无效"': 1,
        '"CG 列表超过容量"': 1,
        '"scene.dat 文件长度无效"': 1,
        '"scene.dat 场景记录不完整"': 1,
        '"scene.dat 场景引用无效"': 1,
        '"scene.dat 片段编号无效"': 1,
        '"scene.dat 尾部无效"': 1,
        '"scene.dat 记录不完整"': 1,
        '"scene.dat 数量无效"': 1,
        '"鉴赏列表含有无效字节"': 1,
        '"鉴赏列表超出格式上限"': 1,
        '"这张 CG 尚未解锁"': 1,
        '"无法读取 CG 图像"': 1,
    },
    "GalleryStore.java": {
        '"鉴赏记录格式无效"': 1,
        '"鉴赏记录数量无效"': 1,
        '"鉴赏记录项目无效"': 1,
        '"鉴赏记录尾部无效"': 1,
        '"鉴赏记录达到容量上限"': 1,
    },
    "GameProgress.java": {
        '"全局进度主文件损坏，已保留原文件 · "': 2,
        '"无法读取"': 1,
        '"进度，请恢复存储访问后重试"': 1,
        '"基础"': 1,
        '"扩展"': 1,
    },
    "GameSave.java": {
        '"无法恢复回看记录"': 1,
        '"无法恢复脚本系统变量"': 1,
        '"无法恢复结果记录"': 1,
        '"无法恢复鉴赏记录"': 1,
        '"无法恢复随机数状态"': 1,
        '"无法恢复系统时间样本"': 1,
        '"无效的游戏输入"': 1,
        '"原生快照缺失或无效，此存档没有可回退的完整输入记录"': 1,
        '"无效的全局脚本状态数量"': 1,
        '"鉴赏记录超过上限"': 1,
        '"动画恢复缺少输入记录"': 1,
        '"回看检查点已失效"': 3,
        '"尚未开始游戏"': 2,
        '"无法生成完整的原生快照"': 1,
        '"无法写入完整的原生快照"': 1,
        '"缺少存档位置"': 1,
        '"存档摘要与当前脚本不一致"': 1,
        '"存档记录不完整"': 1,
        '"无法识别存档"': 2,
        '"存档长度无效"': 2,
        '"旧版存档不完整"': 1,
        '"存档文本数量无效"': 1,
        '"存档文本过大"': 2,
        '"旧版存档输入无效"': 1,
        '"缺少存档输入"': 1,
        '"存档校验失败，文件可能已损坏"': 1,
        '"存档尾部无效"': 2,
        '"选择场景存档缺少有效原生快照"': 1,
        '"缺少恢复状态"': 2,
        '"系统时间样本缺少对应输入"': 2,
        '"缺少系统变量起始记录"': 1,
        '"缺少结果起始记录"': 1,
        '"缺少鉴赏起始记录"': 1,
        '"无效的鉴赏起始记录"': 1,
        '"缺少随机数起始记录"': 1,
        '"系统时间样本对应多条输入"': 1,
        '"存档与此游戏目录中的脚本、对白或选项不匹配"': 1,
        '"，旧输入日志没有随机数和本地时间记录"': 1,
        '"存档开关无效"': 1,
        '"存档编号与文件不匹配"': 1,
        '"存档输入无效"': 1,
        '"存档状态记录无效"': 1,
        '"存档摘要无效"': 1,
        '"缺少存档文本"': 1,
    },
    "GameSavePaths.java": {
        '"游戏存档目录无效 · "': 2,
    },
    "LegacySaveMigration.java": {
        '"无法访问旧版应用存档目录"': 1,
        '"无法迁移游戏存档 · "': 1,
        '"目标已有全局进度扩展，但缺少对应的基础文件 · "': 1,
        '"无法识别迁移暂存项 · "': 1,
        '"文件类型不符 · "': 1,
    },
    "SaveSlots.java": {
        '"无效的存档编号"': 1,
        '"存档编号不匹配"': 1,
        '"无效的存档截图"': 1,
    },
    "SystemNnnHost.java": {
        '"这款游戏的存档正在使用，请退出另一个播放器后重试"': 1,
    },
    "GameRunner.java": {
        '"ムービー番号"': 1,
    },
    "GameTextDecoder.java": {
        '"。「」、・ヲァィゥェォャュョッーアイウエオカキクケコサシスセソ"': 1,
        '"タチツテトナニヌネノハヒフヘホマミムメモヤユヨラリルレロワン゛゜"': 1,
        '"无效的输入长度限制"': 1,
        '"输入中含有不可用字符"': 1,
        '"这个字对应多个原版字符，无法确定游戏所需的编码"': 1,
        '"游戏编码无法表示「"': 1,
        '"输入超过 "': 1,
        '" 字节"': 1,
    },
    "UiPreviewPool.java": {
        '"図鑑番号"': 1,
    },
}


def check_cjk_literals(name: str, paths, allowlist: dict[Path, int]) -> list[str]:
    errors: list[str] = []
    for path in sorted(paths):
        allowed_count = allowlist.get(path)
        source = path.read_text(encoding="utf-8")
        hits = [
            (index, code)
            for index, code in strip_comments(source)
            if VISIBLE_CJK_STRING.search(code)
        ]
        if allowed_count is not None:
            if len(hits) > allowed_count:
                errors.append(
                    f"[{name}] {path.relative_to(ROOT)}: 整文件豁免的 CJK 字面量基线为 "
                    f"{allowed_count}，实际 {len(hits)}——新增的请确认是匹配键还是界面文案"
                )
                errors.extend(f"  - {index}: {code.strip()}" for index, code in hits)
            continue
        for index, code in hits:
            errors.append(f"[{name}] {path.relative_to(ROOT)}:{index}: {code.strip()}")
    return errors


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


def translate_java_unicode(source: str) -> str:
    """按 Java 词法顺序先处理 Unicode 转义，包括成对的反斜杠。"""
    if r"\u" not in source:
        return source

    translated: list[str] = []
    position = 0
    backslashes = 0
    previous_escape = False
    while position < len(source):
        char = source[position]
        escape = None
        if char == "\\" and (previous_escape or backslashes % 2 == 0):
            escape = JAVA_UNICODE_ESCAPE.match(source, position)
        if escape is not None:
            char = chr(int(escape[1], 16))
            position = escape.end()
        else:
            position += 1
        translated.append(char)
        backslashes = backslashes + 1 if char == "\\" else 0
        previous_escape = escape is not None
    return "".join(translated)


def check_java_cjk_literals(paths) -> list[str]:
    errors: list[str] = []
    for path in sorted(paths):
        remaining = SYSTEMNNN_LITERAL_BASELINE.get(path.name, {}).copy()
        source = translate_java_unicode(path.read_bytes().decode("utf-8"))
        source = source.replace("\r\n", "\n").replace("\r", "\n")
        for match in JAVA_TOKEN.finditer(source):
            literal = match["literal"]
            if literal is None or not CJK.search(literal):
                continue
            if remaining.get(literal, 0) > 0:
                remaining[literal] -= 1
                continue
            index = source.count("\n", 0, match.start()) + 1
            errors.append(f"[SystemNNN] {path.relative_to(ROOT)}:{index}: {literal!r}")
    return errors


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


def main() -> int:
    errors: list[str] = []
    for name, java_root, string_files in MODULES:
        errors += check_resource_parity(name, string_files)
        errors += check_cjk_literals(name, java_root.rglob("*.kt"), LITERAL_ALLOWLIST)
    for filename in ("systemnnn_controls.xml", "systemnnn_host.xml"):
        files = [ENGINE_RES / directory / filename for directory in ("values", "values-en", "values-ja")]
        errors += check_resource_parity("SystemNNN", files)
    errors += check_java_cjk_literals(SYSTEMNNN_JAVA.glob("*.java"))
    if errors:
        print("Hardcoded UI string check failed:")
        print("\n".join(errors))
        return 1
    print("Hardcoded UI string check passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
