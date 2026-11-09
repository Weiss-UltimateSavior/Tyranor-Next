#!/usr/bin/env bash
# Build host: x86-64 Linux or x86-64 WSL with the Linux Android NDK, GNU tools and flock.
set -euo pipefail

archive=$1
ndk=$2
prefix=$3
linkage=${4:-shared}
case "$linkage" in
    shared) library_options=(--enable-shared --disable-static) ;;
    static) library_options=(--disable-shared --enable-static) ;;
    *) printf 'Expected FFmpeg linkage shared or static, got %s\n' "$linkage" >&2; exit 2 ;;
esac
ndk=$(CDPATH= cd -- "$ndk" && pwd -P)
mkdir -p -- "$prefix"
prefix=$(CDPATH= cd -- "$prefix" && pwd -P)
source_dir="$prefix/source"
build_dir="$prefix/obj"
exec 9>"$prefix/.emberfall-build.lock"
flock 9
# A killed extraction can leave behind its temporary directory.
for stale in "$prefix"/.source.????????; do
    if [[ -d "$stale" && ! -L "$stale" && "${stale##*/}" =~ ^\.source\.[[:alnum:]]{8}$ ]]; then
        rm -rf -- "$stale"
    fi
done
if [[ -L "$source_dir" || ! -f "$source_dir/.emberfall-extracted" || ! -f "$source_dir/configure" ]]; then
    staging=$(mktemp -d "$prefix/.source.XXXXXXXX")
    [[ "$staging" == "$prefix"/.source.* && -d "$staging" ]]
    trap 'rm -rf -- "$staging"' EXIT
    mkdir "$staging/tree"
    tar -xzf "$archive" --strip-components=1 -C "$staging/tree"
    [[ -f "$staging/tree/configure" ]]
    touch "$staging/tree/.emberfall-extracted"
    # Invalidate configuration before publishing the new tree, including on an interrupted build.
    rm -f -- "$build_dir/ffbuild/emberfall-options"
    if [[ -e "$source_dir" || -L "$source_dir" ]]; then
        previous=$(mktemp -d "$prefix/source-previous.XXXXXXXX")
        mv -T -- "$source_dir" "$previous/source"
    fi
    mv -T -- "$staging/tree" "$source_dir"
fi
mkdir -p "$build_dir"
cd "$build_dir"
llvm="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
options=(
    --prefix="$prefix"
    --arch=aarch64 --target-os=android --enable-cross-compile
    --cc="$llvm/aarch64-linux-android26-clang" --ar="$llvm/llvm-ar"
    --nm="$llvm/llvm-nm" --ranlib="$llvm/llvm-ranlib" --strip="$llvm/llvm-strip"
    --extra-ldflags=-Wl,-z,max-page-size=16384
    "${library_options[@]}" --enable-pic --disable-autodetect
    --disable-everything --disable-programs --disable-doc --disable-network
    --disable-avfilter --disable-avdevice --enable-swscale --enable-swresample
    # MPEG-PS without a program map uses the elementary-video demuxer to identify its video codec.
    --enable-demuxer=asf,mpegps,mpegvideo --enable-protocol=file
    --enable-parser=mpegvideo,mpegaudio
    --enable-decoder=wmv3,vc1,wmav2,wmapro,mpeg1video,mpeg2video,mp2
)
if [[ ! -f ffbuild/config.mak ]] || ! cmp -s ffbuild/emberfall-options <(printf '%s\n' "${options[@]}"); then
    if [[ -f ffbuild/config.mak ]]; then make -s distclean; fi
    "$source_dir/configure" "${options[@]}"
    printf '%s\n' "${options[@]}" > ffbuild/emberfall-options
fi
make -s -j6
make -s install
