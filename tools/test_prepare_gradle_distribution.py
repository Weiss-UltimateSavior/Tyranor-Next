#!/usr/bin/env python3
"""Offline integrity, cache compatibility and failure tests for the CI fallback."""

import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("prepare_gradle_distribution", Path(__file__).with_name("prepare-gradle-distribution.py"))
bootstrap = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bootstrap)

URL = "https://services.gradle.org/distributions/gradle-9.5.1-bin.zip"
ARCHIVE = "gradle-9.5.1-bin.zip"
PAYLOAD = b"synthetic official Gradle archive"
CHECKSUM = hashlib.sha256(PAYLOAD).hexdigest()


class GradleDistributionPreparationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.properties = self.root / "gradle-wrapper.properties"
        self.home = self.root / "gradle-home"
        self.write_properties()
        self.release = {"assets": [{"name": ARCHIVE, "id": 123}]}
        self.metadata = self.start_patch(bootstrap.subprocess, "check_output", side_effect=lambda *a, **k: json.dumps(self.release).encode())
        self.download = self.start_patch(bootstrap.subprocess, "run", side_effect=self.write_download)
        self.checksum = self.start_patch(bootstrap, "urlopen", return_value=io.BytesIO(CHECKSUM.encode()))

    def start_patch(self, target, attribute, **kwargs):
        patcher = patch.object(target, attribute, **kwargs)
        self.addCleanup(patcher.stop)
        return patcher.start()

    def write_properties(self, url=URL, checksum=CHECKSUM, path="wrapper/dists"):
        text = f"distributionBase=GRADLE_USER_HOME\ndistributionPath={path}\ndistributionUrl={url}\nzipStoreBase=GRADLE_USER_HOME\nzipStorePath=wrapper/dists\n"
        if checksum is not None:
            text += f"distributionSha256Sum={checksum}\n"
        self.properties.write_text(text, encoding="utf-8")

    @staticmethod
    def write_download(command, *, stdout, **kwargs):
        stdout.write(PAYLOAD)
        return subprocess.CompletedProcess(command, 0)

    def test_verified_asset_uses_the_existing_wrapper_cache_key(self):
        # Obtained from this repository's PathAssembler.getDistribution, not a second hash implementation.
        expected = self.home / "wrapper/dists/gradle-9.5.1-bin/iq79hdu3mqx29lgffhp8bfmx" / ARCHIVE
        target = bootstrap.prepare_distribution(self.properties, self.home)
        self.assertEqual(expected.resolve(), target)
        self.assertEqual(PAYLOAD, target.read_bytes())
        self.assertEqual([target], list(target.parent.iterdir()))
        self.metadata.assert_called_once_with(["gh", "api", "repos/gradle/gradle-distributions/releases/tags/v9.5.1"], timeout=60)
        self.assertEqual(["gh", "api", "repos/gradle/gradle-distributions/releases/assets/123", "--header", "Accept: application/octet-stream"], self.download.call_args.args[0])
        self.assertTrue(self.download.call_args.kwargs["check"])
        self.assertEqual(300, self.download.call_args.kwargs["timeout"])
        self.checksum.assert_not_called()

    def test_missing_pinned_checksum_reads_the_official_checksum(self):
        self.write_properties(checksum=None)
        target = bootstrap.prepare_distribution(self.properties, self.home)
        self.assertEqual(PAYLOAD, target.read_bytes())
        self.checksum.assert_called_once_with(URL + ".sha256", timeout=60)

    def test_checksum_mismatch_preserves_cache_and_removes_partial_download(self):
        self.write_properties(checksum="0" * 64)
        target = bootstrap.wrapper_cache_zip(URL, self.home)
        target.parent.mkdir(parents=True)
        target.write_bytes(b"previous cache")
        with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
            bootstrap.prepare_distribution(self.properties, self.home)
        self.assertEqual(b"previous cache", target.read_bytes())
        self.assertEqual([target], list(target.parent.iterdir()))

    def test_download_failure_preserves_cache_and_removes_partial_download(self):
        target = bootstrap.wrapper_cache_zip(URL, self.home)
        target.parent.mkdir(parents=True)
        target.write_bytes(b"previous cache")

        def fail(command, *, stdout, **kwargs):
            stdout.write(b"partial")
            raise subprocess.CalledProcessError(1, command)

        self.download.side_effect = fail
        with self.assertRaises(subprocess.CalledProcessError):
            bootstrap.prepare_distribution(self.properties, self.home)
        self.assertEqual(b"previous cache", target.read_bytes())
        self.assertEqual([target], list(target.parent.iterdir()))

    def test_untrusted_or_unsupported_configuration_fails_before_network_or_cache_write(self):
        for url, path in [
            (URL.replace("https:", "http:"), "wrapper/dists"),
            (URL.replace("services.gradle.org", "other.invalid"), "wrapper/dists"),
            ("https://" + "user" + "@" + "services.gradle.org/distributions/" + ARCHIVE, "wrapper/dists"),
            (URL + "?redirect=other", "wrapper/dists"),
            (URL + "#fragment", "wrapper/dists"),
            (URL.replace("9.5.1-bin", "../9.5.1-bin"), "wrapper/dists"),
            (URL, "../outside"),
        ]:
            with self.subTest(url=url, path=path):
                self.write_properties(url=url, path=path)
                with self.assertRaises(ValueError):
                    bootstrap.prepare_distribution(self.properties, self.home)
        self.metadata.assert_not_called()
        self.download.assert_not_called()
        self.checksum.assert_not_called()
        self.assertFalse(self.home.exists())

    def test_invalid_checksum_is_rejected_before_asset_download(self):
        self.write_properties(checksum="not a checksum")
        with self.assertRaisesRegex(ValueError, "Invalid official"):
            bootstrap.prepare_distribution(self.properties, self.home)
        self.metadata.assert_not_called()
        self.download.assert_not_called()
        self.assertFalse(self.home.exists())

    def test_missing_duplicate_or_non_numeric_release_asset_is_rejected(self):
        for assets in [[], self.release["assets"] * 2, [{"name": ARCHIVE, "id": "123"}]]:
            with self.subTest(assets=assets):
                self.release = {"assets": assets}
                with self.assertRaisesRegex(ValueError, "exactly one"):
                    bootstrap.prepare_distribution(self.properties, self.home)
        self.download.assert_not_called()
        self.assertFalse(self.home.exists())


if __name__ == "__main__":
    unittest.main()
