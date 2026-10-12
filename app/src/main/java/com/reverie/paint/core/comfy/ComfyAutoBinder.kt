package com.reverie.paint.core.comfy

import org.json.JSONArray
import org.json.JSONObject

/** 自动绑定识别出的工作流形态 */
internal enum class WorkflowKind { TXT2IMG, IMG2IMG, INPAINT, UNKNOWN }

/**
 * 自动绑定的结果。
 *
 * 字段与 [ComfyAutoBinder] 的判定顺序一一对应, 任何一项为 null 都意味着"没认出来",
 * 对应说明会出现在 [unresolved] 里 —— **宁可让用户知道边界, 不静默出错**。
 *
 * @property prompt 正向提示词绑定 (KSampler.positive 反查到的 CLIPTextEncode)
 * @property negativePrompt 负面提示词绑定 (KSampler.negative 反查到的 CLIPTextEncode)
 * @property imageInput 图片输入绑定 (LoadImage 的 image 字段)
 * @property outputNodeId 产图节点 id, 优先取 SaveImage / PreviewImage 的 images 连线生产节点
 * @property workflowKind 形态判定
 * @property unresolved 没识别出来的部分的中文说明
 * @property denoise 改动幅度落到哪个字段。**纯文生图为 null** (txt2img 没有 denoise 语义)
 * @property maskNodeId 第二个图像输入节点 (蒙版), null 表示该预设不支持蒙版类模式
 * @property samplerNodeId 采样器节点 id, 用于写 seed
 */
internal data class AutoBindResult(
    val prompt: ComfyBinding?,
    val negativePrompt: ComfyBinding?,
    val imageInput: ComfyBinding?,
    val outputNodeId: String?,
    val workflowKind: WorkflowKind,
    val unresolved: List<String> = emptyList(),
    val denoise: ComfyBinding? = null,
    val maskNodeId: String? = null,
    val samplerNodeId: String? = null,
) {
    /** 至少要有正向提示词与输出节点, 这份工作流才真的能用 */
    val isUsable: Boolean get() = prompt != null && outputNodeId != null

    /** 转换成可序列化的绑定集合; prompt 或 outputNodeId 缺失时返回 null */
    fun bindingSet(): AiBindingSet? {
        val p = prompt ?: return null
        val out = outputNodeId ?: return null
        return AiBindingSet(
            base = ComfyBindings(prompt = p, image = imageInput, outputNodeId = out),
            negative = negativePrompt,
            denoise = denoise,
            maskNodeId = maskNodeId,
            samplerNodeId = samplerNodeId,
        )
    }
}

/**
 * 一份工作流的完整绑定。
 *
 * [base] 复用已提交的 [ComfyBindings] 三字段类型 (prompt / image / outputNodeId),
 * 其余是这个功能自己的扩展。
 */
internal data class AiBindingSet(
    val base: ComfyBindings,
    val negative: ComfyBinding? = null,
    val denoise: ComfyBinding? = null,
    val maskNodeId: String? = null,
    val samplerNodeId: String? = null,
)

/**
 * 工作流自动绑定器。
 *
 * 绑定界面本期不做 (既定边界), 所以导入时必须能自动推断出"哪个节点的哪个字段是
 * 提示词 / 负面提示词 / 图片 / 输出"。推断的核心不是"找第一个 CLIPTextEncode",
 * 而是**顺着连线反查**:
 *
 * ```
 * KSampler.positive → 节点 6 → 那个 CLIPTextEncode 就是正向提示词
 * KSampler.negative → 节点 7 → 那个 CLIPTextEncode 就是负面提示词
 * ```
 *
 * 只有在完全没有采样器连线时, 才退回"text 为字符串字面量的 CLIPTextEncode 减去被
 * negative 引用的那些"。
 *
 * > SDXL 的 CLIPTextEncode 用 `text_g` / `text_l` 两个字段, 这里只绑第一个命中的那个
 * > (`text` → `text_g` → `text_l`)。一期不做多字段联动, 导入 SDXL 预设时用宽松的提示词字段即可。
 *
 * @param rawPrompt `ComfyWorkflowParser.ParseResult.Success.rawPrompt`。**必须传**:
 *   连线关系只存在于输入值里, 而 `ComfyNode` 只保留 class_type 与字段名, 拿不到连线目标
 */
internal object ComfyAutoBinder {

