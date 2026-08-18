package com.aiagents.ui.context

import androidx.compose.runtime.compositionLocalOf
import com.aiagents.ui.hooks.CustomAsrState

val LocalASRState = compositionLocalOf<CustomAsrState> { error("Not provided yet") }

