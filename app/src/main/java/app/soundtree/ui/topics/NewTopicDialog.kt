package app.soundtree.ui.topics

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import app.soundtree.R
import app.soundtree.databinding.DialogNewTopicBinding
import app.soundtree.ui.MainViewModel
import app.soundtree.ui.common.EmojiPickerBottomSheet
import app.soundtree.ui.common.TopicPickerBottomSheet
import app.soundtree.util.emojiToColor
import app.soundtree.util.themeColor

/**
 * Modal dialog for creating or renaming a topic.
 *
 * ── Modes ─────────────────────────────────────────────────────────────────────
 *
 * [Mode.CREATE] — all three elements visible; positive button reads "Create".
 *   Pass [initialParentId] to pre-fill the parent selector (New Subtopic entry
 *   points), or null for a root-level topic (FAB, TopicPickerBottomSheet "+").
 *
 * [Mode.RENAME] — parent selector hidden (reparenting is a separate "Move"
 *   action); positive button reads "Save". Pass the existing topic's [initialName]
 *   and [initialIcon] so the fields are pre-filled and the user can adjust both
 *   in a single step.
 *
 * ── Elements ──────────────────────────────────────────────────────────────────
 *
 * • Icon picker     — large emoji in a circular card; tap opens [EmojiPickerBottomSheet].
 *                     Visible in both modes.
 * • Name field      — [TextInputLayout] with built-in clear-text (✕) end icon.
 *                     Pre-filled and fully selected in RENAME mode.
 * • Parent topic    — outlined button; opens [TopicPickerBottomSheet] in [PICK_PARENT]
 *                     mode. Hidden in RENAME mode.
 * • Confirm/Cancel  — confirm button disabled until name is non-blank.
 *
 * ── Callback ──────────────────────────────────────────────────────────────────
 *
 * [onConfirm] receives the final (name, parentId, icon, color) tuple. In RENAME
 * mode, parentId is always null (unused); callers should ignore it and preserve
 * the existing parentId when calling updateTopic. The caller is responsible for
 * all ViewModel writes; this dialog commits nothing itself.
 */
class NewTopicDialog(
    private val mode: Mode,
    private val initialName: String = "",
    private val initialIcon: String? = null,     // null → default emoji
    private val initialParentId: Long? = null,
    private val onConfirm: (name: String, parentId: Long?, icon: String, color: String) -> Unit,
) : DialogFragment() {

    enum class Mode { CREATE, RENAME }

    companion object {
        private const val REQUEST_PICK_PARENT = "NewTopicDialog_pickParent"
    }

    private var _binding: DialogNewTopicBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()

    private var selectedIcon: String = ""
    private var selectedParentId: Long? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        selectedParentId = initialParentId
        selectedIcon     = initialIcon ?: getString(R.string.topic_emoji_default)

        _binding = DialogNewTopicBinding.inflate(layoutInflater)

        // Pre-fill fields
        binding.tvTopicIcon.text = selectedIcon
        if (initialName.isNotEmpty()) {
            binding.etTopicName.setText(initialName)
            binding.etTopicName.selectAll()   // ready to overtype in RENAME mode
        }

        // Parent button: hidden in RENAME (reparenting is a distinct action)
        binding.btnParentTopic.visibility =
            if (mode == Mode.CREATE) android.view.View.VISIBLE else android.view.View.GONE
        updateParentButton()

        // Icon tap → emoji picker (constructor-callback, no result listener needed)
        binding.tvTopicIcon.setOnClickListener {
            EmojiPickerBottomSheet { emoji ->
                selectedIcon = emoji
                binding.tvTopicIcon.text = emoji
            }.show(childFragmentManager, "topic_dialog_icon")
        }

        // Parent button → PICK_PARENT mode picker (CREATE only)
        binding.btnParentTopic.setOnClickListener {
            TopicPickerBottomSheet.newInstance(
                selectedTopicId = selectedParentId,
                requestKey      = REQUEST_PICK_PARENT,
                mode            = TopicPickerBottomSheet.Mode.PICK_PARENT,
            ).show(childFragmentManager, "topic_dialog_parent")
        }

        val titleRes  =
            if (mode == Mode.CREATE)
                R.string.topic_dialog_new_title
            else
                R.string.topic_dialog_rename_title
        val btnTextRes =
            if (mode == Mode.CREATE)
                R.string.common_btn_create
            else
                R.string.common_btn_save

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(titleRes)
            .setView(binding.root)
            .setPositiveButton(btnTextRes) { _, _ ->
                val name = binding.etTopicName.text.toString().trim()
                if (name.isNotEmpty()) {
                    onConfirm(name, selectedParentId, selectedIcon, emojiToColor(selectedIcon))
                }
            }
            .setNegativeButton(R.string.common_btn_cancel, null)
            .create()
    }

    override fun onStart() {
        super.onStart()

        // Parent picker result — only relevant in CREATE mode, but registering
        // unconditionally is harmless since the button is hidden in RENAME mode.
        childFragmentManager.setFragmentResultListener(REQUEST_PICK_PARENT, this) { _, bundle ->
            selectedParentId = TopicPickerBottomSheet.topicIdFromBundle(bundle)
            updateParentButton()
        }

        // Enable/disable the confirm button based on name content. In RENAME mode
        // the field is pre-filled so the button starts enabled immediately.
        val confirmBtn = (dialog as? AlertDialog)
            ?.getButton(AlertDialog.BUTTON_POSITIVE) ?: return

        confirmBtn.isEnabled = binding.etTopicName.text?.isNotBlank() == true
        binding.etTopicName.doAfterTextChanged { text ->
            confirmBtn.isEnabled = text?.isNotBlank() == true
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Refreshes the parent button label and text colour to reflect [selectedParentId].
     * No-op when called in RENAME mode (button is GONE).
     *
     * • Null  → "Parent topic (optional)" in secondary colour  (root / no parent)
     * • Set   → "{icon}  {name}"          in primary colour   (specific parent)
     */
    private fun updateParentButton() {
        val parent = selectedParentId?.let { id ->
            viewModel.allTopics.value.firstOrNull { it.id == id }
        }
        if (parent == null) {
            binding.btnParentTopic.text = getString(R.string.topic_new_btn_parent_unset)
            binding.btnParentTopic.setTextColor(
                requireContext().themeColor(R.attr.colorTextSecondary)
            )
        } else {
            binding.btnParentTopic.text = "${parent.icon}  ${parent.name}"
            binding.btnParentTopic.setTextColor(
                requireContext().themeColor(R.attr.colorTextPrimary)
            )
        }
    }
}