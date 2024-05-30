package org.session.libsession.messaging.sending_receiving

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.scan
import network.loki.messenger.libsession_util.util.ExpiryMode
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.jobs.JobQueue
import org.session.libsession.messaging.jobs.MessageSendJob
import org.session.libsession.messaging.jobs.NotifyPNServerJob
import org.session.libsession.messaging.messages.Destination
import org.session.libsession.messaging.messages.Message
import org.session.libsession.messaging.messages.applyExpiryMode
import org.session.libsession.messaging.messages.control.CallMessage
import org.session.libsession.messaging.messages.control.ClosedGroupControlMessage
import org.session.libsession.messaging.messages.control.ConfigurationMessage
import org.session.libsession.messaging.messages.control.ExpirationTimerUpdate
import org.session.libsession.messaging.messages.control.MessageRequestResponse
import org.session.libsession.messaging.messages.control.SharedConfigurationMessage
import org.session.libsession.messaging.messages.control.UnsendRequest
import org.session.libsession.messaging.messages.visible.LinkPreview
import org.session.libsession.messaging.messages.visible.Quote
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.messaging.open_groups.OpenGroupApi
import org.session.libsession.messaging.open_groups.OpenGroupApi.Capability
import org.session.libsession.messaging.open_groups.OpenGroupMessage
import org.session.libsession.messaging.utilities.MessageWrapper
import org.session.libsession.messaging.utilities.SessionId
import org.session.libsession.messaging.utilities.SodiumUtilities
import org.session.libsession.snode.RawResponse
import org.session.libsession.snode.SnodeAPI
import org.session.libsession.snode.SnodeAPI.nowWithOffset
import org.session.libsession.snode.SnodeMessage
import org.session.libsession.snode.SnodeModule
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Device
import org.session.libsession.utilities.GroupUtil
import org.session.libsession.utilities.SSKEnvironment
import org.session.libsignal.crypto.PushTransportDetails
import org.session.libsignal.protos.SignalServiceProtos
import org.session.libsignal.utilities.Base64
import org.session.libsignal.utilities.IdPrefix
import org.session.libsignal.utilities.Namespace
import org.session.libsignal.utilities.defaultRequiresAuth
import org.session.libsignal.utilities.hasNamespaces
import org.session.libsignal.utilities.hexEncodedPublicKey
import java.util.concurrent.TimeUnit
import org.session.libsession.messaging.sending_receiving.attachments.Attachment as SignalAttachment
import org.session.libsession.messaging.sending_receiving.link_preview.LinkPreview as SignalLinkPreview
import org.session.libsession.messaging.sending_receiving.quotes.QuoteModel as SignalQuote

object MessageSender {

    // Error
    sealed class Error(val description: String) : Exception(description) {
        object InvalidMessage : Error("Invalid message.")
        object ProtoConversionFailed : Error("Couldn't convert message to proto.")
        object NoUserED25519KeyPair : Error("Couldn't find user ED25519 key pair.")
        object SigningFailed : Error("Couldn't sign message.")
        object EncryptionFailed : Error("Couldn't encrypt message.")

        // Closed groups
        object NoThread : Error("Couldn't find a thread associated with the given group public key.")
        object NoKeyPair: Error("Couldn't find a private key associated with the given group public key.")
        object InvalidClosedGroupUpdate : Error("Invalid group update.")

        internal val isRetryable: Boolean = when (this) {
            is InvalidMessage, ProtoConversionFailed, InvalidClosedGroupUpdate -> false
            else -> true
        }
    }

    // Convenience
    suspend fun send(message: Message, destination: Destination, isSyncMessage: Boolean) {
        if (message is VisibleMessage) MessagingModuleConfiguration.shared.lastSentTimestampCache.submitTimestamp(message.threadID!!, message.sentTimestamp!!)
        return if (destination is Destination.LegacyOpenGroup || destination is Destination.OpenGroup || destination is Destination.OpenGroupInbox) {
            sendToOpenGroupDestination(destination, message)
        } else {
            sendToSnodeDestination(destination, message, isSyncMessage)
        }
    }

