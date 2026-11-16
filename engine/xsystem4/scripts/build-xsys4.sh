#!/usr/bin/env bash
#
# build-xsys4.sh — 一键可复现构建 xsystem4（System4）Android arm64 运行库。
#
# 背景
#   TyranorNext 主仓**不 vendored** xsystem4 源码与依赖：本脚本把 fork 源码、libsys4 子模块、
#   libsndfile（xsystem4 分支）与全部静态依赖准备到**仓库外**构建目录（默认
#   `${TMPDIR:-/tmp}/xsys4-build`，可用 `--build-dir` 覆盖），以 fork 检出目录作为**顶层 CMake
#   源**构建，再把 `libxsystem4.so` 入库到 `engine/src/main/jniLibs/arm64-v8a/`，并把 53 个着色器
#   原样复制到 `engine/src/main/assets/shaders/`，许可文本写入 `engine/xsystem4/licenses/`。
#   完整背景、依赖校验表、产物实测与复现性记录见 `engine/xsystem4/BUILD.md`。
#   方案见 `docs/System4-xsystem4-引擎接入方案.md` §5.4。
#
# 步骤（幂等可重跑；依赖以 staging 前缀 stamp 判定是否跳过）
#   1) 源码：`--src` 指向本地 xsystem4 checkout（只读复用，克隆一份到构建目录）；缺省 clone fork；
#      两种情况都检出目标 commit（`--ref`，缺省固定基线），并拉取 libsys4 / libsndfile(xsystem4 分支) 固定 commit；
#   2) 依赖下载：§2.2 全表 + cglm；SHA1/SHA256 校验，失败即退出；SDL2 仅取头文件；
#      `libSDL2x.so` 必须与 `engine/xsystem35/BUILD.md` 记录的入库产物 SHA-256 一致；
#   3) staging 构建：ogg / vorbis / freetype / libjpeg-turbo / libwebp / libpng / libdeflate /
#      libsndfile（CMake）+ libffi（autotools），全部装入同一 staging 前缀；
#   4) xsystem4 配置 + 构建（顶层 -S = fork 检出；自研 find 模块在 android-host/cmake）；
#   5) 门禁：构建树只允许 libxsystem4.so 一个 .so；strip；NEEDED 集合；SDL_main；LOAD p_align；
#   6) 入库：libxsystem4.so → `--out`，shaders → `--shaders-out`，许可 → `--licenses-out`。
#
# 用法
#   scripts/build-xsys4.sh [选项]
#
#   --build-dir <dir>      构建根目录（源码/依赖/产物都在此，默认 ${TMPDIR:-/tmp}/xsys4-build）
#   --out <dir>            libxsystem4.so 输出目录（默认 <repo>/engine/src/main/jniLibs/arm64-v8a）
#   --shaders-out <dir>    着色器输出目录（默认 <repo>/engine/src/main/assets/shaders）
#   --licenses-out <dir>   许可文本输出目录（默认 <repo>/engine/xsystem4/licenses）
#   --src <path>           本地 xsystem4 checkout（默认 clone fork 到 <build-dir>/src/xsystem4）
#   --ref <rev>            构建的 xsystem4 版本（commit / 分支 / tag，默认固定基线 FORK_COMMIT；
#                          传 HEAD 之类的分支名会随克隆当前状态变化，发布记录请写明完整 commit）
#   --sdk <path>           Android SDK 根目录（默认 $ANDROID_SDK_ROOT / $ANDROID_HOME / ~/Library/Android/sdk）
#   --cmake <path>         cmake 可执行文件（默认 <sdk>/cmake/3.31.1/bin/cmake）
#   --ndk <path>           NDK 根目录（默认 <sdk>/ndk/28.0.13004108）
#   --jobs <n>             并行构建任务数（默认交给 ninja / cmake 决定）
#   --clean                清空 <build-dir> 下的 build/、staging/、third_party/（保留 downloads/ 与 src/）
#   -h, --help             显示本帮助
#
# 示例
#   # 默认：clone fork + 下载依赖到临时目录，产物入库 jniLibs / assets
#   bash engine/xsystem4/scripts/build-xsys4.sh
#
#   # 复用本地 checkout、全部产物输出到仓库外目录（审核/复现用，不动仓库）
#   bash engine/xsystem4/scripts/build-xsys4.sh --src /path/to/xsystem4 \
#       --build-dir "$TMPDIR/xsys4-verify" --out "$TMPDIR/xsys4-verify/so" \
#       --shaders-out "$TMPDIR/xsys4-verify/shaders" --licenses-out "$TMPDIR/xsys4-verify/licenses"
#
#   注意：--shaders-out / --licenses-out 会被 rm -rf 重建，只允许位于仓库根或 --build-dir 之下（否则拒绝）。
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
XSYS4_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_ROOT="$(cd "${XSYS4_DIR}/../.." && pwd)"
HOST_CMAKE_DIR="${XSYS4_DIR}/android-host/cmake"

# ---- 固定基线（与 BUILD.md §1 一致） ---------------------------------------
FORK_URL="https://github.com/Weiss-UltimateSavior/xsystem4"
FORK_COMMIT="46f7c253d63d7b513516f42af027419314cbf047"
LIBSYS4_URL="https://github.com/Weiss-UltimateSavior/libsys4"
LIBSYS4_COMMIT="e2bf438d0156a2ca70fc6018fddf7c870794a2ca"
LIBSNDFILE_URL="https://github.com/Weiss-UltimateSavior/libsndfile"
LIBSNDFILE_BRANCH="xsystem4"
LIBSNDFILE_COMMIT="be82f4be73c77f1e759bda9dfe1625204441465a"
# 与 System35 共用的 libSDL2x.so 入库产物（engine/xsystem35/BUILD.md:251）
SDL2X_SO_SHA256="cee53fbc5fd41a895658ce98401cd6aa033ad6039b426d4401f9ac1b3caba0c1"
# 计划内 NEEDED 集合（方案 §5.4-6）
EXPECTED_NEEDED="libGLESv3.so libSDL2x.so libc.so libdl.so liblog.so libm.so libz.so"
EXPECTED_SHADER_COUNT=53

