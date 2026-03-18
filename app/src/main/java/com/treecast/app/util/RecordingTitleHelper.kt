package com.treecast.app.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Single source of truth for how a pending display name is resolved into
 * a final recording title.
 *
 *  - User explicitly renamed → use verbatim
 *  - Auto-generated timestamp still set → prepend "Recording – "
 *  - Nothing set (shouldn't happen in practice) → "Recording – <now>"
 */
object RecordingTitleHelper {

    fun generateStamp(): String =
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date())

    fun resolve(pendingDisplayName: String, userHasRenamed: Boolean): String {
        val stamp = generateStamp()
        return when {
            userHasRenamed && pendingDisplayName.isNotEmpty() -> pendingDisplayName
            pendingDisplayName.isNotEmpty()                   -> "Recording – $pendingDisplayName"
            else                                              -> "Recording – $stamp"
        }
    }
}