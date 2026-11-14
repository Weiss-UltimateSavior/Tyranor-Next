#!/usr/bin/env python3
"""Check that the values duplicated between the Tyranor app and the Impacto host APK still agree.

The app (Kotlin) and the host APK (Java) are built separately, so these constants exist twice. This reads both sides
as text and fails on any drift: Intent action/extras/package, the accepted profile list, the host version the app
expects, and the release URL derived from that version. It does not run either side.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / 'app/src/main/java/com/tyranor/next/core'
HOST = ROOT / 'engine/impacto/host-apk/app'

CONTRACT = APP / 'engine/external/ImpactoExternalEngineModule.kt'
CATALOG = APP / 'game/scan/ImpactoGameCatalog.kt'
REQUEST = HOST / 'src/main/java/com/tyranor/next/impacto/ImpactoRequest.java'
GRADLE = HOST / 'build.gradle.kts'
MANIFEST = HOST / 'src/main/AndroidManifest.xml'
WORKFLOW = ROOT / '.github/workflows/impacto-host-release.yml'


def text(path):
    return path.read_text(encoding='utf-8')


def kotlin_const(source, name):
    match = re.search(r'const val %s\s*(?::\s*\w+)?\s*=\s*("([^"]*)"|\d+L?)' % name, source)
    if not match:
        raise SystemExit(f'{name} not found in the Kotlin contract')
    return match.group(2) if match.group(2) is not None else match.group(1).rstrip('L')


def java_const(source, name):
    match = re.search(r'static final String %s = "([^"]*)"' % name, source)
    if not match:
        raise SystemExit(f'{name} not found in ImpactoRequest.java')
    return match.group(1)


def catalog_profiles(source):
    return set(re.findall(r'^\s*game\(\s*\n?\s*"([^"]+)"\s*,', source, re.M))


def java_profiles(source):
    block = re.search(r'PROFILES = .*?\)\)\);', source, re.S)
    if not block:
        raise SystemExit('PROFILES not found in ImpactoRequest.java')
    return set(re.findall(r'"([^"]+)"', block.group(0)))


def check():
    problems = []
    kotlin, java, gradle = text(CONTRACT), text(REQUEST), text(GRADLE)

    pairs = {
        'ACTION_RUN': ('ACTION_RUN', 'ACTION_RUN'),
        'EXTRA_GAME_DIR': ('EXTRA_GAME_DIR', 'EXTRA_GAME_DIR'),
        'EXTRA_PROFILE': ('EXTRA_PROFILE', 'EXTRA_PROFILE'),
        'EXTRA_GAME_KEY': ('EXTRA_GAME_KEY', 'EXTRA_GAME_KEY'),
        'EXTRA_TITLE': ('EXTRA_TITLE', 'EXTRA_TITLE'),
    }
    for label, (k, j) in pairs.items():
        if kotlin_const(kotlin, k) != java_const(java, j):
            problems.append(f'{label} differs between the app and the host')

    package = kotlin_const(kotlin, 'PACKAGE')
    if not re.search(r'applicationId\s*=\s*"%s"' % re.escape(package), gradle):
        problems.append('host applicationId differs from ImpactoHostContract.PACKAGE')
    if f'<action android:name="{kotlin_const(kotlin, "ACTION_RUN")}"' not in text(MANIFEST):
        problems.append('host manifest does not declare the app action')

    if catalog_profiles(text(CATALOG)) != java_profiles(java):
        problems.append('profile list differs between ImpactoGameCatalog and ImpactoRequest.PROFILES')

    gradle_code = re.search(r'versionCode\s*=\s*(\d+)', gradle)
    gradle_name = re.search(r'versionName\s*=\s*if \(hostOnly\) "[^"]*" else "([^"]+)"', gradle)
    if not gradle_code or not gradle_name:
        problems.append('could not read the host versionCode/versionName')
    else:
        if kotlin_const(kotlin, 'VERSION_CODE') != gradle_code.group(1):
            problems.append('ImpactoHostContract.VERSION_CODE differs from the host versionCode')
        if kotlin_const(kotlin, 'VERSION_NAME') != gradle_name.group(1):
            problems.append('ImpactoHostContract.VERSION_NAME differs from the host versionName')
        if kotlin_const(kotlin, 'RELEASE_TAG_PREFIX') != 'impacto-host-':
            problems.append('release tag prefix changed; update the release workflow too')
        if WORKFLOW.is_file() and 'impacto-host-${VERSION}' not in text(WORKFLOW):
            problems.append('release workflow does not use the impacto-host-<version> tag')
    return problems


def main():
    problems = check()
    for problem in problems:
        print(f'problem: {problem}')
    print('host contract consistent' if not problems else f'{len(problems)} problem(s)')
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
