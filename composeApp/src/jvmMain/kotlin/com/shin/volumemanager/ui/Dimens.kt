package com.shin.volumemanager.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Animation
const val ANIM_MS = 120

// Icon column window
val COLLAPSED_W = 60.dp

// Side panel (DialogWindow)
val PANEL_W = 204.dp
val PANEL_H = 40.dp
val PANEL_GAP = 8.dp

// Icons
val ICON_BOX = 40.dp
val ICON_IMG = 22.dp
val ICON_DEF = 22.dp

// Y offsets within the icon column window. Used both for laying out the
// column itself and for positioning the panel dialog opposite each row.
//
// Layout: surface padding 4dp · column top padding 6dp · pin row 40dp
//       · opacity row 40dp · divider 5dp · session rows from there
val OPACITY_Y: Dp = 4.dp + 6.dp + 40.dp           // = 50dp  (right below pin)
val ICONS_START_Y: Dp = OPACITY_Y + 40.dp + 5.dp  // = 95dp  (after opacity + divider)
val ICON_STEP: Dp = 42.dp                         // ICON_BOX(40) + Spacer(2)

/**
 * Total icon column window height for [sessionCount] sessions.
 *
 * Math: surface pads(8) + col pads(12) + pin(40) + opacity(40) +
 * 2×dividers(10) + close(40) = 150, plus sessions × 42.
 * Floored at 200dp so the close button stays comfortably reachable when
 * there are no sessions.
 */
fun windowHeight(sessionCount: Int): Dp =
    maxOf(200.dp, (150 + sessionCount * 42).dp)
