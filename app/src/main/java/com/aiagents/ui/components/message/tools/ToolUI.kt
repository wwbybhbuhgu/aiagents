package com.aiagents.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import com.aiagents.ai.ui.UIMessagePart
import com.aiagents.common.http.jsonObjectOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tools
import me.rerere.hugeicons.stroke.AlarmClock
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Browser
import me.rerere.hugeicons.stroke.Brush
import me.rerere.hugeicons.stroke.Camera01
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Cursor01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.Eye
import me.rerere.hugeicons.stroke.Share01
import me.rerere.hugeicons.stroke.FileView
import me.rerere.hugeicons.stroke.Flashlight
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.Grid02
import me.rerere.hugeicons.stroke.Home01
import me.rerere.hugeicons.stroke.Id
import me.rerere.hugeicons.stroke.Image01
import me.rerere.hugeicons.stroke.Keyboard
import me.rerere.hugeicons.stroke.Link01
import me.rerere.hugeicons.stroke.Location01
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.hugeicons.stroke.Message01
import me.rerere.hugeicons.stroke.Message02
import me.rerere.hugeicons.stroke.Move
import me.rerere.hugeicons.stroke.MusicNote01
import me.rerere.hugeicons.stroke.Notification01
import me.rerere.hugeicons.stroke.PackageAdd01
import me.rerere.hugeicons.stroke.Reminder
import me.rerere.hugeicons.stroke.Rocket
import me.rerere.hugeicons.stroke.Scroll
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.hugeicons.stroke.Stop
import me.rerere.hugeicons.stroke.Sun01
import me.rerere.hugeicons.stroke.Text
import me.rerere.hugeicons.stroke.Timer01
import me.rerere.hugeicons.stroke.Upload01
import me.rerere.hugeicons.stroke.UserEdit01
import me.rerere.hugeicons.stroke.UserAdd01
import me.rerere.hugeicons.stroke.UserMultiple02
import me.rerere.hugeicons.stroke.Voice
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.hugeicons.stroke.Wifi01
import com.aiagents.R
import com.aiagents.ui.components.richtext.HighlightCodeBlock
import com.aiagents.ui.components.richtext.ZoomableAsyncImage
import com.aiagents.ui.components.ui.FormItem
import com.aiagents.utils.JsonInstant
import com.aiagents.utils.JsonInstantPretty
import com.aiagents.utils.jsonPrimitiveOrNull

/**
 * 工具调用的渲染上下文, 预解析好工具入参与输出, 避免各渲染器重复解析
 */
data class ToolUIContext(
    val tool: UIMessagePart.Tool,
    /** 工具入参 ([UIMessagePart.Tool.input] 的 JSON 解析结果) */
    val arguments: JsonElement,
    /** 输出文本部件解析出的 JSON, 工具未执行时为 null */
    val content: JsonElement?,
    /** 该工具调用是否在生成中 */
    val loading: Boolean,
)

/**
 * 工具名的本地化显示: 未单独注册 UI 渲染器的工具, 在卡片标题/详情中
 * 显示本地化名称而非原始英文 toolName。返回 null 表示无对应翻译, 回退原文。
 */
