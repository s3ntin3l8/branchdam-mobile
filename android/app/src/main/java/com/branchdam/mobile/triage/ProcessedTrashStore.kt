package com.branchdam.mobile.triage

import android.content.SharedPreferences
import com.branchdam.mobile.BranchDamKeys

/**
 * The content URIs of trashed items whose delete event was already
 * handled, persisted in SharedPreferences so a process restart does not
 * re-send EVENT_NODE_DELETED for everything still in the recycle bin.
 *
 * Kept bounded two ways: [TrashSyncObserver] drops entries that are no
 * longer in the bin after each successful query (restored or purged
 * items), and the set never holds more than [maxEntries] (oldest
 * dropped first). Stored as one newline-separated string rather than a
 * StringSet so insertion order survives; content URIs never contain a
 * newline.
 *
 * Not thread-safe; the observer's single-in-flight debounced scan is its
 * only user.
 */
class ProcessedTrashStore(
    private val prefs: SharedPreferences,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private var cache: LinkedHashSet<String>? = null

    private fun entries(): LinkedHashSet<String> {
        cache?.let { return it }
        val raw = prefs.getString(BranchDamKeys.TRASH_PROCESSED_URIS, null).orEmpty()
        val loaded = raw.split('\n').filterTo(LinkedHashSet()) { it.isNotEmpty() }
        cache = loaded
        return loaded
    }

    operator fun contains(uri: String): Boolean = uri in entries()

    /** Records [uri] as handled and persists the set. */
    fun add(uri: String) {
        val set = entries()
        set.remove(uri) // re-adding moves it to the newest end
        set.add(uri)
        trimAndSave(set)
    }

    /**
     * Keeps only the URIs still in the recycle bin ([stillTrashed]) and
     * persists the result. Callers must pass the full, successfully
     * queried bin contents: an empty set clears everything.
     */
    fun retainOnly(stillTrashed: Set<String>) {
        val set = entries()
        if (set.retainAll(stillTrashed)) {
            trimAndSave(set)
        }
    }

    fun snapshot(): Set<String> = LinkedHashSet(entries())

    private fun trimAndSave(set: LinkedHashSet<String>) {
        val overflow = set.size - maxEntries
        if (overflow > 0) {
            val oldestFirst = set.iterator()
            repeat(overflow) {
                oldestFirst.next()
                oldestFirst.remove()
            }
        }
        prefs.edit().putString(BranchDamKeys.TRASH_PROCESSED_URIS, set.joinToString("\n")).apply()
    }

    companion object {
        /**
         * Far more than a camera roll's 30-day recycle bin normally
         * holds; only a safety net, since [retainOnly] already drops
         * entries that left the bin.
         */
        const val DEFAULT_MAX_ENTRIES = 5_000
    }
}
