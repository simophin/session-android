package org.session.libsession.messaging.messages.control

import org.session.libsession.messaging.messages.copyExpiration
import org.session.libsignal.protos.SignalServiceProtos
import org.session.libsignal.utilities.Log

/** In the case of a sync message, the public key of the person the message was targeted at.
 *
 * **Note:** `nil` if this isn't a sync message.
 */
data class ExpirationTimerUpdate(var syncTarget: String? = null) : ControlMessage() {
    override val isSelfSendValid: Boolean = true

    override fun shouldDiscardIfBlocked(): Boolean = true

    companion object {
        const val TAG = "ExpirationTimerUpdate"

        fun fromProto(proto: SignalServiceProtos.Content): ExpirationTimerUpdate? {
            val dataMessageProto = if (proto.hasDataMessage()) proto.dataMessage else return null
            val isExpirationTimerUpdate = dataMessageProto.flags.and(
                SignalServiceProtos.DataMessage.Flags.EXPIRATION_TIMER_UPDATE_VALUE
            ) != 0
            if (!isExpirationTimerUpdate) return null

            return ExpirationTimerUpdate(dataMessageProto.syncTarget)
                    .copyExpiration(proto)
        }
    }

    override fun toProto(): SignalServiceProtos.Content? {
        val dataMessageProto = SignalServiceProtos.DataMessage.newBuilder()
        dataMessageProto.flags = SignalServiceProtos.DataMessage.Flags.EXPIRATION_TIMER_UPDATE_VALUE
        dataMessageProto.expireTimer = expiryMode.expirySeconds.toInt()
        // Sync target
        if (syncTarget != null) {
            dataMessageProto.syncTarget = syncTarget
        }
        return try {
            SignalServiceProtos.Content.newBuilder().apply {
                dataMessage = dataMessageProto.build()
                setExpirationConfigurationIfNeeded(threadID)
            }.build()
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't construct expiration timer update proto from: $this")
            null
        }
    }
}