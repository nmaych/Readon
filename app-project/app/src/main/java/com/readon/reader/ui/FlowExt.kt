package com.readon.reader.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.StateFlow

/**
 * Observe a [StateFlow] as Compose state.
 *
 * `collectAsState` for a StateFlow lives behind the lifecycle-aware artifact;
 * this project keeps its dependency list to what the offline Gradle cache holds,
 * so the collection is written out here. The effect is keyed on the flow itself,
 * which means a screen that swaps its source re-subscribes and drops the old
 * collector.
 */
@Composable
fun <T> StateFlow<T>.collectAsStateSafe(): State<T> {
    val state = remember(this) { mutableStateOf(value) }
    LaunchedEffect(this) {
        collect { state.value = it }
    }
    return state
}
