package network.loki.messenger

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescriptionExactly
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.thoughtcrime.securesms.ApplicationContext
import org.thoughtcrime.securesms.groups.compose.EditGroupView
import org.thoughtcrime.securesms.groups.compose.EditGroupViewState
import org.thoughtcrime.securesms.groups.compose.MemberState
import org.thoughtcrime.securesms.groups.compose.MemberViewModel
import org.thoughtcrime.securesms.ui.AppTheme

@RunWith(AndroidJUnit4::class)
@SmallTest
class EditGroupTests {

    @get:Rule
    val composeTest = createComposeRule()

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

    @Test
    fun testDisplaysNameAndDesc() {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ApplicationContext
        // Accessibility IDs
        val nameDesc = application.getString(R.string.AccessibilityId_group_name)
        val descriptionDesc = application.getString(R.string.AccessibilityId_group_description)

        val state = EditGroupViewState(
            "TestGroup",
            "TestDesc",
            emptyList(),
            false
        )

        composeTest.setContent {
            AppTheme {
                EditGroupView(
                    onBack = {},
                    onInvite = {},
                    onReinvite = {},
                    onPromote = {},
                    onRemove = {},
                    onEditName = {},
                    onMemberSelected = {},
                    viewState = state
                )
            }
        }

        with(composeTest) {
            onNode(hasContentDescriptionExactly(nameDesc)).assertTextEquals("TestGroup")
            onNode(hasContentDescriptionExactly(descriptionDesc)).assertTextEquals("TestDesc")
        }

    }

}