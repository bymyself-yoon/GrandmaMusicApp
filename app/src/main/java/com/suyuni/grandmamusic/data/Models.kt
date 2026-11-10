package com.suyuni.grandmamusic.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 노래 한 곡. 실제 파일은 앱 전용 폴더(files/music/)에 [fileName]으로 저장돼 있어. */
data class Song(
    val id: String = UUID.randomUUID().toString(),
    val fileName: String,
    val originalName: String,
    val title: String,
    val durationMs: Long = 0L,
    /** 비공개 노래 저장소에서 받은 노래면 그 경로(예: "찬송가/나 같은 죄인.mp3")와 파일 버전(sha) */
    val remotePath: String? = null,
    val remoteSha: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("fileName", fileName)
        .put("originalName", originalName)
        .put("title", title)
        .put("durationMs", durationMs)
        .apply {
            if (remotePath != null) put("remotePath", remotePath)
            if (remoteSha != null) put("remoteSha", remoteSha)
        }

    companion object {
        fun fromJson(o: JSONObject) = Song(
            id = o.getString("id"),
            fileName = o.getString("fileName"),
            originalName = o.optString("originalName", o.getString("fileName")),
            title = o.optString("title", o.getString("fileName")),
            durationMs = o.optLong("durationMs", 0L),
            remotePath = o.optString("remotePath", "").ifEmpty { null },
            remoteSha = o.optString("remoteSha", "").ifEmpty { null },
        )
    }
}

/** 재생목록(노래 종류). [colorIndex]는 [Palette]의 색 번호. */
data class Playlist(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val colorIndex: Int,
    val showOnWidget: Boolean = true,
    val songs: List<Song> = emptyList(),
    /** 노래 저장소의 폴더 이름 (이 재생목록이 그 폴더랑 연결돼 있으면) */
    val remoteFolder: String? = null,
) {
    val color: Int get() = Palette.color(colorIndex)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("colorIndex", colorIndex)
        .put("showOnWidget", showOnWidget)
        .put("songs", JSONArray().apply { songs.forEach { put(it.toJson()) } })
        .apply { if (remoteFolder != null) put("remoteFolder", remoteFolder) }

    companion object {
        fun fromJson(o: JSONObject): Playlist {
            val arr = o.optJSONArray("songs") ?: JSONArray()
            return Playlist(
                id = o.getString("id"),
                name = o.getString("name"),
                colorIndex = o.optInt("colorIndex", 0),
                showOnWidget = o.optBoolean("showOnWidget", true),
                songs = (0 until arr.length()).map { Song.fromJson(arr.getJSONObject(it)) },
                remoteFolder = o.optString("remoteFolder", "").ifEmpty { null },
            )
        }
    }
}
