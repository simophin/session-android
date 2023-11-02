package org.thoughtcrime.securesms.groups

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.liveData
import dagger.hilt.android.lifecycle.HiltViewModel
import network.loki.messenger.R
import org.session.libsession.utilities.recipients.Recipient
import org.thoughtcrime.securesms.database.Storage
import org.thoughtcrime.securesms.groups.compose.StateUpdate
import org.thoughtcrime.securesms.groups.compose.ViewState
import javax.inject.Inject

@HiltViewModel
class CreateGroupViewModel @Inject constructor(
    private val storage: Storage,
) : ViewModel() {

    private fun create() {
        tryCreateGroup()
    }

    private inline fun <reified T> MutableLiveData<T>.update(body: T.() -> T) {
        this.postValue(body(this.value!!))
    }

    private val _viewState = MutableLiveData(ViewState.DEFAULT.copy())

    val viewState: LiveData<ViewState> = _viewState

    fun updateState(stateUpdate: StateUpdate) {
        when (stateUpdate) {
            is StateUpdate.AddContacts -> _viewState.update { copy(members = members + stateUpdate.value) }
            is StateUpdate.Description -> _viewState.update { copy(description = stateUpdate.value) }
            is StateUpdate.Name -> _viewState.update { copy(name = stateUpdate.value) }
            is StateUpdate.RemoveContact -> _viewState.update { copy(members = members - stateUpdate.value) }
            StateUpdate.Create -> { tryCreateGroup() }
        }
    }

    val contacts
        get() = liveData { emit(storage.getAllContacts()) }

    fun tryCreateGroup(): Recipient? {

        val currentState = _viewState.value!!

        _viewState.postValue(currentState.copy(isLoading = true, error = null))

        val name = currentState.name
        val description = currentState.description
        val members = currentState.members.toMutableSet()

        // do some validation
        // need a name
        if (name.isEmpty()) {
            _viewState.postValue(
                currentState.copy(isLoading = false, error = R.string.error)
            )
            return null
        }

        storage.getAllContacts().forEach { contact ->
            members.add(contact)
        }

        if (members.size <= 1) {
            _viewState.postValue(
                currentState.copy(
                    isLoading = false,
                    error = R.string.activity_create_closed_group_not_enough_group_members_error
                )
            )
        }

        // make a group
        val newGroup = storage.createNewGroup(name, description, members)
        if (!newGroup.isPresent) {
            _viewState.postValue(currentState.copy(isLoading = false, error = null))
        }
        return newGroup.orNull()
    }
}