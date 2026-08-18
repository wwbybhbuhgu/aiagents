package com.aiagents.data.ai.tools

import java.util.Calendar

/**
 * 极简 cron 解析器：支持 5 段表达式（分 时 日 月 周）与 every N m/h/d 间隔语法。
 * - 字段支持星号、数字、步长（星号加斜杠加 N）、a-b 区间、逗号列表（如 0,30）
 * - 周字段 0-6（0=周日），月 1-12
 */
object CronParser {

    /** 计算 [schedule] 在 [from] 之后的下一次触发时间（毫秒） */
    fun parseNext(schedule: String, from: Long): Long {
        val s = schedule.trim().lowercase()
        if (s.startsWith("every")) {
            val parts = s.split(Regex("\\s+"))
            val n = parts.getOrNull(1)?.toLongOrNull() ?: 1
            val unit = parts.getOrNull(2) ?: "m"
            val intervalMs = when {
                unit.startsWith("h") -> n * 3_600_000L
                unit.startsWith("d") -> n * 86_400_000L
                else -> n * 60_000L
            }
            return from + intervalMs
        }

        val fields = s.split(Regex("\\s+"))
        require(fields.size == 5) { "无效的调度表达式: $schedule（支持 cron 5 段或 every N m/h/d）" }
        val minutes = parseField(fields[0], 0, 59)
        val hours = parseField(fields[1], 0, 23)
        val doms = parseField(fields[2], 1, 31)
        val months = parseField(fields[3], 1, 12)
        val dows = parseField(fields[4], 0, 6)

        val cal = Calendar.getInstance().apply {
            timeInMillis = from
            add(Calendar.MINUTE, 1)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // 最多向后找 3 年
        repeat(3 * 366 * 24 * 60) {
            val month = cal.get(Calendar.MONTH) + 1
            val dom = cal.get(Calendar.DAY_OF_MONTH)
            val dow = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Calendar.SUNDAY=1 → 0
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            val minute = cal.get(Calendar.MINUTE)
            if (month in months && dom in doms && dow in dows && hour in hours && minute in minutes) {
                return cal.timeInMillis
            }
            cal.add(Calendar.MINUTE, 1)
        }
        throw IllegalArgumentException("无法计算下一次触发时间: $schedule")
    }

    private fun parseField(field: String, min: Int, max: Int): Set<Int> {
        if (field == "*") return (min..max).toSet()
        val result = mutableSetOf<Int>()
        field.split(",").forEach { part ->
            if (part.startsWith("*/")) {
                val step = part.removePrefix("*/").toIntOrNull() ?: return@forEach
                var v = min
                while (v <= max) {
                    result += v
                    v += step
                }
            } else if (part.contains('-')) {
                val (a, b) = part.split('-').map { it.toIntOrNull() ?: return@forEach }
                for (v in a..b) result += v
            } else {
                part.toIntOrNull()?.let { result += it }
            }
        }
        require(result.isNotEmpty()) { "无效的 cron 字段: $field" }
        return result
    }
}
