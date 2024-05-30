@file:Suppress("NAME_SHADOWING")

package org.session.libsession.messaging.sending_receiving

import com.google.protobuf.ByteString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.deferred
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.messages.control.ClosedGroupControlMessage
import org.session.libsession.messaging.sending_receiving.MessageSender.Error
import org.session.libsession.messaging.sending_receiving.notifications.PushRegistryV1
import org.session.libsession.messaging.sending_receiving.pollers.ClosedGroupPollerV2
import org.session.libsession.snode.SnodeAPI
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.fromSerialized
import org.session.libsession.utilities.Device
import org.session.libsession.utilities.GroupUtil
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.crypto.ecc.Curve
import org.session.libsignal.crypto.ecc.ECKeyPair
import org.session.libsignal.messages.SignalServiceGroup
import org.session.libsignal.protos.SignalServiceProtos
import org.session.libsignal.utilities.Hex
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.ThreadUtils
import org.session.libsignal.utilities.guava.Optional
import org.session.libsignal.utilities.hexEncodedPublicKey
import org.session.libsignal.utilities.removingIdPrefixIfNeeded
import java.util.LinkedList
import java.util.concurrent.ConcurrentHashMap

const val groupSizeLimit = 100

val pendingKeyPairs = ConcurrentHashMap<String, Optional<ECKeyPair>>()

suspend fun MessageSender.create(
    device: Device,
    name: String,
    members: Collection<String>
): String {
    return withContext(Dispatchers.IO) {
        // Prepare
        val context = MessagingModuleConfiguration.shared.context
        val storage = MessagingModuleConfiguration.shared.storage
        val userPublicKey = storage.getUserPublicKey()!!
        val membersAsData = members.map { ByteString.copyFrom(Hex.fromStringCondensed(it)) }
        // Generate the group's public key
        val groupPublicKey = Curve.generateKeyPair().hexEncodedPublicKey // Includes the "05" prefix
        // Generate the key pair that'll be used for encryption and decryption
        val encryptionKeyPair = Curve.generateKeyPair()
        // Create the group
        val groupID = GroupUtil.doubleEncodeGroupID(groupPublicKey)
        val admins = setOf( userPublicKey )
        val adminsAsData = admins.map { ByteString.copyFrom(Hex.fromStringCondensed(it)) }
        storage.createGroup(groupID, name, LinkedList(members.map { fromSerialized(it) }),
            null, null, LinkedList(admins.map { Address.fromSerialized(it) }), SnodeAPI.nowWithOffset)
        storage.setProfileSharing(Address.fromSerialized(groupID), true)

        // Send a closed group update message to all members individually
        val closedGroupUpdateKind = ClosedGroupControlMessage.Kind.New(ByteString.copyFrom(Hex.fromStringCondensed(groupPublicKey)), name, encryptionKeyPair, membersAsData, adminsAsData, 0)
        val sentTime = SnodeAPI.nowWithOffset

        // Add the group to the user's set of public keys to poll for
        storage.addClosedGroupPublicKey(groupPublicKey)
        // Store the encryption key pair
        storage.addClosedGroupEncryptionKeyPair(encryptionKeyPair, groupPublicKey, sentTime)
        // Create the thread
        storage.getOrCreateThreadIdFor(Address.fromSerialized(groupID))

        // Notify the user
        val threadID = storage.getOrCreateThreadIdFor(Address.fromSerialized(groupID))
        storage.insertOutgoingInfoMessage(context, groupID, SignalServiceGroup.Type.CREATION, name, members, admins, threadID, sentTime)

        val ourPubKey = storage.getUserPublicKey()
        for (member in members) {
            val closedGroupControlMessage = ClosedGroupControlMessage(closedGroupUpdateKind, groupID)
            closedGroupControlMessage.sentTimestamp = sentTime
            try {
                sendNonDurably(closedGroupControlMessage, Address.fromSerialized(member), member == ourPubKey)
            } catch (e: Exception) {
                // We failed to properly create the group so delete it's associated data (in the past
                // we didn't create this data until the messages successfully sent but this resulted
                // in race conditions due to the `NEW` message sent to our own swarm)
                storage.removeClosedGroupPublicKey(groupPublicKey)
                storage.removeAllClosedGroupEncryptionKeyPairs(groupPublicKey)
                storage.deleteConversation(threadID)
                throw e
            }
        }

        // Add the group to the config now that it was successfully created
        storage.createInitialConfigGroup(groupPublicKey, name, GroupUtil.createConfigMemberMap(members, admins), sentTime, encryptionKeyPair, 0)
        // Notify the PN server
        PushRegistryV1.register(device = device, publicKey = userPublicKey)
        // Start polling
        ClosedGroupPollerV2.shared.startPolling(groupPublicKey)
        // Fulfill the promise
        groupID
    }
}

