package com.tyranor.next.ui.common

import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.ui.common.motion.MotionMode

/** Keeps motion acceptance tests confined to an explicitly enabled, isolated installation. */
internal object MotionDeviceAcceptance {
    const val ARGUMENT = "motion_device_acceptance"
    const val TARGET_PACKAGE_ARGUMENT = "motion_target_package"

    /** Fails closed on missing opt-in or a mismatched target; null leaves mode restoration to the caller. */
    fun require(enhanced: Boolean? = true) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        check(arguments.getString(ARGUMENT) == "true") {
            "Motion device acceptance requires explicit '-e $ARGUMENT true'; no motion checks were run. " +
                "See app/src/androidTest/README.md."
        }
        val expectedPackage = arguments.getString(TARGET_PACKAGE_ARGUMENT)?.trim().orEmpty()
        check(expectedPackage.startsWith("com.tyranor.next.") && !expectedPackage.endsWith(".test")) {
            "Set '-e $TARGET_PACKAGE_ARGUMENT <isolated applicationId>' to a dedicated com.tyranor.next.* package. " +
                "The production com.tyranor.next and instrumentation .test packages are never accepted."
        }
        val actualPackage = instrumentation.targetContext.packageName
        check(actualPackage == expectedPackage) {
            "Motion target mismatch: expected '$expectedPackage' from $TARGET_PACKAGE_ARGUMENT, actual '$actualPackage'. " +
                "Build and install both APKs with TYRANOR_APPLICATION_ID=$expectedPackage; no motion checks were run."
        }
        if (enhanced != null) instrumentation.runOnMainSync { MotionMode.sync(enhanced) }
    }
}
