package com.aiagents.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Settings03
import com.aiagents.R
import com.aiagents.Screen
import com.aiagents.data.datastore.Settings
import com.aiagents.ui.components.ui.AutoAIIcon
import com.aiagents.ui.components.ui.ToggleSurface
import com.aiagents.ui.context.LocalNavController
import com.aiagents.ui.context.Navigator

@Composable
fun SearchPickerButton(
    enableSearch: Boolean,
    settings: Settings,
    modifier: Modifier = Modifier,
    onToggleSearch: (Boolean) -> Unit,
    onUpdateSearchService: (Int) -> Unit,
) {
    var showSearchPicker by remember { mutableStateOf(false) }
    val currentService = settings.searchServices.getOrNull(settings.searchServiceSelected)

    ToggleSurface(
        modifier = modifier,
        checked = enableSearch,
        onClick = {
            showSearchPicker = true
        }
    ) {
        Row(
            modifier = Modifier
                .padding(vertical = 8.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                if (enableSearch && currentService != null) {
                    AutoAIIcon(
                        name = currentService.displayName,
                        color = Color.Transparent
                    )
                } else {
                    Icon(
                        imageVector = HugeIcons.Search01,
                        contentDescription = stringResource(R.string.use_web_search),
                    )
                }
            }
        }
    }

    if (showSearchPicker) {
        ModalBottomSheet(
            onDismissRequest = { showSearchPicker = false },
            sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.7f)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.search_picker_title),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold
                    )
                )

                SearchPicker(
                    enableSearch = enableSearch,
                    settings = settings,
                    onToggleSearch = onToggleSearch,
                    onUpdateSearchService = { index ->
                        onUpdateSearchService(index)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    onDismiss = {
                        showSearchPicker = false
                    }
                )
            }
        }
    }
}

@Composable
private fun SearchPicker(
    enableSearch: Boolean,
    settings: Settings,
    modifier: Modifier = Modifier,
    onToggleSearch: (Boolean) -> Unit,
    onUpdateSearchService: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AppSearchSettings(
        enableSearch = enableSearch,
        onDismiss = onDismiss,
        navBackStack = LocalNavController.current,
        onToggleSearch = onToggleSearch,
        modifier = modifier,
        settings = settings,
        onUpdateSearchService = onUpdateSearchService
    )
}

@Composable
private fun AppSearchSettings(
    enableSearch: Boolean,
    onDismiss: () -> Unit,
    navBackStack: Navigator,
    onToggleSearch: (Boolean) -> Unit,
    modifier: Modifier,
    settings: Settings,
    onUpdateSearchService: (Int) -> Unit
) {
    Card {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(HugeIcons.GlobalSearch, null)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.use_web_search),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = if (enableSearch) {
                        stringResource(R.string.web_search_enabled)
                    } else {
                        stringResource(R.string.web_search_disabled)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = 0.8f)
                )
            }
            IconButton(
                onClick = {
                    onDismiss()
                    navBackStack.navigate(Screen.SettingSearch)
                }
            ) {
                Icon(HugeIcons.Settings03, null)
            }
        }
    }
}
