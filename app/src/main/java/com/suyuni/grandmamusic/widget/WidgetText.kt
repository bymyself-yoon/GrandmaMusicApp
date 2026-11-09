package com.suyuni.grandmamusic.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import com.suyuni.grandmamusic.ui.Ui
import kotlin.math.ceil
import kotlin.math.max

/**
 * 위젯은 홈 화면 앱이 그리기 때문에 앱에 넣은 글꼴(페이퍼로지)이 적용되지 않아.
 * 그래서 글씨를 앱 안에서 페이퍼로지로 "그림"으로 그려서 위젯 ImageView에 넣어.
 * ImageView가 글씨보다 좁으면 그림이 줄어들어서, 긴 제목도 한 줄에 들어가.
 * 위젯으로 보내는 데이터를 줄이려고 글씨 모양(알파)만 그리고, 색은 위젯에서 입혀 (setColorFilter).
 */
object WidgetText {
    private const val MAX_WIDTH_PX = 1600

    fun render(
        ctx: Context,
        text: CharSequence,
        sizeSp: Float,
        bold: Boolean = true,
        center: Boolean = false,
    ): Bitmap {
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, ctx.resources.displayMetrics)
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            textSize = px
            color = Color.BLACK
            typeface = Ui.font(ctx, bold)
        }
        val lines = text.toString().ifEmpty { " " }.split('\n')
        val width = ceil(lines.maxOf { paint.measureText(it) }).toInt().coerceIn(1, MAX_WIDTH_PX)
        val layout = StaticLayout.Builder.obtain(text.ifEmpty { " " }, 0, text.ifEmpty { " " }.length, paint, width)
            .setAlignment(if (center) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.05f)
            .setIncludePad(false)
            .build()
        val bmp = Bitmap.createBitmap(max(1, layout.width), max(1, layout.height), Bitmap.Config.ALPHA_8)
        bmp.eraseColor(Color.TRANSPARENT)
        layout.draw(Canvas(bmp))
        return bmp
    }
}
