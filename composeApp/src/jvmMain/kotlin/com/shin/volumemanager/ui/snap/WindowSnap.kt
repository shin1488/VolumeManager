package com.shin.volumemanager.ui.snap

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.awt.Toolkit
import java.awt.Window

/**
 * Drives two pieces of behavior whenever [windowState] changes:
 *
 * 1. **Corner snap**: when the window comes within `min(width/2, 20px)` of any
 *    horizontal AND vertical screen edge simultaneously, the position is
 *    rewritten so the window sticks to that corner.
 *
 * 2. **Side selection**: emits [onPanelOnLeftChange] with `true` whenever the
 *    icon column window's center is past the screen's horizontal center,
 *    meaning the side panel should open to the LEFT of the icons.
 *
 * **DPI / multi-monitor correctness.**
 *
 * Compose Desktop's [WindowState] stores position and size in [androidx.compose.ui.unit.Dp],
 * but the dp values are *not* density-multiplied at the AWT boundary — Compose
 * just wraps `awtWindow.x/y/width/height` (which are already in AWT user-space
 * pixels, with Windows DPI scaling handled by the JVM) as `x.dp`. See
 * `SwingWindow.desktop.kt` in `compose-multiplatform-core`. So multiplying
 * `Dp.value` by `density.density` here would over-scale on every non-100% DPI
 * display, which is exactly why an earlier version of this code snapped at
 * phantom edges on 125 / 150% laptops.
 *
 * Likewise, [java.awt.GraphicsEnvironment.maximumWindowBounds] only ever
 * returns the *default* screen — useless on multi-monitor setups. We instead
 * read [Window.getGraphicsConfiguration] every tick, so the snap targets the
 * monitor the window currently lives on, and subtract [Toolkit.getScreenInsets]
 * so we snap to the usable region (above the taskbar, etc.) rather than the
 * raw monitor bounds.
 */
@Composable
fun WindowSnap(
    awtWindow: Window,
    windowState: WindowState,
    onPanelOnLeftChange: (Boolean) -> Unit,
) {
    LaunchedEffect(Unit) {
        snapshotFlow { windowState.position to windowState.size }
            .distinctUntilChanged()
            .collect { (pos, size) ->
                if (pos !is WindowPosition.Absolute) return@collect

                // All quantities live in AWT user-space pixels.
                val x = pos.x.value.toInt()
                val y = pos.y.value.toInt()
                val w = size.width.value.toInt()
                val h = size.height.value.toInt()

                // Bounds of the screen the window is *currently on*, minus
                // taskbar / dock insets. graphicsConfiguration can transiently
                // be null while a window is being moved between monitors —
                // skip that tick rather than snap to garbage.
                val gc = awtWindow.graphicsConfiguration ?: return@collect
                val bounds = gc.bounds
                val insets = Toolkit.getDefaultToolkit().getScreenInsets(gc)
                val screenLeft = bounds.x + insets.left
                val screenTop = bounds.y + insets.top
                val screenRight = bounds.x + bounds.width - insets.right
                val screenBottom = bounds.y + bounds.height - insets.bottom

                // Corner snap. Threshold is in user-space pixels (so the
                // "feel" is consistent across DPI scales) and capped at
                // half the window so a tiny window still has room to move.
                val threshold = (w / 2).coerceAtMost(20)
                val nearLeft = x - screenLeft < threshold
                val nearRight = screenRight - (x + w) < threshold
                val nearTop = y - screenTop < threshold
                val nearBottom = screenBottom - (y + h) < threshold

                if ((nearLeft || nearRight) && (nearTop || nearBottom)) {
                    val snapX = if (nearLeft) screenLeft else screenRight - w
                    val snapY = if (nearTop) screenTop else screenBottom - h
                    if (x != snapX || y != snapY) {
                        windowState.position = WindowPosition(snapX.dp, snapY.dp)
                    }
                }

                // Which side should the panel open on? Compare against the
                // center of the *current* screen, not the primary monitor —
                // otherwise on a multi-monitor setup the panel would always
                // open on the same side regardless of which display the
                // icon column is on.
                val centerX = x + w / 2
                val screenCenterX = screenLeft + (screenRight - screenLeft) / 2
                onPanelOnLeftChange(centerX > screenCenterX)
            }
    }
}
