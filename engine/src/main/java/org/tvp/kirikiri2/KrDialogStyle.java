package org.tvp.kirikiri2;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import bridge.KrPathUtils;
import com.core.engine.EngineUiText;
import com.core.engine.LaunchContract;

/**
 * 统一弹窗样式 — KRKR / Artemis 共用。
 *
 * <p>从启动 Activity 的 Intent extras 读取主题色（由 LauncherUiBridge.appendEngineThemeExtras
 * 注入），使引擎弹窗在视觉上与 Launcher 主题一致。无 extras 时回退到 Material 深色默认值。</p>
 *
 * <p>样式规格遵循 AGENTS.md：20dp 卡片圆角、20dp 按钮圆角、16sp bold 标题、
 * 12sp muted 正文、13sp bold 按钮、38dp 按钮高度。</p>
 */
public final class KrDialogStyle {

    // --- 回退颜色（无 Intent extras 时使用） ---
    private static final int FALLBACK_CARD         = 0xFF2A2A2A;
    private static final int FALLBACK_PRIMARY       = 0xFF18B978;
    private static final int FALLBACK_ON_PRIMARY    = 0xFFFFFFFF;
    private static final int FALLBACK_TEXT          = 0xFFFFFFFF;
    private static final int FALLBACK_TEXT_MUTED    = 0xFFB0B0B0;
    private static final int FALLBACK_INPUT_BG      = 0xFF3A3A3A;
    private static final int FALLBACK_INPUT_TEXT    = 0xFFFFFFFF;
    private static final int FALLBACK_INPUT_HINT    = 0xFF808080;

    // --- 尺寸常量（AGENTS.md） ---
    private static final float CARD_RADIUS_DP   = 20f;
    private static final float BUTTON_RADIUS_DP = 20f;
    private static final float INPUT_RADIUS_DP  = 8f;
    private static final int   DIALOG_WIDTH_DP  = 280;
    private static final int   BUTTON_HEIGHT_DP = 38;
    private static final int   BUTTON_TEXT_SP   = 13;
    private static final int   TITLE_TEXT_SP    = 16;
    private static final int   MESSAGE_TEXT_SP  = 12;

    public interface Callback {
        /** @param which 0=positive, 1=neutral, 2=negative */
        void onButtonClicked(int which, String inputText);
    }

    private KrDialogStyle() {}

    // ---- 入口 ----

    /** 仅消息弹窗（不可取消） */
    public static Dialog showMessageBox(Context context, String title, String message,
                                        String[] buttons, Callback callback) {
        return showInputBox(context, title, message, null, buttons, false, callback);
    }

    /** 带可选文本输入的弹窗（不可取消） */
    public static Dialog showInputBox(Context context, String title, String message,
                                      String initialText, String[] buttons, Callback callback) {
        return showInputBox(context, title, message, initialText, buttons, false, callback);
    }

    /**
     * 带可选文本输入的弹窗。
     *
     * @param cancelable true 时允许返回键取消（回调 which=1），false 时完全阻塞。
     *                   KRKR 弹窗应传 false（引擎必须收到按钮响应）；
     *                   Artemis 弹窗按游戏需求传递。
     */
    public static Dialog showInputBox(Context context, String title, String message,
                                      String initialText, String[] buttons,
                                      boolean cancelable, Callback callback) {
        return showInputBoxInternal(context, title, message, initialText, buttons, cancelable, callback,
                InputType.TYPE_CLASS_TEXT, (which, rawText) -> rawText);
    }

    /**
     * 带数值输入的弹窗：确认时对 [min,max] 做钳制，返回 String.valueOf(值)；取消/无效输入回退 initial。
     *
     * <p>与文本输入入口共用同一卡片与按钮，输入控件为同一规格的 EditText（数字键盘、允许负号）。
     * 确认（which==0）时解析输入框文本：空或非法回退 initial，再钳制到规范化后的 [min,max]；
     * 取消（which!=0）时回调 inputText 为空串。</p>
     *
     * @param initial 预填值，内部钳制入 [min,max]（min/max 顺序不限，内部先规范化）。
     */
    public static Dialog showNumberBox(Context context, String title, String message,
                                       int initial, int min, int max,
                                       String[] buttons, boolean cancelable, Callback callback) {
        final int lo = Math.min(min, max);
        final int hi = Math.max(min, max);
        final int start = Math.min(Math.max(initial, lo), hi);
        return showInputBoxInternal(context, title, message, String.valueOf(start), buttons, cancelable, callback,
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED,
                (which, rawText) -> which == 0 ? resolveNumberText(rawText, start, lo, hi) : "");
    }

    /**
     * 数值输入解析（{@link #showNumberBox} 确认路径）：空或非法输入回退 fallback，
     * 再钳制到 [min,max]（顺序不限）。包内可见以便单元测试。
     */
    static String resolveNumberText(String rawText, int fallback, int min, int max) {
        String text = rawText == null ? "" : rawText.trim();
        int value = fallback;
        if (!text.isEmpty()) {
            try {
                value = Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                value = fallback;
            }
        }
        return String.valueOf(Math.min(Math.max(value, Math.min(min, max)), Math.max(min, max)));
    }

