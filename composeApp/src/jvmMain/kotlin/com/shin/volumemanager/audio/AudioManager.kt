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
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Polls the Windows Core Audio session list once per second and exposes the
 * result as a [StateFlow] of [AudioSession]s to the UI layer.
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
class AudioManager {
    private val comDispatcher = newSingleThreadContext("COM-Audio")
    private val scope = CoroutineScope(SupervisorJob() + comDispatcher)

    private val _sessions = MutableStateFlow<List<AudioSession>>(emptyList())
    val sessions: StateFlow<List<AudioSession>> = _sessions.asStateFlow()

    // Icon cache keyed by exe path (absolute, lowercased). Safer than
    // caching by PID: PIDs get recycled, exe paths don't change.
    private val iconCache = mutableMapOf<String, ImageBitmap?>()

    // Live ISimpleAudioVolume interfaces keyed by *underlying* PID.
    // Rebuilt on every refresh so expired sessions don't leak COM refs.
    private val volumeControls = mutableMapOf<Int, SimpleAudioVolume>()

    // Map from the representative pid (exposed to the UI) to every
    // underlying pid that belongs to the same app group. A volume command
    // on the representative fans out to all of these.
    private val groupMembers = mutableMapOf<Int, List<Int>>()

    /**
     * Pending user-initiated changes, keyed by representative pid. While an
     * entry is present, [groupSessions] overlays the pending value over what
     * the OS reports — so a poll that snapshotted before a setMute / setVolume
     * COM call can't transiently revert the UI to the pre-call state. The
     * entry is cleared as soon as the OS-observed value catches up to the
     * pending value (or after [PENDING_TTL_MS] as a safety net for sessions
     * that died mid-change).
     *
     * Without this overlay, two rapid mute clicks could land in a tiny
     * window where the first click's setMute was queued behind a refresh:
     * the refresh would emit the pre-mute snapshot, ViewModel would revert
     * the optimistic state, then the second click would read the reverted
     * value and toggle in the same direction as the first — a stuck mute
     * button that needs a third click to recover. Pending overlay closes
     * that window.
     */
    private val pendingMute = ConcurrentHashMap<Int, PendingChange<Boolean>>()
    private val pendingVolume = ConcurrentHashMap<Int, PendingChange<Float>>()

    private data class PendingChange<T>(val value: T, val deadline: Long)

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
                    // 250 ms balances responsiveness to external volume
                    // changes (media keys, other mixers) against COM /
                    // CPU cost. User-initiated changes don't wait for a
                    // poll — setVolume / setMute write into _sessions
                    // immediately after their COM call returns.
                    delay(250)
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
                        // Accumulate every active session, then collapse
                        // into groups. Two-phase so that duplicate sessions
                        // within the same group still have their
                        // SimpleAudioVolume tracked for fan-out control.
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
            if (state == 2) return // expired

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

            // Keep system sounds visible even when inactive — users expect
            // the notification-volume slider to be available at any time,
            // not just during the brief window when a sound is actually
            // playing. `state == 2` (expired) is already filtered above.

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

    /**
     * Collapses [raw] into one [AudioSession] per app group. Groups are
     * keyed by exe path (lowercased) — system sounds use a fixed sentinel
     * key so every system-sounds session is one entry, and processes we
     * couldn't resolve to an exe path fall back to keying by PID (so they
     * at least don't all collapse into a single `null` bucket).
     *
     * The first session in each group wins for display name, icon, volume,
     * and mute state; remaining members are remembered in [groupMembers]
     * so `setVolume`/`setMute` can fan out to every underlying PID.
     */
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

        // Pin system sounds to the top of the list. Windows doesn't
        // guarantee enumeration order, and the system-sounds entry is the
        // one the user is most likely to want quick access to (notification
        // volume), so anchor it regardless of where Core Audio surfaces it.
        val ordered = groups.values.sortedByDescending { it.first().isSystemSounds }

        return ordered.map { members ->
            val head = members.first()
            // Use pid=0 as the representative id for the system-sounds
            // group — the UI layer (SessionIconButton) treats pid==0 as
            // the marker for the speaker fallback icon. For everything
            // else the first member's real PID identifies the group.
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

            // Apply pending overlay so a refresh that snapshotted before
            // the user's setMute/setVolume COM call doesn't transiently
            // emit the pre-call value. Pending entries clear themselves
            // here once the OS-observed value catches up, or after the
            // TTL elapses (in case the session died mid-change).
            val now = System.currentTimeMillis()
            val effectiveMuted = resolvePending(
                pending = pendingMute,
                key = rep,
                observed = head.isMuted,
                now = now,
                matches = { a, b -> a == b },
            )
            val effectiveVolume = resolvePending(
                pending = pendingVolume,
                key = rep,
                observed = head.volume,
                now = now,
                // Volume comes back from the OS quantized to a step the
                // mixer can express, so accept "close enough" as a match.
                matches = { a, b -> abs(a - b) < 0.005f },
            )

            AudioSession(
                pid = rep,
                displayName = head.displayName,
                icon = icon,
                volume = effectiveVolume,
                isMuted = effectiveMuted,
            )
        }
    }

    private fun <T> resolvePending(
        pending: ConcurrentHashMap<Int, PendingChange<T>>,
        key: Int,
        observed: T,
        now: Long,
        matches: (T, T) -> Boolean,
    ): T {
        val p = pending[key] ?: return observed
        return when {
            now > p.deadline -> { pending.remove(key); observed }
            matches(observed, p.value) -> { pending.remove(key); observed }
            else -> p.value
        }
    }

    fun setVolume(pid: Int, volume: Float) {
        val level = volume.coerceIn(0f, 1f)
        // Record BEFORE launching so any refresh that's already in flight
        // (running on the same comDispatcher, ahead of our coroutine in
        // the queue) sees the pending value when it gets to groupSessions.
        pendingVolume[pid] = PendingChange(level, System.currentTimeMillis() + PENDING_TTL_MS)
        scope.launch {
            try {
                // Fan out to every PID in the group so grouped sessions
                // move in lockstep. Falls back to the single PID if this
                // one isn't a group representative.
                val targets = groupMembers[pid] ?: listOf(pid)
                for (p in targets) {
                    volumeControls[p]?.setMasterVolume(level)
                }
                // Mirror into _sessions so observers see the change before
                // the next poll. The pending overlay still owns the truth
                // until the OS-observed value catches up.
                _sessions.value = _sessions.value.map {
                    if (it.pid == pid) it.copy(volume = level) else it
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun setMute(pid: Int, muted: Boolean) {
        pendingMute[pid] = PendingChange(muted, System.currentTimeMillis() + PENDING_TTL_MS)
        scope.launch {
            try {
                val targets = groupMembers[pid] ?: listOf(pid)
                for (p in targets) {
                    volumeControls[p]?.setMute(muted)
                }
                _sessions.value = _sessions.value.map {
                    if (it.pid == pid) it.copy(isMuted = muted) else it
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private companion object {
        // How long a pending user change overrides the OS-observed value
        // before we give up and accept what the OS reports. Long enough
        // to cover any plausible COM round-trip + a couple of poll cycles
        // (so the second poll after setMute can confirm), short enough
        // that a session that died mid-change doesn't freeze its row.
        private const val PENDING_TTL_MS = 3000L
    }

    private fun releaseVolumeControls() {
        volumeControls.values.forEach { try { it.Release() } catch (_: Exception) {} }
        volumeControls.clear()
    }

    fun dispose() {
        scope.cancel()
        comDispatcher.close()
    }
}
