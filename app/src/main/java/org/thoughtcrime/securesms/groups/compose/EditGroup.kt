package org.thoughtcrime.securesms.groups.compose

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.rememberScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment.Companion.CenterVertically
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.cash.molecule.RecompositionMode.Immediate
import app.cash.molecule.launchMolecule
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import com.ramcosta.composedestinations.result.NavResult
import com.ramcosta.composedestinations.result.ResultBackNavigator
import com.ramcosta.composedestinations.result.ResultRecipient
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import network.loki.messenger.R
import network.loki.messenger.libsession_util.util.GroupMember
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.contacts.Contact
import org.thoughtcrime.securesms.groups.ContactList
import org.thoughtcrime.securesms.groups.destinations.EditClosedGroupInviteScreenDestination
import org.thoughtcrime.securesms.ui.CellWithPaddingAndMargin
import org.thoughtcrime.securesms.ui.NavigationBar
import org.thoughtcrime.securesms.ui.PreviewTheme

@EditGroupNavGraph(start = true)
@Composable
@Destination
fun EditClosedGroupScreen(
    navigator: DestinationsNavigator,
    resultSelectContact: ResultRecipient<EditClosedGroupInviteScreenDestination, ContactList>,
    viewModel: EditGroupViewModel,
    onFinish: () -> Unit
) {
    val group by viewModel.viewState.collectAsState()
    val context = LocalContext.current
    val viewState = group.viewState
    val eventSink = group.eventSink

    resultSelectContact.onNavResult { navResult ->
        if (navResult is NavResult.Value) {
            eventSink(EditGroupEvent.InviteContacts(context, navResult.value))
        }
    }

    EditGroupView(
        onBack = {
            onFinish()
        },
        onInvite = {
            navigator.navigate(EditClosedGroupInviteScreenDestination)
        },
        viewState = viewState
    )
}

@EditGroupNavGraph
@Composable
@Destination
fun EditClosedGroupInviteScreen(
    resultNavigator: ResultBackNavigator<ContactList>,
    viewModel: EditGroupInviteViewModel,
) {

    val state by viewModel.viewState.collectAsState()
    val viewState = state.viewState
    val currentMemberSessionIds = viewState.currentMembers.map { it.memberSessionId }

    SelectContacts(
        viewState.allContacts
            .filterNot { it.sessionID in currentMemberSessionIds }
            .toSet(),
        onBack = { resultNavigator.navigateBack() },
        onContactsSelected = {
            resultNavigator.navigateBack(ContactList(it))
        },
    )
}


