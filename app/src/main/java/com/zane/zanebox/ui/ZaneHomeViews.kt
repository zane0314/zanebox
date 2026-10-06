package com.zane.zanebox.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout

/**
 * Visual components adapted from AnyBox 2.1.9 support/AnyBoxHomeViews.kt.
 * White pill toolbar with a smooth bulge around the centered power button. Children are
 * positioned by tag: "settings", "routing", "power", "power_label".
 */
class ZaneHomeToolbar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val oval = RectF()
    private var backdrop: Drawable? = null
    private var backdropOwner: View? = null
    private var backdropResolved = false
    private val here = IntArray(2)
    private val there = IntArray(2)

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        fill.color = 0x80FFFFFF.toInt()
        fill.style = Paint.Style.FILL
        shadow.color = Color.WHITE
        shadow.style = Paint.Style.FILL
        shadow.setShadowLayer(dp(10f), 0f, dp(2f), 0x1F33476B)
        stroke.color = 0xE6FFFFFF.toInt()
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = dp(1f)
        // Hardware-accelerated shadow layers on paths need API 28.
        if (Build.VERSION.SDK_INT < 28) setLayerType(View.LAYER_TYPE_SOFTWARE, null)
    }

    private fun dp(value: Float): Float = value * density

    /** Top edge of the white frame, below the bulge. */
    val frameTop: Float get() = dp(SHADOW + BULGE_RISE)
    private val frameLeft: Float get() = dp(SIDE_MARGIN)
    private val frameRight: Float get() = width - dp(SIDE_MARGIN)
    private val frameBottom: Float get() = frameTop + dp(FRAME_HEIGHT)
    val powerCenterY: Float get() = frameTop + dp(POWER_OFFSET)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = dp(SHADOW + BULGE_RISE + FRAME_HEIGHT + BOTTOM_SHADOW).toInt()
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val frameWidth = frameRight - frameLeft
        val cx = width / 2f
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            val w = child.measuredWidth
            val h = child.measuredHeight
            val position = when (child.tag) {
                "settings" -> Pair(frameLeft + frameWidth * 0.20f - w / 2f, frameTop + (dp(FRAME_HEIGHT) - h) / 2f)
                "routing" -> Pair(frameLeft + frameWidth * 0.80f - w / 2f, frameTop + (dp(FRAME_HEIGHT) - h) / 2f)
                "power" -> Pair(cx - w / 2f, powerCenterY - h / 2f)
                "power_label" -> Pair(cx - w / 2f, frameTop + dp(LABEL_TOP))
                else -> null
            } ?: continue
            val x = position.first.toInt()
            val y = position.second.toInt()
            child.layout(x, y, x + w, y + h)
        }
    }

    /** Taps on the painted pill or bulge stop here so they never reach the node list underneath. */
    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return true
        val x = event.x
        val y = event.y
        if (x >= frameLeft && x <= frameRight && y >= frameTop && y <= frameBottom) return true
        val dx = x - width / 2f
        val dy = y - powerCenterY
        val bulge = dp(BULGE_RADIUS)
        return dx * dx + dy * dy <= bulge * bulge
    }

    override fun onDraw(canvas: Canvas) {
        buildPath()
        // The fill is translucent, so the shadow must not be painted underneath it.
        canvas.save()
        if (Build.VERSION.SDK_INT >= 26) canvas.clipOutPath(path)
        else @Suppress("DEPRECATION") canvas.clipPath(path, Region.Op.DIFFERENCE)
        canvas.drawPath(path, shadow)
        canvas.restore()
        drawBackdrop(canvas)
        canvas.drawPath(path, fill)
        canvas.drawPath(path, stroke)
    }

    /**
     * Paints the page's static background inside the pill at the same coordinates, so scrolling
     * rows never bleed through the translucent fill while the gradient texture is preserved.
     */
    private fun drawBackdrop(canvas: Canvas) {
        if (!backdropResolved) {
            backdropResolved = true
            // The toolbar is a direct child of nodes_root with anybox_home_background.
            backdropOwner = rootView
            backdrop = androidx.core.content.ContextCompat.getDrawable(context,com.zane.zanebox.R.drawable.zb_ref_anybox_home_background)?.mutate()
        }
        val d = backdrop ?: return
        val owner = backdropOwner ?: return
        if (owner.width <= 0 || owner.height <= 0) return
        getLocationOnScreen(here)
        owner.getLocationOnScreen(there)
        canvas.save()
        canvas.clipPath(path)
        canvas.translate((there[0] - here[0]).toFloat(), (there[1] - here[1]).toFloat())
        d.setBounds(0, 0, owner.width, owner.height)
        d.draw(canvas)
        canvas.restore()
    }

    private fun buildPath() {
        val top = frameTop
        val bottom = frameBottom
        val left = frameLeft
        val right = frameRight
        val radius = (bottom - top) / 2f
        val cx = width / 2f
        val cy = powerCenterY
        val bulge = dp(BULGE_RADIUS)
        val fillet = dp(FILLET_RADIUS)
        val dy = cy - (top - fillet)
        val dx = Math.sqrt(((bulge + fillet) * (bulge + fillet) - dy * dy).toDouble()).toFloat()
        val tangent = Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
        path.reset()
        path.moveTo(left + radius, top)
        path.lineTo(cx - dx, top)
        oval.set(cx - dx - fillet, top - 2 * fillet, cx - dx + fillet, top)
        path.arcTo(oval, 90f, tangent - 90f, false)
        oval.set(cx - bulge, cy - bulge, cx + bulge, cy + bulge)
        path.arcTo(oval, 180f + tangent, 180f - 2 * tangent, false)
        oval.set(cx + dx - fillet, top - 2 * fillet, cx + dx + fillet, top)
        path.arcTo(oval, 180f - tangent, tangent - 90f, false)
        path.lineTo(right - radius, top)
        oval.set(right - 2 * radius, top, right, bottom)
        path.arcTo(oval, -90f, 180f, false)
        path.lineTo(left + radius, bottom)
        oval.set(left, top, left + 2 * radius, bottom)
        path.arcTo(oval, 90f, 180f, false)
        path.close()
    }

    companion object {
        const val SHADOW = 6f
        const val BULGE_RISE = 39f
        const val FRAME_HEIGHT = 72f
        const val BOTTOM_SHADOW = 10f
        const val SIDE_MARGIN = 24f
        const val POWER_OFFSET = 6f
        const val BULGE_RADIUS = 45f
        const val FILLET_RADIUS = 18f
        const val LABEL_TOP = 47f
    }
}

