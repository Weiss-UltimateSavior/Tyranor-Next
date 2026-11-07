package com.tyranor.next.ui.common

import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.ui.common.motion.MotionMode
import org.junit.Assume.assumeTrue

/** Keeps motion acceptance tests confined to an explicitly enabled, isolated installation. */
internal object MotionDeviceAcceptance {
    const val TARGET_PACKAGE = "com.tyranor.next.motionv2"
    const val ARGUMENT = "motion_device_acceptance"

    /** Skips unapproved targets; a null [enhanced] leaves mode setup and restoration to the caller. */
    fun require(enhanced: Boolean? = true) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(instrumentation.targetContext.packageName == TARGET_PACKAGE)
        assumeTrue(InstrumentationRegistry.getArguments().getString(ARGUMENT) == "true")
        if (enhanced != null) instrumentation.runOnMainSync { MotionMode.sync(enhanced) }
    }
}
