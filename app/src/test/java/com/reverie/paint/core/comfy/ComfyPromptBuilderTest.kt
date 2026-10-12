package com.reverie.paint.core.comfy

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComfyPromptBuilderTest {

    private val inpaintJson = """
        {
          "1": {"class_type": "LoadImage", "inputs": {"image": "old.png", "upload": "image"}},
          "2": {"class_type": "LoadImage", "inputs": {"image": "old_mask.png", "upload": "image"}},
          "3": {
            "class_type": "VAEEncodeForInpaint",
            "inputs": {"pixels": ["1", 0], "vae": ["4", 2], "mask": ["2", 0], "width": 512, "height": 512}
          },
          "4": {"class_type": "CheckpointLoaderSimple", "inputs": {"ckpt_name": "sd_xl_base_1.0.safetensors"}},
          "5": {"class_type": "CLIPTextEncode", "inputs": {"text": "old positive", "clip": ["4", 1]}},
          "6": {"class_type": "CLIPTextEncode", "inputs": {"text": "old negative", "clip": ["4", 1]}},
          "7": {
            "class_type": "KSampler",
            "inputs": {
              "seed": 42, "steps": 28, "cfg": 7, "sampler_name": "euler",
              "scheduler": "normal", "denoise": 0.75,
              "model": ["4", 0], "positive": ["5", 0], "negative": ["6", 0], "latent_image": ["3", 0]
            }
          },
          "8": {"class_type": "VAEDecode", "inputs": {"samples": ["7", 0], "vae": ["4", 2]}},
          "9": {"class_type": "SaveImage", "inputs": {"filename_prefix": "ComfyUI", "images": ["8", 0]}}
        }
    """.trimIndent()

    private fun parsed(json: String = inpaintJson): ComfyWorkflowParser.ParseResult.Success =
        ComfyWorkflowParser.parse(json, ComfyWorkflowType.TXT2IMG)
            as ComfyWorkflowParser.ParseResult.Success

    private fun bindingSet(): AiBindingSet {
        val ok = parsed()
        val result = ComfyAutoBinder.bind(ok.rawPrompt, ok.workflow)
        return result.bindingSet()!!
    }

    private fun built(params: AiGenerationParams, json: String = inpaintJson): JSONObject =
        ComfyPromptBuilder.build(parsed(json).rawPrompt, bindingSet(), params)

    // ---------------- 基本注入 ----------------

    @Test
    fun `injects prompt negative image denoise and seed`() {
        val out = built(
            AiGenerationParams(
                prompt = "a snowy mountain",
                negativePrompt = "blurry",
                imageName = "abc123.png",
                strength = 62,
                seed = 987654321L,
            ),
        )
        assertEquals("a snowy mountain", ComfyPromptBuilder.valueAt(out, "5.inputs.text"))
        assertEquals("blurry", ComfyPromptBuilder.valueAt(out, "6.inputs.text"))
        assertEquals("abc123.png", ComfyPromptBuilder.valueAt(out, "1.inputs.image"))
        assertEquals(0.62, (ComfyPromptBuilder.valueAt(out, "7.inputs.denoise") as Number).toDouble(), 1e-9)
        assertEquals(987654321L, (ComfyPromptBuilder.valueAt(out, "7.inputs.seed") as Number).toLong())
    }

    @Test
    fun `binding renders as a dotted path`() {
        assertEquals("6.inputs.text", ComfyPromptBuilder.pathOf(ComfyBinding("6", "text")))
        assertEquals("11.inputs.image", ComfyPromptBuilder.pathOf(ComfyBinding("11", "image")))
    }

    // ---------------- 不误改其他节点 ----------------

    @Test
    fun `untouched inputs survive verbatim`() {
        val out = built(AiGenerationParams(prompt = "x", negativePrompt = "y", imageName = "z.png"))
        val sampler = out.getJSONObject("7")
        assertEquals(28, sampler.getJSONObject("inputs").getInt("steps"))
        assertEquals(7.0, sampler.getJSONObject("inputs").getDouble("cfg"), 1e-9)
        assertEquals("euler", sampler.getJSONObject("inputs").getString("sampler_name"))
        assertEquals(42L, sampler.getJSONObject("inputs").getLong("seed"))
        assertEquals(
            "sd_xl_base_1.0.safetensors",
            out.getJSONObject("4").getJSONObject("inputs").getString("ckpt_name"),
        )
        assertEquals(
            "ComfyUI",
            out.getJSONObject("9").getJSONObject("inputs").getString("filename_prefix"),
        )
        assertEquals(
            "old_mask.png",
            out.getJSONObject("2").getJSONObject("inputs").getString("image"),
        )
        assertEquals(
            512,
            out.getJSONObject("3").getJSONObject("inputs").getInt("width"),
        )
    }

    @Test
    fun `the source workflow object is never mutated`() {
        val raw = parsed().rawPrompt
        val before = raw.toString()
        ComfyPromptBuilder.build(raw, bindingSet(), AiGenerationParams(prompt = "changed", strength = 10))
        assertEquals(before, raw.toString())
    }

    // ---------------- 空输入 ----------------

    @Test
    fun `blank prompt is legal and overwrites the template text with an empty string`() {
        val out = built(AiGenerationParams(prompt = "   "))
        assertEquals("", ComfyPromptBuilder.valueAt(out, "5.inputs.text"))
        assertTrue(out.getJSONObject("5").getJSONObject("inputs").has("text"))
    }

    @Test
    fun `null negative prompt leaves the template value alone`() {
        val out = built(AiGenerationParams(prompt = "x", negativePrompt = null))
        assertEquals("old negative", ComfyPromptBuilder.valueAt(out, "6.inputs.text"))
    }

    @Test
    fun `null image name leaves the template value alone`() {
        val out = built(AiGenerationParams(prompt = "x", imageName = null))
        assertEquals("old.png", ComfyPromptBuilder.valueAt(out, "1.inputs.image"))
    }

    @Test
    fun `null seed leaves the template seed alone`() {
        val out = built(AiGenerationParams(prompt = "x", seed = null))
        assertEquals(42L, out.getJSONObject("7").getJSONObject("inputs").getLong("seed"))
    }

    // ---------------- steps / cfg 走绑定注入 ----------------

    @Test
    fun `injects steps and cfg through their bindings`() {
        val out = built(AiGenerationParams(prompt = "x", steps = 33, cfg = 5.5f))
        assertEquals(33, out.getJSONObject("7").getJSONObject("inputs").getInt("steps"))
        assertEquals(5.5, out.getJSONObject("7").getJSONObject("inputs").getDouble("cfg"), 1e-9)
    }

    @Test
    fun `null steps and cfg leave the template values alone`() {
        val out = built(AiGenerationParams(prompt = "x", steps = null, cfg = null))
        assertEquals(28, out.getJSONObject("7").getJSONObject("inputs").getInt("steps"))
        assertEquals(7.0, out.getJSONObject("7").getJSONObject("inputs").getDouble("cfg"), 1e-9)
    }

    @Test
    fun `steps are not injected when the workflow has no steps binding`() {
        val noSampler = """
            {
              "1": {"class_type": "CLIPTextEncode", "inputs": {"text": "p"}},
              "2": {"class_type": "SaveImage", "inputs": {"images": ["1", 0]}}
            }
        """.trimIndent()
        val raw = parsed(noSampler).rawPrompt
        val set = AiBindingSet(base = ComfyBindings(prompt = ComfyBinding("1", "text"), outputNodeId = "2"))
        val out = ComfyPromptBuilder.build(raw, set, AiGenerationParams(prompt = "x", steps = 40, cfg = 9f))
        assertNull(ComfyPromptBuilder.valueAt(out, "1.inputs.steps"))
        assertNull(ComfyPromptBuilder.valueAt(out, "1.inputs.cfg"))
    }

    @Test
    fun `steps and cfg are not injected when the field is left unbound`() {
        val ok = parsed()
        val set = ComfyAutoBinder.bind(ok.rawPrompt, ok.workflow).bindingSet()!!
        val noParams = set.copy(steps = null, cfg = null, seed = null)
        val out =
            ComfyPromptBuilder.build(
                ok.rawPrompt,
                noParams,
                AiGenerationParams(prompt = "x", steps = 40, cfg = 9f, seed = 12345L),
            )
        assertEquals(28, out.getJSONObject("7").getJSONObject("inputs").getInt("steps"))
        assertEquals(7.0, out.getJSONObject("7").getJSONObject("inputs").getDouble("cfg"), 1e-9)
        assertEquals(42L, out.getJSONObject("7").getJSONObject("inputs").getLong("seed"))
    }

    // ---------------- strength → denoise ----------------

    @Test
    fun `strength maps to denoise across the whole range`() {
        val cases = mapOf(0 to 0.0, 1 to 0.01, 30 to 0.30, 62 to 0.62, 100 to 1.0, 250 to 1.0, -9 to 0.0)
        cases.forEach { (strength, expected) ->
            val params = AiGenerationParams(prompt = "x", strength = strength)
            assertEquals(expected, params.denoise, 1e-9)
            val out = built(params)
            assertEquals(
                "strength=$strength",
                expected,
                (ComfyPromptBuilder.valueAt(out, "7.inputs.denoise") as Number).toDouble(),
                1e-9,
            )
        }
    }

    @Test
    fun `txt2img binding set has no denoise slot so strength is not injected`() {
        val txt2img = """
            {
              "3": {
                "class_type": "KSampler",
                "inputs": {"seed": 1, "steps": 20, "cfg": 8, "denoise": 1,
                           "model": ["4", 0], "positive": ["6", 0], "negative": ["7", 0]}
              },
              "4": {"class_type": "CheckpointLoaderSimple", "inputs": {"ckpt_name": "a"}},
              "6": {"class_type": "CLIPTextEncode", "inputs": {"text": "p", "clip": ["4", 1]}},
              "7": {"class_type": "CLIPTextEncode", "inputs": {"text": "n", "clip": ["4", 1]}},
              "9": {"class_type": "SaveImage", "inputs": {"images": ["3", 0]}}
            }
        """.trimIndent()
        val ok = parsed(txt2img)
        val set = ComfyAutoBinder.bind(ok.rawPrompt, ok.workflow).bindingSet()!!
        assertNull(set.denoise)
        val out = ComfyPromptBuilder.build(ok.rawPrompt, set, AiGenerationParams(prompt = "x", strength = 40))
        assertEquals(1.0, out.getJSONObject("3").getJSONObject("inputs").getDouble("denoise"), 1e-9)
    }

    // ---------------- 三字段绑定的重载 ----------------

    @Test
    fun `the ComfyBindings overload still writes denoise and prompt`() {
        val raw = parsed().rawPrompt
        val set = bindingSet()
        val out = ComfyPromptBuilder.build(
            raw,
            set.base,
            AiGenerationParams(prompt = "short path", negativePrompt = "ignored", imageName = "ignored.png"),
        )
        assertEquals("short path", ComfyPromptBuilder.valueAt(out, "5.inputs.text"))
        // denoise 落点由 findDenoiseBinding 现查, 仍然写进采样器
        assertEquals(1.0, (ComfyPromptBuilder.valueAt(out, "7.inputs.denoise") as Number).toDouble(), 1e-9)
        // 只有三字段的绑定写不了负面提示词, 原值保持不变
        assertEquals("old negative", ComfyPromptBuilder.valueAt(out, "6.inputs.text"))
        assertEquals("ignored.png", ComfyPromptBuilder.valueAt(out, "1.inputs.image"))
    }

    // ---------------- 坏数据 ----------------

    @Test
    fun `a binding pointing at a missing node is skipped instead of throwing`() {
        val broken = """
            {
              "1": {"class_type": "CLIPTextEncode", "inputs": {"text": "p"}},
              "2": {"class_type": "SaveImage", "inputs": {"images": ["1", 0]}}
            }
        """.trimIndent()
        val raw = parsed(broken).rawPrompt
        val set = AiBindingSet(base = ComfyBindings(prompt = ComfyBinding("77", "text"), outputNodeId = "2"))
        val out = ComfyPromptBuilder.build(raw, set, AiGenerationParams(prompt = "x", strength = 50))
        assertFalse(out.has("77"))
        assertEquals("p", ComfyPromptBuilder.valueAt(out, "1.inputs.text"))
    }

    @Test
    fun `valueAt returns null for an unknown path`() {
        val out = built(AiGenerationParams(prompt = "x"))
        assertNull(ComfyPromptBuilder.valueAt(out, "88.inputs.text"))
        assertNull(ComfyPromptBuilder.valueAt(out, "5"))
        assertNull(ComfyPromptBuilder.valueAt(out, "5.inputs.nope"))
    }

    @Test
    fun `put reports whether the path was writable`() {
        val out = built(AiGenerationParams(prompt = "x"))
        assertTrue(ComfyPromptBuilder.put(out, "5.inputs.text", "written"))
        assertEquals("written", ComfyPromptBuilder.valueAt(out, "5.inputs.text"))
        assertFalse(ComfyPromptBuilder.put(out, "88.inputs.text", "nope"))
        assertFalse(ComfyPromptBuilder.put(out, "5", "nope"))
    }

    @Test
    fun `building from an empty workflow returns an empty object instead of crashing`() {
        val out = ComfyPromptBuilder.build(
            JSONObject("{}"),
            AiBindingSet(base = ComfyBindings(prompt = ComfyBinding("1", "text"), outputNodeId = "1")),
            AiGenerationParams(prompt = "x"),
        )
        assertEquals(0, out.length())
    }

    @Test
    fun `round trip through the auto binder and the builder covers a real inpaint preset`() {
        val ok = parsed()
        val result = ComfyAutoBinder.bind(ok.rawPrompt, ok.workflow)
        assertEquals(WorkflowKind.INPAINT, result.workflowKind)
        val out = ComfyPromptBuilder.build(
            ok.rawPrompt,
            result.bindingSet()!!,
            AiGenerationParams(prompt = "a quiet harbour", strength = 45, imageName = "canvas.png"),
        )
        assertEquals("a quiet harbour", ComfyPromptBuilder.valueAt(out, "5.inputs.text"))
        assertEquals("canvas.png", ComfyPromptBuilder.valueAt(out, "1.inputs.image"))
        assertEquals(0.45, (ComfyPromptBuilder.valueAt(out, "7.inputs.denoise") as Number).toDouble(), 1e-9)
        assertEquals(
            ok.rawPrompt.getJSONObject("7").getJSONObject("inputs").getInt("steps"),
            out.getJSONObject("7").getJSONObject("inputs").getInt("steps"),
        )
    }
}