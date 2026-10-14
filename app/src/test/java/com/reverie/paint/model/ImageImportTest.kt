/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import com.reverie.paint.core.ImageImportHelper
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageImportTest {

    @Test
    fun `computeSampleSize maintains dimension within limit`() {
        // Under limit: no downsampling
        assertEquals(1, ImageImportHelper.computeSampleSize(4000, 3000, 8192))
        assertEquals(1, ImageImportHelper.computeSampleSize(8192, 8192, 8192))

        // Exceeding limit: power of 2 downsampling
        assertEquals(2, ImageImportHelper.computeSampleSize(12000, 8000, 8192))
        assertEquals(2, ImageImportHelper.computeSampleSize(8193, 1000, 8192))
        assertEquals(4, ImageImportHelper.computeSampleSize(20000, 30000, 8192))
    }

    @Test
    fun `computeSafeSampleSize never exceeds dimension or heap budget`() {
        val heap = 256L * 1024 * 1024
        // 小图: 不需要降采样
        assertEquals(1, ImageImportHelper.computeSafeSampleSize(3000, 2000, 8192, heap))
        // 大图: 结果只会比"纯边长口径"更保守(≥), 且解码后一定落在堆预算内
        val byDimension = ImageImportHelper.computeSampleSize(12000, 8000, 8192)
        val safe = ImageImportHelper.computeSafeSampleSize(12000, 8000, 8192, heap)
        assert(safe >= byDimension) { "safe=$safe dimension=$byDimension" }
        val decodedBytes = (12000L / safe) * (8000L / safe) * 4L
        assert(decodedBytes <= com.reverie.paint.model.MemoryBudget.decodeBudgetBytes(heap))
        // 8000x8000 = 256MB 位图在 256MB 堆上必须比"只看边长"更激进
        val safeBig = ImageImportHelper.computeSafeSampleSize(8000, 8000, 8192, heap)
        assert(safeBig >= 2) { "expected downsample for big PNG, got $safeBig" }
        val bytes = (8000L / safeBig) * (8000L / safeBig) * 4L
        assert(bytes <= com.reverie.paint.model.MemoryBudget.decodeBudgetBytes(heap))
    }

    @Test
    fun `computeSafeSampleSize degrades on unknown heap`() {
        // 堆未知(-1)时只按边长上限, 不允许把结果算成 0 或负数
        val sample = ImageImportHelper.computeSafeSampleSize(4000, 4000, 8192, -1L)
        assertEquals(1, sample)
    }

    @Test
    fun `calculateFitPlacement keeps original size and centers if within maxRatio`() {
        val docW = 1000
        val docH = 1000
        // 80% boundary is 800 x 800
        val imgW = 600
        val imgH = 400

        val placement = ImageImportHelper.calculateFitPlacement(docW, docH, imgW, imgH, 0.8f)
        assertEquals(600, placement.targetW)
        assertEquals(400, placement.targetH)
        assertEquals(200, placement.x) // (1000 - 600) / 2
        assertEquals(300, placement.y) // (1000 - 400) / 2
    }

    @Test
    fun `calculateFitPlacement scales down proportionally and centers if oversized`() {
        val docW = 1000
        val docH = 1000
        // 80% boundary is 800 x 800
        val imgW = 1600
        val imgH = 800

        val placement = ImageImportHelper.calculateFitPlacement(docW, docH, imgW, imgH, 0.8f)
        // Scaled by 800 / 1600 = 0.5
        assertEquals(800, placement.targetW)
        assertEquals(400, placement.targetH)
        assertEquals(100, placement.x) // (1000 - 800) / 2
        assertEquals(300, placement.y) // (1000 - 400) / 2
    }

    @Test
    fun `calculateFitPlacement handles tall vertical image`() {
        val docW = 1000
        val docH = 1000
        // 80% boundary is 800 x 800
        val imgW = 500
        val imgH = 2000

        val placement = ImageImportHelper.calculateFitPlacement(docW, docH, imgW, imgH, 0.8f)
        // Scaled by 800 / 2000 = 0.4
        assertEquals(200, placement.targetW)
        assertEquals(800, placement.targetH)
        assertEquals(400, placement.x) // (1000 - 200) / 2
        assertEquals(100, placement.y) // (1000 - 800) / 2
    }
    @Test
    fun `canvas-sized images preserve pixels and align to origin`() {
        for ((w, h) in listOf(1000 to 1000, 1920 to 1080, 1080 to 1920, 1001 to 777)) {
            val placement = ImageImportHelper.calculateFitPlacement(w, h, w, h, 0.8f)
            assertEquals(ImageImportHelper.FitPlacement(0, 0, w, h), placement)
        }
    }

    @Test
    fun `canvas-sized image bypasses default margin`() {
        assertEquals(
            ImageImportHelper.FitPlacement(0, 0, 2048, 1024),
            ImageImportHelper.calculateFitPlacement(2048, 1024, 2048, 1024),
        )
    }

    @Test
    fun `matching only one dimension still uses existing fit rule`() {
        assertEquals(
            ImageImportHelper.FitPlacement(100, 300, 800, 400),
            ImageImportHelper.calculateFitPlacement(1000, 1000, 1000, 500),
        )
        assertEquals(
            ImageImportHelper.FitPlacement(300, 100, 400, 800),
            ImageImportHelper.calculateFitPlacement(1000, 1000, 500, 1000),
        )
    }

    @Test
    fun `matching aspect ratio alone does not bypass scaling`() {
        assertEquals(
            ImageImportHelper.FitPlacement(100, 50, 800, 400),
            ImageImportHelper.calculateFitPlacement(1000, 500, 2000, 1000),
        )
    }

    @Test
    fun `invalid matching dimensions do not bypass input guard`() {
        assertEquals(
            ImageImportHelper.FitPlacement(0, 0, 1, 1),
            ImageImportHelper.calculateFitPlacement(0, 0, 0, 0),
        )
    }

    @Test
    fun `cover placement keeps canvas-sized image untouched at origin`() {
        for ((w, h) in listOf(1000 to 1000, 1920 to 1080, 1080 to 1920, 1001 to 777)) {
            assertEquals(
                ImageImportHelper.FitPlacement(0, 0, w, h),
                ImageImportHelper.calculateCoverPlacement(w, h, w, h),
            )
        }
    }

    @Test
    fun `cover placement scales same aspect ratio exactly to canvas`() {
        assertEquals(
            ImageImportHelper.FitPlacement(0, 0, 1000, 500),
            ImageImportHelper.calculateCoverPlacement(1000, 500, 2000, 1000),
        )
        assertEquals(
            ImageImportHelper.FitPlacement(0, 0, 1000, 1000),
            ImageImportHelper.calculateCoverPlacement(1000, 1000, 300, 300),
        )
    }

    @Test
    fun `cover placement crops mismatched ratios instead of stretching`() {
        // 画布 1000x1000, 结果 2000x1000: 等比不变, 左右各溢出 500
        assertEquals(
            ImageImportHelper.FitPlacement(-500, 0, 2000, 1000),
            ImageImportHelper.calculateCoverPlacement(1000, 1000, 2000, 1000),
        )
        // 画布 1000x1000, 结果 500x2000: 宽放大到 1000, 高溢出到 4000, 上下各裁 1500
        assertEquals(
            ImageImportHelper.FitPlacement(0, -1500, 1000, 4000),
            ImageImportHelper.calculateCoverPlacement(1000, 1000, 500, 2000),
        )
    }

    @Test
    fun `cover placement always covers the canvas without leaving gaps`() {
        val cases = listOf(
            Triple(1000, 1000, 2000 to 1000),
            Triple(1000, 1000, 500 to 2000),
            Triple(1000, 1000, 300 to 300),
            Triple(2048, 1024, 1536 to 1536),
            Triple(999, 1001, 997 to 1003),
        )
        for ((docW, docH, img) in cases) {
            val p = ImageImportHelper.calculateCoverPlacement(docW, docH, img.first, img.second)
            assert(p.targetW >= docW && p.targetH >= docH) {
                "cover $docW x $docH with ${img.first}x${img.second} -> ${p.targetW}x${p.targetH}"
            }
            assert(p.x <= 0 && p.y <= 0) { "cover must not leave a top-left gap: $p" }
        }
    }

}
