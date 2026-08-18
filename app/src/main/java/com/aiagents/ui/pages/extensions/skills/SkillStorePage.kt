package com.aiagents.ui.pages.extensions.skills

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiagents.R
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.hugeicons.stroke.Search01
import com.aiagents.Screen
import com.aiagents.data.files.SkillStore
import com.aiagents.data.files.StoreSkill
import com.aiagents.data.files.StoreSkillDetail
import com.aiagents.ui.components.nav.BackButton
import com.aiagents.ui.components.richtext.MarkdownBlock
import com.aiagents.ui.components.ui.AIAgentsConfirmDialog
import com.aiagents.ui.context.LocalNavController
import com.aiagents.ui.context.LocalToaster
import com.aiagents.ui.theme.CustomColors
import com.aiagents.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SkillStorePage() {
    val vm = koinViewModel<SkillsVM>()
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val storeManager = org.koin.java.KoinJavaComponent.getKoin().get<com.aiagents.data.files.SkillStoreManager>()

    var skills by remember { mutableStateOf<List<StoreSkill>>(SkillStore.skills) }
    var query by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        vm.loadStoreSkills { list ->
            if (list.isNotEmpty()) skills = list
            loading = false
        }
    }

    val trimmedQuery = query.trim()
    val urlSkill = remember(trimmedQuery) { parseGitHubSkillUrl(trimmedQuery) }
    val filtered = remember(skills, trimmedQuery) {
        if (trimmedQuery.isEmpty() || urlSkill != null) skills
        else storeManager.search(trimmedQuery, skills)
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.skill_store_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.skill_store_search_hint)) },
                leadingIcon = { Icon(HugeIcons.Search01, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(HugeIcons.Cancel01, contentDescription = null)
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (loading) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (urlSkill != null) {
                        item {
                            StoreSkillCard(
                                skill = urlSkill,
                                onClick = { navigateToDetail(navController, urlSkill) },
                            )
                        }
                    }
                    items(filtered, key = { it.name }) { skill ->
                        StoreSkillCard(
                            skill = skill,
                            onClick = { navigateToDetail(navController, skill) },
                        )
                    }
                }
            }
        }
    }
}

private fun navigateToDetail(navController: com.aiagents.ui.context.Navigator, skill: StoreSkill) {
    navController.navigate(
        Screen.SkillStoreDetail(
            name = skill.name,
            owner = skill.owner,
            repo = skill.repo,
            branch = skill.branch,
            skillPath = skill.skillPath,
            description = skill.description,
        )
    )
}

/** 解析 GitHub 技能链接为 StoreSkill，支持 https://github.com/owner/repo/tree/branch/path 形式。 */
private fun parseGitHubSkillUrl(url: String): StoreSkill? {
    if (!url.startsWith("https://github.com/")) return null
    val trimmed = url.trim().trimEnd('/')
    val regex = Regex("""https://github\.com/([^/]+)/([^/]+)(?:/tree/([^/]+)(/.*)?)?""")
    val match = regex.matchEntire(trimmed) ?: return null
    val owner = match.groupValues[1]
    val repo = match.groupValues[2]
    val branch = match.groupValues[3].ifBlank { "main" }
    val subPath = match.groupValues[4].trimStart('/')
    if (subPath.isBlank()) return null
    val name = subPath.substringAfterLast('/')
    return StoreSkill(
        owner = owner,
        repo = repo,
        branch = branch,
        skillPath = subPath,
        name = name,
        description = "",
    )
}

@Composable
private fun StoreSkillCard(
    skill: StoreSkill,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = HugeIcons.Puzzle,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = skill.name,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                )
                Text(
                    text = skill.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${skill.owner}/${skill.repo}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
fun SkillStoreDetailPage(
    name: String,
    owner: String,
    repo: String,
    branch: String,
    skillPath: String,
    description: String,
) {
    val vm = koinViewModel<SkillsVM>()
    val toaster = LocalToaster.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val skill = remember(name, owner, repo, branch, skillPath, description) {
        StoreSkill(
            owner = owner,
            repo = repo,
            branch = branch,
            skillPath = skillPath,
            name = name,
            description = description,
        )
    }
    var detail by remember { mutableStateOf<StoreSkillDetail?>(null) }
    var installing by remember { mutableStateOf(false) }
    var showUninstallConfirm by remember { mutableStateOf(false) }
    val installSuccessMsg = stringResource(R.string.skill_store_install_success)
    val installFailedMsg = stringResource(R.string.skill_store_install_failed)

    LaunchedEffect(skill) {
        vm.loadStoreDetail(skill) { detail = it }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(name) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        val d = detail
        if (d == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(innerPadding + PaddingValues(16.dp)),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                MetadataCard(detail = d)
                BodySection(detail = d)
                ActionBar(
                    detail = d,
                    installing = installing,
                    onInstall = {
                        installing = true
                        vm.installFromStore(skill) { success, message ->
                            installing = false
                            if (success) {
                                toaster.show(installSuccessMsg.format(message))
                                vm.loadStoreDetail(skill) { detail = it }
                            } else {
                                toaster.show(installFailedMsg.format(message))
                            }
                        }
                    },
                    onUninstall = { showUninstallConfirm = true },
                )
            }
        }
    }

    AIAgentsConfirmDialog(
        show = showUninstallConfirm,
        title = stringResource(R.string.skill_store_uninstall_title),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            showUninstallConfirm = false
            vm.deleteSkill(name)
            vm.loadStoreDetail(skill) { detail = it }
        },
        onDismiss = { showUninstallConfirm = false },
    ) {
        Text(stringResource(R.string.skill_store_uninstall_message, name))
    }
}

@Composable
private fun MetadataCard(detail: StoreSkillDetail) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = detail.name,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = detail.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                detail.license?.let {
                    Text(
                        text = stringResource(R.string.skill_store_license, it),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                detail.compatibility?.let {
                    Text(
                        text = stringResource(R.string.skill_store_compatibility, it),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun BodySection(detail: StoreSkillDetail) {
    val body = detail.body
    if (body.isNullOrBlank()) {
        Text(
            text = stringResource(R.string.skill_store_no_readme),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        MarkdownBlock(content = body)
    }
}

@Composable
private fun ActionBar(
    detail: StoreSkillDetail,
    installing: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    if (detail.isInstalled) {
        OutlinedButton(
            onClick = onUninstall,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.skill_store_uninstall))
        }
    } else {
        Button(
            onClick = onInstall,
            enabled = !installing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (installing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = HugeIcons.Download01,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = stringResource(R.string.skill_store_install),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
