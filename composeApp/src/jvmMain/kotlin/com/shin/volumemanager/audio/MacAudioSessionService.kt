package com.shin.volumemanager.audio

import com.shin.volumemanager.model.AudioSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
 * Communicates with `VolumeManagerHelper` — a Swift process that uses the
 * modern macOS 14.2+ Audio Tap API (`AudioHardwareCreateProcessTap`) for
 * per-app volume control. Communication happens over JSON-over-stdio.
 *
 * **Protocol (helper stdin/stdout):**
 * ```
 * → {"op":"list"}
 * ← {"type":"sessions","sessions":[{"pid":1234,"name":"Spotify","volume":0.8,"muted":false}]}
 *
 * → {"op":"setVolume","pid":1234,"value":0.5}
 * → {"op":"setMute","pid":1234,"value":1}
 * ```
 *
 * The helper is located via:
 *   1. `VOLUMEMANAGER_HELPER` environment variable (dev override)
 *   2. `~/.volumemanager/VolumeManagerHelper` (installed location)
 *   3. Common Swift build output paths
 */
class MacAudioSessionService : AudioSessionService {
    private val _sessions = MutableStateFlow<List<AudioSession>>(emptyList())
    override val sessions: StateFlow<List<AudioSession>> = _sessions.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var readerJob: Job? = null
    private var pollerJob: Job? = null
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
                    "macOS audio backend is disabled."
            )
            return
        }
        System.err.println("[VolumeManager] launching helper: $helperPath")

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

        // Drain stderr in background so the helper doesn't block.
        scope.launch {
            BufferedReader(InputStreamReader(proc.errorStream)).use { reader ->
                while (isActive) {
                    val line = reader.readLine() ?: break
                    System.err.println("[Helper] $line")
                }
            }
        }

        // Read stdout — parse JSON events from the helper.
        readerJob = scope.launch {
            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                while (isActive) {
                    val line = reader.readLine() ?: break
                    try {
                        handleEvent(line)
                    } catch (e: Exception) {
                        System.err.println("[VolumeManager] parse error: ${e.message} — $line")
                    }
                }
            }
            // Helper exited — clear sessions.
            _sessions.value = emptyList()
            System.err.println("[VolumeManager] helper process exited")
        }

        // Poll every second until subscribe is implemented in the helper.
        pollerJob = scope.launch {
            delay(500) // let helper start up
            while (isActive) {
                send("""{"op":"list"}""")
                delay(1000)
            }
        }
    }

    private fun handleEvent(line: String) {
        val type = extractString(line, "type") ?: return

        when (type) {
            "sessions" -> {
                val list = parseSessionsArray(line)
                _sessions.value = list
            }
            "status" -> {
                val msg = extractString(line, "message")
                System.err.println("[VolumeManager] helper status: $msg")
            }
            "error" -> {
                val msg = extractString(line, "message")
                System.err.println("[VolumeManager] helper error: $msg")
            }
        }
    }

    private fun parseSessionsArray(json: String): List<AudioSession> {
        val arrStart = json.indexOf("\"sessions\"")
        if (arrStart < 0) return emptyList()
        val bracketStart = json.indexOf('[', arrStart)
        if (bracketStart < 0) return emptyList()
        val bracketEnd = json.lastIndexOf(']')
        if (bracketEnd <= bracketStart) return emptyList()

        val arrayContent = json.substring(bracketStart + 1, bracketEnd).trim()
        if (arrayContent.isEmpty()) return emptyList()

        val objects = splitJsonObjects(arrayContent)

        return objects.mapNotNull { obj ->
            val pid = extractInt(obj, "pid") ?: return@mapNotNull null
            val name = extractString(obj, "name") ?: "pid:$pid"
            val volume = extractDouble(obj, "volume") ?: 1.0
            val muted = extractBool(obj, "muted") ?: false

            AudioSession(
                pid = pid,
                displayName = name,
                icon = null,
                volume = volume.toFloat(),
                isMuted = muted,
            )
        }
    }

    private fun splitJsonObjects(content: String): List<String> {
        val results = mutableListOf<String>()
        var depth = 0
        var start = 0
        for (i in content.indices) {
            when (content[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        results.add(content.substring(start, i + 1))
                        start = i + 1
                        while (start < content.length && content[start] in listOf(',', ' ', '\t', '\n')) start++
                    }
                }
            }
        }
        return results
    }

    private fun extractString(json: String, key: String): String? {
        val pattern = "\"$key\"\\s*:\\s*\"([^\"]*)\""
        val match = Regex(pattern).find(json) ?: return null
        return match.groupValues[1]
    }

    private fun extractInt(json: String, key: String): Int? {
        val pattern = "\"$key\"\\s*:\\s*(-?\\d+)"
        val match = Regex(pattern).find(json) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    private fun extractDouble(json: String, key: String): Double? {
        val pattern = "\"$key\"\\s*:\\s*(-?[\\d.]+)"
        val match = Regex(pattern).find(json) ?: return null
        return match.groupValues[1].toDoubleOrNull()
    }

    private fun extractBool(json: String, key: String): Boolean? {
        val pattern = "\"$key\"\\s*:\\s*(true|false)"
        val match = Regex(pattern).find(json) ?: return null
        return match.groupValues[1] == "true"
    }

    private fun locateHelperBinary(): String? {
        System.getenv("VOLUMEMANAGER_HELPER")?.let { path ->
            if (java.io.File(path).canExecute()) return path
        }

        val home = System.getProperty("user.home") ?: return null

        val installed = java.io.File("$home/.volumemanager/VolumeManagerHelper")
        if (installed.canExecute()) return installed.absolutePath

        val devPaths = listOf(
            "$home/IdeaProjects/VM_Mac/VolumeManagerHelper/.build/debug/VolumeManagerHelper",
            "$home/IdeaProjects/VM_Mac/VolumeManagerHelper/.build/release/VolumeManagerHelper",
        )
        for (p in devPaths) {
            val f = java.io.File(p)
            if (f.canExecute()) return f.absolutePath
        }

        return null
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
        pollerJob?.cancel()
        readerJob?.cancel()
        try { helperWriter?.close() } catch (_: Exception) {}
        try { helperProcess?.destroy() } catch (_: Exception) {}
        scope.cancel()
    }
}

class NoopAudioSessionService : AudioSessionService {
    private val _sessions = MutableStateFlow<List<AudioSession>>(emptyList())
    override val sessions: StateFlow<List<AudioSession>> = _sessions.asStateFlow()

    override fun setVolume(pid: Int, volume: Float) = Unit
    override fun setMute(pid: Int, muted: Boolean) = Unit
    override fun dispose() = Unit
}
