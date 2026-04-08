package com.shin.volumemanager

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.application
import com.shin.volumemanager.audio.AudioManager
import com.shin.volumemanager.state.VolumeManagerViewModel
import com.shin.volumemanager.ui.AppRoot

/**
 * Process entry point. Wires the platform [AudioManager] into a
 * [VolumeManagerViewModel] and hands the view model to [AppRoot], which owns
 * all UI. Disposal is handled in a single place via [DisposableEffect] so
 * COM resources are released even on hard exit.
 */
fun main() = application {
    val audioManager = remember { AudioManager() }
    val viewModel = remember { VolumeManagerViewModel(audioManager) }

    DisposableEffect(Unit) {
        onDispose { viewModel.dispose() }
    }

    AppRoot(
        viewModel = viewModel,
        onClose = ::exitApplication,
    )
}
