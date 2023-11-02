package org.thoughtcrime.securesms.groups

import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.hilt.navigation.compose.hiltViewModel
import com.ramcosta.composedestinations.DestinationsNavHost
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import com.ramcosta.composedestinations.navigation.dependency
import com.ramcosta.composedestinations.result.NavResult
import com.ramcosta.composedestinations.result.ResultBackNavigator
import com.ramcosta.composedestinations.result.ResultRecipient
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.parcelize.Parcelize
import network.loki.messenger.databinding.FragmentCreateGroupBinding
import org.session.libsession.messaging.contacts.Contact
import org.thoughtcrime.securesms.conversation.start.NewConversationDelegate
import org.thoughtcrime.securesms.groups.compose.CreateGroup
import org.thoughtcrime.securesms.groups.compose.CreateGroupNavGraph
import org.thoughtcrime.securesms.groups.compose.SelectContacts
import org.thoughtcrime.securesms.groups.compose.StateUpdate
import org.thoughtcrime.securesms.groups.compose.ViewState
import org.thoughtcrime.securesms.groups.destinations.SelectContactsScreenDestination
import org.thoughtcrime.securesms.ui.AppTheme

@AndroidEntryPoint
class CreateGroupFragment : Fragment() {

    private lateinit var binding: FragmentCreateGroupBinding

    lateinit var delegate: NewConversationDelegate

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            val getDelegate = { delegate }
            setContent {
                AppTheme {
                    DestinationsNavHost(
                        navGraph = NavGraphs.createGroup,
                        dependenciesContainerBuilder = {
                            dependency(getDelegate)
                        })
                }
            }
        }
    }

}

@Parcelize
data class ContactList(val contacts: List<Contact>) : Parcelable

@CreateGroupNavGraph(start = true)
@Composable
@Destination
fun CreateGroupScreen(
    navigator: DestinationsNavigator,
    resultSelectContact: ResultRecipient<SelectContactsScreenDestination, ContactList>,
    viewModel: CreateGroupViewModel = hiltViewModel(),
    getDelegate: () -> NewConversationDelegate
) {
    val viewState by viewModel.viewState.observeAsState(ViewState.DEFAULT)

    resultSelectContact.onNavResult { navResult ->
        when (navResult) {
            is NavResult.Value -> {
                viewModel.updateState(StateUpdate.AddContacts(navResult.value.contacts))
            }

            is NavResult.Canceled -> { /* do nothing */
            }
        }
    }

    CreateGroup(
        viewState,
        viewModel::updateState,
        onClose = {
            getDelegate().onDialogClosePressed()
        },
        onSelectContact = { navigator.navigate(SelectContactsScreenDestination) },
        onBack = {
            getDelegate().onDialogBackPressed()
        }
    )
}

@CreateGroupNavGraph
@Composable
@Destination
fun SelectContactsScreen(
    resultNavigator: ResultBackNavigator<ContactList>,
    viewModel: CreateGroupViewModel = hiltViewModel(),
    getDelegate: () -> NewConversationDelegate
) {

    val viewState by viewModel.viewState.observeAsState(ViewState.DEFAULT)
    val currentMembers = viewState.members
    val contacts by viewModel.contacts.observeAsState(initial = emptySet())

    SelectContacts(
        contacts - currentMembers,
        onBack = { resultNavigator.navigateBack() },
        onClose = { getDelegate().onDialogClosePressed() },
        onContactsSelected = {
            resultNavigator.navigateBack(ContactList(it))
        }
    )
}