package com.core.gl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * mpv hook 着色器（Anime4K 官方分发格式）解析器。
 *
 * 输入文件由多个 pass 组成，每个 pass 以 //!DESC 开始，指令行语法：
 * <pre>
 * //!DESC 描述
 * //!HOOK MAIN              （Anime4K 全部 hook MAIN，忽略）
 * //!BIND NAME              （输入纹理；HOOKED 为被 hook 纹理的别名，即 MAIN）
 * //!SAVE NAME              （输出纹理；SAVE MAIN 表示最终输出写回主画面）
 * //!WIDTH NAME.w           （输出宽度表达式，仅支持「纹理名.分量」形式）
 * //!HEIGHT NAME.h
 * //!COMPONENTS n           （1/3/4，仅提示性，统一按 RGBA 分配）
 * </pre>
 * 其余非指令行为 GLSL pass 体（vec4 hook() 定义与 #define 宏）。
 */
public final class MpvHookShader {

    /** 单个后处理 pass 的结构化描述 */
    public static final class Pass {
        public final String desc;
        /** 输入纹理名列表（HOOKED 已归一化为 MAIN） */
        public final List<String> binds;
        /** 输出纹理名；null 或 "MAIN" 表示输出到屏幕（最终 pass） */
        public final String save;
        /** 输出宽度引用（纹理名），null 表示取主画面尺寸 */
        public final String widthRef;
        /** 输出高度引用（纹理名），null 表示取主画面尺寸 */
        public final String heightRef;
        /** GLSL pass 体（含 #define 宏与 vec4 hook() 定义） */
        public final String body;

        Pass(String desc, List<String> binds, String save,
             String widthRef, String heightRef, String body) {
            this.desc = desc;
            this.binds = Collections.unmodifiableList(binds);
            this.save = save;
            this.widthRef = widthRef;
            this.heightRef = heightRef;
            this.body = body;
        }

        /** 是否为写回主画面的最终 pass */
        public boolean isFinalToScreen() {
            return save == null || "MAIN".equals(save);
        }
    }

    /** HOOKED 是 mpv 对被 hook 纹理（MAIN）的别名 */
    private static final String ALIAS_HOOKED = "HOOKED";

    /**
     * 解析着色器源码为 pass 列表。
     *
     * @throws IllegalArgumentException 格式无法识别时抛出（调用方应禁用该着色器而非崩溃）
     */
    public static List<Pass> parse(String source) {
        List<Pass> passes = new ArrayList<>();
        List<String> binds = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        String desc = null, save = null, widthRef = null, heightRef = null;
        boolean inPass = false;

        for (String raw : source.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("//!")) {
                String directive = line.substring(3);
                if (directive.startsWith("DESC")) {
                    if (inPass) {
                        passes.add(new Pass(desc, binds, save, widthRef, heightRef, body.toString()));
                        binds = new ArrayList<>();
                        body = new StringBuilder();
                        save = widthRef = heightRef = null;
                    }
                    desc = directive.length() > 5 ? directive.substring(5).trim() : "";
                    inPass = true;
                } else if (directive.startsWith("BIND")) {
                    String name = directive.substring(4).trim();
                    if (ALIAS_HOOKED.equals(name)) name = "MAIN";
                    if (!binds.contains(name)) binds.add(name);
                } else if (directive.startsWith("SAVE")) {
                    save = directive.substring(4).trim();
                } else if (directive.startsWith("WIDTH")) {
                    widthRef = parseDimRef(directive.substring(5).trim());
                } else if (directive.startsWith("HEIGHT")) {
                    heightRef = parseDimRef(directive.substring(6).trim());
                }
                // HOOK / COMPONENTS / OFFSET 等指令对Anime4K管线无实现意义，忽略
            } else if (inPass) {
                body.append(raw).append('\n');
            }
        }
        if (inPass) {
            passes.add(new Pass(desc, binds, save, widthRef, heightRef, body.toString()));
        }
        if (passes.isEmpty()) {
            throw new IllegalArgumentException("no pass found in shader source");
        }
        return passes;
    }

    /** 解析尺寸表达式「NAME.w」/「NAME.h」→ 纹理名；其余形式不支持 */
    private static String parseDimRef(String expr) {
        if (expr == null) return null;
        int dot = expr.indexOf('.');
        if (dot <= 0 || dot != expr.length() - 2) return null;
        char comp = expr.charAt(expr.length() - 1);
        if (comp != 'w' && comp != 'h') return null;
        String name = expr.substring(0, dot);
        return ALIAS_HOOKED.equals(name) ? "MAIN" : name;
    }
}
