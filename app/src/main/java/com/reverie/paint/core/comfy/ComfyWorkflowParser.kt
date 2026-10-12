package com.reverie.paint.core.comfy

import org.json.JSONArray
import org.json.JSONObject

/**
 * ComfyUI 工作流 JSON 解析器。
 *
 * ComfyUI 存在两种互不兼容的导出格式，必须严格区分：
 * - **API 格式**（本应用需要）：顶层是 `{"1": {"class_type": "...", "inputs": {...}}, ...}`
 * - **UI 格式**（必须拒绝）：顶层含 `workflow` 键，或 `nodes` / `links` / `last_node_id`
 *   等带画布坐标信息的结构，由编辑器「保存」产生，无法用于 API 调用。
 */
internal object ComfyWorkflowParser {

    sealed class ParseResult {
        /** 解析成功，[rawPrompt] 为原始 JSON，提交时在其上改写提示词/图片字段 */
        data class Success(val workflow: ComfyWorkflow, val rawPrompt: JSONObject) : ParseResult()

        /** 是 UI 格式（编辑器保存的文件），需要引导用户改用「导出(API)」 */
        data class NotApiFormat(val reason: String) : ParseResult()

        /** 内容损坏或不是有效的工作流 */
        data class Invalid(val reason: String) : ParseResult()
    }

    private const val UI_MARKER_WORKFLOW = "workflow"

    /** UI 格式特有的顶层键；API 格式的顶层键是纯数字节点 id，不会命中这些名字 */
    private val UI_MARKER_KEYS =
        setOf("nodes", "links", "groups", "extra", "last_node_id", "last_link_id", "version")

    fun parse(json: String, type: ComfyWorkflowType): ParseResult {
        val text = json.trim()
        if (text.isEmpty()) return ParseResult.Invalid("工作流内容为空")

        // Android 的 org.json 在畸形输入上有若干已知越界缺陷（JSONArray/JSONTokener），
        // 因此这里捕获整个 Exception 家族，保证不把异常抛给 UI。
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            return ParseResult.Invalid("不是合法的 JSON: ${e.message ?: e.javaClass.simpleName}")
        }

        if (root.has(UI_MARKER_WORKFLOW)) {
            return ParseResult.NotApiFormat(
                "这是编辑器保存的 UI 格式（含 workflow 键），请改用「导出(API)」重新导出",
            )
        }
        UI_MARKER_KEYS.firstOrNull { root.has(it) }?.let {
            return ParseResult.NotApiFormat("这是编辑器保存的 UI 格式（含 $it 键），请改用「导出(API)」重新导出")
        }

        val nodes = ArrayList<ComfyNode>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val value = root.opt(id)
            if (value !is JSONObject) return ParseResult.Invalid("节点 $id 不是一个对象")
            val classType = value.optString("class_type").takeIf { it.isNotBlank() }
                ?: return ParseResult.Invalid("节点 $id 缺少 class_type")
            nodes += ComfyNode(id = id, classType = classType, inputNames = inputNames(value))
        }
        if (nodes.isEmpty()) return ParseResult.Invalid("工作流中没有任何节点")

        val sorted = nodes.sortedWith { a, b -> compareNodeIds(a.id, b.id) }
        return ParseResult.Success(workflow = ComfyWorkflow(type = type, nodes = sorted), rawPrompt = root)
    }

    /**
     * 取节点的输入字段名列表（按字典序）。
     *
     * `inputs` 正常是对象；link 形式的输入（`[node_id, slot_index]`）整体为数组时取不到字段名，
     * 按空列表处理。
     *
     * 注意：org.json 的 `keys()` 走 HashMap，**不保证插入顺序**，因此这里统一排序，
     * 否则 UI 列表与单测都会随实现漂移。
     */
    private fun inputNames(node: JSONObject): List<String> =
        when (val inputs = node.opt("inputs")) {
            is JSONObject -> inputs.keys().asSequence().toList().sorted()
            is JSONArray -> emptyList()
            else -> emptyList()
        }

    /** 纯数字 id 按数值排序（3 在 10 前面），非数字 id 退回字典序 */
    private fun compareNodeIds(a: String, b: String): Int {
        val na = a.toLongOrNull()
        val nb = b.toLongOrNull()
        return when {
            na != null && nb != null -> na.compareTo(nb)
            na != null -> -1
            nb != null -> 1
            else -> a.compareTo(b)
        }
    }
}
