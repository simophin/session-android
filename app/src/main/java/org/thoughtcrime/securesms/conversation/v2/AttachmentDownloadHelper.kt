package org.thoughtcrime.securesms.conversation.v2

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withTimeoutOrNull
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.jobs.AttachmentDownloadJob
import org.session.libsession.messaging.jobs.AttachmentUploadJob
import org.session.libsession.messaging.jobs.JobQueue
import org.session.libsession.messaging.sending_receiving.attachments.AttachmentTransferProgress
import org.session.libsession.messaging.sending_receiving.attachments.DatabaseAttachment
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.MmsDatabase
import org.thoughtcrime.securesms.database.model.MessageRecord

class AttachmentDownloadHelper(
    private val storage: StorageProtocol,
    mmsDatabase: MmsDatabase,
    jobQueue: JobQueue = JobQueue.shared,
    scope: CoroutineScope = CoroutineScope(Dispatchers.Default) + SupervisorJob(),
) {
    private val downloadRequests = Channel<DatabaseAttachment>(UNLIMITED)

    /**
     * Single use flow that buffers attachment download requests and emits them in batches. The batch
     * will have size ([BUFFER_MAX_ITEMS]) and time([BUFFER_TIMEOUT_MILLS]) limit.
     */
    private val downloadRequestsFlow: Flow<List<DatabaseAttachment>> = flow {
        var deadline = -1L
        val buffer = mutableListOf<DatabaseAttachment>()

        while (true) {
            if (buffer.isEmpty()) {
                // When started, wait indefinitely for the first attachment to arrive
                buffer += downloadRequests.receive()
                deadline = SystemClock.uptimeMillis() + BUFFER_TIMEOUT_MILLS
                continue
            }

            if (buffer.size < BUFFER_MAX_ITEMS) {
                // Wait for next attachment to arrive with a timeout
                val attachment =
                    withTimeoutOrNull((deadline - SystemClock.uptimeMillis()).coerceAtLeast(0L)) {
                        downloadRequests.receive()
                    }

                if (attachment != null) {
                    // Received an attachment before timeout, loop again
                    buffer += attachment
                    continue
                }
            }

            // When we reach here, the buffer has reached its max size AND the timeout has expired
            emit(buffer.toList())
            buffer.clear()
            deadline = -1L
        }
    }


    init {
        scope.launch {
            downloadRequestsFlow
                .flowOn(Dispatchers.IO)
                .map { attachments ->
                    val pendingAttachmentIDs = storage
                        .getAllPendingJobs(AttachmentDownloadJob.KEY, AttachmentUploadJob.KEY)
                        .values
                        .mapNotNullTo(hashSetOf()) {
                            (it as? AttachmentUploadJob)?.attachmentID
                                ?: (it as? AttachmentDownloadJob)?.attachmentID
                        }

                    val messagesByID = mmsDatabase.getMessages(attachments.map { it.mmsId })
                        .associateBy { it.id }

                    // Before handling out attachment to the download task, we need to
                    // check the requisite for that attachment. This check is very likely to be
                    // performed again in the download task, but adding stuff into job system
                    // is expensive so we need to avoid spawning new task whenever we can.
                    attachments.filter {
                        !pendingAttachmentIDs.contains(it.attachmentId.rowId) &&
                                it.eligibleForDownloadTask(messagesByID[it.mmsId])
                    }
                }
                .collect { attachmentsToDownload ->
                    for (attachment in attachmentsToDownload) {
                        jobQueue.add(
                            AttachmentDownloadJob(
                                attachmentID = attachment.attachmentId.rowId,
                                databaseMessageID = attachment.mmsId
                            )
                        )
                    }
                }
        }
    }

    private fun DatabaseAttachment.eligibleForDownloadTask(message: MessageRecord?): Boolean {
        if (message == null) {
            Log.w(LOG_TAG, "Message $mmsId not found for attachment $attachmentId")
            return false
        }

        assert(message.id == mmsId) {
            "Message ID mismatch: ${message.id} != $mmsId"
        }

        if (message.isOutgoing) {
            return true
        }

        val sender = message.individualRecipient.address.serialize()
        val contact = storage.getContactWithSessionID(sender)

        if (contact == null) {
            Log.w(LOG_TAG, "Contact not found for $sender")
            return false
        }

        val threadRecipient = storage.getRecipientForThread(message.threadId)
        if (threadRecipient == null) {
            Log.w(LOG_TAG, "Thread recipient not found for ${message.threadId}")
            return false
        }

        if (threadRecipient.isGroupRecipient) {
            return true
        }

        return contact.isTrusted
    }


    fun onAttachmentDownloadRequest(attachment: DatabaseAttachment) {
        if (attachment.transferState != AttachmentTransferProgress.TRANSFER_PROGRESS_PENDING) {
            Log.i(
                LOG_TAG,
                "Attachment ${attachment.attachmentId} is not pending, skipping download"
            )
            return
        }

        downloadRequests.trySend(attachment)
    }

    companion object {
        private const val BUFFER_TIMEOUT_MILLS = 500L
        private const val BUFFER_MAX_ITEMS = 10

        private const val LOG_TAG = "AttachmentDownloadHelper"
    }
}