    /**
     * 输入弹窗核心：文本与数值入口共用同一卡片、按钮与键盘唤起流程。
     *
     * @param inputType 输入控件类型（文本或数值）。
     * @param resolver 按钮点击时把输入框原文转换为回调的 inputText。
     */
    private static Dialog showInputBoxInternal(Context context, String title, String message,
                                               String initialText, String[] buttons,
                                               boolean cancelable, Callback callback,
                                               int inputType, InputTextResolver resolver) {
        Colors c = resolveColors(context);

        // 用 Dialog 而非 AlertDialog，避免内部布局冲突。
        // setContentView 必须在 show() 之前调用，使 Window 首次显示时
        // 就能在视图树中找到已获焦的 EditText 并触发 SOFT_INPUT_STATE_ALWAYS_VISIBLE。
        Dialog dialog = new Dialog(context);
        dialog.setCancelable(false); // 始终不允许返回键直接 dismiss，用 OnCancelListener 模拟
        dialog.setCanceledOnTouchOutside(false);

        // 按钮点击标志 — 区分"用户点击按钮 dismiss"和"系统意外 dismiss"
        final boolean[] buttonClicked = {false};

        LinearLayout card = createCard(context, c);
        addTitle(card, title, c);
        if (message != null && !message.isEmpty()) {
            addMessage(card, message, c);
        }
        final EditText editText;
        if (initialText != null) {
            editText = addEditText(card, initialText, c, inputType);
        } else {
            editText = null;
        }
        addButtons(card, buttons, c, (which) -> {
            buttonClicked[0] = true;
            dialog.dismiss();
            String text = editText != null ? resolver.resolve(which, editText.getText().toString()) : "";
            if (callback != null) callback.onButtonClicked(which, text);
        });

        // 全屏容器拦截所有触摸事件，防止穿透到下层 GL Activity。
        FrameLayout root = new FrameLayout(context);
        root.setClickable(true);
        root.setFocusable(true);
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                dialogWidthPx(context), FrameLayout.LayoutParams.WRAP_CONTENT);
        cardParams.gravity = Gravity.CENTER;
        root.addView(card, cardParams);

