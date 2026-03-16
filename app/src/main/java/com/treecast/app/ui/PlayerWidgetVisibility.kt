package com.treecast.app.ui

/**
 * Controls when the Mini Player widget is shown above the bottom nav.
 *
 * NEVER        — always hidden (pill can still be shown via alwaysShowPlayerPill;
 *                tapping the pill navigates to the Listen tab instead of expanding)
 * WHILE_PLAYING — visible only while a recording is loaded/playing (default, existing behaviour)
 * ALWAYS       — always visible; shows an idle state ("NO SELECTION") when nothing is loaded
 */
enum class PlayerWidgetVisibility {
    NEVER, WHILE_PLAYING, ALWAYS;

    companion object {
        fun fromString(s: String?) = entries.firstOrNull { it.name == s } ?: WHILE_PLAYING
    }
}