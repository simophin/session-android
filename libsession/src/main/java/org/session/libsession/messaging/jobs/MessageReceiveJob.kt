package org.session.libsession.messaging.jobs

import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.messages.Message
import org.session.libsession.messaging.sending_receiving.MessageReceiver
import org.session.libsession.messaging.sending_receiving.handle
import org.session.libsession.messaging.utilities.Data
import org.session.libsignal.utilities.Log

class MessageReceiveJob(val data: ByteArray, val serverHash: String? = null, val openGroupMessageServerID: Long? = null, val openGroupID: String? = null) : Job {
    override var id: String? = null
    override var failureCount: Int = 0
    override val maxFailureCount: Int = 10

    override val jobKey: Any?
        get() = null

    companion object {
        val TAG = MessageReceiveJob::class.simpleName
        val KEY: String = "MessageReceiveJob"

        // Keys used for database storage
        private val DATA_KEY = "data"
        private val SERVER_HASH_KEY = "serverHash"
        private val OPEN_GROUP_MESSAGE_SERVER_ID_KEY = "openGroupMessageServerID"
        private val OPEN_GROUP_ID_KEY = "open_group_id"
    }

    override suspend fun execute(dispatcherName: String) {
        try {
            val storage = MessagingModuleConfiguration.shared.storage
            val serverPublicKey = openGroupID?.let {
                storage.getOpenGroupPublicKey(it.split(".").dropLast(1).joinToString("."))
            }
            val currentClosedGroups = storage.getAllActiveClosedGroupPublicKeys()
            val (message, proto) = MessageReceiver.parse(this.data, this.openGroupMessageServerID, openGroupPublicKey = serverPublicKey, currentClosedGroups = currentClosedGroups)
            val threadId = Message.getThreadId(message, this.openGroupID, storage, false)
            message.serverHash = serverHash
            MessageReceiver.handle(message, proto, threadId ?: -1, this.openGroupID)
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't receive message.", e)
            if (e is MessageReceiver.Error && !e.isRetryable) {
                Log.e("Loki", "Message receive job permanently failed.", e)
                throw JobPermanentlyFailedException(e)
            } else {
                Log.e("Loki", "Couldn't receive message.", e)
                throw e
            }
        }
    }

    override fun serialize(): Data {
        val builder = Data.Builder().putByteArray(DATA_KEY, data)
        serverHash?.let { builder.putString(SERVER_HASH_KEY, it) }
        openGroupMessageServerID?.let { builder.putLong(OPEN_GROUP_MESSAGE_SERVER_ID_KEY, it) }
        openGroupID?.let { builder.putString(OPEN_GROUP_ID_KEY, it) }
        return builder.build();
    }

    override fun getFactoryKey(): String {
        return KEY
    }

    class Factory: Job.Factory<MessageReceiveJob> {

        override fun create(data: Data): MessageReceiveJob {
            val dataArray = data.getByteArray(DATA_KEY)
            val serverHash = data.getStringOrDefault(SERVER_HASH_KEY, null)
            val openGroupMessageServerID = data.getLongOrDefault(OPEN_GROUP_MESSAGE_SERVER_ID_KEY, -1).let { if (it == -1L) null else it }
            val openGroupID = data.getStringOrDefault(OPEN_GROUP_ID_KEY, null)
            return MessageReceiveJob(
                dataArray,
                serverHash,
                openGroupMessageServerID,
                openGroupID
            )
        }
    }
}