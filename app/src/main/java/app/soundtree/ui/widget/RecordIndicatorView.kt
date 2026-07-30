package app.soundtree.ui.widget

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.content.Context
import android.util.AttributeSet
import android.view.View
import app.soundtree.R
import app.soundtree.util.themeColor

/**
 * RecordIndicatorView
 *
 * A custom View that owns the visual language for recording state across all
 * three placements in the app:
 *
 *   • Large  (80 dp) — main Record tab         [future migration: replaces btnRecord]
 *   • Small  (36 dp) — Mini Recorder bar       [future migration: replaces btnMiniRecPause]
 *   • Header (28 dp) — Topic Details header    [Step 1: new placement, wired now]
 *
 * Each placement is configured entirely through XML attrs (see
 * res/values/attrs.xml declare-styleable "RecordIndicatorView"), so the
 * state-color semantics only need to change in one place.
 *
 * ── States ───────────────────────────────────────────────────────────────────
 *
 *   IDLE              — red fill, optional border ring, optional "REC" label
 *   RECORDING         — yellow fill, no border, optional pause icon
 *   PAUSED            — accent fill, border, optional resume icon + label
 *   SESSION_ELSEWHERE — greyed fill, mic+dot indicator, touch blocked
 *                       Used on Topic Details when a session is active
 *                       elsewhere; communicates state without offering control.
 *
 * ── Interactivity ────────────────────────────────────────────────────────────
 *
 * The view itself is not a button. Hosts wire click behaviour via
 * [onIdleClick] (called when IDLE and tapped). Standard setOnClickListener
 * can be used for RECORDING/PAUSED states (host-controlled). SESSION_ELSEWHERE
 * consumes touches internally so the host never needs to manage isEnabled.
 *
 * ── Migration path ───────────────────────────────────────────────────────────
 *
 *  Step 1 (now):    Topic Details header button wired.
 *  Step 2 (later):  Mini Recorder btnMiniRecPause replaced; ic_record_circle_mini retired.
 *  Step 3 (later):  Main Record tab btnRecord replaced; updateUiForState() simplified.
 */
class RecordIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Public state ──────────────────────────────────────────────────────────

    enum class IndicatorState {
        IDLE,
        RECORDING,
        PAUSED,
        /** Session active but owned by a different context (Topic Details header). */
        SESSION_ELSEWHERE
    }

    /** Called when the view is tapped in [IndicatorState.IDLE] state. */
    var onIdleClick: (() -> Unit)? = null

    private var _state: IndicatorState = IndicatorState.IDLE

    fun setState(state: IndicatorState) {
        if (_state == state) return
        _state = state
        // SESSION_ELSEWHERE is non-interactive by design.
        isClickable = state != IndicatorState.SESSION_ELSEWHERE
        contentDescription = contentDescriptionForState()
        invalidate()
    }

    fun getState(): IndicatorState = _state

    // ── XML-configurable behaviour ────────────────────────────────────────────

    /** Whether to render the text label ("REC", "PAUSE", "RESUME"). Large only. */
    private var showLabel: Boolean = false

    /** Whether to render the pause/resume icon inside the circle. */
    private var showStateIcon: Boolean = true

    /**
     * Whether to draw the outer border ring.
     * Applied in IDLE and PAUSED; suppressed in RECORDING (matches main tab behaviour).
     */
    private var showBorder: Boolean = true

    /**
     * When true: SESSION_ELSEWHERE shows a greyed mic+dot indicator instead of
     * acting as a normal interactive button. Set true for the Topic Details header.
     * When false: SESSION_ELSEWHERE treated as IDLE (Mini Recorder, main Record tab).
     */
    private var externalSessionBehavior: Boolean = false

    // ── Size bucket ───────────────────────────────────────────────────────────

    private enum class SizeBucket { LARGE, SMALL, HEADER }
    private var sizeBucket: SizeBucket = SizeBucket.SMALL

    // ── Derived metrics (computed in onSizeChanged) ───────────────────────────

    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    private var borderWidthPx = 0f
    private var iconRadius = 0f
    private var labelTextSizePx = 0f

    // ── Paints ────────────────────────────────────────────────────────────────

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style     = Paint.Style.FILL
        color     = Color.WHITE
        typeface  = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    // Reusable path/rect to avoid per-draw allocations.
    private val iconPath = Path()
    private val iconRect = RectF()

    // ── Colors (resolved from theme once in init) ─────────────────────────────

    private val colorIdle:      Int  // colorRecordActive  — red
    private val colorRecording: Int  // colorRecordPause   — yellow/amber
    private val colorPaused:    Int  // colorAccent        — purple
    private val colorBorder:    Int  // colorRecordButtonBorder
    private val colorElsewhere: Int  // colorTextSecondary — greyed

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        colorIdle      = context.themeColor(R.attr.colorRecordActive)
        colorRecording = context.themeColor(R.attr.colorRecordPause)
        colorPaused    = context.themeColor(R.attr.colorAccent)
        colorBorder    = context.themeColor(R.attr.colorRecordButtonBorder)
        colorElsewhere = context.themeColor(R.attr.colorTextSecondary)

        val a = context.obtainStyledAttributes(attrs, R.styleable.RecordIndicatorView, defStyleAttr, 0)
        try {
            val sizeOrdinal = a.getInt(R.styleable.RecordIndicatorView_riv_size, 1 /* SMALL */)
            sizeBucket               = SizeBucket.values()[sizeOrdinal.coerceIn(0, SizeBucket.values().lastIndex)]
            showLabel                = a.getBoolean(R.styleable.RecordIndicatorView_riv_showLabel, false)
            showStateIcon            = a.getBoolean(R.styleable.RecordIndicatorView_riv_showStateIcon, true)
            showBorder               = a.getBoolean(R.styleable.RecordIndicatorView_riv_showBorder, true)
            externalSessionBehavior  = a.getBoolean(R.styleable.RecordIndicatorView_riv_externalSessionBehavior, false)
        } finally {
            a.recycle()
        }

        isClickable  = true
        isFocusable  = true
        contentDescription = contentDescriptionForState()

        setOnClickListener {
            if (_state == IndicatorState.IDLE) onIdleClick?.invoke()
        }
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val density = resources.displayMetrics.density
        cx = w / 2f
        cy = h / 2f
        val insetPx = 1.5f * density
        radius = (minOf(w, h) / 2f) - insetPx

        borderWidthPx = when (sizeBucket) {
            SizeBucket.LARGE  -> 1.5f * density
            SizeBucket.SMALL  -> 1.0f * density
            SizeBucket.HEADER -> 0.75f * density
        }
        borderPaint.strokeWidth = borderWidthPx

        iconRadius       = radius * 0.55f
        labelTextSizePx  = radius * 0.50f
        labelPaint.textSize = labelTextSizePx
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        when (_state) {
            IndicatorState.IDLE              -> drawIdle(canvas)
            IndicatorState.RECORDING         -> drawRecording(canvas)
            IndicatorState.PAUSED            -> drawPaused(canvas)
            IndicatorState.SESSION_ELSEWHERE -> drawSessionElsewhere(canvas)
        }
    }

    private fun drawIdle(canvas: Canvas) {
        fillPaint.color = colorIdle
        canvas.drawCircle(cx, cy, radius, fillPaint)

        if (showBorder) {
            borderPaint.color = colorBorder
            canvas.drawCircle(cx, cy, radius - borderWidthPx / 2f, borderPaint)
        }

        if (showLabel) {
            labelPaint.textSize    = labelTextSizePx
            labelPaint.letterSpacing = 0.05f
            val textY = cy - (labelPaint.ascent() + labelPaint.descent()) / 2f
            canvas.drawText(context.getString(R.string.record_btn_rec), cx, textY, labelPaint)
        }
    }

    private fun drawRecording(canvas: Canvas) {
        fillPaint.color = colorRecording
        canvas.drawCircle(cx, cy, radius, fillPaint)
        // No border in RECORDING state.

        if (showStateIcon) drawPauseIcon(canvas)

        if (showLabel) {
            labelPaint.textSize    = labelTextSizePx * 0.55f
            labelPaint.letterSpacing = 0.0f
            val textY = cy + iconRadius * 1.1f - labelPaint.ascent()
            canvas.drawText(context.getString(R.string.record_btn_pause), cx, textY, labelPaint)
        }
    }

    private fun drawPaused(canvas: Canvas) {
        fillPaint.color = colorPaused
        canvas.drawCircle(cx, cy, radius, fillPaint)

        if (showBorder) {
            borderPaint.color = colorBorder
            canvas.drawCircle(cx, cy, radius - borderWidthPx / 2f, borderPaint)
        }

        if (showStateIcon) drawResumeIcon(canvas)

        if (showLabel) {
            labelPaint.textSize    = labelTextSizePx * 0.42f
            labelPaint.letterSpacing = 0.0f
            val textY = cy + iconRadius * 1.2f - labelPaint.ascent()
            canvas.drawText(context.getString(R.string.record_btn_resume), cx, textY, labelPaint)
        }
    }

    private fun drawSessionElsewhere(canvas: Canvas) {
        fillPaint.color = colorElsewhere
        fillPaint.alpha = 80
        canvas.drawCircle(cx, cy, radius, fillPaint)
        fillPaint.alpha = 255

        if (showBorder) {
            borderPaint.color = colorElsewhere
            borderPaint.alpha = 120
            canvas.drawCircle(cx, cy, radius - borderWidthPx / 2f, borderPaint)
            borderPaint.alpha = 255
        }

        drawMicIcon(canvas)

        // Small red dot at lower-right: "something is recording."
        val dotRadius = iconRadius * 0.28f
        val dotCx = cx + iconRadius * 0.55f
        val dotCy = cy + iconRadius * 0.55f
        fillPaint.color = colorIdle
        fillPaint.alpha = 200
        canvas.drawCircle(dotCx, dotCy, dotRadius, fillPaint)
        fillPaint.alpha = 255
    }

    // ── Icon drawers ─────────────────────────────────────────────────────────

    private fun drawPauseIcon(canvas: Canvas) {
        iconPaint.color = Color.WHITE
        iconPaint.style = Paint.Style.FILL
        val barW  = iconRadius * 0.28f
        val barH  = iconRadius * 0.90f
        val gap   = iconRadius * 0.22f
        val barR  = barW * 0.45f

        iconRect.set(cx - gap / 2f - barW, cy - barH / 2f, cx - gap / 2f, cy + barH / 2f)
        canvas.drawRoundRect(iconRect, barR, barR, iconPaint)

        iconRect.set(cx + gap / 2f, cy - barH / 2f, cx + gap / 2f + barW, cy + barH / 2f)
        canvas.drawRoundRect(iconRect, barR, barR, iconPaint)
    }

    private fun drawResumeIcon(canvas: Canvas) {
        iconPaint.color = Color.WHITE
        iconPaint.style = Paint.Style.FILL
        val w       = iconRadius * 0.75f
        val h       = iconRadius * 0.90f
        val offsetX = iconRadius * 0.08f

        iconPath.rewind()
        iconPath.moveTo(cx - w * 0.5f + offsetX, cy - h)
        iconPath.lineTo(cx + w + offsetX,         cy)
        iconPath.lineTo(cx - w * 0.5f + offsetX,  cy + h)
        iconPath.close()
        canvas.drawPath(iconPath, iconPaint)
    }

    private fun drawMicIcon(canvas: Canvas) {
        iconPaint.color = colorElsewhere
        iconPaint.alpha = 200
        iconPaint.style = Paint.Style.FILL

        val micW = iconRadius * 0.38f
        val micH = iconRadius * 0.55f
        val topY = cy - micH * 1.1f

        // Capsule body (mic head)
        iconRect.set(cx - micW, topY, cx + micW, cy + micH * 0.35f)
        canvas.drawRoundRect(iconRect, micW, micW, iconPaint)

        // Stand arc
        iconPaint.style       = Paint.Style.STROKE
        iconPaint.strokeWidth = borderWidthPx * 1.2f
        val arcRadius = iconRadius * 0.52f
        val arcRect   = RectF(cx - arcRadius, cy - arcRadius, cx + arcRadius, cy + arcRadius)
        canvas.drawArc(arcRect, 0f, 180f, false, iconPaint)

        // Base bar
        val baseY  = cy + arcRadius
        val baseHW = arcRadius * 0.55f
        canvas.drawLine(cx - baseHW, baseY, cx + baseHW, baseY, iconPaint)

        // Restore paint state
        iconPaint.style = Paint.Style.FILL
        iconPaint.alpha = 255
    }

    // ── onMeasure ─────────────────────────────────────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val preferred = when (sizeBucket) {
            SizeBucket.LARGE  -> (80 * density).toInt()
            SizeBucket.SMALL  -> (36 * density).toInt()
            SizeBucket.HEADER -> (28 * density).toInt()
        }
        setMeasuredDimension(
            resolveSize(preferred, widthMeasureSpec),
            resolveSize(preferred, heightMeasureSpec)
        )
    }

    // ── Accessibility ─────────────────────────────────────────────────────────

    private fun contentDescriptionForState(): String = when (_state) {
        IndicatorState.IDLE              -> context.getString(R.string.record_cd_start)
        IndicatorState.RECORDING         -> context.getString(R.string.record_cd_pause)
        IndicatorState.PAUSED            -> context.getString(R.string.record_cd_resume)
        IndicatorState.SESSION_ELSEWHERE -> context.getString(R.string.record_cd_session_elsewhere)
    }
}