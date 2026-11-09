package com.suyuni.grandmamusic.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import java.io.File
import java.util.UUID

/**
 * 재생목록을 앱 안의 JSON 파일 하나(files/playlists.json)에 저장해.
 * 인터넷이 전혀 필요 없고, 앱을 지우기 전까지 계속 남아 있어.
 */
object Repository {
    const val WIDGET_MAX = 4

    private fun dataFile(ctx: Context) = File(ctx.filesDir, "playlists.json")

    fun musicDir(ctx: Context): File = File(ctx.filesDir, "music").apply { mkdirs() }

    fun songFile(ctx: Context, song: Song): File = File(musicDir(ctx), song.fileName)

    @Synchronized
    fun load(ctx: Context): List<Playlist> {
        val f = dataFile(ctx)
        if (!f.exists()) {
            val defaults = listOf(
                Playlist(name = "트로트", colorIndex = 0),
                Playlist(name = "찬송가", colorIndex = 1),
                Playlist(name = "옛날 노래", colorIndex = 2),
            )
            save(ctx, defaults)
            return defaults
        }
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { Playlist.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun save(ctx: Context, list: List<Playlist>) {
        val arr = JSONArray().apply { list.forEach { put(it.toJson()) } }
        val tmp = File(ctx.filesDir, "playlists.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(dataFile(ctx))
    }

    fun get(ctx: Context, id: String?): Playlist? = id?.let { pid -> load(ctx).firstOrNull { it.id == pid } }

    fun widgetPlaylists(ctx: Context): List<Playlist> = load(ctx).filter { it.showOnWidget }.take(WIDGET_MAX)

    @Synchronized
    fun upsert(ctx: Context, p: Playlist) {
        val list = load(ctx).toMutableList()
        val i = list.indexOfFirst { it.id == p.id }
        if (i >= 0) list[i] = p else list.add(p)
        save(ctx, list)
    }

    @Synchronized
    fun delete(ctx: Context, id: String) {
        save(ctx, load(ctx).filterNot { it.id == id })
        cleanupOrphans(ctx)
    }

    @Synchronized
    fun move(ctx: Context, id: String, delta: Int) {
        val list = load(ctx).toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        val t = list[i]; list[i] = list[j]; list[j] = t
        save(ctx, list)
    }

    /** 어떤 재생목록에도 없는 음악 파일을 지워서 저장 공간을 비워. */
    @Synchronized
    fun cleanupOrphans(ctx: Context) {
        val used = load(ctx).flatMap { p -> p.songs.map { it.fileName } }.toSet()
        musicDir(ctx).listFiles()?.forEach { if (it.name !in used) it.delete() }
    }

    /** 고른 음악 파일을 앱 전용 폴더로 복사해. (백그라운드 스레드에서 불러야 해) */
    fun importSong(ctx: Context, uri: Uri): Song? {
        val display = queryDisplayName(ctx, uri) ?: "노래.mp3"
        val ext = display.substringAfterLast('.', "mp3").lowercase().take(5)
        val fileName = "${UUID.randomUUID()}.$ext"
        val target = File(musicDir(ctx), fileName)
        try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            } ?: return null
        } catch (e: Exception) {
            target.delete()
            return null
        }
        var title: String? = null
        var duration = 0L
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(target.absolutePath)
            title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
        val fallback = display.substringBeforeLast('.')
        return Song(
            fileName = fileName,
            originalName = display,
            title = title?.trim()?.takeIf { it.isNotEmpty() } ?: fallback,
            durationMs = duration,
        )
    }

    private fun queryDisplayName(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }
}