usage() {
    awk 'NR == 1 { next } /^set -euo/ { exit } { sub(/^# ?/, ""); print }' "${BASH_SOURCE[0]}"
}

# 默认值
BUILD_BASE="${TMPDIR:-/tmp}"
BUILD_BASE="${BUILD_BASE%/}"               # TMPDIR 在 macOS 上常以 / 结尾
BUILD_DIR="${BUILD_BASE}/xsys4-build"
OUT_DIR="${REPO_ROOT}/engine/src/main/jniLibs/arm64-v8a"
SHADERS_OUT="${REPO_ROOT}/engine/src/main/assets/shaders"
LICENSES_OUT="${XSYS4_DIR}/licenses"
SRC_DIR=""
XS4_REF="${FORK_COMMIT}"
SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
CMAKE_BIN=""
NDK_DIR=""
JOBS=""
CLEAN=0

while [ $# -gt 0 ]; do
    case "$1" in
        --build-dir)    BUILD_DIR="${2:?--build-dir 需要参数}"; shift 2 ;;
        --out)          OUT_DIR="${2:?--out 需要参数}"; shift 2 ;;
        --shaders-out)  SHADERS_OUT="${2:?--shaders-out 需要参数}"; shift 2 ;;
        --licenses-out) LICENSES_OUT="${2:?--licenses-out 需要参数}"; shift 2 ;;
        --src)          SRC_DIR="${2:?--src 需要参数}"; shift 2 ;;
        --ref)          XS4_REF="${2:?--ref 需要参数}"; shift 2 ;;
        --sdk)          SDK_DIR="${2:?--sdk 需要参数}"; shift 2 ;;
        --cmake)        CMAKE_BIN="${2:?--cmake 需要参数}"; shift 2 ;;
        --ndk)          NDK_DIR="${2:?--ndk 需要参数}"; shift 2 ;;
        --jobs)         JOBS="${2:?--jobs 需要参数}"; shift 2 ;;
        --clean)        CLEAN=1; shift ;;
        -h|--help)      usage; exit 0 ;;
        *)
            echo "error: 未知参数：$1" >&2
            usage >&2
            exit 2
            ;;
    esac
done

# ---- 工具函数 ---------------------------------------------------------------

log() { echo "==> $*"; }

die() {
    echo "error: $*" >&2
    exit 1
}

sha1_of() {
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 1 "$1" | awk '{print $1}'
    else
        sha1sum "$1" | awk '{print $1}'
    fi
}

sha1_of_stdin() {
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 1 | awk '{print $1}'
    else
        sha1sum | awk '{print $1}'
    fi
}

sha256_of() {
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{print $1}'
    else
        sha256sum "$1" | awk '{print $1}'
    fi
}

hash_of() {
    case "$1" in
        sha1)   sha1_of "$2" ;;
        sha256) sha256_of "$2" ;;
        *) die "未知哈希算法：$1" ;;
    esac
}

# ---- 工具链 -----------------------------------------------------------------

[ -n "${CMAKE_BIN}" ] || CMAKE_BIN="${SDK_DIR}/cmake/3.31.1/bin/cmake"
[ -n "${NDK_DIR}" ]   || NDK_DIR="${SDK_DIR}/ndk/28.0.13004108"

[ -x "${CMAKE_BIN}" ] || die "找不到 cmake：${CMAKE_BIN}（用 --cmake 或 --sdk 指定）"
[ -f "${NDK_DIR}/build/cmake/android.toolchain.cmake" ] || die "无效的 NDK：${NDK_DIR}（用 --ndk 指定，需 r28）"

# SDK cmake 目录自带 ninja：优先用它（BUILD.md 同一约定，不用 homebrew cmake 4.x）。
CMAKE_BIN_DIR="$(dirname "${CMAKE_BIN}")"
if [ -x "${CMAKE_BIN_DIR}/ninja" ]; then
    export PATH="${CMAKE_BIN_DIR}:${PATH}"
fi
command -v ninja >/dev/null 2>&1 || die "找不到 ninja（SDK cmake 目录应自带，或用 PATH 提供一个）"

TOOLCHAIN_BIN="$(ls -d "${NDK_DIR}"/toolchains/llvm/prebuilt/*/bin 2>/dev/null | head -n 1)"
[ -n "${TOOLCHAIN_BIN}" ] || die "找不到 NDK llvm 工具链目录：${NDK_DIR}/toolchains/llvm/prebuilt/*/bin"
LLVM_STRIP="${TOOLCHAIN_BIN}/llvm-strip"
LLVM_READELF="${TOOLCHAIN_BIN}/llvm-readelf"
LLVM_NM="${TOOLCHAIN_BIN}/llvm-nm"
[ -x "${LLVM_STRIP}" ] || die "找不到 llvm-strip：${LLVM_STRIP}"
[ -x "${LLVM_READELF}" ] || die "找不到 llvm-readelf：${LLVM_READELF}"
[ -x "${LLVM_NM}" ] || die "找不到 llvm-nm：${LLVM_NM}"
CLANG_BIN="${TOOLCHAIN_BIN}/clang"
[ -x "${CLANG_BIN}" ] || die "找不到 clang：${CLANG_BIN}"

# bison ≥ 3：系统 /usr/bin/bison 为 2.3，无法解析 libsys4 的 ini_parser.y（方案 G10）。
find_bison() {
    local cand major
    local brew_prefix=""
    if command -v brew >/dev/null 2>&1; then
        brew_prefix="$(brew --prefix bison 2>/dev/null || true)"
    fi
    for cand in "${brew_prefix:+${brew_prefix}/bin/bison}" \
                /opt/homebrew/opt/bison/bin/bison \
                /usr/local/opt/bison/bin/bison \
                "$(command -v bison 2>/dev/null || true)"; do
        [ -n "${cand}" ] && [ -x "${cand}" ] || continue
        major="$("${cand}" --version 2>/dev/null | head -n 1 | sed -n 's/.* \([0-9][0-9]*\)\..*/\1/p')"
        if [ -n "${major}" ] && [ "${major}" -ge 3 ]; then
            echo "${cand}"
            return 0
        fi
    done
    return 1
}
BISON_BIN="$(find_bison || true)"
[ -n "${BISON_BIN}" ] || die "找不到 bison ≥ 3（系统 bison 2.3 不可用）。请运行：brew install bison flex"

