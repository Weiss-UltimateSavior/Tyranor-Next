package com.core.impacto;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pure generator: only trusted bundled Lua runs, and every argv element is separate. */
final class ImpactoLaunchConfig {
    private ImpactoLaunchConfig() { }
    static String luaString(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (byte encoded : text.getBytes(StandardCharsets.UTF_8)) {
            int value = encoded & 255;
            if (value == '"' || value == '\\') out.append('\\').append((char) value);
            else if (value < 32 || value >= 127) out.append(String.format(java.util.Locale.ROOT, "\\%03d", value));
            else out.append((char) value);
        }
        return out.append('"').toString();
    }
    static Map<String, String> scripts(File game, File resources, File user, File session, ImpactoProfiles.Profile profile) throws IOException {
        File saves = ImpactoFiles.child(user, "saves");
        Map<String, String> result = new LinkedHashMap<>();
        result.put("basepaths.lua", "root.BasePaths = {\n"
                + "  RootInstallDir = " + luaString(resources.getPath()) + ",\n"
                + "  RootGamedataDir = " + luaString(game.getPath()) + ",\n"
                + "  RootProfilesDir = " + luaString(new File(resources, "profiles").getPath()) + ",\n"
                + "  RootPatchesDir = \"\",\n"
                + "  RootSavesDir = " + luaString(saves.getPath()) + ",\n};\n");
        result.put("gamedefinitions.lua", "root.GameDefinitions = { [" + luaString(profile.id) + "] = {\n"
                + "  Name = " + luaString(profile.name) + ",\n"
                + "  GameProfile = " + luaString(ImpactoFiles.child(session, "profile.lua").getPath()) + ",\n"
                + "  Hidden = false,\n  Patch = {},\n} };\n");
        StringBuilder wrapper = new StringBuilder("-- Generated from the audited bundled profile. No game-directory Lua.\n");
        wrapper.append("dofile(").append(luaString(ImpactoFiles.child(resources, profile.lua).getPath())).append(")\n");
        wrapper.append("local prefix = ").append(luaString(game.getPath() + "/" + profile.gamePrefix + "/")).append("\n");
        wrapper.append("local paths = {\n");
        for (ImpactoProfiles.Requirement required : profile.required) {
            wrapper.append("  [").append(luaString(required.path)).append("] = ")
                    .append(luaString(ImpactoFiles.child(game, required.path).getPath())).append(",\n");
        }
        wrapper.append("}\nlocal function remap(path)\n"
                + "  if type(path) ~= 'string' or string.sub(path, 1, #prefix) ~= prefix then return path end\n"
                + "  local mapped = paths[string.sub(path, #prefix + 1)]\n"
                + "  assert(mapped, 'Unapproved game mount')\n  return mapped\nend\n"
                + "if root.Vfs and root.Vfs.Mounts then\n"
                + "  for _, mounts in pairs(root.Vfs.Mounts) do\n"
                + "    for index, mount in ipairs(mounts) do\n"
                + "      if type(mount) == 'string' then mounts[index] = remap(mount)\n"
                + "      elseif type(mount) == 'table' then\n"
                + "        mount.Path = remap(mount.Path)\n        mount.Order = remap(mount.Order)\n"
                + "      end\n    end\n  end\nend\n");
        wrapper.append("assert(type(root.SaveData) == 'table', 'Missing bundled save configuration')\n");
        for (Map.Entry<String, String> field : profile.saveFields.entrySet()) {
            wrapper.append("root.SaveData[").append(luaString(field.getKey())).append("] = ")
                    .append(luaString(ImpactoFiles.child(saves, field.getValue()).getPath())).append("\n");
        }
        wrapper.append("if type(root.AchievementData) == 'table' then\n  root.AchievementData.AchievementSystem = ")
                .append(luaString(ImpactoFiles.child(saves, "ACHIEVEMENTS.DAT").getPath())).append("\nend\n");
        result.put("profile.lua", wrapper.toString());
        return result;
    }
    static void write(File session, Map<String, String> scripts) throws IOException {
        for (Map.Entry<String, String> script : scripts.entrySet()) {
            File target = ImpactoFiles.child(session, script.getKey());
            Files.write(target.toPath(), script.getValue().getBytes(StandardCharsets.UTF_8));
            if (!target.setReadOnly()) throw new IOException("Cannot protect launch configuration");
        }
    }
    static void verify(File session, Map<String, String> expected) throws IOException {
        for (Map.Entry<String, String> script : expected.entrySet()) {
            File target = ImpactoFiles.child(session, script.getKey());
            byte[] bytes = script.getValue().getBytes(StandardCharsets.UTF_8);
            if (!target.isFile() || target.length() != bytes.length
                    || !java.util.Arrays.equals(Files.readAllBytes(target.toPath()), bytes)) {
                throw new IOException("Launch configuration is missing or changed");
            }
        }
    }
    static String[] arguments(ImpactoProfiles.Profile profile, File session, File user, File resources) throws IOException {
        return new String[]{"-g", profile.id, "-bp", ImpactoFiles.child(session, "basepaths.lua").getPath(),
                "-gc", ImpactoFiles.child(session, "gamedefinitions.lua").getPath(),
                "-uc", ImpactoFiles.child(user, "user.toml").getPath(),
                "--tyranor-resource-root", resources.getPath()};
    }
}
