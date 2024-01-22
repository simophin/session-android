package org.thoughtcrime.securesms.conversation.disappearingmessages

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import network.loki.messenger.R
import network.loki.messenger.libsession_util.util.ExpiryMode
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.messages.ExpirationConfiguration
import org.session.libsession.messaging.messages.control.ExpirationTimerUpdate
import org.session.libsession.messaging.sending_receiving.MessageSender
import org.session.libsession.snode.SnodeAPI
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.ExpirationUtil
import org.session.libsession.utilities.SSKEnvironment.MessageExpirationManagerProtocol
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.getExpirationTypeDisplayValue
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.showSessionDialog
import org.thoughtcrime.securesms.util.ConfigurationMessageUtilities
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

class DisappearingMessages @Inject constructor(
    @ApplicationContext private val context: Context,
    private val textSecurePreferences: TextSecurePreferences,
    private val messageExpirationManager: MessageExpirationManagerProtocol,
) {
    fun set(threadId: Long, address: Address, mode: ExpiryMode) {
        val expiryChangeTimestampMs = SnodeAPI.nowWithOffset
        MessagingModuleConfiguration.shared.storage.setExpirationConfiguration(ExpirationConfiguration(threadId, mode, expiryChangeTimestampMs))

        val message = ExpirationTimerUpdate().apply {
            expiryMode = mode
            sender = textSecurePreferences.getLocalNumber()
            isSenderSelf = true
            recipient = address.serialize()
            sentTimestamp = expiryChangeTimestampMs
        }

        messageExpirationManager.setExpirationTimer(message)
        MessageSender.send(message, address)
        ConfigurationMessageUtilities.forceSyncConfigurationNowIfNeeded(context)
    }

    fun showFollowSettingDialog(context: Context, message: MessageRecord) = context.showSessionDialog {
        title(R.string.dialog_disappearing_messages_follow_setting_title)
        if (message.expiresIn == 0L) {
            text(R.string.dialog_disappearing_messages_follow_setting_off_body)
        } else {
            text(
                context.getString(
                    R.string.dialog_disappearing_messages_follow_setting_on_body,
                    ExpirationUtil.getExpirationDisplayValue(
                        context,
                        message.expiresIn.milliseconds
                    ),
                    context.getExpirationTypeDisplayValue(message.isNotDisappearAfterRead)
                )
            )
        }
        destructiveButton(if (message.expiresIn == 0L) R.string.dialog_disappearing_messages_follow_setting_confirm else R.string.dialog_disappearing_messages_follow_setting_set) {
            set(message.threadId, message.recipient.address, message.expiryMode)
        }
        cancelButton()
    }
}

val MessageRecord.expiryMode get() = if (expiresIn <= 0) ExpiryMode.NONE
    else if (expireStarted == timestamp) ExpiryMode.AfterSend(expiresIn / 1000)
    else ExpiryMode.AfterRead(expiresIn / 1000)
