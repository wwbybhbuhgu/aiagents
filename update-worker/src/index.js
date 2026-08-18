/**
 * AI Agents 自动更新服务端 (Cloudflare Workers)
 *
 * 自定义域名: https://wiueiwi.wujunbo.top
 *
 * 提供以下能力:
 * 1. `/` 官网首页 (HTML): 项目介绍 + 下载按钮 + GitHub 地址
 * 2. `/update` 更新接口: 代理 GitHub Releases 最新版, 返回应用 UpdateChecker 期望的 JSON
 * 3. `/docs` 文档页: 渲染仓库内 docs/AI_AGENTS_USER_GUIDE.md
 * 4. `/health` 健康检查
 *
 * 更新接口返回格式:
 *   { "version": "2.4.5", "publishedAt": "<ISO8601>", "changelog": "markdown",
 *     "downloads": [ { "name": "xxx.apk", "url": "<apk 下载地址>", "size": "12345" } ] }
 */

const REPO = "wwbybhbuhgu/aiagents";
const GITHUB_TOKEN = "";
const UPSTREAM_MIRROR_BASE = "";
const ARM64_APK_PREFIX = "app-arm64-v8a";

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    // 健康检查
    if (url.pathname === "/health") {
      return new Response("ok", { status: 200 });
    }

    // 赞助列表 (本项目无赞助, 返回空)
    if (url.pathname === "/sponsors") {
      return new Response("[]", {
        status: 200,
        headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "public, max-age=600" },
      });
    }

    // 仅允许 GET/HEAD
    if (request.method !== "GET" && request.method !== "HEAD") {
      return new Response("method not allowed", { status: 405 });
    }

    // 官网首页
    if (url.pathname === "/" || url.pathname === "/index.html") {
      return new Response(landingPage(env), {
        status: 200,
        headers: { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "public, max-age=300" },
      });
    }

    // 文档页
    if (url.pathname === "/docs" || url.pathname.startsWith("/docs/")) {
      return serveDocs(request);
    }

    // 更新接口
    if (url.pathname === "/update") {
      return serveUpdate(request, env, ctx);
    }

    // 其余路径: 重定向到官网
    return Response.redirect("https://" + url.host + "/", 302);
  },
};

/* ------------------------- 更新接口 ------------------------- */

async function serveUpdate(request, env, ctx) {
  const repo = env.REPO || REPO;
  const token = env.GITHUB_TOKEN || GITHUB_TOKEN;
  const mirrorBase = env.UPSTREAM_MIRROR_BASE || UPSTREAM_MIRROR_BASE;

  try {
    const cacheKey = new Request("https://api.github.com/repos/" + repo + "/releases/latest", request);
    const cache = caches.default;
    let response = await cache.match(cacheKey);
    if (!response) {
      const headers = {
        "Accept": "application/vnd.github+json",
        "User-Agent": "aiagents-update-worker",
      };
      if (token) headers["Authorization"] = "Bearer " + token;
      const ghResponse = await fetch(
        "https://api.github.com/repos/" + repo + "/releases/latest",
        { headers },
      );
      if (!ghResponse.ok) {
        return new Response(
          JSON.stringify({ error: "github release fetch failed: " + ghResponse.status }),
          { status: 502, headers: { "Content-Type": "application/json" } },
        );
      }
      response = ghResponse;
      if (response.ok) {
        ctx.waitUntil(cache.put(cacheKey, response.clone()));
      }
    }

    const release = await response.json();
    const version = (release.tag_name || "").replace(/^v/, "");
    const publishedAt = release.published_at;
    const changelog = release.body || "";

    let apkAsset = release.assets.find(
      (a) => a.name && a.name.startsWith(ARM64_APK_PREFIX) && a.name.endsWith(".apk"),
    );
    if (!apkAsset) {
      apkAsset = release.assets.find((a) => a.name && a.name.endsWith(".apk"));
    }
    if (!apkAsset) {
      return new Response(
        JSON.stringify({ error: "no apk asset in release" }),
        { status: 502, headers: { "Content-Type": "application/json" } },
      );
    }

    let apkUrl = apkAsset.browser_download_url;
    if (mirrorBase) {
      const filename = apkAsset.name;
      apkUrl = mirrorBase.endsWith("/") ? mirrorBase + filename : mirrorBase + "/" + filename;
    }

    const info = {
      version: version,
      publishedAt: publishedAt,
      changelog: changelog,
      downloads: [
        {
          name: apkAsset.name,
          url: apkUrl,
          size: String(apkAsset.size),
        },
      ],
    };

    return new Response(JSON.stringify(info), {
      status: 200,
      headers: {
        "Content-Type": "application/json; charset=utf-8",
        "Cache-Control": "public, max-age=300",
      },
    });
  } catch (e) {
    return new Response(
      JSON.stringify({ error: "internal error: " + String((e && e.message) || e) }),
      { status: 500, headers: { "Content-Type": "application/json" } },
    );
  }
}

