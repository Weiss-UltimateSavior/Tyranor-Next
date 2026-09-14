package com.ies_net.artemis.debug;

import android.app.Activity;

import org.tvp.kirikiri2.KrDialogStyle;

/**
 * 自研 clean-room Artemis 内核（artemis-compat）的宿主弹窗。libartemis-clean.so
 * 通过 JNI 调用本类：{@code install(Activity)} / {@code show(...)} /
 * {@code isDone()} / {@code resultOk()} / {@code resultText()}。
 *
 * 三种模式（与内核 DialogRequest 对应）：
 * <ul>
 *   <li>mode 0：仅消息（单 OK，不可取消，阻塞）</li>
 *   <li>mode 1：是/否确认（无输入框，可取消）</li>
 *   <li>mode 2：文本输入（带输入框，可取消）</li>
 * </ul>
 *
 * 外观复用引擎侧统一样式 {@link KrDialogStyle}（与官方 Artemis / KRKR 弹窗同款卡片、
 * 圆角、按钮与输入框），主题色经启动 Intent 的 {@code LaunchContract.THEME_COLOR_*}
 * extras 注入，缺失时回落默认绿。
 *
 * 注意：{@code show} 的签名与 libartemis-clean.so 的 JNI 查找签名成对约束
 * （{@code (String,String,String,int,int)V}），改动必须同步重编内核。
 */
public final class NativeInput {
    private static Activity sActivity;
    private static volatile boolean sDone;
    private static volatile boolean sOk;
    private static volatile String sText = "";

    private NativeInput() {}

    /** Called once from native (ANativeActivity_onCreate) with the Activity. */
    public static void install(Activity activity) {
        sActivity = activity;
    }

    public static boolean isDone() {
        return sDone;
    }

    public static boolean resultOk() {
        return sOk;
    }

    public static String resultText() {
        return sText == null ? "" : sText;
    }

    /** Native entry point: show the modal box. mode 0 = message-only alert,
     *  1 = yes/no confirm, 2 = text input. */
    public static void show(final String title, final String message,
                            final String def, final int maxLen, final int mode) {
        sDone = false;
        sOk = false;
        sText = "";
        final Activity a = sActivity;
        if (a == null) {
            sDone = true;
            return;
        }
        // KrDialogStyle 不支持运行时长度限制，只能预截断默认值（与官方弹窗行为一致）
        String initial = def == null ? "" : def;
        if (maxLen > 0 && initial.length() > maxLen) {
            initial = initial.substring(0, maxLen);
        }
        final String initialText = initial;
        a.runOnUiThread(() -> {
            try {
                if (a.isFinishing()) {
                    sDone = true;
                    return;
                }
                final String t = title == null ? "" : title;
                final String m = message == null ? "" : message;
                if (mode == 2) {
                    // 文本输入：带输入框，OK/Cancel，可取消
                    KrDialogStyle.showInputBox(
                            a, t, m, initialText,
                            new String[]{"OK", "Cancel"}, true,
                            (which, text) -> {
                                sText = text == null ? "" : text;
                                sOk = which == 0;
                                sDone = true;
                            });
                } else if (mode == 1) {
                    // 是/否确认：无输入框，OK/Cancel，可取消
                    KrDialogStyle.showInputBox(
                            a, t, m, null,
                            new String[]{"OK", "Cancel"}, true,
                            (which, text) -> {
                                sText = "";
                                sOk = which == 0;
                                sDone = true;
                            });
                } else {
                    // 仅消息：单 OK，不可取消（与官方阻塞语义一致）
                    KrDialogStyle.showMessageBox(
                            a, t, m,
                            new String[]{"OK"},
                            (which, text) -> {
                                sText = "";
                                sOk = which == 0;
                                sDone = true;
                            });
                }
            } catch (Throwable t2) {
                sDone = true;
            }
        });
    }
}
