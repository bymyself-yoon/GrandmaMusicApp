package com.suyuni.grandmamusic.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 지금 어떤 재생목록이 나오는지 / 나오는 중인지. 앱 화면과 위젯이 같이 봐. */
data class PlayerUi(val playlistId: String?, val playing: Boolean)

object PlayerState {
    private val _state = MutableStateFlow(PlayerUi(null, false))
    val state: StateFlow<PlayerUi> = _state

    fun set(playlistId: String?, playing: Boolean) {
        _state.value = PlayerUi(playlistId, playing)
    }

    fun ensureInit(savedPlaylistId: String?) {
        if (_state.value.playlistId == null && savedPlaylistId != null) {
            _state.value = _state.value.copy(playlistId = savedPlaylistId)
        }
    }
}
