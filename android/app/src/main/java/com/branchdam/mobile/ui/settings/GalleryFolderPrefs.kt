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
 * folder of that name could collide with (issue #222). [read] migrates
 * that legacy value once, to [BranchDamKeys.NO_GALLERY_FOLDERS_SELECTED]
 * = true plus an empty set. The migration only runs while the boolean
 * key is absent; every [write] stores it, so after the first write or
 * migration a folder literally named "__NONE__" is an ordinary folder.
 */
object GalleryFolderPrefs {

    /** The pre-#222 "no folders selected" marker. Read-only: never written. */
    const val LEGACY_NO_FOLDERS_SENTINEL = "__NONE__"

    fun read(prefs: SharedPreferences): GalleryFolderSelection {
        val folders = prefs.getStringSet(BranchDamKeys.INCLUDED_GALLERY_FOLDERS, emptySet()) ?: emptySet()
        if (!prefs.contains(BranchDamKeys.NO_GALLERY_FOLDERS_SELECTED) &&
            folders == setOf(LEGACY_NO_FOLDERS_SENTINEL)
        ) {
            val migrated = GalleryFolderSelection(folders = emptySet(), noneSelected = true)
            write(prefs, migrated)
            return migrated
        }
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
