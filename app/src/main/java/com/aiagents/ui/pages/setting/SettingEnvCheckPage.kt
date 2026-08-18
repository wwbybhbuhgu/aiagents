package com.aiagents.ui.pages.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiagents.R
import com.aiagents.ui.components.nav.BackButton
import com.aiagents.ui.context.LocalNavController
import com.aiagents.ui.pages.extensions.workspace.OnboardingChecksList
import com.aiagents.ui.pages.extensions.workspace.WorkspaceOnboardingVM
import com.aiagents.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel

/**
 * 环境与权限检查页: 从设置里随时进入, 复用初始化向导的检查清单
 * (工作区容器 / ffmpeg / 系统权限 / Shizuku 一键授权)。
 */
@Composable
fun SettingEnvCheckPage(
    vm: WorkspaceOnboardingVM = koinViewModel(),
) {
    val workspaces by vm.workspaces.collectAsStateWithLifecycle()
    val progress by vm.installProgress.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_env_check_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = 24.dp,
                    end = 24.dp,
                    top = 16.dp,
                    bottom = 32.dp,
                ),
        ) {
            Text(
                text = stringResource(R.string.setting_env_check_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(
                modifier = Modifier.padding(top = 12.dp),
            )
            val allGranted = OnboardingChecksList(
                workspaces = workspaces,
                installProgress = progress,
                containerError = error,
                onRetryContainer = vm::retry,
            )

            Spacer(
                modifier = Modifier.padding(top = 24.dp),
            )
            val navController = LocalNavController.current
            Button(
                onClick = { navController.popBackStack() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.setting_env_check_done))
            }
        }
    }
}
