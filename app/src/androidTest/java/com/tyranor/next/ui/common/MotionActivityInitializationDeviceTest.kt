package com.tyranor.next.ui.common

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.MainActivity
import com.tyranor.next.R
import com.tyranor.next.core.settings.AppIconSwitcher
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
    /** Resolve the installed manifest, toggle each actual launcher alias, and restore its prior state. */
    @Suppress("DEPRECATION")
    @Test
    fun launcherIconSwitchUsesInstalledAliasesAndRetainsOneResolvableEntry() {
        MotionDeviceAcceptance.require(enhanced = null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageManager = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(context.packageName)
        // Include disabled components so this also covers an installation currently using the classic icon.
        val manifestLaunchers = packageManager.queryIntentActivities(
            launcherIntent,
            PackageManager.MATCH_DISABLED_COMPONENTS,
        ).map { it.activityInfo }
        val aliases = manifestLaunchers.filter { it.targetActivity == MainActivity::class.java.name }
        assertEquals("The installed manifest must expose both icon choices", 2, aliases.size)
        // Identify the manifest entries by their resources, rather than reconstructing production names.
        val defaultAlias = aliases.single { it.icon == R.mipmap.ic_launcher }
        val classicAlias = aliases.single { it.icon == R.mipmap.ic_launcher_classic }
        fun component(alias: android.content.pm.ActivityInfo) = ComponentName(alias.packageName, alias.name)
        val aliasComponents = aliases.map(::component).toSet()
        val originalStates = aliases.associate { alias ->
            component(alias).let { it to packageManager.getComponentEnabledSetting(it) }
        }
        // Debug builds also expose tool launchers, such as LeakCanary. They are not icon aliases.
        val otherStates = manifestLaunchers.map(::component).filterNot { it in aliasComponents }
            .associateWith { packageManager.getComponentEnabledSetting(it) }
        val enabledTools = packageManager.queryIntentActivities(launcherIntent, 0)
            .map { component(it.activityInfo) }.filterNot { it in aliasComponents }.toSet()
        fun assertToolLaunchersUnchanged(resolved: List<ComponentName>) {
            assertEquals("Icon switching must retain unrelated tool entries", enabledTools,
                resolved.filterNot { it in aliasComponents }.toSet())
            otherStates.forEach { (tool, state) ->
                assertEquals("Icon switching must not alter a debug tool", state,
                    packageManager.getComponentEnabledSetting(tool))
            }
        }
        try {
            for (classic in listOf(false, true, false)) {
                AppIconSwitcher.apply(context, classic)
                val expected = component(if (classic) classicAlias else defaultAlias)
                val resolved = packageManager.queryIntentActivities(launcherIntent, 0)
                    .map { component(it.activityInfo) }
                assertEquals("Each switch must leave one business icon alias", listOf(expected),
                    resolved.filter { it in aliasComponents })
                assertToolLaunchersUnchanged(resolved)
                val expectedIntent = Intent(launcherIntent).setComponent(expected)
                val launchActivity = packageManager.resolveActivity(expectedIntent, 0)!!.activityInfo
                assertEquals(expected, component(launchActivity))
                assertEquals(MainActivity::class.java.name, launchActivity.targetActivity)
                // Applying the same setting again must preserve the resolved launch target.
                AppIconSwitcher.apply(context, classic)
                assertEquals(expected, component(packageManager.resolveActivity(expectedIntent, 0)!!.activityInfo))
                assertToolLaunchersUnchanged(packageManager.queryIntentActivities(launcherIntent, 0)
                    .map { component(it.activityInfo) })
            }
        } finally {
            originalStates.forEach { (alias, state) ->
                packageManager.setComponentEnabledSetting(alias, state, PackageManager.DONT_KILL_APP)
            }
        }
        originalStates.forEach { (alias, state) ->
            assertEquals("The test must restore the original launcher state", state, packageManager.getComponentEnabledSetting(alias))
        }
    }

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
