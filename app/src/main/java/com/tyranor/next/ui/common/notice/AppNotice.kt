package com.tyranor.next.ui.common.notice

import android.content.Context
import androidx.annotation.StringRes

/**
 * 应用内提示条：「增强」动效档下替代系统 `Toast`（普通档仍是系统 Toast，与原版一致）。
 *
 * 用法与 `Toast.makeText(context, text, duration).show()` 一一对应，多一个 [Kind]：
 * ```
 * AppNotice.makeText(context, R.string.engine_settings_saved, Toast.LENGTH_SHORT, AppNotice.Kind.Success).show()
 * ```
 * 提示条画在窗口顶部（不会被底栏 / 弹窗卡 / 抽屉 / 输入法挡住），弹窗打开时画在弹窗窗口里、遮罩之上；
 * 当前没有可用的界面窗口（后台完成、没有界面的 Activity、抽屉打开期间）时自动退回系统 Toast，所以任何时候都不会丢提示。
 * 位置、进退场、驻留时长、替换 / 排队规则见 [NoticeCenter] 与 [NoticeHost]。
 */
object AppNotice {
    /**
     * 提示的性质，决定图标与重音：
     * - [Success]：已完成（正文色勾）；
     * - [Failure]：失败（错误色叉，整条横摇一下，驻留更久）；
     * - [Info]：中性提示（正文色 i）；
     * - [Limit]：操作被拒绝 / 受限的说明（次级色感叹号，整条轻摇）；
     * - [Auto]：仅对 `@StringRes` 重载有效，按资源名里的关键词推断（见 [inferKind]）；新代码请显式传类型。
     */
    enum class Kind { Auto, Success, Failure, Info, Limit }

    /** [makeText] 的返回值，`show()` 才真正显示（与 `Toast` 一致）。 */
    class Handle internal constructor(
        private val context: Context,
        private val text: CharSequence,
        private val duration: Int,
        private val kind: Kind,
    ) {
        fun show() = NoticeCenter.show(context, text, duration, kind)
    }

    /** [duration] 取 `Toast.LENGTH_SHORT` / `Toast.LENGTH_LONG`：决定驻留时长的基准。 */
    fun makeText(context: Context, text: CharSequence, duration: Int, kind: Kind = Kind.Info): Handle =
        Handle(context, text, duration, if (kind == Kind.Auto) Kind.Info else kind)

    fun makeText(context: Context, @StringRes resId: Int, duration: Int, kind: Kind = Kind.Auto): Handle {
        val resolved = if (kind == Kind.Auto) inferKind(runCatching { context.resources.getResourceEntryName(resId) }.getOrDefault("")) else kind
        return Handle(context, context.getString(resId), duration, resolved)
    }

    /**
     * 按资源 / 变量名里的关键词推断性质（命名约定：`*_failed` / `*_saved` / `*_running` …）。
     * 先判限制，再判失败，再判成功，其余为 [Kind.Info]。
     */
    fun inferKind(name: String): Kind {
        val n = name.lowercase()
        fun has(vararg keys: String) = keys.any { n.contains(it) }
        return when {
            has("running", "busy", "full", "limit", "conflict", "exists", "already", "unsupported", "require") -> Kind.Limit
            has("fail", "error", "unavailable", "invalid", "denied", "missing", "cannot", "not_found") -> Kind.Failure
            has("saved", "imported", "installed", "success", "done", "enabled", "copied", "exported", "written", "applied", "updated") -> Kind.Success
            else -> Kind.Info
        }
    }
}
