package com.suyuni.grandmamusic.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 지금 어떤 재생목록·노래가 나오는지 / 나오는 중인지. 앱 화면과 위젯이 같이 봐. */
data class PlayerUi(val playlistId: String?, val playing: Boolean, val songTitle: String? = null)

object PlayerState {
    private val _state = MutableStateFlow(PlayerUi(null, false))
    val state: StateFlow<PlayerUi> = _state

    fun set(playlistId: String?, playing: Boolean, songTitle: String? = _state.value.songTitle) {
        _state.value = PlayerUi(playlistId, playing, songTitle)
    }

    fun ensureInit(savedPlaylistId: String?, savedSongTitle: String? = null) {
        val cur = _state.value
        if (cur.playlistId == null && savedPlaylistId != null) {
            _state.value = cur.copy(playlistId = savedPlaylistId, songTitle = cur.songTitle ?: savedSongTitle)
        }
    }
}
