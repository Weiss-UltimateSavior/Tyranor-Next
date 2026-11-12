package com.core.ethornell;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.core.engine.EngineUiText;
import com.core.engine.R;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Native host UI for Ethornell kind-5 dialogs.
 *
 * <p>This is a game-host window, not an app page: it intentionally uses plain Android views and
 * carries its own soft-input mode so the Activity's {@code adjustNothing} Surface is never resized.
 * Buttons and list rows have no ripple, sound or haptic feedback, matching the existing host
 * dialogs. Select submissions are explicit; no dialog answers itself.
 */
final class EthornellHostDialog {
    interface Listener {
        /** Called once with a validated answer, or with the explicit cancel object. */
        void onAnswer(String json);
        /** Called after an answer/dismissal; programmatic {@link #dismiss()} does not call it. */
        void onDismissed();
    }

    private final Activity activity;
    private final EthornellDialogProtocol.Request request;
    private final List<EditText> fields = new ArrayList<>();
    private Listener listener;
    private Dialog dialog;
    private boolean finished;

    private EthornellHostDialog(Activity activity, EthornellDialogProtocol.Request request) {
        this.activity = activity;
        this.request = request;
    }

    static EthornellHostDialog forRequest(Activity activity, EthornellDialogProtocol.Request request) {
        if (request == null) throw new IllegalArgumentException("request");
        return new EthornellHostDialog(activity, request);
    }

    static EthornellHostDialog unavailable(Activity activity) {
        return new EthornellHostDialog(activity, null);
    }

    void setListener(Listener listener) {
        if (dialog != null) throw new IllegalStateException("dialog already shown");
        this.listener = listener;
    }

