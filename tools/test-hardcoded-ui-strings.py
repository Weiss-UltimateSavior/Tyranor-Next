#!/usr/bin/env python3
"""Exercise the SystemNNN Java UI-string check with focused source fixtures."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location(
    "check_hardcoded_ui_strings", Path(__file__).with_name("check-hardcoded-ui-strings.py"),
)
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class HardcodedUiStringsTest(unittest.TestCase):
    def scan(self, source: str, filename: str = "PlayerActivity.java", *, exact_baseline=False) -> list[str]:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / filename
            path.write_text(source, encoding="utf-8")
            with patch.object(checker, "ROOT", root):
                return checker.check_java_cjk_literals([path], exact_baseline=exact_baseline)

    def test_rejects_an_overcounted_baseline(self):
        source = 'class Sample { String message = "診断"; }'
        with patch.object(checker, "SYSTEMNNN_LITERAL_BASELINE", {"Sample.java": {'"診断"': 2}}):
            errors = self.scan(source, "Sample.java", exact_baseline=True)
        self.assertEqual(1, len(errors))
        self.assertIn("baseline overcounts", errors[0])

    def test_rejects_summary_replacing_an_allowed_diagnostic(self):
        source = '''class GameSave {
    void validate() throws java.io.IOException {
        throw new java.io.IOException("无效的游戏输入");
    }
    String summary() { return ""; }
}'''
        self.assertEqual([], self.scan(source, "GameSave.java"))
        source = source.replace('"无效的游戏输入"', '"Invalid input"')
        source = source.replace('return "";', 'return "未保存的进度";')
        errors = self.scan(source, "GameSave.java")
        self.assertEqual(1, len(errors))
        self.assertIn("GameSave.java:5:", errors[0])
        self.assertIn('"未保存的进度"', errors[0])

    def test_rejects_excess_copies_of_a_literal_on_the_same_line(self):
        source = '''class GameSave {
    String diagnostic = "无法识别存档" + "无法识别存档";
}'''
        self.assertEqual([], self.scan(source, "GameSave.java"))
        source = source.replace(";", ' + "无法识别存档";')
        errors = self.scan(source, "GameSave.java")
        self.assertEqual(1, len(errors))
        self.assertIn('"无法识别存档"', errors[0])

    def test_rejects_text_block_with_escaped_quotes_and_comment_markers(self):
        source = r'''class PlayerActivity {
    // """ "行注释"
    /* """
        块注释
        """ */
    char quote = '"';
    String ordinary = "with \"quotes\" and //";
    String block = """
        \""" escaped delimiter
        /* marker */
        // marker
        continued \
        next line with two quotes ""
        """;
}'''
        self.assertEqual([], self.scan(source))
        errors = self.scan(source.replace("// marker", "// 界面文案"))
        self.assertEqual(1, len(errors))
        self.assertIn("PlayerActivity.java:8:", errors[0])
        self.assertIn("// 界面文案", errors[0])

    def test_resolves_java_unicode_escapes_before_scanning(self):
        source = r'''class PlayerActivity {
    String ascii = "\\u6587";
    String block = """
        \uu6587
        """;
}'''
        errors = self.scan(source)
        self.assertEqual(1, len(errors))
        self.assertIn("PlayerActivity.java:3:", errors[0])
        self.assertIn("文", errors[0])


if __name__ == "__main__":
    unittest.main()
