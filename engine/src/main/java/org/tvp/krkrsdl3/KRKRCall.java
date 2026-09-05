package org.tvp.krkrsdl3;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.core.engine.EngineThemeColors;

import org.libsdl3.app.SDLActivity;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class KRKRCall {
    /**
     * 输入对话框
     *
     * mInputResult/mInputResultCode 由 UI 线程写入、native 线程（WaitInputResult）读取，
     * 跨线程 happens-before 由 CountDownLatch.await/countDown 保证；加 volatile 双保险（ARM 弱内存模型）。
     */
    private static final long WAIT_SLICE_MS = 200L;
    /** 保险丝默认值：极端异常下（对话框既未显示也未被取消）最多阻塞 10 分钟后按取消返回，避免引擎永久冻结 */
    static final long DEFAULT_MAX_WAIT_MS = 10L * 60L * 1000L;
    // 非常量以便同包回归测试注入短时限（验证保险丝未被移除）；生产路径勿改
    static volatile long maxWaitMs = DEFAULT_MAX_WAIT_MS;
    // 以下四个字段包内可见（默认 private），仅供同包回归测试重置/注入状态
    static AlertDialog mInputDialog = null;
    static volatile String mInputResult = "";
    static volatile int mInputResultCode = -1;
    // 初始即为已触发态：无弹窗时的杂散 WaitInputResult 立即按取消返回，而非永久阻塞
    static volatile CountDownLatch mInputLatch = new CountDownLatch(0);

    public static void ShowInputBox(String title, String prompt, String text, String[] buttons) {
        final Activity act = SDLActivity.getContext();
        if (act == null) {
            // 宿主未就绪：本次输入无法展示，替换为已触发 latch 立即放行 native 等待方（按取消返回），
            // 而非让等待者悬置到保险丝时限才兜底
            mInputResult = "";
            mInputResultCode = -1;
            mInputLatch = new CountDownLatch(0);
            return;
        }

        // 同步重置状态：先于 runOnUiThread，确保 native 线程 WaitInputResult 等待的是新 latch
        mInputResult = "";
        mInputResultCode = -1;
        final CountDownLatch latch = new CountDownLatch(1);
        mInputLatch = latch;

        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // 守卫：post 与执行之间 Activity 可能已被销毁（Home 键/系统回收），
                // 避免 dialog.show() 在已销毁 Activity 上抛 WindowManager$BadTokenException 闪退；
                // 同时立即放行 native 等待方，不依赖 onDestroy 的时序兜底
                if (act.isFinishing() || act.isDestroyed()) {
                    latch.countDown();
                    return;
                }
                // 主题色从 Launcher 传入的 Intent extras 读取（跟随启动器主题与深浅色）
                final EngineThemeColors.Palette colors = EngineThemeColors.fromIntent(act.getIntent());
                final float density = act.getResources().getDisplayMetrics().density;

                final AlertDialog dialog = new AlertDialog.Builder(act).create();
                dialog.setCancelable(false);

                // 根容器：圆角卡片背景 + 22dp padding，复刻 LauncherDialogFactory 视觉风格
                LinearLayout root = new LinearLayout(act);
                root.setOrientation(LinearLayout.VERTICAL);
                root.setPadding(dp(22f, density), dp(22f, density), dp(22f, density), dp(22f, density));
                root.setBackground(rounded(colors.getCard(), 20f, density));

                // 标题：16sp bold 居中
                if (title != null && title.length() != 0) {
                    TextView titleView = new TextView(act);
                    titleView.setText(title);
                    titleView.setTextColor(colors.getText());
                    titleView.setTextSize(16f);
                    titleView.setTypeface(titleView.getTypeface(), android.graphics.Typeface.BOLD);
                    titleView.setGravity(Gravity.CENTER);
                    root.addView(titleView, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                }

                // 提示：13sp 居中
                if (prompt != null && prompt.length() != 0) {
                    TextView promptView = new TextView(act);
                    promptView.setText(prompt);
                    promptView.setTextColor(colors.getTextMuted());
                    promptView.setTextSize(13f);
                    promptView.setGravity(Gravity.CENTER);
                    LinearLayout.LayoutParams promptParams = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    promptParams.topMargin = dp(14f, density);
                    root.addView(promptView, promptParams);
                }

                // 输入框：主题文字色 + 圆角描边
                final EditText editText = new EditText(act);
                editText.setText(text);
                editText.selectAll();
                editText.setTextColor(colors.getText());
                editText.setHintTextColor(colors.getTextMuted());
                editText.setTextSize(14f);
                editText.setBackground(roundedInputBackground(colors, density));
                editText.setPadding(dp(12f, density), dp(8f, density), dp(12f, density), dp(8f, density));
                LinearLayout.LayoutParams editParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                editParams.topMargin = dp(14f, density);
                root.addView(editText, editParams);

                // 按钮行：topMargin 22dp，药丸形按钮
                if (buttons.length >= 1) {
                    LinearLayout buttonRow = new LinearLayout(act);
                    buttonRow.setOrientation(LinearLayout.HORIZONTAL);
                    buttonRow.setGravity(Gravity.CENTER_VERTICAL);
                    LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    rowParams.topMargin = dp(22f, density);
                    root.addView(buttonRow, rowParams);

                    // 取消按钮（buttons[1]，可选）：card 底色 + primary 文字，左侧
                    if (buttons.length >= 2) {
                        TextView cancelBtn = pillButton(act, buttons[1],
                                colors.getCard(), colors.getPrimary(), 13f, density);
                        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(0, dp(36f, density));
                        cancelParams.weight = 1f;
                        cancelParams.rightMargin = dp(7f, density);
                        buttonRow.addView(cancelBtn, cancelParams);
                        cancelBtn.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                mInputResult = "";
                                mInputResultCode = -1;
                                mInputDialog = null;
                                dialog.dismiss();
                                latch.countDown();
                            }
                        });
                    }

                    // 确认按钮（buttons[0]）：primary 底色 + onPrimary 文字
                    TextView okBtn = pillButton(act, buttons[0],
                            colors.getPrimary(), colors.getOnPrimary(), 13f, density);
                    LinearLayout.LayoutParams okParams = new LinearLayout.LayoutParams(
                            buttons.length >= 2 ? 0 : ViewGroup.LayoutParams.MATCH_PARENT, dp(36f, density));
                    if (buttons.length >= 2) {
                        okParams.weight = 1f;
                        okParams.leftMargin = dp(7f, density);
                    }
                    buttonRow.addView(okBtn, okParams);
                    okBtn.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            mInputResult = editText.getText().toString();
                            mInputResultCode = 0;
                            mInputDialog = null;
                            dialog.dismiss();
                            latch.countDown();
                        }
                    });
                }

                mInputDialog = dialog;
                dialog.setView(root);
                dialog.show();

                // 窗口：透明背景 + 252dp 宽度（带屏幕兜底），与 LauncherDialogFactory 一致
                dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                int width = Math.min(dp(252f, density),
                        act.getResources().getDisplayMetrics().widthPixels - dp(48f, density));
                dialog.getWindow().setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);

                // 布局完成后唤起软键盘，规避部分 ROM 上 SHOW_IMPLICIT 时序失灵
                editText.requestFocus();
                editText.post(new Runnable() {
                    @Override
                    public void run() {
                        editText.requestFocus();
                        InputMethodManager imm = (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
                        if (imm != null) {
                            imm.showSoftInput(editText, 0);
                        }
                    }
                });
            }
        });
    }

    /**
     * 宿主销毁时解除 native 线程 WaitInputResult 阻塞。
     *
     * Activity 在输入弹窗未确认时被销毁（Home 键/系统回收）会导致 latch 永不 countDown，
     * native 线程自旋等待使 SDL3 onDestroy 的 mSDLThread.join 死锁。强制置完成态并 dismiss 弹窗。
     * 仅 UI 线程调用；无弹窗时亦安全。
     */
    public static void cancelPendingInput() {
        mInputResult = "";
        mInputResultCode = -1;
        final AlertDialog dialog = mInputDialog;
        mInputDialog = null;
        if (dialog != null && dialog.isShowing()) {
            // dialog.dismiss() 必须在 UI 线程执行，非 UI 线程（未来扩展调用）调用会抛异常；
            // 当前唯一调用点 onDestroy 在 UI 线程，此处防御性包裹保证线程安全
            final Activity act = SDLActivity.getContext();
            if (act == null) {
                dialog.dismiss();
            } else {
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (dialog.isShowing()) {
                            dialog.dismiss();
                        }
                    }
                });
            }
        }
        mInputLatch.countDown();
    }

    // 阻塞等待对话框关闭
    public static int WaitInputResult() {
        final long startNs = System.nanoTime();
        while (true) {
            // 快照当前 latch：期间若出现新的 ShowInputBox，旧等待立即作废，避免等错对象导致无限挂起
            final CountDownLatch latch = mInputLatch;
            try {
                if (latch.await(WAIT_SLICE_MS, TimeUnit.MILLISECONDS)) {
                    // 触发的必须是当前 latch：若期间已被新一轮 ShowInputBox 替换，
                    // 结果码可能已被重置/覆写而不可信，按取消返回而非上浮被污染的结果
                    if (mInputLatch == latch) {
                        return mInputResultCode;
                    }
                    return -1;
                }
            } catch (InterruptedException e) {
                // 等待被中断（宿主销毁/系统回收）：恢复中断标记并按取消处理，不静默吞异常
                Thread.currentThread().interrupt();
                return -1;
            }
            if (mInputLatch != latch) {
                return -1;
            }
            // 注意单位：nanoTime 差值为纳秒，maxWaitMs 为毫秒，必须经 TimeUnit 显式换算后比较
            if (System.nanoTime() - startNs > TimeUnit.MILLISECONDS.toNanos(maxWaitMs)) {
                return -1;
            }
        }
    }
    // 获取结果
    public static String GetInputResult() {
        return mInputResult;
    }

    /**
     * 菜单栏
     */
    public enum MenuItemType {
        NORMAL,
        CHECKBOX,
        SUBMENU,
        SEPARATOR
    }
    public static class MenuItemData {
        public int id;
        public String caption;
        public MenuItemType type;
        public boolean checked;
        public int order;
        public MenuItemData[] children;
        public MenuItemData(int id, String caption) {
            this.id = id;
            this.caption = caption;
            this.type = MenuItemType.NORMAL;
        }
        public MenuItemData asCheckbox(boolean checked) {
            this.type = MenuItemType.CHECKBOX;
            this.checked = checked;
            return this;
        }
        public MenuItemData asSeparator() {
            this.type = MenuItemType.SEPARATOR;
            return this;
        }
        public MenuItemData withChildren(MenuItemData... children) {
            this.type = MenuItemType.SUBMENU;
            this.children = children;
            return this;
        }
    }

    // 按钮事件回调
    static native void nativeOnMenuItemClick(int itemId, String itemCaption);
    static native void nativeOnMenuDismiss();
    static boolean s_menuItemClicked = false;

    // 显示（统一风格：圆角卡片 + 主题色菜单项，跟随启动器主题与深浅色）
    public static void showDynamicMenu(final int x, final int y, final MenuItemData[] items) {
        final Activity act = SDLActivity.getContext();
        if (act == null) return;
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // 守卫：post 与执行之间 Activity 可能已被销毁，避免 popup.showAtLocation 在已销毁 Activity 上抛 BadTokenException
                if (act.isFinishing() || act.isDestroyed()) return;
                final EngineThemeColors.Palette colors = EngineThemeColors.fromIntent(act.getIntent());
                final float density = act.getResources().getDisplayMetrics().density;
                int width = Math.min(dp(200f, density),
                        act.getResources().getDisplayMetrics().widthPixels - dp(48f, density));

                // 容器：圆角卡片背景
                LinearLayout container = new LinearLayout(act);
                container.setOrientation(LinearLayout.VERTICAL);
                container.setPadding(dp(7f, density), dp(7f, density), dp(7f, density), dp(7f, density));
                container.setBackground(rounded(colors.getCard(), 20f, density));

                final PopupWindow popup = new PopupWindow(container, width,
                        ViewGroup.LayoutParams.WRAP_CONTENT, true);
                popup.setOutsideTouchable(true);
                // 纯透明遮罩：PopupWindow 必须设置背景才能拦截外部点击并触发关闭，此处仅作遮罩不参与绘制
                popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

                s_menuItemClicked = false;
                addMenuItems(act, container, items, colors, density, popup, 0);

                popup.setOnDismissListener(new PopupWindow.OnDismissListener() {
                    @Override
                    public void onDismiss() {
                        try {
                            if (!s_menuItemClicked) {
                                nativeOnMenuDismiss();
                            }
                        } finally {
                            // 回调异常也需复位标记，避免下一次菜单误判为「未点击」
                            s_menuItemClicked = false;
                        }
                    }
                });

                // 以 decorView 为父窗口定位（必已挂载），按原生坐标展示，避免未挂载 anchor 抛 BadTokenException
                popup.showAtLocation(act.getWindow().getDecorView(), Gravity.NO_GRAVITY, x, y);
            }
        });
    }

    /** 递归构建菜单项；SUBMENU 子项展平缩进展示，保留全部回调。 */
    private static void addMenuItems(Activity act, LinearLayout container, MenuItemData[] items,
                                     EngineThemeColors.Palette colors, float density,
                                     final PopupWindow popup, int indentDp) {
        if (items == null) return;
        for (final MenuItemData item : items) {
            if (item == null) continue;
            if (item.type == MenuItemType.SEPARATOR ||
                    (item.caption != null && item.caption.equals("-"))) {
                View divider = new View(act);
                divider.setBackgroundColor((colors.getTextMuted() & 0x00FFFFFF) | 0x33000000);
                LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1f, density));
                divParams.topMargin = dp(6f, density);
                divParams.bottomMargin = dp(6f, density);
                container.addView(divider, divParams);
                continue;
            }

            String label = item.caption != null ? item.caption : "";
            boolean hasChildren = item.children != null && item.children.length > 0;
            if (item.type == MenuItemType.CHECKBOX) {
                label = (item.checked ? "✓ " : "   ") + label;
            }
            if (hasChildren) {
                label = label + "  ›";
            }

            TextView menuItem = new TextView(act);
            menuItem.setText(label);
            menuItem.setTextColor(colors.getPrimary());
            menuItem.setTextSize(13f);
            menuItem.setTypeface(menuItem.getTypeface(), android.graphics.Typeface.BOLD);
            menuItem.setGravity(Gravity.CENTER_VERTICAL);
            menuItem.setSingleLine(true);
            // 深层菜单项过长时以省略号截断，避免文字溢出卡片
            menuItem.setEllipsize(TextUtils.TruncateAt.END);
            menuItem.setPadding(dp(13f + indentDp, density), 0, dp(13f, density), 0);
            menuItem.setBackgroundColor(Color.TRANSPARENT);
            LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(34f, density));
            itemParams.bottomMargin = dp(5f, density);
            menuItem.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    s_menuItemClicked = true;
                    popup.dismiss();
                    nativeOnMenuItemClick(item.id, item.caption != null ? item.caption : "");
                }
            });
            container.addView(menuItem, itemParams);

            if (hasChildren) {
                addMenuItems(act, container, item.children, colors, density, popup, indentDp + 12);
            }
        }
    }

    /** dp 转 px（四舍五入）。 */
    private static int dp(float value, float density) {
        return (int) (value * density + 0.5f);
    }

    /** 圆角纯色背景。 */
    private static GradientDrawable rounded(int color, float radiusDp, float density) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radiusDp * density);
        return drawable;
    }

    /** 输入框背景：透明填充 + 主题文字色 33% 透明度圆角描边。 */
    private static GradientDrawable roundedInputBackground(EngineThemeColors.Palette colors, float density) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.TRANSPARENT);
        drawable.setStroke(dp(1f, density), (colors.getTextMuted() & 0x00FFFFFF) | 0x55000000);
        drawable.setCornerRadius(dp(10f, density));
        return drawable;
    }

    /** 药丸形按钮。 */
    private static TextView pillButton(Activity act, String label, int backgroundColor, int textColor,
                                       float textSize, float density) {
        TextView button = new TextView(act);
        button.setText(label);
        button.setTextColor(textColor);
        button.setTextSize(textSize);
        button.setTypeface(button.getTypeface(), android.graphics.Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setBackground(rounded(backgroundColor, 999f, density));
        return button;
    }
}
