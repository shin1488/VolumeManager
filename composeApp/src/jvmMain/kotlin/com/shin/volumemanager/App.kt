package com.shin.volumemanager

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import com.shin.volumemanager.audio.AudioManager
import java.awt.GraphicsEnvironment
import com.shin.volumemanager.model.AudioSession

private const val ANIM_MS = 120
private val COLLAPSED_W = 60.dp
private val PANEL_W = 204.dp
private val PANEL_H = 40.dp
private val PANEL_GAP = 8.dp
private val EXPANDED_W = COLLAPSED_W + PANEL_GAP + PANEL_W + 4.dp

private val ICON_BOX = 40.dp
private val ICON_IMG = 22.dp
private val ICON_DEF = 22.dp

// Y positions within the window Box
// Surface padding 4dp · Column top padding 6dp · Pin 40dp · Opacity 40dp · Divider 5dp
private val OPACITY_Y    = 4.dp + 6.dp + 40.dp               // = 50dp  (right below pin)
private val ICONS_START_Y = OPACITY_Y + 40.dp + 5.dp         // = 95dp  (after opacity + divider)
private val ICON_STEP    = 42.dp                              // ICON_BOX(40) + Spacer(2)

sealed class PanelContent {
    data class VolumeSession(val pid: Int) : PanelContent()
    data object Opacity : PanelContent()
}

// surface pads(8) + col pads(12) + pin(40) + opacity(40) + 2×dividers(10) + close(40) = 150
// + sessions N×42
private fun windowHeight(sessionCount: Int): Dp =
    maxOf(200.dp, (150 + sessionCount * 42).dp)

