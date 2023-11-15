package org.thoughtcrime.securesms.groups.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.rememberScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.CenterVertically
import androidx.compose.ui.Modifier
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
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import network.loki.messenger.R
import network.loki.messenger.libsession_util.util.GroupMember
import org.session.libsession.database.StorageProtocol
import org.thoughtcrime.securesms.ui.Divider
import org.thoughtcrime.securesms.ui.NavigationBar
import org.thoughtcrime.securesms.ui.PreviewTheme

@EditGroupNavGraph(start = true)
@Composable
@Destination
fun EditClosedGroupScreen(
    navigator: DestinationsNavigator,
    viewModel: EditGroupViewModel,
    onFinish: () -> Unit
) {

    val group by viewModel.viewState.collectAsState()
    val viewState = group.viewState
    val eventSink = group.eventSink

    EditGroupView(
        onBack = {
            onFinish()
        },
        viewState = viewState as EditGroupViewState.Group
    )

}


class EditGroupViewModel @AssistedInject constructor(
    @Assisted private val groupSessionId: String,
    private val storage: StorageProtocol): ViewModel() {

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
            EditGroupViewState.Group(
                groupName = name,
                groupDescription = description,
                memberStateList = closedGroupMembers,
                admin = closedGroup.isUserAdmin
            )
        ) { event ->

        }
    }

    @AssistedFactory
    interface Factory {
        fun create(groupSessionId: String): EditGroupViewModel
    }

}

@Composable
fun EditGroupView(
    onBack: ()->Unit,
    viewState: EditGroupViewState.Group,
) {
    val scaffoldState = rememberScaffoldState()

    Scaffold(
        scaffoldState = scaffoldState,
        topBar = {
            NavigationBar(
                title = stringResource(id = R.string.activity_edit_closed_group_title),
                onBack = onBack
            ) {
                Text(
                    text = stringResource(id = R.string.menu_done_button),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(2.dp)
                        .align(Alignment.Center),
                    textAlign = TextAlign.Center,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues)) {
            // Group name title
            Text(
                text = viewState.groupName,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            // members header
            Divider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
            ) {
                Text(
                    text = stringResource(id = R.string.activity_edit_closed_group_edit_members),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 24.dp)
                        .align(CenterVertically),
                )
                // if admin add member outline button TODO
                if (viewState.admin) {
                    OutlinedButton(onClick = { /*TODO*/ }) {
                        Text(
                            text = stringResource(id = R.string.activity_edit_closed_group_add_members),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }
            Divider()
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
    val eventSink: (Unit)->Unit
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

sealed class EditGroupViewState {
    data class Group(
        val groupName: String,
        val groupDescription: String?,
        val memberStateList: List<MemberViewModel>,
        val admin: Boolean
    ): EditGroupViewState()
}

@Preview
@Composable
fun PreviewList() {

    val oneMember = MemberViewModel(
        "Test User",
        "05abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234",
        MemberState.InviteSent,
        false
    )

    val viewState = EditGroupViewState.Group(
        "Preview",
        "This is a preview description",
        listOf(oneMember),
        false
    )

    PreviewTheme(themeResId = R.style.Ocean_Dark) {
        EditGroupView(onBack = { /*TODO*/ }, viewState =viewState)
    }
}