package com.aiagents.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 通知监听服务: 读取其他应用的系统通知(需用户在系统设置中开启"通知使用权")。
 * 通知内容存入内存仓库, 供 get_device_info 工具读取, 回答"最新通知"类问题。
 */
class AppNotificationListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationRepository.connected = true
        runCatching {
            activeNotifications.forEach { NotificationRepository.upsert(it) }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        scope.launch { NotificationRepository.upsert(sbn) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        scope.launch { NotificationRepository.remove(sbn.key) }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        NotificationRepository.connected = false
    }
}

/** 通知内容仓库: 监听服务写入, 工具读取 */
object NotificationRepository {
    @Volatile
    var connected: Boolean = false

    private data class Entry(
        val key: String,
        val packageName: String,
        val title: String,
        val text: String,
        val timestamp: Long,
        /** 单调递增序号, 用于心跳等增量消费(检测"新到达的通知") */
        val seq: Long,
    )

    private val entries = ArrayDeque<Entry>()

    private val seqCounter = java.util.concurrent.atomic.AtomicLong(0)

    /** 最近一条通知的序号; 无通知时为 0 */
    @Volatile
    var latestSeq: Long = 0
        private set

    @Synchronized
    fun upsert(sbn: StatusBarNotification) {
        val n = sbn.notification
        val title = n.extras?.getString(Notification.EXTRA_TITLE).orEmpty()
        val text = n.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val seq = seqCounter.incrementAndGet()
        latestSeq = seq
        entries.removeAll { it.key == sbn.key }
        entries.addFirst(
            Entry(
                key = sbn.key,
                packageName = sbn.packageName,
                title = title,
                text = text,
                timestamp = System.currentTimeMillis(),
                seq = seq,
            )
        )
        if (entries.size > 100) entries.removeLast()
    }

    @Synchronized
    fun remove(key: String) {
        entries.removeAll { it.key == key }
    }

    /** 最近 [limit] 条通知(排除本应用自身) */
    @Synchronized
    fun latest(limit: Int = 10, excludePackage: String? = null): List<NotificationItem> {
        return entries
            .filter { excludePackage == null || it.packageName != excludePackage }
            .take(limit)
            .map { NotificationItem(it.packageName, it.title, it.text, it.timestamp) }
    }

    /** 序号 > [afterSeq] 的通知(增量), 供心跳捕获"新到达的通知"; 可排除本应用自身 */
    @Synchronized
    fun since(afterSeq: Long, excludePackage: String? = null): List<NotificationItem> {
        return entries
            .asSequence()
            .filter { it.seq > afterSeq }
            .filter { excludePackage == null || it.packageName != excludePackage }
            .map { NotificationItem(it.packageName, it.title, it.text, it.timestamp) }
            .toList()
    }
}

data class NotificationItem(
    val packageName: String,
    val title: String,
    val text: String,
    val timestamp: Long,
)
