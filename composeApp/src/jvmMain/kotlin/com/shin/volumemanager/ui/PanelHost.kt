package com.shin.volumemanager.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.AwtWindow
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import com.shin.volumemanager.model.AudioSession
import com.shin.volumemanager.model.PanelContent
import com.shin.volumemanager.state.VolumeManagerIntent
import com.shin.volumemanager.state.VolumeManagerState
import com.shin.volumemanager.ui.components.CompactOpacityPanel
import com.shin.volumemanager.ui.components.CompactVolumePanel
import java.awt.Dialog

/**
 * Hosts the side panel as an [AwtWindow]-managed [ComposeDialog] owned by the
 * main icon column window.
 *
 * We **cannot** use the high-level `DialogWindow` composable here. With
 * `focusable = false` on Windows, Compose Desktop's `DialogWindow` still lets
 * the JDialog become the *active* window briefly when first shown, which
 * silently kills mouse input on the owner (the icon column) — clicks on the
 * main column stop firing entirely. The fix is to set
 * `focusableWindowState = false` + `isAutoRequestFocus = false` + utility
 * window type **before** `pack()` / `setVisible(true)`. After the window is
 * displayable those properties either no-op or throw, so we have to wire the
 * dialog up via [AwtWindow]'s `create` factory.
 *
 * Owning the dialog by the parent [FrameWindowScope.window] keeps it out of
 * the Windows taskbar (single app entry) and tied to the main window's
 * lifecycle.
 */
@Composable
fun FrameWindowScope.PanelHost(
    state: VolumeManagerState,
    mainWindowState: WindowState,
    onIntent: (VolumeManagerIntent) -> Unit,
) {
    val pc = state.panelContent ?: return
    val mainPos = mainWindowState.position as? WindowPosition.Absolute ?: return

    val density = LocalDensity.current
    val parentWindow = window

    // Compute initial position ONCE on mount so the dialog appears at the
    // correct spot on its first frame instead of flashing at PlatformDefault.
    val initialPosition = remember {
        computePanelPosition(mainPos, state.panelOnLeft, pc, state.sessions)
    }

    // Drives the entry animation. Flipping `visible` to true on the next
    // frame is what makes AnimatedVisibility actually play the enter
    // transition.
    var animationVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { animationVisible = true }

    // The dialog's Compose content runs in a *separate* composition rooted
    // at ComposeDialog.setContent. That composition only observes mutable
    // state it reads directly — capturing `pc`/`state`/`onIntent` as plain
    // locals from this scope means the dialog content freezes on whatever
    // values existed at create time, which is why clicking another icon
    // would adjust the wrong session. Hoist the dynamic inputs into a
    // mutableStateOf cell that the dialog content reads, and refresh the
    // cell from AwtWindow's `update` callback (which fires whenever this
    // outer PanelHost recomposes).
    val contentHolder = remember {
        mutableStateOf(PanelInputs(pc, state, onIntent))
    }
    contentHolder.value = PanelInputs(pc, state, onIntent)

    AwtWindow(
        create = {
            ComposeDialog(parentWindow, Dialog.ModalityType.MODELESS).apply {
                title = "Panel"
                isUndecorated = true
                isResizable = false
                isAlwaysOnTop = parentWindow.isAlwaysOnTop

                // CRITICAL: must be set BEFORE the window becomes displayable
                // (before pack()/setVisible). After addNotify() these flags
                // either no-op or throw on Windows. This is the whole reason
                // we can't use the DialogWindow composable.
                focusableWindowState = false
                isAutoRequestFocus = false
                type = java.awt.Window.Type.UTILITY

                // Transparent layered window. ComposeDialog.isTransparent
                // is the public API that propagates transparency all the
                // way down to the underlying skia layer / ComposePanel —
                // setting AWT-level background alone leaves the skia
                // surface opaque, which is why a white rectangle was
                // showing behind the rounded Surface. Must be set BEFORE
                // the dialog becomes displayable and after isUndecorated.
                isTransparent = true

                // Position and size the JDialog directly so the first frame
                // appears in the right place — Compose's WindowState would
                // have to wait for an UpdateEffect cycle.
                val px = (initialPosition.x.value * density.density).toInt()
                val py = (initialPosition.y.value * density.density).toInt()
                val pw = (PANEL_W.value * density.density).toInt()
                val ph = (PANEL_H.value * density.density).toInt()
                setBounds(px, py, pw, ph)

                setContent {
                    val inputs = contentHolder.value
                    MaterialTheme(colorScheme = darkColorScheme()) {
                        Box(
                            modifier = Modifier.size(PANEL_W, PANEL_H),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            AnimatedVisibility(
                                visible = animationVisible,
                                enter = expandHorizontally(
                                    animationSpec = tween(ANIM_MS),
                                    expandFrom = if (inputs.state.panelOnLeft) Alignment.End
                                                 else Alignment.Start,
                                ) + fadeIn(tween(ANIM_MS)),
                            ) {
                                Surface(
                                    modifier = Modifier.fillMaxSize(),
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    tonalElevation = 4.dp,
                                ) {
                                    PanelBody(inputs.pc, inputs.state, inputs.onIntent)
                                }
                            }
                        }
                    }
                }
            }
        },
        dispose = { dialog ->
            dialog.isVisible = false
            dialog.dispose()
        },
        update = { dialog ->
            // Mirror always-on-top in case the user toggles the pin while
            // the panel is open.
            if (dialog.isAlwaysOnTop != parentWindow.isAlwaysOnTop) {
                dialog.isAlwaysOnTop = parentWindow.isAlwaysOnTop
            }

            // Reposition the dialog every recomposition so that switching
            // to a different session icon (pc change), dragging the main
            // window (mainWindowState.position change), or flipping sides
            // (panelOnLeft change) all move the panel to the new anchor.
            // PanelHost recomposes on any of those, so update() fires here
            // and we re-derive the absolute screen coords from the latest
            // pc/state. Doing this in update — instead of a snapshotFlow
            // keyed on pc — guarantees we don't miss the first frame after
            // pc changes.
            val absPos = mainWindowState.position as? WindowPosition.Absolute
                ?: return@AwtWindow
            val idx = if (pc is PanelContent.VolumeSession)
                state.sessions.indexOfFirst { it.pid == pc.pid } else -1
            val py = panelYForIndex(pc, idx)
            val pxDp = if (state.panelOnLeft) absPos.x - PANEL_W - PANEL_GAP
                       else                    absPos.x + COLLAPSED_W + PANEL_GAP
            val pyDp = absPos.y + py
            val sx = (pxDp.value * density.density).toInt()
            val sy = (pyDp.value * density.density).toInt()
            if (dialog.x != sx || dialog.y != sy) {
                dialog.setLocation(sx, sy)
            }
        },
    )
}

