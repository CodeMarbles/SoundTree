package com.treecast.app.ui

/**
 * The four main chrome elements that can be reordered in the app layout.
 *
 * [displayName] is the human-readable label shown in the reorder widget.
 * [DEFAULT_ORDER] is the factory arrangement: Title Bar → Content → Mini Player → Nav.
 */
enum class LayoutElement(val displayName: String) {
    TITLE_BAR("Title Bar"),
    CONTENT("Content"),
    MINI_PLAYER("Mini Player"),
    NAV("Nav");

    companion object {
        val DEFAULT_ORDER: List<LayoutElement> =
            listOf(TITLE_BAR, CONTENT, MINI_PLAYER, NAV)

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