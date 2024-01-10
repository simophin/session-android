package org.thoughtcrime.securesms.groups.compose

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.rememberScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.CenterVertically
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.cash.molecule.RecompositionMode.Immediate
import app.cash.molecule.launchMolecule
import com.google.android.material.color.MaterialColors
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import com.ramcosta.composedestinations.result.NavResult
import com.ramcosta.composedestinations.result.ResultBackNavigator
import com.ramcosta.composedestinations.result.ResultRecipient
import com.ramcosta.composedestinations.spec.DestinationStyle
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import network.loki.messenger.R
import network.loki.messenger.libsession_util.util.GroupMember
import org.session.libsession.database.StorageProtocol
import org.session.libsession.messaging.contacts.Contact
import org.session.libsession.messaging.jobs.InviteContactsJob
import org.session.libsession.messaging.jobs.JobQueue
import org.session.libsignal.utilities.SessionId
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.groups.ContactList
import org.thoughtcrime.securesms.groups.destinations.EditClosedGroupInviteScreenDestination
import org.thoughtcrime.securesms.groups.destinations.EditClosedGroupNameScreenDestination
import org.thoughtcrime.securesms.ui.CellWithPaddingAndMargin
import org.thoughtcrime.securesms.ui.LocalExtraColors
import org.thoughtcrime.securesms.ui.NavigationBar
import org.thoughtcrime.securesms.ui.PreviewTheme
import org.thoughtcrime.securesms.ui.ThemeResPreviewParameterProvider

@EditGroupNavGraph(start = true)
@Composable
@Destination
fun EditClosedGroupScreen(
    navigator: DestinationsNavigator,
    resultSelectContact: ResultRecipient<EditClosedGroupInviteScreenDestination, ContactList>,
    resultEditName: ResultRecipient<EditClosedGroupNameScreenDestination, String>,
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

    resultEditName.onNavResult { navResult ->
        if (navResult is NavResult.Value) {
            eventSink(EditGroupEvent.ChangeName(navResult.value))
        }
    }

    EditGroupView(
        onBack = {
            onFinish()
        },
        onInvite = {
            navigator.navigate(EditClosedGroupInviteScreenDestination)
        },
        onReinvite = { contact ->
            eventSink(EditGroupEvent.ReInviteContact(contact))
        },
        onPromote = { contact ->
            eventSink(EditGroupEvent.PromoteContact(contact))
        },
        onRemove = { contact ->
            eventSink(EditGroupEvent.RemoveContact(contact))
        },
        onEditName = {
            navigator.navigate(EditClosedGroupNameScreenDestination)
        },
        viewState = viewState
    )
}

@EditGroupNavGraph
@Composable
@Destination(style = DestinationStyle.Dialog::class)
fun EditClosedGroupNameScreen(
    resultNavigator: ResultBackNavigator<String>,
    viewModel: EditGroupViewModel
) {
    EditClosedGroupView { name ->
        if (name.isEmpty()) {
            resultNavigator.navigateBack()
        } else {
            resultNavigator.navigateBack(name)
        }
    }
}

