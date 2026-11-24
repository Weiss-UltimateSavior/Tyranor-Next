package com.core.ethornell;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.core.engine.EngineThemeColors;
import com.core.engine.EngineUiText;
import com.core.engine.R;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.tvp.kirikiri2.KrDialogStyle;

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
    private final EngineThemeColors.Palette colors;
    private final List<EditText> fields = new ArrayList<>();
    private Listener listener;
    private Dialog dialog;
    private boolean finished;

    private EthornellHostDialog(Activity activity, EthornellDialogProtocol.Request request) {
        this.activity = activity;
        this.request = request;
        this.colors = EngineThemeColors.fromIntent(activity.getIntent());
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

    void show() {
        if (dialog != null || finished) return;
        if (listener == null) throw new IllegalStateException("listener missing");
        boolean acceptsInput = request != null && !"select".equals(request.type);
        String cancel = text(R.string.engine_cancel);
        String[] buttons = acceptsInput ? new String[]{text(R.string.engine_ok), cancel} : new String[]{cancel};
        Dialog shown;
        if (request == null) {
            shown = KrDialogStyle.createDialog(activity, text(R.string.engine_ethornell_dialog_unavailable),
                    text(R.string.engine_ethornell_dialog_unavailable_detail), null, buttons, true, false,
                    (d, index) -> cancel());
        } else {
            LinearLayout form = new LinearLayout(activity);
            form.setOrientation(LinearLayout.VERTICAL);
            if (acceptsInput) addFields(form);
            else addOptions(form);
            String title = request.title.isEmpty()
                    ? text("date".equals(request.type) ? R.string.engine_ethornell_dialog_date_title
                            : R.string.engine_ethornell_dialog_default_title)
                    : request.title;
            shown = KrDialogStyle.createContentDialog(activity, title, request.prompt, form, buttons, true, false,
                    (d, index) -> finish(acceptsInput && index == 0 ? submit() : EthornellDialogProtocol.cancel()));
            if (acceptsInput && !fields.isEmpty()) KrDialogStyle.focusInput(shown, fields.get(0));
        }
        shown.setOnCancelListener(v -> cancel());
        shown.setOnDismissListener(v -> cancel());
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
                    ? text(R.string.engine_ethornell_dialog_empty_option) : option, 14, colors.getText());
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
        int inputType = numeric ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED
                : InputType.TYPE_CLASS_TEXT;
        EditText field = KrDialogStyle.createInput(activity, initial, inputType);
        field.setHint(hint);
        field.setSoundEffectsEnabled(false);
        field.setHapticFeedbackEnabled(false);
        fields.add(field);
        return field;
    }

    private LinearLayout.LayoutParams fieldParams(int index) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (index > 0) params.topMargin = dp(8);
        return params;
    }

    private TextView textView(String value, int sp, int color) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    private GradientDrawable rowBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(colors.getCard());
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
