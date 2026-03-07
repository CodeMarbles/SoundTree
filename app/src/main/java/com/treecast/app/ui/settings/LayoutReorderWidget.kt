package com.treecast.app.ui.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.treecast.app.R
import com.treecast.app.ui.LayoutElement
import com.treecast.app.util.themeColor

/**
 * A compact, fixed-width widget showing the four main UI chrome elements as
 * draggable strips — a miniature model of the app's own layout.
 *
 * Usage in SettingsFragment:
 *   widget.setOrder(viewModel.layoutOrder.value)
 *   widget.showTitleBar = viewModel.showTitleBar.value
 *   // ...
 *   btnEditLayout.setOnClickListener { widget.setEditing(true) }
 *   btnApply.setOnClickListener {
 *       viewModel.setLayoutOrder(widget.getOrder())
 *       widget.setEditing(false)
 *   }
 *
 * Design notes:
 *  - Fixed width (~170 dp), set via XML or programmatically.
 *  - CONTENT is rendered as a taller card with a subtle grid, the others
 *    as narrow strips, so the hierarchy is visually self-explanatory.
 *  - When [showTitleBar] is false the TITLE_BAR item disappears from the
 *    widget. Its position in the full 4-element order is preserved so it
 *    snaps back correctly when the toggle is re-enabled.
 *  - In locked mode (editing = false) items render at reduced opacity.
 *    In edit mode they brighten and become draggable via long-press or
 *    immediate touch on the drag handle area.
 */
