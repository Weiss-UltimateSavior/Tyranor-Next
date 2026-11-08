#!/usr/bin/env python3
"""Prepare Gradle toolchain artifacts from their verified official GitHub releases."""

import argparse
import errno
import hashlib
from http.client import IncompleteRead
import json
import os
from pathlib import Path
import re
import socket
import ssl
import subprocess
import tempfile
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import urlopen


class ArtifactNetworkError(RuntimeError):
    """An official artifact transport is temporarily unavailable, before any integrity failure."""


def wrapper_cache_zip(distribution_url: str, gradle_home: Path) -> Path:
    """Match Gradle PathAssembler's MD5/base36 URL cache key without changing its URL."""
    number = int.from_bytes(hashlib.md5(distribution_url.encode("utf-8")).digest(), "big")
    alphabet = "0123456789abcdefghijklmnopqrstuvwxyz"
    digits = ""
    while number:
        number, digit = divmod(number, 36)
        digits = alphabet[digit] + digits
    archive = urlsplit(distribution_url).path.rsplit("/", 1)[-1]
    cache_root = (gradle_home / "wrapper" / "dists").resolve()
    target = (cache_root / archive.removesuffix(".zip") / (digits or "0") / archive).resolve()
    if not target.is_relative_to(cache_root):
        raise ValueError("Wrapper cache path must stay in the distribution cache")
    return target


def release_asset(repository: str, version: str, filename: str) -> dict:
    """Select one exact release asset without trusting a metadata-provided download URL."""
    try:
        metadata = subprocess.check_output([
            "gh", "api", f"repos/{repository}/releases/tags/v{version}",
        ], timeout=60, stderr=subprocess.PIPE)
    except (subprocess.TimeoutExpired, subprocess.CalledProcessError) as error:
        if is_transient_network_error(error):
            raise ArtifactNetworkError("Official release metadata is temporarily unavailable") from error
        raise
    release = json.loads(metadata)
    assets = [asset for asset in release["assets"] if asset["name"] == filename]
    if len(assets) != 1 or type(assets[0]["id"]) is not int or assets[0]["id"] <= 0:
        raise ValueError("The official release must contain exactly one matching asset")
    return assets[0]


def file_digests(path: Path) -> tuple:
    """Read the artifact once for strong integrity and Gradle's legacy content cache identity."""
    sha256 = hashlib.sha256()
    sha1 = hashlib.sha1(usedforsecurity=False)
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            sha256.update(chunk)
            sha1.update(chunk)
    return sha256.hexdigest(), sha1.hexdigest()


