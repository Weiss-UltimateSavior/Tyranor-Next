package com.tyranor.next.core.net

import org.junit.Assert.assertEquals
import org.junit.Test

class HttpJsonSanitizeTest {

    @Test
    fun sanitizeUrlForLogStripsQueryAndFragment() {
        assertEquals(
            "https://api.example.com/v2/works/123",
            sanitizeUrlForLog("https://api.example.com/v2/works/123?access_token=secret&foo=bar#section"),
        )
        assertEquals(
            "https://api.example.com/v2/works/123",
            sanitizeUrlForLog("https://api.example.com/v2/works/123#token=abc"),
        )
        assertEquals(
            "https://api.example.com/v2/works/123",
            sanitizeUrlForLog("https://api.example.com/v2/works/123"),
        )
    }
}
