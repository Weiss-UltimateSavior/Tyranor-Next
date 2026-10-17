#!/usr/bin/env bash
#
# sync-app32-java.sh — 生成 `org.libsdl.app32` Java 层（源自 SDL2 2.32.10）。
#
# 用途
#   TyranorNext 内置的 ONS 插件使用 SDL2 2.26.3 的 `org.libsdl.app` Java 层，其
#   `SDLActivity.onCreate` 对 `nativeGetVersion()` 做硬校验（≠ "2.26.3" 即退出），
#   不能与 xsystem35 使用的 SDL2 2.32.10 native 混用（见 docs/System35-xsystem35-引擎接入方案.md §3.4）。
#   因此把 SDL2 2.32.10 的 Java 层整包改名到 `org.libsdl.app32`，与 native 侧的
#   `SDL_JAVA_PREFIX org_libsdl_app32` patch（patches/0001-*.patch、0002-*.patch）配套。
#
# 来源解析（按优先级）
#   1) `--sdl2-src <path>` 或环境变量 `SDL2_SRC`：本地 SDL2 **源码根目录**；也接受直接指向
#      `android-project/app/src/main/java/org/libsdl/app` 目录本身；
#   2) `engine/xsystem35/third_party/SDL2`（若存在；兼容手动按 BUILD.md 准备的旧布局）；
#   3) 下载 `SDL2-2.32.10.tar.gz` 到临时目录（SHA1 校验通过后使用，脚本退出时清理）。
#   SDL2 源码不随主仓分发；一键构建时由 `scripts/build-xsys35.sh` 下载到仓库外构建目录。
#
# 输出
#   engine/src/main/java/org/libsdl/app32/*.java
#
# 转换规则
#   1) `package org.libsdl.app;` → `package org.libsdl.app32;`
#   2) 全文 `org.libsdl.app` → `org.libsdl.app32`（含互引、字符串常量）
#
# 用法
#   sync-app32-java.sh                       无参数：原地重新生成（默认行为，先清空输出目录）
#   sync-app32-java.sh --check               生成到临时目录，与已入库输出逐文件比对：
#                                            一致 → 打印通过并 exit 0；
#                                            有任何差异或文件数不符 → 打印差异摘要并 exit 1
#                                            （用于构建/审核期校验入库的 app32 层与上游源同步，
#                                             不写仓库内文件）。
#   sync-app32-java.sh --sdl2-src <path>     指定 SDL2 源码目录（或 SDL2_SRC=<path> 环境变量）
#
# 行为
#   - 默认模式每次执行先清空输出目录再重新生成，保证可重复执行且不残留已删除的上游文件；
#   - 上游 SDL2 更新（改版本 / URL / SHA1）后重跑本脚本即可；--check 模式可先确认差异。
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
XSYS_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
DEST_DIR="${XSYS_ROOT}/../src/main/java/org/libsdl/app32"

SDL2_VERSION="2.32.10"
SDL2_TARBALL="SDL2-${SDL2_VERSION}.tar.gz"
SDL2_TARBALL_URL="https://github.com/libsdl-org/SDL/releases/download/release-${SDL2_VERSION}/${SDL2_TARBALL}"
SDL2_TARBALL_SHA1="ce98fa93e31836a751feca374ab28a0770b63c16"
SDL2_JAVA_REL="android-project/app/src/main/java/org/libsdl/app"

SDL2_SRC_ARG="${SDL2_SRC:-}"
CHECK=0

usage() {
    sed -n '2,37p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

while [ $# -gt 0 ]; do
    case "$1" in
        --check) CHECK=1; shift ;;
        --sdl2-src) SDL2_SRC_ARG="${2:?--sdl2-src 需要参数}"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *)
            echo "error: 未知参数：$1" >&2
            usage >&2
            exit 2
            ;;
    esac
done

sha1_of() {
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 1 "$1" | awk '{print $1}'
    else
        sha1sum "$1" | awk '{print $1}'
    fi
}

die() {
    echo "error: $*" >&2
    exit 1
}

# ---- 来源解析 ---------------------------------------------------------------

TMP_ROOT=""
cleanup() { [ -n "${TMP_ROOT}" ] && rm -rf "${TMP_ROOT}"; }
trap cleanup EXIT

# 尝试把一个目录解释为 SDL2 Java 层目录（先按源码根，再按 java 目录本身）。
try_java_dir() {
    local base="$1"
    if [ -d "${base}/${SDL2_JAVA_REL}" ]; then
        printf '%s\n' "${base}/${SDL2_JAVA_REL}"
        return 0
    fi
    if [ -f "${base}/SDLActivity.java" ]; then
        printf '%s\n' "${base}"
        return 0
    fi
    return 1
}

