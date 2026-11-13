#!/usr/bin/env python3
"""Exercise the source gate with real temporary Git checkouts, without network."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

CHECK = Path(__file__).with_name("verify-upstream.py")
UPSTREAM_URL = "https://github.com/CommitteeOfZero/impacto.git"


class SourceIntegrityTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="impacto-upstream-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / "source"
        self.source.mkdir()
        self.git("init", "--quiet")
        self.git("config", "user.email", "fixture@example.invalid")
        self.git("config", "user.name", "Source gate fixture")
        self.git("config", "core.autocrlf", "false")
        (self.source / "engine.cpp").write_text("int main() { return 0; }\n", encoding="utf-8")
        (self.source / ".gitignore").write_text("build/\n", encoding="utf-8")
        self.git("add", ".")
        self.git("-c", "commit.gpgsign=false", "commit", "--quiet", "-m", "fixture")
        self.revision = self.git("rev-parse", "HEAD").strip()
        self.lock = self.root / "source-lock.json"
        self.write_lock(self.revision)
        self.report = self.root / "report.json"

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.source), *args],
                                       text=True, encoding="utf-8", stderr=subprocess.STDOUT)

    def write_lock(self, revision, url=UPSTREAM_URL):
        self.lock.write_text(json.dumps({"impacto": {"url": url, "revision": revision}}),
                             encoding="utf-8")

    def check(self, success):
        result = subprocess.run([sys.executable, str(CHECK), "--source", str(self.source),
                                 "--lock", str(self.lock), "--report", str(self.report)],
                                capture_output=True, text=True, encoding="utf-8")
        self.assertEqual(result.returncode, 0 if success else 1, result.stdout + result.stderr)
        report = json.loads(self.report.read_text(encoding="utf-8"))
        self.assertEqual(report["clean"], success)
        return report

    def test_clean_checkout_records_revision_and_tree(self):
        report = self.check(True)
        self.assertEqual(report["revision"], self.revision)
        self.assertEqual(report["tree"], self.git("rev-parse", "HEAD^{tree}").strip())

    def test_unstaged_change_rejected(self):
        (self.source / "engine.cpp").write_text("modified\n", encoding="utf-8")
        self.check(False)

    def test_replace_ref_cannot_impersonate_locked_revision(self):
        (self.source / "engine.cpp").write_text("patched\n", encoding="utf-8")
        self.git("add", "engine.cpp")
        self.git("-c", "commit.gpgsign=false", "commit", "--quiet", "-m", "modified engine")
        modified = self.git("rev-parse", "HEAD").strip()
        self.git("checkout", "--quiet", "--detach", self.revision)
        self.git("replace", self.revision, modified)
        self.git("read-tree", "--reset", "-u", "HEAD")
        self.assertEqual((self.source / "engine.cpp").read_text(), "patched\n")
        self.assertEqual(self.git("status", "--porcelain"), "")
        self.check(False)

    def test_parent_repository_cannot_impersonate_source_checkout(self):
        child = self.source / "source"
        child.mkdir()
        self.source = child
        self.check(False)

    def test_staged_change_rejected(self):
        (self.source / "engine.cpp").write_text("modified\n", encoding="utf-8")
        self.git("add", "engine.cpp")
        self.check(False)

    def test_untracked_input_rejected(self):
        (self.source / "host.h").write_text("injected\n", encoding="utf-8")
        self.check(False)

    def test_ignored_build_input_rejected(self):
        (self.source / "build").mkdir()
        (self.source / "build" / "generated.h").write_text("injected\n", encoding="utf-8")
        self.check(False)

    def test_deleted_tracked_input_rejected(self):
        (self.source / "engine.cpp").unlink()
        self.check(False)

    def test_assume_unchanged_cannot_hide_change(self):
        self.git("update-index", "--assume-unchanged", "engine.cpp")
        (self.source / "engine.cpp").write_text("modified\n", encoding="utf-8")
        self.check(False)

    def test_skip_worktree_cannot_hide_missing_input(self):
        self.git("update-index", "--skip-worktree", "engine.cpp")
        (self.source / "engine.cpp").unlink()
        self.check(False)

    def test_wrong_revision_rejected(self):
        self.write_lock("0" * 40)
        self.check(False)

    def test_symbolic_revision_rejected(self):
        self.write_lock("main")
        self.check(False)

    def test_other_upstream_rejected(self):
        self.write_lock(self.revision, "https://example.invalid/fork.git")
        self.check(False)

    def test_failed_recheck_replaces_old_success_report(self):
        self.check(True)
        (self.source / "engine.cpp").write_text("modified\n", encoding="utf-8")
        self.check(False)

    def test_git_line_ending_conversion_is_not_a_patch(self):
        converted = self.root / "converted"
        subprocess.run(["git", "clone", "--quiet", "--config", "core.autocrlf=true",
                        str(self.source), str(converted)], check=True)
        self.source = converted
        self.assertEqual((self.source / "engine.cpp").read_bytes(),
                         b"int main() { return 0; }\r\n")
        self.check(True)

    def test_report_cannot_modify_source(self):
        self.report = self.source / "engine.cpp"
        original = self.report.read_bytes()
        result = subprocess.run([sys.executable, str(CHECK), "--source", str(self.source),
                                 "--lock", str(self.lock), "--report", str(self.report)],
                                capture_output=True)
        self.assertEqual(result.returncode, 2)
        self.assertEqual(self.report.read_bytes(), original)

    def add_submodule(self):
        self.git("-c", "protocol.file.allow=always", "submodule", "add", "--quiet",
                 str(self.source), "vendor/engine")
        self.git("-c", "commit.gpgsign=false", "commit", "--quiet", "-m", "submodule fixture")
        self.revision = self.git("rev-parse", "HEAD").strip()
        self.write_lock(self.revision)
        return self.source / "vendor/engine"

    def test_clean_submodule_records_locked_tree(self):
        self.add_submodule()
        report = self.check(True)
        self.assertEqual(len(report["submodules"]), 1)
        self.assertEqual(report["submodules"][0]["path"], "vendor/engine")
        self.assertTrue(report["submodules"][0]["clean"])

    def test_uninitialized_submodule_rejected(self):
        self.add_submodule()
        self.git("submodule", "deinit", "--force", "--all")
        self.check(False)

    def test_modified_submodule_rejected(self):
        child = self.add_submodule()
        (child / "engine.cpp").write_text("modified\n", encoding="utf-8")
        self.check(False)

    def test_ignored_submodule_input_rejected(self):
        child = self.add_submodule()
        (child / "build").mkdir()
        (child / "build/generated.h").write_text("injected\n", encoding="utf-8")
        self.check(False)


if __name__ == "__main__":
    unittest.main(verbosity=2)
