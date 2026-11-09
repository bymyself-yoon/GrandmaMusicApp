package com.suyuni.grandmamusic.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.suyuni.grandmamusic.data.Playlist
import com.suyuni.grandmamusic.data.Prefs
import com.suyuni.grandmamusic.data.Repository
import com.suyuni.grandmamusic.playback.PlaybackService
import com.suyuni.grandmamusic.playback.PlayerState
import com.suyuni.grandmamusic.ui.Ui.dp
import com.suyuni.grandmamusic.widget.WidgetUpdater
import kotlinx.coroutines.launch

/** 시안 ② 앱을 켰을 때 화면 */
class MainActivity : BaseActivity() {

    private lateinit var prefs: Prefs
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var listBox: LinearLayout
    private lateinit var barTitle: TextView
    private lateinit var barButton: TextView
    private var settingsButton: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val openSettings = Runnable { startActivity(Intent(this, SettingsActivity::class.java)) }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        PlayerState.ensureInit(prefs.currentPlaylistId)

        val root = Ui.vertical(this).apply {
            setBackgroundColor(Ui.BG)
            fitsSystemWindows = true
        }

        val header = Ui.vertical(this, 4).apply { setPadding(dp(22), dp(28), dp(22), dp(16)) }
        title = Ui.text(this, "할머니를 위한 손녀 선물❤️", 32f, true)
        subtitle = Ui.text(this, "듣고 싶은 노래를 누르세요", 22f, true, Color.parseColor("#444444"))
        header.addView(title)
        header.addView(subtitle)
        root.addView(header)

        listBox = Ui.vertical(this, 14).apply { setPadding(dp(16), dp(4), dp(16), dp(8)) }
        val scroll = ScrollView(this).apply { addView(listBox) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // 아래 검은 재생 바: "트로트 재생 중  [노래 끄기]"
        val bar = Ui.horizontal(this, 12).apply {
            background = Ui.shape(context, Ui.INK, 28)
            setPadding(dp(22), dp(16), dp(16), dp(16))
        }
        barTitle = Ui.text(this, "", 30f, true, Color.WHITE).apply { maxLines = 1 }
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(barTitle, 14, 32, 1, TypedValue.COMPLEX_UNIT_SP)
        bar.addView(barTitle, LinearLayout.LayoutParams(0, dp(56), 1f))
        barTitle.gravity = Gravity.CENTER_VERTICAL
        barButton = Ui.text(this, "", 24f, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(18), 0, dp(18), 0)
            isClickable = true
            setOnClickListener { onBarButton() }
        }
        bar.addView(barButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(92)))
        val barWrap = Ui.vertical(this).apply { setPadding(dp(16), dp(12), dp(16), dp(4)) }
        barWrap.addView(bar)
        root.addView(barWrap)

        // 가족 설정: 작은 글씨 + 3초 꾹 눌러야 열림. 누르고 있는 동안 노란색
        val settings = Ui.text(this, "수윤이만 접근 가능!", 12f, color = Color.parseColor("#6B6B6B")).apply {
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.background = Ui.shape(this@MainActivity, Ui.YELLOW, 12)
                        (v as TextView).setTextColor(Ui.INK)
                        handler.postDelayed(openSettings, 3000)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        resetSettingsButton(v as TextView)
                        handler.removeCallbacks(openSettings)
                    }
                }
                true
            }
        }
        settingsButton = settings
        root.addView(settings, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(8)
        })

        setContentView(root)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                PlayerState.state.collect { render() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        render()
        WidgetUpdater.updateAll(this)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(openSettings)
        settingsButton?.let { resetSettingsButton(it) }
    }

    private fun resetSettingsButton(v: TextView) {
        v.background = null
        v.setTextColor(Color.parseColor("#6B6B6B"))
    }

    private fun render() {
        val s = prefs.textScale
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f * s)
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f * s)

        val state = PlayerState.state.value
        val all = Repository.load(this)
        // 가족 설정에서 눈 아이콘을 끈 재생목록은 할머니 화면(위젯 + 앱)에서 숨겨
        val lists = all.filter { it.showOnWidget }
        listBox.removeAllViews()
        lists.forEach { listBox.addView(card(it, it.id == state.playlistId, s)) }
        if (lists.isEmpty()) {
            listBox.addView(Ui.text(this, "가족 설정에서 노래를 넣어주세요", 22f * s, true, Ui.SUB))
        }

        val current = all.firstOrNull { it.id == state.playlistId }
        barTitle.text = when {
            current == null -> "노래를 골라주세요"
            state.playing -> "${current.name} 재생 중"
            else -> "${current.name} 멈춤"
        }
        barButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f * s)
        if (state.playing) {
            barButton.text = "노래\n끄기"
            barButton.setTextColor(Color.WHITE)
            barButton.background = Ui.pressable(this, Ui.INK, 20, Color.WHITE, 4)
        } else {
            barButton.text = "노래\n틀기"
            barButton.setTextColor(Ui.INK)
            barButton.background = Ui.pressable(this, Ui.YELLOW, 20, Ui.YELLOW, 4)
        }
    }

    private fun card(p: Playlist, selected: Boolean, s: Float): LinearLayout {
        val c = Ui.horizontal(this, 12).apply {
            background = Ui.pressable(context, p.color, 26, if (selected) Ui.INK else p.color, if (selected) 5 else 0)
            setPadding(dp(26), dp(14), dp(18), dp(14))
            minimumHeight = dp(124)
            isClickable = true
            setOnClickListener { playPlaylist(p) }
        }
        val texts = Ui.vertical(this, 2)
        texts.addView(Ui.text(this, p.name, 34f * s, true, Color.WHITE).apply { maxLines = 1 })
        texts.addView(Ui.text(this, "노래 ${p.songs.size}곡", 20f * s, true, Color.WHITE))
        c.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val chip = Ui.text(this, "노래\n틀기", 22f * s, true, p.color).apply {
            gravity = Gravity.CENTER
            background = Ui.shape(context, Color.WHITE, 16)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        c.addView(chip)
        return c
    }

    private fun playPlaylist(p: Playlist) {
        if (p.songs.isEmpty()) {
            Toast.makeText(this, "아직 노래가 없어요. 가족 설정에서 넣어주세요.", Toast.LENGTH_LONG).show()
            return
        }
        PlaybackService.startPlay(this, p.id)
    }

    private fun onBarButton() {
        val state = PlayerState.state.value
        if (state.playing) {
            PlaybackService.stop(this)
        } else {
            val p = Repository.get(this, state.playlistId)
                ?: Repository.load(this).firstOrNull { it.showOnWidget && it.songs.isNotEmpty() }
            if (p == null || p.songs.isEmpty()) {
                Toast.makeText(this, "위에서 노래 종류를 먼저 골라주세요", Toast.LENGTH_LONG).show()
            } else {
                PlaybackService.startPlay(this, p.id)
            }
        }
    }
}
