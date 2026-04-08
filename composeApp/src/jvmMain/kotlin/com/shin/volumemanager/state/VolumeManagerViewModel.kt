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

            is VolumeManagerIntent.SetVolume ->
                audioManager.setVolume(intent.pid, intent.volume)

            is VolumeManagerIntent.ToggleMute -> {
                val s = _state.value.sessions.find { it.pid == intent.pid } ?: return
                audioManager.setMute(intent.pid, !s.isMuted)
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
