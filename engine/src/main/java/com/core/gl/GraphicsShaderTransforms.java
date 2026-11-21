package com.core.gl;

import androidx.annotation.VisibleForTesting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java GLSL pass analysis, MRT fusion, and coordinate/convolution transform utilities.
 * Decoupled from Android GL APIs so optimization and shader codegen stay acyclic and unit-testable.
 */
final class GraphicsShaderTransforms {

    /** Immutable per-pipeline configuration snapshotted at pipeline setup/compile time. */
    static final class Config {
        static final Config DEFAULT = new Config(true, true);

        final boolean tieredPrecisionEnabled;
        final boolean runtimeMrtFusionEnabled;

        Config(boolean tieredPrecisionEnabled,
               boolean runtimeMrtFusionEnabled) {
            this.tieredPrecisionEnabled = tieredPrecisionEnabled;
            this.runtimeMrtFusionEnabled = runtimeMrtFusionEnabled;
        }
    }

    private GraphicsShaderTransforms() {}

    /** u_FLIP_Y flips shader-logical sampling only when the declared row order and the host's physical rows disagree. */
    static int flipYUniform(boolean declaredTopLeft, boolean inputTopLeft) {
        return declaredTopLeft != inputTopLeft ? 1 : 0;
    }

    private static final Pattern HOOK_SIG_PATTERN = Pattern.compile("\\b(vec4|void)\\s+hook\\s*\\(\\s*(?:void\\s*)?\\)\\s*\\{");
    private static final Pattern COMPLEX_FLOW_OR_RETURN_PATTERN =
            Pattern.compile("\\b(if|else|switch|case|default|while|for|do|discard|break|continue|return)\\b");
    private static final Pattern RESERVED_FRAGCOLOR_PATTERN = Pattern.compile("\\bfragColor\\d*\\b");
    private static final Pattern PREPROC_LINE_MACRO_PATTERN = Pattern.compile("\\b__LINE__\\b");
    private static final Pattern HOIST_HOST_ENV_IDENT_PATTERN = Pattern.compile("\\b(?:vPos|hookCoord|u_FLIP_Y)\\b");
    private static final Pattern FINAL_RETURN_PATTERN = Pattern.compile("\\breturn\\b([\\s\\S]*?);\\s*$");
    private static final Pattern HORIZONTAL_SPACE_PATTERN = Pattern.compile("[ \\t\\f\\r]+");

    // VN-SR header & body grammar tokens
    private static final Pattern VNSR_SAMPLE_DECL_PATTERN = Pattern.compile(
            "vec4\\s+sample_(\\d+)\\s*\\(\\s*vec2\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\)\\s*\\{\\s*"
                    + "return\\s+([A-Za-z_][A-Za-z0-9_]*)_tex\\s*\\(\\s*"
                    + "([A-Za-z_][A-Za-z0-9_]*)_pos\\s*\\+\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\*\\s*"
                    + "([A-Za-z_][A-Za-z0-9_]*)_pt\\s*\\)\\s*;\\s*\\}");
    private static final Pattern VNSR_BIAS_STMT_PATTERN = Pattern.compile(
            "vec4\\s+v(\\d+)\\s*=\\s*vec4\\(([^)]+)\\)\\s*;");
    private static final Pattern VNSR_SCRATCH_S_STMT_PATTERN = Pattern.compile("vec4\\s+s\\s*;");
    private static final Pattern VNSR_TAP_STMT_PATTERN = Pattern.compile(
            "s\\s*=\\s*sample_(\\d+)\\s*\\(\\s*vec2\\s*\\(\\s*"
                    + "([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?)\\s*,\\s*"
                    + "([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?)\\s*\\)\\s*\\)\\s*;");
    private static final Pattern VNSR_MAT4_STMT_PATTERN = Pattern.compile(
            "v(\\d+)\\s*\\+=\\s*mat4\\(([^)]+)\\)\\s*\\*\\s*s\\s*;");
    private static final Pattern VNSR_FRAGCOLOR_STMT_PATTERN = Pattern.compile(
            "fragColor(\\d+)\\s*=\\s*(max\\s*\\(\\s*v(\\d+)\\s*,\\s*0\\.0+\\s*\\)|v(\\d+))\\s*;");
    private static final Pattern VNSR_RESERVED_NAMES_PATTERN =
            Pattern.compile("\\b(?:_p_[A-Za-z0-9_]+|_s[0-2])\\b");

    // 1x1 CReLU aggregation (#define g_N ...)
    private static final Pattern AGG_DEFINE_LINE_PATTERN = Pattern.compile(
            "^#define\\s+g_(\\d+)\\s+\\(\\s*max\\s*\\(\\s*(-?)\\(\\s*([A-Za-z_][A-Za-z0-9_]*)_tex\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_]*)_pos\\s*\\)\\s*\\)\\s*,\\s*0\\.0+\\s*\\)\\s*\\)$");
    private static final Pattern AGG_ANY_TOKEN_PATTERN = Pattern.compile("\\bg_[A-Za-z0-9_]*\\b");
    private static final Pattern AGG_RESERVED_NAMES_PATTERN = Pattern.compile("\\b(?:_raw_\\d+|_g_\\d+)\\b");

