# =============================================================================
# xsystem35 Android 预构建剧本（android-host）
#
# 等价于上游 `xsystem35-sdl2` 的 `android/app/jni/CMakeLists.txt`（基线 commit
# 18af9be92dbe811b783c81393ca2307ccc50efd5，XSYSTEM35_VERSION 2.20.0），差异如下：
#
#   1) 源码与依赖均**不随 TyranorNext 主仓分发**：xsystem35 源码由 `XSYS35_SRC`
#      指向 fork checkout（https://github.com/Weiss-UltimateSavior/xsystem35-sdl2），
#      依赖源码目录由 `XSYS35_THIRD_PARTY_DIR` 指向（需含 SDL2 / freetype /
#      SDL2_mixer / libwebp 四个子目录）；二者均由 `../scripts/build-xsys35.sh`
#      准备到**仓库外**构建目录，本剧本不使用 FetchContent 联网下载
#      （上游用 URL 拉取 SDL2 2.32.10 / freetype 2.14.3 / SDL2_mixer 2.8.0）；
#   2) libwebp 由 xsystem35 根 CMakeLists.txt 的 `fetch_webp()` 消费：这里用
#      `FETCHCONTENT_SOURCE_DIR_LIBWEBP` 指到依赖目录，FetchContent 便不再下载；
#   3) SDL2 / SDL2_mixer 产物重命名（`libSDL2x.so` / `libSDL2_mixer_xs35.so`），避免与
#      ONS 插件的 `libSDL2.so`（2.26.3）在 APK 内同名冲突（方案 §3.4）；
#   4) 各依赖的库形态用「目录作用域普通变量」显式固定：freetype 静态、SDL2_mixer 共享、
#      libwebp 静态；不写全局 cache 选项。原因：CMakeCache 跨配置保留
#      `BUILD_SHARED_LIBS`，若交给上游 `option()` 的先后顺序决定，重配置时 freetype 会
#      因缓存残留被构建成共享库（首配/重配产物不一致）；SDL2 自身显式定义共享/静态两个
#      target，不受影响。见下方依赖段落。
#
# 参数：
#   XSYS35_SRC              必填；xsystem35 源码目录（fork checkout，根含 CMakeLists.txt）。
#                           未提供时 FATAL_ERROR，并提示用 ../scripts/build-xsys35.sh。
#   XSYS35_THIRD_PARTY_DIR  选填；依赖源码根目录（含 SDL2 / freetype / SDL2_mixer / libwebp）。
#                           默认 ${CMAKE_CURRENT_SOURCE_DIR}/../third_party（该目录存在才可用）；
#                           缺失时报 FATAL_ERROR 并提示用 ../scripts/build-xsys35.sh。
#
# 上游同款裁剪（freetype 只用自带光栅器、SDL2_mixer 关掉可选解码器）逐条保留。
#
# 产物：libxsystem35.so（+ libSDL2x.so、libSDL2_mixer_xs35.so）；freetype / libwebp
# 静态链入 libxsystem35.so。完整命令与产物校验见 ../BUILD.md。
# =============================================================================
cmake_minimum_required(VERSION 3.14)

project(xsystem35-android-host LANGUAGES C CXX)

# -----------------------------------------------------------------------------
# 参数校验（见文件头「参数」段）
# -----------------------------------------------------------------------------
if (NOT DEFINED XSYS35_SRC OR XSYS35_SRC STREQUAL "")
  message(FATAL_ERROR
    "XSYS35_SRC 未设置：请用 -DXSYS35_SRC=<xsystem35 源码目录> 指向 fork checkout "
    "(Weiss-UltimateSavior/xsystem35-sdl2 @ 18af9be92dbe811b783c81393ca2307ccc50efd5)。"
    " 推荐直接运行 engine/xsystem35/scripts/build-xsys35.sh（自动准备源码与依赖）。")
endif()
get_filename_component(XSYS35_SRC "${XSYS35_SRC}" ABSOLUTE)
if (NOT EXISTS "${XSYS35_SRC}/CMakeLists.txt")
  message(FATAL_ERROR
    "XSYS35_SRC 不是有效的 xsystem35 源码目录（缺少 CMakeLists.txt）：${XSYS35_SRC}")
endif()