    // One-on-One Chats & Closed Groups
    @Throws(Exception::class)
    fun buildWrappedMessageToSnode(destination: Destination, message: Message, isSyncMessage: Boolean): SnodeMessage {
        val storage = MessagingModuleConfiguration.shared.storage
        val userPublicKey = storage.getUserPublicKey()
        // Set the timestamp, sender and recipient
        val messageSendTime = nowWithOffset
        if (message.sentTimestamp == null) {
            message.sentTimestamp =
                messageSendTime // Visible messages will already have their sent timestamp set
        }

        message.sender = userPublicKey
        // SHARED CONFIG
        when (destination) {
            is Destination.Contact -> message.recipient = destination.publicKey
            is Destination.ClosedGroup -> message.recipient = destination.groupPublicKey
            else -> throw IllegalStateException("Destination should not be an open group.")
        }

        val isSelfSend = (message.recipient == userPublicKey)
        // Validate the message
        if (!message.isValid()) {
            throw Error.InvalidMessage
        }
        // Stop here if this is a self-send, unless it's:
        // • a configuration message
        // • a sync message
        // • a closed group control message of type `new`
        var isNewClosedGroupControlMessage = false
        if (message is ClosedGroupControlMessage && message.kind is ClosedGroupControlMessage.Kind.New) isNewClosedGroupControlMessage =
            true
        if (isSelfSend
            && message !is ConfigurationMessage
            && !isSyncMessage
            && !isNewClosedGroupControlMessage
            && message !is UnsendRequest
            && message !is SharedConfigurationMessage
        ) {
            throw Error.InvalidMessage
        }
        // Attach the user's profile if needed
        if (message is VisibleMessage) {
            message.profile = storage.getUserProfile()
        }
        if (message is MessageRequestResponse) {
            message.profile = storage.getUserProfile()
        }
        // Convert it to protobuf
        val proto = message.toProto() ?: throw Error.ProtoConversionFailed
        // Serialize the protobuf
        val plaintext = PushTransportDetails.getPaddedMessageBody(proto.toByteArray())
        // Encrypt the serialized protobuf
        val ciphertext = when (destination) {
            is Destination.Contact -> MessageEncrypter.encrypt(plaintext, destination.publicKey)
            is Destination.ClosedGroup -> {
                val encryptionKeyPair =
                    MessagingModuleConfiguration.shared.storage.getLatestClosedGroupEncryptionKeyPair(
                        destination.groupPublicKey
                    )!!
                MessageEncrypter.encrypt(plaintext, encryptionKeyPair.hexEncodedPublicKey)
            }
            else -> throw IllegalStateException("Destination should not be open group.")
        }
        // Wrap the result
        val kind: SignalServiceProtos.Envelope.Type
        val senderPublicKey: String
        when (destination) {
            is Destination.Contact -> {
                kind = SignalServiceProtos.Envelope.Type.SESSION_MESSAGE
                senderPublicKey = ""
            }
            is Destination.ClosedGroup -> {
                kind = SignalServiceProtos.Envelope.Type.CLOSED_GROUP_MESSAGE
                senderPublicKey = destination.groupPublicKey
            }
            else -> throw IllegalStateException("Destination should not be open group.")
        }
        val wrappedMessage = MessageWrapper.wrap(kind, message.sentTimestamp!!, senderPublicKey, ciphertext)
        val base64EncodedData = Base64.encodeBytes(wrappedMessage)
        // Send the result
        return SnodeMessage(
            message.recipient!!,
            base64EncodedData,
            ttl = getSpecifiedTtl(message, isSyncMessage) ?: message.ttl,
            messageSendTime
        )
    }

