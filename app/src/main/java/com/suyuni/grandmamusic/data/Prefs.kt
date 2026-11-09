package com.suyuni.grandmamusic.data

import android.content.Context
import android.content.SharedPreferences

/** 가족 설정 값과 "마지막에 듣던 곳"을 저장해. */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var shuffle: Boolean
        get() = sp.getBoolean("shuffle", false)
        set(v) = sp.edit().putBoolean("shuffle", v).apply()

    /** 다 끝나면 처음부터 반복 */
    var repeat: Boolean
        get() = sp.getBoolean("repeat", true)
        set(v) = sp.edit().putBoolean("repeat", v).apply()

    /** 껐다 켜면 듣던 노래부터 */
    var resume: Boolean
        get() = sp.getBoolean("resume", true)
        set(v) = sp.edit().putBoolean("resume", v).apply()

    /** 노래를 틀 때 맞출 기본 소리 크기 (10~100%) */
    var volumePercent: Int
        get() = sp.getInt("volume", 70)
        set(v) = sp.edit().putInt("volume", v).apply()

    /** 글씨 크기: false = 크게, true = 아주 크게 */
    var hugeText: Boolean
        get() = sp.getBoolean("hugeText", false)
        set(v) = sp.edit().putBoolean("hugeText", v).apply()

    /** 잠자기 타이머 (분). 0이면 안 씀 */
    var sleepMinutes: Int
        get() = sp.getInt("sleep", 0)
        set(v) = sp.edit().putInt("sleep", v).apply()

    var currentPlaylistId: String?
        get() = sp.getString("current", null)
        set(v) = sp.edit().putString("current", v).apply()

    fun savePosition(playlistId: String, index: Int, positionMs: Long) {
        sp.edit().putInt("pos_idx_$playlistId", index).putLong("pos_ms_$playlistId", positionMs).apply()
    }

    fun savedIndex(playlistId: String) = sp.getInt("pos_idx_$playlistId", 0)
    fun savedPosition(playlistId: String) = sp.getLong("pos_ms_$playlistId", 0L)

    val textScale: Float get() = if (hugeText) 1.18f else 1f
}
