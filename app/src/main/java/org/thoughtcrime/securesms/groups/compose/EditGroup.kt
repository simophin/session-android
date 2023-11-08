package org.thoughtcrime.securesms.groups.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.cash.molecule.RecompositionMode
import app.cash.molecule.RecompositionMode.Immediate
import app.cash.molecule.launchMolecule
import com.google.android.gms.auth.api.signin.internal.Storage
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import org.session.libsession.database.StorageProtocol
import javax.inject.Inject

@EditGroupNavGraph(start = true)
@Composable
@Destination
fun EditClosedGroupScreen(
    navigator: DestinationsNavigator,
    viewModel: EditGroupViewModel
) {

}



@HiltViewModel
class EditGroupViewModel @Inject constructor(private val groupSessionId: String,
                                             private val storage: StorageProtocol): ViewModel() {

    val viewState = viewModelScope.launchMolecule(Immediate) {

        val closedGroup = remember {
//            storage.getLibSessionClosedGroup()
        }

    }

}

data class EditGroupState(
    val viewState: EditGroupViewState,
    val eventSink: (Unit)->Unit
)

sealed class EditGroupViewState {
    data object NoOp: EditGroupViewState()
}