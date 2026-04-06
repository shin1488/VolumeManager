package com.shin.volumemanager

import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.shin.volumemanager.audio.AudioManager

fun main() = application {
    var isAlwaysOnTop by remember { mutableStateOf(false) }
    val audioManager = remember { AudioManager() }
    val windowState = rememberWindowState(
        width = 64.dp,
        height = 200.dp,
        position = WindowPosition.PlatformDefault
    )

    DisposableEffect(Unit) {
        onDispose { audioManager.dispose() }
    }

    Window(
        onCloseRequest = {
            audioManager.dispose()
            exitApplication()
        },
        state = windowState,
        undecorated = true,
        transparent = true,
        alwaysOnTop = isAlwaysOnTop,
        title = "Volume Manager",
        resizable = false
    ) {
        WindowDraggableArea {
            App(
                audioManager = audioManager,
                windowState = windowState,
                isAlwaysOnTop = isAlwaysOnTop,
                onAlwaysOnTopChange = { isAlwaysOnTop = it },
                onClose = {
                    audioManager.dispose()
                    exitApplication()
                }
            )
        }
    }
}
