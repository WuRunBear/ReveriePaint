package com.reverie.paint.core.comfy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComfyErrorClassifierTest {

    @Test
    fun `401 with credentials sent and a www-authenticate challenge is auth failed`() {
        assertEquals(
            ComfyConnectionError.AuthFailed,
            ComfyErrorClassifier.classify(401, sentAuth = true, hasWwwAuthenticateHeader = true),
        )
    }

    @Test
    fun `401 without credentials sent is auth not configured`() {
        assertEquals(
            ComfyConnectionError.AuthNotConfigured,
            ComfyErrorClassifier.classify(401, sentAuth = false, hasWwwAuthenticateHeader = true),
        )
        assertEquals(
            ComfyConnectionError.AuthNotConfigured,
            ComfyErrorClassifier.classify(401, sentAuth = false, hasWwwAuthenticateHeader = false),
        )
    }

    @Test
    fun `401 without a www-authenticate header but with credentials sent is still auth failed`() {
        assertEquals(
            ComfyConnectionError.AuthFailed,
            ComfyErrorClassifier.classify(401, sentAuth = true, hasWwwAuthenticateHeader = false),
        )
    }

    @Test
    fun `server errors keep their status code`() {
        assertEquals(ComfyConnectionError.ServerError(500), ComfyErrorClassifier.classify(500, false, false))
        assertEquals(ComfyConnectionError.ServerError(502), ComfyErrorClassifier.classify(502, false, false))
        assertEquals(ComfyConnectionError.ServerError(404), ComfyErrorClassifier.classify(404, false, false))
        assertEquals(ComfyConnectionError.ServerError(422), ComfyErrorClassifier.classify(422, true, false))
    }

    @Test
    fun `forbidden with credentials sent is auth failed`() {
        assertEquals(
            ComfyConnectionError.AuthFailed,
            ComfyErrorClassifier.classify(403, sentAuth = true, hasWwwAuthenticateHeader = false),
        )
    }

    @Test
    fun `forbidden with a www-authenticate challenge is auth failed`() {
        assertEquals(
            ComfyConnectionError.AuthFailed,
            ComfyErrorClassifier.classify(403, sentAuth = false, hasWwwAuthenticateHeader = true),
        )
    }

    @Test
    fun `forbidden with no auth context stays a server error`() {
        assertEquals(
            ComfyConnectionError.ServerError(403),
            ComfyErrorClassifier.classify(403, sentAuth = false, hasWwwAuthenticateHeader = false),
        )
    }

    @Test
    fun `wrong credentials and missing credentials are distinguishable`() {
        // 实测: ComfyUI 对两者都回 401, 只能靠「是否真的发了认证头」区分
        val wrongCreds = ComfyErrorClassifier.classify(401, sentAuth = true, hasWwwAuthenticateHeader = true)
        val noCreds = ComfyErrorClassifier.classify(401, sentAuth = false, hasWwwAuthenticateHeader = true)
        assertTrue(wrongCreds is ComfyConnectionError.AuthFailed)
        assertTrue(noCreds is ComfyConnectionError.AuthNotConfigured)
    }
}