    private const val FIELD_IMAGE = "image"
    private const val FIELD_UPLOAD = "upload"
    private const val FIELD_NEGATIVE = "negative"
    private const val FIELD_POSITIVE = "positive"
    private const val FIELD_DENOISE = "denoise"
    private const val FIELD_IMAGES = "images"
    private const val FIELD_MASK = "mask"

    private const val CLASS_SAVE_IMAGE = "SaveImage"
    private const val CLASS_PREVIEW_IMAGE = "PreviewImage"
    private const val CLASS_TEXT_ENCODE = "CLIPTextEncode"
    private const val CLASS_VAE_ENCODE = "VAEEncode"
    private const val CLASS_MASK_IMAGE = "LoadImageMask"
    private const val LOAD_IMAGE_PREFIX = "LoadImage"

    /** CLIPTextEncode 可能把提示词拆到这几个字段 (SDXL 的 text_g / text_l) */
    private val TEXT_FIELDS = listOf("text", "text_g", "text_l")

    private val OUTPUT_CLASSES = listOf(CLASS_SAVE_IMAGE, CLASS_PREVIEW_IMAGE)

    private val SAMPLER_CLASSES = setOf("KSampler", "KSamplerAdvanced", "SamplerCustom")

    fun bind(
        rawPrompt: JSONObject,
        workflow: ComfyWorkflow,
        hasReference: Boolean = false,
    ): AutoBindResult {
        val notes = ArrayList<String>()
        val byId = workflow.nodes.associateBy { it.id }

        // ---- ① 采样器: 带 denoise 字段的节点优先, 否则按已知 class_type ----
        val sampler = workflow.nodes.firstOrNull { FIELD_DENOISE in it.inputNames }
            ?: workflow.nodes.firstOrNull { it.classType in SAMPLER_CLASSES }
        val samplerInputs = sampler?.let { inputsOf(rawPrompt, it.id) }

        // ---- ② 正 / 负提示词: 从采样器的连线反查 ----
        val positiveRef = samplerInputs?.let { linkTargetOf(it, FIELD_POSITIVE) }
        val negativeRef = samplerInputs?.let { linkTargetOf(it, FIELD_NEGATIVE) }
        val prompts = resolvePrompts(rawPrompt, workflow, positiveRef, negativeRef, sampler, notes)

        // ---- ③ 输出节点 ----
        val outputNode = OUTPUT_CLASSES.firstNotNullOfOrNull { cls ->
            workflow.nodes.firstOrNull { it.classType == cls }
        }
        val outputNodeId = resolveOutput(rawPrompt, byId, outputNode, notes)

        // ---- ④ 图片输入 ----
        val loaders = workflow.nodes.filter { it.classType.startsWith(LOAD_IMAGE_PREFIX) }
        val imageNode = loaders.firstOrNull()
        // ⚠️ 蒙版取"编号最大的 LoadImage"是**约定而非推导**: ComfyUI 的 inpaint 模板里
        // 蒙版那张 LoadImage 排在后面。设置页必须把这条 warning 原样显示给用户。
        val maskNode = loaders.lastOrNull()?.takeIf { loaders.size >= 2 }
        val imageInput = imageNode?.let { node ->
            val field = imageFieldOf(node)
            if (field == null) {
                notes += "图片输入节点 ${node.id} 没有可写的图像字段"
                null
            } else {
                ComfyBinding(nodeId = node.id, fieldName = field)
            }
        }
        if (maskNode != null) {
            notes += "蒙版输入按约定取编号靠后的 LoadImage（${maskNode.id}），" +
                "若与你的工作流不符请调整节点顺序"
        } else if (imageNode != null) {
            notes += "该预设只有一个图片输入，蒙版类模式不可用"
        }

        val hasMaskInput = maskNode != null ||
            workflow.nodes.any { it.classType == CLASS_MASK_IMAGE } ||
            workflow.nodes.any { it.classType == CLASS_VAE_ENCODE && FIELD_MASK in it.inputNames }

        // ---- ⑤ 形态判定 ----
        val kind = when {
            sampler == null -> WorkflowKind.UNKNOWN
            hasMaskInput -> WorkflowKind.INPAINT
            imageNode != null -> WorkflowKind.IMG2IMG
            else -> WorkflowKind.TXT2IMG
        }
        if (sampler == null) {
            notes += "工作流里没有采样器节点，无法提交"
        }

        val denoiseLiteral = samplerInputs?.let { literalDenoise(it) }
        if (imageNode != null && denoiseLiteral == null) {
            notes += "采样器 ${sampler?.id} 的 denoise 不是字面量，改动幅度将被忽略"
        }
        if (denoiseLiteral != null && denoiseLiteral <= 0.0) {
            notes += "采样器 ${sampler?.id} 的 denoise 为 0，采样结果会原样等于输入图"
        }
        if (hasReference && imageNode == null) {
            notes += "已选择参考图，但工作流里没有图片输入节点，图生图与局部重绘不可用"
        }

        // ---- ⑥ 改动幅度落点: 纯文生图没有 denoise 语义, 不绑定 ----
        val denoise = sampler
            ?.takeIf { kind != WorkflowKind.TXT2IMG && FIELD_DENOISE in it.inputNames }
            ?.let { ComfyBinding(nodeId = it.id, fieldName = FIELD_DENOISE) }

        return AutoBindResult(
            prompt = prompts.prompt,
            negativePrompt = prompts.negative,
            imageInput = imageInput,
            outputNodeId = outputNodeId,
            workflowKind = kind,
            unresolved = notes,
            denoise = denoise,
            maskNodeId = maskNode?.id,
            samplerNodeId = sampler?.id,
        )
    }

