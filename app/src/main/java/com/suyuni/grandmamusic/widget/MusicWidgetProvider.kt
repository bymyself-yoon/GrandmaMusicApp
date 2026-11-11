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

open class MusicWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        WidgetUpdater.updateAll(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        WidgetUpdater.updateAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_WIDGET_STOP -> {
                val s = PlaybackService.instance
                if (s != null) {
                    s.pausePlayback()
                } else {
                    PlayerState.set(PlayerState.state.value.playlistId, false)
                }
                WidgetLock.lockNow(context) // 노래 끄면 바로 잠금 (잠금 꺼져 있으면 그냥 다시 그림)
                return
            }
            WidgetLock.ACTION_UNLOCK -> { WidgetLock.unlock(context); return }
            WidgetLock.ACTION_RELOCK -> { WidgetUpdater.updateAll(context); return }
        }
        super.onReceive(context, intent)
    }

    companion object {
        const val ACTION_WIDGET_STOP = "com.suyuni.grandmamusic.WIDGET_STOP"
    }
}

/** 화면 반만 차지하는 위젯. 그리는 방법은 같고, 처음 크기만 달라 (xml/widget_info_half.xml) */
class HalfMusicWidgetProvider : MusicWidgetProvider()

object WidgetUpdater {
    private val rowIds = intArrayOf(R.id.pl_row_0, R.id.pl_row_1, R.id.pl_row_2, R.id.pl_row_3)
    private val nameIds = intArrayOf(R.id.pl_name_0, R.id.pl_name_1, R.id.pl_name_2, R.id.pl_name_3)

    fun updateAll(context: Context) {
        val ctx = context.applicationContext
        val manager = AppWidgetManager.getInstance(ctx)
        val ids = manager.getAppWidgetIds(ComponentName(ctx, MusicWidgetProvider::class.java)) +
            manager.getAppWidgetIds(ComponentName(ctx, HalfMusicWidgetProvider::class.java))
        // 위젯마다 크기가 다를 수 있어서 하나씩 그 크기에 맞게 그려
        for (id in ids) {
            try {
                manager.updateAppWidget(id, build(ctx, manager.getAppWidgetOptions(id)))
            } catch (e: Exception) {
                // 한 위젯이 실패해도 다른 위젯은 계속
            }
        }
    }

