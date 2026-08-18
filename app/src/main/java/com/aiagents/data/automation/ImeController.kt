package com.aiagents.data.automation

import android.content.Context
import android.content.Intent
import android.util.Base64
import com.aiagents.ime.AdbIME
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 输入法控制器: 通过 Shizuku(shell/root) 切换系统输入法到内置 AI 键盘,
 * 向 AdbIME 发送文本, 并维护/恢复"原输入法"。
 */
object ImeController {
    private const val PREFS = "ime_controller"
    private const val KEY_ORIGINAL_IME = "original_ime"

    private data class ImeInfo(val id: String, val name: String, val packageName: String)

    /** 本应用内置 AdbIME 输入法的组件 id(用完整类名, 避免短类名相对 applicationId 解析错误) */
    fun aiKeyboardId(context: Context): String =
        "${context.packageName}/${AdbIME::class.java.name}"

    /** 读取当前默认输入法 id */
    suspend fun getCurrentIme(context: Context): String? =
        ShizukuController.exec("settings get secure default_input_method")
            .stdout.trim().ifBlank { null }

    /** 当前输入法的显示信息(名称 + id), 供提示词/结果回显 */
    suspend fun currentImeInfo(context: Context): String? {
        val current = getCurrentIme(context) ?: return null
        val info = listImeInfo(context).firstOrNull { it.id == current }
        return info?.let { "${it.name} (${it.id})" } ?: current
    }

    /** 记录当前输入法为"原输入法"(仅当尚未记录时) */
    suspend fun rememberOriginalIme(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_ORIGINAL_IME, null).isNullOrBlank()) {
            getCurrentIme(context)?.let {
                prefs.edit().putString(KEY_ORIGINAL_IME, it).apply()
            }
        }
    }

    /** 已记录的原输入法 id */
    fun storedOriginalIme(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ORIGINAL_IME, null)

    /** 切换到本应用的 AdbIME 输入法(先启用再切换) */
    suspend fun switchToAiKeyboard(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val id = aiKeyboardId(context)
            val enable = ShizukuController.exec("ime enable $id")
            if (enable.exitCode != 0) error("ime enable 失败: ${enable.stderr.ifBlank { enable.stdout }}")
            val set = ShizukuController.exec("ime set $id")
            if (set.exitCode != 0) error("ime set 失败: ${set.stderr.ifBlank { set.stdout }}")
        }
    }

    /**
     * 恢复输入法:
     * - [target] 非空时按包名/名称/组件 id 解析并切换(找不到则报错);
     * - 否则用已记录的原输入法, 再退化为当前输入法。
     * 避免切回 AI 键盘自身。
     */
    suspend fun restoreOriginalIme(context: Context, target: String? = null): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val original = if (!target.isNullOrBlank()) {
                    resolveImeId(context, target) ?: error("找不到输入法: $target")
                } else {
                    storedOriginalIme(context) ?: getCurrentIme(context) ?: error("未找到原输入法")
                }
                if (original == aiKeyboardId(context)) error("目标输入法就是 AI 键盘, 无需切换")
                val set = ShizukuController.exec("ime set $original")
                if (set.exitCode != 0) error("ime set 失败: ${set.stderr.ifBlank { set.stdout }}")
            }
        }

    /** 按组件 id / 包名 / 显示名(子串)解析已启用输入法 id; 匹配不到返回 null */
    suspend fun resolveImeId(context: Context, query: String): String? {
        val q = query.trim()
        if (q.isBlank()) return null
        val infos = listImeInfo(context)
        infos.firstOrNull { it.id.equals(q, ignoreCase = true) }?.let { return it.id }
        infos.firstOrNull { it.packageName.equals(q, ignoreCase = true) }?.let { return it.id }
        infos.firstOrNull { it.name.contains(q, ignoreCase = true) }?.let { return it.id }
        infos.firstOrNull { it.packageName.contains(q, ignoreCase = true) }?.let { return it.id }
        return null
    }

    /** 解析 `ime list -v -s` 输出为 (id, name, packageName) 列表 */
    private suspend fun listImeInfo(context: Context): List<ImeInfo> {
        val out = ShizukuController.exec("ime list -v -s").stdout
        val result = mutableListOf<ImeInfo>()
        var currentId: String? = null
        var name = ""
        var pkg = ""
        for (line in out.lines()) {
            val trimmed = line.trim()
            if (trimmed.endsWith(":") && trimmed.contains("/")) {
                if (currentId != null) result.add(ImeInfo(currentId, name, pkg))
                currentId = trimmed.removeSuffix(":")
                name = ""
                pkg = ""
            } else if (trimmed.startsWith("name=")) {
                name = trimmed.removePrefix("name=")
            } else if (trimmed.startsWith("packageName=")) {
                pkg = trimmed.removePrefix("packageName=")
            }
        }
        if (currentId != null) result.add(ImeInfo(currentId, name, pkg))
        return result
    }

    /** 向 AdbIME 发送文本(需已切换到本应用输入法并聚焦输入框) */
    suspend fun sendText(context: Context, text: String) {
        // 等待输入法就绪并绑定到输入框
        delay(800)
        val intent = Intent("ADB_INPUT_B64").apply {
            setPackage(context.packageName)
            putExtra("msg", Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.DEFAULT))
        }
        context.sendBroadcast(intent)
    }
}