/** Large circular power toggle: white disc, state-colored ring and icon, spinning arc while busy. */
class ZanePowerButton @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = RectF()
    private var spin = 0f
    private var spinner: ValueAnimator? = null
    private var accent = 0xFF2767E8.toInt()
    private var busy = false

    init {
        isClickable = true
        isFocusable = true
        disc.style = Paint.Style.FILL
        ring.style = Paint.Style.STROKE
        ring.strokeCap = Paint.Cap.ROUND
        icon.style = Paint.Style.STROKE
        icon.strokeCap = Paint.Cap.ROUND
    }

    private fun dp(value: Float): Float = value * density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = dp(82f).toInt()
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
    }

    /** state: 0 disconnected, 1 connecting, 2 connected, 3 stopping, 4 failed, -1 unknown. */
    fun render(state: Int, pending: Boolean) {
        accent = when (state) {
            2, 1, 3 -> 0xFF2767E8.toInt()
            4 -> 0xFFE04B54.toInt()
            else -> 0xFF8E9AAE.toInt()
        }
        val nextBusy = pending || state == 1 || state == 3 || state < 0
        if (nextBusy != busy) {
            busy = nextBusy
            if (busy) startSpinner() else stopSpinner()
        }
        invalidate()
    }

    private fun startSpinner() {
        if (spinner != null || !isAttachedToWindow) return
        spinner = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 900L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { spin = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stopSpinner() {
        spinner?.cancel()
        spinner = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (busy) startSpinner()
    }

    override fun onDetachedFromWindow() {
        stopSpinner()
        super.onDetachedFromWindow()
    }

    override fun setPressed(pressed: Boolean) {
        val changed = pressed != isPressed
        super.setPressed(pressed)
        if (changed) {
            animate().cancel()
            animate().scaleX(if (pressed) 0.93f else 1f).scaleY(if (pressed) 0.93f else 1f).setDuration(if (pressed) 90L else 160L).start()
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val ringWidth = dp(2.5f)
        val radius = dp(35f) - ringWidth / 2f
        disc.color = if (isPressed) 0xFFEDF2FD.toInt() else Color.WHITE
        canvas.drawCircle(cx, cy, dp(35f), disc)
        ring.strokeWidth = ringWidth
        arc.set(cx - radius, cy - radius, cx + radius, cy + radius)
        if (busy) {
            ring.color = 0xFFD5E0F6.toInt()
            canvas.drawCircle(cx, cy, radius, ring)
            ring.color = accent
            canvas.drawArc(arc, spin - 90f, 100f, false, ring)
        } else {
            ring.color = accent
            canvas.drawCircle(cx, cy, radius, ring)
        }
        icon.color = if (busy) (accent and 0x00FFFFFF) or 0x99000000.toInt() else accent
        icon.strokeWidth = dp(4f)
        val iconRadius = dp(14f)
        arc.set(cx - iconRadius, cy - iconRadius + dp(1.5f), cx + iconRadius, cy + iconRadius + dp(1.5f))
        canvas.drawArc(arc, -55f, 290f, false, icon)
        canvas.drawLine(cx, cy - dp(16f), cx, cy - dp(2f), icon)
    }
}
