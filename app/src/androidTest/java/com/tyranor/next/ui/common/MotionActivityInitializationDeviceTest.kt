package com.tyranor.next.ui.common

import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.patch.KrkrOnlinePatchActivity
import com.tyranor.next.ui.settings.PerGameSettingsActivity
import org.junit.Assert.assertFalse
import org.junit.Test

/** Verifies the pre-Compose finish path using invalid input, without launching a game. */
class MotionActivityInitializationDeviceTest {
    /** Waits for actual mode initialization, including when Activity creation is delayed by the system. */
    @Test
    fun invalidInputActivitiesLoadOrdinaryModeBeforeFinishing() {
        MotionDeviceAcceptance.require(enhanced = null)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val previous = AppSettingsStore.isMotionEnhanced(context)
        try {
            AppSettingsStore.setMotionEnhanced(context, false)
            for (activity in listOf(PerGameSettingsActivity::class.java, KrkrOnlinePatchActivity::class.java)) {
                instrumentation.runOnMainSync { MotionMode.sync(true) }
                context.startActivity(Intent(context, activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                val deadline = SystemClock.uptimeMillis() + 5_000L
                var enhanced = true
                while (SystemClock.uptimeMillis() < deadline) {
                    instrumentation.runOnMainSync { enhanced = MotionMode.enhanced }
                    if (!enhanced) break
                    Thread.sleep(50)
                }
                assertFalse("${activity.simpleName} must load the persisted mode before its early finish", enhanced)
            }
        } finally {
            AppSettingsStore.setMotionEnhanced(context, previous)
            instrumentation.runOnMainSync { MotionMode.sync(previous) }
        }
    }
}
