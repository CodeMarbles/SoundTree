package app.soundtree.ui

/**
 * Where the player pill navigates when it has nothing to expand into
 * (no recording selected). Routes the user somewhere they can pick something.
 *
 * ALL_RECORDINGS — Library → All (flat chronological list)
 * TOPICS         — Library → Topics (topic tree)
 */
enum class PlayerBrowseDestination {
    ALL_RECORDINGS, TOPICS;

    companion object {
        fun fromString(s: String?) = entries.firstOrNull { it.name == s } ?: ALL_RECORDINGS
    }
}