    /** 위젯 실제 크기(dp). 세로 화면 기준: 너비 = MIN_WIDTH, 높이 = MAX_HEIGHT */
    private fun sizeOf(options: Bundle?): Pair<Float, Float> {
        val w = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
        val h = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0) ?: 0
        return (if (w > 0) w.toFloat() else 300f) to (if (h > 0) h.toFloat() else 420f)
    }

    /** 잠김 화면: 회색 바탕 + 가운데 "노래 듣기" 버튼 하나 */
    private fun buildLocked(ctx: Context, options: Bundle?): RemoteViews {
        val (wDp, hDp) = sizeOf(options)
        val v = RemoteViews(ctx.packageName, R.layout.widget_locked)
        // 버튼 글씨 칸: 위젯 안쪽(여백 16)에서 너비 75%, 높이 35% 정도. 버튼 안쪽 여백(가로 22, 세로 14)은 빼고
        val textW = (minOf((wDp - 32f) * 0.75f, 300f) - 44f).coerceAtLeast(30f)
        val textH = (minOf((hDp - 32f) * 0.35f, 110f) - 28f).coerceAtLeast(14f)
        v.setImageViewBitmap(R.id.btn_unlock, WidgetText.box(ctx, "노래 듣기", textW, textH, 40f, align = WidgetText.Align.CENTER))
        v.setInt(R.id.btn_unlock, "setColorFilter", Color.parseColor("#111111"))
        v.setOnClickPendingIntent(
            R.id.btn_unlock,
            PendingIntent.getBroadcast(
                ctx, 31,
                Intent(ctx, MusicWidgetProvider::class.java).setAction(WidgetLock.ACTION_UNLOCK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        return v
    }

    private fun build(ctx: Context, options: Bundle?): RemoteViews {
        val prefs = Prefs(ctx)
        PlayerState.ensureInit(prefs.currentPlaylistId, prefs.currentSongTitle)
        val state = PlayerState.state.value
        if (WidgetLock.isLocked(ctx, state.playing)) return buildLocked(ctx, options)
        val lists = Repository.widgetPlaylists(ctx)
        val all = Repository.load(ctx)
        val current = all.firstOrNull { it.id == state.playlistId }

        // ── 칸 크기 계산: 위젯 높이를 보고 보통/작은 배치를 고르고, 각 칸 크기를 구해 ──
        val (wDp, hDp) = sizeOf(options)
        // 가로로 넓고 납작하면 "넓은" 배치 (재생목록 2칸씩), 세로로 짧으면 "작은" 배치, 아니면 보통
        val wide = wDp > hDp * 1.05f && hDp < 330f
        val compact = !wide && hDp < 400f
        val v = RemoteViews(
            ctx.packageName,
            when {
                wide -> R.layout.widget_music_wide
                compact -> R.layout.widget_music_compact
                else -> R.layout.widget_music
            },
        )
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val ink = Color.parseColor("#111111")

        val song = state.songTitle?.takeIf { current != null && it.isNotBlank() }
        val showSong = song != null && !compact && !wide // 작은·넓은 위젯은 노래 제목 줄을 빼고 나머지를 크게
        val rows = lists.size
        // (아래 숫자는 widget_music*.xml의 여백·비율과 같아야 해)
        val pad = when { wide -> 10f; compact -> 8f; else -> 12f }
        val innerW = wDp - pad * 2
        val innerH = hDp - pad * 2
        val lines = if (rows > 2) 2 else 1 // 넓은 배치: 재생목록 줄 수
        val rowGap = if (compact) 4f else 6f
        val fixed = when {
            wide -> 6f + 8f + (lines - 1) * 6f
            compact -> 4f + 1f + 6f + rows * rowGap
            else -> 8f + 2f + 10f + rows * rowGap
        }
        val wTitle = when { wide -> 0.9f; compact -> 0.8f; else -> 1f }
        val wControls = when { wide -> 1.6f; compact -> 1.8f; else -> 2.6f }
        val wLists = when { wide -> 2.6f; compact -> 4.4f; else -> 3.6f }
        val weights = wTitle + (if (showSong) 0.6f else 0f) + wControls + wLists
        val unit = ((innerH - fixed) / weights).coerceAtLeast(4f)
        val titleH = unit * wTitle
        val songH = unit * 0.6f
        val controlsH = unit * wControls
        val listsH = unit * wLists
        val side = compact || wide // 버튼 글씨가 아이콘 오른쪽에 있는 배치
        val btnW = (innerW - (if (side) 8f else 10f)) / 2f
        val labelW = if (side) btnW - 8f - 2f - 28f - 4f else btnW - 12f
        val labelH = if (side) controlsH - 8f else (controlsH - 12f - 4f) / 2f
        // 재생목록 칸 하나의 글씨 칸 크기 (i번째)
        fun rowBox(i: Int): Pair<Float, Float> {
            if (wide) {
                val lineH = (listsH - (lines - 1) * 6f) / lines
                val inLine = if (i < 2) minOf(rows, 2) else rows - 2
                val cellW = if (inLine >= 2) (innerW - 6f) / 2f else innerW
                return (cellW - 18f) to (lineH - 4f)
            }
            val h = if (rows > 0) (listsH - rows * rowGap) / rows - (if (compact) 4f else 8f) else 0f
            return (innerW - (if (compact) 18f else 24f)) to h
        }

        fun put(id: Int, bmp: android.graphics.Bitmap, color: Int, desc: String) {
            v.setImageViewBitmap(id, bmp)
            v.setInt(id, "setColorFilter", color)
            v.setContentDescription(id, desc)
        }

        // 제목: "트로트 재생 중" / "트로트 멈춤"
        val title = when {
            current == null -> "노래를 골라주세요"
            state.playing -> "${current.name} 재생 중"
            else -> "${current.name} 멈춤"
        }
        put(R.id.title, WidgetText.box(ctx, title, innerW, titleH, 34f), ink, title)

        // 제목 아래 작은 글씨로 지금 노래 제목
        if (showSong) {
            put(R.id.song_title, WidgetText.box(ctx, song!!, innerW, songH, 22f, bold = false), Color.parseColor("#444444"), song)
            v.setViewVisibility(R.id.song_title, View.VISIBLE)
        } else {
            v.setViewVisibility(R.id.song_title, View.GONE)
        }

        // 노래 틀기 / 노래 끄기: 칸이 넓고 납작하면 한 줄, 아니면 두 줄
        val oneLine = labelW / labelH.coerceAtLeast(1f) > (if (side) 1.6f else 2.6f)
        val playText = if (oneLine) "노래 틀기" else "노래\n틀기"
        val stopText = if (oneLine) "노래 끄기" else "노래\n끄기"
        put(R.id.play_label, WidgetText.box(ctx, playText, labelW, labelH, 30f, align = WidgetText.Align.CENTER), ink, "노래 틀기")
        put(R.id.stop_label, WidgetText.box(ctx, stopText, labelW, labelH, 30f, align = WidgetText.Align.CENTER), Color.WHITE, "노래 끄기")

        v.setOnClickPendingIntent(
            R.id.title,
            PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), flags),
        )
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
        if (lists.isEmpty()) {
            val msg = "아직 노래가 없어요 ㅠㅠ\n수윤이한테 넣어달라고\n말해보세요!"
            put(
                R.id.empty,
                WidgetText.box(ctx, msg, innerW - 12f, listsH - 12f, 20f, bold = false, align = WidgetText.Align.CENTER),
                Color.parseColor("#555555"), msg.replace('\n', ' '),
            )
        }
        if (wide) {
            v.setViewVisibility(R.id.pl_line_0, if (rows > 0) View.VISIBLE else View.GONE)
            v.setViewVisibility(R.id.pl_line_1, if (rows > 2) View.VISIBLE else View.GONE)
        }
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
            put(
                nameIds[i],
                rowBox(i).let { (bw, bh) ->
                    WidgetText.box(ctx, p.name, bw, bh, 28f, chip = if (selected) "고른 노래" else null)
                },
                if (selected) Color.WHITE else p.color,
                if (selected) "${p.name}, 고른 노래" else p.name,
            )
            v.setOnClickPendingIntent(
                rowIds[i],
                PendingIntent.getForegroundService(ctx, 10 + i, PlaybackService.playIntent(ctx, p.id), flags),
            )
        }
        return v
    }
}
