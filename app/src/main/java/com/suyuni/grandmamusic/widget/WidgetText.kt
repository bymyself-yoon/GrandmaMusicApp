package com.suyuni.grandmamusic.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.text.TextPaint
import com.suyuni.grandmamusic.ui.Ui
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 위젯은 홈 화면 앱이 그리기 때문에 앱에 넣은 글꼴(페이퍼로지)이 적용되지 않아.
 * 그래서 글씨를 앱 안에서 페이퍼로지로 "그림"으로 그려서 위젯 ImageView에 넣어.
 *
 * 그림은 위젯 칸 크기에 딱 맞게 그려: 글씨가 칸보다 크면 글씨 크기를 줄여서
 * 위젯을 작게 줄여도 글씨가 잘리지 않아.
 * 글씨 모양(알파)만 그리고 색은 위젯에서 입혀 (setColorFilter) → 보내는 데이터가 작아.
 */
object WidgetText {
    enum class Align { START, CENTER }

    /** 그림 해상도 상한 (너무 크면 위젯 데이터가 커져서) */
    private const val MAX_DENSITY = 2.5f

    /**
     * [boxWdp] x [boxHdp] 칸에 [text]를 넣은 그림. 글씨는 최대 [maxSp], 칸에 맞게 줄어.
     * [chip]이 있으면 오른쪽에 알약 모양 표시(예: "고른 노래")를 붙여. 알약 안 글씨는 뚫려 있어서 배경색이 보여.
     */
    fun box(
        ctx: Context,
        text: String,
        boxWdp: Float,
        boxHdp: Float,
        maxSp: Float,
        bold: Boolean = true,
        align: Align = Align.START,
        chip: String? = null,
    ): Bitmap {
        val dm = ctx.resources.displayMetrics
        val d = min(dm.density, MAX_DENSITY)
        val w = max(8, (boxWdp * d).roundToInt())
        val h = max(8, (boxHdp * d).roundToInt())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        bmp.density = (d * 160).roundToInt() // 그림을 그린 해상도 → 크기 그대로(wrap_content) 쓸 때 dp가 맞게
        val canvas = Canvas(bmp)

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Ui.font(ctx, bold)
        }
        val lines = text.ifEmpty { " " }.split('\n')
        // 글씨 크기: 최대 크기에서 시작해서 칸 높이·너비에 맞을 때까지 줄여
        val maxPx = maxSp * dm.scaledDensity / dm.density * d
        var size = min(maxPx, h / (lines.size * 1.22f))

        var chipW = 0f
        var chipH = 0f
        var chipSize = 0f
        fun measureChip() {
            if (chip == null) return
            chipSize = size * 0.6f
            paint.textSize = chipSize
            chipH = chipSize * 1.7f
            chipW = paint.measureText(chip) + chipSize * 1.4f
        }
        fun widest(): Float { paint.textSize = size; return lines.maxOf { paint.measureText(it) } }
        measureChip()
        var gap = if (chip != null) size * 0.4f else 0f
        var guard = 0
        while (widest() + chipW + gap > w && size > 6f && guard++ < 60) {
            size *= 0.94f
            measureChip()
            gap = if (chip != null) size * 0.4f else 0f
        }
        paint.textSize = size

        // 글씨: 세로 가운데
        val fm = paint.fontMetrics
        val lineH = (fm.descent - fm.ascent) * 1.05f
        var y = (h - lineH * lines.size) / 2f - fm.ascent
        val textAreaW = w - chipW - gap
        for (line in lines) {
            val lw = paint.measureText(line)
            val x = if (align == Align.CENTER) (textAreaW - lw) / 2f else 0f
            canvas.drawText(line, max(0f, x), y, paint)
            y += lineH
        }

        // 오른쪽 알약: 흰 알약 + 글씨 부분은 뚫기
        if (chip != null) {
            val left = w - chipW
            val top = (h - chipH) / 2f
            val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
            canvas.drawRoundRect(RectF(left, top, w.toFloat(), top + chipH), chipH / 2f, chipH / 2f, pill)
            val cut = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Ui.font(ctx, true)
                textSize = chipSize
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }
            val cfm = cut.fontMetrics
            val cy = top + (chipH - (cfm.descent - cfm.ascent)) / 2f - cfm.ascent
            canvas.drawText(chip, left + (chipW - cut.measureText(chip)) / 2f, cy, cut)
        }
        return bmp
    }
}