@Composable
fun App(
    audioManager: AudioManager,
    windowState: WindowState,
    isAlwaysOnTop: Boolean,
    onAlwaysOnTopChange: (Boolean) -> Unit,
    onClose: () -> Unit
) {
    val sessions by audioManager.sessions.collectAsState()
    var panelContent by remember { mutableStateOf<PanelContent?>(null) }
    var showPanel by remember { mutableStateOf(false) }
    var opacity by remember { mutableStateOf(0.95f) }
    var panelOnLeft by remember { mutableStateOf(false) }
    var isPanelExpanded by remember { mutableStateOf(false) }
    val screen = remember { GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds }
    val density = androidx.compose.ui.platform.LocalDensity.current

    // Snap to screen corner while dragging near a corner (immediate, during drag)
    // All math is done in physical pixels to avoid DPI-scaling mismatches.
    LaunchedEffect(Unit) {
        snapshotFlow { Triple(windowState.position, windowState.size, panelContent) }
            .collect { (pos, size, content) ->
                if (content != null || pos !is WindowPosition.Absolute) return@collect
                val scale = density.density
                // Convert dp → physical pixels
                val x = (pos.x.value * scale).toInt()
                val y = (pos.y.value * scale).toInt()
                val w = (size.width.value * scale).toInt()
                val h = (size.height.value * scale).toInt()
                // Threshold = half the window width (≈30px at 100% on this app)
                val threshold = (w / 2).coerceAtMost((20 * scale).toInt())
                val nearLeft   = x - screen.x          < threshold
                val nearRight  = (screen.x + screen.width)  - (x + w) < threshold
                val nearTop    = y - screen.y           < threshold
                val nearBottom = (screen.y + screen.height) - (y + h) < threshold
                if ((nearLeft || nearRight) && (nearTop || nearBottom)) {
                    val snapXpx = if (nearLeft) screen.x else screen.x + screen.width - w
                    val snapYpx = if (nearTop)  screen.y else screen.y + screen.height - h
                    if (x != snapXpx || y != snapYpx)
                        windowState.position = WindowPosition(
                            (snapXpx / scale).dp,
                            (snapYpx / scale).dp
                        )
                }
            }
    }

    // Adjust height when session count changes
    LaunchedEffect(sessions.size) {
        windowState.size = DpSize(windowState.size.width, windowHeight(sessions.size))
    }

    // Close panel when the selected session exits
    LaunchedEffect(sessions) {
        val c = panelContent
        if (c is PanelContent.VolumeSession && sessions.none { it.pid == c.pid }) {
            panelContent = null
        }
    }

    // Expand/collapse window width; shift left when window is on the right side of the screen
    val panelShiftW = PANEL_W + PANEL_GAP + 4.dp
    LaunchedEffect(panelContent) {
        if (panelContent != null) {
            // Only compute side and shift when going from closed → open.
            // If already expanded (e.g. switching sessions), skip — otherwise
            // the window shifts left again on every session switch.
            if (!isPanelExpanded) {
                val pos = windowState.position
                if (pos is WindowPosition.Absolute) {
                    val windowCenterX = pos.x.value + windowState.size.width.value / 2f
                    val screenCenterX = screen.x + screen.width / 2f
                    panelOnLeft = windowCenterX > screenCenterX
                } else {
                    panelOnLeft = false
                }
                if (panelOnLeft) {
                    val pos2 = windowState.position
                    if (pos2 is WindowPosition.Absolute)
                        windowState.position = WindowPosition(pos2.x - panelShiftW, pos2.y)
                }
                windowState.size = DpSize(EXPANDED_W, windowState.size.height)
                isPanelExpanded = true
            }
            showPanel = true
        } else {
            showPanel = false
            kotlinx.coroutines.delay(ANIM_MS.toLong() + 30)
            if (panelOnLeft) {
                val pos = windowState.position
                if (pos is WindowPosition.Absolute)
                    windowState.position = WindowPosition(pos.x + panelShiftW, pos.y)
            }
            windowState.size = DpSize(COLLAPSED_W, windowState.size.height)
            panelOnLeft = false
            isPanelExpanded = false
        }
    }

    // Y coordinate for the floating panel
    val panelY: Dp = when (val c = panelContent) {
        is PanelContent.VolumeSession -> {
            val idx = sessions.indexOfFirst { it.pid == c.pid }
            if (idx >= 0) ICONS_START_Y + ICON_STEP * idx else ICONS_START_Y
        }
        PanelContent.Opacity -> OPACITY_Y
        null -> ICONS_START_Y
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(Modifier.fillMaxSize().graphicsLayer(alpha = opacity)) {

            // ── Icon column (left normally, right when panel is on left) ──
            Surface(
                modifier = Modifier
                    .width(COLLAPSED_W)
                    .fillMaxHeight()
                    .padding(4.dp)
                    .align(if (panelOnLeft) Alignment.TopEnd else Alignment.TopStart),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Pin button
                    Box(
                        modifier = Modifier
                            .size(ICON_BOX)
                            .clip(CircleShape)
                            .background(
                                if (isAlwaysOnTop) MaterialTheme.colorScheme.primary.copy(0.15f)
                                else Color.Transparent
                            )
                            .clickable { onAlwaysOnTopChange(!isAlwaysOnTop) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.PushPin, "Always on top",
                            tint = if (isAlwaysOnTop) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(ICON_DEF)
                        )
                    }

                    // Opacity button — grouped with pin
                    val isOpacitySelected = panelContent is PanelContent.Opacity
                    Box(
                        modifier = Modifier
                            .size(ICON_BOX)
                            .clip(CircleShape)
                            .background(
                                if (isOpacitySelected) MaterialTheme.colorScheme.primary.copy(0.15f)
                                else Color.Transparent
                            )
                            .clickable {
                                panelContent = if (isOpacitySelected) null else PanelContent.Opacity
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Opacity, "투명도", Modifier.size(ICON_DEF),
                            tint = if (isOpacitySelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    ColumnDivider()

                    // Session icons
                    sessions.forEach { session ->
                        key(session.pid) {
                            val isSelected = panelContent is PanelContent.VolumeSession &&
                                    (panelContent as PanelContent.VolumeSession).pid == session.pid
                            SessionIconButton(
                                session = session,
                                isSelected = isSelected,
                                onClick = {
                                    panelContent = if (isSelected) null
                                    else PanelContent.VolumeSession(session.pid)
                                }
                            )
                        }
                    }

                    if (sessions.isEmpty()) Spacer(Modifier.height(ICON_BOX))

                    ColumnDivider()

                    Spacer(Modifier.weight(1f))

                    // Close button
                    Box(
                        modifier = Modifier
                            .size(ICON_BOX)
                            .clip(CircleShape)
                            .clickable { onClose() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Close, "Close", Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            // ── Compact floating panel (right normally, left when panelOnLeft) ──
            AnimatedVisibility(
                visible = showPanel,
                modifier = Modifier
                    .offset(x = if (panelOnLeft) 4.dp else COLLAPSED_W + PANEL_GAP, y = panelY)
                    .width(PANEL_W)
                    .height(PANEL_H),
                enter = expandHorizontally(tween(ANIM_MS), if (panelOnLeft) Alignment.End else Alignment.Start) + fadeIn(tween(ANIM_MS)),
                exit = shrinkHorizontally(tween(ANIM_MS), if (panelOnLeft) Alignment.End else Alignment.Start) + fadeOut(tween(ANIM_MS))
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 4.dp
                ) {
                    when (val c = panelContent) {
                        is PanelContent.VolumeSession ->
                            sessions.find { it.pid == c.pid }?.let { session ->
                                CompactVolumePanel(
                                    session = session,
                                    onVolumeChange = { audioManager.setVolume(session.pid, it) },
                                    onMuteToggle = { audioManager.setMute(session.pid, !session.isMuted) }
                                )
                            }
                        PanelContent.Opacity ->
                            CompactOpacityPanel(opacity, onOpacityChange = { opacity = it })
                        null -> {}
                    }
                }
            }
        }
    }
}

// ── Shared composables ─────────────────────────────────────────────

@Composable
private fun ColumnDivider() {
    Spacer(Modifier.height(2.dp))
    Box(
        Modifier
            .padding(horizontal = 8.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    )
    Spacer(Modifier.height(2.dp))
}

@Composable
private fun SessionIconButton(
    session: AudioSession,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(ICON_BOX)
            .clip(CircleShape)
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                else Color.Transparent
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (session.icon != null) {
            Image(
                bitmap = session.icon,
                contentDescription = session.displayName,
                modifier = Modifier.size(ICON_IMG),
                contentScale = ContentScale.Fit,
                alpha = if (session.isMuted) 0.35f else 1f
            )
        } else {
            Icon(
                imageVector = if (session.pid == 0) Icons.AutoMirrored.Filled.VolumeUp
                else Icons.Default.MusicNote,
                contentDescription = session.displayName,
                modifier = Modifier.size(ICON_DEF),
                tint = if (session.isMuted)
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (session.isMuted) {
            Icon(
                Icons.AutoMirrored.Filled.VolumeOff, "Muted",
                Modifier.size(11.dp).align(Alignment.BottomEnd),
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
    Spacer(Modifier.height(2.dp))
}

// ── Compact volume panel ───────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactVolumePanel(
    session: AudioSession,
    onVolumeChange: (Float) -> Unit,
    onMuteToggle: () -> Unit
) {
    var localVolume by remember(session.pid) { mutableStateOf(session.volume) }
    var isDragging by remember { mutableStateOf(false) }
    var isEditing by remember(session.pid) { mutableStateOf(false) }
    var hasFocus by remember(session.pid) { mutableStateOf(false) }
    var inputValue by remember(session.pid) { mutableStateOf(TextFieldValue("")) }
    val focusRequester = remember(session.pid) { FocusRequester() }

    LaunchedEffect(session.volume) {
        if (!isDragging) localVolume = session.volume
    }

    LaunchedEffect(isEditing) {
        if (isEditing) {
            hasFocus = false
            val text = "${(localVolume * 100).toInt()}"
            inputValue = TextFieldValue(text, selection = TextRange(0, text.length))
            kotlinx.coroutines.delay(50)
            runCatching { focusRequester.requestFocus() }
        }
    }

    fun commitInput() {
        val v = inputValue.text.toIntOrNull()?.coerceIn(0, 100)
        if (v != null) {
            localVolume = v / 100f
            onVolumeChange(v / 100f)
        }
        isEditing = false
        hasFocus = false
    }

    val activeColor = if (session.isMuted)
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
    else MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)

    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Mute toggle
        Box(
            modifier = Modifier.size(26.dp).clip(CircleShape).clickable(onClick = onMuteToggle),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (session.isMuted) Icons.AutoMirrored.Filled.VolumeOff
                else Icons.AutoMirrored.Filled.VolumeUp,
                null, Modifier.size(14.dp),
                tint = if (session.isMuted) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary
            )
        }

        Spacer(Modifier.width(4.dp))

        // Process name
        Text(
            session.displayName,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(
                alpha = if (session.isMuted) 0.4f else 0.75f
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(46.dp)
        )

        Spacer(Modifier.width(4.dp))

        // Volume % (tappable) ↔ inline input
        if (isEditing) {
            BasicTextField(
                value = inputValue,
                onValueChange = {
                    inputValue = it.copy(text = it.text.filter(Char::isDigit).take(3))
                },
                modifier = Modifier
                    .width(34.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged {
                        if (it.isFocused) hasFocus = true
                        else if (hasFocus) commitInput()
                    }
                    .onKeyEvent { event ->
                        when {
                            event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                                if (event.type == KeyEventType.KeyUp) commitInput()
                                true
                            }
                            event.type == KeyEventType.KeyDown && event.key == Key.Escape -> {
                                isEditing = false; hasFocus = false; true
                            }
                            else -> false
                        }
                    },
                textStyle = TextStyle(
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.End
                ),
                singleLine = true,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
            )
        } else {
            Text(
                "${(localVolume * 100).toInt()}%",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(
                    alpha = if (session.isMuted) 0.4f else 1f
                ),
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier
                    .width(34.dp)
                    .pointerInput(session.isMuted) {
                        detectTapGestures { if (!session.isMuted) isEditing = true }
                    }
            )
        }

        Spacer(Modifier.width(4.dp))

        // Slider
        Slider(
            value = localVolume,
            onValueChange = {
                localVolume = it
                isDragging = true
                isEditing = false
                onVolumeChange(it)
            },
            onValueChangeFinished = { isDragging = false },
            modifier = Modifier.weight(1f),
            enabled = !session.isMuted,
            thumb = {},
            track = { state ->
                val fraction = (state.value / state.valueRange.endInclusive).coerceIn(0f, 1f)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(inactiveColor)
                ) {
                    Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(activeColor))
                }
            }
        )
    }
}

// ── Compact opacity panel ──────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactOpacityPanel(opacity: Float, onOpacityChange: (Float) -> Unit) {
    var isEditing by remember { mutableStateOf(false) }
    var hasFocus by remember { mutableStateOf(false) }
    var inputValue by remember { mutableStateOf(TextFieldValue("")) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(isEditing) {
        if (isEditing) {
            hasFocus = false
            val text = "${(opacity * 100).toInt()}"
            inputValue = TextFieldValue(text, selection = TextRange(0, text.length))
            kotlinx.coroutines.delay(50)
            runCatching { focusRequester.requestFocus() }
        }
    }

    fun commitInput() {
        val v = inputValue.text.toIntOrNull()?.coerceIn(20, 100)
        if (v != null) onOpacityChange(v / 100f)
        isEditing = false
        hasFocus = false
    }

    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Opacity, null, Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.width(5.dp))

        Text(
            "투명도",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(34.dp)
        )

        if (isEditing) {
            BasicTextField(
                value = inputValue,
                onValueChange = {
                    inputValue = it.copy(text = it.text.filter(Char::isDigit).take(3))
                },
                modifier = Modifier
                    .width(34.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged {
                        if (it.isFocused) hasFocus = true
                        else if (hasFocus) commitInput()
                    }
                    .onKeyEvent { event ->
                        when {
                            event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                                if (event.type == KeyEventType.KeyUp) commitInput()
                                true
                            }
                            event.type == KeyEventType.KeyDown && event.key == Key.Escape -> {
                                isEditing = false; hasFocus = false; true
                            }
                            else -> false
                        }
                    },
                textStyle = TextStyle(
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.End
                ),
                singleLine = true,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
            )
        } else {
            Text(
                "${(opacity * 100).toInt()}%",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier
                    .width(34.dp)
                    .pointerInput(Unit) {
                        detectTapGestures { isEditing = true }
                    }
            )
        }

        Spacer(Modifier.width(4.dp))

        val activeColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)

        Slider(
            value = opacity,
            onValueChange = onOpacityChange,
            valueRange = 0.2f..1f,
            modifier = Modifier.weight(1f),
            thumb = {},
            track = { state ->
                val fraction = ((state.value - state.valueRange.start) /
                        (state.valueRange.endInclusive - state.valueRange.start)).coerceIn(0f, 1f)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(inactiveColor)
                ) {
                    Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(activeColor))
                }
            }
        )
    }
}
