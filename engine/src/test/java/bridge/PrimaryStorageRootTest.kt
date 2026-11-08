package bridge

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [PrimaryStorageRoot]：JVM 环境下 android API 为 stub，解析链必须安全兜底到
 * [PrimaryStorageRoot.DEFAULT]。设备上的 per-user 解析行为（安全文件夹挂载于
 * /storage/emulated/<uid>）由实机验证覆盖（issue #152）。
 */
class PrimaryStorageRootTest {

    @Test
    fun jvmEnvironmentFallsBackToDefaultRoot() {
        assertEquals(PrimaryStorageRoot.DEFAULT, PrimaryStorageRoot.resolve(null))
        assertEquals(PrimaryStorageRoot.resolve(null), PrimaryStorageRoot.get())
    }
}
