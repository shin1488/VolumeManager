package com.shin.volumemanager.model

sealed class PanelContent {
    data class VolumeSession(val pid: Int) : PanelContent()
    data object Opacity : PanelContent()
}