FLEX_BIN=""
if command -v brew >/dev/null 2>&1; then
    flex_prefix="$(brew --prefix flex 2>/dev/null || true)"
    [ -n "${flex_prefix}" ] && [ -x "${flex_prefix}/bin/flex" ] && FLEX_BIN="${flex_prefix}/bin/flex"
fi
[ -n "${FLEX_BIN}" ] || FLEX_BIN="$(command -v flex 2>/dev/null || true)"
[ -n "${FLEX_BIN}" ] || die "找不到 flex。请运行：brew install bison flex"

for tool in curl tar git awk sed; do
    command -v "${tool}" >/dev/null 2>&1 || die "缺少工具：${tool}"
done

# SDL2x 入库产物校验（构建前；不重新构建、不改动该 .so）
SDL2X_SO="${REPO_ROOT}/engine/src/main/jniLibs/arm64-v8a/libSDL2x.so"
[ -f "${SDL2X_SO}" ] || die "缺少 ${SDL2X_SO}（System35 入库产物，见 engine/xsystem35/BUILD.md）"
[ "$(sha256_of "${SDL2X_SO}")" = "${SDL2X_SO_SHA256}" ] \
    || die "libSDL2x.so SHA-256 与 engine/xsystem35/BUILD.md 记录值不一致（$(sha256_of "${SDL2X_SO}")）"

mkdir -p "${BUILD_DIR}"
BUILD_DIR="$(cd "${BUILD_DIR}" && pwd)"

# 解析路径（目标可尚未存在）：先解析最近的已存在祖先，再拼接剩余段
resolve_path() {
    local p="$1" rest="" base
    while [ ! -e "${p}" ] && [ "${p}" != "/" ]; do
        rest="/$(basename "${p}")${rest}"
        p="$(dirname "${p}")"
    done
    base="$(cd "${p}" && pwd -P)"
    printf '%s%s\n' "${base%/}" "${rest}"
}

