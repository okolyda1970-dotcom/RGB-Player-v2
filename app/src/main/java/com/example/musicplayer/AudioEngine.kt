package com.example.musicplayer

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.Visualizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AudioEngine(private val context: Context) {

    private val _state = MutableStateFlow(PlayerUiState())
    val state = _state.asStateFlow()

    var playlist: List<TrackInfo> = emptyList()
        private set

    private var player: MediaPlayer? = null
    private var visualizer: Visualizer? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var hideTextJob: Job? = null
    private var progressJob: Job? = null

    // Переменная для защиты от конфликтов при перемотке
    private var isSeeking = false

    fun setPlaylist(tracks: List<TrackInfo>) {
        playlist = tracks
        _state.value = _state.value.copy(currentIndex = 0)
        loadCurrent()
    }

    fun init() {
        if (playlist.isNotEmpty()) loadCurrent()
    }

    private fun loadCurrent() {
        if (playlist.isEmpty()) return
        val track = playlist[_state.value.currentIndex]

        // Освобождаем старый плеер и визуализатор
        visualizer?.release()
        visualizer = null
        player?.release()
        player = null

        val mp = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            try {
                if (track.uri != null) {
                    setDataSource(context, track.uri!!)
                } else if (track.resId != 0) {
                    val afd = context.resources.openRawResourceFd(track.resId)
                        ?: throw IllegalStateException("res not found")
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    afd.close()
                } else {
                    throw IllegalStateException("no source")
                }
                prepare()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            setOnCompletionListener { next() }
        }
        player = mp
        setupVisualizer(mp.audioSessionId)

        // Запускаем обновление прогресс-бара
        startProgressUpdates()
    }

    private fun setupVisualizer(sessionId: Int) {
        try {
            visualizer = Visualizer(sessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(
                            v: Visualizer?, bytes: ByteArray?, rate: Int
                        ) {}

                        override fun onFftDataCapture(
                            v: Visualizer?, fft: ByteArray?, rate: Int
                        ) {
                            if (fft == null) return
                            // fft: пары (re, im), 8-битные, знаковые
                            val n = fft.size / 2
                            val out = FloatArray(n)
                            for (i in 0 until n) {
                                val re = fft[2 * i].toFloat()
                                val im = fft[2 * i + 1].toFloat()
                                out[i] = kotlin.math.sqrt(re * re + im * im) / 128f
                            }
                            _state.value = _state.value.copy(fft = out)
                        }
                    },
                    Visualizer.getMaxCaptureRate() / 2,
                    false,   // waveform не нужен
                    true     // fft нужен
                )
                enabled = true
            }
        } catch (_: Exception) {}
    }

    // Обновляем таймер каждые 500 мс
    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                player?.let { p ->
                    if (p.isPlaying && !isSeeking) {
                        _state.value = _state.value.copy(
                            currentPosition = p.currentPosition,
                            duration = p.duration.coerceAtLeast(0)
                        )
                    }
                }
                delay(500)
            }
        }
    }

    // Метод для перемотки ползунком
    fun seekTo(positionMs: Int) {
        player?.let { p ->
            val max = p.duration.coerceAtLeast(0)
            val pos = positionMs.coerceIn(0, max)
            p.seekTo(pos)
            _state.value = _state.value.copy(currentPosition = pos)
        }
    }

    // Метод для фиксации конца перемотки (вызывается, когда палец отпускает ползунок)
    fun onSeekFinished() {
        isSeeking = false
    }

    // Метод, когда палец только коснулся ползунка
    fun onSeekStarted() {
        isSeeking = true
    }

    fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
            _state.value = _state.value.copy(isPlaying = false)
        } else {
            p.start()
            _state.value = _state.value.copy(isPlaying = true, showText = true)
            scheduleHideText()
        }
    }

    fun next() {
        if (playlist.isEmpty()) return
        _state.value = _state.value.copy(
            currentIndex = (_state.value.currentIndex + 1) % playlist.size
        )
        loadCurrent()
        player?.start()
        _state.value = _state.value.copy(isPlaying = true, showText = true)
        scheduleHideText()
    }

    fun prev() {
        if (playlist.isEmpty()) return
        _state.value = _state.value.copy(
            currentIndex = (_state.value.currentIndex - 1 + playlist.size) % playlist.size
        )
        loadCurrent()
        player?.start()
        _state.value = _state.value.copy(isPlaying = true, showText = true)
        scheduleHideText()
    }

    fun playAt(index: Int) {
        if (index !in playlist.indices) return
        _state.value = _state.value.copy(
            currentIndex = index,
            isPlaying = true,
            showText = true
        )
        loadCurrent()
        player?.start()
        scheduleHideText()
    }

    fun toggleMode() {
        _state.value = _state.value.copy(
            mode = if (_state.value.mode == VisualMode.RGB) VisualMode.BARS else VisualMode.RGB
        )
    }

    fun showText() {
        _state.value = _state.value.copy(showText = true)
        scheduleHideText()
    }

    private fun scheduleHideText() {
        hideTextJob?.cancel()
        hideTextJob = scope.launch {
            delay(3000)
            if (_state.value.isPlaying) {
                _state.value = _state.value.copy(showText = false)
            }
        }
    }

    fun fileNameOf(resId: Int): String {
        if (resId == 0) return "—"
        return try {
            context.resources.getResourceEntryName(resId)
        } catch (_: Exception) {
            "—"
        }
    }

    fun release() {
        hideTextJob?.cancel()
        progressJob?.cancel()
        visualizer?.release()
        player?.release()
        scope.cancel()
    }
}