    // 3x3 CReLU deduplication (#define go_K ...)
    private static final Pattern CRELU_DEFINE_LINE_PATTERN = Pattern.compile(
            "^#define\\s+go_([0-3])\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*,\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\)\\s*(.+)$");
    private static final Pattern CRELU_PLAIN_RHS_PATTERN = Pattern.compile(
            "^\\(\\s*([A-Za-z_][A-Za-z0-9_]*)_texOff\\s*\\(\\s*vec2\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*,\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\)\\s*\\)\\s*\\)$");
    private static final Pattern CRELU_MAX_RHS_PATTERN = Pattern.compile(
            "^\\(\\s*max\\s*\\(\\s*(-?)\\(\\s*([A-Za-z_][A-Za-z0-9_]*)_texOff\\s*\\(\\s*vec2\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*,\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\)\\s*\\)\\s*\\)\\s*,\\s*0\\.0+\\s*\\)\\s*\\)$");
    private static final Pattern GO_ANY_CALL_OR_IDENT_PATTERN =
            Pattern.compile("\\bgo_[A-Za-z0-9_]*\\b(?:\\s*\\([^()]*\\))?");
    private static final Pattern GO_VALID_CALL_PATTERN = Pattern.compile(
            "^go_([0-3])\\s*\\(\\s*(-?1(?:\\.0+)?|0(?:\\.0+)?)\\s*,\\s*(-?1(?:\\.0+)?|0(?:\\.0+)?)\\s*\\)$");
    private static final Pattern CRELU_RESERVED_NAMES_PATTERN =
            Pattern.compile("\\b(?:_t[01]|_go_[0-3])_[A-Za-z0-9_]*\\b");

    private static final String[][] OFFSETS_3X3 = {
            {"-1.0", "-1.0", "m1_m1"},
            {"-1.0", "0.0",  "m1_0"},
            {"-1.0", "1.0",  "m1_1"},
            {"0.0",  "-1.0", "0_m1"},
            {"0.0",  "0.0",  "0_0"},
            {"0.0",  "1.0",  "0_1"},
            {"1.0",  "-1.0", "1_m1"},
            {"1.0",  "0.0",  "1_0"},
            {"1.0",  "1.0",  "1_1"}
    };

    private static String stripGlslCommentsOrNull(String source) {
        return MpvHookShader.stripGlslCommentsOrNull(source);
    }

    static String generateFragmentShader(MpvHookShader.Pass pass,
                                         boolean usePtUniform,
                                         boolean tieredPrecisionEnabled) {
        return MpvHookShader.fragmentSource(pass, usePtUniform, tieredPrecisionEnabled);
    }

    private static final class HookParts {
        final boolean isVoid;
        final String header;
        final String inner;

        HookParts(boolean isVoid, String header, String inner) {
            this.isVoid = isVoid;
            this.header = header;
            this.inner = inner;
        }
    }