# rm -rf 目标防护：非空绝对路径、无 . / .. 段、解析后不是 / 或 $HOME，且严格位于仓库根或 --build-dir 之下
assert_rm_target() {
    local label="$1" raw="$2" resolved home_resolved repo_resolved build_resolved
    [ -n "${raw}" ] || die "${label} 不能为空"
    case "${raw}" in
        /*) ;;
        *) die "${label} 必须是绝对路径：${raw}" ;;
    esac
    case "${raw}/" in
        */../*|*/./*) die "${label} 不得包含 . 或 .. 段：${raw}" ;;
    esac
    resolved="$(resolve_path "${raw}")"
    home_resolved="$(resolve_path "${HOME}")"
    [ "${resolved}" != "/" ] && [ "${resolved}" != "${home_resolved}" ] \
        || die "${label} 不得为 / 或 \$HOME：${raw}"
    repo_resolved="$(resolve_path "${REPO_ROOT}")"
    build_resolved="$(resolve_path "${BUILD_DIR}")"
    case "${resolved}" in
        "${repo_resolved}"/?*|"${build_resolved}"/?*) ;;
        *) die "${label} 必须位于仓库根或 --build-dir 之下（${repo_resolved} / ${build_resolved}）：${raw}" ;;
    esac
}

[ "$(resolve_path "${BUILD_DIR}")" != "/" ] && [ "$(resolve_path "${BUILD_DIR}")" != "$(resolve_path "${HOME}")" ] \
    || die "--build-dir 不得为 / 或 \$HOME：${BUILD_DIR}"
assert_rm_target "--shaders-out" "${SHADERS_OUT}"
assert_rm_target "--licenses-out" "${LICENSES_OUT}"

DOWNLOAD_DIR="${BUILD_DIR}/downloads"
THIRD_PARTY_DIR="${BUILD_DIR}/third_party"
SRC_ROOT="${BUILD_DIR}/src"
BUILD_TREE="${BUILD_DIR}/build"
STAGING="${BUILD_DIR}/staging"
STAMP_DIR="${STAGING}/.stamps"

if [ "${CLEAN}" -eq 1 ]; then
    log "清空构建树、staging 与依赖解压目录（保留 downloads/ 与 src/）"
    rm -rf "${BUILD_TREE}" "${STAGING}" "${THIRD_PARTY_DIR}"
fi
mkdir -p "${DOWNLOAD_DIR}" "${THIRD_PARTY_DIR}" "${SRC_ROOT}" "${BUILD_TREE}" "${STAMP_DIR}"

log "构建根目录：${BUILD_DIR}"
log "输出目录：${OUT_DIR}"
log "shaders 输出：${SHADERS_OUT}"
log "工具链：cmake=${CMAKE_BIN} ndk=${NDK_DIR}"
log "bison=${BISON_BIN} flex=${FLEX_BIN}"

# ---- 1) 源码 ----------------------------------------------------------------

XS4_SRC="${SRC_ROOT}/xsystem4"
if [ -n "${SRC_DIR}" ]; then
    SRC_DIR="$(cd "${SRC_DIR}" && pwd)" || die "找不到 --src 目录"
    [ "$(resolve_path "${SRC_DIR}")" != "$(resolve_path "${XS4_SRC}")" ] \
        || die "--src 不能指向构建目录自身的克隆（${XS4_SRC}），请指向独立的 xsystem4 checkout"
    log "使用本地源码（只读复用，克隆到构建目录）：${SRC_DIR}"
    if [ ! -d "${XS4_SRC}/.git" ]; then
        rm -rf "${XS4_SRC}"
        git clone -q --no-hardlinks "${SRC_DIR}" "${XS4_SRC}"
    else
        existing_origin="$(git -C "${XS4_SRC}" remote get-url origin 2>/dev/null || echo none)"
        [ "${existing_origin}" = "${SRC_DIR}" ] \
            || die "已有克隆 ${XS4_SRC} 的 origin 为 ${existing_origin}，与 --src ${SRC_DIR} 不一致（脚本不会静默沿用旧克隆）。请改用其它 --build-dir，或删除 ${XS4_SRC} 后重跑"
    fi
else
    if [ ! -d "${XS4_SRC}/.git" ]; then
        log "clone fork：${FORK_URL}"
        rm -rf "${XS4_SRC}"
        git clone -q "${FORK_URL}" "${XS4_SRC}"
    fi
fi
# 解析目标 commit：--ref 可给短哈希 / 分支 / tag，在克隆内解析成完整 40 位；
# 克隆里没有的对象先 fetch（默认 clone 的 origin 是 fork）。
if ! XS4_TARGET="$(git -C "${XS4_SRC}" rev-parse --verify --quiet "${XS4_REF}^{commit}")"; then
    log "fetch origin（解析 --ref ${XS4_REF}）"
    git -C "${XS4_SRC}" fetch -q origin || die "git fetch 失败，无法解析 --ref ${XS4_REF}"
    XS4_TARGET="$(git -C "${XS4_SRC}" rev-parse --verify --quiet "${XS4_REF}^{commit}")" \
        || die "无法解析 --ref：${XS4_REF}"
fi
if [ "$(git -C "${XS4_SRC}" rev-parse HEAD 2>/dev/null || echo none)" != "${XS4_TARGET}" ]; then
    git -C "${XS4_SRC}" checkout -q --detach "${XS4_TARGET}"
fi
[ "$(git -C "${XS4_SRC}" rev-parse HEAD)" = "${XS4_TARGET}" ] || die "xsystem4 checkout 不在目标 commit ${XS4_TARGET}"
log "xsystem4 目标 commit：${XS4_TARGET}（--ref ${XS4_REF}）"

# libsys4 子模块：不依赖 git submodule update，不改 .gitmodules（方案 §5.4-0）
LIBSYS4_DIR="${XS4_SRC}/subprojects/libsys4"
if [ ! -d "${LIBSYS4_DIR}/.git" ]; then
    rm -rf "${LIBSYS4_DIR}"
    log "clone libsys4：${LIBSYS4_URL}"
    git clone -q "${LIBSYS4_URL}" "${LIBSYS4_DIR}"
fi
if [ "$(git -C "${LIBSYS4_DIR}" rev-parse HEAD 2>/dev/null || echo none)" != "${LIBSYS4_COMMIT}" ]; then
    git -C "${LIBSYS4_DIR}" cat-file -e "${LIBSYS4_COMMIT}^{commit}" 2>/dev/null \
        || git -C "${LIBSYS4_DIR}" fetch -q origin
    git -C "${LIBSYS4_DIR}" checkout -q --detach "${LIBSYS4_COMMIT}"
fi
[ "$(git -C "${LIBSYS4_DIR}" rev-parse HEAD)" = "${LIBSYS4_COMMIT}" ] || die "libsys4 不在固定 commit ${LIBSYS4_COMMIT}"
log "libsys4 固定 commit：${LIBSYS4_COMMIT}"

# libsndfile：必须取 xsystem4 分支（固定 commit 不在默认分支）
LIBSNDFILE_SRC="${SRC_ROOT}/libsndfile"
if [ ! -d "${LIBSNDFILE_SRC}/.git" ]; then
    rm -rf "${LIBSNDFILE_SRC}"
    log "clone libsndfile（分支 ${LIBSNDFILE_BRANCH}）：${LIBSNDFILE_URL}"
    git clone -q --branch "${LIBSNDFILE_BRANCH}" "${LIBSNDFILE_URL}" "${LIBSNDFILE_SRC}"
fi
if [ "$(git -C "${LIBSNDFILE_SRC}" rev-parse HEAD 2>/dev/null || echo none)" != "${LIBSNDFILE_COMMIT}" ]; then
    git -C "${LIBSNDFILE_SRC}" cat-file -e "${LIBSNDFILE_COMMIT}^{commit}" 2>/dev/null \
        || git -C "${LIBSNDFILE_SRC}" fetch -q origin
    git -C "${LIBSNDFILE_SRC}" checkout -q --detach "${LIBSNDFILE_COMMIT}"
fi
[ "$(git -C "${LIBSNDFILE_SRC}" rev-parse HEAD)" = "${LIBSNDFILE_COMMIT}" ] || die "libsndfile 不在固定 commit ${LIBSNDFILE_COMMIT}"
log "libsndfile 固定 commit：${LIBSNDFILE_COMMIT}（${LIBSNDFILE_BRANCH}）"

# ---- 2) 依赖下载与解压（SHA 校验，失败即退出） -------------------------------

# fetch_dep <tarball> <sha1|sha256> <expected> <解压目录名> <url>...
fetch_dep() {
    local tarball="$1" algo="$2" want="$3" dir="$4"
    shift 4
    local tar_path="${DOWNLOAD_DIR}/${tarball}"
    local legacy="${BUILD_BASE}/xsys35-build/downloads/${tarball}"
    local have url ok=0

    if [ ! -f "${tar_path}" ] && [ -f "${legacy}" ] && [ "$(hash_of "${algo}" "${legacy}")" = "${want}" ]; then
        log "复用 xsystem35 下载缓存：${tarball}"
        cp "${legacy}" "${tar_path}"
    fi

    if [ -f "${tar_path}" ]; then
        have="$(hash_of "${algo}" "${tar_path}")"
        [ "${have}" = "${want}" ] \
            || die "${tarball} 校验失败（已存在，${algo}=${have}，期望 ${want}）；请删除后重跑：rm '${tar_path}'"
        log "复用已下载依赖：${tarball}（${algo} 校验通过）"
    else
        for url in "$@"; do
            log "下载 ${tarball}：${url}"
            if curl -fL -sS --retry 3 --connect-timeout 30 -o "${tar_path}.part" "${url}"; then
                have="$(hash_of "${algo}" "${tar_path}.part")"
                if [ "${have}" = "${want}" ]; then
                    mv "${tar_path}.part" "${tar_path}"
                    ok=1
                    break
                fi
                echo "warning: ${url} 校验失败：${algo}=${have}，期望 ${want}" >&2
            fi
            rm -f "${tar_path}.part"
        done
        [ "${ok}" -eq 1 ] || die "${tarball} 下载或校验失败（期望 ${algo}=${want}）"
        log "${tarball} ${algo} 校验通过"
    fi

    if [ ! -d "${THIRD_PARTY_DIR}/${dir}" ]; then
        log "解压 ${tarball} -> third_party/${dir}/"
        tar -xf "${tar_path}" -C "${THIRD_PARTY_DIR}"
        [ -d "${THIRD_PARTY_DIR}/${dir}" ] || die "解压后未找到目录：${THIRD_PARTY_DIR}/${dir}"
    fi
}

fetch_dep libffi-3.4.6.tar.gz sha1 19251dfee520dff42acefe36bfe76d7168071e01 libffi-3.4.6 \
    "https://github.com/libffi/libffi/releases/download/v3.4.6/libffi-3.4.6.tar.gz"
fetch_dep freetype-2.14.3.tar.gz sha1 a911f65c6355ddcf7aaba46e250dbc00f67e1678 freetype-2.14.3 \
    "https://downloads.sourceforge.net/project/freetype/freetype2/2.14.3/freetype-2.14.3.tar.gz" \
    "https://download.savannah.gnu.org/releases/freetype/freetype-2.14.3.tar.gz"
fetch_dep libogg-1.3.5.tar.xz sha1 5a368421a636f7faa4c2f662857cb507dffd7c99 libogg-1.3.5 \
    "https://downloads.xiph.org/releases/ogg/libogg-1.3.5.tar.xz"
fetch_dep libvorbis-1.3.7.tar.xz sha1 0a2dd71a999656b8091506839e8007a61a8fda1f libvorbis-1.3.7 \
    "https://downloads.xiph.org/releases/vorbis/libvorbis-1.3.7.tar.xz"
fetch_dep libjpeg-turbo-3.1.0.tar.gz sha1 ba3c0152f14a504018de19c9f62250d8f3351525 libjpeg-turbo-3.1.0 \
    "https://github.com/libjpeg-turbo/libjpeg-turbo/releases/download/3.1.0/libjpeg-turbo-3.1.0.tar.gz"
fetch_dep libwebp-1.3.2.tar.gz sha1 f941661a869df7347d7ca9208acb4e6d41f614de libwebp-1.3.2 \
    "https://storage.googleapis.com/downloads.webmproject.org/releases/webp/libwebp-1.3.2.tar.gz"
fetch_dep libpng-1.6.47.tar.xz sha256 b213cb381fbb1175327bd708a77aab708a05adde7b471bc267bd15ac99893631 libpng-1.6.47 \
    "https://downloads.sourceforge.net/project/libpng/libpng16/1.6.47/libpng-1.6.47.tar.xz" \
    "https://download.sourceforge.net/libpng/libpng-1.6.47.tar.xz"
fetch_dep libdeflate-1.25.tar.gz sha1 7e70557d094b54c3ee16f6c012976dc8d685b22a libdeflate-1.25 \
    "https://github.com/ebiggers/libdeflate/releases/download/v1.25/libdeflate-1.25.tar.gz"
fetch_dep cglm-0.9.2.tar.gz sha1 cb8472aa8c2ab67b66378dbaf10c2c7368d4e4c3 cglm-0.9.2 \
    "https://github.com/recp/cglm/archive/refs/tags/v0.9.2.tar.gz"
fetch_dep SDL2-2.32.10.tar.gz sha1 ce98fa93e31836a751feca374ab28a0770b63c16 SDL2-2.32.10 \
    "https://github.com/libsdl-org/SDL/releases/download/release-2.32.10/SDL2-2.32.10.tar.gz"

SDL2_INC_DIR="${THIRD_PARTY_DIR}/SDL2-2.32.10/include"
CGLM_SRC="${THIRD_PARTY_DIR}/cglm-0.9.2"
[ -f "${SDL2_INC_DIR}/SDL.h" ] || die "SDL2 头文件缺失：${SDL2_INC_DIR}"

# ---- 3) staging 构建 --------------------------------------------------------

# 公共 CMake 参数（方案 §5.4-2）。CMAKE_FIND_ROOT_PATH 限定依赖只从 staging 与 NDK sysroot 解析。
COMMON_CMAKE_ARGS=(
    -G Ninja
    -DCMAKE_TOOLCHAIN_FILE="${NDK_DIR}/build/cmake/android.toolchain.cmake"
    -DANDROID_ABI=arm64-v8a
    -DANDROID_PLATFORM=android-26
    -DANDROID_STL=c++_static
    -DCMAKE_BUILD_TYPE=Release
    -DCMAKE_INTERPROCEDURAL_OPTIMIZATION=ON
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON
    -DCMAKE_C_FLAGS="-ffile-prefix-map=${BUILD_DIR}=/xsys4-build"
    -DCMAKE_INSTALL_PREFIX="${STAGING}"
    -DCMAKE_STAGING_PREFIX="${STAGING}"
    -DCMAKE_PREFIX_PATH="${STAGING}"
    -DCMAKE_FIND_ROOT_PATH="${STAGING}"
    -DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=ONLY
    -DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=ONLY
    -DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=ONLY
)

# stamp：依赖的「输入指纹」（tar SHA + 全部参数 + 工具链版本）。指纹一致且已安装即跳过。
stamp_key() {
    { printf '%s\n' "$@"; printf '%s\n' "${COMMON_CMAKE_ARGS[@]}"; printf 'cmake=%s\n' "$("${CMAKE_BIN}" --version | head -n 1)"; } \
        | sha1_of_stdin
}
stamp_fresh() {
    [ -f "${STAMP_DIR}/$1" ] && [ "$(cat "${STAMP_DIR}/$1")" = "$2" ]
}
stamp_set() {
    printf '%s\n' "$2" > "${STAMP_DIR}/$1"
}

# build_cmake_dep <name> <src-dir> <seed(tar SHA)> [cmake 额外参数...]
build_cmake_dep() {
    local name="$1" src="$2" seed="$3"
    shift 3
    local key bdir
    key="$(stamp_key "${seed}" "$@")"
    if stamp_fresh "${name}" "${key}"; then
        log "跳过（已构建）：${name}"
        return 0
    fi
    bdir="${BUILD_TREE}/${name}"
    rm -rf "${bdir}"
    log "构建依赖：${name}"
    "${CMAKE_BIN}" -S "${src}" -B "${bdir}" "${COMMON_CMAKE_ARGS[@]}" "$@"
    if [ -n "${JOBS}" ]; then
        "${CMAKE_BIN}" --build "${bdir}" -j "${JOBS}"
    else
        "${CMAKE_BIN}" --build "${bdir}" -j
    fi
    "${CMAKE_BIN}" --install "${bdir}" >/dev/null
    stamp_set "${name}" "${key}"
}

# libffi：autotools 交叉编译（方案 §5.4-3；release 包自带 configure，无需 autoreconf）
build_libffi() {
    local name="libffi" src="${THIRD_PARTY_DIR}/libffi-3.4.6" key
    key="$(stamp_key "libffi-3.4.6.tar.gz=19251dfee520dff42acefe36bfe76d7168071e01" "autotools")"
    if stamp_fresh "${name}" "${key}"; then
        log "跳过（已构建）：${name}"
        return 0
    fi
    log "构建依赖：libffi（autotools 交叉编译）"
    (
        cd "${src}"
        make distclean >/dev/null 2>&1 || true
        CC="${CLANG_BIN} --target=aarch64-linux-android26" \
        AS="${CLANG_BIN} --target=aarch64-linux-android26" \
        CFLAGS="-O2 -ffile-prefix-map=${BUILD_DIR}=/xsys4-build" \
        AR="${TOOLCHAIN_BIN}/llvm-ar" \
        RANLIB="${TOOLCHAIN_BIN}/llvm-ranlib" \
        STRIP="${TOOLCHAIN_BIN}/llvm-strip" \
        LD="${TOOLCHAIN_BIN}/ld.lld" \
        ./configure --host=aarch64-linux-android26 --prefix="${STAGING}" \
            --disable-shared --disable-dependency-tracking --disable-builddir \
            --disable-multi-os-directory --disable-structs --disable-raw-api --disable-docs
        make -j "${JOBS:-$(sysctl -n hw.ncpu 2>/dev/null || echo 4)}"
        make install
        make distclean >/dev/null 2>&1 || true
    )
    stamp_set "${name}" "${key}"
}

# 依赖构建顺序：无互相依赖的先构建；libsndfile 依赖 ogg/vorbis；libpng 依赖 zlib（sysroot）。
build_libffi

build_cmake_dep ogg "${THIRD_PARTY_DIR}/libogg-1.3.5" "libogg-1.3.5.tar.xz=5a368421a636f7faa4c2f662857cb507dffd7c99" \
    -DBUILD_SHARED_LIBS=OFF \
    -DINSTALL_DOCS=OFF \
    -DINSTALL_PKG_CONFIG_MODULE=OFF

build_cmake_dep vorbis "${THIRD_PARTY_DIR}/libvorbis-1.3.7" "libvorbis-1.3.7.tar.xz=0a2dd71a999656b8091506839e8007a61a8fda1f" \
    -DBUILD_SHARED_LIBS=OFF \
    -DINSTALL_DOCS=OFF \
    -DINSTALL_PKG_CONFIG_MODULE=OFF

build_cmake_dep freetype "${THIRD_PARTY_DIR}/freetype-2.14.3" "freetype-2.14.3.tar.gz=a911f65c6355ddcf7aaba46e250dbc00f67e1678" \
    -DBUILD_SHARED_LIBS=OFF \
    -DFT_DISABLE_ZLIB=ON \
    -DFT_DISABLE_BZIP2=ON \
    -DFT_DISABLE_PNG=ON \
    -DFT_DISABLE_HARFBUZZ=ON \
    -DFT_DISABLE_BROTLI=ON

# libjpeg-turbo 的 BUILD 缓存变量默认取 string(TIMESTAMP "%Y%m%d")，会写进 jpeg_version 字符串
# （"libjpeg-turbo version 3.1.0 (build YYYYMMDD)"），使产物随构建日期变化；固定为常量以保证可复现。
build_cmake_dep jpeg "${THIRD_PARTY_DIR}/libjpeg-turbo-3.1.0" "libjpeg-turbo-3.1.0.tar.gz=ba3c0152f14a504018de19c9f62250d8f3351525" \
    -DBUILD=xsys4 \
    -DENABLE_SHARED=OFF \
    -DENABLE_STATIC=ON \
    -DWITH_TURBOJPEG=ON \
    -DWITH_TOOLS=OFF \
    -DWITH_TESTS=OFF \
    -DWITH_JAVA=OFF

build_cmake_dep webp "${THIRD_PARTY_DIR}/libwebp-1.3.2" "libwebp-1.3.2.tar.gz=f941661a869df7347d7ca9208acb4e6d41f614de" \
    -DBUILD_SHARED_LIBS=OFF \
    -DWEBP_BUILD_ANIM_UTILS=OFF \
    -DWEBP_BUILD_CWEBP=OFF \
    -DWEBP_BUILD_DWEBP=OFF \
    -DWEBP_BUILD_GIF2WEBP=OFF \
    -DWEBP_BUILD_IMG2WEBP=OFF \
    -DWEBP_BUILD_VWEBP=OFF \
    -DWEBP_BUILD_WEBPINFO=OFF \
    -DWEBP_BUILD_WEBPMUX=OFF \
    -DWEBP_BUILD_EXTRAS=OFF \
    -DWEBP_BUILD_WEBP_JS=OFF

build_cmake_dep png "${THIRD_PARTY_DIR}/libpng-1.6.47" "libpng-1.6.47.tar.xz=b213cb381fbb1175327bd708a77aab708a05adde7b471bc267bd15ac99893631" \
    -DPNG_SHARED=OFF \
    -DPNG_STATIC=ON \
    -DPNG_TESTS=OFF \
    -DPNG_TOOLS=OFF \
    -DPNG_ARM_NEON=on

build_cmake_dep deflate "${THIRD_PARTY_DIR}/libdeflate-1.25" "libdeflate-1.25.tar.gz=7e70557d094b54c3ee16f6c012976dc8d685b22a" \
    -DLIBDEFLATE_BUILD_SHARED_LIB=OFF \
    -DLIBDEFLATE_BUILD_STATIC_LIB=ON \
    -DLIBDEFLATE_GZIP_SUPPORT=OFF \
    -DLIBDEFLATE_BUILD_GZIP=OFF \
    -DLIBDEFLATE_BUILD_TESTS=OFF

build_cmake_dep sndfile "${LIBSNDFILE_SRC}" "${LIBSNDFILE_COMMIT}" \
    -DBUILD_SHARED_LIBS=OFF \
    -DBUILD_PROGRAMS=OFF \
    -DBUILD_EXAMPLES=OFF \
    -DBUILD_TESTING=OFF \
    -DENABLE_CPACK=OFF \
    -DENABLE_MPEG=OFF \
    -DENABLE_EXTERNAL_LIBS=ON

# ---- 4) xsystem4 本体 -------------------------------------------------------

XS4_BUILD="${BUILD_TREE}/xsystem4"

# 顶层 -S 直接指向 fork 检出（不写 wrapper：上游 git rev-parse 必须取到 fork 提交号）。
log "cmake 配置：xsystem4 @ ${XS4_TARGET}"
"${CMAKE_BIN}" -S "${XS4_SRC}" -B "${XS4_BUILD}" "${COMMON_CMAKE_ARGS[@]}" \
    -DCMAKE_MODULE_PATH="${HOST_CMAKE_DIR}" \
    -DFETCHCONTENT_FULLY_DISCONNECTED=ON \
    -DFETCHCONTENT_SOURCE_DIR_CGLM="${CGLM_SRC}" \
    -DCGLM_SHARED=OFF \
    -DCGLM_STATIC=ON \
    -DBISON_EXECUTABLE="${BISON_BIN}" \
    -DFLEX_EXECUTABLE="${FLEX_BIN}" \
    -DXSYS4_SDL2_INCLUDE_DIR="${SDL2_INC_DIR}" \
    -DXSYS4_SDL2_LIBRARY="${SDL2X_SO}" \
    -DCMAKE_SHARED_LINKER_FLAGS="-Wl,--build-id=none"

# 门禁：libsys4 必须命中 libdeflate（未命中会静默回落 ZLIB，方案 G10 同类风险）
# Ninja 生成器的编译定义写在 build.ninja 中（无 flags.make）。
grep -q -- "-DHAVE_LIBDEFLATE" "${XS4_BUILD}/build.ninja" \
    || die "libsys4 未命中 HAVE_LIBDEFLATE（静默回落 ZLIB）"

log "构建 xsystem4"
if [ -n "${JOBS}" ]; then
    "${CMAKE_BIN}" --build "${XS4_BUILD}" -j "${JOBS}"
else
    "${CMAKE_BIN}" --build "${XS4_BUILD}" -j
fi

# ---- 5) 门禁 ----------------------------------------------------------------

# 计划内产物只允许 libxsystem4.so；出现其它 .so（如 libcglm.so）即报错（方案 §5.4-5）。
extra_sos="$(find "${BUILD_TREE}" "${STAGING}" -name '*.so' -type f \
    ! -path '*/CMakeFiles/*' ! -name 'libxsystem4.so' 2>/dev/null || true)"
if [ -n "${extra_sos}" ]; then
    echo "error: 构建树出现计划外 .so：" >&2
    while IFS= read -r so; do
        printf '  %s\n' "${so}" >&2
    done <<< "${extra_sos}"
    die "产物集合应只有 libxsystem4.so（若为旧配置残留，请加 --clean 重建）"
fi

ARTIFACT="${XS4_BUILD}/libxsystem4.so"
[ -f "${ARTIFACT}" ] || die "未生成预期产物：${ARTIFACT}"

log "strip（llvm-strip --strip-unneeded）"
"${LLVM_STRIP}" --strip-unneeded "${ARTIFACT}"

# NEEDED 集合
needed="$("${LLVM_READELF}" -d "${ARTIFACT}" | sed -n 's/.*(NEEDED).*\[\(.*\)\].*/\1/p' | sort | tr '\n' ' ')"
expected="$(printf '%s\n' ${EXPECTED_NEEDED} | sort | tr '\n' ' ')"
[ "${needed}" = "${expected}" ] || die "NEEDED 集合不符：实际 [${needed}]，期望 [${expected}]"
case " ${needed} " in
    *" libSDL2.so "*|*" libcglm.so "*) die "NEEDED 不得包含 libSDL2.so / libcglm.so" ;;
