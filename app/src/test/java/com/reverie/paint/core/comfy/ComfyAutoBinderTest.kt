package com.reverie.paint.core.comfy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComfyAutoBinderTest {

    private fun parse(json: String): ComfyWorkflowParser.ParseResult.Success =
        ComfyWorkflowParser.parse(json, ComfyWorkflowType.TXT2IMG)
            as ComfyWorkflowParser.ParseResult.Success

    private fun bind(json: String, hasReference: Boolean = false): AutoBindResult {
        val ok = parse(json)
        return ComfyAutoBinder.bind(ok.rawPrompt, ok.workflow, hasReference)
    }

    private fun AutoBindResult.has(key: BindNoteKey) = unresolved.any { it.key == key }

    private fun AutoBindResult.argContains(fragment: String) =
        unresolved.any { note -> note.args.any { it.contains(fragment) } }

    // ---------------- 真实工作流: ComfyUI 标准 txt2img ----------------

    /** ComfyUI「默认文生图」导出的 API 格式, 逐字按官方示例构造 */
    private val standardTxt2img = """
        {
          "3": {
            "class_type": "KSampler",
            "inputs": {
              "seed": 156680208700286,
              "steps": 20,
              "cfg": 8,
              "sampler_name": "euler",
              "scheduler": "normal",
              "denoise": 1,
              "model": ["4", 0],
              "positive": ["6", 0],
              "negative": ["7", 0],
              "latent_image": ["5", 0]
            }
          },
          "4": {
            "class_type": "CheckpointLoaderSimple",
            "inputs": {"ckpt_name": "v1-5-pruned-emaonly.safetensors"}
          },
          "5": {
            "class_type": "EmptyLatentImage",
            "inputs": {"width": 512, "height": 512, "batch_size": 1}
          },
          "6": {
            "class_type": "CLIPTextEncode",
            "inputs": {
              "text": "beautiful scenery nature glass bottle landscape",
              "clip": ["4", 1]
            }
          },
          "7": {
            "class_type": "CLIPTextEncode",
            "inputs": {"text": "text, watermark", "clip": ["4", 1]}
          },
          "8": {
            "class_type": "VAEDecode",
            "inputs": {"samples": ["3", 0], "vae": ["4", 2]}
          },
          "9": {
            "class_type": "SaveImage",
            "inputs": {"filename_prefix": "ComfyUI", "images": ["8", 0]}
          }
        }
    """.trimIndent()

    @Test
    fun `standard txt2img resolves prompt and negative through sampler links`() {
        val r = bind(standardTxt2img)
        assertEquals(ComfyBinding("6", "text"), r.prompt)
        assertEquals(ComfyBinding("7", "text"), r.negativePrompt)
        assertEquals(WorkflowKind.TXT2IMG, r.workflowKind)
        assertEquals("3", r.samplerNodeId)
        assertTrue("unexpected notes: ${r.unresolved}", r.unresolved.isEmpty())
        assertTrue(r.isUsable)
    }

    @Test
    fun `standard txt2img output node is the producer behind SaveImage`() {
        // SaveImage 的 images 是连线, executed 消息按生产节点给 outputs, 因此取 8 而非 9
        assertEquals("8", bind(standardTxt2img).outputNodeId)
    }

    @Test
    fun `standard txt2img has neither image input nor denoise binding`() {
        val r = bind(standardTxt2img)
        assertNull(r.imageInput)
        assertNull(r.maskNodeId)
        assertNull(r.denoise)
        assertNull(r.bindingSet()?.denoise)
    }

    @Test
    fun `standard txt2img binds sampler steps cfg and seed`() {
        val r = bind(standardTxt2img)
        assertEquals(ComfyBinding("3", "steps"), r.steps)
        assertEquals(ComfyBinding("3", "cfg"), r.cfg)
        assertEquals(ComfyBinding("3", "seed"), r.seed)
    }

    @Test
    fun `binding set and editable carry steps cfg and seed`() {
        val r = bind(standardTxt2img)
        val set = r.bindingSet()!!
        assertEquals(ComfyBinding("3", "steps"), set.steps)
        assertEquals(ComfyBinding("3", "cfg"), set.cfg)
        assertEquals(ComfyBinding("3", "seed"), set.seed)
        assertEquals(set, r.toEditable().toBindingSet())
    }

    @Test
    fun `missing sampler fields are reported instead of silently skipped`() {
        val json = """
            {
              "1": {"class_type": "CLIPTextEncode", "inputs": {"text": "p"}},
              "2": {"class_type": "KSampler", "inputs": {"denoise": 1, "positive": ["1", 0]}},
              "3": {"class_type": "SaveImage", "inputs": {"images": ["2", 0]}}
            }
        """.trimIndent()
        val r = bind(json)
        assertNull(r.steps)
        assertNull(r.cfg)
        assertNull(r.seed)
        assertTrue(r.has(BindNoteKey.STEPS_FIELD_MISSING))
        assertTrue(r.has(BindNoteKey.CFG_FIELD_MISSING))
        assertTrue(r.has(BindNoteKey.SEED_FIELD_MISSING))
    }

    @Test
    fun `KSamplerAdvanced noise_seed is recognised as the seed slot`() {
        val json = """
            {
              "1": {"class_type": "CLIPTextEncode", "inputs": {"text": "p"}},
              "2": {"class_type": "KSamplerAdvanced", "inputs": {"add_noise": "enable", "steps": 20,
                       "cfg": 7, "noise_seed": 42, "positive": ["1", 0]}},
              "3": {"class_type": "SaveImage", "inputs": {"images": ["2", 0]}}
            }
        """.trimIndent()
        val r = bind(json)
        assertEquals(ComfyBinding("2", "steps"), r.steps)
        assertEquals(ComfyBinding("2", "cfg"), r.cfg)
        assertEquals(ComfyBinding("2", "noise_seed"), r.seed)
    }

    @Test
    fun `a linked seed is not a writable slot and is reported`() {
        val json = """
            {
              "1": {"class_type": "CLIPTextEncode", "inputs": {"text": "p"}},
              "2": {"class_type": "RandomNoise", "inputs": {"noise_seed": 1}},
              "3": {"class_type": "KSampler", "inputs": {"denoise": 1, "steps": 20, "cfg": 7,
                       "seed": ["2", 0], "positive": ["1", 0]}},
              "4": {"class_type": "SaveImage", "inputs": {"images": ["3", 0]}}
            }
        """.trimIndent()
        val r = bind(json)
        assertNull(r.seed)
        assertTrue(r.has(BindNoteKey.SEED_FIELD_MISSING))
        assertEquals(ComfyBinding("3", "steps"), r.steps)
    }

    @Test
    fun `hasReference does not disturb a workflow that has no image input`() {
        val r = bind(standardTxt2img, hasReference = true)
        assertTrue(r.has(BindNoteKey.REFERENCE_WITHOUT_IMAGE_INPUT))
        assertEquals(ComfyBinding("6", "text"), r.prompt)
    }

    // ---------------- 真实工作流: ComfyUI 标准 inpaint ----------------

    private val standardInpaint = """
        {
          "1": {"class_type": "LoadImage", "inputs": {"image": "input.png", "upload": "image"}},
          "2": {"class_type": "LoadImage", "inputs": {"image": "mask.png", "upload": "image"}},
          "3": {
            "class_type": "VAEEncodeForInpaint",
            "inputs": {"pixels": ["1", 0], "vae": ["4", 2], "mask": ["2", 0], "width": 512, "height": 512}
          },
          "4": {"class_type": "CheckpointLoaderSimple", "inputs": {"ckpt_name": "sd_xl_base_1.0.safetensors"}},
          "5": {"class_type": "CLIPTextEncode", "inputs": {"text": "a castle", "clip": ["4", 1]}},
          "6": {"class_type": "CLIPTextEncode", "inputs": {"text": "blurry", "clip": ["4", 1]}},
          "7": {
            "class_type": "KSampler",
            "inputs": {
              "seed": 42, "steps": 20, "cfg": 7, "sampler_name": "euler",
              "scheduler": "normal", "denoise": 0.75,
              "model": ["4", 0], "positive": ["5", 0], "negative": ["6", 0], "latent_image": ["3", 0]
            }
          },
          "8": {"class_type": "VAEDecode", "inputs": {"samples": ["7", 0], "vae": ["4", 2]}},
          "9": {"class_type": "SaveImage", "inputs": {"filename_prefix": "ComfyUI", "images": ["8", 0]}}
        }
    """.trimIndent()

    @Test
    fun `standard inpaint binds the second load image as mask`() {
        val r = bind(standardInpaint)
        assertEquals(WorkflowKind.INPAINT, r.workflowKind)
        assertEquals(ComfyBinding("1", "image"), r.imageInput)
        assertEquals("2", r.maskNodeId)
        assertEquals(ComfyBinding("7", "denoise"), r.denoise)
        assertEquals(ComfyBinding("5", "text"), r.prompt)
        assertEquals(ComfyBinding("6", "text"), r.negativePrompt)
        assertEquals("8", r.outputNodeId)
        assertTrue("mask convention must be spelled out", r.has(BindNoteKey.MASK_CONVENTION))
    }

    @Test
    fun `inpaint binding set carries every slot the builder needs`() {
        val set = bind(standardInpaint).bindingSet()
        assertNotNull(set)
        assertEquals(ComfyBinding("5", "text"), set!!.base.prompt)
        assertEquals(ComfyBinding("1", "image"), set.base.image)
        assertEquals("8", set.base.outputNodeId)
        assertEquals(ComfyBinding("6", "text"), set.negative)
        assertEquals(ComfyBinding("7", "denoise"), set.denoise)
        assertEquals("2", set.maskNodeId)
        assertEquals("7", set.samplerNodeId)
    }

    @Test
    fun `editable round trip preserves every binding slot`() {
        val r = bind(standardInpaint)
        // 手动编辑走 EditableBindings 中转, 全部角色与辅助绑定 (蒙版 / 采样器) 都不能丢
        assertEquals(r.bindingSet(), r.toEditable().toBindingSet())
    }

    @Test
    fun `editable sameRolesAs catches every role edit including denoise`() {
        val r = bind(standardInpaint)
        val auto = r.toEditable()
        // 改动幅度已是正式角色: 手动改它必须被判定为"改过"
        assertTrue(!auto.sameRolesAs(auto.copy(denoise = null)))
        assertTrue(!auto.sameRolesAs(auto.copy(prompt = ComfyBinding("6", "text"))))
        // 蒙版 / 采样器仍是辅助绑定, 不参与角色比较
        assertTrue(auto.sameRolesAs(auto.copy(maskNodeId = null, samplerNodeId = null)))
    }

    // ---------------- 真实工作流: img2img ----------------

    private val standardImg2img = """
        {
          "1": {"class_type": "LoadImage", "inputs": {"image": "input.png"}},
          "2": {"class_type": "VAEEncode", "inputs": {"pixels": ["1", 0], "vae": ["3", 2]}},
          "3": {"class_type": "CheckpointLoaderSimple", "inputs": {"ckpt_name": "anything.safetensors"}},
          "4": {"class_type": "CLIPTextEncode", "inputs": {"text": "watercolor", "clip": ["3", 1]}},
          "5": {
            "class_type": "KSampler",
            "inputs": {
              "seed": 7, "steps": 20, "cfg": 8, "sampler_name": "euler",
              "scheduler": "normal", "denoise": 0.5,
              "model": ["3", 0], "positive": ["4", 0], "negative": ["4", 0], "latent_image": ["2", 0]
            }
          },
          "6": {"class_type": "VAEDecode", "inputs": {"samples": ["5", 0], "vae": ["3", 2]}},
          "7": {"class_type": "SaveImage", "inputs": {"filename_prefix": "out", "images": ["6", 0]}}
        }
    """.trimIndent()

    @Test
    fun `img2img binds image input and denoise`() {
        val r = bind(standardImg2img)
        assertEquals(WorkflowKind.IMG2IMG, r.workflowKind)
        assertEquals(ComfyBinding("1", "image"), r.imageInput)
        assertEquals(ComfyBinding("5", "denoise"), r.denoise)
        assertNull(r.maskNodeId)
        assertTrue(r.has(BindNoteKey.ONLY_ONE_IMAGE_INPUT))
    }

    // ---------------- 连线写法 ----------------

    @Test
    fun `string form links resolve like array form`() {
        val json = """
            {
              "1": {"class_type": "KSampler", "inputs": {"denoise": 1, "positive": "2", "negative": "3"}},
              "2": {"class_type": "CLIPTextEncode", "inputs": {"text": "pos"}},
              "3": {"class_type": "CLIPTextEncode", "inputs": {"text": "neg"}},
              "4": {"class_type": "SaveImage", "inputs": {"images": "2"}}
            }
        """.trimIndent()
        val r = bind(json)
        assertEquals(ComfyBinding("2", "text"), r.prompt)
        assertEquals(ComfyBinding("3", "text"), r.negativePrompt)
        assertEquals("2", r.outputNodeId)
    }

    @Test
    fun `single clip text encode used for both ends keeps positive and drops negative`() {
        val r = bind(standardImg2img)
        assertEquals(ComfyBinding("4", "text"), r.prompt)
        assertNull(r.negativePrompt)
        assertTrue(r.has(BindNoteKey.SAME_POSITIVE_NEGATIVE))
    }

    @Test
    fun `dangling link is reported instead of guessed`() {
        val json = """
            {
              "1": {"class_type": "KSampler", "inputs": {"denoise": 1, "positive": ["99", 0], "negative": ["2", 0]}},
              "2": {"class_type": "CLIPTextEncode", "inputs": {"text": "neg"}},
              "3": {"class_type": "SaveImage", "inputs": {"images": ["1", 0]}}
            }
        """.trimIndent()
        val r = bind(json)
        assertNull(r.prompt)
        assertEquals(ComfyBinding("2", "text"), r.negativePrompt)
        assertTrue(r.has(BindNoteKey.POSITIVE_TARGET_NOT_PROMPT))
        assertTrue(r.argContains("99"))
        assertTrue(!r.isUsable)
        assertNull(r.bindingSet())
    }

    @Test
    fun `dangling output link falls back to the output node itself`() {
        val json = """
            {
              "1": {"class_type": "CLIPTextEncode", "inputs": {"text": "p"}},
              "2": {"class_type": "KSampler", "inputs": {"denoise": 1, "positive": ["1", 0]}},
              "3": {"class_type": "SaveImage", "inputs": {"images": ["42", 0]}}
            }
        """.trimIndent()
        val r = bind(json)
        assertEquals("3", r.outputNodeId)
        assertTrue(r.has(BindNoteKey.OUTPUT_LINK_MISSING))
        assertTrue(r.argContains("42"))
    }

    // ---------------- 没有采样器 / 没有可识别内容 ----------------

    @Test
    fun `without a sampler the literal clip text encode scan takes over`() {
        val json = """
            {
              "6": {"class_type": "CLIPTextEncode", "inputs": {"text": "a cat", "clip": ["4", 1]}},
              "7": {"class_type": "CLIPTextEncode", "inputs": {"text": "blurry", "clip": ["4", 1]}},
              "8": {"class_type": "VAEDecode", "inputs": {"samples": ["6", 0], "vae": ["4", 2]}},
              "9": {"class_type": "PreviewImage", "inputs": {"images": ["8", 0]}}
            }
        """.trimIndent()
        val r = bind(json)
        assertEquals(WorkflowKind.UNKNOWN, r.workflowKind)
        assertEquals(ComfyBinding("6", "text"), r.prompt)
        assertEquals("8", r.outputNodeId)
        assertTrue(r.has(BindNoteKey.NO_SAMPLER))
        assertNull(r.negativePrompt)
    }

    @Test
    fun `preview image is used when there is no save image`() {
        val json = """
            {"6": {"class_type": "CLIPTextEncode", "inputs": {"text": "a cat"}},
             "9": {"class_type": "PreviewImage", "inputs": {"images": ["6", 0]}}}
        """.trimIndent()
        assertEquals("6", bind(json).outputNodeId)
    }

    @Test
    fun `unrecognisable workflow returns unknown with explanations`() {
        val r = bind("""{"9": {"class_type": "Note", "inputs": {"text": "hello"}}}""")
        assertEquals(WorkflowKind.UNKNOWN, r.workflowKind)
        assertNull(r.prompt)
        assertNull(r.negativePrompt)
        assertNull(r.imageInput)
        assertNull(r.outputNodeId)
        assertTrue("expected at least 3 notes, got ${r.unresolved}", r.unresolved.size >= 3)
        assertTrue(r.has(BindNoteKey.NO_SAMPLER))
        assertTrue(r.has(BindNoteKey.NO_PROMPT_NODE))
        assertTrue(r.has(BindNoteKey.NO_OUTPUT_NODE))
        assertTrue(!r.isUsable)
        assertNull(r.bindingSet())
    }

    @Test
    fun `a clip text encode whose text is a link is not treated as a prompt slot`() {
        val json = """
            {
              "1": {"class_type": "CLIPTextEncode", "inputs": {"text": "real"}},
              "2": {"class_type": "CLIPTextEncode", "inputs": {"text": ["1", 0]}},
              "3": {"class_type": "CLIPTextEncode", "inputs": {"text_g": "sdxl pos", "text_l": "sdxl neg"}},
              "4": {"class_type": "PreviewImage", "inputs": {"images": ["1", 0]}}
            }
        """.trimIndent()
        val r = bind(json)
        assertEquals(ComfyBinding("1", "text"), r.prompt)
    }

    // ---------------- 图片输入 ----------------

    @Test
    fun `three load images use the first as image and the last as mask`() {
        val json = """
            {
              "1": {"class_type": "LoadImage", "inputs": {"image": "a.png"}},
              "2": {"class_type": "LoadImage", "inputs": {"image": "control.png"}},
              "3": {"class_type": "LoadImage", "inputs": {"image": "mask.png"}},
              "4": {"class_type": "VAEEncode", "inputs": {"pixels": ["1", 0], "mask": ["3", 0]}},
              "5": {"class_type": "KSampler", "inputs": {"denoise": 0.6, "positive": ["1", 0]}}
            }
        """.trimIndent()
        val r = bind(json)
        assertEquals(ComfyBinding("1", "image"), r.imageInput)
        assertEquals("3", r.maskNodeId)
        assertEquals(WorkflowKind.INPAINT, r.workflowKind)
    }

    @Test
    fun `single load image leaves the mask slot null with a warning`() {
        val json = """
            {
              "1": {"class_type": "LoadImage", "inputs": {"image": "a.png"}},
              "2": {"class_type": "KSampler", "inputs": {"denoise": 0.5, "positive": ["1", 0]}}
            }
        """.trimIndent()
        val r = bind(json)
        assertEquals(ComfyBinding("1", "image"), r.imageInput)
        assertNull(r.maskNodeId)
        assertEquals(WorkflowKind.IMG2IMG, r.workflowKind)
        assertTrue(r.has(BindNoteKey.ONLY_ONE_IMAGE_INPUT))
    }

    @Test
    fun `load image mask node counts as a mask input`() {
        val json = """
            {
              "1": {"class_type": "LoadImage", "inputs": {"image": "a.png"}},
              "2": {"class_type": "LoadImageMask", "inputs": {"image": "m.png", "channel": "red"}},
              "3": {"class_type": "KSampler", "inputs": {"denoise": 0.8, "positive": ["1", 0]}}
            }
        """.trimIndent()
        assertEquals(WorkflowKind.INPAINT, bind(json).workflowKind)
    }

    @Test
    fun `denoise of zero is called out because the result would equal the input`() {
        val json = """
            {
              "1": {"class_type": "LoadImage", "inputs": {"image": "a.png"}},
              "2": {"class_type": "KSampler", "inputs": {"denoise": 0, "positive": ["1", 0]}}
            }
        """.trimIndent()
        assertTrue(bind(json).has(BindNoteKey.DENOISE_ZERO))
    }

    @Test
    fun `non literal denoise is called out`() {
        val json = """
            {
              "1": {"class_type": "LoadImage", "inputs": {"image": "a.png"}},
              "2": {"class_type": "PrimitiveNode", "inputs": {"value": 0.4}},
              "3": {"class_type": "KSampler", "inputs": {"denoise": ["2", 0], "positive": ["1", 0]}}
            }
        """.trimIndent()
        assertTrue(bind(json).has(BindNoteKey.DENOISE_NOT_LITERAL))
    }

    // ---------------- SDXL 的 text_g / text_l ----------------

    @Test
    fun `sdxl clip text encode binds the text_g field`() {
        val json = """
            {
              "1": {"class_type": "KSampler", "inputs": {"denoise": 1, "positive": ["2", 0], "negative": ["3", 0]}},
              "2": {"class_type": "CLIPTextEncode", "inputs": {"text_g": "pos", "text_l": "pos", "clip": ["4", 1]}},
              "3": {"class_type": "CLIPTextEncode", "inputs": {"text_g": "neg", "text_l": "neg", "clip": ["4", 1]}},
              "4": {"class_type": "SaveImage", "inputs": {"images": ["1", 0]}}
            }
        """.trimIndent()
        val r = bind(json)
        assertEquals(ComfyBinding("2", "text_g"), r.prompt)
        assertEquals(ComfyBinding("3", "text_g"), r.negativePrompt)
    }

    // ---------------- denoise 落点 (正式角色, 与 steps/cfg/seed 同源) ----------------

    @Test
    fun `denoise lands in the binding set so it can be overridden by hand`() {
        val r = bind(standardInpaint)
        assertEquals(ComfyBinding("7", "denoise"), r.denoise)
        assertEquals(ComfyBinding("7", "denoise"), r.bindingSet()?.denoise)
        assertEquals(ComfyBinding("7", "denoise"), r.toEditable().denoise)
    }

    @Test
    fun `denoise is unbound when no sampler carries the field`() {
        val json = """
            {
              "1": {"class_type": "CLIPTextEncode", "inputs": {"text": "p"}},
              "2": {"class_type": "SaveImage", "inputs": {"images": ["1", 0]}}
            }
        """.trimIndent()
        assertNull(bind(json).denoise)
        assertNull(bind(json).bindingSet()?.denoise)
    }
}