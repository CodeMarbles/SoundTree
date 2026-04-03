package com.treecast.app.util

import android.net.Uri
import android.provider.DocumentsContract

/**
 * Extracts a human-readable root-relative path from a SAF tree URI.
 *
 * External-storage document IDs have the form "<uuid>:<path>" (e.g.
 * "1A2B-3C4D:TreeCast/backups") or "primary:<path>" for internal storage.
 * We drop the volume prefix and prepend "/" to produce "/TreeCast/backups".
 *
 * Returns null when [dirUri] is null, blank, or cannot be parsed — the
 * caller simply omits the path line rather than showing a raw URI.
 */
fun backupDirDisplayPath(dirUri: String?): String? {
    if (dirUri.isNullOrBlank()) return null
    return try {
        val docId = DocumentsContract.getTreeDocumentId(Uri.parse(dirUri))
        "/" + docId.substringAfter(":", missingDelimiterValue = docId)
    } catch (_: Exception) {
        null
    }
}