SRC_DIR=""
if [ -n "${SDL2_SRC_ARG}" ]; then
    SRC_DIR="$(try_java_dir "${SDL2_SRC_ARG}")" || die \
        "--sdl2-src / SDL2_SRC 不是有效的 SDL2 源码目录：${SDL2_SRC_ARG}"$'\n'"       （需包含 ${SDL2_JAVA_REL}，或直接指向该目录）"
else
    LEGACY_SDL2="${XSYS_ROOT}/third_party/SDL2"
    if [ -d "${LEGACY_SDL2}" ]; then
        SRC_DIR="$(try_java_dir "${LEGACY_SDL2}")" || die \
            "本地 ${LEGACY_SDL2} 不是有效的 SDL2 源码目录（缺少 ${SDL2_JAVA_REL}）"
        echo "source: ${LEGACY_SDL2}（本地 third_party）"
    else
        command -v curl >/dev/null 2>&1 || die "缺少 curl 且无本地 SDL2 源（可用 --sdl2-src 指定）"
        TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/xsys35-sdl2-src.XXXXXX")"
        echo "source: 下载 ${SDL2_TARBALL}（临时目录，用完清理）"
        curl -fL --retry 3 --connect-timeout 30 -o "${TMP_ROOT}/${SDL2_TARBALL}.part" "${SDL2_TARBALL_URL}"
        have_sha1="$(sha1_of "${TMP_ROOT}/${SDL2_TARBALL}.part")"
        [ "${have_sha1}" = "${SDL2_TARBALL_SHA1}" ] || die \
            "${SDL2_TARBALL} SHA1 校验失败：下载得到 ${have_sha1}，期望 ${SDL2_TARBALL_SHA1}"
        mv "${TMP_ROOT}/${SDL2_TARBALL}.part" "${TMP_ROOT}/${SDL2_TARBALL}"
        tar -xzf "${TMP_ROOT}/${SDL2_TARBALL}" -C "${TMP_ROOT}"
        SRC_DIR="$(try_java_dir "${TMP_ROOT}/SDL2-${SDL2_VERSION}")" || die \
            "解压后的 SDL2 源码缺少 ${SDL2_JAVA_REL}"
    fi
fi

[ -n "${SRC_DIR}" ] || die "未能解析 SDL2 Java 层源目录"
java_count="$(find "${SRC_DIR}" -maxdepth 1 -name '*.java' | wc -l | tr -d ' ')"
[ "${java_count}" -gt 0 ] || die "源目录没有 .java 文件：${SRC_DIR}"

if [ "${CHECK}" -eq 1 ]; then
    [ -n "${TMP_ROOT}" ] || TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/xsys35-app32-check.XXXXXX")"
    OUT_DIR="${TMP_ROOT}/out"
else
    OUT_DIR="${DEST_DIR}"
fi

rm -rf "${OUT_DIR}"
mkdir -p "${OUT_DIR}"

count=0
for src in "${SRC_DIR}"/*.java; do
    name="$(basename "${src}")"
    # 从上游 pristine 源生成，因此直接全局替换即可（不需要 word boundary，
    # macOS 的 BSD sed 也不支持 \b）；重复执行的幂等性由「先删目录再生成」保证。
    sed -e 's/org\.libsdl\.app/org.libsdl.app32/g' "${src}" > "${OUT_DIR}/${name}"
    count=$((count + 1))
done

if [ "${CHECK}" -eq 0 ]; then
    echo "generated ${count} file(s) in ${DEST_DIR}"
    exit 0
fi

# --check：先比对文件集合，再逐文件比对内容。
if [ ! -d "${DEST_DIR}" ]; then
    echo "check failed: 输出目录不存在：${DEST_DIR}" >&2
    echo "             请先用无参数模式生成一次。" >&2
    exit 1
fi

fail=0

list_diff="$(diff -u <(cd "${OUT_DIR}" && printf '%s\n' *.java | sort) \
                      <(cd "${DEST_DIR}" && printf '%s\n' *.java | sort) || true)"
if [ -n "${list_diff}" ]; then
    echo "check failed: 文件集合不一致（生成 ${count} 个）" >&2
    printf '%s\n' "${list_diff}" >&2
    fail=1
fi

for generated in "${OUT_DIR}"/*.java; do
    name="$(basename "${generated}")"
    committed="${DEST_DIR}/${name}"
    [ -f "${committed}" ] || continue   # 缺失文件已在集合比对中报告
    if ! cmp -s "${generated}" "${committed}"; then
        echo "check failed: 内容不一致：${name}" >&2
        diff -u "${committed}" "${generated}" | head -40 >&2 || true
        fail=1
    fi
done

if [ "${fail}" -ne 0 ]; then
    echo "check failed: ${DEST_DIR} 与 SDL2 源的生成结果不同步；" >&2
    echo "             请运行 engine/xsystem35/scripts/sync-app32-java.sh 重新生成。" >&2
    exit 1
fi

echo "check passed: ${count} file(s) 与 ${DEST_DIR} 一致"