        dialog.setContentView(root);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            window.setDimAmount(0f);
            window.setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            // 弹窗 Window 必须持有焦点并消费所有触摸事件，
            // 否则触摸会穿透到下层 GL Activity。
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
            // 全屏 Window 覆盖整个屏幕，确保所有触摸都由弹窗消费
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.width = WindowManager.LayoutParams.MATCH_PARENT;
            lp.height = WindowManager.LayoutParams.MATCH_PARENT;
            window.setAttributes(lp);
        }

        // 可取消时，用 OnCancelListener 模拟原始 Artemis 行为（返回键 → close(0)）
        if (cancelable) {
            dialog.setOnCancelListener(d -> {
                if (!buttonClicked[0] && callback != null) {
                    buttonClicked[0] = true;
                    callback.onButtonClicked(1, "");
                }
            });
            dialog.setCancelable(true);
        }

        dialog.show();

        if (editText != null) {
            // Cocos2dx GL 环境下 focus 可能被抢回，延迟触发 IME 作为保底
            editText.postDelayed(() -> {
                try {
                    editText.requestFocus();
                    InputMethodManager imm = (InputMethodManager) editText.getContext()
                            .getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.showSoftInput(editText, InputMethodManager.SHOW_FORCED);
                        if (!imm.isActive(editText)) {
                            imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
                        }
                    }
                } catch (Throwable ignored) {}
            }, 300);
        }
        return dialog;
    }

    // ---- 主题色解析 ----

    private static class Colors {
        int card, primary, onPrimary, text, textMuted;
        int inputBg, inputText, inputHint;
    }

    private static Colors resolveColors(Context context) {
        Colors c = new Colors();
        c.card        = FALLBACK_CARD;
        c.primary     = FALLBACK_PRIMARY;
        c.onPrimary   = FALLBACK_ON_PRIMARY;
        c.text        = FALLBACK_TEXT;
        c.textMuted   = FALLBACK_TEXT_MUTED;
        c.inputBg     = FALLBACK_INPUT_BG;
        c.inputText   = FALLBACK_INPUT_TEXT;
        c.inputHint   = FALLBACK_INPUT_HINT;

        try {
            Intent intent = null;
            if (context instanceof android.app.Activity) {
                intent = ((android.app.Activity) context).getIntent();
            }
            // 同进程回退：从 KR2Activity singleton 读取
            if (!hasThemeExtras(intent)) {
                KR2Activity kr = KrPathUtils.currentActivity();
                if (kr != null) intent = kr.getIntent();
            }
            if (hasThemeExtras(intent)) {
                c.card       = intent.getIntExtra(LaunchContract.THEME_COLOR_CARD, c.card);
                c.primary    = intent.getIntExtra(LaunchContract.THEME_COLOR_PRIMARY, c.primary);
                c.onPrimary  = intent.getIntExtra(LaunchContract.THEME_COLOR_ON_PRIMARY, c.onPrimary);
                c.text       = intent.getIntExtra(LaunchContract.THEME_COLOR_TEXT, c.text);
                c.textMuted  = intent.getIntExtra(LaunchContract.THEME_COLOR_TEXT_MUTED, c.textMuted);
                c.inputBg    = darken(c.card, 0.12f);
                c.inputText  = c.text;
                c.inputHint  = c.textMuted;
            }
        } catch (Throwable ignored) {}
        return c;
    }

    private static boolean hasThemeExtras(Intent intent) {
        return intent != null && intent.hasExtra(LaunchContract.THEME_COLOR_PRIMARY);
    }

    private static int darken(int color, float amount) {
        int r = Math.max(0, (int) (Color.red(color) * (1f - amount)));
        int g = Math.max(0, (int) (Color.green(color) * (1f - amount)));
        int b = Math.max(0, (int) (Color.blue(color) * (1f - amount)));
        return Color.rgb(r, g, b);
    }

    // ---- 组件构建 ----

    private static LinearLayout createCard(Context context, Colors c) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 24), dp(context, 20), dp(context, 24), dp(context, 16));
        card.setBackground(roundedRect(c.card, dp(context, CARD_RADIUS_DP)));
        return card;
    }

    private static void addTitle(LinearLayout card, String text, Colors c) {
        Context context = card.getContext();
        TextView view = new TextView(context);
        view.setText(EngineUiText.localizeCommonDialogText(context, text));
        view.setTextColor(c.text);
        view.setTextSize(TITLE_TEXT_SP);
        view.setTypeface(null, Typeface.BOLD);
        view.setGravity(Gravity.CENTER);
        card.addView(view);
    }

    private static void addMessage(LinearLayout card, String text, Colors c) {
        Context context = card.getContext();
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(c.textMuted);
        view.setTextSize(MESSAGE_TEXT_SP);
        view.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(context, 12);
        card.addView(view, params);
    }

    private static EditText addEditText(LinearLayout card, String initialText, Colors c, int inputType) {
        Context context = card.getContext();
        EditText edit = new EditText(context);
        edit.setText(initialText);
        edit.setTextColor(c.inputText);
        edit.setHintTextColor(c.inputHint);
        edit.setSingleLine(true);
        edit.setInputType(inputType);
        edit.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        edit.setFocusable(true);
        edit.setFocusableInTouchMode(true);
        edit.setBackground(roundedRect(c.inputBg, dp(context, INPUT_RADIUS_DP)));
        edit.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(context, 12);
        card.addView(edit, params);
        return edit;
    }

    private static void addButtons(LinearLayout card, String[] buttonTexts, Colors c, ButtonClickCallback callback) {
        Context context = card.getContext();
        String[] texts = buttonTexts != null ? buttonTexts : new String[]{"OK"};

        LinearLayout buttonRow = new LinearLayout(context);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(context, 16);

        for (int i = 0; i < texts.length; i++) {
            final int index = i;
            boolean isPrimary = (i == 0);
            TextView btn = new TextView(context);
            btn.setText(EngineUiText.localizeCommonDialogText(context, texts[i]));
            btn.setGravity(Gravity.CENTER);
            btn.setTextSize(BUTTON_TEXT_SP);
            btn.setTypeface(null, Typeface.BOLD);
            if (isPrimary) {
                btn.setTextColor(c.onPrimary);
                btn.setBackground(roundedRect(c.primary, dp(context, BUTTON_RADIUS_DP)));
            } else {
                btn.setTextColor(c.primary);
                btn.setBackground(roundedRect(c.card, dp(context, BUTTON_RADIUS_DP)));
            }
            btn.setOnClickListener(v -> {
                if (callback != null) callback.onClicked(index);
            });
            LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(0, dp(context, BUTTON_HEIGHT_DP), 1f);
            if (i > 0) btnParams.leftMargin = dp(context, 8);
            buttonRow.addView(btn, btnParams);
        }
        card.addView(buttonRow, rowParams);
    }

    // ---- Drawable 工具 ----

    private static GradientDrawable roundedRect(int color, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        return d;
    }

    private static int dp(Context context, float value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static int dialogWidthPx(Context context) {
        int desiredWidth = dp(context, DIALOG_WIDTH_DP);
        int horizontalMargin = dp(context, 24) * 2;
        int maxWidth = Math.max(0, context.getResources().getDisplayMetrics().widthPixels - horizontalMargin);
        return Math.min(desiredWidth, maxWidth);
    }

    private interface ButtonClickCallback {
        void onClicked(int index);
    }

    /** 输入框原文 → 回调 inputText 的转换（数值入口用于解析与钳制）。 */
    private interface InputTextResolver {
        String resolve(int which, String rawText);
    }
}
