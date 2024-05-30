package org.session.libsession.messaging.jobs

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import okio.Buffer
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.file_server.FileServerApi
import org.session.libsession.messaging.messages.Destination
import org.session.libsession.messaging.messages.Message
import org.session.libsession.messaging.open_groups.OpenGroupApi
import org.session.libsession.messaging.sending_receiving.MessageSender
import org.session.libsession.messaging.utilities.Data
import org.session.libsession.utilities.DecodedAudio
import org.session.libsession.utilities.InputStreamMediaDataSource
import org.session.libsession.utilities.UploadResult
import org.session.libsignal.messages.SignalServiceAttachmentStream
import org.session.libsignal.streams.AttachmentCipherOutputStream
import org.session.libsignal.streams.AttachmentCipherOutputStreamFactory
import org.session.libsignal.streams.DigestingRequestBody
import org.session.libsignal.streams.PaddingInputStream
import org.session.libsignal.streams.PlaintextOutputStreamFactory
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.PushAttachmentData
import org.session.libsignal.utilities.Util

class AttachmentUploadJob(val attachmentID: Long, val threadID: String, val message: Message, val messageSendJobID: String) : Job {
    override var delegate: JobDelegate? = null
    override var id: String? = null
    override var failureCount: Int = 0

    // Error
    internal sealed class Error(val description: String) : Exception(description) {
        object NoAttachment : Error("No such attachment.")
    }

    // Settings
    override val maxFailureCount: Int = 20

    companion object {
        val TAG = AttachmentUploadJob::class.simpleName
        val KEY: String = "AttachmentUploadJob"

        // Keys used for database storage
        private val ATTACHMENT_ID_KEY = "attachment_id"
        private val THREAD_ID_KEY = "thread_id"
        private val MESSAGE_KEY = "message"
        private val MESSAGE_SEND_JOB_ID_KEY = "message_send_job_id"
    }

    override suspend fun execute(dispatcherName: String) {
        try {
            val storage = MessagingModuleConfiguration.shared.storage
            val messageDataProvider = MessagingModuleConfiguration.shared.messageDataProvider
            val attachment = messageDataProvider.getScaledSignalAttachmentStream(attachmentID)
                ?: return handleFailure(dispatcherName, Error.NoAttachment)
            val openGroup = storage.getOpenGroup(threadID.toLong())
            if (openGroup != null) {
                val keyAndResult = upload(attachment, openGroup.server, false) {
                    OpenGroupApi.upload(it, openGroup.room, openGroup.server)
                }
                handleSuccess(dispatcherName, attachment, keyAndResult.first, keyAndResult.second)
            } else {
                val keyAndResult = upload(attachment, FileServerApi.server, true) {
                    FileServerApi.upload(it)
                }
                handleSuccess(dispatcherName, attachment, keyAndResult.first, keyAndResult.second)
            }
        } catch (e: java.lang.Exception) {
            if (e == Error.NoAttachment) {
                this.handlePermanentFailure(dispatcherName, e)
            } else {
                this.handleFailure(dispatcherName, e)
            }
        }
    }

    private suspend fun upload(attachment: SignalServiceAttachmentStream, server: String, encrypt: Boolean, upload: suspend (ByteArray) -> Long): Pair<ByteArray, UploadResult> {
        // Key
        val key = if (encrypt) Util.getSecretBytes(64) else ByteArray(0)
        // Length
        val rawLength = attachment.length
        val length = if (encrypt) {
            val paddedLength = PaddingInputStream.getPaddedSize(rawLength)
            AttachmentCipherOutputStream.getCiphertextLength(paddedLength)
        } else {
            attachment.length
        }
        // In & out streams
        // PaddingInputStream adds padding as data is read out from it. AttachmentCipherOutputStream
        // encrypts as it writes data.
        val inputStream = if (encrypt) PaddingInputStream(attachment.inputStream, rawLength) else attachment.inputStream
        val outputStreamFactory = if (encrypt) AttachmentCipherOutputStreamFactory(key) else PlaintextOutputStreamFactory()
        // Create a digesting request body but immediately read it out to a buffer. Doing this makes
        // it easier to deal with inputStream and outputStreamFactory.
        val pad = PushAttachmentData(attachment.contentType, inputStream, length, outputStreamFactory, attachment.listener)
        val contentType = "application/octet-stream"
        val drb = DigestingRequestBody(pad.data, pad.outputStreamFactory, contentType, pad.dataSize, pad.listener)
        Log.d("Loki", "File size: ${length.toDouble() / 1000} kb.")
        val b = Buffer()
        drb.writeTo(b)
        val data = b.readByteArray()
        // Upload the data
        val id = upload(data)
        val digest = drb.transmittedDigest
        // Return
        return Pair(key, UploadResult(id, "${server}/file/$id", digest))
    }

