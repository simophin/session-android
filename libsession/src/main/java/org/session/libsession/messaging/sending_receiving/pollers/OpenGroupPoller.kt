package org.session.libsession.messaging.sending_receiving.pollers

import com.google.protobuf.ByteString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import org.session.libsession.messaging.BlindedIdMapping
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.jobs.BatchMessageReceiveJob
import org.session.libsession.messaging.jobs.GroupAvatarDownloadJob
import org.session.libsession.messaging.jobs.JobQueue
import org.session.libsession.messaging.jobs.MessageReceiveJob
import org.session.libsession.messaging.jobs.MessageReceiveParameters
import org.session.libsession.messaging.jobs.OpenGroupDeleteJob
import org.session.libsession.messaging.jobs.TrimThreadJob
import org.session.libsession.messaging.messages.Message
import org.session.libsession.messaging.messages.control.ExpirationTimerUpdate
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.messaging.open_groups.Endpoint
import org.session.libsession.messaging.open_groups.GroupMember
import org.session.libsession.messaging.open_groups.GroupMemberRole
import org.session.libsession.messaging.open_groups.OpenGroup
import org.session.libsession.messaging.open_groups.OpenGroupApi
import org.session.libsession.messaging.open_groups.OpenGroupMessage
import org.session.libsession.messaging.sending_receiving.MessageReceiver
import org.session.libsession.messaging.sending_receiving.handle
import org.session.libsession.messaging.sending_receiving.handleOpenGroupReactions
import org.session.libsession.snode.OnionRequestAPI
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.GroupUtil
import org.session.libsignal.protos.SignalServiceProtos
import org.session.libsignal.utilities.Base64
import org.session.libsignal.utilities.Log
import java.util.concurrent.TimeUnit

class OpenGroupPoller(private val server: String) {
    var isCaughtUp = false
    var secondToLastJob: MessageReceiveJob? = null