fun MessageSender.setName(groupPublicKey: String, newName: String) {
    val context = MessagingModuleConfiguration.shared.context
    val storage = MessagingModuleConfiguration.shared.storage
    val groupID = GroupUtil.doubleEncodeGroupID(groupPublicKey)
    val group = storage.getGroup(groupID) ?: run {
        Log.d("Loki", "Can't change name for nonexistent closed group.")
        throw Error.NoThread
    }
    val members = group.members.map { it.serialize() }.toSet()
    val admins = group.admins.map { it.serialize() }
    // Send the update to the group
    val kind = ClosedGroupControlMessage.Kind.NameChange(newName)
    val sentTime = SnodeAPI.nowWithOffset
    val closedGroupControlMessage = ClosedGroupControlMessage(kind, groupID)
    closedGroupControlMessage.sentTimestamp = sentTime
    send(closedGroupControlMessage, Address.fromSerialized(groupID))
    // Update the group
    storage.updateTitle(groupID, newName)
    // Notify the user
    val infoType = SignalServiceGroup.Type.NAME_CHANGE
    val threadID = storage.getOrCreateThreadIdFor(Address.fromSerialized(groupID))
    storage.insertOutgoingInfoMessage(context, groupID, infoType, newName, members, admins, threadID, sentTime)
}

fun MessageSender.addMembers(groupPublicKey: String, membersToAdd: List<String>) {
    val context = MessagingModuleConfiguration.shared.context
    val storage = MessagingModuleConfiguration.shared.storage
    val groupID = GroupUtil.doubleEncodeGroupID(groupPublicKey)
    val group = storage.getGroup(groupID) ?: run {
        Log.d("Loki", "Can't add members to nonexistent closed group.")
        throw Error.NoThread
    }
    val threadId = storage.getOrCreateThreadIdFor(fromSerialized(groupID))
    val expireTimer = storage.getExpirationConfiguration(threadId)?.expiryMode?.expirySeconds ?: 0
    if (membersToAdd.isEmpty()) {
        Log.d("Loki", "Invalid closed group update.")
        throw Error.InvalidClosedGroupUpdate
    }
    val updatedMembers = group.members.map { it.serialize() }.toSet() + membersToAdd
    // Save the new group members
    storage.updateMembers(groupID, updatedMembers.map { Address.fromSerialized(it) })
    val membersAsData = updatedMembers.map { ByteString.copyFrom(Hex.fromStringCondensed(it)) }
    val newMembersAsData = membersToAdd.map { ByteString.copyFrom(Hex.fromStringCondensed(it)) }
    val admins = group.admins.map { it.serialize() }
    val adminsAsData = admins.map { ByteString.copyFrom(Hex.fromStringCondensed(it)) }
    val encryptionKeyPair = storage.getLatestClosedGroupEncryptionKeyPair(groupPublicKey) ?: run {
        Log.d("Loki", "Couldn't get encryption key pair for closed group.")
        throw Error.NoKeyPair
    }
    val name = group.title
    // Send the update to the group
    val memberUpdateKind = ClosedGroupControlMessage.Kind.MembersAdded(newMembersAsData)
    val sentTime = SnodeAPI.nowWithOffset
    val closedGroupControlMessage = ClosedGroupControlMessage(memberUpdateKind, groupID)
    closedGroupControlMessage.sentTimestamp = sentTime
    send(closedGroupControlMessage, Address.fromSerialized(groupID))
    // Send closed group update messages to any new members individually
    for (member in membersToAdd) {
        val closedGroupNewKind = ClosedGroupControlMessage.Kind.New(
            ByteString.copyFrom(Hex.fromStringCondensed(groupPublicKey)),
            name,
            encryptionKeyPair,
            membersAsData,
            adminsAsData,
            expireTimer.toInt()
        )
        val closedGroupControlMessage = ClosedGroupControlMessage(closedGroupNewKind, groupID)
        // It's important that the sent timestamp of this message is greater than the sent timestamp
        // of the `MembersAdded` message above. The reason is that upon receiving this `New` message,
        // the recipient will update the closed group formation timestamp and ignore any closed group
        // updates from before that timestamp. By setting the timestamp of the message below to a value
        // greater than that of the `MembersAdded` message, we ensure that newly added members ignore
        // the `MembersAdded` message.
        closedGroupControlMessage.sentTimestamp = SnodeAPI.nowWithOffset
        send(closedGroupControlMessage, Address.fromSerialized(member))
    }
    // Notify the user
    val infoType = SignalServiceGroup.Type.MEMBER_ADDED
    val threadID = storage.getOrCreateThreadIdFor(Address.fromSerialized(groupID))
    storage.insertOutgoingInfoMessage(context, groupID, infoType, name, membersToAdd, admins, threadID, sentTime)
}

