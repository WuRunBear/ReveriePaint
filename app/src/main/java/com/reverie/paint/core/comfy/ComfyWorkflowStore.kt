/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.comfy

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 一份已导入的 ComfyUI 工作流的**元数据**。
 *
 * 正文 JSON 可能很大 (一份复杂工作流几十到几百 KB), 因此**存文件**而不是塞进
 * SharedPreferences —— 这里只放名字 / 时间 / 自动绑定结果。
 *
 * [binding] 是导入时的自动绑定结果, 直接落盘, 后续生成链路不必重新推导;
 * 为 null 表示自动绑定没认出正向提示词或输出节点 (UI 会提示不可用)。
 */
internal data class StoredWorkflow(
    val id: String,
    val name: String,
    val importedAt: Long,
    val kind: WorkflowKind,
    val binding: AiBindingSet?,
    val unresolved: List<BindNote>,
    /** 绑定是否被用户手动改过 (true 时 UI 提供「恢复自动识别」) */
    val manual: Boolean = false,
)

/**
 * 已导入工作流的持久化。
 *
 * 形状对齐 [ComfyCredentialStore] (同一个 `paint_prefs`, 独立的 key), 但正文走文件:
 * - `filesDir/comfy_workflows/<uuid>.json` —— 原始 API 格式 JSON
 * - `paint_prefs:comfy_workflows_meta`    —— 元数据数组
 * - `paint_prefs:comfy_workflows_current` —— 当前选中的工作流 id
 *
 * 所有方法都是同步阻塞的文件/SharedPreferences 读写, 调用方必须放到
 * `Dispatchers.IO` (见 [com.reverie.paint.core.PaintViewModel.loadComfyWorkflows] 等)。
 */
internal object ComfyWorkflowStore {
    private const val PREFS = "paint_prefs"
    private const val KEY_META = "comfy_workflows_meta"
    private const val KEY_CURRENT = "comfy_workflows_current"
    private const val DIR = "comfy_workflows"