    @SuppressWarnings("deprecation") // An explicit dialog-window resize is required on all supported releases.
    void show() {
        if (dialog != null || finished) return;
        if (listener == null) throw new IllegalStateException("listener missing");
        Dialog shown = new Dialog(activity);
        shown.requestWindowFeature(Window.FEATURE_NO_TITLE);
        shown.setCancelable(true);
        shown.setCanceledOnTouchOutside(false);
        shown.setContentView(build());
        Window window = shown.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.4f);
            // The Activity stays adjustNothing; only this dialog window reacts to the IME.
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            window.setGravity(Gravity.CENTER);
        }
        shown.setOnCancelListener(v -> finish(EthornellDialogProtocol.cancel()));
        shown.setOnDismissListener(v -> {
            if (!finished) finish(EthornellDialogProtocol.cancel());
        });
        shown.setOnShowListener(v -> {
            Window visible = shown.getWindow();
            if (visible != null) {
                int width = Math.min(activity.getResources().getDisplayMetrics().widthPixels - dp(32),
                        dp(600));
                visible.setLayout(Math.max(dp(240), width), ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            if (!fields.isEmpty()) {
                EditText first = fields.get(0);
                first.requestFocus();
                first.post(() -> {
                    InputMethodManager imm = inputMethod();
                    if (imm != null) imm.showSoftInput(first, InputMethodManager.SHOW_IMPLICIT);
                });
            }
        });
        dialog = shown;
        shown.show();
    }

    /** Explicit user cancellation from the host back path. */
    void cancel() { finish(EthornellDialogProtocol.cancel()); }

    /** Activity-driven dismissal (closed event, destroy, playlist reset): never sends an answer. */
    void dismiss() {
        finished = true;
        Dialog old = dialog;
        dialog = null;
        if (old == null) return;
        old.setOnCancelListener(null);
        old.setOnDismissListener(null);
        hideIme(old);
        if (old.isShowing()) old.dismiss();
    }

    private void finish(String answer) {
        if (finished) return;
        finished = true;
        Dialog old = dialog;
        dialog = null;
        if (old != null) {
            old.setOnCancelListener(null);
            old.setOnDismissListener(null);
            hideIme(old);
            if (old.isShowing()) old.dismiss();
        }
        Listener callback = listener;
        try {
            if (answer != null) callback.onAnswer(answer);
        } finally {
            callback.onDismissed();
        }
    }

    private View build() {
        FrameLayout root = new FrameLayout(activity);
        int margin = dp(24);
        root.setPadding(margin, margin, margin, margin);
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(20), dp(20), dp(16));
        panel.setBackground(panelBackground());
        scroll.addView(panel, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        String title;
        if (request == null) {
            title = text(R.string.engine_ethornell_dialog_unavailable);
        } else if (!request.title.isEmpty()) {
            title = request.title;
        } else if ("date".equals(request.type)) {
            title = text(R.string.engine_ethornell_dialog_date_title);
        } else {
            title = text(R.string.engine_ethornell_dialog_default_title);
        }
        TextView heading = textView(title, 15, Color.BLACK);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setPadding(0, 0, 0, dp(8));
        panel.addView(heading);

        if (request == null) {
            TextView message = textView(text(R.string.engine_ethornell_dialog_unavailable_detail),
                    14, 0xFF666666);
            message.setPadding(0, 0, 0, dp(4));
            panel.addView(message);
        } else {
            if (!request.prompt.isEmpty()) {
                TextView prompt = textView(request.prompt, 14, 0xFF666666);
                prompt.setPadding(0, 0, 0, dp(4));
                panel.addView(prompt);
            }
            if ("select".equals(request.type)) addOptions(panel);
            else addFields(panel);
        }

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        actions.setPadding(0, dp(12), 0, 0);
        addAction(actions, text(R.string.engine_cancel), false, () -> finish(EthornellDialogProtocol.cancel()));
        if (request != null && !"select".equals(request.type))
            addAction(actions, text(R.string.engine_ok), true, () -> finish(submit()));
        panel.addView(actions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return root;
    }

    private void addFields(LinearLayout panel) {
        if ("input".equals(request.type)) {
            for (int i = 0; i < request.fields.size(); i++) {
                EthornellDialogProtocol.Field field = request.fields.get(i);
                String hint = field.label.isEmpty() ? text(R.string.engine_ethornell_dialog_field) : field.label;
                panel.addView(editText(hint, field.initial, field.numeric), fieldParams(i));
            }
        } else if ("segmented".equals(request.type)) {
            for (int i = 0; i < request.segments; i++) {
                String hint = text(R.string.engine_ethornell_dialog_segment, i + 1, request.segments);
                panel.addView(editText(hint, "", request.numeric), fieldParams(i));
            }
        } else if ("date".equals(request.type)) {
            for (int i = 0; i < 4; i++) {
                String hint = text(R.string.engine_ethornell_dialog_date_field, i + 1);
                panel.addView(editText(hint, request.fields.get(i).initial, false), fieldParams(i));
            }
            editText(text(R.string.engine_ethornell_dialog_month),
                    Integer.toString(request.month + 1), true);
            editText(text(R.string.engine_ethornell_dialog_day),
                    Integer.toString(request.day + 1), true);
            for (int i = 4; i < fields.size(); i++) panel.addView(fields.get(i), fieldParams(i));
        }
    }

    private void addOptions(LinearLayout panel) {
        for (int i = 0; i < request.options.size(); i++) {
            String option = request.options.get(i);
            TextView row = textView(option.isEmpty()
                    ? text(R.string.engine_ethornell_dialog_empty_option) : option, 14, Color.BLACK);
            row.setPadding(dp(12), dp(12), dp(12), dp(12));
            row.setBackground(rowBackground());
            row.setClickable(true);
            row.setSoundEffectsEnabled(false);
            row.setHapticFeedbackEnabled(false);
            final int index = i;
            row.setOnClickListener(v -> finish(request.answerOrCancel(null, index, 0, 0)));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(6);
            panel.addView(row, params);
        }
    }

    private String submit() {
        if (request == null) return EthornellDialogProtocol.cancel();
        if ("date".equals(request.type)) {
            final int month;
            final int day;
            try {
                month = Integer.parseInt(fields.get(4).getText().toString().trim()) - 1;
                day = Integer.parseInt(fields.get(5).getText().toString().trim()) - 1;
            } catch (RuntimeException e) {
                return EthornellDialogProtocol.cancel();
            }
            String[] texts = new String[4];
            for (int i = 0; i < texts.length; i++) texts[i] = fields.get(i).getText().toString();
            return request.answerOrCancel(texts, 0, month, day);
        }
        String[] texts = new String[fields.size()];
        for (int i = 0; i < fields.size(); i++) texts[i] = fields.get(i).getText().toString();
        return request.answerOrCancel(texts, 0, 0, 0);
    }

    private EditText editText(String hint, String initial, boolean numeric) {
        EditText field = new EditText(activity);
        field.setHint(hint);
        field.setText(initial);
        field.setSingleLine(true);
        field.setTextSize(14);
        field.setTextColor(Color.BLACK);
        field.setHintTextColor(0xFF777777);
        field.setPadding(dp(12), dp(10), dp(12), dp(10));
        field.setBackground(fieldBackground());
        field.setSoundEffectsEnabled(false);
        field.setHapticFeedbackEnabled(false);
        if (numeric) field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        else field.setInputType(InputType.TYPE_CLASS_TEXT);
        fields.add(field);
        return field;
    }

    private LinearLayout.LayoutParams fieldParams(int index) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (index > 0) params.topMargin = dp(8);
        return params;
    }

    private void addAction(LinearLayout actions, String label, boolean strong, Runnable action) {
        TextView button = textView(label, 14, Color.BLACK);
        if (strong) button.setTypeface(null, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(16), dp(10), dp(16), dp(10));
        button.setBackground(null);
        button.setClickable(true);
        button.setSoundEffectsEnabled(false);
        button.setHapticFeedbackEnabled(false);
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = dp(8);
        actions.addView(button, params);
    }

    private TextView textView(String value, int sp, int color) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    private GradientDrawable panelBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(8));
        return background;
    }

    private GradientDrawable fieldBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFFF2F2F2);
        background.setCornerRadius(dp(8));
        return background;
    }

    private GradientDrawable rowBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFFF7F7F7);
        background.setCornerRadius(dp(8));
        return background;
    }

    private InputMethodManager inputMethod() {
        return (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
    }

    private void hideIme(Dialog old) {
        Window window = old.getWindow();
        if (window == null) return;
        InputMethodManager imm = inputMethod();
        if (imm != null) imm.hideSoftInputFromWindow(window.getDecorView().getWindowToken(), 0);
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private String text(int id, Object... args) {
        String value = EngineUiText.get(activity, id);
        return args.length == 0 ? value : String.format(Locale.ROOT, value, args);
    }
}