suspend fun MessageSender.removeMembers(groupPublicKey: String, membersToRemove: List<String>) {
    val context = MessagingModuleConfiguration.shared.context
    val storage = MessagingModuleConfiguration.shared.storage
    val userPublicKey = storage.getUserPublicKey()!!
    val groupID = GroupUtil.doubleEncodeGroupID(groupPublicKey)
    val group = storage.getGroup(groupID) ?: run {
        Log.d("Loki", "Can't remove members from nonexistent closed group.")
        throw Error.NoThread
    }
    if (membersToRemove.isEmpty() || membersToRemove.contains(userPublicKey)) {
        Log.d("Loki", "Invalid closed group update.")
        throw Error.InvalidClosedGroupUpdate
    }
    val admins = group.admins.map { it.serialize() }
    if (!admins.contains(userPublicKey)) {
        Log.d("Loki", "Only an admin can remove members from a group.")
        throw Error.InvalidClosedGroupUpdate
    }
    val updatedMembers = group.members.map { it.serialize() }.toSet() - membersToRemove
    if (membersToRemove.any { it in admins } && updatedMembers.isNotEmpty()) {
        Log.d("Loki", "Can't remove admin from closed group unless the group is destroyed entirely.")
        throw Error.InvalidClosedGroupUpdate
    }
    // Save the new group members
    storage.updateMembers(groupID, updatedMembers.map { Address.fromSerialized(it) })
    // Update the zombie list
    val oldZombies = storage.getZombieMembers(groupID)
    storage.setZombieMembers(groupID, oldZombies.minus(membersToRemove).map { Address.fromSerialized(it) })
    val removeMembersAsData = membersToRemove.map { ByteString.copyFrom(Hex.fromStringCondensed(it)) }
    val name = group.title
    // Send the update to the group
    val memberUpdateKind = ClosedGroupControlMessage.Kind.MembersRemoved(removeMembersAsData)
    val sentTime = SnodeAPI.nowWithOffset
    val closedGroupControlMessage = ClosedGroupControlMessage(memberUpdateKind, groupID)
    closedGroupControlMessage.sentTimestamp = sentTime
    send(closedGroupControlMessage, Address.fromSerialized(groupID))
    // Send the new encryption key pair to the remaining group members.
    // At this stage we know the user is admin, no need to test.
    generateAndSendNewEncryptionKeyPair(groupPublicKey, updatedMembers)
    // Notify the user
    // We don't display zombie members in the notification as users have already been notified when those members left
    val notificationMembers = membersToRemove.minus(oldZombies)
    if (notificationMembers.isNotEmpty()) {
        // No notification to display when only zombies have been removed
        val infoType = SignalServiceGroup.Type.MEMBER_REMOVED
        val threadID = storage.getOrCreateThreadIdFor(Address.fromSerialized(groupID))
        storage.insertOutgoingInfoMessage(context, groupID, infoType, name, notificationMembers, admins, threadID, sentTime)
    }
}

suspend fun MessageSender.leave(groupPublicKey: String, notifyUser: Boolean = true){
    withContext(Dispatchers.IO) {
        val context = MessagingModuleConfiguration.shared.context
        val storage = MessagingModuleConfiguration.shared.storage
        val userPublicKey = TextSecurePreferences.getLocalNumber(context)!!
        val groupID = GroupUtil.doubleEncodeGroupID(groupPublicKey)
        val group = storage.getGroup(groupID) ?: throw Error.NoThread
        val updatedMembers = group.members.map { it.serialize() }.toSet() - userPublicKey
        val admins = group.admins.map { it.serialize() }
        val name = group.title
        // Send the update to the group
        val closedGroupControlMessage = ClosedGroupControlMessage(ClosedGroupControlMessage.Kind.MemberLeft(), groupID)
        val sentTime = SnodeAPI.nowWithOffset
        closedGroupControlMessage.sentTimestamp = sentTime
        storage.setActive(groupID, false)

        try {
            sendNonDurably(closedGroupControlMessage, Address.fromSerialized(groupID), isSyncMessage = false)
            val infoType = SignalServiceGroup.Type.QUIT
            if (notifyUser) {
                val threadID = storage.getOrCreateThreadIdFor(Address.fromSerialized(groupID))
                storage.insertOutgoingInfoMessage(context, groupID, infoType, name, updatedMembers, admins, threadID, sentTime)
            }
            // Remove the group private key and unsubscribe from PNs
            MessageReceiver.disableLocalGroupAndUnsubscribe(groupPublicKey, groupID, userPublicKey, true)
        } catch (e: Exception) {
            storage.setActive(groupID, true)
            throw e
        }

    }
}

