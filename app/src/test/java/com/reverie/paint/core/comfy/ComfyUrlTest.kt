package com.reverie.paint.core.comfy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComfyUrlTest {

    private fun ok(input: String): String {
        val result = ComfyUrl.normalize(input)
        assertTrue("expected Ok($input) but was $result", result is ComfyUrl.UrlResult.Ok)
        return (result as ComfyUrl.UrlResult.Ok).base
    }

    private fun invalid(input: String): ComfyUrl.UrlResult.Invalid {
        val result = ComfyUrl.normalize(input)
        assertTrue("expected Invalid($input) but was $result", result is ComfyUrl.UrlResult.Invalid)
        return result as ComfyUrl.UrlResult.Invalid
    }

    @Test
    fun `missing scheme on an ip defaults to http`() {
        assertEquals("http://192.168.1.5:8188", ok("192.168.1.5:8188"))
        assertEquals("http://127.0.0.1:8188", ok("127.0.0.1:8188"))
        assertEquals("http://10.0.0.7:8188", ok("10.0.0.7:8188"))
        assertEquals("http://0.0.0.0:8188", ok("0.0.0.0:8188"))
    }

    @Test
    fun `missing scheme on localhost defaults to http`() {
        assertEquals("http://localhost:8188", ok("localhost:8188"))
        assertEquals("http://localhost", ok("localhost"))
        assertEquals("http://api.localhost:8188", ok("api.localhost:8188"))
    }

    @Test
    fun `missing scheme on a domain defaults to https`() {
        assertEquals("https://comfy.example.com", ok("comfy.example.com"))
        assertEquals("https://comfy.example.com:8188", ok("comfy.example.com:8188"))
        assertEquals("https://example.com/comfy", ok("example.com/comfy"))
    }

    @Test
    fun `existing scheme is preserved`() {
        assertEquals("http://192.168.1.5:8188", ok("http://192.168.1.5:8188"))
        assertEquals("https://comfy.example.com", ok("https://comfy.example.com"))
        assertEquals("http://Comfy.Example.com", ok("HTTP://Comfy.Example.com"))
    }

    @Test
    fun `websocket scheme is mapped back to http family`() {
        assertEquals("http://192.168.1.5:8188", ok("ws://192.168.1.5:8188"))
        assertEquals("https://comfy.example.com", ok("wss://comfy.example.com"))
    }

    @Test
    fun `trailing slashes are stripped`() {
        assertEquals("http://192.168.1.5:8188", ok("http://192.168.1.5:8188/"))
        assertEquals("http://192.168.1.5:8188", ok("http://192.168.1.5:8188///"))
        assertEquals("https://x.com/comfy", ok("https://x.com/comfy/"))
        assertEquals("http://192.168.1.5:8188", ok("192.168.1.5:8188/"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("http://192.168.1.5:8188", ok("   http://192.168.1.5:8188   "))
        assertEquals("http://192.168.1.5:8188", ok("\n 192.168.1.5:8188 \t"))
    }

    @Test
    fun `query and fragment are dropped from the base`() {
        assertEquals("http://192.168.1.5:8188", ok("http://192.168.1.5:8188/?foo=1"))
        assertEquals("http://192.168.1.5:8188", ok("http://192.168.1.5:8188#frag"))
    }

    @Test
    fun `blank input is invalid`() {
        assertTrue(invalid("").reason.isNotBlank())
        assertTrue(invalid("    ").reason.isNotBlank())
        assertTrue(invalid("/").reason.isNotBlank())
        assertTrue(invalid("http://").reason.isNotBlank())
    }

    @Test
    fun `unsupported scheme is invalid`() {
        assertTrue(invalid("ftp://comfy.example.com").reason.contains("ftp"))
        assertTrue(invalid("file:///tmp/x").reason.contains("file"))
    }

    @Test
    fun `join never produces a double slash`() {
        assertEquals("http://x.com/queue", ComfyUrl.join("http://x.com", "/queue"))
        assertEquals("http://x.com/queue", ComfyUrl.join("http://x.com/", "/queue"))
        assertEquals("http://x.com/queue", ComfyUrl.join("http://x.com", "queue"))
        assertEquals("http://x.com/queue", ComfyUrl.join("http://x.com/", "queue"))
        assertEquals("http://x.com/queue", ComfyUrl.join("http://x.com///", "///queue"))
        assertEquals("http://x.com/comfy/queue", ComfyUrl.join("http://x.com/comfy", "/queue"))
        assertEquals("http://x.com/comfy/queue", ComfyUrl.join("http://x.com/comfy/", "queue"))
    }

    @Test
    fun `join with empty path returns the base`() {
        assertEquals("http://x.com", ComfyUrl.join("http://x.com/", ""))
        assertEquals("http://x.com", ComfyUrl.join("http://x.com/", "   "))
    }

    @Test
    fun `webSocketUrl converts http to ws and https to wss`() {
        assertEquals(
            "ws://192.168.1.5:8188/ws?clientId=abc-123",
            ComfyUrl.webSocketUrl("http://192.168.1.5:8188", "abc-123"),
        )
        assertEquals(
            "wss://comfy.example.com/ws?clientId=abc",
            ComfyUrl.webSocketUrl("https://comfy.example.com", "abc"),
        )
        assertEquals(
            "wss://comfy.example.com/ws?clientId=abc",
            ComfyUrl.webSocketUrl("HTTPS://comfy.example.com", "abc"),
        )
    }

    @Test
    fun `webSocketUrl tolerates a trailing slash base`() {
        assertEquals(
            "wss://comfy.example.com/ws?clientId=abc",
            ComfyUrl.webSocketUrl("https://comfy.example.com/", "abc"),
        )
    }

    @Test
    fun `webSocketUrl url encodes the client id`() {
        assertEquals(
            "ws://x.com/ws?clientId=a+b%26c%3Dd",
            ComfyUrl.webSocketUrl("http://x.com", "a b&c=d"),
        )
    }

    @Test
    fun `webSocketUrl keeps sub path bases`() {
        assertEquals(
            "ws://x.com/comfy/ws?clientId=id",
            ComfyUrl.webSocketUrl("http://x.com/comfy", "id"),
        )
    }
}