    // One-on-One Chats & Closed Groups
    private suspend fun sendToSnodeDestination(destination: Destination, message: Message, isSyncMessage: Boolean = false) {
        val storage = MessagingModuleConfiguration.shared.storage
        val userPublicKey = storage.getUserPublicKey()

        // recipient will be set later, so initialize it as a function here
        val isSelfSend = { message.recipient == userPublicKey }

        // Set the failure handler (need it here already for precondition failure handling)
        fun handleFailure(error: Exception) {
            handleFailedMessageSend(message, error, isSyncMessage)
            if (destination is Destination.Contact && message is VisibleMessage && !isSelfSend()) {
                SnodeModule.shared.broadcaster.broadcast("messageFailed", message.sentTimestamp!!)
            }
            throw error
        }
        try {
            val snodeMessage = buildWrappedMessageToSnode(destination, message, isSyncMessage)
            // TODO: this might change in future for config messages
            val forkInfo = SnodeAPI.forkInfo
            val namespaces: List<Int> = when {
                destination is Destination.ClosedGroup
                        && forkInfo.defaultRequiresAuth() -> listOf(Namespace.UNAUTHENTICATED_CLOSED_GROUP)

                destination is Destination.ClosedGroup
                        && forkInfo.hasNamespaces() -> listOf(
                    Namespace.UNAUTHENTICATED_CLOSED_GROUP,
                    Namespace.DEFAULT
                )

                else -> listOf(Namespace.DEFAULT)
            }

            // Send message to all namespaces async, wait for the first successful response,
            // or fail at the end if all requests failed.
            @Suppress("OPT_IN_USAGE")
            val response = namespaces.asFlow()
                .flatMapMerge { namespace ->
                    flow {
                        emit(runCatching { SnodeAPI.sendMessage(snodeMessage, requiresAuth = false, namespace = namespace) })
                    }
                }
                .scan(emptyList<Result<RawResponse>>()) { acc, value -> acc + value }
                .mapNotNull { results ->
                    val lastResult = results.lastOrNull()

                    // If the last result is successful, returns it
                    // If all requests failed, also returns the last result
                    // Otherwise, returns null to indicate we need to continue mapping
                    if (lastResult != null &&
                        (lastResult.isSuccess || results.size == namespaces.size)) {
                        lastResult
                    } else {
                        null
                    }
                }
                .first()
                .getOrThrow()

            val hash = response["hash"] as? String
            message.serverHash = hash
            handleSuccessfulMessageSend(message, destination, isSyncMessage)

            val shouldNotify: Boolean = when (message) {
                is VisibleMessage, is UnsendRequest -> !isSyncMessage
                is CallMessage -> {
                    // Note: Other 'CallMessage' types are too big to send as push notifications
                    // so only send the 'preOffer' message as a notification
                    when (message.type) {
                        SignalServiceProtos.CallMessage.Type.PRE_OFFER -> true
                        else -> false
                    }
                }
                else -> false
            }

            /*
            if (message is ClosedGroupControlMessage && message.kind is ClosedGroupControlMessage.Kind.New) {
                shouldNotify = true
            }
             */
            if (shouldNotify) {
                val notifyPNServerJob = NotifyPNServerJob(snodeMessage)
                JobQueue.shared.add(notifyPNServerJob)
            }

        } catch (exception: Exception) {
            handleFailure(exception)
        }
    }

    private fun getSpecifiedTtl(
        message: Message,
        isSyncMessage: Boolean
    ): Long? = message.takeUnless { it is ClosedGroupControlMessage }?.run {
        threadID ?: (if (isSyncMessage && this is VisibleMessage) syncTarget else recipient)
            ?.let(Address.Companion::fromSerialized)
            ?.let(MessagingModuleConfiguration.shared.storage::getThreadId)
    }?.let(MessagingModuleConfiguration.shared.storage::getExpirationConfiguration)
    ?.takeIf { it.isEnabled }
    ?.expiryMode
    ?.takeIf { it is ExpiryMode.AfterSend || isSyncMessage }
    ?.expiryMillis

