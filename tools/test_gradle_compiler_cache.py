#!/usr/bin/env python3
"""Exercise compiler prefetch with the real repository Wrapper and local Maven metadata.

Run ``python tools/test_gradle_compiler_cache.py -v`` after this repository's
``./gradlew --version --no-daemon`` (``gradlew.bat`` on Windows) has installed its
distribution. Only Python's standard library and the existing Java/Wrapper are
required. No compiler or Gradle distribution is downloaded by these tests.

The installed Wrapper distribution and its existing completion marker are staged
in temporary homes, using hard links for immutable distribution files when
possible. Dependency caches, daemon state, projects and Java temporary files are
private to each test and removed afterwards. The user's Gradle home is read only.
"""

import hashlib
from http.server import BaseHTTPRequestHandler, HTTPServer
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import shutil
import signal
import struct
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
from urllib.parse import urlsplit
import zipfile


REPOSITORY = Path(__file__).resolve().parent.parent
CATALOG = REPOSITORY / "gradle/libs.versions.toml"
WRAPPER_PROPERTIES = REPOSITORY / "gradle/wrapper/gradle-wrapper.properties"


def load_bootstrap():
    """Import the production entry points without writing bytecode into the checkout."""
    source = REPOSITORY / "tools/prepare-gradle-distribution.py"
    spec = importlib.util.spec_from_file_location("compiler_cache_bootstrap", source)
    module = importlib.util.module_from_spec(spec)
    with patch.object(sys, "dont_write_bytecode", True):
        spec.loader.exec_module(module)
    return module


def synthetic_jar():
    """Build a small deterministic JAR whose SHA-1 exposes leading-zero cache keys."""
    archive = io.BytesIO()
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_STORED) as jar:
        manifest = zipfile.ZipInfo("META-INF/MANIFEST.MF", (1980, 1, 1, 0, 0, 0))
        jar.writestr(manifest, b"Manifest-Version: 1.0\r\n\r\n")
        marker = zipfile.ZipInfo("cache-probe.txt", (1980, 1, 1, 0, 0, 0))
        jar.writestr(marker, b"Synthetic Kotlin compiler cache integration fixture\n")
    # Change only the ZIP comment: every candidate remains a valid, reproducible JAR.
    prefix = archive.getvalue()[:-2]
    for nonce in range(1_000_000):
        comment = str(nonce).encode("ascii")
        payload = prefix + struct.pack("<H", len(comment)) + comment
        sha1 = hashlib.sha1(payload, usedforsecurity=False).hexdigest()
        if sha1.startswith("00"):
            return payload, sha1, hashlib.sha256(payload).hexdigest()
    raise AssertionError("Could not construct the deterministic leading-zero JAR fixture")


def installed_distribution(bootstrap):
    """Require the current Wrapper's completed local installation before doing any work."""
    properties = {}
    for line in WRAPPER_PROPERTIES.read_text(encoding="utf-8").splitlines():
        key, separator, value = line.partition("=")
        if separator and not key.lstrip().startswith(("#", "!")):
            properties[key.strip()] = value.strip().replace("\\:", ":")
    for key in ("distributionBase", "zipStoreBase"):
        if properties.get(key) != "GRADLE_USER_HOME":
            raise AssertionError("The integration test requires the standard Wrapper user cache")
    for key in ("distributionPath", "zipStorePath"):
        if properties.get(key) != "wrapper/dists":
            raise AssertionError("The integration test requires the standard Wrapper distribution path")
    url = properties["distributionUrl"]
    archive = urlsplit(url).path.rsplit("/", 1)[-1]
    match = re.fullmatch(r"(gradle-.+)-(?:bin|all)\.zip", archive)
    if match is None:
        raise AssertionError("Unsupported Wrapper distribution filename: " + archive)
    gradle_home = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle")))
    cached_zip = bootstrap.wrapper_cache_zip(url, gradle_home)
    distribution = cached_zip.parent / match.group(1)
    marker = cached_zip.with_name(cached_zip.name + ".ok")
    version = match.group(1).removeprefix("gradle-")
    launcher = distribution / "lib" / ("gradle-launcher-" + version + ".jar")
    if not marker.is_file() or not launcher.is_file() or not (distribution / "bin/gradle").is_file():
        wrapper = "gradlew.bat" if os.name == "nt" else "./gradlew"
        raise AssertionError(
            "The current Wrapper distribution is not completely installed. Run "
            + wrapper + " --version --no-daemon first; this test never downloads it."
        )
    return gradle_home.resolve(), distribution, marker, cached_zip


