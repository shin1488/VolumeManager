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
 * `IAudioSessionManager2` via JNA. This is the original implementation the
 * app shipped with; only the class name and the `AudioSessionService`
 * interface wiring were added when macOS support was scaffolded.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WindowsAudioSessionService : AudioSessionService {
    private val comDispatcher = newSingleThreadContext("COM-Audio")
    private val scope = CoroutineScope(SupervisorJob() + comDispatcher)

    private val _sessions = MutableStateFlow<List<AudioSession>>(emptyList())
    override val sessions: StateFlow<List<AudioSession>> = _sessions.asStateFlow()

    private val iconCache = mutableMapOf<Int, ImageBitmap?>()
    private val volumeControls = mutableMapOf<Int, SimpleAudioVolume>()

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
                        val seenPids = mutableSetOf<Int>()
                        val newSessions = mutableListOf<AudioSession>()

                        for (i in 0 until count) {
                            processSession(sessEnum, i, seenPids, newSessions)
                        }

                        _sessions.value = newSessions
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

    private fun processSession(
        sessEnum: AudioSessionEnumerator,
        index: Int,
        seenPids: MutableSet<Int>,
        result: MutableList<AudioSession>
    ) {
        val sessionPtr = try {
            sessEnum.getSession(index)
        } catch (_: Exception) {
            return
        }
        val session = AudioSessionControl(sessionPtr)

        try {
            val state = session.getState()
            if (state == 2) return // expired

            val ctrl2Ptr = session.qi(IID_IAudioSessionControl2) ?: return
            val ctrl2 = AudioSessionControl2(ctrl2Ptr)
            val pid = try {
                ctrl2.getProcessId()
            } catch (_: Exception) {
                ctrl2.Release(); return
            }
            ctrl2.Release()

            if (pid in seenPids) return
            if (pid == 0 && state != 1) return // skip inactive system sounds
            seenPids.add(pid)

            val volPtr = session.qi(IID_ISimpleAudioVolume) ?: return
            val volumeControl = SimpleAudioVolume(volPtr)
            volumeControls[pid] = volumeControl

            val volume = try {
                volumeControl.getMasterVolume()
            } catch (_: Exception) {
                1f
            }
            val isMuted = try {
                volumeControl.getMute()
            } catch (_: Exception) {
                false
            }

            var displayName = session.getDisplayName()
            val exePath = if (pid > 0) IconExtractor.getProcessExePath(pid) else null
            if (displayName.isNullOrEmpty()) {
                displayName = exePath?.let { File(it).nameWithoutExtension } ?: "PID $pid"
            }
            if (pid == 0) displayName = "System Sounds"

            val icon = iconCache.getOrPut(pid) {
                exePath?.let {
                    try {
                        IconExtractor.extractIcon(it)?.toComposeImageBitmap()
                    } catch (_: Exception) {
                        null
                    }
                }
            }

            result.add(AudioSession(pid, displayName, icon, volume, isMuted))
        } finally {
            session.Release()
        }
    }

    override fun setVolume(pid: Int, volume: Float) {
        scope.launch {
            try {
                volumeControls[pid]?.setMasterVolume(volume.coerceIn(0f, 1f))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun setMute(pid: Int, muted: Boolean) {
        scope.launch {
            try {
                volumeControls[pid]?.setMute(muted)
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
