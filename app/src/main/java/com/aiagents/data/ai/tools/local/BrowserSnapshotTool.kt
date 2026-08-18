package com.aiagents.data.ai.tools.local

import com.aiagents.ai.core.Tool
import com.aiagents.ai.ui.UIMessagePart
import com.aiagents.data.automation.BrowserSession
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

private val SNAPSHOT_SCRIPT = """
    (function() {
      var vw = window.innerWidth, vh = window.innerHeight;
      var sel = 'a, button, input, select, textarea, [role], [onclick], [tabindex], img, [aria-label], label, h1, h2, h3, p, li, summary, details';
      var out = [];
      var maxNodes = 400;
      var els = document.querySelectorAll(sel);
      for (var i = 0; i < els.length && out.length < maxNodes; i++) {
        var el = els[i];
        var r = el.getBoundingClientRect();
        if (r.width < 2 && r.height < 2) continue;
        var st = window.getComputedStyle(el);
        if (st.visibility === 'hidden' || st.display === 'none') continue;
        if (r.bottom < 0 || r.right < 0 || r.top > vh || r.left > vw) continue;
        var text = (el.innerText || el.value || el.getAttribute('aria-label') || el.getAttribute('placeholder') || el.getAttribute('alt') || '').trim().replace(/\s+/g, ' ').slice(0, 160);
        var role = el.getAttribute('role') || el.tagName.toLowerCase();
        out.push({
          role: role,
          tag: el.tagName.toLowerCase(),
          text: text,
          name: el.getAttribute('name') || '',
          id: el.id || '',
          x: Math.round(r.left),
          y: Math.round(r.top),
          width: Math.round(r.width),
          height: Math.round(r.height),
          center: { x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2) }
        });
      }
      return {
        url: location.href,
        title: document.title,
        viewport: { width: vw, height: vh },
        page: { width: document.documentElement.scrollWidth, height: document.documentElement.scrollHeight },
        scroll: { x: window.scrollX, y: window.scrollY },
        elements: out
      };
    })()
""".trimIndent()

/** 页面元素快照: 返回当前可视区域内可交互元素的结构化列表(角色/文本/坐标)。 */
internal fun buildBrowserSnapshotTool(): Tool = Tool(
    name = "browser_snapshot",
    description = """
        Get a structured snapshot of the built-in browser page: current URL, title, viewport/page size,
        scroll position, and a list of visible interactive elements (links, buttons, inputs, selects, headings, etc.)
        with their role, text, and bounding-box coordinates (center is where to click).
        Use this to understand the page layout and locate elements before clicking or typing.
    """.trimIndent().replace("\n", " "),
    parameters = { null },
    needsApproval = { false },
    execute = {
        if (!browserReady()) {
            browserUnavailable()
        } else {
            val result = BrowserSession.evaluateJs(SNAPSHOT_SCRIPT).getOrElse { "" }
            val parsed = runCatching { com.aiagents.utils.JsonInstant.parseToJsonElement(result).jsonObject }.getOrNull()
            if (parsed != null) {
                listOf(UIMessagePart.Text(parsed.toString()))
            } else {
                listOf(UIMessagePart.Text(buildJsonObject { put("ok", false); put("error", "snapshot failed") }.toString()))
            }
        }
    },
)
