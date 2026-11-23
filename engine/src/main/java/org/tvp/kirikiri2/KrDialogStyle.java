package org.tvp.kirikiri2;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.core.engine.EngineThemeColors;
import com.core.engine.EngineUiText;
import com.core.engine.R;

/** 内置引擎宿主共用的 KRKR 消息、输入和内容弹窗。 */
public final class KrDialogStyle {

    // --- 尺寸常量（AGENTS.md） ---
    private static final float CARD_RADIUS_DP   = 20f;
    private static final float BUTTON_RADIUS_DP = 20f;
    private static final float INPUT_RADIUS_DP  = 8f;
    private static final int   DIALOG_WIDTH_DP  = 280;
    private static final int   CONTENT_WIDTH_DP = 560;
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

    private static Dialog showInputBoxInternal(Context context, String title, String message,
                                               String initialText, String[] buttons,
                                               boolean cancelable, Callback callback,
                                               int inputType, InputTextResolver resolver) {
        EditText input = initialText == null ? null : createInput(context, initialText, inputType);
        Dialog dialog = createDialog(context, title, message, input, buttons, cancelable, false,
                (d, which) -> {
                    String text = input == null ? "" : resolver.resolve(which, input.getText().toString());
                    if (callback != null) callback.onButtonClicked(which, text);
                });
        if (cancelable && callback != null) {
            dialog.setOnCancelListener(d -> callback.onButtonClicked(1, ""));
        }
        if (input != null) focusInput(dialog, input);
        dialog.show();
        return dialog;
    }

    /** Buttons report their original array index. Back and outside taps use the dialog's cancel listener. */
    public static Dialog createDialog(Context context, String title, String message, View content,
                                      String[] buttons, boolean cancelable, boolean cancelOnOutside,
                                      DialogInterface.OnClickListener action) {
        return createDialog(context, title, message, content, buttons, cancelable, cancelOnOutside,
                DIALOG_WIDTH_DP, action);
    }

    /** Wider card for a scrollable list, form or settings panel. */
    public static Dialog createContentDialog(Context context, String title, String message, View content,
                                             String[] buttons, boolean cancelable, boolean cancelOnOutside,
                                             DialogInterface.OnClickListener action) {
        return createDialog(context, title, message, content, buttons, cancelable, cancelOnOutside,
                CONTENT_WIDTH_DP, action);
    }

    private static Dialog createDialog(Context context, String title, String message, View content,
                                       String[] buttons, boolean cancelable, boolean cancelOnOutside,
                                       int widthDp, DialogInterface.OnClickListener action) {
        EngineThemeColors.Palette colors = palette(context);
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(cancelable);
        dialog.setCanceledOnTouchOutside(cancelable && cancelOnOutside);

        LinearLayout card = createCard(context, colors);
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        if (title != null && !title.isEmpty()) addTitle(body, title, colors);
        if (message != null && !message.isEmpty()) addMessage(body, message, colors);
        if (content != null) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            if (body.getChildCount() != 0) params.topMargin = dp(context, 12);
            body.addView(content, params);
        }
        ScrollView scroll = new ScrollView(context);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        // The body yields height to the actions when text or the keyboard limits the viewport.
        if (body.getChildCount() != 0) card.addView(scroll, new LinearLayout.LayoutParams(-1, -2, 1f));
        addButtons(card, buttons, colors, index -> {
            if (!dialog.isShowing()) return;
            dialog.dismiss();
            if (action != null) action.onClick(dialog, index);
        });

