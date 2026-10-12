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