class LayoutReorderWidget @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    companion object {
        const val TYPE_STRIP = 0
        const val TYPE_CONTENT = 1
    }

    // ── Public state ──────────────────────────────────────────────────

    /**
     * Whether the TITLE_BAR element is visible in both the widget and the
     * live app layout.  Changing this does not lose its position in the order.
     */
    var showTitleBar: Boolean = true
        set(value) {
            if (field == value) return
            if (!value) {
                // Preserve TITLE_BAR's current index before removing it
                hiddenTitleBarIndex = displayItems.indexOf(LayoutElement.TITLE_BAR)
                    .takeIf { it >= 0 } ?: hiddenTitleBarIndex
            }
            field = value
            rebuildDisplayItems()
        }

    /** True while edit mode is active (dragging allowed). */
    var editing: Boolean = false
        private set

    fun setEditing(enabled: Boolean) {
        editing = enabled
        adapter.notifyDataSetChanged()
    }

    // ── Internal state ────────────────────────────────────────────────

    // Full 4-element order — source of truth, always kept in sync.
    private val fullOrder: MutableList<LayoutElement> =
        LayoutElement.DEFAULT_ORDER.toMutableList()

    // Position in fullOrder where TITLE_BAR should be re-inserted when
    // showTitleBar flips back to true.
    private var hiddenTitleBarIndex: Int = 0

    // Filtered view of fullOrder fed to the RecyclerView adapter.
    private val displayItems: MutableList<LayoutElement> = mutableListOf()

    // ── Views ─────────────────────────────────────────────────────────

    private val recycler: RecyclerView
    private val adapter: ElementAdapter
    private val touchHelper: ItemTouchHelper

    init {
        recycler = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            isNestedScrollingEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
        }
        adapter = ElementAdapter()
        recycler.adapter = adapter

        touchHelper = ItemTouchHelper(DragCallback())
        touchHelper.attachToRecyclerView(recycler)

        addView(recycler, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        rebuildDisplayItems()
    }

    // ── Public API ────────────────────────────────────────────────────

    /**
     * Initialises the widget from a saved 4-element order (e.g. from
     * SharedPreferences via [LayoutElement.parseOrder]).
     */
    fun setOrder(order: List<LayoutElement>) {
        fullOrder.clear()
        fullOrder.addAll(order)
        rebuildDisplayItems()
    }

    /**
     * Returns the current full 4-element order.  TITLE_BAR is included at
     * its last known position even when [showTitleBar] is false.
     */
    fun getOrder(): List<LayoutElement> = fullOrder.toList()

    // ── Private helpers ───────────────────────────────────────────────

    private fun rebuildDisplayItems() {
        displayItems.clear()
        displayItems.addAll(
            if (showTitleBar) fullOrder
            else fullOrder.filter { it != LayoutElement.TITLE_BAR }
        )
        adapter.notifyDataSetChanged()
    }

    private fun onItemMoved(from: Int, to: Int) {
        val element = displayItems.removeAt(from)
        displayItems.add(to, element)
        syncFullOrder()
    }

    /**
     * Reconstructs [fullOrder] from [displayItems], re-inserting the hidden
     * TITLE_BAR at [hiddenTitleBarIndex] when applicable.
     */
    private fun syncFullOrder() {
        if (showTitleBar) {
            fullOrder.clear()
            fullOrder.addAll(displayItems)
        } else {
            val rebuilt = displayItems.toMutableList()
            rebuilt.add(hiddenTitleBarIndex.coerceIn(0, rebuilt.size), LayoutElement.TITLE_BAR)
            fullOrder.clear()
            fullOrder.addAll(rebuilt)
        }
    }

    // ── Adapter ───────────────────────────────────────────────────────

    private inner class ElementAdapter : RecyclerView.Adapter<ElementAdapter.VH>() {

        inner class VH(val row: LinearLayout, val label: TextView, val handle: TextView) :
            RecyclerView.ViewHolder(row)

        override fun getItemCount(): Int = displayItems.size

        override fun getItemViewType(position: Int): Int =
            if (displayItems[position] == LayoutElement.CONTENT) TYPE_CONTENT else TYPE_STRIP

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val isContent = viewType == TYPE_CONTENT
            val dp = context.resources.displayMetrics.density

            val heightDp = if (isContent) 80 else 34
            val heightPx = (heightDp * dp).toInt()

            val label = TextView(context).apply {
                textSize = if (isContent) 10.5f else 9.5f
                gravity = Gravity.CENTER
                setTextColor(context.themeColor(R.attr.colorTextSecondary))
                letterSpacing = 0.07f
            }

            val row: LinearLayout = if (isContent) ContentCard(context) else LinearLayout(context)
            row.apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT, heightPx
                )
                val margin = (2 * dp).toInt()
                (layoutParams as RecyclerView.LayoutParams)
                    .setMargins(0, margin, 0, margin)

                background = GradientDrawable().apply {
                    setColor(context.themeColor(R.attr.colorSurfaceBase))
                    setStroke(
                        (1 * dp).toInt(),
                        context.themeColor(R.attr.colorSurfaceElevated)
                    )
                    cornerRadius = (4 * dp)
                }
            }

            val handle = TextView(context).apply {
                text = "⠿"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(context.themeColor(R.attr.colorTextSecondary))
                setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), 0)
                visibility = if (editing) View.VISIBLE else View.INVISIBLE
            }
            row.orientation = LinearLayout.HORIZONTAL
            row.addView(handle)
            row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            return VH(row, label, handle)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val element = displayItems[position]
            holder.label.text = element.displayName.uppercase()
            val targetAlpha = if (editing) 1f else 0.55f
            holder.row.animate().alpha(targetAlpha).setDuration(150).start()

            holder.handle.visibility = if (editing) View.VISIBLE else View.INVISIBLE
            holder.handle.setOnTouchListener { _, event ->
                if (editing && event.actionMasked == MotionEvent.ACTION_DOWN) {
                    touchHelper.startDrag(holder)
                    true
                } else false
            }
        }
    }

    // ── Drag callback ─────────────────────────────────────────────────

    private inner class DragCallback : ItemTouchHelper.Callback() {

        override fun getMovementFlags(
            rv: RecyclerView,
            vh: RecyclerView.ViewHolder
        ): Int {
            if (!editing) return makeMovementFlags(0, 0)
            return makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
        }

        override fun onMove(
            rv: RecyclerView,
            vh: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            val from = vh.adapterPosition
            val to   = target.adapterPosition
            onItemMoved(from, to)
            adapter.notifyItemMoved(from, to)
            return true
        }

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun isLongPressDragEnabled() = false
        override fun isItemViewSwipeEnabled() = false

        override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, state: Int) {
            super.onSelectedChanged(vh, state)
            if (state == ItemTouchHelper.ACTION_STATE_DRAG) {
                recycler.parent?.requestDisallowInterceptTouchEvent(true)
                vh?.itemView?.animate()?.alpha(0.65f)?.scaleX(1.05f)?.scaleY(1.05f)
                    ?.setDuration(120)?.start()
            }
        }

        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            recycler.parent?.requestDisallowInterceptTouchEvent(false)
            vh.itemView.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(100).start()
        }
    }

    // ── Content card with grid overlay ───────────────────────────────

    private inner class ContentCard(context: Context) : LinearLayout(context) {

        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.75f
            color = context.themeColor(R.attr.colorSurfaceElevated)
            alpha = 120
        }

        init {
            setWillNotDraw(false)
            orientation = VERTICAL
            gravity = Gravity.CENTER
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val step = 10 * resources.displayMetrics.density
            var x = step
            while (x < width) { canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint); x += step }
            var y = step
            while (y < height) { canvas.drawLine(0f, y, width.toFloat(), y, gridPaint); y += step }
        }
    }
}