@Composable
internal fun toolDisplayName(toolName: String): String? = when (toolName) {
    "workspace_read_file" -> stringResource(R.string.tool_name_workspace_read_file)
    "workspace_write_file" -> stringResource(R.string.tool_name_workspace_write_file)
    "workspace_edit_file" -> stringResource(R.string.tool_name_workspace_edit_file)
    "workspace_glob" -> stringResource(R.string.tool_name_workspace_glob)
    "workspace_grep" -> stringResource(R.string.tool_name_workspace_grep)
    "import_to_workspace" -> stringResource(R.string.tool_name_import_to_workspace)
    "workspace_shell" -> stringResource(R.string.tool_name_workspace_shell)
    "image_generate" -> stringResource(R.string.tool_name_image_generate)
    "image_analysis" -> stringResource(R.string.tool_name_image_analysis)
    "cv_image" -> stringResource(R.string.tool_name_cv_image)
    "edit_image" -> stringResource(R.string.tool_name_edit_image)
    "media_tool" -> stringResource(R.string.tool_name_media_tool)
    "web_fetch" -> stringResource(R.string.tool_name_web_fetch)
    "search_web" -> stringResource(R.string.tool_name_search_web)
    "scrape_web" -> stringResource(R.string.tool_name_scrape_web)
    "memory_tool" -> stringResource(R.string.tool_name_memory_tool)
    "create_assistant" -> stringResource(R.string.tool_name_create_assistant)
    "edit_assistant" -> stringResource(R.string.tool_name_edit_assistant)
    "todo_write" -> stringResource(R.string.tool_name_todo_write)
    "file_share" -> stringResource(R.string.tool_name_file_share)
    "show_file" -> stringResource(R.string.tool_name_show_file)
    "render_html_card" -> stringResource(R.string.tool_name_render_html_card)
    "activity" -> stringResource(R.string.tool_name_activity)
    "use_skill" -> stringResource(R.string.tool_name_use_skill)
    "recent_chats" -> stringResource(R.string.tool_name_recent_chats)
    "conversation_search" -> stringResource(R.string.tool_name_conversation_search)
    "get_time_info" -> stringResource(R.string.tool_name_get_time_info)
    "get_screen_time" -> stringResource(R.string.tool_name_get_screen_time)
    "clipboard_tool" -> stringResource(R.string.tool_name_clipboard_tool)
    "text_to_speech" -> stringResource(R.string.tool_name_text_to_speech)
    "calendar_query" -> stringResource(R.string.tool_name_calendar_query)
    "calendar_create" -> stringResource(R.string.tool_name_calendar_create)
    "cron_create" -> stringResource(R.string.tool_name_cron_create)
    "cron_list" -> stringResource(R.string.tool_name_cron_list)
    "cron_delete" -> stringResource(R.string.tool_name_cron_delete)
    "agent" -> stringResource(R.string.tool_name_agent)
    "ask_user" -> stringResource(R.string.tool_name_ask_user)
    "eval_javascript" -> stringResource(R.string.tool_name_eval_javascript)
    "send_notification" -> stringResource(R.string.tool_name_send_notification)
    "local_speech_recognition" -> stringResource(R.string.tool_name_local_speech_recognition)
    "auto_read_screen" -> stringResource(R.string.tool_name_auto_read_screen)
    "auto_click" -> stringResource(R.string.tool_name_auto_click)
    "auto_input" -> stringResource(R.string.tool_name_auto_input)
    "auto_swipe" -> stringResource(R.string.tool_name_auto_swipe)
    "auto_scroll" -> stringResource(R.string.tool_name_auto_scroll)
    "auto_back" -> stringResource(R.string.tool_name_auto_back)
    "auto_home" -> stringResource(R.string.tool_name_auto_home)
    "auto_enter" -> stringResource(R.string.tool_name_auto_enter)
    "auto_screenshot" -> stringResource(R.string.tool_name_auto_screenshot)
    "auto_shell" -> stringResource(R.string.tool_name_auto_shell)
    "get_screen_text" -> stringResource(R.string.tool_name_get_screen_text)
    "get_device_info" -> stringResource(R.string.tool_name_get_device_info)
    "cron_get" -> stringResource(R.string.tool_name_cron_get)
    "cron_update" -> stringResource(R.string.tool_name_cron_update)
    "reminder_create" -> stringResource(R.string.tool_name_reminder_create)
    "reminder_list" -> stringResource(R.string.tool_name_reminder_list)
    "reminder_update" -> stringResource(R.string.tool_name_reminder_update)
    "reminder_delete" -> stringResource(R.string.tool_name_reminder_delete)
    "app_list" -> stringResource(R.string.tool_name_app_list)
    "app_launch" -> stringResource(R.string.tool_name_app_launch)
    "stop_app" -> stringResource(R.string.tool_name_stop_app)
    "open_url" -> stringResource(R.string.tool_name_open_url)
    "floating_window" -> stringResource(R.string.tool_name_floating_window)
    "browser_open" -> stringResource(R.string.tool_name_open_browser)
    "browser_set_user_agent" -> stringResource(R.string.tool_name_browser)
    "browser_info" -> stringResource(R.string.tool_name_browser)
    "browser_go_back" -> stringResource(R.string.tool_name_browser)
    "browser_go_forward" -> stringResource(R.string.tool_name_browser)
    "browser_reload" -> stringResource(R.string.tool_name_browser)
    "browser_close" -> stringResource(R.string.tool_name_browser)
    "browser_snapshot" -> stringResource(R.string.tool_name_browser)
    "browser_take_screenshot" -> stringResource(R.string.tool_name_browser)
    "browser_set_viewport" -> stringResource(R.string.tool_name_browser)
    "browser_click" -> stringResource(R.string.tool_name_browser)
    "browser_hover" -> stringResource(R.string.tool_name_browser)
    "browser_drag" -> stringResource(R.string.tool_name_browser)
    "browser_scroll" -> stringResource(R.string.tool_name_browser)
    "browser_scroll_to" -> stringResource(R.string.tool_name_browser)
    "browser_type" -> stringResource(R.string.tool_name_browser)
    "browser_press_key" -> stringResource(R.string.tool_name_browser)
    "browser_fill_form" -> stringResource(R.string.tool_name_browser)
    "browser_select_option" -> stringResource(R.string.tool_name_browser)
    "browser_evaluate" -> stringResource(R.string.tool_name_browser)
    "browser_wait_for" -> stringResource(R.string.tool_name_browser)
    "browser_console_messages" -> stringResource(R.string.tool_name_browser)
    "browser_network_requests" -> stringResource(R.string.tool_name_browser)
    "browser_handle_dialog" -> stringResource(R.string.tool_name_browser)
    "open_dialer" -> stringResource(R.string.tool_name_open_dialer)
    "volume_control" -> stringResource(R.string.tool_name_volume_control)
    "media_control" -> stringResource(R.string.tool_name_media_control)
    "set_brightness" -> stringResource(R.string.tool_name_set_brightness)
    "vibrate" -> stringResource(R.string.tool_name_vibrate)
    "flashlight" -> stringResource(R.string.tool_name_flashlight)
    "wifi_toggle" -> stringResource(R.string.tool_name_wifi_toggle)
    "notification_shade" -> stringResource(R.string.tool_name_notification_shade)
    "install_apk" -> stringResource(R.string.tool_name_install_apk)
    "set_wallpaper" -> stringResource(R.string.tool_name_set_wallpaper)
    "send_sms" -> stringResource(R.string.tool_name_send_sms)
    "read_contacts" -> stringResource(R.string.tool_name_read_contacts)
    "get_location" -> stringResource(R.string.tool_name_get_location)
    "phone_call" -> stringResource(R.string.tool_name_phone_call)
    "get_foreground_app" -> stringResource(R.string.tool_name_get_foreground_app)
    "agent_state_get" -> stringResource(R.string.tool_name_agent_state_get)
    "agent_state_set" -> stringResource(R.string.tool_name_agent_state_set)
    "search_sticker" -> stringResource(R.string.tool_name_search_sticker)
    else -> null
}

