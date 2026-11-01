package com.example.musicplayer

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.Visualizer
import android.net.Uri
import android.provider.OpenableColumns
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class AudioEngine private constructor(private val context: Context) {

    companion object {
        @Volatile private var instance: AudioEngine? = null

        fun initInstance(context: Context): AudioEngine =
            instance ?: synchronized(this) {
                instance ?: AudioEngine(context.applicationContext).also { instance = it }
            }

        fun getInstance(): AudioEngine? = instance

        fun releaseInstance() {
            instance?.release()
            instance = null
        }
    }

    private val _state = MutableStateFlow(PlayerUiState())
    val state = _state.asStateFlow()

    var playlist: List<TrackInfo> = emptyList()
        private set

    private var player: MediaPlayer? = null
    private var visualizer: Visualizer? = null
    internal var mediaSession: MediaSessionCompat? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var progressJob: Job? = null
    private var isSeeking: Boolean = false

    fun init() {
        if (mediaSession == null) {
            mediaSession = MediaSessionCompat(context, "RGBPlayerSession").apply {
                setCallback(object : MediaSessionCompat.Callback() {
                    override fun onPlay() {
                        val p = player ?: return
                        if (!p.isPlaying) togglePlay()
                    }
                    override fun onPause() {
                        val p = player ?: return
                        if (p.isPlaying) togglePlay()
                    }
                    override fun onSkipToNext() { next() }
                    override fun onSkipToPrevious() { prev() }
                    override fun onSeekTo(pos: Long) { seekTo((pos / 1000).toInt()) }
                })
                isActive = true
            }
        }
    }

    fun setPlaylist(newList: List<TrackInfo>) {
        playlist = newList
        _state.value = _state.value.copy(currentIndex = 0, isPlaying = false, showText = true)
        if (playlist.isNotEmpty()) loadCurrent()
    }

    fun fileNameOf(resId: Int): String {
        if (resId == 0) return ""
        return try {
            context.resources.getResourceEntryName(resId)
        } catch (_: Exception) { "" }
    }

    fun playAt(index: Int) {
        if (index !in playlist.indices) return
        _state.value = _state.value.copy(currentIndex = index, showText = true)
        loadCurrent()
        safeStart()
        _state.value = _state.value.copy(isPlaying = true)
        updatePlaybackState(true)
        scheduleHideText()
    }

    private fun loadCurrent() {
        if (playlist.isEmpty()) return
        val track = playlist.getOrNull(_state.value.currentIndex) ?: return

        player?.release()
        visualizer?.release()
        progressJob?.cancel()
        player = null
        visualizer = null

        player = try {
            if (track.uri != null) {
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    // Надежный способ открытия Uri в Android
                    setDataSource(context, track.uri)
                    prepare()
                }
            } else if (track.resId != 0) {
                MediaPlayer.create(context, track.resId)?.apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                }
            } else null
        } catch (e: Exception) {
            e.printStackTrace(); null
        }

        player?.setOnCompletionListener { next() }
        player?.let {
            setupVisualizer(it.audioSessionId)
            _state.value = _state.value.copy(
                duration = if (it.duration > 0) it.duration / 1000 else 0,
                currentPosition = 0
            )
            startProgressUpdater()
        }
        updateMetadata(track)
        updatePlaybackState(false)
    }

    private fun startProgressUpdater() {
        progressJob?.cancel()
        progressJob = scope.launch {
            // Ждем до 3 секунд, пока MediaPlayer просчитает длительность
            var waitCount = 0
            while (isActive && (player?.duration ?: 0) <= 0 && waitCount < 15) {
                delay(200)
                waitCount++
            }

            while (isActive) {
                val p = player
                if (p != null && !isSeeking) {
                    try {
                        val pos = p.currentPosition / 1000
                        val dur = p.duration / 1000

                        if (_state.value.currentPosition != pos || _state.value.duration != dur) {
                            _state.value = _state.value.copy(
                                currentPosition = pos,
                                duration = dur
                            )
                        }
                    } catch (_: Exception) {}
                }
                delay(200)
            }
        }
    }

    private fun updateMetadata(track: TrackInfo) {
        val title = track.displayTitle
            ?: if (track.uri != null) queryDisplayName(track.uri) ?: "Unknown"
            else fileNameOf(track.resId).ifEmpty { "Unknown" }

        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.displayArtist ?: "")
            .build()
        mediaSession?.setMetadata(metadata)
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) c.getString(idx) else null
                    } else null
                }
        } catch (_: Exception) { null }
    }

    private fun updatePlaybackState(isPlaying: Boolean) {
        val s = if (isPlaying) PlaybackStateCompat.STATE_PLAYING
        else PlaybackStateCompat.STATE_PAUSED
        val pb = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO
            )
            .setState(s, player?.currentPosition?.toLong() ?: 0L, 1f)
            .build()
        mediaSession?.setPlaybackState(pb)
    }

    private fun setupVisualizer(sessionId: Int) {
        try {
            visualizer = Visualizer(sessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, bytes: ByteArray?, rate: Int) {
                        if (bytes == null) return
                        val floats = FloatArray(bytes.size) {
                            ((bytes[it].toInt() and 0xFF) - 128) / 128f
                        }
                        _state.value = _state.value.copy(fft = floats)
                    }
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) {
                        if (fft == null) return
                        val floats = FloatArray(fft.size / 2) { i ->
                            val re = fft[i * 2].toFloat()
                            val im = if (i * 2 + 1 < fft.size) fft[i * 2 + 1].toFloat() else 0f
                            kotlin.math.sqrt(re * re + im * im) / 128f
                        }
                        _state.value = _state.value.copy(fft = floats)
                    }
                }, Visualizer.getMaxCaptureRate() / 2, true, true)
                enabled = true
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun safeStart() {
        val p = player ?: return
        try {
            if (!p.isPlaying) p.start()
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
            _state.value = _state.value.copy(isPlaying = false)
            updatePlaybackState(false)
        } else {
            safeStart()
            _state.value = _state.value.copy(isPlaying = true, showText = true)
            updatePlaybackState(true)
            scheduleHideText()
        }
    }

    private fun scheduleHideText() {
        scope.launch {
            delay(3000)
            if (_state.value.isPlaying) _state.value = _state.value.copy(showText = false)
        }
    }

    fun next() {
        if (playlist.isEmpty()) return
        _state.value = _state.value.copy(
            currentIndex = (_state.value.currentIndex + 1) % playlist.size
        )
        loadCurrent()
        safeStart()
        _state.value = _state.value.copy(isPlaying = true, showText = true)
        updatePlaybackState(true)
        scheduleHideText()
    }

    fun prev() {
        if (playlist.isEmpty()) return
        _state.value = _state.value.copy(
            currentIndex = (_state.value.currentIndex - 1 + playlist.size) % playlist.size
        )
        loadCurrent()
        safeStart()
        _state.value = _state.value.copy(isPlaying = true, showText = true)
        updatePlaybackState(true)
        scheduleHideText()
    }

    fun toggleMode() {
        val next = when (_state.value.mode) {
            VisualMode.RGB -> VisualMode.BARS
            VisualMode.BARS -> VisualMode.OFF
            VisualMode.OFF -> VisualMode.RGB
        }
        _state.value = _state.value.copy(mode = next)
    }

    fun toggleStrobe() {
        _state.value = _state.value.copy(
            isStrobeEnabled = !_state.value.isStrobeEnabled
        )
    }

    fun showText() { _state.value = _state.value.copy(showText = true) }

    // ====== SEEK ======
    fun onSeekStarted() { isSeeking = true }

    fun seekTo(seconds: Int) {
        player?.let { p ->
            try { p.seekTo(seconds * 1000) } catch (_: Exception) {}
            _state.value = _state.value.copy(currentPosition = seconds)
        }
    }

    fun onSeekFinished() {
        isSeeking = false
        player?.let { p ->
            try {
                _state.value = _state.value.copy(currentPosition = p.currentPosition / 1000)
            } catch (_: Exception) {}
        }
    }

    fun release() {
        progressJob?.cancel()
        mediaSession?.isActive = false
        mediaSession?.release(); mediaSession = null
        visualizer?.release(); visualizer = null
        player?.release(); player = null
        scope.cancel()
    }
}