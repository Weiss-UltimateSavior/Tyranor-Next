package com.tyranor.next.core.engine.external

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.scan.EngineScanner
import com.tyranor.next.core.i18n.AppLocaleController
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ShiinaRioLaunchContractDeviceTest {
    @Test
    fun buildsExplicitHostIntentWithVersionedExtras() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = context.cacheDir.resolve("shiinario-launch-${UUID.randomUUID()}").absolutePath
        val game = ScanGame(
            title = "ShiinaRio fixture",
            uri = "file://$directory",
            engine = EngineType.SHIINARIO,
            launchTarget = EngineScanner.LAUNCH_TARGET_GAME_DIR,
        )
        val intent = ShiinaRioExternalEngineModule.buildLaunchIntent(
            context,
            ExternalEngineLaunchRequest(game = game, gameDirectoryPath = "$directory/"),
        )

        assertEquals("com.tyranor.next.shiinario", intent.component?.packageName)
        assertEquals("com.tyranor.next.shiinario.ShiinaRioLaunchActivity", intent.component?.className)
        assertEquals("com.tyranor.next.action.LAUNCH_SHIINARIO", intent.action)
        assertEquals(1, intent.getIntExtra("com.tyranor.next.extra.PROTOCOL_VERSION", 0))
        assertEquals("shiinario", intent.getStringExtra("com.tyranor.next.extra.ENGINE"))
        assertEquals(directory, intent.getStringExtra("com.tyranor.next.extra.GAME_DIR"))
        assertEquals(game.title, intent.getStringExtra("com.tyranor.next.extra.GAME_NAME"))
        assertEquals(
            AppLocaleController.engineUiLanguageTag(context),
            intent.getStringExtra("com.tyranor.next.extra.LOCALE"),
        )
        val settings = JSONObject(intent.getStringExtra("com.tyranor.next.extra.SETTINGS_JSON")!!)
        assertEquals("Gpu", settings.getString("renderer"))
        assertFalse(settings.getBoolean("showFrameRate"))
        assertNull(intent.getStringExtra("com.tyranor.next.extra.LAUNCH_TARGET"))
        assertFalse(intent.hasExtra("com.tyranor.next.extra.GAME_KEY"))
    }
}
