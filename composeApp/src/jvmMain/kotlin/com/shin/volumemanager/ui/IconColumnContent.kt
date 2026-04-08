package com.shin.volumemanager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme as M3Theme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.shin.volumemanager.model.PanelContent
import com.shin.volumemanager.state.VolumeManagerIntent
import com.shin.volumemanager.state.VolumeManagerState
import com.shin.volumemanager.ui.components.ColumnDivider
import com.shin.volumemanager.ui.components.SessionIconButton

/**
 * The icon column UI hosted inside the main window. Pure presentation:
 * reads from [state] and dispatches user actions through [onIntent].
 *
 * Note: alignment is always `TopStart` because the main window's width is
 * fixed at [COLLAPSED_W] and never grows. The panel that opens to the
 * left/right of these icons lives in a separate [DialogWindow] (see
 * [PanelHost]) so the icon column itself never has to reposition.
 */
@Composable
fun IconColumnContent(
    state: VolumeManagerState,
    onIntent: (VolumeManagerIntent) -> Unit,
    onClose: () -> Unit,
) {
    androidx.compose.material3.MaterialTheme(colorScheme = darkColorScheme()) {
        Box(Modifier.fillMaxSize().graphicsLayer(alpha = state.opacity)) {
            Surface(
                modifier = Modifier
                    .width(COLLAPSED_W)
                    .fillMaxHeight()
                    .padding(4.dp),
                shape = RoundedCornerShape(12.dp),
                color = M3Theme.colorScheme.surface,
                tonalElevation = 4.dp,
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Pin (always-on-top toggle)
                    Box(
                        modifier = Modifier
                            .size(ICON_BOX)
                            .clip(CircleShape)
                            .background(
                                if (state.isAlwaysOnTop)
                                    M3Theme.colorScheme.primary.copy(0.15f)
                                else Color.Transparent
                            )
                            .clickable {
                                onIntent(VolumeManagerIntent.SetAlwaysOnTop(!state.isAlwaysOnTop))
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.PushPin, "Always on top",
                            tint = if (state.isAlwaysOnTop) M3Theme.colorScheme.primary
                            else M3Theme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(ICON_DEF),
                        )
                    }

                    // Opacity
                    val isOpacitySelected = state.panelContent is PanelContent.Opacity
                    Box(
                        modifier = Modifier
                            .size(ICON_BOX)
                            .clip(CircleShape)
                            .background(
                                if (isOpacitySelected)
                                    M3Theme.colorScheme.primary.copy(0.15f)
                                else Color.Transparent
                            )
                            .clickable {
                                onIntent(
                                    VolumeManagerIntent.SelectPanel(
                                        if (isOpacitySelected) null else PanelContent.Opacity
                                    )
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Opacity, "투명도", Modifier.size(ICON_DEF),
                            tint = if (isOpacitySelected) M3Theme.colorScheme.primary
                            else M3Theme.colorScheme.onSurfaceVariant,
                        )
                    }

                    ColumnDivider()

                    // Sessions
                    state.sessions.forEach { session ->
                        key(session.pid) {
                            val pc = state.panelContent
                            val isSelected =
                                pc is PanelContent.VolumeSession && pc.pid == session.pid
                            SessionIconButton(
                                session = session,
                                isSelected = isSelected,
                                onClick = {
                                    onIntent(
                                        VolumeManagerIntent.SelectPanel(
                                            if (isSelected) null
                                            else PanelContent.VolumeSession(session.pid)
                                        )
                                    )
                                },
                            )
                        }
                    }

                    if (state.sessions.isEmpty()) Spacer(Modifier.height(ICON_BOX))

                    ColumnDivider()
                    Spacer(Modifier.weight(1f))

                    // Close
                    Box(
                        modifier = Modifier
                            .size(ICON_BOX)
                            .clip(CircleShape)
                            .clickable { onClose() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Close, "Close", Modifier.size(28.dp),
                            tint = M3Theme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }
            }
        }
    }
}
