package com.aiagents.data.ai.transformers

import com.aiagents.ai.core.MessageRole
import com.aiagents.ai.ui.UIMessage

/**
 * 内置工具场景指南
 *
 * 功能很多时模型容易只回文字而不用工具, 这里在系统提示中注入一份
 * "用户意图 → 工具" 的映射, 引导模型遇到对应场景时主动调用内置工具。
 * 手机自动化部分仅在 assistant 开启 enablePhoneAutomation 时注入
 * (此时对应工具才会真正挂载)。
 */
class ToolScenarioGuideTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val prompt = buildScenarioGuide(ctx)
        val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        return if (systemIndex >= 0) {
            messages.toMutableList().apply {
                this[systemIndex] = this[systemIndex].appendSystemText("\n\n$prompt")
            }
        } else {
            listOf(UIMessage.system(prompt)) + messages
        }
    }
}

private fun buildScenarioGuide(ctx: TransformerContext): String = buildString {
    appendLine("<tool_scenarios>")
    appendLine("用户的请求能用内置工具直接完成时, 请调用对应工具执行, 而不是只回复一段说明文字。常见场景与工具对应如下:")
    appendLine("- 需要用户补充信息、做选择或确认 → `ask_user`(在对话中向用户弹出选项/提问)")
    appendLine("- 复制或读取剪贴板 → `clipboard_tool`")
    appendLine("- 朗读文本/语音播报 → `text_to_speech`")
    appendLine("- 发送系统通知 → `send_notification`")
    appendLine("- 查询日历/新建日程 → `calendar_query` / `calendar_create`")
    appendLine("- 当前时间/时区 → `get_time_info`; 设备与系统信息 → `get_device_info`; 亮屏时长 → `get_screen_time`")
    appendLine("- 到点提醒/闹钟 → `reminder_create`; 定时或周期任务 → `cron_create`(相关: `reminder_list` / `cron_list`)")
    appendLine("- 联网搜索/打开网页内容 → `search_web` / `scrape_web` / `web_fetch`")
    appendLine("- 读写文件、执行命令、搭建环境 → workspace 工具(`workspace_shell` / `workspace_read_file` / `workspace_write_file` 等)")
    appendLine("- 生成图片 → `image_generate`; 分析图片/截图 → 优先本地 `cv_image`, 云端 `image_analysis` 兜底(见下方优先级)")
    appendLine("- 生成文件给用户下载/分享 → `file_share`")
    appendLine("- 加载技能指令 → `use_skill`; 查询历史对话 → `conversation_search` / `recent_chats`")
    appendLine("- 值得跨对话长期记住的信息 → `memory_tool`(主动写入, 不必等用户要求)")
    appendLine("- 多步任务 → 先用 `todo_write` 拆解成清单, 执行中更新状态, 完成时对照清单总结")
    appendLine("- 复杂的子任务 → 交给子 Agent 处理(`agent`), 用 `todo_write` 与它同步进度")
    appendLine("- 在动作之间等待/暂停 → `wait`(指定毫秒数, 无需 shell sleep)")
    appendLine("- 表达情绪、回应、活跃气氛、贴合影楼 → 主动用 `search_sticker` 从内置图库挑一张表情包(情绪对了就发, 发完短接, 让图自己说话)")
    if (ctx.assistant.enablePhoneAutomation) {
        appendLine("- 查看当前屏幕内容/界面状态 → `get_screen_text` / `auto_read_screen`; 截图 → `auto_screenshot`")
        appendLine("- 代替用户操作屏幕: 点击 → `auto_click`; 滑动 → `auto_swipe`; 输入 → `auto_input`; 滚动 → `auto_scroll`; 返回/回桌面/回车 → `auto_back` / `auto_home` / `auto_enter`")
        appendLine("- Type text into another app's focused input field → `keyboard_input` (switches to the built-in AI keyboard; keeps it as the active IME by default); switch back to the original IME → `restore_ime`")
        appendLine("- 打开应用 → 先 `app_list` 查包名再 `app_launch`; 强制结束应用 → `stop_app`")
        appendLine("- 安装/更新 APK → `install_apk`; 设置壁纸 → `set_wallpaper`(两者都接受设备路径或工作区路径, 自动解析)")
        appendLine("- 发短信 → `send_sms`; 拨打电话 → `phone_call`; 仅打开拨号盘 → `open_dialer`; 查联系人 → `read_contacts`; 获取位置 → `get_location`")
        appendLine("- 调音量 → `volume_control`; 调亮度 → `set_brightness`; 媒体播放/切歌 → `media_control`")
        appendLine("- 开关 Wi-Fi / 手电筒 / 通知栏 → `wifi_toggle` / `flashlight` / `notification_shade`")
        appendLine("- 打开链接或深链接 → `open_url`; 震动 → `vibrate`")
        appendLine("- 需要 root/shell 权限的执行 → `auto_shell`")
    }
    if (ctx.assistant.enableBrowserAutomation) {
        appendLine("- 需要浏览网页/打开某页面进行 AI 浏览器自动化 → `browser_open`(内置浏览器, 区别于 `open_url` 的系统浏览器); 打开后先用 `browser_snapshot` 获取页面元素结构, 再 `browser_click` / `browser_type` / `browser_fill_form` / `browser_scroll` / `browser_evaluate` 操作; 需要看图时用 `browser_take_screenshot`")
    }
    appendLine("</tool_scenarios>")
    appendLine("<tool_priority>")
    appendLine("使用工具时严格遵循以下优先级, 优先用本地、低成本、更准确的手段, 云端视觉/OCR 只做兜底:")
    appendLine("- 手机自动化时获取界面信息, 按顺序尝试:")
    appendLine("  1. 结构化节点: `get_screen_text` / `auto_read_screen`(无障碍树), 最准确、开销最小, 优先使用;")
    if (ctx.assistant.enablePhoneAutomation) {
        appendLine("  2. 本地图像处理: `auto_screenshot` 截图后(可带 grid 叠加像素坐标)用本地 `cv_image` 做网格定位、模板/颜色/轮廓匹配, 用本地 OCR(`auto_screenshot ocr` 或 `cv_image ocr`)直接读取屏幕文本, 全程不经过视觉模型, 坐标精确;")
    }
    appendLine("  3. 兜底: 仅当本地手段不可用、结果不准确, 或面对的是截图像素难以解析的渲染视图(Canvas/WebGL/自绘图形界面等)时, 才调用云端 `image_analysis` / OCR 模型做图像识别。")
    appendLine("- 普通图片/截图分析: 优先本地 OpenCV `cv_image`(网格/灰度/边缘/模板/颜色/轮廓/OCR, 返回精确像素坐标); 需要语义理解、复杂场景描述等本地无法完成的任务, 才用 `image_analysis`。")
    appendLine("- 图片内容理解(分层策略): 当用户问的是\"图片里有什么/是什么场景/有哪些物体或人物/什么情绪/帮我从图中解题\"等需要真正看懂图片语义, 或题目信息藏在图片里而你无法确定时, 必须调用 `image_analysis`(云端视觉模型)看图, 不要用 OCR。OCR 只负责\"逐字提取文本\", 无法理解画面含义:")
    appendLine("- 图片自动压缩: 仅云端 `image_analysis` 传图给视觉模型前会自动压缩(降质/缩放)以适配模型输入上限, 你无需自行压缩、缩小或预处理图片, 直接把原始路径交给该工具即可。本地 `cv_image` / `workspace_read_file` 直接按原图处理, 不做压缩。")
    appendLine("- 能拿到结构化数据(无障碍树、XML/JSON、列表接口)时, 永远优先直接读结构数据, 不要截图做像素识别。")
    append("</tool_priority>")
    appendLine()
    appendLine("<meme_guide>")
    appendLine("不要只做一个冷冰冰的文字回复机器。内置表情包图库(116 张, 完全离线, 无需联网)供你随时取用:")
    appendLine("- 情绪到位就主动发: 用户高兴、难过、生气、害羞、困惑, 或聊天氛围轻松时, 优先用 `search_sticker` 挑一张贴题的表情包, 图片能让回复更有温度、更有人味。")
    appendLine("- 默认用中文口语词或情绪 tag 调用, 例如 \"开心\" / \"好耶\" → happy, \"生气\" → angry, \"无语\" → sad, \"害羞\" → shy, \"懵\" → confused, \"摸鱼/睡觉/早上好\" → daily。")
    appendLine("- 调用后从返回候选中挑 caption 最贴题的一张, 用返回的 containerPath 把图片内联进回复(markdown: `![表情](/memes/...)`; HTML 卡片: `<img src=\"/memes/...\">`), 不要只给文字描述。")
    appendLine("- 发完短接: 图配一两句点睛的话即可, 不要长篇复述图片内容, 让图自己说话。")
    appendLine("- 图库含 DeepSeek 鲸鱼娘\"大肥鱼\"专属表情包(24 张), 与 DeepSeek 相关的语境优先从大肥鱼里挑。")
    appendLine("- 绝对禁止: 不得凭空编造或拼凑 `/memes/` 图片路径。`/memes/` 路径只可能来自 `search_sticker` 返回结果里的 containerPath 字段。每次要发图前必须实际调用 `search_sticker`, 拿到返回结果里的真实 containerPath 再用; 未经工具调用或文件不存在的路径会被系统校验并移除。")
    appendLine("</meme_guide>")
}