def link_or_copy(source, destination):
    """Share immutable installed distribution bytes, falling back across filesystems."""
    try:
        os.link(source, destination)
    except OSError:
        shutil.copy2(source, destination)
    return destination


class LocalMavenRepository:
    """Serve synthetic POM/checksums and reject every artifact GET on loopback."""

    def __init__(self, version, payload, sha1, sha256):
        """Keep all bytes and the request ledger in memory, independently of the cache."""
        self.requests = []
        self.artifact_path = (
            "/org/jetbrains/kotlin/kotlin-compiler-embeddable/" + version
            + "/kotlin-compiler-embeddable-" + version + ".jar"
        )
        pom = (
            "<project><modelVersion>4.0.0</modelVersion>"
            "<groupId>org.jetbrains.kotlin</groupId>"
            "<artifactId>kotlin-compiler-embeddable</artifactId>"
            "<version>" + version + "</version><packaging>jar</packaging></project>"
        ).encode("utf-8")
        pom_path = self.artifact_path.removesuffix(".jar") + ".pom"
        resources = {
            pom_path: pom,
            pom_path + ".sha1": hashlib.sha1(pom, usedforsecurity=False).hexdigest().encode("ascii"),
            pom_path + ".sha256": hashlib.sha256(pom).hexdigest().encode("ascii"),
            self.artifact_path + ".sha1": sha1.encode("ascii"),
            self.artifact_path + ".sha256": sha256.encode("ascii"),
        }
        fixture = self

        class Handler(BaseHTTPRequestHandler):
            """Expose only the exact synthetic Maven coordinates, with no network forwarding."""

            def setup(self):
                """Bound incomplete local requests so server shutdown cannot wait on a stalled client."""
                self.request.settimeout(2)
                super().setup()

            def log_message(self, *_):
                """Keep test output limited to the explicit request ledger."""

            def respond(self):
                """Allow artifact HEAD, but make every JAR GET an observable failure."""
                fixture.requests.append({"method": self.command, "path": self.path})
                status, reason, data = 200, "OK", resources.get(self.path)
                if urlsplit(self.path).path.endswith(".jar") and self.command == "GET":
                    status, reason = 403, "JAR GET forbidden by cache integration test"
                    data = reason.encode("ascii")
                elif self.path == fixture.artifact_path and self.command == "HEAD":
                    data = payload
                elif data is None:
                    status, reason, data = 404, "No synthetic metadata", b"Not found"
                self.send_response(status, reason)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                if self.command != "HEAD":
                    self.wfile.write(data)

            def do_GET(self):
                """Serve metadata and checksums, denying artifact bytes."""
                self.respond()

            def do_HEAD(self):
                """Report the synthetic artifact's true size without sending its bytes."""
                self.respond()

            def do_CONNECT(self):
                """Deny accidental external HTTPS, including any Wrapper download attempt."""
                fixture.requests.append({"method": self.command, "path": self.path})
                self.send_error(403, "External networking forbidden by cache integration test")

        self.server = HTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(
            target=self.server.serve_forever,
            kwargs={"poll_interval": 0.05},
            name="compiler-cache-maven",
            daemon=True,
        )

    def __enter__(self):
        """Start listening before Wrapper runs, using an OS-assigned local port."""
        self.thread.start()
        return self

    def __exit__(self, *_):
        """Release the listener and join its thread even when Gradle or assertions fail."""
        try:
            self.server.shutdown()
        finally:
            self.server.server_close()
            self.thread.join(timeout=10)
        if self.thread.is_alive():
            raise AssertionError("Local Maven server thread did not stop")

    @property
    def artifact_gets(self):
        """Identify forbidden transfers independently of Gradle's exit status."""
        return [
            request for request in self.requests
            if request["method"] == "GET" and urlsplit(request["path"]).path.endswith(".jar")
        ]


