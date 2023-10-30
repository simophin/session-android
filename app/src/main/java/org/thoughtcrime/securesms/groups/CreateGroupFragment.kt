package org.thoughtcrime.securesms.groups

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import network.loki.messenger.databinding.FragmentCreateGroupBinding
import org.session.libsession.utilities.Device
import org.session.libsession.utilities.recipients.Recipient
import org.thoughtcrime.securesms.conversation.start.NewConversationDelegate
import org.thoughtcrime.securesms.conversation.v2.ConversationActivityV2
import org.thoughtcrime.securesms.groups.compose.CreateGroup
import org.thoughtcrime.securesms.groups.compose.CreateGroupState
import org.thoughtcrime.securesms.groups.compose.ViewState
import org.thoughtcrime.securesms.ui.AppTheme
import javax.inject.Inject

@AndroidEntryPoint
class CreateGroupFragment : Fragment() {

    @Inject
    lateinit var device: Device

    private lateinit var binding: FragmentCreateGroupBinding
    private val viewModel: CreateGroupViewModel by viewModels()

    lateinit var delegate: NewConversationDelegate

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                // this is kind of annoying to require an initial state in the fragment and the VM
                val currentState = viewModel.viewState.observeAsState(initial = ViewState.DEFAULT)
                // create group state might be useful in future for adding members and returning
                // to the create group state with a copy or something
                CreateGroupScreen(currentState.value, createGroupState = CreateGroupState("", "", emptySet()))
            }
        }
    }

    private fun openConversationActivity(context: Context, recipient: Recipient) {
        val intent = Intent(context, ConversationActivityV2::class.java)
        intent.putExtra(ConversationActivityV2.ADDRESS, recipient.address)
        context.startActivity(intent)
    }

    @Composable
    fun CreateGroupScreen(viewState: ViewState,
                          createGroupState: CreateGroupState,
                          modifier: Modifier = Modifier) {
        AppTheme {
            CreateGroup(
                viewState,
                createGroupState,
                onCreate = { newGroup ->
                    // launch something to create here
                    // dunno if we want to key this here as a launched effect on some property :thinking:
                    lifecycleScope.launch(Dispatchers.IO) {
                        val groupRecipient = viewModel.tryCreateGroup(newGroup)
                        groupRecipient?.let { recipient ->
                            openConversationActivity(requireContext(), recipient)
                            delegate.onDialogClosePressed()
                        }
                    }
                },
                onClose = {
                    delegate.onDialogClosePressed()
                },
                onBack = {
                    delegate.onDialogBackPressed()
                }
            )
        }
    }

}