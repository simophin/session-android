package org.thoughtcrime.securesms.conversation.v2.utilities

import android.content.ContentResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext
import org.session.libsession.messaging.mentions.MentionCandidate
import org.thoughtcrime.securesms.database.DatabaseContentProviders
import org.thoughtcrime.securesms.database.GroupDatabase
import org.thoughtcrime.securesms.database.MmsSmsDatabase
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.util.observeChanges
import javax.inject.Inject

/**
 * A repository for managing mentions in a conversation.
 */
class MentionRepository @Inject constructor(
    private val smsDb: MmsSmsDatabase,
    private val groupDb: GroupDatabase,
    private val threadDb: ThreadDatabase,
    private val contentResolver: ContentResolver,
) {
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    fun observeThreadMemberPublicKeys(threadId: Long): Flow<List<String>> {
        return merge(
            contentResolver.observeChanges(DatabaseContentProviders.Conversation.getUriForThread(threadId), true),
            contentResolver.observeChanges(DatabaseContentProviders.Recipient.CONTENT_URI, true),
        ).debounce(500L)
            .flowOn(Dispatchers.IO)
            .mapLatest {
                val recipient = checkNotNull(threadDb.getRecipientForThreadId(threadId)) {
                    "No recipient found for threadId: $threadId"
                }

                if (recipient.address.isClosedGroup) {
                    groupDb.getGroupMembers(recipient.address.toGroupString(), false)
                        .map { it.address.serialize() }
                } else {
                    smsDb.getParticipantPublicKeysForThread(threadId)
                }
            }
    }
}
