package com.treecast.app.ui.tree

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.emoji2.emojipicker.EmojiPickerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.treecast.app.R

/**
 * Bottom sheet that presents the system emoji picker.
 *
 * Usage:
 *   EmojiPickerBottomSheet { emoji ->
 *       viewModel.updateCategoryIcon(catId, emoji)
 *   }.show(childFragmentManager, "emoji_picker")
 *
 * Requires in build.gradle (app):
 *   implementation "androidx.emoji2:emoji2-views:1.4.0"
 *
 * Theme fix: overriding getTheme() forces the dark surface/icon colours
 * from our palette rather than the default light BottomSheetDialog theme.
 * This is what makes the category tab icons render white instead of black.
 *
 * Scroll fix: in onStart we expand the sheet fully and set skipCollapsed
 * so the BottomSheetBehavior hands all vertical scroll events to the
 * EmojiPickerView's internal RecyclerView rather than dragging the sheet.
 */
class EmojiPickerBottomSheet(
    private val onEmojiSelected: (String) -> Unit
) : BottomSheetDialogFragment() {

    // Apply our dark BottomSheet theme so colorOnSurface = text_primary,
    // which the EmojiPickerView uses for its category icon tint.
    override fun getTheme(): Int = R.style.Theme_TreeCast_BottomSheet

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return EmojiPickerView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                // Half the screen height — the natural keyboard-sized slot.
                resources.displayMetrics.heightPixels / 2
            )
            setOnEmojiPickedListener { emojiInfo ->
                onEmojiSelected(emojiInfo.emoji)
                dismiss()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val sheet = dialog as? BottomSheetDialog ?: return
        val behavior = sheet.behavior

        // Expand immediately — no peek / half-expanded state.
        behavior.state = BottomSheetBehavior.STATE_EXPANDED

        // skipCollapsed: dragging down dismisses instead of collapsing to
        // a half-height peek. This means all upward scroll events reach
        // the EmojiPickerView's RecyclerView unobstructed.
        behavior.skipCollapsed = true
    }
}