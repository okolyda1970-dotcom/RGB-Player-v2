package com.example.musicplayer

import android.net.Uri

enum class VisualMode { BARS, RGB }

data class TrackInfo(
    val title: String = "",
    val artist: String = "",
    val resId: Int = 0,
    val uri: Uri? = null
) {
    val displayTitle: String?
        get() = title.trim().ifEmpty { null }

    val displayArtist: String?
        get() = artist.trim().ifEmpty { null }
}

data class PlayerUiState(
    val isPlaying: Boolean = false,
    val currentIndex: Int = 0,
    val fft: FloatArray = FloatArray(0),
    val showText: Boolean = true,
    val mode: VisualMode = VisualMode.RGB,

    // НОВЫЕ ПОЛЯ:
    val currentPosition: Int = 0, // текущая секунда
    val duration: Int = 0         // всего секунд
)