@Composable
fun EditClosedGroupView(navigateBack: (String) -> Unit) {

    var newName by remember {
        mutableStateOf("")
    }

    var newDescription by remember {
        mutableStateOf("")
    }

    Box(
        Modifier
            .fillMaxWidth()
            .shadow(8.dp)
            .background(MaterialTheme.colors.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(id = R.string.dialog_edit_group_information_title),
                modifier = Modifier.padding(bottom = 8.dp),
                style = MaterialTheme.typography.subtitle1,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(id = R.string.dialog_edit_group_information_message),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                maxLines = 1,
                singleLine = true,
                placeholder = {
                    Text(
                        text = stringResource(id = R.string.dialog_edit_group_information_enter_group_name)
                    )
                }
            )
            OutlinedTextField(
                value = newDescription,
                onValueChange = { newDescription = it },
                modifier = Modifier
                    .fillMaxWidth(),
                minLines = 2,
                maxLines = 2,
                placeholder = {
                    Text(
                        text = stringResource(id = R.string.dialog_edit_group_information_enter_group_description)
                    )
                }
            )
            Row(modifier = Modifier
                .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.save),
                    modifier = Modifier
                        .padding(16.dp)
                        .weight(1f)
                        .clickable {
                            navigateBack(newName)
                        },
                    textAlign = TextAlign.Center,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.cancel),
                    modifier = Modifier
                        .padding(16.dp)
                        .weight(1f)
                        .clickable {
                            navigateBack("")
                        },
                    textAlign = TextAlign.Center,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = LocalExtraColors.current.destructive
                )
            }
        }
    }
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
    private val configFactory: ConfigFactory
): ViewModel() {

    val viewState = viewModelScope.launchMolecule(Immediate) {

        val currentUserId = rememberSaveable {
            storage.getUserPublicKey()!!
        }

        val closedGroup by configFactory.configUpdateNotifications.map(SessionId::hexString).filter { it == groupSessionId }
            .map {
                storage.getClosedGroupDisplayInfo(it)!!
            }.collectAsState(initial = storage.getClosedGroupDisplayInfo(groupSessionId)!!)

        val closedGroupMembers =
            storage.getMembers(groupSessionId).map { member ->
                MemberViewModel(
                    memberName = member.name,
                    memberSessionId = member.sessionId,
                    currentUser = member.sessionId == currentUserId,
                    memberState = memberStateOf(member)
                )
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
                }
                is EditGroupEvent.ReInviteContact -> {
                    // do a buffer
                    JobQueue.shared.add(InviteContactsJob(groupSessionId, arrayOf(event.contactSessionId)))
                }
                is EditGroupEvent.PromoteContact -> {
                    // do a buffer
                    storage.promoteMember(groupSessionId, arrayOf(event.contactSessionId))
                }
                is EditGroupEvent.RemoveContact -> {
                    storage.removeMember(groupSessionId, arrayOf(event.contactSessionId))
                }
                is EditGroupEvent.ChangeName -> {
                    storage.setName(groupSessionId, event.newName)
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
    onReinvite: (String)->Unit,
    onPromote: (String)->Unit,
    onRemove: (String)->Unit,
    onEditName: ()->Unit,
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = viewState.groupName,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                if (viewState.admin) {
                    Icon(
                        painterResource(R.drawable.ic_baseline_edit_24),
                        null,
                        modifier = Modifier
                            .padding(8.dp)
                            .size(16.dp)
                            .clip(CircleShape)
                            .align(CenterVertically)
                            .clickable { onEditName() }
                    )
                }
            }
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
                        // Resend button
                        if (viewState.admin && member.memberState == MemberState.InviteFailed) {
                            TextButton(
                                onClick = {
                                    onReinvite(member.memberSessionId)
                                },
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(
                                        Color(
                                            MaterialColors.getColor(
                                                LocalContext.current,
                                                R.attr.colorControlHighlight,
                                                MaterialTheme.colors.onPrimary.toArgb()
                                            )
                                        )
                                    )
                            ) {
                                Text(
                                    "Re-send",
                                    color = MaterialTheme.colors.onPrimary
                                )
                            }
                        } else if (viewState.admin && member.memberState == MemberState.Member) {
                            TextButton(
                                onClick = {
                                    onPromote(member.memberSessionId)
                                },
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(
                                        Color(
                                            MaterialColors.getColor(
                                                LocalContext.current,
                                                R.attr.colorControlHighlight,
                                                MaterialTheme.colors.onPrimary.toArgb()
                                            )
                                        )
                                    )
                            ) {
                                Text(
                                    "Promote",
                                    color = MaterialTheme.colors.onPrimary
                                )
                            }
                            TextButton(
                                onClick = {
                                    onRemove(member.memberSessionId)
                                },
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(
                                        Color(
                                            MaterialColors.getColor(
                                                LocalContext.current,
                                                R.attr.colorControlHighlight,
                                                MaterialTheme.colors.onPrimary.toArgb()
                                            )
                                        )
                                    )
                            ) {
                                Icon(painter = painterResource(id = R.drawable.ic_baseline_close_24), contentDescription = null)
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
    member.inviteFailed -> MemberState.InviteFailed
    member.invitePending -> MemberState.InviteSent
    member.promotionFailed -> MemberState.PromotionFailed
    member.promotionPending -> MemberState.PromotionSent
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
    data class ReInviteContact(val contactSessionId: String): EditGroupEvent()
    data class PromoteContact(val contactSessionId: String): EditGroupEvent()
    data class RemoveContact(val contactSessionId: String): EditGroupEvent()
    data class ChangeName(val newName: String): EditGroupEvent()
}

data class EditGroupInviteViewState(
    val currentMembers: List<MemberViewModel>,
    val allContacts: Set<Contact>
)

@Preview
@Composable
fun PreviewDialogChange(@PreviewParameter(ThemeResPreviewParameterProvider::class) styleRes: Int) {

    PreviewTheme(themeResId = styleRes) {
        EditClosedGroupView {

        }
    }

}

@Preview
@Composable
fun PreviewList() {

    PreviewTheme(themeResId = R.style.Classic_Dark) {

        val oneMember = MemberViewModel(
            "Test User",
            "05abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234",
            MemberState.InviteSent,
            false
        )
        val twoMember = MemberViewModel(
            "Test User 2",
            "05abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1235",
            MemberState.InviteFailed,
            false
        )
        val threeMember = MemberViewModel(
            "Test User 3",
            "05abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1236",
            MemberState.Member,
            false
        )

        val viewState = EditGroupViewState(
            "Preview",
            "This is a preview description",
            listOf(oneMember, twoMember, threeMember),
            true
        )

        EditGroupView(
            onBack = {},
            onInvite = {},
            onReinvite = {},
            onPromote = {},
            onRemove = {},
            onEditName = {},
            viewState = viewState
        )
    }
}