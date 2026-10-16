package com.branchdam.mobile.triage

import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * How MediaStore lets this app delete media it did not create. Scoped
 * storage (API 29+) refuses a plain `ContentResolver.delete` on another
 * app's camera-roll item, so the user has to confirm through a system
 * dialog that only the UI layer can launch.
 */
enum class DeleteStrategy {
    /** API 28: legacy storage, `ContentResolver.delete` works directly. */
    DIRECT,

    /**
     * API 29: `delete` throws [RecoverableSecurityException]; launching
     * its `userAction` grants write access to that one item, after which
     * the delete must be retried.
     */
    RECOVERABLE_SECURITY_EXCEPTION,

    /**
     * API 30+: [MediaStore.createDeleteRequest] builds one system dialog
     * for the whole batch; the system performs the delete itself when the
     * user confirms.
     */
    CREATE_DELETE_REQUEST,
}

/** Pure decision: which [DeleteStrategy] a device at [sdkInt] needs. */
fun deleteStrategyFor(sdkInt: Int): DeleteStrategy = when {
    sdkInt >= 30 -> DeleteStrategy.CREATE_DELETE_REQUEST
    sdkInt == 29 -> DeleteStrategy.RECOVERABLE_SECURITY_EXCEPTION
    else -> DeleteStrategy.DIRECT
}

/** Outcome of one plain `ContentResolver.delete` attempt. */
sealed interface DirectDeleteOutcome {
    object Deleted : DirectDeleteOutcome
    object NotDeleted : DirectDeleteOutcome

    /** API 29 only: the user must approve via [intentSender] first. */
    data class NeedsConsent(val intentSender: IntentSender) : DirectDeleteOutcome
}

/**
 * A system confirmation the UI must launch (as an `IntentSenderRequest`)
 * before MediaStore will delete [uris].
 */
data class DeleteConsentRequest(
    val intentSender: IntentSender,
    val uris: List<String>,
    /**
     * True on API 29: approval only grants write access, so the delete is
     * retried afterwards. False on API 30+: RESULT_OK means the system
     * already deleted the items.
     */
    val retryDeleteAfterApproval: Boolean,
)

sealed interface DeleteStep {
    /** Nothing more for the user to confirm. */
    data class Done(val deleted: Set<String>, val failed: Set<String>) : DeleteStep

    /**
     * The UI must launch [request] and report the result through
     * [MediaStoreDeleter.resume]. [remaining] are the items still to be
     * attempted afterwards (API 29 asks one item at a time).
     */
    data class NeedsConsent(
        val request: DeleteConsentRequest,
        val deletedSoFar: Set<String>,
        val failedSoFar: Set<String>,
        val remaining: List<String>,
    ) : DeleteStep
}

/**
 * Deletes MediaStore items in the way the device's API level allows,
 * pausing with a [DeleteStep.NeedsConsent] whenever the user has to
 * confirm. The platform calls are injected so the per-API branching can
 * be unit-tested without a device; production code uses [forContext].
 */
