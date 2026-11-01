package com.example.musicplayer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

@Composable
fun PlayerScreen(engine: AudioEngine, onPickFolder: () -> Unit) {
    val state by engine.state.collectAsState()
    val listState = rememberLazyListState()

    // --- ПЕРЕМЕННЫЕ ДЛЯ СТРОБОСКОПА ---
    var flashAlpha by remember { mutableStateOf(0f) }
    var lastStrobeTime by remember { mutableStateOf(0L) }
    var isStrobing by remember { mutableStateOf(false) }

    // Блокируем стробоскоп на 40 секунд при каждом нажатии Play или смене трека
    LaunchedEffect(state.isPlaying) {
        if (state.isPlaying) {
            lastStrobeTime = System.currentTimeMillis()
        }
    }

    // Эффект слежения за громкостью и запуска стробоскопа
    LaunchedEffect(state.isPlaying, state.fft, state.isStrobeEnabled) {
        if (!state.isPlaying || state.fft.isEmpty() || !state.isStrobeEnabled) {
            if (!state.isStrobeEnabled) flashAlpha = 0f
            return@LaunchedEffect
        }

        // Считаем громкость по низким частотам (басам)
        var bassEnergy = 0f
        val binsToCheck = minOf(10, state.fft.size)
        for (i in 0 until binsToCheck) {
            bassEnergy += state.fft[i]
        }
        if (binsToCheck > 0) bassEnergy /= binsToCheck

        val now = System.currentTimeMillis()

        if (bassEnergy > 0.35f && (now - lastStrobeTime) > 40000L && !isStrobing) {
            isStrobing = true
            lastStrobeTime = now
        }
    }

    // Эффект самой анимации мигания
    LaunchedEffect(isStrobing) {
        if (isStrobing) {
            repeat(10) {
                flashAlpha = 1f
                delay(40)
                flashAlpha = 0f
                delay(60)
            }
            isStrobing = false
        }
    }

    val showPlaylist = state.showText || state.mode == VisualMode.OFF

    LaunchedEffect(state.showText, state.currentIndex) {
        if (state.showText && engine.playlist.isNotEmpty()) {
            val targetIndex = (state.currentIndex - 2).coerceAtLeast(0)
            listState.scrollToItem(targetIndex)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { engine.showText() } }
    ) {
        // Визуализация
        when (state.mode) {
            VisualMode.RGB -> RgbBands(fft = state.fft, isPlaying = state.isPlaying)
            VisualMode.BARS -> BarsVisualizer(fft = state.fft)
            VisualMode.OFF -> { }
        }

        // ПЛЕЙЛИСТ
        if (showPlaylist && engine.playlist.isNotEmpty()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 40.dp)
                    .padding(bottom = 120.dp)
                    .padding(horizontal = 16.dp)
            ) {
                itemsIndexed(engine.playlist) { idx, track ->
                    val active = idx == state.currentIndex
                    val title = track.displayTitle ?: engine.fileNameOf(track.resId)
                    val lineText = if (track.displayArtist != null)
                        "$title — ${track.displayArtist}" else title

                    val prefix = if (active) "▶" else "♪"

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                engine.playAt(idx)
                                engine.showText()
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = prefix,
                            color = if (active) Color.White else Color.White.copy(alpha = 0.5f),
                            fontSize = 24.sp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = lineText,
                            color = if (active) Color.White else Color.White.copy(alpha = 0.5f),
                            fontSize = 17.sp
                        )
                    }
                }
            }
        }

        // НИЖНЯЯ ПАНЕЛЬ
        if (showPlaylist) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.85f))
                    .padding(vertical = 8.dp)
            ) {
                // ПОЛОСА ПРОКРУТКИ
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = formatTime(state.currentPosition), color = Color.White, fontSize = 11.sp)

                    Slider(
                        value = if (state.duration in 1..10800) (state.currentPosition.toFloat() / state.duration).coerceIn(0f, 1f) else 0f,
                        onValueChange = { value ->
                            if (state.duration in 1..10800) {
                                engine.onSeekStarted()
                                engine.seekTo((value * state.duration).toInt())
                            }
                        },
                        onValueChangeFinished = { engine.onSeekFinished() },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                        )
                    )

                    Text(text = formatTime(state.duration), color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                }

                Spacer(modifier = Modifier.height(4.dp))

                // КНОПКИ (Компактные)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val smallPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                    val btnColors = ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.15f),
                        contentColor = Color.White
                    )
                    val btnElevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)

                    Button(onClick = { engine.prev() }, contentPadding = smallPadding, colors = btnColors, elevation = btnElevation) {
                        Text("⏮", fontSize = 14.sp)
                    }

                    Button(onClick = { engine.togglePlay() }, contentPadding = smallPadding, colors = btnColors, elevation = btnElevation) {
                        Text(if (state.isPlaying) "⏸" else "▶", fontSize = 14.sp)
                    }

                    Button(onClick = { engine.next() }, contentPadding = smallPadding, colors = btnColors, elevation = btnElevation) {
                        Text("⏭", fontSize = 14.sp)
                    }

                    Button(onClick = { engine.toggleMode() }, contentPadding = smallPadding, colors = btnColors, elevation = btnElevation) {
                        Text(
                            when (state.mode) {
                                VisualMode.RGB -> "RGB"
                                VisualMode.BARS -> "BRS"
                                VisualMode.OFF -> "---"
                            },
                            fontSize = 11.sp
                        )
                    }

                    Button(
                        onClick = { engine.toggleStrobe() },
                        contentPadding = smallPadding,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (state.isStrobeEnabled) Color.White.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f),
                            contentColor = if (state.isStrobeEnabled) Color.White else Color.White.copy(alpha = 0.3f)
                        ),
                        elevation = btnElevation
                    ) {
                        Text("⚡", fontSize = 14.sp)
                    }

                    Button(onClick = onPickFolder, contentPadding = smallPadding, colors = btnColors, elevation = btnElevation) {
                        Text("Папка", fontSize = 11.sp)
                    }
                }
            }
        }

        // БЕЛЫЙ СЛОЙ СТРОБОСКОПА
        if (flashAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = flashAlpha))
            )
        }
    }
}