    /** 列出全部工作流, 保持导入顺序 (先导入的在前) */
    fun list(context: Context): List<StoredWorkflow> {
        val raw = prefs(context).getString(KEY_META, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<StoredWorkflow>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                decode(obj)?.let { out.add(it) }
            }
            out
        } catch (_: Exception) {
            // 元数据损坏时不阻塞应用启动, 退化成"没有导入过"
            emptyList()
        }
    }

    /**
     * 写入正文文件 + 元数据。
     *
     * 同 [id] 已存在时覆盖 (用于将来的"重命名/重导入"), 否则追加到末尾。
     */
    fun save(
        context: Context,
        id: String,
        name: String,
        json: String,
        kind: WorkflowKind,
        binding: AiBindingSet?,
        unresolved: List<BindNote>,
        manual: Boolean = false,
    ): StoredWorkflow {
        workflowDir(context).mkdirs()
        File(workflowDir(context), "$id.json").writeText(json, Charsets.UTF_8)
        val entry =
            StoredWorkflow(
                id = id,
                name = name,
                importedAt = System.currentTimeMillis(),
                kind = kind,
                binding = binding,
                unresolved = unresolved,
                manual = manual,
            )
        val entries = list(context).filterNot { it.id == id } + entry
        writeMeta(context, entries)
        return entry
    }

    /**
     * 覆盖某份工作流的绑定 (手动编辑 / 恢复自动识别后调用)。
     *
     * 其余元数据 (名字 / 正文文件 / 形态) 原样保留; [manual] 标记是否手动改过。
     * 找不到 [id] 时返回 null, 不写入。
     */
    fun updateBinding(
        context: Context,
        id: String,
        binding: AiBindingSet?,
        manual: Boolean,
    ): StoredWorkflow? {
        val entries = list(context)
        val index = entries.indexOfFirst { it.id == id }
        if (index < 0) return null
        val updated = entries[index].copy(binding = binding, manual = manual)
        writeMeta(context, entries.toMutableList().also { it[index] = updated })
        return updated
    }

    /** 读取某份工作流的原始 JSON; 文件已被删或读失败时返回 null */
    fun readJson(context: Context, id: String): String? =
        runCatching {
            File(workflowDir(context), "$id.json").takeIf { it.isFile }?.readText(Charsets.UTF_8)
        }.getOrNull()

    /** 删除正文文件与元数据; 若删的是当前项, 一并清掉"当前"指针 */
    fun delete(context: Context, id: String) {
        runCatching { File(workflowDir(context), "$id.json").delete() }
        writeMeta(context, list(context).filterNot { it.id == id })
        if (currentId(context) == id) setCurrent(context, null)
    }

    fun currentId(context: Context): String? =
        prefs(context).getString(KEY_CURRENT, null)?.takeIf { it.isNotBlank() }

    fun setCurrent(context: Context, id: String?) {
        prefs(context)
            .edit()
            .apply {
                if (id.isNullOrBlank()) remove(KEY_CURRENT) else putString(KEY_CURRENT, id)
            }.apply()
    }

    // ---------------------------------------------------------------- 内部

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun workflowDir(context: Context) = File(context.filesDir, DIR)

    private fun writeMeta(
        context: Context,
        entries: List<StoredWorkflow>,
    ) {
        val arr = JSONArray()
        entries.forEach { arr.put(encode(it)) }
        prefs(context).edit().putString(KEY_META, arr.toString()).apply()
    }

    private fun encode(entry: StoredWorkflow): JSONObject =
        JSONObject().apply {
            put("id", entry.id)
            put("name", entry.name)
            put("importedAt", entry.importedAt)
            put("kind", entry.kind.name)
            entry.binding?.let { put("binding", encodeBinding(it)) }
            if (entry.manual) put("manual", true)
            if (entry.unresolved.isNotEmpty()) {
                put("unresolved", JSONArray().apply { entry.unresolved.forEach { put(encodeNote(it)) } })
            }
        }

    private fun decode(obj: JSONObject): StoredWorkflow? {
        val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return null
        val kind =
            runCatching { WorkflowKind.valueOf(obj.optString("kind")) }.getOrDefault(WorkflowKind.UNKNOWN)
        return StoredWorkflow(
            id = id,
            name = obj.optString("name").ifBlank { id },
            importedAt = obj.optLong("importedAt", 0L),
            kind = kind,
            binding = obj.optJSONObject("binding")?.let(::decodeBinding),
            unresolved = decodeNotes(obj.optJSONArray("unresolved")),
            manual = obj.optBoolean("manual", false),
        )
    }

    private fun encodeNote(note: BindNote): JSONObject =
        JSONObject().apply {
            put("key", note.key.name)
            if (note.args.isNotEmpty()) {
                put("args", JSONArray().apply { note.args.forEach { put(it) } })
            }
        }

    /** 语义标记数组的解码; 单个坏项跳过, 不因此丢整份元数据 */
    private fun decodeNotes(arr: JSONArray?): List<BindNote> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val key = runCatching { BindNoteKey.valueOf(obj.optString("key")) }.getOrNull()
                ?: return@mapNotNull null
            val argsArr = obj.optJSONArray("args")
            val args =
                if (argsArr == null) {
                    emptyList()
                } else {
                    (0 until argsArr.length()).map { argsArr.optString(it) }
                }
            BindNote(key, args)
        }
    }

    private fun encodeBinding(set: AiBindingSet): JSONObject =
        JSONObject().apply {
            put("output", set.base.outputNodeId)
            put("prompt", encodeComfyBinding(set.base.prompt))
            set.base.image?.let { put("image", encodeComfyBinding(it)) }
            set.negative?.let { put("negative", encodeComfyBinding(it)) }
            set.steps?.let { put("steps", encodeComfyBinding(it)) }
            set.cfg?.let { put("cfg", encodeComfyBinding(it)) }
            set.seed?.let { put("seed", encodeComfyBinding(it)) }
            set.denoise?.let { put("denoise", encodeComfyBinding(it)) }
            set.maskNodeId?.let { put("mask", it) }
            set.samplerNodeId?.let { put("sampler", it) }
        }

    private fun encodeComfyBinding(binding: ComfyBinding): JSONObject =
        JSONObject().apply {
            put("node", binding.nodeId)
            put("field", binding.fieldName)
        }

    /** 正向提示词或输出节点缺失时, 整份绑定视为无效 (对应 [AutoBindResult.bindingSet] 的约定) */
    private fun decodeBinding(obj: JSONObject): AiBindingSet? {
        val prompt = decodeComfyBinding(obj.optJSONObject("prompt")) ?: return null
        val output = obj.optString("output").takeIf { it.isNotBlank() } ?: return null
        return AiBindingSet(
            base =
                ComfyBindings(
                    prompt = prompt,
                    image = decodeComfyBinding(obj.optJSONObject("image")),
                    outputNodeId = output,
                ),
            negative = decodeComfyBinding(obj.optJSONObject("negative")),
            steps = decodeComfyBinding(obj.optJSONObject("steps")),
            cfg = decodeComfyBinding(obj.optJSONObject("cfg")),
            seed = decodeComfyBinding(obj.optJSONObject("seed")),
            denoise = decodeComfyBinding(obj.optJSONObject("denoise")),
            maskNodeId = obj.optString("mask").takeIf { it.isNotBlank() },
            samplerNodeId = obj.optString("sampler").takeIf { it.isNotBlank() },
        )
    }

    private fun decodeComfyBinding(obj: JSONObject?): ComfyBinding? {
        if (obj == null) return null
        val node = obj.optString("node").takeIf { it.isNotBlank() } ?: return null
        val field = obj.optString("field").takeIf { it.isNotBlank() } ?: return null
        return ComfyBinding(nodeId = node, fieldName = field)
    }
}
