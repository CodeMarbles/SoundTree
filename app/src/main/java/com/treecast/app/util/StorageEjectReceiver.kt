package com.treecast.app.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives storage removal broadcasts and notifies a listener so the UI
 * can refresh its volume list and orphan indicators immediately.
 *
 * Registered/unregistered dynamically in [MainActivity] — NOT in the
 * manifest. Static registration of ACTION_MEDIA_REMOVED is blocked by
 * the OS on API 26+ for this action family.
 */
class StorageEjectReceiver(
    private val onEject: () -> Unit
) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_MEDIA_REMOVED,
            Intent.ACTION_MEDIA_UNMOUNTED,
            Intent.ACTION_MEDIA_BAD_REMOVAL -> onEject()
        }
    }
}