    /** Parses a single top-level vec4/void hook() function, failing closed if extra code or extra hook() exists. */
    private static HookParts parseSingleHookFunction(String strippedBody) {
        if (strippedBody == null) return null;
        Matcher m = HOOK_SIG_PATTERN.matcher(strippedBody);
        if (!m.find()) return null;
        boolean isVoid = "void".equals(m.group(1));
        int openBrace = m.end() - 1;
        int depth = 1;
        int pos = openBrace + 1;
        int len = strippedBody.length();
        int closeBrace = -1;
        while (pos < len) {
            char ch = strippedBody.charAt(pos);
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    closeBrace = pos;
                    break;
                }
            }
            pos++;
        }
        if (closeBrace < 0) return null;
        for (int i = closeBrace + 1; i < len; i++) {
            if (!Character.isWhitespace(strippedBody.charAt(i))) {
                return null;
            }
        }
        String header = strippedBody.substring(0, m.start());
        String inner = strippedBody.substring(openBrace + 1, closeBrace);
        return new HookParts(isVoid, header, inner);
    }

    private static String normalizeHeaderPreservingNewlines(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder(text.length());
        for (String line : lines) {
            String collapsed = HORIZONTAL_SPACE_PATTERN.matcher(line.trim()).replaceAll(" ");
            if (collapsed.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(collapsed);
        }
        return sb.toString();
    }

    private static final Pattern PREPROCESSOR_DEFINE_PATTERN =
            Pattern.compile("^#define\\s+[A-Za-z_][A-Za-z0-9_]*(?:\\([^)]*\\))?(?:\\s+(.*))?$");
    private static final Pattern HOOK_REF_PATTERN = Pattern.compile("\\bhook\\b");

    private static boolean hasPreprocessorDirective(String text) {
        if (text == null || text.isEmpty()) return false;
        for (String line : text.split("\\R")) {
            if (line.trim().startsWith("#")) return true;
        }
        return false;
    }

    /**
     * Proves a pass header contains no mutable global variables, conditional preprocessor
     * directives, control-flow macros, or helper functions whose state/control could leak
     * between fused invocations: only top-level `const` declarations and expression-only
     * `#define` macros without `;`, `{`, `}`, `hook`, or control-flow keywords are accepted.
     */
    private static boolean isSafeReadOnlyHeader(String strippedHeader) {
        if (strippedHeader == null || strippedHeader.isEmpty()) return true;
        StringBuilder stmt = new StringBuilder();
        StringBuilder directive = new StringBuilder();
        boolean inPreprocessor = false;
        String[] lines = strippedHeader.split("\n", -1);
        for (String line : lines) {
            String trimmed = line.trim();
            if (inPreprocessor) {
                boolean cont = trimmed.endsWith("\\");
                String part = cont ? trimmed.substring(0, trimmed.length() - 1).trim() : trimmed;
                if (!part.isEmpty()) directive.append(' ').append(part);
                if (!cont) {
                    inPreprocessor = false;
                    if (!isSafeReadOnlyDefine(directive.toString().trim())) return false;
                    directive.setLength(0);
                }
                continue;
            }
            if (trimmed.isEmpty()) continue;
            if (trimmed.startsWith("#")) {
                if (!stmt.toString().trim().isEmpty()) return false;
                boolean cont = trimmed.endsWith("\\");
                String part = cont ? trimmed.substring(0, trimmed.length() - 1).trim() : trimmed;
                directive.setLength(0);
                directive.append(part);
                if (cont) {
                    inPreprocessor = true;
                } else {
                    if (!isSafeReadOnlyDefine(directive.toString().trim())) return false;
                    directive.setLength(0);
                }
                continue;
            }
            if (trimmed.indexOf('{') >= 0 || trimmed.indexOf('}') >= 0) {
                return false;
            }
            stmt.append(trimmed).append(' ');
            int semi;
            while ((semi = stmt.indexOf(";")) >= 0) {
                String single = stmt.substring(0, semi).trim();
                stmt.delete(0, semi + 1);
                if (!single.isEmpty() && !single.matches("^const\\b[\\s\\S]*")) {
                    return false;
                }
            }
        }
        return !inPreprocessor && stmt.toString().trim().isEmpty();
    }

    private static boolean isSafeReadOnlyDefine(String directiveLine) {
        Matcher m = PREPROCESSOR_DEFINE_PATTERN.matcher(directiveLine);
        if (!m.matches()) return false;
        String body = m.group(1);
        if (body == null || body.isEmpty()) return true;
        if (body.indexOf(';') >= 0 || body.indexOf('{') >= 0 || body.indexOf('}') >= 0
                || body.indexOf('#') >= 0) {
            return false;
        }
        return !COMPLEX_FLOW_OR_RETURN_PATTERN.matcher(body).find()
                && !HOOK_REF_PATTERN.matcher(body).find();
    }

    static boolean canFusePasses(MpvHookShader.Pass passA, MpvHookShader.Pass passB) {
        if (passA == null || passB == null) return false;
        if (passA.isFinalToScreen() || passB.isFinalToScreen()) return false;
        if (passA.saves.size() != 1 || passB.saves.size() != 1) return false;
        if (passA.save == null || passB.save == null || passA.save.equals(passB.save)) return false;
        if (!passA.binds.equals(passB.binds)) return false;
        if (passB.binds.contains(passA.save) || passA.binds.contains(passB.save)) return false;
        if (!Objects.equals(passA.widthRef, passB.widthRef) || !Objects.equals(passA.heightRef, passB.heightRef)) {
            return false;
        }
        // Never fuse a highp non-convolution pass with a mediump convolution pass:
        // MpvHookShader switches the entire fused body to mediump whenever either side matches.
        if (MpvHookShader.isCnnConvolutionPass(passA) != MpvHookShader.isCnnConvolutionPass(passB)) {
            return false;
        }
        String strippedA = stripGlslCommentsOrNull(passA.body);
        String strippedB = stripGlslCommentsOrNull(passB.body);
        if (strippedA == null || strippedB == null) return false;

        HookParts partsA = parseSingleHookFunction(strippedA);
        HookParts partsB = parseSingleHookFunction(strippedB);
        if (partsA == null || partsB == null || partsA.isVoid || partsB.isVoid) return false;
        if (hasPreprocessorDirective(partsA.inner) || hasPreprocessorDirective(partsB.inner)) return false;
        if (!normalizeHeaderPreservingNewlines(partsA.header).equals(normalizeHeaderPreservingNewlines(partsB.header))) {
            return false;
        }
        if (!isSafeReadOnlyHeader(partsA.header)) return false;
        if (RESERVED_FRAGCOLOR_PATTERN.matcher(strippedA).find()
                || RESERVED_FRAGCOLOR_PATTERN.matcher(strippedB).find()
                || PREPROC_LINE_MACRO_PATTERN.matcher(strippedA).find()
                || PREPROC_LINE_MACRO_PATTERN.matcher(strippedB).find()) {
            return false;
        }
        return extractTerminalReturnParts(partsA.inner) != null
                && extractTerminalReturnParts(partsB.inner) != null;
    }

    private static String[] extractTerminalReturnParts(String inner) {
        Matcher ret = FINAL_RETURN_PATTERN.matcher(inner);
        if (!ret.find()) return null;
        String prefix = inner.substring(0, ret.start());
        if (COMPLEX_FLOW_OR_RETURN_PATTERN.matcher(prefix).find()) {
            return null;
        }
        String expr = ret.group(1).trim();
        if (expr.isEmpty() || expr.indexOf(';') >= 0 || !hasBalancedParens(expr)) {
            return null;
        }
        if (COMPLEX_FLOW_OR_RETURN_PATTERN.matcher(expr).find()) {
            return null;
        }
        return new String[]{prefix, expr};
    }

    private static boolean hasBalancedParens(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth < 0) return false;
            }
        }
        return depth == 0;
    }

    private static String rewriteReturnBlock(String inner, String fragColorTarget) {
        String[] parts = extractTerminalReturnParts(inner);
        if (parts == null) return null;
        StringBuilder block = new StringBuilder(inner.length() + 48);
        block.append("    {\n").append(parts[0]);
        if (!parts[0].isEmpty() && parts[0].charAt(parts[0].length() - 1) != '\n') {
            block.append('\n');
        }
        block.append("    ").append(fragColorTarget).append(" = (").append(parts[1]).append(");\n    }");
        return block.toString();
    }

    private static MpvHookShader.Pass fusePair(MpvHookShader.Pass passA, MpvHookShader.Pass passB) {
        String strippedA = stripGlslCommentsOrNull(passA.body);
        String strippedB = stripGlslCommentsOrNull(passB.body);
        HookParts partsA = parseSingleHookFunction(strippedA);
        HookParts partsB = parseSingleHookFunction(strippedB);
        if (partsA == null || partsB == null) return null;

        String block0 = rewriteReturnBlock(partsA.inner, "fragColor0");
        String block1 = rewriteReturnBlock(partsB.inner, "fragColor1");
        if (block0 == null || block1 == null) return null;

        String desc = passA.desc + " + " + passB.desc;
        List<String> saves = new ArrayList<>(2);
        saves.add(passA.save);
        saves.add(passB.save);

        StringBuilder fusedBody = new StringBuilder(partsA.header.length() + block0.length() + block1.length() + 48);
        fusedBody.append(partsA.header);
        if (!partsA.header.isEmpty() && partsA.header.charAt(partsA.header.length() - 1) != '\n') {
            fusedBody.append('\n');
        }
        fusedBody.append("void hook() {\n");
        fusedBody.append(block0).append('\n');
        fusedBody.append(block1).append('\n');
        fusedBody.append("}\n");

        return new MpvHookShader.Pass(desc, passA.binds, saves, passA.widthRef, passA.heightRef, fusedBody.toString());
    }

    static List<MpvHookShader.Pass> fuseCompatiblePasses(List<MpvHookShader.Pass> passes) {
        if (passes == null || passes.size() <= 1) return passes;
        List<MpvHookShader.Pass> fused = new ArrayList<>(passes.size());
        int i = 0;
        while (i < passes.size()) {
            MpvHookShader.Pass passA = passes.get(i);
            if (i + 1 < passes.size()) {
                MpvHookShader.Pass passB = passes.get(i + 1);
                if (canFusePasses(passA, passB)) {
                    MpvHookShader.Pass fusedPass = fusePair(passA, passB);
                    if (fusedPass != null) {
                        fused.add(fusedPass);
                        i += 2;
                        continue;
                    }
                }
            }
            fused.add(passA);
            i++;
        }
        return fused;
    }

    private static String coordTag(String c) {
        if (c.startsWith("-1")) return "m1";
        if (c.startsWith("0")) return "0";
        if (c.startsWith("1")) return "1";
        return c.replace('-', 'm').replace('.', '_');
    }

    private static String vnsrCoordVar(int x, int y) {
        String sx = x < 0 ? "m" + (-x) : String.valueOf(x);
        String sy = y < 0 ? "m" + (-y) : String.valueOf(y);
        return "_p_" + sx + "_" + sy;
    }

    private static final class VnsrMatAccum {
        final String destName;
        final String matExpr;
        final boolean isMat3x4;

        VnsrMatAccum(String destName, String matExpr, boolean isMat3x4) {
            this.destName = destName;
            this.matExpr = matExpr;
            this.isMat3x4 = isMat3x4;
        }
    }

    private static final class VnsrTap {
        final int sampleIdx;
        final String coordVar;
        final List<VnsrMatAccum> accumList = new ArrayList<>();

        VnsrTap(int sampleIdx, String coordVar) {
            this.sampleIdx = sampleIdx;
            this.coordVar = coordVar;
        }
    }

    @VisibleForTesting
    static MpvHookShader.Pass optimizePassBody(MpvHookShader.Pass pass) {
        return optimizePassBody(pass, pass != null && pass.binds != null && pass.binds.size() <= 1);
    }

    private static MpvHookShader.Pass optimizePassBody(MpvHookShader.Pass pass, boolean sameResBinds) {
        if (pass == null || pass.body == null) return pass;
        String stripped = stripGlslCommentsOrNull(pass.body);
        if (stripped == null) return pass;

        if (VNSR_SAMPLE_DECL_PATTERN.matcher(stripped).find() && stripped.contains("mat4(")) {
            return optimizePassBodyVnsrConv(pass, stripped, sameResBinds);
        }
        if (stripped.contains("#define g_0") && stripped.contains("#define g_1")) {
            return optimizePassBodyAggregation1x1(pass, stripped);
        }
        if (stripped.contains("#define go_0")) {
            return optimizePassBodyCrelu3x3(pass, stripped);
        }
        return pass;
    }

    private static int skipWs(String s, int pos) {
        int len = s.length();
        while (pos < len && Character.isWhitespace(s.charAt(pos))) {
            pos++;
        }
        return pos;
    }

    private static boolean parseFiniteFloats(String csv, int expectedCount, float[] outValues) {
        String[] tokens = csv.split(",");
        if (tokens.length != expectedCount) return false;
        for (int i = 0; i < expectedCount; i++) {
            try {
                float v = Float.parseFloat(tokens[i].trim());
                if (!Float.isFinite(v)) return false;
                outValues[i] = v;
            } catch (NumberFormatException nfe) {
                return false;
            }
        }
        return true;
    }

    private static MpvHookShader.Pass optimizePassBodyVnsrConv(MpvHookShader.Pass pass, String stripped,
                                                               boolean sameResBinds) {
        if (pass.binds.isEmpty() || pass.saves.isEmpty()) return pass;
        if (!sameResBinds && pass.binds.size() > 1) return pass;
        if (VNSR_RESERVED_NAMES_PATTERN.matcher(stripped).find()) return pass;

        HookParts parts = parseSingleHookFunction(stripped);
        if (parts == null || !parts.isVoid) return pass;

        int bindCount = pass.binds.size();
        int saveCount = pass.saves.size();

        // Strictly validate EVERY token in parts.header (sample_k helpers only, matching pass.binds).
        boolean[] definedSample = new boolean[bindCount];
        Matcher sampleMatcher = VNSR_SAMPLE_DECL_PATTERN.matcher(parts.header);
        int pos = skipWs(parts.header, 0);
        while (pos < parts.header.length()) {
            sampleMatcher.region(pos, parts.header.length());
            if (!sampleMatcher.lookingAt()) {
                return pass;
            }
            int sampleIdx;
            try {
                sampleIdx = Integer.parseInt(sampleMatcher.group(1));
            } catch (NumberFormatException nfe) {
                return pass;
            }
            if (sampleIdx < 0 || sampleIdx >= bindCount || definedSample[sampleIdx]) {
                return pass;
            }
            String argName = sampleMatcher.group(2);
            String bindTex = sampleMatcher.group(3);
            String bindPos = sampleMatcher.group(4);
            String usedArg = sampleMatcher.group(5);
            String bindPt = sampleMatcher.group(6);
            String expectedBind = pass.binds.get(sampleIdx);
            if (!argName.equals(usedArg)
                    || hasShadowedSamplingEnv(argName, Collections.singletonList(expectedBind))
                    || !expectedBind.equals(bindTex)
                    || !expectedBind.equals(bindPos)
                    || !expectedBind.equals(bindPt)) {
                return pass;
            }
            definedSample[sampleIdx] = true;
            pos = skipWs(parts.header, sampleMatcher.end());
        }

        // Strictly consume parts.inner statement-by-statement: biases -> vec4 s; -> taps/mat4 -> fragColorK.
        String inner = parts.inner;
        pos = skipWs(inner, 0);

        String[] biases = new String[saveCount];
        String[] accNames = new String[saveCount];
        boolean[] biasSeen = new boolean[saveCount];
        float[] tmp4 = new float[4];
        Matcher biasMatcher = VNSR_BIAS_STMT_PATTERN.matcher(inner);
        while (pos < inner.length()) {
            biasMatcher.region(pos, inner.length());
            if (!biasMatcher.lookingAt()) {
                break;
            }
            String vGroup = biasMatcher.group(1);
            int vIdx;
            try {
                vIdx = Integer.parseInt(vGroup);
            } catch (NumberFormatException nfe) {
                return pass;
            }
            if (vIdx < 0 || vIdx >= saveCount || biasSeen[vIdx]) {
                return pass;
            }
            if (!parseFiniteFloats(biasMatcher.group(2), 4, tmp4)) {
                return pass;
            }
            biasSeen[vIdx] = true;
            accNames[vIdx] = "v" + vGroup;
            biases[vIdx] = biasMatcher.group().trim();
            pos = skipWs(inner, biasMatcher.end());
        }
        for (int s = 0; s < saveCount; s++) {
            if (!biasSeen[s]) return pass;
        }

        Matcher sDeclMatcher = VNSR_SCRATCH_S_STMT_PATTERN.matcher(inner);
        sDeclMatcher.region(pos, inner.length());
        if (!sDeclMatcher.lookingAt()) {
            return pass;
        }
        pos = skipWs(inner, sDeclMatcher.end());

        List<VnsrTap> taps = new ArrayList<>();
        Matcher tapMatcher = VNSR_TAP_STMT_PATTERN.matcher(inner);
        Matcher matMatcher = VNSR_MAT4_STMT_PATTERN.matcher(inner);
        float[] mat16 = new float[16];
        while (pos < inner.length()) {
            tapMatcher.region(pos, inner.length());
            if (!tapMatcher.lookingAt()) {
                break;
            }
            int sampleIdx;
            float dxF, dyF;
            try {
                sampleIdx = Integer.parseInt(tapMatcher.group(1));
                dxF = Float.parseFloat(tapMatcher.group(2));
                dyF = Float.parseFloat(tapMatcher.group(3));
            } catch (NumberFormatException nfe) {
                return pass;
            }
            if (sampleIdx < 0 || sampleIdx >= bindCount || !definedSample[sampleIdx]) {
                return pass;
            }
            int dx = Math.round(dxF);
            int dy = Math.round(dyF);
            if (dx != dxF || dy != dyF || dx < -1 || dx > 1 || dy < -1 || dy > 1) {
                return pass;
            }
            VnsrTap tap = new VnsrTap(sampleIdx, vnsrCoordVar(dx, dy));
            pos = skipWs(inner, tapMatcher.end());

            while (pos < inner.length()) {
                matMatcher.region(pos, inner.length());
                if (!matMatcher.lookingAt()) {
                    break;
                }
                String destGroup = matMatcher.group(1);
                int destIdx;
                try {
                    destIdx = Integer.parseInt(destGroup);
                } catch (NumberFormatException nfe) {
                    return pass;
                }
                if (destIdx < 0 || destIdx >= saveCount) {
                    return pass;
                }
                String destName = "v" + destGroup;
                if (!destName.equals(accNames[destIdx])) {
                    return pass;
                }
                String matArgs = matMatcher.group(2);
                if (!parseFiniteFloats(matArgs, 16, mat16)) {
                    return pass;
                }
                if (mat16[12] == 0.0f && mat16[13] == 0.0f && mat16[14] == 0.0f && mat16[15] == 0.0f) {
                    String[] tokens = matArgs.split(",");
                    StringBuilder m3x4 = new StringBuilder("mat3x4(");
                    for (int k = 0; k < 12; k++) {
                        if (k > 0) m3x4.append(',');
                        m3x4.append(tokens[k].trim());
                    }
                    m3x4.append(')');
                    tap.accumList.add(new VnsrMatAccum(destName, m3x4.toString(), true));
                } else {
                    tap.accumList.add(new VnsrMatAccum(destName, "mat4(" + matArgs + ")", false));
                }
                pos = skipWs(inner, matMatcher.end());
            }
            if (tap.accumList.isEmpty()) {
                return pass;
            }
            taps.add(tap);
        }
        if (taps.isEmpty()) return pass;

        String[] fragColors = new String[saveCount];
        boolean[] fcSeen = new boolean[saveCount];
        Matcher fcMatcher = VNSR_FRAGCOLOR_STMT_PATTERN.matcher(inner);
        while (pos < inner.length()) {
            fcMatcher.region(pos, inner.length());
            if (!fcMatcher.lookingAt()) {
                break;
            }
            int fcIdx;
            int vIdx;
            String vGroup = fcMatcher.group(3) != null ? fcMatcher.group(3) : fcMatcher.group(4);
            try {
                fcIdx = Integer.parseInt(fcMatcher.group(1));
                vIdx = Integer.parseInt(vGroup);
            } catch (NumberFormatException nfe) {
                return pass;
            }
            if (fcIdx < 0 || fcIdx >= saveCount || fcIdx != vIdx || fcSeen[fcIdx]
                    || !("v" + vGroup).equals(accNames[fcIdx])) {
                return pass;
            }
            fcSeen[fcIdx] = true;
            fragColors[fcIdx] = fcMatcher.group().trim();
            pos = skipWs(inner, fcMatcher.end());
        }
        for (int s = 0; s < saveCount; s++) {
            if (!fcSeen[s]) return pass;
        }
        // Fail closed if ANY unconsumed text remains inside hook().
        if (pos != inner.length()) {
            return pass;
        }

        String b0 = pass.binds.get(0);
        StringBuilder sb = new StringBuilder(inner.length() + 512);
        sb.append("void hook() {\n");
        sb.append("    highp vec2 _p_m1_m1 = ").append(b0).append("_pos + vec2(-1.0, -1.0) * ").append(b0).append("_pt;\n");
        sb.append("    highp vec2 _p_0_m1  = ").append(b0).append("_pos + vec2(0.0, -1.0) * ").append(b0).append("_pt;\n");
        sb.append("    highp vec2 _p_1_m1  = ").append(b0).append("_pos + vec2(1.0, -1.0) * ").append(b0).append("_pt;\n");
        sb.append("    highp vec2 _p_m1_0  = ").append(b0).append("_pos + vec2(-1.0, 0.0) * ").append(b0).append("_pt;\n");
        sb.append("    highp vec2 _p_0_0   = ").append(b0).append("_pos;\n");
        sb.append("    highp vec2 _p_1_0   = ").append(b0).append("_pos + vec2(1.0, 0.0) * ").append(b0).append("_pt;\n");
        sb.append("    highp vec2 _p_m1_1  = ").append(b0).append("_pos + vec2(-1.0, 1.0) * ").append(b0).append("_pt;\n");
        sb.append("    highp vec2 _p_0_1   = ").append(b0).append("_pos + vec2(0.0, 1.0) * ").append(b0).append("_pt;\n");
        sb.append("    highp vec2 _p_1_1   = ").append(b0).append("_pos + vec2(1.0, 1.0) * ").append(b0).append("_pt;\n");
        for (String b : biases) {
            sb.append("    ").append(b).append("\n");
        }
        sb.append("    vec4 _s0, _s1, _s2;\n");
        for (int i = 0; i < taps.size(); i += 3) {
            int burst = Math.min(3, taps.size() - i);
            for (int k = 0; k < burst; k++) {
                VnsrTap tap = taps.get(i + k);
                String bind = pass.binds.get(tap.sampleIdx);
                sb.append("    _s").append(k).append(" = ").append(bind).append("_tex(").append(tap.coordVar).append(");\n");
            }
            for (int k = 0; k < burst; k++) {
                VnsrTap tap = taps.get(i + k);
                for (VnsrMatAccum acc : tap.accumList) {
                    sb.append("    ").append(acc.destName).append(" += ").append(acc.matExpr);
                    if (acc.isMat3x4) {
                        sb.append(" * _s").append(k).append(".rgb;\n");
                    } else {
                        sb.append(" * _s").append(k).append(";\n");
                    }
                }
            }
        }
        for (String fc : fragColors) {
            sb.append("    ").append(fc).append("\n");
        }
        sb.append("}\n");
        return new MpvHookShader.Pass(pass.desc, pass.binds, pass.saves, pass.widthRef, pass.heightRef, sb.toString());
    }

    private static final class AggDefine {
        final int index;
        final int rawSlot;
        final boolean negative;

        AggDefine(int index, int rawSlot, boolean negative) {
            this.index = index;
            this.rawSlot = rawSlot;
            this.negative = negative;
        }
    }

    private static boolean hasShadowedSamplingEnv(String inner, Iterable<String> hoistedBinds) {
        if (HOIST_HOST_ENV_IDENT_PATTERN.matcher(inner).find()) {
            return true;
        }
        for (String b : hoistedBinds) {
            if (b == null) continue;
            Pattern bindShadowPat = Pattern.compile(
                    "\\b(?:" + Pattern.quote(b) + "_(?:tex|texOff|pos|size|pt)|u_" + Pattern.quote(b)
                            + "(?:_size|_pt)?)\\b");
            if (bindShadowPat.matcher(inner).find()) {
                return true;
            }
        }
        return false;
    }

    private static MpvHookShader.Pass optimizePassBodyAggregation1x1(MpvHookShader.Pass pass, String stripped) {
        if (AGG_RESERVED_NAMES_PATTERN.matcher(stripped).find()) return pass;
        HookParts parts = parseSingleHookFunction(stripped);
        if (parts == null || hasPreprocessorDirective(parts.inner)) return pass;

        List<String> aggBinds = new ArrayList<>();
        Map<String, Integer> aggBindSlot = new HashMap<>();
        List<AggDefine> defines = new ArrayList<>();
        Set<Integer> definedIndices = new HashSet<>();
        Set<String> definedNames = new HashSet<>();

        for (String rawLine : parts.header.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            Matcher m = AGG_DEFINE_LINE_PATTERN.matcher(line);
            if (!m.matches()) {
                return pass;
            }
            int idx;
            try {
                idx = Integer.parseInt(m.group(1));
            } catch (NumberFormatException nfe) {
                return pass;
            }
            if (!m.group(1).equals(Integer.toString(idx))) return pass;
            String sign = m.group(2);
            String texBind = m.group(3);
            String posBind = m.group(4);
            if (!texBind.equals(posBind) || !pass.binds.contains(texBind) || !definedIndices.add(idx)) {
                return pass;
            }
            definedNames.add("g_" + idx);
            Integer rawSlot = aggBindSlot.get(texBind);
            if (rawSlot == null) {
                rawSlot = aggBinds.size();
                aggBinds.add(texBind);
                aggBindSlot.put(texBind, rawSlot);
            }
            defines.add(new AggDefine(idx, rawSlot, "-".equals(sign)));
        }
        if (aggBinds.isEmpty() || !definedIndices.contains(0) || !definedIndices.contains(1)
                || hasShadowedSamplingEnv(parts.inner, aggBinds)) {
            return pass;
        }

        // Fail closed if inner uses any undefined g_* token or zero-padded alias (e.g. g_01).
        Matcher checkTokens = AGG_ANY_TOKEN_PATTERN.matcher(parts.inner);
        StringBuffer replacedInner = new StringBuffer(parts.inner.length() + 64);
        while (checkTokens.find()) {
            String tok = checkTokens.group();
            if (!definedNames.contains(tok)) {
                return pass;
            }
            checkTokens.appendReplacement(replacedInner, "_" + tok);
        }
        checkTokens.appendTail(replacedInner);

        StringBuilder newBody = new StringBuilder(stripped.length() + 512);
        newBody.append(parts.isVoid ? "void hook() {\n" : "vec4 hook() {\n");
        newBody.append("    // Optimized: Pre-sample 1x1 feature taps once (deduplicating 2x CReLU fetches)\n");
        for (int k = 0; k < aggBinds.size(); k++) {
            String b = aggBinds.get(k);
            newBody.append("    vec4 _raw_").append(k).append(" = ").append(b)
                    .append("_tex(").append(b).append("_pos);\n");
        }
        for (AggDefine def : defines) {
            newBody.append("    vec4 _g_").append(def.index).append(" = max(")
                    .append(def.negative ? "-" : "").append("_raw_").append(def.rawSlot).append(", 0.0);\n");
        }
        newBody.append(replacedInner).append("}\n");
        return new MpvHookShader.Pass(pass.desc, pass.binds, pass.saves, pass.widthRef, pass.heightRef,
                newBody.toString());
    }

    private static MpvHookShader.Pass optimizePassBodyCrelu3x3(MpvHookShader.Pass pass, String stripped) {
        if (CRELU_RESERVED_NAMES_PATTERN.matcher(stripped).find()) return pass;
        HookParts parts = parseSingleHookFunction(stripped);
        if (parts == null || hasPreprocessorDirective(parts.inner)) return pass;

        String[] macroBinds = new String[4];
        boolean[] macroNeg = new boolean[4];
        boolean[] macroCrelu = new boolean[4];

        for (String rawLine : parts.header.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            Matcher m = CRELU_DEFINE_LINE_PATTERN.matcher(line);
            if (!m.matches()) {
                return pass;
            }
            int idx = Integer.parseInt(m.group(1));
            String pX = m.group(2);
            String pY = m.group(3);
            String rhs = m.group(4).trim();
            if (pX.equals(pY) || macroBinds[idx] != null) {
                return pass;
            }
            Matcher plain = CRELU_PLAIN_RHS_PATTERN.matcher(rhs);
            Matcher crelu = CRELU_MAX_RHS_PATTERN.matcher(rhs);
            if (plain.matches()) {
                String bind = plain.group(1);
                if (!pX.equals(plain.group(2)) || !pY.equals(plain.group(3)) || !pass.binds.contains(bind)) {
                    return pass;
                }
                macroBinds[idx] = bind;
                macroNeg[idx] = false;
                macroCrelu[idx] = false;
            } else if (crelu.matches()) {
                String sign = crelu.group(1);
                String bind = crelu.group(2);
                if (!pX.equals(crelu.group(3)) || !pY.equals(crelu.group(4)) || !pass.binds.contains(bind)) {
                    return pass;
                }
                macroBinds[idx] = bind;
                macroNeg[idx] = "-".equals(sign);
                macroCrelu[idx] = true;
            } else {
                return pass;
            }
        }

        // Validate supported 1-tap plain, 2-tap CReLU, or 4-tap CReLU shapes strictly.
        String bind0 = macroBinds[0];
        if (bind0 == null) return pass;
        StringBuilder precompute = new StringBuilder(1024);
        precompute.append("    // Optimized: Pre-sample 3x3 neighborhood taps once (eliminating redundant texture fetches)\n");

        if (macroBinds[2] != null || macroBinds[3] != null) {
            // 4-tap CReLU: go_0 (+b0), go_1 (+b1), go_2 (-b0), go_3 (-b1)
            if (macroBinds[1] == null || macroBinds[2] == null || macroBinds[3] == null) return pass;
            if (!macroCrelu[0] || !macroCrelu[1] || !macroCrelu[2] || !macroCrelu[3]) return pass;
            if (macroNeg[0] || macroNeg[1] || !macroNeg[2] || !macroNeg[3]) return pass;
            if (!bind0.equals(macroBinds[2]) || !macroBinds[1].equals(macroBinds[3])) return pass;
            String bind1 = macroBinds[1];

            for (String[] off : OFFSETS_3X3) {
                precompute.append("    vec4 _t0_").append(off[2]).append(" = ").append(bind0)
                        .append("_texOff(vec2(").append(off[0]).append(", ").append(off[1]).append("));\n");
            }
            for (String[] off : OFFSETS_3X3) {
                precompute.append("    vec4 _t1_").append(off[2]).append(" = ").append(bind1)
                        .append("_texOff(vec2(").append(off[0]).append(", ").append(off[1]).append("));\n");
            }
            for (String[] off : OFFSETS_3X3) {
                precompute.append("    vec4 _go_0_").append(off[2]).append(" = max(_t0_").append(off[2]).append(", 0.0);\n");
                precompute.append("    vec4 _go_2_").append(off[2]).append(" = max(-_t0_").append(off[2]).append(", 0.0);\n");
                precompute.append("    vec4 _go_1_").append(off[2]).append(" = max(_t1_").append(off[2]).append(", 0.0);\n");
                precompute.append("    vec4 _go_3_").append(off[2]).append(" = max(-_t1_").append(off[2]).append(", 0.0);\n");
            }
        } else if (macroBinds[1] != null) {
            // 2-tap CReLU: go_0 (+b0), go_1 (-b0)
            if (!macroCrelu[0] || !macroCrelu[1] || macroNeg[0] || !macroNeg[1] || !bind0.equals(macroBinds[1])) {
                return pass;
            }
            for (String[] off : OFFSETS_3X3) {
                precompute.append("    vec4 _t0_").append(off[2]).append(" = ").append(bind0)
                        .append("_texOff(vec2(").append(off[0]).append(", ").append(off[1]).append("));\n");
            }
            for (String[] off : OFFSETS_3X3) {
                precompute.append("    vec4 _go_0_").append(off[2]).append(" = max(_t0_").append(off[2]).append(", 0.0);\n");
                precompute.append("    vec4 _go_1_").append(off[2]).append(" = max(-_t0_").append(off[2]).append(", 0.0);\n");
            }
        } else {
            // 1-tap plain: go_0 (b0)
            if (macroCrelu[0]) return pass;
            for (String[] off : OFFSETS_3X3) {
                precompute.append("    vec4 _go_0_").append(off[2]).append(" = ").append(bind0)
                        .append("_texOff(vec2(").append(off[0]).append(", ").append(off[1]).append("));\n");
            }
        }

        // Fail closed if ANY go_* reference in inner has unsupported index or out-of-range coordinates (e.g. go_0(2.0, 0.0)).
        Matcher scanGo = GO_ANY_CALL_OR_IDENT_PATTERN.matcher(parts.inner);
        StringBuffer replacedInner = new StringBuffer(parts.inner.length());
        while (scanGo.find()) {
            Matcher valid = GO_VALID_CALL_PATTERN.matcher(scanGo.group());
            if (!valid.matches()) {
                return pass;
            }
            int idx = Integer.parseInt(valid.group(1));
            if (macroBinds[idx] == null) {
                return pass;
            }
            String x = coordTag(valid.group(2));
            String y = coordTag(valid.group(3));
            scanGo.appendReplacement(replacedInner, "_go_" + idx + "_" + x + "_" + y);
        }
        scanGo.appendTail(replacedInner);

        StringBuilder newBody = new StringBuilder(precompute.length() + replacedInner.length() + 32);
        newBody.append(parts.isVoid ? "void hook() {\n" : "vec4 hook() {\n");
        newBody.append(precompute);
        newBody.append(replacedInner).append("}\n");
        return new MpvHookShader.Pass(pass.desc, pass.binds, pass.saves, pass.widthRef, pass.heightRef,
                newBody.toString());
    }

    static List<MpvHookShader.Pass> optimizePasses(List<MpvHookShader.Pass> passes, boolean runtimeMrtFusionEnabled) {
        if (passes == null || passes.isEmpty()) return passes;
        List<MpvHookShader.Pass> current = runtimeMrtFusionEnabled ? fuseCompatiblePasses(passes) : passes;

        // Track resolution provenance of each texture across passes so mixed-resolution binds fail closed.
        Map<String, String[]> dimRoots = new HashMap<>();
        dimRoots.put("MAIN", new String[]{"MAIN", "MAIN"});

        List<MpvHookShader.Pass> optimized = new ArrayList<>(current.size());
        for (MpvHookShader.Pass pass : current) {
            boolean sameResBinds = true;
            if (!pass.binds.isEmpty()) {
                String[] firstRoot = dimRoots.get(pass.binds.get(0));
                if (firstRoot == null) {
                    sameResBinds = false;
                } else {
                    for (int b = 1; b < pass.binds.size(); b++) {
                        String[] root = dimRoots.get(pass.binds.get(b));
                        if (root == null || !firstRoot[0].equals(root[0]) || !firstRoot[1].equals(root[1])) {
                            sameResBinds = false;
                            break;
                        }
                    }
                }
            }
            optimized.add(optimizePassBody(pass, sameResBinds));

            if (!pass.isFinalToScreen() && !pass.binds.isEmpty()) {
                String wRef = pass.widthRef != null ? pass.widthRef : pass.binds.get(0);
                String hRef = pass.heightRef != null ? pass.heightRef : pass.binds.get(0);
                String[] wRootArr = dimRoots.get(wRef);
                String[] hRootArr = dimRoots.get(hRef);
                String[] saveRoot = new String[]{
                        wRootArr != null ? wRootArr[0] : wRef,
                        hRootArr != null ? hRootArr[1] : hRef
                };
                for (String s : pass.saves) {
                    dimRoots.put(s, saveRoot);
                }
            }
        }
        return Collections.unmodifiableList(optimized);
    }

    static int[][] resolveStaticInputSlots(List<MpvHookShader.Pass> passes, GraphicsShaderTargets targetPlan) {
        int passCount = passes.size();
        int[][] resolved = new int[passCount][];
        Map<String, Integer> publishedSlots = new HashMap<>();

        for (int p = 0; p < passCount; p++) {
            MpvHookShader.Pass pass = passes.get(p);
            int bindCount = pass.binds.size();
            resolved[p] = new int[bindCount];

            for (int b = 0; b < bindCount; b++) {
                String bind = pass.binds.get(b);
                if ("MAIN".equals(bind)) {
                    resolved[p][b] = -1; // -1 denotes MAIN texture
                } else {
                    Integer slot = publishedSlots.get(bind);
                    if (slot == null) {
                        throw new IllegalStateException("unresolved CNN bind in pass " + p + ": " + bind);
                    }
                    resolved[p][b] = slot;
                }
            }

            if (!pass.isFinalToScreen()) {
                int[] slots = targetPlan.passTargetSlots[p];
                for (int s = 0; s < pass.saves.size(); s++) {
                    publishedSlots.put(pass.saves.get(s), slots[s]);
                }
            }
        }
        return resolved;
    }
}
