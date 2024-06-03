package org.thoughtcrime.securesms.conversation.v2

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.jobs.AttachmentDownloadJob
import org.session.libsession.messaging.jobs.AttachmentUploadJob
import org.session.libsession.messaging.jobs.JobQueue
import org.session.libsession.messaging.sending_receiving.attachments.AttachmentTransferProgress
import org.session.libsession.messaging.sending_receiving.attachments.DatabaseAttachment
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.MmsDatabase
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.util.timedBuffer

class AttachmentDownloadHelper(
    private val storage: StorageProtocol,
    mmsDatabase: MmsDatabase,
    jobQueue: JobQueue = JobQueue.shared,
    scope: CoroutineScope = CoroutineScope(Dispatchers.Default) + SupervisorJob(),
) {
    companion object {
        private const val BUFFER_TIMEOUT_MILLS = 500L
        private const val BUFFER_MAX_ITEMS = 10
        private const val LOG_TAG = "AttachmentDownloadHelper"
    }

    private val downloadRequests = Channel<DatabaseAttachment>(UNLIMITED)

    init {
        scope.launch {
            downloadRequests
                .receiveAsFlow()
                .timedBuffer(BUFFER_TIMEOUT_MILLS, BUFFER_MAX_ITEMS)
                .map { attachments ->
                    withContext(Dispatchers.IO) {
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
                        attachments.filter { attachment ->
                            attachment.attachmentId.rowId !in pendingAttachmentIDs &&
                                    eligibleForDownloadTask(attachment, messagesByID[attachment.mmsId])
                        }
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

    private fun eligibleForDownloadTask(attachment: DatabaseAttachment, message: MessageRecord?): Boolean {
        if (message == null) {
            Log.w(LOG_TAG, "Message ${attachment.mmsId} not found for attachment ${attachment.attachmentId}")
            return false
        }

        assert(message.id == attachment.mmsId) { "Message ID mismatch: ${message.id} != ${attachment.mmsId}" }

        if (message.isOutgoing) return true

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

        if (threadRecipient.isGroupRecipient) return true

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
}
