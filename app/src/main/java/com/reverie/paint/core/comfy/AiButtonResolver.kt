package com.reverie.paint.core.comfy

/**
 * 主按钮的读法与它背后的管线。**一次决定 = 一次决定** (原则 P3)。
 *
 * @property text 主按钮文案对应的 strings key 语义名 (如 `ai_action_fill`)。
 *   这里是 key 名而不是 `R.string.*` 的 int, 是因为 S0 不产出任何 UI 依赖,
 *   让整条判定链在纯 JVM 上可跑; UI 层 (S3) 负责 `stringResource()` 取字面量
 * @property mode 这次提交实际走的生成方式
 * @property subtitle 副标题 key 名 (如 `ai_sub_inpaint`), 用 11sp 说明"会做什么"
 */
internal data class AiButtonLabel(
    val text: String,
    val mode: AiGenerateMode,
    val subtitle: String,
)

/**
 * 主按钮随上下文变形。
 *
 * ## 规则: 按钮只随「生成方式 × 取材来源」变, **不随改动幅度变**
 *
 * 早期方案把 `strength` 也绑进按钮语义 (100% → 「生成」, 62% → 「填充」), 已废弃。
 * 原因: **强度是连续量, 管的是"改多少", 不是"改哪里"**。让它切管线, 意味着同一个
 * 「填充」按钮在 100% 和 62% 下跑两套完全不同的图, 用户看不见任何区别却拿到不同结果 ——
 * 这才是真正的困惑源。强度现在只喂 `KSampler.denoise`。
 *
 * 判定优先级 (自上而下, 命中即返回):
 * 1. `EXTEND` → 「扩展」。作用在画布边界之外, 与有无选区无关
 * 2. `ADD` / `REMOVE` / `REPLACE_BG` → 「填充」+ 各自副标题
 * 3. 有选区 → 「填充」+ `ai_sub_inpaint`
 * 4. 无选区 → 「生成」, 副标题只看取材来源: 整画布 → `ai_sub_refine_canvas`, 其余 → `ai_sub_free_generate`
 *
 * > [resolve] 的返回值与 `context.strengthPercent` 无关, 这条不变量由单测钉死。
 */
internal object AiButtonResolver {

    // ---- strings key 语义名 (与 res/values/strings.xml §5.2 / §5.3 对应) ----

    const val ACTION_GENERATE = "ai_action_generate"
    const val ACTION_FILL = "ai_action_fill"
    const val ACTION_EXPAND = "ai_action_expand"

    const val SUB_FREE_GENERATE = "ai_sub_free_generate"
    const val SUB_REFINE_CANVAS = "ai_sub_refine_canvas"
    const val SUB_INPAINT = "ai_sub_inpaint"
    const val SUB_OUTPAINT = "ai_sub_outpaint"
    const val SUB_ADD_CONTENT = "ai_sub_add_content"
    const val SUB_REMOVE_CONTENT = "ai_sub_remove_content"
    const val SUB_REPLACE_BG = "ai_sub_replace_bg"

    fun resolve(context: AiContext): AiButtonLabel = when {
        // ① 扩展: 画布外延展, 唯一不走选区的模式
        context.mode == AiGenerateMode.EXTEND -> expand()

        // ② 选区类模式: 按钮都读作「填充」, 只靠副标题区分意图
        context.mode == AiGenerateMode.ADD ->
            fill(AiGenerateMode.ADD, SUB_ADD_CONTENT)

        context.mode == AiGenerateMode.REMOVE ->
            fill(AiGenerateMode.REMOVE, SUB_REMOVE_CONTENT)

        context.mode == AiGenerateMode.REPLACE_BG ->
            fill(AiGenerateMode.REPLACE_BG, SUB_REPLACE_BG)

        // ③ 有选区一律「填充」, 与强度无关
        context.hasSelection -> fill(AiGenerateMode.FILL, SUB_INPAINT)

        // ④ 无选区一律「生成」; 整画布取材时副标题说明"以当前画面为底"
        context.sourceKind == AiSourceKind.WHOLE_CANVAS ->
            generate(SUB_REFINE_CANVAS)

        else -> generate(SUB_FREE_GENERATE)
    }

    private fun generate(subtitle: String) =
        AiButtonLabel(ACTION_GENERATE, AiGenerateMode.CUSTOM, subtitle)

    private fun fill(mode: AiGenerateMode, subtitle: String) =
        AiButtonLabel(ACTION_FILL, mode, subtitle)

    private fun expand() =
        AiButtonLabel(ACTION_EXPAND, AiGenerateMode.EXTEND, SUB_OUTPAINT)
}