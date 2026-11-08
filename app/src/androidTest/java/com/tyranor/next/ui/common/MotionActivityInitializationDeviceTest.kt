package com.tyranor.next.ui.common

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.patch.KrkrOnlinePatchActivity
import com.tyranor.next.ui.settings.PerGameSettingsActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies isolated startup and permission-result contracts without launching games or permission UI. */
class MotionActivityInitializationDeviceTest {
    /** An unanswered request stays distinct from a real denial so only explicit answers become permanent. */
    @Test
    fun notificationPermissionContractDistinguishesInterruptionFromExplicitAnswers() {
        MotionDeviceAcceptance.require(enhanced = null)
        val contract = ActivityResultContracts.RequestMultiplePermissions()
        assertTrue(contract.parseResult(Activity.RESULT_CANCELED, null).isEmpty())
        assertTrue(contract.parseResult(Activity.RESULT_OK, Intent()).isEmpty())
        for (grantResult in listOf(PackageManager.PERMISSION_GRANTED, PackageManager.PERMISSION_DENIED)) {
            val data = Intent()
                .putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSIONS, arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                .putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSION_GRANT_RESULTS, intArrayOf(grantResult))
            assertEquals(
                mapOf(Manifest.permission.POST_NOTIFICATIONS to (grantResult == PackageManager.PERMISSION_GRANTED)),
                contract.parseResult(Activity.RESULT_OK, data),
            )
        }
    }

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
