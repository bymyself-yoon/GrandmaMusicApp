package com.suyuni.grandmamusic.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.suyuni.grandmamusic.R

/** 화면을 코드로 만들 때 쓰는 작은 도구들. 색과 크기는 캔버스 시안 기준이야. */
object Ui {
    val INK = Color.parseColor("#111111")
    val SUB = Color.parseColor("#555555")
    val BG = Color.parseColor("#F4F4F2")
    val LINE = Color.parseColor("#E5E5E5")
    val YELLOW = Color.parseColor("#FACC15")
    val PRESSED = Color.parseColor("#BDBDBD")
    val DANGER = Color.parseColor("#B91C1C")
    val DANGER_LINE = Color.parseColor("#FCA5A5")
    val ON = Color.parseColor("#15803D")

    fun Context.dp(v: Number): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
    ).toInt()

    fun shape(ctx: Context, fill: Int, radiusDp: Number, stroke: Int? = null, strokeDp: Number = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = ctx.dp(radiusDp).toFloat()
            if (stroke != null) setStroke(ctx.dp(strokeDp), stroke)
        }

    /** 누르고 있는 동안만 회색이 되는 배경 */
    fun pressable(ctx: Context, fill: Int, radiusDp: Number, stroke: Int? = null, strokeDp: Number = 0): Drawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(ctx, PRESSED, radiusDp, stroke, strokeDp))
            addState(intArrayOf(), shape(ctx, fill, radiusDp, stroke, strokeDp))
        }

    /** 페이퍼로지 글꼴: 보통 = 7 Bold, 굵게 = 8 ExtraBold (할머니가 읽기 쉽게 둘 다 두꺼워) */
    private var regularFont: Typeface? = null
    private var boldFont: Typeface? = null
    private var lightFont: Typeface? = null

    /** 페이퍼로지 3 Light: 가족 설정(수유니만 보는 화면)의 소제목·설명용 */
    fun lightFont(ctx: Context): Typeface {
        lightFont?.let { return it }
        val tf = ResourcesCompat.getFont(ctx, R.font.paperlogy_light) ?: Typeface.DEFAULT
        lightFont = tf
        return tf
    }

    fun font(ctx: Context, bold: Boolean): Typeface {
        val cached = if (bold) boldFont else regularFont
        if (cached != null) return cached
        val tf = ResourcesCompat.getFont(ctx, if (bold) R.font.paperlogy_extrabold else R.font.paperlogy_bold)
            ?: Typeface.DEFAULT_BOLD
        if (bold) boldFont = tf else regularFont = tf
        return tf
    }

    fun text(ctx: Context, s: CharSequence, sp: Float, bold: Boolean = false, color: Int = INK, light: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
            typeface = if (light) lightFont(ctx) else font(ctx, bold)
            includeFontPadding = false
            setLineSpacing(0f, 1.1f)
        }

    fun button(
        ctx: Context, label: String, sp: Float = 16f,
        fill: Int = Color.WHITE, fg: Int = INK, stroke: Int? = INK, strokeDp: Number = 1.5,
        heightDp: Int = 48, onClick: () -> Unit,
    ): Button = Button(ctx).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        typeface = font(ctx, true)
        setTextColor(fg)
        background = pressable(ctx, fill, 12, stroke, strokeDp)
        stateListAnimator = null
        minHeight = ctx.dp(44)
        minimumHeight = ctx.dp(44)
        setPadding(ctx.dp(12), 0, ctx.dp(12), 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(heightDp))
        setOnClickListener { onClick() }
    }

    fun vertical(ctx: Context, gapDp: Int = 0): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        if (gapDp > 0) {
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = GradientDrawable().apply { setSize(0, ctx.dp(gapDp)) }
        }
    }

    fun horizontal(ctx: Context, gapDp: Int = 0): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        if (gapDp > 0) {
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = GradientDrawable().apply { setSize(ctx.dp(gapDp), 0) }
        }
    }

    /** 흰 카드 섹션 (제목 포함) */
    fun section(ctx: Context, title: String, hint: String? = null): LinearLayout {
        val box = vertical(ctx, 14).apply {
            background = shape(ctx, Color.WHITE, 20)
            setPadding(ctx.dp(20), ctx.dp(20), ctx.dp(20), ctx.dp(20))
        }
        val head = horizontal(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(text(ctx, title, 17f, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (hint != null) head.addView(text(ctx, hint, 12f, color = SUB, light = true))
        box.addView(head)
        return box
    }

    /** 여러 개 중 하나 고르는 버튼 줄 (순서대로/섞어서 등) */
    fun segment(ctx: Context, options: List<Pair<String, Any>>, selected: Any, onPick: (Any) -> Unit): LinearLayout {
        val row = horizontal(ctx, 8)
        options.forEach { (label, value) ->
            val sel = value == selected
            val b = button(
                ctx, label, 15f,
                fill = if (sel) INK else Color.WHITE,
                fg = if (sel) Color.WHITE else INK,
                heightDp = 44,
            ) { onPick(value) }
            b.setPadding(0, 0, 0, 0)
            row.addView(b, LinearLayout.LayoutParams(0, ctx.dp(44), 1f))
        }
        return row
    }

    /**
     * 알약 모양 스위치 (켜짐 = 초록 알약 + 흰 동그라미 오른쪽, 꺼짐 = 회색 알약 + 왼쪽).
     * 화면 읽기 기능에는 일반 스위치로 읽혀.
     */
    fun pillSwitch(ctx: Context, initial: Boolean, label: String, onChange: (Boolean) -> Unit): View {
        val w = ctx.dp(50); val h = ctx.dp(30); val pad = ctx.dp(3); val knob = h - pad * 2
        var checked = initial
        val track = android.widget.FrameLayout(ctx)
        val thumb = View(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            elevation = ctx.dp(1).toFloat()
        }
        track.addView(thumb, android.widget.FrameLayout.LayoutParams(knob, knob).apply {
            gravity = Gravity.CENTER_VERTICAL; leftMargin = pad
        })
        val travel = (w - pad * 2 - knob).toFloat()
        fun paint(animate: Boolean) {
            track.background = shape(ctx, if (checked) ON else Color.parseColor("#D1D5DB"), h / 2f / ctx.resources.displayMetrics.density)
            val x = if (checked) travel else 0f
            if (animate) thumb.animate().translationX(x).setDuration(160).start() else thumb.translationX = x
        }
        paint(false)
        track.contentDescription = label
        track.isClickable = true
        track.isFocusable = true
        track.setOnClickListener {
            checked = !checked
            paint(true)
            track.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED)
            onChange(checked)
        }
        track.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.Switch"
                info.isCheckable = true
                info.isChecked = checked
            }
        }
        // 누르기 쉽게 터치 영역은 넉넉히 (48dp), 보이는 알약은 50x30
        return android.widget.FrameLayout(ctx).apply {
            setPadding(0, ctx.dp(9), 0, ctx.dp(9))
            addView(track, android.widget.FrameLayout.LayoutParams(w, h))
        }
    }

    fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    fun View.marginTop(ctx: Context, dp: Int): View {
        val lp = (layoutParams as? LinearLayout.LayoutParams) ?: matchWrap()
        lp.topMargin = ctx.dp(dp)
        layoutParams = lp
        return this
    }

    fun tint(color: Int) = ColorStateList.valueOf(color)
}
