package com.branchdam.mobile.service

import com.branchdam.mobile.EngineHolder
import com.branchdam.mobile.PendingImport

/**
 * Keeps the import-confirmation notification in step with the persisted
 * pending_import set, not with what one scan pass staged.
 *
 * Rows staged before a process death survive in the core's table but sit
 * below the scan cursor, so no later scan stages them again. Announcing
 * only "what this pass staged" left those photos unreachable until a new
 * photo happened to be staged. [refresh] reads the whole persisted set
 * instead and posts when something awaits an answer and either
 *
 * - this pass staged new items, or
 * - the count differs from the last one this process announced (always
 *   the case for the first readable refresh after a process start, which
 *   is what brings back the rows of a process that died).
 *
 * An unchanged set is not re-posted on every unrelated MediaStore change,
 * so a notification the user swiped away does not keep coming back.
 *
 * Every pending row is announced whatever the auto-import setting: a row
 * leaves pending_import only through a notification action, and with
 * auto-import on rows still appear (an item whose upload enqueue failed is
 * staged instead, and rows staged before the setting was switched on stay).
 *
 * Thread-safe: the observer's startup refresh and its debounced scan may
 * call [refresh] concurrently.
 */
internal class PendingImportAnnouncer(
    private val readPending: () -> List<PendingImport>? = { EngineHolder.listPendingImports() },
    private val post: (pendingCount: Int, stagedBeforeUnix: Long) -> Unit,
) {
    private var lastAnnounced = NOT_ANNOUNCED

    /**
     * Re-reads the persisted pending set and posts the notification for it
     * when warranted (see the class doc). [stagedThisPass] is how many items
     * the calling scan pass just staged. Returns true when it posted. When
     * the set can't be read (engine not open yet) nothing changes, so the
     * next refresh still announces.
     */
    @Synchronized
    fun refresh(stagedThisPass: Int = 0): Boolean {
        val pending = readPending() ?: return false
        val count = pending.size
        val announce = count > 0 && (stagedThisPass > 0 || count != lastAnnounced)
        if (announce) {
            // The notification's actions apply to the rows it announced:
            // anything staged later (even in the same second as this post)
            // has a larger createdAtUnix than the newest announced row.
            post(count, pending.maxOf { it.createdAtUnix })
        }
        lastAnnounced = count
        return announce
    }

    private companion object {
        const val NOT_ANNOUNCED = -1
    }
}