class MediaStoreDeleter(
    private val sdkInt: Int,
    private val directDelete: (uri: String) -> DirectDeleteOutcome,
    private val createDeleteRequest: (uris: List<String>) -> IntentSender,
) {

    val strategy: DeleteStrategy get() = deleteStrategyFor(sdkInt)

    fun start(uris: List<String>): DeleteStep {
        val distinct = uris.distinct()
        if (distinct.isEmpty()) return DeleteStep.Done(emptySet(), emptySet())
        return when (strategy) {
            DeleteStrategy.CREATE_DELETE_REQUEST -> {
                val sender = try {
                    createDeleteRequest(distinct)
                } catch (e: Exception) {
                    // IllegalArgumentException for a non-MediaStore URI,
                    // SecurityException without read access, etc.
                    Log.w(TAG, "createDeleteRequest failed for ${distinct.size} item(s)", e)
                    return DeleteStep.Done(emptySet(), distinct.toSet())
                }
                DeleteStep.NeedsConsent(
                    request = DeleteConsentRequest(sender, distinct, retryDeleteAfterApproval = false),
                    deletedSoFar = emptySet(),
                    failedSoFar = emptySet(),
                    remaining = emptyList(),
                )
            }
            DeleteStrategy.RECOVERABLE_SECURITY_EXCEPTION,
            DeleteStrategy.DIRECT -> deleteSequentially(distinct, emptySet(), emptySet())
        }
    }

    /**
     * Continues after the user answered [step]'s dialog. [approved] is
     * true only for `Activity.RESULT_OK`. A declined dialog fails the
     * item it was for and every item not yet attempted: the user said no,
     * so the app does not keep asking.
     */
    fun resume(step: DeleteStep.NeedsConsent, approved: Boolean): DeleteStep {
        val request = step.request
        if (!approved) {
            return DeleteStep.Done(
                deleted = step.deletedSoFar,
                failed = step.failedSoFar + request.uris + step.remaining,
            )
        }
        if (!request.retryDeleteAfterApproval) {
            // API 30+: the system already deleted the batch.
            return DeleteStep.Done(
                deleted = step.deletedSoFar + request.uris,
                failed = step.failedSoFar,
            )
        }
        // API 29: write access is granted now; retry each approved item.
        val deleted = step.deletedSoFar.toMutableSet()
        val failed = step.failedSoFar.toMutableSet()
        for (uri in request.uris) {
            if (directDelete(uri) == DirectDeleteOutcome.Deleted) deleted += uri else failed += uri
        }
        return deleteSequentially(step.remaining, deleted, failed)
    }

    private fun deleteSequentially(
        uris: List<String>,
        deletedSoFar: Set<String>,
        failedSoFar: Set<String>,
    ): DeleteStep {
        val deleted = deletedSoFar.toMutableSet()
        val failed = failedSoFar.toMutableSet()
        for ((index, uri) in uris.withIndex()) {
            when (val outcome = directDelete(uri)) {
                DirectDeleteOutcome.Deleted -> deleted += uri
                DirectDeleteOutcome.NotDeleted -> failed += uri
                is DirectDeleteOutcome.NeedsConsent -> {
                    if (strategy != DeleteStrategy.RECOVERABLE_SECURITY_EXCEPTION) {
                        failed += uri
                        continue
                    }
                    return DeleteStep.NeedsConsent(
                        request = DeleteConsentRequest(
                            outcome.intentSender,
                            listOf(uri),
                            retryDeleteAfterApproval = true,
                        ),
                        deletedSoFar = deleted,
                        failedSoFar = failed,
                        remaining = uris.drop(index + 1),
                    )
                }
            }
        }
        return DeleteStep.Done(deleted, failed)
    }

    companion object {
        private const val TAG = "MediaStoreDeleter"

        /** Production deleter backed by [context]'s ContentResolver. */
        fun forContext(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): MediaStoreDeleter {
            val resolver = context.contentResolver
            return MediaStoreDeleter(
                sdkInt = sdkInt,
                directDelete = { uri -> resolverDelete(resolver, uri) },
                createDeleteRequest = { uris ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        createDeleteRequestApi30(resolver, uris)
                    } else {
                        throw UnsupportedOperationException("createDeleteRequest needs API 30")
                    }
                },
            )
        }

        private fun resolverDelete(resolver: ContentResolver, uri: String): DirectDeleteOutcome =
            try {
                if (resolver.delete(Uri.parse(uri), null, null) > 0) {
                    DirectDeleteOutcome.Deleted
                } else {
                    DirectDeleteOutcome.NotDeleted
                }
            } catch (e: SecurityException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException) {
                    DirectDeleteOutcome.NeedsConsent(e.userAction.actionIntent.intentSender)
                } else {
                    Log.w(TAG, "delete($uri) denied", e)
                    DirectDeleteOutcome.NotDeleted
                }
            } catch (e: Exception) {
                Log.w(TAG, "delete($uri) failed", e)
                DirectDeleteOutcome.NotDeleted
            }

        @RequiresApi(Build.VERSION_CODES.R)
        private fun createDeleteRequestApi30(resolver: ContentResolver, uris: List<String>): IntentSender =
            MediaStore.createDeleteRequest(resolver, uris.map { Uri.parse(it) }).intentSender
    }
}
