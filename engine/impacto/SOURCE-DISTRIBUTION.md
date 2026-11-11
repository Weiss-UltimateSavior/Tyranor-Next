# Impacto corresponding-source distribution

## Release contents

Distribute the actual files listed in `sources/index.json`, `sources/SHA256SUMS`,
this document, and the dependency notices together with each matching Impacto
runtime. Repository links and a promise to provide source later are **not** the
source-distribution mechanism. A runtime must not be published without its matching
source set. The index is produced only after the native build has completed.

The source set contains:

- The patched Impacto application, its vendored libraries (including mspack),
  profiles, headers and build files. The unchanged resources directory is supplied once in the separate runtime resources.zip.xz; restore its resources/ entries into the extracted Impacto source before rebuilding. Each omitted source resource is listed with its original hash in metadata/excluded-files.json. `source/` is already patched;
  the informational patch must not be applied again.
- The exact LibAtrac9, ImGui and magic_enum checkouts used by CMake, including
  local build modifications.
- One archive for every installed target vcpkg dependency, including transitive
  and header-only dependencies, and the installed host build dependencies. Each
  includes the original downloaded source bytes, matched by SHA512, the exact
  port recipe and patches matched against installed SPDX hashes, license text,
  and available package/ABI metadata. Port-only helper packages contain their
  actual source in `port/`. Vulkan's Android loader is supplied by the NDK/system;
  its detection recipe and Vulkan headers are included as applicable.
- Pinned vcpkg build scripts, triplets and bootstrap metadata.
- A snapshot of Tyranor application/integration source and resources, excluding
  prebuilt executable code and local build output. Excluded files and hashes are
  recorded inside that archive. Unrelated prebuilt game engines are not Impacto
  dependencies; the ordinary complete Tyranor repository/release supplies them
  when rebuilding the entire APK. The Gradle wrapper JAR is also excluded; use
  Gradle's official wrapper bootstrap for the version in its properties file.
- A separate archive containing all generated dependency notices.

The packager does not archive `.git`, native build directories, installed
libraries, object files, executables or APKs. Original upstream source downloads
are kept intact, including their own upstream fixtures and examples. Runtime
resource data (images/fonts) are source-distribution inputs, not executables. They are distributed once in the runtime companion archive rather than duplicated in the code-source archives. Generated runtime manifests/provenance are also separate release companions, avoiding self-referential source hashes.

Every outer archive is deterministic for identical inputs (sorted paths, stable
permissions, zero timestamps) and must be **less than 100,000,000 bytes**. The
packager fails rather than silently omitting a component that exceeds this limit.
SHA256SUMS covers every archive and the index. SPDX creation-time randomness is
normalized; source and binary provenance hashes remain intact.

## Generate and validate

Keep vcpkg downloads and the fetched Git checkouts until packaging is complete.
After the final integration edits and successful native build, run from the
Tyranor repository root:

```sh
python3 tools/impacto/package-sources.py \
  --source "$IMPACTO_BUILD_ROOT/source" \
  --build "$IMPACTO_BUILD_ROOT/build" \
  --vcpkg "$IMPACTO_BUILD_ROOT/vcpkg"
(cd engine/impacto/sources && sha256sum -c SHA256SUMS)
```

The script requires `libimpacto.so`, all direct Android manifest dependencies,
installed SPDX/ABI metadata, exact downloads and fetched Git source pins. It
fails on missing or unverifiable inputs. SPDX's statically scanned disabled
libpng APNG patch is excluded only when the actual ABI feature list proves APNG
was not used. Meson's unresolved SPDX checksum is resolved from its verified
port recipe. Other unresolvable resources fail closed.

Run packaging a second time and compare SHA256SUMS if testing repeatability.
Do not edit application source between those runs. Run `package-runtime.py` after
source packaging so the runtime's license asset bundle includes the generated
notices. The source archives themselves are release companion files, not APK
assets. Keep the index associated with the exact native-runtime checksum manifest.

## Rebuild and relink a modified library

The supplied Impacto source and non-system library sources allow rebuilding and
relinking `libimpacto.so` with modified LGPL libraries. No proprietary object
file is needed in place of the supplied Impacto application source. You may
modify these libraries and reverse engineer the linked work to debug those
modifications as permitted by their licenses. The source bundle does not add
restrictions on those rights.

