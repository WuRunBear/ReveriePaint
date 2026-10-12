package com.reverie.paint.core.comfy

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComfyAuthTest {

    private fun creds(
        user: String?,
        pass: String?,
        extra: Map<String, String> = emptyMap(),
    ) = ComfyCredentials("http://x.com", user, pass, extra)

    @Test
    fun `base64 matches known vector user colon pass`() {
        assertEquals("dXNlcjpwYXNz", ComfyAuth.encodeBase64("user:pass"))
    }

    @Test
    fun `base64 matches rfc4648 test vectors`() {
        assertEquals("", ComfyAuth.encodeBase64(""))
        assertEquals("Zg==", ComfyAuth.encodeBase64("f"))
        assertEquals("Zm8=", ComfyAuth.encodeBase64("fo"))
        assertEquals("Zm9v", ComfyAuth.encodeBase64("foo"))
        assertEquals("Zm9vYg==", ComfyAuth.encodeBase64("foob"))
        assertEquals("Zm9vYmE=", ComfyAuth.encodeBase64("fooba"))
        assertEquals("Zm9vYmFy", ComfyAuth.encodeBase64("foobar"))
    }

    @Test
    fun `base64 handles the plus and slash alphabet`() {
        assertEquals("5LK+", ComfyAuth.encodeBase64("\u4cbe"))
        val slash = ComfyAuth.encodeBase64("!\u00ff")
        assertEquals(Base64.getEncoder().encodeToString("!\u00ff".toByteArray(Charsets.UTF_8)), slash)
        assertTrue("expected a slash in $slash", slash.contains('/'))
    }

    @Test
    fun `base64 encodes utf8 text`() {
        assertEquals("5L2g5aW9", ComfyAuth.encodeBase64("你好"))
    }

    @Test
    fun `base64 agrees with the jdk reference implementation`() {
        // 本实现是为了兼容 minSdk 23 而手写的，JVM 单元测试里可以拿 JDK 实现做交叉验证
        val samples = buildList {
            repeat(200) { len ->
                add("a".repeat(len))
                add("user:$len:密码🔐")
            }
            add("你好，世界")
            add("\u0000\u0001\u007f\u0080\u00ff")
            add("192.168.1.5:8188")
        }
        for (sample in samples) {
            assertEquals(
                sample,
                Base64.getEncoder().encodeToString(sample.toByteArray(Charsets.UTF_8)),
                ComfyAuth.encodeBase64(sample),
            )
        }
    }

    @Test
    fun `base64 length is always padded to a multiple of four`() {
        for (len in 0..64) {
            val encoded = ComfyAuth.encodeBase64("a".repeat(len))
            assertEquals("len=$len -> $encoded", 0, encoded.length % 4)
        }
    }

    @Test
    fun `username and password produce a basic authorization header`() {
        val headers = ComfyAuth.buildHeaders(creds("user", "pass"))
        assertEquals(mapOf("Authorization" to "Basic dXNlcjpwYXNz"), headers)
    }

    @Test
    fun `empty password still produces a header`() {
        assertEquals("Basic dXNlcjo=", ComfyAuth.buildHeaders(creds("user", ""))["Authorization"])
    }

    @Test
    fun `missing username or password yields no header`() {
        assertTrue(ComfyAuth.buildHeaders(creds(null, "pass")).isEmpty())
        assertTrue(ComfyAuth.buildHeaders(creds("user", null)).isEmpty())
        assertTrue(ComfyAuth.buildHeaders(creds(null, null)).isEmpty())
        assertTrue(ComfyAuth.buildHeaders(creds("   ", "pass")).isEmpty())
        assertTrue(ComfyAuth.buildHeaders(creds("", "")).isEmpty())
        assertNull(ComfyAuth.basicHeader(creds(null, null)))
    }

    @Test
    fun `chinese credentials are utf8 base64 encoded`() {
        val expected = "Basic " + Base64.getEncoder().encodeToString("你好:密码".toByteArray(Charsets.UTF_8))
        assertEquals(expected, ComfyAuth.buildHeaders(creds("你好", "密码"))["Authorization"])
    }

    @Test
    fun `extra headers are merged`() {
        val headers = ComfyAuth.buildHeaders(
            creds("user", "pass", mapOf("X-Api-Key" to "abc", "X-Trace" to "1")),
        )
        assertEquals("Basic dXNlcjpwYXNz", headers["Authorization"])
        assertEquals("abc", headers["X-Api-Key"])
        assertEquals("1", headers["X-Trace"])
    }

    @Test
    fun `extra headers are merged even without credentials`() {
        val headers = ComfyAuth.buildHeaders(creds(null, null, mapOf("X-Api-Key" to "abc")))
        assertEquals(mapOf("X-Api-Key" to "abc"), headers)
    }

    @Test
    fun `extra headers override the generated authorization`() {
        val headers = ComfyAuth.buildHeaders(
            creds("user", "pass", mapOf("Authorization" to "Bearer token")),
        )
        assertEquals("Bearer token", headers["Authorization"])
        assertEquals(1, headers.size)
    }

    @Test
    fun `extra headers override authorization case insensitively`() {
        val headers = ComfyAuth.buildHeaders(creds("user", "pass", mapOf("authorization" to "Bearer t")))
        assertEquals("Bearer t", headers["authorization"])
        assertFalse(headers.containsKey("Authorization"))
        assertEquals(1, headers.size)
    }
}
