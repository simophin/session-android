package org.thoughtcrime.securesms.groups.compose

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import network.loki.messenger.R
import org.session.libsession.messaging.contacts.Contact
import org.thoughtcrime.securesms.ui.EditableAvatar
import org.thoughtcrime.securesms.ui.NavigationBar
import org.thoughtcrime.securesms.ui.PreviewTheme
import org.thoughtcrime.securesms.ui.ThemeResPreviewParameterProvider


data class CreateGroupState (
    val groupName: String,
    val groupDescription: String,
    val members: Set<Contact>
)

@Composable
fun CreateGroup(
    viewState: ViewState,
    createGroupState: CreateGroupState,
    onCreate: (CreateGroupState) -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {

    var name by remember { mutableStateOf(createGroupState.groupName) }
    var description by remember { mutableStateOf(createGroupState.groupDescription) }
    var members by remember { mutableStateOf(createGroupState.members) }

    val scrollState = rememberScrollState()
    val lazyState = rememberLazyListState()

    val onDeleteMember = { contact: Contact ->
        members -= contact
    }

    Box {
        Column(
            modifier
                .fillMaxWidth()) {
            LazyColumn(state = lazyState) {
                // Top bar
                item {
                    Column(modifier.fillMaxWidth()) {
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
                    }
                }
                // Group list
                memberList(contacts = members.toList(), modifier = Modifier.padding(vertical = 8.dp, horizontal = 24.dp), onDeleteMember)
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

@Preview
@Composable
fun ClosedGroupPreview(
    @PreviewParameter(ThemeResPreviewParameterProvider::class) themeResId: Int
) {
    val random = "05abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234"
    val previewMembers = setOf(
        Contact(random).apply {
            name = "Person"
        }
    )
    PreviewTheme(themeResId) {
        CreateGroup(
            viewState = ViewState(false, null),
            createGroupState = CreateGroupState("Group Name", "Test Group Description", previewMembers),
            onCreate = {},
            onClose = {},
            onBack = {},
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