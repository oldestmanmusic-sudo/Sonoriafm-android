package com.sonoriafm.radio

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import kotlin.concurrent.thread
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

/** Pantalla sencilla del móvil: play/pausa de la radio y qué está sonando. */
class MainActivity : Activity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var playButton: Button
    private lateinit var updateButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val bg = Color.parseColor("#0A0A0F")
        val purple = Color.parseColor("#8B33FF")
        val pad = (24 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(bg)
            setPadding(pad, pad, pad, pad)
        }

        val brand = TextView(this).apply {
            text = "SONORIA FM"
            textSize = 32f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val tagline = TextView(this).apply {
            text = "Radio online de música creada con IA"
            textSize = 14f
            setTextColor(Color.parseColor("#B0B0C0"))
            gravity = Gravity.CENTER
        }
        titleView = TextView(this).apply {
            text = "Pulsa play para escuchar"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, pad * 2, 0, 0)
        }
        subtitleView = TextView(this).apply {
            text = ""
            textSize = 16f
            setTextColor(Color.parseColor("#B0B0C0"))
            gravity = Gravity.CENTER
        }
        playButton = Button(this).apply {
            text = "Escuchar"
            setTextColor(Color.WHITE)
            setBackgroundColor(purple)
            setOnClickListener { onPlayClicked() }
        }
        val webButton = Button(this).apply {
            text = "Abrir sonoriafm.com"
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://sonoriafm.com")))
            }
        }

        updateButton = Button(this).apply {
            visibility = android.view.View.GONE
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1FA45B"))
        }

        root.addView(brand)
        root.addView(tagline)
        root.addView(titleView)
        root.addView(subtitleView)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = pad }
        root.addView(playButton, lp)
        root.addView(webButton, lp)
        root.addView(updateButton, lp)
        setContentView(root)
        checkForUpdate()
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
                    updateButton.visibility = android.view.View.VISIBLE
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
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "Permite instalar desde SONORIA FM y vuelve a pulsar Actualizar", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
            )
            updateButton.isEnabled = true
            updateButton.text = "Hay una versión nueva · Actualizar"
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )
        updateButton.isEnabled = true
        updateButton.text = "Hay una versión nueva · Actualizar"
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
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

    private fun refresh() {
        val c = controller ?: return
        playButton.text = if (c.isPlaying) "Pausa" else "Escuchar"
        val md = c.mediaMetadata
        val t = md.title?.toString()
        if (!t.isNullOrEmpty()) {
            titleView.text = t
            subtitleView.text = md.artist?.toString() ?: ""
        }
    }

    companion object {
        private const val RELEASE_URL =
            "https://api.github.com/repos/oldestmanmusic-sudo/sonoriafm-android/releases/latest"
    }
}