    // Open Groups
    private suspend fun sendToOpenGroupDestination(destination: Destination, message: Message) {
        val storage = MessagingModuleConfiguration.shared.storage
        val configFactory = MessagingModuleConfiguration.shared.configFactory
        if (message.sentTimestamp == null) {
            message.sentTimestamp = nowWithOffset
        }
        // Attach the blocks message requests info
        configFactory.user?.let { user ->
            if (message is VisibleMessage) {
                message.blocksMessageRequests = !user.getCommunityMessageRequests()
            }
        }
        val userEdKeyPair = MessagingModuleConfiguration.shared.getUserED25519KeyPair()!!
        var serverCapabilities = listOf<String>()
        var blindedPublicKey: ByteArray? = null
        when(destination) {
            is Destination.OpenGroup -> {
                serverCapabilities = storage.getServerCapabilities(destination.server)
                storage.getOpenGroup(destination.roomToken, destination.server)?.let {
                    blindedPublicKey = SodiumUtilities.blindedKeyPair(it.publicKey, userEdKeyPair)?.publicKey?.asBytes
                }
            }
            is Destination.OpenGroupInbox -> {
                serverCapabilities = storage.getServerCapabilities(destination.server)
                blindedPublicKey = SodiumUtilities.blindedKeyPair(destination.serverPublicKey, userEdKeyPair)?.publicKey?.asBytes
            }
            is Destination.LegacyOpenGroup -> {
                serverCapabilities = storage.getServerCapabilities(destination.server)
                storage.getOpenGroup(destination.roomToken, destination.server)?.let {
                    blindedPublicKey = SodiumUtilities.blindedKeyPair(it.publicKey, userEdKeyPair)?.publicKey?.asBytes
                }
            }
            else -> {}
        }
        val messageSender = if (serverCapabilities.contains(Capability.BLIND.name.lowercase()) && blindedPublicKey != null) {
            SessionId(IdPrefix.BLINDED, blindedPublicKey!!).hexString
        } else {
            SessionId(IdPrefix.UN_BLINDED, userEdKeyPair.publicKey.asBytes).hexString
        }
        message.sender = messageSender
        // Set the failure handler (need it here already for precondition failure handling)
        fun handleFailure(error: Exception) {
            handleFailedMessageSend(message, error)
            throw error
        }
        try {
            // Attach the user's profile if needed
            if (message is VisibleMessage) {
                message.profile = storage.getUserProfile()
            }
            when (destination) {
                is Destination.OpenGroup -> {
                    val whisperMods = if (destination.whisperTo.isNullOrEmpty() && destination.whisperMods) "mods" else null
                    message.recipient = "${destination.server}.${destination.roomToken}.${destination.whisperTo}.$whisperMods"
                    // Validate the message
                    if (message !is VisibleMessage || !message.isValid()) {
                        throw Error.InvalidMessage
                    }
                    val messageBody = message.toProto()?.toByteArray()!!
                    val plaintext = PushTransportDetails.getPaddedMessageBody(messageBody)
                    val openGroupMessage = OpenGroupMessage(
                        sender = message.sender,
                        sentTimestamp = message.sentTimestamp!!,
                        base64EncodedData = Base64.encodeBytes(plaintext),
                    )

                    val result = OpenGroupApi.sendMessage(openGroupMessage, destination.roomToken, destination.server, destination.whisperTo, destination.whisperMods, destination.fileIds)
                    message.openGroupServerMessageID = result.serverID
                    handleSuccessfulMessageSend(message, destination, openGroupSentTimestamp = result.sentTimestamp)
                }
                is Destination.OpenGroupInbox -> {
                    message.recipient = destination.blindedPublicKey
                    // Validate the message
                    if (message !is VisibleMessage || !message.isValid()) {
                        throw Error.InvalidMessage
                    }
                    val messageBody = message.toProto()?.toByteArray()!!
                    val plaintext = PushTransportDetails.getPaddedMessageBody(messageBody)
                    val ciphertext = MessageEncrypter.encryptBlinded(
                        plaintext,
                        destination.blindedPublicKey,
                        destination.serverPublicKey
                    )
                    val base64EncodedData = Base64.encodeBytes(ciphertext)
                    val result = OpenGroupApi.sendDirectMessage(base64EncodedData, destination.blindedPublicKey, destination.server)
                    message.openGroupServerMessageID = result.id
                    handleSuccessfulMessageSend(message, destination, openGroupSentTimestamp = TimeUnit.SECONDS.toMillis(result.postedAt))
                }
                else -> throw IllegalStateException("Invalid destination.")
            }
        } catch (exception: Exception) {
            handleFailure(exception)
        }
    }