/**
 * 未注册专用渲染器的工具使用此映射选择图标。
 * 已注册专用渲染器的工具会自行覆盖 [ToolUIRenderer.icon]。
 */
internal fun toolIcon(toolName: String): ImageVector = when (toolName) {
    "file_share" -> HugeIcons.Share01
    "show_file" -> HugeIcons.Eye
    "render_html_card" -> HugeIcons.Browser
    "activity" -> HugeIcons.Code
    "create_assistant" -> HugeIcons.UserAdd01
    "edit_assistant" -> HugeIcons.UserEdit01
    "import_to_workspace" -> HugeIcons.Upload01
    "workspace_glob" -> HugeIcons.Search01
    "workspace_grep" -> HugeIcons.Search01
    "image_generate" -> HugeIcons.Image01
    "cv_image" -> HugeIcons.Camera01
    "edit_image" -> HugeIcons.Brush
    "media_tool" -> HugeIcons.SmartPhone01
    "web_fetch" -> HugeIcons.GlobalSearch
    "cron_create" -> HugeIcons.Timer01
    "cron_list" -> HugeIcons.Clock01
    "cron_get" -> HugeIcons.Clock01
    "cron_update" -> HugeIcons.Edit01
    "cron_delete" -> HugeIcons.Delete01
    "agent" -> HugeIcons.UserMultiple02
    "ask_user" -> HugeIcons.Message01
    "eval_javascript" -> HugeIcons.Code
    "send_notification" -> HugeIcons.Notification01
    "reminder_create" -> HugeIcons.Reminder
    "reminder_list" -> HugeIcons.Clock01
    "reminder_update" -> HugeIcons.Edit01
    "reminder_delete" -> HugeIcons.Delete01
    "app_list" -> HugeIcons.Grid02
    "app_launch" -> HugeIcons.Rocket
    "stop_app" -> HugeIcons.Stop
    "open_url" -> HugeIcons.Link01
    "floating_window" -> HugeIcons.Id
    "open_dialer" -> HugeIcons.SmartPhone01
    "volume_control" -> HugeIcons.VolumeHigh
    "media_control" -> HugeIcons.MusicNote01
    "set_brightness" -> HugeIcons.Sun01
    "vibrate" -> HugeIcons.SmartPhone01
    "flashlight" -> HugeIcons.Flashlight
    "wifi_toggle" -> HugeIcons.Wifi01
    "notification_shade" -> HugeIcons.Notification01
    "install_apk" -> HugeIcons.PackageAdd01
    "set_wallpaper" -> HugeIcons.Image01
    "send_sms" -> HugeIcons.Message01
    "read_contacts" -> HugeIcons.Id
    "get_location" -> HugeIcons.Location01
    "phone_call" -> HugeIcons.SmartPhone01
    "get_foreground_app" -> HugeIcons.SmartPhone01
    "get_screen_text" -> HugeIcons.Text
    "get_device_info" -> HugeIcons.SmartPhone01
    "agent_state_get" -> HugeIcons.Id
    "agent_state_set" -> HugeIcons.UserEdit01
    "search_sticker" -> HugeIcons.Image01
    "browser_open" -> HugeIcons.Earth
    "browser_set_user_agent" -> HugeIcons.Browser
    "browser_info" -> HugeIcons.Browser
    "browser_go_back" -> HugeIcons.ArrowLeft01
    "browser_go_forward" -> HugeIcons.ArrowLeft01
    "browser_reload" -> HugeIcons.Browser
    "browser_close" -> HugeIcons.Browser
    "browser_snapshot" -> HugeIcons.Camera01
    "browser_take_screenshot" -> HugeIcons.Camera01
    "browser_set_viewport" -> HugeIcons.Browser
    "browser_click" -> HugeIcons.Cursor01
    "browser_hover" -> HugeIcons.Cursor01
    "browser_drag" -> HugeIcons.Move
    "browser_scroll" -> HugeIcons.Scroll
    "browser_scroll_to" -> HugeIcons.Scroll
    "browser_type" -> HugeIcons.Edit01
    "browser_press_key" -> HugeIcons.Keyboard
    "browser_fill_form" -> HugeIcons.Edit01
    "browser_select_option" -> HugeIcons.Edit01
    "browser_evaluate" -> HugeIcons.Code
    "browser_wait_for" -> HugeIcons.Clock01
    "browser_console_messages" -> HugeIcons.Code
    "browser_network_requests" -> HugeIcons.GlobalSearch
    "browser_handle_dialog" -> HugeIcons.Message01
    "auto_read_screen" -> HugeIcons.Eye
    "auto_click" -> HugeIcons.Cursor01
    "auto_input" -> HugeIcons.Keyboard
    "auto_swipe" -> HugeIcons.Move
    "auto_scroll" -> HugeIcons.Scroll
    "auto_back" -> HugeIcons.ArrowLeft01
    "auto_home" -> HugeIcons.Home01
    "auto_enter" -> HugeIcons.ArrowDown01
    "auto_screenshot" -> HugeIcons.Camera01
    "auto_shell" -> HugeIcons.ComputerTerminal01
    "wait" -> HugeIcons.Clock01
    "restore_ime" -> HugeIcons.Keyboard
    "get_screen_time" -> HugeIcons.SmartPhone01
    "text_to_speech" -> HugeIcons.Voice
    else -> HugeIcons.Tools
}

