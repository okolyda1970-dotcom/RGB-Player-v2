package com.example.musicplayer

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.media.session.MediaButtonReceiver

class MediaPlaybackService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("BT_TEST", "Service onStartCommand: ${intent?.action}")

        val engine = AudioEngine.getInstance()
            ?: AudioEngine.initInstance(applicationContext).also { it.init() }

        val session = engine.mediaSession
        Log.d("BT_TEST", "Session is ${if (session == null) "NULL" else "OK"}")

        session?.let {
            MediaButtonReceiver.handleIntent(it, intent)
        }

        return START_NOT_STICKY
    }
}