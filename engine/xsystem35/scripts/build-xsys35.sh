#!/usr/bin/env bash
#
# build-xsys35.sh — 一键可复现构建 xsystem35（System35）Android arm64 运行库。
#
# 背景
#   TyranorNext 主仓**不再 vendored** xsystem35 源码与依赖：本脚本把源码与依赖准备到
#   **仓库外**构建目录（默认 `${TMPDIR:-/tmp}/xsys35-build`，可用 `--build-dir` 覆盖），
#   调用 `engine/xsystem35/android-host/CMakeLists.txt` 构建，再把 3 个产物复制到
#   `engine/src/main/jniLibs/arm64-v8a/`（与 librfvp.so 同模式预构建入库）。
#
#   完整背景、产物校验与许可说明见 `engine/xsystem35/BUILD.md`。
#
# 步骤（幂等可重跑）
#   1) 依赖下载：SDL2 2.32.10 / freetype 2.14.3 / SDL2_mixer 2.8.0 / libwebp 1.5.0，
#      下载到 `<build-dir>/downloads/`，逐个 SHA1 校验（失败即退出）；
#      已存在且校验通过的 tar 包直接复用，已解压的目录跳过解压；
#   2) 打补丁：把 `engine/xsystem35/patches/*.patch` 顺序应用到
#      `<build-dir>/third_party/SDL2`；补丁只对「从缓存 tar 包重新解压的干净源」重放
#      （git apply --check 校验，无 git 时 patch --dry-run），应用结果记入 marker；
#      重复运行且 marker/内容复核通过时跳过；
#   3) 源码：`--src <path>` 提供本地 xsystem35 checkout；缺省则 clone fork
#      https://github.com/Weiss-UltimateSavior/xsystem35-sdl2 到
#      `<build-dir>/xsystem35-src` 并 checkout 固定 commit（已存在则 fetch + checkout）；
#   4) cmake 配置：SDK CMake 3.31.1 + NDK r28（默认 ~/Library/Android/sdk 推导，
#      可用 `--sdk` / `--cmake` / `--ndk` 覆盖），arm64-v8a / android-26 / c++_static / Release；
#   5) 构建 + `llvm-strip --strip-unneeded` + 复制 3 个产物到 `--out`
#      （默认 `engine/src/main/jniLibs/arm64-v8a`），并打印 SONAME/NEEDED 与 SHA256。
#
# 用法
#   scripts/build-xsys35.sh [选项]
#
#   --build-dir <dir>   构建根目录（源码/依赖/产物都在此，默认 ${TMPDIR:-/tmp}/xsys35-build）
#   --out <dir>         3 个 .so 的输出目录（默认 <repo>/engine/src/main/jniLibs/arm64-v8a）
#   --src <path>        本地 xsystem35 源码 checkout（默认 clone fork 到 <build-dir>/xsystem35-src）
#   --sdk <path>        Android SDK 根目录（默认 $ANDROID_SDK_ROOT / $ANDROID_HOME / ~/Library/Android/sdk）
#   --cmake <path>      cmake 可执行文件（默认 <sdk>/cmake/3.31.1/bin/cmake）
#   --ndk <path>        NDK 根目录（默认 <sdk>/ndk/28.0.13004108）
#   --jobs <n>          并行构建任务数（默认交给 ninja 决定）
#   --clean             构建前清空 <build-dir>/build（源码/依赖缓存保留）
#   -h, --help          显示本帮助
#
# 示例
#   # 默认（clone fork + 下载依赖到临时目录，产物入库 jniLibs）
#   bash engine/xsystem35/scripts/build-xsys35.sh
#
#   # 复用本地 checkout、只构建到仓库外目录（审核/复现用，不动 jniLibs）
#   bash engine/xsystem35/scripts/build-xsys35.sh \
#       --src /path/to/xsystem35-sdl2 --out "$TMPDIR/xsys35-verify"
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
XSYS35_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_ROOT="$(cd "${XSYS35_DIR}/../.." && pwd)"
PATCH_DIR="${XSYS35_DIR}/patches"
HOST_CMAKE_DIR="${XSYS35_DIR}/android-host"

