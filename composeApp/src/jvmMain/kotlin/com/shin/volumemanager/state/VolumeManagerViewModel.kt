package com.shin.volumemanager.state

import com.shin.volumemanager.audio.AudioManager
import com.shin.volumemanager.model.PanelContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Holds [VolumeManagerState] and processes [VolumeManagerIntent]s.
 *
 * UI layer reads [state] reactively and dispatches user actions through
 * [handle]. Audio I/O is delegated to the injected [AudioManager]; this class
 * owns no platform/Compose types so it can be unit-tested in isolation.
 */
class VolumeManagerViewModel(
    private val audioManager: AudioManager,
) {
    private val _state = MutableStateFlow(VolumeManagerState())
    val state: StateFlow<VolumeManagerState> = _state.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        // Observe live audio sessions and reflect them into state. If the
        // currently selected session disappears (process exited), close the
        // panel automatically so the UI doesn't show a stale slider.
        scope.launch {
            audioManager.sessions.collect { sessions ->
                _state.update { current ->
                    val pc = current.panelContent
                    val newPanel = if (pc is PanelContent.VolumeSession &&
                        sessions.none { it.pid == pc.pid }
                    ) null else pc
                    current.copy(sessions = sessions, panelContent = newPanel)
                }
            }
        }
    }

    fun handle(intent: VolumeManagerIntent) {
        when (intent) {
            is VolumeManagerIntent.SelectPanel ->
                _state.update { it.copy(panelContent = intent.content) }

            is VolumeManagerIntent.SetVolume -> {
                // Optimistically reflect the new volume in state so any UI
                // bound to session.volume (sliders that don't track local
                // state, future indicators, etc.) updates this frame instead
                // of waiting up to a full poll cycle for AudioManager to
                // re-emit. The poll will reconcile with the OS-reported
                // value if the SetMasterVolume call rounded or clamped.
                val v = intent.volume.coerceIn(0f, 1f)
                _state.update { s ->
                    s.copy(sessions = s.sessions.map {
                        if (it.pid == intent.pid) it.copy(volume = v) else it
                    })
                }
                audioManager.setVolume(intent.pid, v)
            }

            is VolumeManagerIntent.ToggleMute -> {
                val s = _state.value.sessions.find { it.pid == intent.pid } ?: return
                val newMute = !s.isMuted
                // Optimistic flip so the speaker icon, the dimmed app
                // icon in the column, and the slider's enabled state all
                // change on this frame. Without this the user clicks mute
                // and waits ~½s (avg poll latency) for any visual feedback
                // — feels broken even though the COM call already fired.
                _state.update { st ->
                    st.copy(sessions = st.sessions.map {
                        if (it.pid == intent.pid) it.copy(isMuted = newMute) else it
                    })
                }
                audioManager.setMute(intent.pid, newMute)
            }

            is VolumeManagerIntent.SetOpacity ->
                _state.update { it.copy(opacity = intent.opacity) }

            is VolumeManagerIntent.SetAlwaysOnTop ->
                _state.update { it.copy(isAlwaysOnTop = intent.value) }

            is VolumeManagerIntent.SetPanelOnLeft ->
                _state.update { it.copy(panelOnLeft = intent.value) }
        }
    }

    fun dispose() {
        scope.cancel()
        audioManager.dispose()
    }
}