def download_verified_asset(repository: str, asset: dict, expected_sha256: str, target: Path, expected_sha1: str = None) -> Path:
    """Reuse verified bytes or replace the cache atomically; failures preserve its previous contents."""
    def matches(path: Path) -> bool:
        """Require the strong digest and, for Maven artifacts, its independently published identity."""
        sha256, sha1 = file_digests(path)
        return sha256 == expected_sha256.lower() and (expected_sha1 is None or sha1 == expected_sha1.lower())

    invalid_cache = target.is_file()
    if invalid_cache:
        if matches(target):
            print(f"Reused verified official release asset: {asset['name']}")
            return target
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=target.parent, prefix=target.name + ".", delete=False) as output:
            temporary = Path(output.name)
            try:
                subprocess.run([
                    "gh", "api", f"repos/{repository}/releases/assets/{asset['id']}",
                    "--header", "Accept: application/octet-stream",
                ], stdout=output, stderr=subprocess.PIPE, check=True, timeout=300)
            except (subprocess.TimeoutExpired, subprocess.CalledProcessError) as error:
                if is_transient_network_error(error):
                    if invalid_cache:
                        raise ValueError("Cached release asset failed integrity validation and a verified replacement is unavailable") from error
                    raise ArtifactNetworkError("Official release asset is temporarily unavailable") from error
                raise
        sha256, sha1 = file_digests(temporary)
        if sha256 != expected_sha256.lower():
            raise ValueError("Official release asset SHA-256 mismatch")
        if expected_sha1 is not None and sha1 != expected_sha1.lower():
            raise ValueError("Official release asset Maven SHA-1 mismatch")
        temporary.replace(target)
        print(f"Verified official release asset: {asset['name']}")
        return target
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def prepare_distribution(properties_file: Path, gradle_home: Path) -> Path:
    """Verify the same official archive before atomically making it available to Wrapper."""
    properties = {}
    for line in properties_file.read_text(encoding="utf-8").splitlines():
        key, separator, value = line.partition("=")
        if separator and not key.lstrip().startswith(("#", "!")):
            properties[key.strip()] = value.strip().replace("\\:", ":")
    for base in ("distributionBase", "zipStoreBase"):
        if properties.get(base) != "GRADLE_USER_HOME":
            raise ValueError("Only the standard Gradle user cache is supported")
    for path in ("distributionPath", "zipStorePath"):
        if properties.get(path) != "wrapper/dists":
            raise ValueError("Only the standard Wrapper distribution path is supported")
    distribution_url = properties["distributionUrl"]
    parsed = urlsplit(distribution_url)
    if parsed.scheme != "https" or parsed.netloc not in ("services.gradle.org", "downloads.gradle.org") or parsed.query or parsed.fragment:
        raise ValueError("The distribution must use an official Gradle HTTPS URL")
    match = re.fullmatch(r"/distributions/(gradle-(\d+(?:\.\d+){1,2}(?:-[A-Za-z0-9.-]+)?)-(?:bin|all)\.zip)", parsed.path)
    if not match:
        raise ValueError("Unsupported Gradle distribution filename")
    archive, version = match.groups()
    expected = properties.get("distributionSha256Sum")
    if expected is None:
        with urlopen(distribution_url + ".sha256", timeout=60) as response:
            expected = response.read(1024).decode("ascii").strip()
    if not re.fullmatch(r"[a-fA-F0-9]{64}", expected):
        raise ValueError("Invalid official Gradle SHA-256 checksum")

    repository = "gradle/gradle-distributions"
    asset = release_asset(repository, version, archive)
    target = wrapper_cache_zip(distribution_url, gradle_home)
    return download_verified_asset(repository, asset, expected, target)


def prepare_kotlin_compiler(catalog_file: Path, gradle_home: Path) -> Path:
    """Seed only the catalog's compiler JAR, requiring official release SHA-256 and Maven SHA-1."""
    sections = re.findall(r"(?ms)^\[versions\][ \t]*\n(.*?)(?=^\[|\Z)", catalog_file.read_text(encoding="utf-8"))
    versions = re.findall(r'^\s*kotlin\s*=\s*"([^"\n]+)"\s*(?:#.*)?$', sections[0], re.MULTILINE) if len(sections) == 1 else []
    if len(versions) != 1 or not re.fullmatch(r"\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?", versions[0]):
        raise ValueError("The versions catalog must declare exactly one supported Kotlin version")
    version = versions[0]
    filename = f"kotlin-compiler-embeddable-{version}.jar"
    maven_url = f"https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-compiler-embeddable/{version}/{filename}"
    try:
        with urlopen(maven_url + ".sha1", timeout=60) as response:
            declared_length = getattr(response, "headers", {}).get("Content-Length")
            expected_length = int(declared_length) if declared_length is not None else None
            if expected_length is not None and not 0 <= expected_length <= 1024:
                raise ValueError("Invalid official Maven checksum response length")
            checksum = response.read(1025)
            if len(checksum) > 1024:
                raise ValueError("Official Maven checksum response is too large")
            if expected_length is not None and len(checksum) < expected_length:
                raise IncompleteRead(checksum, expected_length - len(checksum))
    except (URLError, TimeoutError, ConnectionError, IncompleteRead) as error:
        if is_transient_network_error(error):
            if isinstance(error, HTTPError):
                error.close()
            raise ArtifactNetworkError("Official Maven checksum is temporarily unavailable") from error
        raise
    expected_sha1 = checksum.decode("ascii").strip()
    if not re.fullmatch(r"[a-fA-F0-9]{40}", expected_sha1):
        raise ValueError("Invalid official Maven SHA-1 checksum")
    repository = "JetBrains/kotlin"
    asset = release_asset(repository, version, filename)
    digest = asset.get("digest")
    if not isinstance(digest, str) or not re.fullmatch(r"sha256:[a-fA-F0-9]{64}", digest):
        raise ValueError("The official Kotlin release asset must provide a SHA-256 digest")
    expected_sha256 = digest.removeprefix("sha256:")
    cache_root = (gradle_home / "caches" / "modules-2" / "files-2.1").resolve()
    cache_key = expected_sha1.lower().lstrip("0") or "0"
    target = (cache_root / "org.jetbrains.kotlin" / "kotlin-compiler-embeddable" / version / cache_key / filename).resolve()
    if not target.is_relative_to(cache_root):
        raise ValueError("Compiler cache path must stay in the Gradle artifact cache")
    return download_verified_asset(repository, asset, expected_sha256, target, expected_sha1)


