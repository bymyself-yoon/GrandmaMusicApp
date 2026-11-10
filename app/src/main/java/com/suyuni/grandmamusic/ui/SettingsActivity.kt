package com.suyuni.grandmamusic.ui

import android.Manifest
import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.Formatter
import android.view.Gravity
import android.view.ViewGroup
import android.app.AlertDialog
import android.net.ConnectivityManager
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.suyuni.grandmamusic.BuildConfig
import com.suyuni.grandmamusic.R
import com.suyuni.grandmamusic.data.Prefs
import com.suyuni.grandmamusic.data.Repository
import com.suyuni.grandmamusic.sync.SongSync
import com.suyuni.grandmamusic.sync.SyncPrefs
import com.suyuni.grandmamusic.playback.PlaybackService
import com.suyuni.grandmamusic.ui.Ui.dp
import com.suyuni.grandmamusic.ui.Ui.marginTop
import com.suyuni.grandmamusic.widget.MusicWidgetProvider
import com.suyuni.grandmamusic.update.UpdatePrefs
import com.suyuni.grandmamusic.update.Updater
import com.suyuni.grandmamusic.widget.WidgetUpdater

/** 시안 ③ 가족 설정 */
class SettingsActivity : BaseActivity() {

    private lateinit var prefs: Prefs
    private lateinit var content: LinearLayout
    private var scroll: ScrollView? = null

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        Toast.makeText(this, if (ok) "알림 권한을 허용했어요" else "알림 권한이 거부됐어요", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        content = Ui.vertical(this, 18).apply { setPadding(dp(16), dp(16), dp(16), dp(32)) }
        scroll = ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            fitsSystemWindows = true
            addView(content)
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun rebuildKeepScroll() {
        val y = scroll?.scrollY ?: 0
        build()
        scroll?.post { scroll?.scrollTo(0, y) }
    }

    private fun build() {
        content.removeAllViews()

        val head = Ui.vertical(this, 4)
        head.addView(Ui.text(this, "← 메인 화면으로", 16f, true, Color.parseColor("#1D4ED8")).apply {
            setPadding(0, dp(10), dp(10), dp(10))
            setOnClickListener { finish() }
        })
        head.addView(Ui.text(this, "수윤이만 접근 가능!", 30f, true))
        head.addView(Ui.text(this, "할머니 화면에는 보이지 않는 관리용 화면이에요", 15f, color = Ui.SUB))
        content.addView(head)

        content.addView(playlistSection())
        content.addView(playbackSection())
        content.addView(soundSection())
        content.addView(setupSection())
        content.addView(songSyncSection())
        content.addView(updateSection())
        content.addView(infoSection())
    }

    // ── 재생목록 ─────────────────────────────────────────
    private fun playlistSection(): LinearLayout {
        val box = Ui.section(this, "재생목록", "최대 ${Repository.WIDGET_MAX}개까지 표시")
        val lists = Repository.load(this)
        lists.forEachIndexed { i, p ->
            val row = Ui.horizontal(this, 8).apply { setPadding(0, dp(8), 0, dp(8)) }
            row.addView(TextView(this).apply { background = Ui.shape(context, p.color, 6) }, LinearLayout.LayoutParams(dp(22), dp(22)))
            val t = Ui.vertical(this)
            t.addView(Ui.text(this, p.name, 18f, true).apply { maxLines = 1 })
            t.addView(Ui.text(this, "노래 ${p.songs.size}곡", 13f, color = Ui.SUB))
            row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            fun iconBtn(icon: Int, desc: String, enabled: Boolean, fill: Int, tint: Int, stroke: Int, onClick: () -> Unit) =
                ImageButton(this).apply {
                    setImageResource(icon)
                    imageTintList = Ui.tint(tint)
                    background = Ui.pressable(context, fill, 10, stroke, 1.5)
                    contentDescription = desc
                    isEnabled = enabled
                    alpha = if (enabled) 1f else 0.3f
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setOnClickListener { onClick() }
                    layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                }
            val lightLine = Color.parseColor("#D4D4D4")
            row.addView(iconBtn(R.drawable.ic_arrow_up, "위로", i > 0, Color.WHITE, Ui.INK, lightLine) {
                Repository.move(this, p.id, -1); changed()
            })
            row.addView(iconBtn(R.drawable.ic_arrow_down, "아래로", i < lists.lastIndex, Color.WHITE, Ui.INK, lightLine) {
                Repository.move(this, p.id, 1); changed()
            })

            val onW = p.showOnWidget
            row.addView(
                iconBtn(
                    if (onW) R.drawable.ic_eye else R.drawable.ic_eye_off,
                    if (onW) "할머니 화면에 보임" else "할머니 화면에서 숨김",
                    true,
                    fill = if (onW) Ui.INK else Color.WHITE,
                    tint = if (onW) Color.WHITE else Color.parseColor("#9CA3AF"),
                    stroke = if (onW) Ui.INK else lightLine,
                ) {
                    val count = Repository.load(this).count { it.showOnWidget }
                    if (!onW && count >= Repository.WIDGET_MAX) {
                        Toast.makeText(this, "할머니 화면에는 ${Repository.WIDGET_MAX}개까지만 보여요", Toast.LENGTH_SHORT).show()
                    } else {
                        Repository.upsert(this, p.copy(showOnWidget = !onW)); changed()
                    }
                },
            )
            row.addView(Ui.text(this, "편집", 15f, true, Color.parseColor("#1D4ED8")).apply {
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(10), dp(2), dp(10))
                setOnClickListener {
                    startActivity(Intent(this@SettingsActivity, PlaylistEditActivity::class.java).putExtra(PlaylistEditActivity.EXTRA_ID, p.id))
                }
            })
            if (i > 0) row.background = topLine()
            box.addView(row)
        }
        box.addView(
            Ui.button(this, "+ 새 재생목록 만들기", 16f, stroke = Color.parseColor("#9CA3AF")) {
                startActivity(Intent(this, PlaylistEditActivity::class.java))
            },
        )
        return box
    }

