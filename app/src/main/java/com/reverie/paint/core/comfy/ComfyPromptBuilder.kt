package com.reverie.paint.core.comfy

import org.json.JSONObject

/**
 * 提交前构造 workflow JSON 时捕获的用户输入。
 *
 * @property prompt 正向提示词。**可为空 —— 空提示词是合法输入**:
 *   文案承诺"留空即按周边像素续写", 实现就是把 CLIPTextEncode 的 text 写成空串
 *   (无条件嵌入, 画面完全由输入图与选区边缘决定), 而不是保留模板里的原提示词
 * @property negativePrompt 负面提示词; null 表示不注入, 模板里原有的值原样保留
 * @property imageName `/upload/image` 返回的文件名; null 表示不注入
 * @property strength 改动幅度 0..100, 换算成 `KSampler.denoise = strength / 100`;
 *   null 表示不注入 (沿用模板里的值), 与 steps / cfg / seed 相同语义
 * @property steps 采样步数; null 表示不注入 (沿用模板里的值)
 * @property cfg 提示词引导强度; null 表示不注入 (沿用模板里的值)
 * @property seed 随机种子; null 表示不注入 (沿用模板里的值), 走 SEED 角色绑定
 */
internal data class AiGenerationParams(
    val prompt: String,
    val negativePrompt: String? = null,
    val imageName: String? = null,
    val strength: Int? = null,
    val steps: Int? = null,
    val cfg: Float? = null,
    val seed: Long? = null,
) {
    /** 改动幅度 → denoise, 0..100 归一化到 0.0..1.0; strength 未设置时返回 null (不注入) */
    val denoise: Double? get() = strength?.let { it.coerceIn(0, 100) / 100.0 }
}

/**
 * 把用户输入注入工作流 JSON。
 *
 * 三条硬约束:
 * 1. **只改绑定的字段, 不碰其他节点** —— 绑定之外的输入原样提交, 用户在 ComfyUI 里
 *    调好的 steps / cfg / sampler 一次都不该被我们覆盖
 * 2. **不污染入参** —— 在深拷贝上改写 ([JSONObject] 没有 copy 构造, 走 toString 往返)
 * 3. **路径找不到就跳过, 绝不抛异常** —— 工作流是用户给的不可信数据, 坏一个字段
 *    不该让整次提交崩掉; 调用方靠 [ComfyAutoBinder] 的 unresolved 看绑定摘要
 */
internal object ComfyPromptBuilder {

    private const val SEPARATOR = '.'
    private const val INPUTS = "inputs"

    fun build(
        rawWorkflow: JSONObject,
        bindingSet: AiBindingSet,
        params: AiGenerationParams,
    ): JSONObject {
        val out = JSONObject(rawWorkflow.toString())

        // 正向提示词: 永远写, 空串也写 (空提示词是合法输入)
        put(out, pathOf(bindingSet.base.prompt), params.prompt.ifBlank { "" })

        bindingSet.negative?.let { binding ->
            params.negativePrompt?.let { put(out, pathOf(binding), it) }
        }

        bindingSet.base.image?.let { binding ->
            params.imageName?.let { put(out, pathOf(binding), it) }
        }

        bindingSet.denoise?.let { binding ->
            params.denoise?.let { put(out, pathOf(binding), it) }
        }

        // 采样参数走绑定: 未绑定 / 参数为 null 都不注入, 保持工作流原值
        bindingSet.steps?.let { binding ->
            params.steps?.let { put(out, pathOf(binding), it) }
        }
        bindingSet.cfg?.let { binding ->
            params.cfg?.let { put(out, pathOf(binding), it) }
        }
        bindingSet.seed?.let { binding ->
            params.seed?.let { put(out, pathOf(binding), it) }
        }

        return out
    }

    /** 绑定 → 点号路径, 如 `6.inputs.text` */
    fun pathOf(binding: ComfyBinding): String =
        binding.nodeId + SEPARATOR + INPUTS + SEPARATOR + binding.fieldName

    /**
     * 按点号路径读值, 路径不存在时返回 null。
     *
     * 暴露出来是为了让单测能用 `"6.inputs.text"` 断言注入结果, 不必手写三层 getJSONObject。
     */
    fun valueAt(root: JSONObject, path: String): Any? {
        val target = resolve(root, path) ?: return null
        val value = target.first.opt(target.second)
        return if (value === JSONObject.NULL) null else value
    }

    /**
     * 按点号路径写值, 返回是否写成功。
     *
     * 路径中除最后一段外必须**已经存在** —— 我们只允许覆盖已声明的绑定字段,
     * 绝不凭空造节点或造 inputs 容器。
     */
    fun put(root: JSONObject, path: String, value: Any): Boolean {
        val target = resolve(root, path) ?: return false
        target.first.put(target.second, value)
        return true
    }

    /** 把 `a.b.c` 拆成 (容器 a.b, 末段 c); 任一段不存在则返回 null */
    private fun resolve(root: JSONObject, path: String): Pair<JSONObject, String>? {
        val parts = path.split(SEPARATOR)
        if (parts.size < 2) return null
        var cursor: Any? = root
        for (i in 0 until parts.size - 1) {
            cursor = (cursor as? JSONObject)?.opt(parts[i]) ?: return null
        }
        val container = cursor as? JSONObject ?: return null
        return container to parts.last()
    }
}