package com.aiagents.utils

/**
 * 移除字符串中的Markdown格式
 * @return 移除Markdown格式后的纯文本
 */
fun String.stripMarkdown(): String {
    return this
        // 移除代码块 (```...``` 和 `...`)
        .replace(Regex("```[\\s\\S]*?```|`[^`]*?`"), "")
        // 移除图片和链接，但保留其文本内容
        .replace(Regex("!?\\[([^\\]]+)\\]\\([^\\)]*\\)"), "$1")
        // 移除加粗和斜体 (先处理两个星号的)
        .replace(Regex("\\*\\*([^*]+?)\\*\\*"), "$1")
        .replace(Regex("\\*([^*]+?)\\*"), "$1")
        // 移除下划线
        .replace(Regex("__([^_]+?)__"), "$1")
        .replace(Regex("_([^_]+?)_"), "$1")
        // 移除删除线
        .replace(Regex("~~([^~]+?)~~"), "$1")
        // 移除标题标记 (多行模式)
        .replace(Regex("(?m)^#+\\s*"), "")
        // 移除列表标记 (多行模式)
        .replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
        .replace(Regex("(?m)^\\s*\\d+\\.\\s+"), "")
        // 移除引用标记 (多行模式)
        .replace(Regex("(?m)^>\\s*"), "")
        // 移除水平分割线
        .replace(Regex("(?m)^(\\s*[-*_]){3,}\\s*$"), "")
        // 将多个换行符压缩，以保留段落
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

/**
 * 为语音朗读清洗文本(防串扰):
 * - 移除反斜杠(会被语音引擎逐字读出, 如 "\n" 读成 "反斜杠n")
 * - 把 Markdown/排版格式修饰(标题、列表、引用、代码、分割线等)替换为句号,
 *   使朗读自然停顿, 避免格式标记干扰听感
 * - 压缩多余空白
 */
fun String.cleanForSpeech(): String {
    return this
        // 移除反斜杠
        .replace("\\", "")
        // 代码块 → 句号; 行内代码 → 保留内容
        .replace(Regex("```[\\s\\S]*?```"), "。")
        .replace(Regex("`([^`]*?)`"), "$1")
        // 图片/链接, 保留其文本内容
        .replace(Regex("!?\\[([^\\]]+)\\]\\([^\\)]*\\)"), "$1")
        // 加粗/斜体/下划线/删除线, 保留内容
        .replace(Regex("\\*\\*([^*]+?)\\*\\*"), "$1")
        .replace(Regex("\\*([^*]+?)\\*"), "$1")
        .replace(Regex("__([^_]+?)__"), "$1")
        .replace(Regex("_([^_]+?)_"), "$1")
        .replace(Regex("~~([^~]+?)~~"), "$1")
        // 标题/列表/引用标记 → 句号
        .replace(Regex("(?m)^#+\\s*"), "。")
        .replace(Regex("(?m)^\\s*[-*+]\\s+"), "。")
        .replace(Regex("(?m)^\\s*\\d+\\.\\s+"), "。")
        .replace(Regex("(?m)^>\\s*"), "。")
        // 水平分割线 → 句号
        .replace(Regex("(?m)^(\\s*[-*_]){3,}\\s*$"), "。")
        // 压缩空白(含换行)为单个空格
        .replace(Regex("\\s+"), " ")
        .trim()
}

fun String.extractThinkingTitle(): String? {
    // 按行分割文本
    val lines = this.lines()

    // 从后往前查找最后一个符合条件的加粗文本行
    for (i in lines.indices.reversed()) {
        val line = lines[i].trim()

        // 检查是否为加粗格式且独占一整行
        val boldPattern = Regex("^\\*\\*(.+?)\\*\\*$")
        val match = boldPattern.find(line)

        if (match != null) {
            // 返回加粗标记内的文本内容
            return match.groupValues[1].trim().takeUnless { it.isBlank() }
        }
    }

    return null
}
