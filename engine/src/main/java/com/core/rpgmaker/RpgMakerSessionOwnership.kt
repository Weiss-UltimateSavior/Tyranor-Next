package com.core.rpgmaker

import java.util.concurrent.atomic.AtomicLong

/**
 * `:rpgmaker` 进程内的会话所有权。
 *
 * 该 Activity 是独立进程（`:rpgmaker`）+ `singleInstance`：旧会话 `finish()` 排队的
 * `killProcess` 会在延迟后才执行，而这段时间里进程可能已经被新会话复用（进程还活着，
 * Android 就直接拿它开新 Activity）。谁拥有这个进程只能由「期号」判定——
 * [beginSession] 每来一个新会话就推进一次，旧任务拿自己创建时的那期比对，
 * 不相等即表示进程已易主，必须放弃杀进程。
 *
 * 抽成独立类是为了让这条不变量可被 JVM 回归覆盖：Activity 本身要 Robolectric 才能测。
 */
internal class RpgMakerSessionOwnership {

    private val epoch = AtomicLong(0L)

    /** 新会话接管进程，返回它的期号。 */
    fun beginSession(): Long = epoch.incrementAndGet()

    /** 该期会话是否仍拥有进程（期号未被后来者推进）。 */
    fun isOwner(session: Long): Boolean = epoch.get() == session
}
