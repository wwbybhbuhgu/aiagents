package com.aiagents.ui.pages.setting

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiagents.data.automation.AutoAccessibilityController
import com.aiagents.data.automation.ShizukuController
import com.aiagents.service.ScreenTextAccessibilityService
import com.aiagents.service.ScreenTextRepository
import com.aiagents.ui.components.nav.BackButton
import com.aiagents.ui.components.ui.CardGroup
import com.aiagents.ui.theme.CustomColors
import com.aiagents.utils.plus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingAutomationPage(vm: SettingVM = koinViewModel()) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    var accessibilityEnabled by remember { mutableStateOf(false) }
    var accessibilityConnected by remember { mutableStateOf(false) }
    var shizukuAlive by remember { mutableStateOf(false) }
    var shizukuGranted by remember { mutableStateOf(false) }
    var shizukuVersion by remember { mutableStateOf(-1) }
    var screenshotResult by remember { mutableStateOf<String?>(null) }
    var hierarchyResult by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        accessibilityEnabled = isAccessibilityEnabled(context)
        accessibilityConnected = ScreenTextRepository.isConnected() && AutoAccessibilityController.isConnected()
        shizukuAlive = ShizukuController.isBinderAlive()
        shizukuGranted = ShizukuController.isPermissionGranted()
        shizukuVersion = ShizukuController.getVersion()
    }

    LaunchedEffect(Unit) {
        while (true) {
            refresh()
            delay(1000)
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("自动化设置") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("无障碍服务") },
                ) {
                    item(
                        headlineContent = { Text("状态") },
                        supportingContent = {
                            Text(
                                when {
                                    !accessibilityEnabled -> "未开启：点击下方按钮前往系统设置开启"
                                    accessibilityConnected -> "已连接：AI 可读取屏幕并执行点击/滑动/输入"
                                    else -> "服务已开启但尚未连接，请稍候"
                                }
                            )
                        },
                        trailingContent = {
                            Text(if (accessibilityEnabled) "已开启" else "未开启")
                        },
                    )
                    item(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                )
                            }
                        },
                        supportingContent = { Text("开启后 AI 可以自动操作手机（点击、滑动、输入、返回等）") },
                        headlineContent = { Text("打开无障碍设置") },
                    )
                    item(
                        onClick = {
                            scope.launch {
                                val json = AutoAccessibilityController.hierarchyJson(maxDepth = 10, maxNodes = 50)
                                hierarchyResult = if (json == null) {
                                    "读取失败：无障碍未连接"
                                } else {
                                    json.take(300)
                                }
                            }
                        },
                        supportingContent = { Text("读取当前窗口的前 50 个节点并打印预览") },
                        headlineContent = { Text("测试：读取屏幕层级") },
                    )
                    hierarchyResult?.let { r ->
                        item(
                            supportingContent = { Text(r) },
                            headlineContent = { Text("层级预览") },
                        )
                    }
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("悬浮窗语音") },
                ) {
                    item(
                        headlineContent = { Text("静音几秒后自动停止") },
                        supportingContent = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Slider(
                                    value = settings.displaySetting.floatingAsrSilenceSeconds.toFloat(),
                                    onValueChange = {
                                        vm.updateSettings(
                                            settings.copy(
                                                displaySetting = settings.displaySetting.copy(
                                                    floatingAsrSilenceSeconds = it.toInt()
                                                )
                                            )
                                        )
                                    },
                                    valueRange = 1f..15f,
                                    steps = 13,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(text = "${settings.displaySetting.floatingAsrSilenceSeconds} 秒")
                            }
                        },
                    )
                    item(
                        headlineContent = { Text("说明") },
                        supportingContent = {
                            Text("悬浮窗语音输入时，静默停留超过该时长后自动结束本次识别（默认 3 秒）")
                        },
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("Shizuku（截图）") },
                ) {
                    item(
                        headlineContent = { Text("运行状态") },
                        supportingContent = {
                            Text(
                                when {
                                    !shizukuAlive -> "Shizuku 未运行：请先启动 Shizuku（无线调试或 root 方式）"
                                    !shizukuGranted -> "已连接但未授权：点击下方“请求权限”"
                                    else -> "已连接并授权（API v${shizukuVersion}），可执行 screencap 截图"
                                }
                            )
                        },
                        trailingContent = {
                            Text(
                                when {
                                    !shizukuAlive -> "未连接"
                                    !shizukuGranted -> "未授权"
                                    else -> "就绪"
                                }
                            )
                        },
                    )
                    item(
                        onClick = { ShizukuController.requestPermission() },
                        supportingContent = { Text("首次使用需弹出系统授权对话框，请在对话框中允许") },
                        headlineContent = { Text("请求 Shizuku 权限") },
                    )
                    item(
                        onClick = {
                            screenshotResult = null
                            scope.launch {
                                val file = ShizukuController.screenshotDir(context)
                                    .resolve("test_${System.currentTimeMillis()}.png")
                                val ok = ShizukuController.screenshotPng(file)
                                screenshotResult = if (ok) {
                                    "截图成功：${file.absolutePath}（${file.length()} bytes）"
                                } else {
                                    "截图失败：请确认 Shizuku 已运行并授权"
                                }
                            }
                        },
                        supportingContent = { Text("通过 Shizuku 执行 screencap -p 保存到应用目录") },
                        headlineContent = { Text("测试截图") },
                    )
                    screenshotResult?.let { r ->
                        item(
                            supportingContent = { Text(r) },
                            headlineContent = { Text("截图结果") },
                        )
                    }
                }
            }
        }
    }
}

private fun isAccessibilityEnabled(context: android.content.Context): Boolean {
    val expected = "${context.packageName}/${ScreenTextAccessibilityService::class.java.name}"
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ).orEmpty()
    return enabledServices.split(':').any { it.trim() == expected }
}
