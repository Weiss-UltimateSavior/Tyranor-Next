#!/usr/bin/env python3
"""Seed the Wrapper cache from Gradle's official GitHub release after bootstrap fails."""

import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
from urllib.parse import urlsplit
from urllib.request import urlopen


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

    release = json.loads(subprocess.check_output([
        "gh", "api", f"repos/gradle/gradle-distributions/releases/tags/v{version}",
    ], timeout=60))
    assets = [asset for asset in release["assets"] if asset["name"] == archive]
    if len(assets) != 1 or type(assets[0]["id"]) is not int:
        raise ValueError("The official release must contain exactly one matching asset")
    target = wrapper_cache_zip(distribution_url, gradle_home)
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=target.parent, prefix=archive + ".", delete=False) as output:
            temporary = Path(output.name)
            subprocess.run([
                "gh", "api", f"repos/gradle/gradle-distributions/releases/assets/{assets[0]['id']}",
                "--header", "Accept: application/octet-stream",
            ], stdout=output, check=True, timeout=300)
        digest = hashlib.sha256()
        with temporary.open("rb") as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(chunk)
        if digest.hexdigest() != expected.lower():
            raise ValueError("Official Gradle archive SHA-256 mismatch")
        temporary.replace(target)
        print(f"Verified official Gradle release asset: {archive}")
        return target
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


if __name__ == "__main__":
    repository = Path(__file__).resolve().parent.parent
    gradle_home = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle")))
    prepare_distribution(repository / "gradle/wrapper/gradle-wrapper.properties", gradle_home)