    private fun topLine() = android.graphics.drawable.LayerDrawable(
        arrayOf(android.graphics.drawable.ColorDrawable(Ui.LINE), android.graphics.drawable.ColorDrawable(Color.WHITE)),
    ).apply { setLayerInset(1, 0, dp(1), 0, 0) }

    private fun changed() {
        WidgetUpdater.updateAll(this)
        rebuildKeepScroll()
    }

    // ── 재생 방식 ─────────────────────────────────────────
    private fun playbackSection(): LinearLayout {
        val box = Ui.section(this, "재생 방식")
        box.addView(Ui.text(this, "재생 순서", 15f, true))
        box.addView(Ui.segment(this, listOf("순서대로" to false, "섞어서" to true), prefs.shuffle) {
            prefs.shuffle = it as Boolean; applyLive(); rebuildKeepScroll()
        })
        box.addView(switchRow("다 끝나면 처음부터", "재생목록이 끝나도 멈추지 않아요", prefs.repeat) { prefs.repeat = it; applyLive() })
        box.addView(switchRow("이어서 재생", "껐다 켜면 듣던 노래부터 나와요", prefs.resume) { prefs.resume = it })
        return box
    }

    private fun applyLive() {
        PlaybackService.instance?.applySettings()
    }

    private fun switchRow(label: String, desc: String, on: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val row = Ui.horizontal(this, 12)
        val t = Ui.vertical(this)
        t.addView(Ui.text(this, label, 16f, true))
        t.addView(Ui.text(this, desc, 13f, color = Ui.SUB))
        row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(SwitchCompat(this).apply {
            isChecked = on
            contentDescription = label
            thumbTintList = Ui.tint(Color.WHITE)
            trackTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Ui.ON, Color.parseColor("#9CA3AF")),
            )
            setOnCheckedChangeListener { _, v -> onChange(v) }
        })
        return row
    }

    // ── 소리와 화면 ─────────────────────────────────────────
    private fun soundSection(): LinearLayout {
        val box = Ui.section(this, "소리와 화면")

        val volHead = Ui.horizontal(this)
        volHead.addView(Ui.text(this, "기본 소리 크기", 16f, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val volLabel = Ui.text(this, "${prefs.volumePercent}%", 16f, true)
        volHead.addView(volLabel)
        box.addView(volHead)
        box.addView(SeekBar(this).apply {
            max = 18 // 10% ~ 100%, 5% 단위
            progress = (prefs.volumePercent - 10) / 5
            contentDescription = "기본 소리 크기"
            progressTintList = Ui.tint(Ui.INK)
            thumbTintList = Ui.tint(Ui.INK)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    val v = 10 + p * 5
                    prefs.volumePercent = v
                    volLabel.text = "$v%"
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        })
        box.addView(Ui.text(this, "노래를 틀 때마다 이 크기로 시작해요. 할머니가 소리를 줄여놔도 다시 들려요.", 13f, color = Ui.SUB))

        box.addView(Ui.text(this, "글씨 크기", 16f, true).marginTop(this, 6))
        box.addView(Ui.segment(this, listOf("크게" to false, "아주 크게" to true), prefs.hugeText) {
            prefs.hugeText = it as Boolean; rebuildKeepScroll()
        })

        box.addView(Ui.text(this, "잠자기 타이머", 16f, true).marginTop(this, 6))
        box.addView(Ui.segment(this, listOf("안 씀" to 0, "30분" to 30, "1시간" to 60, "2시간" to 120), prefs.sleepMinutes) {
            prefs.sleepMinutes = it as Int; rebuildKeepScroll()
        })
        box.addView(Ui.text(this, "노래를 틀고 정한 시간이 지나면 저절로 꺼져요.", 13f, color = Ui.SUB))
        return box
    }

    // ── 처음 설치할 때 ─────────────────────────────────────────
    @SuppressLint("BatteryLife")
    private fun setupSection(): LinearLayout {
        val box = Ui.section(this, "처음 설치할 때")
        box.addView(Ui.button(this, "알림 권한 허용하기", 15f) {
            if (Build.VERSION.SDK_INT >= 33) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                Toast.makeText(this, "이 폰은 따로 허용 안 해도 돼요", Toast.LENGTH_SHORT).show()
            }
        }.apply { gravity = Gravity.CENTER_VERTICAL or Gravity.START })

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val ignoring = pm.isIgnoringBatteryOptimizations(packageName)
        box.addView(Ui.button(this, if (ignoring) "배터리 최적화 예외: 설정됨 ✓" else "배터리 최적화 예외로 설정하기", 15f) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }.apply { gravity = Gravity.CENTER_VERTICAL or Gravity.START })

        box.addView(Ui.button(this, "위젯을 바탕화면에 추가하기", 15f, fill = Ui.INK, fg = Color.WHITE) {
            val mgr = AppWidgetManager.getInstance(this)
            if (mgr.isRequestPinAppWidgetSupported) {
                mgr.requestPinAppWidget(ComponentName(this, MusicWidgetProvider::class.java), null, null)
            } else {
                Toast.makeText(this, "바탕화면을 길게 눌러 '위젯'에서 '손녀의 선물'을 추가해 주세요", Toast.LENGTH_LONG).show()
            }
        }.apply { gravity = Gravity.CENTER_VERTICAL or Gravity.START })
        box.addView(Ui.text(this, "갤럭시에서 화면이 꺼져도 노래가 끊기지 않게 하려면 위 두 개를 꼭 해주세요.", 13f, color = Ui.SUB))
        return box
    }

    // ── 새 노래 받기 ─────────────────────────────────────────
    private var syncing = false
    private var syncProgress: TextView? = null
    private var syncMessage = ""
    private val syncIo = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun songSyncSection(): LinearLayout {
        val box = Ui.section(this, "새 노래 받기")
        val sp = SyncPrefs(this)
        box.addView(Ui.text(this, "비공개 GitHub 저장소의 폴더 = 재생목록, 파일 이름 = 노래 제목이에요. 저장소에서 지운 노래는 폰에서도 지워져요. (폰에서 직접 넣은 노래는 그대로)", 13f, color = Ui.SUB))

        fun field(label: String, value: String, hint: String, password: Boolean): EditText {
            box.addView(Ui.text(this, label, 15f, true))
            val e = EditText(this).apply {
                setText(value)
                this.hint = hint
                setSingleLine()
                textSize = 15f
                setTextColor(Ui.INK)
                background = Ui.shape(context, Color.WHITE, 12, Color.parseColor("#9CA3AF"), 1.5)
                setPadding(dp(12), 0, dp(12), 0)
                contentDescription = label
                inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            box.addView(e, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
            return e
        }
        val repoInput = field("저장소 (아이디/저장소이름)", sp.repo, SyncPrefs.DEFAULT_REPO, false)
        val saved = sp.token
        val tokenInput = field(
            "토큰", "",
            if (saved.isEmpty()) "github_pat_로 시작하는 토큰 붙여넣기" else "저장됨 (…${saved.takeLast(4)}) · 바꿀 때만 입력",
            true,
        )
        box.addView(
            Ui.button(this, "저장", 15f) {
                sp.repo = repoInput.text.toString()
                val t = tokenInput.text.toString().trim()
                if (t.isNotEmpty()) sp.token = t
                Toast.makeText(this, "저장했어요", Toast.LENGTH_SHORT).show()
                rebuildKeepScroll()
            },
        )

        val last = if (sp.lastSync == 0L) "없음" else
            java.text.SimpleDateFormat("M월 d일 a h:mm", java.util.Locale.KOREA).format(java.util.Date(sp.lastSync))
        box.addView(Ui.text(this, "결과: ${sp.status}\n마지막 받기: $last", 13f, color = Ui.SUB))

        box.addView(
            Ui.button(
                this, if (syncing) "받는 중…" else "새 노래 받기", 17f,
                fill = if (syncing) Ui.PRESSED else Ui.INK, fg = Color.WHITE, stroke = null, heightDp = 56,
            ) { startSongSync() }.apply { isEnabled = !syncing },
        )
        val prog = Ui.text(this, syncMessage, 14f, true).apply {
            visibility = if (syncing) View.VISIBLE else View.GONE
        }
        syncProgress = prog
        box.addView(prog)
        return box
    }

    private fun startSongSync() {
        if (syncing || SongSync.isRunning) return
        val sp = SyncPrefs(this)
        if (sp.token.isEmpty()) {
            Toast.makeText(this, "토큰을 먼저 넣고 저장해주세요", Toast.LENGTH_LONG).show(); return
        }
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        if (cm.activeNetwork == null) {
            Toast.makeText(this, "인터넷이 연결돼 있지 않아요", Toast.LENGTH_LONG).show(); return
        }
        if (cm.isActiveNetworkMetered) {
            AlertDialog.Builder(this)
                .setMessage("지금 와이파이가 아니라 데이터로 연결돼 있어요.\n노래를 받으면 데이터 요금이 나올 수 있어요. 계속할까요?")
                .setPositiveButton("받기") { _, _ -> runSongSync() }
                .setNegativeButton("취소", null)
                .show()
        } else {
            runSongSync()
        }
    }

    private fun runSongSync() {
        syncing = true
        syncMessage = "준비 중…"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // 받는 동안 화면 안 꺼지게
        rebuildKeepScroll()
        syncIo.execute {
            val r = SongSync.run(applicationContext) { msg ->
                runOnUiThread {
                    syncMessage = msg
                    syncProgress?.text = msg
                }
            }
            runOnUiThread {
                syncing = false
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val msg = when (r) {
                    is SongSync.Result.Done -> SyncPrefs(this).status
                    is SongSync.Result.Failed -> r.message
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                if (!isFinishing) rebuildKeepScroll()
            }
        }
    }

    // ── 업데이트 ─────────────────────────────────────────
    private fun updateSection(): LinearLayout {
        val box = Ui.section(this, "업데이트")
        val up = UpdatePrefs(this)
        box.addView(Ui.text(this, "현재 버전 ${BuildConfig.VERSION_NAME} (versionCode ${BuildConfig.VERSION_CODE})", 15f, true))
        val last = if (up.lastCheck == 0L) "없음" else
            java.text.SimpleDateFormat("M월 d일 a h:mm", java.util.Locale.KOREA).format(java.util.Date(up.lastCheck))
        box.addView(Ui.text(this, "상태: ${up.status}\n마지막 확인: $last", 13f, color = Ui.SUB))

        if (!Updater.isConfigured) {
            box.addView(Ui.text(this, "app/build.gradle.kts의 updateJsonUrl을 내 GitHub 주소로 바꿔야 업데이트할 수 있어요.", 13f, color = Ui.DANGER))
            return box
        }

        val canInstall = Updater.canInstallPackages(this)
        if (!canInstall) {
            box.addView(
                Ui.button(this, "설치 권한 허용하기 (처음 한 번)", 15f) {
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                }.apply { gravity = Gravity.CENTER_VERTICAL or Gravity.START },
            )
        }
        box.addView(
            Ui.button(
                this, if (updating) "받는 중…" else "업데이트", 17f,
                fill = if (updating) Ui.PRESSED else Ui.INK, fg = Color.WHITE, stroke = null, heightDp = 56,
            ) { startUpdate() }.apply { isEnabled = !updating },
        )
        box.addView(Ui.text(this, "누르면 최신 버전을 받아서 바로 설치해요. 노래가 나오고 있으면 꺼져요. (인터넷 필요)", 13f, color = Ui.SUB))
        return box
    }

    private var updating = false
    private val updateIo = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun startUpdate() {
        if (updating) return
        if (!Updater.canInstallPackages(this)) {
            Toast.makeText(this, "먼저 '설치 권한 허용하기'를 눌러주세요", Toast.LENGTH_LONG).show()
            return
        }
        updating = true
        rebuildKeepScroll()
        updateIo.execute {
            val r = Updater.run(applicationContext)
            runOnUiThread {
                updating = false
                val msg = when (r) {
                    is Updater.Result.UpToDate -> "이미 최신 버전이에요"
                    is Updater.Result.Installing -> "새 버전 ${r.versionName} 설치를 시작해요"
                    is Updater.Result.Failed -> r.message
                    is Updater.Result.NotConfigured -> "업데이트 주소가 설정되지 않았어요"
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                if (!isFinishing) rebuildKeepScroll()
            }
        }
    }

    // ── 정보 ─────────────────────────────────────────
    private fun infoSection(): LinearLayout {
        val box = Ui.section(this, "정보")
        val lists = Repository.load(this)
        val songs = lists.flatMap { it.songs }
        val totalMs = songs.sumOf { it.durationMs }
        val h = totalMs / 3_600_000
        val m = (totalMs / 60_000) % 60
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
        listOf(
            "남은 저장 공간" to Formatter.formatShortFileSize(this, filesDir.usableSpace),
            "음악이 쓰는 공간" to Formatter.formatShortFileSize(this, Repository.musicDir(this).listFiles()?.sumOf { it.length() } ?: 0L),
            "전체 노래" to "${songs.size}곡",
            "전체 재생 시간" to "${h}시간 ${m}분",
            "앱 버전" to (version ?: "?"),
        ).forEach { (k, v) ->
            val row = Ui.horizontal(this)
            row.addView(Ui.text(this, k, 15f, color = Ui.SUB), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Ui.text(this, v, 15f, true))
            box.addView(row)
        }
        return box
    }
}
