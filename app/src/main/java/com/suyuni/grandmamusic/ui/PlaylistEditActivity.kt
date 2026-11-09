package com.suyuni.grandmamusic.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.suyuni.grandmamusic.R
import com.suyuni.grandmamusic.data.Palette
import com.suyuni.grandmamusic.data.Playlist
import com.suyuni.grandmamusic.data.Repository
import com.suyuni.grandmamusic.data.Song
import com.suyuni.grandmamusic.ui.Ui.dp
import com.suyuni.grandmamusic.widget.WidgetUpdater
import java.util.Collections
import java.util.concurrent.Executors

/** 시안 ④ 재생목록 편집. 저장하기를 눌러야 실제로 바뀌어. */
class PlaylistEditActivity : BaseActivity() {

    private var original: Playlist? = null
    private lateinit var playlistId: String
    private var name = ""
    private var colorIndex = 0
    private val songs = mutableListOf<Song>()
    private var dirty = false

    private lateinit var nameInput: EditText
    private lateinit var preview: TextView
    private lateinit var swatchRow: LinearLayout
    private lateinit var countLabel: TextView
    private lateinit var adapter: SongAdapter
    private lateinit var touchHelper: ItemTouchHelper
    private val io = Executors.newSingleThreadExecutor()

    private val pickSongs = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importSongs(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val existing = Repository.get(this, intent.getStringExtra(EXTRA_ID))
        original = existing
        if (existing != null) {
            playlistId = existing.id
            name = existing.name
            colorIndex = existing.colorIndex
            songs.addAll(existing.songs)
        } else {
            val used = Repository.load(this).map { it.colorIndex }.toSet()
            playlistId = java.util.UUID.randomUUID().toString()
            name = "새 재생목록"
            colorIndex = Palette.entries.indices.firstOrNull { it !in used } ?: 0
            dirty = true
        }
        setContentView(buildUi())

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmLeave()
        })
    }

    private fun buildUi(): View {
        val root = Ui.vertical(this).apply {
            setBackgroundColor(Ui.BG)
            fitsSystemWindows = true
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }

        root.addView(Ui.text(this, "← 뒤로", 16f, true, Color.parseColor("#1D4ED8")).apply {
            setPadding(0, dp(10), dp(10), dp(10))
            setOnClickListener { confirmLeave() }
        })
        root.addView(Ui.text(this, if (original == null) "새 재생목록" else "재생목록 편집", 26f, true))

        // 이름 · 색 · 미리보기
        val top = Ui.vertical(this, 10).apply {
            background = Ui.shape(context, Color.WHITE, 18)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val nameRow = Ui.horizontal(this, 10)
        nameRow.addView(Ui.text(this, "이름", 16f, true))
        nameInput = EditText(this).apply {
            setText(name)
            setSingleLine()
            textSize = 18f
            setTextColor(Ui.INK)
            background = Ui.shape(context, Color.WHITE, 12, Color.parseColor("#9CA3AF"), 1.5)
            setPadding(dp(12), 0, dp(12), 0)
            contentDescription = "재생목록 이름"
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    name = s?.toString().orEmpty(); dirty = true; renderPreview()
                }
            })
        }
        nameRow.addView(nameInput, LinearLayout.LayoutParams(0, dp(48), 1f))
        top.addView(nameRow)

        swatchRow = Ui.horizontal(this, 8)
        top.addView(swatchRow)

        preview = Ui.text(this, "", 28f, true, Color.WHITE).apply {
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            setPadding(dp(20), 0, dp(20), 0)
            contentDescription = "할머니 화면 미리보기"
        }
        top.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))
        root.addView(top, Ui.matchWrap().apply { topMargin = dp(10) })

        // 노래 목록 머리
        val listHead = Ui.horizontal(this, 10).apply { setPadding(0, dp(14), 0, dp(6)) }
        countLabel = Ui.text(this, "", 19f, true)
        listHead.addView(countLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        listHead.addView(
            Ui.button(this, "+ 노래 넣기", 15f, fill = Ui.INK, fg = Color.WHITE, stroke = null, heightDp = 44) {
                pickSongs.launch(arrayOf("audio/*"))
            }.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)) },
        )
        root.addView(listHead)
        root.addView(Ui.text(this, "손잡이(≡)를 끌어서 순서 바꾸기 · 옆으로 밀어서 빼기 · 제목을 누르면 고칠 수 있어요", 13f, color = Ui.SUB))

        // 노래 목록
        adapter = SongAdapter()
        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@PlaylistEditActivity.adapter
            background = Ui.shape(context, Color.WHITE, 18)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            clipToPadding = false
        }
        touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
        ) {
            override fun isLongPressDragEnabled() = false
            override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
                val a = from.bindingAdapterPosition; val b = to.bindingAdapterPosition
                Collections.swap(songs, a, b)
                adapter.notifyItemMoved(a, b)
                dirty = true
                return true
            }
            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                adapter.notifyDataSetChanged() // 번호 다시 매기기
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                removeAt(vh.bindingAdapterPosition)
            }
        })
        touchHelper.attachToRecyclerView(rv)
        root.addView(rv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })

        // 아래 버튼
        val bottom = Ui.horizontal(this, 10).apply { setPadding(0, dp(12), 0, 0) }
        if (original != null) {
            bottom.addView(
                Ui.button(this, "지우기", 15f, fg = Ui.DANGER, stroke = Ui.DANGER_LINE, heightDp = 56) { confirmDelete() },
                LinearLayout.LayoutParams(0, dp(56), 1f),
            )
        }
        bottom.addView(
            Ui.button(this, "저장하기", 18f, fill = Ui.INK, fg = Color.WHITE, stroke = null, heightDp = 56) { save() },
            LinearLayout.LayoutParams(0, dp(56), 2f),
        )
        root.addView(bottom)

        renderSwatches()
        renderPreview()
        renderCount()
        return root
    }

    private fun renderSwatches() {
        swatchRow.removeAllViews()
        Palette.entries.forEachIndexed { i, e ->
            val sel = i == colorIndex
            val v = FrameLayout(this).apply {
                background = Ui.shape(context, Color.parseColor(e.hex), 12, if (sel) Ui.INK else Color.WHITE, if (sel) 4 else 0)
                contentDescription = "${e.name} 색" + if (sel) " (선택됨)" else ""
                isClickable = true
                setOnClickListener { colorIndex = i; dirty = true; renderSwatches(); renderPreview() }
            }
            swatchRow.addView(v, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
    }

    private fun renderPreview() {
        preview.text = name.ifBlank { "이름을 넣어주세요" }
        preview.background = Ui.shape(this, Palette.color(colorIndex), 18)
    }

    private fun renderCount() {
        countLabel.text = "노래 ${songs.size}곡"
    }

    private fun removeAt(pos: Int) {
        if (pos !in songs.indices) return
        songs.removeAt(pos)
        adapter.notifyItemRemoved(pos)
        adapter.notifyItemRangeChanged(pos, songs.size - pos)
        dirty = true
        renderCount()
    }

    private fun rename(pos: Int) {
        val s = songs.getOrNull(pos) ?: return
        val input = EditText(this).apply {
            setText(s.title)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT
            setSelection(text.length)
        }
        val wrap = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle("노래 제목 고치기")
            .setMessage("파일: ${s.originalName}")
            .setView(wrap)
            .setPositiveButton("바꾸기") { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) {
                    songs[pos] = s.copy(title = t); adapter.notifyItemChanged(pos); dirty = true
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun importSongs(uris: List<Uri>) {
        Toast.makeText(this, "노래 ${uris.size}곡 넣는 중…", Toast.LENGTH_SHORT).show()
        io.execute {
            val added = uris.mapNotNull { Repository.importSong(applicationContext, it) }
            runOnUiThread {
                val start = songs.size
                songs.addAll(added)
                adapter.notifyItemRangeInserted(start, added.size)
                dirty = true
                renderCount()
                val failed = uris.size - added.size
                Toast.makeText(
                    this,
                    if (failed == 0) "노래 ${added.size}곡을 넣었어요. 저장하기를 눌러주세요." else "${added.size}곡 넣음, ${failed}곡은 못 넣었어요",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun save() {
        val n = name.trim()
        if (n.isEmpty()) {
            Toast.makeText(this, "이름을 넣어주세요", Toast.LENGTH_SHORT).show(); return
        }
        val showOnWidget = original?.showOnWidget
            ?: (Repository.load(this).count { it.showOnWidget } < Repository.WIDGET_MAX)
        Repository.upsert(this, Playlist(playlistId, n, colorIndex, showOnWidget, songs.toList()))
        Repository.cleanupOrphans(this)
        WidgetUpdater.updateAll(this)
        Toast.makeText(this, "저장했어요", Toast.LENGTH_SHORT).show()
        dirty = false
        finish()
    }

    private fun confirmLeave() {
        if (!dirty) { finish(); return }
        AlertDialog.Builder(this)
            .setMessage("저장하지 않고 나갈까요?\n바꾼 내용은 없어져요.")
            .setPositiveButton("나가기") { _, _ ->
                dirty = false
                Repository.cleanupOrphans(this) // 넣다 만 노래 파일 정리
                finish()
            }
            .setNegativeButton("계속 편집", null)
            .show()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setMessage("'${original?.name}' 재생목록을 지울까요?\n안에 있는 노래도 같이 지워져요.")
            .setPositiveButton("지우기") { _, _ ->
                Repository.delete(this, playlistId)
                WidgetUpdater.updateAll(this)
                dirty = false
                finish()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private inner class SongAdapter : RecyclerView.Adapter<SongAdapter.VH>() {
        inner class VH(val row: LinearLayout, val handle: ImageView, val no: TextView, val title: TextView) :
            RecyclerView.ViewHolder(row)

        @SuppressLint("ClickableViewAccessibility")
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val row = Ui.horizontal(ctx, 8).apply {
                setPadding(ctx.dp(4), ctx.dp(8), ctx.dp(4), ctx.dp(8))
                setBackgroundColor(Color.WHITE)
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val handle = ImageView(ctx).apply {
                setImageResource(R.drawable.ic_drag)
                contentDescription = "끌어서 순서 바꾸기"
                setPadding(ctx.dp(6), ctx.dp(10), ctx.dp(6), ctx.dp(10))
            }
            row.addView(handle, LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(44)))
            val col = Ui.vertical(ctx)
            val no = Ui.text(ctx, "", 12f, color = Ui.SUB).apply { maxLines = 1 }
            val title = Ui.text(ctx, "", 16f, true).apply { maxLines = 2 }
            col.addView(no); col.addView(title)
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val vh = VH(row, handle, no, title)
            row.addView(
                Ui.button(ctx, "빼기", 13f, fg = Ui.DANGER, stroke = Ui.DANGER_LINE, heightDp = 44) {
                    removeAt(vh.bindingAdapterPosition)
                }.apply {
                    setPadding(ctx.dp(8), 0, ctx.dp(8), 0)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ctx.dp(44))
                },
            )
            handle.setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) touchHelper.startDrag(vh)
                false
            }
            col.setOnClickListener { rename(vh.bindingAdapterPosition) }
            return vh
        }

        override fun getItemCount() = songs.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val s = songs[position]
            h.no.text = "${position + 1}번 · ${s.originalName}"
            h.title.text = s.title
        }
    }

    companion object {
        const val EXTRA_ID = "playlistId"
    }
}
