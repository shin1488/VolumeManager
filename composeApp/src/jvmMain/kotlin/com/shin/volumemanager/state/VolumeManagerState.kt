package com.shin.volumemanager.state

import com.shin.volumemanager.model.AudioSession
import com.shin.volumemanager.model.PanelContent

/**
 * Immutable UI state for the Volume Manager.
 *
 * - [sessions]: live list of audio sessions from [AudioSessionService].
 * - [panelContent]: which side panel (if any) the user has selected. `null`
 *   means no panel is shown.
 * - [panelOnLeft]: which side of the icon column the panel should appear on.
 *   `true` = panel to the LEFT of the icons (used when the icon column is
 *   docked near the right edge of the screen).
 * - [opacity]: window opacity (0.2..1.0).
 * - [isAlwaysOnTop]: whether the icon column window stays above other windows.
 */
data class VolumeManagerState(
    val sessions: List<AudioSession> = emptyList(),
    val panelContent: PanelContent? = null,
    val panelOnLeft: Boolean = false,
    val opacity: Float = 0.95f,
    val isAlwaysOnTop: Boolean = false,
)