class GradleCompilerCacheIntegrationTest(unittest.TestCase):
    """Verify actual Gradle cache reuse and the guard against hidden artifact downloads."""

    @classmethod
    def setUpClass(cls):
        """Read repository versions and require existing Wrapper files without installing anything."""
        cls.bootstrap = load_bootstrap()
        cls.source_home, cls.distribution, cls.completion_marker, cls.cached_zip = installed_distribution(cls.bootstrap)
        match = re.search(r'^kotlin\s*=\s*"([^"]+)"', CATALOG.read_text(encoding="utf-8"), re.MULTILINE)
        if match is None:
            raise AssertionError("The repository catalog has no Kotlin version")
        cls.version = match.group(1)
        cls.payload, cls.sha1, cls.sha256 = synthetic_jar()

    def setUp(self):
        """Stage only the immutable distribution into a short, otherwise empty temporary home."""
        temporary = tempfile.TemporaryDirectory(prefix="kc-")
        self.addCleanup(self.cleanup_temporary, temporary)
        self.root = Path(temporary.name).resolve()
        self.home = self.root / "g"
        relative = self.cached_zip.relative_to(self.source_home)
        staged_zip = self.home / relative
        shutil.copytree(self.distribution, staged_zip.parent / self.distribution.name, copy_function=link_or_copy)
        # Preserve an existing Wrapper completion marker; never synthesize or share its lock files.
        shutil.copy2(self.completion_marker, staged_zip.with_name(staged_zip.name + ".ok"))
        self.project = self.root / "p"
        self.project.mkdir()
        self.result_file = self.root / "result.json"
        self.seeded = self.seed_compiler()

    @staticmethod
    def cleanup_temporary(temporary):
        """Allow Windows single-use daemon DLL handles a bounded interval to close."""
        deadline = time.monotonic() + 15
        while True:
            try:
                temporary.cleanup()
                return
            except PermissionError:
                if os.name != "nt" or time.monotonic() >= deadline:
                    raise
                time.sleep(0.2)

    def seed_compiler(self):
        """Run production preparation with only official metadata and transport replaced."""
        filename = "kotlin-compiler-embeddable-" + self.version + ".jar"
        release = {"assets": [{"name": filename, "id": 1, "digest": "sha256:" + self.sha256}]}

        def metadata(command, **_):
            """Require the real catalog's official release route before returning fixture metadata."""
            self.assertEqual(["gh", "api", "repos/JetBrains/kotlin/releases/tags/v" + self.version], command)
            return json.dumps(release).encode("utf-8")

        def checksum(url, **_):
            """Replace only the official Maven checksum request, never arbitrary network calls."""
            expected = (
                "https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-compiler-embeddable/"
                + self.version + "/" + filename + ".sha1"
            )
            self.assertEqual(expected, url)
            return io.BytesIO(self.sha1.encode("ascii"))

        def download(command, *, stdout, **_):
            """Write a tiny valid JAR through the production atomic-download/integrity path."""
            self.assertEqual([
                "gh", "api", "repos/JetBrains/kotlin/releases/assets/1",
                "--header", "Accept: application/octet-stream",
            ], command)
            stdout.write(self.payload)
            return subprocess.CompletedProcess(command, 0)

        with patch.object(self.bootstrap.subprocess, "check_output", side_effect=metadata), \
                patch.object(self.bootstrap, "urlopen", side_effect=checksum), \
                patch.object(self.bootstrap.subprocess, "run", side_effect=download):
            target = self.bootstrap.prepare_kotlin_compiler(CATALOG, self.home)
        self.assertEqual(self.payload, target.read_bytes())
        self.assertTrue(target.is_relative_to(self.home))
        return target

    def run_gradle(self, repository):
        """Resolve through the original Wrapper with all Gradle writes isolated and networking local."""
        (self.project / "settings.gradle").write_bytes(b"rootProject.name = 'compiler-cache-integration'\n")
        build = """import groovy.json.JsonOutput
import java.security.MessageDigest

repositories {
    maven {
        url = uri(System.getenv('CACHE_PROBE_REPOSITORY'))
        allowInsecureProtocol = true
        metadataSources { mavenPom(); artifact() }
    }
}
configurations { compiler }
dependencies {
    compiler('org.jetbrains.kotlin:kotlin-compiler-embeddable:' + System.getenv('CACHE_PROBE_VERSION')) {
        transitive = false
    }
}
tasks.register('verifySeededCompiler') {
    doLast {
        def artifact = configurations.compiler.singleFile.canonicalFile
        def expected = new File(System.getenv('CACHE_PROBE_SEEDED')).canonicalFile
        if (artifact != expected) {
            throw new GradleException('Resolved compiler path differs from seeded path: ' + artifact)
        }
        def digest = MessageDigest.getInstance('SHA-256').digest(artifact.bytes).encodeHex().toString()
        if (digest != System.getenv('CACHE_PROBE_SHA256')) {
            throw new GradleException('Resolved compiler bytes differ from the synthetic JAR')
        }
        new File(System.getenv('CACHE_PROBE_RESULT')).text = JsonOutput.toJson([
            resolvedPath: artifact.path,
            sha256: digest
        ])
    }
}
"""
        (self.project / "build.gradle").write_bytes(build.encode("utf-8"))
        java_temp = self.root / "t"
        java_temp.mkdir()
        env = os.environ.copy()
        for name in ("JAVA_OPTS", "GRADLE_OPTS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"):
            env.pop(name, None)
        env.update({
            "GRADLE_USER_HOME": str(self.home),
            "CACHE_PROBE_REPOSITORY": "http://127.0.0.1:" + str(repository.server.server_port) + "/",
            "CACHE_PROBE_VERSION": self.version,
            "CACHE_PROBE_SEEDED": str(self.seeded),
            "CACHE_PROBE_SHA256": self.sha256,
            "CACHE_PROBE_RESULT": str(self.result_file),
            "TEMP": str(java_temp), "TMP": str(java_temp), "TMPDIR": str(java_temp),
        })
        wrapper = REPOSITORY / ("gradlew.bat" if os.name == "nt" else "gradlew")
        command = [str(wrapper)] if os.name == "nt" else ["sh", str(wrapper)]
        command += [
            "-g", str(self.home), "-p", str(self.project),
            "--no-daemon", "--console=plain", "--no-configuration-cache", "--no-build-cache",
            "-Djava.io.tmpdir=" + str(java_temp),
            "-Dhttp.proxyHost=127.0.0.1", "-Dhttps.proxyHost=127.0.0.1",
            "-Dhttp.proxyPort=" + str(repository.server.server_port),
            "-Dhttps.proxyPort=" + str(repository.server.server_port),
            "-Dhttp.nonProxyHosts=127.0.0.1", "verifySeededCompiler",
        ]
        options = {"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP} if os.name == "nt" else {"start_new_session": True}
        with subprocess.Popen(command, cwd=self.project, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, **options) as process:
            try:
                output, _ = process.communicate(timeout=180)
            except BaseException:
                if os.name == "nt":
                    subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15)
                else:
                    os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=15)
                raise
        return process.returncode, output.decode("utf-8", errors="replace")

    def report(self, repository, returncode, **details):
        """Print portable evidence to stdout so callers can retain logs outside the repository."""
        print(json.dumps({
            "test": self._testMethodName, "gradle_exit": returncode,
            "kotlin_version": self.version, "fixture_sha1": self.sha1,
            "seededPath": str(self.seeded), "requests": repository.requests,
            "artifact_gets": len(repository.artifact_gets),
            "server_stopped": not repository.thread.is_alive(), **details,
        }, sort_keys=True), flush=True)

    def test_prefetched_compiler_is_resolved_from_the_seeded_path(self):
        """Gradle must resolve the exact production-seeded bytes without a single JAR GET."""
        with LocalMavenRepository(self.version, self.payload, self.sha1, self.sha256) as repository:
            returncode, output = self.run_gradle(repository)
        self.assertEqual([], repository.artifact_gets, output)
        self.assertEqual(0, returncode, output)
        result = json.loads(self.result_file.read_text(encoding="utf-8"))
        self.assertEqual(self.seeded.resolve(), Path(result["resolvedPath"]).resolve())
        self.assertEqual(self.sha256, result["sha256"])
        self.assertEqual(self.payload, Path(result["resolvedPath"]).read_bytes())
        self.assertIn({"method": "GET", "path": repository.artifact_path + ".sha1"}, repository.requests)
        self.report(repository, returncode, **result)

    def test_empty_cache_attempts_an_artifact_get_and_is_rejected(self):
        """The empty-cache control proves the HTTP denial and positive test detect downloads."""
        self.seeded.unlink()
        with LocalMavenRepository(self.version, self.payload, self.sha1, self.sha256) as repository:
            returncode, output = self.run_gradle(repository)
        self.assertTrue(repository.artifact_gets, output)
        self.assertNotEqual(0, returncode, output)
        self.assertIn("403", output)
        self.assertIn("JAR GET forbidden", output)
        self.assertFalse(self.result_file.exists())
        self.assertFalse(self.seeded.exists())
        self.report(repository, returncode)


if __name__ == "__main__":
    unittest.main()