private fun formatTime(sec: Int): String {
    if (sec <= 0) return "0:00"
    val min = sec / 60
    val s = sec % 60
    return "%d:%02d".format(min, s)
}

@Composable
fun RgbBands(fft: FloatArray, isPlaying: Boolean) {
    val rBin = 3
    val gBin = 23
    val bBin = 46

    val rTarget = fft.getOrElse(rBin) { 0f }.coerceIn(0f, 1f)
    val gTarget = fft.getOrElse(gBin) { 0f }.coerceIn(0f, 1f)
    val bTarget = fft.getOrElse(bBin) { 0f }.coerceIn(0f, 1f)

    var rAmp by remember { mutableStateOf(0f) }
    var gAmp by remember { mutableStateOf(0f) }
    var bAmp by remember { mutableStateOf(0f) }

    var rGain by remember { mutableStateOf(1.0f) }
    var gGain by remember { mutableStateOf(6.0f) }
    var bGain by remember { mutableStateOf(7.0f) }

    val K = 0.20f
    rAmp += (rTarget - rAmp) * K
    gAmp += (gTarget - gAmp) * K
    bAmp += (bTarget - bAmp) * K

    var rShow = (rAmp * rGain).coerceIn(0f, 1f)
    var gShow = (gAmp * gGain).coerceIn(0f, 1f)
    var bShow = (bAmp * bGain).coerceIn(0f, 1f)

    if (isPlaying) {
        if (rShow > 0.85f) rGain *= 0.995f else if (rShow < 0.25f) rGain *= 1.005f
        rGain = rGain.coerceIn(0.3f, 4f)
        if (gShow > 0.85f) gGain *= 0.995f else if (gShow < 0.25f) gGain *= 1.005f
        gGain = gGain.coerceIn(1f, 20f)
        if (bShow > 0.85f) bGain *= 0.995f else if (bShow < 0.25f) bGain *= 1.005f
        bGain = bGain.coerceIn(1f, 30f)
    }

    rShow = (rAmp * rGain).coerceIn(0f, 1f)
    gShow = (gAmp * gGain).coerceIn(0f, 1f)
    bShow = (bAmp * bGain).coerceIn(0f, 1f)

    val idle = 0.15f
    val rDraw = if (isPlaying) rShow.coerceAtLeast(idle) else idle
    val gDraw = if (isPlaying) gShow.coerceAtLeast(idle) else idle
    val bDraw = if (isPlaying) bShow.coerceAtLeast(idle) else idle

    Canvas(modifier = Modifier.fillMaxSize()) {
        val yellowAlpha = (rDraw + gDraw) / 2f
        val cyanAlpha = (gDraw + bDraw) / 2f

        val gradient = Brush.verticalGradient(
            0.00f to Color.Red.copy(alpha = rDraw),
            0.15f to Color.Red.copy(alpha = rDraw),
            0.30f to Color.Yellow.copy(alpha = yellowAlpha),
            0.45f to Color.Green.copy(alpha = gDraw),
            0.55f to Color.Green.copy(alpha = gDraw),
            0.70f to Color.Cyan.copy(alpha = cyanAlpha),
            0.85f to Color.Blue.copy(alpha = bDraw),
            1.00f to Color.Blue.copy(alpha = bDraw)
        )

        drawRect(brush = gradient)
    }
}

@Composable
fun BarsVisualizer(fft: FloatArray) {
    val bars = 64
    val floor = 0.05f
    val ceiling = 0.85f
    val K = 0.20f
    val amps = remember { FloatArray(bars) }

    if (fft.isNotEmpty()) {
        val lowIdx = 2
        val highIdx = fft.size
        val logRange = ln(highIdx.toFloat() / lowIdx)
        val targets = FloatArray(bars)
        var prevIdx = lowIdx
        for (i in 0 until bars) {
            val t = (i + 1).toFloat() / bars
            val nextIdx = (lowIdx * exp(t * logRange)).toInt()
                .coerceIn(prevIdx + 1, highIdx)
            var sum = 0f; var count = 0
            for (j in prevIdx until nextIdx) { sum += fft[j]; count++ }
            targets[i] = if (count > 0) sum / count else 0f
            prevIdx = nextIdx
        }
        val smoothed = FloatArray(bars) { i ->
            val l = targets[(i - 1).coerceAtLeast(0)]
            val c = targets[i]
            val r = targets[(i + 1).coerceAtMost(bars - 1)]
            (l + c * 2f + r) / 4f
        }
        for (i in 0 until bars) {
            val freqWeight = 0.6f + 1.4f * (i.toFloat() / bars)
            val shaped = sqrt(smoothed[i]) * freqWeight
            amps[i] += (shaped - amps[i]) * K
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        if (fft.isEmpty()) return@Canvas
        val barWidth = size.width / bars
        for (i in 0 until bars) {
            val amp = (floor + amps[i] * ceiling).coerceIn(floor, 1f)
            val barHeight = amp * size.height
            val color = Color.hsv((i * 360f / bars), 0.85f, 1f)
            drawRect(color, Offset(i * barWidth, size.height - barHeight),
                Size(barWidth, barHeight))
        }
    }
}