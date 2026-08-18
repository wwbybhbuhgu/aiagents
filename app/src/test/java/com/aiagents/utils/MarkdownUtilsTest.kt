package com.aiagents.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownUtilsTest {

    @Test
    fun `cleanForSpeech removes backslashes`() {
        assertEquals("反斜杠n 换行", "反斜杠\\n 换行".cleanForSpeech())
    }

    @Test
    fun `cleanForSpeech converts markdown decorations to periods`() {
        assertEquals("。标题", "# 标题".cleanForSpeech())
        assertEquals("。列表项", "- 列表项".cleanForSpeech())
        assertEquals("。引用", "> 引用".cleanForSpeech())
    }

    @Test
    fun `cleanForSpeech keeps inline content of bold and code`() {
        assertEquals("加粗 内容", "**加粗** `内容`".cleanForSpeech())
    }

    @Test
    fun `cleanForSpeech collapses whitespace`() {
        assertEquals("第一句 第二句", "第一句\n\n  第二句".cleanForSpeech())
    }

    @Test
    fun `cleanForSpeech trims surrounding whitespace`() {
        assertEquals("你好", "  你好  ".cleanForSpeech())
    }
}
