package org.thoughtcrime.securesms.groups

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.Fragment
import androidx.hilt.navigation.compose.hiltViewModel
import com.ramcosta.composedestinations.DestinationsNavHost
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import com.ramcosta.composedestinations.navigation.dependency
import com.ramcosta.composedestinations.result.ResultBackNavigator
import com.ramcosta.composedestinations.result.ResultRecipient
import dagger.hilt.android.AndroidEntryPoint
import network.loki.messenger.databinding.FragmentCreateGroupBinding
import org.session.libsession.messaging.contacts.Contact
import org.thoughtcrime.securesms.conversation.start.NewConversationDelegate
import org.thoughtcrime.securesms.groups.compose.CreateGroup
import org.thoughtcrime.securesms.groups.compose.CreateGroupNavGraph
import org.thoughtcrime.securesms.groups.compose.SelectContacts
import org.thoughtcrime.securesms.groups.compose.ViewState
import org.thoughtcrime.securesms.groups.destinations.SelectContactScreenDestination
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
                    DestinationsNavHost(navGraph = NavGraphs.createGroup, dependenciesContainerBuilder = {
                        dependency(getDelegate)
                    })
                }
            }
        }
    }

}

@CreateGroupNavGraph(start = true)
@Composable
@Destination
fun CreateGroupScreen(
    navigator: DestinationsNavigator,
    resultSelectContact: ResultRecipient<SelectContactScreenDestination, Contact>,
    viewModel: CreateGroupViewModel = hiltViewModel(),
    getDelegate: () -> NewConversationDelegate
) {
    val viewState by viewModel.viewState.observeAsState(ViewState.DEFAULT)
    val lifecycleScope = rememberCoroutineScope()
    val context = LocalContext.current

    CreateGroup(
        viewState,
        onClose = {
            getDelegate().onDialogClosePressed()
        },
        onSelectContact = { navigator.navigate(SelectContactScreenDestination) },
        onBack = {
            getDelegate().onDialogBackPressed()
        }
    )
}

@CreateGroupNavGraph
@Composable
@Destination
fun SelectContactScreen(
    resultNavigator: ResultBackNavigator<Contact>,
    viewModel: CreateGroupViewModel = hiltViewModel(),
    getDelegate: () -> NewConversationDelegate
) {

    val viewState by viewModel.viewState.observeAsState(ViewState.DEFAULT)
    val currentMembers = viewState.members
    val contacts by viewModel.contacts.observeAsState(initial = emptyList())

    SelectContacts(
        contacts - currentMembers,
        onBack = { resultNavigator.navigateBack() },
        onClose = { getDelegate().onDialogClosePressed() }
    )
}