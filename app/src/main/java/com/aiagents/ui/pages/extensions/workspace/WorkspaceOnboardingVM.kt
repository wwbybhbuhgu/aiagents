package com.aiagents.ui.pages.extensions.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.aiagents.data.db.entity.WorkspaceEntity
import com.aiagents.data.repository.WorkspaceRepository
import com.aiagents.workspace.RootfsInstallProgress
import com.aiagents.workspace.RootfsInstallStage
import com.aiagents.workspace.WorkspaceShellStatus

/**
 * 初次启动向导：没有任何就绪工作区时, 自动创建默认工作区并下载 rootfs。
 * 完成后 [ready] 变为 true, 上层隐藏引导页进入聊天。
 */
class WorkspaceOnboardingVM(
    private val repository: WorkspaceRepository,
) : ViewModel() {
    val workspaces: StateFlow<List<WorkspaceEntity>> = repository.listFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _installProgress = MutableStateFlow<RootfsInstallProgress?>(null)
    val installProgress: StateFlow<RootfsInstallProgress?> = _installProgress.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        viewModelScope.launch {
            val list = repository.listFlow().first()
            if (list.none { it.shellStatus == WorkspaceShellStatus.READY.name }) {
                val workspace = list.firstOrNull() ?: runCatching {
                    repository.create("Workspace")
                }.getOrNull()
                if (workspace != null &&
                    workspace.shellStatus != WorkspaceShellStatus.READY.name &&
                    workspace.shellStatus != WorkspaceShellStatus.INSTALLING.name
                ) {
                    installRootfs(workspace.id, DEFAULT_ROOTFS_URL)
                }
            }
        }
    }

    fun retry() {
        viewModelScope.launch {
            val workspace = workspaces.value.firstOrNull()
                ?: runCatching { repository.create("Workspace") }.getOrNull()
                ?: return@launch
            if (workspace.shellStatus == WorkspaceShellStatus.READY.name) return@launch
            installRootfs(workspace.id, DEFAULT_ROOTFS_URL)
        }
    }

    fun dismissError() {
        _error.value = null
    }

    private fun installRootfs(id: String, url: String) {
        viewModelScope.launch {
            _error.value = null
            _installProgress.value = RootfsInstallProgress(stage = RootfsInstallStage.DOWNLOADING)
            try {
                repository.installRootfs(id, url) { progress ->
                    _installProgress.value = progress
                }
            } catch (e: CancellationException) {
                throw e
            } catch (error: Throwable) {
                _error.value = error.message ?: "Rootfs 安装失败"
            } finally {
                _installProgress.value = null
            }
        }
    }
}
