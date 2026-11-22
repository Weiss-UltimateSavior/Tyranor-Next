package com.tyranor.next.ui.main

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.R
import com.tyranor.next.core.engine.external.ImpactoExternalEngineModule
import com.tyranor.next.core.net.HttpJsonResult
import com.tyranor.next.core.net.testHttpRoundTrip
import com.tyranor.next.theme.TyranorNextTheme
import com.tyranor.next.ui.common.MotionDeviceAcceptance
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MainEngineUpdateDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun opensEngineTabAndShowsUpdateAfterOnboarding() {
        MotionDeviceAcceptance.require(enhanced = null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        checkNotNull(context.packageManager.getPackageInfo(ImpactoExternalEngineModule.packageName, 0).versionName)
        val engineLabel = context.getString(R.string.nav_engine)
        val updateStatus = context.getString(R.string.engine_module_update_available)
        val requests = AtomicInteger()
        val source = ImpactoExternalEngineModule.updateSource
        testHttpRoundTrip = { url, _, _, _ ->
            if (url == "https://api.github.com/repos/${source.repository}/releases/latest") {
                requests.incrementAndGet()
                HttpJsonResult(200, JSONObject()
                    .put("tag_name", "${source.tagPrefix}9999.0")
                    .put("assets", JSONArray().put(JSONObject().put("name", "Tyranor-Next-MAGES.apk"))))
            } else HttpJsonResult(404, null)
        }
        try {
            compose.setContent { TyranorNextTheme { MainScreen(coachPage = null) } }
            compose.waitForIdle()
            assertEquals("The hidden engine tab must not check for updates", 0, requests.get())

            compose.onNodeWithContentDescription(engineLabel).performClick()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("MAGES."))
            compose.waitUntil(5_000) {
                compose.onAllNodesWithContentDescription(updateStatus).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription(updateStatus).assertIsDisplayed()
            compose.onNodeWithText("MAGES.").performClick()
            compose.onNodeWithText(context.getString(R.string.engine_module_download_latest)).assertIsDisplayed()
            assertEquals(1, requests.get())
        } finally {
            testHttpRoundTrip = null
        }
    }
}
