package com.aiagents.ui.pages.setting

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.Connect
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Refresh
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.hugeicons.stroke.Stop
import com.aiagents.data.proxy.ProxyGroup
import com.aiagents.data.proxy.ProxyManager
import com.aiagents.ui.components.nav.BackButton
import com.aiagents.ui.components.ui.CardGroup
import com.aiagents.ui.theme.CustomColors
import com.aiagents.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun SettingProxyPage(vm: SettingVM = koinViewModel()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val settings by vm.settings.collectAsState()
    val proxyConfig = settings.proxyConfig
    val proxyManager: ProxyManager = koinInject()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 文件选择器
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            val content = context.contentResolver.openInputStream(it)?.bufferedReader()?.readText() ?: ""
            vm.updateSettings(
                settings.copy(
                    proxyConfig = proxyConfig.copy(
                        configContent = content,
                        subscription = "",
                    )
                )
            )
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("代理") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        // 可观察的代理运行状态
        var isProxyRunning by remember { mutableStateOf(proxyManager.isRunning) }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 基本配置
            item("proxyConfig") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("基本配置") },
                ) {
                    // 启用代理开关
                    item(
                        leadingContent = { Icon(HugeIcons.SmartPhone01, null) },
                        trailingContent = {
                            Switch(
                                checked = proxyConfig.enabled,
                                onCheckedChange = { enabled ->
                                    val newConfig = proxyConfig.copy(enabled = enabled)
                                    vm.updateSettings(settings.copy(proxyConfig = newConfig))
                                    scope.launch {
                                        if (enabled) {
                                            val started = proxyManager.start(newConfig)
                                            isProxyRunning = proxyManager.isRunning
                                        } else {
                                            proxyManager.stop()
                                            isProxyRunning = proxyManager.isRunning
                                        }
                                    }
                                },
                            )
                        },
                        headlineContent = { Text("启用代理") },
                        supportingContent = { Text("开启后通过 mihomo 内核进行本地代理") },
                    )

                    // 订阅地址
                    item(
                        leadingContent = { Icon(HugeIcons.Earth, null) },
                        headlineContent = { Text("订阅地址") },
                        supportingContent = {
                            Column {
                                OutlinedTextField(
                                    value = proxyConfig.subscription,
                                    onValueChange = { url ->
                                        vm.updateSettings(
                                            settings.copy(
                                                proxyConfig = proxyConfig.copy(
                                                    subscription = url,
                                                    configContent = "",
                                                )
                                            )
                                        )
                                    },
                                    placeholder = { Text("https://example.com/subscribe") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    enabled = proxyConfig.configContent.isEmpty(),
                                )
                                if (proxyConfig.configContent.isNotEmpty()) {
                                    Text(
                                        text = "已导入配置文件，订阅地址已禁用",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                            }
                        },
                    )

                    // 从文件导入配置
                    item(
                        leadingContent = { Icon(HugeIcons.File02, null) },
                        headlineContent = { Text("导入配置文件") },
                        supportingContent = {
                            Column {
                                Text(
                                    text = if (proxyConfig.configContent.isNotEmpty()) {
                                        "已导入配置文件"
                                    } else {
                                        "支持 Clash/mihomo YAML 配置文件"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(top = 8.dp),
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            filePickerLauncher.launch(
                                                arrayOf(
                                                    "text/yaml",
                                                    "text/plain",
                                                    "application/x-yaml",
                                                    "*/*",
                                                )
                                            )
                                        },
                                    ) {
                                        Icon(HugeIcons.File02, null)
                                        Text("选择文件")
                                    }
                                    if (proxyConfig.configContent.isNotEmpty()) {
                                        OutlinedButton(
                                            onClick = {
                                                vm.updateSettings(
                                                    settings.copy(
                                                        proxyConfig = proxyConfig.copy(configContent = "")
                                                    )
                                                )
                                            },
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                contentColor = MaterialTheme.colorScheme.error,
                                            ),
                                        ) {
                                            Text("清空配置")
                                        }
                                    }
                                }
                            }
                        },
                    )

                    // 本地端口
                    item(
                        leadingContent = { Icon(HugeIcons.SmartPhone01, null) },
                        headlineContent = { Text("本地端口") },
                        supportingContent = {
                            OutlinedTextField(
                                value = proxyConfig.port.toString(),
                                onValueChange = { port ->
                                    port.toIntOrNull()?.let { p ->
                                        vm.updateSettings(settings.copy(proxyConfig = proxyConfig.copy(port = p)))
                                    }
                                },
                                placeholder = { Text("7890") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                        },
                    )

                    // 直连优先
                    item(
                        leadingContent = { Icon(HugeIcons.SmartPhone01, null) },
                        trailingContent = {
                            Switch(
                                checked = proxyConfig.directAsDefault,
                                onCheckedChange = { direct ->
                                    vm.updateSettings(settings.copy(proxyConfig = proxyConfig.copy(directAsDefault = direct)))
                                },
                            )
                        },
                        headlineContent = { Text("直连优先") },
                        supportingContent = { Text("未匹配规则时优先走直连") },
                    )

                    // 代理模式选择
                    item(
                        leadingContent = { Icon(HugeIcons.Earth, null) },
                        headlineContent = { Text("代理模式") },
                        supportingContent = {
                            Column {
                                val modes = listOf(
                                    0 to "全部走代理",
                                    1 to "仅工具和搜索",
                                    2 to "仅 LLM API",
                                    3 to "仅工作区",
                                    4 to "自定义",
                                )
                                modes.forEach { (mode, label) ->
                                    ListItem(
                                        headlineContent = { Text(label) },
                                        leadingContent = {
                                            RadioButton(
                                                selected = proxyConfig.proxyMode == mode,
                                                onClick = {
                                                    vm.updateSettings(settings.copy(proxyConfig = proxyConfig.copy(proxyMode = mode)))
                                                },
                                            )
                                        },
                                        modifier = Modifier.clickable {
                                            vm.updateSettings(settings.copy(proxyConfig = proxyConfig.copy(proxyMode = mode)))
                                        },
                                    )
                                }

                                // 自定义选项
                                if (proxyConfig.proxyMode == 4) {
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                    ListItem(
                                        headlineContent = { Text("工具调用") },
                                        trailingContent = {
                                            Switch(
                                                checked = proxyConfig.proxyTools,
                                                onCheckedChange = { vm.updateSettings(settings.copy(proxyConfig = proxyConfig.copy(proxyTools = it))) },
                                            )
                                        },
                                    )
                                    ListItem(
                                        headlineContent = { Text("LLM API") },
                                        trailingContent = {
                                            Switch(
                                                checked = proxyConfig.proxyLlmApi,
                                                onCheckedChange = { vm.updateSettings(settings.copy(proxyConfig = proxyConfig.copy(proxyLlmApi = it))) },
                                            )
                                        },
                                    )
                                    ListItem(
                                        headlineContent = { Text("工作区") },
                                        trailingContent = {
                                            Switch(
                                                checked = proxyConfig.proxyWorkspace,
                                                onCheckedChange = { vm.updateSettings(settings.copy(proxyConfig = proxyConfig.copy(proxyWorkspace = it))) },
                                            )
                                        },
                                    )
                                    ListItem(
                                        headlineContent = { Text("搜索服务") },
                                        trailingContent = {
                                            Switch(
                                                checked = proxyConfig.proxySearch,
                                                onCheckedChange = { vm.updateSettings(settings.copy(proxyConfig = proxyConfig.copy(proxySearch = it))) },
                                            )
                                        },
                                    )
                                }
                            }
                        },
                    )
                }
            }

            // 内核控制
            item("kernelControl") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("内核控制") },
                ) {
                    item(
                        leadingContent = { Icon(HugeIcons.Refresh, null) },
                        headlineContent = { Text("状态") },
                        supportingContent = {
                            Text(if (isProxyRunning) "运行中 (${proxyManager.localProxyAddress})" else "未运行")
                        },
                    )
                    item(
                        leadingContent = { Icon(HugeIcons.Connect, null) },
                        headlineContent = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            proxyManager.start(proxyConfig)
                                            isProxyRunning = proxyManager.isRunning
                                        }
                                    },
                                    enabled = !isProxyRunning,
                                ) {
                                    Text("启动")
                                }
                                Button(
                                    onClick = {
                                        scope.launch {
                                            proxyManager.stop()
                                            isProxyRunning = proxyManager.isRunning
                                        }
                                    },
                                    enabled = isProxyRunning,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error,
                                    ),
                                ) {
                                    Icon(HugeIcons.Stop, null)
                                    Text("停止")
                                }
                            }
                        },
                    )
                }
            }

            // 流量统计
            if (isProxyRunning) {
                item("traffic") {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        title = { Text("流量统计") },
                    ) {
                        item(
                            leadingContent = { Icon(HugeIcons.Earth, null) },
                            headlineContent = { Text("当前速度") },
                            supportingContent = {
                                var traffic by remember { mutableStateOf("加载中...") }
                                androidx.compose.runtime.LaunchedEffect(Unit) {
                                    traffic = proxyManager.getTrafficText()
                                }
                                Text(traffic)
                            },
                        )
                        item(
                            leadingContent = { Icon(HugeIcons.Earth, null) },
                            headlineContent = { Text("总流量") },
                            supportingContent = {
                                var totalTraffic by remember { mutableStateOf("加载中...") }
                                androidx.compose.runtime.LaunchedEffect(Unit) {
                                    totalTraffic = proxyManager.getTotalTrafficText()
                                }
                                Text(totalTraffic)
                            },
                        )
                    }
                }
            }

            // 节点列表
            if (isProxyRunning) {
                item("proxies") {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        title = { Text("节点") },
                    ) {
                        item(
                            headlineContent = {
                                var proxyGroups by remember { mutableStateOf<List<ProxyGroup>>(emptyList()) }
                                var selectedGroup by remember { mutableStateOf<String?>(null) }
                                var delayMap by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
                                var isTesting by remember { mutableStateOf(false) }

                                androidx.compose.runtime.LaunchedEffect(Unit) {
                                    proxyGroups = proxyManager.getProxyGroups()
                                }

                                Column {
                                    if (proxyGroups.isEmpty()) {
                                        Text("加载中...", style = MaterialTheme.typography.bodySmall)
                                    } else {
                                        // 测速按钮
                                        OutlinedButton(
                                            onClick = {
                                                scope.launch {
                                                    isTesting = true
                                                    // 收集所有节点名称
                                                    val allNodes = proxyGroups.flatMap { it.all }
                                                    // 并行测速
                                                    val results = proxyManager.testAllDelays(allNodes)
                                                    delayMap = results
                                                    isTesting = false
                                                }
                                            },
                                            enabled = !isTesting,
                                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                        ) {
                                            if (isTesting) {
                                                Text("测速中...")
                                            } else {
                                                Text("全部测速")
                                            }
                                        }

                                        proxyGroups.forEach { group ->
                                            // 组标题
                                            ListItem(
                                                headlineContent = {
                                                    Text(
                                                        text = "${group.name} (${group.type})",
                                                        style = MaterialTheme.typography.titleSmall,
                                                    )
                                                },
                                                supportingContent = {
                                                    Text(
                                                        text = "当前: ${group.now}",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.primary,
                                                    )
                                                },
                                                modifier = Modifier.clickable {
                                                    selectedGroup = if (selectedGroup == group.name) null else group.name
                                                },
                                                colors = ListItemDefaults.colors(
                                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                                ),
                                            )
                                            // 节点列表（展开时显示）
                                            if (selectedGroup == group.name) {
                                                group.all.forEach { node ->
                                                    val delay = delayMap[node]
                                                    ListItem(
                                                        headlineContent = {
                                                            Row(
                                                                modifier = Modifier.fillMaxWidth(),
                                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                                            ) {
                                                                Text(
                                                                    text = node,
                                                                    style = MaterialTheme.typography.bodyMedium,
                                                                    modifier = Modifier.weight(1f),
                                                                )
                                                                // 延迟显示
                                                                if (delay != null) {
                                                                    Text(
                                                                        text = when {
                                                                            delay < 0 -> "超时"
                                                                            delay == 0 -> "失败"
                                                                            else -> "${delay}ms"
                                                                        },
                                                                        style = MaterialTheme.typography.bodySmall,
                                                                        color = when {
                                                                            delay < 0 -> MaterialTheme.colorScheme.error
                                                                            delay < 200 -> MaterialTheme.colorScheme.primary
                                                                            delay < 500 -> MaterialTheme.colorScheme.tertiary
                                                                            else -> MaterialTheme.colorScheme.error
                                                                        },
                                                                    )
                                                                }
                                                            }
                                                        },
                                                        modifier = Modifier.clickable {
                                                            scope.launch {
                                                                proxyManager.changeProxy(group.name, node)
                                                                // 保存选择
                                                                val newConfig = proxyManager.saveSelectedProxy(group.name, node, proxyConfig)
                                                                vm.updateSettings(settings.copy(proxyConfig = newConfig))
                                                                proxyGroups = proxyManager.getProxyGroups()
                                                            }
                                                        },
                                                        colors = ListItemDefaults.colors(
                                                            containerColor = if (node == group.now) {
                                                                MaterialTheme.colorScheme.primaryContainer
                                                            } else {
                                                                Color.Transparent
                                                            },
                                                        ),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
