package org.thoughtcrime.securesms.groups

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.liveData
import dagger.hilt.android.lifecycle.HiltViewModel
import network.loki.messenger.R
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.recipients.Recipient
import org.thoughtcrime.securesms.database.Storage
import org.thoughtcrime.securesms.groups.compose.CreateGroupState
import org.thoughtcrime.securesms.groups.compose.ViewState
import javax.inject.Inject

@HiltViewModel
class CreateGroupViewModel @Inject constructor(
    private val textSecurePreferences: TextSecurePreferences,
    private val storage: Storage,
) : ViewModel() {

    private val _viewState = MutableLiveData(ViewState.DEFAULT)
    val viewState: LiveData<ViewState>  = _viewState

    val createGroupState: MutableLiveData<CreateGroupState> = MutableLiveData(CreateGroupState("","", emptySet()))

    val contacts = liveData {
        emit(storage.getAllContacts().toList())
    }

    init {
//        viewModelScope.launch {
//            threadDb.approvedConversationList.use { openCursor ->
//                val reader = threadDb.readerFor(openCursor)
//                val recipients = mutableListOf<Recipient>()
//                while (true) {
//                    recipients += reader.next?.recipient ?: break
//                }
//                withContext(Dispatchers.Main) {
//                    _recipients.value = recipients
//                        .filter { !it.isGroupRecipient && it.hasApprovedMe() && it.address.serialize() != textSecurePreferences.getLocalNumber() }
//                }
//            }
//        }
    }

    fun tryCreateGroup(createGroupState: CreateGroupState): Recipient? {
        _viewState.postValue(ViewState(true, null))

        val name = createGroupState.groupName
        val description = createGroupState.groupDescription
        val members = createGroupState.members.toMutableSet()

        // do some validation
        // need a name
        if (name.isEmpty()) {
            _viewState.postValue(
                ViewState(false, R.string.error)
            )
            return null
        }

        storage.getAllContacts().forEach { contact ->
            members.add(contact)
        }

        if (members.size <= 1) {
            _viewState.postValue(
                ViewState(false, R.string.activity_create_closed_group_not_enough_group_members_error)
            )
        }

        // make a group
        val newGroup = storage.createNewGroup(name, description, members)
        if (!newGroup.isPresent) {
            _viewState.postValue(ViewState(isLoading = false, null))
        }
        return newGroup.orNull()
    }
}