Use the Android NDK version/API/ABI in `source-lock.json`, CMake, Ninja, Python 3,
Git and normal host build tools. For a full APK also use the repository's pinned
JDK, SDK and Gradle versions. NDK/system libraries and ordinary build-tool
binaries are prerequisites, not bundled source components.

1. Verify SHA256SUMS and extract every `.tar.xz` to one directory, called
   `EXTRACT` below. Restore the Tyranor integration snapshot to a repository
   checkout of the matching release, retaining the separately supplied unrelated
   prebuilt engine libraries when building the full APK.
2. Use a vcpkg checkout at the recorded revision, or restore the supplied
   `vcpkg-build-support/vcpkg` scripts and bootstrap the official vcpkg tool.
   The tool itself is an ordinary build prerequisite. Copy each dependency's
   `upstream/*` files into `$VCPKG_ROOT/downloads/` and each `port/` directory into
   a common overlay-port directory under its component name. Identical target
   and host copies may share a port only when their hashes match. Keep differing
   target/host recipes separate if a future release uses them.
3. Use `impacto/source` as the already patched CMake source. To modify an LGPL
   vcpkg library, unpack its included original source, change it, and add the
   change as an additional patch in its supplied overlay recipe. For vendored
   mspack, edit `impacto/source/vendor/mspack` directly. This preserves the
   checked-in recipe's build configuration and upstream patches.
4. Configure as in `tools/impacto/build-native.sh`, replacing its checkout step
   with the extracted sources. Set the overlay ports to the restored recipes,
   and pass these additional options so FetchContent uses the supplied trees:

   ```sh
   -DFETCHCONTENT_SOURCE_DIR_LIBATRAC9="$EXTRACT/libatrac9/source" \
   -DFETCHCONTENT_SOURCE_DIR_IMGUI="$EXTRACT/imgui/source" \
   -DFETCHCONTENT_SOURCE_DIR_MAGIC_ENUM="$EXTRACT/magic_enum/source"
   ```

   Retain the Android chainload toolchain, `arm64-android-impacto` triplet,
   `IMPACTO_TYRANOR_HOST=1`, static C++ runtime, private SDL namespace and 16 KiB
   linker-page settings from the supplied build script and patches. Then run
   `cmake --build` on that build directory. When using extracted source, do not
   rerun the Git patch application in `build-native.sh` against the already
   patched source. Standard tool bootstrap may require network access; all
   non-system runtime source inputs are nevertheless provided as actual files.
5. Replace the Impacto native library in the matching Tyranor source checkout,
   regenerate its runtime pack/checksum manifest using `package-runtime.py --native-only` with the extracted `--source`, modified `--build`, and matching `--ndk`,
   and build/install an APK with your own signing key. Android cannot upgrade an
   APK signed by a different key; use a separate application ID or explicitly
   migrate/back up data before replacing an installation.

The archive's FFmpeg `configuration/` files record generated configuration,
with local source/build paths replaced by placeholders. The exact effective
features are also recorded in ABI metadata. They are evidence, not portable
build cache files; regenerate them with the supplied recipes.

## License-specific facts and resource scope

FFmpeg 7.1.2 is built with GPL, version-3 and nonfree components disabled. The
packager verifies `CONFIG_GPL`, `CONFIG_VERSION3`, and `CONFIG_NONFREE` are all
zero in the generated configuration. Its applicable library license is
LGPL-2.1-or-later. The full FFmpeg source retains licenses for optional code that
is not enabled in this runtime; those files do not imply that the shipped
runtime enabled that code.

Vendored mspack source headers specify **LGPL version 2.1**. Upstream's combined
THIRDPARTY document embeds LGPL 2.0 under mspack, which does not match those
headers. `licenses/dependencies/mspack/COPYING.LIB` supplies the full applicable
2.1 text, with a correction notice. All included vendor source and upstream
notices remain available for attribution review.

Impacto's upstream ISC license is the stated project-wide scope for retained
project resources; Noto fonts retain their OFL notice. The provenance of some
pre-generated font atlases is not independently established. Retaining the
upstream files and notices does not claim that uncertainty has been resolved.
Commercial game data is not supplied. Source packaging is not a game-asset
license grant or a substitute for the release's asset provenance review.
