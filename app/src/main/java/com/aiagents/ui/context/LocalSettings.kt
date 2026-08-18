package com.aiagents.ui.context

import androidx.compose.runtime.staticCompositionLocalOf
import com.aiagents.data.datastore.Settings

val LocalSettings = staticCompositionLocalOf<Settings> {
    error("No SettingsStore provided")
}
