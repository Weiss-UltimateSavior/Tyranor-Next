package com.core.gl;

import androidx.annotation.VisibleForTesting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parser for MAIN hooks and pure-Java GLSL pass metadata/codegen helpers. */
final class MpvHookShader {
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern HOOKED_SYMBOL =
            Pattern.compile("\\bHOOKED(?=_(?:texOff|tex|pos|size|pt)\\b)");
    private static final Pattern MAT_CALL_PATTERN = Pattern.compile("\\b(?:mat4|mat3x4)\\s*\\(");
    private static final Pattern HOOK_SIGNATURE_PATTERN =
            Pattern.compile("\\b(void|vec4)\\s+hook\\s*\\(\\s*(?:void\\s*)?\\)\\s*\\{");
    private static final Pattern VOID_HOOK_PATTERN =
            Pattern.compile("\\bvoid\\s+hook\\s*\\(\\s*(?:void\\s*)?\\)\\s*\\{");
    private static final Pattern COND_DIRECTIVE_PATTERN =
            Pattern.compile("^#\\s*(ifdef|ifndef|if|elif|else|endif)\\b(.*)$");
    private static final int COND_KNOWN_ACTIVE = 1;
    private static final int COND_KNOWN_INACTIVE = 2;
    private static final int COND_KNOWN_SKIPPED = 3;
    private static final int COND_UNKNOWN_ACTIVE = 4;
    private static final int COND_UNKNOWN_SKIPPED = 5;

