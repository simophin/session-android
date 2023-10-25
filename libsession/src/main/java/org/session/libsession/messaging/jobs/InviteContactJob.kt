package org.session.libsession.messaging.jobs

import com.google.protobuf.ByteString
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
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.SessionId

class InviteContactJob(val groupSessionId: String, val memberSessionId: String): Job {

    sealed class InviteError(message: String): Exception(message) {
        object NO_GROUP_KEYS: InviteError("No group keys config for this group")
    }

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
                ?: return delegate.handleJobFailedPermanently(this, dispatcherName, NullPointerException("No admin key"))
        val subAccount = configs.getGroupKeysConfig(SessionId.from(groupSessionId))?.use { keys ->
            keys.makeSubAccount(SessionId.from(memberSessionId))
        } ?: return delegate.handleJobFailedPermanently(this, dispatcherName, InviteError.NO_GROUP_KEYS)
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
            MessageSender.send(update, Destination.Contact(memberSessionId), false).get()
            Log.d("InviteContactJob", "Sent invite message successfully")
            delegate.handleJobSucceeded(this, dispatcherName)
        } catch (e: Exception) {
            Log.e("InviteContactJob", e)
            delegate.handleJobFailed(this, dispatcherName, e)
        }

    }

    override fun serialize(): Data =
        Data.Builder()
            .putString(GROUP, groupSessionId)
            .putString(MEMBER, memberSessionId)
            .build()

    override fun getFactoryKey(): String = KEY

}