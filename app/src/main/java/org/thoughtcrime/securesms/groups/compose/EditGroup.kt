package org.thoughtcrime.securesms.groups.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.cash.molecule.RecompositionMode.Immediate
import app.cash.molecule.launchMolecule
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import org.session.libsession.database.StorageProtocol

@EditGroupNavGraph(start = true)
@Composable
@Destination
fun EditClosedGroupScreen(
    navigator: DestinationsNavigator,
    viewModel: EditGroupViewModel
) {

    val group by viewModel.viewState.collectAsState()
    val viewState = group.viewState
    val eventSink = group.eventSink

    when (viewState) {
        is EditGroupViewState.Display -> {
            Text(
                text = viewState.text,
                modifier = Modifier.fillMaxSize()
                    .clickable {
                        eventSink(Unit)
                    }
            )
        }
        else -> {

        }
    }

}



class EditGroupViewModel @AssistedInject constructor(
    @Assisted private val groupSessionId: String,
    private val storage: StorageProtocol): ViewModel() {

    val viewState = viewModelScope.launchMolecule(Immediate) {

        val closedGroup = remember {
            storage.getLibSessionClosedGroup(groupSessionId)
        }

        var displayText by remember {
            mutableStateOf(closedGroup!!.groupSessionId.hexString())
        }

        EditGroupState(EditGroupViewState.Display(displayText)) { event ->
            when (event) {
                Unit -> displayText = "different"
            }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(groupSessionId: String): EditGroupViewModel
    }

}

data class EditGroupState(
    val viewState: EditGroupViewState,
    val eventSink: (Unit)->Unit
)

sealed class EditGroupViewState {
    data object NoOp: EditGroupViewState()
    data class Display(val text: String) : EditGroupViewState()
}