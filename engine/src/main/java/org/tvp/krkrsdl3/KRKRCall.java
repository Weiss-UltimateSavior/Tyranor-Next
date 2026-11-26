package org.tvp.krkrsdl3;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import com.core.engine.EngineThemeColors;

import org.libsdl3.app.SDLActivity;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import org.tvp.kirikiri2.KrDialogStyle;

public class KRKRCall {
    /**
     * 输入对话框
     *
     * mInputResult/mInputResultCode 由 UI 线程写入、native 线程（WaitInputResult）读取，
     * 跨线程 happens-before 由 CountDownLatch.await/countDown 保证；加 volatile 双保险（ARM 弱内存模型）。
     */
    private static Dialog mInputDialog = null;
    private static volatile String mInputResult = "";
    private static volatile int mInputResultCode = -1;
    private static volatile CountDownLatch mInputLatch = new CountDownLatch(1);

    public static void ShowInputBox(String title, String prompt, String text, String[] buttons) {
        final Activity act = SDLActivity.getContext();
        if (act == null) { cancelPendingInput(); return; }

        mInputResult = "";
        mInputResultCode = -1;
        final CountDownLatch latch = new CountDownLatch(1);
        mInputLatch = latch;

        act.runOnUiThread(() -> {
            if (mInputLatch != latch || latch.getCount() == 0) return;
            if (act.isFinishing() || act.isDestroyed()) { latch.countDown(); return; }
            if (mInputDialog != null) mInputDialog.dismiss();
            String[] labels = buttons != null && buttons.length > 2
                    ? Arrays.copyOf(buttons, 2) : buttons;
            try {
                Dialog shown = KrDialogStyle.showInputBox(act, title, prompt, text == null ? "" : text,
                        labels, (which, value) -> {
                            if (mInputLatch != latch) return;
                            mInputResult = which == 0 ? value : "";
                            mInputResultCode = which == 0 ? 0 : -1;
                            mInputDialog = null;
                            latch.countDown();
                        });
                mInputDialog = shown;
                shown.setOnDismissListener(d -> {
                    if (mInputDialog == shown) mInputDialog = null;
                    latch.countDown();
                });
            } catch (RuntimeException failure) {
                Log.w("KRKRCall", "Cannot display input box", failure);
                latch.countDown();
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
        final Dialog dialog = mInputDialog;
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
        try {
            mInputLatch.await();
        } catch (InterruptedException e) {
            // 等待被中断（宿主销毁/系统回收）：恢复中断标记并按取消处理，不静默吞异常
            Thread.currentThread().interrupt();
            return -1;
        }
        return mInputResultCode;
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
}
