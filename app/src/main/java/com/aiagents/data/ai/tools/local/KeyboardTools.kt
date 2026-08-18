package com.aiagents.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.aiagents.ai.core.InputSchema
import com.aiagents.ai.core.Tool
import com.aiagents.ai.ui.UIMessagePart
import com.aiagents.data.automation.ImeController

/**
 * 键盘输入工具: 切换到内置 AI 输入法, 向当前聚焦的输入框输入文本。
 * 默认保持 AI 键盘为当前输入法(更可靠), 需要切回原输入法时用 restore_ime 工具或悬浮窗按钮。
 * 需要 Shizuku(shell/root) 权限。
 */
internal fun buildKeyboardInputTool(context: Context): Tool = Tool(
    name = "keyboard_input",
    description = """
        把系统输入法切换到内置 AI 键盘, 向当前聚焦的输入框输入文本。
        当用户想让你在另一个应用的文本框里输入文字时使用。
        默认保持 AI 键盘为当前输入法(不切回, 更可靠); 需要切回原输入法时调用 restore_ime 工具。
        首次调用时会自动记录切换前的原输入法, 并在结果中返回当前输入法与已记录的原输入法。
        需要 Shizuku(shell/root) 权限。
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "要输入到聚焦输入框的文本。")
                })
                put("restore", buildJsonObject {
                    put("type", "boolean")
                    put("description", "输入后是否恢复原输入法。默认 false(保持 AI 键盘为当前输入法)。")
                })
            },
            required = listOf("text")
        )
    },
    execute = {
        val args = it.jsonObject
        val text = args["text"]?.jsonPrimitive?.contentOrNull ?: error("text is required")
        val restore = args["restore"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false

        ImeController.rememberOriginalIme(context)
        ImeController.switchToAiKeyboard(context).getOrElse { error(it.message ?: "切换输入法失败") }
        ImeController.sendText(context, text)
        if (restore) {
            ImeController.restoreOriginalIme(context).getOrElse { error(it.message ?: "恢复输入法失败") }
        }

        val payload = buildJsonObject {
            put("success", true)
            put("typed", text.length)
            put("current_ime", ImeController.currentImeInfo(context))
            put("original_ime", ImeController.storedOriginalIme(context))
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

/**
 * 恢复输入法工具: 把系统输入法切回"原输入法"(使用 AI 键盘前记录的那个)。
 * 作为兜底, 当用户需要切回正常键盘时使用。
 * 可通过 `ime` 参数指定目标输入法(包名/名称/组件 id), 否则用已记录的原输入法。
 */
internal fun buildRestoreImeTool(context: Context): Tool = Tool(
    name = "restore_ime",
    description = """
        把系统输入法切回使用 AI 键盘之前记录的原输入法。
        作为兜底, 当用户需要切回正常键盘时使用。
        可选参数 `ime`: 指定要切换到的目标输入法, 可用包名(如 com.tencent.wetype)、
        显示名称(如 微信输入法)或完整组件 id; 不传则用已记录的原输入法。
        需要 Shizuku(shell/root) 权限。
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("ime", buildJsonObject {
                    put("type", "string")
                    put("description", "可选。要切换到的目标输入法: 包名、显示名称或完整组件 id。不传则用已记录的原输入法。")
                })
            },
            required = emptyList()
        )
    },
    execute = {
        val ime = it.jsonObject["ime"]?.jsonPrimitive?.contentOrNull
        ImeController.restoreOriginalIme(context, ime).getOrElse { error(it.message ?: "恢复输入法失败") }
        val payload = buildJsonObject {
            put("success", true)
            put("current_ime", ImeController.currentImeInfo(context))
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
