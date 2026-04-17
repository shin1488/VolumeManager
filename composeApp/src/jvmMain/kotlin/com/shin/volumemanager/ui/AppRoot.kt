package com.shin.volumemanager.ui

import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.shin.volumemanager.state.VolumeManagerIntent
import com.shin.volumemanager.state.VolumeManagerViewModel
import com.shin.volumemanager.ui.snap.WindowSnap
import java.awt.event.WindowFocusListener
import java.awt.event.WindowEvent

/**
 * Top-level UI host. Owns the main icon column [Window] and, inside it, the
 * [PanelHost] which manages the side panel as an owned [androidx.compose.ui.window.DialogWindow].
 *
 * Both windows live inside the same composition rooted at [Window], which is
 * what makes the dialog inherit the main window as its AWT owner — so it
 * doesn't get its own taskbar entry.
 */
@Composable
fun ApplicationScope.AppRoot(
    viewModel: VolumeManagerViewModel,
    onClose: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    val mainWindowState = rememberWindowState(
        width = COLLAPSED_W,
        height = 200.dp,
        position = WindowPosition.PlatformDefault,
    )

    // Grow/shrink the window vertically as audio sessions come and go.
    // This is a pure height change with the top edge anchored, which never
    // flickers — unlike the previous horizontal resize.
    LaunchedEffect(state.sessions.size) {
        mainWindowState.size = DpSize(COLLAPSED_W, windowHeight(state.sessions.size))
    }

    Window(
        onCloseRequest = onClose,
        state = mainWindowState,
        undecorated = true,
        transparent = true,
        alwaysOnTop = state.isAlwaysOnTop,
        title = "Volume Manager",
        resizable = false,
    ) {
        // Snap-to-corner + panel-side selection driven by main window position.
        WindowSnap(
            windowState = mainWindowState,
            onPanelOnLeftChange = {
                viewModel.handle(VolumeManagerIntent.SetPanelOnLeft(it))
            },
        )

        // Close the side panel as soon as focus leaves the main window.
        // The panel dialog is explicitly non-focusable (AWT
        // focusableWindowState = false) so clicking it does NOT cause the
        // main window to lose focus — which means a real focus-lost event
        // means the user clicked elsewhere, and the panel should go away.
        DisposableEffect(Unit) {
            val listener = object : WindowFocusListener {
                override fun windowGainedFocus(e: WindowEvent?) = Unit
                override fun windowLostFocus(e: WindowEvent?) {
                    viewModel.handle(VolumeManagerIntent.SelectPanel(null))
                }
            }
            window.addWindowFocusListener(listener)
            onDispose { window.removeWindowFocusListener(listener) }
        }

        WindowDraggableArea {
            IconColumnContent(
                state = state,
                onIntent = viewModel::handle,
                onClose = onClose,
            )
        }

        // Owned dialog → no second taskbar entry, no main-window resize on
        // panel toggle, no flicker.
        PanelHost(
            state = state,
            mainWindowState = mainWindowState,
            onIntent = viewModel::handle,
        )
    }
}
