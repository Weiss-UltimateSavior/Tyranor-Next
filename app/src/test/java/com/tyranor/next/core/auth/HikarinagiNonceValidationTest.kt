package com.tyranor.next.core.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class HikarinagiNonceValidationTest {

    private fun jwtWithPayload(payloadJson: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"RS256"}""".toByteArray(StandardCharsets.UTF_8))
        val payload = encoder.encodeToString(payloadJson.toByteArray(StandardCharsets.UTF_8))
        return "$header.$payload.sig"
    }

    @Test
    fun skipsWhenIdTokenOrNonceBlank() {
        assertNull(HikarinagiAuthService.validateIdTokenNonceRaw(null, "nonce-1"))
        assertNull(HikarinagiAuthService.validateIdTokenNonceRaw("a.b.c", null))
        assertNull(HikarinagiAuthService.validateIdTokenNonceRaw("  ", "nonce-1"))
    }

    @Test
    fun rejectsMalformedJwtSegmentCount() {
        assertEquals(
            HikarinagiAuthError.ID_TOKEN_INVALID_FORMAT,
            HikarinagiAuthService.validateIdTokenNonceRaw("only.two", "nonce-1"),
        )
    }

    @Test
    fun rejectsMismatchedNonceAndAcceptsMatchingNonce() {
        val token = jwtWithPayload("""{"nonce":"expected-nonce"}""")
        assertNull(HikarinagiAuthService.validateIdTokenNonceRaw(token, "expected-nonce"))
        assertEquals(
            HikarinagiAuthError.ID_TOKEN_NONCE_FAILED,
            HikarinagiAuthService.validateIdTokenNonceRaw(token, "wrong-nonce"),
        )
    }

    @Test
    fun returnsParseFailedOnInvalidBase64OrJson() {
        assertEquals(
            HikarinagiAuthError.ID_TOKEN_PARSE_FAILED,
            HikarinagiAuthService.validateIdTokenNonceRaw("hdr.not_json_payload!.sig", "nonce-1"),
        )
    }
}
