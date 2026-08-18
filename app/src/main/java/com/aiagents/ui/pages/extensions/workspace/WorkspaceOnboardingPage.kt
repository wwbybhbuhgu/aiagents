package com.aiagents.ui.pages.extensions.workspace

import android.app.AlarmManager
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AlertCircle
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.File02
import com.aiagents.R
import com.aiagents.data.automation.ShizukuController
import com.aiagents.data.ai.tools.FFMPEG_INSTALL_SCRIPT
import com.aiagents.data.db.entity.WorkspaceEntity
import com.aiagents.data.repository.WorkspaceRepository
import com.aiagents.service.AppNotificationListenerService
import com.aiagents.service.ScreenTextAccessibilityService
import com.aiagents.utils.NotificationUtil
import com.aiagents.utils.fileSizeToString
import com.aiagents.workspace.RootfsInstallProgress
import com.aiagents.workspace.RootfsInstallStage
import com.aiagents.workspace.WorkspaceShellStatus
import org.koin.compose.koinInject
import org.koin.androidx.compose.koinViewModel

private const val ONBOARDING_PREFS = "onboarding"
private const val KEY_ONBOARDING_COMPLETED = "completed"
private const val POLL_INTERVAL_MS = 1500L

/**
 * 初次启动向导：没有任何就绪工作区时全屏显示, 自动创建默认工作区并下载 rootfs。
 * 同时展示"环境与权限"检查清单(悬浮窗/通知/无障碍/精确闹钟/存储)。
 * 全部检查通过后"进入 AI Agents"按钮才亮起, 点击后记录完成状态并隐藏, 不影响下层聊天页。
 */
