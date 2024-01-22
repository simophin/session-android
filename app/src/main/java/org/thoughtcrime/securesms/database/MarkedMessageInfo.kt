package org.thoughtcrime.securesms.database

import org.thoughtcrime.securesms.conversation.disappearingmessages.ExpiryType
import org.thoughtcrime.securesms.database.MessagingDatabase.SyncMessageId

data class MarkedMessageInfo(val syncMessageId: SyncMessageId, val expirationInfo: ExpirationInfo) {
    fun guessExpiryType(): ExpiryType = expirationInfo.run {
        when {
            syncMessageId.timetamp == expireStarted -> ExpiryType.AFTER_SEND
            expiresIn > 0 -> ExpiryType.AFTER_READ
            else -> ExpiryType.NONE
        }
    }
}
