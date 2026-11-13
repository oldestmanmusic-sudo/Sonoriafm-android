package com.sonoriafm.radio

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import org.json.JSONObject
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Servicio de música de SONORIA FM. Es lo que Android Auto, la pantalla de bloqueo, los
 * auriculares y los asistentes de voz utilizan. Reproduce la emisión en directo: pide a la web
 * (función radioNow) qué canción toca ahora y cuáles vienen después, y se mantiene sincronizado
 * con el reloj para sonar a la vez que la web.
 */
class PlaybackService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    @Volatile private var clockOffset = 0L // hora del servidor - hora del móvil
    private var pausedAt = 0L
    private var lastTransition = 0L
    private var appending = false
    private var resyncing = false
    private var cutMediaId: String? = null
    private val likedIds = HashSet<String>()

    private fun serverNow() = System.currentTimeMillis() + clockOffset

    // ---- Ciclo de vida -------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(playerListener)
        session = MediaLibrarySession.Builder(this, player, LibraryCallback()).build()
        main.postDelayed(syncCheck, SYNC_EVERY_MS)
        loadLikedIds()
        updateLikeButton()
    }

    // ---- "Me gusta" desde Android Auto: usa la sesión iniciada en la app del móvil ------------

    private fun token(): String? =
        getSharedPreferences("sonoria", Context.MODE_PRIVATE).getString("token", null)

    private fun currentTrackId(): String? =
        player.currentMediaItem?.mediaId?.substringBefore("@")?.takeIf { it != LIVE_ID && it != ROOT_ID }

    private fun loadLikedIds() {
        val t = token() ?: return
        io.execute {
            try {
                val (code, text) = RadioApi.post("functions/likeTrack", JSONObject().put("action", "status"), t)
                if (code in 200..299) {
                    val arr = JSONObject(text).optJSONArray("likedIds")
                    main.post {
                        likedIds.clear()
                        if (arr != null) for (i in 0 until arr.length()) likedIds.add(arr.getString(i))
                        updateLikeButton()
                    }
                }
            } catch (e: Exception) {
                // Sin conexión: se vuelve a intentar al abrir la app.
            }
        }
    }

    /** El corazón solo aparece si ya has iniciado sesión en la app del móvil. */
    private fun updateLikeButton() {
        val s = session ?: return
        if (token() == null) {
            s.setCustomLayout(ImmutableList.of())
            return
        }
        val id = currentTrackId()
        val liked = id != null && likedIds.contains(id)
        val button = CommandButton.Builder(
            if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED
        )
            .setDisplayName("Me gusta")
            .setSessionCommand(SessionCommand(CMD_LIKE, Bundle.EMPTY))
            .build()
        s.setCustomLayout(ImmutableList.of(button))
    }

    private fun likeCurrent() {
        val t = token() ?: return
        val id = currentTrackId() ?: return
        if (likedIds.contains(id)) return
        io.execute {
            try {
                val (code, _) = RadioApi.post("functions/likeTrack", JSONObject().put("trackId", id), t)
                if (code in 200..299) main.post {
                    likedIds.add(id)
                    updateLikeButton()
                }
            } catch (e: Exception) {
                // Sin conexión: no se cuenta.
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        session?.let {
            it.player.release()
            it.release()
        }
        session = null
        io.shutdown()
        super.onDestroy()
    }

    // ---- Construcción de elementos -----------------------------------------------------------

    private fun toMediaItem(t: ScheduledTrack): MediaItem =
        MediaItem.Builder()
            .setMediaId(t.mediaId)
            .setUri(t.audioUrl)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(t.title)
                    .setArtist(t.artist)
                    .setAlbumTitle("SONORIA FM")
                    .setArtworkUri(Uri.parse(if (t.coverUrl.isEmpty()) RadioApi.DEFAULT_COVER else t.coverUrl))
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .build()
            )
            .build()

    /** Segundos que ya lleva sonando la primera canción de la lista, según el reloj. */
    private fun startOffsetMs(first: ScheduledTrack): Long {
        var off = serverNow() - first.startsAt
        if (off < 0) off = 0
        val max = first.durationSec * 1000 - 1500
        if (first.durationSec > 0 && off > max) off = if (max > 0) max else 0
        return off
    }

    private fun rootItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(ROOT_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("SONORIA FM")
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build()
            )
            .build()

    private fun liveItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(LIVE_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("SONORIA FM en directo")
                    .setArtist("Radio online 24 h")
                    .setArtworkUri(Uri.parse(RadioApi.DEFAULT_COVER))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                    .build()
            )
            .build()

    // ---- Lo que ven y piden Android Auto y los controladores ----------------------------------

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(SessionCommand(CMD_LIKE, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == CMD_LIKE) {
                likeCurrent()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(rootItem(), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(liveItem()), params))

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(liveItem(), null))

        // Cualquier petición de reproducir (tocar "SONORIA FM en directo", "Ok Google, pon SONORIA FM",
        // botón de play del móvil...) se convierte en: conectar con la emisión en directo.
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val result = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            io.execute {
                try {
                    val s = RadioApi.fetch()
                    clockOffset = s.clockOffsetMs
                    val items = s.tracks.map { toMediaItem(it) }
                    result.set(
                        MediaSession.MediaItemsWithStartPosition(items, 0, startOffsetMs(s.tracks[0]))
                    )
                } catch (e: Exception) {
                    result.setException(e)
                }
            }
            return result
        }
    }

    // ---- Sincronización con la emisión -------------------------------------------------------

    private val playerListener = object : Player.Listener {

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            lastTransition = System.currentTimeMillis()
            updateLikeButton()
            scheduleCut()
            trimPlayed()
            maybeAppend()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) {
                pausedAt = System.currentTimeMillis()
            } else {
                // En una radio, al volver de una pausa larga no se continúa donde se dejó:
                // se vuelve al directo.
                if (pausedAt > 0 && System.currentTimeMillis() - pausedAt > RESUME_LIVE_AFTER_MS) {
                    resync()
                }
                pausedAt = 0
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // Una canción no carga: se salta a la siguiente; si no hay, se vuelve a conectar.
            if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            } else {
                resync()
            }
        }
    }

    /** Vuelve a pedir la emisión y se coloca exactamente donde toca ahora. */
    private fun resync() {
        if (resyncing) return
        resyncing = true
        io.execute {
            try {
                val s = RadioApi.fetch()
                clockOffset = s.clockOffsetMs
                val items = s.tracks.map { toMediaItem(it) }
                val pos = startOffsetMs(s.tracks[0])
                main.post {
                    player.setMediaItems(items, 0, pos)
                    player.prepare()
                    player.play()
                    resyncing = false
                }
            } catch (e: Exception) {
                resyncing = false
            }
        }
    }

    /** Cada 30 s comprueba que lo que suena coincide con el reloj de la emisión. */
    private val syncCheck = object : Runnable {
        override fun run() {
            checkSync()
            main.postDelayed(this, SYNC_EVERY_MS)
        }
    }

    private fun checkSync() {
        if (!player.playWhenReady) return
        val cur = player.currentMediaItem ?: return
        if (System.currentTimeMillis() - lastTransition < GRACE_AFTER_TRANSITION_MS) return
        val parsed = RadioApi.parseId(cur.mediaId) ?: return
        val expected = serverNow() - parsed.startsAt
        if (abs(player.currentPosition - expected) > MAX_DRIFT_MS) resync()
    }

    /**
     * Si la franja horaria siguiente corta esta canción antes de que acabe su audio, a la hora
     * del cambio se salta a la siguiente (que ya está en la lista, empezando en ese instante).
     */
    private val cutRunnable = Runnable {
        val id = cutMediaId ?: return@Runnable
        val cur = player.currentMediaItem
        if (cur != null && cur.mediaId == id && player.playWhenReady && player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
        }
    }

    private fun scheduleCut() {
        main.removeCallbacks(cutRunnable)
        cutMediaId = null
        val cur = player.currentMediaItem ?: return
        val parsed = RadioApi.parseId(cur.mediaId) ?: return
        if (!parsed.clipped) return
        val delay = parsed.endsAt - serverNow()
        if (delay < 0 || delay > 8L * 3600 * 1000) return
        cutMediaId = cur.mediaId
        main.postDelayed(cutRunnable, delay)
    }

    /** Quita de la lista las canciones ya sonadas para que no crezca sin fin. */
    private fun trimPlayed() {
        val idx = player.currentMediaItemIndex
        if (idx > 3) player.removeMediaItems(0, idx - 2)
    }

    /** Cuando quedan pocas canciones por sonar, añade las siguientes de la emisión. */
    private fun maybeAppend() {
        val count = player.mediaItemCount
        if (count == 0 || appending) return
        if (player.currentMediaItemIndex < count - 3) return
        val lastItem = player.getMediaItemAt(count - 1)
        val last = RadioApi.parseId(lastItem.mediaId) ?: return
        appending = true
        io.execute {
            try {
                val s = RadioApi.fetch(12, last.endsAt + 500)
                clockOffset = s.clockOffsetMs
                val newItems = s.tracks
                    .filter { it.startsAt >= last.endsAt - 1000 }
                    .map { toMediaItem(it) }
                main.post {
                    val n = player.mediaItemCount
                    if (n > 0 && player.getMediaItemAt(n - 1).mediaId == lastItem.mediaId && newItems.isNotEmpty()) {
                        player.addMediaItems(newItems)
                    }
                    appending = false
                }
            } catch (e: Exception) {
                main.post { appending = false }
            }
        }
    }

    companion object {
        private const val ROOT_ID = "root"
        const val LIVE_ID = "live"
        private const val CMD_LIKE = "com.sonoriafm.radio.LIKE"
        private const val SYNC_EVERY_MS = 30_000L
        private const val GRACE_AFTER_TRANSITION_MS = 25_000L
        private const val MAX_DRIFT_MS = 25_000L
        private const val RESUME_LIVE_AFTER_MS = 15_000L
    }
}