suspend fun MessageSender.generateAndSendNewEncryptionKeyPair(groupPublicKey: String, targetMembers: Collection<String>) {
    // Prepare
    val storage = MessagingModuleConfiguration.shared.storage
    val userPublicKey = storage.getUserPublicKey()!!
    val groupID = GroupUtil.doubleEncodeGroupID(groupPublicKey)
    val group = storage.getGroup(groupID) ?: run {
        Log.d("Loki", "Can't update nonexistent closed group.")
        throw Error.NoThread
    }
    if (!group.admins.map { it.toString() }.contains(userPublicKey)) {
        Log.d("Loki", "Can't distribute new encryption key pair as non-admin.")
        throw Error.InvalidClosedGroupUpdate
    }
    // Generate the new encryption key pair
    val newKeyPair = Curve.generateKeyPair()
    // Replace call will not succeed if no value already set
    pendingKeyPairs.putIfAbsent(groupPublicKey,Optional.absent())
    do {
        // Make sure we set the pending key pair or wait until it is not null
    } while (!pendingKeyPairs.replace(groupPublicKey,Optional.absent(),Optional.fromNullable(newKeyPair)))
    // Distribute it
    sendEncryptionKeyPair(groupPublicKey, newKeyPair, targetMembers)
    // Store it * after * having sent out the message to the group
    storage.addClosedGroupEncryptionKeyPair(newKeyPair, groupPublicKey, SnodeAPI.nowWithOffset)
    pendingKeyPairs[groupPublicKey] = Optional.absent()
}

suspend fun MessageSender.sendEncryptionKeyPair(groupPublicKey: String, newKeyPair: ECKeyPair, targetMembers: Collection<String>, targetUser: String? = null, force: Boolean = true) {
    val destination = targetUser ?: GroupUtil.doubleEncodeGroupID(groupPublicKey)
    val proto = SignalServiceProtos.KeyPair.newBuilder()
    proto.publicKey = ByteString.copyFrom(newKeyPair.publicKey.serialize().removingIdPrefixIfNeeded())
    proto.privateKey = ByteString.copyFrom(newKeyPair.privateKey.serialize())
    val plaintext = proto.build().toByteArray()
    val wrappers = targetMembers.map { publicKey ->
        val ciphertext = MessageEncrypter.encrypt(plaintext, publicKey)
        ClosedGroupControlMessage.KeyPairWrapper(publicKey, ByteString.copyFrom(ciphertext))
    }
    val kind = ClosedGroupControlMessage.Kind.EncryptionKeyPair(ByteString.copyFrom(Hex.fromStringCondensed(groupPublicKey)), wrappers)
    val sentTime = SnodeAPI.nowWithOffset
    val closedGroupControlMessage = ClosedGroupControlMessage(kind, null)
    closedGroupControlMessage.sentTimestamp = sentTime
    if (force) {
        val isSync = MessagingModuleConfiguration.shared.storage.getUserPublicKey() == destination
        MessageSender.sendNonDurably(closedGroupControlMessage, Address.fromSerialized(destination), isSyncMessage = isSync)
    } else {
        MessageSender.send(closedGroupControlMessage, Address.fromSerialized(destination))
    }
}

fun MessageSender.sendLatestEncryptionKeyPair(publicKey: String, groupPublicKey: String) {
    val storage = MessagingModuleConfiguration.shared.storage
    val groupID = GroupUtil.doubleEncodeGroupID(groupPublicKey)
    val group = storage.getGroup(groupID) ?: run {
        Log.d("Loki", "Can't send encryption key pair for nonexistent closed group.")
        throw Error.NoThread
    }
    val members = group.members.map { it.serialize() }
    if (!members.contains(publicKey)) {
        Log.d("Loki", "Refusing to send latest encryption key pair to non-member.")
        return
    }
    // Get the latest encryption key pair
    val encryptionKeyPair = pendingKeyPairs[groupPublicKey]?.orNull()
        ?: storage.getLatestClosedGroupEncryptionKeyPair(groupPublicKey) ?: return
    // Send it
    val proto = SignalServiceProtos.KeyPair.newBuilder()
    proto.publicKey = ByteString.copyFrom(encryptionKeyPair.publicKey.serialize().removingIdPrefixIfNeeded())
    proto.privateKey = ByteString.copyFrom(encryptionKeyPair.privateKey.serialize())
    val plaintext = proto.build().toByteArray()
    val ciphertext = MessageEncrypter.encrypt(plaintext, publicKey)
    Log.d("Loki", "Sending latest encryption key pair to: $publicKey.")
    val wrapper = ClosedGroupControlMessage.KeyPairWrapper(publicKey, ByteString.copyFrom(ciphertext))
    val kind = ClosedGroupControlMessage.Kind.EncryptionKeyPair(ByteString.copyFrom(Hex.fromStringCondensed(groupPublicKey)), listOf(wrapper))
    val closedGroupControlMessage = ClosedGroupControlMessage(kind, groupID)
    MessageSender.send(closedGroupControlMessage, Address.fromSerialized(publicKey))
}