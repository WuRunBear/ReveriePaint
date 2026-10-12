/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.comfy

import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.ReverieCoreBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * 画布导出结果。
 *
 * 失败区分「引擎没起来」与「导出失败」两档: 前者是时序问题, 后者是数据/内存问题,
 * 排查方向不同。
 */
internal sealed class CanvasExportResult {
    data class Ok(val bytes: ByteArray) : CanvasExportResult()

    /** renderHandler 尚未就绪 (引擎线程未起 / 文档未加载) */
    data object EngineNotReady : CanvasExportResult()

    /** 渲染 / 缩放 / 编码任一步失败 */
    data object Failed : CanvasExportResult()
}

/**
 * 把当前画布导出成可上传的 WebP。
 *
 * **为什么不用 `savePng`**: 它依赖 Qt 的图片写入链路, 而该链路在本机不可用 ——
 * App 自身的 PNG 导出同样失败 (既有问题, 与本功能无关)。这里改走 `renderToBuffer`,
 * 直接把像素写进 Android Bitmap, 与画布显示 (`PaintViewModel` 的双缓冲) 和
 * 动画导出 (`PaintViewModelAnimationExport`) 是同一条通路。
 *
 * 编码全部在 Kotlin 侧完成, 不经过 Qt 的文件写入。
 */
internal object ComfyCanvasExporter {
    /** WebP 质量。实测目标 ComfyUI 实例支持 WebP 上传 */
    const val WEBP_QUALITY = 90

    /** 上传给 ComfyUI 的输入图文件名 */
    const val UPLOAD_FILENAME = "reverie_input.webp"

    private const val TAG = "ReverieComfy"
    private const val WHITE = 0xFFFFFFFF.toInt()

    suspend fun export(vm: PaintViewModel): CanvasExportResult {
        if (!vm.hasAppContext()) {
            Log.w(TAG, "export: hasAppContext=false")
            return CanvasExportResult.EngineNotReady
        }
        // 整幅原尺寸位图 (与显示缓冲同量级), 后处理完立刻回收
        val full = vm.renderDocumentBitmap() ?: return CanvasExportResult.Failed
        return try {
            withContext(Dispatchers.IO) { process(full) }
        } finally {
            runCatching { full.recycle() }
        }
    }

    // ---------------------------------------------------------------- 渲染

    /**
     * 取整幅合成结果。必须走渲染线程 ([PaintViewModel.runCore], AGENTS.md §4 铁律 2)。
     *
     * 引擎线程未起 / 文档尺寸非法 / 分配位图失败 / `renderToBuffer` 返回 false 都返回 null。
     */
    private suspend fun PaintViewModel.renderDocumentBitmap(): Bitmap? =
        suspendCancellableCoroutine { cont ->
            var out: Bitmap? = null
            runCore(
                render = false,
                after = { if (cont.isActive) cont.resume(out) },
            ) {
                val w = runCatching { ReverieCoreBridge.docWidth() }.getOrDefault(0)
                val h = runCatching { ReverieCoreBridge.docHeight() }.getOrDefault(0)
                if (w <= 0 || h <= 0) {
                    Log.w(TAG, "renderDocumentBitmap: 文档尺寸非法 ${w}x$h")
                    return@runCore
                }
                val bmp =
                    try {
                        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    } catch (t: Throwable) {
                        Log.w(TAG, "renderDocumentBitmap: 分配 ${w}x$h 位图失败", t)
                        null
                    } ?: return@runCore
                val ok =
                    runCatching { ReverieCoreBridge.renderToBuffer(bmp, true, null) }
                        .getOrDefault(false)
                if (ok) {
                    out = bmp
                } else {
                    runCatching { bmp.recycle() }
                    Log.w(TAG, "renderDocumentBitmap: renderToBuffer=false")
                }
            }
        }

    // ---------------------------------------------------------------- 后处理

    /** 缩放 → 透明填白 → WebP。调用方负责回收 [full] */
    private fun process(full: Bitmap): CanvasExportResult {
        val target = ComfyResolution.compute(full.width, full.height, ComfyResolutionTier.HIGH)
        Log.i(TAG, "process: src=${full.width}x${full.height} target=${target.width}x${target.height}")

        var scaled: Bitmap? = null
        var opaque: Bitmap? = null
        return try {
            scaled =
                if (full.width == target.width && full.height == target.height) {
                    full
                } else {
                    Bitmap.createScaledBitmap(full, target.width, target.height, true)
                }
            opaque = fillTransparentWithWhite(scaled)
            val result = encodeWebp(opaque)
            if (result is CanvasExportResult.Ok) {
                Log.i(TAG, "process: ok ${result.bytes.size}B")
            } else {
                Log.w(TAG, "process: WebP 编码失败")
            }
            result
        } catch (t: Throwable) {
            Log.w(TAG, "process: 缩放/填白/编码抛异常", t)
            CanvasExportResult.Failed
        } finally {
            // 回收中间位图, 不碰调用方传入的 full
            if (opaque != null && opaque !== scaled && opaque !== full) {
                runCatching { opaque.recycle() }
            }
            if (scaled != null && scaled !== full) {
                runCatching { scaled.recycle() }
            }
        }
    }

    /** 把 alpha == 0 的像素填成不透明白; 其余像素原样保留 */
    private fun fillTransparentWithWhite(src: Bitmap): Bitmap {
        val out =
            if (src.isMutable && src.config == Bitmap.Config.ARGB_8888) {
                src
            } else {
                src.copy(Bitmap.Config.ARGB_8888, true) ?: return src
            }
        val w = out.width
        val h = out.height
        if (w <= 0 || h <= 0) return out
        val row = IntArray(w)
        for (y in 0 until h) {
            out.getPixels(row, 0, w, 0, y, w, 1)
            var changed = false
            for (x in 0 until w) {
                if (row[x] ushr 24 == 0) {
                    row[x] = WHITE
                    changed = true
                }
            }
            if (changed) out.setPixels(row, 0, w, 0, y, w, 1)
        }
        return out
    }

    private fun encodeWebp(bitmap: Bitmap): CanvasExportResult {
        val out = ByteArrayOutputStream()
        val ok =
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, WEBP_QUALITY, out)
                } else {
                    @Suppress("DEPRECATION")
                    bitmap.compress(Bitmap.CompressFormat.WEBP, WEBP_QUALITY, out)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "encodeWebp 抛异常", t)
                false
            }
        return if (ok && out.size() > 0) {
            CanvasExportResult.Ok(out.toByteArray())
        } else {
            CanvasExportResult.Failed
        }
    }
}
