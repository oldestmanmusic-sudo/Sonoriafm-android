package com.sonoriafm.radio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Una canción de la emisión, con la hora exacta (ms desde 1970, hora del servidor) en que empieza y acaba. */
data class ScheduledTrack(
    val id: String,
    val title: String,
    val artist: String,
    val audioUrl: String,
    val coverUrl: String,
    val durationSec: Long,
    val startsAt: Long,
    val endsAt: Long,
) {
    /** true si el cambio de franja de la parrilla corta esta canción antes de que acabe su audio. */
    val clipped: Boolean
        get() = durationSec > 0 && endsAt < startsAt + durationSec * 1000 - 2000

    /** Identificador único de esta emisión concreta: id@inicio@fin@cortada. */
    val mediaId: String
        get() = "$id@$startsAt@$endsAt@${if (clipped) 1 else 0}"
}

/** clockOffsetMs = hora del servidor menos hora del móvil (para ir sincronizado con la web). */
data class Schedule(val tracks: List<ScheduledTrack>, val clockOffsetMs: Long)

/** Datos de una canción sacados de su mediaId. */
data class ParsedId(val startsAt: Long, val endsAt: Long, val clipped: Boolean)

object RadioApi {
    // Función pública de Base44 que calcula qué suena ahora (mismo algoritmo que la web).
    private const val URL_NOW =
        "https://base44.app/api/apps/6ace0c27732bbb0f4d7504e1/functions/radioNow"

    const val DEFAULT_COVER =
        "https://media.base44.com/images/public/6ace0c27732bbb0f4d7504e1/b5af580b8_generated_image.png"

    /** Pide la canción actual y las siguientes. Bloquea: llamar siempre fuera del hilo principal. */
    /** Zona de emisión según el huso horario del móvil: la parrilla va en hora local. */
    fun zone(): String {
        val off = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000
        return when {
            off >= -60 -> "eu"
            off > -300 -> "sa"
            off == -300 -> "co"
            else -> "mx"
        }
    }

    fun fetch(count: Int = 12, atMs: Long? = null): Schedule {
        val body = JSONObject().put("count", count).put("zone", zone())
        if (atMs != null) body.put("at", atMs)
        val t0 = System.currentTimeMillis()
        val conn = URL(URL_NOW).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val t1 = System.currentTimeMillis()
            val json = JSONObject(text)
            val offset = json.getLong("serverTime") - (t0 + t1) / 2
            val arr = json.getJSONArray("tracks")
            val list = ArrayList<ScheduledTrack>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    ScheduledTrack(
                        id = o.getString("id"),
                        title = o.optString("title", ""),
                        artist = o.optString("artist", ""),
                        audioUrl = o.getString("audio_url"),
                        coverUrl = o.optString("cover_url", ""),
                        durationSec = parseDuration(o.optString("duration", "")),
                        startsAt = o.getLong("startsAt"),
                        endsAt = o.getLong("endsAt"),
                    )
                )
            }
            if (list.isEmpty()) error("La radio no devolvió canciones")
            return Schedule(list, offset)
        } finally {
            conn.disconnect()
        }
    }

    /** POST JSON a la app (funciones, login). Devuelve (código HTTP, texto). Bloquea: usar fuera del hilo principal. */
    fun post(path: String, body: JSONObject, token: String?): Pair<Int, String> {
        val conn = URL("https://base44.app/api/apps/6ace0c27732bbb0f4d7504e1/$path").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return Pair(code, stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            conn.disconnect()
        }
    }

    /** Código al azar de este móvil, para poder votar sin cuenta (un voto por canción). Se crea al votar por primera vez. */
    @Synchronized
    private fun voterId(context: Context, create: Boolean): String? {
        val prefs = context.getSharedPreferences("sonoria", Context.MODE_PRIVATE)
        val saved = prefs.getString("voter_id", null)
        if (saved != null || !create) return saved
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val random = java.security.SecureRandom()
        val id = String(CharArray(32) { chars[random.nextInt(chars.length)] })
        prefs.edit().putString("voter_id", id).apply()
        return id
    }

    /**
     * Llama a la función de votos: con la sesión si la hay y, si no, como oyente sin cuenta.
     * Si la sesión ha caducado, la borra y repite sin cuenta. Bloquea: usar fuera del hilo principal.
     */
    fun likes(context: Context, body: JSONObject): Pair<Int, String> {
        val prefs = context.getSharedPreferences("sonoria", Context.MODE_PRIVATE)
        voterId(context, create = body.has("trackId"))?.let { body.put("did", it) }
        val token = prefs.getString("token", null)
        var result = post("functions/likeTrack", body, token)
        if (result.first == 401 && token != null) {
            prefs.edit().remove("token").apply()
            result = post("functions/likeTrack", body, null)
        }
        return result
    }

    /** El TOP 10 igual que en la web: las 10 canciones con más "me gusta". Bloquea: usar fuera del hilo principal. */
    fun topTen(): JSONArray {
        val conn = URL(
            "https://base44.app/api/apps/6ace0c27732bbb0f4d7504e1/entities/Track?sort=-likes&limit=10"
        ).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            return JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    /** "4:17" -> 257 segundos (0 si no se puede leer). */
    fun parseDuration(s: String): Long {
        val parts = s.split(":").map { it.trim().toLongOrNull() }
        if (parts.isEmpty() || parts.any { it == null }) return 0
        return parts.fold(0L) { acc, p -> acc * 60 + (p ?: 0L) }
    }

    fun parseId(mediaId: String): ParsedId? {
        val p = mediaId.split("@")
        if (p.size != 4) return null
        val start = p[1].toLongOrNull() ?: return null
        val end = p[2].toLongOrNull() ?: return null
        return ParsedId(start, end, p[3] == "1")
    }
}
