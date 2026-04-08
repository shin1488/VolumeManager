package com.shin.volumemanager.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.shin.volumemanager.model.AudioSession
import com.shin.volumemanager.ui.ICON_BOX
import com.shin.volumemanager.ui.ICON_DEF
import com.shin.volumemanager.ui.ICON_IMG

@Composable
fun SessionIconButton(
    session: AudioSession,
    isSelected: Boolean,
    onClick: () -> Unit,
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
