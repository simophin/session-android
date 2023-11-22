package org.session.libsession.messaging.jobs

import com.google.protobuf.ByteString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.messages.Destination
import org.session.libsession.messaging.messages.control.GroupUpdated
import org.session.libsession.messaging.sending_receiving.MessageSender
import org.session.libsession.messaging.utilities.Data
import org.session.libsession.messaging.utilities.SodiumUtilities
import org.session.libsession.snode.SnodeAPI
import org.session.libsignal.protos.SignalServiceProtos.DataMessage.GroupUpdateInviteMessage
import org.session.libsignal.protos.SignalServiceProtos.DataMessage.GroupUpdateMessage
import org.session.libsignal.protos.SignalServiceProtos.DataMessage.LokiProfile
import org.session.libsignal.utilities.SessionId
import org.session.libsignal.utilities.prettifiedDescription

class InviteContactsJob(val groupSessionId: String, val memberSessionIds: Array<String>) : Job {

    companion object {
        const val KEY = "InviteContactJob"
        private const val GROUP = "group"
        private const val MEMBER = "member"
    }

    override var delegate: JobDelegate? = null
    override var id: String? = null
    override var failureCount: Int = 0
    override val maxFailureCount: Int = 1

    override suspend fun execute(dispatcherName: String) {
        val delegate = delegate ?: return
        val configs = MessagingModuleConfiguration.shared.configFactory
        val storage = MessagingModuleConfiguration.shared.storage
        val adminKey = configs.userGroups?.getClosedGroup(groupSessionId)?.adminKey
            ?: return delegate.handleJobFailedPermanently(
                this,
                dispatcherName,
                NullPointerException("No admin key")
            )

        withContext(Dispatchers.IO) {
            val sessionId = SessionId.from(groupSessionId)
            val members = configs.getGroupMemberConfig(sessionId)
            val info = configs.getGroupInfoConfig(sessionId)
            val keys = configs.getGroupKeysConfig(sessionId, info, members, free = false)

            if (members == null || info == null || keys == null) {
                return@withContext delegate.handleJobFailedPermanently(
                    this@InviteContactsJob,
                    dispatcherName,
                    NullPointerException("One of the group configs was null")
                )
            }

            val requests = memberSessionIds.map { memberSessionId ->
                async {
                    // Make the request for this member
                    val member = members.get(memberSessionId) ?: return@async run {
                        InviteResult.failure(
                            memberSessionId,
                            NullPointerException("No group member ${memberSessionId.prettifiedDescription()} in members config")
                        )
                    }
                    members.set(member.copy(invitePending = true, inviteFailed = false))
                    configs.saveGroupConfigs(keys, info, members)

                    val subAccount = keys.makeSubAccount(SessionId.from(memberSessionId))

                    val timestamp = SnodeAPI.nowWithOffset
                    val messageToSign = "INVITE$memberSessionId$timestamp"
                    val signature = SodiumUtilities.sign(messageToSign.toByteArray(), adminKey)
                    val userProfile = storage.getUserProfile()
                    val lokiProfile = LokiProfile.newBuilder()
                        .setDisplayName(userProfile.displayName)
                    if (userProfile.profilePictureURL?.isNotEmpty() == true) {
                        lokiProfile.profilePicture = userProfile.profilePictureURL
                    }
                    val groupInvite = GroupUpdateInviteMessage.newBuilder()
                        .setGroupSessionId(groupSessionId)
                        .setMemberAuthData(ByteString.copyFrom(subAccount))
                        .setAdminSignature(ByteString.copyFrom(signature))
                        .setName(userProfile.displayName)
                        .setProfile(lokiProfile.build())
                    if (userProfile.profileKey?.isNotEmpty() == true) {
                        groupInvite.profileKey = ByteString.copyFrom(userProfile.profileKey)
                    }
                    val message = GroupUpdateMessage.newBuilder()
                        .setInviteMessage(groupInvite)
                        .build()
                    val update = GroupUpdated(message).apply {
                        sentTimestamp = timestamp
                    }
                    try {
                        //throw Exception("Just for testing")
                        MessageSender.send(update, Destination.Contact(memberSessionId), false)
                            .get()
                        InviteResult.success(memberSessionId)
                    } catch (e: Exception) {
                        InviteResult.failure(memberSessionId, e)
                    }
                }
            }
            val results = requests.awaitAll()
            results.forEach { result ->
                if (result.success) {
                    // update invite pending / invite failed
                    val toSet = members.get(result.memberSessionId)!!.copy(
                        inviteFailed = false,
                        invitePending = false
                    )
                    members.set(toSet)
                } else {
                    // update invite failed
                    val toSet = members.get(result.memberSessionId)?.copy(
                        inviteFailed = true,
                        invitePending = false
                    ) ?: return@forEach
                    members.set(toSet)
                }
            }
            configs.saveGroupConfigs(keys, info, members)
            keys.free()
            info.free()
            members.free()
        }
    }

    @Suppress("DataClassPrivateConstructor")
    data class InviteResult private constructor(
        val memberSessionId: String,
        val success: Boolean,
        val error: Exception? = null
    ) {
        companion object {
            fun success(memberSessionId: String) = InviteResult(memberSessionId, success = true)
            fun failure(memberSessionId: String, error: Exception) =
                InviteResult(memberSessionId, success = false, error)
        }
    }

    override fun serialize(): Data =
        Data.Builder()
            .putString(GROUP, groupSessionId)
            .putStringArray(MEMBER, memberSessionIds)
            .build()

    override fun getFactoryKey(): String = KEY

}