package com.reverie.paint.core.comfy

/** 工作流类型: 文生图 / 图生图 */
internal enum class ComfyWorkflowType { TXT2IMG, IMG2IMG }

/** 从 API 格式工作流 JSON 中解析出的结构 */
internal data class ComfyWorkflow(
    val type: ComfyWorkflowType,
    val nodes: List<ComfyNode>,
)

/**
 * 单个节点。
 * ComfyUI API 格式为 `nodeId -> { class_type, inputs }`，这里只保留 UI 需要的信息。
 */
internal data class ComfyNode(
    val id: String,
    val classType: String,
    val inputNames: List<String>,
)

/** 节点绑定: 用户选择某个节点的某个字段 */
internal data class ComfyBinding(
    val nodeId: String,
    val fieldName: String,
)

/** 完整绑定配置 */
internal data class ComfyBindings(
    val prompt: ComfyBinding,
    val image: ComfyBinding? = null,
    val outputNodeId: String,
)

/** 绑定角色: 用户在绑定行里能手动指定的各项 */
internal enum class BindRole { PROMPT, NEGATIVE, IMAGE, OUTPUT, STEPS, CFG, SEED, DENOISE }

/**
 * 自动绑定未识别 / 需提醒的事项。
 *
 * **刻意不携带任何中文文案**: 只给语义 key 与参数, 文案交给 UI 层 `stringResource`
 * 翻译。原实现把中文硬编码在这里, 英文界面下会漏出中文 (i18n 缺陷)。
 */
internal enum class BindNoteKey {
    /** 图片输入节点没有可写的图像字段, args = [nodeId] */
    IMAGE_NODE_NO_FIELD,

    /** 蒙版按"编号靠后的 LoadImage"约定取, args = [nodeId] */
    MASK_CONVENTION,

    /** 只有一个图片输入, 蒙版类模式不可用 */
    ONLY_ONE_IMAGE_INPUT,

    /** 没有采样器节点 */
    NO_SAMPLER,

    /** denoise 不是字面量, args = [samplerId] */
    DENOISE_NOT_LITERAL,

    /** denoise 为 0, args = [samplerId] */
    DENOISE_ZERO,

    /** 有参考图但工作流没有图片输入节点 */
    REFERENCE_WITHOUT_IMAGE_INPUT,

    /** 正负提示词指向同一节点, args = [nodeId] */
    SAME_POSITIVE_NEGATIVE,

    /** positive 指向的节点不存在, args = [samplerId, nodeId] */
    POSITIVE_TARGET_MISSING,

    /** positive 指向的节点无法用作提示词, args = [samplerId, nodeId] */
    POSITIVE_TARGET_NOT_PROMPT,

    /** negative 指向的节点无法用作提示词, args = [samplerId, nodeId] */
    NEGATIVE_TARGET_NOT_PROMPT,

    /** 没有找到可用的提示词节点 */
    NO_PROMPT_NODE,

    /** 没找到输出节点 */
    NO_OUTPUT_NODE,

    /** 输出节点的 images 指向不存在, args = [outputNodeId, linkId] */
    OUTPUT_LINK_MISSING,

    /** 采样器没有可写的 steps 字段, args = [samplerId] */
    STEPS_FIELD_MISSING,

    /** 采样器没有可写的 cfg 字段, args = [samplerId] */
    CFG_FIELD_MISSING,

    /** 采样器没有可写的 seed 字段, args = [samplerId] */
    SEED_FIELD_MISSING,
}

/** 一条识别提示: 语义 key + 参数, 由 UI 层翻译 */
internal data class BindNote(
    val key: BindNoteKey,
    val args: List<String> = emptyList(),
)

/**
 * 用户可手动编辑的角色 + 自动推导出的辅助绑定 (蒙版 / 采样器)。
 *
 * 用户能改的是各个角色 (提示词 / 负面 / 图片 / 输出 / 步数 / CFG / 种子 / 改动幅度);
 * [maskNodeId] / [samplerNodeId] 随自动结果原样带过, 保存时一并落盘
 * —— 生成链路读的就是 [toBindingSet] 的产物。
 */
internal data class EditableBindings(
    val prompt: ComfyBinding? = null,
    val negative: ComfyBinding? = null,
    val image: ComfyBinding? = null,
    val outputNodeId: String? = null,
    val steps: ComfyBinding? = null,
    val cfg: ComfyBinding? = null,
    val seed: ComfyBinding? = null,
    val denoise: ComfyBinding? = null,
    val maskNodeId: String? = null,
    val samplerNodeId: String? = null,
) {
    /** 正向提示词与输出节点齐备才能生成 (对齐 [AutoBindResult.isUsable]) */
    val usable: Boolean get() = prompt != null && outputNodeId != null

    /** 转成生成链路读取的绑定集合; 缺正向提示词或输出节点时返回 null */
    fun toBindingSet(): AiBindingSet? {
        val p = prompt ?: return null
        val out = outputNodeId ?: return null
        return AiBindingSet(
            base = ComfyBindings(prompt = p, image = image, outputNodeId = out),
            negative = negative,
            steps = steps,
            cfg = cfg,
            seed = seed,
            denoise = denoise,
            maskNodeId = maskNodeId,
            samplerNodeId = samplerNodeId,
        )
    }

    /** 只比较用户可改的各个角色, 用来判定"是否被手动改过" */
    fun sameRolesAs(other: EditableBindings): Boolean =
        prompt == other.prompt &&
            negative == other.negative &&
            image == other.image &&
            outputNodeId == other.outputNodeId &&
            steps == other.steps &&
            cfg == other.cfg &&
            seed == other.seed &&
            denoise == other.denoise
}
