package app.soundtree.ui.settings

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import app.soundtree.R
import app.soundtree.diagnostic.MediaMountEvent
import app.soundtree.diagnostic.MediaMountEventLog
import app.soundtree.util.themeColor
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val EVENT_DIFF = object : DiffUtil.ItemCallback<MediaMountEvent>() {
    override fun areItemsTheSame(a: MediaMountEvent, b: MediaMountEvent) =
        a.timestampMs == b.timestampMs && a.receiverSource == b.receiverSource
    override fun areContentsTheSame(a: MediaMountEvent, b: MediaMountEvent) = a == b
}

/**
 * Dev-tool bottom-sheet that displays the in-memory [MediaMountEventLog].
 *
 * Shows every storage broadcast received since the process started, with
 * the receiver source (static vs dynamic), action, mount path, and resolved
 * volume UUID. Intended for diagnosing whether GrapheneOS delivers
 * [android.content.Intent.ACTION_MEDIA_MOUNTED] to the app's receivers.
 *
 * Launched from the Developer Options section in Settings → Tools.
 * Gated behind the devOptions flag so it never appears in production use.
 *
 * Structure:
 *   ┌──────────────────────────────────────────┐
 *   │  [drag handle]                           │
 *   │  Storage Event Log          [Clear]      │ ← fixed header
 *   │  "N events captured" or "No events yet" │ ← count line
 *   ├──────────────────────────────────────────┤
 *   │  [event row] …                           │ ← RecyclerView, fills height
 *   └──────────────────────────────────────────┘
 *
 * Each row shows:
 *   [timestamp]  [STATIC|DYNAMIC chip]  action short name
 *   mount path
 *   UUID: <resolved value>
 */
class StorageMountEventLogDialog : BottomSheetDialogFragment() {

    override fun getTheme(): Int = R.style.Theme_SoundTree_BottomSheet

    companion object {
        const val TAG = "storage_mount_event_log"
    }

    // ── Bottom-sheet behaviour ────────────────────────────────────────────────

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state         = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }

    // ── View construction ─────────────────────────────────────────────────────

    private lateinit var countView:   TextView
    private lateinit var recycler:    RecyclerView
    private val eventAdapter = EventAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val ctx  = requireContext()
        val dm   = ctx.resources.displayMetrics
        val px1  = dm.density.toInt().coerceAtLeast(1)
        val px12 = (12 * dm.density).toInt()
        val px16 = (16 * dm.density).toInt()
        val px24 = (24 * dm.density).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(ctx.themeColor(R.attr.colorSurfaceBase))
        }

        // ── Drag handle ───────────────────────────────────────────────────────
        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                (36 * dm.density).toInt(),
                (4  * dm.density).toInt(),
            ).also {
                it.gravity      = Gravity.CENTER_HORIZONTAL
                it.topMargin    = px12
                it.bottomMargin = (8 * dm.density).toInt()
            }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape        = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 2 * dm.density
                setColor(ctx.themeColor(R.attr.colorTextSecondary))
                alpha = 80
            }
        })

        // ── Title row: "Storage Event Log" + "Clear" ─────────────────────────
        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setPadding(px24, px16, px24, (8 * dm.density).toInt())
        }
        titleRow.addView(TextView(ctx).apply {
            text = getString(R.string.dev_storage_event_log_title)
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ctx.themeColor(R.attr.colorTextPrimary))
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            )
        })
        titleRow.addView(TextView(ctx).apply {
            text = getString(R.string.dev_storage_event_log_clear)
            textSize = 13f
            setTextColor(ctx.themeColor(R.attr.colorAccent))
            setOnClickListener {
                MediaMountEventLog.clear()
            }
        })
        root.addView(titleRow)

        // ── Count line ────────────────────────────────────────────────────────
        countView = TextView(ctx).apply {
            textSize = 12f
            setTextColor(ctx.themeColor(R.attr.colorTextSecondary))
            setPadding(px24, 0, px24, px12)
        }
        root.addView(countView)

        // ── Divider ───────────────────────────────────────────────────────────
        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px1,
            ).also { it.setMargins(px24, 0, px24, 0) }
            setBackgroundColor(ctx.themeColor(R.attr.colorSurfaceElevated))
        })

        // ── RecyclerView — fills remaining height ─────────────────────────────
        recycler = RecyclerView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0,
            ).also { it.weight = 1f }
            layoutManager  = LinearLayoutManager(ctx)
            adapter        = eventAdapter
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        root.addView(recycler)

        return root
    }

    // ── Flow observation ──────────────────────────────────────────────────────

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                MediaMountEventLog.events.collect { events ->
                    try {
                        eventAdapter.submitList(events)
                        countView.text = if (events.isEmpty()) {
                            getString(R.string.dev_storage_event_log_empty)
                        } else {
                            resources.getQuantityString(
                                R.plurals.dev_storage_event_log_count,
                                events.size,
                                events.size,
                            )
                        }
                    } catch (t: Throwable) {
                        android.widget.Toast.makeText(
                            requireContext(),
                            "EventLog collector crash: ${t::class.simpleName}: ${t.message}",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private inner class EventAdapter :
        ListAdapter<MediaMountEvent, EventAdapter.ViewHolder>(EVENT_DIFF) {

        inner class ViewHolder(
            root:                  LinearLayout,
            val tvHeader:    TextView,
            val tvMountPath: TextView,
            val tvUuid:      TextView,
        ) : RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val ctx  = parent.context
            val dm   = ctx.resources.displayMetrics
            val px4  = (4  * dm.density).toInt()
            val px16 = (16 * dm.density).toInt()

            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px16, (10 * dm.density).toInt(), px16, (10 * dm.density).toInt())
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }

            val tvHeader = TextView(ctx).apply {
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(ctx.themeColor(R.attr.colorTextPrimary))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).also { it.bottomMargin = px4 }
            }
            row.addView(tvHeader)

            val tvMountPath = TextView(ctx).apply {
                textSize = 11f
                setTextColor(ctx.themeColor(R.attr.colorTextSecondary))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            row.addView(tvMountPath)

            val tvUuid = TextView(ctx).apply {
                textSize = 11f
                setTextColor(ctx.themeColor(R.attr.colorTextSecondary))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            row.addView(tvUuid)

            return ViewHolder(row, tvHeader, tvMountPath, tvUuid)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val event   = getItem(position)
            val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            val time    = timeFmt.format(Date(event.timestampMs))
            val source  = event.receiverSource.label.uppercase()
            val action  = event.action.substringAfterLast('.')  // e.g. "MEDIA_MOUNTED"

            holder.tvHeader.text    = "[$time]  $source  $action"
            holder.tvMountPath.text = "path: ${event.mountPath ?: "(none)"}"
            holder.tvUuid.text      = "uuid: ${event.resolvedUuid}"
        }

    }
}