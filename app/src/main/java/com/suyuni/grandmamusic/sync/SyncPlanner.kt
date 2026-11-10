package com.suyuni.grandmamusic.sync

import com.suyuni.grandmamusic.data.Playlist

/** 노래 저장소에 있는 노래 파일 하나 (예: "찬송가/나 같은 죄인.mp3") */
data class RemoteSong(val path: String, val sha: String, val size: Long) {
    val folder: String get() = path.substringBefore('/')
    val fileName: String get() = path.substringAfter('/')

    /** 화면에 보일 제목: 확장자를 빼고, 순서용 앞번호("01 ", "02_")도 빼 */
    val title: String
        get() = fileName.substringBeforeLast('.')
            .replace(Regex("^\\d{1,3}[\\s._\\-]+"), "")
            .trim()
            .ifEmpty { fileName.substringBeforeLast('.') }
}

/** 받기 전에 "무엇을 받고 무엇을 지울지" 정리한 계획 */
data class SyncPlan(
    /** 새로 받거나(없던 노래) 다시 받을(파일이 바뀐) 노래 */
    val downloads: List<RemoteSong>,
    /** 저장소에서 지워져서 폰에서도 지울 노래: (재생목록 id, 노래 id) */
    val removals: List<Pair<String, String>>,
    /** 저장소에서 폴더가 통째로 없어진 재생목록 id (노래가 다 빠져서 비면 재생목록도 지움) */
    val goneFolders: Set<String>,
)

object SyncPlanner {
    val AUDIO_EXT = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wav", "wma", "amr", "m4b", "mp4", "3gp")

    /** 저장소 파일 목록에서 노래만 골라: "폴더/파일" 한 단계만, 숨김 파일 제외, 음악 확장자만 */
    fun filterSongs(entries: List<RemoteSong>): List<RemoteSong> = entries.filter { e ->
        val parts = e.path.split('/')
        parts.size == 2 &&
            parts.none { it.isEmpty() || it.startsWith(".") } &&
            e.path.substringAfterLast('.', "").lowercase() in AUDIO_EXT
    }.sortedBy { it.path }

    fun plan(remote: List<RemoteSong>, local: List<Playlist>): SyncPlan {
        val remoteByPath = remote.associateBy { it.path }
        val localRemote = local.flatMap { p -> p.songs.filter { it.remotePath != null } }

        // 이미 같은 버전(sha)이 폰에 있으면 다시 안 받아
        val have = localRemote.map { it.remotePath to it.remoteSha }.toSet()
        val downloads = remote.filter { (it.path to it.sha) !in have }

        // 저장소에서 없어진 노래는 폰에서도 지워 (폰에서 직접 넣은 노래는 안 건드려)
        val removals = local.flatMap { p ->
            p.songs.filter { s -> s.remotePath != null && s.remotePath !in remoteByPath }
                .map { p.id to it.id }
        }

        val remoteFolders = remote.map { it.folder }.toSet()
        val goneFolders = local.filter { it.remoteFolder != null && it.remoteFolder !in remoteFolders }
            .map { it.id }.toSet()

        return SyncPlan(downloads, removals, goneFolders)
    }
}
