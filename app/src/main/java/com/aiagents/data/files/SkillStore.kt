package com.aiagents.data.files

import kotlinx.serialization.Serializable

/**
 * 技能商店内置精选列表。
 *
 * 每个条目指向一个 GitHub 仓库内的 Skill 目录（整目录下载，见 [SkillDownloader]）。
 * 数据源为内置硬编码，后续可扩展为远程清单。
 */
@Serializable
data class StoreSkill(
    val owner: String,
    val repo: String,
    val branch: String,
    val skillPath: String,
    val name: String,
    val description: String,
)

/** 商店技能详情页数据：元数据 + 正文（README.md 或 SKILL.md 正文）+ 安装状态。 */
data class StoreSkillDetail(
    val name: String,
    val description: String,
    val license: String? = null,
    val compatibility: String? = null,
    val body: String? = null,
    val isInstalled: Boolean = false,
)

object SkillStore {
    val skills: List<StoreSkill> = listOf(
        StoreSkill(
            owner = "vercel-labs",
            repo = "agent-skills",
            branch = "main",
            skillPath = "skills/web-design-guidelines",
            name = "web-design-guidelines",
            description = "Review UI code for Web Interface Guidelines compliance. Use when asked to \"review my UI\", \"check accessibility\", \"audit design\", \"review UX\", or \"check my site against best practices\".",
        ),
        StoreSkill(
            owner = "vercel-labs",
            repo = "agent-skills",
            branch = "main",
            skillPath = "skills/react-best-practices",
            name = "vercel-react-best-practices",
            description = "React and Next.js performance optimization guidelines from Vercel Engineering. Use when writing, reviewing, or refactoring React/Next.js code for optimal performance.",
        ),
        StoreSkill(
            owner = "vercel-labs",
            repo = "agent-skills",
            branch = "main",
            skillPath = "skills/writing-guidelines",
            name = "writing-guidelines",
            description = "Review docs/prose for Writing Guidelines compliance. Use when asked to \"review my docs\", \"check writing style\", \"audit prose\", or \"review docs voice and tone\".",
        ),
        StoreSkill(
            owner = "vercel-labs",
            repo = "agent-skills",
            branch = "main",
            skillPath = "skills/deploy-to-vercel",
            name = "deploy-to-vercel",
            description = "Deploy applications and websites to Vercel. Use when the user requests deployment actions like \"deploy my app\", \"deploy and give me the link\", or \"create a preview deployment\".",
        ),
        StoreSkill(
            owner = "vercel-labs",
            repo = "agent-skills",
            branch = "main",
            skillPath = "skills/composition-patterns",
            name = "vercel-composition-patterns",
            description = "React composition patterns that scale. Use when refactoring components with boolean prop proliferation, building flexible component libraries, or designing reusable APIs.",
        ),
    )
}
