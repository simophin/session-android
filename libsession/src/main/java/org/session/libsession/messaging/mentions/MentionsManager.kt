package org.session.libsession.messaging.mentions

import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.contacts.Contact
import java.util.Locale

object MentionsManager {
    var userPublicKeyCache = mutableMapOf<Long, Set<String>>() // Thread ID to set of user hex encoded public keys

    fun cache(publicKey: String, threadID: Long) {
        val cache = userPublicKeyCache[threadID]
        if (cache != null) {
            userPublicKeyCache[threadID] = cache.plus(publicKey)
        } else {
            userPublicKeyCache[threadID] = setOf( publicKey )
        }
    }

    fun getMentionCandidates(query: String, threadID: Long, isOpenGroup: Boolean): List<MentionCandidate> {
        val cache = userPublicKeyCache[threadID] ?: return listOf()
        // Prepare
        val context = if (isOpenGroup) Contact.ContactContext.OPEN_GROUP else Contact.ContactContext.REGULAR
        val storage = MessagingModuleConfiguration.shared.storage
        val userPublicKey = storage.getUserPublicKey()
        // Gather candidates
        var candidates: List<MentionCandidate> = cache.mapNotNull { publicKey ->
            val contact = storage.getContactWithSessionID(publicKey)
            val displayName = contact?.displayName(context) ?: return@mapNotNull null
            MentionCandidate(publicKey, displayName)
        }
        candidates = candidates.filter { it.publicKey != userPublicKey }
        // Sort alphabetically first
        candidates.sortedBy { it.displayName }
        if (query.length >= 2) {
            // Filter out any non-matching candidates
            candidates = candidates.filter { it.displayName.lowercase(Locale.getDefault()).contains(query.lowercase(Locale.getDefault())) }
            // Sort based on where in the candidate the query occurs
            candidates.sortedBy { it.displayName.lowercase(Locale.getDefault()).indexOf(query.lowercase(Locale.getDefault())) }
        }
        // Return
        return candidates
    }
}
