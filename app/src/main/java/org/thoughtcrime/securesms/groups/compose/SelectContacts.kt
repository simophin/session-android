package org.thoughtcrime.securesms.groups.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import network.loki.messenger.R
import org.session.libsession.messaging.contacts.Contact
import org.thoughtcrime.securesms.home.search.getSearchName
import org.thoughtcrime.securesms.ui.NavigationBar
import org.thoughtcrime.securesms.ui.SearchBar

@Composable
fun SelectContacts(
    contactListState: List<Contact>,
    onBack: ()->Unit,
    onClose: ()->Unit,
) {

    var queryFilter by remember { mutableStateOf("") }

    // May introduce more advanced filters
    val filtered = if (queryFilter.isEmpty()) contactListState
        else {
            contactListState
            .filter { contact ->
                contact.getSearchName()
                    .contains(queryFilter)
            }
        }

    Column {
        NavigationBar(
            title = stringResource(id = R.string.activity_create_closed_group_select_contacts),
            onBack = onBack,
            onClose = onClose
        )

        LazyColumn {
            item {
                // Search Bar
                SearchBar(queryFilter, onValueChanged = { value -> queryFilter = value })
            }
        }
    }

}

