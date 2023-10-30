package org.thoughtcrime.securesms.groups.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import network.loki.messenger.R
import org.session.libsession.messaging.contacts.Contact
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.recipients.Recipient
import org.thoughtcrime.securesms.ui.Avatar
import org.thoughtcrime.securesms.ui.LocalPreviewMode


@Composable
fun EmptyPlaceholder(modifier: Modifier = Modifier) {
    Column {
        Text(
            text = stringResource(id = R.string.conversation_settings_group_members),
            modifier = Modifier
                .align(Alignment.Start)
                .padding(vertical = 8.dp)
        )
        // TODO group list representation
        Text(
            text = stringResource(id = R.string.activity_create_closed_group_not_enough_group_members_error),
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(vertical = 8.dp)
        )
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
fun LazyListScope.memberList(
    contacts: List<Contact>,
    modifier: Modifier = Modifier,
    onDelete: (Contact) -> Unit
) {
    if (contacts.isEmpty()) {
        item {
            EmptyPlaceholder(modifier.fillParentMaxWidth())
        }
    } else {
        items(contacts) { contact ->
            Row(modifier) {
                ContactPhoto(contact, modifier = Modifier.size(48.dp))
            }
        }
    }
}


@Composable
fun RowScope.ContactPhoto(contact: Contact, modifier: Modifier = Modifier) {
    return if (LocalPreviewMode.current) {
        Image(
            painterResource(id = R.drawable.ic_profile_default),
            colorFilter = ColorFilter.tint(MaterialTheme.colors.onPrimary),
            contentScale = ContentScale.Inside,
            contentDescription = null,
            modifier = modifier
                .size(48.dp)
                .clip(CircleShape)
                .border(1.dp, MaterialTheme.colors.onPrimary, CircleShape)
        )
    } else {
        val context = LocalContext.current
        val recipient = remember(contact) {
            Recipient.from(context, Address.fromSerialized(contact.sessionID), false)
        }
        Avatar(recipient)
    }
}