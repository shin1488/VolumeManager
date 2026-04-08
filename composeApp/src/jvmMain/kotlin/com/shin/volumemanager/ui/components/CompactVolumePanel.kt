package com.shin.volumemanager.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shin.volumemanager.model.AudioSession

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactVolumePanel(
    session: AudioSession,
    onVolumeChange: (Float) -> Unit,
    onMuteToggle: () -> Unit,
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
