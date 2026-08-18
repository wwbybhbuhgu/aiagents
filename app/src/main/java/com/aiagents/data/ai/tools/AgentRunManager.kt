package com.aiagents.data.ai.tools

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class AgentRunStatus { RUNNING, COMPLETED, FAILED, CANCELLED }

/** 一次后台委派 Agent 运行的记录 */
data class AgentRun(
    val id: String,
    val parentConversationId: String,
    val prompt: String,
    val status: AgentRunStatus,
    val result: String? = null,
    val error: String? = null,
    val createdAt: Long,
    val finishedAt: Long? = null,
    /** 是否为周期性监控运行(每隔 [intervalMillis] 重复执行一次, 每次心跳写回父对话) */
    val recurring: Boolean = false,
    val intervalMillis: Long? = null,
    /** 周期性运行已执行的周期数(一次性运行固定为 1) */
    val cycleCount: Int = 1,
)

/**
 * 后台委派 Agent 运行管理器。
 *
 * 主 Agent 通过 `agent` 工具发起子任务后立即返回（不阻塞主生成链）；
 * 子任务在 [scope]（app 级作用域）后台执行，完成后通过 [onFinished]/[onFailed]
 * 回调把结果/日志写回父对话 —— 与宿主 Agent 的后台 agent 工具行为一致。
 */
class AgentRunManager(
    private val scope: CoroutineScope,
) {
    private val runs = ConcurrentHashMap<String, AgentRun>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val counter = AtomicLong(0)

    /**
     * 启动一个后台 Agent 运行，立即返回 [AgentRun]（状态 RUNNING）。
     *
     * @param runBlock 子任务执行体（suspend），返回最终结果文本
     * @param onFinished 子任务成功完成回调（suspend，可写回父对话）
     * @param onFailed 子任务失败/取消回调（suspend）
     */
    fun launch(
        parentConversationId: String,
        prompt: String,
        runBlock: suspend (AgentRun) -> String,
        onFinished: suspend (AgentRun) -> Unit = {},
        onFailed: suspend (AgentRun, Throwable) -> Unit = { _, _ -> },
    ): AgentRun {
        val id = "agent-${System.currentTimeMillis()}-${counter.incrementAndGet()}"
        val run = AgentRun(
            id = id,
            parentConversationId = parentConversationId,
            prompt = prompt,
            status = AgentRunStatus.RUNNING,
            createdAt = System.currentTimeMillis(),
        )
        runs[id] = run
        val job = scope.launch {
            try {
                val result = runBlock(run)
                val finished = run.copy(
                    status = AgentRunStatus.COMPLETED,
                    result = result,
                    finishedAt = System.currentTimeMillis(),
                )
                runs[id] = finished
                onFinished(finished)
            } catch (e: CancellationException) {
                val cancelled = run.copy(
                    status = AgentRunStatus.CANCELLED,
                    error = "cancelled",
                    finishedAt = System.currentTimeMillis(),
                )
                runs[id] = cancelled
                onFailed(cancelled, e)
                throw e
            } catch (e: Throwable) {
                val failed = run.copy(
                    status = AgentRunStatus.FAILED,
                    error = e.message ?: e.javaClass.simpleName,
                    finishedAt = System.currentTimeMillis(),
                )
                runs[id] = failed
                onFailed(failed, e)
            }
        }
        jobs[id] = job
        return run
    }

    /**
     * 启动一个周期性后台 Agent 运行（心跳/定时监控）。
     *
     * 每隔 [intervalMillis] 执行一次 [runBlock]，每次成功执行后调用 [onCycle]
     * 写回父对话；循环持续到被 [cancel] 或作用域取消。运行记录会随周期推进
     * 更新 [AgentRun.cycleCount]。
     */
    fun launchRecurring(
        parentConversationId: String,
        prompt: String,
        intervalMillis: Long,
        runBlock: suspend (AgentRun) -> String,
        onCycle: suspend (AgentRun) -> Unit = {},
        onFailed: suspend (AgentRun, Throwable) -> Unit = { _, _ -> },
    ): AgentRun {
        val id = "agent-${System.currentTimeMillis()}-${counter.incrementAndGet()}"
        var run = AgentRun(
            id = id,
            parentConversationId = parentConversationId,
            prompt = prompt,
            status = AgentRunStatus.RUNNING,
            createdAt = System.currentTimeMillis(),
            recurring = true,
            intervalMillis = intervalMillis,
            cycleCount = 0,
        )
        runs[id] = run
        val job = scope.launch {
            while (currentCoroutineContext().isActive) {
                val current = run
                try {
                    val result = runBlock(current)
                    run = current.copy(
                        status = AgentRunStatus.RUNNING,
                        result = result,
                        cycleCount = current.cycleCount + 1,
                    )
                    runs[id] = run
                    onCycle(run)
                    delay(intervalMillis)
                } catch (e: CancellationException) {
                    val cancelled = run.copy(
                        status = AgentRunStatus.CANCELLED,
                        error = "cancelled",
                        finishedAt = System.currentTimeMillis(),
                    )
                    runs[id] = cancelled
                    onFailed(cancelled, e)
                    throw e
                } catch (e: Throwable) {
                    val failed = run.copy(
                        status = AgentRunStatus.RUNNING,
                        error = e.message ?: e.javaClass.simpleName,
                        cycleCount = current.cycleCount + 1,
                    )
                    runs[id] = failed
                    onCycle(failed)
                    // 周期性侦测: 单次失败不终止监视, 记录后继续下一周期
                    delay(intervalMillis)
                }
            }
        }
        jobs[id] = job
        return run
    }

    fun cancel(id: String): Boolean {
        val job = jobs[id] ?: return false
        job.cancel()
        return true
    }

    fun get(id: String): AgentRun? = runs[id]

    fun list(parentConversationId: String): List<AgentRun> =
        runs.values
            .filter { it.parentConversationId == parentConversationId }
            .sortedByDescending { it.createdAt }

    /** 清理该父对话的所有运行记录（对话删除时调用） */
    fun cleanup(parentConversationId: String) {
        runs.entries.removeAll { it.value.parentConversationId == parentConversationId }
        jobs.entries.removeAll { entry ->
            runs[entry.key] == null && entry.value.isActive
        }
    }
}
