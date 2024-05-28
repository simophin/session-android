package org.thoughtcrime.securesms.conversation.start

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.loki.messenger.libsession_util.util.Contact
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.recipients.Recipient
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import javax.inject.Inject

@HiltViewModel
class NewConversationHomeViewModel
@Inject constructor(
        private val configFactory: ConfigFactory,
        @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _recipientGroups = MutableLiveData<List<RecipientGroup>>()
    val recipientsGroups: LiveData<List<RecipientGroup>> get() = _recipientGroups


    init {
        viewModelScope.launch {
            val groups = withContext(Dispatchers.IO) {
                configFactory.contacts!!.all()
                        .asSequence()
                        .sortedBy { it.displayName }
                        .groupBy {
                            it.displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "#"
                        }
                        .map { (title, values) ->
                            RecipientGroup(title = title, recipients = values.map {
                                ContactRecipient(
                                    Recipient.from(appContext, Address.fromSerialized(it.id), false),
                                    it.displayName
                                )
                            })
                        }
            }

            _recipientGroups.value = groups
        }
    }

    private val Contact.displayName: String
        get() = nickname.takeIf { it.isNotBlank() } ?: name

    data class ContactRecipient(val recipient: Recipient, val displayName: String)

    data class RecipientGroup(val title: String, val recipients: List<ContactRecipient>)
}
