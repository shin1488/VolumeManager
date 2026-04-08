package com.shin.volumemanager.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Opacity
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactOpacityPanel(opacity: Float, onOpacityChange: (Float) -> Unit) {
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
