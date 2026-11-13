package com.tyranor.next.core.engine.external

import android.content.pm.ActivityInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Confirms the generated third-party launch requests use Android's fixed landscape value. */
@RunWith(AndroidJUnit4::class)
class ExternalEngineOrientationDeviceTest {
    private companion object {
        const val TEST_APPLICATION_ID = "com.tyranor.next.startuptest"
    }

    @Test
    fun ruffleRpgMakerAndRenPyRequestsUseFixedLandscape() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue("Dedicated startup acceptance package required", context.packageName == TEST_APPLICATION_ID)
        assumeTrue("Explicit startup device-test opt-in required",
            InstrumentationRegistry.getArguments().getString("startup_test") == "true")
        val fixture = File(context.cacheDir, "external_orientation_fixture_${UUID.randomUUID()}").apply {
            check(mkdirs()) { "Could not create isolated external orientation fixture" }
            resolve("game.swf").writeText("fixture")
        }
        try {
            val cases = listOf(
                RuffleExternalEngineModule.buildLaunchIntent(
                    request(EngineType.RUFFLE, fixture, "game.swf", "external.flash"),
                ),
                RpgMakerExternalEngineModule.buildLaunchIntent(
                    request(EngineType.RPGMAKER, fixture, "[游戏目录]", "internal.rpgmvxace"),
                ),
                RenPyExternalEngineModule.buildLaunchIntent(
                    request(EngineType.RENPY, fixture, "[游戏目录]", "internal.renpy"),
                ),
            )

            cases.forEach { intent ->
                assertEquals(
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                    intent.getIntExtra(ExternalEngineContract.ORIENTATION, Int.MIN_VALUE),
                )
            }
        } finally {
            fixture.deleteRecursively()
        }
    }

    private fun request(
        engine: EngineType,
        folder: File,
        launchTarget: String,
        alias: String,
    ) = ExternalEngineLaunchRequest(
        game = ScanGame(
            title = "Orientation fixture",
            uri = "file://${folder.absolutePath}",
            engine = engine,
            launchTarget = launchTarget,
            externalModuleAlias = alias,
        ),
        gameDirectoryPath = folder.absolutePath,
        launchTarget = launchTarget,
    )
}
