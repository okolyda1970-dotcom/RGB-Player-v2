package com.example.musicplayer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.documentfile.provider.DocumentFile

class MainActivity : ComponentActivity() {

    private lateinit var engine: AudioEngine

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val folderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri ?: return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) { }

        saveFolderUri(uri)
        loadTracksFromTree(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Immersive fullscreen — убираем статус-бар и панель навигации
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        permLauncher.launch(Manifest.permission.RECORD_AUDIO)

        engine = AudioEngine(this)

        val savedUri = getSavedFolderUri()
        if (savedUri != null) {
            loadTracksFromTree(savedUri)
        }

        setContent {
            MaterialTheme {
                Surface {
                    PlayerScreen(
                        engine = engine,
                        onPickFolder = { folderLauncher.launch(null) }
                    )
                }
            }
        }
    }

    private fun loadTracksFromTree(treeUri: Uri) {
        val tracks = mutableListOf<TrackInfo>()

        try {
            val root = DocumentFile.fromTreeUri(this, treeUri) ?: run {
                Toast.makeText(this, "Не удалось открыть папку", Toast.LENGTH_SHORT).show()
                return
            }

            val queue = ArrayDeque<DocumentFile>()
            queue.add(root)

            while (queue.isNotEmpty()) {
                val dir = queue.removeFirst()
                for (file in dir.listFiles()) {
                    if (file.isDirectory) {
                        queue.add(file)
                    } else if (isAudioFile(file)) {
                        val displayName = resolveDisplayName(file)
                        tracks.add(TrackInfo(title = displayName, uri = file.uri))
                    }
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка чтения папки: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }

        if (tracks.isEmpty()) {
            Toast.makeText(this, "В папке нет аудиофайлов", Toast.LENGTH_LONG).show()
            return
        }

        tracks.sortBy { it.title.lowercase() }
        engine.setPlaylist(tracks)
        Toast.makeText(this, "Загружено треков: ${tracks.size}", Toast.LENGTH_SHORT).show()
    }

    private fun resolveDisplayName(file: DocumentFile): String {
        file.name?.takeIf { it.isNotBlank() }?.let { return it }

        try {
            val docId = DocumentsContract.getDocumentId(file.uri)
            if (!docId.isNullOrBlank()) {
                val name = docId.substringAfterLast('/').substringAfterLast(':')
                if (name.isNotBlank()) return name
            }
        } catch (_: Exception) { }

        val seg = file.uri.lastPathSegment
        if (!seg.isNullOrBlank()) {
            val name = seg.substringAfterLast('/').substringAfterLast(':')
            if (name.isNotBlank()) return name
        }

        return "[${file.uri}]"
    }

    private fun isAudioFile(file: DocumentFile): Boolean {
        val mime = file.type
        if (mime != null && mime.startsWith("audio/")) return true
        val name = file.name?.lowercase() ?: return false
        return name.endsWith(".mp3") ||
                name.endsWith(".m4a") ||
                name.endsWith(".aac") ||
                name.endsWith(".ogg") ||
                name.endsWith(".opus") ||
                name.endsWith(".flac") ||
                name.endsWith(".wav")
    }

    private fun saveFolderUri(uri: Uri) {
        getSharedPreferences("player", Context.MODE_PRIVATE)
            .edit()
            .putString("folder_uri", uri.toString())
            .apply()
    }

    private fun getSavedFolderUri(): Uri? {
        val s = getSharedPreferences("player", Context.MODE_PRIVATE)
            .getString("folder_uri", null) ?: return null
        return Uri.parse(s)
    }

    override fun onDestroy() {
        engine.release()
        super.onDestroy()
    }
}