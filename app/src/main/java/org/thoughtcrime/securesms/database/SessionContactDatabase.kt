package org.thoughtcrime.securesms.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import androidx.core.database.getLongOrNull
import androidx.core.database.getStringOrNull
import org.session.libsession.messaging.contacts.Contact
import org.session.libsession.messaging.utilities.SessionId
import org.session.libsignal.utilities.Base64
import org.session.libsignal.utilities.IdPrefix
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper

class SessionContactDatabase(context: Context, helper: SQLCipherOpenHelper) : Database(context, helper) {

    companion object {
        private const val sessionContactTable = "session_contact_database"
        const val sessionID = "session_id"
        const val name = "name"
        const val nickname = "nickname"
        const val profilePictureURL = "profile_picture_url"
        const val profilePictureFileName = "profile_picture_file_name"
        const val profilePictureEncryptionKey = "profile_picture_encryption_key"
        const val threadID = "thread_id"
        const val isTrusted = "is_trusted"
        @JvmStatic val createSessionContactTableCommand =
            "CREATE TABLE $sessionContactTable " +
                "($sessionID STRING PRIMARY KEY, " +
                "$name TEXT DEFAULT NULL, " +
                "$nickname TEXT DEFAULT NULL, " +
                "$profilePictureURL TEXT DEFAULT NULL, " +
                "$profilePictureFileName TEXT DEFAULT NULL, " +
                "$profilePictureEncryptionKey BLOB DEFAULT NULL, " +
                "$threadID INTEGER DEFAULT -1, " +
                "$isTrusted INTEGER DEFAULT 0);"
    }

    fun getContactWithSessionID(sessionID: String): Contact? {
        val database = databaseHelper.readableDatabase
        return database.get(sessionContactTable, "${Companion.sessionID} = ?", arrayOf( sessionID )) { cursor ->
            contactFromCursor(cursor)
        }
    }

    fun getAllContacts(): Set<Contact> {
        val database = databaseHelper.readableDatabase
        return database.getAll(sessionContactTable, null, null) { cursor ->
            contactFromCursor(cursor)
        }.filter { contact ->
            val sessionId = SessionId(contact.sessionID)
            sessionId.prefix == IdPrefix.STANDARD
        }.toSet()
    }

    fun setContactIsTrusted(contact: Contact, isTrusted: Boolean, threadID: Long) {
        val database = databaseHelper.writableDatabase
        val contentValues = ContentValues(1)
        contentValues.put(Companion.isTrusted, if (isTrusted) 1 else 0)
        database.update(sessionContactTable, contentValues, "$sessionID = ?", arrayOf( contact.sessionID ))
        if (threadID >= 0) {
            notifyConversationListeners(threadID)
        }
        notifyConversationListListeners()
    }

    fun setContact(contact: Contact) {
        val database = databaseHelper.writableDatabase
        val contentValues = ContentValues(8)
        contentValues.put(sessionID, contact.sessionID)
        contentValues.put(name, contact.name)
        contentValues.put(nickname, contact.nickname)
        contentValues.put(profilePictureURL, contact.profilePictureURL)
        contentValues.put(profilePictureFileName, contact.profilePictureFileName)
        contact.profilePictureEncryptionKey?.let {
            contentValues.put(profilePictureEncryptionKey, Base64.encodeBytes(it))
        }
        contentValues.put(threadID, contact.threadID)
        contentValues.put(isTrusted, if (contact.isTrusted) 1 else 0)
        database.insertOrUpdate(sessionContactTable, contentValues, "$sessionID = ?", arrayOf( contact.sessionID ))
        notifyConversationListListeners()
    }

    fun contactFromCursor(cursor: Cursor): Contact {
        val sessionID = cursor.getString(cursor.getColumnIndexOrThrow(sessionID))
        val contact = Contact(sessionID)
        contact.name = cursor.getStringOrNull(cursor.getColumnIndexOrThrow(name))
        contact.nickname = cursor.getStringOrNull(cursor.getColumnIndexOrThrow(nickname))
        contact.profilePictureURL = cursor.getStringOrNull(cursor.getColumnIndexOrThrow(profilePictureURL))
        contact.profilePictureFileName = cursor.getStringOrNull(cursor.getColumnIndexOrThrow(profilePictureFileName))
        cursor.getStringOrNull(cursor.getColumnIndexOrThrow(profilePictureEncryptionKey))?.let {
            contact.profilePictureEncryptionKey = Base64.decode(it)
        }
        contact.threadID = cursor.getLongOrNull(cursor.getColumnIndexOrThrow(threadID))
        contact.isTrusted = cursor.getInt(cursor.getColumnIndexOrThrow(isTrusted)) != 0
        return contact
    }

    fun queryContactsByName(constraint: String): Cursor {
        return databaseHelper.readableDatabase.query(
            sessionContactTable, null, " $name LIKE ? OR $nickname LIKE ?", arrayOf(
                "%$constraint%",
                "%$constraint%"
            ),
            null, null, null
        )
    }
}