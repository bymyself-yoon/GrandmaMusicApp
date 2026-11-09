package com.suyuni.grandmamusic.data

import android.graphics.Color
import com.suyuni.grandmamusic.R

/** 흰 글씨가 잘 보이는 진한 색 6가지만 쓸 수 있게 고정해 둔 색표. */
object Palette {
    data class Entry(val name: String, val hex: String, val widgetBg: Int, val widgetSelectedBg: Int)

    val entries = listOf(
        Entry("주황", "#C2410C", R.drawable.wg_pl_0, R.drawable.wg_pl_sel_0),
        Entry("파랑", "#1D4ED8", R.drawable.wg_pl_1, R.drawable.wg_pl_sel_1),
        Entry("초록", "#15803D", R.drawable.wg_pl_2, R.drawable.wg_pl_sel_2),
        Entry("보라", "#7E22CE", R.drawable.wg_pl_3, R.drawable.wg_pl_sel_3),
        Entry("빨강", "#BE123C", R.drawable.wg_pl_4, R.drawable.wg_pl_sel_4),
        Entry("청록", "#0F766E", R.drawable.wg_pl_5, R.drawable.wg_pl_sel_5),
    )

    fun entry(index: Int): Entry = entries[index.coerceIn(0, entries.lastIndex)]
    fun color(index: Int): Int = Color.parseColor(entry(index).hex)
}
