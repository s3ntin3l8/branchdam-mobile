package com.branchdam.mobile.lineage

import com.branchdam.mobile.EngineHolder
import com.branchdam.mobile.observer.MediaItem

data class InPhoneEdit(
    val originalMaster: MediaItem,
    val editedDerivative: MediaItem,
    val editorApp: String,
    val confidence: Double = 0.95
)

object EditCorrelator {

    /**
     * Correlates in-phone edits (Google Photos / Luminar Neo Mobile exports in Pictures/ or DCIM/Restored)
     * back to the camera roll master asset.
     */
    fun findInPhoneEdits(masters: List<MediaItem>, derivatives: List<MediaItem>): List<InPhoneEdit> {
        val edits = mutableListOf<InPhoneEdit>()

        for (edited in derivatives) {
            val app = when {
                edited.filePath.contains("Luminar", ignoreCase = true) -> "Luminar Neo Mobile"
                edited.filePath.contains("Edited", ignoreCase = true) -> "Google Photos Editor"
                edited.filePath.contains("Restored", ignoreCase = true) -> "Google Photos Restored"
                else -> "In-Phone Editor"
            }

            // Look for master with stem match or date match
            val editedStem = extractEditedBaseStem(edited.displayName)
            val matchingMaster = masters.firstOrNull { master ->
                master.id != edited.id && (
                    master.displayName.contains(editedStem, ignoreCase = true) ||
                    edited.displayName.contains(extractBaseStem(master.displayName), ignoreCase = true)
                )
            }

            if (matchingMaster != null) {
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
                resolver = "in_phone_${edit.editorApp.lowercase().replace(" ", "_")}",
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
