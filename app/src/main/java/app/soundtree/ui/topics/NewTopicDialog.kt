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
 * Modal dialog for creating a new topic.
 *
 * ── Elements ──────────────────────────────────────────────────────────────────
 *
 * • Icon picker     — large emoji in a circular card; tap opens [EmojiPickerBottomSheet].
 * • Name field      — [TextInputLayout] with built-in clear-text (✕) end icon.
 * • Parent topic    — outlined button; opens [TopicPickerBottomSheet] in [PICK_PARENT]
 *                     mode. Unset = root topic; "Top level" in the picker clears a
 *                     previously chosen parent back to root.
 * • Save / Cancel   — Save is disabled until the name field contains non-blank text.
 *
 * ── Callback ──────────────────────────────────────────────────────────────────
 *
 * [onCreated] receives the final (name, parentId, icon, color) tuple. The caller
 * is responsible for persisting the topic; this dialog does no ViewModel writes.
 *
 * ── Call sites ────────────────────────────────────────────────────────────────
 *
 * Pass [initialParentId] = null for a root-level topic (FAB, TopicPickerBottomSheet "+"
 * button) or a known topic ID to pre-fill the parent field (context-menu "New Subtopic"
 * actions in TopicsManageFragment and TopicDetailsFragment).
 */
class NewTopicDialog(
    private val initialParentId: Long?,
    private val onCreated: (name: String, parentId: Long?, icon: String, color: String) -> Unit,
) : DialogFragment() {

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
        // Initialise transient state. If the device rotates while the dialog is
        // open it will dismiss and reopen in default state — acceptable for a
        // creation flow where no data has been committed yet.
        selectedParentId = initialParentId
        selectedIcon     = getString(R.string.topic_emoji_default)

        _binding = DialogNewTopicBinding.inflate(layoutInflater)

        binding.tvTopicIcon.text = selectedIcon
        updateParentButton()

        // Icon tap → emoji picker (constructor-callback, no result listener needed)
        binding.tvTopicIcon.setOnClickListener {
            EmojiPickerBottomSheet { emoji ->
                selectedIcon = emoji
                binding.tvTopicIcon.text = emoji
            }.show(childFragmentManager, "new_topic_icon")
        }

        // Parent button → PICK_PARENT mode picker
        binding.btnParentTopic.setOnClickListener {
            TopicPickerBottomSheet.newInstance(
                selectedTopicId = selectedParentId,
                requestKey      = REQUEST_PICK_PARENT,
                mode            = TopicPickerBottomSheet.Mode.PICK_PARENT,
            ).show(childFragmentManager, "new_topic_parent")
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.topic_dialog_new_title)
            .setView(binding.root)
            // Real listener here — the button is only ever enabled when the name
            // is non-empty (enforced in onStart), so we can always dismiss and
            // fire the callback without needing to intercept the click separately.
            .setPositiveButton(R.string.common_btn_create) { _, _ ->
                val name = binding.etTopicName.text.toString().trim()
                if (name.isNotEmpty()) {
                    onCreated(name, selectedParentId, selectedIcon, emojiToColor(selectedIcon))
                }
            }
            .setNegativeButton(R.string.common_btn_cancel, null)
            .create()
    }

    override fun onStart() {
        super.onStart()

        // Result listener for the parent-topic picker. Registered here (not in
        // onCreateDialog) so the DialogFragment lifecycle is fully STARTED and
        // the childFragmentManager is ready to receive back-stack changes.
        childFragmentManager.setFragmentResultListener(REQUEST_PICK_PARENT, this) { _, bundle ->
            selectedParentId = TopicPickerBottomSheet.topicIdFromBundle(bundle)
            updateParentButton()
        }

        // Enable/disable the Save button based on whether the name field has
        // content. The button starts disabled; the real click action lives in
        // the setPositiveButton listener above, so we don't touch it here.
        val saveBtn = (dialog as? AlertDialog)
            ?.getButton(AlertDialog.BUTTON_POSITIVE) ?: return

        saveBtn.isEnabled = binding.etTopicName.text?.isNotBlank() == true
        binding.etTopicName.doAfterTextChanged { text ->
            saveBtn.isEnabled = text?.isNotBlank() == true
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Refreshes the parent button label and text colour to reflect [selectedParentId].
     *
     * • Null  → "Parent topic (optional)" in secondary colour  (root / no parent)
     * • Set   → "{icon}  {name}"          in primary colour   (specific parent)
     *
     * Tapping the button always re-opens the picker. Choosing "🌳 Top level"
     * in that picker delivers null back here, which acts as the clear action.
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