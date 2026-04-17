package com.shin.volumemanager

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.application
import com.shin.volumemanager.audio.AudioSessionServiceFactory
import com.shin.volumemanager.state.VolumeManagerViewModel
import com.shin.volumemanager.ui.AppRoot
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Process entry point. Picks the platform [AudioSessionService] via
 * [AudioSessionServiceFactory] (Windows WASAPI, macOS Audio Tap) and
 * wires it into a [VolumeManagerViewModel], which is then handed to
 * [AppRoot]. Disposal is handled in a single place via [DisposableEffect]
 * so platform resources are released even on hard exit.
 *
 * Installs a file-based crash logger first — packaged launches have
 * no attached console, so an unhandled exception anywhere disappears
 * silently. Writing to `~/volumemanager-error.log` gives us something
 * to inspect when the app misbehaves in an end-user install.
 */
fun main() {
    installCrashLogger()
    application {
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
