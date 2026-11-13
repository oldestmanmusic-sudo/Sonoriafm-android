package com.sonoriafm.radio

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Pantalla del móvil: radio en directo, carátula, play/pausa, "me gusta" y actualizaciones. */
class MainActivity : Activity() {

    private val bg = Color.parseColor("#0A0A0F")
    private val card = Color.parseColor("#17171F")
    private val purple = Color.parseColor("#8B33FF")
    private val grey = Color.parseColor("#B0B0C0")
    private val pink = Color.parseColor("#FF4D8D")

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private lateinit var coverView: ImageView
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var playButton: TextView
    private lateinit var likeButton: TextView
    private lateinit var shareButton: TextView
    private lateinit var nextView: TextView
    private lateinit var sleepView: TextView
    private var lastListedId: String? = null
    private lateinit var updateButton: Button
    private lateinit var loginPanel: LinearLayout
    private lateinit var emailInput: EditText
    private lateinit var passInput: EditText
    private lateinit var loginMsg: TextView
    private lateinit var loginSubmit: Button

    private val prefs by lazy { getSharedPreferences("sonoria", Context.MODE_PRIVATE) }
    private var token: String? = null
    private val likedIds = HashSet<String>()
    private val likeCounts = HashMap<String, Long>()
    private var lastCover: String? = null
    private var pendingLike = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    // ---- Construcción de la pantalla -------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        token = prefs.getString("token", null)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(28), dp(24), dp(24))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#2A1250"), bg, bg)
            )
        }

        val brand = ImageView(this).apply {
            setImageResource(R.drawable.logo_sonoria)
            adjustViewBounds = true
            contentDescription = "SONORIA FM"
        }
        val live = TextView(this).apply {
            text = "●  EN DIRECTO"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = rounded(purple, 14)
            setPadding(dp(14), dp(5), dp(14), dp(5))
        }

        coverView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(card)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(24).toFloat())
                }
            }
            elevation = dp(12).toFloat()
        }

        titleView = TextView(this).apply {
            text = "Pulsa play para escuchar"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            maxLines = 2
        }
        subtitleView = TextView(this).apply {
            textSize = 16f
            setTextColor(grey)
            gravity = Gravity.CENTER
            maxLines = 1
        }

        playButton = circleButton("▶", 76, purple, 30f).apply { setOnClickListener { onPlayClicked() } }
        likeButton = circleButton("♡", 60, card, 26f).apply { setOnClickListener { onLikeClicked() } }

        shareButton = circleButton("⤴", 60, card, 24f).apply { setOnClickListener { onShareClicked() } }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        controls.addView(likeButton, LinearLayout.LayoutParams(dp(60), dp(60)).apply { rightMargin = dp(24) })
        controls.addView(playButton, LinearLayout.LayoutParams(dp(76), dp(76)))
        controls.addView(shareButton, LinearLayout.LayoutParams(dp(60), dp(60)).apply { leftMargin = dp(24) })

        sleepView = TextView(this).apply {
            text = "☾  Temporizador para dormir"
            textSize = 14f
            setTextColor(grey)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(4))
            setOnClickListener { onSleepClicked() }
        }
        nextView = TextView(this).apply {
            textSize = 14f
            setTextColor(grey)
            setLineSpacing(dp(4).toFloat(), 1f)
            background = rounded(card, 16)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            visibility = View.GONE
        }

        // Panel para iniciar sesión (hace falta para dar "me gusta", igual que en la web).
        loginPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = rounded(card, 18)
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        loginPanel.addView(TextView(this).apply {
            text = "Inicia sesión para dar «me gusta»"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        loginPanel.addView(TextView(this).apply {
            text = "Usa el mismo correo y contraseña de sonoriafm.com"
            textSize = 13f
            setTextColor(grey)
            setPadding(0, dp(2), 0, dp(8))
        })
        emailInput = field("Correo electrónico", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        passInput = field("Contraseña", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        loginMsg = TextView(this).apply {
            textSize = 13f
            setTextColor(pink)
            visibility = View.GONE
        }
        loginSubmit = Button(this).apply {
            text = "Entrar"
            setTextColor(Color.WHITE)
            background = rounded(purple, 12)
            setOnClickListener { doLogin() }
        }
        val googleButton = Button(this).apply {
            text = "Entrar con Google"
            setTextColor(Color.parseColor("#1F1F1F"))
            background = rounded(Color.WHITE, 12)
            setOnClickListener {
                val from = Uri.encode("https://sonoriafm.com/app-login")
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://base44.app/api/apps/auth/login?app_id=$APP_ID&from_url=$from")
                    )
                )
            }
        }
        val cancel = TextView(this).apply {
            text = "Cancelar"
            textSize = 14f
            setTextColor(grey)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
            setOnClickListener {
                pendingLike = false
                loginPanel.visibility = View.GONE
            }
        }
        loginPanel.addView(emailInput)
        loginPanel.addView(passInput)
        loginPanel.addView(loginMsg)
        loginPanel.addView(loginSubmit)
        loginPanel.addView(googleButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
        loginPanel.addView(cancel)

        updateButton = Button(this).apply {
            visibility = View.GONE
            setTextColor(Color.WHITE)
            background = rounded(Color.parseColor("#1FA45B"), 12)
        }
        val web = TextView(this).apply {
            text = "Abrir sonoriafm.com"
            textSize = 14f
            setTextColor(grey)
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(6))
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://sonoriafm.com")))
            }
        }

        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        root.addView(brand, LinearLayout.LayoutParams(dp(210), wrap))
        root.addView(live, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dp(10) })
        root.addView(coverView, LinearLayout.LayoutParams(dp(250), dp(250)).apply { topMargin = dp(22) })
        root.addView(titleView, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(22) })
        root.addView(subtitleView, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(4) })
        root.addView(controls, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dp(22) })
        root.addView(sleepView, LinearLayout.LayoutParams(match, wrap))
        root.addView(nextView, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(12) })
        root.addView(loginPanel, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(20) })
        root.addView(updateButton, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(18) })
        root.addView(web)

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(bg)
            addView(root)
        })
        coverView.setImageBitmap(null)
        handleLoginLink(intent)
        checkForUpdate()
        if (token != null) loadLikedIds()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLoginLink(intent)
    }

    /** Vuelta del inicio de sesión con Google: guarda la sesión y carga tus «me gusta». */
    private fun handleLoginLink(i: Intent?) {
        val data = i?.data ?: return
        if (data.scheme != "sonoriafm" || data.host != "login") return
        val t = data.getQueryParameter("token")
        if (t.isNullOrEmpty()) return
        token = t
        prefs.edit().putString("token", t).apply()
        loginPanel.visibility = View.GONE
        Toast.makeText(this, "Sesión iniciada", Toast.LENGTH_SHORT).show()
        val again = pendingLike
        pendingLike = false
        loadLikedIds { if (again) onLikeClicked() }
    }

    private fun circleButton(text: String, sizeDp: Int, color: Int, sp: Float) = TextView(this).apply {
        this.text = text
        textSize = sp
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        elevation = dp(6).toFloat()
        minimumWidth = dp(sizeDp)
        minimumHeight = dp(sizeDp)
    }

    private fun field(hint: String, type: Int) = EditText(this).apply {
        this.hint = hint
        inputType = type
        setTextColor(Color.WHITE)
        setHintTextColor(Color.parseColor("#80808F"))
        textSize = 15f
        background = rounded(Color.parseColor("#22222C"), 10)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) }
    }

    // ---- Conexión con la radio -------------------------------------------------------------

    override fun onStart() {
        super.onStart()
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture = future
        future.addListener({
            try {
                val c = future.get()
                controller = c
                c.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) = refresh()
                    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = refresh()
                    override fun onPlaybackStateChanged(playbackState: Int) = refresh()
                })
                refresh()
            } catch (e: Exception) {
                titleView.text = "No se pudo conectar con la radio"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        super.onStop()
    }

    private fun onPlayClicked() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
            return
        }
        if (c.mediaItemCount == 0) {
            // El servicio convierte esta petición en "conectar con la emisión en directo".
            c.setMediaItem(MediaItem.Builder().setMediaId(PlaybackService.LIVE_ID).build())
            c.prepare()
        }
        c.play()
    }

    private fun currentTrackId(): String? {
        val id = controller?.currentMediaItem?.mediaId ?: return null
        if (id == PlaybackService.LIVE_ID) return null
        return id.substringBefore("@")
    }

    private fun onSleepClicked() {
        val c = controller ?: return
        val options = arrayOf("15 minutos", "30 minutos", "1 hora", "Desactivar")
        val minutes = intArrayOf(15, 30, 60, 0)
        AlertDialog.Builder(this)
            .setTitle("Apagar la radio en…")
            .setItems(options) { _, which ->
                val args = Bundle().apply { putInt("min", minutes[which]) }
                c.sendCustomCommand(SessionCommand(PlaybackService.CMD_SLEEP, Bundle.EMPTY), args)
                sleepView.text = if (minutes[which] > 0) "☾  Se apagará en ${options[which]}" else "☾  Temporizador para dormir"
            }
            .show()
    }

    /** Las próximas canciones de la emisión. */
    private fun loadUpcoming() {
        thread {
            try {
                val s = RadioApi.fetch(5)
                val now = System.currentTimeMillis() + s.clockOffsetMs
                val next = s.tracks.filter { it.startsAt > now }.take(3)
                runOnUiThread {
                    if (next.isEmpty()) {
                        nextView.visibility = View.GONE
                    } else {
                        nextView.text = "A continuación\n" + next.joinToString("\n") { "♪  ${it.title} · ${it.artist}" }
                        nextView.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                // Sin conexión: no se muestra la lista.
            }
        }
    }

    private fun onShareClicked() {
        val id = currentTrackId()
        if (id == null) {
            Toast.makeText(this, "Pulsa play y espera a que suene una canción", Toast.LENGTH_SHORT).show()
            return
        }
        val md = controller?.mediaMetadata
        val name = listOfNotNull(md?.title?.toString(), md?.artist?.toString()).joinToString(" - ")
        val text = "Estoy escuchando $name en SONORIA FM 🎧 https://sonoriafm.com/cancion/$id"
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                },
                "Compartir canción"
            )
        )
    }

    private fun refresh() {
        val c = controller ?: return
        playButton.text = if (c.isPlaying) "❚❚" else "▶"
        val md = c.mediaMetadata
        val t = md.title?.toString()
        if (!t.isNullOrEmpty()) {
            titleView.text = t
            subtitleView.text = md.artist?.toString() ?: ""
        }
        val art = md.artworkUri?.toString()
        if (art != null && art != lastCover) {
            lastCover = art
            thread {
                try {
                    val bmp: Bitmap? = URL(art).openStream().use { BitmapFactory.decodeStream(it) }
                    if (bmp != null && art == lastCover) runOnUiThread { coverView.setImageBitmap(bmp) }
                } catch (e: Exception) {
                    // Sin carátula: se queda el fondo.
                }
            }
        }
        val id = currentTrackId()
        if (id != null && id != lastListedId) {
            lastListedId = id
            loadUpcoming()
        }
        val liked = id != null && likedIds.contains(id)
        val n = if (id != null) likeCounts[id] else null
        likeButton.text = (if (liked) "♥" else "♡") + (if (n != null && n > 0) " $n" else "")
        likeButton.setTextColor(if (liked) pink else Color.WHITE)
    }

    // ---- "Me gusta" y sesión ------------------------------------------------------------------

    private fun call(path: String, body: JSONObject, auth: String?): Pair<Int, String> {
        val conn = URL("https://base44.app/api/apps/$APP_ID/$path").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (auth != null) conn.setRequestProperty("Authorization", "Bearer $auth")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return Pair(code, text)
        } finally {
            conn.disconnect()
        }
    }

    private fun showLogin(message: String? = null) {
        loginPanel.visibility = View.VISIBLE
        loginMsg.visibility = if (message == null) View.GONE else View.VISIBLE
        loginMsg.text = message ?: ""
    }

    private fun onLikeClicked() {
        val id = currentTrackId()
        if (id == null) {
            Toast.makeText(this, "Pulsa play y espera a que suene una canción", Toast.LENGTH_SHORT).show()
            return
        }
        val t = token
        if (t == null) {
            pendingLike = true
            showLogin()
            return
        }
        if (likedIds.contains(id)) {
            Toast.makeText(this, "Ya te gustaba esta canción", Toast.LENGTH_SHORT).show()
            return
        }
        thread {
            try {
                val (code, text) = call("functions/likeTrack", JSONObject().put("trackId", id), t)
                if (code in 200..299) {
                    val json = JSONObject(text)
                    runOnUiThread {
                        likedIds.add(id)
                        likeCounts[id] = json.optLong("likes", 0)
                        refresh()
                    }
                } else {
                    // La sesión ha caducado: se pide entrar otra vez.
                    runOnUiThread {
                        token = null
                        prefs.edit().remove("token").apply()
                        pendingLike = true
                        showLogin("Tu sesión ha caducado. Entra otra vez.")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Sin conexión", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun doLogin() {
        val email = emailInput.text.toString().trim()
        val pass = passInput.text.toString()
        if (email.isEmpty() || pass.isEmpty()) {
            showLogin("Escribe tu correo y tu contraseña")
            return
        }
        loginSubmit.isEnabled = false
        loginSubmit.text = "Entrando…"
        thread {
            try {
                val (code, text) = call("auth/login", JSONObject().put("email", email).put("password", pass), null)
                if (code in 200..299) {
                    val access = JSONObject(text).optString("access_token", "")
                    if (access.isEmpty()) throw IllegalStateException("sin token")
                    runOnUiThread {
                        token = access
                        prefs.edit().putString("token", access).apply()
                        passInput.setText("")
                        loginPanel.visibility = View.GONE
                        loginSubmit.isEnabled = true
                        loginSubmit.text = "Entrar"
                        val again = pendingLike
                        pendingLike = false
                        loadLikedIds { if (again) onLikeClicked() }
                    }
                } else {
                    runOnUiThread {
                        loginSubmit.isEnabled = true
                        loginSubmit.text = "Entrar"
                        showLogin("Correo o contraseña incorrectos")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    loginSubmit.isEnabled = true
                    loginSubmit.text = "Entrar"
                    showLogin("No se pudo conectar. Prueba otra vez.")
                }
            }
        }
    }

    private fun loadLikedIds(done: (() -> Unit)? = null) {
        val t = token ?: return
        thread {
            try {
                val (code, text) = call("functions/likeTrack", JSONObject().put("action", "status"), t)
                if (code in 200..299) {
                    val arr = JSONObject(text).optJSONArray("likedIds")
                    runOnUiThread {
                        likedIds.clear()
                        if (arr != null) for (i in 0 until arr.length()) likedIds.add(arr.getString(i))
                        refresh()
                        done?.invoke()
                    }
                } else {
                    runOnUiThread {
                        token = null
                        prefs.edit().remove("token").apply()
                    }
                }
            } catch (e: Exception) {
                // Sin conexión: se reintenta al abrir la app otra vez.
            }
        }
    }

    // ---- Actualizaciones: mira si hay una versión nueva publicada en GitHub ----------------------

    private fun currentVersion(): Long =
        packageManager.getPackageInfo(packageName, 0).longVersionCode

    private fun checkForUpdate() {
        thread {
            try {
                val conn = URL(RELEASE_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                if (conn.responseCode !in 200..299) return@thread
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val latest = json.getString("tag_name").removePrefix("v").toLongOrNull() ?: return@thread
                if (latest <= currentVersion()) return@thread
                val assets = json.getJSONArray("assets")
                var apkUrl: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.getString("name").endsWith(".apk")) apkUrl = a.getString("browser_download_url")
                }
                val url = apkUrl ?: return@thread
                runOnUiThread {
                    updateButton.text = "Hay una versión nueva · Actualizar"
                    updateButton.visibility = View.VISIBLE
                    updateButton.setOnClickListener { downloadAndInstall(url) }
                }
            } catch (e: Exception) {
                // Sin conexión o sin versión nueva: no pasa nada.
            }
        }
    }

    private fun downloadAndInstall(apkUrl: String) {
        updateButton.isEnabled = false
        updateButton.text = "Descargando…"
        thread {
            try {
                val dir = File(cacheDir, "updates").apply { mkdirs() }
                val file = File(dir, "sonoria-update.apk")
                val conn = URL(apkUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 60_000
                conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
                runOnUiThread { installApk(file) }
            } catch (e: Exception) {
                runOnUiThread {
                    updateButton.isEnabled = true
                    updateButton.text = "Error al descargar · Reintentar"
                }
            }
        }
    }

    private fun installApk(file: File) {
        updateButton.isEnabled = true
        updateButton.text = "Hay una versión nueva · Actualizar"
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "Permite instalar desde SONORIA FM y vuelve a pulsar Actualizar", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )
    }

    companion object {
        private const val APP_ID = "6ace0c27732bbb0f4d7504e1"
        private const val RELEASE_URL =
            "https://api.github.com/repos/oldestmanmusic-sudo/sonoriafm-android/releases/latest"
    }
}
