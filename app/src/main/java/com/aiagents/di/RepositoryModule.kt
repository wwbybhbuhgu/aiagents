package com.aiagents.di

import android.content.Context
import android.os.Environment
import com.aiagents.data.files.FileFolders
import com.aiagents.data.files.FilesManager
import com.aiagents.data.files.MemoryManager
import com.aiagents.data.files.SkillDownloader
import com.aiagents.data.files.SkillManager
import com.aiagents.data.files.SkillStoreManager
import com.aiagents.data.repository.ConversationRepository
import com.aiagents.data.repository.FavoriteRepository
import com.aiagents.data.repository.FolderRepository
import com.aiagents.data.repository.FilesRepository
import com.aiagents.data.repository.GenMediaRepository
import com.aiagents.data.repository.MemoryRepository
import com.aiagents.data.repository.WorkspaceRepository
import com.aiagents.workspace.PersistentShellRunner
import com.aiagents.workspace.ProotShellRunner
import com.aiagents.workspace.RootfsInstaller
import com.aiagents.workspace.WorkspaceBindMount
import com.aiagents.workspace.WorkspaceManager
import org.koin.dsl.module
import java.io.File

/** 共享存储根目录下的 AI-Agent 目录(用户可见, proot 容器可访问) */
const val AI_AGENT_SHARED_DIR = "AI-Agent"

val repositoryModule = module {
    single {
        ConversationRepository(get(), get(), get(), get(), get(), get())
    }

    single {
        FolderRepository(get(), get())
    }

    single {
        MemoryRepository(get(), get(), get(), get())
    }

    single {
        MemoryManager(get())
    }

    single {
        GenMediaRepository(get())
    }

    single {
        FilesRepository(get())
    }

    single {
        FavoriteRepository(get())
    }

    single {
        val context: Context = get()
        PersistentShellRunner(
            nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir),
        )
    }

    single {
        val context: Context = get()
        // bind mount 源目录需放开到 o+rx(755), 否则 proot --root-id 容器内 root 无法读取
        // 共享存储(FUSE)目录 setReadable 不生效, 用 chmod 显式放开
        fun File.makeWorldReadableDir(): File {
            mkdirs()
            runCatching { setReadable(true, false) }
            runCatching { setExecutable(true, false) }
            runCatching {
                Runtime.getRuntime().exec(arrayOf("chmod", "771", absolutePath)).waitFor()
            }
            return this
        }
        WorkspaceManager(
            baseDir = File(context.filesDir, "workspaces"),
            shellRunner = get<PersistentShellRunner>(),
            // 共享存储根目录 AI-Agent 下的子目录(用户可见, proot 容器可访问)
            sdDir = File(Environment.getExternalStorageDirectory(), "$AI_AGENT_SHARED_DIR/${FileFolders.SD_DIR}")
                .makeWorldReadableDir(),
            // 同一份挂载表既用于 PRoot 的 -b 参数, 也用于文件工具的路径解析, 避免两处漂移
            bindMounts = listOf(
                WorkspaceBindMount(
                    source = File(Environment.getExternalStorageDirectory(), "$AI_AGENT_SHARED_DIR/${FileFolders.SKILLS}")
                        .makeWorldReadableDir(),
                    target = "/skills",
                ),
                WorkspaceBindMount(
                    source = File(Environment.getExternalStorageDirectory(), "$AI_AGENT_SHARED_DIR/${FileFolders.MEMORIES}")
                        .makeWorldReadableDir(),
                    target = "/memories",
                ),
                WorkspaceBindMount(
                    source = File(context.filesDir, FileFolders.TOOL_OUTPUTS).makeWorldReadableDir(),
                    target = "/tool_outputs",
                ),
                WorkspaceBindMount(
                    source = File(context.filesDir, FileFolders.UPLOAD).makeWorldReadableDir(),
                    target = "/upload",
                ),
                WorkspaceBindMount(
                    source = File(Environment.getExternalStorageDirectory(), "$AI_AGENT_SHARED_DIR/${FileFolders.SCREENSHOTS}")
                        .makeWorldReadableDir(),
                    target = "/screenshots",
                ),
                WorkspaceBindMount(
                    source = File(Environment.getExternalStorageDirectory(), "$AI_AGENT_SHARED_DIR/${FileFolders.SD_DIR}")
                        .makeWorldReadableDir(),
                    target = com.aiagents.workspace.WorkspaceManager.ROOTFS_SD_DIR,
                ),
            ),
        )
    }

    single {
        RootfsInstaller(get())
    }

    single {
        WorkspaceRepository(get(), get(), get(), get())
    }

    single {
        FilesManager(get(), get(), get())
    }

    single {
        SkillManager(get(), get())
    }

    single {
        SkillDownloader(get())
    }

    single {
        SkillStoreManager(get(), get())
    }
}