def is_transient_network_error(error: Exception) -> bool:
    """Allow only transport outages and retryable HTTP responses, never integrity or access errors."""
    if isinstance(error, HTTPError):
        return error.code in (408, 429) or 500 <= error.code <= 599
    if isinstance(error, URLError):
        reason = error.reason
        if isinstance(reason, ssl.SSLError):
            return False
        return isinstance(reason, (TimeoutError, ConnectionError, socket.gaierror)) or (
            isinstance(reason, OSError) and reason.errno in (
                errno.ENETUNREACH, errno.EHOSTUNREACH, errno.ETIMEDOUT,
                errno.ECONNRESET, errno.ECONNREFUSED, errno.ECONNABORTED,
            )
        )
    if isinstance(error, (TimeoutError, ConnectionError, IncompleteRead, subprocess.TimeoutExpired)):
        return True
    if isinstance(error, subprocess.CalledProcessError):
        message = error.stderr or b""
        if isinstance(message, bytes):
            message = message.decode("utf-8", errors="replace")
        message = message.lower()
        if any(fragment in message for fragment in (
            "permission denied", "operation not permitted", "access is denied", "x509:",
            "certificate verify failed", "certificate verification failed", "unknown authority",
        )):
            return False
        statuses = re.findall(r"\bHTTP(?:/[\d.]+)?[ :]+(\d{3})\b", message, re.IGNORECASE)
        if statuses:
            return all(int(status) in (408, 429) or 500 <= int(status) <= 599 for status in statuses)
        return any(fragment in message for fragment in (
            "tls handshake timeout", "i/o timeout", "context deadline exceeded",
            "connection reset by peer", "connection refused", "network is unreachable",
            "no such host", "unexpected eof", "http2: client connection lost",
        ))
    return False


def main(argv=None) -> None:
    """Keep Gradle bootstrap mandatory; let optional Kotlin prefetch yield only to network outages."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kotlin-compiler", action="store_true", help="Prepare the catalog's Kotlin compiler instead of the Gradle distribution")
    options = parser.parse_args(argv)
    repository = Path(__file__).resolve().parent.parent
    gradle_home = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle")))
    if options.kotlin_compiler:
        try:
            prepare_kotlin_compiler(repository / "gradle/libs.versions.toml", gradle_home)
        except ArtifactNetworkError:
            print("::warning::Kotlin compiler prefetch unavailable; continuing with normal Gradle dependency resolution")
    else:
        prepare_distribution(repository / "gradle/wrapper/gradle-wrapper.properties", gradle_home)


if __name__ == "__main__":
    main()