esac

# SDL_main 导出
dynsyms="$("${LLVM_NM}" -D "${ARTIFACT}")"
sdl_main_line="$(printf '%s\n' "${dynsyms}" | awk '$NF == "SDL_main"')"
[ -n "${sdl_main_line}" ] || die "动态符号表缺少 SDL_main"
case "${sdl_main_line}" in
    *" T SDL_main") ;;
    *) die "SDL_main 不是已定义的导出符号（T）：${sdl_main_line}" ;;
esac

# 16 KB 页对齐：所有 LOAD 段 p_align = 0x4000
load_aligns="$("${LLVM_READELF}" -l "${ARTIFACT}" | awk '/LOAD/ { print $NF }' | sort -u)"
[ "${load_aligns}" = "0x4000" ] || die "LOAD p_align 不是 0x4000：${load_aligns}"

# ---- 6) 入库：so / shaders / licenses --------------------------------------

SHADER_SRC="${XS4_SRC}/shaders"
shader_count="$(find "${SHADER_SRC}" -type f | wc -l | tr -d ' ')"
[ "${shader_count}" -eq "${EXPECTED_SHADER_COUNT}" ] \
    || die "着色器数量门禁失败：${shader_count}，期望 ${EXPECTED_SHADER_COUNT}"

# 门禁全部通过后才写入 .so，避免门禁失败留下半成品
log "复制 libxsystem4.so 到 ${OUT_DIR}"
mkdir -p "${OUT_DIR}"
cp "${ARTIFACT}" "${OUT_DIR}/libxsystem4.so"

