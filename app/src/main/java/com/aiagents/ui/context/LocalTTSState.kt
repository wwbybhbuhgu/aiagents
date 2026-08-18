package com.aiagents.ui.context

import androidx.compose.runtime.compositionLocalOf
import com.aiagents.ui.hooks.CustomTtsState

val LocalTTSState = compositionLocalOf<CustomTtsState> { error("Not provided yet") }
