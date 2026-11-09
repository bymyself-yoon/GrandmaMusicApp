package com.suyuni.grandmamusic.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.suyuni.grandmamusic.R
import com.suyuni.grandmamusic.data.Prefs
import com.suyuni.grandmamusic.data.Repository
import com.suyuni.grandmamusic.ui.MainActivity
import com.suyuni.grandmamusic.widget.WidgetUpdater

/**
 * 폰에 저장된 음악 파일을 트는 서비스.
 * 화면이 꺼지거나 다른 앱으로 가도 계속 나오고, 알림·잠금화면·이어폰 버튼으로도 조작돼.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private var loadedPlaylistId: String? = null
    private val sleepRunnable = Runnable { pausePlayback() }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_PLAY_WHEN_READY_CHANGED,
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                )
            ) {
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) savePosition()
                publish()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // 이어폰 빠지면 멈춤
            .setWakeMode(C.WAKE_MODE_LOCAL)    // 화면 꺼져도 계속
            .build()
        player.addListener(listener)

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player).setSessionActivity(openApp).build()
        instance = this
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                // 위젯에서 startForegroundService로 불렸을 수 있으니 바로 포그라운드로 올려.
                // (같은 알림 번호를 써서 Media3 알림이 이 자리를 그대로 덮어씀)
                goForeground()
                if (!play(intent.getStringExtra(EXTRA_PLAYLIST_ID))) {
                    if (player.isPlaying) {
                        refreshNotification() // 다른 노래가 나오는 중이면 알림을 원래대로
                    } else {
                        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                pausePlayback()
                return START_NOT_STICKY
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /** 재생목록을 틀어. 노래가 하나도 없으면 false. */
    fun play(requestedId: String?): Boolean {
        val all = Repository.load(this)
        val id = requestedId ?: loadedPlaylistId ?: prefs.currentPlaylistId
            ?: all.firstOrNull { it.showOnWidget && it.songs.isNotEmpty() }?.id
        val playlist = all.firstOrNull { it.id == id } ?: return false
        val songs = playlist.songs.filter { Repository.songFile(this, it).exists() }
        if (songs.isEmpty()) return false

        if (playlist.id == loadedPlaylistId && player.mediaItemCount == songs.size) {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0, 0)
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
        } else {
            savePosition()
            val items = songs.map { s ->
                MediaItem.Builder()
                    .setMediaId(s.id)
                    .setUri(Uri.fromFile(Repository.songFile(this, s)))
                    .setMediaMetadata(
                        MediaMetadata.Builder().setTitle(s.title).setArtist(playlist.name).build()
                    )
                    .build()
            }
            var startIndex = 0
            var startPos = 0L
            if (prefs.resume) {
                startIndex = prefs.savedIndex(playlist.id).coerceIn(0, items.lastIndex)
                startPos = prefs.savedPosition(playlist.id)
            }
            player.setMediaItems(items, startIndex, startPos)
            player.prepare()
            loadedPlaylistId = playlist.id
        }
        prefs.currentPlaylistId = playlist.id
        applySettings()
        applyDefaultVolume()
        scheduleSleepTimer()
        player.play()
        publish()
        refreshNotification()
        return true
    }

    /** 임시 알림을 Media3의 진짜 재생 알림(제목, 재생/멈춤 버튼)으로 바꿔. */
    private fun refreshNotification() {
        session?.let { onUpdateNotification(it, /* startInForegroundRequired = */ true) }
    }

    fun pausePlayback() {
        player.pause()
        savePosition()
        handler.removeCallbacks(sleepRunnable)
        publish()
    }

    /** 가족 설정에서 바꾼 반복/섞기 설정을 바로 반영해. */
    fun applySettings() {
        player.repeatMode = if (prefs.repeat) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        player.shuffleModeEnabled = prefs.shuffle
    }

    private fun applyDefaultVolume() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (max * prefs.volumePercent / 100f).toInt().coerceIn(1, max)
        try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        } catch (_: SecurityException) {
            // 방해 금지 모드 등으로 막혀 있으면 그냥 넘어가
        }
    }

    private fun scheduleSleepTimer() {
        handler.removeCallbacks(sleepRunnable)
        val min = prefs.sleepMinutes
        if (min > 0) handler.postDelayed(sleepRunnable, min * 60_000L)
    }

    private fun savePosition() {
        val id = loadedPlaylistId ?: return
        if (player.mediaItemCount == 0) return
        prefs.savePosition(id, player.currentMediaItemIndex, player.currentPosition)
    }

    private fun publish() {
        val playing = player.playWhenReady &&
            player.playbackState != Player.STATE_ENDED &&
            player.mediaItemCount > 0
        PlayerState.set(loadedPlaylistId ?: prefs.currentPlaylistId, playing)
        WidgetUpdater.updateAll(this)
    }

    private fun goForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 &&
            nm.getNotificationChannel(DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID) == null
        ) {
            nm.createNotificationChannel(
                NotificationChannel(
                    DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID,
                    getString(R.string.notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
        val n: Notification = NotificationCompat.Builder(this, DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("노래 준비 중")
            .setSilent(true)
            .build()
        ServiceCompat.startForeground(
            this,
            DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID,
            n,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 최근 앱 목록에서 지워도, 노래가 나오는 중이면 계속 틀어
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        savePosition()
        handler.removeCallbacks(sleepRunnable)
        instance = null
        session?.release()
        session = null
        player.release()
        PlayerState.set(loadedPlaylistId ?: prefs.currentPlaylistId, false)
        WidgetUpdater.updateAll(this)
        super.onDestroy()
    }

    companion object {
        const val ACTION_PLAY = "com.suyuni.grandmamusic.PLAY"
        const val ACTION_STOP = "com.suyuni.grandmamusic.STOP"
        const val EXTRA_PLAYLIST_ID = "playlistId"

        @Volatile
        var instance: PlaybackService? = null
            private set

        fun playIntent(ctx: Context, playlistId: String?): Intent =
            Intent(ctx, PlaybackService::class.java).setAction(ACTION_PLAY).apply {
                if (playlistId != null) {
                    putExtra(EXTRA_PLAYLIST_ID, playlistId)
                    data = Uri.parse("grandmamusic://play/$playlistId") // PendingIntent끼리 안 섞이게
                }
            }

        /** 앱 화면(포그라운드)에서 부를 때 */
        fun startPlay(ctx: Context, playlistId: String?) {
            ContextCompat.startForegroundService(ctx, playIntent(ctx, playlistId))
        }

        fun stop(ctx: Context) {
            val s = instance
            if (s != null) s.pausePlayback()
        }
    }
}
