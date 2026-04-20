package app.soundtree.ui.record

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import app.soundtree.R
import app.soundtree.util.themeColor

/**
 * Adapter for the horizontal mark-strip [RecyclerView] inside the Record tab's
 * extended mark controls panel (markExtendedControls card).
 *
 * Each item is a compact monospace timestamp chip, styled to match the mark
 * chips on the Listen tab:
 *   • Selected  — teal text + teal border (colorMarkSelected)
 *   • Unselected — muted text, no border   (colorTextSecondary)
 *
 * Tapping a chip calls [onChipTapped] with the item's list index; RecordFragment
 * forwards that to [app.soundtree.ui.MainViewModel.selectRecordingMark].
 *
 * Data is updated via [update], which replaces timestamps and selectedIndex in
 * one shot to avoid a double-notify flash.
 */
class RecordMarkChipAdapter(
    private val context: Context,
    private val onChipTapped: (index: Int) -> Unit,
) : RecyclerView.Adapter<RecordMarkChipAdapter.ChipVH>() {

    private var timestamps: List<Long> = emptyList()
    private var selectedIndex: Int = -1

    /**
     * Replace the full data set and selected index atomically.
     * Callers should pass [selectedIndex] = -1 when nothing is selected.
     */
    fun update(timestamps: List<Long>, selectedIndex: Int) {
        this.timestamps    = timestamps
        this.selectedIndex = selectedIndex
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = timestamps.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChipVH {
        val dp = context.resources.displayMetrics.density
        val chip = TextView(context).apply {
            textSize  = 11f
            typeface  = Typeface.MONOSPACE
            gravity   = Gravity.CENTER
            setPadding(
                (10 * dp).toInt(), (3 * dp).toInt(),
                (10 * dp).toInt(), (3 * dp).toInt(),
            )
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.WRAP_CONTENT,
                RecyclerView.LayoutParams.WRAP_CONTENT,
            ).apply {
                val m = (4 * dp).toInt()
                setMargins(m, 0, m, 0)
                gravity = Gravity.CENTER_VERTICAL
            }
            isClickable = true
            isFocusable = true
        }
        return ChipVH(chip)
    }

    override fun onBindViewHolder(holder: ChipVH, position: Int) {
        val chip       = holder.chip
        val isSelected = (position == selectedIndex)
        val dp         = context.resources.displayMetrics.density

        chip.text = formatMs(timestamps[position])

        val tealColor    = context.themeColor(R.attr.colorMarkSelected)
        val surfaceColor = context.themeColor(R.attr.colorSurfaceElevated)
        val mutedColor   = context.themeColor(R.attr.colorTextSecondary)

        chip.setTextColor(if (isSelected) tealColor else mutedColor)
        chip.background = GradientDrawable().apply {
            shape        = GradientDrawable.RECTANGLE
            cornerRadius = 12 * dp
            setColor(surfaceColor)
            if (isSelected) setStroke((1.5f * dp).toInt(), tealColor)
        }

        chip.setOnClickListener { onChipTapped(position) }
    }

    private fun formatMs(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    inner class ChipVH(val chip: TextView) : RecyclerView.ViewHolder(chip)
}