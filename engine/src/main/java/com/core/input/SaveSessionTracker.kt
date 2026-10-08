package com.core.input

/**
 * 异步落盘的会话账本（纯 Kotlin，无 Android 依赖，可直接单测）。
 *
 * 编辑器把写盘放到 IO 线程后，结果回来时可能已经**过期**：用户连点了两次保存（先发的被
 * 后发的接替），或中途点了取消/退回上一页。过期结果若照常生效，就会出现「已经提示保存
 * 成功，却又弹出保存失败」「用户已离开编辑页还收到提示」「文件已写好但界面停在编辑态」。
 *
 * 因此每次发起提交分配一个会话号，宿主在**执行任何用户可见动作之前**用 [isCurrent] 校验，
 * 只有仍属当前会话的结果才继续处理。会话边界（进入/退出编辑态、发起新提交）都会清空
 * 在途状态，使更早的会话自动失效。
 */
class SaveSessionTracker {

    private var sequence = 0
    private var pending = 0

    val hasPending: Boolean get() = pending != 0

    /** 发起一次提交，返回会话号（后续结果必须原样回传做校验）。 */
    fun begin(): Int {
        val session = ++sequence
        pending = session
        return session
    }

    /** 该会话号是否仍是当前在途的提交。 */
    fun isCurrent(session: Int): Boolean = pending == session

    /**
     * 结束该会话（无论成败）。
     *
     * @return false = 该结果已过期（被新提交接替或会话已结束），调用方不得再提示或改状态
     */
    fun complete(session: Int): Boolean {
        if (pending != session) return false
        pending = 0
        return true
    }

    /** 会话边界（进入/退出编辑态）：在途提交全部作废。 */
    fun clear() {
        pending = 0
    }
}