/**
 * 单个工具的 UI 渲染器
 *
 * 在 [ToolUIRegistry] 注册后, 聊天消息中对应的工具调用将使用该渲染器展示;
 * 未注册的工具 fallback 到接口的默认实现 (通用标题/图标 + JSON 详情)
 */
interface ToolUIRenderer {
    /** 渲染器对应的工具名 */
    val toolName: String

    /** 折叠步骤的图标 */
    fun icon(context: ToolUIContext): ImageVector = toolIcon(context.tool.toolName)

    /** 折叠步骤的标题 */
    @Composable
    fun title(context: ToolUIContext): String =
        toolDisplayName(context.tool.toolName) ?: stringResource(R.string.chat_message_tool_call_generic, context.tool.toolName)

    /** 步骤展开时是否显示内联摘要 */
    fun hasSummary(context: ToolUIContext): Boolean = false

    /** 步骤展开时的内联摘要 */
    @Composable
    fun Summary(context: ToolUIContext) {
    }

    /** 点击步骤后的详情, 渲染在 BottomSheet 内 */
    @Composable
    fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        DefaultToolPreview(context = context)
    }
}

/** 未注册工具使用的默认渲染器, 全部行为来自 [ToolUIRenderer] 的默认实现 */
private object DefaultToolUIRenderer : ToolUIRenderer {
    override val toolName: String get() = ""
}

