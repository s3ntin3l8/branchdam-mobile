package com.branchdam.mobile.ui.settings

import android.content.SharedPreferences
import com.branchdam.mobile.BranchDamKeys

/**
 * The persisted Gallery folder filter.
 *
 * [folders] empty and [noneSelected] false means "all folders";
 * [noneSelected] true means "no folders" (and [folders] is empty).
 */
data class GalleryFolderSelection(
    val folders: Set<String>,
    val noneSelected: Boolean,
)

/**
 * Reads and writes [GalleryFolderSelection] in the plain prefs file.
 *
 * Older builds stored "no folders" as the set {"__NONE__"}, which a real
 * folder of that name could collide with (issue #222). [migrateLegacy]
 * rewrites that legacy value once, at app start
 * ([com.branchdam.mobile.BranchDamApplication.onCreate], before any screen
 * reads the filter), to [BranchDamKeys.NO_GALLERY_FOLDERS_SELECTED] = true
 * plus an empty set. It only acts while the boolean key is absent; every
 * [write] stores it, so after the first write or migration a folder
 * literally named "__NONE__" is an ordinary folder. [read] never writes.
 */
object GalleryFolderPrefs {

    /** The pre-#222 "no folders selected" marker. Read-only: never written. */
    const val LEGACY_NO_FOLDERS_SENTINEL = "__NONE__"

    /**
     * One-shot migration of the pre-#222 sentinel; returns true when it
     * rewrote the prefs. Idempotent: a no-op once the boolean key exists.
     */
    fun migrateLegacy(prefs: SharedPreferences): Boolean {
        if (prefs.contains(BranchDamKeys.NO_GALLERY_FOLDERS_SELECTED)) return false
        val folders = prefs.getStringSet(BranchDamKeys.INCLUDED_GALLERY_FOLDERS, null) ?: return false
        if (folders != setOf(LEGACY_NO_FOLDERS_SENTINEL)) return false
        write(prefs, GalleryFolderSelection(folders = emptySet(), noneSelected = true))
        return true
    }

    /** The stored selection. A pure read: see [migrateLegacy]. */
    fun read(prefs: SharedPreferences): GalleryFolderSelection {
        val folders = prefs.getStringSet(BranchDamKeys.INCLUDED_GALLERY_FOLDERS, emptySet()) ?: emptySet()
        val noneSelected = prefs.getBoolean(BranchDamKeys.NO_GALLERY_FOLDERS_SELECTED, false)
        // Copy: the set getStringSet returns must not be modified or kept.
        return GalleryFolderSelection(folders = if (noneSelected) emptySet() else folders.toSet(), noneSelected = noneSelected)
    }

    fun write(prefs: SharedPreferences, selection: GalleryFolderSelection) {
        prefs.edit()
            .putStringSet(BranchDamKeys.INCLUDED_GALLERY_FOLDERS, selection.folders)
            .putBoolean(BranchDamKeys.NO_GALLERY_FOLDERS_SELECTED, selection.noneSelected)
            .apply()
    }
}
