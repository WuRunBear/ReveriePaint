package com.reverie.paint.core.comfy

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComfyParamDefaultsTest {

    private val samplerJson = """
        {
          "3": {"class_type": "KSampler", "inputs": {"seed": 1234567890123456789, "steps": 20, "cfg": 8.5}},
          "6": {"class_type": "CLIPTextEncode", "inputs": {"text": "p"}},
          "9": {"class_type": "SaveImage", "inputs": {"images": ["3", 0]}}
        }
    """.trimIndent()

    private fun raw(json: String = samplerJson): JSONObject = JSONObject(json)

    private fun bindings(
        steps: ComfyBinding? = ComfyBinding("3", "steps"),
        cfg: ComfyBinding? = ComfyBinding("3", "cfg"),
        seed: ComfyBinding? = ComfyBinding("3", "seed"),
    ): AiBindingSet =
        AiBindingSet(
            base = ComfyBindings(prompt = ComfyBinding("6", "text"), outputNodeId = "9"),
            steps = steps,
            cfg = cfg,
            seed = seed,
        )

    @Test
    fun `reads the actual bound steps cfg and seed from the workflow`() {
        val d = ComfyParamDefaultsReader.read(raw(), bindings())
        assertEquals(20, d.steps)
        assertEquals(8.5f, d.cfg!!, 1e-6f)
        assertEquals(1234567890123456789L, d.seed)
    }

    @Test
    fun `a null binding set yields no defaults at all`() {
        val d = ComfyParamDefaultsReader.read(raw(), null)
        assertNull(d.steps)
        assertNull(d.cfg)
        assertNull(d.seed)
    }

    @Test
    fun `unbound roles stay null so the panel keeps its own value`() {
        val d = ComfyParamDefaultsReader.read(raw(), bindings(steps = null, cfg = null, seed = null))
        assertNull(d.steps)
        assertNull(d.cfg)
        assertNull(d.seed)
    }

    @Test
    fun `a binding pointing at a missing node yields null instead of throwing`() {
        val d = ComfyParamDefaultsReader.read(raw(), bindings(steps = ComfyBinding("77", "steps")))
        assertNull(d.steps)
        assertEquals(8.5f, d.cfg!!, 1e-6f)
    }

    @Test
    fun `steps are clamped to a positive sane range`() {
        assertEquals(1, ComfyParamDefaultsReader.read(raw("""{"3":{"inputs":{"steps":0}}}"""), bindings()).steps)
        assertEquals(1, ComfyParamDefaultsReader.read(raw("""{"3":{"inputs":{"steps":-5}}}"""), bindings()).steps)
        assertEquals(
            ComfyParamDefaultsReader.MAX_STEPS,
            ComfyParamDefaultsReader.read(raw("""{"3":{"inputs":{"steps":999999}}}"""), bindings()).steps,
        )
    }

    @Test
    fun `cfg is clamped to a sane range`() {
        assertEquals(
            ComfyParamDefaultsReader.MAX_CFG,
            ComfyParamDefaultsReader.read(raw("""{"3":{"inputs":{"cfg":999}}}"""), bindings()).cfg!!,
            1e-6f,
        )
        assertEquals(
            ComfyParamDefaultsReader.MIN_CFG,
            ComfyParamDefaultsReader.read(raw("""{"3":{"inputs":{"cfg":-2}}}"""), bindings()).cfg!!,
            1e-6f,
        )
    }

    @Test
    fun `a seed beyond Long range saturates instead of overflowing`() {
        val d = ComfyParamDefaultsReader.read(raw("""{"3":{"inputs":{"seed":99999999999999999999}}}"""), bindings())
        assertEquals(Long.MAX_VALUE, d.seed)
    }

    @Test
    fun `a negative seed is clamped to zero`() {
        val d = ComfyParamDefaultsReader.read(raw("""{"3":{"inputs":{"seed":-42}}}"""), bindings())
        assertEquals(0L, d.seed)
    }

    @Test
    fun `numeric strings are accepted as a fallback`() {
        val d =
            ComfyParamDefaultsReader.read(
                raw("""{"3":{"inputs":{"steps":"24","cfg":"6.5","seed":"900000000000"}}}"""),
                bindings(),
            )
        assertEquals(24, d.steps)
        assertEquals(6.5f, d.cfg!!, 1e-6f)
        assertEquals(900000000000L, d.seed)
    }

    @Test
    fun `non numeric junk yields null instead of throwing`() {
        val d = ComfyParamDefaultsReader.read(raw("""{"3":{"inputs":{"steps":"abc","cfg":{},"seed":null}}}"""), bindings())
        assertNull(d.steps)
        assertNull(d.cfg)
        assertNull(d.seed)
    }
}
