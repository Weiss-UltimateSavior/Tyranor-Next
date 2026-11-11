package com.tyranor.next.core.game.launch

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Install/wait for native packs before preparation publishes its short-lived host dispatch lease. */
internal suspend fun <T : Any> prepareImpactoAfterRuntime(
    ensureRuntime: suspend () -> Boolean,
    prepare: suspend () -> T,
): T? {
    currentCoroutineContext().ensureActive()
    if (!ensureRuntime()) return null
    currentCoroutineContext().ensureActive()
    return prepare()
}
