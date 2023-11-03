package org.thoughtcrime.securesms.groups.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import network.loki.messenger.R
import org.session.libsession.messaging.contacts.Contact
import org.thoughtcrime.securesms.home.search.getSearchName
import org.thoughtcrime.securesms.ui.NavigationBar
import org.thoughtcrime.securesms.ui.SearchBar

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SelectContacts(
    contactListState: Set<Contact>,
    onBack: ()->Unit,
    onClose: ()->Unit,
    onContactsSelected: (Set<Contact>) -> Unit,
) {

    var queryFilter by remember { mutableStateOf("") }

    // May introduce more advanced filters
    val filtered = if (queryFilter.isEmpty()) contactListState.toList()
        else {
            contactListState
            .filter { contact ->
                contact.getSearchName().lowercase()
                    .contains(queryFilter)
            }
                .toList()
        }

    var selected by remember {
        mutableStateOf(emptySet<Contact>())
    }

    Column {
        NavigationBar(
            title = stringResource(id = R.string.activity_create_closed_group_select_contacts),
            onBack = onBack,
            onClose = onClose
        )

        LazyColumn(modifier = Modifier.weight(1f)) {
            stickyHeader {
                // Search Bar
                SearchBar(queryFilter, onValueChanged = { value -> queryFilter = value })
            }

            multiSelectMemberList(
                contacts = filtered.toList(),
                selectedContacts = selected,
                onListUpdated = { selected = it },
            )
        }
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { onContactsSelected(selected) },
                shape = CircleShape,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Text(stringResource(id = R.string.ok))
            }
        }
    }

}

