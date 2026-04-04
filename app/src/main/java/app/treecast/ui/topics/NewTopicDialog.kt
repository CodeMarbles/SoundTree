package app.treecast.ui.topics

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import androidx.fragment.app.DialogFragment
import app.treecast.R
import app.treecast.util.emojiToColor

/**
 * Dialog for creating a new topic/folder.
 */
class NewTopicDialog(
    private val parentId: Long?,
    private val onCreated: (name: String, icon: String, color: String) -> Unit
) : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val nameInput = EditText(requireContext()).apply { hint = getString(R.string.topic_hint_name) }
        layout.addView(nameInput)

        return AlertDialog.Builder(requireContext())
            .setTitle(
                if (parentId == null) R.string.topic_dialog_new_root_title
                else R.string.topic_dialog_new_subtopic_title
            )
            .setView(layout)
            .setPositiveButton(R.string.common_btn_create) { _, _ ->
                val name = nameInput.text.toString().trim()
                val defaultEmoji = getString(R.string.topic_emoji_default)
                if (name.isNotEmpty()) onCreated(name, defaultEmoji, emojiToColor(defaultEmoji))
            }
            .setNegativeButton(R.string.common_btn_cancel, null)
            .create()
    }
}