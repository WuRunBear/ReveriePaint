package com.reverie.paint.core.comfy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AiButtonResolverTest {

    private fun context(
        hasSelection: Boolean = false,
        source: AiSourceKind = AiSourceKind.WHOLE_CANVAS,
        strength: Int = 100,
        mode: AiGenerateMode = AiGenerateMode.CUSTOM,
    ) = AiContext(hasSelection, source, strength, mode)

    // ---------------- 矩阵: 有无选区 × 取材来源 × 生成方式 ----------------

    @Test
    fun `no selection at full strength reads generate from prompt alone`() {
        val label = AiButtonResolver.resolve(context(source = AiSourceKind.WHOLE_CANVAS, strength = 100))
        assertEquals(AiButtonResolver.ACTION_GENERATE, label.text)
        assertEquals(AiButtonResolver.SUB_REFINE_CANVAS, label.subtitle)
    }

    @Test
    fun `no selection below full strength still reads generate but feeds whole canvas`() {
        val label = AiButtonResolver.resolve(context(source = AiSourceKind.WHOLE_CANVAS, strength = 62))
        assertEquals(AiButtonResolver.ACTION_GENERATE, label.text)
        assertEquals(AiButtonResolver.SUB_REFINE_CANVAS, label.subtitle)
    }

    @Test
    fun `no selection with reference source reads generate from prompt alone`() {
        val label = AiButtonResolver.resolve(context(source = AiSourceKind.REFERENCE, strength = 62))
        assertEquals(AiButtonResolver.ACTION_GENERATE, label.text)
        assertEquals(AiButtonResolver.SUB_FREE_GENERATE, label.subtitle)
    }

    @Test
    fun `selection cleared falls back to generate from prompt alone`() {
        val label = AiButtonResolver.resolve(context(source = AiSourceKind.SELECTION, strength = 62))
        assertEquals(AiButtonResolver.ACTION_GENERATE, label.text)
        assertEquals(AiButtonResolver.SUB_FREE_GENERATE, label.subtitle)
    }

    @Test
    fun `selection at any strength reads fill`() {
        listOf(0, 30, 62, 100).forEach { strength ->
            val label = AiButtonResolver.resolve(
                context(hasSelection = true, source = AiSourceKind.SELECTION, strength = strength),
            )
            assertEquals("strength=$strength", AiButtonResolver.ACTION_FILL, label.text)
            assertEquals(AiGenerateMode.FILL, label.mode)
            assertEquals(AiButtonResolver.SUB_INPAINT, label.subtitle)
        }
    }

    @Test
    fun `explicit selection source without a selection does not claim fill`() {
        val label = AiButtonResolver.resolve(
            context(hasSelection = false, source = AiSourceKind.SELECTION, mode = AiGenerateMode.FILL),
        )
        assertEquals(AiButtonResolver.ACTION_GENERATE, label.text)
        assertNotEquals(AiButtonResolver.SUB_INPAINT, label.subtitle)
    }

    @Test
    fun `extend mode reads expand regardless of selection`() {
        val withSelection = AiButtonResolver.resolve(
            context(hasSelection = true, source = AiSourceKind.SELECTION, mode = AiGenerateMode.EXTEND),
        )
        assertEquals(AiButtonResolver.ACTION_EXPAND, withSelection.text)
        assertEquals(AiGenerateMode.EXTEND, withSelection.mode)
        assertEquals(AiButtonResolver.SUB_OUTPAINT, withSelection.subtitle)

        val withoutSelection = AiButtonResolver.resolve(
            context(hasSelection = false, mode = AiGenerateMode.EXTEND),
        )
        assertEquals(withSelection, withoutSelection)
    }

    @Test
    fun `add remove and replace all read as fill but differ in subtitle`() {
        val cases = mapOf(
            AiGenerateMode.ADD to AiButtonResolver.SUB_ADD_CONTENT,
            AiGenerateMode.REMOVE to AiButtonResolver.SUB_REMOVE_CONTENT,
            AiGenerateMode.REPLACE_BG to AiButtonResolver.SUB_REPLACE_BG,
        )
        cases.forEach { (mode, subtitle) ->
            val label = AiButtonResolver.resolve(context(hasSelection = true, mode = mode))
            assertEquals(mode.name, AiButtonResolver.ACTION_FILL, label.text)
            assertEquals(mode.name, mode, label.mode)
            assertEquals(mode.name, subtitle, label.subtitle)
        }
    }

    @Test
    fun `mode carries through untouched by selection state`() {
        val label = AiButtonResolver.resolve(context(hasSelection = true, mode = AiGenerateMode.REMOVE))
        assertEquals(AiGenerateMode.REMOVE, label.mode)
    }

    // ---------------- 核心不变量: 强度不参与按钮判定 ----------------

    @Test
    fun `strength never changes the button label`() {
        val contexts = listOf(
            context(hasSelection = false, source = AiSourceKind.WHOLE_CANVAS),
            context(hasSelection = false, source = AiSourceKind.REFERENCE),
            context(hasSelection = true, source = AiSourceKind.SELECTION),
            context(hasSelection = true, source = AiSourceKind.SELECTION, mode = AiGenerateMode.ADD),
            context(hasSelection = false, mode = AiGenerateMode.EXTEND),
            context(hasSelection = false, source = AiSourceKind.SELECTION, mode = AiGenerateMode.CUSTOM),
        )
        contexts.forEach { ctx ->
            val labels = listOf(0, 1, 30, 62, 99, 100).map {
                AiButtonResolver.resolve(ctx.copy(strengthPercent = it))
            }.distinct()
            assertEquals("strength changed the label for $ctx", 1, labels.size)
        }
    }

    @Test
    fun `out of range strength is normalized on the context`() {
        assertEquals(100, context(strength = 140).strength)
        assertEquals(0, context(strength = -5).strength)
        assertEquals(62, context(strength = 62).strength)
    }

    @Test
    fun `default context resolves to generate`() {
        val label = AiButtonResolver.resolve(AiContext())
        assertEquals(AiButtonResolver.ACTION_GENERATE, label.text)
        assertEquals(AiGenerateMode.CUSTOM, label.mode)
    }
}