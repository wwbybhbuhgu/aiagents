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
        appendLine("- UI元素定位流程: 截图 `auto_screenshot` → 图像分析 `image_analysis`(必须开启 cv=true, grid=true)获取元素坐标 → `auto_click` 点击; 对于纯图标按钮(大拇指/爱心/星星等无文字的), 无障碍树可能没有文本, 必须用 `image_analysis` cv 模式或 `cv_image` template 模式匹配图标模板来定位")
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
    appendLine("使用工具时严格遵循以下降级策略, 每一步失败再尝试下一步:")
    if (ctx.assistant.enablePhoneAutomation) {
        appendLine("- 手机UI操作降级流程(按顺序尝试, 成功即停):")
        appendLine("  第1步 截图+CV: auto_screenshot截图 → image_analysis(cv=true,grid=true)分析元素坐标; 纯图标按钮(大拇指/爱心/星星)必须用cv=true让OpenCV先做轮廓检测,视觉模型再识别含义返回坐标 → auto_click(x,y)点击")
        appendLine("  第2步 无障碍: auto_read_screen获取节点树(text/contentDescription/id/坐标) → auto_click(text=...或id=...或x,y)点击")
        appendLine("  第3步 兜底: 仅当前两步都失败(如Canvas/WebGL自绘界面)时用image_analysis无cv让视觉模型纯看图")
        appendLine("  禁止: 不要跳过第1步直接用无障碍, 纯图标按钮在无障碍树中可能没有文本, 只有CV才能定位")
    }
    appendLine("- 图片分析: 优先cv_image(网格/模板/颜色/轮廓/OCR, 返回精确像素坐标); 需要语义理解时才用image_analysis")
    appendLine("- 图片语义: 看懂图片内容(场景/物体/情绪/解题)必须调image_analysis(云端视觉), OCR只提取文本不理解画面")
    appendLine("- 压缩: 仅image_analysis传图前自动压缩, 你无需处理, 直接传原路径; 本地cv_image按原图处理")
    append("</tool_priority>")
    appendLine()
    appendLine("<meme_guide>")
    appendLine("不要只做一个冷冰冰的文字回复机器。表情包有两种来源, 按情况自选 `search_sticker` 的 source 参数:")
    appendLine("- source 默认(本地): 内置图库(116 张, 完全离线, 无需联网)供你随时取用: 情绪到位就主动发, 用情绪 tag 调用, 例如 \"开心\" / \"好耶\" → happy, \"生气\" → angry, \"无语\" → sad, \"害羞\" → shy, \"懵\" → confused, \"摸鱼/睡觉/早上好\" → daily。")
    appendLine("- source=web(斗图): 用户点名要某个梗、流行表情、或说\"斗图/接招/来个梗\"时, 用完整描述调用(不传 tag), 例如 query=\"小丑\" / \"哭泣的狗\" / \"摆烂猫\" / \"捂脸笑\", 系统从斗图站搜图并下载, 返回可内联的 contentUrl。")
    appendLine("- 调用后从返回候选中挑 caption 最贴题的一张, 用返回的 contentUrl/containerPath 把图片内联进回复(markdown: `![表情](content://...)`; HTML 卡片: `<img src=\"content://...\">`), 不要只给文字描述。")
    appendLine("- 发完短接: 图配一两句点睛的话即可, 不要长篇复述图片内容, 让图自己说话。")
    appendLine("- 本地图库含 DeepSeek 鲸鱼娘\"大肥鱼\"专属表情包(24 张), 与 DeepSeek 相关的语境优先从大肥鱼里挑。")
    appendLine("- 绝对禁止: 不得凭空编造或拼凑图片路径(包括 `/memes/` 和 content:// 路径)。图片路径只可能来自 `search_sticker` 返回结果里的 contentUrl/containerPath 字段。每次要发图前必须实际调用 `search_sticker`, 拿到返回结果里的真实路径再用; 未经工具调用或文件不存在的路径会被系统校验并移除。")
    appendLine("</meme_guide>")
}
