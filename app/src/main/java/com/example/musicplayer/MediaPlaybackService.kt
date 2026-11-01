package com.example.musicplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MediaPlaybackService : Service() {

    companion object {
        private const val CHANNEL_ID = "rgb_player_channel"
        private const val NOTIFICATION_ID = 1
    }

    private lateinit var engine: AudioEngine
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var stateJob: Job? = null

    override fun onCreate() {
        super.onCreate()

        engine = AudioEngine.getInstance()
            ?: AudioEngine.initInstance(applicationContext).also { it.init() }

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Кнопки от Bluetooth/наушников (если пришли)
        intent?.let { i ->
            engine.mediaSession?.let { session ->
                MediaButtonReceiver.handleIntent(session, i)
            }
        }

        // Обязательно: переводим сервис в foreground
        startForeground(NOTIFICATION_ID, buildNotification())

        // Подписка на состояние движка — обновляем уведомление при любых изменениях
        if (stateJob == null) {
            stateJob = serviceScope.launch {
                engine.state.collect {
                    val manager = getSystemService(NotificationManager::class.java)
                    manager.notify(NOTIFICATION_ID, buildNotification())
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stateJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ==================== УВЕДОМЛЕНИЕ ====================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Воспроизведение",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Управление музыкой"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val session = engine.mediaSession
        val state = engine.state.value
        val track = engine.playlist.getOrNull(state.currentIndex)

        // Заголовок: имя файла/тега или «Нет трека»
        val title = track?.displayTitle
            ?: track?.title?.takeIf { it.isNotBlank() }
            ?: engine.fileNameOf(track?.resId ?: 0).ifBlank { "Нет трека" }

        val subtitle = track?.displayArtist?.takeIf { it.isNotBlank() }
            ?: "RGB Player"

        // Интент на открытие приложения по тапу
        val contentIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        // Кнопки
        val prevAction = NotificationCompat.Action(
            android.R.drawable.ic_media_previous,
            "Предыдущий",
            MediaButtonReceiver.buildMediaButtonPendingIntent(
                this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            )
        )

        val playPauseAction: NotificationCompat.Action =
            if (state.isPlaying) {
                NotificationCompat.Action(
                    android.R.drawable.ic_media_pause,
                    "Пауза",
                    MediaButtonReceiver.buildMediaButtonPendingIntent(
                        this, PlaybackStateCompat.ACTION_PAUSE
                    )
                )
            } else {
                NotificationCompat.Action(
                    android.R.drawable.ic_media_play,
                    "Играть",
                    MediaButtonReceiver.buildMediaButtonPendingIntent(
                        this, PlaybackStateCompat.ACTION_PLAY
                    )
                )
            }

        val nextAction = NotificationCompat.Action(
            android.R.drawable.ic_media_next,
            "Следующий",
            MediaButtonReceiver.buildMediaButtonPendingIntent(
                this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT
            )
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(state.isPlaying)
            .setOnlyAlertOnce(true)
            .addAction(prevAction)
            .addAction(playPauseAction)
            .addAction(nextAction)

        if (session != null) {
            builder.setStyle(
                MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        }

        return builder.build()
    }
}