/* ------------------------- 官网首页 ------------------------- */

function landingPage(env) {
  const repo = env.REPO || REPO;
  const githubUrl = "https://github.com/" + repo;
  const releaseUrl = githubUrl + "/releases/latest";
  const host = "wiueiwi.wujunbo.top";
  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>AI Agents - 开源安卓 AI 助手</title>
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body {
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "PingFang SC", "Microsoft YaHei", sans-serif;
    background: linear-gradient(135deg, #0f172a 0%, #1e293b 50%, #0f172a 100%);
    color: #e2e8f0;
    min-height: 100vh;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    padding: 24px;
    text-align: center;
  }
  h1 { font-size: 32px; font-weight: 700; margin-bottom: 8px; }
  .subtitle { font-size: 16px; color: #94a3b8; margin-bottom: 32px; max-width: 560px; line-height: 1.6; }
  .buttons { display: flex; gap: 16px; flex-wrap: wrap; justify-content: center; }
  .btn {
    display: inline-block;
    padding: 14px 28px;
    border-radius: 12px;
    font-size: 16px;
    font-weight: 600;
    text-decoration: none;
    transition: transform 0.15s ease, box-shadow 0.15s ease;
  }
  .btn:hover { transform: translateY(-2px); box-shadow: 0 8px 24px rgba(99, 102, 241, 0.35); }
  .btn-primary { background: #6366f1; color: #fff; }
  .btn-secondary { background: rgba(148, 163, 184, 0.15); color: #e2e8f0; border: 1px solid rgba(148, 163, 184, 0.3); }
  .features {
    display: flex; flex-wrap: wrap; gap: 12px; justify-content: center;
    margin-top: 40px; max-width: 640px;
  }
  .feature {
    background: rgba(148, 163, 184, 0.08);
    border: 1px solid rgba(148, 163, 184, 0.15);
    padding: 8px 14px; border-radius: 999px; font-size: 13px; color: #cbd5e1;
  }
  .footer { margin-top: 48px; font-size: 13px; color: #64748b; }
  .footer a { color: #818cf8; text-decoration: none; }
</style>
</head>
<body>
  <h1>AI Agents</h1>
  <p class="subtitle">原生 Android 大模型聊天客户端，支持多供应商切换、工作区智能体、多模态输入、Web 多端访问。</p>
  <div class="buttons">
    <a class="btn btn-primary" href="${releaseUrl}">下载 APK</a>
    <a class="btn btn-secondary" href="${githubUrl}">GitHub 仓库</a>
    <a class="btn btn-secondary" href="/docs">文档</a>
  </div>
  <div class="features">
    <span class="feature">Material You 设计</span>
    <span class="feature">proot 工作区</span>
    <span class="feature">多供应商支持</span>
    <span class="feature">多模态输入</span>
    <span class="feature">MCP 支持</span>
    <span class="feature">Markdown 渲染</span>
    <span class="feature">消息分支</span>
    <span class="feature">搜索能力</span>
    <span class="feature">记忆功能</span>
    <span class="feature">AI 翻译</span>
  </div>
  <p class="footer">
    Fork of <a href="https://github.com/rikkahub/rikkahub">rikkahub/rikkahub</a> ·
    Licensed under <a href="${githubUrl}/blob/master/LICENSE">AGPL-3.0</a>
  </p>
</body>
</html>`;
}

/* ------------------------- 文档页 ------------------------- */

async function serveDocs(request) {
  const url = new URL(request.url);
  // /docs/zh 或 /docs/zh-cn → 中文, 其余默认英文
  const isZh = /\/docs\/(zh|zh-cn|zh-CN|zh_CN)/.test(url.pathname);

  const cache = caches.default;
  const cacheKey = new Request(
    "https://raw.githubusercontent.com/" + REPO + "/master/docs/AI_AGENTS_USER_GUIDE.md",
    request,
  );
  let response = await cache.match(cacheKey);
  if (!response) {
    response = await fetch(
      "https://raw.githubusercontent.com/" + REPO + "/master/docs/AI_AGENTS_USER_GUIDE.md",
      { headers: { "User-Agent": "aiagents-docs-worker" } },
    );
    if (response.ok) {
      // 缓存 1 小时
      const cached = response.clone();
      requestWaitUntil(cache, cacheKey, cached);
    }
  }
  if (!response.ok) {
    return new Response("docs not found", { status: 502 });
  }
  const markdown = await response.text();

  const html = renderMarkdown(markdown, isZh);
  return new Response(html, {
    status: 200,
    headers: { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "public, max-age=3600" },
  });
}

// 简易 waitUntil 包装 (在无 ctx 上下文时立即缓存)
function requestWaitUntil(cache, key, response) {
  cache.put(key, response);
}

/* 极简 Markdown → HTML (标题/段落/列表/代码块/表格/链接/加粗) */
function renderMarkdown(md, isZh) {
  const title = isZh ? "AI Agents 用户指南" : "AI Agents User Guide";
  const escapeHtml = (s) =>
    s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  const inline = (s) =>
    escapeHtml(s)
      .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
      .replace(/\*([^*]+)\*/g, "<em>$1</em>")
      .replace(/`([^`]+)`/g, "<code>$1</code>")
      .replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2">$1</a>');

  const lines = md.split(/\r?\n/);
  let html = "";
  let inCode = false;
  let inTable = false;
  let tableRows = [];
  let listType = null;

  const closeTable = () => {
    if (tableRows.length) {
      html += "<table><thead><tr>" + tableRows[0].split("|").filter((c) => c.trim()).map((c) => "<th>" + inline(c.trim()) + "</th>").join("") + "</tr></thead><tbody>";
      for (let i = 2; i < tableRows.length; i++) {
        const cells = tableRows[i].split("|").filter((c) => c.trim());
        html += "<tr>" + cells.map((c) => "<td>" + inline(c.trim()) + "</td>").join("") + "</tr>";
      }
      html += "</tbody></table>";
    }
    tableRows = [];
    inTable = false;
  };

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];

    if (line.startsWith("```")) {
      if (inCode) { html += "</code></pre>"; inCode = false; }
      else { html += "<pre><code>"; inCode = true; }
      continue;
    }
    if (inCode) { html += escapeHtml(line) + "\n"; continue; }

    const trimmed = line.trim();
    if (trimmed.startsWith("|")) {
      if (!inTable) { inTable = true; tableRows = []; }
      tableRows.push(trimmed);
      continue;
    } else if (inTable) {
      closeTable();
    }

    if (trimmed.startsWith("### ")) { html += "<h3>" + inline(trimmed.slice(4)) + "</h3>"; continue; }
    if (trimmed.startsWith("## ")) { html += "<h2>" + inline(trimmed.slice(3)) + "</h2>"; continue; }
    if (trimmed.startsWith("# ")) { html += "<h1>" + inline(trimmed.slice(2)) + "</h1>"; continue; }
    if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
      if (listType !== "ul") { if (listType) html += "</ul>"; html += "<ul>"; listType = "ul"; }
      html += "<li>" + inline(trimmed.slice(2)) + "</li>";
      continue;
    }
    if (/^\d+\.\s/.test(trimmed)) {
      if (listType !== "ol") { if (listType) html += "</ul>"; html += "<ol>"; listType = "ol"; }
      html += "<li>" + inline(trimmed.replace(/^\d+\.\s/, "")) + "</li>";
      continue;
    }
    if (listType) { html += "</" + listType + ">"; listType = null; }

    if (!trimmed) { html += "<p></p>"; continue; }
    if (/^>\s/.test(trimmed)) {
      html += "<blockquote>" + inline(trimmed.replace(/^>\s?/, "")) + "</blockquote>";
      continue;
    }
    if (/^---+\s*$/.test(trimmed)) { html += "<hr>"; continue; }
    html += "<p>" + inline(trimmed) + "</p>";
  }
  if (inCode) html += "</code></pre>";
  if (inTable) closeTable();
  if (listType) html += "</" + listType + ">";

  return `<!DOCTYPE html>
<html lang="${isZh ? "zh-CN" : "en"}">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>${title}</title>
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body {
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "PingFang SC", "Microsoft YaHei", sans-serif;
    background: #0f172a; color: #e2e8f0; line-height: 1.7; padding: 24px;
  }
  .container { max-width: 820px; margin: 0 auto; }
  h1 { font-size: 26px; margin: 20px 0 12px; border-bottom: 1px solid #334155; padding-bottom: 8px; }
  h2 { font-size: 22px; margin: 24px 0 10px; }
  h3 { font-size: 18px; margin: 18px 0 8px; }
  p { margin: 10px 0; }
  a { color: #818cf8; }
  code { background: #1e293b; padding: 2px 6px; border-radius: 4px; font-size: 14px; }
  pre { background: #1e293b; padding: 16px; border-radius: 8px; overflow-x: auto; margin: 12px 0; }
  pre code { padding: 0; background: none; }
  table { border-collapse: collapse; width: 100%; margin: 12px 0; }
  th, td { border: 1px solid #334155; padding: 8px 12px; text-align: left; font-size: 14px; }
  th { background: #1e293b; }
  ul, ol { margin: 10px 0 10px 24px; }
  blockquote { border-left: 3px solid #6366f1; padding-left: 12px; color: #94a3b8; margin: 10px 0; }
  hr { border: none; border-top: 1px solid #334155; margin: 16px 0; }
</style>
</head>
<body>
<div class="container">${html}</div>
</body>
</html>`;
}