log "复制 ${shader_count} 个着色器到 ${SHADERS_OUT}（保留子目录）"
rm -rf "${SHADERS_OUT}"
mkdir -p "${SHADERS_OUT}"
cp -R "${SHADER_SRC}/." "${SHADERS_OUT}/"
copied_count="$(find "${SHADERS_OUT}" -type f | wc -l | tr -d ' ')"
[ "${copied_count}" -eq "${EXPECTED_SHADER_COUNT}" ] || die "着色器复制后数量不符：${copied_count}"

# ---- 6b) 许可文本（随二进制分发；全部取自固定源码，不手写） ---------------------

log "收集许可文本到 ${LICENSES_OUT}"
rm -rf "${LICENSES_OUT}"
mkdir -p "${LICENSES_OUT}"

# lic_copy <源文件> <目标文件名>：整文件复制
lic_copy() {
    [ -f "$1" ] || die "许可文件缺失：$1"
    cp "$1" "${LICENSES_OUT}/$2"
}

lic_copy "${XS4_SRC}/COPYING"                              "xsystem4-COPYING-GPL-2.0.txt"
lic_copy "${LIBSYS4_DIR}/COPYING"                          "libsys4-COPYING-GPL-2.0.txt"
lic_copy "${LIBSNDFILE_SRC}/COPYING"                       "libsndfile-COPYING-LGPL-2.1.txt"
lic_copy "${THIRD_PARTY_DIR}/libffi-3.4.6/LICENSE"         "libffi-LICENSE.txt"
lic_copy "${THIRD_PARTY_DIR}/libogg-1.3.5/COPYING"         "libogg-COPYING.txt"
lic_copy "${THIRD_PARTY_DIR}/libvorbis-1.3.7/COPYING"      "libvorbis-COPYING.txt"
lic_copy "${THIRD_PARTY_DIR}/libjpeg-turbo-3.1.0/LICENSE.md" "libjpeg-turbo-LICENSE.md"
lic_copy "${THIRD_PARTY_DIR}/libjpeg-turbo-3.1.0/README.ijg" "README.ijg"
lic_copy "${THIRD_PARTY_DIR}/libwebp-1.3.2/COPYING"        "libwebp-COPYING.txt"
lic_copy "${THIRD_PARTY_DIR}/libwebp-1.3.2/PATENTS"        "libwebp-PATENTS.txt"
lic_copy "${THIRD_PARTY_DIR}/libpng-1.6.47/LICENSE"        "libpng-LICENSE.txt"
lic_copy "${THIRD_PARTY_DIR}/libdeflate-1.25/COPYING"      "libdeflate-COPYING.txt"
lic_copy "${THIRD_PARTY_DIR}/freetype-2.14.3/docs/FTL.TXT" "freetype-FTL.txt"
lic_copy "${THIRD_PARTY_DIR}/freetype-2.14.3/docs/GPLv2.TXT" "freetype-GPLv2.txt"
lic_copy "${THIRD_PARTY_DIR}/SDL2-2.32.10/LICENSE.txt"     "SDL2-LICENSE.txt"
lic_copy "${CGLM_SRC}/LICENSE"                             "cglm-LICENSE.txt"

