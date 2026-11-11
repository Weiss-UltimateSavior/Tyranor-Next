#!/usr/bin/env python3
"""Compile the actual patch-selection function in host and upstream modes."""
import argparse
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    args = parser.parse_args()
    source = args.source.resolve()
    text = (source/'src/userconfig.cpp').read_text()
    begin = text.index('std::optional<std::string_view> GetPatchProfile() {')
    end = text.index('\n}\n', begin) + 2
    function = text[begin:end]
    harness = r'''
#include <cassert>
#include <optional>
#include <string>
#include <string_view>
struct Settings { bool UsePatch; std::string PatchProfile; int OtherSetting = 42; } settings;
std::string PatchProfileOverride;
int activeReads = 0;
Settings const& ActiveGameSettings() { ++activeReads; return settings; }
''' + function + r'''
int main() {
  for (bool enabled : {false, true}) {
    for (std::string profile : {"", "custom"}) {
      for (std::string override : {"", "override"}) {
        settings = {enabled, profile, 42};
        PatchProfileOverride = override;
        activeReads = 0;
        auto result = GetPatchProfile();
#ifdef IMPACTO_TYRANOR_HOST
        assert(!result && activeReads == 0);
#else
        if (!override.empty()) assert(result && *result == override);
        else if (enabled) assert(result && *result == profile);
        else assert(!result);
#endif
        // Ignoring unsupported patches must not reset persisted preferences.
        assert(settings.UsePatch == enabled && settings.PatchProfile == profile);
        assert(settings.OtherSetting == 42 && PatchProfileOverride == override);
      }
    }
  }
}
'''
    with tempfile.TemporaryDirectory(prefix='impacto-patch-test-') as directory:
        temp = Path(directory)
        (temp/'patch.cpp').write_text(harness)
        ui = '\n'.join(line for line in (source/'src/overlay/settingspage.cpp').read_text().splitlines() if not line.lstrip().startswith('#include'))
        (temp/'ui.cpp').write_text(ui)
        for host in (False, True):
            define = ['-DIMPACTO_TYRANOR_HOST=1'] if host else []
            exe = temp/('host' if host else 'upstream')
            subprocess.run(['c++', '-std=c++20', '-Wall', '-Wextra', '-Werror', *define, str(temp/'patch.cpp'), '-o', str(exe)], check=True)
            subprocess.run([str(exe)], check=True)
            processed = subprocess.check_output(['c++', '-E', '-P', *define, str(temp/'ui.cpp')], text=True)
            if ('ShowPatchSettings' not in processed) != host or ('"Use Patch"' not in processed) != host:
                raise AssertionError('Patch settings UI guard does not match host mode')
        lua = (source/'src/profile/profile.cpp').read_text()
        begin = lua.index('static int LuaGetPatchProfile(lua_State* ctx) {')
        host_branch = lua[begin:lua.index('#else', begin)]
        if '#ifdef IMPACTO_TYRANOR_HOST' not in host_branch or 'lua_pushstring(ctx, "");' not in host_branch:
            raise AssertionError('Lua patch query can expose a stale host patch profile')
    print('PASS: 16 actual patch-selection cases preserve settings, host-only UI exclusion, and empty host Lua patch query')


if __name__ == '__main__':
    main()
