package com.branchdam.mobile.lineage

import com.branchdam.mobile.EngineHolder
import com.branchdam.mobile.observer.MediaItem
import com.branchdam.mobile.ui.lineage.InPhoneEditResolver

data class InPhoneEdit(
    val originalMaster: MediaItem,
    val editedDerivative: MediaItem,
    val editorApp: String,
    val confidence: Double = 0.95
)

object EditCorrelator {

    /**
     * Correlates in-phone edits (Google Photos / Luminar Neo Mobile exports in Pictures/ or DCIM/Restored)
     * back to the camera roll master asset using O(1) hash map indexing.
     */
    fun findInPhoneEdits(masters: List<MediaItem>, derivatives: List<MediaItem>): List<InPhoneEdit> {
        val edits = mutableListOf<InPhoneEdit>()

        // Index masters by normalized base stem for O(1) hash lookup
        val masterStemMap = HashMap<String, MediaItem>(masters.size)
        for (master in masters) {
            val stem = extractBaseStem(master.displayName)
            if (stem.isNotBlank()) {
                masterStemMap[stem] = master
            }
        }

        // Only evaluate candidates that contain editor path or filename hints
        for (edited in derivatives) {
            val path = edited.filePath
            val name = edited.displayName

            val isEditedCandidate = path.contains("Luminar", ignoreCase = true) ||
                path.contains("Edited", ignoreCase = true) ||
                path.contains("Restored", ignoreCase = true) ||
                name.contains("edited", ignoreCase = true) ||
                name.contains("exported", ignoreCase = true) ||
                name.contains("-EDIT", ignoreCase = true)

            if (!isEditedCandidate) continue

            val app = when {
                path.contains("Luminar", ignoreCase = true) -> "Luminar Neo Mobile"
                path.contains("Edited", ignoreCase = true) -> "Google Photos Editor"
                path.contains("Restored", ignoreCase = true) -> "Google Photos Restored"
                else -> "In-Phone Editor"
            }

            val editedStem = extractEditedBaseStem(edited.displayName)
            val matchingMaster = masterStemMap[editedStem]

            if (matchingMaster != null && matchingMaster.id != edited.id) {
                edits.add(
                    InPhoneEdit(
                        originalMaster = matchingMaster,
                        editedDerivative = edited,
                        editorApp = app,
                        confidence = 0.95
                    )
                )
            }
        }

        return edits
    }

    fun registerEditLineage(edits: List<InPhoneEdit>): Int {
        var count = 0
        for (edit in edits) {
            EngineHolder.enqueueLineageEvent(
                parentLocalID = edit.originalMaster.contentUri,
                childLocalID = edit.editedDerivative.contentUri,
                relationshipType = "DERIVED_FROM",
                resolver = InPhoneEditResolver.format(edit.editorApp),
                confidence = edit.confidence
            )
            count++
        }
        return count
    }

    private fun extractBaseStem(filename: String): String {
        return filename.substringBeforeLast('.').removePrefix("PXL_").removePrefix("IMG_")
    }

    private fun extractEditedBaseStem(filename: String): String {
        return extractBaseStem(filename)
            .replace("_exported", "")
            .replace("_edited", "")
            .replace("-EDIT", "")
            .replace("_Luminar", "")
    }
}