@Composable
fun WorkspaceOnboardingPage(
    vm: WorkspaceOnboardingVM = koinViewModel(),
) {
    val workspaces by vm.workspaces.collectAsStateWithLifecycle()
    val progress by vm.installProgress.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val prefs = remember {
        context.getSharedPreferences(ONBOARDING_PREFS, Context.MODE_PRIVATE)
    }
    var completed by rememberSaveable {
        mutableStateOf(prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false))
    }
    if (completed) return

    fun finish() {
        prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, true).apply()
        completed = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = HugeIcons.File02,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.onboarding_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 24.dp),
        )
        Text(
            text = stringResource(R.string.onboarding_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = stringResource(R.string.onboarding_check_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.onboarding_check_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(modifier = Modifier.height(12.dp))

        val allGranted = OnboardingChecksList(
            workspaces = workspaces,
            installProgress = progress,
            containerError = error,
            onRetryContainer = vm::retry,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { finish() },
            enabled = allGranted,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.onboarding_action_enter))
        }
        if (!allGranted) {
            Text(
                text = stringResource(R.string.onboarding_proceed_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = vm::dismissError,
            title = { Text(stringResource(R.string.workspace_detail_rootfs_install_failed)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = vm::retry) {
                    Text(stringResource(R.string.common_retry))
                }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissError) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
        )
    }
}

/**
 * 环境与权限检查清单(初始化向导与"环境与权限检查"页复用):
 * 工作区容器 / 媒体工具依赖(ffmpeg) / 系统权限清单 / Shizuku 一键授权。
 * 返回所有必选项是否就绪。
 */
@Composable
internal fun OnboardingChecksList(
    workspaces: List<WorkspaceEntity>,
    installProgress: RootfsInstallProgress?,
    containerError: String?,
    onRetryContainer: () -> Unit,
): Boolean {
    val context = LocalContext.current
    val containerReady = workspaces.any { it.shellStatus == WorkspaceShellStatus.READY.name }
    val containerInstalling = workspaces.any { it.shellStatus == WorkspaceShellStatus.INSTALLING.name }
    val containerBusy = (installProgress != null || containerInstalling) && !containerReady
    val containerFailed = containerError != null && !containerReady && !containerBusy

    // 通知权限: 第一次弹出系统授权框, 之后(含永久拒绝)引导到应用详情页
    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }
    var notificationRequested by rememberSaveable { mutableStateOf(false) }
    val requestNotification = {
        if (notificationRequested) {
            openAppDetails(context)
        } else {
            notificationRequested = true
            notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val checks = rememberOnboardingChecks(context, requestNotification)

    // 媒体工具依赖(ffmpeg): 按工作区逐个检查/安装, 支持多工作区
    val repository = koinInject<WorkspaceRepository>()
    val scope = rememberCoroutineScope()
    val readyWorkspaces = remember(workspaces) {
        workspaces.filter { it.shellStatus == WorkspaceShellStatus.READY.name }
    }
    var mediaInstalledIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var mediaInstalling by remember { mutableStateOf(false) }
    var mediaInstallFailed by remember { mutableStateOf(false) }

    suspend fun WorkspaceRepository.ffmpegInstalled(id: String): Boolean = runCatching {
        executeCommand(
            id = id,
            command = "command -v ffmpeg >/dev/null 2>&1 && command -v ffprobe >/dev/null 2>&1",
            timeoutMillis = 30_000L,
        ).exitCode == 0
    }.getOrDefault(false)

    LaunchedEffect(readyWorkspaces.map { it.id to it.shellStatus }) {
        val ready = readyWorkspaces
        if (ready.isEmpty()) {
            mediaInstalledIds = emptySet()
            return@LaunchedEffect
        }
        mediaInstalledIds = ready.filter { repository.ffmpegInstalled(it.id) }
            .map { it.id }.toSet()
        mediaInstallFailed = false
    }

    val installMediaDeps: () -> Unit = {
        val targets = readyWorkspaces.filter { it.id !in mediaInstalledIds }
        if (!mediaInstalling && targets.isNotEmpty()) {
            scope.launch {
                mediaInstalling = true
                mediaInstallFailed = false
                for (ws in targets) {
                    val ok = runCatching {
                        repository.executeCommand(
                            id = ws.id,
                            command = FFMPEG_INSTALL_SCRIPT,
                            timeoutMillis = 600_000L,
                        ).exitCode == 0
                    }.getOrDefault(false)
                    if (ok) mediaInstalledIds = mediaInstalledIds + ws.id
                }
                val installed = readyWorkspaces.filter { repository.ffmpegInstalled(it.id) }
                    .map { it.id }.toSet()
                mediaInstalledIds = installed
                mediaInstallFailed = installed.size < readyWorkspaces.size
                mediaInstalling = false
            }
        }
    }

    // 工作区容器
    OnboardingCheckRow(
        title = stringResource(R.string.onboarding_check_container),
        statusText = when {
            containerReady -> stringResource(R.string.onboarding_check_ready)
            containerBusy -> stringResource(R.string.onboarding_check_running)
            containerFailed -> stringResource(R.string.onboarding_check_failed)
            else -> stringResource(R.string.onboarding_check_pending)
        },
        granted = containerReady,
        busy = containerBusy,
        failed = containerFailed,
        onAction = if (!containerReady) onRetryContainer else null,
    )

    // 媒体工具依赖(ffmpeg): 按工作区逐个安装
    val mediaTargets = readyWorkspaces.filter { it.id !in mediaInstalledIds }
    OnboardingCheckRow(
        title = stringResource(R.string.onboarding_check_media_deps),
        statusText = when {
            mediaInstalling -> stringResource(R.string.onboarding_check_running)
            mediaInstallFailed -> stringResource(R.string.onboarding_check_failed)
            readyWorkspaces.isNotEmpty() && mediaTargets.isEmpty() ->
                stringResource(R.string.onboarding_check_ready)
            else -> stringResource(R.string.onboarding_check_pending)
        },
        granted = readyWorkspaces.isNotEmpty() && mediaTargets.isEmpty(),
        busy = mediaInstalling,
        failed = mediaInstallFailed,
        optional = true,
        onAction = if (mediaInstalling || readyWorkspaces.isEmpty()) null else installMediaDeps,
        actionText = stringResource(R.string.onboarding_media_install),
    )

    // 权限检查清单
    checks.forEach { check ->
        OnboardingCheckRow(
            title = check.title,
            statusText = if (check.granted) {
                stringResource(R.string.onboarding_check_ready)
            } else {
                stringResource(R.string.onboarding_check_pending)
            },
            granted = check.granted,
            optional = check.optional,
            onAction = if (!check.granted) check.onAction else null,
        )
    }

    // Shizuku 一键授权(执行前必须征得用户同意)
    var showShizukuGrantDialog by remember { mutableStateOf(false) }
    var shizukuGrantRunning by remember { mutableStateOf(false) }
    var shizukuGrantResult by remember { mutableStateOf<String?>(null) }

    val pkg = context.packageName
    val runtimeGrants = remember {
        buildList {
            add(R.string.onboarding_grant_camera to listOf(android.Manifest.permission.CAMERA))
            add(R.string.onboarding_grant_mic to listOf(android.Manifest.permission.RECORD_AUDIO))
            add(
                R.string.onboarding_grant_sms to listOf(
                    android.Manifest.permission.SEND_SMS,
                    android.Manifest.permission.READ_SMS,
                )
            )
            add(R.string.onboarding_grant_contacts to listOf(android.Manifest.permission.READ_CONTACTS))
            add(
                R.string.onboarding_grant_location to listOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
            add(R.string.onboarding_grant_call to listOf(android.Manifest.permission.CALL_PHONE))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(
                    R.string.onboarding_grant_media to listOf(
                        android.Manifest.permission.READ_MEDIA_IMAGES,
                        android.Manifest.permission.READ_MEDIA_VIDEO,
                        android.Manifest.permission.READ_MEDIA_AUDIO,
                    )
                )
            }
        }
    }
    // 一键执行全部 ADB 命令: 所有特殊权限的 shizukuCommand + 全部运行时权限 pm grant
    val grantableJobs = remember(checks, pkg) {
        buildList {
            for (check in checks) {
                val cmd = check.shizukuCommand?.replace("\$pkg", pkg) ?: continue
                add(
                    GrantJob(
                        title = check.title,
                        cmd = cmd,
                        verify = {
                            computeOnboardingChecks(context, requestNotification)
                                .any { it.title == check.title && it.granted }
                        },
                    )
                )
            }
            for ((res, perms) in runtimeGrants) {
                val title = context.getString(res)
                for (perm in perms) {
                    add(
                        GrantJob(
                            title = title,
                            cmd = "pm grant $pkg $perm",
                            verify = {
                                context.checkSelfPermission(perm) ==
                                    android.content.pm.PackageManager.PERMISSION_GRANTED
                            },
                        )
                    )
                }
            }
        }
    }

    val privilegeAvailable = ShizukuController.hasPrivilege()
    if (privilegeAvailable && grantableJobs.isNotEmpty()) {
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { showShizukuGrantDialog = true },
            enabled = !shizukuGrantRunning,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.onboarding_action_shizuku_grant_all, grantableJobs.size))
        }
        shizukuGrantResult?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }

    if (showShizukuGrantDialog) {
        AlertDialog(
            onDismissRequest = { showShizukuGrantDialog = false },
            title = { Text(stringResource(R.string.onboarding_grant_dialog_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.onboarding_grant_dialog_desc),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    grantableJobs.forEach { job ->
                        Text(
                            text = "• ${job.title}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showShizukuGrantDialog = false
                        scope.launch {
                            shizukuGrantRunning = true
                            shizukuGrantResult = null
                            // 未授权时先自动弹出 Shizuku 授权框并等待结果
                            if (!ShizukuController.ensurePermission(20_000)) {
                                shizukuGrantRunning = false
                                shizukuGrantResult = context.getString(
                                    R.string.onboarding_grant_shizuku_denied
                                )
                                return@launch
                            }
                            suspend fun runGrantJob(job: GrantJob): Boolean {
                                val r = ShizukuController.exec(job.cmd, timeoutMs = 20_000)
                                if (r.error != null) return false
                                delay(800)
                                return job.verify()
                            }
                            val failed = mutableListOf<String>()
                            for (job in grantableJobs) {
                                var ok = runGrantJob(job)
                                if (!ok) {
                                    delay(600)
                                    ok = runGrantJob(job)
                                }
                                if (!ok) failed.add(job.title)
                            }
                            val okCount = grantableJobs.size - failed.size
                            shizukuGrantRunning = false
                            shizukuGrantResult = if (failed.isEmpty()) {
                                context.getString(R.string.onboarding_grant_success, okCount)
                            } else {
                                context.getString(
                                    R.string.onboarding_grant_partial,
                                    okCount,
                                    failed.size,
                                    failed.joinToString(),
                                )
                            }
                        }
                    },
                ) { Text(stringResource(R.string.onboarding_grant_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showShizukuGrantDialog = false }) {
                    Text(stringResource(R.string.onboarding_grant_cancel))
                }
            },
        )
    }

    if (installProgress != null && !containerReady) {
        OnboardingProgress(progress = installProgress, modifier = Modifier.padding(top = 16.dp))
    }

    return containerReady && checks.all { it.granted || it.optional }
}

/**
 * 一条检查项: 左侧状态图标, 中间标题, 右侧状态文本或"去开启"按钮。
 */
@Composable
private fun OnboardingCheckRow(
    title: String,
    statusText: String,
    granted: Boolean,
    busy: Boolean = false,
    failed: Boolean = false,
    optional: Boolean = false,
    onAction: (() -> Unit)? = null,
    actionText: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when {
                busy -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                granted -> Icon(
                    imageVector = HugeIcons.CheckmarkCircle02,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                failed -> Icon(
                    imageVector = HugeIcons.AlertCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                else -> Box(
                    modifier = Modifier
                        .size(18.dp)
                        .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape),
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(12.dp))
        when {
            busy -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            granted -> Text(
                text = statusText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                if (optional) {
                    Text(
                        text = stringResource(R.string.onboarding_optional),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                when {
                    failed && onAction != null -> TextButton(onClick = onAction) {
                        Text(actionText ?: stringResource(R.string.onboarding_action_retry))
                    }
                    onAction != null -> TextButton(onClick = onAction) {
                        Text(actionText ?: stringResource(R.string.onboarding_action_grant))
                    }
                    else -> Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    HorizontalDivider()
}

/**
 * 计算并轮询权限检查状态:
 * - 页面可见期间每 [POLL_INTERVAL_MS] 刷新一次
 * - 从系统设置页返回(ON_RESUME)时立即刷新
 */
@Composable
private fun rememberOnboardingChecks(
    context: Context,
    requestNotification: () -> Unit,
): List<OnboardingCheckState> {
    var checks by remember { mutableStateOf(computeOnboardingChecks(context, requestNotification)) }

    val refresh = {
        checks = computeOnboardingChecks(context, requestNotification)
    }
    refresh()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(context) {
        while (true) {
            delay(POLL_INTERVAL_MS)
            refresh()
        }
    }

    return checks
}

private data class GrantJob(
    val title: String,
    val cmd: String,
    val verify: () -> Boolean,
)

private data class OnboardingCheckState(
    val title: String,
    val granted: Boolean,
    val optional: Boolean = false,
    val shizukuCommand: String? = null,
    val onAction: () -> Unit,
)

private fun computeOnboardingChecks(
    context: Context,
    requestNotification: () -> Unit,
): List<OnboardingCheckState> {
    val overlayGranted = Settings.canDrawOverlays(context)
    val notificationGranted =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            NotificationUtil.hasNotificationPermission(context)
    val accessibilityGranted = isAccessibilityServiceEnabled(context)
    val exactAlarmGranted = canScheduleExactAlarms(context)
    val storageGranted =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
    val pkg = context.packageName
    val notificationListenerComponent = "${AppNotificationListenerService::class.java.name}"

    return buildList {
        add(
            OnboardingCheckState(
                title = context.getString(R.string.onboarding_check_overlay),
                granted = overlayGranted,
                shizukuCommand = "appops set \$pkg android:system_alert_window allow",
                onAction = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
            )
        )
        add(
            OnboardingCheckState(
                title = context.getString(R.string.onboarding_check_notification),
                granted = notificationGranted,
                shizukuCommand = "pm grant \$pkg android.permission.POST_NOTIFICATIONS",
                onAction = requestNotification,
            )
        )
        add(
            OnboardingCheckState(
                title = context.getString(R.string.onboarding_check_accessibility),
                granted = accessibilityGranted,
                // 保留已有无障碍服务: 先读当前列表再拼接(冒号分隔), 最后打开总开关
                shizukuCommand = "C=\$(settings get secure enabled_accessibility_services); " +
                    "case \":\$C:\" in *\":\$pkg/${ScreenTextAccessibilityService::class.java.name}:\"*) ;; " +
                    "*) if [ -z \"\$C\" ]; then settings put secure enabled_accessibility_services " +
                    "\"\$pkg/${ScreenTextAccessibilityService::class.java.name}\"; " +
                    "else settings put secure enabled_accessibility_services " +
                    "\"\$C:\$pkg/${ScreenTextAccessibilityService::class.java.name}\"; fi ;; esac; " +
                    "settings put secure accessibility_enabled 1",
                onAction = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
            )
        )
        add(
            OnboardingCheckState(
                title = context.getString(R.string.onboarding_check_exact_alarm),
                granted = exactAlarmGranted,
                shizukuCommand = "appops set \$pkg SCHEDULE_EXACT_ALARM allow",
                onAction = {
                    openExactAlarmSettings(context)
                },
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            add(
                OnboardingCheckState(
                    title = context.getString(R.string.onboarding_check_storage),
                    granted = storageGranted,
                    shizukuCommand = "appops set \$pkg MANAGE_EXTERNAL_STORAGE allow",
                    onAction = {
                        openStorageSettings(context)
                    },
                )
            )
        }

        // ===== 自动化可选权限(不阻塞进入, 供"强大 Agent"使用) =====
        add(
            OnboardingCheckState(
                title = context.getString(R.string.onboarding_check_notification_listener),
                granted = isNotificationListenerEnabled(context),
                shizukuCommand = buildString {
                    append("L=\$(settings get secure enabled_notification_listeners); ")
                    append("if [ -z \"\$L\" ]; then ")
                    append("settings put secure enabled_notification_listeners \"\$pkg/$notificationListenerComponent\"; ")
                    append("else echo \"\$L\" | grep -q \"\$pkg/$notificationListenerComponent\" ")
                    append("|| settings put secure enabled_notification_listeners \"\$L:\$pkg/$notificationListenerComponent\"; fi")
                },
                onAction = { openSettings(context, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) },
                optional = true,
            )
        )
        add(
            OnboardingCheckState(
                title = context.getString(R.string.onboarding_check_usage_access),
                granted = isUsageAccessGranted(context),
                shizukuCommand = "cmd appops set \$pkg GET_USAGE_STATS allow; pm grant \$pkg android.permission.PACKAGE_USAGE_STATS",
                onAction = { openSettings(context, Settings.ACTION_USAGE_ACCESS_SETTINGS) },
                optional = true,
            )
        )
        add(
            OnboardingCheckState(
                title = context.getString(R.string.onboarding_check_write_settings),
                granted = Settings.System.canWrite(context),
                shizukuCommand = "pm grant \$pkg android.permission.WRITE_SETTINGS; appops set \$pkg WRITE_SETTINGS allow",
                onAction = { openSettingsWithPackage(context, Settings.ACTION_MANAGE_WRITE_SETTINGS) },
                optional = true,
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            add(
                OnboardingCheckState(
                    title = context.getString(R.string.onboarding_check_unknown_sources),
                    granted = context.packageManager.canRequestPackageInstalls(),
                    shizukuCommand = "pm grant \$pkg android.permission.REQUEST_INSTALL_PACKAGES",
                    onAction = { openSettingsWithPackage(context, Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES) },
                    optional = true,
                )
            )
        }
        add(
            OnboardingCheckState(
                title = context.getString(R.string.onboarding_check_battery),
                granted = isIgnoringBatteryOptimizations(context),
                shizukuCommand = "cmd deviceidle whitelist +\$pkg",
                onAction = { openBatteryOptimizationSettings(context) },
                optional = true,
            )
        )
        // Shizuku: 显示连接/授权状态, 点击即申请授权(无线调试或 root)
        add(
            OnboardingCheckState(
                title = context.getString(R.string.onboarding_check_shizuku),
                granted = ShizukuController.isBinderAlive() && ShizukuController.isPermissionGranted(),
                onAction = { ShizukuController.requestPermission() },
                optional = true,
            )
        )
    }
}

private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expected = ComponentName(context, ScreenTextAccessibilityService::class.java).flattenToString()
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
}

private fun isNotificationListenerEnabled(context: Context): Boolean {
    val expected = ComponentName(context, AppNotificationListenerService::class.java).flattenToString()
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners",
    ) ?: return false
    return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
}

private fun isUsageAccessGranted(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
    } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
    }
    return mode == AppOpsManager.MODE_ALLOWED
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun openSettings(context: Context, action: String) {
    runCatching {
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun openSettingsWithPackage(context: Context, action: String) {
    runCatching {
        context.startActivity(
            Intent(action, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun openBatteryOptimizationSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (intent.resolveActivity(context.packageManager) != null) {
        runCatching { context.startActivity(intent) }
    } else {
        openAppDetails(context)
    }
}

private fun canScheduleExactAlarms(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    return alarmManager.canScheduleExactAlarms()
}

private fun openExactAlarmSettings(context: Context) {
    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
        data = Uri.parse("package:${context.packageName}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (intent.resolveActivity(context.packageManager) != null) {
        runCatching { context.startActivity(intent) }
    } else {
        openAppDetails(context)
    }
}

private fun openStorageSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (intent.resolveActivity(context.packageManager) != null) {
        runCatching { context.startActivity(intent) }
    } else {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

private fun openAppDetails(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }
}

@Composable
private fun OnboardingProgress(
    progress: RootfsInstallProgress,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val fraction = progress.totalBytes?.takeIf { it > 0 }?.let {
            (progress.bytesRead.toFloat() / it).coerceIn(0f, 1f)
        }
        if (fraction != null && progress.stage == RootfsInstallStage.DOWNLOADING) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            text = when (progress.stage) {
                RootfsInstallStage.DOWNLOADING -> {
                    val total = progress.totalBytes?.let { " / ${it.fileSizeToString()}" }.orEmpty()
                    stringResource(R.string.workspace_detail_downloading, progress.bytesRead.fileSizeToString(), total)
                }

                RootfsInstallStage.EXTRACTING -> {
                    val entry = progress.currentEntry?.let { " · $it" }.orEmpty()
                    stringResource(R.string.workspace_detail_extracting, progress.entriesExtracted, entry)
                }

                RootfsInstallStage.INSTALLED -> stringResource(R.string.workspace_detail_install_complete)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
