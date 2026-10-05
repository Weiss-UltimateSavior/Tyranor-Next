package com.tyranor.next.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Web 首页地址规范化：协议补全/小写、主机与端口校验、非法输入拒绝。 */
class HomeWebUrlsTest {

    @Test
    fun prependsHttpsWhenSchemeMissing() {
        assertEquals("https://www.letmoe.com", HomeWebUrls.normalize("www.letmoe.com"))
        assertEquals("https://letmoe.com/path?q=1", HomeWebUrls.normalize("letmoe.com/path?q=1"))
        assertEquals("https://example.com:8080", HomeWebUrls.normalize("example.com:8080"))
    }

    @Test
    fun keepsExplicitSchemeAndLowercasesIt() {
        assertEquals("http://example.com", HomeWebUrls.normalize("http://example.com"))
        assertEquals("https://www.kungal.com", HomeWebUrls.normalize("  https://www.kungal.com  "))
        assertEquals("https://EXAMPLE.COM", HomeWebUrls.normalize("HTTPS://EXAMPLE.COM"))
    }

    @Test
    fun rejectsInvalidInputs() {
        assertNull(HomeWebUrls.normalize(""))
        assertNull(HomeWebUrls.normalize("   "))
        assertNull(HomeWebUrls.normalize("ftp://example.com"))
        assertNull(HomeWebUrls.normalize("javascript:alert(1)"))
        assertNull(HomeWebUrls.normalize("mailto:user@example.com"))
        assertNull(HomeWebUrls.normalize("file:///etc/hosts"))
        assertNull(HomeWebUrls.normalize("not a url"))
        assertNull(HomeWebUrls.normalize("https://"))
        assertNull(HomeWebUrls.normalize("https://localhost"))
        assertNull(HomeWebUrls.normalize(".com"))
        assertNull(HomeWebUrls.normalize("example..com"))
        assertNull(HomeWebUrls.normalize("exa_mple.com"))
        assertNull(HomeWebUrls.normalize("https://example.com:0"))
        assertNull(HomeWebUrls.normalize("https://example.com:badport"))
        assertNull(HomeWebUrls.normalize("https://example.com:"))
        assertNull(HomeWebUrls.normalize("https://example.com/a b"))
        assertNull(HomeWebUrls.normalize("https://user:pass@example.com"))
        assertNull(HomeWebUrls.normalize("https://" + "a".repeat(3000) + ".com"))
    }

    @Test
    fun acceptsIdnHost() {
        assertEquals("https://例子.测试", HomeWebUrls.normalize("例子.测试"))
    }

    @Test
    fun isPresetMatchesBuiltInPresets() {
        assertTrue(HomeWebUrls.isPreset(AppSettingsStore.HOME_WEB_URL_KUNGAL))
        assertTrue(HomeWebUrls.isPreset(AppSettingsStore.HOME_WEB_URL_LETMOE))
        assertFalse(HomeWebUrls.isPreset("https://example.com"))
    }
}
