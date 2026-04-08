package com.shin.volumemanager.ui.snap

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.snapshotFlow
import java.awt.GraphicsEnvironment

/**
 * Drives two pieces of behavior whenever [windowState] changes:
 *
 * 1. **Corner snap**: when the window comes within `min(width/2, 20px)` of any
 *    horizontal AND vertical screen edge simultaneously, the position is
 *    rewritten so the window sticks to that corner.
 *
 * 2. **Side selection**: emits [onPanelOnLeftChange] with `true` whenever the
 *    icon column window's center is past the screen's horizontal center,
 *    meaning the side panel should open to the LEFT of the icons. The
 *    callback is invoked on every position update; the receiver is expected
 *    to no-op when the value is unchanged (StateFlow does this for free).
 *
 * Compared with the previous in-App snap effect, this is much simpler because
 * the icon column window never resizes horizontally — there is no special
 * "panel open" branch to keep the icon anchored.
 */
@Composable
fun WindowSnap(
    windowState: WindowState,
    onPanelOnLeftChange: (Boolean) -> Unit,
) {
    val density = LocalDensity.current
    val screen = remember { GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds }

    LaunchedEffect(Unit) {
        snapshotFlow { windowState.position to windowState.size }
            .distinctUntilChanged()
            .collect { (pos, size) ->
                if (pos !is WindowPosition.Absolute) return@collect

                val scale = density.density
                val x = (pos.x.value * scale).toInt()
                val y = (pos.y.value * scale).toInt()
                val w = (size.width.value * scale).toInt()
                val h = (size.height.value * scale).toInt()

                // Corner snap
                val threshold = (w / 2).coerceAtMost((20 * scale).toInt())
                val nearLeft = x - screen.x < threshold
                val nearRight = screen.x + screen.width - (x + w) < threshold
                val nearTop = y - screen.y < threshold
                val nearBottom = screen.y + screen.height - (y + h) < threshold

                if ((nearLeft || nearRight) && (nearTop || nearBottom)) {
                    val snapX = if (nearLeft) screen.x else screen.x + screen.width - w
                    val snapY = if (nearTop) screen.y else screen.y + screen.height - h
                    if (x != snapX || y != snapY) {
                        windowState.position = WindowPosition(
                            (snapX / scale).dp,
                            (snapY / scale).dp
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
