package com.shin.volumemanager

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowState
import com.shin.volumemanager.audio.AudioManager
import com.shin.volumemanager.model.AudioSession

private const val ANIM_MS = 120
private val COLLAPSED_W = 60.dp
private val PANEL_W = 204.dp
private val PANEL_H = 40.dp          // same as ICON_BOX → lines up perfectly
private val PANEL_GAP = 8.dp
private val EXPANDED_W = COLLAPSED_W + PANEL_GAP + PANEL_W + 4.dp  // 276dp

private val ICON_BOX = 40.dp
private val ICON_IMG = 26.dp
private val ICON_DEF = 20.dp

// Y position of the first session icon within the window Box
// Surface top padding: 4dp · Column top padding: 6dp · Pin: 40dp · Divider: 5dp
private val ICONS_START_Y = 4.dp + 6.dp + 40.dp + 5.dp   // = 55dp
private val ICON_STEP = 42.dp                              // ICON_BOX(40) + Spacer(2)

sealed class PanelContent {
    data class VolumeSession(val pid: Int) : PanelContent()
    data object Opacity : PanelContent()
}

// Total height = surface pads(8) + col pads(12) + pin(40) + 2×dividers(10) + N×42 + opacity(40) + close(40)
// = 150 + N×42
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

    // Adjust height when session count changes
    LaunchedEffect(sessions.size) {
        windowState.size = DpSize(windowState.size.width, windowHeight(sessions.size))
    }

    // Expand/collapse window width when panel opens/closes
    LaunchedEffect(panelContent) {
        if (panelContent != null) {
            windowState.size = DpSize(EXPANDED_W, windowState.size.height)
            showPanel = true
        } else {
            showPanel = false
            kotlinx.coroutines.delay(ANIM_MS.toLong() + 30)
            windowState.size = DpSize(COLLAPSED_W, windowState.size.height)
        }
    }

    // Y coordinate for the floating panel (aligns to the clicked icon)
    val panelY: Dp = when (val c = panelContent) {
        is PanelContent.VolumeSession -> {
            val idx = sessions.indexOfFirst { it.pid == c.pid }
            if (idx >= 0) ICONS_START_Y + ICON_STEP * idx else ICONS_START_Y
        }
        PanelContent.Opacity -> ICONS_START_Y + ICON_STEP * sessions.size + 5.dp
        null -> ICONS_START_Y
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(Modifier.fillMaxSize().graphicsLayer(alpha = opacity)) {

            // ── LEFT: icon column ──────────────────────────────────
            Surface(
                modifier = Modifier.width(COLLAPSED_W).fillMaxHeight().padding(4.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Pin button
                    IconButton(
                        onClick = { onAlwaysOnTopChange(!isAlwaysOnTop) },
                        modifier = Modifier.size(ICON_BOX)
                    ) {
                        Icon(
                            Icons.Default.PushPin,
                            "Always on top",
                            tint = if (isAlwaysOnTop) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
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

                    // Opacity button
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
                            Icons.Default.Opacity, "Opacity", Modifier.size(ICON_DEF),
                            tint = if (isOpacitySelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    // Close button
                    IconButton(onClick = onClose, modifier = Modifier.size(ICON_BOX)) {
                        Icon(
                            Icons.Default.Close, "Close", Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            // ── RIGHT: compact floating panel aligned to the icon ──
            AnimatedVisibility(
                visible = showPanel,
                modifier = Modifier
                    .offset(x = COLLAPSED_W + PANEL_GAP, y = panelY)
                    .width(PANEL_W)
                    .height(PANEL_H),
                enter = expandHorizontally(tween(ANIM_MS), Alignment.Start) + fadeIn(tween(ANIM_MS)),
                exit = shrinkHorizontally(tween(ANIM_MS), Alignment.Start) + fadeOut(tween(ANIM_MS))
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

@Composable
private fun CompactVolumePanel(
    session: AudioSession,
    onVolumeChange: (Float) -> Unit,
    onMuteToggle: () -> Unit
) {
    var localVolume by remember(session.pid) { mutableStateOf(session.volume) }
    var isDragging by remember { mutableStateOf(false) }

    LaunchedEffect(session.volume) {
        if (!isDragging) localVolume = session.volume
    }

    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Mute toggle icon
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .clickable(onClick = onMuteToggle),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (session.isMuted) Icons.AutoMirrored.Filled.VolumeOff
                else Icons.AutoMirrored.Filled.VolumeUp,
                null,
                Modifier.size(14.dp),
                tint = if (session.isMuted) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary
            )
        }

        Spacer(Modifier.width(5.dp))

        // Process name
        Text(
            session.displayName,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(
                alpha = if (session.isMuted) 0.4f else 0.75f
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(50.dp)
        )

        // Volume %
        Text(
            "${(localVolume * 100).toInt()}%",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(
                alpha = if (session.isMuted) 0.4f else 1f
            ),
            textAlign = TextAlign.End,
            modifier = Modifier.width(26.dp)
        )

        Spacer(Modifier.width(4.dp))

        // Slider
        Slider(
            value = localVolume,
            onValueChange = {
                localVolume = it
                isDragging = true
                onVolumeChange(it)
            },
            onValueChangeFinished = { isDragging = false },
            modifier = Modifier.weight(1f),
            enabled = !session.isMuted,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                disabledThumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                disabledActiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
            )
        )
    }
}

// ── Compact opacity panel ──────────────────────────────────────────

@Composable
private fun CompactOpacityPanel(opacity: Float, onOpacityChange: (Float) -> Unit) {
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
            "Opacity",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(40.dp)
        )

        Text(
            "${(opacity * 100).toInt()}%",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.width(26.dp)
        )

        Spacer(Modifier.width(4.dp))

        Slider(
            value = opacity,
            onValueChange = onOpacityChange,
            valueRange = 0.2f..1f,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                activeTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                inactiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
            )
        )
    }
}
