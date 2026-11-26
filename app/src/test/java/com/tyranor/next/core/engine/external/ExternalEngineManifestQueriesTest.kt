package com.tyranor.next.core.engine.external

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android 11+ hides packages that the manifest does not declare in `<queries>`, so an external engine module
 * whose package is missing there always looks "not installed" and its launch Intent cannot resolve.
 */
class ExternalEngineManifestQueriesTest {
    private val manifest: String by lazy {
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        )
        candidates.first { it.isFile }.readText(Charsets.UTF_8)
    }

    private fun declaredQueryPackages(): Set<String> {
        val queries = Regex("<queries>(.*?)</queries>", RegexOption.DOT_MATCHES_ALL).find(manifest)?.groupValues?.get(1)
            ?: error("no <queries> block in the manifest")
        return Regex("""<package\s+android:name="([^"]+)"\s*/>""").findAll(queries).map { it.groupValues[1] }.toSet()
    }

    @Test
    fun everyExternalEngineModulePackageIsVisibleToTheApp() {
        val declared = declaredQueryPackages()
        val missing = ExternalEngineModuleRegistry.modules.map { it.packageName }.filterNot { it in declared }
        assertTrue("module packages missing from manifest <queries>: $missing", missing.isEmpty())
    }

    @Test
    fun impactoHostPackageIsDeclared() {
        assertTrue(ImpactoHostContract.PACKAGE in declaredQueryPackages())
    }

    @Test
    fun easyRpgHostPackageIsDeclared() {
        assertTrue(EasyRpgHostContract.PACKAGE in declaredQueryPackages())
    }
}
