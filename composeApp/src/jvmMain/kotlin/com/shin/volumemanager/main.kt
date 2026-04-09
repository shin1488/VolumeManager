package com.shin.volumemanager

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.application
import com.shin.volumemanager.audio.AudioSessionServiceFactory
import com.shin.volumemanager.state.VolumeManagerViewModel
import com.shin.volumemanager.ui.AppRoot

/**
 * Process entry point. Picks the platform [AudioSessionService] via
 * [AudioSessionServiceFactory] (Windows today, macOS stub in progress) and
 * wires it into a [VolumeManagerViewModel], which is then handed to
 * [AppRoot]. Disposal is handled in a single place via [DisposableEffect]
 * so platform resources are released even on hard exit.
 */
fun main() = application {
    val audioService = remember { AudioSessionServiceFactory.create() }
    val viewModel = remember { VolumeManagerViewModel(audioService) }

    DisposableEffect(Unit) {
        onDispose { viewModel.dispose() }
    }

    AppRoot(
        viewModel = viewModel,
        onClose = ::exitApplication,
    )
}
