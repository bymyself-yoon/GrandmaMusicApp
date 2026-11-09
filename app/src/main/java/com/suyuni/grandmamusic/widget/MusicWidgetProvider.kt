package com.suyuni.grandmamusic.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.suyuni.grandmamusic.R
import com.suyuni.grandmamusic.data.Palette
import com.suyuni.grandmamusic.data.Prefs
import com.suyuni.grandmamusic.data.Repository
import com.suyuni.grandmamusic.playback.PlaybackService
import com.suyuni.grandmamusic.playback.PlayerState
import com.suyuni.grandmamusic.ui.MainActivity

class MusicWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        WidgetUpdater.updateAll(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        WidgetUpdater.updateAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_WIDGET_STOP) {
            val s = PlaybackService.instance
            if (s != null) {
                s.pausePlayback()
            } else {
                PlayerState.set(PlayerState.state.value.playlistId, false)
                WidgetUpdater.updateAll(context)
            }
            return
        }
        super.onReceive(context, intent)
    }

    companion object {
        const val ACTION_WIDGET_STOP = "com.suyuni.grandmamusic.WIDGET_STOP"
    }
}

object WidgetUpdater {
    private val rowIds = intArrayOf(R.id.pl_row_0, R.id.pl_row_1, R.id.pl_row_2, R.id.pl_row_3)
    private val nameIds = intArrayOf(R.id.pl_name_0, R.id.pl_name_1, R.id.pl_name_2, R.id.pl_name_3)
    private val chipIds = intArrayOf(R.id.pl_chip_0, R.id.pl_chip_1, R.id.pl_chip_2, R.id.pl_chip_3)

    fun updateAll(context: Context) {
        val ctx = context.applicationContext
        val manager = AppWidgetManager.getInstance(ctx)
        val ids = manager.getAppWidgetIds(ComponentName(ctx, MusicWidgetProvider::class.java))
        if (ids.isEmpty()) return
        manager.updateAppWidget(ids, build(ctx))
    }

    private fun build(ctx: Context): RemoteViews {
        val prefs = Prefs(ctx)
        PlayerState.ensureInit(prefs.currentPlaylistId)
        val state = PlayerState.state.value
        val lists = Repository.widgetPlaylists(ctx)
        val all = Repository.load(ctx)
        val current = all.firstOrNull { it.id == state.playlistId }

        val v = RemoteViews(ctx.packageName, R.layout.widget_music)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        // 제목: "트로트 재생 중" / "트로트 멈춤"
        v.setTextViewText(
            R.id.title,
            when {
                current == null -> "노래를 골라주세요"
                state.playing -> "${current.name} 재생 중"
                else -> "${current.name} 멈춤"
            },
        )
        v.setOnClickPendingIntent(
            R.id.title,
            PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), flags),
        )

        // 노래 틀기 (지금 고른 재생목록) / 노래 끄기
        v.setOnClickPendingIntent(
            R.id.btn_play,
            PendingIntent.getForegroundService(ctx, 1, PlaybackService.playIntent(ctx, null), flags),
        )
        v.setOnClickPendingIntent(
            R.id.btn_stop,
            PendingIntent.getBroadcast(
                ctx, 2,
                Intent(ctx, MusicWidgetProvider::class.java).setAction(MusicWidgetProvider.ACTION_WIDGET_STOP),
                flags,
            ),
        )

        // 노래 종류 버튼 (최대 4개)
        v.setViewVisibility(R.id.empty, if (lists.isEmpty()) View.VISIBLE else View.GONE)
        for (i in 0 until Repository.WIDGET_MAX) {
            val p = lists.getOrNull(i)
            if (p == null) {
                v.setViewVisibility(rowIds[i], View.GONE)
                continue
            }
            val selected = p.id == state.playlistId
            val pal = Palette.entry(p.colorIndex)
            v.setViewVisibility(rowIds[i], View.VISIBLE)
            v.setInt(rowIds[i], "setBackgroundResource", if (selected) pal.widgetSelectedBg else pal.widgetBg)
            v.setTextViewText(nameIds[i], p.name)
            v.setTextColor(nameIds[i], if (selected) Color.WHITE else p.color)
            v.setViewVisibility(chipIds[i], if (selected) View.VISIBLE else View.GONE)
            v.setOnClickPendingIntent(
                rowIds[i],
                PendingIntent.getForegroundService(ctx, 10 + i, PlaybackService.playIntent(ctx, p.id), flags),
            )
        }
        return v
    }
}
