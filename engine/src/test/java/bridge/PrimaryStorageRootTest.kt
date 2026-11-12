package bridge

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * [PrimaryStorageRoot]：JVM 环境下 android API 为 stub，解析链必须安全兜底到
 * [PrimaryStorageRoot.DEFAULT]。经 [PrimaryStorageRoot.probeCanonicalRoot] 注入
 * 可控输入，覆盖隔离 user 根解析、别名拼写拒绝、成功缓存与失败重试。设备上的
 * per-user 解析（安全文件夹挂载于 /storage/emulated/<uid>）由实机验证覆盖（issue #152）。
 */
class PrimaryStorageRootTest {

    @Before
    fun reset() {
        PrimaryStorageRoot.resetForTest()
    }

    @After
    fun cleanUp() {
        PrimaryStorageRoot.resetForTest()
    }

    @Test
    fun jvmEnvironmentFallsBackToDefaultRoot() {
        assertEquals(PrimaryStorageRoot.DEFAULT, PrimaryStorageRoot.resolve(null))
        assertEquals(PrimaryStorageRoot.resolve(null), PrimaryStorageRoot.get())
    }

    @Test
    fun isolatedUserRootResolvedViaCanonicalProbe() {
        PrimaryStorageRoot.probeCanonicalRoot = { "/storage/emulated/150" }
        assertEquals("/storage/emulated/150", PrimaryStorageRoot.resolve(null))
        // 缓存命中：探针改值后仍返回首次成功结果
        PrimaryStorageRoot.probeCanonicalRoot = { "/storage/emulated/151" }
        assertEquals("/storage/emulated/150", PrimaryStorageRoot.get())
    }

    @Test
    fun aliasSpellingsAreRejectedAndNotCached() {
        var calls = 0
        PrimaryStorageRoot.probeCanonicalRoot = {
            calls++
            "/storage/self/primary"
        }
        assertEquals(PrimaryStorageRoot.DEFAULT, PrimaryStorageRoot.resolve(null))
        assertEquals(PrimaryStorageRoot.DEFAULT, PrimaryStorageRoot.get())
        assertEquals(2, calls)
    }

    @Test
    fun sdcardSpellingIsRejectedButRetryCanSelfHeal() {
        PrimaryStorageRoot.probeCanonicalRoot = { "/sdcard" }
        assertEquals(PrimaryStorageRoot.DEFAULT, PrimaryStorageRoot.get())
        // 失败未缓存：探针改回真实挂载根后重算自愈
        PrimaryStorageRoot.probeCanonicalRoot = { "/storage/emulated/999" }
        assertEquals("/storage/emulated/999", PrimaryStorageRoot.get())
    }

    @Test
    fun resolutionFailureIsNotCachedAndRetries() {
        var fail = true
        PrimaryStorageRoot.probeCanonicalRoot = { if (fail) null else "/storage/emulated/150" }
        assertEquals(PrimaryStorageRoot.DEFAULT, PrimaryStorageRoot.get())
        fail = false
        assertEquals("/storage/emulated/150", PrimaryStorageRoot.get())
    }

    @Test
    fun relativeFormsAreRejected() {
        PrimaryStorageRoot.probeCanonicalRoot = { "storage/emulated/150" }
        assertEquals(PrimaryStorageRoot.DEFAULT, PrimaryStorageRoot.get())
        assertFalse(PrimaryStorageRoot.get().startsWith("storage/"))
    }

    @Test
    fun selectRootPrefersStorageManagerSource() {
        assertEquals(
            "/storage/emulated/150",
            PrimaryStorageRoot.selectRoot("/storage/emulated/150", "/storage/emulated/0"),
        )
    }

    @Test
    fun selectRootFallsBackToCanonicalWhenStorageManagerInvalid() {
        // SM 来源缺失或非挂载根拼写时回退 canonical
        assertEquals(
            "/storage/emulated/0",
            PrimaryStorageRoot.selectRoot(null, "/storage/emulated/0"),
        )
        assertEquals(
            "/storage/emulated/999",
            PrimaryStorageRoot.selectRoot("/storage/self/primary", "/storage/emulated/999"),
        )
    }

    @Test
    fun selectRootRejectsAliasAndBroadRootSpellings() {
        assertEquals("", PrimaryStorageRoot.selectRoot("/storage/emulated", "/sdcard"))
        assertEquals("", PrimaryStorageRoot.selectRoot("/storage/emulated", "/storage/self/primary"))
        assertEquals("", PrimaryStorageRoot.selectRoot(null, "/storage/emulated"))
    }

    @Test
    fun selectRootReturnsEmptyWhenAllSourcesFail() {
        assertEquals("", PrimaryStorageRoot.selectRoot(null, null))
        // 相对路径不是挂载根
        assertEquals("", PrimaryStorageRoot.selectRoot("storage/emulated/150", null))
    }
}