/**
 * 工具 UI 渲染器注册表, 为新工具定制渲染时在 [renderers] 中注册即可
 */
object ToolUIRegistry {
    private val renderers: Map<String, ToolUIRenderer> = listOf(
        MemoryToolUI,
        SearchWebToolUI,
        ScrapeWebToolUI,
        OpenBrowserToolUI,
        GetTimeInfoToolUI,
        ImageAnalysisToolUI,
        ClipboardToolUI,
        TextToSpeechToolUI,
        KeyboardInputToolUI,
        GetScreenTimeToolUI,
        CalendarQueryToolUI,
        CalendarCreateToolUI,
        UseSkillToolUI,
        RecentChatsToolUI,
        ConversationSearchToolUI,
        EditFileToolUI,
        ReadFileToolUI,
        WriteFileToolUI,
        ShellToolUI,
        FileShareToolUI,
        TodoToolUI,
        LocalSpeechRecognitionToolUI,
        // 后台进程工具
        ShellBgToolUI,
        ShellOutputToolUI,
        ShellKillToolUI,
        ShellListToolUI,
        NodeBgToolUI,
        NodeOutputToolUI,
        NodeKillToolUI,
        NodeListToolUI,
        EvalJavascriptToolUI,
    ).associateBy { it.toolName }

    /** 查找工具对应的渲染器, 未注册时返回默认渲染器 */
    fun resolve(toolName: String): ToolUIRenderer =
        renderers[toolName] ?: assistantToolRenderers[toolName] ?: DefaultToolUIRenderer
}

/** create_assistant / edit_assistant 共用渲染器 (AssistantToolUI.toolName 只能表示一个, 这里显式注册两个名字) */
private val assistantToolRenderers: Map<String, ToolUIRenderer> = mapOf(
    "create_assistant" to AssistantToolUI,
    "edit_assistant" to AssistantToolUI,
)

internal fun JsonElement?.getStringContent(key: String): String? =
    this?.jsonObjectOrNull?.get(key)?.jsonPrimitiveOrNull?.contentOrNull

/**
 * 默认工具详情: 入参与输出的 JSON 高亮展示
 *
 * @param headerActions 标题栏右侧的附加操作区
 */
@Composable
fun DefaultToolPreview(
    context: ToolUIContext,
    headerActions: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxHeight(0.8f)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.chat_message_tool_call_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            headerActions?.invoke()
        }
        FormItem(
            label = {
                Text(
                    stringResource(
                        R.string.chat_message_tool_call_label,
                        toolDisplayName(context.tool.toolName) ?: context.tool.toolName
                    )
                )
            }
        ) {
            HighlightCodeBlock(
                code = JsonInstantPretty.encodeToString(context.arguments),
                language = "json",
                style = TextStyle(fontSize = 10.sp, lineHeight = 12.sp)
            )
        }
        if (context.tool.output.isNotEmpty()) {
            FormItem(
                label = {
                    Text(stringResource(R.string.chat_message_tool_call_result))
                }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    context.tool.output.fastForEach { part ->
                        when (part) {
                            is UIMessagePart.Text -> HighlightCodeBlock(
                                code = runCatching {
                                    JsonInstantPretty.encodeToString(
                                        JsonInstant.parseToJsonElement(part.text)
                                    )
                                }.getOrElse { part.text },
                                language = "json",
                                style = TextStyle(fontSize = 10.sp, lineHeight = 12.sp)
                            )

                            is UIMessagePart.Image -> ZoomableAsyncImage(
                                model = part.url,
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth(),
                            )

                            else -> {}
                        }
                    }
                }
            }
        }
    }
}
