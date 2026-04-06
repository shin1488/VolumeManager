package com.shin.volumemanager.model

import androidx.compose.ui.graphics.ImageBitmap

data class AudioSession(
    val pid: Int,
    val displayName: String,
    val icon: ImageBitmap?,
    val volume: Float,
    val isMuted: Boolean
)
