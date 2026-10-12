/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.comfy

import org.json.JSONObject

/**
 * 从工作流绑定字段读出的采样参数默认值。
 *
 * 每个字段为 null 表示**该角色未绑定 / 读不到** —— 调用方应当保留面板上的原值,
 * 绝不能把 null 当成 0 写进去 (未绑定时面板对应控件保持禁用)。
 */
internal data class ComfyParamDefaults(
    val steps: Int? = null,
    val cfg: Float? = null,
    val seed: Long? = null,
)

/**
 * 只读工具: 给定工作流 JSON + 绑定集合, 取出 STEPS / CFG / SEED 绑定位置的实际值。
 *
 * 读法与 [ComfyPromptBuilder] 写值走**同一条点号路径** ([ComfyPromptBuilder.pathOf] +
 * [ComfyPromptBuilder.valueAt]) —— 写进去是什么, 读出来就是什么, 两边不会漂移。
 * 工作流是用户给的不可信数据: 路径找不到、字段类型不是数字一律返回 null, 绝不抛异常。
 *
 * 读到的值做**合法性钳制**, 避免工作流里的怪值 (负步数 / 天文数字 CFG) 把面板滑块搞崩:
 * - 步数: [MIN_STEPS]..[MAX_STEPS]
 * - CFG: [MIN_CFG]..[MAX_CFG]
 * - 种子: 非负 (JSON 数字超出 Long 范围时饱和到 `Long.MAX_VALUE`, 不溢出)
 */
internal object ComfyParamDefaultsReader {

    const val MIN_STEPS = 1
    const val MAX_STEPS = 150
    const val MIN_CFG = 0f
    const val MAX_CFG = 30f

    fun read(rawWorkflow: JSONObject, bindings: AiBindingSet?): ComfyParamDefaults =
        ComfyParamDefaults(
            steps = stepsOf(rawWorkflow, bindings?.steps),
            cfg = cfgOf(rawWorkflow, bindings?.cfg),
            seed = seedOf(rawWorkflow, bindings?.seed),
        )

    /** 步数: 非数字 / 未绑定返回 null; 否则钳制到 [MIN_STEPS]..[MAX_STEPS] */
    fun stepsOf(rawWorkflow: JSONObject, binding: ComfyBinding?): Int? =
        numericValue(valueOf(rawWorkflow, binding))?.toInt()?.coerceIn(MIN_STEPS, MAX_STEPS)

    /** CFG: 非数字 / 未绑定返回 null; 否则钳制到 [MIN_CFG]..[MAX_CFG] */
    fun cfgOf(rawWorkflow: JSONObject, binding: ComfyBinding?): Float? =
        numericValue(valueOf(rawWorkflow, binding))?.toFloat()?.coerceIn(MIN_CFG, MAX_CFG)

    /** 种子: 非数字 / 未绑定返回 null; 保留 Long 精度, 负数钳到 0 */
    fun seedOf(rawWorkflow: JSONObject, binding: ComfyBinding?): Long? =
        longValue(valueOf(rawWorkflow, binding))?.coerceAtLeast(0L)

    /** 绑定 → 路径 → 读值; 绑定为空或路径不存在时返回 null */
    private fun valueOf(rawWorkflow: JSONObject, binding: ComfyBinding?): Any? =
        binding?.let { ComfyPromptBuilder.valueAt(rawWorkflow, ComfyPromptBuilder.pathOf(it)) }

    /** 宽松取数: 兼容 JSON 数字与数字字符串, 非有限值视为读不到 */
    private fun numericValue(value: Any?): Double? =
        when (value) {
            is Number -> value.toDouble().takeIf { it.isFinite() }
            is String -> value.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
            else -> null
        }

    /**
     * 种子专用: **Long 原样保留** (走 numericValue 的 Double 转换会丢精度,
     * 18 位以上的种子会偏几十)。
     */
    private fun longValue(value: Any?): Long? =
        when (value) {
            is Long -> value
            is Int -> value.toLong()
            is Number -> value.toDouble().takeIf { it.isFinite() }?.toLong()
            is String ->
                value.trim().toLongOrNull()
                    ?: value.trim().toDoubleOrNull()?.takeIf { it.isFinite() }?.toLong()
            else -> null
        }
}