FORK_URL="https://github.com/Weiss-UltimateSavior/xsystem35-sdl2"
FORK_COMMIT="18af9be92dbe811b783c81393ca2307ccc50efd5"

# 依赖清单：<名> <tar 包名> <URL> <SHA1>（与 BUILD.md「依赖」表一致）
DEP_NAMES=(SDL2 freetype SDL2_mixer libwebp)
DEP_TARBALLS=(
    SDL2-2.32.10.tar.gz
    freetype-2.14.3.tar.gz
    SDL2_mixer-2.8.0.tar.gz
    libwebp-1.5.0.tar.gz
)
DEP_DIRS=(
    SDL2-2.32.10
    freetype-2.14.3
    SDL2_mixer-2.8.0
    libwebp-1.5.0
)
DEP_URLS=(
    "https://github.com/libsdl-org/SDL/releases/download/release-2.32.10/SDL2-2.32.10.tar.gz"
    "https://downloads.sourceforge.net/project/freetype/freetype2/2.14.3/freetype-2.14.3.tar.gz"
    "https://github.com/libsdl-org/SDL_mixer/releases/download/release-2.8.0/SDL2_mixer-2.8.0.tar.gz"
    "https://storage.googleapis.com/downloads.webmproject.org/releases/webp/libwebp-1.5.0.tar.gz"
)
DEP_SHA1=(
    ce98fa93e31836a751feca374ab28a0770b63c16
    a911f65c6355ddcf7aaba46e250dbc00f67e1678
    a58c69f9d00e44833b9e00e1adb58d85759ca499
    b21aa842136dc59a72a38776a5aa73f4d0b00ac5
)

usage() {
    sed -n '2,48p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

# 默认值
BUILD_BASE="${TMPDIR:-/tmp}"
BUILD_BASE="${BUILD_BASE%/}"               # TMPDIR 在 macOS 上常以 / 结尾
BUILD_DIR="${BUILD_BASE}/xsys35-build"
OUT_DIR="${REPO_ROOT}/engine/src/main/jniLibs/arm64-v8a"
SRC_DIR=""
SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
CMAKE_BIN=""
NDK_DIR=""
JOBS=""
CLEAN=0

while [ $# -gt 0 ]; do
    case "$1" in
        --build-dir) BUILD_DIR="${2:?--build-dir 需要参数}"; shift 2 ;;
        --out)       OUT_DIR="${2:?--out 需要参数}"; shift 2 ;;
        --src)       SRC_DIR="${2:?--src 需要参数}"; shift 2 ;;
        --sdk)       SDK_DIR="${2:?--sdk 需要参数}"; shift 2 ;;
        --cmake)     CMAKE_BIN="${2:?--cmake 需要参数}"; shift 2 ;;
        --ndk)       NDK_DIR="${2:?--ndk 需要参数}"; shift 2 ;;
        --jobs)      JOBS="${2:?--jobs 需要参数}"; shift 2 ;;
        --clean)     CLEAN=1; shift ;;
        -h|--help)   usage; exit 0 ;;
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

# ---- 工具链 -----------------------------------------------------------------

[ -n "${CMAKE_BIN}" ] || CMAKE_BIN="${SDK_DIR}/cmake/3.31.1/bin/cmake"
[ -n "${NDK_DIR}" ]   || NDK_DIR="${SDK_DIR}/ndk/28.0.13004108"

[ -x "${CMAKE_BIN}" ] || die "找不到 cmake：${CMAKE_BIN}（用 --cmake 或 --sdk 指定）"
[ -f "${NDK_DIR}/build/cmake/android.toolchain.cmake" ] || die "无效的 NDK：${NDK_DIR}（用 --ndk 指定，需 r28+）"

# SDK cmake 目录自带 ninja：优先用它（BUILD.md §5.1 的同一约定）。
CMAKE_BIN_DIR="$(dirname "${CMAKE_BIN}")"
if [ -x "${CMAKE_BIN_DIR}/ninja" ]; then
    export PATH="${CMAKE_BIN_DIR}:${PATH}"
