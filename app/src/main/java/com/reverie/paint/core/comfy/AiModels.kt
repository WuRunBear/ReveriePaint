package com.reverie.paint.core.comfy

/**
 * 生成方式: 决定主按钮读作什么、走哪条技术管线。
 *
 * 语义分组 (按钮文案见 [AiButtonResolver]):
 * - [FILL] / [ADD] / [REMOVE] / [REPLACE_BG] 都作用在选区上, 按钮**一律读作「填充」**,
 *   只靠副标题区分用户到底想要什么;
 * - [EXTEND] 是唯一作用在画布边界之外的, 按钮读作「扩展」;
 * - [CUSTOM] 是无选区约束的通用生成 (纯文生图或以整画布为底的图生图), 按钮读作「生成」。
 */
internal enum class AiGenerateMode { FILL, EXTEND, ADD, REMOVE, REPLACE_BG, CUSTOM }

/** 取材来源: 决定喂给工作流的像素从哪来 */
internal enum class AiSourceKind { WHOLE_CANVAS, SELECTION, REFERENCE }

/**
 * AI 会话的上下文快照 —— 纯状态镜像, 不持有任何像素数据 (铁律 1: 文档真身在 C++)。
 *
 * 由 `PaintViewModelAi` 在每次重组前从 VM 状态拼出来, 交给 [AiButtonResolver] 求主按钮读法。
 * 这里不 import 任何 Android 类型, 因此整条判定链可以 100% 单测。
 *
 * @property hasSelection 当前是否有有效选区 (决定局部重绘还是整幅处理)
 * @property sourceKind 取材来源 (决定喂哪张图)
 * @property strengthPercent 改动幅度 0..100。**只喂 KSampler.denoise, 不参与按钮判定**,
 *   见 [AiButtonResolver] 的 KDoc
 * @property mode 用户选定的生成方式
 */
internal data class AiContext(
    val hasSelection: Boolean = false,
    val sourceKind: AiSourceKind = AiSourceKind.WHOLE_CANVAS,
    val strengthPercent: Int = 100,
    val mode: AiGenerateMode = AiGenerateMode.CUSTOM,
) {
    /** 归一化后的改动幅度, 保证下游拿到的永远是 0..100 的整数 */
    val strength: Int get() = strengthPercent.coerceIn(0, 100)
}

/**
 * 一张生成结果 (变体)。
 *
 * 这里只持有 ComfyUI 侧的引用 (文件名), 不持有解码后的位图 —— 位图的生命周期
 * 归 S5 的变体条管, 避免这一层被当成像素容器。
 */
internal data class AiVariant(
    val index: Int,
    val imageRef: ComfyImageRef,
    val label: String = "",
)