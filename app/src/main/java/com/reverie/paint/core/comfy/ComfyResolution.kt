package com.reverie.paint.core.comfy

/** 分辨率档位；[maxEdge] 为最长边上限，null 表示保持原图尺寸 */
internal enum class ComfyResolutionTier(val maxEdge: Int?) {
    ORIGINAL(null),
    SD15(768),
    SDXL(1024),
    HIGH(1536),
}

/** 导出尺寸与实际缩放比 */
internal data class ComfyTargetSize(
    val width: Int,
    val height: Int,
    val scale: Float,
)

/**
 * 导出分辨率计算：等比缩放到不超过档位值，并对齐到 64 的倍数（VAE latent 约束）。
 *
 * 对齐策略：
 * - 决定上限的**长边**用向下取整，保证结果永不超档位；
 * - 短边取最近邻后再夹到 `[64, 长边]` 区间，减少比例失真同时不越界；
 * - 不需要缩小时保持原尺寸，只套用 64 的下限（1×1 -> 64×64，最小一个 latent）。
 *
 * 长边/短边的缩放全部走整数运算（`min * maxEdge / max`），避免浮点误差把 1536 压到 1535。
 */
internal object ComfyResolution {
    const val ALIGN = 64
    const val MIN_EDGE = 64

    fun compute(srcWidth: Int, srcHeight: Int, tier: ComfyResolutionTier): ComfyTargetSize {
        val w = srcWidth.coerceAtLeast(1)
        val h = srcHeight.coerceAtLeast(1)
        val srcLong = maxOf(w, h)
        val srcShort = minOf(w, h)
        val maxEdge = tier.maxEdge

        if (maxEdge == null || maxEdge >= srcLong) {
            return ComfyTargetSize(
                width = w.coerceAtLeast(MIN_EDGE),
                height = h.coerceAtLeast(MIN_EDGE),
                scale = 1f,
            )
        }

        val scale = maxEdge.toFloat() / srcLong.toFloat()
        val longEdge = alignTo64(maxEdge)
        val shortRaw = (srcShort.toLong() * maxEdge / srcLong).coerceAtLeast(1L)
        val shortEdge = alignTo64Nearest(shortRaw.toInt()).coerceIn(MIN_EDGE, longEdge)

        return if (w >= h) {
            ComfyTargetSize(width = longEdge, height = shortEdge, scale = scale)
        } else {
            ComfyTargetSize(width = shortEdge, height = longEdge, scale = scale)
        }
    }

    /** 向下对齐到 64 的倍数，并保证不小于一个 latent（64） */
    internal fun alignTo64(v: Int): Int = (v / ALIGN * ALIGN).coerceAtLeast(MIN_EDGE)

    private fun alignTo64Nearest(v: Int): Int =
        ((v + ALIGN / 2) / ALIGN * ALIGN).coerceAtLeast(MIN_EDGE)
}
