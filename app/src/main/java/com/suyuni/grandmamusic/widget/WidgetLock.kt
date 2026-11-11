package com.suyuni.grandmamusic.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.suyuni.grandmamusic.data.Prefs

/**
 * 위젯 잠금: 할머니가 모르고 폰을 만지다가 노래가 나오는 걸 막아.
 * - 잠김: 회색 바탕에 "노래 듣기" 버튼 하나. 누르면 잠금만 풀려 (노래는 "노래 틀기"를 또 눌러야 나와)
 * - 노래가 나오는 동안은 절대 안 잠가 (끄려는데 잠겨 있으면 곤란하니까)
 * - 위젯에서 "노래 끄기"를 누르면 바로 잠겨
 * - 잠금을 풀거나 노래가 저절로 끝나면, 1분 뒤 다시 잠겨
 * - 가족 설정에서 끌 수 있어
 */
object WidgetLock {
    const val GRACE_MS = 60_000L
    const val ACTION_UNLOCK = "com.suyuni.grandmamusic.WIDGET_UNLOCK"
    const val ACTION_RELOCK = "com.suyuni.grandmamusic.WIDGET_RELOCK"

    fun isLocked(ctx: Context, playing: Boolean): Boolean {
        val prefs = Prefs(ctx)
        if (!prefs.widgetLock || playing) return false
        return System.currentTimeMillis() - prefs.widgetUnlockedAt > GRACE_MS
    }

    /** "노래 듣기"를 눌렀을 때 */
    fun unlock(ctx: Context) {
        Prefs(ctx).widgetUnlockedAt = System.currentTimeMillis()
        scheduleRelock(ctx)
        WidgetUpdater.updateAll(ctx)
    }

    /** 위젯에서 "노래 끄기"를 눌렀을 때: 바로 잠금 */
    fun lockNow(ctx: Context) {
        Prefs(ctx).widgetUnlockedAt = 0L
        WidgetUpdater.updateAll(ctx)
    }

    /** 노래가 멈췄을 때 (끝까지 다 나왔거나 잠자기 타이머 등): 1분 동안은 열어두고 그 뒤 잠금 */
    fun keepOpenAfterStop(ctx: Context) {
        Prefs(ctx).widgetUnlockedAt = System.currentTimeMillis()
        scheduleRelock(ctx)
    }

    private fun scheduleRelock(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            ctx, 30,
            Intent(ctx, MusicWidgetProvider::class.java).setAction(ACTION_RELOCK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // 정확한 알람 권한 없이 쓰는 일반 알람이라 몇 초~몇 분 늦을 수 있어 (늦어도 위젯을 다시 그릴 때 잠김 상태로 계산돼)
        am.set(AlarmManager.RTC, System.currentTimeMillis() + GRACE_MS + 1_000L, pi)
    }
}