class EditGroupViewModel @AssistedInject constructor(
    @Assisted private val groupSessionId: String,
    private val storage: StorageProtocol,
): ViewModel() {

    val viewState = viewModelScope.launchMolecule(Immediate) {

        val currentUserId = rememberSaveable {
            storage.getUserPublicKey()!!
        }

        val closedGroupInfo = remember {
            storage.getLibSessionClosedGroup(groupSessionId)!!
        }

        val closedGroup = remember(closedGroupInfo) {
            storage.getClosedGroupDisplayInfo(groupSessionId)!!
        }

        val closedGroupMembers = remember(closedGroupInfo) {
            storage.getMembers(groupSessionId).map { member ->
                MemberViewModel(
                    memberName = member.name,
                    memberSessionId = member.sessionId,
                    currentUser = member.sessionId == currentUserId,
                    memberState = memberStateOf(member)
                )
            }
        }

        val name = closedGroup.name
        val description = closedGroup.description

        EditGroupState(
            EditGroupViewState(
                groupName = name,
                groupDescription = description,
                memberStateList = closedGroupMembers,
                admin = closedGroup.isUserAdmin
            )
        ) { event ->
            when (event) {
                is EditGroupEvent.InviteContacts -> {
                    val sessionIds = event.contacts
                    storage.inviteClosedGroupMembers(
                        groupSessionId,
                        sessionIds.contacts.map(Contact::sessionID)
                    )
                    Toast.makeText(
                        event.context,
                        "Inviting ${event.contacts.contacts.size}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(groupSessionId: String): EditGroupViewModel
    }

}

class EditGroupInviteViewModel @AssistedInject constructor(
    @Assisted private val groupSessionId: String,
    private val storage: StorageProtocol
): ViewModel() {

    val viewState = viewModelScope.launchMolecule(Immediate) {

        val currentUserId = rememberSaveable {
            storage.getUserPublicKey()!!
        }

        val contacts = remember {
            storage.getAllContacts()
        }

        val closedGroupMembers = remember {
            storage.getMembers(groupSessionId).map { member ->
                MemberViewModel(
                    memberName = member.name,
                    memberSessionId = member.sessionId,
                    currentUser = member.sessionId == currentUserId,
                    memberState = memberStateOf(member)
                )
            }
        }

        EditGroupInviteState(
            EditGroupInviteViewState(closedGroupMembers, contacts)
        )
    }

    @AssistedFactory
    interface Factory {
        fun create(groupSessionId: String): EditGroupInviteViewModel
    }

}

@Composable
fun EditGroupView(
    onBack: ()->Unit,
    onInvite: ()->Unit,
    viewState: EditGroupViewState,
) {
    val scaffoldState = rememberScaffoldState()

    Scaffold(
        scaffoldState = scaffoldState,
        topBar = {
            NavigationBar(
                title = stringResource(id = R.string.activity_edit_closed_group_title),
                onBack = onBack,
                actionElement = {}
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues)) {
            // Group name title
            Text(
                text = viewState.groupName,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            // Description

            // Invite
            if (viewState.admin) {
                CellWithPaddingAndMargin(margin = 16.dp, padding = 16.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onInvite)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = CenterVertically,
                    ) {
                        Icon(painterResource(id = R.drawable.ic_add_admins), contentDescription = null)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(text = stringResource(id = R.string.activity_edit_closed_group_add_members))
                    }
                }
            }
            // members header
            Text(
                text = stringResource(id = R.string.conversation_settings_group_members),
                style = MaterialTheme.typography.subtitle2,
                modifier = Modifier
                    .padding(vertical = 8.dp, horizontal = 32.dp)
            )
            LazyColumn(modifier = Modifier) {

                items(viewState.memberStateList) { member ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp, horizontal = 16.dp)) {
                        ContactPhoto(member.memberSessionId)
                        Column(modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(horizontal = 8.dp)
                            .align(CenterVertically)) {
                            // Member's name
                            Text(
                                text = member.memberName ?: member.memberSessionId,
                                style = MemberNameStyle,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(1.dp)
                            )
                            if (member.memberState !in listOf(MemberState.Member, MemberState.Admin)) {
                                Text(
                                    text = member.memberState.toString(),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(1.dp)
                                )
                            }
                        }
                    }
                }

            }
        }
    }
}

data class EditGroupState(
    val viewState: EditGroupViewState,
    val eventSink: (EditGroupEvent) -> Unit
)

data class EditGroupInviteState(
    val viewState: EditGroupInviteViewState,
)

data class MemberViewModel(
    val memberName: String?,
    val memberSessionId: String,
    val memberState: MemberState,
    val currentUser: Boolean,
)

enum class MemberState {
    InviteSent,
    Inviting, // maybe just use these in view
    InviteFailed,
    PromotionSent,
    Promoting, // maybe just use these in view
    PromotionFailed,
    Admin,
    Member
}

fun memberStateOf(member: GroupMember): MemberState = when {
    member.invitePending -> MemberState.InviteSent
    member.inviteFailed -> MemberState.InviteFailed
    member.promotionPending -> MemberState.PromotionSent
    member.promotionFailed -> MemberState.PromotionFailed
    member.admin -> MemberState.Admin
    else -> MemberState.Member
}

data class EditGroupViewState(
    val groupName: String,
    val groupDescription: String?,
    val memberStateList: List<MemberViewModel>,
    val admin: Boolean
)

sealed class EditGroupEvent {
    data class InviteContacts(val context: Context,
                              val contacts: ContactList): EditGroupEvent()
}

data class EditGroupInviteViewState(
    val currentMembers: List<MemberViewModel>,
    val allContacts: Set<Contact>
)

@Preview
@Composable
fun PreviewList() {

    val oneMember = MemberViewModel(
        "Test User",
        "05abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234",
        MemberState.InviteSent,
        false
    )

    val viewState = EditGroupViewState(
        "Preview",
        "This is a preview description",
        listOf(oneMember),
        true
    )

    PreviewTheme(themeResId = R.style.Classic_Dark) {
        EditGroupView(
            onBack = {},
            onInvite = {},
            viewState = viewState
        )
    }
}