        FrameLayout root = new FrameLayout(context);
        root.setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8));
        root.setOnClickListener(cancelable && cancelOnOutside ? v -> dialog.cancel() : null);
        root.setClickable(true);
        card.setClickable(true);
        int maxWidth = Math.max(1, context.getResources().getDisplayMetrics().widthPixels - dp(context, 48));
        root.addView(card, new FrameLayout.LayoutParams(Math.min(dp(context, widthDp), maxWidth), -2,
                Gravity.CENTER));
        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            window.setLayout(-1, -1);
        }
        return dialog;
    }

    public static void focusInput(Dialog dialog, EditText input) {
        input.requestFocus();
        dialog.setOnShowListener(d -> input.postDelayed(() -> {
            if (!dialog.isShowing() || !input.hasFocus()) return;
            InputMethodManager ime = input.getContext().getSystemService(InputMethodManager.class);
            if (ime != null) ime.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        }, 300));
        Window window = dialog.getWindow();
        if (window != null) window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
    }

    private static EngineThemeColors.Palette palette(Context context) {
        return EngineThemeColors.fromIntent(context instanceof Activity ? ((Activity) context).getIntent() : null);
    }

    private static int darken(int color, float amount) {
        int r = Math.max(0, (int) (Color.red(color) * (1f - amount)));
        int g = Math.max(0, (int) (Color.green(color) * (1f - amount)));
        int b = Math.max(0, (int) (Color.blue(color) * (1f - amount)));
        return Color.rgb(r, g, b);
    }

    // ---- 组件构建 ----

    private static LinearLayout createCard(Context context, EngineThemeColors.Palette c) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 24), dp(context, 20), dp(context, 24), dp(context, 16));
        card.setBackground(roundedRect(c.getCard(), dp(context, CARD_RADIUS_DP)));
        return card;
    }

    private static void addTitle(LinearLayout card, String text, EngineThemeColors.Palette c) {
        Context context = card.getContext();
        TextView view = new TextView(context);
        view.setText(EngineUiText.localizeCommonDialogText(context, text));
        view.setTextColor(c.getText());
        view.setTextSize(TITLE_TEXT_SP);
        view.setTypeface(null, Typeface.BOLD);
        view.setGravity(Gravity.CENTER);
        card.addView(view);
    }

    private static void addMessage(LinearLayout card, String text, EngineThemeColors.Palette c) {
        Context context = card.getContext();
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(c.getTextMuted());
        view.setTextSize(MESSAGE_TEXT_SP);
        view.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(context, 12);
        card.addView(view, params);
    }

    public static EditText createInput(Context context, String initialText, int inputType) {
        EngineThemeColors.Palette colors = palette(context);
        EditText edit = new EditText(context);
        edit.setSingleLine(true);
        edit.setInputType(inputType);
        edit.setImeOptions(EditorInfo.IME_ACTION_DONE);
        edit.setText(initialText);
        edit.setTextColor(colors.getText());
        edit.setHintTextColor(colors.getTextMuted());
        edit.setBackgroundTintList(null);
        edit.setBackground(roundedRect(darken(colors.getCard(), 0.12f), dp(context, INPUT_RADIUS_DP)));
        edit.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
        return edit;
    }

    private static void addButtons(LinearLayout card, String[] buttonTexts, EngineThemeColors.Palette c, ButtonClickCallback callback) {
        Context context = card.getContext();
        String[] texts = buttonTexts != null ? buttonTexts : new String[]{EngineUiText.get(context, R.string.engine_ok)};

        LinearLayout buttonRow = new LinearLayout(context);
        boolean stacked = texts.length > 3;
        buttonRow.setOrientation(stacked ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        if (card.getChildCount() != 0) rowParams.topMargin = dp(context, 16);
        int buttonHeight = dp(context, BUTTON_HEIGHT_DP);
        for (int i = 0; i < texts.length; i++) {
            final int index = i;
            Button button = new Button(context);
            button.setText(EngineUiText.localizeCommonDialogText(context, texts[i]));
            button.setGravity(Gravity.CENTER);
            button.setAllCaps(false);
            button.setTextSize(BUTTON_TEXT_SP);
            button.setTypeface(null, Typeface.BOLD);
            button.setPadding(dp(context, 4), 0, dp(context, 4), 0);
            button.setMinimumWidth(0);
            button.setMinimumHeight(0);
            button.setStateListAnimator(null);
            button.setSoundEffectsEnabled(false);
            button.setHapticFeedbackEnabled(false);
            button.setTextColor(i == 0 ? c.getOnPrimary() : c.getPrimary());
            button.setBackgroundTintList(null);
            button.setBackground(roundedRect(i == 0 ? c.getPrimary() : c.getCard(),
                    dp(context, BUTTON_RADIUS_DP)));
            button.setOnClickListener(v -> callback.onClicked(index));
            LinearLayout.LayoutParams params = stacked
                    ? new LinearLayout.LayoutParams(-1, buttonHeight)
                    : new LinearLayout.LayoutParams(0, buttonHeight, 1f);
            if (i > 0) {
                if (stacked) params.topMargin = dp(context, 8);
                else params.setMarginStart(dp(context, 8));
            }
            buttonRow.addView(button, params);
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

    private interface ButtonClickCallback {
        void onClicked(int index);
    }

    /** 输入框原文 → 回调 inputText 的转换（数值入口用于解析与钳制）。 */
    private interface InputTextResolver {
        String resolve(int which, String rawText);
    }
}
