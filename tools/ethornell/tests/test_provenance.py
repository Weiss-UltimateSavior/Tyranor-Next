"""Provenance gate regression tests; no network/NDK/real Android library needed."""

import copy
import argparse
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import verify_provenance as provenance
import verify_native as native


class ProvenanceTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.source = self.root / "engine-source"
        self.source.mkdir()
        self.header = self.root / "vendored.h"
        self.header.write_text("#define ETHORNELL_ANDROID_ABI_VERSION 1u\n"
                               "uint32_t ethornell_android_abi_version(void);\n")
        self.library = self.root / "libethornell.so"
        # Not an ELF! These tests concern provenance only, never native acceptance.
        self.library.write_bytes(b"synthetic provenance test, not an Android library")
        self.manifest = {
            "source_inventory_policy": provenance.INVENTORY_POLICY,
            "abi": 1, "target": "aarch64-linux-android", "api": 26,
            "ndk": "28.0.13004108", "rustc": "rustc 1.90.0 (test)",
            "source_revision": "a" * 40, "dirty": False,
            "source_file_sha256": {
                provenance.SOURCE_HEADER: native.sha256(self.header.read_bytes()),
                "Cargo.toml": native.sha256(b"[workspace]\n"),
                "Cargo.lock": native.sha256(b"lock")},
            "cargo_lock_sha256": native.sha256(b"lock"),
            "library_sha256": native.sha256(self.library.read_bytes()),
            "library_bytes": self.library.stat().st_size,
        }
        self.file = self.root / "libethornell.provenance.json"

    def preflight(self):
        return provenance.preflight(self.manifest, self.library, self.header)

    def test_valid_build_manifest_format(self):
        self.assertEqual(self.preflight(), "a" * 40)

    def test_missing_library_fails_explicitly(self):
        self.library.unlink()
        with self.assertRaisesRegex(native.ValidationError, "missing formal runtime"):
            self.preflight()

    def test_dirty_or_missing_state_never_accepted(self):
        for dirty in [True, None, 0, "false"]:
            self.manifest["dirty"] = dirty
            with self.subTest(dirty=dirty), self.assertRaisesRegex(native.ValidationError, "dirty"):
                self.preflight()

    def test_local_mode_can_record_dirty_claim_but_still_checks_hashes(self):
        self.manifest["dirty"] = True
        self.assertEqual(provenance.preflight(self.manifest, self.library, self.header, strict=False), "a" * 40)
        self.header.write_bytes(b"wrong header")
        with self.assertRaises(native.ValidationError):
            provenance.preflight(self.manifest, self.library, self.header, strict=False)

    def test_ref_or_abbreviated_sha_not_accepted(self):
        for revision in ["HEAD", "main", "a" * 7, "-" + "a" * 39, None]:
            self.manifest["source_revision"] = revision
            with self.subTest(revision=revision), self.assertRaises(native.ValidationError):
                self.preflight()

    def test_build_policy_types_and_values(self):
        original = copy.deepcopy(self.manifest)
        for key, value in [("api", 27), ("api", "26"), ("abi", True),
                           ("ndk", "28.1.13356709"), ("target", "x86_64-linux-android"),
                           ("rustc", ""), ("source_repository", "https://example.com/evil.git")]:
            self.manifest = {**original, key: value}
            with self.subTest(key=key, value=value), self.assertRaises(native.ValidationError):
                self.preflight()

    def test_header_raw_byte_hash_not_just_prototype_names(self):
        self.header.write_text(self.header.read_text() + "/* changed */\n")
        with self.assertRaisesRegex(native.ValidationError, "header SHA256"):
            self.preflight()

    def test_changed_library_bytes_rejected(self):
        self.library.write_bytes(b"other library")
        with self.assertRaisesRegex(native.ValidationError, "runtime SHA256"):
            self.preflight()

    def test_library_size_and_cargo_lock_disagreement(self):
        self.manifest["library_bytes"] += 1
        with self.assertRaisesRegex(native.ValidationError, "byte size"):
            self.preflight()
        self.manifest["library_bytes"] -= 1
        self.manifest["cargo_lock_sha256"] = "0" * 64
        with self.assertRaisesRegex(native.ValidationError, "Cargo.lock hash"):
            self.preflight()

    def test_unsafe_manifest_paths_fail_before_network(self):
        for name in [".", "../x", "/tmp/x", "x\\y", "x/../y", "x//y", "./x", ".git/config", "x\0y"]:
            with self.subTest(name=name), self.assertRaises(native.ValidationError):
                provenance.valid_path(name)

    def test_duplicate_json_key_rejected(self):
        self.file.write_text('{"dirty":true,"dirty":false}')
        with self.assertRaisesRegex(native.ValidationError, "duplicate JSON"):
            provenance.read_manifest(self.file)

    def test_missing_or_malformed_source_inventory(self):
        for inventory in [None, {}, {"Cargo.lock": "bad"}, {"Cargo.lock": "a" * 64}]:
            self.manifest["source_file_sha256"] = inventory
            with self.subTest(inventory=inventory), self.assertRaises(native.ValidationError):
                self.preflight()

    def git(self, *arguments):
        result = subprocess.run(["git", "-C", str(self.source), *arguments],
                                check=True, capture_output=True, text=True)
        return result.stdout.strip()

    def real_source(self):
        self.git("init", "--quiet")
        self.git("config", "user.email", "test@example.invalid")
        self.git("config", "user.name", "Test")
        self.git("remote", "add", "origin", provenance.SOURCE_REPOSITORY)
        header = self.source / provenance.SOURCE_HEADER
        header.parent.mkdir(parents=True)
        header.write_bytes(self.header.read_bytes())
        (self.source / "Cargo.lock").write_bytes(b"lock")
        (self.source / "Cargo.toml").write_bytes(b"[workspace]\n")
        self.git("add", ".")
        self.git("-c", "core.hooksPath=/dev/null", "commit", "--quiet", "-m", "synthetic test repository")
        self.manifest["source_revision"] = self.git("rev-parse", "HEAD")

    def verify_source(self):
        return provenance.verify_source(self.source, self.manifest, self.root / "app")

    def test_actual_clean_git_repository_inventory_matches(self):
        self.real_source()
        self.assertEqual(self.verify_source()["files_verified"], 3)

    def test_app_checkout_cannot_be_used_as_source(self):
        with self.assertRaisesRegex(native.ValidationError, "App checkout"):
            provenance.verify_source(self.source, self.manifest, self.source)

    def test_local_repo_must_match_origin_and_revision(self):
        self.real_source()
        self.manifest["source_revision"] = "b" * 40
        with self.assertRaisesRegex(native.ValidationError, "build revision"):
            self.verify_source()
        self.manifest["source_revision"] = self.git("rev-parse", "HEAD")
        self.git("remote", "set-url", "origin", "https://example.invalid/other.git")
        with self.assertRaisesRegex(native.ValidationError, "approved public fork"):
            self.verify_source()

    def test_actual_dirty_or_untracked_source_rejected(self):
        self.real_source()
        (self.source / "new.rs").write_text("untracked")
        with self.assertRaisesRegex(native.ValidationError, "checkout is dirty"):
            self.verify_source()

    def test_inventory_cannot_omit_or_add_source(self):
        self.real_source()
        for name, digest in [("Cargo.lock", None), ("extra.rs", "f" * 64)]:
            original = copy.deepcopy(self.manifest["source_file_sha256"])
            if digest:
                self.manifest["source_file_sha256"][name] = digest
            else:
                del self.manifest["source_file_sha256"][name]
            with self.assertRaisesRegex(native.ValidationError, "inventory/hashes differ"):
                self.verify_source()
            self.manifest["source_file_sha256"] = original

    def test_symlink_and_submodule_modes_rejected(self):
        for mode in ["120000", "160000"]:
            with patch.object(provenance, "git", side_effect=[str(self.source), provenance.SOURCE_REPOSITORY,
                    self.manifest["source_revision"], "", f"{mode} {'c' * 40} 0\tlink\0"]):
                with self.assertRaisesRegex(native.ValidationError, "source mode"):
                    self.verify_source()

    def test_fetch_uses_full_revision_from_fixed_fork(self):
        destination = self.root / "fetched"
        revision = "f" * 40
        with patch.object(provenance, "git", return_value="") as mocked:
            provenance.fetch_source(destination, revision)
        calls = [call.args[1:] for call in mocked.call_args_list]
        self.assertIn(("remote", "add", "origin", provenance.SOURCE_REPOSITORY), calls)
        self.assertIn(("fetch", "--depth=1", "--no-tags", "origin", revision), calls)
        with self.assertRaises(FileExistsError):
            provenance.fetch_source(destination, revision)

    def test_cli_dirty_rejected_without_fetching_or_modifying_destination(self):
        self.manifest["dirty"] = True
        self.file.write_text(json.dumps(self.manifest))
        destination = self.root / "must-not-exist"
        result = subprocess.run([sys.executable, str(Path(provenance.__file__)),
            "--library", str(self.library), "--header", str(self.header),
            "--fetch-source", str(destination)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 1)
        self.assertFalse(json.loads(result.stdout)["ok"])
        self.assertIn("dirty", result.stderr)
        self.assertFalse(destination.exists())

    def test_cli_source_only_and_native_modes_distinguished(self):
        self.real_source()
        self.file.write_text(json.dumps(self.manifest))
        command = [sys.executable, str(Path(provenance.__file__)), "--library", str(self.library),
                   "--header", str(self.header), "--source-root", str(self.source)]
        result = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotIn("native", json.loads(result.stdout))
        # Provenance-only success must never accidentally count as ELF acceptance.
        result = subprocess.run(command + ["--ndk", str(self.root / "missing-ndk")],
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 1)
        self.assertFalse(json.loads(result.stdout)["ok"])

    def test_local_cli_reports_pending_never_release_ready_and_does_not_fetch(self):
        self.manifest["dirty"] = True
        self.file.write_text(json.dumps(self.manifest))
        ndk = self.root / "ndk"
        ndk.mkdir()
        (ndk / "source.properties").write_text("Pkg.Revision = 28.0.13004108\n")
        output = io.StringIO()
        args = [str(provenance.__file__), "--library", str(self.library), "--header", str(self.header),
                "--local-static", "--ndk", str(ndk)]
        with patch.object(sys, "argv", args), patch.object(sys, "stdout", output), \
                patch.object(provenance, "local_static", return_value={"ok": True, "errors": []}), \
                patch.object(provenance, "fetch_source") as fetch, \
                patch.object(provenance, "verify_source") as source:
            self.assertEqual(provenance.main(), 0)
        report = json.loads(output.getvalue())
        self.assertTrue(report["ok"])
        self.assertTrue(report["source"]["dirty"])
        self.assertFalse(report["release_ready"])
        self.assertEqual(report["source_status"], "pending")
        self.assertIn("strict release", report["pending"][0])
        fetch.assert_not_called()
        source.assert_not_called()

    def strict_with_mock_native(self, native_result=None):
        self.file.write_text(json.dumps(self.manifest))
        ndk = self.root / "ndk"
        ndk.mkdir(exist_ok=True)
        (ndk / "source.properties").write_text("Pkg.Revision = 28.0.13004108\n")
        args = [str(provenance.__file__), "--library", str(self.library), "--header", str(self.header),
                "--source-root", str(self.source), "--ndk", str(ndk)]
        output = io.StringIO()
        with patch.object(sys, "argv", args), patch.object(sys, "stdout", output), \
                patch.object(sys, "stderr", io.StringIO()), \
                patch.object(native, "verify", return_value=native_result or {"ok": True, "errors": []}) as verify:
            code = provenance.main()
        return code, json.loads(output.getvalue()), verify

    def test_strict_ndk_success_passes_real_engine_root_and_preserves_clean_manifest(self):
        self.real_source()
        original = copy.deepcopy(self.manifest)
        code, report, verify = self.strict_with_mock_native()
        self.assertEqual(code, 0, report)
        self.assertTrue(report["ok"])
        self.assertTrue(report["release_ready"])
        self.assertEqual(report["source_status"], "verified-consistency-not-attestation")
        self.assertEqual(report["source"]["files_verified"], len(original["source_file_sha256"]))
        self.assertFalse(report["source"]["dirty"])
        verify.assert_called_once()
        passed = verify.call_args.args[0]
        self.assertEqual(passed.source_root.resolve(), self.source.resolve())
        self.assertNotEqual(passed.source_root.resolve(), Path(provenance.__file__).resolve().parents[2])
        self.assertEqual(passed.header, self.header)
        self.assertEqual(passed.library, self.library)
        self.assertEqual(passed.ndk, str(self.root / "ndk"))
        self.assertEqual(passed.host_tag, "linux-x86_64")
        self.assertEqual(passed.api, 26)
        self.assertEqual(json.loads(self.file.read_text()), original)
        self.assertEqual(self.manifest, original)

    def test_strict_ndk_rejects_dirty_or_incomplete_inventory_before_native(self):
        self.real_source()
        # A committed extra file must be present in the full inventory too.
        (self.source / "engine.rs").write_text("synthetic source")
        self.git("add", "engine.rs")
        self.git("-c", "core.hooksPath=/dev/null", "commit", "--quiet", "-m", "extra source")
        self.manifest["source_revision"] = self.git("rev-parse", "HEAD")
        self.manifest["source_file_sha256"]["engine.rs"] = native.sha256(b"synthetic source")
        original = copy.deepcopy(self.manifest)
        for case in ["incomplete inventory", "dirty manifest", "dirty checkout"]:
            with self.subTest(case=case):
                self.manifest = copy.deepcopy(original)
                self.manifest["dirty"] = case == "dirty manifest"
                if case == "incomplete inventory":
                    del self.manifest["source_file_sha256"]["engine.rs"]
                if case == "dirty checkout":
                    (self.source / "untracked.rs").write_text("untracked source")
                code, report, verify = self.strict_with_mock_native()
                self.assertEqual(code, 1)
                self.assertFalse(report["ok"])
                self.assertFalse(report["release_ready"])
                expected = {"incomplete inventory": "inventory/hashes differ",
                            "dirty manifest": "dirty source provenance",
                            "dirty checkout": "checkout is dirty"}[case]
                self.assertTrue(any(expected in error for error in report["errors"]), report)
                verify.assert_not_called()
                self.assertEqual(json.loads(self.file.read_text()), self.manifest)
        self.assertFalse(original["dirty"])

    def test_strict_ndk_clean_sources_do_not_override_native_failure(self):
        self.real_source()
        code, report, verify = self.strict_with_mock_native({"ok": False, "errors": ["ELF policy failed"]})
        self.assertEqual(code, 1)
        self.assertFalse(report["ok"])
        self.assertFalse(report["release_ready"])
        self.assertIn("ELF policy failed", report["errors"])
        verify.assert_called_once()

    def add_source_files(self, files):
        for name, content in files.items():
            path = self.source / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
            if provenance.inventory_includes(name):
                self.manifest["source_file_sha256"][name] = native.sha256(content)
        self.git("add", ".")
        self.git("-c", "core.hooksPath=/dev/null", "commit", "--quiet", "-m", "synthetic inputs")
        self.manifest["source_revision"] = self.git("rev-parse", "HEAD")

    def test_inventory_policy_must_be_explicit_in_strict_mode(self):
        del self.manifest["source_inventory_policy"]
        with self.assertRaisesRegex(native.ValidationError, "source_inventory_policy"):
            self.preflight()
        # Legacy local reports remain useful but never establish source closure.
        provenance.preflight(self.manifest, self.library, self.header, strict=False)
        self.manifest["source_inventory_policy"] = "anything-the-manifest-claims"
        with self.assertRaisesRegex(native.ValidationError, "source_inventory_policy"):
            provenance.preflight(self.manifest, self.library, self.header, strict=False)

    def test_policy_independently_enumerates_build_inputs_excluding_reference(self):
        self.real_source()
        self.add_source_files({"crates/a/Cargo.toml": b"[package]\n", "crates/a/build.rs": b"fn main() {}",
            "crates/a/src/lib.rs": b"pub fn f() {}", "scripts/build.sh": b"echo build",
            "crates/a/include/api.h": b"/* header */", "crates/a/shader.wgsl": b"shader",
            "raw/disassembly.c": b"reference", "game.arc": b"reference",
            "crates/a/BGI.gdb": b"reference", "dist/output.zip": b"output"})
        self.assertEqual(self.verify_source()["files_verified"], 9)
        for name in ["crates/a/Cargo.toml", "crates/a/build.rs", "crates/a/src/lib.rs",
                     "scripts/build.sh", "crates/a/include/api.h", "crates/a/shader.wgsl"]:
            original = self.manifest["source_file_sha256"].pop(name)
            with self.subTest(name=name), self.assertRaisesRegex(native.ValidationError, "inventory/hashes differ"):
                self.verify_source()
            self.manifest["source_file_sha256"][name] = original

    def test_compile_include_literal_closure_covers_non_source_assets(self):
        self.real_source()
        self.add_source_files({"crates/a/src/lib.rs":
            b'const X: &[u8] = include_bytes!("../data.bin");\n'
            b'const Y: &str = include_str!(r#"../shader.wgsl"#);\n'
            b'const Z: &str = include_str /* comment */ !("../shader.wgsl");\n'
            b'include!(r"generated.rs");\n',
            "crates/a/data.bin": b"not game data", "crates/a/shader.wgsl": b"shader",
            "crates/a/src/generated.rs": b"fn generated() {}"})
        self.assertEqual(self.verify_source()["files_verified"], 7)
        del self.manifest["source_file_sha256"]["crates/a/data.bin"]
        with self.assertRaisesRegex(native.ValidationError, "inventory/hashes differ"):
            self.verify_source()

    def test_compile_include_excluded_reference_or_outside_root_rejected(self):
        self.real_source()
        self.add_source_files({"crates/a/src/lib.rs": b"", "raw/data.bin": b"reference"})
        files = self.manifest["source_file_sha256"]
        source = self.source / "crates/a/src/lib.rs"
        for target in ["../../../raw/data.bin", "../../../../outside.bin", "missing.bin", str(source)]:
            source.write_text(f'const X: &[u8] = include_bytes!("{target}");')
            with self.subTest(target=target), self.assertRaisesRegex(native.ValidationError, "compile-time include"):
                provenance.verify_compile_includes(self.source.resolve(), files)

    def test_dynamic_compile_include_requires_explicit_policy_review(self):
        self.real_source()
        self.add_source_files({"crates/a/src/lib.rs": b""})
        path = self.source / "crates/a/src/lib.rs"
        for expression in ['concat!(env!("OUT_DIR"), "/data.bin")', 'env!("ASSET")', '"escaped\\x2ebin"']:
            path.write_text(f"include_bytes!({expression});")
            with self.subTest(expression=expression), self.assertRaisesRegex(native.ValidationError, "unresolved"):
                provenance.verify_compile_includes(self.source.resolve(), self.manifest["source_file_sha256"])

    def test_build_source_cannot_be_hidden_under_excluded_directory(self):
        self.real_source()
        self.add_source_files({"raw/hidden.rs": b"fn hidden() {}"})
        with self.assertRaisesRegex(native.ValidationError, "excluded directory requires policy review"):
            self.verify_source()

    def test_local_mode_requires_ndk_never_provenance_only_success(self):
        self.file.write_text(json.dumps(self.manifest))
        result = subprocess.run([sys.executable, str(provenance.__file__),
            "--library", str(self.library), "--header", str(self.header), "--local-static"],
            capture_output=True, text=True)
        self.assertEqual(result.returncode, 1)
        self.assertIn("requires --ndk", result.stderr)

    def local_static(self, apk=None, needed=None):
        args = argparse.Namespace(ndk=self.root, host_tag="linux-x86_64", header=self.header,
                                  library=self.library, apk=apk)
        def inspect(path, tools, abi, api):
            return {"name": path.name, "errors": [], "needed": needed or []}
        with patch.object(native, "resolve_tools", return_value={"nm": "fake"}), \
                patch.object(native, "run", return_value="fake tool version"), \
                patch.object(native, "inspect_library", side_effect=inspect):
            return provenance.local_static(args)

    def test_local_static_still_enforces_dependency_allowlist_and_presence(self):
        self.assertTrue(self.local_static()["ok"])
        self.assertFalse(self.local_static(needed=["libevil.so"])["ok"])
        self.assertFalse(self.local_static(needed=["libc++_shared.so"])["ok"])
        (self.library.parent / "libc++_shared.so").write_bytes(b"helper")
        report = self.local_static(needed=["libc++_shared.so"])
        self.assertTrue(report["ok"])
        self.assertEqual(len(report["libraries"]), 2)

    def test_local_apk_checks_exact_bytes_missing_and_duplicate_runtime(self):
        apk = self.root / "app.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("lib/arm64-v8a/libethornell.so", self.library.read_bytes())
        self.assertTrue(self.local_static(apk)["ok"])
        self.library.write_bytes(b"changed runtime")
        with self.assertRaisesRegex(native.ValidationError, "differs from packaged"):
            self.local_static(apk)
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("lib/x86_64/libethornell.so", b"wrong ABI")
        with self.assertRaisesRegex(native.ValidationError, "runtime absent"):
            self.local_static(apk)
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("lib/arm64-v8a/libethornell.so", self.library.read_bytes())
            with self.assertWarns(UserWarning):
                archive.writestr("lib/arm64-v8a/libethornell.so", b"duplicate")
        with self.assertRaisesRegex(native.ValidationError, "duplicate APK"):
            self.local_static(apk)


if __name__ == "__main__":
    unittest.main()