# 源码内嵌的第三方许可声明：按固定 commit 的文件头截取（无许可头的见 BUILD.md §7）
awk 'NR == 1, /\*\//' "${XS4_SRC}/src/cJSON.c"               > "${LICENSES_OUT}/cJSON-MIT.txt"
awk '/-- LICENSE/, /^SOFTWARE\./' "${XS4_SRC}/include/pl_mpeg.h" > "${LICENSES_OUT}/pl_mpeg-MIT.txt"
awk 'NR == 1, /\*\//' "${XS4_SRC}/include/queue.h"          > "${LICENSES_OUT}/queue-BSD-3-Clause.txt"
awk 'NR == 1, /\*\//' "${XS4_SRC}/src/base64.c"             > "${LICENSES_OUT}/base64-BSD.txt"
awk '/This is free and unencumbered/ { p = 1 } p { print } /unlicense\.org/ { p = 0 }' \
    "${XS4_SRC}/include/sts_mixer.h"                        > "${LICENSES_OUT}/sts_mixer-Unlicense.txt"
awk 'NR == 1, /\*\//' "${LIBSYS4_DIR}/src/kvec.h"           > "${LICENSES_OUT}/kvec-MIT.txt"
awk '/^\/\*/ || /^$/ { print; next } { exit }' "${LIBSYS4_DIR}/src/mt19937int.c" \
                                                            > "${LICENSES_OUT}/mt19937int-LGPL-notice.txt"

