#!/usr/bin/env python3
"""Offline integrity, cache compatibility and failure tests for the CI fallback."""

import hashlib
from contextlib import redirect_stdout
import errno
from http.client import IncompleteRead
import importlib.util
import io
import json
from pathlib import Path
import socket
import ssl
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError, URLError


spec = importlib.util.spec_from_file_location("prepare_gradle_distribution", Path(__file__).with_name("prepare-gradle-distribution.py"))
bootstrap = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bootstrap)

URL = "https://services.gradle.org/distributions/gradle-9.5.1-bin.zip"
ARCHIVE = "gradle-9.5.1-bin.zip"
PAYLOAD = b"synthetic official Gradle archive"
CHECKSUM = hashlib.sha256(PAYLOAD).hexdigest()


class GradleDistributionPreparationTest(unittest.TestCase):
    """Keep Wrapper compatibility and failure protection while its download route changes."""

    def setUp(self):
        """Isolate preferences, downloads and cache files from the user's real Gradle home."""
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
        """Restore patched standard-library entry points before deleting owned fixture files."""
        patcher = patch.object(target, attribute, **kwargs)
        self.addCleanup(patcher.stop)
        return patcher.start()

    def write_properties(self, url=URL, checksum=CHECKSUM, path="wrapper/dists"):
        """Exercise supported and rejected Wrapper configuration through an actual properties file."""
        text = f"distributionBase=GRADLE_USER_HOME\ndistributionPath={path}\ndistributionUrl={url}\nzipStoreBase=GRADLE_USER_HOME\nzipStorePath=wrapper/dists\n"
        if checksum is not None:
            text += f"distributionSha256Sum={checksum}\n"
        self.properties.write_text(text, encoding="utf-8")

    @staticmethod
    def write_download(command, *, stdout, **kwargs):
        """Provide synthetic archive bytes without a network request or child process."""
        stdout.write(PAYLOAD)
        return subprocess.CompletedProcess(command, 0)

    def test_verified_asset_uses_the_existing_wrapper_cache_key(self):
        """The official API route must preserve the cache key independently obtained from Wrapper."""
        # Obtained from this repository's PathAssembler.getDistribution, not a second hash implementation.
        expected = self.home / "wrapper/dists/gradle-9.5.1-bin/iq79hdu3mqx29lgffhp8bfmx" / ARCHIVE
        target = bootstrap.prepare_distribution(self.properties, self.home)
        self.assertEqual(expected.resolve(), target)
        self.assertEqual(PAYLOAD, target.read_bytes())
        self.assertEqual([target], list(target.parent.iterdir()))
        self.metadata.assert_called_once_with(["gh", "api", "repos/gradle/gradle-distributions/releases/tags/v9.5.1"], timeout=60, stderr=subprocess.PIPE)
        self.assertEqual(["gh", "api", "repos/gradle/gradle-distributions/releases/assets/123", "--header", "Accept: application/octet-stream"], self.download.call_args.args[0])
        self.assertTrue(self.download.call_args.kwargs["check"])
        self.assertEqual(300, self.download.call_args.kwargs["timeout"])
        self.checksum.assert_not_called()

    def test_missing_pinned_checksum_reads_the_official_checksum(self):
        """Keep compatibility for wrappers without a pinned checksum using only its official URL."""
        self.write_properties(checksum=None)
        target = bootstrap.prepare_distribution(self.properties, self.home)
        self.assertEqual(PAYLOAD, target.read_bytes())
        self.checksum.assert_called_once_with(URL + ".sha256", timeout=60)

    def test_checksum_mismatch_preserves_cache_and_removes_partial_download(self):
        """A download that fails integrity must never replace previously cached bytes."""
        self.write_properties(checksum="0" * 64)
        target = bootstrap.wrapper_cache_zip(URL, self.home)
        target.parent.mkdir(parents=True)
        target.write_bytes(b"previous cache")
        with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
            bootstrap.prepare_distribution(self.properties, self.home)
        self.assertEqual(b"previous cache", target.read_bytes())
        self.assertEqual([target], list(target.parent.iterdir()))

    def test_download_failure_preserves_cache_and_removes_partial_download(self):
        """An interrupted asset transfer must leave no partial artifact or lost prior cache."""
        target = bootstrap.wrapper_cache_zip(URL, self.home)
        target.parent.mkdir(parents=True)
        target.write_bytes(b"previous cache")

        def fail(command, *, stdout, **kwargs):
            """Fail after real temporary-file output to exercise exception cleanup."""
            stdout.write(b"partial")
            raise subprocess.CalledProcessError(1, command)

        self.download.side_effect = fail
        with self.assertRaises(subprocess.CalledProcessError):
            bootstrap.prepare_distribution(self.properties, self.home)
        self.assertEqual(b"previous cache", target.read_bytes())
        self.assertEqual([target], list(target.parent.iterdir()))

    def test_untrusted_or_unsupported_configuration_fails_before_network_or_cache_write(self):
        """Reject sources and layouts outside the fixed official Wrapper contract."""
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
        """Malformed configured integrity data must fail before any asset lookup or write."""
        self.write_properties(checksum="not a checksum")
        with self.assertRaisesRegex(ValueError, "Invalid official"):
            bootstrap.prepare_distribution(self.properties, self.home)
        self.metadata.assert_not_called()
        self.download.assert_not_called()
        self.assertFalse(self.home.exists())

    def test_missing_duplicate_or_non_numeric_release_asset_is_rejected(self):
        """Ambiguous release metadata cannot select an unintended download."""
        for assets in [[], self.release["assets"] * 2, [{"name": ARCHIVE, "id": "123"}]]:
            with self.subTest(assets=assets):
                self.release = {"assets": assets}
                with self.assertRaisesRegex(ValueError, "exactly one"):
                    bootstrap.prepare_distribution(self.properties, self.home)
        self.download.assert_not_called()
        self.assertFalse(self.home.exists())


