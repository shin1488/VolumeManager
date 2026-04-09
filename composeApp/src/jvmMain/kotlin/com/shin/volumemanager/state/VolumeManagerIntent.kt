package com.shin.volumemanager.state

import com.shin.volumemanager.model.PanelContent

/**
 * User-driven actions that mutate [VolumeManagerState] or trigger side effects
 * via [VolumeManagerViewModel.handle].
 */
sealed class VolumeManagerIntent {
    /** Open a side panel, or pass `null` to close it. */
    data class SelectPanel(val content: PanelContent?) : VolumeManagerIntent()

    /** Set per-session volume (0..1). Forwarded to AudioSessionService. */
    data class SetVolume(val pid: Int, val volume: Float) : VolumeManagerIntent()

    /** Toggle mute on a session. Forwarded to AudioSessionService. */
    data class ToggleMute(val pid: Int) : VolumeManagerIntent()

    /** Set the global window opacity (0.2..1.0). */
    data class SetOpacity(val opacity: Float) : VolumeManagerIntent()

    /** Pin/unpin the icon column window. */
    data class SetAlwaysOnTop(val value: Boolean) : VolumeManagerIntent()

    /**
     * Set which side of the icon column the panel docks on. Computed by the
     * snap effect based on the icon column's screen position relative to the
     * screen center; not directly user-controlled.
     */
    data class SetPanelOnLeft(val value: Boolean) : VolumeManagerIntent()
}
