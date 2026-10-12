package com.reverie.paint.core.comfy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComfyWorkflowParserTest {

    private val txt2imgJson = """
        {
          "3": {
            "class_type": "KSampler",
            "inputs": {
              "seed": 12345,
              "steps": 20,
              "model": ["4", 0],
              "positive": ["6", 0]
            }
          },
          "4": {
            "class_type": "CheckpointLoaderSimple",
            "inputs": {"ckpt_name": "sd_xl_base_1.0.safetensors"}
          },
          "6": {
            "class_type": "CLIPTextEncode",
            "inputs": {"text": "a cute cat, 水彩风格", "clip": ["4", 1]}
          },
          "9": {
            "class_type": "SaveImage",
            "inputs": {"filename_prefix": "ComfyUI", "images": ["8", 0]}
          }
        }
    """.trimIndent()

    private val img2imgJson = """
        {
          "1": {"class_type": "LoadImage", "inputs": {"image": "upload.png"}},
          "2": {"class_type": "VAEEncode", "inputs": {"pixels": ["1", 0], "vae": ["3", 0]}},
          "7": {"class_type": "SaveImage", "inputs": {"images": ["5", 0]}}
        }
    """.trimIndent()

    @Test
    fun `parses standard api format txt2img`() {
        val result = ComfyWorkflowParser.parse(txt2imgJson, ComfyWorkflowType.TXT2IMG)
        assertTrue("expected Success but was $result", result is ComfyWorkflowParser.ParseResult.Success)
        val ok = result as ComfyWorkflowParser.ParseResult.Success
        assertEquals(ComfyWorkflowType.TXT2IMG, ok.workflow.type)
        assertEquals(listOf("3", "4", "6", "9"), ok.workflow.nodes.map { it.id })
        assertEquals("KSampler", ok.workflow.nodes[0].classType)
        // org.json 的 keys() 不保证顺序, 解析器统一按字典序输出
        assertEquals(listOf("model", "positive", "seed", "steps"), ok.workflow.nodes[0].inputNames)
        assertEquals("CheckpointLoaderSimple", ok.workflow.nodes[1].classType)
    }

    @Test
    fun `numeric node ids are ordered numerically not lexicographically`() {
        val json = """{"10":{"class_type":"B"},"2":{"class_type":"C"},"1":{"class_type":"A"}}"""
        val ok = ComfyWorkflowParser.parse(json, ComfyWorkflowType.TXT2IMG)
                as ComfyWorkflowParser.ParseResult.Success
        assertEquals(listOf("1", "2", "10"), ok.workflow.nodes.map { it.id })
    }

    @Test
    fun `parses standard api format img2img`() {
        val result = ComfyWorkflowParser.parse(img2imgJson, ComfyWorkflowType.IMG2IMG)
        assertTrue("expected Success but was $result", result is ComfyWorkflowParser.ParseResult.Success)
        val ok = result as ComfyWorkflowParser.ParseResult.Success
        assertEquals(ComfyWorkflowType.IMG2IMG, ok.workflow.type)
        assertEquals(listOf("1", "2", "7"), ok.workflow.nodes.map { it.id })
        assertTrue(ok.workflow.nodes[0].inputNames.contains("image"))
    }
    @Test
    fun `success exposes raw prompt for later rewrite`() {
        val ok = ComfyWorkflowParser.parse(txt2imgJson, ComfyWorkflowType.TXT2IMG)
                as ComfyWorkflowParser.ParseResult.Success
        assertEquals("a cute cat, 水彩风格", ok.rawPrompt.getJSONObject("6").getJSONObject("inputs").getString("text"))
    }

    @Test
    fun `rejects ui format with workflow key`() {
        val ui = """
            {
              "workflow": {"nodes": [{"id": 1, "type": "KSampler", "pos": [10, 20]}], "links": []},
              "id": 12,
              "revision": 0
            }
        """.trimIndent()
        val result = ComfyWorkflowParser.parse(ui, ComfyWorkflowType.TXT2IMG)
        assertTrue("expected NotApiFormat but was $result", result is ComfyWorkflowParser.ParseResult.NotApiFormat)
    }

    @Test
    fun `rejects ui format without workflow key but with nodes array`() {
        val ui = """{"last_node_id": 9, "last_link_id": 9, "nodes": [{"id": 1}], "links": [], "groups": []}"""
        val result = ComfyWorkflowParser.parse(ui, ComfyWorkflowType.TXT2IMG)
        assertTrue("expected NotApiFormat but was $result", result is ComfyWorkflowParser.ParseResult.NotApiFormat)
    }

    @Test
    fun `empty string is invalid`() {
        assertTrue(ComfyWorkflowParser.parse("", ComfyWorkflowType.TXT2IMG) is ComfyWorkflowParser.ParseResult.Invalid)
    }

    @Test
    fun `whitespace only is invalid`() {
        val result = ComfyWorkflowParser.parse("   \n\t ", ComfyWorkflowType.TXT2IMG)
        assertTrue(result is ComfyWorkflowParser.ParseResult.Invalid)
    }

    @Test
    fun `non json text is invalid`() {
        val result = ComfyWorkflowParser.parse("hello world", ComfyWorkflowType.TXT2IMG)
        assertTrue(result is ComfyWorkflowParser.ParseResult.Invalid)
    }

    @Test
    fun `truncated json is invalid`() {
        assertTrue(ComfyWorkflowParser.parse("""{"1": {"class_type": """, ComfyWorkflowType.TXT2IMG)
                is ComfyWorkflowParser.ParseResult.Invalid)
    }

    @Test
    fun `top level array is invalid`() {
        assertTrue(ComfyWorkflowParser.parse("""[{"class_type":"KSampler"}]""", ComfyWorkflowType.TXT2IMG)
                is ComfyWorkflowParser.ParseResult.Invalid)
    }

    @Test
    fun `top level scalar is invalid`() {
        assertTrue(ComfyWorkflowParser.parse("42", ComfyWorkflowType.TXT2IMG)
                is ComfyWorkflowParser.ParseResult.Invalid)
        assertTrue(ComfyWorkflowParser.parse(""""just a string"""", ComfyWorkflowType.TXT2IMG)
                is ComfyWorkflowParser.ParseResult.Invalid)
    }

    @Test
    fun `node without class_type is invalid`() {
        val json = """{"1": {"inputs": {"text": "hi"}}, "2": {"class_type": "SaveImage", "inputs": {}}}"""
        val result = ComfyWorkflowParser.parse(json, ComfyWorkflowType.TXT2IMG)
        assertTrue("expected Invalid but was $result", result is ComfyWorkflowParser.ParseResult.Invalid)
        assertTrue((result as ComfyWorkflowParser.ParseResult.Invalid).reason.contains("class_type"))
    }

    @Test
    fun `node with blank class_type is invalid`() {
        assertTrue(ComfyWorkflowParser.parse("""{"1": {"class_type": "  "}}""", ComfyWorkflowType.TXT2IMG)
                is ComfyWorkflowParser.ParseResult.Invalid)
    }

    @Test
    fun `node value that is not an object is invalid`() {
        assertTrue(ComfyWorkflowParser.parse("""{"1": ["a", 0]}""", ComfyWorkflowType.TXT2IMG)
                is ComfyWorkflowParser.ParseResult.Invalid)
        assertTrue(ComfyWorkflowParser.parse("""{"1": "KSampler"}""", ComfyWorkflowType.TXT2IMG)
                is ComfyWorkflowParser.ParseResult.Invalid)
    }

    @Test
    fun `empty object is invalid`() {
        val result = ComfyWorkflowParser.parse("{}", ComfyWorkflowType.TXT2IMG)
        assertTrue("expected Invalid but was $result", result is ComfyWorkflowParser.ParseResult.Invalid)
        assertTrue((result as ComfyWorkflowParser.ParseResult.Invalid).reason.contains("节点"))
    }

    @Test
    fun `array shaped inputs yield empty input names`() {
        val json = """{"1": {"class_type": "LinkBatch", "inputs": ["3", 0]}, "3": {"class_type": "KSampler"}}"""
        val ok = ComfyWorkflowParser.parse(json, ComfyWorkflowType.TXT2IMG)
                as ComfyWorkflowParser.ParseResult.Success
        assertEquals(emptyList<String>(), ok.workflow.nodes[0].inputNames)
        assertEquals("KSampler", ok.workflow.nodes[1].classType)
    }

    @Test
    fun `missing inputs yield empty input names`() {
        val json = """{"1": {"class_type": "Note"}}"""
        val ok = ComfyWorkflowParser.parse(json, ComfyWorkflowType.TXT2IMG)
                as ComfyWorkflowParser.ParseResult.Success
        assertEquals(emptyList<String>(), ok.workflow.nodes[0].inputNames)
    }

    @Test
    fun `null inputs yield empty input names`() {
        val json = """{"1": {"class_type": "Note", "inputs": null}}"""
        val ok = ComfyWorkflowParser.parse(json, ComfyWorkflowType.TXT2IMG)
                as ComfyWorkflowParser.ParseResult.Success
        assertEquals(emptyList<String>(), ok.workflow.nodes[0].inputNames)
    }

    @Test
    fun `chinese prompt text is preserved`() {
        val json = """{"6": {"class_type": "CLIPTextEncode", "inputs": {"text": "水彩风格的猫，柔和光线"}}}"""
        val ok = ComfyWorkflowParser.parse(json, ComfyWorkflowType.TXT2IMG)
                as ComfyWorkflowParser.ParseResult.Success
        assertEquals("水彩风格的猫，柔和光线", ok.rawPrompt.getJSONObject("6").getJSONObject("inputs").getString("text"))
        assertEquals(listOf("text"), ok.workflow.nodes[0].inputNames)
    }

    @Test
    fun `escaped unicode prompt is decoded`() {
        val json = """{"6": {"class_type": "CLIPTextEncode", "inputs": {"text": "雪山\u00b7日出"}}}"""
        val ok = ComfyWorkflowParser.parse(json, ComfyWorkflowType.TXT2IMG)
                as ComfyWorkflowParser.ParseResult.Success
        assertEquals("雪山\u00b7日出", ok.rawPrompt.getJSONObject("6").getJSONObject("inputs").getString("text"))
    }

    @Test
    fun `parser never throws on garbage input`() {
        val samples = listOf(
            "{", "}", "[]", "null", "{\"1\":", "{\"1\":{\"inputs\":\"oops\"}}",
            "{\"1\":[]}", "\u0000\u0001", "{\"workflow\":", "{\"nodes\":",
        )
        for (sample in samples) {
            val result = ComfyWorkflowParser.parse(sample, ComfyWorkflowType.TXT2IMG)
            assertTrue(
                "unexpected success for $sample",
                result !is ComfyWorkflowParser.ParseResult.Success,
            )
        }
    }

    @Test
    fun `non numeric node ids are tolerated as long as they look like nodes`() {
        val ok = ComfyWorkflowParser.parse("""{"checkpoint-1": {"class_type": "CheckpointLoaderSimple"}}""",
                ComfyWorkflowType.TXT2IMG) as ComfyWorkflowParser.ParseResult.Success
        assertEquals("checkpoint-1", ok.workflow.nodes[0].id)
    }
}
