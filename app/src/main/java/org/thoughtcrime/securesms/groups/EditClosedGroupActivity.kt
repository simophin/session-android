package org.thoughtcrime.securesms.groups

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ramcosta.composedestinations.DestinationsNavHost
import com.ramcosta.composedestinations.navigation.dependency
import dagger.hilt.android.AndroidEntryPoint
import org.thoughtcrime.securesms.PassphraseRequiredActionBarActivity
import org.thoughtcrime.securesms.groups.compose.EditGroupViewModel
import org.thoughtcrime.securesms.ui.AppTheme

@AndroidEntryPoint
class EditClosedGroupActivity: PassphraseRequiredActionBarActivity() {

    override fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {
        setContent {
            AppTheme {
                DestinationsNavHost(
                    navGraph = NavGraphs.editGroup,
                    dependenciesContainerBuilder = {
                        dependency(NavGraphs.editGroup) {
                            EditGroupViewModel.Factory()
                        }
                    }
                )
            }
        }
    }
}