    private fun handleSuccess(dispatcherName: String, attachment: SignalServiceAttachmentStream, attachmentKey: ByteArray, uploadResult: UploadResult) {
        Log.d(TAG, "Attachment uploaded successfully.")
        delegate?.handleJobSucceeded(this, dispatcherName)
        val messageDataProvider = MessagingModuleConfiguration.shared.messageDataProvider
        messageDataProvider.handleSuccessfulAttachmentUpload(attachmentID, attachment, attachmentKey, uploadResult)
        if (attachment.contentType.startsWith("audio/")) {
            // process the duration
            try {
                val inputStream = messageDataProvider.getAttachmentStream(attachmentID)!!.inputStream!!
                InputStreamMediaDataSource(inputStream).use { mediaDataSource ->
                    val durationMs = (DecodedAudio.create(mediaDataSource).totalDuration / 1000.0).toLong()
                    messageDataProvider.getDatabaseAttachment(attachmentID)?.attachmentId?.let { attachmentId ->
                        messageDataProvider.updateAudioAttachmentDuration(attachmentId, durationMs, threadID.toLong())
                    }
                }
            } catch (e: Exception) {
                Log.e("Loki", "Couldn't process audio attachment", e)
            }
        }
        val storage = MessagingModuleConfiguration.shared.storage
        storage.getMessageSendJob(messageSendJobID)?.let {
            val destination = it.destination as? Destination.OpenGroup ?: return@let
            val updatedJob = MessageSendJob(
                message = it.message,
                destination = Destination.OpenGroup(
                    destination.roomToken,
                    destination.server,
                    destination.whisperTo,
                    destination.whisperMods,
                    destination.fileIds + uploadResult.id.toString()
                )
            )
            updatedJob.id = it.id
            updatedJob.delegate = it.delegate
            updatedJob.failureCount = it.failureCount
            storage.persistJob(updatedJob)
        }
        storage.resumeMessageSendJobIfNeeded(messageSendJobID)
    }

    private fun handlePermanentFailure(dispatcherName: String, e: Exception) {
        Log.w(TAG, "Attachment upload failed permanently due to error: $this.")
        delegate?.handleJobFailedPermanently(this, dispatcherName, e)
        MessagingModuleConfiguration.shared.messageDataProvider.handleFailedAttachmentUpload(attachmentID)
        failAssociatedMessageSendJob(e)
    }

    private fun handleFailure(dispatcherName: String, e: Exception) {
        Log.w(TAG, "Attachment upload failed due to error: $this.")
        delegate?.handleJobFailed(this, dispatcherName, e)
        if (failureCount + 1 >= maxFailureCount) {
            failAssociatedMessageSendJob(e)
        }
    }

    private fun failAssociatedMessageSendJob(e: Exception) {
        val storage = MessagingModuleConfiguration.shared.storage
        val messageSendJob = storage.getMessageSendJob(messageSendJobID)
        MessageSender.handleFailedMessageSend(this.message, e)
        if (messageSendJob != null) {
            storage.markJobAsFailedPermanently(messageSendJobID)
        }
    }

    override fun serialize(): Data {
        val kryo = Kryo()
        kryo.isRegistrationRequired = false
        val serializedMessage = ByteArray(4096)
        val output = Output(serializedMessage, Job.MAX_BUFFER_SIZE)
        kryo.writeClassAndObject(output, message)
        output.close()
        return Data.Builder()
            .putLong(ATTACHMENT_ID_KEY, attachmentID)
            .putString(THREAD_ID_KEY, threadID)
            .putByteArray(MESSAGE_KEY, output.toBytes())
            .putString(MESSAGE_SEND_JOB_ID_KEY, messageSendJobID)
            .build()
    }

    override fun getFactoryKey(): String {
        return KEY
    }

    class Factory: Job.Factory<AttachmentUploadJob> {

        override fun create(data: Data): AttachmentUploadJob? {
            val serializedMessage = data.getByteArray(MESSAGE_KEY)
            val kryo = Kryo()
            kryo.isRegistrationRequired = false
            val input = Input(serializedMessage)
            val message: Message
            try {
                message = kryo.readClassAndObject(input) as Message
            } catch (e: Exception) {
                Log.e("Loki","Couldn't serialize the AttachmentUploadJob", e)
                return null
            }
            input.close()
            return AttachmentUploadJob(
                    data.getLong(ATTACHMENT_ID_KEY),
                    data.getString(THREAD_ID_KEY)!!,
                    message,
                    data.getString(MESSAGE_SEND_JOB_ID_KEY)!!
            )
        }
    }
}