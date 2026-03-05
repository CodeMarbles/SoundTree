package com.treecast.app.util

import android.content.Context
import android.os.StatFs
import android.os.storage.StorageManager
import java.io.File

/**
 * Represents one storage volume available to the app.
 *
 * [uuid] is a stable identifier across mounts:
 *   - "primary" for the primary external/emulated volume (what used to be
 *     the only option, i.e. the previous [getExternalFilesDir] call)
 *   - The volume UUID string (e.g. "1A2B-3C4D") for removable SD cards
 *
 * [rootDir] is the app-private recordings sub-directory on this volume.
 * The app never needs WRITE_EXTERNAL_STORAGE for these paths on API 29+.
 *
 * [isMounted] reflects whether the volume is currently accessible. A volume
 * that was previously selected but is now unmounted (SD card ejected) will
 * appear in the list with [isMounted] = false so the UI can warn the user.
 */
data class AppVolume(
    val uuid: String,
    val label: String,
    val rootDir: File,
    val totalBytes: Long,
    val freeBytes: Long,
    val isMounted: Boolean
) {
    /** Fraction of space used, in [0.0, 1.0]. */
    val usedFraction: Float
        get() = if (totalBytes == 0L) 0f else (totalBytes - freeBytes).toFloat() / totalBytes

    /** Formatted "X.X GB free of Y.Y GB" string for display. */
    fun freeLabel(): String =
        "${formatBytes(freeBytes)} free of ${formatBytes(totalBytes)}"

    companion object {
        fun formatBytes(bytes: Long): String = when {
            bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576L     -> "%.0f MB".format(bytes / 1_048_576.0)
            else                    -> "%.0f KB".format(bytes / 1_024.0)
        }
    }
}

object StorageVolumeHelper {

    /** Stable UUID used for the primary external (emulated) storage volume. */
    const val UUID_PRIMARY = "primary"

    /**
     * Returns all storage volumes currently known to this device, in order:
     * primary external first, then any removable volumes.
     *
     * Previously-seen volumes that are now unmounted will NOT appear here —
     * the OS simply returns null for those dirs. Use [buildVolumeList] with a
     * set of known UUIDs if you want to show a "unavailable" placeholder.
     */
    fun getVolumes(context: Context): List<AppVolume> {
        val sm = context.getSystemService(StorageManager::class.java)
        val dirs = context.getExternalFilesDirs(null)   // index 0 = primary external

        return dirs.mapIndexedNotNull { index, dir ->
            if (dir == null) return@mapIndexedNotNull null
            val sv = sm.getStorageVolume(dir)
            val uuid = sv?.uuid ?: UUID_PRIMARY
            val label = when {
                index == 0  -> "Internal Storage"
                sv != null  -> sv.getDescription(context) ?: "SD Card"
                else        -> "SD Card"
            }
            val stat = runCatching { StatFs(dir.path) }.getOrNull()
            val rootDir = File(dir, "recordings")
            AppVolume(
                uuid       = uuid,
                label      = label,
                rootDir    = rootDir,
                totalBytes = stat?.totalBytes ?: 0L,
                freeBytes  = stat?.availableBytes ?: 0L,
                isMounted  = dir.exists()
            )
        }
    }

    /**
     * Returns the volume with the given [uuid], or null if it is not currently
     * mounted / available.
     */
    fun getVolumeByUuid(context: Context, uuid: String): AppVolume? =
        getVolumes(context).firstOrNull { it.uuid == uuid }

    /**
     * Returns the preferred default volume. Falls back through:
     *   1. The primary external volume (index 0 from getExternalFilesDirs)
     *   2. Internal files dir (should never actually be needed)
     */
    fun getDefaultVolume(context: Context): AppVolume =
        getVolumes(context).firstOrNull() ?: run {
            val dir = File(context.filesDir, "recordings")
            AppVolume(
                uuid       = UUID_PRIMARY,
                label      = "Internal Storage",
                rootDir    = dir,
                totalBytes = context.filesDir.totalSpace,
                freeBytes  = context.filesDir.freeSpace,
                isMounted  = true
            )
        }

    /** Minimum free space (bytes) before the app warns on recording start. */
    const val WARN_FREE_BYTES = 50L * 1024 * 1024   // 50 MB
}