    // Result Handling
    private suspend fun handleSuccessfulMessageSend(message: Message, destination: Destination, isSyncMessage: Boolean = false, openGroupSentTimestamp: Long = -1) {
        if (message is VisibleMessage) MessagingModuleConfiguration.shared.lastSentTimestampCache.submitTimestamp(message.threadID!!, openGroupSentTimestamp)
        val storage = MessagingModuleConfiguration.shared.storage
        val userPublicKey = storage.getUserPublicKey()!!
        val timestamp = message.sentTimestamp!!
        // Ignore future self-sends
        storage.addReceivedMessageTimestamp(timestamp)
        storage.getMessageIdInDatabase(timestamp, userPublicKey)?.let { (messageID, mms) ->
            if (openGroupSentTimestamp != -1L && message is VisibleMessage) {
                storage.addReceivedMessageTimestamp(openGroupSentTimestamp)
                storage.updateSentTimestamp(messageID, message.isMediaMessage(), openGroupSentTimestamp, message.threadID!!)
                message.sentTimestamp = openGroupSentTimestamp
            }

            // When the sync message is successfully sent, the hash value of this TSOutgoingMessage
            // will be replaced by the hash value of the sync message. Since the hash value of the
            // real message has no use when we delete a message. It is OK to let it be.
            message.serverHash?.let {
                storage.setMessageServerHash(messageID, mms, it)
            }

            // in case any errors from previous sends
            storage.clearErrorMessage(messageID)

            // Track the open group server message ID
            val messageIsAddressedToCommunity = message.openGroupServerMessageID != null && (destination is Destination.LegacyOpenGroup || destination is Destination.OpenGroup)
            if (messageIsAddressedToCommunity) {
                val server: String
                val room: String
                when (destination) {
                    is Destination.LegacyOpenGroup -> {
                        server = destination.server
                        room = destination.roomToken
                    }
                    is Destination.OpenGroup -> {
                        server = destination.server
                        room = destination.roomToken
                    }
                    else -> {
                        throw Exception("Destination was a different destination than we were expecting")
                    }
                }
                val encoded = GroupUtil.getEncodedOpenGroupID("$server.$room".toByteArray())
                val threadID = storage.getThreadId(Address.fromSerialized(encoded))
                if (threadID != null && threadID >= 0) {
                    storage.setOpenGroupServerMessageID(messageID, message.openGroupServerMessageID!!, threadID, !(message as VisibleMessage).isMediaMessage())
                }
            }

            // Mark the message as sent.
            // Note: When sending a message to a community the server modifies the message timestamp
            // so when we go to look up the message in the local database by timestamp it fails and
            // we're left with the message delivery status as "Sending" forever! As such, we use a
            // pair of modified "markAsSentToCommunity" and "markUnidentifiedInCommunity" methods
            // to retrieve the local message by thread & message ID rather than timestamp when
            // handling community messages only so we can tick the delivery status over to 'Sent'.
            // Fixed in: https://optf.atlassian.net/browse/SES-1567
            if (messageIsAddressedToCommunity)
            {
                storage.markAsSentToCommunity(message.threadID!!, message.id!!)
                storage.markUnidentifiedInCommunity(message.threadID!!, message.id!!)
            }
            else
            {
                storage.markAsSent(timestamp, userPublicKey)
                storage.markUnidentified(timestamp, userPublicKey)
            }

            // Start the disappearing messages timer if needed
            SSKEnvironment.shared.messageExpirationManager.maybeStartExpiration(message, startDisappearAfterRead = true)
        } ?: run {
            storage.updateReactionIfNeeded(message, message.sender?:userPublicKey, openGroupSentTimestamp)
        }
        // Sync the message if:
        // • it's a visible message
        // • the destination was a contact
        // • we didn't sync it already
        if (destination is Destination.Contact && !isSyncMessage) {
            if (message is VisibleMessage) message.syncTarget = destination.publicKey
            if (message is ExpirationTimerUpdate) message.syncTarget = destination.publicKey

            storage.markAsSyncing(timestamp, userPublicKey)
            sendToSnodeDestination(Destination.Contact(userPublicKey), message, true)
        }
    }

