package com.branchdam.mobile.observer

/**
 * Records what one MediaStore scan pass found, in the only safe order:
 * the intent for each item (an upload-queue row, or a pending_import row
 * awaiting the user's answer) is persisted first, and the scan cursor is
 * advanced only past items whose intent is on disk.
 *
 * The cursor used to be saved before anything was recorded, with the
 * pending items held in memory, so a process death between the scan and
 * the user's "Import now" lost those photos for good: the next scan
 * started after them.
 *
 * Pure logic with the side effects injected, so the ordering is
 * unit-testable without a ContentResolver or the native engine.
 */
internal object NewMediaIntake {

    /** What persisting one scanned item achieved. */
    enum class Outcome {
        /** Queued for upload (auto-import). */
        ENQUEUED,

        /** Recorded in pending_import, awaiting the user's decision. */
        STAGED,

        /** Nothing to record: the user already skipped it. */
        ALREADY_DECIDED,

        /** Nothing was recorded; the item must be offered again. */
        FAILED,
    }

    data class Result(
        val enqueued: List<MediaItem>,
        val staged: List<MediaItem>,
        /** The cursor after this pass (saved when it moved). */
        val cursor: Long,
    )

    /**
     * Persists each of [scanned] via [persist], oldest first, then hands
     * the new cursor (seconds, compared against DATE_TAKEN) to
     * [saveCursor] when it moved. Items without a file path are ignored,
     * as before.
     *
     * On the first FAILED item the pass stops, and the cursor stays
     * below that item's timestamp so the next scan offers it (and any
     * later item) again. Re-persisting an item is harmless: staging is
     * an upsert and the core dedups enqueues by content hash.
     */
    fun commit(
        startCursor: Long,
        scanned: List<MediaItem>,
        persist: (MediaItem) -> Outcome,
        saveCursor: (Long) -> Unit,
    ): Result {
        val ordered = scanned
            .filter { it.filePath.isNotEmpty() }
            .sortedWith(compareBy<MediaItem>({ it.dateTakenUnix }, { it.id }))

        val enqueued = mutableListOf<MediaItem>()
        val staged = mutableListOf<MediaItem>()
        var cursor = startCursor
        for (item in ordered) {
            when (persist(item)) {
                Outcome.ENQUEUED -> {
                    enqueued += item
                    cursor = maxOf(cursor, item.dateTakenUnix)
                }
                Outcome.STAGED -> {
                    staged += item
                    cursor = maxOf(cursor, item.dateTakenUnix)
                }
                Outcome.ALREADY_DECIDED -> cursor = maxOf(cursor, item.dateTakenUnix)
                Outcome.FAILED -> {
                    cursor = minOf(cursor, item.dateTakenUnix - 1).coerceAtLeast(startCursor)
                    break
                }
            }
        }
        if (cursor != startCursor) {
            saveCursor(cursor)
        }
        return Result(enqueued = enqueued, staged = staged, cursor = cursor)
    }
}