fi
command -v ninja >/dev/null 2>&1 || die "找不到 ninja（SDK cmake 目录应自带，或用 PATH 提供一个）"

TOOLCHAIN_BIN="$(ls -d "${NDK_DIR}"/toolchains/llvm/prebuilt/*/bin 2>/dev/null | head -n 1)"
[ -n "${TOOLCHAIN_BIN}" ] || die "找不到 NDK llvm 工具链目录：${NDK_DIR}/toolchains/llvm/prebuilt/*/bin"
LLVM_STRIP="${TOOLCHAIN_BIN}/llvm-strip"
LLVM_READELF="${TOOLCHAIN_BIN}/llvm-readelf"
[ -x "${LLVM_STRIP}" ] || die "找不到 llvm-strip：${LLVM_STRIP}"

for tool in curl tar; do
    command -v "${tool}" >/dev/null 2>&1 || die "缺少工具：${tool}"
done
if ! command -v git >/dev/null 2>&1 && ! command -v patch >/dev/null 2>&1; then
    die "缺少 git 与 patch（patches 应用需要其一）"
fi

mkdir -p "${BUILD_DIR}"
BUILD_DIR="$(cd "${BUILD_DIR}" && pwd)"

log "构建根目录：${BUILD_DIR}"
log "输出目录：${OUT_DIR}"
log "工具链：cmake=${CMAKE_BIN} ndk=${NDK_DIR}"

# ---- 1) 依赖下载与解压 ------------------------------------------------------

THIRD_PARTY_DIR="${BUILD_DIR}/third_party"
DOWNLOAD_DIR="${BUILD_DIR}/downloads"
mkdir -p "${THIRD_PARTY_DIR}" "${DOWNLOAD_DIR}"

for i in "${!DEP_NAMES[@]}"; do
    name="${DEP_NAMES[$i]}"
    tarball="${DEP_TARBALLS[$i]}"
    url="${DEP_URLS[$i]}"
    want_sha1="${DEP_SHA1[$i]}"
    extracted="${DEP_DIRS[$i]}"

    tar_path="${DOWNLOAD_DIR}/${tarball}"
    dest="${THIRD_PARTY_DIR}/${name}"

    if [ -f "${tar_path}" ]; then
        have_sha1="$(sha1_of "${tar_path}")"
        [ "${have_sha1}" = "${want_sha1}" ] \
            || die "${tarball} 校验失败（已存在，SHA1=${have_sha1}，期望 ${want_sha1}）；请删除后重跑：rm '${tar_path}'"
        log "复用已下载依赖：${tarball}（SHA1 校验通过）"
    else
        log "下载 ${tarball}"
        curl -fL --retry 3 --connect-timeout 30 -o "${tar_path}.part" "${url}"
        have_sha1="$(sha1_of "${tar_path}.part")"
        if [ "${have_sha1}" != "${want_sha1}" ]; then
            rm -f "${tar_path}.part"
            die "${tarball} SHA1 校验失败：下载得到 ${have_sha1}，期望 ${want_sha1}"
        fi
        mv "${tar_path}.part" "${tar_path}"
        log "${tarball} SHA1 校验通过"
    fi

    if [ -d "${dest}" ]; then
        log "复用已解压依赖：${name}/"
    else
        log "解压 ${tarball} -> ${name}/"
        rm -rf "${THIRD_PARTY_DIR:?}/${extracted}"
        tar -xzf "${tar_path}" -C "${THIRD_PARTY_DIR}"
        [ -d "${THIRD_PARTY_DIR}/${extracted}" ] || die "解压后未找到目录：${THIRD_PARTY_DIR}/${extracted}"
        mv "${THIRD_PARTY_DIR}/${extracted}" "${dest}"
    fi
done

