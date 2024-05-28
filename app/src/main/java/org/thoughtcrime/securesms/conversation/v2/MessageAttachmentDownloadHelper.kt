package org.thoughtcrime.securesms.conversation.v2

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.jobs.AttachmentDownloadJob
import org.session.libsession.messaging.jobs.JobQueue
import org.session.libsession.messaging.sending_receiving.attachments.Attachment
import org.session.libsession.messaging.sending_receiving.attachments.AttachmentTransferProgress
import org.session.libsession.messaging.sending_receiving.attachments.DatabaseAttachment
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.MmsMessageRecord

class MessageAttachmentDownloadHelper(
    private val jobQueue: JobQueue,
    storage: StorageProtocol = MessagingModuleConfiguration.shared.storage,
    scope: CoroutineScope = GlobalScope,
) {
    private val downloadRequests = MutableSharedFlow<DatabaseAttachment>(extraBufferCapacity = 1)

    init {
        scope.launch {
            downloadRequests.timedBuffer(100, 10)
                .flowOn(Dispatchers.IO)
                .map { bulkDownloadRequests ->
                    // For each download request to start a new job, these conditions must be met:
                    // 1. The transfer state of the attachment must be in a pending state
                    // 2. The attachment must not still be in the process of being uploaded

                    val eligibleRequests = bulkDownloadRequests
                        .asSequence()
                        .filter { it.transferState == AttachmentTransferProgress.TRANSFER_PROGRESS_PENDING }
                        .distinctBy { it.attachmentId.rowId }
                        .toList()

                    Log.d("MessageAttachmentDownloadHelper", "Eligible requests: $eligibleRequests")

                    if (eligibleRequests.isEmpty()) {
                        return@map emptyList()
                    }

                    val existingUploadJobIDs =
                        storage.getAttachmentUploadJobs(eligibleRequests.map { it.attachmentId.rowId })
                            .mapTo(hashSetOf()) { it.attachmentID }

                    // Remove the requests that are still being uploaded
                    eligibleRequests.filterNot { existingUploadJobIDs.contains(it.attachmentId.rowId) }
                }
                .collect { requests ->
                    for (req in requests) {
                        val job = AttachmentDownloadJob(req.attachmentId.rowId, req.mmsId)
                        Log.d(
                            "MessageAttachmentDownloadHelper",
                            "Starting download job for attachment ${req.attachmentId.rowId}"
                        )
                        jobQueue.add(job)
                    }
                }
        }
    }

    /**
     * Notify the helper that a message has become visible. The helper may choose to start
     * downloading the attachments in the message if any. Whether this helper will actually
     * download any attachment is up to its own discretion.
     *
     * This method is cheap, so the callers don't need to worry about excessive, or redundant calls.
     */
    fun onMessageBecomeVisible(msg: MessageRecord) {
        msg.allAttachments
            .filterIsInstance<DatabaseAttachment>()
            .forEach(downloadRequests::tryEmit)
    }

    fun onAttachmentDownloadRequested(attachment: Attachment) {
        if (attachment is DatabaseAttachment) {
            downloadRequests.tryEmit(attachment)
        }
    }

    private val MessageRecord.allAttachments: Sequence<Attachment>
        get() = when (this) {
            is MmsMessageRecord -> slideDeck.asAttachments()
                .asSequence() + linkPreviews.asSequence().mapNotNull { it.thumbnail.orNull() }

            else -> emptySequence()
        }
}

/**
 * Scan this flow with the given initial state and accumulator function, emitting the latest result.
 *
 * This function is similar to [kotlinx.coroutines.flow.scan], the crucial difference is that this function
 * will cancel the previous transform when a new item is emitted.
 */
private fun <T, S, R> Flow<T>.scanLatest(state: S, transform: suspend (S, T) -> R): Flow<R> {
    return channelFlow {
        collectLatest {
            send(transform(state, it))
        }
    }
}

/**
 * Transform this flow into a flow of lists of items, where each list contains at most [maxItems] items,
 * or the buffer ot items when the interval of [intervalMills] has passed since the first item was emitted,
 * whichever comes first.
 */
private fun <T> Flow<T>.timedBuffer(intervalMills: Long, maxItems: Int): Flow<List<T>> {
    require(maxItems > 0) { "maxItems must be greater than 0" }
    require(intervalMills > 0) { "intervalMills must be greater than 0" }

    class State(
        var items: MutableList<T> = mutableListOf(),
        var firstItemTimestamp: Long = -1
    )

    return scanLatest(State()) { state, item ->
        val now = SystemClock.uptimeMillis()

        if (state.firstItemTimestamp < 0L) {
            state.firstItemTimestamp = now
        }

        state.items.add(item)

        if (state.items.size < maxItems) {
            // As we don't have enough items for the buffer, we will wait until the delay time is up.
            // The delay itself can be interrupted by a new item (from `scanLatest`), at which point
            // the state will be re-evaluated.
            val deadline = state.firstItemTimestamp + intervalMills
            if (now < deadline) {
                delay(deadline - now)
            }
        }

        // If we reach here, we have enough items or the interval has passed.
        val result = state.items
        state.items = mutableListOf()
        state.firstItemTimestamp = -1
        result
    }.filter { it.isNotEmpty() }
}