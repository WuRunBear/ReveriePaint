package com.reverie.paint.core.comfy

import org.junit.Assert.assertEquals
import org.junit.Test

class AiModelsTest {

    @Test
    fun `context defaults resolve to plain generation over the whole canvas`() {
        val ctx = AiContext()
        assertEquals(false, ctx.hasSelection)
        assertEquals(AiSourceKind.WHOLE_CANVAS, ctx.sourceKind)
        assertEquals(100, ctx.strengthPercent)
        assertEquals(AiGenerateMode.CUSTOM, ctx.mode)
    }

    @Test
    fun `context strength is clamped into zero to one hundred`() {
        assertEquals(0, AiContext(strengthPercent = -1).strength)
        assertEquals(0, AiContext(strengthPercent = 0).strength)
        assertEquals(55, AiContext(strengthPercent = 55).strength)
        assertEquals(100, AiContext(strengthPercent = 100).strength)
        assertEquals(100, AiContext(strengthPercent = 101).strength)
    }

    @Test
    fun `context is a value holder so equality drives recomposition`() {
        val a = AiContext(true, AiSourceKind.SELECTION, 62, AiGenerateMode.FILL)
        val b = AiContext(true, AiSourceKind.SELECTION, 62, AiGenerateMode.FILL)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(false, a == b.copy(strengthPercent = 63))
    }

    @Test
    fun `variant carries the comfy image reference and defaults to no label`() {
        val ref = ComfyImageRef(filename = "ComfyUI_00001_.png", subfolder = "", type = "output")
        val variant = AiVariant(index = 0, imageRef = ref)
        assertEquals(0, variant.index)
        assertEquals(ref, variant.imageRef)
        assertEquals("", variant.label)
    }

    @Test
    fun `variant index and label are carried through`() {
        val ref = ComfyImageRef("a.png", "", "output")
        val variant = AiVariant(index = 3, imageRef = ref, label = "第 2 批")
        assertEquals(3, variant.index)
        assertEquals("第 2 批", variant.label)
    }

    @Test
    fun `generate modes cover the painter facing intents`() {
        assertEquals(
            listOf("FILL", "EXTEND", "ADD", "REMOVE", "REPLACE_BG", "CUSTOM"),
            AiGenerateMode.entries.map { it.name },
        )
        assertEquals(
            listOf("WHOLE_CANVAS", "SELECTION", "REFERENCE"),
            AiSourceKind.entries.map { it.name },
        )
    }
}