    private var pollingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO) + SupervisorJob()

    companion object {
        private const val pollInterval: Long = 4000L
        const val maxInactivityPeriod = 14 * 24 * 60 * 60 * 1000

        public fun handleRoomPollInfo(
            server: String,
            roomToken: String,
            pollInfo: OpenGroupApi.RoomPollInfo,
            createGroupIfMissingWithPublicKey: String? = null
        ) {
            val storage = MessagingModuleConfiguration.shared.storage
            val groupId = "$server.$roomToken"
            val dbGroupId = GroupUtil.getEncodedOpenGroupID(groupId.toByteArray())
            val existingOpenGroup = storage.getOpenGroup(roomToken, server)

            // If we don't have an existing group and don't have a 'createGroupIfMissingWithPublicKey'
            // value then don't process the poll info
            val publicKey = existingOpenGroup?.publicKey ?: createGroupIfMissingWithPublicKey
            val name = pollInfo.details?.name ?: existingOpenGroup?.name
            val infoUpdates = pollInfo.details?.infoUpdates ?: existingOpenGroup?.infoUpdates

            if (publicKey == null) return

            val openGroup = OpenGroup(
                server = server,
                room = pollInfo.token,
                name = name ?: "",
                publicKey = publicKey,
                imageId = (pollInfo.details?.imageId ?: existingOpenGroup?.imageId),
                canWrite = pollInfo.write,
                infoUpdates = infoUpdates ?: 0
            )
            // - Open Group changes
            storage.updateOpenGroup(openGroup)

            // - User Count
            storage.setUserCount(roomToken, server, pollInfo.activeUsers)

            // - Moderators
            pollInfo.details?.moderators?.let { moderatorList ->
                storage.setGroupMemberRoles(moderatorList.map {
                    GroupMember(groupId, it, GroupMemberRole.MODERATOR)
                })
            }
            pollInfo.details?.hiddenModerators?.let { moderatorList ->
                storage.setGroupMemberRoles(moderatorList.map {
                    GroupMember(groupId, it, GroupMemberRole.HIDDEN_MODERATOR)
                })
            }
            // - Admins
            pollInfo.details?.admins?.let { moderatorList ->
                storage.setGroupMemberRoles(moderatorList.map {
                    GroupMember(groupId, it, GroupMemberRole.ADMIN)
                })
            }
            pollInfo.details?.hiddenAdmins?.let { moderatorList ->
                storage.setGroupMemberRoles(moderatorList.map {
                    GroupMember(groupId, it, GroupMemberRole.HIDDEN_ADMIN)
                })
            }

            // Update the group avatar
            if (
                (
                    pollInfo.details != null &&
                        pollInfo.details.imageId != null && (
                        pollInfo.details.imageId != existingOpenGroup?.imageId ||
                            !storage.hasDownloadedProfilePicture(dbGroupId)
                        ) &&
                        storage.getGroupAvatarDownloadJob(openGroup.server, openGroup.room, pollInfo.details.imageId) == null
                    ) || (
                    pollInfo.details == null &&
                        existingOpenGroup?.imageId != null &&
                        !storage.hasDownloadedProfilePicture(dbGroupId) &&
                        storage.getGroupAvatarDownloadJob(openGroup.server, openGroup.room, existingOpenGroup.imageId) == null
                    )
            ) {
                JobQueue.shared.add(GroupAvatarDownloadJob(server, roomToken, openGroup.imageId))
            }
            else if (
                pollInfo.details != null &&
                pollInfo.details.imageId == null &&
                existingOpenGroup?.imageId != null
            ) {
                storage.removeProfilePicture(dbGroupId)
            }
        }
    }

    @Synchronized
    fun startIfNeeded() {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch {
            while (true) {
                try {
                    pollOnce()
                } catch (e: Exception) {
                    Log.e("Loki", "Failed to poll open group: $server.", e)
                }
                delay(pollInterval)
            }
        }
    }

    @Synchronized
    fun stop() {
        val job = pollingJob
        pollingJob = null
        job?.cancel()
    }

    private suspend fun pollOnce(isPostCapabilitiesRetry: Boolean = false) {
        val storage = MessagingModuleConfiguration.shared.storage
        try {
            val rooms = storage.getAllOpenGroups().values.filter { it.server == server }.map { it.room }
            val responses = OpenGroupApi.poll(rooms, server)

            responses.filterNot { it.body == null }.forEach { response ->
                when (response.endpoint) {
                    is Endpoint.Capabilities -> {
                        handleCapabilities(server, response.body as OpenGroupApi.Capabilities)
                    }
                    is Endpoint.RoomPollInfo -> {
                        handleRoomPollInfo(server, response.endpoint.roomToken, response.body as OpenGroupApi.RoomPollInfo)
                    }
                    is Endpoint.RoomMessagesRecent -> {
                        handleMessages(server, response.endpoint.roomToken, response.body as List<OpenGroupApi.Message>)
                    }
                    is Endpoint.RoomMessagesSince  -> {
                        handleMessages(server, response.endpoint.roomToken, response.body as List<OpenGroupApi.Message>)
                    }
                    is Endpoint.Inbox, is Endpoint.InboxSince -> {
                        handleDirectMessages(server, false, response.body as List<OpenGroupApi.DirectMessage>)
                    }
                    is Endpoint.Outbox, is Endpoint.OutboxSince -> {
                        handleDirectMessages(server, true, response.body as List<OpenGroupApi.DirectMessage>)
                    }
                    else -> { /* We don't care about the result of any other calls (won't be polled for) */}
                }
                if (secondToLastJob == null && !isCaughtUp) {
                    isCaughtUp = true
                }
            }
        } catch (e: Exception) {
            updateCapabilitiesIfNeeded(isPostCapabilitiesRetry, e)
            throw e
        }
    }

    private suspend fun updateCapabilitiesIfNeeded(isPostCapabilitiesRetry: Boolean, exception: Exception) {
        if (exception is OnionRequestAPI.HTTPRequestFailedBlindingRequiredException) {
            if (!isPostCapabilitiesRetry) {
                handleCapabilities(server, OpenGroupApi.getCapabilities(server))
            }
        }
    }

    private fun handleCapabilities(server: String, capabilities: OpenGroupApi.Capabilities) {
        val storage = MessagingModuleConfiguration.shared.storage
        storage.setServerCapabilities(server, capabilities.capabilities)
    }
    
    private fun handleMessages(
        server: String,
        roomToken: String,
        messages: List<OpenGroupApi.Message>
    ) {
        val sortedMessages = messages.sortedBy { it.seqno }
        sortedMessages.maxOfOrNull { it.seqno }?.let { seqNo ->
            MessagingModuleConfiguration.shared.storage.setLastMessageServerID(roomToken, server, seqNo)
            OpenGroupApi.pendingReactions.removeAll { !(it.seqNo == null || it.seqNo!! > seqNo) }
        }
        val (deletions, additions) = sortedMessages.partition { it.deleted }
        handleNewMessages(server, roomToken, additions.map {
            OpenGroupMessage(
                serverID = it.id,
                sender = it.sessionId,
                sentTimestamp = (it.posted * 1000).toLong(),
                base64EncodedData = it.data,
                base64EncodedSignature = it.signature,
                reactions = it.reactions
            )
        })
        handleDeletedMessages(server, roomToken, deletions.map { it.id })
    }

    private suspend fun handleDirectMessages(
        server: String,
        fromOutbox: Boolean,
        messages: List<OpenGroupApi.DirectMessage>
    ) {
        if (messages.isEmpty()) return
        val storage = MessagingModuleConfiguration.shared.storage
        val serverPublicKey = storage.getOpenGroupPublicKey(server)!!
        val sortedMessages = messages.sortedBy { it.id }
        val lastMessageId = sortedMessages.last().id
        val mappingCache = mutableMapOf<String, BlindedIdMapping>()
        if (fromOutbox) {
            storage.setLastOutboxMessageId(server, lastMessageId)
        } else {
            storage.setLastInboxMessageId(server, lastMessageId)
        }
        sortedMessages.forEach {
            val encodedMessage = Base64.decode(it.message)
            val envelope = SignalServiceProtos.Envelope.newBuilder()
                .setTimestamp(TimeUnit.SECONDS.toMillis(it.postedAt))
                .setType(SignalServiceProtos.Envelope.Type.SESSION_MESSAGE)
                .setContent(ByteString.copyFrom(encodedMessage))
                .setSource(it.sender)
                .build()
            try {
                val (message, proto) = MessageReceiver.parse(
                    envelope.toByteArray(),
                    null,
                    fromOutbox,
                    if (fromOutbox) it.recipient else it.sender,
                    serverPublicKey,
                    emptySet() // this shouldn't be necessary as we are polling open groups here
                )
                if (fromOutbox) {
                    val mapping = mappingCache[it.recipient] ?: storage.getOrCreateBlindedIdMapping(
                        it.recipient,
                        server,
                        serverPublicKey,
                        true
                    )
                    val syncTarget = mapping.sessionId ?: it.recipient
                    if (message is VisibleMessage) {
                        message.syncTarget = syncTarget
                    } else if (message is ExpirationTimerUpdate) {
                        message.syncTarget = syncTarget
                    }
                    mappingCache[it.recipient] = mapping
                }
                val threadId = Message.getThreadId(message, null, MessagingModuleConfiguration.shared.storage, false)
                MessageReceiver.handle(message, proto, threadId ?: -1, null)
            } catch (e: Exception) {
                Log.e("Loki", "Couldn't handle direct message", e)
            }
        }
    }

    private fun handleNewMessages(server: String, roomToken: String, messages: List<OpenGroupMessage>) {
        val storage = MessagingModuleConfiguration.shared.storage
        val openGroupID = "$server.$roomToken"
        val groupID = GroupUtil.getEncodedOpenGroupID(openGroupID.toByteArray())
        // check thread still exists
        val threadId = storage.getThreadId(Address.fromSerialized(groupID)) ?: -1
        val threadExists = threadId >= 0
        if (!threadExists) { return }
        val envelopes =  mutableListOf<Triple<Long?, SignalServiceProtos.Envelope, Map<String, OpenGroupApi.Reaction>?>>()
        messages.sortedBy { it.serverID!! }.forEach { message ->
            if (!message.base64EncodedData.isNullOrEmpty()) {
                val envelope = SignalServiceProtos.Envelope.newBuilder()
                    .setType(SignalServiceProtos.Envelope.Type.SESSION_MESSAGE)
                    .setSource(message.sender!!)
                    .setSourceDevice(1)
                    .setContent(message.toProto().toByteString())
                    .setTimestamp(message.sentTimestamp)
                    .build()
                envelopes.add(Triple( message.serverID, envelope, message.reactions))
            } else if (!message.reactions.isNullOrEmpty()) {
                message.serverID?.let {
                    MessageReceiver.handleOpenGroupReactions(threadId, it, message.reactions)
                }
            }
        }

        envelopes.chunked(BatchMessageReceiveJob.BATCH_DEFAULT_NUMBER).forEach { list ->
            val parameters = list.map { (serverId, message, reactions) ->
                MessageReceiveParameters(message.toByteArray(), openGroupMessageServerID = serverId, reactions = reactions)
            }
            JobQueue.shared.add(BatchMessageReceiveJob(parameters, openGroupID))
        }

        if (envelopes.isNotEmpty()) {
            JobQueue.shared.add(TrimThreadJob(threadId, openGroupID))
        }
    }

    private fun handleDeletedMessages(server: String, roomToken: String, serverIds: List<Long>) {
        val openGroupId = "$server.$roomToken"
        val storage = MessagingModuleConfiguration.shared.storage
        val groupID = GroupUtil.getEncodedOpenGroupID(openGroupId.toByteArray())
        val threadID = storage.getThreadId(Address.fromSerialized(groupID)) ?: return

        if (serverIds.isNotEmpty()) {
            val deleteJob = OpenGroupDeleteJob(serverIds.toLongArray(), threadID, openGroupId)
            JobQueue.shared.add(deleteJob)
        }
    }
}