    fun handleFailedMessageSend(message: Message, error: Exception, isSyncMessage: Boolean = false) {
        val storage = MessagingModuleConfiguration.shared.storage
        val userPublicKey = storage.getUserPublicKey()!!

        val timestamp = message.sentTimestamp!!
        val author = message.sender ?: userPublicKey

        if (isSyncMessage) storage.markAsSyncFailed(timestamp, author, error)
        else storage.markAsSentFailed(timestamp, author, error)
    }

    // Convenience
    @JvmStatic
    fun send(message: VisibleMessage, address: Address, attachments: List<SignalAttachment>, quote: SignalQuote?, linkPreview: SignalLinkPreview?) {
        val messageDataProvider = MessagingModuleConfiguration.shared.messageDataProvider
        val attachmentIDs = messageDataProvider.getAttachmentIDsFor(message.id!!)
        message.attachmentIDs.addAll(attachmentIDs)
        message.quote = Quote.from(quote)
        message.linkPreview = LinkPreview.from(linkPreview)
        message.linkPreview?.let { linkPreview ->
            if (linkPreview.attachmentID == null) {
                messageDataProvider.getLinkPreviewAttachmentIDFor(message.id!!)?.let { attachmentID ->
                    linkPreview.attachmentID = attachmentID
                    message.attachmentIDs.remove(attachmentID)
                }
            }
        }
        send(message, address)
    }

    @JvmStatic
    fun send(message: Message, address: Address) {
        val threadID = MessagingModuleConfiguration.shared.storage.getThreadId(address)
        threadID?.let(message::applyExpiryMode)
        message.threadID = threadID
        val destination = Destination.from(address)
        val job = MessageSendJob(message, destination)
        JobQueue.shared.add(job)
    }

    suspend fun sendNonDurably(message: VisibleMessage, attachments: List<SignalAttachment>, address: Address, isSyncMessage: Boolean) {
        val attachmentIDs = MessagingModuleConfiguration.shared.messageDataProvider.getAttachmentIDsFor(message.id!!)
        message.attachmentIDs.addAll(attachmentIDs)
        return sendNonDurably(message, address, isSyncMessage)
    }

    suspend fun sendNonDurably(message: Message, address: Address, isSyncMessage: Boolean) {
        val threadID = MessagingModuleConfiguration.shared.storage.getThreadId(address)
        message.threadID = threadID
        val destination = Destination.from(address)
        return send(message, destination, isSyncMessage)
    }

    // Closed groups
    suspend fun createClosedGroup(device: Device, name: String, members: Collection<String>): String {
        return create(device, name, members)
    }

    fun explicitNameChange(groupPublicKey: String, newName: String) {
        return setName(groupPublicKey, newName)
    }

    fun explicitAddMembers(groupPublicKey: String, membersToAdd: List<String>) {
        return addMembers(groupPublicKey, membersToAdd)
    }

    suspend fun explicitRemoveMembers(groupPublicKey: String, membersToRemove: List<String>) {
        removeMembers(groupPublicKey, membersToRemove)
    }

    @JvmStatic
    suspend fun explicitLeave(groupPublicKey: String, notifyUser: Boolean) {
        return leave(groupPublicKey, notifyUser)
    }

}