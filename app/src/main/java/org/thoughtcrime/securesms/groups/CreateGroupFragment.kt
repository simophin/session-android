package org.thoughtcrime.securesms.groups

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import network.loki.messenger.R
import network.loki.messenger.databinding.FragmentCreateGroupBinding
import org.session.libsession.messaging.contacts.Contact
import org.session.libsession.utilities.Device
import org.session.libsession.utilities.recipients.Recipient
import org.thoughtcrime.securesms.conversation.start.NewConversationDelegate
import org.thoughtcrime.securesms.conversation.v2.ConversationActivityV2
import org.thoughtcrime.securesms.ui.AppTheme
import org.thoughtcrime.securesms.ui.EditableAvatar
import org.thoughtcrime.securesms.ui.NavigationBar
import org.thoughtcrime.securesms.ui.PreviewTheme
import org.thoughtcrime.securesms.ui.ThemeResPreviewParameterProvider
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

    data class ViewState(
        val isLoading: Boolean,
        @StringRes val error: Int?
    ) {
        companion object {
            val DEFAULT = ViewState(false, null)
        }
    }

}

data class CreateGroupState (
    val groupName: String,
    val groupDescription: String,
    val members: Set<Contact>
)

@Composable
fun CreateGroup(
    viewState: CreateGroupFragment.ViewState,
    createGroupState: CreateGroupState,
    onCreate: (CreateGroupState) -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier) {

    var name by remember { mutableStateOf(createGroupState.groupName) }
    var description by remember { mutableStateOf(createGroupState.groupDescription) }
    val members by remember { mutableStateOf(createGroupState.members) }

    val scrollState = rememberScrollState()

    Box {
        Column(
            modifier
                .fillMaxWidth()) {
            Column(modifier.scrollable(scrollState, orientation = Orientation.Vertical)) {
                // Top bar
                NavigationBar(
                    title = stringResource(id = R.string.activity_create_group_title),
                    onBack = onBack,
                    onClose = onClose
                )
                // Editable avatar (future chunk)
                EditableAvatar(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 16.dp)
                )
                // Title
                val nameDescription = stringResource(id = R.string.AccessibilityId_closed_group_edit_group_name)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.CenterHorizontally)
                        .padding(vertical = 8.dp, horizontal = 24.dp)
                        .semantics {
                            contentDescription = nameDescription
                        },
                )
                // Description
                val descriptionDescription = stringResource(id = R.string.AccessibilityId_closed_group_edit_group_description)
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.CenterHorizontally)
                        .padding(vertical = 8.dp, horizontal = 24.dp)
                        .semantics {
                            contentDescription = descriptionDescription
                        },
                )
                // Group list
                MemberList(contacts = members, modifier = Modifier.padding(vertical = 8.dp, horizontal = 24.dp))
            }
            // Create button
            val createDescription = stringResource(id = R.string.AccessibilityId_create_closed_group_create_button)
            OutlinedButton(
                onClick = { onCreate(CreateGroupState(name, description, members)) },
                enabled = name.isNotBlank() && !viewState.isLoading,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(16.dp)
                    .semantics {
                        contentDescription = createDescription
                    }
                ,
                shape = RoundedCornerShape(32.dp)
            ) {
                Text(
                    text = stringResource(id = R.string.activity_create_group_create_button_title),
                    // TODO: colours of everything here probably needs to be redone
                    color = MaterialTheme.colors.onBackground,
                    modifier = Modifier.width(160.dp),
                    textAlign = TextAlign.Center
                )
            }
        }
        if (viewState.isLoading) {
            Box(modifier = modifier
                .fillMaxSize()
                .background(Color.Gray.copy(alpha = 0.5f))) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}


@Composable
fun MemberList(contacts: Collection<Contact>, modifier: Modifier = Modifier) {
    Column(modifier = modifier
        .fillMaxWidth()
        .defaultMinSize(minHeight = 240.dp)) {
        Text(text = stringResource(id = R.string.conversation_settings_group_members),
            modifier = Modifier
                .align(Alignment.Start)
                .padding(vertical = 8.dp)
        )
        // TODO group list representation
        Text(
            text = stringResource(id = R.string.activity_create_closed_group_not_enough_group_members_error),
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(vertical = 8.dp)
        )
    }
}

@Preview
@Composable
fun ClosedGroupPreview(
    @PreviewParameter(ThemeResPreviewParameterProvider::class) themeResId: Int
) {
    PreviewTheme(themeResId) {
        CreateGroup(
            viewState = CreateGroupFragment.ViewState(false, null),
            createGroupState = CreateGroupState("Group Name", "Test Group Description", emptySet()),
            onCreate = {},
            onClose = {},
            onBack = {},
        )
    }
}