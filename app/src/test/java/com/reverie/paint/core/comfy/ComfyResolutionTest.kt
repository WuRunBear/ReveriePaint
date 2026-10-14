package com.reverie.paint.core.comfy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComfyResolutionTest {

    @Test
    fun `alignTo64 floors to multiple of 64 with a 64 minimum`() {
        assertEquals(1536, ComfyResolution.alignTo64(1536))
        assertEquals(1024, ComfyResolution.alignTo64(1075))
        assertEquals(1024, ComfyResolution.alignTo64(1088 - 1))
        assertEquals(64, ComfyResolution.alignTo64(64))
        assertEquals(64, ComfyResolution.alignTo64(65))
        assertEquals(64, ComfyResolution.alignTo64(1))
        assertEquals(64, ComfyResolution.alignTo64(0))
        assertEquals(64, ComfyResolution.alignTo64(-100))
    }

    @Test
    fun `4000x2800 with HIGH tier lands on 1536x1088`() {
        val out = ComfyResolution.compute(4000, 2800, ComfyResolutionTier.HIGH)
        assertEquals(1536, out.width)
        assertEquals(1088, out.height)
        assertEquals(0.384f, out.scale, 1e-6f)
    }

    @Test
    fun `tiers scale the same source proportionally`() {
        val sd15 = ComfyResolution.compute(4000, 2800, ComfyResolutionTier.SD15)
        assertEquals(768, sd15.width)
        assertEquals(512, sd15.height)

        val sdxl = ComfyResolution.compute(4000, 2800, ComfyResolutionTier.SDXL)
        assertEquals(1024, sdxl.width)
        assertEquals(704, sdxl.height)

        val high = ComfyResolution.compute(4000, 2800, ComfyResolutionTier.HIGH)
        assertEquals(1536, high.width)
        assertEquals(1088, high.height)
    }

    @Test
    fun `small image keeps its size even with a huge tier`() {
        val out = ComfyResolution.compute(500, 300, ComfyResolutionTier.HIGH)
        assertEquals(500, out.width)
        assertEquals(300, out.height)
        assertEquals(1f, out.scale, 1e-6f)
    }

    @Test
    fun `image exactly at the tier limit keeps its size`() {
        val out = ComfyResolution.compute(1024, 768, ComfyResolutionTier.SDXL)
        assertEquals(1024, out.width)
        assertEquals(768, out.height)
        assertEquals(1f, out.scale, 1e-6f)
    }

    @Test
    fun `one pixel image is raised to the latent minimum`() {
        val out = ComfyResolution.compute(1, 1, ComfyResolutionTier.HIGH)
        assertEquals(64, out.width)
        assertEquals(64, out.height)
        assertEquals(1f, out.scale, 1e-6f)
    }

    @Test
    fun `zero and negative sizes degrade to the latent minimum`() {
        assertEquals(ComfyTargetSize(64, 64, 1f), ComfyResolution.compute(0, 0, ComfyResolutionTier.SDXL))
        assertEquals(ComfyTargetSize(64, 64, 1f), ComfyResolution.compute(-10, -20, ComfyResolutionTier.SDXL))
        assertEquals(ComfyTargetSize(64, 64, 1f), ComfyResolution.compute(0, 0, ComfyResolutionTier.ORIGINAL))
    }

    @Test
    fun `degenerate one pixel wide source still gets a one latent short edge`() {
        assertEquals(
            ComfyTargetSize(64, 1024, 1024f / 5000f),
            ComfyResolution.compute(0, 5000, ComfyResolutionTier.SDXL),
        )
    }

    @Test
    fun `square images stay square`() {
        assertEquals(
            ComfyTargetSize(1024, 1024, 0.5f),
            ComfyResolution.compute(2048, 2048, ComfyResolutionTier.SDXL),
        )
        assertEquals(
            ComfyTargetSize(1024, 1024, 1024f / 8000f),
            ComfyResolution.compute(8000, 8000, ComfyResolutionTier.SDXL),
        )
    }

    @Test
    fun `extreme landscape aspect ratio keeps a usable short edge`() {
        val out = ComfyResolution.compute(8000, 800, ComfyResolutionTier.HIGH)
        assertEquals(1536, out.width)
        assertEquals(128, out.height)
    }

    @Test
    fun `extreme portrait aspect ratio keeps orientation`() {
        val out = ComfyResolution.compute(800, 4000, ComfyResolutionTier.HIGH)
        assertEquals(320, out.width)
        assertEquals(1536, out.height)
    }

    @Test
    fun `ultra extreme aspect ratio clamps the short edge to one latent`() {
        val out = ComfyResolution.compute(10000, 10, ComfyResolutionTier.HIGH)
        assertEquals(1536, out.width)
        assertEquals(64, out.height)
    }

    @Test
    fun `original tier keeps the source size untouched`() {
        assertEquals(ComfyTargetSize(4000, 2800, 1f), ComfyResolution.compute(4000, 2800, ComfyResolutionTier.ORIGINAL))
        assertEquals(ComfyTargetSize(500, 300, 1f), ComfyResolution.compute(500, 300, ComfyResolutionTier.ORIGINAL))
        // 原图档位仍受 latent 下限保护
        assertEquals(ComfyTargetSize(64, 64, 1f), ComfyResolution.compute(1, 1, ComfyResolutionTier.ORIGINAL))
    }

    @Test
    fun `never upscales and never exceeds the tier limit`() {
        val sizes = listOf(
            1 to 1, 17 to 63, 64 to 64, 100 to 50, 500 to 300, 768 to 1365,
            1024 to 1024, 1920 to 1080, 4000 to 2800, 8000 to 8000, 800 to 4000,
            10000 to 10, 10 to 10000, 7999 to 7999, 4096 to 2304,
        )
        for (tier in ComfyResolutionTier.entries) {
            for ((w, h) in sizes) {
                val out = ComfyResolution.compute(w, h, tier)
                val label = "${w}x$h tier=$tier -> ${out.width}x${out.height}"
                val srcLong = maxOf(w, h).coerceAtLeast(1)
                val cap = maxOf(minOf(tier.maxEdge ?: srcLong, srcLong), 64)
                assertTrue("$label long edge ${maxOf(out.width, out.height)} > $cap",
                    maxOf(out.width, out.height) <= cap)
                assertTrue("$label has zero edge", out.width > 0 && out.height > 0)
                // 不缩小的分支保持原尺寸(仅套用 latent 下限), 缩放分支两条边都是 64 的倍数
                assertTrue("$label below latent minimum", out.width >= minOf(64, w) && out.height >= minOf(64, h))
                if (tier.maxEdge != null && tier.maxEdge < srcLong) {
                    assertTrue("$label width not 64-aligned", out.width % 64 == 0)
                    assertTrue("$label height not 64-aligned", out.height % 64 == 0)
                }
            }
        }
    }

    @Test
    fun `scale is reported as the requested downscale ratio`() {
        assertEquals(0.192f, ComfyResolution.compute(8000, 6000, ComfyResolutionTier.HIGH).scale, 1e-6f)
        assertEquals(1f, ComfyResolution.compute(700, 700, ComfyResolutionTier.SD15).scale, 1e-6f)
        assertEquals(0.256f, ComfyResolution.compute(6000, 6000, ComfyResolutionTier.HIGH).scale, 1e-6f)
    }

    @Test
    fun `every export resolution tier is already 64 aligned`() {
        for (res in ComfyExportResolution.entries) {
            assertEquals(res.maxEdge, ComfyResolution.alignTo64(res.maxEdge))
        }
    }

    @Test
    fun `export resolution default equals the historical HIGH tier`() {
        assertEquals(ComfyResolutionTier.HIGH.maxEdge, ComfyExportResolution.DEFAULT.maxEdge)
    }

    @Test
    fun `fromMaxEdge restores a known value and falls back to default`() {
        assertEquals(ComfyExportResolution.R4096, ComfyExportResolution.fromMaxEdge(4096))
        assertEquals(ComfyExportResolution.DEFAULT, ComfyExportResolution.fromMaxEdge(1234))
    }

    @Test
    fun `user resolution cap scales to the selected long edge`() {
        val out = ComfyResolution.compute(4000, 2800, ComfyExportResolution.R2048)
        assertEquals(2048, out.width)
        assertEquals(1408, out.height)

        // 4096 大于源图长边, 不放大, 保持原尺寸
        val noUpscale = ComfyResolution.compute(4000, 2800, ComfyExportResolution.R4096)
        assertEquals(4000, noUpscale.width)
        assertEquals(2800, noUpscale.height)
        assertEquals(1f, noUpscale.scale, 1e-6f)
    }
}
