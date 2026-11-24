package com.sonora.music

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media.session.MediaButtonReceiver
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.support.v4.media.MediaMetadataCompat

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val filePickerRequest = 1001
    private val notificationPermissionRequest = 1002
    private var mediaSession: MediaSessionCompat? = null
    private val channelId = "sonora_playback"
    private val notificationId = 7001
    private var trackTitle = "SONORA MUSIC"
    private var trackArtist = "Sua biblioteca"
    private var trackDurationMs = 0L
    private var isPlaying = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()
        setupMediaSession()
        webView = WebView(this)
        setContentView(webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = true
        webView.settings.mediaPlaybackRequiresUserGesture = true
        webView.addJavascriptInterface(SonoraBridge(), "SonoraBridge")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.fileCallback?.onReceiveValue(null)
                this@MainActivity.fileCallback = filePathCallback
                val accept = fileChooserParams?.acceptTypes?.joinToString(",") ?: ""
                val mime = when {
                    accept.contains("audio", ignoreCase = true) -> "audio/*"
                    accept.contains("image", ignoreCase = true) -> "image/*"
                    else -> "*/*"
                }
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = mime
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                }
                return try {
                    startActivityForResult(intent, filePickerRequest)
                    true
                } catch (_: Exception) {
                    this@MainActivity.fileCallback?.onReceiveValue(null)
                    this@MainActivity.fileCallback = null
                    false
                }
            }
        }
        webView.loadUrl("file:///android_asset/index.html")
        handleMediaIntent(intent)
        requestNotificationPermissionIfNeeded()
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "SONORA_MUSIC").apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = sendMediaAction("play")
                override fun onPause() = sendMediaAction("pause")
                override fun onSkipToNext() = sendMediaAction("next")
                override fun onSkipToPrevious() = sendMediaAction("previous")
            })
            isActive = true
        }
    }

    private fun sendMediaAction(action: String) {
        if (::webView.isInitialized) {
            runOnUiThread { webView.evaluateJavascript("window.sonoraMediaAction('$action')", null) }
        }
    }

    private inner class SonoraBridge {
        @JavascriptInterface
        fun updatePlayback(title: String, artist: String, durationSeconds: Int, playing: Boolean) {
            runOnUiThread {
                trackTitle = title.take(120)
                trackArtist = artist.take(120)
                trackDurationMs = durationSeconds.coerceAtLeast(0) * 1000L
                isPlaying = playing
                updateMediaSessionAndNotification()
            }
        }
    }

    private fun updateMediaSessionAndNotification() {
        val session = mediaSession ?: return
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, trackTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, trackArtist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, trackDurationMs)
                .build()
        )
        val actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED, 0L, 1f)
                .build()
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(if (isPlaying) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause)
            .setContentTitle(trackTitle)
            .setContentText(trackArtist)
            .setContentIntent(activityPendingIntent("open"))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(isPlaying)
            .addAction(android.R.drawable.ic_media_previous, "Anterior", activityPendingIntent("previous"))
            .addAction(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "Pausar" else "Reproduzir", activityPendingIntent(if (isPlaying) "pause" else "play"))
            .addAction(android.R.drawable.ic_media_next, "Próxima", activityPendingIntent("next"))
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(session.sessionToken)
                .setShowActionsInCompactView(0, 1, 2))
            .build()
        if (Build.VERSION.SDK_INT < 33 || ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            NotificationManagerCompat.from(this).notify(notificationId, notification)
        }
    }

    private fun activityPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            this.action = "com.sonora.music.MEDIA_$action"
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(this, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun handleMediaIntent(intent: Intent?) {
        when (intent?.action) {
            "com.sonora.music.MEDIA_play" -> sendMediaAction("play")
            "com.sonora.music.MEDIA_pause" -> sendMediaAction("pause")
            "com.sonora.music.MEDIA_next" -> sendMediaAction("next")
            "com.sonora.music.MEDIA_previous" -> sendMediaAction("previous")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleMediaIntent(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Reprodução SONORA", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Controles de música na notificação e tela de bloqueio"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), notificationPermissionRequest)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == notificationPermissionRequest && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) updateMediaSessionAndNotification()
    }

    @Deprecated("Deprecated for WebView file chooser compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == filePickerRequest) {
            val callback = fileCallback ?: return
            val results = if (resultCode == Activity.RESULT_OK && data != null) {
                val clip = data.clipData
                when {
                    clip != null -> Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                    data.data != null -> arrayOf(data.data!!)
                    else -> null
                }
            } else null
            callback.onReceiveValue(results)
            fileCallback = null
        }
    }

    @Deprecated("Deprecated on newer Android versions")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        NotificationManagerCompat.from(this).cancel(notificationId)
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }
}