KOTLIN_VERSION = "2.3.20"
KOTLIN_ARCHIVE = f"kotlin-compiler-embeddable-{KOTLIN_VERSION}.jar"
KOTLIN_PAYLOAD = b"synthetic official Kotlin compiler artifact"
KOTLIN_SHA256 = hashlib.sha256(KOTLIN_PAYLOAD).hexdigest()
KOTLIN_SHA1 = hashlib.sha1(KOTLIN_PAYLOAD, usedforsecurity=False).hexdigest()


class KotlinCompilerPreparationTest(unittest.TestCase):
    """Validate compiler provenance and the content cache used by ordinary Gradle resolution."""

    def setUp(self):
        """Provide independent official-source metadata and synthetic compiler bytes in a private home."""
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.catalog = self.root / "gradle/libs.versions.toml"
        self.catalog.parent.mkdir()
        self.home = self.root / "gradle-home"
        self.write_catalog()
        self.sha1 = KOTLIN_SHA1
        self.release = {"assets": [{"name": KOTLIN_ARCHIVE, "id": 456, "digest": "sha256:" + KOTLIN_SHA256}]}
        self.metadata = self.start_patch(bootstrap.subprocess, "check_output", side_effect=lambda *a, **k: json.dumps(self.release).encode())
        self.download = self.start_patch(bootstrap.subprocess, "run", side_effect=self.write_download)
        self.checksum = self.start_patch(bootstrap, "urlopen", side_effect=lambda *a, **k: io.BytesIO(self.sha1.encode()))

    def start_patch(self, target, attribute, **kwargs):
        """Make every mock test-scoped, including assertions that fail during preparation."""
        patcher = patch.object(target, attribute, **kwargs)
        self.addCleanup(patcher.stop)
        return patcher.start()

    def write_catalog(self, version=KOTLIN_VERSION):
        """Declare the only version input while retaining an unrelated catalog section."""
        self.catalog.write_text(f'[versions]\nandroid = "1.0.0"\nkotlin = "{version}"\n\n[libraries]\nexample = "other:artifact:1"\n', encoding="utf-8")

    def expected_cache(self, version=KOTLIN_VERSION, cache_key=KOTLIN_SHA1):
        """Name the documented Gradle module identity without reusing the production path helper."""
        return self.home / "caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-compiler-embeddable" / version / cache_key / f"kotlin-compiler-embeddable-{version}.jar"

    @staticmethod
    def write_download(command, *, stdout, **kwargs):
        """Keep the artifact integrity checks real while replacing its external transport."""
        stdout.write(KOTLIN_PAYLOAD)
        return subprocess.CompletedProcess(command, 0)

    def test_verified_compiler_uses_maven_content_cache_and_official_release(self):
        """Both independent digests must verify bytes before ordinary Gradle can discover them."""
        target = bootstrap.prepare_kotlin_compiler(self.catalog, self.home)
        self.assertEqual(self.expected_cache().resolve(), target)
        self.assertEqual(KOTLIN_PAYLOAD, target.read_bytes())
        self.assertEqual([target], list(target.parent.iterdir()))
        self.metadata.assert_called_once_with(["gh", "api", "repos/JetBrains/kotlin/releases/tags/v2.3.20"], timeout=60, stderr=subprocess.PIPE)
        self.checksum.assert_called_once_with(f"https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-compiler-embeddable/{KOTLIN_VERSION}/{KOTLIN_ARCHIVE}.sha1", timeout=60)
        self.assertEqual(["gh", "api", "repos/JetBrains/kotlin/releases/assets/456", "--header", "Accept: application/octet-stream"], self.download.call_args.args[0])

    def test_existing_artifact_is_reused_only_after_both_digests_match(self):
        """A correct cache avoids a repeated compiler download while still checking its provenance."""
        expected = self.expected_cache()
        expected.parent.mkdir(parents=True)
        expected.write_bytes(KOTLIN_PAYLOAD)
        self.assertEqual(expected.resolve(), bootstrap.prepare_kotlin_compiler(self.catalog, self.home))
        self.download.assert_not_called()
        self.assertEqual(KOTLIN_PAYLOAD, expected.read_bytes())

    def test_leading_zero_digest_reuses_gradle_canonical_cache_without_download(self):
        """Gradle's unpadded directory key must not shorten the published content checksum."""
        payload = b"synthetic Kotlin compiler leading-zero fixture 66"
        published_sha1 = "00f5e5a91a4f4aa97b657e7842dd3873de341863"
        published_sha256 = "d5407a79e32ceb5e83106a2c672c0950ab4e744f0f2172a7c04ad43a22920f39"
        self.assertEqual(published_sha1, hashlib.sha1(payload, usedforsecurity=False).hexdigest())
        self.assertEqual(published_sha256, hashlib.sha256(payload).hexdigest())
        self.sha1 = published_sha1
        self.release["assets"][0]["digest"] = "sha256:" + published_sha256
        # Gradle's canonical key for this fixed digest; no cache-key algorithm is duplicated here.
        expected = self.expected_cache(cache_key="f5e5a91a4f4aa97b657e7842dd3873de341863")
        expected.parent.mkdir(parents=True)
        expected.write_bytes(payload)
        self.assertEqual(expected.resolve(), bootstrap.prepare_kotlin_compiler(self.catalog, self.home))
        self.download.assert_not_called()
        self.assertEqual(payload, expected.read_bytes())
        self.assertFalse(self.expected_cache(cache_key=published_sha1).exists())

    def test_catalog_version_controls_release_filename_and_cache_identity(self):
        """A future valid catalog version must not reuse the initially observed CI dependency."""
        version = "2.4.1"
        self.write_catalog(version)
        self.release["assets"][0]["name"] = f"kotlin-compiler-embeddable-{version}.jar"
        target = bootstrap.prepare_kotlin_compiler(self.catalog, self.home)
        self.assertEqual(self.expected_cache(version=version).resolve(), target)
        self.metadata.assert_called_once_with(["gh", "api", "repos/JetBrains/kotlin/releases/tags/v2.4.1"], timeout=60, stderr=subprocess.PIPE)

    def test_either_digest_mismatch_preserves_previous_cache_and_cleans_temporary_file(self):
        """GitHub integrity and Maven identity are both required, even if the other one matches."""
        for sha1, sha256, cache_key, message in [
            ("0" * 40, KOTLIN_SHA256, "0", "Maven SHA-1 mismatch"),
            (KOTLIN_SHA1, "0" * 64, KOTLIN_SHA1, "SHA-256 mismatch"),
        ]:
            with self.subTest(checksum=message):
                self.sha1 = sha1
                self.release["assets"][0]["digest"] = "sha256:" + sha256
                target = self.expected_cache(cache_key=cache_key)
                target.parent.mkdir(parents=True)
                target.write_bytes(b"previous compiler")
                with self.assertRaisesRegex(ValueError, message):
                    bootstrap.prepare_kotlin_compiler(self.catalog, self.home)
                self.assertEqual(b"previous compiler", target.read_bytes())
                self.assertEqual([target], list(target.parent.iterdir()))

    def test_download_failure_preserves_compiler_cache_and_cleans_temporary_file(self):
        """Transport failure keeps the old module artifact available and propagates its error."""
        target = self.expected_cache()
        target.parent.mkdir(parents=True)
        target.write_bytes(b"previous compiler")

        def fail(command, *, stdout, **kwargs):
            """Exercise cleanup after a partial real temporary-file write."""
            stdout.write(b"partial compiler")
            raise subprocess.CalledProcessError(1, command)

        self.download.side_effect = fail
        with self.assertRaises(subprocess.CalledProcessError):
            bootstrap.prepare_kotlin_compiler(self.catalog, self.home)
        self.assertEqual(b"previous compiler", target.read_bytes())
        self.assertEqual([target], list(target.parent.iterdir()))

    def test_invalid_maven_checksum_fails_before_release_lookup(self):
        """Missing or malformed Maven identity must not create any Gradle cache entries."""
        for value in ("", "0" * 39, "x" * 40):
            with self.subTest(checksum=value):
                self.sha1 = value
                with self.assertRaisesRegex(ValueError, "Invalid official Maven"):
                    bootstrap.prepare_kotlin_compiler(self.catalog, self.home)
        self.metadata.assert_not_called()
        self.download.assert_not_called()
        self.assertFalse(self.home.exists())

    def test_missing_or_invalid_release_sha256_fails_before_cache_write(self):
        """An official asset without a strong digest is insufficient for the fallback."""
        for value in (None, "", "sha1:" + KOTLIN_SHA1, "sha256:" + "x" * 64):
            with self.subTest(digest=value):
                self.release["assets"][0]["digest"] = value
                with self.assertRaisesRegex(ValueError, "must provide a SHA-256"):
                    bootstrap.prepare_kotlin_compiler(self.catalog, self.home)
        self.download.assert_not_called()
        self.assertFalse(self.home.exists())

    def test_unsupported_or_ambiguous_catalog_fails_before_network(self):
        """Version input cannot redirect a request or escape the fixed artifact layout."""
        catalogs = [
            '[versions]\nkotlin = "2.3.20/../../other"\n',
            '[versions]\nkotlin = "2.3.20?source=other"\n',
            '[versions]\nkotlin = "2.3.20"\nkotlin = "2.4.1"\n',
            '[libraries]\nkotlin = "2.3.20"\n',
            '[versions]\nkotlin = "2.3.20"\n[versions]\nkotlin = "2.3.20"\n',
        ]
        for text in catalogs:
            with self.subTest(catalog=text):
                self.catalog.write_text(text, encoding="utf-8")
                with self.assertRaisesRegex(ValueError, "exactly one supported Kotlin"):
                    bootstrap.prepare_kotlin_compiler(self.catalog, self.home)
        self.metadata.assert_not_called()
        self.download.assert_not_called()
        self.checksum.assert_not_called()
        self.assertFalse(self.home.exists())

    def run_cli(self, arguments=None):
        """Run the real CLI policy using only this test's catalog, home and captured output."""
        output = io.StringIO()
        with patch.object(bootstrap, "__file__", str(self.root / "tools/prepare-gradle-distribution.py")):
            with patch.dict(bootstrap.os.environ, {"GRADLE_USER_HOME": str(self.home)}):
                with redirect_stdout(output):
                    bootstrap.main(["--kotlin-compiler"] if arguments is None else arguments)
        return output.getvalue()

    def test_optional_prefetch_yields_to_transient_checksum_outages(self):
        """Temporary checksum transport failures must leave the original Gradle resolution available."""
        for error in (
            HTTPError("https://repo.maven.apache.org/fixture.sha1", 504, "Gateway timeout", {}, None),
            HTTPError("https://repo.maven.apache.org/fixture.sha1", 429, "Rate limited", {}, None),
            URLError(socket.gaierror(socket.EAI_AGAIN, "Temporary DNS failure")),
            URLError(TimeoutError("Read timeout")),
            ConnectionResetError("Connection reset"),
            IncompleteRead(b"unfinished checksum", 40),
        ):
            with self.subTest(error=type(error).__name__):
                self.checksum.side_effect = error
                self.assertIn("::warning::", self.run_cli())
        self.metadata.assert_not_called()
        self.download.assert_not_called()
        self.assertFalse(self.home.exists())

    def test_optional_prefetch_yields_to_retryable_github_metadata_failures(self):
        """Only explicit GitHub transport failures and retryable statuses may bypass metadata prefetch."""
        for error in (
            subprocess.CalledProcessError(1, "gh", stderr=b"gh: HTTP 503: unavailable"),
            subprocess.CalledProcessError(1, "gh", stderr=b"Get https://api.github.com/: dial tcp 127.0.0.1:443: i/o timeout"),
            subprocess.TimeoutExpired("gh", 60),
        ):
            with self.subTest(error=str(error)):
                self.metadata.side_effect = error
                self.assertIn("::warning::", self.run_cli())
        self.download.assert_not_called()
        self.assertFalse(self.home.exists())

    def test_incomplete_checksum_response_yields_after_closing_the_response(self):
        """An interrupted response body is a transport outage and must release its open response."""
        class InterruptedChecksum(io.BytesIO):
            """Model a successful connection whose chunked checksum body ends prematurely."""

            def read(self, *_):
                """Fail during consumption, after urlopen has already returned successfully."""
                raise IncompleteRead(b"partial checksum", 40)

        response = InterruptedChecksum()
        self.checksum.side_effect = None
        self.checksum.return_value = response
        self.assertIn("::warning::", self.run_cli())
        self.assertTrue(response.closed)
        self.metadata.assert_not_called()
        self.download.assert_not_called()
        self.assertFalse(self.home.exists())

    def test_interrupted_optional_download_cleans_partial_file_before_fallback(self):
        """A transient download outage can yield only after removing its unfinished cache write."""
        def fail(command, *, stdout, **kwargs):
            """Fail a real partial temporary write with a retryable HTTP response."""
            stdout.write(b"partial compiler")
            raise subprocess.CalledProcessError(1, command, stderr=b"gh: HTTP 502: Bad gateway")

        self.download.side_effect = fail
        self.assertIn("::warning::", self.run_cli())
        self.assertFalse(self.expected_cache().exists())
        self.assertEqual([], list(self.expected_cache().parent.iterdir()))

    def test_short_content_length_response_yields_instead_of_becoming_bad_metadata(self):
        """An ordinary HTTP body ending before its declared length is also a transport failure."""
        response = io.BytesIO(b"partial sha")
        response.headers = {"Content-Length": "40"}
        self.checksum.side_effect = None
        self.checksum.return_value = response
        self.assertIn("::warning::", self.run_cli())
        self.assertTrue(response.closed)
        self.metadata.assert_not_called()
        self.assertFalse(self.home.exists())

    def test_known_bad_cache_cannot_yield_to_normal_gradle_resolution(self):
        """A failed replacement must not turn detected cache corruption into a successful prefetch exit."""
        target = self.expected_cache()
        target.parent.mkdir(parents=True)
        target.write_bytes(b"known corrupt compiler")
        self.download.side_effect = subprocess.CalledProcessError(1, "gh", stderr=b"gh: HTTP 504: Gateway timeout")
        with self.assertRaisesRegex(ValueError, "failed integrity validation"):
            self.run_cli()
        self.assertEqual(b"known corrupt compiler", target.read_bytes())
        self.assertEqual([target], list(target.parent.iterdir()))

    def test_cli_rejects_integrity_and_configuration_failures(self):
        """Malformed metadata, digest mismatches and unsupported versions remain blocking at the CLI."""
        self.release["assets"][0]["digest"] = "sha256:" + "0" * 64
        with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
            self.run_cli()
        self.sha1 = "0" * 40
        self.release["assets"][0]["digest"] = "sha256:" + KOTLIN_SHA256
        with self.assertRaisesRegex(ValueError, "Maven SHA-1 mismatch"):
            self.run_cli()
        self.sha1 = "malformed"
        with self.assertRaisesRegex(ValueError, "Invalid official Maven"):
            self.run_cli()
        self.write_catalog("../unsupported")
        with self.assertRaisesRegex(ValueError, "exactly one supported Kotlin"):
            self.run_cli()

    def test_cli_does_not_hide_access_tls_or_local_failures(self):
        """Authentication, missing tools, TLS verification and local I/O failures are not network fallback."""
        for status in (400, 401, 403, 404):
            with self.subTest(status=status):
                self.metadata.side_effect = subprocess.CalledProcessError(1, "gh", stderr=f"gh: HTTP {status}: denied".encode())
                with self.assertRaises(subprocess.CalledProcessError):
                    self.run_cli()
        for message in (
            b'Get "https://api.github.com/": dial tcp 127.0.0.1:443: connect: permission denied',
            b'error connecting to api.github.com: operation not permitted',
            b'error connecting to api.github.com: x509: certificate signed by unknown authority',
            b'error connecting to api.github.com: connectex: An attempt was made to access a socket in a way forbidden by its access permissions.',
            b'error connecting to api.github.com: unsupported protocol scheme "bogus"',
            b'error connecting to api.github.com',
            b'gh: unknown process failure',
        ):
            with self.subTest(stderr=message):
                self.metadata.side_effect = subprocess.CalledProcessError(1, "gh", stderr=message)
                with self.assertRaises(subprocess.CalledProcessError):
                    self.run_cli()
        self.metadata.side_effect = FileNotFoundError("missing gh executable")
        with self.assertRaises(FileNotFoundError):
            self.run_cli()
        self.metadata.side_effect = None
        self.metadata.return_value = json.dumps(self.release).encode()
        for error in (
            URLError(ssl.SSLCertVerificationError("certificate rejected")),
            URLError(PermissionError(errno.EACCES, "permission denied")),
            URLError("unknown transport error"),
        ):
            with self.subTest(error=str(error)):
                self.checksum.side_effect = error
                with self.assertRaises(URLError):
                    self.run_cli()
        self.checksum.side_effect = lambda *a, **k: io.BytesIO(self.sha1.encode())
        self.download.side_effect = PermissionError("cache write denied")
        with self.assertRaises(PermissionError):
            self.run_cli()

    def test_distribution_bootstrap_remains_mandatory_on_network_failure(self):
        """The already-needed Gradle distribution fallback must never inherit optional compiler semantics."""
        with patch.object(bootstrap, "prepare_distribution", side_effect=bootstrap.ArtifactNetworkError("unavailable")):
            with self.assertRaises(bootstrap.ArtifactNetworkError):
                self.run_cli([])

    def test_successful_optional_prefetch_keeps_verified_bytes_without_warning(self):
        """The CLI's success path still prepares and verifies the same compiler artifact."""
        self.assertNotIn("::warning::", self.run_cli())
        self.assertEqual(KOTLIN_PAYLOAD, self.expected_cache().read_bytes())

    def test_github_rate_limit_403_yields_without_weakening_access_errors(self):
        """Explicit GitHub primary/secondary limits may yield; ordinary or mixed denial statuses may not."""
        for message in (
            b"gh: API rate limit exceeded for fixture (HTTP 403)",
            b"gh: You have exceeded a secondary rate limit. Please wait. (HTTP 403)",
        ):
            with self.subTest(stderr=message):
                error = subprocess.CalledProcessError(1, "gh", stderr=message)
                self.metadata.side_effect = error
                self.assertIn("::warning::", self.run_cli())
                self.metadata.side_effect = None
                self.metadata.return_value = json.dumps(self.release).encode()
                self.download.side_effect = error
                self.assertIn("::warning::", self.run_cli())
                self.assertFalse(self.expected_cache().exists())
                self.assertEqual([], list(self.expected_cache().parent.iterdir()))
        for message in (
            b"gh: Forbidden (HTTP 403)",
            b"gh: Resource not accessible by integration (HTTP 403)",
            b"gh: Resource not accessible by integration (HTTP 403); API rate limit exceeded",
            b"gh: API rate limit exceeded (HTTP 403); Unauthorized (HTTP 401)",
            b"gh: API rate limit exceeded (HTTP 403)\ngh: Forbidden (HTTP 403)",
            b"gh: API rate limit exceeded (HTTP 403); permission denied",
            b"gh: API rate limit exceeded (HTTP 403); x509: certificate verification failed",
        ):
            with self.subTest(stderr=message):
                self.metadata.side_effect = subprocess.CalledProcessError(1, "gh", stderr=message)
                with self.assertRaises(subprocess.CalledProcessError):
                    self.run_cli()
        self.checksum.side_effect = HTTPError("https://repo.maven.apache.org/fixture.sha1", 403, "Forbidden", {}, None)
        self.addCleanup(self.checksum.side_effect.close)
        with self.assertRaises(HTTPError):
            self.run_cli()

    def test_permanent_dns_errors_are_not_optional_network_outages(self):
        """Invalid proxy/domain names remain visible configuration failures in urllib and gh."""
        for code in (socket.EAI_NONAME, socket.EAI_FAIL):
            with self.subTest(errno=code):
                self.checksum.side_effect = URLError(socket.gaierror(code, "permanent lookup failure"))
                with self.assertRaises(URLError):
                    self.run_cli()
        self.metadata.assert_not_called()
        self.assertFalse(self.home.exists())
        self.checksum.side_effect = lambda *a, **k: io.BytesIO(self.sha1.encode())
        self.metadata.side_effect = subprocess.CalledProcessError(1, "gh", stderr=b"lookup wrong-proxy.invalid: no such host")
        with self.assertRaises(subprocess.CalledProcessError):
            self.run_cli()

    def test_explicit_temporary_dns_diagnostics_still_yield(self):
        """Temporary DNS errno and explicit gh diagnostics retain normal Gradle resolution as fallback."""
        self.checksum.side_effect = URLError(socket.gaierror(socket.EAI_AGAIN, "temporary lookup failure"))
        self.assertIn("::warning::", self.run_cli())
        self.checksum.side_effect = lambda *a, **k: io.BytesIO(self.sha1.encode())
        for message in (
            b"lookup proxy.invalid: Temporary failure in name resolution",
            b"lookup proxy.invalid: This is usually a temporary error during hostname resolution.",
        ):
            with self.subTest(stderr=message):
                self.metadata.side_effect = subprocess.CalledProcessError(1, "gh", stderr=message)
                self.assertIn("::warning::", self.run_cli())
        self.assertFalse(self.home.exists())


if __name__ == "__main__":
    unittest.main()