# ---- 2) 给 SDL2 顺序应用 patches（只对「干净解压源」重放） -------------------
#
# 注意：不能依赖 `patch` 的「已应用」探测——Apple patch 在批处理模式下会把不适用
# 的方向自动翻转、dry-run 也返回成功（实测），因此检测不到未应用的源。这里改为：
#   marker（patches 内容 + SDL2 tar SHA1 的哈希）+ 落点内容复核；任一不满足即
#   从缓存 tar 包重新解压出 pristine 源、顺序重放全部 patch（不联网）。
# 应用优先用 `git apply`（严格、无方向翻转）；无 git 时退回 `patch -p1`（对 pristine
# 源正向应用，不存在方向歧义）。

SDL2_DIR="${THIRD_PARTY_DIR}/SDL2"
PATCH_MARKER="${THIRD_PARTY_DIR}/.xsys35-sdl2-patches"

shopt -s nullglob
patches=("${PATCH_DIR}"/*.patch)
shopt -u nullglob
[ ${#patches[@]} -gt 0 ] || die "patches 目录为空：${PATCH_DIR}"

patches_expected() {
    { cat "${patches[@]}"; printf '%s\n' "${DEP_SHA1[0]}"; } | sha1_of_stdin
}

applied_ok() {
    grep -q 'org_libsdl_app32' "${SDL2_DIR}/src/core/android/SDL_android.c" \
        && grep -q 'org/libsdl/app32/SDLActivity' "${SDL2_DIR}/src/core/android/SDL_android.c" \
        && grep -q 'org_libsdl_app32' "${SDL2_DIR}/src/hidapi/android/hid.cpp"
}

want="$(patches_expected)"
if [ -f "${PATCH_MARKER}" ] && [ "$(cat "${PATCH_MARKER}")" = "${want}" ] \
    && [ -d "${SDL2_DIR}" ] && applied_ok; then
    log "SDL2 补丁已应用，跳过：${SDL2_DIR}"
else
    log "在干净解压源上应用 patches：${SDL2_DIR}"
    # 重新解压，保证从 pristine 源顺序重放（解压自已校验的缓存 tar 包，不联网）。
    rm -rf "${SDL2_DIR}"
    tar -xzf "${DOWNLOAD_DIR}/${DEP_TARBALLS[0]}" -C "${THIRD_PARTY_DIR}"
    [ -d "${THIRD_PARTY_DIR}/${DEP_DIRS[0]}" ] || die "解压后未找到目录：${THIRD_PARTY_DIR}/${DEP_DIRS[0]}"
    mv "${THIRD_PARTY_DIR}/${DEP_DIRS[0]}" "${SDL2_DIR}"

    for p in "${patches[@]}"; do
        pname="$(basename "${p}")"
        if command -v git >/dev/null 2>&1; then
            (cd "${SDL2_DIR}" && git apply --check "${p}") || die "git apply --check 失败：${pname}"
            (cd "${SDL2_DIR}" && git apply "${p}") || die "git apply 失败：${pname}"
        else
            patch -p1 --dry-run -d "${SDL2_DIR}" < "${p}" || die "patch --dry-run 失败：${pname}"
            patch -p1 -d "${SDL2_DIR}" < "${p}" >/dev/null || die "patch 失败：${pname}"
        fi
        log "  已应用：${pname}"
    done
    applied_ok || die "patch 应用后内容复核失败（app32 前缀缺失）：${SDL2_DIR}"
    printf '%s\n' "${want}" > "${PATCH_MARKER}"
fi

# ---- 3) xsystem35 源码 ------------------------------------------------------

if [ -n "${SRC_DIR}" ]; then
    SRC_DIR="$(cd "${SRC_DIR}" && pwd)" || die "找不到 --src 目录"
    log "使用本地源码：${SRC_DIR}"
    if [ -f "${SRC_DIR}/.git/HEAD" ] || git -C "${SRC_DIR}" rev-parse --git-dir >/dev/null 2>&1; then
        head_commit="$(git -C "${SRC_DIR}" rev-parse HEAD 2>/dev/null || echo unknown)"
        if [ "${head_commit}" != "${FORK_COMMIT}" ]; then
            echo "warning: ${SRC_DIR} 的 HEAD=${head_commit}，与固定 commit ${FORK_COMMIT} 不同；" >&2
            echo "         产物可能无法与 BUILD.md 记录复现一致。" >&2
        fi
    fi
else
    SRC_DIR="${BUILD_DIR}/xsystem35-src"
    if [ -d "${SRC_DIR}/.git" ]; then
        log "更新已有源码 checkout：${SRC_DIR}"
        git -C "${SRC_DIR}" fetch origin --tags
    else
        log "clone fork：${FORK_URL}"
        rm -rf "${SRC_DIR}"
        git clone "${FORK_URL}" "${SRC_DIR}"
    fi
    git -C "${SRC_DIR}" checkout --detach "${FORK_COMMIT}"
    log "checkout 固定 commit：${FORK_COMMIT}"
fi
[ -f "${SRC_DIR}/CMakeLists.txt" ] || die "源码目录缺少 CMakeLists.txt：${SRC_DIR}"

# ---- 4) 配置 + 构建 ---------------------------------------------------------

BUILD_TREE="${BUILD_DIR}/build"
if [ "${CLEAN}" -eq 1 ]; then
    log "清空构建树：${BUILD_TREE}"
    rm -rf "${BUILD_TREE}"
fi

log "cmake 配置"
"${CMAKE_BIN}" -S "${HOST_CMAKE_DIR}" -B "${BUILD_TREE}" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="${NDK_DIR}/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-26 \
    -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release \
    -DXSYS35_SRC="${SRC_DIR}" \
    -DXSYS35_THIRD_PARTY_DIR="${THIRD_PARTY_DIR}"

log "构建（ninja）"
if [ -n "${JOBS}" ]; then
    "${CMAKE_BIN}" --build "${BUILD_TREE}" -j "${JOBS}"
else
    "${CMAKE_BIN}" --build "${BUILD_TREE}" -j
fi

# 计划内产物只应有 3 个 .so；多余产物（如 libwebp.so）说明裁剪失效，直接报错。
extra_sos="$(find "${BUILD_TREE}" -name '*.so' -type f \
    ! -path '*/CMakeFiles/*' \
    ! -name 'libxsystem35.so' ! -name 'libSDL2x.so' ! -name 'libSDL2_mixer_xs35.so' \
    2>/dev/null || true)"
if [ -n "${extra_sos}" ]; then
    echo "error: 构建树出现计划外 .so：" >&2
    while IFS= read -r f; do
        [ -n "${f}" ] && echo "  ${f}" >&2
    done <<< "${extra_sos}"
    die "产物集合应为 libxsystem35.so / libSDL2x.so / libSDL2_mixer_xs35.so 三个（若为旧配置残留，请加 --clean 重建）"
fi

# ---- 5) strip + 复制 + 摘要 -------------------------------------------------

ARTIFACTS=(
    "${BUILD_TREE}/xsystem35/src/libxsystem35.so"
    "${BUILD_TREE}/SDL2/libSDL2x.so"
    "${BUILD_TREE}/SDL2_mixer/libSDL2_mixer_xs35.so"
)
for so in "${ARTIFACTS[@]}"; do
    [ -f "${so}" ] || die "未生成预期产物：${so}"
done

log "strip（llvm-strip --strip-unneeded）"
"${LLVM_STRIP}" --strip-unneeded "${ARTIFACTS[@]}"

mkdir -p "${OUT_DIR}"
log "复制产物到 ${OUT_DIR}"
cp "${ARTIFACTS[@]}" "${OUT_DIR}/"

echo
echo "=================== 产物摘要 ==================="
for so in "${ARTIFACTS[@]}"; do
    base="$(basename "${so}")"
    echo
    echo "-- ${base}"
    "${LLVM_READELF}" -d "${so}" | grep -E 'SONAME|NEEDED' | sed 's/^/   /'
    echo "   SHA256 $(sha256_of "${so}")"
done
echo
echo "完成：3 个产物已输出到 ${OUT_DIR}"