/** Bundle of inputs the dialog's inner composition reads via state. */
private data class PanelInputs(
    val pc: PanelContent,
    val state: VolumeManagerState,
    val onIntent: (VolumeManagerIntent) -> Unit,
)

@Composable
private fun PanelBody(
    pc: PanelContent,
    state: VolumeManagerState,
    onIntent: (VolumeManagerIntent) -> Unit,
) {
    when (pc) {
        is PanelContent.VolumeSession -> {
            val session = state.sessions.find { it.pid == pc.pid } ?: return
            CompactVolumePanel(
                session = session,
                onVolumeChange = { onIntent(VolumeManagerIntent.SetVolume(session.pid, it)) },
                onMuteToggle = { onIntent(VolumeManagerIntent.ToggleMute(session.pid)) },
            )
        }
        PanelContent.Opacity -> CompactOpacityPanel(
            opacity = state.opacity,
            onOpacityChange = { onIntent(VolumeManagerIntent.SetOpacity(it)) },
        )
    }
}

/** Where the panel dialog should sit on screen, in dp. */
private fun computePanelPosition(
    mainPos: WindowPosition.Absolute,
    panelOnLeft: Boolean,
    content: PanelContent,
    sessions: List<AudioSession>,
): WindowPosition.Absolute {
    val idx = if (content is PanelContent.VolumeSession)
        sessions.indexOfFirst { it.pid == content.pid } else -1
    val py = panelYForIndex(content, idx)
    val px = if (panelOnLeft) mainPos.x - PANEL_W - PANEL_GAP
             else             mainPos.x + COLLAPSED_W + PANEL_GAP
    return WindowPosition.Absolute(px, mainPos.y + py)
}

/** Y offset within the icon column window for the row matching [content]. */
private fun panelYForIndex(content: PanelContent, sessionIdx: Int): Dp =
    when (content) {
        is PanelContent.VolumeSession ->
            if (sessionIdx >= 0) ICONS_START_Y + ICON_STEP * sessionIdx else ICONS_START_Y
        PanelContent.Opacity -> OPACITY_Y
    }
