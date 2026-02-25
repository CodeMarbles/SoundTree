package com.treecast.app.ui.topics

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import androidx.fragment.app.DialogFragment

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
        val nameInput = EditText(requireContext()).apply { hint = "Topic name" }
        layout.addView(nameInput)

        return AlertDialog.Builder(requireContext())
            .setTitle(if (parentId == null) "New Root Topic" else "New Sub-topic")
            .setView(layout)
            .setPositiveButton("Create") { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isNotEmpty()) onCreated(name, "🎙️", "#6C63FF")
            }
            .setNegativeButton("Cancel", null)
            .create()
    }
}