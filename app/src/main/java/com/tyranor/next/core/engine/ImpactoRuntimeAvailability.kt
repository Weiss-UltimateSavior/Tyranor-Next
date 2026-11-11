package com.tyranor.next.core.engine

import android.content.Context
import com.core.impacto.ImpactoLaunchPreparation

/** Core facade keeps native host packages out of Compose and never equates code with a bundled runtime. */
object ImpactoRuntimeAvailability {
    fun isBundled(context: Context): Boolean = ImpactoLaunchPreparation.isRuntimeBundled(context)
}
