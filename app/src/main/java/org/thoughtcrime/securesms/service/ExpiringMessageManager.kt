package org.thoughtcrime.securesms.service

import android.content.Context
import network.loki.messenger.libsession_util.util.ExpiryMode
import network.loki.messenger.libsession_util.util.ExpiryMode.AfterSend
import org.session.libsession.messaging.MessagingModuleConfiguration.Companion.shared
import org.session.libsession.messaging.messages.control.ExpirationTimerUpdate
import org.session.libsession.messaging.messages.signal.IncomingMediaMessage
import org.session.libsession.messaging.messages.signal.OutgoingExpirationUpdateMessage
import org.session.libsession.snode.SnodeAPI.nowWithOffset
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.fromSerialized
import org.session.libsession.utilities.GroupUtil.doubleEncodeGroupID
import org.session.libsession.utilities.GroupUtil.getDecodedGroupIDAsData
import org.session.libsession.utilities.SSKEnvironment.MessageExpirationManagerProtocol
import org.session.libsession.utilities.TextSecurePreferences.Companion.getLocalNumber
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsignal.messages.SignalServiceGroup
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.guava.Optional
import org.thoughtcrime.securesms.database.MmsDatabase
import org.thoughtcrime.securesms.database.MmsSmsDatabase
import org.thoughtcrime.securesms.database.SmsDatabase
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.dependencies.DatabaseComponent.Companion.get
import org.thoughtcrime.securesms.mms.MmsException
import java.io.IOException
import java.util.TreeSet
import java.util.concurrent.Executor
import java.util.concurrent.Executors

private val TAG = ExpiringMessageManager::class.java.simpleName
class ExpiringMessageManager(context: Context) : MessageExpirationManagerProtocol {
    private val expiringMessageReferences = TreeSet<ExpiringMessageReference>()
    private val executor: Executor = Executors.newSingleThreadExecutor()
    private val smsDatabase: SmsDatabase
    private val mmsDatabase: MmsDatabase
    private val mmsSmsDatabase: MmsSmsDatabase
    private val context: Context

    init {
        this.context = context.applicationContext
        smsDatabase = get(context).smsDatabase()
        mmsDatabase = get(context).mmsDatabase()
        mmsSmsDatabase = get(context).mmsSmsDatabase()
        executor.execute(LoadTask())
        executor.execute(ProcessTask())
    }

    private fun getDatabase(mms: Boolean) = if (mms) mmsDatabase else smsDatabase

    fun scheduleDeletion(id: Long, mms: Boolean, startedAtTimestamp: Long, expiresInMillis: Long) {
        Log.d(TAG, "scheduleDeletion() called with: id = $id, mms = $mms, startedAtTimestamp = $startedAtTimestamp, expiresInMillis = $expiresInMillis")
        val expiresAtMillis = startedAtTimestamp + expiresInMillis
        synchronized(expiringMessageReferences) {
            expiringMessageReferences += ExpiringMessageReference(id, mms, expiresAtMillis)
            (expiringMessageReferences as Object).notifyAll()
        }
    }

    fun checkSchedule() {
        synchronized(expiringMessageReferences) { (expiringMessageReferences as Object).notifyAll() }
    }

    private fun insertIncomingExpirationTimerMessage(
        message: ExpirationTimerUpdate,
        expireStartedAt: Long
    ) {
        Log.d(TAG, "insertIncomingExpirationTimerMessage() called with: message = $message, expireStartedAt = $expireStartedAt")
        val senderPublicKey = message.sender
        val sentTimestamp = message.sentTimestamp
        val groupId = message.groupPublicKey
        val expiresInMillis = message.expiryMode.expiryMillis
        var groupInfo = Optional.absent<SignalServiceGroup?>()
        val address = fromSerialized(senderPublicKey!!)
        var recipient = Recipient.from(context, address, false)

        // if the sender is blocked, we don't display the update, except if it's in a closed group
        if (recipient.isBlocked && groupId == null) return
        try {
            if (groupId != null) {
                val groupID = doubleEncodeGroupID(groupId)
                groupInfo = Optional.of(
                    SignalServiceGroup(
                        getDecodedGroupIDAsData(groupID),
                        SignalServiceGroup.GroupType.SIGNAL
                    )
                )
                val groupAddress = fromSerialized(groupID)
                recipient = Recipient.from(context, groupAddress, false)
            }
            val threadId = shared.storage.getThreadId(recipient) ?: return
            val mediaMessage = IncomingMediaMessage(
                address, sentTimestamp!!, -1,
                expiresInMillis, expireStartedAt, true,
                false,
                false,
                false,
                Optional.absent(),
                groupInfo,
                Optional.absent(),
                Optional.absent(),
                Optional.absent(),
                Optional.absent(),
                Optional.absent()
            )
            //insert the timer update message
            mmsDatabase.insertSecureDecryptedMessageInbox(mediaMessage, threadId, runThreadUpdate = true)
        } catch (ioe: IOException) {
            Log.e("Loki", "Failed to insert expiration update message.")
        } catch (ioe: MmsException) {
            Log.e("Loki", "Failed to insert expiration update message.")
        }
    }

