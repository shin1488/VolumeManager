package com.shin.volumemanager.audio

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.shin.volumemanager.model.AudioSession
import com.sun.jna.platform.win32.COM.COMUtils
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WTypes
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Windows implementation of [AudioSessionService] built on WASAPI's
 * `IAudioSessionManager2` via JNA.
 *
 * **Grouping:** a single process (e.g. Chrome with several tabs playing
 * audio) and even one-exe-many-instances scenarios (e.g. two Discord
 * windows) create multiple underlying audio sessions. The UI should show
 * *one icon per application*, not one per session, so sessions are grouped
 * by their executable path (or the literal "SystemSounds" marker). Volume
 * and mute commands on the representative [AudioSession.pid] fan out to
 * every underlying PID in that group.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WindowsAudioSessionService : AudioSessionService {
    private val comDispatcher = newSingleThreadContext("COM-Audio")
    private val scope = CoroutineScope(SupervisorJob() + comDispatcher)

    private val _sessions = MutableStateFlow<List<AudioSession>>(emptyList())
    override val sessions: StateFlow<List<AudioSession>> = _sessions.asStateFlow()

    private val iconCache = mutableMapOf<String, ImageBitmap?>()
    private val volumeControls = mutableMapOf<Int, SimpleAudioVolume>()
    private val groupMembers = mutableMapOf<Int, List<Int>>()

    init {
        scope.launch {
            Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_MULTITHREADED)
            try {
                while (isActive) {
                    try {
                        refreshSessions()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    delay(1000)
                }
            } finally {
                releaseVolumeControls()
                Ole32.INSTANCE.CoUninitialize()
            }
        }
    }

    private fun refreshSessions() {
        releaseVolumeControls()
        groupMembers.clear()

        val ppEnum = PointerByReference()
        val hr = Ole32.INSTANCE.CoCreateInstance(
            CLSID_MMDeviceEnumerator, null,
            WTypes.CLSCTX_INPROC_SERVER,
            IID_IMMDeviceEnumerator, ppEnum
        )
        COMUtils.checkRC(hr)

        val enumerator = MMDeviceEnumerator(ppEnum.value)
        try {
            val devicePtr = enumerator.getDefaultAudioEndpoint(eRender, eConsole)
            val device = MMDevice(devicePtr)
            try {
                val mgr2Ptr = device.activate(IID_IAudioSessionManager2, CLSCTX_ALL)
                val mgr2 = AudioSessionManager2(mgr2Ptr)
                try {
                    val sessEnumPtr = mgr2.getSessionEnumerator()
                    val sessEnum = AudioSessionEnumerator(sessEnumPtr)
                    try {
                        val count = sessEnum.getCount()
                        val raw = mutableListOf<RawSession>()
                        for (i in 0 until count) {
                            collectSession(sessEnum, i, raw)
                        }
                        _sessions.value = groupSessions(raw)
                    } finally {
                        sessEnum.Release()
                    }
                } finally {
                    mgr2.Release()
                }
            } finally {
                device.Release()
            }
        } finally {
            enumerator.Release()
        }
    }

    private data class RawSession(
        val pid: Int,
        val exePath: String?,
        val displayName: String,
        val isSystemSounds: Boolean,
        val volume: Float,
        val isMuted: Boolean,
    )

    private fun collectSession(
        sessEnum: AudioSessionEnumerator,
        index: Int,
        out: MutableList<RawSession>,
    ) {
        val sessionPtr = try {
            sessEnum.getSession(index)
        } catch (_: Exception) {
            return
        }
        val session = AudioSessionControl(sessionPtr)
        try {
            val state = session.getState()
            if (state == 2) return

            val ctrl2Ptr = session.qi(IID_IAudioSessionControl2) ?: return
            val ctrl2 = AudioSessionControl2(ctrl2Ptr)
            val isSystemSounds: Boolean
            val pid: Int
            try {
                isSystemSounds = try { ctrl2.isSystemSounds() } catch (_: Exception) { false }
                pid = try { ctrl2.getProcessId() } catch (_: Exception) { 0 }
            } finally {
                ctrl2.Release()
            }

            val volPtr = session.qi(IID_ISimpleAudioVolume) ?: return
            val volumeControl = SimpleAudioVolume(volPtr)
            volumeControls[pid] = volumeControl

            val volume = try { volumeControl.getMasterVolume() } catch (_: Exception) { 1f }
            val isMuted = try { volumeControl.getMute() } catch (_: Exception) { false }

            val exePath = if (!isSystemSounds && pid > 0)
                IconExtractor.getProcessExePath(pid) else null
            var displayName = session.getDisplayName()
            if (displayName.isNullOrEmpty()) {
                displayName = exePath?.let { File(it).nameWithoutExtension }
                    ?: if (isSystemSounds) "System Sounds" else "PID $pid"
            }
            if (isSystemSounds) displayName = "System Sounds"

            out.add(
                RawSession(
                    pid = pid,
                    exePath = exePath,
                    displayName = displayName,
                    isSystemSounds = isSystemSounds,
                    volume = volume,
                    isMuted = isMuted,
                )
            )
        } finally {
            session.Release()
        }
    }

    private fun groupSessions(raw: List<RawSession>): List<AudioSession> {
        val groups = linkedMapOf<String, MutableList<RawSession>>()
        for (s in raw) {
            val key = when {
                s.isSystemSounds -> "__system_sounds__"
                !s.exePath.isNullOrEmpty() -> s.exePath.lowercase()
                else -> "__pid_${s.pid}__"
            }
            groups.getOrPut(key) { mutableListOf() }.add(s)
        }

        val ordered = groups.values.sortedByDescending { it.first().isSystemSounds }

        return ordered.map { members ->
            val head = members.first()
            val rep = if (head.isSystemSounds) 0 else head.pid
            groupMembers[rep] = members.map { it.pid }

            val icon = if (head.isSystemSounds) null
            else head.exePath?.let { path ->
                iconCache.getOrPut(path.lowercase()) {
                    try {
                        IconExtractor.extractIcon(path)?.toComposeImageBitmap()
                    } catch (_: Exception) {
                        null
                    }
                }
            }

            AudioSession(
                pid = rep,
                displayName = head.displayName,
                icon = icon,
                volume = head.volume,
                isMuted = head.isMuted,
            )
        }
    }

    override fun setVolume(pid: Int, volume: Float) {
        scope.launch {
            try {
                val level = volume.coerceIn(0f, 1f)
                val targets = groupMembers[pid] ?: listOf(pid)
                for (p in targets) {
                    volumeControls[p]?.setMasterVolume(level)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun setMute(pid: Int, muted: Boolean) {
        scope.launch {
            try {
                val targets = groupMembers[pid] ?: listOf(pid)
                for (p in targets) {
                    volumeControls[p]?.setMute(muted)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun releaseVolumeControls() {
        volumeControls.values.forEach { try { it.Release() } catch (_: Exception) {} }
        volumeControls.clear()
    }

    override fun dispose() {
        scope.cancel()
        comDispatcher.close()
    }
}
