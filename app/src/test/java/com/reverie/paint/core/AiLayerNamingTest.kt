/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import org.junit.Assert.assertEquals
import org.junit.Test

class AiLayerNamingTest {

    @Test
    fun `sequence pads to three digits`() {
        assertEquals("001", formatAiLayerSequence(1))
        assertEquals("042", formatAiLayerSequence(42))
        assertEquals("999", formatAiLayerSequence(999))
    }

    @Test
    fun `sequence beyond 999 grows without truncation`() {
        assertEquals("1000", formatAiLayerSequence(1000))
        assertEquals("12345", formatAiLayerSequence(12345))
    }

    @Test
    fun `layer name joins sequence and workflow name`() {
        assertEquals("001 · one", formatAiLayerName(1, "one"))
        assertEquals("012 · flux-dev", formatAiLayerName(12, "flux-dev"))
    }

    @Test
    fun `layer name degrades to bare sequence when workflow name blank`() {
        assertEquals("007", formatAiLayerName(7, ""))
        assertEquals("007", formatAiLayerName(7, "   "))
    }
}
