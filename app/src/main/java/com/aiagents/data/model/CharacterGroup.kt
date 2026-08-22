package com.aiagents.data.model

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * 角色群组 - 多个助手组成的群聊角色组
 *
 * 当对话绑定到群组时, 系统会为所有成员生成群聊编排提示词,
 * 使多个角色在同一对话中各自扮演不同身份进行交流.
 */
@Serializable
data class CharacterGroup(
    val id: Uuid = Uuid.random(),
    val name: String = "",
    val description: String = "",
    val memberIds: List<Uuid> = emptyList(),  // 成员助手 ID 列表(按顺序)
    val avatar: Avatar = Avatar.Dummy,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * 生成群聊编排系统提示词
 *
 * 当对话绑定到角色群组时, 此提示词注入到 system prompt 中,
 * 指导 AI 为每个角色生成独立的回复.
 *
 * @param members 成员助手列表(已按 orderIndex 排序)
 * @return 群聊编排提示词
 */
fun buildGroupChatPrompt(members: List<Assistant>): String {
    if (members.isEmpty()) return ""

    val memberDescriptions = members.joinToString("\n\n") { assistant ->
        val name = assistant.name.ifBlank { "角色${members.indexOf(assistant) + 1}" }
        val personality = assistant.systemPrompt.take(500).ifBlank { "（无特殊设定）" }
        buildString {
            append("### $name\n")
            append("人格设定: $personality")
            if (assistant.avatar is Avatar.Image) {
                append("\n头像: ${assistant.avatar.url}")
            } else if (assistant.avatar is Avatar.Emoji) {
                append("\n头像: ${assistant.avatar.content}")
            }
        }
    }

    return buildString {
        appendLine("# 群聊模式")
        appendLine()
        appendLine("当前对话中有以下 ${members.size} 位角色参与:")
        appendLine()
        appendLine(memberDescriptions)
        appendLine()
        appendLine("## 群聊规则")
        appendLine("1. 每条回复只代表一个角色发言, 使用 `[角色名]: ` 前缀标识")
        appendLine("2. 不同角色应有各自独立的性格、语气和说话方式")
        appendLine("3. 角色之间可以互相回应、争论、对话")
        appendLine("4. 根据对话内容, 选择最合适的角色来回应")
        appendLine("5. 如果用户直接对某个角色说话, 该角色应回应")
        appendLine("6. 保持角色一致性, 不要混淆不同角色的设定")
        appendLine("7. 角色之间可以产生互动和化学反应")
    }
}
