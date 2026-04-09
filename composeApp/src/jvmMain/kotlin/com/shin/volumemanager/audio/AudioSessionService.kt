package com.shin.volumemanager.audio

import com.shin.volumemanager.model.AudioSession
import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-independent view of what the UI needs from the OS audio stack.
 *
 * Implementations:
 *  - [WindowsAudioSessionService] – uses WASAPI / Core Audio via JNA.
 *  - [MacAudioSessionService] – talks to the bundled Audio Server Plug-in
 *    (see `macos/VolumeManagerDevice`) over XPC. Not functional yet.
 *
 * The service owns its own background worker. `dispose()` must be called on
 * application exit.
 */
interface AudioSessionService {
    val sessions: StateFlow<List<AudioSession>>

    fun setVolume(pid: Int, volume: Float)
    fun setMute(pid: Int, muted: Boolean)

    fun dispose()
}

/**
 * Picks the right [AudioSessionService] implementation for the current OS.
 *
 * On unsupported platforms we fall back to a no-op stub so the UI can still
 * boot during development.
 */
object AudioSessionServiceFactory {
    fun create(): AudioSessionService {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            os.contains("win") -> WindowsAudioSessionService()
            os.contains("mac") || os.contains("darwin") -> MacAudioSessionService()
            else -> NoopAudioSessionService()
        }
    }
}
