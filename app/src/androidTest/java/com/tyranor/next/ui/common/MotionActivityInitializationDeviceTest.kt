package com.tyranor.next.ui.common

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.patch.KrkrOnlinePatchActivity
import com.tyranor.next.ui.settings.PerGameSettingsActivity
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Verifies the pre-Compose finish path using invalid input, without launching a game. */
class MotionActivityInitializationDeviceTest {
    @Test
    fun invalidInputActivitiesLoadOrdinaryModeBeforeFinishing() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(context.packageName == "com.tyranor.next.motionv2")
        assumeTrue(InstrumentationRegistry.getArguments().getString("motion_device_acceptance") == "true")
        val previous = AppSettingsStore.isMotionEnhanced(context)
        try {
            AppSettingsStore.setMotionEnhanced(context, false)
            for (activity in listOf(PerGameSettingsActivity::class.java, KrkrOnlinePatchActivity::class.java)) {
                instrumentation.runOnMainSync { MotionMode.sync(true) }
                context.startActivity(Intent(context, activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    assertFalse("${activity.simpleName} must load the persisted mode before its early finish", MotionMode.enhanced)
                }
            }
        } finally {
            AppSettingsStore.setMotionEnhanced(context, previous)
            instrumentation.runOnMainSync { MotionMode.sync(previous) }
        }
    }
}
