package org.thoughtcrime.securesms.groups

import android.content.Intent
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import network.loki.messenger.databinding.FragmentCreateGroupBinding
import org.session.libsession.messaging.contacts.Contact
import org.thoughtcrime.securesms.conversation.start.NewConversationDelegate
import org.thoughtcrime.securesms.conversation.v2.ConversationActivityV2
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
    resultSelectContact: ResultRecipient<SelectContactScreenDestination, Contact?>,
    viewModel: CreateGroupViewModel = hiltViewModel(),
    getDelegate: () -> NewConversationDelegate
) {
    val viewState by viewModel.viewState.observeAsState(ViewState.DEFAULT)
    val lifecycleScope = rememberCoroutineScope()
    val context = LocalContext.current
    val currentGroupState = viewModel.createGroupState

    CreateGroup(
        viewState,
        currentGroupState,
        onCreate = { newGroup ->
            // launch something to create here
            // dunno if we want to key this here as a launched effect on some property :thinking:
            lifecycleScope.launch(Dispatchers.IO) {
                val groupRecipient = viewModel.tryCreateGroup(newGroup)
                groupRecipient?.let { recipient ->
                    // launch conversation with this new group
                    val intent = Intent(context, ConversationActivityV2::class.java)
                    intent.putExtra(ConversationActivityV2.ADDRESS, recipient.address)
                    context.startActivity(intent)
                    getDelegate().onDialogClosePressed()
                }
            }
        },
        onSelectContact = {
            navigator.navigate(SelectContactScreenDestination)
        },
        onClose = {
            getDelegate().onDialogClosePressed()
        },
        onBack = {
            getDelegate().onDialogBackPressed()
        }
    )
}

@CreateGroupNavGraph
@Composable
@Destination
fun SelectContactScreen(
    resultNavigator: ResultBackNavigator<Contact?>,
    viewModel: CreateGroupViewModel = hiltViewModel(),
    getDelegate: () -> NewConversationDelegate
) {

    val contacts by viewModel.contacts.observeAsState(initial = emptyList())
    val currentMembers by viewModel.createGroupState.observeAsState()

    SelectContacts(
        contacts - currentMembers?.members.orEmpty(),
        onBack = { resultNavigator.navigateBack(null) },
        onClose = { getDelegate().onDialogClosePressed() }
    )
}