# mt19937int 声明引用的 LGPL-2.0 全文（GNU 官方文本，SHA-256 校验）
LGPL20_URL="https://www.gnu.org/licenses/old-licenses/lgpl-2.0.txt"
LGPL20_SHA256="cc535c21133c895b56b374c8a1dc1eb948d99003ed2b47372069456b62f42b24"
lgpl_cache="${DOWNLOAD_DIR}/lgpl-2.0.txt"
if [ ! -f "${lgpl_cache}" ] || [ "$(sha256_of "${lgpl_cache}")" != "${LGPL20_SHA256}" ]; then
    rm -f "${lgpl_cache}"
    curl -fsSL --retry 3 --connect-timeout 30 -o "${lgpl_cache}.part" "${LGPL20_URL}" \
        || die "下载 LGPL-2.0 失败：${LGPL20_URL}"
    [ "$(sha256_of "${lgpl_cache}.part")" = "${LGPL20_SHA256}" ] \
        || die "LGPL-2.0 SHA-256 校验失败（上游文本已变化？）"
    mv "${lgpl_cache}.part" "${lgpl_cache}"
fi
cp "${lgpl_cache}" "${LICENSES_OUT}/LGPL-2.0.txt"
log "许可文本：$(find "${LICENSES_OUT}" -type f | wc -l | tr -d ' ') 个文件"

echo
echo "=================== 产物摘要 ==================="
echo "-- libxsystem4.so"
"${LLVM_READELF}" -d "${OUT_DIR}/libxsystem4.so" | grep -E 'SONAME|NEEDED' | sed 's/^/   /'
echo "   SHA256 $(sha256_of "${OUT_DIR}/libxsystem4.so")  size $(wc -c < "${OUT_DIR}/libxsystem4.so" | tr -d ' ') B"
echo "-- shaders: ${copied_count} files -> ${SHADERS_OUT}"
echo
echo "完成：libxsystem4.so 已输出到 ${OUT_DIR}"
