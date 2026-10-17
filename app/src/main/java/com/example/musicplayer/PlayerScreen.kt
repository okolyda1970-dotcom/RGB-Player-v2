package com.example.musicplayer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

@Composable
fun PlayerScreen(engine: AudioEngine, onPickFolder: () -> Unit) {
    val state by engine.state.collectAsState()
    val listState = rememberLazyListState()

    // Эффект прокрутки к текущему треку при открытии списка
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
        if (state.mode == VisualMode.RGB) {
            RgbBands(fft = state.fft, isPlaying = state.isPlaying)
        } else {
            BarsVisualizer(fft = state.fft)
        }

        // ПЛЕЙЛИСТ НА ВЕСЬ ЭКРАН
        if (state.showText && engine.playlist.isNotEmpty()) {
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

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                engine.playAt(idx)
                                engine.showText()
                            }
                            .padding(vertical = 8.dp)
                    ) {
                        Text(
                            text = "${if (active) "▶ " else "   "}$lineText",
                            color = if (active) Color.White else Color.White.copy(alpha = 0.5f),
                            fontSize = 17.sp
                        )
                    }
                }
            }
        }

        // НИЖНЯЯ ПАНЕЛЬ (ПРОГРЕСС-БАР + КНОПКИ)
        if (state.showText) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.85f))
                    .padding(vertical = 8.dp)
            ) {
                // --- ПОЛОСА ПРОКРУТКИ ТРЕКА ---
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val curStr = formatTime(state.currentPosition)
                    val durStr = formatTime(state.duration)

                    Text(text = curStr, color = Color.White, fontSize = 11.sp)

                    Slider(
                        value = if (state.duration > 0) state.currentPosition.toFloat() / state.duration else 0f,
                        onValueChange = { value ->
                            engine.onSeekStarted()
                            val targetMs = (value * state.duration).toInt()
                            engine.seekTo(targetMs)
                        },
                        onValueChangeFinished = {
                            engine.onSeekFinished()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                        )
                    )

                    Text(text = durStr, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                }

                Spacer(modifier = Modifier.height(4.dp))

                // КНОПКИ
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { engine.prev() },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White.copy(alpha = 0.15f),
                            contentColor = Color.White
                        ),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                    ) { Text("⏮", fontSize = 16.sp) }

                    Button(
                        onClick = { engine.togglePlay() },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White.copy(alpha = 0.15f),
                            contentColor = Color.White
                        ),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                    ) { Text(if (state.isPlaying) "⏸" else "▶", fontSize = 16.sp) }

                    Button(
                        onClick = { engine.next() },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White.copy(alpha = 0.15f),
                            contentColor = Color.White
                        ),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                    ) { Text("⏭", fontSize = 16.sp) }

                    Button(
                        onClick = { engine.toggleMode() },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White.copy(alpha = 0.15f),
                            contentColor = Color.White
                        ),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                    ) {
                        Text(if (state.mode == VisualMode.RGB) "RGB" else "BARS", fontSize = 11.sp)
                    }

                    Button(
                        onClick = onPickFolder,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White.copy(alpha = 0.15f),
                            contentColor = Color.White
                        ),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                    ) { Text("Папка", fontSize = 11.sp) }
                }
            }
        }
    }
}

private fun formatTime(ms: Int): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}

// ==========================================
// ФУНКЦИИ ВИЗУАЛИЗАЦИИ
// ==========================================

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

    val idle = 0.03f
    val rDraw = if (isPlaying) rShow.coerceAtLeast(idle) else idle
    val gDraw = if (isPlaying) gShow.coerceAtLeast(idle) else idle
    val bDraw = if (isPlaying) bShow.coerceAtLeast(idle) else idle

    Canvas(modifier = Modifier.fillMaxSize()) {
        val h = size.height / 3f
        drawRect(Color.Red.copy(alpha = rDraw), Offset(0f, 0f), size.copy(height = h))
        drawRect(Color.Green.copy(alpha = gDraw), Offset(0f, h), size.copy(height = h))
        drawRect(Color.Blue.copy(alpha = bDraw), Offset(0f, h * 2f), size.copy(height = h))
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