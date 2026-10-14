package com.reverie.paint.core.comfy

/** 分辨率档位；[maxEdge] 为最长边上限，null 表示保持原图尺寸 */
internal enum class ComfyResolutionTier(val maxEdge: Int?) {
    ORIGINAL(null),
    SD15(768),
    SDXL(1024),
    HIGH(1536),
}

/**
 * 用户可选的「生图分辨率上限」(上传给 ComfyUI 的输入图最长边)。
 *
 * 上限越高, 输入图越大 —— 上传耗时 / 显存占用 / 服务器处理时间随之上升, 由用户按机器与
 * 网络自行权衡, 因此做成档位而非直接调大默认值。默认 [R1536] 与历史 HIGH 档行为一致。
 *
 * 四档均为 64 的整数倍, 与 [ComfyResolution.ALIGN] 自然对齐, 不会被向下取整。
 */
internal enum class ComfyExportResolution(val maxEdge: Int) {
    R1536(1536),
    R2048(2048),
    R3072(3072),
    R4096(4096),
    ;

    companion object {
        val DEFAULT = R1536

        /** 从持久化数值恢复; 非法值回落到 [DEFAULT] */
        fun fromMaxEdge(value: Int): ComfyExportResolution =
            entries.firstOrNull { it.maxEdge == value } ?: DEFAULT
    }
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

    fun compute(srcWidth: Int, srcHeight: Int, tier: ComfyResolutionTier): ComfyTargetSize =
        computeForMaxEdge(srcWidth, srcHeight, tier.maxEdge)

    /** 按用户所选导出上限计算, 档位见 [ComfyExportResolution] */
    fun compute(srcWidth: Int, srcHeight: Int, resolution: ComfyExportResolution): ComfyTargetSize =
        computeForMaxEdge(srcWidth, srcHeight, resolution.maxEdge)

    /** [maxEdge] 为最长边上限, null 表示保持原图尺寸 */
    private fun computeForMaxEdge(srcWidth: Int, srcHeight: Int, maxEdge: Int?): ComfyTargetSize {
        val w = srcWidth.coerceAtLeast(1)
        val h = srcHeight.coerceAtLeast(1)
        val srcLong = maxOf(w, h)
        val srcShort = minOf(w, h)

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