if (NOT DEFINED XSYS35_THIRD_PARTY_DIR OR XSYS35_THIRD_PARTY_DIR STREQUAL "")
  set(XSYS35_THIRD_PARTY_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../third_party")
endif()
get_filename_component(XSYS35_THIRD_PARTY_DIR "${XSYS35_THIRD_PARTY_DIR}" ABSOLUTE)
foreach (dep SDL2 freetype SDL2_mixer libwebp)
  if (NOT IS_DIRECTORY "${XSYS35_THIRD_PARTY_DIR}/${dep}")
    message(FATAL_ERROR
      "XSYS35_THIRD_PARTY_DIR 缺少依赖子目录 ${dep}：${XSYS35_THIRD_PARTY_DIR}/${dep}。"
      " 依赖不随主仓分发，请运行 engine/xsystem35/scripts/build-xsys35.sh 准备到仓库外构建目录，"
      " 或显式传 -DXSYS35_THIRD_PARTY_DIR=<含 SDL2/freetype/SDL2_mixer/libwebp 的目录>。")
  endif()
endforeach()

# freetype / libwebp 会以静态库链入 libxsystem35.so，必须带 PIC。
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# -----------------------------------------------------------------------------
# 依赖裁剪（与上游 android/app/jni/CMakeLists.txt 完全一致）
# -----------------------------------------------------------------------------

# Only FreeType's own rasterizer is needed, none of its optional dependencies.
set(FT_DISABLE_ZLIB ON CACHE BOOL "Disable use of system zlib" FORCE)
set(FT_DISABLE_BZIP2 ON CACHE BOOL "Disable use of system libbz2" FORCE)
set(FT_DISABLE_PNG ON CACHE BOOL "Disable use of system libpng" FORCE)
set(FT_DISABLE_HARFBUZZ ON CACHE BOOL "Disable use of harfbuzz" FORCE)
set(FT_DISABLE_BROTLI ON CACHE BOOL "Disable use of brotli" FORCE)

set(SDL2MIXER_OPUS OFF CACHE BOOL "Enable Opus music" FORCE)
set(SDL2MIXER_FLAC OFF CACHE BOOL "Enable FLAC music" FORCE)
set(SDL2MIXER_MOD  OFF CACHE BOOL "Support loading MOD music" FORCE)
set(SDL2MIXER_MIDI OFF CACHE BOOL "Enable MIDI music" FORCE)
set(SDL2MIXER_WAVPACK OFF CACHE BOOL "Enable WavPack music" FORCE)
set(SDL2MIXER_SAMPLES OFF CACHE BOOL "Build the SDL2_mixer sample program(s)" FORCE)
set(SDL2MIXER_INSTALL OFF CACHE BOOL "Enable SDL2_mixer install target" FORCE)
# 新增（上游没有）：让 mixer 的依赖（ogg/vorbis 等，若启用）静态链入
# mixer，避免 libogg/libvorbis 等额外 .so 进入 APK。默认 codec 为内置 STB/minimp3，
# 该项是防御性设置，保证「APK 内只出现计划内的 .so」。
set(SDL2MIXER_DEPS_SHARED OFF CACHE BOOL "Load dependencies dynamically" FORCE)

# -----------------------------------------------------------------------------
# 依赖（按上游 FetchContent_MakeAvailable(SDL freetype SDL_mixer) 的顺序）
#
# 库形态显式固定（首配与重配必须一致，且不写入 CMakeCache）：
#   SDL2       → 上游自身显式定义共享 + 静态两个 target（不受 BUILD_SHARED_LIBS 影响）；
#   freetype   → 静态（仅链入 libxsystem35.so）；
#   SDL2_mixer → 共享（libSDL2_mixer_xs35.so）；
#   libwebp    → 静态（见下方段落）。
# 说明：freetype / libwebp 的 CMakeLists 用无类型 add_library，形态由 BUILD_SHARED_LIBS
# 决定；该变量若交由上游 option() 写入 CMakeCache，重配置时会残留（freetype 在
# SDL2_mixer 之前配置，第二次配置将变成共享库）——因此这里用普通变量显式钉住。
# -----------------------------------------------------------------------------

add_subdirectory(${XSYS35_THIRD_PARTY_DIR}/SDL2 SDL2)
set_target_properties(SDL2 PROPERTIES OUTPUT_NAME SDL2x)

set(BUILD_SHARED_LIBS OFF)
add_subdirectory(${XSYS35_THIRD_PARTY_DIR}/freetype freetype)

set(BUILD_SHARED_LIBS ON)
add_subdirectory(${XSYS35_THIRD_PARTY_DIR}/SDL2_mixer SDL2_mixer)
set_target_properties(SDL2_mixer PROPERTIES OUTPUT_NAME SDL2_mixer_xs35)

# libwebp 静态链入 libxsystem35.so（在 xsystem35 本体之前处理）。
# 注意两点：
#   1) libwebp 自己的库 target 跟随 `BUILD_SHARED_LIBS`（`WEBP_LINK_STATIC` 只影响工具链链接），
#      这里用「目录作用域的普通变量」把它关掉——不写入 CMakeCache.txt、不影响已配置完成的
#      SDL2 / SDL2_mixer，也不构成全局设置；
#   2) 若不处理，libwebp 会产出 libwebp.so / libsharpyuv.so 等额外 .so，破坏「APK 内只出现
#      计划内产物」的约束。
set(BUILD_SHARED_LIBS OFF)
set(WEBP_LINK_STATIC ON CACHE BOOL "Build libwebp as a static library" FORCE)

# -----------------------------------------------------------------------------
# 本地 libwebp（xsystem35 根 CMakeLists.txt 的 fetch_webp() 会在 Android 分支调用）
# -----------------------------------------------------------------------------
set(FETCHCONTENT_SOURCE_DIR_LIBWEBP "${XSYS35_THIRD_PARTY_DIR}/libwebp"
    CACHE PATH "Local libwebp source directory (no download)" FORCE)

# -----------------------------------------------------------------------------
# xsystem35 本体（等价上游 `add_subdirectory(${PROJECT_ROOT_DIR} xsystem35)`）
# -----------------------------------------------------------------------------
add_subdirectory(${XSYS35_SRC} xsystem35)