    /**
     * 找出承载 `denoise` 的节点。
     *
     * 单独暴露给 [ComfyPromptBuilder]: 只拿到 [ComfyBindings] 三字段的调用方拿不到
     * denoise 落点, 由它按同一条规则现查, 避免两处规则漂移。
     */
    fun findDenoiseBinding(rawPrompt: JSONObject): ComfyBinding? {
        val keys = rawPrompt.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val node = rawPrompt.optJSONObject(id) ?: continue
            val classType = node.optString("class_type")
            if (classType in SAMPLER_CLASSES && FIELD_DENOISE in inputNamesOf(node)) {
                return ComfyBinding(nodeId = id, fieldName = FIELD_DENOISE)
            }
        }
        return null
    }

    // ---------------------------------------------------------------- 内部

    private data class PromptPair(val prompt: ComfyBinding?, val negative: ComfyBinding?)

    /**
     * 正 / 负提示词判定。
     *
     * 主路径: 顺着采样器的 `positive` / `negative` 连线反查, 目标节点不存在时如实记为未识别。
     * 兜底路径 (采样器没有这两根连线): 取 text 为**字符串字面量**的 CLIPTextEncode 作候选集,
     * 排除掉被任何节点的 `negative` 字段引用过的, 剩下的第一个作正向。
     */
    private fun resolvePrompts(
        rawPrompt: JSONObject,
        workflow: ComfyWorkflow,
        positiveRef: String?,
        negativeRef: String?,
        sampler: ComfyNode?,
        notes: MutableList<String>,
    ): PromptPair {
        val byId = workflow.nodes.associateBy { it.id }

        if (positiveRef != null || negativeRef != null) {
            // 正负指向同一个 CLIPTextEncode: 无法区分意图, 保留正向、放弃负面并说明
            if (positiveRef != null && positiveRef == negativeRef) {
                notes += "正向与负面提示词指向同一个节点 $positiveRef，无法区分，将不注入负面提示词"
                val kept = promptBindingOf(rawPrompt, byId[positiveRef])
                if (kept == null) {
                    notes += "采样器 ${sampler?.id} 的 positive 指向的节点 $positiveRef 不存在"
                }
                return PromptPair(prompt = kept, negative = null)
            }
            val prompt = if (positiveRef == null) null
            else promptBindingOf(rawPrompt, byId[positiveRef])
            if (positiveRef != null && prompt == null) {
                notes += "采样器 ${sampler?.id} 的 positive 指向的节点 $positiveRef 无法用作提示词"
            }
            val negative = if (negativeRef == null) null
            else promptBindingOf(rawPrompt, byId[negativeRef])
            if (negativeRef != null && negative == null) {
                notes += "采样器 ${sampler?.id} 的 negative 指向的节点 $negativeRef 无法用作提示词"
            }
            return PromptPair(prompt, negative)
        }

        // 兜底: 字面量 CLIPTextEncode 候选集 - 被 negative 引用的
        val candidates = workflow.nodes.filter { node ->
            node.classType == CLASS_TEXT_ENCODE && literalTextField(rawPrompt, node.id) != null
        }
        if (candidates.isEmpty()) {
            notes += "没有找到可用的提示词节点"
            return PromptPair(null, null)
        }
        val negativeIds = workflow.nodes.mapNotNull { node ->
            linkTargetOf(inputsOf(rawPrompt, node.id), FIELD_NEGATIVE)
        }.toSet()
        val positive = candidates.firstOrNull { it.id !in negativeIds } ?: candidates.first()
        val negative = candidates.firstOrNull { it.id in negativeIds }
        return PromptPair(
            prompt = ComfyBinding(positive.id, literalTextField(rawPrompt, positive.id)!!),
            negative = negative?.let { ComfyBinding(it.id, literalTextField(rawPrompt, it.id)!!) },
        )
    }

    /** 反查到的节点必须是"文本是字符串字面量"的 CLIPTextEncode, 否则不能当提示词注入 */
    private fun promptBindingOf(rawPrompt: JSONObject, node: ComfyNode?): ComfyBinding? {
        val target = node ?: return null
        if (target.classType != CLASS_TEXT_ENCODE) return null
        val field = literalTextField(rawPrompt, target.id) ?: return null
        return ComfyBinding(target.id, field)
    }

    /**
     * 输出节点 id。
     *
     * 优先 [CLASS_SAVE_IMAGE], 其次 [CLASS_PREVIEW_IMAGE]; 若它的 `images` 是
     * `[nodeId, slot]` 连线, 取**连线的生产节点**而不是保存节点本身 —— WebSocket 的
     * `executed` 消息正是按生产节点 id 给出 outputs 的, 取保存节点会匹配不上。
     */
    private fun resolveOutput(
        rawPrompt: JSONObject,
        byId: Map<String, ComfyNode>,
        outputNode: ComfyNode?,
        notes: MutableList<String>,
    ): String? {
        if (outputNode == null) {
            notes += "没找到输出节点（$CLASS_SAVE_IMAGE 或 $CLASS_PREVIEW_IMAGE）"
            return null
        }
        val link = linkTargetOf(inputsOf(rawPrompt, outputNode.id), FIELD_IMAGES)
            ?: return outputNode.id
        if (byId.containsKey(link)) return link
        notes += "输出节点 ${outputNode.id} 的 images 指向的节点 $link 不存在，改用输出节点自身"
        return outputNode.id
    }

    /** LoadImage 的图像字段名: 优先 `image`, 兼容只暴露单个字段的变体 */
    private fun imageFieldOf(node: ComfyNode): String? = when {
        FIELD_IMAGE in node.inputNames -> FIELD_IMAGE
        else -> node.inputNames.firstOrNull { it != FIELD_UPLOAD }
    }

    private fun literalDenoise(inputs: JSONObject): Double? =
        when (val value = inputs.opt(FIELD_DENOISE)) {
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull()
            else -> null
        }

    /** 文本字段名; 取不到说明该字段不存在或不是字面量, 不能当提示词用 */
    private fun literalTextField(rawPrompt: JSONObject, nodeId: String): String? {
        val inputs = inputsOf(rawPrompt, nodeId) ?: return null
        return TEXT_FIELDS.firstOrNull { inputs.opt(it) is String }
    }

    private fun inputsOf(rawPrompt: JSONObject, nodeId: String): JSONObject? =
        rawPrompt.optJSONObject(nodeId)?.optJSONObject("inputs")

    private fun inputNamesOf(node: JSONObject): List<String> {
        val inputs = node.optJSONObject("inputs") ?: return emptyList()
        return inputs.keys().asSequence().toList().sorted()
    }

    /**
     * 读取连线目标节点 id。
     *
     * ComfyUI 的连线值三种写法都见过: 数组 `[6, 0]`、纯字符串 `"6"`、纯数字 `6`。
     * 数组形式里第一个元素可能是数字也可能是字符串, 两种都认。
     */
    private fun linkTargetOf(inputs: JSONObject?, field: String): String? {
        val value = inputs?.opt(field) ?: return null
        return when (value) {
            is JSONArray -> linkIdOf(value.opt(0))
            is String -> value.trim().takeIf { it.isNotEmpty() && it != "null" }
            is Number -> linkIdOf(value)
            else -> null
        }
    }

    private fun linkIdOf(element: Any?): String? = when (element) {
        null, JSONObject.NULL -> null
        is Number -> {
            val d = element.toDouble()
            if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString() else d.toString()
        }
        is String -> element.trim().takeIf { it.isNotEmpty() && it != "null" }
        else -> null
    }
}