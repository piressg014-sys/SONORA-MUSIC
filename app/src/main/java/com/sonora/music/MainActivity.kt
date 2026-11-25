package com.sonora.music

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var auth: FirebaseAuth
    private var googleSignInClient: com.google.android.gms.auth.api.signin.GoogleSignInClient? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val filePickerRequest = 1001
    private val notificationPermissionRequest = 1002
    private val googleSignInRequest = 1003
    private var mediaSession: MediaSessionCompat? = null
    private val channelId = "sonora_playback"
    private val notificationId = 7001
    private var trackTitle = "SONORA MUSIC"
    private var trackArtist = "Sua biblioteca"
    private var trackDurationMs = 0L
    private var isPlaying = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auth = FirebaseAuth.getInstance()
        configureGoogleSignIn()
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
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                syncAuthenticationState()
            }
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

    private fun configureGoogleSignIn() {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, options)
    }

    private fun syncAuthenticationState() {
        val user = auth.currentUser
        val email = user?.email.orEmpty()
        val js = "window.sonoraSetAuthState && window.sonoraSetAuthState(${user != null}, ${JSONObject.quote(email)});"
        webView.post { webView.evaluateJavascript(js, null) }
    }

    private fun sendAuthResult(success: Boolean, message: String, email: String = "") {
        val js = "window.sonoraAuthResult && window.sonoraAuthResult($success, ${JSONObject.quote(message)}, ${JSONObject.quote(email)});"
        if (::webView.isInitialized) webView.post { webView.evaluateJavascript(js, null) }
    }

    private fun startGoogleSignIn() {
        val client = googleSignInClient
        if (client == null) {
            sendAuthResult(false, "Não foi possível iniciar o Google. Confira a configuração do Firebase.")
            return
        }
        try {
            startActivityForResult(client.signInIntent, googleSignInRequest)
        } catch (_: Exception) {
            sendAuthResult(false, "Não foi possível abrir o login do Google neste aparelho.")
        }
    }

    private fun friendlyAuthError(error: Exception?): String {
        val code = (error as? com.google.firebase.auth.FirebaseAuthException)?.errorCode.orEmpty()
        return when (code) {
            "ERROR_INVALID_EMAIL" -> "Digite um endereço de e-mail válido."
            "ERROR_WEAK_PASSWORD" -> "A senha precisa ter pelo menos 6 caracteres."
            "ERROR_EMAIL_ALREADY_IN_USE" -> "Este e-mail já possui uma conta. Faça login."
            "ERROR_USER_NOT_FOUND", "ERROR_WRONG_PASSWORD", "ERROR_INVALID_CREDENTIAL" -> "E-mail ou senha incorretos."
            "ERROR_TOO_MANY_REQUESTS" -> "Muitas tentativas. Aguarde um pouco e tente novamente."
            "ERROR_EXPIRED_ACTION_CODE" -> "Este link de recuperação expirou. Solicite um novo link e abra o e-mail mais recente."
            "ERROR_INVALID_ACTION_CODE" -> "Este link de recuperação não é válido ou já foi utilizado. Solicite outro."
            else -> "Não foi possível concluir. Confira sua conexão e tente novamente."
        }
    }

    private inner class SonoraBridge {
        @JavascriptInterface
        fun login(email: String, password: String) {
            runOnUiThread {
                auth.signInWithEmailAndPassword(email.trim(), password)
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) sendAuthResult(true, "Login realizado!", auth.currentUser?.email.orEmpty())
                        else sendAuthResult(false, friendlyAuthError(task.exception))
                    }
            }
        }

        @JavascriptInterface
        fun register(email: String, password: String) {
            runOnUiThread {
                auth.createUserWithEmailAndPassword(email.trim(), password)
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) sendAuthResult(true, "Conta criada com sucesso!", auth.currentUser?.email.orEmpty())
                        else sendAuthResult(false, friendlyAuthError(task.exception))
                    }
            }
        }

        @JavascriptInterface
        fun resetPassword(email: String) {
            runOnUiThread {
                auth.sendPasswordResetEmail(email.trim()).addOnCompleteListener { task ->
                    if (task.isSuccessful) sendAuthResult(true, "E-mail enviado! Abra o link mais recente recebido. Por segurança, o link tem validade limitada. Confira também Spam e Promoções.")
                    else sendAuthResult(false, friendlyAuthError(task.exception))
                }
            }
        }

        @JavascriptInterface
        fun googleSignIn() { runOnUiThread { startGoogleSignIn() } }

        @JavascriptInterface
        fun logout() {
            runOnUiThread {
                auth.signOut()
                googleSignInClient?.signOut()
                syncAuthenticationState()
            }
        }

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
        if (::webView.isInitialized) runOnUiThread { webView.evaluateJavascript("window.sonoraMediaAction('$action')", null) }
    }

    private fun updateMediaSessionAndNotification() {
        val session = mediaSession ?: return
        session.setMetadata(MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, trackTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, trackArtist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, trackDurationMs).build())
        val actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        session.setPlaybackState(PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED, 0L, 1f)
            .build())
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(if (isPlaying) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause)
            .setContentTitle(trackTitle).setContentText(trackArtist)
            .setContentIntent(activityPendingIntent("open"))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setOnlyAlertOnce(true).setOngoing(isPlaying)
            .addAction(android.R.drawable.ic_media_previous, "Anterior", activityPendingIntent("previous"))
            .addAction(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "Pausar" else "Reproduzir", activityPendingIntent(if (isPlaying) "pause" else "play"))
            .addAction(android.R.drawable.ic_media_next, "Próxima", activityPendingIntent("next"))
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
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
        return PendingIntent.getActivity(this, action.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
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

    @Deprecated("Deprecated for WebView file chooser and Google sign-in compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == googleSignInRequest) {
            try {
                val account = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
                val idToken = account.idToken
                if (idToken.isNullOrBlank()) {
                    sendAuthResult(false, "O Google não retornou o token de acesso. Verifique o cliente OAuth Web e Android no Firebase.")
                    return
                }
                val credential = GoogleAuthProvider.getCredential(idToken, null)
                auth.signInWithCredential(credential).addOnCompleteListener { task ->
                    if (task.isSuccessful) sendAuthResult(true, "Login com Google realizado!", auth.currentUser?.email.orEmpty())
                    else sendAuthResult(false, "Falha no Firebase ao validar o Google: ${friendlyAuthError(task.exception)}")
                }
            } catch (e: ApiException) {
                val detail = when (e.statusCode) {
                    10 -> "Configuração OAuth incorreta (DEVELOPER_ERROR). Confira pacote e SHA-1 no Firebase."
                    12500 -> "Falha na configuração do login Google (12500). Confira os clientes OAuth."
                    12501 -> "Login cancelado pelo usuário."
                    7 -> "Sem conexão com a internet."
                    else -> "Erro Google ${e.statusCode}: ${e.status.statusMessage ?: "não foi possível autenticar"}."
                }
                sendAuthResult(false, detail)
            } catch (e: Exception) {
                sendAuthResult(false, "Erro inesperado no login Google: ${e.message ?: "sem detalhes"}.")
            }
            return
        }
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
