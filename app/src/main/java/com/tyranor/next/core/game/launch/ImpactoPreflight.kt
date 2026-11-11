package com.tyranor.next.core.game.launch

/** The current pinned runtime is ARM64 / API 28 and requests an actual GLES 3.2 context. */
object ImpactoPreflight {
    const val MIN_API = 28
    const val MIN_GLES = 0x30002

    fun check(api: Int, abis: List<String>, glesVersion: Int?): ImpactoPrepareError? = when {
        api < MIN_API -> ImpactoPrepareError.ANDROID_VERSION
        "arm64-v8a" !in abis -> ImpactoPrepareError.ABI
        glesVersion == null || glesVersion < MIN_GLES -> ImpactoPrepareError.GLES
        else -> null
    }
}
