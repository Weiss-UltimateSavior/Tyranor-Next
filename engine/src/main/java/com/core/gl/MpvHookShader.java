package com.core.gl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Parser for MAIN hooks. WIDTH/HEIGHT expressions size intermediate targets; the host sets final display geometry. */
public final class MpvHookShader {
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern HOOKED_SYMBOL =
            Pattern.compile("\\bHOOKED(?=_(?:texOff|tex|pos|size|pt)\\b)");

    public static final class Pass {
        public final String desc;
        /** HOOKED names and GLSL symbols are normalized to MAIN. */
        public final List<String> binds;
        /** null or MAIN denotes the final display result. */
        public final String save;
        public final String widthRef;
        public final String heightRef;
        public final String body;

        Pass(String desc, List<String> binds, String save,
             String widthRef, String heightRef, String body) {
            this.desc = desc;
            this.binds = Collections.unmodifiableList(new ArrayList<>(binds));
            this.save = save;
            this.widthRef = widthRef;
            this.heightRef = heightRef;
            this.body = HOOKED_SYMBOL.matcher(body).replaceAll("MAIN");
        }

        public boolean isFinalToScreen() {
            return save == null || "MAIN".equals(save);
        }
    }

    private MpvHookShader() {}

    /** Unsupported mpv features fail explicitly rather than changing shader semantics. */
    public static List<Pass> parse(String source) {
        if (source == null) throw new IllegalArgumentException("null shader source");
        List<Pass> passes = new ArrayList<>();
        List<String> binds = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        String desc = null, save = null, widthRef = null, heightRef = null;
        boolean inPass = false;
        for (String raw : source.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("//!")) {
                String directive = line.substring(3).trim();
                int space = directive.indexOf(' ');
                String key = space < 0 ? directive : directive.substring(0, space);
                String value = space < 0 ? "" : directive.substring(space + 1).trim();
                if ("DESC".equals(key)) {
                    if (inPass) {
                        passes.add(new Pass(desc, binds, save, widthRef, heightRef, body.toString()));
                        binds = new ArrayList<>();
                        body = new StringBuilder();
                        save = widthRef = heightRef = null;
                    }
                    desc = value;
                    inPass = true;
                    continue;
                }
                if (!inPass) throw new IllegalArgumentException("directive before DESC: " + key);
                switch (key) {
                    case "HOOK":
                        if (!"MAIN".equals(value)) {
                            throw new IllegalArgumentException("unsupported hook: " + value);
                        }
                        break;
                    case "BIND":
                        String bind = textureName(value);
                        if (!binds.contains(bind)) binds.add(bind);
                        break;
                    case "SAVE": save = textureName(value); break;
                    case "WIDTH": widthRef = dimensionReference(value, 'w'); break;
                    case "HEIGHT": heightRef = dimensionReference(value, 'h'); break;
                    case "COMPONENTS":
                        if (!"1".equals(value) && !"2".equals(value)
                                && !"3".equals(value) && !"4".equals(value)) {
                            throw new IllegalArgumentException("invalid COMPONENTS: " + value);
                        }
                        break; // RGBA allocation retains all requested components.
                    default:
                        throw new IllegalArgumentException("unsupported mpv directive: " + key);
                }
            } else if (inPass) {
                body.append(raw).append('\n');
            }
        }
        if (inPass) passes.add(new Pass(desc, binds, save, widthRef, heightRef, body.toString()));
        validateSameSizeChain(passes);
        return Collections.unmodifiableList(passes);
    }

    /** The production GLES source, also used by the offline compiler check. */
    static String fragmentSource(Pass pass) {
        StringBuilder fragment = new StringBuilder();
        fragment.append("#version 300 es\nprecision highp float;\nprecision highp int;\nprecision highp sampler2D;\nin vec2 vPos;\nout vec4 fragColor;\nuniform vec2 u_OUTPUT_size;\n#define OUTPUT_size u_OUTPUT_size\n");
        fragment.append("uniform bool u_FLIP_Y;\nvec2 hookCoord(vec2 p) { return vec2(p.x, u_FLIP_Y ? 1.0-p.y : p.y); }\n");
        for (int i = 0; i < pass.binds.size(); i++) {
            String bind = pass.binds.get(i);
            fragment.append("uniform sampler2D u_").append(bind).append(";\n")
                    .append("uniform vec2 u_").append(bind).append("_size;\n")
                    .append("vec4 ").append(bind).append("_tex(vec2 pos) { return texture(u_")
                    .append(bind).append(", hookCoord(pos)); }\n")
                    .append("vec4 ").append(bind).append("_texOff(vec2 off) { return ")
                    .append(bind).append("_tex(hookCoord(vPos) + off / u_").append(bind).append("_size); }\n")
                    .append("#define ").append(bind).append("_pos hookCoord(vPos)\n")
                    .append("#define ").append(bind).append("_size u_").append(bind).append("_size\n")
                    .append("#define ").append(bind).append("_pt (1.0 / u_").append(bind).append("_size)\n");
        }
        fragment.append(pass.body).append("\nvoid main() { fragColor = hook();\n");
        if (pass.isFinalToScreen()) fragment.append("fragColor.a = 1.0;\n");
        fragment.append("}\n");
        return fragment.toString();
    }

    /** Validate read-before-write dependencies and a single terminal MAIN output. */
    static void validateSameSizeChain(List<Pass> passes) {
        if (passes.isEmpty()) throw new IllegalArgumentException("no pass found in shader source");
        Set<String> available = new HashSet<>();
        available.add("MAIN");
        for (int i = 0; i < passes.size(); i++) {
            Pass pass = passes.get(i);
            if (pass.binds.isEmpty()) throw new IllegalArgumentException("pass has no inputs: " + pass.desc);
            for (String bind : pass.binds) {
                if (!available.contains(bind)) throw new IllegalArgumentException("unresolved bind: " + bind);
            }
            if (pass.widthRef != null && !available.contains(pass.widthRef)) {
                throw new IllegalArgumentException("unresolved WIDTH: " + pass.widthRef);
            }
            if (pass.heightRef != null && !available.contains(pass.heightRef)) {
                throw new IllegalArgumentException("unresolved HEIGHT: " + pass.heightRef);
            }
            if (pass.isFinalToScreen()) {
                if (i != passes.size() - 1) throw new IllegalArgumentException("MAIN output must be terminal");
            } else {
                available.add(pass.save);
            }
        }
        if (!passes.get(passes.size() - 1).isFinalToScreen()) {
            throw new IllegalArgumentException("missing final MAIN output");
        }
    }

    private static String textureName(String value) {
        if (!NAME.matcher(value).matches()) throw new IllegalArgumentException("invalid texture name: " + value);
        return "HOOKED".equals(value) ? "MAIN" : value;
    }

    private static String dimensionReference(String value, char component) {
        String suffix = "." + component;
        if (!value.endsWith(suffix)) throw new IllegalArgumentException("unsupported dimension: " + value);
        return textureName(value.substring(0, value.length() - suffix.length()));
    }
}
