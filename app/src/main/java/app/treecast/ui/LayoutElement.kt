package app.treecast.ui

import androidx.annotation.StringRes
import app.treecast.R

/**
 * The five main chrome elements that can be reordered in the app layout.
 *
 * [displayNameRes] is the string resource for the human-readable label
 * shown in the reorder widget.
 * [DEFAULT_ORDER] is the factory arrangement:
 *   Title Bar → Content → Mini Player → Mini Recorder → Nav
 *
 * Note: existing users with a 4-element saved order will fall back to
 * DEFAULT_ORDER on first launch after this update, since [parseOrder]
 * rejects any serialised list whose length doesn't match [entries.size].
 */
enum class LayoutElement(@StringRes val displayNameRes: Int) {
    TITLE_BAR(R.string.settings_layout_elem_title_bar),
    CONTENT(R.string.settings_layout_elem_content),
    MINI_PLAYER(R.string.settings_layout_elem_mini_player),
    MINI_RECORDER(R.string.settings_layout_elem_mini_recorder),
    NAV(R.string.settings_layout_elem_nav);

    companion object {
        val DEFAULT_ORDER: List<LayoutElement> =
            listOf(TITLE_BAR, CONTENT, MINI_PLAYER, MINI_RECORDER, NAV)

        /**
         * Parses a comma-separated string of element names back into an ordered list.
         * Falls back to [DEFAULT_ORDER] if the string is malformed or incomplete.
         */
        fun parseOrder(csv: String): List<LayoutElement> {
            val valid = entries.associateBy { it.name }
            val parsed = csv.split(",")
                .map { it.trim() }
                .mapNotNull { valid[it] }
                .distinct()
            return if (parsed.size == entries.size) parsed else DEFAULT_ORDER
        }

        /** Serialises an ordered list to a comma-separated string for SharedPreferences. */
        fun toOrderString(order: List<LayoutElement>): String =
            order.joinToString(",") { it.name }
    }
}