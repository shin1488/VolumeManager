package com.shin.volumemanager

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.application
import com.shin.volumemanager.audio.AudioManager
import com.shin.volumemanager.state.VolumeManagerViewModel
import com.shin.volumemanager.ui.AppRoot
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Process entry point. Wires the platform [AudioManager] into a
 * [VolumeManagerViewModel] and hands the view model to [AppRoot], which owns
 * all UI. Disposal is handled in a single place via [DisposableEffect] so
 * COM resources are released even on hard exit.
 *
 * Installs a file-based crash logger first — packaged (MSI) launches have
 * no attached console, so an unhandled exception anywhere (JNA init, COM
 * activation, Compose startup) disappears silently. Writing to
 * `%USERPROFILE%/volumemanager-error.log` gives us something to inspect
 * when the app misbehaves in an end-user install.
 */
fun main() {
    installCrashLogger()
    application {
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
}

private fun installCrashLogger() {
    val logFile = File(System.getProperty("user.home"), "volumemanager-error.log")
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        runCatching {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val ts = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            logFile.appendText("[$ts] Uncaught in ${thread.name}:\n$sw\n")
        }
        throwable.printStackTrace()
    }
}
