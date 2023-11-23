package org.session.libsession.messaging.utilities

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.core.JsonParseException
import org.session.libsession.messaging.messages.control.GroupUpdated
import org.session.libsignal.messages.SignalServiceGroup
import org.session.libsignal.protos.SignalServiceProtos.DataMessage.GroupUpdateInfoChangeMessage
import org.session.libsignal.protos.SignalServiceProtos.DataMessage.GroupUpdateMemberChangeMessage.Type
import org.session.libsignal.utilities.JsonUtil
import org.session.libsignal.utilities.Log
import java.util.Collections

// class used to save update messages details
class UpdateMessageData () {

    var kind: Kind? = null

    //the annotations below are required for serialization. Any new Kind class MUST be declared as JsonSubTypes as well
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME)
    @JsonSubTypes(
        JsonSubTypes.Type(Kind.GroupCreation::class, name = "GroupCreation"),
        JsonSubTypes.Type(Kind.GroupNameChange::class, name = "GroupNameChange"),
        JsonSubTypes.Type(Kind.GroupDescriptionChange::class, name = "GroupDescriptionChange"),
        JsonSubTypes.Type(Kind.GroupMemberAdded::class, name = "GroupMemberAdded"),
        JsonSubTypes.Type(Kind.GroupMemberRemoved::class, name = "GroupMemberRemoved"),
        JsonSubTypes.Type(Kind.GroupMemberLeft::class, name = "GroupMemberLeft"),
        JsonSubTypes.Type(Kind.OpenGroupInvitation::class, name = "OpenGroupInvitation"),
        JsonSubTypes.Type(Kind.GroupAvatarUpdated::class, name = "GroupAvatarUpdated"),
        JsonSubTypes.Type(Kind.GroupMemberUpdated::class, name = "GroupMemberUpdated"),
        JsonSubTypes.Type(Kind.GroupExpirationUpdated::class, name = "GroupExpirationUpdated")
    )
    sealed class Kind {
        data object GroupCreation: Kind()
        class GroupNameChange(val name: String): Kind() {
            constructor(): this("") //default constructor required for json serialization
        }
        data class GroupDescriptionChange @JvmOverloads constructor(val description: String = ""): Kind()
        class GroupMemberAdded(val updatedMembers: Collection<String>): Kind() {
            constructor(): this(Collections.emptyList())
        }
        class GroupMemberRemoved(val updatedMembers: Collection<String>): Kind() {
            constructor(): this(Collections.emptyList())
        }
        data object GroupMemberLeft: Kind()
        class GroupMemberUpdated(val sessionIds: List<String>, val type: MemberUpdateType?): Kind() {
            constructor(): this(emptyList(), null)
        }
        data object GroupAvatarUpdated: Kind()
        data class GroupExpirationUpdated(val updatedExpiration: Int = 0): Kind()
        class OpenGroupInvitation(val groupUrl: String, val groupName: String): Kind() {
            constructor(): this("", "")
        }
    }

    sealed class MemberUpdateType {
        data object ADDED: MemberUpdateType()
        data object REMOVED: MemberUpdateType()
        data object PROMOTED: MemberUpdateType()
    }

    constructor(kind: Kind): this() {
        this.kind = kind
    }

    companion object {
        val TAG = UpdateMessageData::class.simpleName

        fun buildGroupUpdate(type: SignalServiceGroup.Type, name: String, members: Collection<String>): UpdateMessageData? {
            return when(type) {
                SignalServiceGroup.Type.CREATION -> UpdateMessageData(Kind.GroupCreation)
                SignalServiceGroup.Type.NAME_CHANGE -> UpdateMessageData(Kind.GroupNameChange(name))
                SignalServiceGroup.Type.MEMBER_ADDED -> UpdateMessageData(Kind.GroupMemberAdded(members))
                SignalServiceGroup.Type.MEMBER_REMOVED -> UpdateMessageData(Kind.GroupMemberRemoved(members))
                SignalServiceGroup.Type.QUIT -> UpdateMessageData(Kind.GroupMemberLeft)
                else -> null
            }
        }

        fun buildGroupUpdate(groupUpdated: GroupUpdated): UpdateMessageData? {
            val inner = groupUpdated.inner
            return when {
                inner.hasMemberChangeMessage() -> {
                    val memberChange = inner.memberChangeMessage
                    val type = when (memberChange.type) {
                        Type.ADDED -> MemberUpdateType.ADDED
                        Type.PROMOTED -> MemberUpdateType.PROMOTED
                        Type.REMOVED -> MemberUpdateType.REMOVED
                        null -> null
                    }
                    val members = memberChange.memberSessionIdsList
                    UpdateMessageData(Kind.GroupMemberUpdated(members, type))
                }
                inner.hasInfoChangeMessage() -> {
                    val infoChange = inner.infoChangeMessage
                    val type = infoChange.type
                    when (type) {
                        GroupUpdateInfoChangeMessage.Type.NAME -> Kind.GroupNameChange(infoChange.updatedName)
                        GroupUpdateInfoChangeMessage.Type.AVATAR -> Kind.GroupAvatarUpdated
                        GroupUpdateInfoChangeMessage.Type.DISAPPEARING_MESSAGES -> Kind.GroupExpirationUpdated(infoChange.updatedExpiration)
                        else -> null
                    }?.let { UpdateMessageData(it) }
                }
                inner.hasMemberLeftMessage() -> UpdateMessageData(Kind.GroupMemberLeft)
                else -> null
            }
        }

        fun buildOpenGroupInvitation(url: String, name: String): UpdateMessageData {
            return UpdateMessageData(Kind.OpenGroupInvitation(url, name))
        }

        fun fromJSON(json: String): UpdateMessageData? {
             return try {
                JsonUtil.fromJson(json, UpdateMessageData::class.java)
            } catch (e: JsonParseException) {
                Log.e(TAG, "${e.message}")
                null
            }
        }
    }

    fun toJSON(): String {
        return JsonUtil.toJson(this)
    }
}