    private fun insertOutgoingExpirationTimerMessage(
        message: ExpirationTimerUpdate,
        expireStartedAt: Long
    ) {
        Log.d(TAG, "insertOutgoingExpirationTimerMessage() called with: message = $message, expireStartedAt = $expireStartedAt")
        val sentTimestamp = message.sentTimestamp
        val groupId = message.groupPublicKey
        val duration = message.expiryMode.expiryMillis
        try {
            val serializedAddress = groupId?.let(::doubleEncodeGroupID)
                ?: message.syncTarget?.takeIf { it.isNotEmpty() }
                ?: message.recipient!!
            val address = fromSerialized(serializedAddress)
            val recipient = Recipient.from(context, address, false)

            message.threadID = shared.storage.getOrCreateThreadIdFor(address)
            val timerUpdateMessage = OutgoingExpirationUpdateMessage(
                recipient,
                sentTimestamp!!,
                duration,
                expireStartedAt,
                groupId
            )
            mmsDatabase.insertSecureDecryptedMessageOutbox(
                timerUpdateMessage,
                message.threadID!!,
                sentTimestamp,
                true
            )
        } catch (ioe: MmsException) {
            Log.e("Loki", "Failed to insert expiration update message.", ioe)
        } catch (ioe: IOException) {
            Log.e("Loki", "Failed to insert expiration update message.", ioe)
        }
    }

    override fun setExpirationTimer(message: ExpirationTimerUpdate) {
        val expiryMode: ExpiryMode = message.expiryMode
        Log.d(TAG, "setExpirationTimer() called with: message = $message, expiryMode = $expiryMode")

        val userPublicKey = getLocalNumber(context)
        val senderPublicKey = message.sender
        val sentTimestamp = if (message.sentTimestamp == null) 0 else message.sentTimestamp!!
        val expireStartedAt =
            if (expiryMode is AfterSend || message.isSenderSelf) sentTimestamp else 0

        // Notify the user
        if (senderPublicKey == null || userPublicKey == senderPublicKey) {
            // sender is self or a linked device
            insertOutgoingExpirationTimerMessage(message, expireStartedAt)
        } else {
            insertIncomingExpirationTimerMessage(message, expireStartedAt)
        }

        if (expiryMode is AfterSend && expiryMode.expirySeconds > 0 && message.sentTimestamp != null && senderPublicKey != null) {
            startAnyExpiration(message.sentTimestamp!!, senderPublicKey, expireStartedAt)
        }
    }

    override fun startAnyExpiration(timestamp: Long, author: String, expireStartedAt: Long) {
        Log.d(TAG, "startAnyExpiration() called with: timestamp = $timestamp, author = $author, expireStartedAt = $expireStartedAt")
        val messageRecord = mmsSmsDatabase.getMessageFor(timestamp, author) ?: return
        val mms = messageRecord.isMms()
        getDatabase(mms).markExpireStarted(messageRecord.getId(), expireStartedAt)
        scheduleDeletion(messageRecord.getId(), mms, expireStartedAt, messageRecord.expiresIn)
    }

    private inner class LoadTask : Runnable {
        override fun run() {
            val smsReader = smsDatabase.readerFor(smsDatabase.getExpirationStartedMessages())
            val mmsReader = mmsDatabase.expireStartedMessages

            val smsMessages = smsReader.use { generateSequence { it.next }.toList() }
            val mmsMessages = mmsReader.use { generateSequence { it.next }.toList() }

            (smsMessages + mmsMessages).forEach { messageRecord ->
                expiringMessageReferences += ExpiringMessageReference(
                    messageRecord.getId(),
                    messageRecord.isMms,
                    messageRecord.expireStarted + messageRecord.expiresIn
                )
            }
        }
    }

    private inner class ProcessTask : Runnable {
        override fun run() {
            while (true) {
                synchronized(expiringMessageReferences) {
                    try {
                        while (expiringMessageReferences.isEmpty()) (expiringMessageReferences as Object).wait()
                        val nextReference = expiringMessageReferences.first()
                        val waitTime = nextReference.expiresAtMillis - nowWithOffset
                        if (waitTime > 0) {
                            ExpirationListener.setAlarm(context, waitTime)
                            (expiringMessageReferences as Object).wait(waitTime)
                            null
                        } else {
                            expiringMessageReferences -= nextReference
                            nextReference
                        }
                    } catch (e: InterruptedException) {
                        Log.w(TAG, e)
                        null
                    }
                }?.run { getDatabase(mms).deleteMessage(id) }
            }
        }
    }

    private data class ExpiringMessageReference(
        val id: Long,
        val mms: Boolean,
        val expiresAtMillis: Long
    ): Comparable<ExpiringMessageReference> {
        override fun compareTo(other: ExpiringMessageReference) = compareValuesBy(this, other, { it.expiresAtMillis }, { it.id }, { it.mms })
    }
}
