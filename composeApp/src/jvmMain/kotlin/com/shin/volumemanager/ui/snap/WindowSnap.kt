package com.shin.volumemanager.ui.snap

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.awt.GraphicsEnvironment

/**
 * Drives two pieces of behavior by polling the AWT window position:
 *
 * 1. **Corner snap**: when the window comes within `min(width/2, 20px)` of any
 *    horizontal AND vertical screen edge simultaneously, the position is
 *    rewritten so the window sticks to that corner.
 *
 * 2. **Side selection**: emits [onPanelOnLeftChange] with `true` whenever the
 *    icon column window's center is past the screen's horizontal center,
 *    meaning the side panel should open to the LEFT of the icons.
 *
 * Uses the AWT window's actual pixel coordinates to avoid dp↔pixel conversion
 * issues across platforms (particularly macOS Retina where density ≠ 1).
 */
@Composable
fun FrameWindowScope.WindowSnap(
    windowState: WindowState,
    onPanelOnLeftChange: (Boolean) -> Unit,
) {
    val density = LocalDensity.current
    val awtWindow = window

    LaunchedEffect(Unit) {
        while (isActive) {
            delay(50) // 20 fps poll — smooth enough for snap

            val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
            val x = awtWindow.x
            val y = awtWindow.y
            val w = awtWindow.width
            val h = awtWindow.height

            if (w == 0 || h == 0) continue

            // Corner snap — all in AWT pixel coordinates.
            val threshold = (w / 2).coerceAtMost(20)
            val nearLeft = x - screen.x < threshold
            val nearRight = screen.x + screen.width - (x + w) < threshold
            val nearTop = y - screen.y < threshold
            val nearBottom = screen.y + screen.height - (y + h) < threshold

            if ((nearLeft || nearRight) && (nearTop || nearBottom)) {
                val snapX = if (nearLeft) screen.x else screen.x + screen.width - w
                val snapY = if (nearTop) screen.y else screen.y + screen.height - h
                if (x != snapX || y != snapY) {
                    // Update both AWT and Compose state.
                    awtWindow.setLocation(snapX, snapY)
                    windowState.position = WindowPosition(
                        (snapX / density.density).dp,
                        (snapY / density.density).dp,
                    )
                }
            }

            // Which side should the panel open on?
            val centerX = x + w / 2
            val screenCenterX = screen.x + screen.width / 2
            onPanelOnLeftChange(centerX > screenCenterX)
        }
    }
}
