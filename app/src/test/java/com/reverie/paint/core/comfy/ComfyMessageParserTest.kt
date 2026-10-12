package com.reverie.paint.core.comfy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComfyMessageParserTest {

    @Test
    fun `parses progress`() {
        val event = ComfyMessageParser.parse(
            """{"type":"progress","data":{"value":12,"max":20,"prompt_id":"abc","node":"6"}}""",
        )
        assertEquals(ComfyMessageParser.Event.Progress(12, 20), event)
    }

    @Test
    fun `progress without max degrades to value`() {
        assertEquals(
            ComfyMessageParser.Event.Progress(5, 5),
            ComfyMessageParser.parse("""{"type":"progress","data":{"value":5}}"""),
        )
    }

    @Test
    fun `progress with zero max degrades to value`() {
        assertEquals(
            ComfyMessageParser.Event.Progress(0, 0),
            ComfyMessageParser.parse("""{"type":"progress","data":{"value":0,"max":0}}"""),
        )
    }

    @Test
    fun `progress with negative values is clamped`() {
        assertEquals(
            ComfyMessageParser.Event.Progress(0, 3),
            ComfyMessageParser.parse("""{"type":"progress","data":{"value":-5,"max":3}}"""),
        )
    }

    @Test
    fun `progress with no data at all degrades`() {
        assertEquals(
            ComfyMessageParser.Event.Progress(0, 0),
            ComfyMessageParser.parse("""{"type":"progress"}"""),
        )
    }

    @Test
    fun `progress with string numbers is accepted`() {
        assertEquals(
            ComfyMessageParser.Event.Progress(4, 8),
            ComfyMessageParser.parse("""{"type":"progress","data":{"value":"4","max":"8"}}"""),
        )
    }

    @Test
    fun `parses executing node`() {
        assertEquals(
            ComfyMessageParser.Event.Executing("6"),
            ComfyMessageParser.parse("""{"type":"executing","data":{"node":"6","prompt_id":"abc"}}"""),
        )
    }

    @Test
    fun `executing with null node means finished`() {
        assertEquals(
            ComfyMessageParser.Event.Executing(null),
            ComfyMessageParser.parse("""{"type":"executing","data":{"node":null,"prompt_id":"abc"}}"""),
        )
    }

    @Test
    fun `executing without node field is null not the string null`() {
        assertEquals(
            ComfyMessageParser.Event.Executing(null),
            ComfyMessageParser.parse("""{"type":"executing","data":{}}"""),
        )
        assertEquals(
            ComfyMessageParser.Event.Executing(null),
            ComfyMessageParser.parse("""{"type":"executing"}"""),
        )
    }

    @Test
    fun `parses executed images`() {
        val event = ComfyMessageParser.parse(
            """
            {"type":"executed","data":{"node":"9","output":{},
             "outputs":{"images":[
               {"filename":"ComfyUI_00001_.png","subfolder":"","type":"output"},
               {"filename":"ComfyUI_00002_.png","subfolder":"batch","type":"temp"}]}}}
            """.trimIndent(),
        )
        assertEquals(
            ComfyMessageParser.Event.Executed(
                listOf(
                    ComfyImageRef("ComfyUI_00001_.png", "", "output"),
                    ComfyImageRef("ComfyUI_00002_.png", "batch", "temp"),
                ),
            ),
            event,
        )
    }

    @Test
    fun `executed without images yields empty list`() {
        assertEquals(
            ComfyMessageParser.Event.Executed(emptyList()),
            ComfyMessageParser.parse("""{"type":"executed","data":{"node":"9","outputs":{}}}"""),
        )
        assertEquals(
            ComfyMessageParser.Event.Executed(emptyList()),
            ComfyMessageParser.parse("""{"type":"executed","data":{"node":"9"}}"""),
        )
    }

    @Test
    fun `executed skips malformed image entries`() {
        val event = ComfyMessageParser.parse(
            """{"type":"executed","data":{"outputs":{"images":[
                 "not-an-object", {"subfolder":"x","type":"output"},
                 {"filename":"","type":"output"}, {"filename":"ok.png"}]}}}""",
        ) as ComfyMessageParser.Event.Executed
        assertEquals(listOf(ComfyImageRef("ok.png", "", "")), event.images)
    }

    @Test
    fun `parses execution error`() {
        assertEquals(
            ComfyMessageParser.Event.ExecutionError("Prompt has no outputs", "ValueError"),
            ComfyMessageParser.parse(
                """{"type":"execution_error","data":{"exception_message":"Prompt has no outputs",
                   "exception_type":"ValueError","node_id":"9"}}""",
            ),
        )
    }

    @Test
    fun `execution error without fields degrades to empty strings`() {
        assertEquals(
            ComfyMessageParser.Event.ExecutionError("", ""),
            ComfyMessageParser.parse("""{"type":"execution_error","data":{}}"""),
        )
    }

    @Test
    fun `parses execution start`() {
        assertEquals(
            ComfyMessageParser.Event.ExecutionStart,
            ComfyMessageParser.parse("""{"type":"execution_start","data":{"prompt_id":"abc"}}"""),
        )
    }

    @Test
    fun `parses queue status`() {
        assertEquals(
            ComfyMessageParser.Event.QueueStatus(3),
            ComfyMessageParser.parse(
                """{"type":"status","data":{"status":{"exec_info":{"queue_remaining":3}},"sid":"abc"}}""",
            ),
        )
    }

    @Test
    fun `queue status without exec info degrades to zero`() {
        assertEquals(
            ComfyMessageParser.Event.QueueStatus(0),
            ComfyMessageParser.parse("""{"type":"status","data":{"status":{"exec_info":{}}}}"""),
        )
        assertEquals(
            ComfyMessageParser.Event.QueueStatus(0),
            ComfyMessageParser.parse("""{"type":"status"}"""),
        )
    }

    @Test
    fun `unknown type becomes Unknown with original type`() {
        assertEquals(
            ComfyMessageParser.Event.Unknown("something_new"),
            ComfyMessageParser.parse("""{"type":"something_new","data":{}}"""),
        )
    }

    @Test
    fun `execution cached is reported as Unknown preserving type`() {
        assertEquals(
            ComfyMessageParser.Event.Unknown("execution_cached"),
            ComfyMessageParser.parse("""{"type":"execution_cached","data":{"nodes":["1","3"],"node":"3"}}"""),
        )
    }

    @Test
    fun `malformed json does not throw`() {
        assertEquals(ComfyMessageParser.Event.Unknown(""), ComfyMessageParser.parse("{not json"))
        assertEquals(ComfyMessageParser.Event.Unknown(""), ComfyMessageParser.parse("{\"type\":"))
    }

    @Test
    fun `empty and blank input do not throw`() {
        assertEquals(ComfyMessageParser.Event.Unknown(""), ComfyMessageParser.parse(""))
        assertEquals(ComfyMessageParser.Event.Unknown(""), ComfyMessageParser.parse("   "))
    }

    @Test
    fun `non object json does not throw`() {
        assertEquals(ComfyMessageParser.Event.Unknown(""), ComfyMessageParser.parse("[1,2,3]"))
        assertEquals(ComfyMessageParser.Event.Unknown(""), ComfyMessageParser.parse("null"))
        assertEquals(ComfyMessageParser.Event.Unknown(""), ComfyMessageParser.parse("123"))
    }

    @Test
    fun `missing type field does not throw`() {
        assertEquals(
            ComfyMessageParser.Event.Unknown(""),
            ComfyMessageParser.parse("""{"data":{"value":1}}"""),
        )
    }

    @Test
    fun `data of unexpected shape does not throw`() {
        assertEquals(
            ComfyMessageParser.Event.Progress(0, 0),
            ComfyMessageParser.parse("""{"type":"progress","data":"oops"}"""),
        )
        assertEquals(
            ComfyMessageParser.Event.Executed(emptyList()),
            ComfyMessageParser.parse("""{"type":"executed","data":{"outputs":[1,2]}}"""),
        )
    }

    @Test
    fun `fuzzed garbage never throws`() {
        val samples = listOf(
            "\u0000", "\u0000{\"type\":\"executed\"}", "{\"type\":123}", "{\"type\":null}",
            "{\"type\":\"progress\",\"data\":{\"value\":\"abc\"}}",
            "{\"type\":\"executed\",\"data\":{\"outputs\":{\"images\":null}}}",
            "{\"type\":\"executing\",\"data\":{\"node\":{}}}",
            "{\"type\":\"execution_error\",\"data\":{\"exception_message\":5}}",
            "{\"type\":\"status\",\"data\":{\"status\":\"busy\"}}",
            "{\"type\":\"executed\",\"data\":{\"outputs\":{\"images\":[{}]}}}",
        )
        for (sample in samples) {
            val event = ComfyMessageParser.parse(sample)
            assertTrue("unexpected null event for $sample", event is ComfyMessageParser.Event)
        }
    }
}
