package com.shin.volumemanager.audio

import com.shin.volumemanager.model.AudioSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter

/**
 * macOS implementation of [AudioSessionService].
 *
 * ## Status: SCAFFOLD — protocol wired, native backend not functional.
 *
 * macOS CoreAudio does not expose per-application volume the way Windows
 * WASAPI does. To provide the same "one slider per running app" experience,
 * VolumeManager ships two native pieces under `macos/`:
 *
 *   1. **VolumeManagerDevice** – an Audio Server Plug-in that publishes a
 *      virtual output device. Apps route through it, and the driver
 *      tracks per-client (per-pid) volume + mute controls.
 *   2. **VolumeManagerHelper** – a tiny Swift process that speaks a JSON
 *      protocol over stdio. It discovers the virtual device via CoreAudio
 *      and exposes `list` / `setVolume` / `setMute` / `subscribe` commands.
 *
 * This class spawns the helper (when the binary is found) and forwards
 * calls to it. Until the helper is actually installed, it silently falls
 * back to an empty session list so the Compose UI still boots on a Mac
 * for development. See `macos/ROADMAP.md` for the build order.
 */
class MacAudioSessionService : AudioSessionService {
    private val _sessions = MutableStateFlow<List<AudioSession>>(emptyList())
    override val sessions: StateFlow<List<AudioSession>> = _sessions.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var readerJob: Job? = null
    private var helperProcess: Process? = null
    private var helperWriter: PrintWriter? = null

    init {
        startHelper()
    }

    private fun startHelper() {
        val helperPath = locateHelperBinary()
        if (helperPath == null) {
            System.err.println(
                "[VolumeManager] VolumeManagerHelper not found. " +
                    "macOS audio backend is disabled. See macos/ROADMAP.md."
            )
            return
        }

        val proc = try {
            ProcessBuilder(helperPath)
                .redirectErrorStream(false)
                .start()
        } catch (e: Exception) {
            System.err.println("[VolumeManager] failed to launch helper: ${e.message}")
            return
        }

        helperProcess = proc
        helperWriter = PrintWriter(proc.outputStream, true)

        readerJob = scope.launch {
            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                while (isActive) {
                    val line = reader.readLine() ?: break
                    // TODO(macos): parse Event JSON and update _sessions.
                    //  Intentionally left unimplemented until the helper
                    //  actually emits real data; see macos/ROADMAP.md.
                }
            }
        }

        // Ask the helper to start streaming session updates.
        send("""{"op":"subscribe"}""")
    }

    private fun locateHelperBinary(): String? {
        // TODO(macos): look up the bundled helper in the app's Resources
        //  directory once packaging is wired. For now, honor an env var
        //  so developers can point at a local build.
        return System.getenv("VOLUMEMANAGER_HELPER")
    }

    private fun send(json: String) {
        helperWriter?.println(json)
    }

    override fun setVolume(pid: Int, volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        send("""{"op":"setVolume","pid":$pid,"value":$clamped}""")
    }

    override fun setMute(pid: Int, muted: Boolean) {
        val v = if (muted) 1 else 0
        send("""{"op":"setMute","pid":$pid,"value":$v}""")
    }

    override fun dispose() {
        readerJob?.cancel()
        try { helperWriter?.close() } catch (_: Exception) {}
        try { helperProcess?.destroy() } catch (_: Exception) {}
        scope.cancel()
    }
}

/**
 * Fallback used when the app is launched on a platform we do not support
 * (e.g. Linux during development). Produces no sessions and ignores writes.
 */
class NoopAudioSessionService : AudioSessionService {
    private val _sessions = MutableStateFlow<List<AudioSession>>(emptyList())
    override val sessions: StateFlow<List<AudioSession>> = _sessions.asStateFlow()

    override fun setVolume(pid: Int, volume: Float) = Unit
    override fun setMute(pid: Int, muted: Boolean) = Unit
    override fun dispose() = Unit
}
