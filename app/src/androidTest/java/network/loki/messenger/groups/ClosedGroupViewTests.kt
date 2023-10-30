package network.loki.messenger.groups

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import junit.framework.TestCase.assertNotNull
import network.loki.messenger.libsession_util.util.Sodium
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.utilities.Hex
import org.session.libsignal.utilities.IdPrefix
import org.session.libsignal.utilities.SessionId
import org.thoughtcrime.securesms.database.ConfigDatabase
import org.thoughtcrime.securesms.database.Storage
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.groups.CreateGroupViewModel
import org.thoughtcrime.securesms.groups.compose.CreateGroupState

@RunWith(MockitoJUnitRunner::class)
class ClosedGroupViewTests {

    companion object {
        private const val OTHER_ID = "051000000000000000000000000000000000000000000000000000000000000000"
    }

    private val seed =
        Hex.fromStringCondensed("0123456789abcdef0123456789abcdef00000000000000000000000000000000")
    private val keyPair = Sodium.ed25519KeyPair(seed)
    private val userSessionId = SessionId(IdPrefix.STANDARD, Sodium.ed25519PkToCurve25519(keyPair.pubKey))

    @Mock lateinit var textSecurePreferences: TextSecurePreferences
    lateinit var storage: Storage

    @Before
    fun setup() {
        val applicationContext = InstrumentationRegistry.getInstrumentation().targetContext
        whenever(textSecurePreferences.getLocalNumber()).thenReturn(userSessionId.hexString())
        val context = mock<Context>()
        val emptyDb = mock<ConfigDatabase> { db ->
            whenever(db.retrieveConfigAndHashes(any(), any())).thenReturn(byteArrayOf())
        }
        val overriddenStorage = Storage(applicationContext, mock(), ConfigFactory(context, emptyDb) {
            keyPair.secretKey to userSessionId.hexString()
        }, mock())
        storage = overriddenStorage
    }

    @Test
    fun tryCreateGroup_shouldErrorOnEmptyName() {
        val viewModel = createViewModel()
        val state = CreateGroupState(
            groupName = "",
            groupDescription = "",
            members = emptySet()
        )
        viewModel.tryCreateGroup(state)
        assertNotNull(viewModel.viewState.value?.error)
    }

    @Test
    fun tryCreateGroup_shouldErrorOnEmptyMembers() {
        val viewModel = createViewModel()
        val state = CreateGroupState(
            groupName = "group",
            groupDescription = "anything",
            members = emptySet()
        )
        viewModel.tryCreateGroup(state)
        assertNotNull(viewModel.viewState.value?.error)
    }

    @Test
    fun tryCreateGroup_shouldSucceedWithCorrectParameters() {
        val viewModel = createViewModel()
        val state = CreateGroupState(
            groupName = "group",
            groupDescription = "",
            members = emptySet()
        )
        assertNotNull(viewModel.tryCreateGroup(state))
    }

    private fun createViewModel() = CreateGroupViewModel(textSecurePreferences, storage)

}