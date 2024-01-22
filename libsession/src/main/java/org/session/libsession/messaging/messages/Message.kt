package org.session.libsession.messaging.messages

import com.google.protobuf.ByteString
import network.loki.messenger.libsession_util.util.ExpiryMode
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.messages.control.ExpirationTimerUpdate
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsignal.protos.SignalServiceProtos
import org.session.libsignal.protos.SignalServiceProtos.Content.ExpirationType

abstract class Message {
    var id: Long? = null
    var threadID: Long? = null
    var sentTimestamp: Long? = null
    var receivedTimestamp: Long? = null
    var recipient: String? = null
    var sender: String? = null
    var isSenderSelf: Boolean = false
    var groupPublicKey: String? = null
    var openGroupServerMessageID: Long? = null
    var serverHash: String? = null
    var specifiedTtl: Long? = null

    var expiryMode: ExpiryMode = ExpiryMode.NONE

    open val defaultTtl: Long = 14 * 24 * 60 * 60 * 1000
    open val ttl: Long get() = specifiedTtl ?: defaultTtl
    open val isSelfSendValid: Boolean = false

    companion object {
        fun getThreadId(message: Message, openGroupID: String?, storage: StorageProtocol, shouldCreateThread: Boolean): Long? {
            val senderOrSync = when (message) {
                is VisibleMessage -> message.syncTarget ?: message.sender!!
                is ExpirationTimerUpdate -> message.syncTarget ?: message.sender!!
                else -> message.sender!!
            }
            return storage.getThreadIdFor(senderOrSync, message.groupPublicKey, openGroupID, createThread = shouldCreateThread)
        }
    }

    open fun isValid(): Boolean {
        val sentTimestamp = sentTimestamp
        if (sentTimestamp != null && sentTimestamp <= 0) { return false }
        val receivedTimestamp = receivedTimestamp
        if (receivedTimestamp != null && receivedTimestamp <= 0) { return false }
        return sender != null && recipient != null
    }

    abstract fun toProto(): SignalServiceProtos.Content?

    abstract fun shouldDiscardIfBlocked(): Boolean

    fun SignalServiceProtos.Content.Builder.setExpirationConfigurationIfNeeded(
        threadId: Long?,
        coerceDisappearAfterSendToRead: Boolean = false
    ): SignalServiceProtos.Content.Builder {
        val config = threadId?.let(MessagingModuleConfiguration.shared.storage::getExpirationConfiguration)
            ?: run {
                expirationTimer = 0
                return this
            }
        expirationTimer = config.expiryMode.expirySeconds.toInt()
        lastDisappearingMessageChangeTimestamp = config.updatedTimestampMs
        expirationType = when (config.expiryMode) {
            is ExpiryMode.AfterSend -> if (coerceDisappearAfterSendToRead) ExpirationType.DELETE_AFTER_READ else ExpirationType.DELETE_AFTER_SEND
            is ExpiryMode.AfterRead -> ExpirationType.DELETE_AFTER_READ
            else -> ExpirationType.UNKNOWN
        }
        return this
    }
}

inline fun <reified M: Message> M.copyExpiration(proto: SignalServiceProtos.Content): M {
    val duration: Int = (if (proto.hasExpirationTimer()) proto.expirationTimer else if (proto.hasDataMessage()) proto.dataMessage?.expireTimer else null) ?: return this

    expiryMode = when (proto.expirationType.takeIf { duration > 0 }) {
        ExpirationType.DELETE_AFTER_SEND -> ExpiryMode.AfterSend(duration.toLong())
        ExpirationType.DELETE_AFTER_READ -> ExpiryMode.AfterRead(duration.toLong())
        else -> ExpiryMode.NONE
    }

    return this
}