    /**
     * Evaluates deterministic `#if 0` / `#if 1` / `#else` / `#endif` blocks and checks whether any
     * `hook()` signature is gated behind an unresolvable preprocessor conditional. Returns null
     * when directives are unbalanced/ambiguous or multiple/conditional `hook()` entries appear.
     */
    private static String resolveEffectiveBodyOrNull(String stripped, boolean requireSingleHook) {
        if (stripped == null) return null;
        int[] stack = new int[64];
        boolean[] hadElse = new boolean[64];
        int depth = 0;
        StringBuilder out = new StringBuilder(stripped.length());
        List<int[]> unknownCharSpans = new ArrayList<>();
        String[] lines = stripped.split("\n", -1);
        for (int idx = 0; idx < lines.length; idx++) {
            String line = lines[idx];
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                Matcher dm = COND_DIRECTIVE_PATTERN.matcher(trimmed);
                if (dm.matches()) {
                    String kw = dm.group(1);
                    String arg = dm.group(2).trim();
                    boolean parentActive = isCondActive(stack, depth);
                    boolean emitDirective = false;
                    if ("if".equals(kw) || "ifdef".equals(kw) || "ifndef".equals(kw)) {
                        if (depth >= stack.length) return null;
                        if (!parentActive) {
                            stack[depth] = isKnownInactive(stack, depth) ? COND_KNOWN_SKIPPED : COND_UNKNOWN_SKIPPED;
                        } else if ("if".equals(kw) && ("0".equals(arg) || "1".equals(arg))) {
                            stack[depth] = "1".equals(arg) ? COND_KNOWN_ACTIVE : COND_KNOWN_INACTIVE;
                        } else {
                            stack[depth] = COND_UNKNOWN_ACTIVE;
                            emitDirective = true;
                        }
                        hadElse[depth] = false;
                        depth++;
                    } else if ("elif".equals(kw)) {
                        if (depth == 0 || hadElse[depth - 1]) return null;
                        int top = stack[depth - 1];
                        if (top == COND_KNOWN_ACTIVE) {
                            stack[depth - 1] = COND_KNOWN_SKIPPED;
                        } else if (top == COND_KNOWN_INACTIVE) {
                            if ("1".equals(arg)) stack[depth - 1] = COND_KNOWN_ACTIVE;
                            else if (!"0".equals(arg)) {
                                stack[depth - 1] = COND_UNKNOWN_ACTIVE;
                                emitDirective = true;
                            }
                        } else if (top == COND_UNKNOWN_ACTIVE) {
                            emitDirective = true;
                        }
                    } else if ("else".equals(kw)) {
                        if (depth == 0 || hadElse[depth - 1]) return null;
                        hadElse[depth - 1] = true;
                        int top = stack[depth - 1];
                        if (top == COND_KNOWN_ACTIVE) stack[depth - 1] = COND_KNOWN_INACTIVE;
                        else if (top == COND_KNOWN_INACTIVE) stack[depth - 1] = COND_KNOWN_ACTIVE;
                        else if (top == COND_UNKNOWN_ACTIVE) emitDirective = true;
                    } else if ("endif".equals(kw)) {
                        if (depth == 0) return null;
                        emitDirective = stack[depth - 1] == COND_UNKNOWN_ACTIVE;
                        depth--;
                    }
                    if (emitDirective) out.append(line);
                    if (idx + 1 < lines.length) out.append('\n');
                    continue;
                }
            }
            if (isCondActive(stack, depth)) {
                if (hasUnknownCond(stack, depth)) {
                    int spanStart = out.length();
                    unknownCharSpans.add(new int[]{spanStart, spanStart + line.length()});
                }
                out.append(line);
            }
            if (idx + 1 < lines.length) out.append('\n');
        }
        if (depth != 0) return null;
        String effective = out.toString();
        Matcher hm = HOOK_SIGNATURE_PATTERN.matcher(effective);
        int hookCount = 0;
        while (hm.find()) {
            hookCount++;
            for (int[] span : unknownCharSpans) {
                if (span[1] > hm.start() && span[0] < hm.end()) {
                    return null;
                }
            }
        }
        if (requireSingleHook && hookCount != 1) {
            return null;
        }
        return effective;
    }

    private static boolean isCondActive(int[] stack, int depth) {
        for (int i = 0; i < depth; i++) {
            int st = stack[i];
            if (st == COND_KNOWN_INACTIVE || st == COND_KNOWN_SKIPPED || st == COND_UNKNOWN_SKIPPED) {
                return false;
            }
        }
        return true;
    }

    private static boolean isKnownInactive(int[] stack, int depth) {
        for (int i = 0; i < depth; i++) {
            if (stack[i] == COND_KNOWN_INACTIVE || stack[i] == COND_KNOWN_SKIPPED) return true;
        }
        return false;
    }

    private static boolean hasUnknownCond(int[] stack, int depth) {
        for (int i = 0; i < depth; i++) {
            if (stack[i] == COND_UNKNOWN_ACTIVE) return true;
        }
        return false;
    }

    static final class Pass {
        final String desc;
        /** HOOKED names and GLSL symbols are normalized to MAIN. */
        final List<String> binds;
        /** All render target texture names (MRT); contains single name for standard passes. */
        final List<String> saves;
        /** null or MAIN denotes the final display result; first save target for intermediate passes. */
        final String save;
        final String widthRef;
        final String heightRef;
        final String body;

        Pass(String desc, List<String> binds, List<String> saves,
             String widthRef, String heightRef, String body) {
            this.desc = desc;
            this.binds = Collections.unmodifiableList(new ArrayList<>(binds));
            this.saves = Collections.unmodifiableList(new ArrayList<>(saves));
            this.save = this.saves.isEmpty() ? null : this.saves.get(0);
            this.widthRef = widthRef;
            this.heightRef = heightRef;
            this.body = HOOKED_SYMBOL.matcher(body).replaceAll("MAIN");
        }

        boolean isFinalToScreen() {
            return saves.isEmpty() || (saves.size() == 1 && "MAIN".equals(saves.get(0)));
        }
    }

    private MpvHookShader() {}

    /** Unsupported mpv features fail explicitly rather than changing shader semantics. */
    static List<Pass> parse(String source) {
        if (source == null) throw new IllegalArgumentException("null shader source");
        List<Pass> passes = new ArrayList<>();
        List<String> binds = new ArrayList<>();
        List<String> saves = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        String desc = null, widthRef = null, heightRef = null;
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
                        passes.add(new Pass(desc, binds, saves, widthRef, heightRef, body.toString()));
                        binds = new ArrayList<>();
                        saves = new ArrayList<>();
                        body = new StringBuilder();
                        widthRef = heightRef = null;
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
                    case "SAVE":
                        boolean parsedSave = false;
                        for (String part : value.split("\\s+")) {
                            if (!part.isEmpty()) {
                                String s = textureName(part);
                                if (saves.contains(s)) {
                                    throw new IllegalArgumentException(
                                            "duplicate SAVE target in pass " + desc + ": " + s);
                                }
                                saves.add(s);
                                parsedSave = true;
                            }
                        }
                        if (!parsedSave) {
                            throw new IllegalArgumentException("empty SAVE directive in pass: " + desc);
                        }
                        break;
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
        if (inPass) passes.add(new Pass(desc, binds, saves, widthRef, heightRef, body.toString()));
        validateSameSizeChain(passes);
        return Collections.unmodifiableList(passes);
    }

    /**
     * Resolves GLSL backslash line continuations and strips line/block comments while preserving
     * non-continued newlines. Returns null (fail-closed) on null input or an unclosed block comment.
     */
    static String stripGlslCommentsOrNull(String source) {
        if (source == null) return null;
        StringBuilder spliced = new StringBuilder(source.length());
        for (int k = 0; k < source.length(); k++) {
            char ch = source.charAt(k);
            if (ch == '\\' && k + 1 < source.length()) {
                if (source.charAt(k + 1) == '\n') {
                    k++;
                    continue;
                }
                if (source.charAt(k + 1) == '\r' && k + 2 < source.length() && source.charAt(k + 2) == '\n') {
                    k += 2;
                    continue;
                }
            }
            spliced.append(ch);
        }
        int len = spliced.length();
        StringBuilder out = new StringBuilder(len);
        int i = 0;
        while (i < len) {
            char c = spliced.charAt(i);
            if (c == '/' && i + 1 < len) {
                char next = spliced.charAt(i + 1);
                if (next == '/') {
                    i += 2;
                    while (i < len) {
                        char ch = spliced.charAt(i);
                        if (ch == '\n' || ch == '\r') {
                            break;
                        }
                        i++;
                    }
                    continue;
                }
                if (next == '*') {
                    i += 2;
                    boolean closed = false;
                    while (i < len) {
                        if (spliced.charAt(i) == '*' && i + 1 < len && spliced.charAt(i + 1) == '/') {
                            i += 2;
                            closed = true;
                            break;
                        }
                        i++;
                    }
                    if (!closed) {
                        return null;
                    }
                    out.append(' ');
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    static boolean isVnsrPass(Pass pass) {
        if (pass == null) return false;
        if (pass.desc != null && pass.desc.startsWith("VN-SR")) return true;
        if (pass.saves != null) {
            for (String s : pass.saves) {
                if (s.startsWith("vnsr_")) return true;
            }
        }
        if (pass.binds != null) {
            for (String b : pass.binds) {
                if (b.startsWith("vnsr_")) return true;
            }
        }
        return false;
    }

    static boolean isVnsrChain(List<Pass> passes) {
        if (passes == null || passes.isEmpty()) return false;
        Pass finalPass = passes.get(passes.size() - 1);
        if (!finalPass.isFinalToScreen()) return false;
        boolean isVnsrReconstruction = (finalPass.desc != null && finalPass.desc.contains("VN-SR"))
                || (finalPass.body != null && finalPass.body.contains("vnsr_output_0"));
        if (!isVnsrReconstruction) return false;
        for (int i = 0; i < passes.size() - 1; i++) {
            if (!isVnsrPass(passes.get(i))) return false;
        }
        return true;
    }

    static boolean isCnnConvolutionPass(Pass pass) {
        if (pass == null) return false;
        String stripped = stripGlslCommentsOrNull(pass.body);
        if (stripped == null) return false;
        String effective = resolveEffectiveBodyOrNull(stripped, false);
        return effective != null && MAT_CALL_PATTERN.matcher(effective).find();
    }

    /**
     * Returns true only for genuine multi-pass convolution CNN chains (Anime4K Restore CNN / VN-SR),
     * excluding traditional post-processing shaders (Thin_HQ, Darken_HQ, Deblur_DoG, Denoise, CAS).
     */
    static boolean isCnnConvolutionChain(List<Pass> passes) {
        if (passes == null || passes.size() <= 1) return false;
        int lastIdx = passes.size() - 1;
        Pass finalPass = passes.get(lastIdx);
        if (finalPass == null || !finalPass.isFinalToScreen()) return false;
        for (int i = 0; i < lastIdx; i++) {
            if (!isCnnConvolutionPass(passes.get(i))) {
                return false;
            }
        }
        return isVnsrChain(passes) || isCnnConvolutionPass(finalPass);
    }

    static boolean hasVoidHook(String body) {
        String stripped = stripGlslCommentsOrNull(body);
        String effective = resolveEffectiveBodyOrNull(stripped, true);
        if (effective == null) {
            throw new IllegalArgumentException("unverified or ambiguous hook() entry point");
        }
        return VOID_HOOK_PATTERN.matcher(effective).find();
    }

    /** Emits GLES fragment shader source for an mpv hook pass using reciprocal division for pt. */
    @VisibleForTesting
    static String fragmentSource(Pass pass) {
        return fragmentSource(pass, false, false);
    }

    /**
     * Emits GLES fragment shader source for an mpv hook pass.
     * Coordinates, sizes, texel-step vectors, and sampler declarations stay IEEE highp float;
     * when tieredPrecisionEnabled is true, convolution passes (mat4/mat3x4) switch to mediump float
     * before the pass body.
     */
    static String fragmentSource(Pass pass, boolean usePtUniform, boolean tieredPrecisionEnabled) {
        if (pass == null || pass.body == null) {
            throw new IllegalArgumentException("null shader pass");
        }
        StringBuilder fragment = new StringBuilder(pass.body.length() + 512);
        fragment.append("#version 300 es\nprecision highp float;\nprecision highp int;\nprecision highp sampler2D;\n");
        fragment.append("in highp vec2 vPos;\n");
        if (pass.saves.size() > 1) {
            for (int i = 0; i < pass.saves.size(); i++) {
                fragment.append("layout(location = ").append(i).append(") out vec4 fragColor").append(i).append(";\n");
            }
            fragment.append("#define fragColor fragColor0\n");
        } else {
            fragment.append("layout(location = 0) out vec4 fragColor;\n#define fragColor0 fragColor\n");
        }
        fragment.append("uniform highp vec2 u_OUTPUT_size;\n#define OUTPUT_size u_OUTPUT_size\n");
        fragment.append("uniform bool u_FLIP_Y;\nvec2 hookCoord(vec2 p) { return vec2(p.x, u_FLIP_Y ? 1.0-p.y : p.y); }\n");

        for (int i = 0; i < pass.binds.size(); i++) {
            String bind = pass.binds.get(i);
            fragment.append("uniform sampler2D u_").append(bind).append(";\n")
                    .append("uniform highp vec2 u_").append(bind).append("_size;\n");

            String decodeExpr = "texture(u_" + bind + ", hookCoord(pos))";

            if (usePtUniform) {
                fragment.append("uniform highp vec2 u_").append(bind).append("_pt;\n")
                        .append("vec4 ").append(bind).append("_tex(vec2 pos) { return ").append(decodeExpr).append("; }\n")
                        .append("vec4 ").append(bind).append("_texOff(vec2 off) { return ")
                        .append(bind).append("_tex(hookCoord(vPos) + off * u_").append(bind).append("_pt); }\n")
                        .append("#define ").append(bind).append("_pos hookCoord(vPos)\n")
                        .append("#define ").append(bind).append("_size u_").append(bind).append("_size\n")
                        .append("#define ").append(bind).append("_pt u_").append(bind).append("_pt\n");
            } else {
                fragment.append("vec4 ").append(bind).append("_tex(vec2 pos) { return ").append(decodeExpr).append("; }\n")
                        .append("vec4 ").append(bind).append("_texOff(vec2 off) { return ")
                        .append(bind).append("_tex(hookCoord(vPos) + off / u_").append(bind).append("_size); }\n")
                        .append("#define ").append(bind).append("_pos hookCoord(vPos)\n")
                        .append("#define ").append(bind).append("_size u_").append(bind).append("_size\n")
                        .append("#define ").append(bind).append("_pt (1.0 / u_").append(bind).append("_size)\n");
            }
        }
        // P1 Tiered FP16 ALU Acceleration: start the header in highp float for coordinates and sampling,
        // then switch convolution passes (mat4/mat3x4 outside comments) to mediump float before pass.body.
        if (tieredPrecisionEnabled && isCnnConvolutionPass(pass)) {
            fragment.append("precision mediump float;\n");
        }
        fragment.append(pass.body).append("\nvoid main() {\n");
        if (hasVoidHook(pass.body)) {
            fragment.append("    hook();\n");
        } else {
            fragment.append("    fragColor = hook();\n");
        }
        if (pass.isFinalToScreen()) {
            fragment.append("    fragColor.a = 1.0;\n");
        }
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
                hasVoidHook(pass.body);
                if (i != passes.size() - 1) throw new IllegalArgumentException("MAIN output must be terminal");
            } else {
                // A vec4 hook() writes only fragColor0; with multiple SAVE targets the
                // remaining MRT locations would keep undefined content that later passes
                // sample, and a void hook() that leaves any fragColorN unwritten has the
                // same hazard. Fail closed at parse time instead of emitting a malformed pass.
                if (pass.saves.size() > 1 && (!hasVoidHook(pass.body)
                        || !multiSaveWritesAllTargets(pass.body, pass.saves.size()))) {
                    throw new IllegalArgumentException(
                            "multi-SAVE pass must write every target via void hook(): " + pass.desc);
                }
                // GraphicsShaderTransforms resolves a MAIN bind to the raw input texture,
                // so a non-terminal MAIN write would make later MAIN binds silently read
                // the original input instead of the pass output. Only the single terminal
                // MAIN SAVE (or a SAVE-less terminal pass) is a legal MAIN write.
                if (pass.saves.contains("MAIN")) {
                    throw new IllegalArgumentException(
                            "only the terminal pass may SAVE MAIN: " + pass.desc);
                }
                for (String s : pass.saves) {
                    available.add(s);
                }
            }
        }
        if (!passes.get(passes.size() - 1).isFinalToScreen()) {
            throw new IllegalArgumentException("missing final MAIN output");
        }
    }

    private static final Pattern MULTI_SAVE_UNSUPPORTED_FLOW = Pattern.compile(
            "\\b(?:return|discard|if|else|for|while|do|switch|break|continue)\\b");
    private static final Pattern MULTI_SAVE_FRAGCOLOR_DECL = Pattern.compile(
            "\\b(?:const\\s+|(?:highp|mediump|lowp)\\s+)?[A-Za-z_][A-Za-z0-9_]*\\s+(?:[^;{}]*,\\s*)?fragColor\\d*\\b");
    private static final Pattern MULTI_SAVE_FRAGCOLOR_DEFINE = Pattern.compile(
            "^[ \\t\\f]*#[ \\t\\f]*(?:define|undef)[ \\t\\f]+fragColor\\d*\\b", Pattern.MULTILINE);
    private static final Pattern MULTI_SAVE_CONTROL_FLOW_DEFINE = Pattern.compile(
            "^[ \\t\\f]*#[ \\t\\f]*define[ \\t\\f]+[A-Za-z_][A-Za-z0-9_]*(?:\\([^)]*\\)(.*)|(?:[ \\t\\f]+(.*))?)$");
    private static final Pattern MULTI_SAVE_FRAGCOLOR_WRITE = Pattern.compile(
            "(?:^|[;{])\\s*fragColor(\\d+)\\s*=(?!=)");

    private static boolean multiSaveWritesAllTargets(String body, int saveCount) {
        String stripped = stripGlslCommentsOrNull(body);
        String effective = resolveEffectiveBodyOrNull(stripped, true);
        if (effective == null || MULTI_SAVE_FRAGCOLOR_DEFINE.matcher(effective).find()) return false;
        for (String line : effective.split("\\r\\n|[\\r\\n]", -1)) {
            Matcher cfDef = MULTI_SAVE_CONTROL_FLOW_DEFINE.matcher(line);
            if (cfDef.matches()) {
                String rhs = cfDef.group(1) != null ? cfDef.group(1) : cfDef.group(2);
                if (rhs != null && MULTI_SAVE_UNSUPPORTED_FLOW.matcher(rhs).find()) {
                    return false;
                }
            }
        }
        Matcher m = VOID_HOOK_PATTERN.matcher(effective);
        if (!m.find()) return false;
        int openBrace = m.end() - 1;
        int depth = 0;
        int closeBrace = -1;
        for (int pos = openBrace; pos < effective.length(); pos++) {
            char c = effective.charAt(pos);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    closeBrace = pos;
                    break;
                }
            }
        }
        if (closeBrace < 0) return false;
        String hookInner = effective.substring(openBrace + 1, closeBrace);
        for (String line : hookInner.split("\\r\\n|[\\r\\n]")) {
            if (line.trim().startsWith("#")) return false;
        }
        if (MULTI_SAVE_UNSUPPORTED_FLOW.matcher(hookInner).find()) return false;
        if (MULTI_SAVE_FRAGCOLOR_DECL.matcher(hookInner).find()) return false;
        StringBuilder topLevelInner = new StringBuilder(hookInner.length());
        int innerDepth = 0;
        for (int pos = 0; pos < hookInner.length(); pos++) {
            char c = hookInner.charAt(pos);
            if (c == '{') {
                innerDepth++;
                continue;
            }
            if (c == '}') {
                if (innerDepth == 0) return false;
                innerDepth--;
                continue;
            }
            if (innerDepth == 0) topLevelInner.append(c);
        }
        boolean[] written = new boolean[saveCount];
        Matcher w = MULTI_SAVE_FRAGCOLOR_WRITE.matcher(topLevelInner);
        while (w.find()) {
            int slot;
            try {
                slot = Integer.parseInt(w.group(1));
            } catch (NumberFormatException nfe) {
                return false;
            }
            if (slot >= 0 && slot < saveCount) {
                written[slot] = true;
            }
        }
        for (boolean ok : written) {
            if (!ok) return false;
        }
        return true;
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
